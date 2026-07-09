package promovolve.creative

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LayoutTemplatesSpec extends AnyWordSpec with Matchers {

  import LayoutTemplates as LT

  "LayoutTemplates catalog" should {

    "expose at least one template" in {
      LT.all should not be empty
    }

    "have unique ids" in {
      val ids = LT.all.map(_.id)
      ids.distinct.size shouldBe ids.size
    }

    "match the TypeScript copy on the canonical ids" in {
      // Drift guard: if the dashboard shows a template that the
      // designer's TS catalog has never heard of, applying-then-
      // re-editing breaks. Both copies must enumerate the same ids
      // (platform/creative-designer/src/layout-templates.ts TEMPLATES).
      val expected = Set("promo", "mobile-hero-top", "mobile-fullbleed-overlay")
      LT.all.map(_.id).toSet shouldBe expected
    }

    "give every template at least one slot" in {
      LT.all.foreach { t =>
        withClue(s"template ${t.id}: ") { t.slots should not be empty }
      }
    }

    "mark every CTA slot as primary" in {
      // Sanity check matching the TS test: the CTA is the conversion
      // action; if a template has one, it should anchor its region.
      LT.all.foreach { t =>
        t.slots.filter(_.role == LT.SlotRole.Cta).foreach { s =>
          withClue(s"template ${t.id} CTA prominence: ") {
            s.prominence shouldBe Some(LT.Prominence.Primary)
          }
        }
      }
    }
  }

  "findById" should {
    "return the template when present" in {
      LT.findById("promo").map(_.name) shouldBe Some("Promo / Sale")
    }
    "return None for unknown ids" in {
      LT.findById("does-not-exist") shouldBe None
    }
  }

  "slotsAsPromptLine" should {

    "render a single sentence describing every slot" in {
      val t = LT.findById("promo").get
      val line = LT.slotsAsPromptLine(t)
      line should include("Layout intent:")
      line should include("hero (primary) in left")
      line should include("headline (primary) in right")
      line should include("body (secondary) in right")
    }

    "return empty for a slot-less template" in {
      val empty = LT.Template(
        id = "x", name = "x", description = "x",
        orientation = LT.Orientation.Any,
        slots = Vector.empty
      )
      LT.slotsAsPromptLine(empty) shouldBe ""
    }

    "omit prominence parens when not set" in {
      val t = LT.Template(
        id = "x", name = "x", description = "x",
        orientation = LT.Orientation.Any,
        slots = Vector(LT.Slot(LT.SlotRole.Headline, LT.SlotRegion.Center, None))
      )
      LT.slotsAsPromptLine(t) shouldBe "Layout intent: headline in center."
    }
  }

  "wire encoders" should {
    "produce kebab-case region strings matching the TS copy" in {
      LT.regionToWire(LT.SlotRegion.TopLeft) shouldBe "top-left"
      LT.regionToWire(LT.SlotRegion.BottomRight) shouldBe "bottom-right"
      LT.regionToWire(LT.SlotRegion.Center) shouldBe "center"
    }
    "produce lowercase role strings" in {
      LT.roleToWire(LT.SlotRole.Subheadline) shouldBe "subheadline"
      LT.roleToWire(LT.SlotRole.Cta) shouldBe "cta"
    }
    "produce lowercase orientation strings" in {
      LT.orientationToWire(LT.Orientation.Landscape) shouldBe "landscape"
      LT.orientationToWire(LT.Orientation.Any) shouldBe "any"
    }
  }
}
