package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDate
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * One day's observed reader countries for one site.
 *
 * `shares` is derived, not stored — the table holds counts so concurrent
 * API pods can each add their own without coordinating.
 */
final case class AudienceObservation(siteId: String, country: String, day: LocalDate, count: Long)

/**
 * Tier 3 of docs/design/GEOGRAPHIC_CONTEXT.md — the aggregate that makes a
 * publisher's audience declaration checkable.
 *
 * '''What this deliberately is not.''' There is no per-event row and no
 * column anywhere that describes one reader. The beacon path resolves an IP
 * to a country, adds 1 to an in-memory counter, and drops the IP — the same
 * IP it already drops after fraud-hygiene classification. Only
 * `(site, country, day, count)` reaches disk. That is what keeps the claim
 * in `api/GPC.md` true: the auction reads the page, the audit reads
 * aggregates, and neither holds a viewer.
 *
 * Country granularity is a property of the source data (the iptoasn dump has
 * no subdivision), not a choice — which is why a city-level declaration can
 * only ever be verified as far as its country.
 */
trait AudienceObservationRepo {

  /**
   * Add a batch of counts, summing into whatever is already recorded.
   *
   * Additive rather than absolute because every API pod flushes its own
   * partial counts on its own timer; a last-writer-wins upsert would keep
   * only one pod's view and silently undercount a multi-pod cluster.
   */
  def addCounts(counts: Iterable[AudienceObservation]): Future[Unit]

  /** Observed counts for a site over the trailing `days`, newest day first. */
  def forSite(siteId: String, days: Int): Future[Vector[AudienceObservation]]
}

/** PostgreSQL-backed implementation using Slick. */
class SlickAudienceObservationRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends AudienceObservationRepo {

  def ensureSchema(): Unit = {
    val createTableSql = sql"""
      CREATE TABLE IF NOT EXISTS site_audience_daily (
        site_id VARCHAR(128) NOT NULL,
        country CHAR(2) NOT NULL,
        day DATE NOT NULL,
        count BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (site_id, country, day)
      )
    """.asUpdate

    val createIndexSql = sql"""
      CREATE INDEX IF NOT EXISTS idx_site_audience_daily_site_day
        ON site_audience_daily (site_id, day DESC)
    """.asUpdate

    Await.result(db.run(createTableSql >> createIndexSql), 10.seconds)
  }

  override def addCounts(counts: Iterable[AudienceObservation]): Future[Unit] =
    if (counts.isEmpty) Future.successful(())
    else {
      val actions = counts.map { o =>
        sqlu"""
          INSERT INTO site_audience_daily (site_id, country, day, count)
          VALUES (${o.siteId}, ${o.country}, ${o.day.toString}::date, ${o.count})
          ON CONFLICT (site_id, country, day)
          DO UPDATE SET count = site_audience_daily.count + EXCLUDED.count
        """
      }
      db.run(DBIO.sequence(actions.toVector).transactionally).map(_ => ())
    }

  override def forSite(siteId: String, days: Int): Future[Vector[AudienceObservation]] = {
    val cutoff = math.max(1, days)
    db.run(
      sql"""
        SELECT site_id, country, day, count
        FROM site_audience_daily
        WHERE site_id = $siteId
          AND day >= (CURRENT_DATE - ($cutoff || ' days')::interval)
        ORDER BY day DESC, count DESC
      """.as[(String, String, java.sql.Date, Long)]
    ).map(_.map { case (s, c, d, n) => AudienceObservation(s, c, d.toLocalDate, n) }.toVector)
  }
}
