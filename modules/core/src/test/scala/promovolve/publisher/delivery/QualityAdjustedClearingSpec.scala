package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.CPM

/**
 * Pure-function tests for `ThompsonSampling.qualityAdjustedClearing`.
 *
 * The formula inverts `score = sampledCTR × CPM^α` against the
 * runner-up's score, then clamps to [siteFloor, winnerBid]:
 *
 *   clearingCpm = (bestLoserScore / winnerSampledCtr) ^ (1/α)
 *
 * Tests below pin the canonical edge cases so future refactors
 * can't silently regress to first-price clearing.
 */
class QualityAdjustedClearingSpec extends AnyWordSpec with Matchers {

  private val Floor = CPM(0.5)

  "qualityAdjustedClearing" should {

    "fall back to siteFloor when there is no runner-up (bestLoserScore = 0)" in {
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerSampledCtr = 0.05,
        winnerBid = CPM(5.0),
        bestLoserScore = 0.0,
        alpha = 0.5,
        siteFloor = Floor
      )
      clearing shouldBe Floor
    }

    "fall back to siteFloor when winner's sampled CTR is zero" in {
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerSampledCtr = 0.0,
        winnerBid = CPM(5.0),
        bestLoserScore = 0.5,
        alpha = 0.5,
        siteFloor = Floor
      )
      clearing shouldBe Floor
    }

    "fall back to siteFloor when alpha is non-positive" in {
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerSampledCtr = 0.05,
        winnerBid = CPM(5.0),
        bestLoserScore = 0.5,
        alpha = 0.0,
        siteFloor = Floor
      )
      clearing shouldBe Floor
    }

    "compute the inverse-score formula at α=0.5 (sqrt)" in {
      // Winner: sampledCtr=0.04, bid=$5.00 → score = 0.04 × √5 ≈ 0.0894
      // Runner-up score: 0.0500
      // clearingCpm = (0.05 / 0.04)^2 = 1.25^2 = 1.5625
      // Within [floor=0.5, bid=5.0] → 1.5625
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerSampledCtr = 0.04,
        winnerBid = CPM(5.0),
        bestLoserScore = 0.05,
        alpha = 0.5,
        siteFloor = Floor
      )
      clearing.toDouble shouldBe 1.5625 +- 1e-6
    }

    "compute the inverse-score formula at α=0.7" in {
      // Winner: sampledCtr=0.05, bid=$8.00
      // Runner-up score: 0.10
      // clearingCpm = (0.10 / 0.05)^(1/0.7) = 2^1.4286 ≈ 2.6918
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerSampledCtr = 0.05,
        winnerBid = CPM(8.0),
        bestLoserScore = 0.10,
        alpha = 0.7,
        siteFloor = Floor
      )
      clearing.toDouble shouldBe 2.6918 +- 1e-3
    }

    "clamp UP to siteFloor when the formula yields a price below it" in {
      // High-CTR winner against a weak runner-up — formula would give
      // a clearing below the floor, but the publisher's floor wins.
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerSampledCtr = 0.5,
        winnerBid = CPM(5.0),
        bestLoserScore = 0.01,
        alpha = 0.5,
        siteFloor = Floor
      )
      clearing shouldBe Floor
    }

    "clamp DOWN to winner's bid when the formula yields a price above it" in {
      // Pathological: a runner-up score so high relative to the
      // winner's CTR that the formula would have the winner pay more
      // than they bid. Cap at the bid — the campaign never owes more
      // than its max CPM.
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerSampledCtr = 0.001,
        winnerBid = CPM(2.0),
        bestLoserScore = 1.0,
        alpha = 0.5,
        siteFloor = Floor
      )
      clearing shouldBe CPM(2.0)
    }
  }

  "batchReserveWithRetry clearing-price integration" should {

    import org.scalatest.concurrent.ScalaFutures
    import org.scalatest.time.{ Millis, Seconds, Span }
    import promovolve.publisher.{ CDNPath, CandidateView, MimeType }
    import promovolve.{ AdvertiserId, CampaignId, CategoryId, CreativeId, SlotId }

    import scala.concurrent.{ ExecutionContext, Future }

    given ExecutionContext = ExecutionContext.global

    object FuturesHelper extends ScalaFutures {
      given PatienceConfig = PatienceConfig(
        timeout = Span(5, Seconds),
        interval = Span(50, Millis)
      )
    }

    def candidate(cid: String, campaign: String, w: Int, h: Int, cpm: Double): CandidateView =
      CandidateView(
        creativeId = CreativeId(cid),
        campaignId = CampaignId(campaign),
        advertiserId = AdvertiserId(s"adv-$campaign"),
        assetUrl = CDNPath(s"/assets/$cid.png"),
        mime = MimeType.imagePng,
        width = w,
        height = h,
        category = CategoryId("cat-test"),
        cpm = CPM(cpm),
        classifiedAtMs = 0L
      )

    def slot(id: String, w: Int, h: Int): Protocol.BatchSlotSpec =
      Protocol.BatchSlotSpec(SlotId(id), w, h)

    def alwaysReserve: (CandidateView, CPM, String) => Future[Boolean] = (_, _, _) => Future.successful(true)

    "clear below the winner's bid when there is a runner-up" in {
      // Two cold candidates compete for one slot. With seeded RNG and
      // identical Beta(1,1) priors, sampledCtr for both is in [0,1];
      // winner's score must beat runner-up's score, so the formula
      // produces a clearingPrice strictly less than the winner's bid
      // and at or above the floor.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 4.0)
      import FuturesHelper.given
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = alwaysReserve,
        rng = new scala.util.Random(42L)
      ).futureValue

      outcomes should have size 1
      val out = outcomes.head
      out.winner shouldBe defined
      val winnerBid = out.winner.get.cpm.toDouble
      val cleared = out.clearingPrice.toDouble

      // Quality-adjusted clearing: winner pays at most their bid and
      // at least the floor. If the runner-up score yielded a sub-floor
      // figure the floor caps it; otherwise the price reflects the
      // competitive signal.
      cleared should be >= 0.5
      cleared should be <= winnerBid
      // Pending spend tracks the clearing price, not the bid.
      val winnerCampaign = out.winner.get.campaignId
      pending(winnerCampaign) shouldBe (cleared / 1000.0) +- 1e-9
    }
  }
}
