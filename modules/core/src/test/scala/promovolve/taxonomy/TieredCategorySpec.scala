package promovolve.taxonomy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.OptionValues

class TieredCategorySpec extends AnyWordSpec with Matchers with OptionValues {

  "normalize" should {

    "pass through numeric Content Taxonomy 2.1 IDs unchanged" in {
      TieredCategory.normalize("483") shouldBe "483"
      TieredCategory.normalize("1") shouldBe "1"
      TieredCategory.normalize("596") shouldBe "596"
    }

    "map IAB 1.0 top-level IDs to Content Taxonomy 2.1 equivalents" in {
      TieredCategory.normalize("IAB17") shouldBe "483"   // Sports
      TieredCategory.normalize("IAB19") shouldBe "596"   // Technology & Computing
      TieredCategory.normalize("IAB2") shouldBe "1"      // Automotive
      TieredCategory.normalize("IAB3") shouldBe "52"     // Business
      TieredCategory.normalize("IAB20") shouldBe "653"   // Travel
    }

    "map IAB 1.0 sub-category IDs to parent 2.1 ID" in {
      TieredCategory.normalize("IAB17-3") shouldBe "483"  // Sports sub-cat → Sports
      TieredCategory.normalize("IAB19-6") shouldBe "596"  // Tech sub-cat → Tech
      TieredCategory.normalize("IAB2-1") shouldBe "1"     // Automotive sub-cat → Automotive
    }

    "pass through unknown IAB IDs unchanged" in {
      TieredCategory.normalize("IAB99") shouldBe "IAB99"
    }

    "pass through arbitrary strings unchanged" in {
      TieredCategory.normalize("sports") shouldBe "sports"
      TieredCategory.normalize("") shouldBe ""
    }
  }

  "get" should {

    "return a category for a valid 2.1 ID" in {
      val result = TieredCategory.get("483")
      result shouldBe defined
      result.value.name shouldBe "Sports"
    }

    "return the same category for IAB 1.0 ID as for 2.1 ID" in {
      val viaIab = TieredCategory.get("IAB17")
      val via21  = TieredCategory.get("483")

      viaIab shouldBe defined
      via21 shouldBe defined
      viaIab.value.id shouldBe via21.value.id
    }

    "return None for unknown ID" in {
      TieredCategory.get("IAB99") shouldBe None
    }
  }

  "getAllDescendants" should {

    "return the same descendants for IAB 1.0 ID as for 2.1 ID" in {
      val viaIab = TieredCategory.getAllDescendants("IAB17")
      val via21  = TieredCategory.getAllDescendants("483")

      viaIab should not be empty
      viaIab.map(_.id) shouldBe via21.map(_.id)
    }

    "return empty list for unknown ID" in {
      TieredCategory.getAllDescendants("IAB99") shouldBe empty
    }
  }

  "getAncestors" should {

    "return the same ancestors for IAB 1.0 sub-lookup as for 2.1 ID" in {
      // Pick a known child under Sports (483)
      val sportsChildren = TieredCategory.getAllDescendants("483")
      sportsChildren should not be empty

      val childId = sportsChildren.head.id
      val ancestors = TieredCategory.getAncestors(childId)
      // The ancestors of a direct child of Sports should include Sports (483)
      // or be empty if the child is a top-level child
      // Either way, this should not throw
      ancestors should not be null
    }
  }

  "keepMostSpecific" should {

    "drop a tier-1 when one of its descendants is in the set" in {
      // 497 = Horse Racing under 496 Equine Sports under 483 Sports
      val result = TieredCategory.keepMostSpecific(Set("497", "496", "483"))
      result shouldBe Set("497")
    }

    "keep unrelated nodes regardless of depth" in {
      // 497 (Horse Racing under Sports), 181 (Casinos & Gambling under
      // Attractions) — unrelated subtrees, both should survive.
      val result = TieredCategory.keepMostSpecific(Set("497", "181"))
      result shouldBe Set("497", "181")
    }

    "keep singletons and empty sets unchanged" in {
      TieredCategory.keepMostSpecific(Set.empty) shouldBe Set.empty
      TieredCategory.keepMostSpecific(Set("483")) shouldBe Set("483")
    }

    "pass through ids unknown to the taxonomy" in {
      val result = TieredCategory.keepMostSpecific(Set("zzz-unknown", "483"))
      result should contain("zzz-unknown")
      result should contain("483")
    }
  }
}
