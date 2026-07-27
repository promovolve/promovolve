package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType }

/**
 * Serve-stat accounting for a resolved batch.
 *
 * The invariant under test: `totalSpend` accrues the CLEARING price of
 * each winner and nothing else. The regression this guards against is a
 * fallback that treated `clearingPrice == 0` as missing data and
 * substituted the winner's bid — which booked every free dog-ear pin
 * impression at the advertiser's full maxCpm, inflating the publisher
 * Revenue tile and the traffic-shape spend series while billing (which
 * reads the clearing price) stayed correct.
 */
class AdServerServeStatsSpec extends AnyWordSpec with Matchers {

  import promovolve.publisher.delivery.Protocol.*

  private def candidate(cid: String, cpm: Double): CandidateView =
    CandidateView(
      creativeId = CreativeId(cid),
      campaignId = CampaignId(s"camp-$cid"),
      advertiserId = AdvertiserId(s"adv-$cid"),
      assetUrl = CDNPath(s"/assets/$cid.png"),
      mime = MimeType.imagePng,
      width = 300,
      height = 250,
      category = CategoryId("tech"),
      cpm = CPM(cpm),
      classifiedAtMs = 0L
    )

  private def won(slot: String, bid: Double, clearing: Double): BatchSlotOutcome =
    BatchSlotOutcome(
      slotId = SlotId(slot),
      winner = Some(candidate(slot, bid)),
      clearingPrice = CPM(clearing)
    )

  /** An honored dog-ear pin: a real winner that serves free. */
  private def pinned(slot: String, bid: Double): BatchSlotOutcome =
    BatchSlotOutcome(
      slotId = SlotId(slot),
      winner = Some(candidate(slot, bid)),
      clearingPrice = CPM.zero,
      dogear = Some(DogearOutcome(honored = true))
    )

  private def empty(slot: String): BatchSlotOutcome =
    BatchSlotOutcome(slotId = SlotId(slot), winner = None)

  private val hour = 9

  "AdServer.recordBatchServeStats" should {

    "accrue the clearing price, not the bid" in {
      val stats =
        AdServer.recordBatchServeStats(ServeStats("site-1"), Vector(won("a", bid = 5.0, clearing = 2.0)), hour)

      stats.selected shouldBe 1
      stats.totalSpend shouldBe 0.002 +- 1e-9 // 2.00 CPM = $0.002 per impression
      stats.hourlyImpressions(hour) shouldBe 1
    }

    "book an honored pin at ZERO, not at the winner's bid" in {
      val stats = AdServer.recordBatchServeStats(ServeStats("site-1"), Vector(pinned("a", bid = 5.0)), hour)

      // The impression is real and counted...
      stats.selected shouldBe 1
      stats.hourlyImpressions(hour) shouldBe 1
      // ...but it was free. The regression booked $0.005 here.
      stats.totalSpend shouldBe 0.0 +- 1e-9
    }

    "keep a paid winner's spend intact when a free pin shares the batch" in {
      val stats = AdServer.recordBatchServeStats(
        ServeStats("site-1"),
        Vector(won("a", bid = 5.0, clearing = 2.0), pinned("b", bid = 9.0)),
        hour
      )

      stats.selected shouldBe 2
      stats.hourlyImpressions(hour) shouldBe 2
      stats.totalSpend shouldBe 0.002 +- 1e-9
    }

    "ignore unfilled slots but still count the filled ones" in {
      val stats = AdServer.recordBatchServeStats(
        ServeStats("site-1"),
        Vector(won("a", bid = 5.0, clearing = 2.0), empty("b")),
        hour
      )

      stats.selected shouldBe 1
      stats.totalSpend shouldBe 0.002 +- 1e-9
    }

    "record noCandidates when the batch filled nothing" in {
      val stats = AdServer.recordBatchServeStats(ServeStats("site-1"), Vector(empty("a"), empty("b")), hour)

      stats.noCandidates shouldBe 1
      stats.selected shouldBe 0
      stats.totalSpend shouldBe 0.0 +- 1e-9
    }

    "treat an all-pins batch as served, not as noCandidates" in {
      val stats = AdServer.recordBatchServeStats(ServeStats("site-1"), Vector(pinned("a", 5.0), pinned("b", 3.0)), hour)

      stats.noCandidates shouldBe 0
      stats.selected shouldBe 2
      stats.totalSpend shouldBe 0.0 +- 1e-9
    }

    "accumulate onto existing stats rather than replacing them" in {
      val seed = AdServer.recordBatchServeStats(ServeStats("site-1"), Vector(won("a", 5.0, 2.0)), hour)
      val stats = AdServer.recordBatchServeStats(seed, Vector(won("b", 5.0, 3.0)), hour)

      stats.selected shouldBe 2
      stats.totalSpend shouldBe 0.005 +- 1e-9
      stats.hourlyImpressions(hour) shouldBe 2
    }
  }
}
