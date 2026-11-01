package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.publisher.SiteEntity.BidWindow

/**
 * A floor window merges every auction report for a category (or the site)
 * into one view, so the floor rule no longer depends on which page
 * reported last. Pages differ (place targeting): a Tokyo page may see two
 * bidders where another page sees one.
 */
class BidWindowSpec extends AnyWordSpec with Matchers {

  // of(bidders, floorRejected, topBid, secondBid) — one auction's report.
  private val otherPage = BidWindow.of(1, 0, 5.0, 0.0) // A $5 alone
  private val tokyoPage = BidWindow.of(2, 0, 5.0, 4.0) // A $5 + B $4 (Tokyo-only)

  "BidWindow" should {
    "read the same whichever page reported last" in {
      otherPage.merge(tokyoPage) shouldBe tokyoPage.merge(otherPage)
    }

    "protect every page's top two: competitive, ceiling at the lowest protected bid" in {
      val w = otherPage.merge(tokyoPage)
      w.bidders shouldBe 2
      w.ceilingBid shouldBe 4.0
      SiteEntity.capAtSecondBid(4.95, w.bidders, w.ceilingBid, 0.5) shouldBe 3.96 +- 1e-9
    }

    "price a monopoly under the lowest sole bidder across pages" in {
      val w = BidWindow.of(1, 0, 5.0, 0.0).merge(BidWindow.of(1, 0, 4.0, 0.0))
      w.bidders shouldBe 1
      FloorSweepOptimizer.bidDerivedFloor(w.bidders, w.ceilingBid, 0.5) shouldBe Some(3.96)
    }

    "count as servable when any page had a bid that cleared" in {
      BidWindow.of(1, 1, 3.0, 0.0).anyServable shouldBe false
      BidWindow.of(0, 0, 0.0, 0.0).anyServable shouldBe false
      BidWindow.of(1, 1, 3.0, 0.0).merge(tokyoPage).anyServable shouldBe true
    }

    "keep the highest top bid for display" in {
      BidWindow.of(1, 0, 3.0, 0.0).merge(tokyoPage).topBid shouldBe 5.0
    }
  }
}
