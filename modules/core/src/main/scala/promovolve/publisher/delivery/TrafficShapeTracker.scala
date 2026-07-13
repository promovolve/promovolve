package promovolve.publisher.delivery

import java.time.Instant

/**
 * Tracks traffic shape using EMA per time bucket for traffic-aware pacing.
 *
 * == Problem ==
 *
 * Linear pacing assumes uniform traffic throughout the day:
 * {{{
 * Budget: $30/day
 *
 * Linear Target:
 * Hour:    0    6    12   18   24
 *          |----|----|----|----|
 * Target:  $0  $7.5  $15  $22.5 $30
 *          └─────────────────────┘ straight line
 * }}}
 *
 * But real traffic has shape — peaks during day, valleys at night:
 * {{{
 * Traffic Volume:
 *          ▁▁▂▃▅▆███▇▆▅▄▃▂▁▁
 *          night  peak  evening
 * }}}
 *
 * This causes:
 *  - '''Over-throttling during peaks''' — wasted inventory when traffic is abundant
 *  - '''Under-delivery during valleys''' — impossible targets when traffic is scarce
 *  - '''Integral windup''' — PI controller fights against reality
 *
 * == Solution ==
 *
 * Track relative traffic volume per time bucket using EMA. The cumulative
 * distribution function (CDF) of learned traffic becomes the expected spend curve:
 * {{{
 * Traffic-Shaped Target:
 * Hour:    0    6    12   18   24
 *          |----|----|----|----|
 * Target:  $1   $5   $18   $26  $30
 *            ╱      ╱╲
 *           ╱      ╱  ╲
 *          ╱──────╱    ╲────────
 *          └─ matches traffic shape ─┘
 * }}}
 *
 * With traffic-shaped targeting:
 *  - '''Night (0-6)''': Only expect $5 spend (not $7.5) — less aggressive catch-up
 *  - '''Peak (9-15)''': Expect bulk of budget — allow more serving
 *  - '''Evening (18-24)''': Gentle decline — smooth finish
 *
 * == How It Works ==
 *
 * Each bucket tracks relative request volume (not absolute counts):
 * {{{
 * historical[hour] = α × observation + (1-α) × historical[hour]
 * }}}
 *
 * The CDF at any point is:
 * {{{
 * cumulativeFraction(hour) = sum(buckets[0..hour]) / sum(buckets[0..23])
 * }}}
 *
 * This CDF replaces linear time fraction in spend ratio calculation:
 * {{{
 * // Before (linear):
 * expectedSpend = dailyBudget × (elapsedHours / 24)
 *
 * // After (traffic-shaped):
 * expectedSpend = dailyBudget × cumulativeFraction(elapsedHours)
 * }}}
 *
 * == Cold Start ==
 *
 * New sites start with uniform distribution (equivalent to linear pacing).
 * As traffic flows, the tracker learns the actual pattern. Use [[TrafficShapeTracker.restore]]
 * to pre-load a known traffic shape for faster convergence.
 *
 * == Thread Safety ==
 *
 * This class is '''not thread-safe'''. It should be used within a single actor
 * (AdServer) where updates happen synchronously in the message handler.
 *
 * @param bucketCount        Number of time buckets per day (24 = hourly, 96 = 15-min)
 * @param alpha              EMA smoothing factor. Lower = slower adaptation, more stable.
 *                           Default 0.1 means ~10 update steps to converge (e.g., ~10 days with hourly buckets).
 * @param interpolateVolumes If true, [[relativeVolumeAtTime]] interpolates between buckets for smooth
 *                           transitions. If false (default), uses bucket volume directly for sharper
 *                           peak definition. Use false for accurate shape matching in tests, true for
 *                           smoother production pacing with longer bucket durations.
 *
 * @see [[PacingContext.expectedSpendFraction]] for integration with pacing
 * @see [[AdaptivePacing]] for the PI controller that uses this
 */
class TrafficShapeTracker(
    val bucketCount: Int = 24,
    val alpha: Double = TrafficShapeTracker.DefaultAlpha,
    val interpolateVolumes: Boolean = false
) {
  require(bucketCount > 0 && bucketCount <= 1440, "bucketCount must be 1-1440")
  require(alpha > 0 && alpha <= 1, "alpha must be in (0, 1]")

  // Weekday shape - used for pacing on Mon-Fri (stable baseline)
  private val weekdayShape: Array[Double] = Array.fill(bucketCount)(1.0)

  // Weekend shape - used for pacing on Sat-Sun (stable baseline)
  private val weekendShape: Array[Double] = Array.fill(bucketCount)(1.0)
  // Today's raw request counts per bucket (reset at day rollover)
  private val todayCount: Array[Long] = Array.fill(bucketCount)(0L)
  private val warmupThreshold: Int = bucketCount - 1
  // Cached sums to avoid O(bucketCount) on every request
  private var weekdayTotal: Double = bucketCount.toDouble
  private var weekendTotal: Double = bucketCount.toDouble
  // Track what type of day "today" is (for rollover blending)
  private var todayIsWeekend: Boolean = false
  // Current bucket being tracked
  private var currentBucket: Int = -1
  // Bootstrap intra-day learning: a FRESH tracker (no snapshot, no
  // rollover yet) learns per-bucket within its first day so pacing gets
  // rough shape awareness immediately. Restore and the first rollover
  // both turn it off — from then on the daily 20/80 blend is the only
  // writer. (Field name kept for snapshot/test stability.)
  private var requestsInBucket: Long = 0
  private var emaBucketRequests: Double = 1.0
  private var useLegacyMode: Boolean = true
  // Warmup tracking. Threshold is bucketCount - 1 because we count bucket transitions, not buckets.
  // Starting in bucket 0 and transitioning through all 24 buckets requires 23 transitions
  // (0→1, 1→2, ..., 22→23) to visit every bucket.
  private var bucketUpdateCount: Int = 0

  /** Set the day type for today (call at start of day or when day type changes) */
  def setDayType(isWeekend: Boolean): Unit = {
    todayIsWeekend = isWeekend
  }

  /** Set day type from a DayOfWeek */
  def setDayType(dayOfWeek: java.time.DayOfWeek): Unit = {
    todayIsWeekend = isWeekend(dayOfWeek)
  }

  /** Check if a day of week is weekend */
  private def isWeekend(dayOfWeek: java.time.DayOfWeek): Boolean = {
    import java.time.DayOfWeek.*
    dayOfWeek == SATURDAY || dayOfWeek == SUNDAY
  }

  /** Whether today is being tracked as a weekend day */
  def isTrackingWeekend: Boolean = todayIsWeekend

  /**
   * Record a request arrival. Called synchronously on each Select.
   *
   * Updates are batched per bucket — the EMA is applied when the bucket changes.
   * This reduces noise from individual request timing.
   *
   * @param elapsedSeconds Seconds elapsed since day start
   */
  def recordRequest(elapsedSeconds: Double): Unit = {
    val bucket = bucketForElapsed(elapsedSeconds)

    todayCount(bucket) += 1

    // Track current bucket for display purposes
    if (bucket != currentBucket) {
      // Bucket changed - apply bootstrap intra-day EMA (first day of a
      // fresh tracker only; see useLegacyMode)
      if (useLegacyMode && currentBucket >= 0 && requestsInBucket > 0) {
        applyBucketUpdate(currentBucket, requestsInBucket)
      }
      currentBucket = bucket
      requestsInBucket = 1
    } else {
      requestsInBucket += 1
    }
  }

  /** Apply EMA update when bucket completes (bootstrap first-day mode only) */
  private def applyBucketUpdate(bucket: Int, requests: Long): Unit = {
    val req = requests.toDouble

    // Track typical absolute traffic for this site so normalization adapts to volume changes.
    // This keeps historical scale-invariant (shape only), avoiding arbitrary constants.
    emaBucketRequests = alpha * req + (1 - alpha) * emaBucketRequests

    // Normalize to a dimensionless relative observation around ~1.0.
    // If traffic doubles/halves, the observation remains comparable and learning dynamics stay stable.
    val scale = math.max(1.0, emaBucketRequests)
    val observation = req / scale

    // Get the appropriate shape based on day type
    val shape = currentShape
    val oldValue = shape(bucket)

    val newValue = alpha * observation + (1 - alpha) * oldValue
    shape(bucket) = newValue

    // Maintain cached total incrementally (update the correct total)
    val delta = newValue - oldValue
    if (todayIsWeekend) {
      weekendTotal += delta
    } else {
      weekdayTotal += delta
    }

    // Track warmup progress
    bucketUpdateCount += 1
  }

  /**
   * Flush pending updates. Call at day boundary to ensure last bucket is recorded.
   */
  def flush(): Unit = {
    // Gated on bootstrap mode: flush is called by EVERY snapshot
    // (hourly timer, rollover persist, PostStop), and ungated it applied
    // a bootstrap-EMA nudge to the learned shape's current bucket on
    // each call even in daily-blend mode — a slow, scheduled distortion
    // of exactly the shape it was persisting (found 2026-07-13 by the
    // restore regression spec).
    if (useLegacyMode && currentBucket >= 0 && requestsInBucket > 0) {
      applyBucketUpdate(currentBucket, requestsInBucket)
      requestsInBucket = 0
    }
  }

  /**
   * Perform day rollover: blend today's observations into the appropriate shape.
   *
   * Call this at day boundary to:
   * 1. Blend today's observed traffic into the correct shape (weekday or weekend)
   * 2. Reset today's counters for the new day
   *
   * The blend uses EMA: shape = dayAlpha * todayNormalized + (1-dayAlpha) * shape
   *
   * After calling this, pacing will use the updated shape for the appropriate day type,
   * and today's observations will start fresh.
   *
   * @param dayAlpha Blend factor for today's observations (default 0.2)
   *                 - 0.2 means today contributes 20%, history 80%
   *                 - Higher = faster adaptation, more volatile
   *                 - Lower = slower adaptation, more stable
   */
  def rolloverDay(dayAlpha: Double = 0.2): Unit = {
    require(dayAlpha > 0 && dayAlpha <= 1, "dayAlpha must be in (0, 1]")

    // Check if we have any observations today
    val todayTotal = todayCount.sum
    if (todayTotal <= 0) {
      // No traffic today - just reset counters, keep shapes unchanged
      java.util.Arrays.fill(todayCount, 0L)
      currentBucket = -1
      requestsInBucket = 0
      return
    }

    // Normalize today's counts to relative shape (same scale as shapes)
    // Average bucket count becomes ~1.0, matching shape's scale
    val avgCount = todayTotal.toDouble / bucketCount
    val todayNormalized = todayCount.map(c => c.toDouble / avgCount)

    // Blend today into the appropriate shape based on whether today is weekend
    val shape = if (todayIsWeekend) weekendShape else weekdayShape
    var newTotal = 0.0
    var i = 0
    while (i < bucketCount) {
      shape(i) = dayAlpha * todayNormalized(i) + (1 - dayAlpha) * shape(i)
      newTotal += shape(i)
      i += 1
    }

    // Update the correct cached total
    if (todayIsWeekend) {
      weekendTotal = newTotal
    } else {
      weekdayTotal = newTotal
    }

    // Reset today's counters for new day
    java.util.Arrays.fill(todayCount, 0L)
    currentBucket = -1
    requestsInBucket = 0

    // Switch to new mode (disable legacy within-day EMA updates)
    useLegacyMode = false

    // Mark as warmed up since we now have blended data
    if (bucketUpdateCount < warmupThreshold) {
      bucketUpdateCount = warmupThreshold
    }
  }

  /** Get today's raw request counts per bucket (for debugging/monitoring) */
  def todayCounts: Array[Long] = todayCount.clone()

  /** Get total requests observed today */
  def todayTotalRequests: Long = todayCount.sum

  /**
   * Cumulative traffic fraction up to (and including) given bucket.
   * This is the CDF of expected traffic distribution.
   *
   * @param upToBucket Bucket index (0-based)
   * @return Value in [0, 1] representing fraction of daily traffic expected by this point
   */
  def cumulativeFraction(upToBucket: Int): Double = {
    val shape = currentShape
    val total = cachedTotal
    if (total <= 0) return (upToBucket + 1).toDouble / bucketCount // Fallback to linear

    val cumulative = shape.take(upToBucket + 1).sum
    cumulative / total
  }

  /**
   * Cumulative traffic fraction for elapsed time.
   * Interpolates within bucket for smooth transitions (no step jumps at bucket boundaries).
   *
   * {{{
   * Example at 10:30 (bucket 10, 50% into bucket):
   *   cumulativeFraction = sum(buckets[0..9]) + 0.5 × bucket[10]
   *                        ─────────────────────────────────────
   *                                   sum(all buckets)
   * }}}
   *
   * @param elapsedSeconds Seconds elapsed since day start
   * @return Value in [0, 1] representing expected fraction of daily traffic by this time
   */
  def cumulativeFractionAtTime(elapsedSeconds: Double): Double = {
    val bucket = bucketForElapsed(elapsedSeconds)
    val bucketStart = bucket * bucketDurationSec
    val fractionIntoBucket = math.min(1.0, (elapsedSeconds - bucketStart) / bucketDurationSec)

    // Use cached total (O(1) instead of O(bucketCount))
    val total = cachedTotal
    // Defensive: cachedTotal is always > 0 (initialized to bucketCount, updated incrementally)
    if (total <= 0) return elapsedSeconds / 86400.0

    // Get current shape based on day type
    val shape = currentShape

    // Cumulative up to previous bucket + interpolated current bucket
    var prevCumulative = 0.0
    var i = 0
    while (i < bucket) {
      prevCumulative += shape(i)
      i += 1
    }
    val currentContribution = shape(bucket) * fractionIntoBucket

    (prevCumulative + currentContribution) / total
  }

  /**
   * Get relative volume for a specific bucket (for debugging/monitoring).
   *
   * @return Relative volume (not a probability, just relative to other buckets)
   */
  def volumeForBucket(bucket: Int): Double = {
    require(bucket >= 0 && bucket < bucketCount)
    currentShape(bucket)
  }

  /**
   * Get the relative volume multiplier at a specific time.
   *
   * Returns a value normalized so that the average across all buckets is 1.0:
   *  - `> 1.0`: Higher-than-average traffic (peak hours) → need higher target rate
   *  - `= 1.0`: Average traffic → use base rate
   *  - `< 1.0`: Lower-than-average traffic (valley hours) → need lower target rate
   *
   * This is used by [[AdaptivePacing]] to make the base throttle target
   * traffic-shape aware, reducing lag during shape transitions.
   *
   * When `interpolateVolumes` is true, interpolates between current and next bucket
   * for smooth transitions. When false (default), uses the current bucket's volume
   * directly for sharper peak definition.
   *
   * {{{
   * Example with shape [0.2, 0.2, ..., 3.0, ..., 0.4] (avg ~1.18):
   *   relativeVolumeAtTime(h1)  → 0.2 / 1.18 ≈ 0.17  (expect 17% of avg traffic)
   *   relativeVolumeAtTime(h13) → 3.0 / 1.18 ≈ 2.54  (expect 254% of avg traffic)
   * }}}
   *
   * @param elapsedSeconds Seconds elapsed since day start
   * @return Multiplier for base target rate (1.0 = average)
   */
  def relativeVolumeAtTime(elapsedSeconds: Double): Double = {
    val bucket = bucketForElapsed(elapsedSeconds)
    val total = cachedTotal
    val avgVol = total / bucketCount
    if (avgVol <= 0) return 1.0

    val shape = currentShape
    val effectiveVol = if (interpolateVolumes) {
      // Interpolate between current and next bucket for smooth transitions
      val bucketStart = bucket * bucketDurationSec
      val fractionIntoBucket = math.min(1.0, (elapsedSeconds - bucketStart) / bucketDurationSec)
      val currentVol = shape(bucket)
      val nextBucket = (bucket + 1) % bucketCount
      val nextVol = shape(nextBucket)
      currentVol + fractionIntoBucket * (nextVol - currentVol)
    } else {
      // Use current bucket's volume directly for sharper peak definition
      shape(bucket)
    }

    effectiveVol / avgVol
  }

  /**
   * Get relative volume with feedforward adjustment near bucket transitions.
   *
   * Unlike [[relativeVolumeAtTime]] which steps sharply at bucket boundaries,
   * this method provides proactive adjustment in the transition window before
   * each boundary. This allows the pacing controller to anticipate traffic
   * changes rather than react to them.
   *
   * == Behavior ==
   *
   * For most of the bucket (0% to `1 - feedforwardWindow`):
   *   - Uses current bucket's volume (sharp peak definition preserved)
   *
   * Near the end (`1 - feedforwardWindow` to 100%):
   *   - Smoothly blends toward next bucket's volume
   *   - Blend is proportional to position in the window
   *
   * {{{
   * Example: feedforwardWindow = 0.2 (last 20% of bucket)
   *
   * Bucket progress:  0%────────80%────────100%
   *                   │  sharp   │  blend   │
   *                   │  current │  to next │
   * }}}
   *
   * This is particularly valuable for drastic transitions like:
   *   - h7→h8: 2.5 → 0.1 (25x drop) - start throttling before the cliff
   *   - h21→h22: 2.0 → 5.0 (2.5x spike) - start opening up before the peak
   *
   * @param elapsedSeconds Seconds elapsed since day start
   * @param feedforwardWindow Fraction of bucket for feedforward (0.0-0.5, default 0.2)
   * @return Multiplier for base target rate (1.0 = average)
   */
  def relativeVolumeWithFeedforward(
      elapsedSeconds: Double,
      feedforwardWindow: Double = 0.2
  ): Double = {
    require(feedforwardWindow >= 0.0 && feedforwardWindow <= 0.5,
      "feedforwardWindow must be between 0.0 and 0.5")

    val bucket = bucketForElapsed(elapsedSeconds)
    val total = cachedTotal
    val avgVol = total / bucketCount
    if (avgVol <= 0) return 1.0

    val bucketStart = bucket * bucketDurationSec
    val fractionIntoBucket = math.min(1.0, (elapsedSeconds - bucketStart) / bucketDurationSec)

    val shape = currentShape
    val currentVol = shape(bucket)
    val nextBucket = (bucket + 1) % bucketCount
    val nextVol = shape(nextBucket)

    val effectiveVol = if (feedforwardWindow > 0 && fractionIntoBucket > (1.0 - feedforwardWindow)) {
      // In feedforward window - blend toward next bucket
      // blendFactor: 0.0 at start of window, 1.0 at end of bucket
      val windowStart = 1.0 - feedforwardWindow
      val blendFactor = (fractionIntoBucket - windowStart) / feedforwardWindow

      // Smooth blend using ease-in-out curve for gradual transition
      val smoothBlend = blendFactor * blendFactor * (3.0 - 2.0 * blendFactor)

      currentVol + smoothBlend * (nextVol - currentVol)
    } else {
      // Before feedforward window - use current bucket's sharp value
      currentVol
    }

    effectiveVol / avgVol
  }

  /** Get current shape based on day type (for pacing decisions) */
  private def currentShape: Array[Double] = if (todayIsWeekend) weekendShape else weekdayShape

  /** Get current cached total based on day type */
  private def cachedTotal: Double = if (todayIsWeekend) weekendTotal else weekdayTotal

  /** Bucket duration in seconds */
  def bucketDurationSec: Int = 86400 / bucketCount

  /** Get bucket index for elapsed seconds since day start */
  def bucketForElapsed(elapsedSeconds: Double): Int = {
    val bucket = (elapsedSeconds / bucketDurationSec).toInt
    math.max(0, math.min(bucket, bucketCount - 1))
  }

  /**
   * Get upcoming transition info for the current bucket.
   *
   * Returns the ratio of next bucket volume to current bucket volume,
   * which indicates how drastic the upcoming transition will be.
   *
   * @param elapsedSeconds Seconds elapsed since day start
   * @return (nextBucketRatio, fractionUntilTransition)
   *         - nextBucketRatio: next/current volume (>1 = traffic increasing, <1 = decreasing)
   *         - fractionUntilTransition: 0.0-1.0, how much of bucket remains
   */
  def upcomingTransition(elapsedSeconds: Double): (Double, Double) = {
    val bucket = bucketForElapsed(elapsedSeconds)
    val bucketStart = bucket * bucketDurationSec
    val fractionIntoBucket = math.min(1.0, (elapsedSeconds - bucketStart) / bucketDurationSec)
    val fractionUntilTransition = 1.0 - fractionIntoBucket

    val shape = currentShape
    val currentVol = shape(bucket)
    val nextBucket = (bucket + 1) % bucketCount
    val nextVol = shape(nextBucket)

    val ratio = if (currentVol > 0) nextVol / currentVol else 1.0
    (ratio, fractionUntilTransition)
  }

  /** Get raw bucket volumes for current day type (for persistence/debugging) */
  def volumes: Array[Double] = currentShape.clone()

  /** Get weekday shape volumes (for persistence) */
  def weekdayVolumes: Array[Double] = weekdayShape.clone()

  /** Get weekend shape volumes (for persistence) */
  def weekendVolumes: Array[Double] = weekendShape.clone()

  /** Get current EMA of bucket requests (for persistence) */
  def emaBucketRequestsValue: Double = emaBucketRequests

  /** Get the current bucket being tracked (None if not yet started) */
  def currentBucketOpt: Option[Int] = if (currentBucket >= 0) Some(currentBucket) else None

  /**
   * Whether the tracker has completed warmup (seen enough bucket updates).
   *
   * @return true if warmup is complete (bucketCount updates have occurred)
   */
  def isWarmedUp: Boolean = bucketUpdateCount >= warmupThreshold

  /**
   * Number of bucket updates completed (for monitoring warmup progress).
   */
  def updateCount: Int = bucketUpdateCount

  /**
   * Volatility metric measuring how drastic the traffic shape variations are.
   *
   * Returns the coefficient of variation (stddev / mean) of bucket volumes,
   * normalized so that uniform traffic (no variation) returns 0.0.
   *
   * Typical values:
   *  - `0.0 - 0.3`: Low volatility (smooth shape, values close together)
   *  - `0.3 - 0.7`: Medium volatility (typical daily patterns)
   *  - `0.7 - 1.5`: High volatility (drastic swings like 0.0 to 5.0)
   *  - `> 1.5`: Extreme volatility (very spiky traffic)
   *
   * This can be used by [[AdaptivePacing]] to adjust PI gains:
   *  - Low volatility → lower gains for stability
   *  - High volatility → higher gains for faster adaptation
   *
   * @return Coefficient of variation (0.0 = uniform, higher = more variable)
   */
  def volatility: Double = {
    val total = cachedTotal
    val mean = total / bucketCount
    if (mean <= 0) return 0.0

    val shape = currentShape
    // Calculate standard deviation
    var sumSqDiff = 0.0
    var i = 0
    while (i < bucketCount) {
      val diff = shape(i) - mean
      sumSqDiff += diff * diff
      i += 1
    }
    val stddev = math.sqrt(sumSqDiff / bucketCount)

    // Coefficient of variation
    stddev / mean
  }

  /**
   * Maximum ratio between consecutive buckets.
   *
   * Measures how abrupt the transitions are between time periods.
   * High values indicate sharp spikes/drops that may require aggressive PI response.
   *
   * @return Maximum ratio between adjacent buckets (1.0 = no change, higher = sharper transitions)
   */
  def maxConsecutiveRatio: Double = {
    val shape = currentShape
    var maxRatio = 1.0
    var i = 0
    while (i < bucketCount - 1) {
      val curr = shape(i)
      val next = shape(i + 1)
      if (curr > 0 && next > 0) {
        val ratio = math.max(curr / next, next / curr)
        maxRatio = math.max(maxRatio, ratio)
      }
      i += 1
    }
    maxRatio
  }

  /**
   * Restore from persisted state including emaBucketRequests.
   *
   * @param volumes           Array of bucket volumes (must match bucketCount)
   * @param emaBucketRequests The EMA of bucket request counts
   */
  def restore(volumes: Array[Double], emaBucketRequests: Double): Unit = {
    restore(volumes)
    this.emaBucketRequests = emaBucketRequests
  }

  /**
   * Restore from persisted state (or pre-configured shape).
   *
   * When a shape is restored, it's considered "warmed up" since the pattern is known.
   * This is important for test scenarios with short days where learning from scratch
   * would take most of the day (23 bucket transitions at 25s each = 575s of 600s day).
   *
   * This restores the same shape to both weekday and weekend (legacy compatibility).
   * For separate shapes, use [[restoreBothShapes]].
   *
   * @param volumes Array of bucket volumes (must match bucketCount)
   */
  def restore(volumes: Array[Double]): Unit = {
    require(volumes.length == bucketCount, s"Expected $bucketCount volumes, got ${volumes.length}")
    // Restore same shape to both weekday and weekend (legacy compatibility)
    Array.copy(volumes, 0, weekdayShape, 0, bucketCount)
    Array.copy(volumes, 0, weekendShape, 0, bucketCount)
    weekdayTotal = weekdayShape.sum
    weekendTotal = weekendShape.sum
    // Mark as warmed up since we have a known pattern
    bucketUpdateCount = warmupThreshold
    // A restored shape is KNOWN: learning proceeds via the daily 20/80
    // rollover blend only. Leaving bootstrap intra-day learning on here
    // meant every restart day re-applied the per-bucket EMA on top of
    // the learned shape — up to 23 × α=0.1 pulls per day against the
    // intended 0.2 per day, quietly smearing the shape on each boot
    // (found 2026-07-13).
    useLegacyMode = false
  }

  /**
   * Restore separate weekday and weekend shapes with emaBucketRequests.
   *
   * @param weekdayVols       Weekday shape volumes (must match bucketCount)
   * @param weekendVols       Weekend shape volumes (must match bucketCount)
   * @param emaBucketRequests The EMA of bucket request counts
   */
  def restoreBothShapes(weekdayVols: Array[Double], weekendVols: Array[Double], emaBucketRequests: Double): Unit = {
    restoreBothShapes(weekdayVols, weekendVols)
    this.emaBucketRequests = emaBucketRequests
  }

  /**
   * Restore separate weekday and weekend shapes.
   *
   * @param weekdayVols Weekday shape volumes (must match bucketCount)
   * @param weekendVols Weekend shape volumes (must match bucketCount)
   */
  def restoreBothShapes(weekdayVols: Array[Double], weekendVols: Array[Double]): Unit = {
    require(weekdayVols.length == bucketCount, s"Expected $bucketCount weekday volumes, got ${weekdayVols.length}")
    require(weekendVols.length == bucketCount, s"Expected $bucketCount weekend volumes, got ${weekendVols.length}")
    Array.copy(weekdayVols, 0, weekdayShape, 0, bucketCount)
    Array.copy(weekendVols, 0, weekendShape, 0, bucketCount)
    weekdayTotal = weekdayShape.sum
    weekendTotal = weekendShape.sum
    bucketUpdateCount = warmupThreshold
    // Known shape → daily-blend learning only (see restore()).
    useLegacyMode = false
  }

  /**
   * Create a snapshot of current state for persistence.
   * Flushes pending updates to ensure snapshot is complete.
   */
  def toSnapshot: TrafficShapeSnapshot = TrafficShapeSnapshot.from(this)

  /** Reset to uniform distribution */
  def reset(): Unit = {
    java.util.Arrays.fill(weekdayShape, 1.0)
    java.util.Arrays.fill(weekendShape, 1.0)
    java.util.Arrays.fill(todayCount, 0L)
    weekdayTotal = bucketCount.toDouble
    weekendTotal = bucketCount.toDouble
    todayIsWeekend = false
    currentBucket = -1
    requestsInBucket = 0
    emaBucketRequests = 1.0
    bucketUpdateCount = 0
    useLegacyMode = true
  }

  /**
   * Debug string showing traffic shape as ASCII histogram.
   *
   * {{{
   * TrafficShape(24 buckets, weekday):
   *   00: ▂ 0.3   06: ▅ 0.7   12: █ 1.2   18: ▆ 0.9
   *   01: ▁ 0.2   07: ▆ 0.9   13: █ 1.1   19: ▅ 0.8
   *   ...
   * }}}
   */
  override def toString: String = {
    val shape = currentShape
    val maxVol = shape.max
    val bars = "▁▂▃▄▅▆▇█"
    val dayType = if (todayIsWeekend) "weekend" else "weekday"

    def barChar(vol: Double): Char = {
      if (maxVol <= 0) bars.head
      else {
        val idx = ((vol / maxVol) * (bars.length - 1)).toInt
        bars(math.max(0, math.min(idx, bars.length - 1)))
      }
    }

    val lines = shape.zipWithIndex.grouped(6).map { group =>
      group.map { case (vol, idx) =>
        f"$idx%02d: ${barChar(vol)} $vol%.2f"
      }.mkString("  ")
    }

    s"TrafficShape($bucketCount buckets, $dayType, α=$alpha):\n  ${lines.mkString("\n  ")}"
  }
}

object TrafficShapeTracker {

  /** Default EMA alpha: 0.1 means ~10 observations (days) to converge */
  val DefaultAlpha: Double = 0.1

  /** Create tracker with default settings (uniform distribution) */
  def apply(): TrafficShapeTracker = new TrafficShapeTracker()

  /** Create tracker with custom bucket count */
  def apply(bucketCount: Int): TrafficShapeTracker = new TrafficShapeTracker(bucketCount)

  /** Create tracker with custom settings */
  def apply(bucketCount: Int, alpha: Double): TrafficShapeTracker =
    new TrafficShapeTracker(bucketCount, alpha)

  /**
   * Create tracker from persisted snapshot.
   *
   * @param snapshot Previously saved state
   * @return Restored tracker, or new tracker if snapshot is incompatible
   */
  def fromSnapshot(snapshot: TrafficShapeSnapshot, interpolateVolumes: Boolean = false): TrafficShapeTracker = {
    val tracker = new TrafficShapeTracker(snapshot.bucketCount, snapshot.alpha, interpolateVolumes)
    // Check if we have separate weekday/weekend shapes
    (snapshot.weekdayVolumes, snapshot.weekendVolumes) match {
      case (Some(weekdayVols), Some(weekendVols))
          if weekdayVols.length == snapshot.bucketCount && weekendVols.length == snapshot.bucketCount =>
        tracker.restoreBothShapes(weekdayVols, weekendVols, snapshot.emaBucketRequests)
      case _ if snapshot.volumes.length == snapshot.bucketCount =>
        // Legacy: use single volumes for both shapes
        tracker.restore(snapshot.volumes, snapshot.emaBucketRequests)
      case _ =>
      // Incompatible snapshot, return tracker with defaults
    }
    tracker
  }
}

/**
 * Snapshot of traffic shape for persistence.
 *
 * Store this in SiteEntity or via DData for warm restart.
 * Typical update frequency: once per day or on graceful shutdown.
 *
 * @param bucketCount       Number of buckets (must match tracker on restore)
 * @param alpha             EMA alpha used (for reference)
 * @param volumes           Raw bucket volumes (legacy, for backward compatibility)
 * @param emaBucketRequests EMA of request counts per bucket (for normalization continuity)
 * @param updatedAt         When this snapshot was taken
 * @param weekdayVolumes    Weekday traffic shape (optional, for weekday/weekend separation)
 * @param weekendVolumes    Weekend traffic shape (optional, for weekday/weekend separation)
 */
final case class TrafficShapeSnapshot(
    bucketCount: Int,
    alpha: Double,
    volumes: Array[Double],
    emaBucketRequests: Double,
    updatedAt: Instant,
    weekdayVolumes: Option[Array[Double]] = None,
    weekendVolumes: Option[Array[Double]] = None
) {
  require(volumes.length == bucketCount)
  require(weekdayVolumes.forall(_.length == bucketCount), "weekdayVolumes must match bucketCount")
  require(weekendVolumes.forall(_.length == bucketCount), "weekendVolumes must match bucketCount")
}

object TrafficShapeSnapshot {

  /** Create snapshot from tracker (includes both weekday and weekend shapes) */
  def from(tracker: TrafficShapeTracker, now: Instant = Instant.now()): TrafficShapeSnapshot = {
    tracker.flush() // Ensure pending updates are applied
    TrafficShapeSnapshot(
      bucketCount = tracker.bucketCount,
      alpha = tracker.alpha,
      volumes = tracker.volumes, // Current shape for legacy compatibility
      emaBucketRequests = tracker.emaBucketRequestsValue,
      updatedAt = now,
      weekdayVolumes = Some(tracker.weekdayVolumes),
      weekendVolumes = Some(tracker.weekendVolumes)
    )
  }
}
