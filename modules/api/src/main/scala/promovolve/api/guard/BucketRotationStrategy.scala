package promovolve.api.guard

import scala.concurrent.duration.FiniteDuration

/** Manages time-based bucket rotation for windowed bloom filters. */
final class BucketRotationStrategy(val bucketMs: Long) {

  /** Calculate bucket ID for a given timestamp. */
  def bucketOf(timestampMs: Long): Long = bucketStartMs(timestampMs) / bucketMs

  /** Check if rotation is needed and return rotation info. */
  def checkRotation(currentBucketId: Long): Option[RotationInfo] = {
    val nowBucket = currentBucket
    if (nowBucket > currentBucketId) {
      val gap = nowBucket - currentBucketId
      Some(
        RotationInfo(
          newBucket = nowBucket,
          previousBucket = if (gap == 1) currentBucketId else nowBucket - 1,
          gap = gap,
          shouldPreserveCurrent = gap == 1
        )
      )
    } else None
  }

  /** Calculate the current bucket ID based on system time. */
  def currentBucket: Long = bucketStartMs(System.currentTimeMillis()) / bucketMs

  /** Get the start time (aligned to bucket boundary) for a given timestamp. */
  def bucketStartMs(timestampMs: Long): Long = timestampMs - (timestampMs % bucketMs)
}

/** Information about a bucket rotation event. */
final case class RotationInfo(
    newBucket: Long,
    previousBucket: Long,
    gap: Long,
    shouldPreserveCurrent: Boolean
)

/** Classifies the timing of an event relative to current time buckets. */
enum TimingClassification {
  case Current, Previous, EarlyNext, TooFuture, TooLate, OutOfWindow
}

/** Analyzes event timing against bucket windows and skew tolerances. */
final class EventTimingAnalyzer(
    strategy: BucketRotationStrategy,
    extendedSkew: FiniteDuration
) {

  /** Classify an event's timing relative to current bucket state. */
  def classifyTiming(
      eventTimeMs: Long,
      currentBucket: Long,
      previousBucket: Long
  ): TimingClassification = {
    val nowMs = System.currentTimeMillis()
    val eventBucket = strategy.bucketOf(eventTimeMs)
    val skewMs = extendedSkew.toMillis

    val tooFuture = eventTimeMs - nowMs > skewMs
    val tooLate = nowMs - eventTimeMs > skewMs

    if (tooFuture) TimingClassification.TooFuture
    else if (tooLate) TimingClassification.TooLate
    else if (eventBucket == currentBucket) TimingClassification.Current
    else if (eventBucket == previousBucket) TimingClassification.Previous
    else if (eventBucket == currentBucket + 1 && (eventTimeMs - nowMs) <= skewMs) {
      TimingClassification.EarlyNext
    } else TimingClassification.OutOfWindow
  }
}
