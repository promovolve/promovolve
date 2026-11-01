package promovolve.auction

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.CampaignId

/**
 * The site-wide floor learns from one bid per approved CAMPAIGN. A
 * campaign's second creative is not a second bidder: counting it made a
 * lone campaign look competitive and made its own bid "the second bid",
 * so the second-bid ceiling protected nobody.
 */
class SiteFloorStatsSpec extends AnyWordSpec with Matchers {

  private val campA = CampaignId("A")
  private val campB = CampaignId("B")

  "siteFloorStats" should {
    "count a campaign's creatives as one bidder" in {
      val s = AuctioneerEntity.siteFloorStats(Seq(campA -> 5.0, campA -> 5.0, campB -> 2.0), 0, 0.0, 0.0)
      s.bidders shouldBe 2
      s.secondBid shouldBe 2.0
      s.maxBid shouldBe 5.0
      s.minBid shouldBe 2.0
    }

    "read a lone campaign with two creatives as a monopoly" in {
      val s = AuctioneerEntity.siteFloorStats(Seq(campA -> 5.0, campA -> 5.0), 0, 0.0, 0.0)
      s.bidders shouldBe 1
      s.secondBid shouldBe 0.0
    }

    "add approved below-floor rejects as bidders and bids" in {
      val s = AuctioneerEntity.siteFloorStats(Seq(campA -> 5.0), 2, 3.0, 1.0)
      s.bidders shouldBe 3
      s.secondBid shouldBe 3.0
      s.minBid shouldBe 1.0
    }
  }
}
