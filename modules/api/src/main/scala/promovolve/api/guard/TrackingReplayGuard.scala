package promovolve.api.guard

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }

import scala.concurrent.duration.*

/**
 * Replay guard for tracking URL deduplication.
 *
 * Routes requests to sharded bloom filter entities.
 *
 * Timing derivation:
 * - urlValidityWindow: How long a tracking URL is valid (±3 min = 6 min span)
 * - Bloom bucket size: urlValidityWindow + 1 minute safety margin
 * - With 2 buckets (current + previous): coverage = 2× bucket size
 * - Example: 3 min validity → 4 min bucket → 8 min coverage > 6 min span ✓
 */
object TrackingReplayGuard {

  /**
   * Create a tracking replay guard with timing derived from URL validity.
   *
   * Bloom bucket = urlValidityWindow + 1 minute, giving 2 extra minutes
   * of coverage (since we check both current and previous buckets).
   *
   * @param sharding ClusterSharding instance
   * @param urlValidityWindow How long tracking URLs are valid (should match TrackRoutes.maxSkew)
   * @param partitions Number of partitions (should be power of 2)
   * @param expectedPerPartition Expected unique nonces per partition per bucket
   */
  def apply(
      sharding: ClusterSharding,
      urlValidityWindow: FiniteDuration = 3.minutes,
      partitions: Int = 64,
      expectedPerPartition: Int = 100_000
  ): Behavior[Command] = Behaviors.setup { ctx =>
    // Bloom bucket = urlValidityWindow + 1 min safety margin
    // URL validity span = ±maxSkew = 2× urlValidityWindow (e.g., ±3 min = 6 min)
    // Bloom coverage = 2× bucket (current + previous) = 2× (urlValidityWindow + 1 min)
    // Example: 3 min → bucket=4 min → coverage=8 min > 6 min validity ✓
    val bloomBucketMs = urlValidityWindow.toMillis + 60_000L // +1 min safety margin

    val config = GuardConfiguration(
      expectedPerPart = expectedPerPartition,
      bucketMs = bloomBucketMs,
      maxSkew = urlValidityWindow,
      publishEvery = 10.seconds,
      publishMinAdds = 100,
      bootMaxWait = 500.millis
    )

    val urlValiditySpan = urlValidityWindow.toMillis * 2 // ±maxSkew
    ctx.log.info(
      "TrackingReplayGuard: URL validity span={}ms, bloom bucket={}ms, coverage={}ms",
      urlValiditySpan,
      config.bucketMs,
      config.bucketMs * 2
    )

    initSharding(sharding, config)
    active(sharding, partitions)
  }

  private def initSharding(sharding: ClusterSharding, config: GuardConfiguration): Unit = {
    sharding.init(
      Entity(WindowedBloomReplayGuard.TypeKey) { entityCtx =>
        WindowedBloomReplayGuard.initBehavior(entityCtx, config)
      }
    )
  }

  private def active(sharding: ClusterSharding, partitions: Int): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Validate(nonce, replyTo) =>
          val part = computePartition(nonce, partitions)
          val entityId = s"replay|$part"
          val entityRef = sharding.entityRefFor(WindowedBloomReplayGuard.TypeKey, entityId)
          entityRef ! WindowedBloomReplayGuard.ValidateReplayAt(replyTo, nonce)
          Behaviors.same

        case ValidateKey(key, replyTo) =>
          val nonce = ReplayGuard.hash(key)
          ctx.self ! Validate(nonce, replyTo)
          Behaviors.same
      }
    }

  private def computePartition(nonce: Long, partitions: Int): Int =
    Math.floorMod(nonce.toInt ^ (nonce >> 32).toInt, partitions)

  sealed trait Command

  final case class Validate(nonce: Long, replyTo: ActorRef[Boolean]) extends Command

  final case class ValidateKey(key: String, replyTo: ActorRef[Boolean]) extends Command
}
