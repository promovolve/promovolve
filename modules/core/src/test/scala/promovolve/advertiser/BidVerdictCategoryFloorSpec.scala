package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/**
 * The bid checker must judge a page against the floor the auction uses:
 * the page category's floor when the site has one, else the site floor
 * (AuctioneerEntity: currentCategoryFloors.getOrElse(cat, currentFloorCpm)).
 */
class BidVerdictCategoryFloorSpec extends AnyWordSpec with Matchers {

  private val site = SiteId("site-a")
  private val state: CampaignEntity.State =
    CampaignEntity.State
      .empty(CampaignId("camp-1"), AdvertiserId("adv-1"))
      .copy(status = CampaignEntity.Status.Active, categories = Set(CategoryId("483")), maxCpm = CPM(2.0))

  "The bid checker" should {

    "use the page category's floor, not the site floor" in {
      val v = state.bidVerdict(site, Set("483"), CPM(1.0), Map("483" -> 3.0), Set.empty, false, Set.empty)
      v.wouldBid shouldBe false
      v.reason shouldBe Some("BelowFloor")
      v.floorCpm shouldBe Some(3.0)
    }

    "bid when the category floor is below the site floor" in {
      val v = state.bidVerdict(site, Set("483"), CPM(5.0), Map("483" -> 1.5), Set.empty, false, Set.empty)
      v.wouldBid shouldBe true
      v.floorCpm shouldBe Some(1.5)
    }

    "fall back to the site floor when no category floors are known" in {
      state.bidVerdict(site, Set("483"), CPM(1.0), Map.empty, Set.empty, false, Set.empty).wouldBid shouldBe true
    }
  }
}
