package promovolve.publisher.delivery.approval

import promovolve.publisher.{CDNPath, CandidateView, CreativeMeta, MimeType}
import promovolve.{Candidate, CategoryId, Selection}

import java.time.Instant

/** Pure helper functions for approval workflow.
  *
  * Contains stateless logic extracted from AdServer for testability
  * and reuse. All functions are pure - no side effects.
  */
object ApprovalLogic {

  /** Validate an approval request against the current selection.
    *
    * Checks that:
    * 1. A selection exists for the slot
    * 2. The creative ID matches the current candidate
    *
    * @param selectionOpt The current selection (if any)
    * @param creativeId   The creative ID being approved
    * @return ValidationResult indicating success or failure reason
    */
  def validateApproval(
      selectionOpt: Option[Selection],
      creativeId: String
  ): ValidationResult =
    selectionOpt match {
      case None =>
        NoSelection("No pending selection for this slot")
      case Some(selection) =>
        val candidate = selection.current
        if (candidate.creativeId.value != creativeId) {
          CreativeMismatch(candidate.creativeId.value, creativeId)
        } else {
          Valid(candidate, selection)
        }
    }

  /** Build a CandidateView from approval components.
    *
    * Creates a complete CandidateView ready for serving from the
    * candidate, metadata, and computed scores.
    *
    * @param candidate     The approved candidate
    * @param meta          Creative metadata (dimensions, mime type)
    * @param assetUrl      CDN URL for the creative asset
    * @param categoryScore Thompson-sampled CTR score for the category
    * @param classifiedAt  When the content was classified
    * @return CandidateView ready for ServeIndex
    */
  def buildCandidateView(
      candidate: Candidate,
      meta: CreativeMeta,
      assetUrl: String,
      categoryScore: Double,
      classifiedAt: Instant
  ): CandidateView =
    CandidateView(
      creativeId        = candidate.creativeId,
      campaignId        = candidate.campaignId,
      advertiserId      = candidate.advertiserId,
      assetUrl          = CDNPath(assetUrl),
      mime              = MimeType(meta.mime),
      width             = meta.width,
      height            = meta.height,
      category          = candidate.category,
      cpm               = candidate.cpm,
      classifiedAtMs    = classifiedAt.toEpochMilli,
      categoryScore     = categoryScore,
      adProductCategory = candidate.adProductCategory,
      landingDomain     = candidate.landingDomain,
    )

  /** Get category score with fallback to default.
    *
    * @param scoreMap      Map of category scores from TaxonomyRanker
    * @param category      The category to look up
    * @param defaultScore  Default score if category not found
    * @return The category score or default
    */
  def getCategoryScore(
      scoreMap: Map[CategoryId, Double],
      category: CategoryId,
      defaultScore: Double = 0.5
  ): Double =
    scoreMap.getOrElse(category, defaultScore)

  /** Count approval results from a batch operation.
    *
    * @param results Sequence of (success, _) tuples from batch approval
    * @return (approvedCount, failedCount)
    */
  def countApprovalResults[T](results: Seq[(Boolean, T)]): (Int, Int) = {
    val approved = results.count(_._1)
    val failed = results.count(!_._1)
    (approved, failed)
  }

  /** Validation result for approval requests */
  sealed trait ValidationResult

  case class Valid(candidate: Candidate, selection: Selection) extends ValidationResult

  case class NoSelection(message: String) extends ValidationResult

  case class CreativeMismatch(expected: String, actual: String) extends ValidationResult
}
