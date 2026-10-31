package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType, ServeView }

/**
 * The second price comes from a DIFFERENT campaign. A campaign's own
 * second creative is not a competitor: letting it set the runner-up
 * score prices the winner against itself, i.e. near its own bid.
 *
 * A bids $5 with two equally good creatives, B bids $2 with the same
 * quality. A wins and must clear near B's $2, not near its own $5.
 */
class RunnerUpOtherCampaignSpec extends AnyWordSpec with Matchers {

  import promovolve.publisher.delivery.Protocol.*

  private def candidate(cid: String, campaign: String, cpm: Double): CandidateView =
    CandidateView(
      creativeId = CreativeId(cid),
      campaignId = CampaignId(campaign),
      advertiserId = AdvertiserId(s"adv-$campaign"),
      assetUrl = CDNPath(s"/assets/$cid.png"),
      mime = MimeType.imagePng,
      width = 300,
      height = 250,
      category = CategoryId("sports"),
      cpm = CPM(cpm),
      classifiedAtMs = 0L
    )

  private val pool = Vector(candidate("a1", "A", 5.0), candidate("a2", "A", 5.0), candidate("b", "B", 2.0))
  private val stats = pool.map(_.creativeId -> CreativeStats(buckets = Map(0L -> (1000, 50, 0)))).toMap
  private val slot = BatchSlotSpec(SlotId("s"), 300, 250)
  private val siteFloor = CPM(0.50)

  "pickBestForSlot" should {
    "price the winner against the best OTHER campaign, not its own second creative" in {
      val Some((winner, clearing)) =
        AdServer.pickBestForSlot(slot, pool, Set.empty, 0.5, stats, siteFloor, new scala.util.Random(7L)): @unchecked
      winner.campaignId shouldBe CampaignId("A")
      clearing.toDouble should be < 3.0
    }
  }

  "batchAssign" should {
    "price the winner against the best OTHER campaign, not its own second creative" in {
      val view = ServeView(pool, version = 1L, expiresAtMs = Long.MaxValue)
      val outcomes =
        AdServer.batchAssign(view, Vector(slot), siteFloor, Set.empty, 0.5, stats, new scala.util.Random(7L))
      outcomes.head.winner.map(_.campaignId) shouldBe Some(CampaignId("A"))
      outcomes.head.clearingPrice.toDouble should be < 3.0
    }
  }
}
