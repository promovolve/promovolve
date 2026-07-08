package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/** Covers the pure pieces of the persist-floor-observations change:
  * the bounded append the tick uses and the State field it now rides
  * in. Like the persisted-classifications change, the full
  * recovery-hydrates-the-var loop is verified at runtime (restart the
  * api pods, observations page keeps its rows) — old persisted states
  * without the field deserialize to the Vector.empty default, the same
  * proven no-migration pattern as pageClassifications and
  * demandCategories. */
class FloorObservationPersistenceSpec extends AnyWordSpec with Matchers {

  private val siteId = SiteId("test-site")

  private def obs(hour: Int): SiteEntity.FloorObservation =
    SiteEntity.FloorObservation(
      ts                = java.time.Instant.ofEpochSecond(hour.toLong * 3600),
      hour              = hour,
      trafficShape      = 0.5,
      floorBefore       = 0.10,
      floorAfter        = 0.10,
      epsilon           = 0.0,
      observed          = false,
      trainingLoss      = None,
      slotOverrideCount = 0,
    )

  "appendBounded" should {
    "prepend below the cap (newest first — the dashboard reads take(limit))" in {
      val v = SiteEntity.appendBounded(Vector(obs(1)), obs(2), 100)
      v.map(_.hour) shouldBe Vector(2, 1)
    }

    "drop the oldest (tail) at the cap" in {
      val full = (1 to 100).foldLeft(Vector.empty[SiteEntity.FloorObservation]) { (v, i) =>
        SiteEntity.appendBounded(v, obs(i), 100)
      }
      full should have size 100
      val v2 = SiteEntity.appendBounded(full, obs(101), 100)
      v2 should have size 100
      v2.head.hour shouldBe 101
      v2.last.hour shouldBe 2
    }
  }

  "State.recentFloorObservations" should {
    "default empty (old persisted states carry no field)" in {
      SiteEntity.State.empty(siteId).recentFloorObservations shouldBe empty
    }

    "ride a copy through withRecentFloorObservations" in {
      val s = SiteEntity.State.empty(siteId).withRecentFloorObservations(Vector(obs(1), obs(2)))
      s.recentFloorObservations.map(_.hour) shouldBe Vector(1, 2)
      // and survive unrelated state updates (the tick persists a state
      // derived from the previous one)
      val s2 = s.withFloorCpm(CPM(0.25))
      s2.recentFloorObservations.map(_.hour) shouldBe Vector(1, 2)
    }
  }
}
