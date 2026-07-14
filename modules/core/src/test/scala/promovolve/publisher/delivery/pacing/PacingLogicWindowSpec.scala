package promovolve.publisher.delivery.pacing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import promovolve.{ AdvertiserId, Budget, CampaignId, Spend }
import promovolve.publisher.delivery.Protocol.CachedSpendInfo
import promovolve.publisher.delivery.TrafficShapeTracker

import java.time.Instant

/**
 * Pure tests for the advertiser-timezone budget-window helpers in
 * [[PacingLogic]]: window ends at the advertiser zone's next midnight, so a
 * non-UTC window WRAPS the UTC day boundary and expected spend integrates the
 * (UTC hour-of-day) traffic shape across that wrap. No actors involved.
 */
class PacingLogicWindowSpec extends AnyFlatSpec with Matchers {

  private val Tolerance = 1e-9

  /** Fresh tracker: buckets initialize to 1.0 each, so the CDF is linear. */
  private def uniformTracker: TrafficShapeTracker = TrafficShapeTracker()

  /** Deterministic non-uniform shape (restore bypasses EMA learning). */
  private def skewedTracker: TrafficShapeTracker = {
    val t = new TrafficShapeTracker(bucketCount = 24, alpha = 1.0)
    t.restore(Array.tabulate(24)(i => 0.25 + (i % 7).toDouble))
    t
  }

  /** The legacy UTC-day formula from PacingContext.expectedSpendFraction. */
  private def legacyFraction(tracker: TrafficShapeTracker, startSec: Double, nowSec: Double): Double = {
    val startCdf = tracker.cumulativeFractionAtTime(startSec)
    val currentCdf = tracker.cumulativeFractionAtTime(nowSec)
    val remaining = 1.0 - startCdf
    if (remaining > 0.001) (currentCdf - startCdf) / remaining else 0.0
  }

  // ==================== secOfUtcDay ====================

  "secOfUtcDay" should "return seconds into the UTC day" in {
    PacingLogic.secOfUtcDay(Instant.parse("2026-07-13T00:00:00Z")) shouldBe 0.0
    PacingLogic.secOfUtcDay(Instant.parse("2026-07-13T01:00:00Z")) shouldBe 3600.0
    PacingLogic.secOfUtcDay(Instant.parse("2026-07-13T12:30:15Z")) shouldBe (12 * 3600 + 30 * 60 + 15).toDouble
    PacingLogic.secOfUtcDay(Instant.parse("2026-07-13T23:59:59Z")) shouldBe 86399.0
  }

  // ==================== windowEndFor ====================

  "windowEndFor" should "end a UTC window at the next UTC midnight" in {
    PacingLogic.windowEndFor(Instant.parse("2026-07-13T11:00:00Z"), "") shouldBe
      Instant.parse("2026-07-14T00:00:00Z")
  }

  it should "end a JST window at the next JST midnight (15:00Z)" in {
    // 2026-07-13T15:00Z == 2026-07-14T00:00 JST, so the next JST midnight is
    // exactly 24h later.
    PacingLogic.windowEndFor(Instant.parse("2026-07-13T15:00:00Z"), "Asia/Tokyo") shouldBe
      Instant.parse("2026-07-14T15:00:00Z")
    PacingLogic.windowEndFor(Instant.parse("2026-07-13T16:30:00Z"), "Asia/Tokyo") shouldBe
      Instant.parse("2026-07-14T15:00:00Z")
  }

  // ==================== wrappedMass ====================

  "wrappedMass" should "reduce to a plain CDF difference when the interval does not wrap" in {
    val t = new TrafficShapeTracker(bucketCount = 4, alpha = 1.0)
    t.restore(Array(1.0, 2.0, 3.0, 4.0)) // CDF at bucket starts: [0, 0.1, 0.3, 0.6]
    PacingLogic.wrappedMass(t, 21600.0, 64800.0, fullDay = false) shouldBe 0.5 +- Tolerance
  }

  it should "compute (1 - CDF(from)) + CDF(to) on the wrap branch" in {
    val t = new TrafficShapeTracker(bucketCount = 4, alpha = 1.0)
    t.restore(Array(1.0, 2.0, 3.0, 4.0))
    // from = start of bucket 3 (CDF 0.6), to = start of bucket 1 (CDF 0.1)
    PacingLogic.wrappedMass(t, 64800.0, 21600.0, fullDay = false) shouldBe (0.4 + 0.1) +- Tolerance
  }

  it should "short-circuit to 1.0 for a full-day interval (from == to would misread as empty)" in {
    PacingLogic.wrappedMass(uniformTracker, 54000.0, 54000.0, fullDay = true) shouldBe 1.0
    PacingLogic.wrappedMass(skewedTracker, 0.0, 0.0, fullDay = true) shouldBe 1.0
  }

  // ==================== expectedWindowFraction: UTC equivalence ====================

  "expectedWindowFraction" should "match the legacy remaining-hours formula for a UTC advertiser (uniform shape)" in {
    val tracker = uniformTracker
    val dayStart = Instant.parse("2024-06-01T11:00:00Z")
    val windowEnd = PacingLogic.windowEndFor(dayStart, "")
    val startSec = PacingLogic.secOfUtcDay(dayStart)
    for (h <- 1 to 12) {
      val now = dayStart.plusSeconds(h * 3600L)
      val expected = legacyFraction(tracker, startSec, PacingLogic.secOfUtcDay(now))
      PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, now) shouldBe expected +- Tolerance
    }
  }

  it should "match the legacy remaining-hours formula for a UTC advertiser (skewed shape)" in {
    val tracker = skewedTracker
    val dayStart = Instant.parse("2024-06-01T11:00:00Z")
    val windowEnd = PacingLogic.windowEndFor(dayStart, "")
    val startSec = PacingLogic.secOfUtcDay(dayStart)
    for (h <- 1 to 12) {
      val now = dayStart.plusSeconds(h * 3600L)
      val expected = legacyFraction(tracker, startSec, PacingLogic.secOfUtcDay(now))
      PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, now) shouldBe expected +- Tolerance
    }
  }

  // ==================== expectedWindowFraction: wrapped JST window ====================

  it should "integrate the shape across the UTC midnight wrap for a JST window" in {
    val tracker = uniformTracker
    val dayStart = Instant.parse("2026-07-13T15:00:00Z") // JST midnight
    val windowEnd = PacingLogic.windowEndFor(dayStart, "Asia/Tokyo")
    windowEnd shouldBe Instant.parse("2026-07-14T15:00:00Z")

    // 0.0 exactly at the window start.
    PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, dayStart) shouldBe 0.0

    // Monotonically nondecreasing hour by hour across the UTC midnight.
    val fractions = (0 to 24).map { h =>
      PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, dayStart.plusSeconds(h * 3600L))
    }
    fractions.sliding(2).foreach { case Seq(a, b) => b should be >= a }

    // Uniform shape: halfway through the (wrapped) window is 0.5.
    PacingLogic.expectedWindowFraction(
      tracker, dayStart, windowEnd, dayStart.plusSeconds(12 * 3600L)
    ) shouldBe 0.5 +- Tolerance

    // 1.0 at and after the window end.
    PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, windowEnd) shouldBe 1.0
    PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, windowEnd.plusSeconds(3600)) shouldBe 1.0
  }

  it should "treat a window starting exactly at zone midnight as a full day (no 0/NaN denominator)" in {
    val tracker = uniformTracker
    val dayStart = Instant.parse("2026-07-13T15:00:00Z") // exactly JST midnight
    val windowEnd = PacingLogic.windowEndFor(dayStart, "Asia/Tokyo")
    // from == to in shape coordinates; the fullDay guard must make the
    // denominator 1.0 rather than the empty-interval reading (0 → NaN/∞).
    val f = PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, dayStart.plusSeconds(6 * 3600L))
    f.isNaN shouldBe false
    f shouldBe 0.25 +- Tolerance
  }

  it should "clamp to 0.0 before the window and 1.0 after it" in {
    val tracker = skewedTracker
    val dayStart = Instant.parse("2026-07-13T15:00:00Z")
    val windowEnd = PacingLogic.windowEndFor(dayStart, "Asia/Tokyo")
    PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, dayStart.minusSeconds(3600)) shouldBe 0.0
    PacingLogic.expectedWindowFraction(tracker, dayStart, windowEnd, windowEnd.plusSeconds(1)) shouldBe 1.0
  }

  // ==================== computeAggregateExpectedSpend ====================

  private def spendInfo(dayStart: Instant, budget: Double, timezone: String): CachedSpendInfo =
    CachedSpendInfo(
      advertiserId = AdvertiserId("adv-1"),
      dailyBudget = Budget(budget),
      todaySpend = Spend.zero,
      dayStart = dayStart,
      timestamp = dayStart,
      timezone = timezone
    )

  "computeAggregateExpectedSpend" should "sum per-window expectations for mixed zones and return the latest window end" in {
    val tracker = uniformTracker
    val utcStart = Instant.parse("2026-07-13T00:00:00Z") // UTC midnight → full-day window
    val jstStart = Instant.parse("2026-07-13T15:00:00Z") // JST midnight → wrapped full-day window
    val now = Instant.parse("2026-07-13T18:00:00Z")

    val infos = Seq(
      CampaignId("c-utc") -> spendInfo(utcStart, budget = 100.0, timezone = ""),
      CampaignId("c-jst") -> spendInfo(jstStart, budget = 50.0, timezone = "Asia/Tokyo")
    )

    val (expected, maxWindowEnd) = PacingLogic.computeAggregateExpectedSpend(infos, tracker, now)

    // UTC campaign: 18h of a uniform 24h window → 0.75 × 100 = 75.
    // JST campaign: 3h of a uniform 24h window → 0.125 × 50 = 6.25.
    expected.toDouble shouldBe 81.25 +- Tolerance
    // The pacing hard-stop horizon is the LAST window end.
    maxWindowEnd shouldBe Instant.parse("2026-07-14T15:00:00Z")
  }

  it should "count a campaign whose window has ended at its full budget" in {
    val tracker = uniformTracker
    val utcStart = Instant.parse("2026-07-13T00:00:00Z")
    val now = Instant.parse("2026-07-14T02:00:00Z") // past the UTC window end

    val infos = Seq(CampaignId("c-utc") -> spendInfo(utcStart, budget = 40.0, timezone = ""))
    val (expected, maxWindowEnd) = PacingLogic.computeAggregateExpectedSpend(infos, tracker, now)

    expected.toDouble shouldBe 40.0 +- Tolerance
    // Window end is in the past, so `now` is the max horizon.
    maxWindowEnd shouldBe now
  }

  it should "expect zero spend for a window that has not started" in {
    val tracker = uniformTracker
    val jstStart = Instant.parse("2026-07-13T15:00:00Z")
    val now = Instant.parse("2026-07-13T14:00:00Z") // before the window opens

    val infos = Seq(CampaignId("c-jst") -> spendInfo(jstStart, budget = 50.0, timezone = "Asia/Tokyo"))
    val (expected, maxWindowEnd) = PacingLogic.computeAggregateExpectedSpend(infos, tracker, now)

    expected.toDouble shouldBe 0.0
    maxWindowEnd shouldBe Instant.parse("2026-07-14T15:00:00Z")
  }
}
