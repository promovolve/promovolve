package promovolve.api.guard

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ EntityContext, EntityTypeKey }
import promovolve.CborSerializable
import promovolve.fraud.Suspect

import scala.concurrent.duration.*

/**
 * Protocol-invariant guard for the engagement chain
 * (docs/design/FRAUD_PREVENTION.md, Layer 1).
 *
 * Every serve mints imp/click/cta tracking tokens that all carry the
 * same reservation id (`rid`), so `rid` IS the chain key. This guard
 * records when each beacon ARRIVES at the server and enforces two
 * unforgeable invariants on the money/engagement events:
 *
 *   - CHAIN: a click must follow its impression; a CTA must follow its
 *     click. A beacon whose predecessor never arrived is marked
 *     `suspect(chain)`.
 *   - TIMING: the SERVER-SIDE delay between predecessor and event must
 *     clear a sub-human floor. An EXPAND that reaches the server
 *     milliseconds after the impression did is not a finger →
 *     `suspect(timing)`. Server timestamps, so the delta can't be
 *     forged by the client.
 *
 * Fail-open by design: state is in-memory and short-lived, so a pod
 * restart just means in-flight chains carry no mark (never a false
 * positive). Records TTL out; a per-partition size cap bounds memory.
 *
 * Sharded by `rid` hash so both beacons of a chain route to the same
 * entity regardless of which api pod they land on (the reader's
 * beacons are not guaranteed same-pod).
 */
object EngagementGuard {

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("engagement-guard")

  /** Tunables. Floors are deliberately conservative — a real human is
    * always well above them, so a mark is proof of automation, not a
    * borderline call (the design's "never poison honest stats" rule). */
  final case class Config(
      impToClickFloor: FiniteDuration = 100.millis,
      clickToCtaFloor: FiniteDuration = 100.millis,
      ttl: FiniteDuration = 30.minutes,
      maxPerPartition: Int = 200_000,
      sweepEvery: FiniteDuration = 1.minute
  )

  // ── Pure decision core (tested directly, no actor) ──────────────────

  /** One chain's server-arrival timestamps. */
  final case class Record(impAtMs: Long, clickAtMs: Option[Long])

  /**
   * Fold a click into the chain. Returns the (updated record, suspect
   * reason). No prior impression → chain; sub-floor delta → timing;
   * else clean. Idempotent-ish: a duplicate click keeps the earliest
   * clickAt (replay guard already dedups, but be defensive).
   */
  def onClick(prior: Option[Record], atMs: Long, floorMs: Long): (Record, Option[String]) =
    prior match {
      case None =>
        // Click with no recorded impression: either genuine automation
        // skipping the render beacon, or a lost impression beacon. Marked
        // — the design accepts this as a (soft) signal; Layer 2's ratios
        // separate lost-beacon noise from systematic abuse.
        (Record(atMs, Some(atMs)), Some(Suspect.Chain))
      case Some(r) =>
        val reason =
          if (atMs - r.impAtMs < floorMs) Some(Suspect.Timing)
          else None
        (r.copy(clickAtMs = r.clickAtMs.orElse(Some(atMs))), reason)
    }

  /**
   * Fold a CTA (tap-through) into the chain. A CTA must follow a click;
   * absent one → chain. Sub-floor click→cta delta → timing.
   */
  def onCta(prior: Option[Record], atMs: Long, floorMs: Long): Option[String] =
    prior match {
      case None                       => Some(Suspect.Chain) // no impression even
      case Some(Record(_, None))      => Some(Suspect.Chain) // impression but no click
      case Some(Record(_, Some(clk))) => if (atMs - clk < floorMs) Some(Suspect.Timing) else None
    }

  // ── Actor ───────────────────────────────────────────────────────────

  sealed trait Command extends CborSerializable

  /** Record an impression's server-arrival time (fire-and-forget). */
  final case class RecordImpression(rid: String, atMs: Long) extends Command

  /** Check a click against its impression; replies with the mark (or None). */
  final case class CheckClick(rid: String, atMs: Long, replyTo: ActorRef[Verdict]) extends Command

  /** Check a CTA against its click; replies with the mark (or None). */
  final case class CheckCta(rid: String, atMs: Long, replyTo: ActorRef[Verdict]) extends Command

  private case object Sweep extends Command

  final case class Verdict(suspectReason: Option[String]) extends CborSerializable

  def initBehavior(entityCtx: EntityContext[Command], config: Config): Behavior[Command] =
    apply(config)

  def apply(config: Config): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(Sweep, config.sweepEvery)
      val floorClick = config.impToClickFloor.toMillis
      val floorCta = config.clickToCtaFloor.toMillis
      val ttlMs = config.ttl.toMillis

      // Mutable map is safe: the entity's message loop is single-threaded.
      val records = scala.collection.mutable.LinkedHashMap.empty[String, Record]

      def capAndPut(rid: String, rec: Record): Unit = {
        records.put(rid, rec)
        // LinkedHashMap preserves insertion order → cheap oldest-eviction
        // when the partition is over its cap (memory backstop on top of TTL).
        while (records.size > config.maxPerPartition && records.nonEmpty)
          records.remove(records.head._1)
      }

      Behaviors.receiveMessage {
        case RecordImpression(rid, atMs) =>
          // First impression for the rid wins its timestamp; a duplicate
          // (replay) does not move it earlier/later.
          if (!records.contains(rid)) capAndPut(rid, Record(atMs, None))
          Behaviors.same

        case CheckClick(rid, atMs, replyTo) =>
          val (updated, reason) = onClick(records.get(rid), atMs, floorClick)
          capAndPut(rid, updated)
          replyTo ! Verdict(reason)
          Behaviors.same

        case CheckCta(rid, atMs, replyTo) =>
          replyTo ! Verdict(onCta(records.get(rid), atMs, floorCta))
          Behaviors.same

        case Sweep =>
          val cutoff = System.currentTimeMillis() - ttlMs
          val expired = records.iterator.collect { case (rid, r) if r.impAtMs < cutoff => rid }.toVector
          expired.foreach(records.remove)
          if (expired.nonEmpty)
            ctx.log.debug("EngagementGuard swept {} expired chains ({} live)", expired.size, records.size)
          Behaviors.same
      }
    }
  }
}
