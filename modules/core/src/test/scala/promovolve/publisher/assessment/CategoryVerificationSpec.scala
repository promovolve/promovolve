package promovolve.publisher.assessment

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.CategoryId
import promovolve.publisher.assessment.CategoryVerification.VerificationStatus

class CategoryVerificationSpec extends AnyWordSpec with Matchers {

  // IAB 3.0 ids used in these tests:
  //   596 = Technology & Computing (tier-1)
  //   598 = Augmented Reality (under 596)
  //   483 = Sports (tier-1)
  //   210 = Food & Drink (tier-1)
  //   211 = Alcoholic Beverages (under 210)

  "CategoryVerification.verify" should {

    "return NotAssessed when creative is not yet assessed" in {
      val result = CategoryVerification.verify(
        detectedCategories = List("596"),
        categoryConfidence = 0.9,
        expectedCategories = Set(CategoryId("596")),
        isAssessed = false
      )

      result.status shouldBe VerificationStatus.NotAssessed
      result.matchScore shouldBe 0.5
      result.llmConfidence shouldBe 0.0
    }

    "return NoDeclaredCategory when expectedCategories is empty" in {
      val result = CategoryVerification.verify(
        detectedCategories = List("596"),
        categoryConfidence = 0.85,
        expectedCategories = Set.empty,
        isAssessed = true
      )

      result.status shouldBe VerificationStatus.NoDeclaredCategory
      result.matchScore shouldBe 0.5
      result.llmConfidence shouldBe 0.85
    }

    "return Unverifiable when LLM confidence is below threshold" in {
      val result = CategoryVerification.verify(
        detectedCategories = List("596"),
        categoryConfidence = 0.5,
        expectedCategories = Set(CategoryId("596")),
        isAssessed = true
      )

      result.status shouldBe VerificationStatus.Unverifiable
      result.matchScore shouldBe 0.5
      result.llmConfidence shouldBe 0.5
    }

    "return Unverifiable when detected categories are empty" in {
      val result = CategoryVerification.verify(
        detectedCategories = Nil,
        categoryConfidence = 0.9,
        expectedCategories = Set(CategoryId("596")),
        isAssessed = true
      )

      result.status shouldBe VerificationStatus.Unverifiable
    }

    "return Verified with score 1.0 for direct category match" in {
      val result = CategoryVerification.verify(
        detectedCategories = List("596"),
        categoryConfidence = 0.9,
        expectedCategories = Set(CategoryId("596")),
        isAssessed = true
      )

      result.status shouldBe VerificationStatus.Verified
      result.matchScore shouldBe 1.0
      result.matchedCategories should contain("596")
    }

    "return Verified with ancestor score for hierarchical match" in {
      // detected: 598 (Augmented Reality), expected: 596 (Technology & Computing, parent)
      val result = CategoryVerification.verify(
        detectedCategories = List("598"),
        categoryConfidence = 0.9,
        expectedCategories = Set(CategoryId("596")),
        isAssessed = true
      )

      result.status shouldBe VerificationStatus.Verified
      result.matchScore shouldBe 0.75
      result.matchedCategories should contain("598")
    }

    "return Mismatch when detected and expected have no overlap or ancestry" in {
      // detected: 483 (Sports), expected: 596 (Tech) — completely disjoint subtrees
      val result = CategoryVerification.verify(
        detectedCategories = List("483"),
        categoryConfidence = 0.9,
        expectedCategories = Set(CategoryId("596")),
        isAssessed = true
      )

      result.status shouldBe VerificationStatus.Mismatch
      result.matchScore shouldBe 0.0
      result.matchedCategories shouldBe Nil
    }

    "normalize legacy IAB 1.0 ids on input via TieredCategory" in {
      // "IAB19" (Tech in IAB 1.0) normalizes through 2.x → 3.0 to "596"
      val result = CategoryVerification.verify(
        detectedCategories = List("IAB19"),
        categoryConfidence = 0.9,
        expectedCategories = Set(CategoryId("596")),
        isAssessed = true
      )

      result.status shouldBe VerificationStatus.Verified
      result.matchScore shouldBe 1.0
    }
  }

  "CategoryVerification thresholds" should {
    "use MinConfidenceThreshold of 0.7" in {
      CategoryVerification.MinConfidenceThreshold shouldBe 0.7
    }
    "use HighConfidenceThreshold of 0.8" in {
      CategoryVerification.HighConfidenceThreshold shouldBe 0.8
    }
  }
}
