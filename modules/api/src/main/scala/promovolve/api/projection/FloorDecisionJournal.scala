package promovolve.api.projection

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.scaladsl.{ Keep, Sink, Source }
import slick.jdbc.PostgresProfile.api.*
import java.time.Instant
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

/**
 * One persisted row per completed sweep cycle. Captures the argmax pick
 * and the per-candidate evidence that produced it. Parallel to the
 * existing `tracking_events` journal — same buffered-queue write
 * pattern, separate table because the schema and lifecycle differ.
 *
 * The dashboard's "Optimized floor over time" chart reads from this
 * table so history survives cluster restarts and the in-memory
 * ring buffer's natural rollover.
 *
 * `candidatesJson` is an opaque JSON blob (same shape the API's
 * `/sweep-evidence` endpoint emits) so we can later answer "what was
 * revenue at $X on date Y" without schema migrations.
 */
case class FloorDecision(
    sequenceNr: Long,
    siteId: String,
    ts: Instant,
    argmaxFloor: BigDecimal,
    prevArgmax: Option[BigDecimal],
    cycleRevenue: Option[BigDecimal],
    cycleImps: Option[Long],
    candidatesJson: Option[String],
    category: Option[String] = None
)

/**
 * Append-only writer for floor_decisions. Pekko Streams queue +
 * batched DB writes — same shape as `TrackingEventJournal`. Optional
 * dependency on `creativeRepo` etc. isn't needed here because the
 * decision row is fully self-contained from what `SiteEntity` already
 * has at the moment of the argmax pick.
 */
class FloorDecisionJournal(db: slick.jdbc.JdbcBackend#Database)(
    using
    ec: ExecutionContext,
    system: ActorSystem[?]
) extends promovolve.publisher.FloorDecisionWriter {

  import FloorDecisionJournal.*

  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  // Bounded queue with backpressure — drop newest if the writer can't
  // keep up. Cycle picks are infrequent (one every ~4 min in fast-sim,
  // ~25 min in real-day pacing) so the buffer should never fill in
  // practice. 1000 is generous for hundreds of sites.
  private val (queue, done) = Source
    .queue[FloorDecision](1000)
    .groupedWithin(50, 1.second)
    .mapAsync(2) { batch =>
      db.run(floorDecisions ++= batch)
        .map { _ =>
          log.debug("Wrote batch of {} floor decisions", batch.size)
        }
        .recover { case ex =>
          log.warn("Failed to write batch of {} floor decisions: {}", batch.size, ex.getMessage)
        }
    }
    .toMat(Sink.ignore)(Keep.both)
    .run()

  /** Enqueue a floor decision for persistence. Non-blocking. */
  def writeDecision(d: FloorDecision): Unit = {
    queue.offer(d) match {
      case QueueOfferResult.Dropped =>
        log.warn("Floor decision dropped due to buffer overflow")
      case QueueOfferResult.Failure(ex) =>
        log.warn("Failed to enqueue floor decision: {}", ex.getMessage)
      case _ => // ok
    }
  }

  /**
   * Implements `FloorDecisionWriter`. SiteEntity (in core, no JSON deps)
   * passes primitives + a `Vector[FloorDecisionCandidate]`; we render
   * the candidates as a compact JSON array for storage.
   */
  override def writeDecision(
      siteId: String,
      ts: Instant,
      argmaxFloor: Double,
      prevArgmax: Option[Double],
      cycleRevenue: Double,
      cycleImps: Long,
      candidates: Vector[promovolve.publisher.FloorDecisionCandidate],
      category: Option[String]
  ): Unit = {
    val candidatesJson: Option[String] = if (candidates.isEmpty) None
    else {
      val sb = new StringBuilder("[")
      var first = true
      candidates.foreach { c =>
        if (!first) sb.append(",")
        first = false
        sb.append("{\"floor\":").append(f"${c.floor}%.4f")
          .append(",\"revenue\":").append(f"${c.revenue}%.4f")
          .append(",\"impressions\":").append(c.impressions)
          .append("}")
      }
      sb.append("]")
      Some(sb.toString)
    }

    val decision = FloorDecision(
      sequenceNr = 0L, // auto-assigned by DB
      siteId = siteId,
      ts = ts,
      argmaxFloor = BigDecimal(argmaxFloor),
      prevArgmax = prevArgmax.map(BigDecimal(_)),
      cycleRevenue = Some(BigDecimal(cycleRevenue)),
      cycleImps = Some(cycleImps),
      candidatesJson = candidatesJson,
      category = category
    )
    writeDecision(decision)
  }

  /**
   * Drain on shutdown so we don't lose the last batch. Mirrors
   * TrackingEventJournal.shutdown().
   */
  def shutdown(): Future[Done] = {
    queue.complete()
    done
  }

  /**
   * Read recent decisions for a site, newest-first, capped by `limit`.
   * Used by the `/sweep-history` endpoint.
   */
  def recent(siteId: String, limit: Int): Future[Seq[FloorDecision]] = {
    db.run(
      floorDecisions
        .filter(_.siteId === siteId)
        .sortBy(_.ts.desc)
        .take(limit.max(1).min(1000))
        .result
    )
  }

  /**
   * Read all decisions for a site within a single calendar day of the
   * given zone, newest-first, capped at `limit`. Used by the dashboard's
   * date-picker filter (the platform passes the viewer's display zone).
   */
  def recentInDay(
      siteId: String,
      date: java.time.LocalDate,
      zone: java.time.ZoneId,
      limit: Int
  ): Future[Seq[FloorDecision]] = {
    // The caller's calendar day — the dashboard passes the viewer's
    // display zone so the date picker means the viewer's day, not UTC's.
    val dayStart = date.atStartOfDay(zone).toInstant
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant
    db.run(
      floorDecisions
        .filter(_.siteId === siteId)
        .filter(_.ts >= dayStart)
        .filter(_.ts < dayEnd)
        .sortBy(_.ts.desc)
        .take(limit.max(1).min(1000))
        .result
    )
  }
}

object FloorDecisionJournal {

  import promovolve.SlickMappers.given

  class FloorDecisionsTable(tag: Tag) extends Table[FloorDecision](tag, "floor_decisions") {
    def sequenceNr = column[Long]("sequence_nr", O.AutoInc)
    def siteId = column[String]("site_id")
    def ts = column[Instant]("ts")
    def argmaxFloor = column[BigDecimal]("argmax_floor")
    def prevArgmax = column[Option[BigDecimal]]("prev_argmax")
    def cycleRevenue = column[Option[BigDecimal]]("cycle_revenue")
    def cycleImps = column[Option[Long]]("cycle_imps")
    def candidatesJson = column[Option[String]]("candidates_json")
    def category = column[Option[String]]("category")

    def * = (
      sequenceNr,
      siteId,
      ts,
      argmaxFloor,
      prevArgmax,
      cycleRevenue,
      cycleImps,
      candidatesJson,
      category
    ).mapTo[FloorDecision]
  }

  val floorDecisions = TableQuery[FloorDecisionsTable]
}
