package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDate
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Campaign reach per site, counted without any viewer identity (GH #238).
 *
 * The ad tag keeps, per campaign, the day it last had a viewable impression
 * of it on this site. On the first viewable impression of a day it reports
 * that previous day (or "never") and a one-time freshness value — nothing
 * else. Here we store only how many reports arrived per
 * (campaign, advertiser-local day, site, days since last seen).
 *
 * Unique browsers for ANY range of days a…b is then
 *   Σ over days d in a…b of reports whose days_since > d − a, or = Never
 * because each browser's first visit inside the range is exactly the visit
 * whose previous one fell before the range. Same counting method as
 * Chrome's updater protocol ("client-regulated counting").
 *
 * Freshness (protocol v4's ping_freshness): the tag rotates a random value
 * each time it saves a new day and sends the old one with its report, so
 * each value should arrive once. A repeat means copied or replayed browser
 * storage (e.g. a bot farm cloning a profile) and is not counted.
 */
trait ReachRepo {

  /**
   * Count one report. False when its freshness value was already seen, so
   * nothing was counted.
   */
  def record(campaignId: String, siteId: String, day: LocalDate, daysSince: Int, fresh: String): Future[Boolean]
}

object ReachRepo {

  /** Longest range reach is exact for; the report's date picker stops here. */
  val CapDays = 90

  /** days_since for a browser that had never seen the campaign here. */
  val Never = 0

  /** days_since for "more than CapDays" — new for every range we report. */
  val Beyond: Int = CapDays + 1

  /**
   * days_since from the advertiser-local epoch day of the report and the
   * previous day the tag stored. None when prev isn't before today
   * (clock or timezone change): such a report says nothing about reach.
   */
  def daysSince(today: Long, prev: Option[Long]): Option[Int] = prev match {
    case None                 => Some(Never)
    case Some(p) if p < today => Some(math.min(today - p, Beyond.toLong).toInt)
    case _                    => None
  }

  private val FreshPattern = "^[0-9a-f]{32}$".r

  /** The tag sends 16 random bytes as lowercase hex. */
  def validFresh(fresh: String): Boolean = FreshPattern.matches(fresh)
}

/** PostgreSQL-backed implementation using Slick */
class SlickReachRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext) extends ReachRepo {

  def ensureSchema(): Unit = {
    val counts = sqlu"""
      CREATE TABLE IF NOT EXISTS campaign_reach_daily (
        campaign_id VARCHAR(64) NOT NULL,
        day_bucket DATE NOT NULL,          -- advertiser-local day of the report
        site_id VARCHAR(128) NOT NULL,
        days_since SMALLINT NOT NULL,      -- 0 = never seen here before; 1..90; 91 = more than 90 days
        reports BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (campaign_id, day_bucket, site_id, days_since)
      )
    """
    val fresh = sqlu"""
      CREATE TABLE IF NOT EXISTS reach_freshness (
        fresh VARCHAR(32) PRIMARY KEY,
        seen_day DATE NOT NULL
      )
    """
    val freshIdx = sqlu"CREATE INDEX IF NOT EXISTS idx_reach_freshness_day ON reach_freshness (seen_day)"
    Await.result(db.run(counts >> fresh >> freshIdx), 10.seconds)
  }

  override def record(
      campaignId: String,
      siteId: String,
      day: LocalDate,
      daysSince: Int,
      fresh: String
  ): Future[Boolean] = {
    // Plain SQL binds java.sql.Date, not LocalDate (as FraudFlagRepo does).
    val sqlDay = java.sql.Date.valueOf(day)
    // One statement: the count only happens if the freshness value is new.
    val upsert = sqlu"""
      WITH f AS (
        INSERT INTO reach_freshness (fresh, seen_day) VALUES ($fresh, $sqlDay)
        ON CONFLICT DO NOTHING RETURNING 1
      )
      INSERT INTO campaign_reach_daily (campaign_id, day_bucket, site_id, days_since, reports)
      SELECT $campaignId, $sqlDay, $siteId, ${daysSince.toShort}, 1 FROM f
      ON CONFLICT (campaign_id, day_bucket, site_id, days_since)
      DO UPDATE SET reports = campaign_reach_daily.reports + 1
    """
    // ponytail: probabilistic pruning, ~once per 10k reports. A value only
    // needs remembering for the cap; a scheduled job if volume ever needs it.
    val prune =
      if (ThreadLocalRandom.current().nextInt(10000) == 0)
        sqlu"DELETE FROM reach_freshness WHERE seen_day < ${java.sql.Date.valueOf(day.minusDays(ReachRepo.CapDays + 1L))}"
      else DBIO.successful(0)
    db.run(upsert).flatMap(n => db.run(prune).map(_ => n > 0))
  }
}
