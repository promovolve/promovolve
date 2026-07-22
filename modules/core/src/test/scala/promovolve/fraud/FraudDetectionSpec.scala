package promovolve.fraud

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import FraudDetection.*

class FraudDetectionSpec extends AnyWordSpec with Matchers {

  private val cfg = Config()

  "robust statistics" should {
    "compute median for odd and even series" in {
      median(Vector(3, 1, 2)) shouldBe Some(2.0)
      median(Vector(4, 1, 2, 3)) shouldBe Some(2.5)
      median(Vector.empty) shouldBe None
    }
    "compute a scaled MAD" in {
      // deviations from median 2 are {1,0,1} → MAD median 1 ×1.4826
      mad(Vector(1, 2, 3)).get shouldBe 1.4826 +- 1e-6
    }
    "withhold a z when the baseline has zero spread" in {
      robustZ(100, Vector.fill(6)(5.0), minLen = 5) shouldBe None
    }
    "withhold a z when history is too short" in {
      robustZ(100, Vector(1, 2), minLen = 5) shouldBe None
    }
  }

  private def steady(imp: Long, clk: Long, pv: Long): SiteDay =
    SiteDay(impressions = imp, clicks = clk, pageviews = pv, suspect = 0, total = imp + clk)

  "evaluate" should {

    "stay silent on a healthy site" in {
      val hist = Vector.fill(10)(steady(1000, 20, 400))
      evaluate(SiteMetrics("s", java.time.LocalDate.of(2026,7,22), steady(1050, 21, 420), hist), cfg) shouldBe empty
    }

    "flag a high suspect share" in {
      val day = SiteDay(impressions = 1000, clicks = 20, pageviews = 400, suspect = 500, total = 1520)
      val flags = evaluate(SiteMetrics("s", java.time.LocalDate.of(2026,7,22), day, Vector.empty), cfg)
      flags.map(_.signal) should contain(SigSuspectShare)
    }

    "NOT flag suspect share below the volume floor" in {
      val day = SiteDay(impressions = 100, clicks = 2, pageviews = 40, suspect = 90, total = 102)
      evaluate(SiteMetrics("s", java.time.LocalDate.of(2026,7,22), day, Vector.empty), cfg).map(_.signal) should not contain SigSuspectShare
    }

    "flag an impressions-per-pageview spike vs the site's own history" in {
      // History: ~2.5 imp/pageview, rock steady. Latest: 25 imp/pageview.
      val hist = Vector.tabulate(10)(i => steady(1000 + i, 20, 400))
      val spike = steady(10000, 20, 400) // 25 imp/pv
      val flags = evaluate(SiteMetrics("s", java.time.LocalDate.of(2026,7,22), spike, hist), cfg)
      flags.map(_.signal) should contain(SigImpPerPageview)
      flags.find(_.signal == SigImpPerPageview).get.severity should be > cfg.zThreshold
    }

    "flag a CTR spike vs the site's own history" in {
      val hist = Vector.tabulate(10)(i => steady(1000, 20 + (i % 3), 400)) // ~2% CTR
      val spike = steady(1000, 400, 400) // 40% CTR
      evaluate(SiteMetrics("s", java.time.LocalDate.of(2026,7,22), spike, hist), cfg).map(_.signal) should contain(SigCtrSpike)
    }

    "NOT flag a DROP in impressions/pageview (one-directional)" in {
      val hist = Vector.fill(10)(steady(10000, 20, 400)) // high imp/pv
      val drop = steady(400, 20, 400) // 1 imp/pv — lower, not fraud
      evaluate(SiteMetrics("s", java.time.LocalDate.of(2026,7,22), drop, hist), cfg).map(_.signal) should not contain SigImpPerPageview
    }

    "NOT flag a spike when history is too short to trust" in {
      val hist = Vector(steady(1000, 20, 400), steady(1000, 20, 400)) // 2 days < minHistory
      val spike = steady(10000, 20, 400)
      evaluate(SiteMetrics("s", java.time.LocalDate.of(2026,7,22), spike, hist), cfg).map(_.signal) should not contain SigImpPerPageview
    }
  }

  "assembleMetrics" should {
    import java.time.LocalDate
    val d1 = LocalDate.of(2026, 7, 20)
    val d2 = LocalDate.of(2026, 7, 21)
    val d3 = LocalDate.of(2026, 7, 22)

    "pick the newest day as latest and join pageviews" in {
      val events = Vector(
        EventDay("s", d1, 1000, 20, 0, 1020),
        EventDay("s", d3, 3000, 60, 5, 3065),
        EventDay("s", d2, 2000, 40, 0, 2040)
      )
      val pvs = Map(("s", d3) -> 900L, ("s", d1) -> 400L)
      val m = assembleMetrics(events, pvs).find(_.siteId == "s").get
      m.latestDay shouldBe d3
      m.latest.impressions shouldBe 3000
      m.latest.pageviews shouldBe 900
      m.history.map(_.impressions) shouldBe Vector(2000, 1000) // newest-first
      m.history.head.pageviews shouldBe 0 // d2 had no beacon rows
      m.history(1).pageviews shouldBe 400
    }

    "keep sites separate" in {
      val events = Vector(EventDay("a", d1, 1, 0, 0, 1), EventDay("b", d1, 2, 0, 0, 2))
      assembleMetrics(events, Map.empty).map(_.siteId).toSet shouldBe Set("a", "b")
    }
  }
}
