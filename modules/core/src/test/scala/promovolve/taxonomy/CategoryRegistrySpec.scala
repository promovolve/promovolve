package promovolve.taxonomy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.CategoryId

/**
 * The pure coverage computation behind GetCategoryCoverage — the
 * declared-inventory signal for advertiser-facing chip marking.
 * Taxonomy fixture: 497 (Horse Racing) < 496 (Equine Sports) < 483
 * (Sports); 181 (Casinos & Gambling) is an unrelated tree.
 */
class CategoryRegistrySpec extends AnyWordSpec with Matchers {

  private def rows(
      pubs: Map[String, Set[String]],
      cats: String*
  ): Map[String, Int] =
    CategoryRegistry
      .coverageRows(pubs, cats.map(CategoryId(_)).toSet)
      .map(r => r.categoryId.value -> r.publisherCount)
      .toMap

  "coverageRows" should {

    "count a publisher declaring the exact category" in {
      rows(Map("pub1" -> Set("483")), "483") shouldBe Map("483" -> 1)
    }

    "credit a descendant declaration to an ancestor request" in {
      // Publisher declares Horse Racing; a campaign targeting Sports
      // reaches those pages via the auctioneer's ancestor fan-out.
      rows(Map("pub1" -> Set("497")), "483") shouldBe Map("483" -> 1)
    }

    "NOT credit an ancestor declaration to a descendant request" in {
      // Publisher declares Sports broadly; a campaign targeting Horse
      // Racing only matches pages classified at that depth or deeper.
      rows(Map("pub1" -> Set("483")), "497") shouldBe Map("497" -> 0)
    }

    "count each publisher once even when it declares several subtree members" in {
      rows(Map("pub1" -> Set("496", "497")), "483") shouldBe Map("483" -> 1)
    }

    "count distinct publishers declaring different subtree members" in {
      val pubs = Map("pub1" -> Set("496"), "pub2" -> Set("497"))
      rows(pubs, "483") shouldBe Map("483" -> 2)
    }

    "keep unrelated trees separate" in {
      val pubs = Map("pub1" -> Set("497"), "pub2" -> Set("181"))
      rows(pubs, "483", "181") shouldBe Map("483" -> 1, "181" -> 1)
    }

    "match unknown ids only exactly" in {
      val pubs = Map("pub1" -> Set("custom-x"))
      rows(pubs, "custom-x", "custom-y") shouldBe Map("custom-x" -> 1, "custom-y" -> 0)
    }

    "return an empty vector for no requested categories" in {
      CategoryRegistry.coverageRows(Map("pub1" -> Set("483")), Set.empty) shouldBe empty
    }
  }
}
