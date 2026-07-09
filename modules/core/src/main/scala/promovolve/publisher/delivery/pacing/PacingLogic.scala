package promovolve.publisher.delivery.pacing

import promovolve.CampaignId
import promovolve.publisher.CandidateView
import promovolve.publisher.delivery.Protocol.CachedSpendInfo
import promovolve.publisher.delivery.{ AdaptivePacing, FixedThrottlePacing, PacingStrategy }
import promovolve.publisher.SiteEntity

import java.time.Instant

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
}
