package promovolve.advertiser

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/**
 * The live-ask bid path (bidRejectReason) must honour the campaign's
 * [startAt, endAt) window the same way the bid book does: a campaign
 * that hasn't started, or has ended but not yet been flipped to Ended by
 * the roll tick, must not bid.
 */
class CampaignScheduleBidSpec extends AnyWordSpec with Matchers {

  private val cat = CategoryId("100")
  private val floor = CPM(1.0)
  private val site = SiteId("site-a")
  private val now = Instant.now()

  private def stateWith(startAt: Instant, endAt: Option[Instant]): CampaignEntity.State =
    CampaignEntity.State
      .empty(CampaignId("camp-1"), AdvertiserId("adv-1"))
      .copy(
        status = CampaignEntity.Status.Active,
        categories = Set(cat),
        maxCpm = CPM(5.0),
        startAt = startAt,
        endAt = endAt
      )

  "The live bid path" should {

    "bid inside the schedule" in {
      stateWith(now.minus(1, ChronoUnit.DAYS), Some(now.plus(1, ChronoUnit.DAYS)))
        .bidRejectReason(site, cat, floor) shouldBe None
    }

    "not bid before startAt" in {
      stateWith(now.plus(1, ChronoUnit.DAYS), None)
        .bidRejectReason(site, cat, floor) shouldBe Some(CampaignEntity.BidRejectReason.OutsideSchedule)
    }

    "not bid after endAt, even while the status is still Active" in {
      stateWith(now.minus(2, ChronoUnit.DAYS), Some(now.minus(1, ChronoUnit.HOURS)))
        .bidRejectReason(site, cat, floor) shouldBe Some(CampaignEntity.BidRejectReason.OutsideSchedule)
    }

    "report the schedule on the advertiser's bid checker too" in {
      stateWith(now.plus(1, ChronoUnit.DAYS), None)
        .bidVerdict(site, Set(cat.value), floor, Set.empty, false, Set.empty)
        .reason shouldBe Some("OutsideSchedule")
    }
  }
}
