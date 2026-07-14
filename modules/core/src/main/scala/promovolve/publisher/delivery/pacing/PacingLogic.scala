package promovolve.publisher.delivery.pacing

import promovolve.CampaignId
import promovolve.common.Timezones
import promovolve.publisher.CandidateView
import promovolve.publisher.delivery.Protocol.CachedSpendInfo
import promovolve.publisher.delivery.{ AdaptivePacing, FixedThrottlePacing, PacingStrategy, TrafficShapeTracker }
import promovolve.publisher.SiteEntity

import java.time.{ Instant, ZoneOffset }

/**
 * Pure helper functions for pacing computations.
 *
 * Contains stateless logic extracted from AdServer for testability
 * and reuse. All functions are pure - no side effects.
 */
object PacingLogic {

  /**
   * Compute average CPM per campaign from candidates.
   *
   * Groups candidates by campaign and calculates the mean CPM for each.
   * Used for weighted budget calculations in aggregate pacing.
   *
   * @param candidates  All candidates in the selection pool
   * @param defaultCpm  Default CPM if a campaign has no valid CPMs (default 5.0)
   * @return Map from campaign ID to average CPM
   */
  def computeCpmByCampaign(
      candidates: Vector[CandidateView],
      defaultCpm: Double = 5.0
  ): Map[CampaignId, Double] =
    candidates.groupBy(_.campaignId).view.mapValues { cands =>
      val cpms = cands.map(_.cpm.toDouble)
      if (cpms.nonEmpty) cpms.sum / cpms.size else defaultCpm
    }.toMap

  /**
   * Compute aggregate budget metrics for pacing decisions.
   *
   * Calculates total daily budget, total spend (including pending),
   * and CPM-weighted average for the pool of campaigns.
   *
   * The weighted average CPM accounts for the fact that higher-CPM campaigns
   * will deliver fewer impressions per dollar of budget.
   *
   * @param validInfos    Sequence of (campaignId, spendInfo) pairs with valid data
   * @param cpmByCampaign Map of average CPM per campaign
   * @param pendingSpend  Map of uncommitted spend per campaign
   * @return Tuple of (totalDailyBudget, totalTodaySpend, weightedAvgCpm)
   */
  def computeAggregateBudget(
      validInfos: Seq[(CampaignId, CachedSpendInfo)],
      cpmByCampaign: Map[CampaignId, Double],
      pendingSpend: Map[CampaignId, (Double, java.time.Instant)]
  ): (BigDecimal, BigDecimal, Double) = {
    val totalDailyBudget = validInfos.map(_._2.dailyBudget.value).sum
    val totalTodaySpend = validInfos.map { case (campId, info) =>
      info.todaySpend.value + BigDecimal(pendingSpend.get(campId).map(_._1).getOrElse(0.0))
    }.sum

    // CPM-weighted expected spend calculation
    val avgCpm = computeWeightedAvgCpm(validInfos, cpmByCampaign, totalDailyBudget)
    (totalDailyBudget, totalTodaySpend, avgCpm)
  }

  /**
   * Compute CPM-weighted average across campaigns.
   *
   * This weights each campaign's CPM by its expected impression share,
   * giving a more accurate aggregate CPM for pacing calculations.
   *
   * @param validInfos       Campaign spend info
   * @param cpmByCampaign    CPM per campaign
   * @param totalDailyBudget Total budget (pre-computed for efficiency)
   * @return Weighted average CPM
   */
  def computeWeightedAvgCpm(
      validInfos: Seq[(CampaignId, CachedSpendInfo)],
      cpmByCampaign: Map[CampaignId, Double],
      totalDailyBudget: BigDecimal
  ): Double = {
    val totalExpectedImpressions = validInfos.map { case (campId, info) =>
      val cpm = cpmByCampaign.getOrElse(campId, 5.0)
      if (cpm > 0) (info.dailyBudget.value.toDouble / cpm) * 1000.0 else 0.0
    }.sum

    if (totalExpectedImpressions > 0 && totalDailyBudget > 0)
      totalDailyBudget.toDouble / totalExpectedImpressions * 1000.0
    else {
      val cpms = validInfos.map { case (campId, _) => cpmByCampaign.getOrElse(campId, 5.0) }
      if (cpms.nonEmpty) cpms.sum / cpms.size else 5.0
    }
  }

  /**
   * Convert SiteEntity pacing config to PacingStrategy.
   *
   * Handles three cases:
   * 1. Test override: Fixed throttle probability for testing
   * 2. Shape-aware: Uses configured hourly volume shapes
   * 3. Default: Adaptive pacing with default parameters
   *
   * @param config Pacing configuration from SiteEntity
   * @return Appropriate PacingStrategy instance
   */
  def strategyFromConfig(config: SiteEntity.PacingConfig): PacingStrategy =
    config.testThrottleOverride match {
      case Some(fixedProb) =>
        // For testing: use fixed throttle probability
        FixedThrottlePacing(fixedProb)
      case None =>
        // Normal operation: adaptive pacing (traffic shape is always learned)
        AdaptivePacing()
    }

  /**
   * Calculate spend ratio (spend / budget).
   *
   * Returns 0.0 if budget is zero to avoid division by zero.
   *
   * @param totalSpend  Total spend so far
   * @param totalBudget Total daily budget
   * @return Spend ratio between 0.0 and potentially > 1.0 if overspent
   */
  def spendRatio(totalSpend: BigDecimal, totalBudget: BigDecimal): Double =
    if (totalBudget > 0) (totalSpend / totalBudget).toDouble else 0.0

  /**
   * Check if spend ratio exceeds a threshold.
   *
   * Useful for early-out checks before computing full pacing.
   *
   * @param spendRatio Current spend / budget ratio
   * @param threshold  Maximum acceptable ratio (default 1.0)
   * @return true if spend exceeds threshold
   */
  def isOverBudget(spendRatio: Double, threshold: Double = 1.0): Boolean =
    spendRatio >= threshold

  /**
   * Calculate seconds elapsed in the pacing day.
   *
   * @param dayStart Start of the pacing day
   * @param now      Current time
   * @return Seconds elapsed since day start
   */
  def elapsedSeconds(dayStart: Instant, now: Instant): Double =
    java.time.Duration.between(dayStart, now).toMillis / 1000.0

  // ═══════════════════════════════════════════════════════════════════════
  // ADVERTISER-TIMEZONE BUDGET WINDOWS (real calendar days only)
  //
  // The traffic shape stays a UTC hour-of-day curve (it's the SITE's
  // observed volume — a global-audience site has no single zone). Each
  // campaign's budget window is [dayStart, next advertiser-zone midnight),
  // which for a non-UTC advertiser WRAPS the UTC day boundary, so expected
  // spend integrates the UTC curve across that wrap.
  // ═══════════════════════════════════════════════════════════════════════

  /** End of a campaign's budget window: next advertiser-zone midnight after dayStart. */
  def windowEndFor(dayStart: Instant, timezone: String): Instant =
    Timezones.nextMidnightAfter(dayStart, timezone)

  /** Seconds into the UTC day of `instant` (traffic-shape bucket coordinate). */
  def secOfUtcDay(instant: Instant): Double =
    instant.atZone(ZoneOffset.UTC).toLocalTime.toSecondOfDay.toDouble

  /**
   * Traffic-shape mass over a possibly-midnight-wrapping interval of the UTC
   * day. `fullDay` short-circuits to 1.0 — needed because an interval
   * spanning the whole 24h cycle has fromSec == toSec, which the wrap
   * formula would misread as an empty interval.
   */
  def wrappedMass(
      tracker: TrafficShapeTracker,
      fromSec: Double,
      toSec: Double,
      fullDay: Boolean
  ): Double =
    if (fullDay) 1.0
    else if (toSec >= fromSec)
      tracker.cumulativeFractionAtTime(toSec) - tracker.cumulativeFractionAtTime(fromSec)
    else
      (1.0 - tracker.cumulativeFractionAtTime(fromSec)) + tracker.cumulativeFractionAtTime(toSec)

  /**
   * Expected fraction of a campaign's budget spent by `now`, integrating the
   * UTC traffic shape over the campaign's budget window.
   *
   * For a UTC advertiser this is algebraically identical to the legacy
   * remaining-hours formula in [[promovolve.publisher.delivery.PacingContext]]
   * (windowEnd at UTC midnight makes the denominator `1 - CDF(start)`).
   *
   * Known accepted approximations (bounded, documented):
   *   - a wrapped window can straddle a weekday/weekend shape flip; the
   *     tracker's currentShape is single-day-type.
   *   - on a DST-change day a zone's "day" is 23h/25h; the fullDay guard and
   *     second-of-day coordinates treat it as 24h, mis-weighting at most the
   *     lapped hour once or twice a year.
   */
  def expectedWindowFraction(
      tracker: TrafficShapeTracker,
      dayStart: Instant,
      windowEnd: Instant,
      now: Instant
  ): Double =
    if (!now.isBefore(windowEnd)) 1.0
    else if (!now.isAfter(dayStart)) 0.0
    else {
      // dayStart at (or within seconds of) the zone midnight ⇒ the window is
      // the full 24h cycle and from/to coincide — mass is 1, not 0.
      val fullDay = java.time.Duration.between(dayStart, windowEnd).getSeconds >= 86395L
      val fromSec = secOfUtcDay(dayStart)
      val denom = wrappedMass(tracker, fromSec, secOfUtcDay(windowEnd), fullDay)
      if (denom < 0.001) {
        // Degenerate: near-zero shape mass in the window — fall back to
        // linear-in-window (zone-safe by construction).
        val total = java.time.Duration.between(dayStart, windowEnd).toMillis.toDouble
        if (total <= 0) 1.0
        else math.min(1.0, java.time.Duration.between(dayStart, now).toMillis.toDouble / total)
      } else
        math.min(1.0, wrappedMass(tracker, fromSec, secOfUtcDay(now), fullDay = false) / denom)
    }

  /**
   * Aggregate expected spend across campaigns whose budget windows may sit in
   * DIFFERENT advertiser zones, plus the latest window end.
   *
   * Replaces the single-dayStart `dailyBudget × expectedSpendFraction`: with
   * mixed zones there is no one day window, so each campaign contributes its
   * own `budget × expectedWindowFraction`, and the pacing hard-stop horizon
   * is the LAST window end (the site must not hard-stop while any campaign
   * still has budget-day left).
   *
   * @return (expected aggregate spend, max window end across campaigns)
   */
  def computeAggregateExpectedSpend(
      validInfos: Seq[(CampaignId, CachedSpendInfo)],
      tracker: TrafficShapeTracker,
      now: Instant
  ): (BigDecimal, Instant) = {
    var expected = BigDecimal(0)
    var maxWindowEnd = now
    validInfos.foreach { case (_, info) =>
      val windowEnd = windowEndFor(info.dayStart, info.timezone)
      expected += info.dailyBudget.value *
      BigDecimal(expectedWindowFraction(tracker, info.dayStart, windowEnd, now))
      if (windowEnd.isAfter(maxWindowEnd)) maxWindowEnd = windowEnd
    }
    (expected, maxWindowEnd)
  }
}
