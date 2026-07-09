package promovolve.api.guard

import scala.concurrent.duration.FiniteDuration

/** Configuration for the replay guard. */
final case class GuardConfiguration(
    expectedPerPart: Int = 50_000,
    fpr: Double = 1e-4,
    bucketMs: Long = 60_000L,
    maxSkew: FiniteDuration = FiniteDuration(5, "seconds"),
    publishEvery: FiniteDuration = FiniteDuration(250, "milliseconds"),
    publishMinAdds: Int = 64,
    bootMaxWait: FiniteDuration = FiniteDuration(150, "milliseconds")
) {
  lazy val extendedSkew: FiniteDuration = maxSkew

  def isValid: Boolean =
    expectedPerPart > 0 &&
    fpr > 0 && fpr < 1 &&
    bucketMs > 0 &&
    maxSkew.toNanos > 0 &&
    publishEvery.toNanos > 0 &&
    publishMinAdds > 0 &&
    bootMaxWait.toNanos > 0
}

object GuardConfiguration {

  /** Default configuration for tracking replay detection. */
  val default: GuardConfiguration = GuardConfiguration()

  /** Configuration suitable for testing. */
  val development: GuardConfiguration = GuardConfiguration(
    expectedPerPart = 1_000,
    bucketMs = 5_000L,
    publishEvery = FiniteDuration(100, "milliseconds")
  )
}
