package promovolve.publisher.delivery

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class AdaptivePacingSpec extends AnyFlatSpec with Matchers {

  def ctx(
      hour: Int,
      spend: Double,
      budget: Double = 100.0,
      requestRate: Double = 0.0,
      requestCount: Long = 100L, // Default: past initial grace period (needs >= 50)
      msSinceLastRequest: Long = 100L // Default: not stale (stale threshold is 30000ms)
  ): PacingContext = {
    val dayStart = Instant.parse("2024-01-01T00:00:00Z")
    val now = dayStart.plusSeconds(hour * 3600L)
    PacingContext(
      dailyBudget = BigDecimal(budget),
      todaySpend = BigDecimal(spend),
      dayStart = dayStart,
      now = now,
      requestArrivalRate = requestRate,
      requestCount = requestCount,
      msSinceLastRequest = msSinceLastRequest
    )
  }

  "RateAwarePacing" should "not throttle when underspending (no rate pressure)" in {
    val strategy = AdaptivePacing()

    // At hour 12 (50% of day), expected spend ≈ 50% of budget
    // If we've only spent 30%, no throttle (when no rate pressure)
    strategy.throttleProbability(ctx(hour = 12, spend = 30)) shouldBe 0.0
  }

  it should "throttle via ratio when overspending" in {
    val strategy = AdaptivePacing()

    // At hour 6 (25% of day), expected spend = 25% = $25
    // If we've spent $50 → spendRatio = 50/25 = 2.0 → ratioThrottle = 1.0
    val prob = strategy.throttleProbability(ctx(hour = 6, spend = 50))
    prob should be > 0.0
  }

  it should "throttle via rate when high traffic arrives" in {
    val strategy = AdaptivePacing()

    // Even when on-pace (spendRatio ≈ 1.0), high traffic should throttle
    // At hour 12 with budget=$100 and avgCpm=$5:
    //   targetImps = ($100/86400) / ($5/1000) ≈ 0.23/sec
    // With 100 req/sec arriving: rawRateThrottle = 1 - 0.23/100 ≈ 99.7%
    val prob = strategy.throttleProbability(ctx(hour = 12, spend = 50, requestRate = 100))
    prob should be > 0.9
  }

  it should "reduce throttle when under-paced (after grace period)" in {
    val strategy = AdaptivePacing()

    // At hour 6 (25% of day), spent only 10% → spendRatio = 10/25 = 0.4
    // PI controller should reduce throttle to allow catch-up
    // With 1000 req/sec and small budget, base throttle is very high (~99%)
    // PI adjustment (error = 0.6) reduces it significantly
    val prob = strategy.throttleProbability(ctx(hour = 6, spend = 10, requestRate = 1000))
    // Base throttle ~99%, but PI should reduce it significantly
    prob should be < 0.8 // Throttle reduced to allow catch-up
    prob should be > 0.5 // But still some throttle due to high traffic
  }

  it should "apply burst protection during grace period (high traffic)" in {
    val strategy = AdaptivePacing()

    // During grace the PI integral is disabled, but we still apply the rate
    // cap (baseThrottle) so a cold-start flood cannot burn the budget before
    // the controller warms up. At 1000 req/s against a ~0.23 imps/s target,
    // baseThrottle ≈ 99.98%.
    val dayStart = Instant.parse("2024-01-01T00:00:00Z")
    val fiveSecondsIn = dayStart.plusSeconds(5) // 5 seconds into day (within 10s grace)
    val graceCtx = PacingContext(
      dailyBudget = BigDecimal(100),
      todaySpend = BigDecimal(0),
      dayStart = dayStart,
      now = fiveSecondsIn,
      requestArrivalRate = 1000 // High traffic burst
    )

    val prob = strategy.throttleProbability(graceCtx)
    prob should be > 0.9 // Rate cap still throttles a flood during grace
  }

  it should "SERVE during grace period when traffic is sparse (no cold-start cliff)" in {
    val strategy = AdaptivePacing()

    // Regression guard: grace must NOT refuse to serve a cold/low-traffic site.
    // At near-zero spend we are maximally behind pace, and a sparse arrival
    // rate is well under the paced target, so baseThrottle is ~0 → we serve.
    // Previously grace returned MaxThrottleProb (0.99) here regardless of rate,
    // which meant a low-traffic publisher showed no ads until it warmed up.
    val dayStart = Instant.parse("2024-01-01T00:00:00Z")
    val fiveSecondsIn = dayStart.plusSeconds(5) // within the 10s grace window
    val sparseGraceCtx = PacingContext(
      dailyBudget = BigDecimal(100),
      todaySpend = BigDecimal(0),
      dayStart = dayStart,
      now = fiveSecondsIn,
      requestArrivalRate = 0.05, // sparse: ~1 request every 20s, below pace target
      requestCount = 1L // cold start, not yet past the request gate
    )

    val prob = strategy.throttleProbability(sparseGraceCtx)
    prob shouldBe 0.0 // serves freely during grace when there is no burst risk
  }

  it should "throttle proportionally to overspend" in {
    val strategy = AdaptivePacing()

    // Moderate overspend: at hour 12 (50%), expected = 50, spent = 70 → ratio = 1.4
    // ratioThrottle = (1.4 - 1.0) * 1.0 = 0.4
    val prob = strategy.throttleProbability(ctx(hour = 12, spend = 70))
    prob shouldBe 0.4 +- 0.05

    // More overspend → more throttle
    val higherProb = strategy.throttleProbability(ctx(hour = 12, spend = 80)) // ratio = 1.6
    higherProb should be > prob
  }

  it should "return 1.0 when budget exhausted" in {
    val strategy = AdaptivePacing()

    // When budget IS exhausted, we get 1.0 (hard stop)
    val exhaustedProb = strategy.throttleProbability(ctx(hour = 12, spend = 100, budget = 100))
    // remainingBudget = 0 → hard stop
    exhaustedProb shouldBe 1.0
  }

  it should "handle zero budget gracefully" in {
    val strategy = AdaptivePacing()
    // No budget means hard stop (1.0 throttle)
    strategy.throttleProbability(ctx(hour = 12, spend = 50, budget = 0)) shouldBe 1.0
  }

  it should "handle zero spend gracefully" in {
    val strategy = AdaptivePacing()
    strategy.throttleProbability(ctx(hour = 12, spend = 0)) shouldBe 0.0
  }

  "PacingContext" should "calculate elapsed hours correctly" in {
    val c = ctx(hour = 6, spend = 0)
    c.elapsedHours shouldBe 6.0 +- 0.01
  }

  it should "calculate remaining hours correctly" in {
    val c = ctx(hour = 6, spend = 0)
    c.remainingHours shouldBe 18.0 +- 0.01
  }

  it should "never fully zero the site from PI control (output tolerance)" in {
    // MaxThrottleProb's contract: a true 1.0 (serve NOTHING) is reserved for
    // hard stops (budget exhausted / day over). PI control under even a
    // wildly wrong feedback signal must leave the 1-in-100 trickle — that
    // difference is 'barely-noticeable dip' vs the 2026-07-24 total
    // blackout. Drive the controller with an absurd over-pace signal and
    // assert the cap holds.
    val strategy = AdaptivePacing()
    val wild = ctx(hour = 1, spend = 99.0).copy(expectedSpendOverride = Some(BigDecimal(1.0)))
    var p = 0.0
    (1 to 25).foreach(_ => p = strategy.throttleProbability(wild)) // let the integral wind
    p should be <= PacingStrategy.MaxThrottleProb
    p should be > 0.5 // it IS heavily throttled — just never a full stop
  }

  it should "read a trickle against a sleeping traffic shape as UNDER-paced (slack floor)" in {
    // Just after local midnight the traffic-shape expected spend can be
    // micro-dollars (the learned shape has ~no small-hours traffic). A few
    // cents of real spend divided by that produced six-figure ratios that
    // pinned the PI throttle at 100% — ads died at midnight nightly until
    // the shape accrued (live 2026-07-24). The denominator is floored at 1%
    // of daily budget, so cents of spend read as under-paced (serve), and
    // only spend approaching the slack throttles.
    val c = ctx(hour = 1, spend = 0.04).copy(expectedSpendOverride = Some(BigDecimal("0.00000008")))
    c.spendRatio shouldBe 0.04 +- 0.001 // 0.04 / max(8e-8, 1% of $100) = 0.04/1.00
    // Spend at the slack boundary reads as on-pace, not explosive.
    val atSlack = ctx(hour = 1, spend = 1.0).copy(expectedSpendOverride = Some(BigDecimal("0.00000008")))
    atSlack.spendRatio shouldBe 1.0 +- 0.001
    // No spend at all: at/under pace, never throttled.
    val quiet = ctx(hour = 1, spend = 0).copy(expectedSpendOverride = Some(BigDecimal("0.00000008")))
    quiet.spendRatio shouldBe 0.0 +- 0.001
  }

  it should "calculate spend ratio correctly" in {
    // At hour 12 (50%), spent $40 out of $100 budget
    // Expected = 100 * (12/24) = 50
    // spendRatio = 40 / 50 = 0.8
    val c = ctx(hour = 12, spend = 40)
    c.spendRatio shouldBe 0.8 +- 0.01
  }

  // ==================== Advertiser-timezone pacing (window override) ====================

  it should "use expectedSpendOverride for spendRatio when set" in {
    // Zone-aware pacing pre-computes the aggregate expected spend across
    // per-campaign windows (PacingLogic.computeAggregateExpectedSpend) and
    // injects it here; the fraction-based dailyBudget × expectedSpendFraction
    // must NOT be consulted.
    val c = ctx(hour = 6, spend = 30).copy(expectedSpendOverride = Some(BigDecimal(60)))
    c.expectedSpend shouldBe BigDecimal(60)
    // 30 / 60, not 30 / (100 × 6/24) = 1.2
    c.spendRatio shouldBe 0.5 +- 1e-9
  }

  it should "not hard-stop after dayStart+24h while windowEnd is still in the future" in {
    // A JST campaign's budget day can end up to 24h after the site's cached
    // dayStart+24h. remainingHours must follow windowEnd, so the day-over
    // hard stop only fires when EVERY campaign's budget day is over.
    val dayStart = Instant.parse("2024-01-01T00:00:00Z")
    val now = dayStart.plusSeconds(25 * 3600L) // dayStart + 24h already passed
    val base = PacingContext(
      dailyBudget = BigDecimal(100),
      todaySpend = BigDecimal(50),
      dayStart = dayStart,
      now = now,
      requestCount = 100L,
      msSinceLastRequest = 100L
    )

    // Identical context, no windowEnd: legacy day-over hard stop.
    base.remainingHours shouldBe 0.0
    AdaptivePacing().throttleProbability(base) shouldBe 1.0

    // Only difference: a windowEnd still 2h out.
    val zoned = base.copy(windowEnd = Some(now.plusSeconds(2 * 3600L)))
    zoned.remainingHours shouldBe 2.0 +- 0.01
    AdaptivePacing().throttleProbability(zoned) should not be 1.0
  }

  it should "hard-stop once windowEnd has passed" in {
    val dayStart = Instant.parse("2024-01-01T00:00:00Z")
    val now = dayStart.plusSeconds(6 * 3600L) // well inside the legacy day
    val c = PacingContext(
      dailyBudget = BigDecimal(100),
      todaySpend = BigDecimal(10),
      dayStart = dayStart,
      now = now,
      requestCount = 100L,
      msSinceLastRequest = 100L,
      windowEnd = Some(now.minusSeconds(3600L))
    )
    c.remainingHours shouldBe 0.0
    AdaptivePacing().throttleProbability(c) shouldBe 1.0
  }
}
