package promovolve.publisher.delivery

import java.time.{Duration, Instant}

/**
 * Context for pacing decisions - immutable snapshot of campaign state.
 *
 * == Traffic-Shaped Pacing ==
 *
 * When `trafficShape` is provided, expected spend follows the learned traffic pattern
 * instead of linear time. This prevents over-throttling during peak hours and
 * impossible targets during low-traffic periods.
 *
 * {{{
 * Linear (no trafficShape):
 * Hour:    0    6    12   18   24
 * Target:  $0  $7.5  $15  $22.5 $30  (straight line)
 *
 * Traffic-Shaped (with trafficShape):
 * Hour:    0    6    12   18   24
 * Target:  $1   $5   $18   $26  $30  (follows traffic CDF)
 * }}}
 *
 * @see [[TrafficShapeTracker]] for traffic pattern learning
 */
final case class PacingContext(
    dailyBudget: BigDecimal,
    todaySpend: BigDecimal,
    dayStart: Instant,
    now: Instant,
    requestArrivalRate: Double = 0.0,  // Observed requests/sec (tracked synchronously by AdServer)
    competingCampaigns: Int = 1,       // Number of campaigns sharing this traffic pool
    avgCpm: Double = 5.0,              // Average CPM for this campaign's creatives ($/1000 imps)
    dayDurationSeconds: Int = 86400,   // Simulated day length (default: 24h). Set lower for testing.
    trafficShape: Option[TrafficShapeTracker] = None,  // Traffic pattern for shaped pacing
    requestCount: Long = 0,            // Requests seen today (for initial grace period)
    msSinceLastRequest: Long = 0       // Milliseconds since last request (for staleness detection)
) {
  /**
   * Ratio of actual to expected spend.
   *
   *  - `spendRatio > 1.0`: ahead of target (over-pacing) → increase throttle
   *  - `spendRatio < 1.0`: behind target (under-pacing) → decrease throttle
   *  - `spendRatio = 1.0`: perfectly on pace
   *
   * When trafficShape is provided, "expected" follows the traffic pattern,
   * so being "on pace" means matching the traffic-weighted target, not linear time.
   */
  def spendRatio: Double = {
    val expected = expectedSpend
    if (expected <= 0) {
      // At day start when expectedSpend ≈ 0:
      // - If no spend yet, we're on pace (1.0)
      // - If there's spend but no expected, we're ahead (cap at 2.0 to avoid extreme values)
      if (todaySpend <= 0) 1.0 else 2.0
    } else {
      (todaySpend / expected).toDouble
    }
  }

  /** Expected spend based on traffic shape (or linear if unavailable) */
  def expectedSpend: BigDecimal = dailyBudget * expectedSpendFraction

  /**
   * Expected spend fraction based on traffic shape (or linear if unavailable).
   *
   * This is the key integration point for traffic-shaped pacing:
   *  - With trafficShape: returns CDF of learned traffic pattern
   *  - Without trafficShape: returns linear time fraction (elapsedHours / dayDurationHours)
   *
   * For real calendar days (86400), distributes budget over REMAINING hours only:
   *  - If test starts at hour 11, budget is paced over hours 11-23
   *  - Expected starts at 0% at test start, reaches 100% by end of day
   *
   * @return Value in [0, 1] representing expected fraction of daily budget spent by now
   */
  def expectedSpendFraction: Double = trafficShape match {
    case Some(tracker) =>
      if (dayDurationSeconds == 86400) {
        // Real calendar days: distribute budget over REMAINING hours only
        // Calculate when budget period started (wall clock at test start)
        val budgetStartSeconds = trafficShapeSeconds - elapsedSeconds
        val startCdf = tracker.cumulativeFractionAtTime(math.max(0, budgetStartSeconds))
        val currentCdf = tracker.cumulativeFractionAtTime(trafficShapeSeconds)
        val remainingCdfAtStart = 1.0 - startCdf
        // Fraction of remaining budget that should be spent by now
        if (remainingCdfAtStart > 0.001) (currentCdf - startCdf) / remainingCdfAtStart else 0.0
      } else {
        // Simulated days: use full day cycle
        tracker.cumulativeFractionAtTime(trafficShapeSeconds)
      }
    case None =>
      // Linear fallback
      elapsedHours / dayDurationHours
  }

  /** Day duration in hours */
  def dayDurationHours: Double = dayDurationSeconds / 3600.0

  /** Hours elapsed since day start */
  def elapsedHours: Double = elapsedSeconds / 3600.0

  /**
   * Seconds for traffic shape bucket lookup.
   * - For real calendar days (86400): use time-of-day from `now` (UTC)
   * - For simulated days: scale elapsed time to 24-hour cycle
   */
  def trafficShapeSeconds: Double = if (dayDurationSeconds == 86400) {
    // Real calendar days: extract time-of-day from `now` (not wall clock, so tests work)
    val time = now.atZone(java.time.ZoneOffset.UTC).toLocalTime
    time.getHour * 3600.0 + time.getMinute * 60.0 + time.getSecond
  } else {
    // Simulated short days: scale elapsed time to 24-hour cycle
    elapsedSeconds * (86400.0 / dayDurationSeconds)
  }

  /** Seconds elapsed since day start */
  def elapsedSeconds: Double = Duration.between(dayStart, now).toMillis / 1000.0

  /** Remaining budget */
  def remainingBudget: BigDecimal = dailyBudget - todaySpend

  /** Hours remaining in the day */
  def remainingHours: Double = math.max(0, dayDurationHours - elapsedHours)
}

/**
 * Pacing strategy trait - controls delivery VOLUME, not candidate CHOICE.
 *
 * IMPORTANT: Pacing gates volume, not choice.
 * ============================================
 * Pacing must be applied BEFORE Thompson Sampling selection, not after.
 * This prevents exploration bias where TS picks an exploration arm
 * that then gets filtered by pacing, skewing the learning process.
 *
 * Correct flow:
 *   if (pacing.shouldServe(ctx)) {
 *     winner = ThompsonSampling.pick(candidates)  // TS runs only when serving
 *     serve(winner)
 *   } else {
 *     skip()  // Do NOT run TS, do NOT update TS posteriors
 *   }
 *
 * Wrong flow (biases exploration):
 *   winner = ThompsonSampling.pick(candidates)
 *   if (!pacing.shouldThrottle(ctx)) serve(winner)  // Exploration arms get censored!
 *
 * Implementations:
 * - AdaptivePacing: PI-controlled pacing with rate awareness (recommended)
 * - FixedThrottlePacing: Fixed probability for testing
 */
trait PacingStrategy {

  /**
   * Decide whether to serve this request (Bernoulli gate).
   *
   * MUST be called BEFORE Thompson Sampling selection.
   * If returns false, do NOT call ThompsonSampling.pick().
   *
   * Uses allowProbability with random sampling.
   *
   * @return true if this request should be served (run TS), false to skip
   */
  def shouldServe(ctx: PacingContext): Boolean = {
    val prob = allowProbability(ctx)
    if (prob >= 1.0) true
    else if (prob <= 0.0) false
    else scala.util.Random.nextDouble() < prob
  }

  /**
   * Calculate probability of allowing this request through.
   *
   * @return probability between 0.0 (always skip) and 1.0 (always serve)
   */
  private def allowProbability(ctx: PacingContext): Double = 1.0 - throttleProbability(ctx)

  /**
   * Calculate throttle probability for the given context.
   * (Inverse of allowProbability, kept for backwards compatibility)
   *
   * @return probability between 0.0 (never throttle) and 1.0 (always throttle)
   */
  def throttleProbability(ctx: PacingContext): Double

  /** Reset internal state (e.g., at day boundary). Default no-op. */
  def reset(): Unit = ()

  /**
   * Prepare for day rollover - call BEFORE reset() to carry forward learnings.
   *
   * Allows strategies to learn from end-of-day conditions (e.g., early budget exhaustion)
   * and adjust parameters for the next day. Default no-op.
   *
   * @param budgetExhausted true if budget ran out before day end
   * @param remainingFraction fraction of day remaining when checked (0.0-1.0)
   */
  def prepareForRollover(budgetExhausted: Boolean, remainingFraction: Double): Unit = ()

  /** Record spend for rate calculation. Default no-op. */
  def recordSpend(amount: Double, now: Instant): Unit = ()

  /** Strategy name for logging/debugging */
  def name: String
}

object PacingStrategy {
  /**
   * Maximum throttle probability for normal pacing control.
   *
   * IMPORTANT:
   * - throttleProb=1.0 means "always throttle" (full stop).
   * - We reserve 1.0 for hard-stop conditions (remainingBudget<=0, remainingHours<=0).
   * - Normal pacing should not reach 1.0, otherwise you can get on/off oscillation
   *   (100% throttle -> loose -> 100% throttle) under high traffic.
   */
  val MaxThrottleProb: Double = 0.99
}

/**
 * Fixed throttle probability for testing.
 * Ignores budget/spend - always returns the fixed probability.
 *
 * Note: probability >= 1.0 is allowed (returns 1.0) to test hard-stop behavior.
 * Values in (0, 1) are clamped to MaxThrottleProb.
 *
 * Note: For correct TS interaction, use shouldServe() BEFORE Thompson Sampling.
 */
case object NoPacing extends PacingStrategy {
  override def name: String = "no-pacing"
  override def throttleProbability(ctx: PacingContext): Double = 0.0
}

final case class FixedThrottlePacing(probability: Double) extends PacingStrategy {
  // Allow 1.0 for testing hard stops; otherwise clamp to MaxThrottleProb
  private val clampedProb =
    if (probability >= 1.0) 1.0
    else math.max(0.0, math.min(PacingStrategy.MaxThrottleProb, probability))

  override def name: String = f"fixed-throttle($clampedProb%.0f%%)"

  override def throttleProbability(ctx: PacingContext): Double = clampedProb
}