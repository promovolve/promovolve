package promovolve.api.guard

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Thin routing facade over the sharded [[EngagementGuard]]
 * (docs/design/FRAUD_PREVENTION.md, Layer 1). Partitions by `rid` so a
 * chain's beacons always reach the same entity, and translates the
 * beacon call sites' needs into tell/ask.
 *
 * All checks fail OPEN: an entity ask that times out or errors returns
 * None (no mark), because a guard hiccup must never fabricate a fraud
 * signal on honest traffic.
 */
final class EngagementChecker(
    sharding: ClusterSharding,
    partitions: Int,
    askTimeout: FiniteDuration
)(using system: ActorSystem[?]) {

  private given ExecutionContext = system.executionContext
  private given Timeout = Timeout(askTimeout)

  private def refFor(rid: String) = {
    val part = Math.floorMod(ReplayGuard.hash(rid).toInt, partitions)
    sharding.entityRefFor(EngagementGuard.TypeKey, s"eng|$part")
  }

  /** Record an impression's arrival (fire-and-forget). */
  def recordImpression(rid: String, atMs: Long): Unit =
    refFor(rid) ! EngagementGuard.RecordImpression(rid, atMs)

  /** Mark for a click: chain/timing suspect reason, or None. */
  def checkClick(rid: String, atMs: Long): Future[Option[String]] =
    refFor(rid)
      .ask[EngagementGuard.Verdict](EngagementGuard.CheckClick(rid, atMs, _))
      .map(_.suspectReason)
      .recover { case _ => None }

  /** Mark for a CTA: chain/timing suspect reason, or None. */
  def checkCta(rid: String, atMs: Long): Future[Option[String]] =
    refFor(rid)
      .ask[EngagementGuard.Verdict](EngagementGuard.CheckCta(rid, atMs, _))
      .map(_.suspectReason)
      .recover { case _ => None }
}
