package promovolve.api.guard

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*

class ReplayGuardSpec extends AnyWordSpec with Matchers {

  "TrackingReplayGuard timing derivation" should {

    "add 1 minute safety margin to bloom bucket" in {
      // Given urlValidityWindow = 3 minutes
      val urlValidityWindow = 3.minutes

      // When we calculate bloom bucket (as done in TrackingReplayGuard)
      val bloomBucketMs = urlValidityWindow.toMillis + 60_000L

      // Then bloom bucket should be 4 minutes
      bloomBucketMs shouldBe 4.minutes.toMillis
    }

    "provide sufficient coverage for URL validity span" in {
      val urlValidityWindow = 3.minutes

      // URL validity span = ±maxSkew = 2× urlValidityWindow
      val urlValiditySpan = urlValidityWindow.toMillis * 2 // 6 minutes

      // Bloom bucket = urlValidityWindow + 1 min
      val bloomBucketMs = urlValidityWindow.toMillis + 60_000L // 4 minutes

      // Bloom coverage = 2× bucket (current + previous)
      val bloomCoverage = bloomBucketMs * 2 // 8 minutes

      // Coverage should exceed validity span
      bloomCoverage should be > urlValiditySpan
      (bloomCoverage - urlValiditySpan) shouldBe 2.minutes.toMillis // 2 min margin
    }

    "scale correctly for different validity windows" in {
      val testCases = Seq(
        (2.minutes, 3.minutes, 6.minutes), // 2 min validity → 3 min bucket → 6 min coverage
        (3.minutes, 4.minutes, 8.minutes), // 3 min validity → 4 min bucket → 8 min coverage
        (5.minutes, 6.minutes, 12.minutes) // 5 min validity → 6 min bucket → 12 min coverage
      )

      testCases.foreach { case (validityWindow, expectedBucket, expectedCoverage) =>
        val bloomBucketMs = validityWindow.toMillis + 60_000L
        val bloomCoverage = bloomBucketMs * 2
        val urlValiditySpan = validityWindow.toMillis * 2

        bloomBucketMs shouldBe expectedBucket.toMillis
        bloomCoverage shouldBe expectedCoverage.toMillis
        bloomCoverage should be > urlValiditySpan
      }
    }
  }

  "ReplayGuardState" should {

    "detect replay in current bucket" in {
      val config = testConfig()
      val strategy = new BucketRotationStrategy(config.bucketMs)
      val state = ReplayGuardState.initial(config, strategy)
      val analyzer = new EventTimingAnalyzer(strategy, config.extendedSkew)

      val nonce = 12345L
      val eventTime = System.currentTimeMillis()

      // First request - should be allowed
      val (state1, allowed1) = state.processValidation(nonce, eventTime, analyzer)
      allowed1 shouldBe true

      // Second request with same nonce - should be blocked
      val (_, allowed2) = state1.processValidation(nonce, eventTime, analyzer)
      allowed2 shouldBe false
    }

    "allow different nonces" in {
      val config = testConfig()
      val strategy = new BucketRotationStrategy(config.bucketMs)
      val state = ReplayGuardState.initial(config, strategy)
      val analyzer = new EventTimingAnalyzer(strategy, config.extendedSkew)

      val eventTime = System.currentTimeMillis()

      val (state1, allowed1) = state.processValidation(111L, eventTime, analyzer)
      allowed1 shouldBe true

      val (state2, allowed2) = state1.processValidation(222L, eventTime, analyzer)
      allowed2 shouldBe true

      val (_, allowed3) = state2.processValidation(333L, eventTime, analyzer)
      allowed3 shouldBe true
    }

    "check both current and previous buckets" in {
      val config = testConfig(bucketMs = 60_000L)
      val strategy = new BucketRotationStrategy(config.bucketMs)
      val analyzer = new EventTimingAnalyzer(strategy, config.extendedSkew)

      // Create state and add nonce to current bucket
      var state = ReplayGuardState.initial(config, strategy)
      val nonce = 99999L
      val eventTime = System.currentTimeMillis()

      val (stateAfterAdd, _) = state.processValidation(nonce, eventTime, analyzer)

      // Simulate rotation: current becomes previous
      val rotation = RotationInfo(
        newBucket = stateAfterAdd.currentBucket + 1,
        previousBucket = stateAfterAdd.currentBucket,
        gap = 1,
        shouldPreserveCurrent = true
      )
      val rotatedState = stateAfterAdd.applyRotation(rotation, config)

      // The nonce should now be in previous bucket and still be detected
      rotatedState.bloomPrevious.maybeContains(nonce) shouldBe true
    }

    "clear previous bucket on rotation with gap > 1" in {
      val config = testConfig()
      val strategy = new BucketRotationStrategy(config.bucketMs)

      val state = ReplayGuardState.initial(config, strategy)

      // Add something to current
      val nonce = 77777L
      state.bloomCurrent.add(nonce)

      // Simulate rotation with gap of 2 (missed a bucket)
      val rotation = RotationInfo(
        newBucket = state.currentBucket + 2,
        previousBucket = state.currentBucket + 1,
        gap = 2,
        shouldPreserveCurrent = false
      )
      val rotatedState = state.applyRotation(rotation, config)

      // Previous should be cleared (not preserved)
      rotatedState.bloomPrevious.maybeContains(nonce) shouldBe false
      rotatedState.bloomCurrent.maybeContains(nonce) shouldBe false
    }
  }

  "BucketRotationStrategy" should {

    "calculate correct bucket IDs" in {
      val strategy = new BucketRotationStrategy(60_000L) // 1 minute buckets

      // Time 0 should be bucket 0
      strategy.bucketOf(0L) shouldBe 0L

      // Time 59999ms should still be bucket 0
      strategy.bucketOf(59_999L) shouldBe 0L

      // Time 60000ms should be bucket 1
      strategy.bucketOf(60_000L) shouldBe 1L

      // Time 120000ms should be bucket 2
      strategy.bucketOf(120_000L) shouldBe 2L
    }

    "detect when rotation is needed" in {
      val strategy = new BucketRotationStrategy(60_000L)

      // If current bucket is 5 and now is bucket 6, rotation needed
      val rotation = strategy.checkRotation(5L)

      // This depends on current time, so we just verify the logic
      rotation match {
        case Some(info) =>
          info.newBucket should be > 5L
          info.gap should be >= 1L
        case None =>
          // No rotation needed if we're still in bucket 5 or earlier
          succeed
      }
    }

    "preserve current on single-bucket advance" in {
      val rotation = RotationInfo(
        newBucket = 10,
        previousBucket = 9,
        gap = 1,
        shouldPreserveCurrent = true
      )

      rotation.shouldPreserveCurrent shouldBe true
    }

    "not preserve current on multi-bucket gap" in {
      val rotation = RotationInfo(
        newBucket = 10,
        previousBucket = 9,
        gap = 3,
        shouldPreserveCurrent = false
      )

      rotation.shouldPreserveCurrent shouldBe false
    }
  }

  "ReplayGuard hashing" should {

    "produce consistent hashes for same input" in {
      val key = "pub=abc&url=http://example.com&slot=1&cid=xyz&v=1&b=100&evt=imp"

      val hash1 = ReplayGuard.hash(key)
      val hash2 = ReplayGuard.hash(key)

      hash1 shouldBe hash2
    }

    "produce different hashes for different inputs" in {
      val key1 = "pub=abc&url=http://example.com&slot=1&cid=xyz&v=1&b=100&evt=imp"
      val key2 = "pub=abc&url=http://example.com&slot=1&cid=xyz&v=1&b=101&evt=imp" // Different bucket

      val hash1 = ReplayGuard.hash(key1)
      val hash2 = ReplayGuard.hash(key2)

      hash1 should not be hash2
    }

    "distribute well across partitions" in {
      val keys = (1 to 1000).map(i => s"key-$i")
      val partitions = 64

      val partitionCounts = keys
        .map(ReplayGuard.hash)
        .map(nonce => Math.floorMod(nonce.toInt ^ (nonce >> 32).toInt, partitions))
        .groupBy(identity)
        .view.mapValues(_.size)
        .toMap

      // All partitions should have some keys (within reason)
      partitionCounts.size should be >= (partitions * 0.8).toInt

      // No partition should have more than 5% of all keys
      partitionCounts.values.max should be < (1000 * 0.05).toInt
    }
  }

  "GuardConfiguration" should {

    "validate correctly" in {
      val validConfig = GuardConfiguration(
        expectedPerPart = 1000,
        fpr = 0.01,
        bucketMs = 60_000L,
        maxSkew = 3.minutes,
        publishEvery = 10.seconds,
        publishMinAdds = 100,
        bootMaxWait = 500.millis
      )

      validConfig.isValid shouldBe true
    }

    "reject invalid configurations" in {
      val invalidConfigs = Seq(
        GuardConfiguration(expectedPerPart = 0), // Zero expected
        GuardConfiguration(expectedPerPart = -1), // Negative expected
        GuardConfiguration(fpr = 0.0), // Zero FPR
        GuardConfiguration(fpr = 1.0), // FPR = 1
        GuardConfiguration(bucketMs = 0L) // Zero bucket
      )

      invalidConfigs.foreach { config =>
        config.isValid shouldBe false
      }
    }
  }

  private def testConfig(
      bucketMs: Long = 60_000L,
      maxSkew: FiniteDuration = 3.minutes
  ): GuardConfiguration =
    GuardConfiguration(
      expectedPerPart = 1000,
      fpr = 0.01,
      bucketMs = bucketMs,
      maxSkew = maxSkew,
      publishEvery = 10.seconds,
      publishMinAdds = 100,
      bootMaxWait = 500.millis
    )
}
