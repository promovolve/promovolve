package promovolve.publisher.delivery.pacing

import promovolve.publisher.delivery.TrafficShapeTracker

/**
 * Tracks request rate and traffic patterns for pacing decisions.
 *
 * Provides synchronous rate tracking using EMA (Exponential Moving Average)
 * and integrates with TrafficShapeTracker for pattern learning.
 *
 * == Rate Tracking ==
 * Uses a sliding window approach:
 * - Counts requests within a 1-second window
 * - When window elapses, calculates instant rate and updates EMA
 * - EMA smoothing prevents spiky rate estimates
 *
 * == Traffic Shape Integration ==
 * Records requests to TrafficShapeTracker for learning hourly patterns.
 * Time is scaled to standard 24h (86400s) for compatibility with
 * traffic shape buckets regardless of simulated day duration.
 *
 * @param rateWindowMs Window duration for rate calculation (default 1000ms)
 * @param rateEmaAlpha EMA smoothing factor (default 0.3)
 */
class TrafficObserver(
    rateWindowMs: Long = TrafficObserver.DefaultRateWindowMs,
    rateEmaAlpha: Double = TrafficObserver.DefaultRateEmaAlpha
) {
  require(rateWindowMs > 0, s"rateWindowMs must be > 0, got $rateWindowMs")
  require(rateEmaAlpha > 0 && rateEmaAlpha <= 1, s"rateEmaAlpha must be in (0, 1], got $rateEmaAlpha")

  // Rate tracking state
  @volatile private var windowStartMs: Long = 0
  @volatile private var requestsInWindow: Long = 0
  @volatile private var _smoothedRate: Double = 0.0

  /** Current smoothed request rate (requests/second) */
  def smoothedRate: Double = _smoothedRate

  /**
   * Record a request arrival and update rate tracking.
   *
   * This method should be called synchronously on each request
   * BEFORE any async operations to ensure accurate rate measurement.
   *
   * @param nowMs Current time in milliseconds
   * @return Updated smoothed rate
   */
  def recordRequest(nowMs: Long): Double = synchronized {
    // Initialize window on first call
    val effectiveWindowStart = if (windowStartMs == 0) nowMs else windowStartMs

    // Count this request
    requestsInWindow += 1

    // Check if window has elapsed
    val windowElapsed = nowMs - effectiveWindowStart
    if (windowElapsed >= rateWindowMs) {
      // Calculate instant rate and update EMA
      val windowSec = windowElapsed / 1000.0
      val instantRate = requestsInWindow / windowSec
      _smoothedRate = rateEmaAlpha * instantRate + (1 - rateEmaAlpha) * _smoothedRate

      // Reset window
      windowStartMs = nowMs
      requestsInWindow = 0
    }

    _smoothedRate
  }

  /**
   * Record request for traffic shape learning.
   *
   * @param clock DayClock for time calculations
   * @param tracker TrafficShapeTracker to record to
   */
  def recordForTrafficShape(clock: DayClock, tracker: TrafficShapeTracker): Unit = {
    tracker.recordRequest(clock.scaledElapsedSeconds)
  }

  /** Reset all tracking state for a new day. */
  def reset(): Unit = synchronized {
    windowStartMs = 0
    requestsInWindow = 0
    _smoothedRate = 0.0
  }

  /**
   * Get current state as immutable snapshot.
   *
   * Useful for passing state through actor behavior transitions.
   */
  def snapshot: TrafficObserverState = synchronized {
    TrafficObserverState(windowStartMs, requestsInWindow, _smoothedRate)
  }

  /**
   * Restore state from snapshot.
   *
   * Used when recreating observer from actor state.
   */
  def restore(state: TrafficObserverState): Unit = synchronized {
    windowStartMs = state.windowStartMs
    requestsInWindow = state.requestsInWindow
    _smoothedRate = state.smoothedRate
  }
}

/** Immutable snapshot of TrafficObserver state. */
final case class TrafficObserverState(
    windowStartMs: Long,
    requestsInWindow: Long,
    smoothedRate: Double
)

object TrafficObserver {

  /** Default rate window: 1 second */
  val DefaultRateWindowMs: Long = 1000

  /** Default EMA smoothing factor */
  val DefaultRateEmaAlpha: Double = 0.3

  /** Create observer with default settings */
  def apply(): TrafficObserver = new TrafficObserver()

  /** Create observer with custom settings */
  def apply(rateWindowMs: Long, rateEmaAlpha: Double): TrafficObserver =
    new TrafficObserver(rateWindowMs, rateEmaAlpha)

  /** Create observer from saved state */
  def fromState(state: TrafficObserverState): TrafficObserver = {
    val observer = new TrafficObserver()
    observer.restore(state)
    observer
  }
}
