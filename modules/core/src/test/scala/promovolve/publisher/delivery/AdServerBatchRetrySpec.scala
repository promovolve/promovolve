package promovolve.publisher.delivery

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType }

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Pure-function tests for AdServer.batchReserveWithRetry — the
 * greedy assignment + per-slot retry loop that kicks in when a
 * winner's TryReserve fails. The reservation primitive is injected
 * as a function parameter, so tests wire in mocks returning
 * deterministic success/failure patterns without needing an actor
 * system or real CampaignEntity / AdvertiserEntity.
 */
class AdServerBatchRetrySpec extends AnyWordSpec with Matchers with ScalaFutures {

  import promovolve.publisher.delivery.Protocol.*

  given ExecutionContext = ExecutionContext.global
  given PatienceConfig = PatienceConfig(
    timeout = Span(5, Seconds),
    interval = Span(50, Millis)
  )

  // ─── Test helpers ─────────────────────────────────────────────

  private def candidate(
      cid: String,
      campaign: String,
      w: Int,
      h: Int,
      cpm: Double,
      categoryScore: Double = 0.5
  ): CandidateView = CandidateView(
    creativeId = CreativeId(cid),
    campaignId = CampaignId(campaign),
    advertiserId = AdvertiserId(s"adv-$campaign"),
    assetUrl = CDNPath(s"/assets/$cid.png"),
    mime = MimeType.imagePng,
    width = w,
    height = h,
    category = CategoryId("cat-test"),
    cpm = CPM(cpm),
    classifiedAtMs = 0L,
    categoryScore = categoryScore
  )

  private def slot(id: String, w: Int, h: Int): BatchSlotSpec =
    BatchSlotSpec(SlotId(id), w, h)

  private def seedRng(): scala.util.Random = new scala.util.Random(42L)

  /**
   * A reservation mock. Pass a predicate that takes the candidate
   * and returns whether the reservation succeeds.
   */
  private def reserveFn(
      rule: CandidateView => Boolean
  ): (CandidateView, CPM, String) => Future[Boolean] =
    (c, _, _) => Future.successful(rule(c))

  /**
   * Reservation mock that tracks attempts per creative. Handy for
   * "fail on first attempt, succeed on second" style tests.
   */
  private def reserveTrackingAttempts(
      succeedsAfter: Map[String, Int] // cid → attempts required to pass
  ): (CandidateView, CPM, String) => Future[Boolean] = {
    val attempts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    (c, _, _) =>
      Future.successful {
        val n = attempts(c.creativeId.value) + 1
        attempts.update(c.creativeId.value, n)
        n >= succeedsAfter.getOrElse(c.creativeId.value, 1)
      }
  }

  // ─── Tests ────────────────────────────────────────────────────

  "batchReserveWithRetry" should {

    "confirm the initial winner when its reservation passes" in {
      // Single eligible candidate → no runner-up → quality-adjusted
      // clearing falls back to siteFloor. Pending spend reflects that
      // floor price, not the winner's bid.
      val c = candidate("c1", "camp1", 300, 250, cpm = 5.0)
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(_ => true),
        rng = seedRng()
      ).futureValue
      outcomes should have size 1
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("c1")
      outcomes.head.clearingPrice shouldBe CPM(0.5)
      pending should contain(CampaignId("camp1") -> 0.5 / 1000.0)
    }

    "retry with next candidate when the initial winner's reservation fails" in {
      // Pool has two candidates for the same slot — c1 (high CPM, but
      // its reservation fails) and c2 (lower CPM, different campaign,
      // reservation passes). Retry should pick c2.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 3.0)
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(c => c.creativeId.value == "c2"),
        rng = seedRng()
      ).futureValue
      outcomes should have size 1
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("c2")
      pending.keySet shouldBe Set(CampaignId("campB"))
    }

    "return winner=None when every candidate's reservation fails" in {
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 3.0)
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(_ => false),
        rng = seedRng()
      ).futureValue
      outcomes should have size 1
      outcomes.head.winner shouldBe None
      pending shouldBe empty
    }

    "fill a slot with a recent page-winner when it's the only eligible demand (soft exclusion)" in {
      // pageBlocked normally keeps an advertiser from repeating on the
      // same page within the TTL. But with a single eligible campaign,
      // honoring that hard would blank the slot — a lost impression. The
      // soft-preference fallback lets the sole advertiser repeat instead.
      val c = candidate("c1", "camp1", 300, 250, cpm = 5.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c),
        pageBlocked = Set("camp1"), // camp1 won this page on a recent reload
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(_ => true),
        rng = seedRng()
      ).futureValue
      outcomes.head.winner.map(_.campaignId.value) shouldBe Some("camp1")
    }

    "prefer a non-page-winner over a recent page-winner when both are eligible" in {
      // Two advertisers; campA won this page recently (pageBlocked) and
      // even bids higher. With a real alternative available, the soft
      // preference rotates to campB rather than repeating campA.
      val cA = candidate("cA", "campA", 300, 250, cpm = 9.0)
      val cB = candidate("cB", "campB", 300, 250, cpm = 3.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(cA, cB),
        pageBlocked = Set("campA"),
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(_ => true),
        rng = seedRng()
      ).futureValue
      outcomes.head.winner.map(_.campaignId.value) shouldBe Some("campB")
    }

    "only retry the failed slot, not the confirmed ones" in {
      // Two slots, two candidates. c1 fits s1 (970x250), c2 fits s2
      // (300x250). c2's reservation passes; c1 fails. With no other
      // 970x250 candidate in the pool, s1 should stay unfilled while
      // s2 keeps its winner.
      val c1 = candidate("c1", "campA", 970, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 3.0)
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 970, 250), slot("s2", 300, 250)),
        pool = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(c => c.creativeId.value == "c2"),
        rng = seedRng()
      ).futureValue
      val byId = outcomes.map(o => o.slotId.value -> o.winner.map(_.creativeId.value)).toMap
      byId("s2") shouldBe Some("c2")
      byId("s1") shouldBe None
      pending.keySet shouldBe Set(CampaignId("campB"))
    }

    "retry failed-slot from pool minus already-confirmed-slot's creatives" in {
      // Three 300×250 candidates in the pool, two slots. Initial
      // assignment: s1 (best score) → c1, s2 → c2. c1's reservation
      // fails; retry for s1 must NOT pick c2 (already locked to s2).
      // Falls back to c3.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 10.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 5.0)
      val c3 = candidate("c3", "campC", 300, 250, cpm = 2.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250), slot("s2", 300, 250)),
        pool = Vector(c1, c2, c3),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(c => c.creativeId.value != "c1"),
        rng = seedRng()
      ).futureValue
      val winners = outcomes.flatMap(_.winner).map(_.creativeId.value).toSet
      winners should contain("c2")
      winners should contain("c3")
      winners should not contain "c1"
    }

    "free a failed winner's campaign back to the pool for other slots via soft cap" in {
      // Two slots, same size. Pool has two candidates from campaign A
      // (c1, c2) and one from campaign B (c3). Initial assignment
      // picks c1 for s1 (highest CPM) and, via soft cap on campA,
      // c3 for s2 (despite lower raw score). If c1's reservation
      // fails, campA is "unused" again — retry for s1 should be
      // able to pick c2 (campA) since c3 is locked to s2.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 10.0)
      val c2 = candidate("c2", "campA", 300, 250, cpm = 8.0)
      val c3 = candidate("c3", "campB", 300, 250, cpm = 5.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250), slot("s2", 300, 250)),
        pool = Vector(c1, c2, c3),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(c => c.creativeId.value != "c1"),
        rng = seedRng()
      ).futureValue
      val winners = outcomes.flatMap(_.winner).map(_.creativeId.value).toSet
      // s1 retries with c2 (campA — OK since c1's reservation failed
      // → campA not actually locked). s2 has c3 confirmed.
      winners shouldBe Set("c2", "c3")
    }

    "terminate when the pool is exhausted mid-retry without looping forever" in {
      // Pool of 3 candidates for 1 slot; all reservations fail. Must
      // return winner=None after trying all 3, not loop.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 3.0)
      val c3 = candidate("c3", "campC", 300, 250, cpm = 2.0)
      val attempts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
      val tracking: (CandidateView, CPM, String) => Future[Boolean] = (c, _, _) => {
        attempts.update(c.creativeId.value, attempts(c.creativeId.value) + 1)
        Future.successful(false)
      }
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c1, c2, c3),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = tracking,
        rng = seedRng()
      ).futureValue
      outcomes.head.winner shouldBe None
      pending shouldBe empty
      // Each candidate tried exactly once — no retry amplification.
      attempts.values.foreach(_ shouldBe 1)
      attempts.keySet shouldBe Set("c1", "c2", "c3")
    }

    "pending spend deltas only record confirmed winners" in {
      // c2 (top score) is picked first but its reservation fails; c1 wins.
      // Fluid creatives mean both candidates are eligible in the same slot
      // (dimensions don't gate), so c1 wins against c2 as runner-up — the
      // quality-adjusted second price clamps to c1's bid (4.0). Pending
      // records ONLY the confirmed c1 win, at its clearing price.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 4.0) // reservation passes
      val c2 = candidate("c2", "campB", 300, 250, cpm = 6.0) // reservation fails
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250), slot("s2", 300, 250)),
        pool = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(c => c.creativeId.value == "c1"),
        rng = seedRng()
      ).futureValue
      val confirmed = outcomes.flatMap(_.winner).map(_.creativeId.value).toSet
      confirmed shouldBe Set("c1")
      pending shouldBe Map(CampaignId("campA") -> 4.0 / 1000.0)
    }

    "respect pre-existing pageBlocked (soft cap applies across pages too)" in {
      // campA already won on an earlier page (in pageBlocked). A fresh
      // candidate from campB at comparable CPM should win over the
      // penalised campA candidate.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 4.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c1, c2),
        pageBlocked = Set("campA"),
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(_ => true),
        rng = seedRng()
      ).futureValue
      outcomes.head.winner.map(_.campaignId.value) shouldBe Some("campB")
    }

    "exclude creatives in excludedCreatives even when they would win the auction" in {
      // Site-wide pin block: c1 is the best-scoring candidate for s1
      // (highest CPM, same size) and would normally win. But the user
      // has pinned c1 on a slot on a different page, so ServeRoutes
      // forwards c1 in excludedCreatives. c1 must be skipped; c2 wins.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 10.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 3.0)
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = reserveFn(_ => true),
        rng = seedRng(),
        excludedCreatives = Set(CreativeId("c1"))
      ).futureValue
      outcomes should have size 1
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("c2")
      pending.keySet shouldBe Set(CampaignId("campB"))
    }

    "return winner=None when the only fitting candidate is excluded" in {
      // Pool has just c1, but c1 is pinned elsewhere. No fallback
      // candidate exists for this slot — outcome is winner=None, no
      // reservation attempted.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      var reserveCalls = 0
      val countingReserve: (CandidateView, CPM, String) => Future[Boolean] = (_, _, _) => {
        reserveCalls += 1
        Future.successful(true)
      }
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots = Vector(slot("s1", 300, 250)),
        pool = Vector(c1),
        pageBlocked = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        siteFloor = CPM(0.5),
        reserve = countingReserve,
        rng = seedRng(),
        excludedCreatives = Set(CreativeId("c1"))
      ).futureValue
      outcomes.head.winner shouldBe None
      pending shouldBe empty
      reserveCalls shouldBe 0
    }

    "succeed on the second attempt for the same candidate if allowed" in {
      // Demonstrates the attempts-tracking mock works as expected —
      // not necessarily the retry loop's own behaviour, since a slot
      // never re-tries the same creative. But the tracking mock is
      // used below and this confirms its semantics.
      val c = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val reserve = reserveTrackingAttempts(Map("c1" -> 2))
      reserve(c, CPM(5.0), "r1").futureValue shouldBe false // attempt 1
      reserve(c, CPM(5.0), "r2").futureValue shouldBe true // attempt 2
    }
  }
}
