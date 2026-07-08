package promovolve.publisher.assessment

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import promovolve.AdProductCategoryId
import promovolve.publisher.{AssessmentResult, CreativeMeta, CreativeMetadataRepo}

import scala.collection.mutable

/** Actor that orchestrates batch LLM-based creative assessment.
  *
  * Uses BatchAnthropicClient for 50% cost savings. Assessments are queued
  * and processed in batches. Creatives remain ineligible for auction until
  * assessment completes.
  *
  * Features:
  * - Batch processing via Anthropic Message Batches API
  * - Hash-based deduplication (same image = copy existing assessment)
  * - Persists results with category verification to CreativeMetadataRepo
  * - Immediate acknowledgment, async completion
  */
object BatchCreativeAssessor {

  sealed trait Command

  /** Request assessment for a creative.
    *
    * Returns immediately with Queued. Assessment completes asynchronously
    * when the batch is processed. Creative is not eligible for auction
    * until assessment completes (assessedAt is set).
    *
    * @param meta              Creative metadata (must already be persisted)
    * @param imageBytes        Raw image bytes to assess
    * @param adProductCategory Advertiser-declared ad product category for verification
    * @param replyTo           Actor to receive acknowledgment
    */
  case class Assess(
      meta: CreativeMeta,
      imageBytes: Array[Byte],
      adProductCategory: Option[AdProductCategoryId] = None,
      expectedCategories: Set[promovolve.CategoryId] = Set.empty,
      replyTo: ActorRef[Response]
  ) extends Command

  /** Force flush the batch immediately (useful for testing or shutdown) */
  case object FlushBatch extends Command

  /** Internal: Receive result from BatchAnthropicClient */
  private case class BatchResult(
      creativeId: String,
      result: BatchAnthropicClient.Response
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

  /** Assessment request accepted and queued for batch processing */
  case class Queued(creativeId: String) extends Response

  /** Assessment was skipped (already assessed or used cached result) */
  case class Skipped(creativeId: String, reason: String) extends Response

  /** Tracks pending assessment context needed for verification */
  private case class PendingAssessment(
      creativeId: String,
      expectedCategories: Set[promovolve.CategoryId]
  )

  /** Create BatchCreativeAssessor with an existing BatchAnthropicClient actor ref.
    * Use this when you want to share a batch client across multiple assessors.
    */
  def apply(
      batchClient: ActorRef[BatchAnthropicClient.Command],
      metaRepo: CreativeMetadataRepo
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val pending = mutable.Map.empty[String, PendingAssessment]

    val batchResultAdapter: ActorRef[BatchAnthropicClient.Response] =
      ctx.messageAdapter[BatchAnthropicClient.Response] {
        case r: BatchAnthropicClient.AssessmentComplete =>
          BatchResult(r.creativeId, r)
        case r: BatchAnthropicClient.AssessmentFailed =>
          BatchResult(r.creativeId, r)
      }

    behavior(batchClient, metaRepo, pending, batchResultAdapter)
  }

  /** Create BatchCreativeAssessor that spawns its own BatchAnthropicClient.
    * This is the simplest way to use batch assessment.
    *
    * @param apiKey    Anthropic API key
    * @param metaRepo  Creative metadata repository
    * @param config    Optional batch client configuration
    */
  def withApiKey(
      apiKey: String,
      metaRepo: CreativeMetadataRepo,
      config: BatchAnthropicClient.Config = BatchAnthropicClient.Config()
  ): Behavior[Command] = Behaviors.setup { ctx =>
    // Spawn batch client as child actor
    val batchClient = ctx.spawn(BatchAnthropicClient(apiKey, config), "batch-anthropic-client")
    val pending = mutable.Map.empty[String, PendingAssessment]

    val batchResultAdapter: ActorRef[BatchAnthropicClient.Response] =
      ctx.messageAdapter[BatchAnthropicClient.Response] {
        case r: BatchAnthropicClient.AssessmentComplete =>
          BatchResult(r.creativeId, r)
        case r: BatchAnthropicClient.AssessmentFailed =>
          BatchResult(r.creativeId, r)
      }

    behavior(batchClient, metaRepo, pending, batchResultAdapter)
  }

  private def behavior(
      batchClient: ActorRef[BatchAnthropicClient.Command],
      metaRepo: CreativeMetadataRepo,
      pending: mutable.Map[String, PendingAssessment],
      batchResultAdapter: ActorRef[BatchAnthropicClient.Response]
  ): Behavior[Command] = Behaviors.receive { (ctx, msg) =>
    msg match {
      case Assess(meta, imageBytes, adProductCategory, expectedCategories, replyTo) =>
        // Check if already assessed
        if (meta.isAssessed) {
          replyTo ! Skipped(meta.creativeId, "Already assessed")
          Behaviors.same
        } else {
          // Check for duplicate by hash (another creative with same image) - async
          import scala.util.{Success, Failure}
          ctx.pipeToSelf(metaRepo.getByHash(meta.hash)) {
            case Success(existingOpt) => DuplicateNotFound(meta, imageBytes, adProductCategory, expectedCategories, replyTo, existingOpt)
            case Failure(_)           => DuplicateNotFound(meta, imageBytes, adProductCategory, expectedCategories, replyTo, None)
          }
          Behaviors.same
        }

      case DuplicateNotFound(meta, imageBytes, adProductCategory, expectedCategories, replyTo, Some(existing)) if existing.isAssessed =>
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
        ctx.log.info(
          "Creative {} assessment copied from {} (same hash), verification={}",
          meta.creativeId, existing.creativeId, verification.status
        )
        replyTo ! Skipped(meta.creativeId, s"Copied from ${existing.creativeId}")
        Behaviors.same

      case DuplicateNotFound(meta, imageBytes, adProductCategory, expectedCategories, replyTo, _) =>
        // Queue for batch assessment
        val context = AssessmentContext(
          advertiserId = meta.advertiserId,
          creativeId = meta.creativeId,
          width = meta.width,
          height = meta.height,
          declaredAdProductCategory = adProductCategory.map(_.value)
        )
        pending += (meta.creativeId -> PendingAssessment(meta.creativeId, expectedCategories))
        batchClient ! BatchAnthropicClient.Assess(
          creativeId = meta.creativeId,
          imageBytes = imageBytes,
          mimeType = meta.mime,
          context = context,
          replyTo = batchResultAdapter
        )
        ctx.log.debug("Creative {} queued for batch assessment", meta.creativeId)
        replyTo ! Queued(meta.creativeId)
        Behaviors.same

      case FlushBatch =>
        batchClient ! BatchAnthropicClient.FlushBatch
        Behaviors.same

      case BatchResult(creativeId, batchResponse) =>
        pending.remove(creativeId) match {
          case Some(pendingAssessment) =>
            batchResponse match {
              case BatchAnthropicClient.AssessmentComplete(_, result) =>
                // Compute verification
                val verification = CategoryVerification.verify(
                  result.detectedCategories,
                  result.categoryConfidence,
                  pendingAssessment.expectedCategories,
                  isAssessed = true
                )
                // Persist assessment with verification
                metaRepo.updateAssessmentWithVerification(creativeId, result, verification)
                ctx.log.info(
                  "Creative {} assessed: safety={}, quality={}, categories={}, verification={}",
                  creativeId, result.safetyScore, result.qualityScore,
                  result.detectedCategories.mkString(","), verification.status
                )

              case BatchAnthropicClient.AssessmentFailed(_, error) =>
                ctx.log.warn("Batch assessment failed for creative {}: {}", creativeId, error)
                // Creative remains unassessed - will not be eligible for auction
            }

          case None =>
            ctx.log.warn(
              "Received batch result for unknown creative {}, ignoring",
              creativeId
            )
        }
        Behaviors.same
    }
  }
}
