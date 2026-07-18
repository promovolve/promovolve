package promovolve.api.projection

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api.*
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.time.{ Instant, LocalDate }
import scala.concurrent.{ ExecutionContext, Future }

/** Dashboard API routes for advertisers to view campaign performance. */
class DashboardRoutes(dbConfig: DatabaseConfig[PostgresProfile])(using system: ActorSystem[?]) {
  import DashboardRoutes.given

  private val db = dbConfig.db
  given ExecutionContext = system.executionContext

  val routes: Route = pathPrefix("v1" / "dashboard") {
    concat(
      // Campaign-scoped stats are addressed under the owning advertiser so
      // every projection read can be filtered by advertiser_id. This closes
      // the IDOR where the previous /v1/dashboard/campaigns/{id} routes
      // resolved a campaign by id alone and would return any advertiser's
      // numbers. The advertiserId segment is the authenticated advertiser
      // (BFF-injected, proxy-pinned).
      // GET /v1/dashboard/advertisers/{advertiserId}/campaigns/{campaignId}
      path("advertisers" / Segment / "campaigns" / Segment) { (advertiserId, campaignId) =>
        get {
          onSuccess(getCampaignStats(advertiserId, campaignId)) {
            case Some(stats) => complete(stats.toJson.prettyPrint)
            case None        => complete(StatusCodes.NotFound, s"Campaign not found: $campaignId")
          }
        }
      },
      // GET /v1/dashboard/advertisers/{advertiserId}/campaigns/{campaignId}/hourly?hours=24
      path("advertisers" / Segment / "campaigns" / Segment / "hourly") { (advertiserId, campaignId) =>
        get {
          parameter("hours".as[Int].withDefault(24)) { hours =>
            onSuccess(getCampaignHourlyStats(advertiserId, campaignId, hours)) { stats =>
              complete(stats.toJson.prettyPrint)
            }
          }
        }
      },
      // GET /v1/dashboard/advertisers/{advertiserId}/campaigns/{campaignId}/daily?days=7
      path("advertisers" / Segment / "campaigns" / Segment / "daily") { (advertiserId, campaignId) =>
        get {
          parameter("days".as[Int].withDefault(7)) { days =>
            onSuccess(getCampaignDailyStats(advertiserId, campaignId, days)) { stats =>
              complete(stats.toJson.prettyPrint)
            }
          }
        }
      },
      // GET /v1/dashboard/advertisers/{advertiserId}/campaigns/{campaignId}/creatives
      path("advertisers" / Segment / "campaigns" / Segment / "creatives") { (advertiserId, campaignId) =>
        get {
          onSuccess(getCreativeStats(advertiserId, campaignId)) { stats =>
            complete(stats.toJson.prettyPrint)
          }
        }
      },
      // GET /v1/dashboard/advertisers/{advertiserId}/campaigns/{campaignId}/creative-sites?days=30
      // Creative × media matrix: which creative performs on which site.
      // Sourced from tracking_events (the pre-aggregated rollups carry no
      // creative×site dimension), so the window is bounded by its 30-day
      // retention policy.
      path("advertisers" / Segment / "campaigns" / Segment / "creative-sites") { (advertiserId, campaignId) =>
        get {
          parameter("days".as[Int].withDefault(30)) { days =>
            onSuccess(getCreativeSiteStats(advertiserId, campaignId, days.max(1).min(30))) { stats =>
              complete(stats.toJson.prettyPrint)
            }
          }
        }
      },
      // GET /v1/dashboard/advertisers/{advertiserId}
      path("advertisers" / Segment) { advertiserId =>
        get {
          onSuccess(getAdvertiserSummary(advertiserId)) {
            case Some(summary) => complete(summary.toJson.prettyPrint)
            case None          => complete(StatusCodes.NotFound, s"Advertiser not found: $advertiserId")
          }
        }
      },
      // GET /v1/dashboard/advertisers/{advertiserId}/campaigns
      path("advertisers" / Segment / "campaigns") { advertiserId =>
        get {
          onSuccess(getAdvertiserCampaigns(advertiserId)) { campaigns =>
            complete(campaigns.toJson.prettyPrint)
          }
        }
      }
    )
  }

  // ========================================
  // Query Methods
  // ========================================

  private def getCampaignStats(advertiserId: String, campaignId: String): Future[Option[CampaignStatsDTO]] = {
    val query = sql"""
      SELECT campaign_id, advertiser_id, impressions, clicks, total_spend,
             first_impression_at, last_impression_at, last_click_at, updated_at
      FROM campaign_stats
      WHERE campaign_id = $campaignId AND advertiser_id = $advertiserId
    """.as[(String, String, Long, Long, BigDecimal, Option[Instant], Option[Instant], Option[Instant], Instant)]

    db.run(query.headOption).map(_.map { row =>
      CampaignStatsDTO(
        campaignId = row._1,
        advertiserId = row._2,
        impressions = row._3,
        clicks = row._4,
        totalSpend = row._5,
        ctr = if (row._3 > 0) row._4.toDouble / row._3 else 0.0,
        cpc = if (row._4 > 0) row._5.toDouble / row._4 else 0.0,
        cpm = if (row._3 > 0) row._5.toDouble / row._3 * 1000 else 0.0,
        firstImpressionAt = row._6.map(_.toString),
        lastImpressionAt = row._7.map(_.toString),
        lastClickAt = row._8.map(_.toString),
        updatedAt = row._9.toString
      )
    })
  }

  private def getCampaignHourlyStats(advertiserId: String, campaignId: String, hours: Int)
      : Future[Seq[HourlyStatsDTO]] = {
    val cutoff = Instant.now().minusSeconds(hours * 3600L)
    // campaign_hourly_stats has no advertiser_id column, so gate on an
    // ownership EXISTS against campaign_stats (which does) — a campaign only
    // appears there for its owning advertiser.
    val query = sql"""
      SELECT hour_bucket, impressions, clicks, spend
      FROM campaign_hourly_stats
      WHERE campaign_id = $campaignId AND hour_bucket > $cutoff
        AND EXISTS (SELECT 1 FROM campaign_stats cs
                    WHERE cs.campaign_id = $campaignId AND cs.advertiser_id = $advertiserId)
      ORDER BY hour_bucket DESC
    """.as[(Instant, Long, Long, BigDecimal)]

    db.run(query).map(_.map { row =>
      HourlyStatsDTO(
        hourBucket = row._1.toString,
        impressions = row._2,
        clicks = row._3,
        spend = row._4,
        ctr = if (row._2 > 0) row._3.toDouble / row._2 else 0.0
      )
    })
  }

  private def getCampaignDailyStats(advertiserId: String, campaignId: String, days: Int): Future[Seq[DailyStatsDTO]] = {
    // Explicit UTC: day_bucket is written in UTC by DashboardProjectionHandler;
    // the JVM-default-zone LocalDate.now() drifted off it for non-UTC hosts.
    val cutoff = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(days)
    // See getCampaignHourlyStats: gate on ownership via campaign_stats since
    // campaign_daily_stats carries no advertiser_id column.
    val query = sql"""
      SELECT day_bucket, impressions, clicks, spend, unique_sites
      FROM campaign_daily_stats
      WHERE campaign_id = $campaignId AND day_bucket > $cutoff
        AND EXISTS (SELECT 1 FROM campaign_stats cs
                    WHERE cs.campaign_id = $campaignId AND cs.advertiser_id = $advertiserId)
      ORDER BY day_bucket DESC
    """.as[(LocalDate, Long, Long, BigDecimal, Int)]

    db.run(query).map(_.map { row =>
      DailyStatsDTO(
        dayBucket = row._1.toString,
        impressions = row._2,
        clicks = row._3,
        spend = row._4,
        uniqueSites = row._5,
        ctr = if (row._2 > 0) row._3.toDouble / row._2 else 0.0
      )
    })
  }

  private def getCreativeStats(advertiserId: String, campaignId: String): Future[Seq[CreativeStatsDTO]] = {
    val query = sql"""
      SELECT creative_id, impressions, clicks, cta_clicks, total_spend,
             dogeared_impressions, dogeared_clicks, dogeared_cta_clicks,
             updated_at
      FROM creative_stats
      WHERE campaign_id = $campaignId AND advertiser_id = $advertiserId
      ORDER BY impressions DESC
    """.as[(String, Long, Long, Long, BigDecimal, Long, Long, Long, Instant)]

    db.run(query).map(_.map { row =>
      CreativeStatsDTO(
        creativeId = row._1,
        impressions = row._2,
        clicks = row._3,
        ctaClicks = row._4,
        totalSpend = row._5,
        ctr = if (row._2 > 0) row._3.toDouble / row._2 else 0.0,
        dogearedImpressions = row._6,
        dogearedClicks = row._7,
        dogearedCtaClicks = row._8,
        pinCtr = if (row._6 > 0) row._7.toDouble / row._6 else 0.0,
        updatedAt = row._9.toString
      )
    })
  }

  private def getCreativeSiteStats(
      advertiserId: String,
      campaignId: String,
      days: Int
  ): Future[Seq[CreativeSiteStatsDTO]] = {
    // advertiser_id is a first-class column on tracking_events, so the
    // ownership gate is direct (no EXISTS idiom needed). Site label =
    // real host from publisher_sites where known — same advertiser-safe
    // identity the report breakdown exposes; '' falls back to the
    // site_id slug platform-side.
    given slick.jdbc.GetResult[(String, String, String, Long, Long, Long, Double)] =
      slick.jdbc.GetResult(using r =>
        (r.nextString(), r.nextString(), r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(), r.nextDouble()))
    val query = sql"""
      SELECT t.creative_id, t.site_id, COALESCE(ps.host, ''),
             COUNT(*) FILTER (WHERE t.event_type = 'impression')::bigint,
             COUNT(*) FILTER (WHERE t.event_type = 'click')::bigint,
             COUNT(*) FILTER (WHERE t.event_type = 'cta_click')::bigint,
             COALESCE(SUM(t.cpm) FILTER (WHERE t.event_type = 'impression') / 1000.0, 0)::double precision
      FROM tracking_events t
      LEFT JOIN publisher_sites ps ON ps.site_id = t.site_id
      WHERE t.campaign_id = $campaignId AND t.advertiser_id = $advertiserId
        AND t.event_time >= NOW() - make_interval(days => $days)
        AND t.event_type IN ('impression', 'click', 'cta_click')
      GROUP BY t.creative_id, t.site_id, ps.host
      ORDER BY 7 DESC, 4 DESC
    """.as[(String, String, String, Long, Long, Long, Double)]

    db.run(query).map(_.map { case (creativeId, siteId, host, imps, clicks, ctas, spend) =>
      CreativeSiteStatsDTO(
        creativeId = creativeId,
        siteId = siteId,
        siteLabel = host,
        impressions = imps,
        clicks = clicks,
        ctaClicks = ctas,
        spend = BigDecimal(spend).setScale(4, BigDecimal.RoundingMode.HALF_UP),
        ctr = if (imps > 0) clicks.toDouble / imps else 0.0
      )
    })
  }

  private def getAdvertiserSummary(advertiserId: String): Future[Option[AdvertiserSummaryDTO]] = {
    val query = sql"""
      SELECT advertiser_id, total_impressions, total_clicks, total_spend,
             active_campaigns, total_creatives, updated_at
      FROM advertiser_summary
      WHERE advertiser_id = $advertiserId
    """.as[(String, Long, Long, BigDecimal, Int, Int, Instant)]

    db.run(query.headOption).map(_.map { row =>
      AdvertiserSummaryDTO(
        advertiserId = row._1,
        totalImpressions = row._2,
        totalClicks = row._3,
        totalSpend = row._4,
        activeCampaigns = row._5,
        totalCreatives = row._6,
        ctr = if (row._2 > 0) row._3.toDouble / row._2 else 0.0,
        updatedAt = row._7.toString
      )
    })
  }

  private def getAdvertiserCampaigns(advertiserId: String): Future[Seq[CampaignStatsDTO]] = {
    val query = sql"""
      SELECT campaign_id, advertiser_id, impressions, clicks, total_spend,
             first_impression_at, last_impression_at, last_click_at, updated_at
      FROM campaign_stats
      WHERE advertiser_id = $advertiserId
      ORDER BY impressions DESC
    """.as[(String, String, Long, Long, BigDecimal, Option[Instant], Option[Instant], Option[Instant], Instant)]

    db.run(query).map(_.map { row =>
      CampaignStatsDTO(
        campaignId = row._1,
        advertiserId = row._2,
        impressions = row._3,
        clicks = row._4,
        totalSpend = row._5,
        ctr = if (row._3 > 0) row._4.toDouble / row._3 else 0.0,
        cpc = if (row._4 > 0) row._5.toDouble / row._4 else 0.0,
        cpm = if (row._3 > 0) row._5.toDouble / row._3 * 1000 else 0.0,
        firstImpressionAt = row._6.map(_.toString),
        lastImpressionAt = row._7.map(_.toString),
        lastClickAt = row._8.map(_.toString),
        updatedAt = row._9.toString
      )
    })
  }

  // ========================================
  // Slick Implicit Converters
  // ========================================
  given instantGetResult: slick.jdbc.GetResult[Instant] =
    slick.jdbc.GetResult(using r => r.nextTimestamp().toInstant)

  given optInstantGetResult: slick.jdbc.GetResult[Option[Instant]] =
    slick.jdbc.GetResult(using r => Option(r.nextTimestamp()).map(_.toInstant))

  given localDateGetResult: slick.jdbc.GetResult[LocalDate] =
    slick.jdbc.GetResult(using r => r.nextDate().toLocalDate)

  given instantSetParameter: slick.jdbc.SetParameter[Instant] =
    slick.jdbc.SetParameter(using (v, pp) => pp.setTimestamp(java.sql.Timestamp.from(v)))

  given localDateSetParameter: slick.jdbc.SetParameter[LocalDate] =
    slick.jdbc.SetParameter(using (v, pp) => pp.setDate(java.sql.Date.valueOf(v)))
}

// ========================================
// DTOs
// ========================================

case class CampaignStatsDTO(
    campaignId: String,
    advertiserId: String,
    impressions: Long,
    clicks: Long,
    totalSpend: BigDecimal,
    ctr: Double,
    cpc: Double,
    cpm: Double,
    firstImpressionAt: Option[String],
    lastImpressionAt: Option[String],
    lastClickAt: Option[String],
    updatedAt: String
)

case class HourlyStatsDTO(
    hourBucket: String,
    impressions: Long,
    clicks: Long,
    spend: BigDecimal,
    ctr: Double
)

case class DailyStatsDTO(
    dayBucket: String,
    impressions: Long,
    clicks: Long,
    spend: BigDecimal,
    uniqueSites: Int,
    ctr: Double
)

case class CreativeStatsDTO(
    creativeId: String,
    impressions: Long,
    clicks: Long,
    ctaClicks: Long,
    totalSpend: BigDecimal,
    /** Click-through rate on fresh impressions: clicks / impressions. */
    ctr: Double,
    /**
     * Bookmark-driven re-encounter counters. Tracked separately so
     * dashboards can surface pin engagement as its own dimension
     * without polluting fresh CTR.
     */
    dogearedImpressions: Long,
    dogearedClicks: Long,
    dogearedCtaClicks: Long,
    /** Re-engagement click-through rate: dogearedClicks / dogearedImpressions. */
    pinCtr: Double,
    updatedAt: String
)

/** One creative × site cell of the media matrix (trailing-window). */
case class CreativeSiteStatsDTO(
    creativeId: String,
    siteId: String,
    /** Real host where known; "" → platform falls back to the siteId slug. */
    siteLabel: String,
    impressions: Long,
    clicks: Long,
    ctaClicks: Long,
    spend: BigDecimal,
    ctr: Double
)

case class AdvertiserSummaryDTO(
    advertiserId: String,
    totalImpressions: Long,
    totalClicks: Long,
    totalSpend: BigDecimal,
    activeCampaigns: Int,
    totalCreatives: Int,
    ctr: Double,
    updatedAt: String
)

object DashboardRoutes {
  // JSON formats
  given RootJsonFormat[CampaignStatsDTO] = jsonFormat12(CampaignStatsDTO.apply)
  given RootJsonFormat[HourlyStatsDTO] = jsonFormat5(HourlyStatsDTO.apply)
  given RootJsonFormat[DailyStatsDTO] = jsonFormat6(DailyStatsDTO.apply)
  given RootJsonFormat[CreativeStatsDTO] = jsonFormat11(CreativeStatsDTO.apply)
  given RootJsonFormat[CreativeSiteStatsDTO] = jsonFormat8(CreativeSiteStatsDTO.apply)
  given RootJsonFormat[AdvertiserSummaryDTO] = jsonFormat8(AdvertiserSummaryDTO.apply)
}
