package promovolve.api.fraud

import promovolve.fraud.FraudDetection
import slick.jdbc.PostgresProfile.api.*

import java.time.{ Instant, LocalDate }
import scala.concurrent.{ ExecutionContext, Future }

/** One persisted fraud flag (read model for the Phase-3 review queue). */
final case class FraudFlagRow(
    id: Long,
    siteId: String,
    signal: String,
    severity: Double,
    windowDay: LocalDate,
    evidence: String,
    status: String,
    flaggedAt: Instant
)

/**
 * Postgres access for the Layer-2 economics detector
 * (docs/design/FRAUD_PREVENTION.md): the two per-site aggregations that
 * feed the pure detector, plus idempotent flag persistence and the
 * review-queue reads. Raw SQL (matching EndpointRoutes' style) over the
 * shared dashboard DB.
 */
final class FraudFlagRepo(db: slick.jdbc.PostgresProfile.backend.Database)(using ec: ExecutionContext) {

  import FraudFlagRepo.given

  /** Per-site, per-UTC-day tracking-event rollup over the trailing window. */
  def loadEventDays(days: Int): Future[Vector[FraudDetection.EventDay]] = {
    val q = sql"""
      SELECT site_id,
             (event_time AT TIME ZONE 'UTC')::date AS day,
             COUNT(*) FILTER (WHERE event_type = 'impression')::bigint,
             COUNT(*) FILTER (WHERE event_type = 'click')::bigint,
             COUNT(*) FILTER (WHERE suspect_reason IS NOT NULL)::bigint,
             COUNT(*)::bigint
      FROM tracking_events
      WHERE event_time >= NOW() - make_interval(days => $days)
      GROUP BY site_id, day
    """.as[(String, LocalDate, Long, Long, Long, Long)]
    db.run(q).map(_.map { case (s, d, imp, clk, sus, tot) =>
      FraudDetection.EventDay(s, d, imp, clk, sus, tot)
    }.toVector)
  }

  /** Per-site, per-UTC-day mount-beacon count — the pageview proxy. */
  def loadPageviews(days: Int): Future[Map[(String, LocalDate), Long]] = {
    val q = sql"""
      SELECT pub, (ts AT TIME ZONE 'UTC')::date AS day, COUNT(*)::bigint
      FROM mount_beacons
      WHERE ts >= NOW() - make_interval(days => $days)
      GROUP BY pub, day
    """.as[(String, LocalDate, Long)]
    db.run(q).map(_.map { case (pub, day, n) => (pub, day) -> n }.toMap)
  }

  /**
   * Persist flags idempotently: one row per (site, signal, day), each
   * carrying its own site's latest day. A re-run refreshes
   * severity/evidence on still-open rows and never re-opens a resolved
   * one. Returns the number of rows inserted/updated.
   */
  def upsertFlags(flags: Vector[(FraudDetection.Flag, LocalDate)]): Future[Int] =
    if (flags.isEmpty) Future.successful(0)
    else {
      val actions = flags.map { case (f, day) =>
        val sqlDay = java.sql.Date.valueOf(day)
        sqlu"""
          INSERT INTO fraud_flags (site_id, signal, severity, window_day, evidence)
          VALUES (${f.siteId}, ${f.signal}, ${f.severity}, $sqlDay, ${f.evidence})
          ON CONFLICT (site_id, signal, window_day) DO UPDATE
            SET severity = EXCLUDED.severity, evidence = EXCLUDED.evidence
            WHERE fraud_flags.status = 'open'
        """
      }
      db.run(DBIO.sequence(actions).transactionally).map(_.sum)
    }

  /** Open flags for the review queue, newest first. */
  def listOpen(limit: Int = 200): Future[Vector[FraudFlagRow]] = {
    val q = sql"""
      SELECT id, site_id, signal, severity, window_day, evidence, status, flagged_at
      FROM fraud_flags
      WHERE status = 'open'
      ORDER BY flagged_at DESC
      LIMIT $limit
    """.as[FraudFlagRow]
    db.run(q).map(_.toVector)
  }

  /** Resolve a flag (Phase 3): status ∈ {released, confirmed}. */
  def resolve(id: Long, status: String, by: String): Future[Int] =
    db.run(sqlu"""
      UPDATE fraud_flags
      SET status = $status, resolved_at = NOW(), resolved_by = $by
      WHERE id = $id AND status = 'open'
    """)
}

object FraudFlagRepo {
  import slick.jdbc.GetResult

  given GetResult[(String, LocalDate, Long, Long, Long, Long)] =
    GetResult(r => (r.nextString(), r.nextDate().toLocalDate, r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong()))

  given GetResult[(String, LocalDate, Long)] =
    GetResult(r => (r.nextString(), r.nextDate().toLocalDate, r.nextLong()))

  given GetResult[FraudFlagRow] = GetResult(r =>
    FraudFlagRow(
      id = r.nextLong(),
      siteId = r.nextString(),
      signal = r.nextString(),
      severity = r.nextDouble(),
      windowDay = r.nextDate().toLocalDate,
      evidence = r.nextString(),
      status = r.nextString(),
      flaggedAt = r.nextTimestamp().toInstant
    ))
}
