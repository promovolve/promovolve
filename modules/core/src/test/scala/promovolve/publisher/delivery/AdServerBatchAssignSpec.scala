package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType, ServeView }

/**
 * Pure-function tests for AdServer.batchAssign — the greedy
 * score-descending assignment that fills all slots on a page in a
 * single pass. No actor system, no DData, no async. Direct
 * exercise of the algorithm against constructed pools.
 */
class AdServerBatchAssignSpec extends AnyWordSpec with Matchers {

  import promovolve.publisher.delivery.Protocol.*

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

  private def view(cs: CandidateView*): ServeView =
    ServeView(candidates = cs.toVector, version = 0L, expiresAtMs = Long.MaxValue)

  // Seed a fixed RNG so score-based ordering is reproducible across
  // test runs. scoreCandidate reads rng for Beta sampling + cold-start
  // noise; fixing the seed freezes the (stable) winner for a given pool.
  private def rng(): scala.util.Random = new scala.util.Random(42L)

  // ─── Tests ────────────────────────────────────────────────────

  "batchAssign" should {

    "return winner=None for every slot when the candidate pool is empty" in {
      val outcomes = AdServer.batchAssign(
        view = view(),
        slots = Vector(slot("a", 970, 250), slot("b", 300, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      outcomes should have size 2
      outcomes.foreach(_.winner shouldBe None)
    }

    "exclude candidates whose CPM is below the site floor" in {
      val below = candidate("below", "c1", 300, 250, cpm = 0.3)
      val above = candidate("above", "c2", 300, 250, cpm = 2.0)
      val outcomes = AdServer.batchAssign(
        view = view(below, above),
        slots = Vector(slot("only", 300, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("above")
    }

    "ignore slot dimensions for fluid creatives — top-scoring candidate wins regardless of native size" in {
      // Fluid creatives render at any slot size, so selection is by score
      // (CPM × quality), not exact pixel match. `big` is a 970×250 creative
      // bidding higher than the 300×250 `small`; even though `small` is the
      // exact size of the 300×250 slot, the higher-CPM `big` wins.
      val big = candidate("big", "c1", 970, 250, cpm = 5.0)
      val small = candidate("small", "c2", 300, 250, cpm = 1.0)
      val outcomes = AdServer.batchAssign(
        view = view(big, small),
        slots = Vector(slot("mid", 300, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      outcomes.head.winner.map(_.creativeId.value) shouldBe Some("big")
    }

    "give the largest slot first pick of the pool (premium slot gets the top candidate)" in {
      // Slots are assigned biggest-area first. With fluid creatives,
      // dimensions don't gate selection — so the billboard (largest) slot
      // gets first pick and takes the highest-scoring candidate (rect,
      // cpm 3.0), leaving the smaller rect slot the leftover (billboard,
      // cpm 2.0). Validates the area-descending pick order.
      val billboard = candidate("bb", "c1", 970, 250, cpm = 2.0)
      val rect = candidate("mr", "c2", 300, 250, cpm = 3.0)
      val outcomes = AdServer.batchAssign(
        view = view(billboard, rect),
        slots = Vector(slot("rect", 300, 250), slot("billboard", 970, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      val byId = outcomes.map(o => o.slotId.value -> o.winner.map(_.creativeId.value)).toMap
      byId("billboard") shouldBe Some("mr") // largest slot → highest-scoring candidate
      byId("rect") shouldBe Some("bb") // leftover goes to the smaller slot
    }

    "hard-exclude repeat campaigns across slots — narrow pool leaves later slots empty" in {
      // Only one campaign in the pool, two same-size slots. With
      // hard campaign dedup, slot 1 fills and slot 2 is forced empty
      // rather than serving a second creative from the same
      // advertiser on the page.
      val c1 = candidate("c1-a", "camp1", 300, 250, cpm = 5.0)
      val c2 = candidate("c1-b", "camp1", 300, 250, cpm = 3.0)
      val outcomes = AdServer.batchAssign(
        view = view(c1, c2),
        slots = Vector(slot("top", 300, 250), slot("bot", 300, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      val winners = outcomes.flatMap(_.winner)
      winners.size shouldBe 1
      winners.head.campaignId.value shouldBe "camp1"
      outcomes.find(_.winner.isEmpty) should not be None
    }

    "prefer a non-repeat campaign over a prior-winning one at any CPM gap" in {
      // camp1 already won a slot on this page (via pageWinners). Even
      // if its CPM is massively higher than camp2's, camp1 is hard-
      // excluded and camp2 wins.
      val camp1Huge = candidate("c1", "camp1", 300, 250, cpm = 20.0)
      val camp2Tiny = candidate("c2", "camp2", 300, 250, cpm = 0.6)
      val outcomes = AdServer.batchAssign(
        view = view(camp1Huge, camp2Tiny),
        slots = Vector(slot("only", 300, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set("camp1"),
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      outcomes.head.winner.map(_.campaignId.value) shouldBe Some("camp2")
    }

    "leave slot empty when only a prior-winning campaign is eligible" in {
      // camp1 is in pageWinners and it's the only campaign in the
      // pool. Hard exclusion leaves the slot unfilled rather than
      // doubling up the same advertiser on the page.
      val c1 = candidate("c1", "camp1", 300, 250, cpm = 20.0)
      val outcomes = AdServer.batchAssign(
        view = view(c1),
        slots = Vector(slot("only", 300, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set("camp1"),
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      outcomes.head.winner shouldBe None
    }

    "hard-exclude the same creativeId across slots (responsive creative with multiple size variants)" in {
      // Two size variants of the same creative — responsive creative
      // model: one creativeId has multiple CandidateView entries,
      // one per supported size. Without the hard dedup this picks the
      // same creative for both slots (campaign soft cap still lets it
      // win when no alternative exists).
      val variant300 = candidate("same-cid", "camp", 300, 250, cpm = 2.0)
      val variant728 = candidate("same-cid", "camp", 728, 90, cpm = 2.0)
      val outcomes = AdServer.batchAssign(
        view = view(variant300, variant728),
        slots = Vector(slot("a", 300, 250), slot("b", 728, 90)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      val winners = outcomes.flatMap(_.winner)
      winners.size shouldBe 1
      winners.head.creativeId.value shouldBe "same-cid"
      val unfilled = outcomes.find(_.winner.isEmpty)
      unfilled should not be None
    }

    "still fills both slots when the second slot has a distinct-creative alternative" in {
      // Same-campaign responsive creative at 300x250 plus a different
      // creative at 728x90 (could be from any campaign) — both slots
      // should fill.
      val responsive = candidate("cid-A", "camp1", 300, 250, cpm = 2.0)
      val alt728 = candidate("cid-B", "camp2", 728, 90, cpm = 1.5)
      val outcomes = AdServer.batchAssign(
        view = view(responsive, alt728),
        slots = Vector(slot("a", 300, 250), slot("b", 728, 90)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      outcomes.flatMap(_.winner).map(_.creativeId.value).toSet shouldBe Set("cid-A", "cid-B")
    }

    "write clearingPrice = siteFloor when only one candidate fits and stamp a requestId" in {
      // Single eligible candidate → no runner-up → quality-adjusted
      // clearing falls back to siteFloor. The winner pays the floor,
      // not its bid, matching the per-slot exploitation semantics
      // (Solo / no-runner-up paths charge floor).
      val c = candidate("only", "camp", 300, 250, cpm = 1.75)
      val outcomes = AdServer.batchAssign(
        view = view(c),
        slots = Vector(slot("s", 300, 250)),
        siteFloor = CPM(0.5),
        pageWinners = Set.empty,
        alpha = 0.5,
        stats = Map.empty,
        rng = rng()
      )
      val o = outcomes.head
      o.winner should not be None
      o.clearingPrice.toDouble shouldBe 0.5 +- 1e-9
      o.requestId should not be empty
    }
  }
}
