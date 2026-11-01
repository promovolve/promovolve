package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/**
 * A per-slot floor override must not sell a slot below the publisher's
 * minimum floor. The minimum is the publisher's "never below this"; an
 * override set under it (or left under it when the minimum is raised
 * later) is lifted to the minimum in what the auctioneer receives.
 */
class AdminSlotFloorMinSpec extends AnyWordSpec with Matchers {

  private def state(minFloor: Double, overrides: (String, Option[Double])*): SiteEntity.State =
    SiteEntity.State
      .empty(SiteId("site-1"))
      .withConfig(SiteEntity.SiteConfig(
        publisherId = PublisherId("pub-1"),
        domain = "example.com",
        seedUrl = "https://example.com",
        cronSchedule = "0 0 2 * * ?",
        maxDepth = 2,
        concurrency = 1,
        hostRegex = ".*",
        targetElements = Nil,
        taxonomyIds = Set("483"),
        slots = overrides.toList.map { case (id, f) =>
          SiteEntity.AdSlotConfig(id, 300, 250, floorOverride = f.map(CPM(_)))
        }
      ))
      .withMinFloorCpm(CPM(minFloor))

  "State.adminSlotFloors" should {
    "lift an override below the minimum floor to the minimum" in {
      state(1.0, "low" -> Some(0.5)).adminSlotFloors shouldBe Map(SlotId("low") -> CPM(1.0))
    }

    "keep an override at or above the minimum as set" in {
      state(1.0, "high" -> Some(3.0)).adminSlotFloors shouldBe Map(SlotId("high") -> CPM(3.0))
    }

    "leave out slots with no override" in {
      state(1.0, "auto" -> None).adminSlotFloors shouldBe empty
    }
  }
}
