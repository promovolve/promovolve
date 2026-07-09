package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * StalePins.derive feeds client-side DELETION of a user's bookmark
 * (the dog-ear pin in IndexedDB), so every distinction here is
 * safety-critical: a transient lookup failure or an on-page pin must
 * never be reported, or a live bookmark gets destroyed; a genuinely
 * dead pin MUST be reported, or its campaign stays excluded in that
 * browser forever (with one campaign in the system, that's a total
 * per-browser ad blackout — observed live 2026-06-11).
 */
class StalePinsSpec extends AnyWordSpec with Matchers {

  private val onPage = Set("PAGE-SLOT-01")
  private def pin(slot: String, cid: String): PinHint = PinHint(slot, cid)

  "StalePins.derive" should {

    "report a pin whose creative was looked up OK and NOT found" in {
      StalePins.derive(
        pinLookups = Vector("dead-creative" -> Some(None)),
        pins = Vector(pin("OTHER-SLOT", "dead-creative")),
        slotIdsOnPage = onPage,
        siteSlotIds = Set("OTHER-SLOT")
      ) shouldBe Vector("dead-creative")
    }

    "NEVER report a pin whose lookup FAILED (transient repo error)" in {
      StalePins.derive(
        pinLookups = Vector("maybe-alive" -> None),
        pins = Vector(pin("OTHER-SLOT", "maybe-alive")),
        slotIdsOnPage = onPage,
        siteSlotIds = Set("OTHER-SLOT")
      ) shouldBe empty
    }

    "not report a pin whose creative is alive" in {
      StalePins.derive(
        pinLookups = Vector("alive" -> Some(Some("campaign-1"))),
        pins = Vector(pin("OTHER-SLOT", "alive")),
        slotIdsOnPage = onPage,
        siteSlotIds = Set("OTHER-SLOT")
      ) shouldBe empty
    }

    // The slot-existence check was REMOVED — under on-demand classification
    // the slot inventory is built lazily from real traffic, so an off-page
    // pin's slot may simply not be activated yet. Deleting it would destroy a
    // live fold (the "fold won't stay folded" regression). Only deleted
    // creatives are reported stale now.
    "NEVER report a pin merely because its slot is absent from the (lazy) site config" in {
      StalePins.derive(
        pinLookups = Vector("alive" -> Some(Some("campaign-1"))),
        pins = Vector(pin("NOT-YET-ACTIVATED-SLOT", "alive")),
        slotIdsOnPage = onPage,
        siteSlotIds = Set("OTHER-SLOT", "PAGE-SLOT-01")
      ) shouldBe empty
    }

    "never apply the slot check to ON-page pins (per-slot dogear channel owns them)" in {
      // An on-page slot may legitimately be absent from the crawled
      // site config (publisher added it before the next crawl).
      StalePins.derive(
        pinLookups = Vector.empty,
        pins = Vector(pin("PAGE-SLOT-01", "alive")),
        slotIdsOnPage = onPage,
        siteSlotIds = Set("SOMETHING-ELSE")
      ) shouldBe empty
    }

    "skip the slot check entirely when the site slot config is empty (mid-crawl / ask failed)" in {
      StalePins.derive(
        pinLookups = Vector.empty,
        pins = Vector(pin("ANY-SLOT", "alive")),
        slotIdsOnPage = onPage,
        siteSlotIds = Set.empty
      ) shouldBe empty
    }

    "dedupes a pin that is stale by both signals" in {
      StalePins.derive(
        pinLookups = Vector("dead" -> Some(None)),
        pins = Vector(pin("RENAMED-AWAY-SLOT", "dead")),
        slotIdsOnPage = onPage,
        siteSlotIds = Set("PAGE-SLOT-01")
      ) shouldBe Vector("dead")
    }
  }
}
