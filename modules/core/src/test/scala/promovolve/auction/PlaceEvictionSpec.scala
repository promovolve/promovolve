package promovolve.auction

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.auction.AuctioneerEntity.AdSlotSpec

import java.time.Instant

/**
 * Place-narrow eviction is PAGE-scoped, and that is the whole distinction
 * from the audience narrow next to it: dropping a reader population means
 * the campaign no longer targets the site at all, while dropping a place
 * means it no longer targets some ARTICLES. Evicting the site for a place
 * narrow would cut a campaign off pages it still legitimately serves.
 */
class PlaceEvictionSpec extends AnyWordSpec with Matchers {

  private val Kamakura = "GN1860672" // -> JP-14 -> JP
  private val site = "site-a"
  private val tokyoPage = URL("https://s/tokyo")
  private val kyotoPage = URL("https://s/kyoto")
  private val plainPage = URL("https://s/recipes")

  private def slot(id: String) =
    AdSlotSpec(SlotId(id), List(AdSize(300, 250)), AdSize(300, 250), None, None)

  private val lastPage: Map[URL, (Map[String, Double], List[AdSlotSpec], Instant)] = Map(
    tokyoPage -> (Map("483" -> 0.9), List(slot("s1")), Instant.now()),
    kyotoPage -> (Map("483" -> 0.9), List(slot("s2")), Instant.now()),
    plainPage -> (Map("483" -> 0.9), List(slot("s3")), Instant.now())
  )

  private val lastPagePlaces: Map[URL, Set[String]] = Map(
    tokyoPage -> Set("JP-13"),
    kyotoPage -> Set("JP-26"),
    plainPage -> Set.empty
  )

  private def evict(targeting: Set[String], awarded: Set[URL] = lastPage.keySet): Set[String] =
    AuctioneerEntity.placeEvictionSlotKeys(site, awarded, lastPage, lastPagePlaces, targeting)

  "placeEvictionSlotKeys" should {

    "evict nothing when the campaign targets no place" in {
      evict(Set.empty) shouldBe empty
    }

    // The case it exists for: narrowed to Kyoto, so the Tokyo page goes and
    // the Kyoto page stays.
    "evict only the pages that stopped qualifying" in {
      evict(Set("JP-26")) shouldBe Set(s"$site|s1")
    }

    "evict nothing when every awarded page still qualifies" in {
      evict(Set("JP")) shouldBe empty
    }

    // A page the classifier said nothing about is not a contradiction of
    // any target, so a place narrow must leave it alone — otherwise every
    // narrowing edit would cut a campaign off all its unplaced inventory.
    "never evict a page with no places" in {
      evict(Set("FR")) shouldBe Set(s"$site|s1", s"$site|s2")
    }

    "only consider pages the campaign actually won" in {
      evict(Set("JP-26"), awarded = Set(kyotoPage)) shouldBe empty
    }

    "handle an awarded url the page cache no longer holds" in {
      evict(Set("JP-26"), awarded = Set(URL("https://s/gone"))) shouldBe empty
    }
  }
}
