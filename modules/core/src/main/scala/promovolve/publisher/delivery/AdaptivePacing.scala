package promovolve.publisher.delivery

/**
 * Adaptive pacing strategies for budget control.
 *
 * Primary strategy:
 *   - RateAwarePacing: PI-controlled pacing with rate awareness and traffic shaping
 */
object AdaptivePacing {

  /** Default estimated CPM for rate calculation (typical ad platform range) */
  val DefaultAvgCpm: Double = 5.00

  /** Default startup grace period as fraction of day (1%) - reduced throttling during ramp-up */
  val DefaultGracePeriodFraction: Double = 0.01 // 1% of day

  /**
   * Minimum grace period in seconds - ensures enough time for rate/spend stabilization.
   * This prevents the PI controller from making aggressive corrections based on noisy early data.
   * 10s allows: ~5s for rate EMA to stabilize + ~5s for initial spend observation.
   */
  val MinGraceSeconds: Double = 10.0

  /**
   * Minimum requests before initial grace period can end.
   * Combined with MinGraceSeconds: grace ends when BOTH conditions are met.
   * This ensures we have meaningful rate data before PI control engages.
   *
   * When traffic shape data is available, this is dynamically adjusted
   * based on the expected request rate for the current hour, so low-traffic
   * sites don't wait unnecessarily long.
   */
  val MinGraceRequests: Long = 10

  /** Maximum grace requests (caps the dynamic calculation for high-traffic sites) */
  val MaxGraceRequests: Long = 50

  /** Number of EMA windows needed for rate stabilization (1/alpha ≈ 3 windows) */
  val EmaStabilizationWindows: Int = 3

  /**
   * Base threshold for considering rate data stale (milliseconds) - for real 86400s days.
   * If no requests have been seen for this long, re-enter grace mode
   * to let the rate EMA re-stabilize before PI corrections resume.
   * 30 seconds handles typical traffic gaps without being too aggressive.
   *
   * For simulated days, this threshold is scaled proportionally:
   *   scaledThreshold = BaseStaleRateThresholdMs * dayDurationSeconds / 86400
   */
  val BaseStaleRateThresholdMs: Long = 30_000

  /** Minimum staleness threshold (milliseconds) to avoid triggering on normal traffic jitter */
  val MinStaleRateThresholdMs: Long = 1_000

  /** Default PI gains for rate-aware pacing (direct throttle adjustment) */
  val DefaultKp: Double = 0.5 // Proportional gain (under-pacing)
  val DefaultKi: Double = 0.3 // Integral gain (under-pacing)

  /** Base asymmetric gain multiplier for over-pacing (more aggressive recovery) */
  val BaseOverpaceGainMultiplier: Double = 2.0

  /** Maximum overpace gain multiplier (self-tuning cap) */
  val MaxOverpaceGainMultiplier: Double = 5.0

  /** Minimum overpace gain multiplier */
  val MinOverpaceGainMultiplier: Double = 1.5

  /** Self-tuning: window size for spend ratio history (number of samples) */
  val SelfTuneWindowSize: Int = 20

  /** Self-tuning: threshold for "persistent overspend" detection */
  val PersistentOverspendThreshold: Double = 1.05

  /** Self-tuning: how much to boost overpace multiplier when persistently overspending */
  val OverspendBoostFactor: Double = 1.15

  /** Self-tuning: how much to reduce overpace multiplier when well-paced */
  val WellPacedDecayFactor: Double = 0.95

  /** Self-tuning: threshold for "well-paced" (close to 1.0) */
  val WellPacedThreshold: Double = 1.02

  /** Self-tuning: oscillation detection - if std dev of spend ratio exceeds this, dampen */
  val OscillationThreshold: Double = 0.08

  /** Self-tuning: smoothing alpha adjustment when oscillating */
  val OscillationSmoothingBoost: Double = 1.3

  /** Self-tuning: minimum smoothing alpha (floor when dampening oscillation) */
  val MinSmoothingAlpha: Double = 0.1

  /** Self-tuning: maximum smoothing alpha (ceiling when stable) */
  val MaxSmoothingAlpha: Double = 0.5

  /** Self-tuning: smoothing alpha recovery factor when stable */
  val StableSmoothingRecoveryFactor: Double = 1.05

  /**
   * Self-tuning: minimum interval between tuning runs (milliseconds).
   * Prevents too-frequent tuning at high traffic while still allowing
   * responsive tuning at low traffic (where request count is the bottleneck).
   */
  val MinSelfTuneIntervalMs: Long = 500

  /**
   * Cross-day learning: minimum remaining day fraction to trigger early exhaustion boost.
   * If budget exhausts with more than this fraction remaining, boost overpace multiplier.
   * 0.05 = 5% of day remaining (e.g., 30 minutes in a real day, 30s in a 600s simulated day)
   */
  val EarlyExhaustionThreshold: Double = 0.05

  /**
   * Leaky integrator decay factor - prevents indefinite windup.
   * Applied each update: integralError *= decayFactor
   * 0.995 = ~0.5% decay per update, half-life of ~138 updates
   */
  val IntegralDecayFactor: Double = 0.995

  /**
   * EMA smoothing factor for spendRatio - dampens oscillation from bursty SpendInfo updates.
   * 0.3 = new value has 30% weight, smoothed history has 70% weight
   * Balances responsiveness vs stability
   */
  val SpendRatioSmoothingAlpha: Double = 0.3

  /**
   * Default feedforward window: disabled — the PI controller plus the
   * traffic-shape rate multiplier (with bucket interpolation) pace
   * smoothly without anticipatory correction.
   */
  val DefaultFeedforwardWindow: Double = 0.0

  /** Create rate-aware pacing (recommended) - handles high traffic with PI control */
  def apply(): RateAwarePacing = rateAware()

  /** Create rate-aware pacing with custom parameters */
  def rateAware(
      avgCpm: Double = DefaultAvgCpm,
      gracePeriodFraction: Double = DefaultGracePeriodFraction,
      kp: Double = DefaultKp,
      ki: Double = DefaultKi,
      feedforwardWindow: Double = DefaultFeedforwardWindow
  ): RateAwarePacing = new RateAwarePacing(avgCpm, gracePeriodFraction, kp, ki, feedforwardWindow)

  // NOTE (2026-07-13): the volatility-adjusted constructor family
  // (forShape / forShapeVolumes / gainsForVolatility /
  // feedforwardForVolatility) was removed — production only ever built
  // the plain rateAware() controller, and the runtime selfTune +
  // overpace multiplier already adapt gains from OBSERVED behavior,
  // which beats pre-computing them from shape volatility. Git history
  // has the design if a real pacing oscillation ever calls for it.
}

/**
 * Rate-aware pacing with PI control - PI directly adjusts throttle probability.
 *
 * Unlike traditional approaches that adjust target rate (which has diminishing returns
 * at high traffic), this directly adjusts the throttle probability for linear control
 * regardless of traffic level.
 *
 * == How it works ==
 *   - Base throttle: 1 - (baseTarget / requestRate) — what we'd throttle at perfect pace
 *   - Error: 1.0 - spendRatio (positive when under-paced, negative when over-paced)
 *   - PI adjustment: kp × error + ki × integral — directly added/subtracted from throttle
 *   - Final throttle: baseThrottle - adjustment (clamped to [0, 1])
 *
 * Example at 383 req/sec, 0.7x spend ratio (30% under):
 *   - baseThrottle = 94.8%
 *   - error = 0.3, adjustment = 0.3 + integral
 *   - throttle = 94.8% - 30% = 64.8% (or lower with integral)
 *   - Pass rate jumps from 5% to 35%+
 *
 * == Asymmetric Gains ==
 * The controller uses asymmetric gains to recover from overspend faster than it
 * accelerates during underspend. This is because:
 *   - Overspend is costly: budget exhaustion stops delivery entirely
 *   - Underspend is recoverable: can catch up later in the day
 *
 * When over-pacing (spendRatio > 1.0):
 *   - effectiveKp = kp × OverpaceGainMultiplier (default 2.0x)
 *   - effectiveKi = ki × OverpaceGainMultiplier (default 2.0x)
 *
 * When under-pacing (spendRatio <= 1.0):
 *   - effectiveKp = kp (unchanged)
 *   - effectiveKi = ki (unchanged)
 *
 * This ensures early overspend creates a strong corrective response that
 * actually recovers the cumulative gap, rather than just converging to
 * the correct rate while the gap persists.
 *
 * == Feedforward Control ==
 * Near bucket transitions, the controller proactively adjusts the shape multiplier
 * to anticipate upcoming traffic changes. This reduces lag when traffic volume
 * changes sharply between buckets (e.g., h7→h8: 2.5→0.1).
 *
 * The feedforwardWindow parameter controls how much of each bucket is used for
 * proactive blending toward the next bucket's volume:
 *   - 0.0: No feedforward (sharp transitions at bucket boundaries)
 *   - 0.2: Last 20% of each bucket blends toward next (default, recommended)
 *   - 0.5: Last 50% of each bucket blends (very smooth but may blur peaks)
 *
 * == Other Features ==
 * The budget cap (remainingBudget <= 0 → hard stop) is the safety net against overspend.
 * This allows aggressive catch-up without artificial boost limits.
 *
 * The integral term accumulates persistent errors:
 *   - Consistent under-pacing builds positive integral → stronger throttle reduction
 *   - Consistent over-pacing builds negative integral → stronger throttle increase
 *   - Natural decay as error approaches zero
 *
 * Anti-windup prevents integral from growing unbounded.
 *
 * Grace period: During startup (first gracePeriodFraction of day), uses base throttle only.
 * The integral is frozen (not accumulated) to prevent step-change when grace ends.
 * This allows the system to observe traffic patterns before engaging PI control.
 *
 * @param avgCpmEstimate Estimated average CPM for rate calculation (default 5.00)
 * @param gracePeriodFraction Fraction of day for startup grace period (default 0.01 = 1%)
 * @param kp Proportional gain - immediate response to error (default 0.5)
 * @param ki Integral gain - accumulated error correction (default 0.3)
 * @param feedforwardWindow Fraction of bucket for proactive feedforward adjustment (default 0.0)
 * @see [[AdaptivePacing.OverpaceGainMultiplier]] for asymmetric gain configuration
 */
class RateAwarePacing(
    avgCpmEstimate: Double = 5.00,
    gracePeriodFraction: Double = 0.01,
    kp: Double = 0.5,
    ki: Double = 0.3,
    feedforwardWindow: Double = 0.0
) extends PacingStrategy {

  // Anti-windup bounds for integral
  private val maxIntegral: Double = 1.0
  private val minIntegral: Double = -1.0
  // PI state
  private var integralError: Double = 0.0
  private var lastTimestamp: Option[java.time.Instant] = None
  // EMA-smoothed spendRatio to dampen oscillation from bursty feedback
  private var smoothedSpendRatio: Option[Double] = None

  // Self-tuning state
  private val spendRatioHistory: scala.collection.mutable.Queue[Double] =
    scala.collection.mutable.Queue.empty
  private var currentOverpaceMultiplier: Double = AdaptivePacing.BaseOverpaceGainMultiplier
  private var currentSmoothingAlpha: Double = AdaptivePacing.SpendRatioSmoothingAlpha
  private var selfTuneCounter: Int = 0 // Only tune every N updates to avoid thrashing
  private var lastSelfTuneTime: Long = 0 // Epoch millis of last self-tune
  // Cross-day learning: hint recorded by prepareForRollover, applied in reset
  private var rolloverBoostHint: Double = 1.0

  override def name: String = f"rate-aware-pi(kp=$kp%.1f,ki=$ki%.1f,ff=${feedforwardWindow * 100}%.0f%%,self-tune)"

  /**
   * Self-tuning: analyze spend ratio history and adjust gains.
   *
   * Called periodically (every 10 updates) to avoid thrashing.
   */
  private def selfTune(): Unit = {
    if (spendRatioHistory.size < AdaptivePacing.SelfTuneWindowSize / 2) return // Need enough data

    val history = spendRatioHistory.toSeq
    val avgRatio = history.sum / history.size

    // Calculate standard deviation for oscillation detection
    val variance = history.map(r => math.pow(r - avgRatio, 2)).sum / history.size
    val stdDev = math.sqrt(variance)

    // Detect persistent overspend: average ratio > threshold
    if (avgRatio > AdaptivePacing.PersistentOverspendThreshold) {
      // Boost overpace multiplier to recover faster
      currentOverpaceMultiplier = math.min(
        AdaptivePacing.MaxOverpaceGainMultiplier,
        currentOverpaceMultiplier * AdaptivePacing.OverspendBoostFactor
      )
    } else if (avgRatio < AdaptivePacing.WellPacedThreshold && avgRatio > 0.98) {
      // Well-paced: slowly decay multiplier back toward base
      currentOverpaceMultiplier = math.max(
        AdaptivePacing.MinOverpaceGainMultiplier,
        currentOverpaceMultiplier * AdaptivePacing.WellPacedDecayFactor
      )
    }

    // Detect oscillation: high variance in spend ratio
    if (stdDev > AdaptivePacing.OscillationThreshold) {
      // Increase smoothing to dampen oscillation
      currentSmoothingAlpha = math.max(
        AdaptivePacing.MinSmoothingAlpha,
        currentSmoothingAlpha / AdaptivePacing.OscillationSmoothingBoost
      )
    } else if (stdDev < AdaptivePacing.OscillationThreshold / 2) {
      // Stable: can be more responsive
      currentSmoothingAlpha = math.min(
        AdaptivePacing.MaxSmoothingAlpha,
        currentSmoothingAlpha * AdaptivePacing.StableSmoothingRecoveryFactor
      )
    }
  }

  override def throttleProbability(ctx: PacingContext): Double = {
    if (ctx.dailyBudget <= 0) return 1.0 // No budget: hard stop
    if (ctx.remainingHours <= 0) return 1.0 // Day over: hard stop
    if (ctx.remainingBudget <= 0) return 1.0 // Budget exhausted: hard stop

    val effectiveCpm = if (ctx.avgCpm > 0) ctx.avgCpm else avgCpmEstimate

    // EMA-smooth spendRatio to dampen oscillation from bursty SpendInfo updates
    // Uses self-tuned smoothing alpha (adjusted based on oscillation detection)
    val rawSpendRatio = ctx.spendRatio
    val spendRatio = smoothedSpendRatio match {
      case Some(prev) =>
        currentSmoothingAlpha * rawSpendRatio + (1 - currentSmoothingAlpha) * prev
      case None =>
        rawSpendRatio // First call: use raw value
    }
    smoothedSpendRatio = Some(spendRatio)

    // Record spend ratio history for self-tuning
    spendRatioHistory.enqueue(spendRatio)
    while (spendRatioHistory.size > AdaptivePacing.SelfTuneWindowSize) {
      spendRatioHistory.dequeue()
    }

    // Periodic self-tuning: requires both enough samples (10) AND enough time (500ms)
    // This prevents too-frequent tuning at high traffic while remaining responsive at low traffic
    selfTuneCounter += 1
    val nowMs = ctx.now.toEpochMilli
    if (selfTuneCounter >= 10 && (nowMs - lastSelfTuneTime) >= AdaptivePacing.MinSelfTuneIntervalMs) {
      selfTune()
      selfTuneCounter = 0
      lastSelfTuneTime = nowMs
    }

    // Base target: even distribution over day, adjusted for traffic shape
    val evenTargetImpsPerSec = (ctx.dailyBudget.toDouble / ctx.dayDurationSeconds.toDouble) / (effectiveCpm / 1000.0)

    // Apply traffic shape multiplier with feedforward: scale target based on expected traffic volume
    // - During peak hours (multiplier > 1): higher target allows more impressions
    // - During valley hours (multiplier < 1): lower target prevents overspend
    // - Near bucket transitions: proactively blend toward next bucket's volume
    val shapeMultiplier = ctx.trafficShape.map { tracker =>
      // Use trafficShapeSeconds (wall clock for 86400, scaled elapsed for simulated)
      tracker.relativeVolumeWithFeedforward(ctx.trafficShapeSeconds, feedforwardWindow)
    }.getOrElse(1.0)

    val baseTargetImpsPerSec = evenTargetImpsPerSec * shapeMultiplier

    // Base throttle: what we'd need at perfect pace
    val currentReqRate = ctx.requestArrivalRate
    val baseThrottle = if (currentReqRate > baseTargetImpsPerSec && baseTargetImpsPerSec > 0) {
      1.0 - (baseTargetImpsPerSec / currentReqRate)
    } else 0.0

    // Grace period: observe only, don't act or accumulate
    // Two conditions trigger grace mode:
    //   1. Initial grace: need BOTH enough time AND enough requests
    //   2. Staleness: rate data is stale after traffic gap
    val dayScale = ctx.dayDurationSeconds.toDouble / 86400.0
    val graceSeconds = math.max(1.0, AdaptivePacing.MinGraceSeconds * dayScale)
    // Dynamic grace requests: use traffic shape to estimate expected rate,
    // then require enough requests for EMA stabilization (3 windows × rate)
    val graceRequests: Long = ctx.trafficShape match {
      case Some(tracker) =>
        val currentVolume = tracker.relativeVolumeAtTime(ctx.trafficShapeSeconds)
        if (currentVolume > 0 && ctx.requestArrivalRate > 0) {
          // Expected requests during grace period based on observed rate
          val expectedInGrace = (ctx.requestArrivalRate * graceSeconds).toLong
          math.max(AdaptivePacing.MinGraceRequests,
            math.min(AdaptivePacing.MaxGraceRequests, expectedInGrace))
        } else {
          // No rate data yet — use minimum
          math.max(5L, (AdaptivePacing.MinGraceRequests * dayScale).toLong)
        }
      case None =>
        math.max(5L, (AdaptivePacing.MinGraceRequests * dayScale).toLong)
    }
    val initialGraceComplete =
      ctx.elapsedSeconds >= graceSeconds &&
      ctx.requestCount >= graceRequests

    // Scale staleness threshold proportionally to day duration
    // For 600s simulated day: 30000 * 600/86400 = 208ms, clamped to min 1000ms
    val scaledStaleThresholdMs = math.max(
      AdaptivePacing.MinStaleRateThresholdMs,
      AdaptivePacing.BaseStaleRateThresholdMs * ctx.dayDurationSeconds / 86400
    )
    val rateIsStale = ctx.msSinceLastRequest > scaledStaleThresholdMs

    if (!initialGraceComplete) {
      // Initial grace: we don't yet have a stable rate estimate for full PI
      // control (grace completes after MinGraceSeconds AND MinGraceRequests).
      // But refusing to serve is the wrong default. At near-zero spend we are
      // maximally *behind* pace, so serving is the safe direction — you cannot
      // meaningfully overspend in a handful of warmup requests, whereas a cold
      // or low-traffic site that returns MaxThrottleProb here shows NO ads at
      // all until it happens to accumulate enough closely-spaced requests to
      // exit grace (the cold-start cliff). Instead, serve at baseThrottle: the
      // rate cap that holds imps/sec at the paced target. It already provides
      // the only burst protection grace was really buying — proportional to
      // arrival rate (≈0 when traffic is sparse, ~1 under a flood) — and never
      // returns a true hard stop. We still skip the PI integral below so it
      // warms up on clean data once grace completes.
      lastTimestamp = Some(ctx.now)
      // Clamp to MaxThrottleProb so a flood during grace never returns a true
      // 1.0 (which is reserved for hard stops); baseThrottle stays < 1.0 anyway.
      math.min(baseThrottle, PacingStrategy.MaxThrottleProb)
    } else {

      if (rateIsStale) {
        // Traffic gap detected (e.g., machine sleep, idle period).
        // Reset PI state so we don't make corrections based on stale accumulated error,
        // but allow serving immediately — the gap doesn't mean data is bad, just old.
        integralError = 0.0
        smoothedSpendRatio = None
        lastTimestamp = Some(ctx.now)
        // Fall through to normal PI control with fresh state
      }

      // PI error: positive when under-paced, negative when over-paced
      val error = 1.0 - spendRatio
      val isOverPacing = error < 0

      // Dynamic gain boost near drastic bucket transitions
      // If upcoming transition is severe (ratio far from 1.0), boost gains to react faster
      val transitionBoost = ctx.trafficShape.map { tracker =>
        val (ratio, fractionUntil) = tracker.upcomingTransition(ctx.trafficShapeSeconds)

        // Only boost in the last 20% of bucket when transition is drastic
        val inTransitionWindow = fractionUntil < 0.2
        val transitionSeverity = math.abs(math.log(math.max(0.1, math.min(10.0, ratio))))
        // severity: 0 = no change, ~0.7 = 2x change, ~1.6 = 5x change, ~2.3 = 10x change

        if (inTransitionWindow && transitionSeverity > 0.5) {
          // Boost proportional to severity, max 2x
          1.0 + math.min(1.0, transitionSeverity / 2.0)
        } else 1.0
      }.getOrElse(1.0)

      // Asymmetric gains: be more aggressive when over-pacing to recover faster
      // Uses self-tuned overpace multiplier (boosted when persistently overspending)
      val baseEffectiveKp = if (isOverPacing) kp * currentOverpaceMultiplier else kp
      val baseEffectiveKi = if (isOverPacing) ki * currentOverpaceMultiplier else ki

      // Apply transition boost
      val effectiveKp = baseEffectiveKp * transitionBoost
      val effectiveKi = baseEffectiveKi * transitionBoost

      // Calculate dt for integral
      val now = ctx.now
      val dt = lastTimestamp match {
        case Some(last) =>
          val seconds = java.time.Duration.between(last, now).toMillis / 1000.0
          math.max(0.001, math.min(seconds, 1.0)) // Clamp to reasonable range
        case None => 0.1 // Default for first call
      }

      // Update integral with leaky integrator (decay prevents indefinite windup)
      // Decay first, then accumulate new error
      integralError = integralError * AdaptivePacing.IntegralDecayFactor + error * dt
      integralError = math.max(minIntegral, math.min(maxIntegral, integralError))

      // PI output: adjustment to throttle (positive = reduce throttle, negative = increase)
      val pTerm = effectiveKp * error
      val iTerm = effectiveKi * integralError
      val adjustment = pTerm + iTerm

      // Apply adjustment: subtract from base throttle (positive adjustment = less throttle)
      val adjustedThrottle = baseThrottle - adjustment

      // Update state for next call
      lastTimestamp = Some(now)

      // Clamp to valid probability range [0, 1]
      math.max(0.0, math.min(1.0, adjustedThrottle))
    }
  }

  /**
   * Prepare for day rollover - call BEFORE reset() to carry forward learnings.
   *
   * If budget exhausted early (with significant time remaining), boosts the overpace
   * multiplier for the next day. This creates cross-day learning: repeated early
   * exhaustion leads to progressively more aggressive throttling until pacing stabilizes.
   *
   * @param budgetExhausted true if budget ran out before day end
   * @param remainingFraction fraction of day remaining when checked (0.0-1.0)
   */
  override def prepareForRollover(budgetExhausted: Boolean, remainingFraction: Double): Unit = {
    if (budgetExhausted && remainingFraction > AdaptivePacing.EarlyExhaustionThreshold) {
      rolloverBoostHint = 1.0 + remainingFraction
    }
  }

  override def reset(): Unit = {
    integralError = 0.0
    lastTimestamp = None
    smoothedSpendRatio = None
    spendRatioHistory.clear()
    // Apply cross-day learning hint, then decay toward base
    if (rolloverBoostHint > 1.0) {
      currentOverpaceMultiplier = math.min(
        AdaptivePacing.MaxOverpaceGainMultiplier,
        currentOverpaceMultiplier * rolloverBoostHint
      )
      rolloverBoostHint = 1.0
    } else {
      currentOverpaceMultiplier = math.max(
        AdaptivePacing.BaseOverpaceGainMultiplier,
        currentOverpaceMultiplier * AdaptivePacing.WellPacedDecayFactor
      )
    }
    currentSmoothingAlpha = AdaptivePacing.SpendRatioSmoothingAlpha
    selfTuneCounter = 0
    lastSelfTuneTime = 0
  }

  /** Get current self-tuned overpace multiplier (for monitoring/debugging) */
  def overpaceMultiplier: Double = currentOverpaceMultiplier

  /** Get current self-tuned smoothing alpha (for monitoring/debugging) */
  def smoothingAlpha: Double = currentSmoothingAlpha
}
