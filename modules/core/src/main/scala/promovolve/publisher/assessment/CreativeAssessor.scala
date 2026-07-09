package promovolve.publisher.assessment

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import promovolve.AdProductCategoryId
import promovolve.publisher.{ AssessmentResult, CreativeMeta, CreativeMetadataRepo }

import scala.util.{ Failure, Success }

/**
 * Actor that orchestrates LLM-based creative assessment.
 *
 * Features:
 * - Async assessment via multimodal LLM
 * - Hash-based deduplication (same image = skip re-assessment)
 * - Persists results to CreativeMetadataRepo
 */
object CreativeAssessor {

  sealed trait Command

  /**
   * Request assessment for a creative.
   *
   * @param meta               Creative metadata (must already be persisted)
   * @param imageBytes         Raw image bytes to assess
   * @param adProductCategory  Advertiser-declared ad product (passed to the LLM
   *                           prompt so it knows what the ad claims to sell).
   * @param expectedCategories Advertiser-declared target content topics. The
   *                           creative is verified against these (the LLM's
   *                           detected categories must overlap, exactly or via
   *                           a shared 3.0 ancestor).
   * @param replyTo            Actor to receive the result
   */
  case class Assess(
      meta: CreativeMeta,
      imageBytes: Array[Byte],
      adProductCategory: Option[AdProductCategoryId] = None,
      expectedCategories: Set[promovolve.CategoryId] = Set.empty,
      replyTo: ActorRef[Response]
  ) extends Command

  /** Internal: LLM call completed successfully */
  private case class LLMSuccess(
      creativeId: String,
      result: AssessmentResult,
      adProductCategory: Option[AdProductCategoryId],
      expectedCategories: Set[promovolve.CategoryId],
      replyTo: ActorRef[Response]
  ) extends Command

  /** Internal: LLM call failed */
  private case class LLMFailure(
      creativeId: String,
      error: Throwable,
      replyTo: ActorRef[Response]
  ) extends Command

  /** Internal: hash dedup lookup completed */
  private case class DuplicateNotFound(
      meta: CreativeMeta,
      imageBytes: Array[Byte],
      adProductCategory: Option[AdProductCategoryId],
      expectedCategories: Set[promovolve.CategoryId],
      replyTo: ActorRef[Response],
      existing: Option[CreativeMeta]
  ) extends Command

  sealed trait Response
  case class AssessmentComplete(creativeId: String, result: AssessmentResult) extends Response
  case class AssessmentSkipped(creativeId: String, reason: String) extends Response
  case class AssessmentFailed(creativeId: String, error: String) extends Response

  /**
   * Create CreativeAssessor with GeminiClient (default, recommended).
   * Gemini is fast and cheap - no batching needed.
   *
   * @param apiKey   Gemini API key
   * @param metaRepo Creative metadata repository
   * @param model    Gemini model (default: gemini-2.5-flash)
   */
  def withGemini(
      apiKey: String,
      metaRepo: CreativeMetadataRepo,
      model: String = "gemini-2.5-flash"
  ): Behavior[Command] = Behaviors.setup { ctx =>
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = ctx.system
    given ec: scala.concurrent.ExecutionContext = ctx.executionContext
    val client = new GeminiClient(apiKey, model)
    apply(client, metaRepo)
  }

  /**
   * Create CreativeAssessor with OpenAIClient.
   *
   * @param apiKey   OpenAI API key
   * @param metaRepo Creative metadata repository
   * @param model    OpenAI model (default: gpt-4o-mini)
   */
  def withOpenAI(
      apiKey: String,
      metaRepo: CreativeMetadataRepo,
      model: String = "gpt-4o-mini"
  ): Behavior[Command] = Behaviors.setup { ctx =>
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = ctx.system
    given ec: scala.concurrent.ExecutionContext = ctx.executionContext
    val client = new OpenAIClient(apiKey, model)
    apply(client, metaRepo)
  }

  /**
   * Create CreativeAssessor with AnthropicClient.
   *
   * @param apiKey   Anthropic API key
   * @param metaRepo Creative metadata repository
   * @param model    Anthropic model (default: claude-sonnet-4-20250514)
   */
  def withAnthropic(
      apiKey: String,
      metaRepo: CreativeMetadataRepo,
      model: String = "claude-sonnet-4-20250514"
  ): Behavior[Command] = Behaviors.setup { ctx =>
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = ctx.system
    given ec: scala.concurrent.ExecutionContext = ctx.executionContext
    val client = new AnthropicClient(apiKey, model)
    apply(client, metaRepo)
  }

  /** Create CreativeAssessor with any LLMClient. */
  def apply(
      llmClient: LLMClient,
      metaRepo: CreativeMetadataRepo
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.receiveMessage {
      case Assess(meta, imageBytes, adProductCategory, expectedCategories, replyTo) =>
        // Check if already assessed
        if (meta.isAssessed) {
          replyTo ! AssessmentSkipped(meta.creativeId, "Already assessed")
          Behaviors.same
        } else {
          // Check for duplicate by hash (another creative with same image) - async
          ctx.pipeToSelf(metaRepo.getByHash(meta.hash)) {
            case Success(existingOpt) =>
              DuplicateNotFound(meta, imageBytes, adProductCategory, expectedCategories, replyTo, existingOpt)
            case Failure(_) => DuplicateNotFound(meta, imageBytes, adProductCategory, expectedCategories, replyTo, None)
          }
          Behaviors.same
        }

      case DuplicateNotFound(meta, _, _, expectedCategories, replyTo, Some(existing)) if existing.isAssessed =>
        // Copy assessment from existing
        val result = AssessmentResult(
          safetyScore = existing.safetyScore.getOrElse(0.5),
          qualityScore = existing.qualityScore.getOrElse(0.5),
          adultContent = existing.adultContent,
          violence = existing.violence,
          hateSpeech = existing.hateSpeech,
          detectedCategories = existing.categoryList,
          categoryConfidence = existing.categoryConfidence.getOrElse(0.5),
          suggestedCategory = existing.suggestedCategory,
          extractedText = existing.extractedText,
          model = existing.assessmentModel.getOrElse("cached")
        )
        val verification = CategoryVerification.verify(
          result.detectedCategories,
          result.categoryConfidence,
          expectedCategories,
          isAssessed = true
        )
        metaRepo.updateAssessmentWithVerification(meta.creativeId, result, verification)
        replyTo ! AssessmentComplete(meta.creativeId, result)
        Behaviors.same

      case DuplicateNotFound(meta, imageBytes, adProductCategory, expectedCategories, replyTo, _) =>
        // No existing assessment found — call LLM
        val context = AssessmentContext(
          advertiserId = meta.advertiserId,
          creativeId = meta.creativeId,
          width = meta.width,
          height = meta.height,
          declaredAdProductCategory = adProductCategory.map(_.value)
        )
        ctx.pipeToSelf(llmClient.assessCreative(imageBytes, meta.mime, context)) {
          case Success(result) => LLMSuccess(meta.creativeId, result, adProductCategory, expectedCategories, replyTo)
          case Failure(ex)     => LLMFailure(meta.creativeId, ex, replyTo)
        }
        Behaviors.same

      case LLMSuccess(creativeId, result, _, expectedCategories, replyTo) =>
        // Compute verification
        val verification = CategoryVerification.verify(
          result.detectedCategories,
          result.categoryConfidence,
          expectedCategories,
          isAssessed = true
        )
        // Persist assessment with verification
        metaRepo.updateAssessmentWithVerification(creativeId, result, verification)
        ctx.log.info(
          "Creative {} assessed: safety={}, quality={}, categories={}, verification={}",
          creativeId, result.safetyScore, result.qualityScore,
          result.detectedCategories.mkString(","), verification.status
        )
        replyTo ! AssessmentComplete(creativeId, result)
        Behaviors.same

      case LLMFailure(creativeId, error, replyTo) =>
        ctx.log.warn("Assessment failed for creative {}: {}, marking as failed (permissive)", creativeId,
          error.getMessage)
        // Permissive approach: mark as failed but allow participation with neutral scores
        metaRepo.markAssessmentFailed(creativeId, error.getMessage)
        replyTo ! AssessmentFailed(creativeId, error.getMessage)
        Behaviors.same
    }
  }
}
