package promovolve.api.guard

import promovolve.common.BloomFilter

/** Immutable state container for the replay guard actor. */
final case class ReplayGuardState(
    currentBucket: Long,
    previousBucket: Long,
    bloomCurrent: BloomFilter,
    bloomPrevious: BloomFilter,
    addsSincePublish: Int = 0,
    publishInFlight: Boolean = false,
    lastPublishAtNs: Long = 0L,
    forceInitialPublish: Boolean = true,
    rotatedAtNs: Long = System.nanoTime(),
    firstGrantDone: Boolean = false
) {

  /** Process a validation request and return updated state with result. */
  def processValidation(
      nonce: Long,
      eventTimeMs: Long,
      analyzer: EventTimingAnalyzer
  ): (ReplayGuardState, Boolean) = {
    val timing = analyzer.classifyTiming(eventTimeMs, currentBucket, previousBucket)

    timing match {
      case TimingClassification.TooFuture | TimingClassification.TooLate | TimingClassification.OutOfWindow =>
        (this, false)

      case TimingClassification.Current | TimingClassification.EarlyNext =>
        if (bloomCurrent.maybeContains(nonce)) {
          (this, false)
        } else {
          bloomCurrent.add(nonce)
          val updatedFirstGrant = if (!firstGrantDone) {
            bloomPrevious.add(nonce)
            true
          } else firstGrantDone

          val newState = copy(
            addsSincePublish = addsSincePublish + 1,
            firstGrantDone = updatedFirstGrant
          )
          (newState, true)
        }

      case TimingClassification.Previous =>
        if (bloomPrevious.maybeContains(nonce)) {
          (this, false)
        } else {
          bloomPrevious.add(nonce)
          val newState = copy(addsSincePublish = addsSincePublish + 1)
          (newState, true)
        }
    }
  }

  /** Apply bucket rotation, returning new state. */
  def applyRotation(rotation: RotationInfo, config: GuardConfiguration): ReplayGuardState = {
    if (rotation.shouldPreserveCurrent) {
      bloomPrevious.loadFromArray(bloomCurrent.snapshotWords)
    } else {
      bloomPrevious.clear()
    }
    bloomCurrent.clear()

    copy(
      currentBucket = rotation.newBucket,
      previousBucket = rotation.previousBucket,
      addsSincePublish = 0,
      lastPublishAtNs = 0L,
      rotatedAtNs = System.nanoTime()
    )
  }

  /** Check if we should publish to DData. */
  def shouldPublish(config: GuardConfiguration): Boolean = {
    if (publishInFlight) false
    else if (forceInitialPublish) true
    else {
      val nowNs = System.nanoTime()
      val due = (nowNs - lastPublishAtNs) >= config.publishEvery.toNanos
      val enoughAdds = addsSincePublish >= config.publishMinAdds

      due && enoughAdds
    }
  }

  /** Mark publish as started. */
  def startPublish: ReplayGuardState =
    copy(publishInFlight = true, forceInitialPublish = false)

  /** Mark publish as completed. */
  def publishCompleted: ReplayGuardState =
    copy(publishInFlight = false, lastPublishAtNs = System.nanoTime(), addsSincePublish = 0)

  /** Force initial publish flag. */
  def withForceInitialPublish: ReplayGuardState =
    copy(forceInitialPublish = true)
}

object ReplayGuardState {

  /** Create initial state for a new replay guard. */
  def initial(config: GuardConfiguration, bucketStrategy: BucketRotationStrategy): ReplayGuardState = {
    val currentBucket = bucketStrategy.currentBucket
    val bloomCurrent = BloomFilter.create(config.expectedPerPart, config.fpr)
    val bloomPrevious = BloomFilter.create(config.expectedPerPart, config.fpr)

    ReplayGuardState(
      currentBucket = currentBucket,
      previousBucket = currentBucket - 1,
      bloomCurrent = bloomCurrent,
      bloomPrevious = bloomPrevious
    )
  }

  /** Load state from a snapshot. */
  def fromSnapshot(
      snapshot: SnapshotEnvelope,
      config: GuardConfiguration,
      bucketStrategy: BucketRotationStrategy
  ): Option[ReplayGuardState] = {
    val bloomCurrent = BloomFilter.create(config.expectedPerPart, config.fpr)
    val bloomPrevious = BloomFilter.create(config.expectedPerPart, config.fpr)

    if (snapshot.mBits == bloomCurrent.mBits && snapshot.k == bloomCurrent.k) {
      bloomCurrent.loadFromArray(snapshot.current)
      bloomPrevious.loadFromArray(snapshot.previous)

      Some(ReplayGuardState(
        currentBucket = snapshot.currentBucket,
        previousBucket = snapshot.prevBucket,
        bloomCurrent = bloomCurrent,
        bloomPrevious = bloomPrevious,
        rotatedAtNs = snapshot.rotatedAtNanos,
        forceInitialPublish = true
      ))
    } else None
  }
}
