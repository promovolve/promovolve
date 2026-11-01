package promovolve.publisher

import java.time.Instant

import slick.jdbc.GetResult
import slick.jdbc.PostgresProfile.api.*

/**
 * A site's top pages and top creatives since an instant (the publisher
 * Overview's "last 7 days" tables), from the tracking_events projection.
 *
 * Same event rules as the revenue-today tile, so the two reconcile: clean
 * events only (`suspect_reason IS NULL`), and dogeared re-views are neither
 * counted nor paid. Ranked by gross revenue, then impressions. Revenue is
 * gross; the platform deducts its margin for display.
 */
object SiteTopQueries {

  val Limit = 5

  /** `page` is the URL without query or fragment; "" = no URL recorded. */
  final case class PageRow(page: String, impressions: Long, clicks: Long, grossRevenue: Double)

  /** Name, domain and key are "" when the creative row is gone. */
  final case class CreativeRow(
      creativeId: String,
      name: String,
      landingDomain: String,
      s3Key: String,
      impressions: Long,
      clicks: Long,
      grossRevenue: Double
  )

  private given GetResult[PageRow] =
    GetResult(using r => PageRow(r.nextString(), r.nextLong(), r.nextLong(), r.nextDouble()))
  private given GetResult[CreativeRow] = GetResult(using r =>
    CreativeRow(r.nextString(), r.nextString(), r.nextString(), r.nextString(), r.nextLong(), r.nextLong(),
      r.nextDouble()))

  def pages(siteId: String, since: Instant): DBIO[Vector[PageRow]] = {
    val from = since.toString
    sql"""
      SELECT COALESCE(split_part(split_part(url, '#', 1), '?', 1), ''),
             COUNT(*) FILTER (WHERE event_type = 'impression' AND NOT dogeared)::bigint AS imps,
             COUNT(*) FILTER (WHERE event_type = 'click')::bigint,
             (COALESCE(SUM(cpm) FILTER (WHERE event_type = 'impression' AND NOT dogeared), 0) / 1000.0)::double precision AS rev
      FROM tracking_events
      WHERE site_id = $siteId
        AND suspect_reason IS NULL
        AND event_time >= $from::timestamptz
      GROUP BY 1
      ORDER BY rev DESC, imps DESC
      LIMIT #$Limit
    """.as[PageRow]
  }

  def creatives(siteId: String, since: Instant): DBIO[Vector[CreativeRow]] = {
    val from = since.toString
    // Rank in tracking_events first, then join only the winners.
    sql"""
      SELECT t.creative_id, COALESCE(c.name, ''), COALESCE(c.landing_domain, ''), COALESCE(c.s3_key, ''),
             t.imps, t.clicks, t.rev
      FROM (
        SELECT creative_id,
               COUNT(*) FILTER (WHERE event_type = 'impression' AND NOT dogeared)::bigint AS imps,
               COUNT(*) FILTER (WHERE event_type = 'click')::bigint AS clicks,
               (COALESCE(SUM(cpm) FILTER (WHERE event_type = 'impression' AND NOT dogeared), 0) / 1000.0)::double precision AS rev
        FROM tracking_events
        WHERE site_id = $siteId
          AND suspect_reason IS NULL
          AND event_time >= $from::timestamptz
        GROUP BY creative_id
        ORDER BY rev DESC, imps DESC
        LIMIT #$Limit
      ) t
      LEFT JOIN creative c ON c.creative_id = t.creative_id
      ORDER BY t.rev DESC, t.imps DESC
    """.as[CreativeRow]
  }
}
