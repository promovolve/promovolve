package promovolve.publisher.delivery

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import promovolve.publisher.{CDNPath, CandidateView, MimeType}
import promovolve.{AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** Pure-function tests for the dog-ear pin-honoring path inside
  * AdServer.batchReserveWithRetry. Three behaviors matter:
  *
  *   1. Pin honored: pinned creative bypasses the CPM reservation
  *      (folds are free engagement signals) and wins its slot.
  *   2. Pin fallthrough: when the pinned creativeId is no longer in
  *      the pool, the slot runs a normal auction and the outcome
  *      carries dogear.honored=false reason="creative_removed".
  *   3. The pinned slot still consumes a campaign/creative in the
  *      page-cap state so other slots don't double up on the same
  *      advertiser.
  */
class AdServerPinHonorSpec extends AnyWordSpec with Matchers with ScalaFutures {

  import promovolve.publisher.delivery.Protocol.*

  given ExecutionContext = ExecutionContext.global
  given PatienceConfig = PatienceConfig(
    timeout  = Span(5, Seconds),
    interval = Span(50, Millis),
  )

  // ─── Test helpers ─────────────────────────────────────────────

  private def candidate(
      cid: String,
      campaign: String,
      w: Int,
      h: Int,
      cpm: Double,
  ): CandidateView = CandidateView(
    creativeId     = CreativeId(cid),
    campaignId     = CampaignId(campaign),
    advertiserId   = AdvertiserId(s"adv-$campaign"),
    assetUrl       = CDNPath(s"/assets/$cid.png"),
    mime           = MimeType.imagePng,
    width          = w,
    height         = h,
    category       = CategoryId("cat-test"),
    cpm            = CPM(cpm),
    classifiedAtMs = 0L,
  )

  private def slot(id: String, w: Int, h: Int): BatchSlotSpec =
    BatchSlotSpec(SlotId(id), w, h)

  private def slotPinned(id: String, w: Int, h: Int, pin: String): BatchSlotSpec =
    BatchSlotSpec(SlotId(id), w, h, pin = Some(CreativeId(pin)))

  private def seedRng(): scala.util.Random = new scala.util.Random(42L)

  /** Reservation mock that records every creative it's asked to reserve.
    * Lets tests assert pinned creatives never enter the reservation path.
    */
  private class TrackingReserve(rule: CandidateView => Boolean = _ => true) {
    val attempts: mutable.Set[String] = mutable.Set.empty
    val fn: (CandidateView, CPM, String) => Future[Boolean] = (c, _, _) => {
      attempts += c.creativeId.value
      Future.successful(rule(c))
    }
  }

  // ─── Tests ────────────────────────────────────────────────────

  "batchReserveWithRetry pin-honoring" should {

    "honor a pin when the creative is in the pool, bypassing reservation" in {
      val pinned = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val reserve = new TrackingReserve()
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots       = Vector(slotPinned("s1", 300, 250, pin = "c1")),
        pool        = Vector(pinned),
        pageBlocked = Set.empty,
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(0.5),
        reserve     = reserve.fn,
        rng         = seedRng(),
      ).futureValue

      outcomes should have size 1
      val out = outcomes.head
      out.winner.map(_.creativeId.value) shouldBe Some("c1")
      out.dogear shouldBe Some(DogearOutcome(honored = true))
      // Folds are free engagement signals — no CPM clearing for honored serves.
      out.clearingPrice shouldBe CPM.zero
      // Reservation is skipped entirely for pinned slots.
      reserve.attempts shouldBe empty
      // No CPM pending spend gets recorded for an honored pin.
      pending shouldBe empty
    }

    "fall through to auction with dogear.honored=false when the pinned creative is not in the pool" in {
      // Only c-other lives in the pool; the pin points at c-removed which
      // was deleted from ServeIndex (campaign paused, creative unassigned, etc.).
      // isApproved returns false for c-removed → dogearFallthrough emits
      // creative_removed (truly gone). c-other stays approved.
      val other = candidate("c-other", "campA", 300, 250, cpm = 4.0)
      val reserve = new TrackingReserve()
      val (outcomes, pending) = AdServer.batchReserveWithRetry(
        slots       = Vector(slotPinned("s1", 300, 250, pin = "c-removed")),
        pool        = Vector(other),
        pageBlocked = Set.empty,
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(0.5),
        reserve     = reserve.fn,
        rng         = seedRng(),
        isApproved  = cid => cid.value != "c-removed",
      ).futureValue

      outcomes should have size 1
      val out = outcomes.head
      out.winner.map(_.creativeId.value) shouldBe Some("c-other")
      out.dogear shouldBe Some(DogearOutcome(honored = false, reason = Some("creative_removed")))
      // Auction runs normally for the fallthrough slot — its winner went
      // through reservation and pending spend was recorded.
      reserve.attempts should contain("c-other")
      pending.keySet shouldBe Set(CampaignId("campA"))
    }

    "leave dogear=None on a slot that carried no pin" in {
      val c = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots       = Vector(slot("s1", 300, 250)),
        pool        = Vector(c),
        pageBlocked = Set.empty,
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(0.5),
        reserve     = new TrackingReserve().fn,
        rng         = seedRng(),
      ).futureValue
      outcomes.head.dogear shouldBe None
    }

    "honor a pin even when the pinned creative's CPM is below the site floor" in {
      // Pinned creatives bypass the floor check — pins are reader bookmarks,
      // not auction wins, so the floor isn't a relevant gate. Without the
      // bypass, this pin would fail and the reader would lose their bookmark.
      val cheap = candidate("c1", "campA", 300, 250, cpm = 0.10)
      val reserve = new TrackingReserve()
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots       = Vector(slotPinned("s1", 300, 250, pin = "c1")),
        pool        = Vector(cheap),
        pageBlocked = Set.empty,
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(5.0), // far above c1's CPM
        reserve     = reserve.fn,
        rng         = seedRng(),
      ).futureValue
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("c1")
      outcomes.head.dogear shouldBe Some(DogearOutcome(honored = true))
      reserve.attempts shouldBe empty
    }

    "honor a pin via pinLookupPool even when the pinned creative was floor-filtered out of the auction pool" in {
      // Regression test: this simulates the live serve path where the
      // caller filters by floor BEFORE batchReserveWithRetry sees the
      // pool. The auction pool (`pool`) doesn't include the pinned
      // creative, but the pre-floor pool (`pinLookupPool`) does. Pin
      // honor must succeed using the wider lookup pool. Without the
      // pinLookupPool parameter, the sweep optimizer would silently
      // void user bookmarks whenever it tests a candidate floor above
      // the pinned creative's CPM.
      val cheap = candidate("c1", "campA", 300, 250, cpm = 0.10)
      val reserve = new TrackingReserve()
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots         = Vector(slotPinned("s1", 300, 250, pin = "c1")),
        pool          = Vector.empty,      // post-floor: pin excluded
        pageBlocked   = Set.empty,
        alpha         = 0.5,
        stats         = Map.empty,
        siteFloor     = CPM(5.0),
        reserve       = reserve.fn,
        rng           = seedRng(),
        pinLookupPool = Vector(cheap),     // pre-floor: pin present
      ).futureValue
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("c1")
      outcomes.head.dogear shouldBe Some(DogearOutcome(honored = true))
      reserve.attempts shouldBe empty
    }

    "honor a pin even when the pinned creative's campaign is in pageBlocked" in {
      // Soft cap doesn't apply to pins — the user chose this exact
      // creative; we honor it regardless of cross-page dedup state.
      val pinned = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots       = Vector(slotPinned("s1", 300, 250, pin = "c1")),
        pool        = Vector(pinned),
        pageBlocked = Set("campA"), // campA already won on a prior page
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(0.5),
        reserve     = new TrackingReserve().fn,
        rng         = seedRng(),
      ).futureValue
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("c1")
      outcomes.head.dogear shouldBe Some(DogearOutcome(honored = true))
    }

    "lock the pinned creative's campaign so other slots can't double up" in {
      // s1 is pinned to c1 (campA). s2 has no pin. The pool has another
      // campA candidate (c2) at higher CPM than the campB candidate (c3).
      // Without the page-cap consume, s2 would pick c2 and the page would
      // show two campA ads. With it, s2 must pick c3.
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campA", 300, 250, cpm = 8.0)
      val c3 = candidate("c3", "campB", 300, 250, cpm = 4.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots       = Vector(slotPinned("s1", 300, 250, pin = "c1"), slot("s2", 300, 250)),
        pool        = Vector(c1, c2, c3),
        pageBlocked = Set.empty,
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(0.5),
        reserve     = new TrackingReserve().fn,
        rng         = seedRng(),
      ).futureValue
      val byId = outcomes.map(o => o.slotId.value -> o.winner.map(_.creativeId.value)).toMap
      byId("s1") shouldBe Some("c1")
      byId("s2") shouldBe Some("c3") // not c2 — campA already used by the pin
    }

    "lock the pinned creativeId so it can't be re-served on another slot" in {
      // Pin uses c1 on s1. s2 is also 300x250 and could otherwise pick c1
      // on its own. With the pin's hard-exclude on creativeId, s2 must
      // pick something else (or stay empty).
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 300, 250, cpm = 4.0)
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots       = Vector(slotPinned("s1", 300, 250, pin = "c1"), slot("s2", 300, 250)),
        pool        = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(0.5),
        reserve     = new TrackingReserve().fn,
        rng         = seedRng(),
      ).futureValue
      val byId = outcomes.map(o => o.slotId.value -> o.winner.map(_.creativeId.value)).toMap
      byId("s1") shouldBe Some("c1")
      byId("s2") shouldBe Some("c2")
    }

    "honor multiple pins independently in a single batch" in {
      val c1 = candidate("c1", "campA", 300, 250, cpm = 5.0)
      val c2 = candidate("c2", "campB", 970, 250, cpm = 4.0)
      val reserve = new TrackingReserve()
      val (outcomes, _) = AdServer.batchReserveWithRetry(
        slots       = Vector(
          slotPinned("s1", 300, 250, pin = "c1"),
          slotPinned("s2", 970, 250, pin = "c2"),
        ),
        pool        = Vector(c1, c2),
        pageBlocked = Set.empty,
        alpha       = 0.5,
        stats       = Map.empty,
        siteFloor   = CPM(0.5),
        reserve     = reserve.fn,
        rng         = seedRng(),
      ).futureValue
      val byId = outcomes.map(o => o.slotId.value -> o.dogear).toMap
      byId("s1") shouldBe Some(DogearOutcome(honored = true))
      byId("s2") shouldBe Some(DogearOutcome(honored = true))
      reserve.attempts shouldBe empty
    }
  }
}
