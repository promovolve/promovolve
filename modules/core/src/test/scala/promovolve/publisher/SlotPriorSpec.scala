package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.CPM
import promovolve.publisher.SiteEntity.{ effectiveFloor, AdSlotConfig, SlotPrior }

/**
 * Validates per-slot floor pricing math: the crawler-derived prior
 * (`SlotPrior.computeQualityScore`) must rank slots in the order
 * we'd expect (article > sidebar > footer), and `effectiveFloor`
 * must spread the site floor proportionally.
 */
class SlotPriorSpec extends AnyWordSpec with Matchers {

  private val siteFloor: CPM = CPM(1.00)

  // Inputs lifted from the TestExtractSlotsApp fixture run — these
  // are the actual numbers a fresh Playwright crawl produced, so this
  // also pins down the crawler→prior pipeline.
  private val hdr =
    SlotPrior.computeQualityScore(aboveFold = true, initialViewability = 1.00, region = "header", textDensity = 0.73)
  private val article =
    SlotPrior.computeQualityScore(aboveFold = true, initialViewability = 1.00, region = "article", textDensity = 0.71)
  private val sidebar =
    SlotPrior.computeQualityScore(aboveFold = true, initialViewability = 0.88, region = "aside", textDensity = 0.71)
  private val footer =
    SlotPrior.computeQualityScore(aboveFold = false, initialViewability = 0.00, region = "footer", textDensity = 0.73)

  "SlotPrior.computeQualityScore" should {
    "rank in-article highest, then sidebar header, then footer" in {
      article should be > sidebar
      sidebar should be > footer
    }

    "produce scores in [0,1]" in {
      Seq(hdr, article, sidebar, footer).foreach { s =>
        s should be >= 0.0
        s should be <= 1.0
      }
    }
  }

  "effectiveFloor" should {
    "fall through to site floor when no prior and no override" in {
      val slot = AdSlotConfig("s1", 300, 250)
      effectiveFloor(slot, siteFloor).toDouble shouldBe siteFloor.toDouble +- 1e-9
    }

    "honour an explicit override regardless of prior" in {
      val slot = AdSlotConfig(
        "s1", 300, 250,
        prior = Some(SlotPrior(qualityScore = 0.9, aboveFold = true, region = "article")),
        floorOverride = Some(CPM(2.50))
      )
      effectiveFloor(slot, siteFloor).toDouble shouldBe 2.50 +- 1e-9
    }

    "scale site floor by 0.5..1.0x based on quality score (discount-only)" in {
      val low = AdSlotConfig("low", 728, 90, prior = Some(SlotPrior(0.0, aboveFold = false, region = "footer")))
      val high = AdSlotConfig("high", 300, 250, prior = Some(SlotPrior(1.0, aboveFold = true, region = "article")))
      effectiveFloor(low, siteFloor).toDouble shouldBe 0.50 +- 1e-9
      // Capped at 1.0x — a premium slot never surcharges the floor. The old
      // 1.5x ceiling let the EFFECTIVE floor exceed every observed bid the
      // learned floor was derived from (floor=bid ratchet × 1.275 priced out
      // the entire field, live 2026-07-24).
      effectiveFloor(high, siteFloor).toDouble shouldBe 1.00 +- 1e-9
    }

    "never lift the effective floor above the raw floor (the 2026-07-24 regression)" in {
      // Live numbers from the outage: category floor $8.00, slot quality
      // 0.775 → old multiplier 1.275 asked bidders for $10.20 and rejected
      // the $10.00 field. Now effective must stay ≤ raw.
      val slot = AdSlotConfig("s", 300, 250, prior = Some(SlotPrior(0.775, aboveFold = true, region = "content")))
      effectiveFloor(slot, CPM(8.0)).toDouble shouldBe 8.0 +- 1e-9
    }

    "rank slots by floor with premium slots tying at the raw floor (fixture run)" in {
      def floor(qs: Double) = effectiveFloor(
        AdSlotConfig("s", 300, 250, prior = Some(SlotPrior(qs, aboveFold = true, region = "x"))),
        siteFloor
      ).toDouble

      val articleFloor = floor(article)
      val sidebarFloor = floor(sidebar)
      val footerFloor = floor(footer)

      // Discount-only pricing: premium slots (quality ≥ 0.5) all cap at the
      // raw floor — they no longer out-rank each other via surcharge — while
      // weak slots still discount below it. Ordering is preserved weakly.
      articleFloor shouldBe siteFloor.toDouble +- 1e-9
      articleFloor should be >= sidebarFloor
      sidebarFloor should be > footerFloor
      // Spread should be meaningful — top vs bottom > 30c on a $1 floor.
      (articleFloor - footerFloor) should be > 0.30
    }
  }
}
