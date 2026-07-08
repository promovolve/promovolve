package promovolve.publisher.assessment

import promovolve.CategoryId
import promovolve.taxonomy.TieredCategory

/** Publisher-side verification that compares LLM-detected creative categories
  * against the advertiser's declared target topics.
  *
  * Both sides now speak IAB Content Taxonomy 3.0 (after the 2.x→3.0 migration),
  * so matching is hierarchical: an exact id is a strong match; sharing an
  * ancestor in the 3.0 tree is a soft match. There is no longer a separate
  * "ad product → content" bridge — the advertiser declares target topics
  * directly, and that's what the creative is verified against.
  *
  * Key principle: err on the side of NOT blocking. Only flag Mismatch when
  * the LLM is confident AND the detected categories share no ancestry with
  * any declared target.
  */
object CategoryVerification {

  /** Minimum LLM confidence threshold to consider detection reliable */
  val MinConfidenceThreshold: Double = 0.7

  /** High confidence threshold for auto-rejection */
  val HighConfidenceThreshold: Double = 0.8

  enum VerificationStatus {
    /** LLM confident + detected matches (or shares ancestry with) a target */
    case Verified
    /** LLM confident + detected has no overlap with declared targets */
    case Mismatch
    /** LLM low confidence or empty categories — neutral */
    case Unverifiable
    /** Creative not yet assessed by LLM */
    case NotAssessed
    /** Advertiser has not declared any target topics */
    case NoDeclaredCategory
  }

  final case class VerificationResult(
      status: VerificationStatus,
      matchScore: Double,
      llmConfidence: Double,
      matchedCategories: List[String],
      expectedCategories: Set[String],
      detectedCategories: List[String]
  ) {
    def statusString: String = status.toString
  }

  /** Verify a creative against the campaign's declared target topics.
    *
    * @param detectedCategories Categories the LLM detected in the creative
    * @param categoryConfidence LLM's confidence (0.0-1.0)
    * @param expectedCategories Advertiser-declared target content categories (IAB 3.0)
    * @param isAssessed         Whether the LLM has run against this creative
    */
  def verify(
      detectedCategories: List[String],
      categoryConfidence: Double,
      expectedCategories: Set[CategoryId],
      isAssessed: Boolean
  ): VerificationResult = {

    if (!isAssessed) {
      return VerificationResult(
        status = VerificationStatus.NotAssessed,
        matchScore = 0.5,
        llmConfidence = 0.0,
        matchedCategories = Nil,
        expectedCategories = Set.empty,
        detectedCategories = detectedCategories
      )
    }

    if (expectedCategories.isEmpty) {
      return VerificationResult(
        status = VerificationStatus.NoDeclaredCategory,
        matchScore = 0.5,
        llmConfidence = categoryConfidence,
        matchedCategories = Nil,
        expectedCategories = Set.empty,
        detectedCategories = detectedCategories
      )
    }

    if (categoryConfidence < MinConfidenceThreshold || detectedCategories.isEmpty) {
      return VerificationResult(
        status = VerificationStatus.Unverifiable,
        matchScore = 0.5,
        llmConfidence = categoryConfidence,
        matchedCategories = Nil,
        expectedCategories = expectedCategories.map(_.value),
        detectedCategories = detectedCategories
      )
    }

    val expectedNormalized = expectedCategories.map(c => TieredCategory.normalize(c.value))
    val detectedNormalized = detectedCategories.map(TieredCategory.normalize)
    val (matched, score) = calculateMatch(detectedNormalized, expectedNormalized)

    val baseResult = VerificationResult(
      status = if (matched.nonEmpty) VerificationStatus.Verified else VerificationStatus.Mismatch,
      matchScore = if (matched.nonEmpty) score else 0.0,
      llmConfidence = categoryConfidence,
      matchedCategories = matched,
      expectedCategories = expectedNormalized,
      detectedCategories = detectedNormalized
    )
    baseResult
  }

  /** Match detected against expected using the 3.0 hierarchy.
    *   - Direct id match → score 1.0
    *   - Shared ancestor → score 0.75 (fraction of detected with an
    *     ancestor in expected)
    */
  private def calculateMatch(
      detected: List[String],
      expected: Set[String]
  ): (List[String], Double) = {
    val directMatches = detected.filter(expected.contains)
    if (directMatches.nonEmpty) return (directMatches, 1.0)

    val expectedWithAncestors: Set[String] = expected.flatMap { e =>
      TieredCategory.getAncestors(e).map(_.id).toSet + e
    }
    val ancestorMatches = detected.filter { d =>
      val dWithAncestors = TieredCategory.getAncestors(d).map(_.id).toSet + d
      dWithAncestors.intersect(expectedWithAncestors).nonEmpty
    }
    if (ancestorMatches.nonEmpty) (ancestorMatches, 0.75)
    else (Nil, 0.0)
  }
}
