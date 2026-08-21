package promovolve.publisher

import org.slf4j.LoggerFactory

import java.time.{ Clock, LocalDate }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters.*

/**
 * In-memory tally of observed reader countries, per site per day.
 *
 * This class is the privacy boundary for tier 3 of
 * docs/design/GEOGRAPHIC_CONTEXT.md. The beacon path hands it a
 * `(siteId, country)` pair and nothing else — no IP, no URL, no creative,
 * no identifier of any kind — and it can only ever emit sums. There is no
 * method here that returns anything per-request, because there is no
 * per-request state to return.
 *
 * Counting in memory and flushing periodically is what keeps it that way:
 * writing a row per beacon would put a country next to a timestamp, and a
 * timestamped country is a far more revealing artefact than a daily total.
 *
 * Day boundaries are UTC. Settlement is local-day per entity, but this is
 * an audit statistic rather than money, and a single global boundary keeps
 * counts from several API pods addable without agreeing on a timezone.
 */
final class AudienceObservationCounter(clock: Clock = Clock.systemUTC()) {

  private val log = LoggerFactory.getLogger(getClass)

  // (siteId, country, day) -> count. LongAdder because every beacon on
  // every API pod thread lands here; this must never become the thing
  // that serialises the beacon path.
  private val counts = new ConcurrentHashMap[(String, String, LocalDate), LongAdder]()

  /** Highest number of distinct keys held before new ones are dropped. */
  private val MaxKeys = 50000

  /**
   * Record one observation. Fire-and-forget, non-blocking, never throws.
   *
   * `country` is expected to be ISO 3166-1 alpha-2; anything else is
   * ignored rather than counted, so a malformed row in the ASN dump cannot
   * invent a country.
   */
  def record(siteId: String, country: String): Unit =
    if (siteId.nonEmpty && country.length == 2) {
      val key = (siteId, country.toUpperCase, LocalDate.now(clock))
      val existing = counts.get(key)
      if (existing != null) existing.increment()
      else if (counts.size < MaxKeys) {
        counts.computeIfAbsent(key, _ => new LongAdder()).increment()
      } else {
        // A cardinality guard, not a correctness one: sites × countries ×
        // days is bounded in practice, and an unbounded map on the beacon
        // path is how a memory leak gets shipped. Dropping is safe — this
        // is a sampled audit statistic, and the flush interval keeps the
        // live key set small.
        log.warn("AudienceObservationCounter at {} keys — dropping new observations until the next flush", MaxKeys)
      }
    }

  /**
   * Take everything counted so far and reset.
   *
   * Drain-and-reset rather than read-then-clear: a beacon landing between
   * the two would be lost. Counts removed here are owned by the caller —
   * if its write fails they are gone, which is the right trade for an
   * audit statistic that must never block a beacon.
   */
  def drain(): Vector[AudienceObservation] = {
    val snapshot = Vector.newBuilder[AudienceObservation]
    counts.keySet().asScala.toVector.foreach { key =>
      val adder = counts.remove(key)
      if (adder != null) {
        val n = adder.sum()
        if (n > 0) snapshot += AudienceObservation(key._1, key._2, key._3, n)
      }
    }
    snapshot.result()
  }

  /** Distinct keys currently held. Diagnostics only. */
  def size: Int = counts.size

  /** Drain and persist. Failures are logged, never propagated to a beacon. */
  def flush(repo: AudienceObservationRepo)(using ec: ExecutionContext): Future[Unit] = {
    val batch = drain()
    if (batch.isEmpty) Future.successful(())
    else
      repo.addCounts(batch).recover { case ex =>
        log.warn("Audience observation flush failed, {} row(s) dropped: {}", batch.size, ex.toString)
      }
  }
}
