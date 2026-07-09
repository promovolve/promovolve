package promovolve.publisher.delivery.pacing

import java.time.{ Duration, Instant, LocalDate, ZoneOffset }

/**
 * Time source abstraction for day boundary detection.
 *
 * Enables simulated days for testing without modifying CampaignEntity's
 * real UTC budget accounting. AdServer uses DayClock for pacing calculations
 * while CampaignEntity continues to use real calendar days.
 *
 * == Usage ==
 * {{{
 * // Production: real UTC calendar days
 * val clock = DayClock.real()
 *
 * // Testing: 10-minute simulated day
 * val clock = DayClock.simulated(600, Instant.now())
 *
 * // Check for day rollover
 * if (clock.hasNewDayStarted) {
 *   pacingStrategy.reset()
 *   clock.rollDay()
 * }
 * }}}
 */
trait DayClock {

  /** Current time */
  def now(): Instant

  /** Duration of a "day" in seconds (86400 for real, configurable for simulated) */
  def dayDurationSeconds: Int

  /** Start of the current day */
  def dayStart: Instant

  /** Fraction of day elapsed [0, 1] */
  def dayFraction: Double =
    math.min(1.0, elapsedSeconds / dayDurationSeconds)

  /** Seconds elapsed since day start */
  def elapsedSeconds: Double =
    Duration.between(dayStart, now()).toMillis / 1000.0

  /** Hours remaining in the day */
  def remainingHours: Double =
    math.max(0, (dayDurationSeconds / 3600.0) - elapsedHours)

  /** Hours elapsed since day start */
  def elapsedHours: Double = elapsedSeconds / 3600.0

  /** Check if elapsed time exceeds day duration (for simulated days) */
  def hasNewDayStarted: Boolean

  /** Trigger day rollover - resets dayStart to now (for simulated days) */
  def rollDay(): Unit

  /**
   * Scale elapsed seconds to standard 24h for traffic shape compatibility.
   *
   * TrafficShapeTracker uses 24 hourly buckets based on 86400s.
   * This scales elapsed time so a 10-minute day maps to the full 24h bucket range.
   */
  def scaledElapsedSeconds: Double =
    elapsedSeconds * (86400.0 / dayDurationSeconds)
}

object DayClock {

  /**
   * Create clock based on configuration.
   *
   * Returns real clock if dayDurationSeconds is 86400, simulated otherwise.
   */
  def forConfig(dayDurationSeconds: Int): DayClock =
    if (dayDurationSeconds == 86400) real()
    else simulated(dayDurationSeconds, Instant.now())

  /**
   * Create a real clock using UTC calendar days.
   *
   * Day boundaries are at UTC midnight. Use this for production.
   */
  def real(): DayClock = new RealDayClock()

  /**
   * Create a simulated clock with configurable day duration.
   *
   * @param dayDurationSeconds Duration of a "day" in seconds (e.g., 600 for 10 minutes)
   * @param startInstant       When the first simulated day started
   */
  def simulated(dayDurationSeconds: Int, startInstant: Instant): DayClock =
    new SimulatedDayClock(dayDurationSeconds, startInstant)
}

/**
 * Real clock using system time and UTC calendar days.
 *
 * Day boundaries are at UTC midnight. hasNewDayStarted checks if the
 * current UTC date differs from the day start date.
 */
private[pacing] class RealDayClock extends DayClock {
  @volatile private var _dayStart: Instant = utcMidnightToday()

  override def now(): Instant = Instant.now()

  override def dayDurationSeconds: Int = 86400

  override def dayStart: Instant = _dayStart

  override def hasNewDayStarted: Boolean = {
    val startDate = LocalDate.ofInstant(_dayStart, ZoneOffset.UTC)
    val todayDate = LocalDate.now(ZoneOffset.UTC)
    todayDate != startDate
  }

  override def rollDay(): Unit = {
    _dayStart = utcMidnightToday()
  }

  private def utcMidnightToday(): Instant =
    LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)
}

/**
 * Simulated clock with configurable day duration.
 *
 * Day boundaries occur when elapsed time exceeds dayDurationSeconds.
 * Rolling the day resets the start time to now.
 *
 * @param dayDurationSeconds Duration of a simulated "day" in seconds
 * @param initialStart       When the first simulated day started
 */
private[pacing] class SimulatedDayClock(
    override val dayDurationSeconds: Int,
    initialStart: Instant
) extends DayClock {
  require(dayDurationSeconds > 0, s"dayDurationSeconds must be > 0, got $dayDurationSeconds")

  @volatile private var _dayStart: Instant = initialStart

  override def dayStart: Instant = _dayStart

  override def hasNewDayStarted: Boolean = {
    val elapsed = Duration.between(_dayStart, now()).toSeconds
    elapsed >= dayDurationSeconds
  }

  override def now(): Instant = Instant.now()

  override def rollDay(): Unit = {
    _dayStart = Instant.now()
  }
}
