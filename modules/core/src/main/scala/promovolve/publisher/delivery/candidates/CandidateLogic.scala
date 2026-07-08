package promovolve.publisher.delivery.candidates

import org.slf4j.LoggerFactory
import promovolve.publisher.*
import promovolve.{CPM, Candidate, CategoryId}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

// Type alias for creative lookup (async)
type CreativeLookup = String => Future[Option[Creative]]

/** Pure helper functions for candidate processing pipeline.
  *
  * Contains stateless logic extracted from AdServer for testability
  * and reuse.
  */
object CandidateLogic {

  private val log = LoggerFactory.getLogger(getClass)

  /** Filter candidates by domain blocklist.
    *
    * Separates candidates into allowed and blocked based on the
    * publisher's domain blocklist configuration.
    */
  def filterByDomainBlocklist(
      candidates: Vector[Candidate],
      blocklist: Option[PublisherEntity.CachedDomainBlocklist]
  ): (Vector[Candidate], Vector[Candidate]) =
    blocklist match {
      case Some(bl) =>
        candidates.partition(c => !bl.contains(c.landingDomain))
      case None =>
        (candidates, Vector.empty)
    }

  /** Partition candidates by pre-approval status. */
  def partitionByApprovalStatus(
      candidates: Vector[Candidate]
  ): (Vector[Candidate], Vector[Candidate]) =
    candidates.partition(_.preApproved)

  /** Get unique categories from candidates. */
  def uniqueCategories(candidates: Vector[Candidate]): Vector[CategoryId] =
    candidates.map(_.category).distinct

  /** Build CandidateViews from candidates with creative lookup (async).
    *
    * Filters out candidates where creative is not found or cannot participate.
    */
  def buildCandidateViews(
      candidates: Vector[Candidate],
      creativeLookup: CreativeLookup,
      categoryScores: Map[CategoryId, Double],
      classifiedAt: Instant,
      defaultScore: Double = 0.5
  )(using ec: ExecutionContext): Future[Vector[CandidateView]] =
    Future.traverse(candidates) { candidate =>
      creativeLookup(candidate.creativeId.value).map {
        case Some(creative) if creative.canParticipate =>
          Some(buildCandidateView(candidate, creative, categoryScores, classifiedAt, defaultScore))
        case _ => None
      }
    }.map(_.flatten)

  /** Multiplier applied to the category score when Gemini's
    * suggestedContentCategories on the creative include the
    * publisher-page category being auctioned. 1.15 is deliberately
    * modest — Gemini is helpful but not always right, and the boost
    * primarily biases cold-start selection (where categoryScore is
    * the Beta prior in Thompson Sampling). Once a creative has
    * impression stats, the sampled Beta posterior dominates and the
    * boost fades naturally.
    */
  private val GeminiCategoryBoost = 1.15

  /** Build CandidateView from candidate with creative and scores. */
  def buildCandidateView(
      candidate: Candidate,
      creative: Creative,
      categoryScores: Map[CategoryId, Double],
      classifiedAt: Instant,
      defaultScore: Double = 0.5
  ): CandidateView = {
    val rawScore = categoryScores.getOrElse(candidate.category, defaultScore)
    // Gemini-suggested categories live on the creative row from
    // category verification. When the publisher-page category
    // (candidate.category) is one the classifier flagged as a
    // strong fit for this creative, nudge categoryScore upward so
    // the creative competes harder during cold start. Clamped at
    // 1.0 because downstream code treats categoryScore as a
    // probability-shaped prior for Thompson Sampling.
    val geminiMatch = creative.suggestedContentCategories.contains(candidate.category.value)
    val score = if (geminiMatch) math.min(1.0, rawScore * GeminiCategoryBoost) else rawScore
    if (geminiMatch && log.isDebugEnabled) {
      log.debug(
        "Gemini category boost: creative={} page-cat={} raw={} → boosted={} (suggested={})",
        candidate.creativeId.value,
        candidate.category.value,
        f"$rawScore%.3f",
        f"$score%.3f",
        creative.suggestedContentCategories.mkString(","),
      )
    }
    val serveCpm = if (candidate.maxCpm > CPM.zero) candidate.maxCpm else candidate.cpm
    CandidateView(
      creativeId        = candidate.creativeId,
      campaignId        = candidate.campaignId,
      advertiserId      = candidate.advertiserId,
      assetUrl          = CDNPath(creative.s3Key),
      mime              = MimeType(creative.mime),
      width             = creative.width,
      height            = creative.height,
      category          = candidate.category,
      cpm               = serveCpm,
      classifiedAtMs    = classifiedAt.toEpochMilli,
      categoryScore     = score,
      adProductCategory = candidate.adProductCategory,
      landingDomain     = candidate.landingDomain,
      landingUrl        = creative.landingUrl,
    )
  }

  /** Create a ServeView from candidate views. */
  def buildServeView(
      candidateViews: Vector[CandidateView],
      ttlMs: Long,
      nowMs: Long = System.currentTimeMillis()
  ): ServeView =
    ServeView(
      candidates  = candidateViews,
      version     = nowMs,
      expiresAtMs = nowMs + ttlMs
    )

  /** Get blocked domain names from filtered candidates. */
  def blockedDomains(blocked: Vector[Candidate]): Seq[String] =
    blocked.map(_.landingDomain).distinct

  /** Check if any candidates have creatives that can participate (async). */
  def hasAnyCreatives(
      candidates: Vector[Candidate],
      creativeLookup: CreativeLookup
  )(using ec: ExecutionContext): Future[Boolean] =
    Future.traverse(candidates) { c =>
      creativeLookup(c.creativeId.value).map(_.exists(_.canParticipate))
    }.map(_.exists(identity))
}
