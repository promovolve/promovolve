package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType }

/**
 * PHASE 2 — serve-time enforcement of per-category floors.
 *
 * Exercises the REAL `AdServer.pickBestForSlot` with a `categoryFloors`
 * map: per-category admission + same-category second-price clearing clamped
 * to the winner's category floor. An EMPTY map must reproduce legacy
 * single-floor behavior exactly (the off/shadow path).
 */
class AdServerPerCategoryFloorSpec extends AnyWordSpec with Matchers {

  import promovolve.publisher.delivery.Protocol.*

  private def candidate(cid: String, campaign: String, category: String, cpm: Double): CandidateView =
    CandidateView(
      creativeId = CreativeId(cid),
      campaignId = CampaignId(campaign),
      advertiserId = AdvertiserId(s"adv-$campaign"),
      assetUrl = CDNPath(s"/assets/$cid.png"),
      mime = MimeType.imagePng,
      width = 300,
      height = 250,
      category = CategoryId(category),
      cpm = CPM(cpm),
      classifiedAtMs = 0L
    )

  /**
   * Warm stats with a known CTR so the winner is deterministic (warm
   * candidates sample from a tight Beta posterior, not the cold prior).
   */
  private def warm(ctrPct: Int): CreativeStats =
    CreativeStats(buckets = Map(0L -> (100, ctrPct, 0)))

  private val slot = BatchSlotSpec(SlotId("s"), 300, 250)
  private def rng() = new scala.util.Random(7L)
  private val siteFloor = CPM(0.50)

  "pickBestForSlot with per-category floors" should {

    "ENFORCE admission per category: a $3 bidder clears a $1 floor but not a $4 floor" in {
      val tech = candidate("t", "c1", "tech", 3.0)
      val pool = Vector(tech)

      AdServer.pickBestForSlot(slot, pool, Set.empty, 0.5, Map.empty, siteFloor, rng(),
        categoryFloors = Map(CategoryId("tech") -> CPM(1.0))).isDefined shouldBe true
      AdServer.pickBestForSlot(slot, pool, Set.empty, 0.5, Map.empty, siteFloor, rng(),
        categoryFloors = Map(CategoryId("tech") -> CPM(4.0))) shouldBe None
    }

    "NOT undercut a monopoly winner: sports $5 clears at its $5 floor despite a cheap tech runner-up" in {
      val sportsC = candidate("c", "cs", "sports", 5.0)
      val techA = candidate("a", "ca", "tech", 3.0)
      val pool = Vector(sportsC, techA)
      val stats = Map(CreativeId("c") -> warm(80), CreativeId("a") -> warm(10))
      val floors = Map(CategoryId("sports") -> CPM(5.0), CategoryId("tech") -> CPM(1.0))

      val Some((winner, clearing)) =
        AdServer.pickBestForSlot(slot, pool, Set.empty, 0.5, stats, siteFloor, rng(),
          categoryFloors = floors): @unchecked
      winner.category shouldBe CategoryId("sports") // higher quality+CPM wins the slot
      clearing.toDouble shouldBe 5.0 +- 0.01 // pays its OWN category floor, not dragged to tech's

      // Contrast: with NO per-category map, the same winner is priced against
      // the site floor and the cross-category runner-up — i.e. undercut.
      val Some((_, legacyClearing)) =
        AdServer.pickBestForSlot(slot, pool, Set.empty, 0.5, stats, siteFloor, rng(),
          categoryFloors = Map.empty): @unchecked
      legacyClearing.toDouble should be < 5.0
    }

    "price a competitive category at its within-category second price" in {
      val c1 = candidate("c1", "k1", "sports", 5.0)
      val c2 = candidate("c2", "k2", "sports", 4.0)
      val pool = Vector(c1, c2)
      val stats = Map(CreativeId("c1") -> warm(80), CreativeId("c2") -> warm(80))
      val floors = Map(CategoryId("sports") -> CPM(2.0))

      val Some((winner, clearing)) =
        AdServer.pickBestForSlot(slot, pool, Set.empty, 0.5, stats, siteFloor, rng(),
          categoryFloors = floors): @unchecked
      winner.creativeId shouldBe CreativeId("c1") // higher CPM, equal CTR
      clearing.toDouble should be > 2.0 // priced by competition, above the floor
      clearing.toDouble should be <= 5.0 // never above its own bid
    }

    "empty map == legacy: clears against the single site floor" in {
      val only = candidate("o", "c1", "anything", 2.0)
      val Some((_, clearing)) =
        AdServer.pickBestForSlot(slot, Vector(only), Set.empty, 0.5, Map.empty, siteFloor, rng(),
          categoryFloors = Map.empty): @unchecked
      clearing shouldBe siteFloor // sole candidate → pays the site floor
    }
  }

  "pickBestForSlot with an admin slot floor override" should {

    // The auction admits bids against the slot's admin override, the
    // publisher approves the winner — the serve gate must then use the
    // SAME floor, or the approved creative is silently blocked forever.
    "ADMIT a bidder below the site floor when the slot's override allows it" in {
      val cheap = candidate("c", "c1", "health", 1.0)
      val highSite = CPM(5.0)

      // Without the override the $5 site floor blocks the $1 bid...
      AdServer.pickBestForSlot(slot, Vector(cheap), Set.empty, 0.5, Map.empty, highSite, rng()) shouldBe None
      // ...with the slot overridden to $1 it serves.
      AdServer.pickBestForSlot(slot, Vector(cheap), Set.empty, 0.5, Map.empty, highSite, rng(),
        adminSlotFloor = Some(CPM(1.0))).isDefined shouldBe true
    }

    "clamp a sole bidder's clearing to the admin override, not the site floor" in {
      val cheap = candidate("c", "c1", "health", 1.0)
      val Some((_, clearing)) =
        AdServer.pickBestForSlot(slot, Vector(cheap), Set.empty, 0.5, Map.empty, CPM(5.0), rng(),
          adminSlotFloor = Some(CPM(0.75))): @unchecked
      clearing shouldBe CPM(0.75) // pays the override floor, not $5
    }

    "beat the per-category floor too (same precedence as auction admission)" in {
      val cheap = candidate("c", "c1", "health", 1.0)
      val floors = Map(CategoryId("health") -> CPM(4.0))

      AdServer.pickBestForSlot(slot, Vector(cheap), Set.empty, 0.5, Map.empty, siteFloor, rng(),
        categoryFloors = floors) shouldBe None
      AdServer.pickBestForSlot(slot, Vector(cheap), Set.empty, 0.5, Map.empty, siteFloor, rng(),
        categoryFloors = floors, adminSlotFloor = Some(CPM(1.0))).isDefined shouldBe true
    }
  }
}
