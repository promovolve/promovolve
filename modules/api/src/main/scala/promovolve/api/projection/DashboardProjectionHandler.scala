package promovolve.api.projection

import org.apache.pekko.Done
import org.apache.pekko.projection.slick.SlickHandler
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api.*
import slick.jdbc.SetParameter
import java.time.{ Instant, LocalDate, ZoneId, ZoneOffset }
import java.time.temporal.ChronoUnit

/**
 * Projection handler that updates dashboard read-side tables.
 *
 * Processes TrackingEvent from the journal and updates:
 * - campaign_stats: Real-time campaign aggregates
 * - creative_stats: Per-creative metrics
 * - campaign_hourly_stats: Hourly time series
 * - campaign_daily_stats: Daily aggregates (advertiser-local days)
 * - campaign_dim_daily_stats: Daily site x category rollup — carries BOTH
 *   owners' local days (day_bucket = advertiser, pub_day_bucket = publisher)
 * - advertiser_summary: Advertiser-level rollup
 *
 * Day buckets are the OWNER's local day, resolved from the shared `orgs`
 * table (platform-written; advertiser_id/publisher_id → timezone) at
 * write time with a short-lived cache. This makes report days match the
 * budget-rollover and settlement boundaries instead of UTC. Org-less
 * entities and lookup failures fall back to UTC — bucketing must never
 * stall the projection.
 */
class DashboardProjectionHandler extends SlickHandler[TrackingEvent] {
  import DashboardProjectionHandler.given
  import scala.concurrent.ExecutionContext.Implicits.global

  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  override def process(event: TrackingEvent): DBIO[Done] = {
    log.debug("Processing {} event: seq={} campaign={}", event.eventType, event.sequenceNr,
      event.campaignId.getOrElse("-"))

    // Hygiene-marked (suspect) events are fraud evidence, not delivery:
    // they exist only in tracking_events for the Layer-2 economics
    // detector and never move a dashboard counter — not the primary
    // totals, not even the dogeared_* parallel counters
    // (docs/design/FRAUD_PREVENTION.md).
    if (event.suspectReason.isDefined) DBIO.successful(Done)
    else event.eventType match {
      case "impression" => processImpression(event)
      case "click"      => processClick(event)
      case "cta_click"  => processCTAClick(event)
      case "fold"       => processFold(event)
      case "unfold"     => processUnfold(event)
      case other        =>
        log.warn("Unknown event type: {}", other)
        DBIO.successful(Done)
    }
  }

  /** Dimension key for campaign_dim_daily_stats; '' = no matched category. */
  private def dimCategory(e: TrackingEvent): String =
    e.category.map(_.trim).filter(_.nonEmpty).getOrElse("")

  // ---------------------------------------------------------------
  // Owner-timezone resolution (advertiser + publisher local days)
  // ---------------------------------------------------------------

  /** (zoneId string, cached-at millis); "" = UTC. */
  private val tzCache = new java.util.concurrent.ConcurrentHashMap[String, (String, Long)]()
  private val TzCacheTtlMs = 10 * 60 * 1000L

  /**
   * Resolve a timezone through the cache, falling back to UTC on any
   * failure (missing orgs table in a bare-core dev DB, org-less entity,
   * bad zone id). A stale-by-≤TTL zone after an operator timezone change
   * mis-buckets at most a few minutes of events — acceptable for
   * analytics rollups, and the alternative (a lookup per event) would
   * put the platform DB on the serve path of every tracked event.
   */
  private def cachedZone(key: String)(fetch: => DBIO[Option[String]]): DBIO[ZoneId] = {
    val now = System.currentTimeMillis()
    Option(tzCache.get(key)) match {
      case Some((tz, at)) if now - at < TzCacheTtlMs =>
        DBIO.successful(promovolve.common.Timezones.zoneOf(tz))
      case _ =>
        fetch.asTry.map { res =>
          res.failed.foreach { ex =>
            // Surface, don't stall: a broken lookup (e.g. a self-hosted
            // split DB where `orgs` isn't reachable from this connection)
            // means UTC bucketing — visible here, once per cache TTL.
            log.warn("Owner-timezone lookup failed for {} — bucketing UTC: {}", key, ex.toString)
          }
          val tz = res.toOption.flatten.getOrElse("")
          tzCache.put(key, (tz, now))
          promovolve.common.Timezones.zoneOf(tz)
        }
    }
  }

  private def advertiserZone(e: TrackingEvent): DBIO[ZoneId] =
    e.advertiserId.filter(_.nonEmpty) match {
      case None        => DBIO.successful(ZoneOffset.UTC)
      case Some(advId) =>
        cachedZone(s"adv:$advId") {
          sql"SELECT timezone FROM orgs WHERE advertiser_id = $advId".as[String].headOption
        }
    }

  private def publisherZone(e: TrackingEvent): DBIO[ZoneId] =
    Option(e.siteId).filter(_.nonEmpty) match {
      case None         => DBIO.successful(ZoneOffset.UTC)
      case Some(siteId) =>
        cachedZone(s"site:$siteId") {
          sql"""
            SELECT o.timezone FROM publisher_sites ps
            JOIN orgs o ON o.publisher_id = ps.publisher_id
            WHERE ps.site_id = $siteId
          """.as[String].headOption
        }
    }

  /** Both owners' local days for an event, resolved before the write txn. */
  private def ownerDays(e: TrackingEvent): DBIO[(LocalDate, LocalDate)] =
    for {
      advZone <- advertiserZone(e)
      pubZone <- publisherZone(e)
    } yield (
      e.eventTime.atZone(advZone).toLocalDate,
      e.eventTime.atZone(pubZone).toLocalDate
    )

  private def processImpression(e: TrackingEvent): DBIO[Done] = {
    val spend = e.cpm.map(c => c / 1000).getOrElse(BigDecimal(0))
    val hourBucket = e.eventTime.truncatedTo(ChronoUnit.HOURS)

    ownerDays(e).flatMap { case (dayBucket, pubDayBucket) =>
      // Dogeared serves are bookmark fulfillment, not billable impressions.
      // Skip every primary metric (impressions, total_spend, hourly, daily,
      // advertiser_summary) and only bump the parallel dogeared_*
      // counters so the auction + budget surfaces stay clean while the
      // pin-value telemetry still moves.
      if (e.dogeared) {
        bumpDogearedImpression(e, hourBucket, dayBucket, pubDayBucket).map(_ => Done).transactionally
      } else {
        // Combine all updates into a single transaction
        val updates = for {
          // Update campaign_stats
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_stats (campaign_id, advertiser_id, impressions, total_spend,
                                        first_impression_at, last_impression_at, updated_at)
            VALUES ($campId, ${e.advertiserId}, 1, $spend, ${e.eventTime}, ${e.eventTime}, NOW())
            ON CONFLICT (campaign_id) DO UPDATE SET
              impressions = campaign_stats.impressions + 1,
              total_spend = campaign_stats.total_spend + $spend,
              first_impression_at = COALESCE(campaign_stats.first_impression_at, EXCLUDED.first_impression_at),
              last_impression_at = ${e.eventTime},
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Update creative_stats
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO creative_stats (creative_id, campaign_id, advertiser_id, impressions, total_spend, updated_at)
            VALUES (${e.creativeId}, $campId, ${e.advertiserId}, 1, $spend, NOW())
            ON CONFLICT (creative_id, campaign_id) DO UPDATE SET
              impressions = creative_stats.impressions + 1,
              total_spend = creative_stats.total_spend + $spend,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Update hourly stats
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_hourly_stats (campaign_id, hour_bucket, impressions, spend, updated_at)
            VALUES ($campId, $hourBucket, 1, $spend, NOW())
            ON CONFLICT (campaign_id, hour_bucket) DO UPDATE SET
              impressions = campaign_hourly_stats.impressions + 1,
              spend = campaign_hourly_stats.spend + $spend,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Update daily stats
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_daily_stats (campaign_id, day_bucket, impressions, spend, unique_sites, updated_at)
            VALUES ($campId, $dayBucket, 1, $spend, 1, NOW())
            ON CONFLICT (campaign_id, day_bucket) DO UPDATE SET
              impressions = campaign_daily_stats.impressions + 1,
              spend = campaign_daily_stats.spend + $spend,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Dimensional daily rollup (site x category) — feeds the advertiser
          // report's by-site / by-category / by-publisher breakdowns.
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_dim_daily_stats (campaign_id, day_bucket, pub_day_bucket, site_id, category, impressions, spend, updated_at)
            VALUES ($campId, $dayBucket, $pubDayBucket, ${e.siteId}, ${dimCategory(e)}, 1, $spend, NOW())
            ON CONFLICT (campaign_id, day_bucket, pub_day_bucket, site_id, category) DO UPDATE SET
              impressions = campaign_dim_daily_stats.impressions + 1,
              spend = campaign_dim_daily_stats.spend + $spend,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Update advertiser summary
          _ <- e.advertiserId match {
            case Some(advId) if advId.nonEmpty =>
              sqlu"""
            INSERT INTO advertiser_summary (advertiser_id, total_impressions, total_spend, updated_at)
            VALUES ($advId, 1, $spend, NOW())
            ON CONFLICT (advertiser_id) DO UPDATE SET
              total_impressions = advertiser_summary.total_impressions + 1,
              total_spend = advertiser_summary.total_spend + $spend,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Non-dogeared path only — the branch above handles the
          // dogeared case. The dogeared_* counters surface bookmark-driven
          // re-engagement as a separate dimension; primary metrics above
          // track only fresh paid impressions.
        } yield Done

        updates.transactionally
      }
    }
  }

  private def bumpDogearedImpression(
      e: TrackingEvent,
      hourBucket: Instant,
      dayBucket: LocalDate,
      pubDayBucket: LocalDate
  ): DBIO[Int] = {
    val updates = for {
      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_stats
            SET dogeared_impressions = dogeared_impressions + 1, updated_at = NOW()
            WHERE campaign_id = $campId
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE creative_stats
            SET dogeared_impressions = dogeared_impressions + 1, updated_at = NOW()
            WHERE creative_id = ${e.creativeId} AND campaign_id = $campId
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_hourly_stats
            SET dogeared_impressions = dogeared_impressions + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND hour_bucket = $hourBucket
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_daily_stats
            SET dogeared_impressions = dogeared_impressions + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND day_bucket = $dayBucket
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.advertiserId match {
        case Some(advId) if advId.nonEmpty =>
          sqlu"""
            UPDATE advertiser_summary
            SET total_dogeared_impressions = total_dogeared_impressions + 1, updated_at = NOW()
            WHERE advertiser_id = $advId
          """
        case _ => DBIO.successful(0)
      }

      // Dimensional daily rollup — INSERT-capable (unlike the UPDATEs above)
      // because a dogeared serve can be the first event for this
      // (campaign, day, site, category) key. No spend: dogeared is unbilled.
      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            INSERT INTO campaign_dim_daily_stats (campaign_id, day_bucket, pub_day_bucket, site_id, category, dogeared_impressions, updated_at)
            VALUES ($campId, $dayBucket, $pubDayBucket, ${e.siteId}, ${dimCategory(e)}, 1, NOW())
            ON CONFLICT (campaign_id, day_bucket, pub_day_bucket, site_id, category) DO UPDATE SET
              dogeared_impressions = campaign_dim_daily_stats.dogeared_impressions + 1,
              updated_at = NOW()
          """
        case _ => DBIO.successful(0)
      }
    } yield 0
    updates
  }

  /**
   * Mirror of bumpDogearedImpression for click re-encounters. The
   * advertiser_summary, campaign_stats, creative_stats, hourly + daily
   * tables all carry a parallel `dogeared_clicks` counter so dashboards
   * can show "fresh CTR" (`clicks / impressions`) and "pin re-engagement
   * CTR" (`dogeared_clicks / dogeared_impressions`) as separate dimensions.
   */
  private def bumpDogearedClick(
      e: TrackingEvent,
      hourBucket: Instant,
      dayBucket: LocalDate
  ): DBIO[Int] = {
    val updates = for {
      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_stats
            SET dogeared_clicks = dogeared_clicks + 1, updated_at = NOW()
            WHERE campaign_id = $campId
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE creative_stats
            SET dogeared_clicks = dogeared_clicks + 1, updated_at = NOW()
            WHERE creative_id = ${e.creativeId} AND campaign_id = $campId
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_hourly_stats
            SET dogeared_clicks = dogeared_clicks + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND hour_bucket = $hourBucket
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_daily_stats
            SET dogeared_clicks = dogeared_clicks + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND day_bucket = $dayBucket
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.advertiserId match {
        case Some(advId) if advId.nonEmpty =>
          sqlu"""
            UPDATE advertiser_summary
            SET total_dogeared_clicks = total_dogeared_clicks + 1, updated_at = NOW()
            WHERE advertiser_id = $advId
          """
        case _ => DBIO.successful(0)
      }
    } yield 0
    updates
  }

  /** Mirror of bumpDogearedImpression for CTA re-encounters. */
  private def bumpDogearedCTAClick(
      e: TrackingEvent,
      hourBucket: Instant,
      dayBucket: LocalDate
  ): DBIO[Int] = {
    val updates = for {
      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_stats
            SET dogeared_cta_clicks = dogeared_cta_clicks + 1, updated_at = NOW()
            WHERE campaign_id = $campId
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE creative_stats
            SET dogeared_cta_clicks = dogeared_cta_clicks + 1, updated_at = NOW()
            WHERE creative_id = ${e.creativeId} AND campaign_id = $campId
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_hourly_stats
            SET dogeared_cta_clicks = dogeared_cta_clicks + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND hour_bucket = $hourBucket
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.campaignId match {
        case Some(campId) if campId.nonEmpty =>
          sqlu"""
            UPDATE campaign_daily_stats
            SET dogeared_cta_clicks = dogeared_cta_clicks + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND day_bucket = $dayBucket
          """
        case _ => DBIO.successful(0)
      }

      _ <- e.advertiserId match {
        case Some(advId) if advId.nonEmpty =>
          sqlu"""
            UPDATE advertiser_summary
            SET total_dogeared_cta_clicks = total_dogeared_cta_clicks + 1, updated_at = NOW()
            WHERE advertiser_id = $advId
          """
        case _ => DBIO.successful(0)
      }
    } yield 0
    updates
  }

  private def processClick(e: TrackingEvent): DBIO[Done] = {
    val hourBucket = e.eventTime.truncatedTo(ChronoUnit.HOURS)

    ownerDays(e).flatMap { case (dayBucket, pubDayBucket) =>
      // Dogeared click = same user re-engaging with a creative they
      // already folded. Not a fresh CTR signal — skip every primary
      // metric and bump the parallel dogeared_clicks counters instead.
      if (e.dogeared) {
        bumpDogearedClick(e, hourBucket, dayBucket).map(_ => Done).transactionally
      } else {
        val updates = for {
          // Update campaign_stats
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            UPDATE campaign_stats
            SET clicks = clicks + 1, last_click_at = ${e.eventTime}, updated_at = NOW()
            WHERE campaign_id = $campId
          """
            case _ => DBIO.successful(0)
          }

          // Update creative_stats
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            UPDATE creative_stats
            SET clicks = clicks + 1, updated_at = NOW()
            WHERE creative_id = ${e.creativeId} AND campaign_id = $campId
          """
            case _ => DBIO.successful(0)
          }

          // Update hourly stats (insert if not exists from impression)
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_hourly_stats (campaign_id, hour_bucket, clicks, updated_at)
            VALUES ($campId, $hourBucket, 1, NOW())
            ON CONFLICT (campaign_id, hour_bucket) DO UPDATE SET
              clicks = campaign_hourly_stats.clicks + 1,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Update daily stats
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_daily_stats (campaign_id, day_bucket, clicks, updated_at)
            VALUES ($campId, $dayBucket, 1, NOW())
            ON CONFLICT (campaign_id, day_bucket) DO UPDATE SET
              clicks = campaign_daily_stats.clicks + 1,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Dimensional daily rollup (site x category)
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_dim_daily_stats (campaign_id, day_bucket, pub_day_bucket, site_id, category, clicks, updated_at)
            VALUES ($campId, $dayBucket, $pubDayBucket, ${e.siteId}, ${dimCategory(e)}, 1, NOW())
            ON CONFLICT (campaign_id, day_bucket, pub_day_bucket, site_id, category) DO UPDATE SET
              clicks = campaign_dim_daily_stats.clicks + 1,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Update advertiser summary
          _ <- e.advertiserId match {
            case Some(advId) if advId.nonEmpty =>
              sqlu"""
            UPDATE advertiser_summary
            SET total_clicks = total_clicks + 1, updated_at = NOW()
            WHERE advertiser_id = $advId
          """
            case _ => DBIO.successful(0)
          }
        } yield Done

        updates.transactionally
      }
    }
  }

  private def processCTAClick(e: TrackingEvent): DBIO[Done] = {
    val hourBucket = e.eventTime.truncatedTo(ChronoUnit.HOURS)

    ownerDays(e).flatMap { case (dayBucket, pubDayBucket) =>
      // Dogeared CTA = LP click from a bookmark-driven re-encounter.
      // First-view CTA already counted on the primary surface; this is
      // the bookmark-conversion signal, tracked on the parallel
      // dogeared_cta_clicks counters so dashboards can show "delayed
      // conversion via pin" as its own dimension.
      if (e.dogeared) {
        bumpDogearedCTAClick(e, hourBucket, dayBucket).map(_ => Done).transactionally
      } else {
        val updates = for {
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            UPDATE campaign_stats
            SET cta_clicks = cta_clicks + 1, updated_at = NOW()
            WHERE campaign_id = $campId
          """
            case _ => DBIO.successful(0)
          }

          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            UPDATE creative_stats
            SET cta_clicks = cta_clicks + 1, updated_at = NOW()
            WHERE creative_id = ${e.creativeId} AND campaign_id = $campId
          """
            case _ => DBIO.successful(0)
          }

          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_hourly_stats (campaign_id, hour_bucket, cta_clicks, updated_at)
            VALUES ($campId, $hourBucket, 1, NOW())
            ON CONFLICT (campaign_id, hour_bucket) DO UPDATE SET
              cta_clicks = campaign_hourly_stats.cta_clicks + 1,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_daily_stats (campaign_id, day_bucket, cta_clicks, updated_at)
            VALUES ($campId, $dayBucket, 1, NOW())
            ON CONFLICT (campaign_id, day_bucket) DO UPDATE SET
              cta_clicks = campaign_daily_stats.cta_clicks + 1,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          // Dimensional daily rollup (site x category)
          _ <- e.campaignId match {
            case Some(campId) if campId.nonEmpty =>
              sqlu"""
            INSERT INTO campaign_dim_daily_stats (campaign_id, day_bucket, pub_day_bucket, site_id, category, cta_clicks, updated_at)
            VALUES ($campId, $dayBucket, $pubDayBucket, ${e.siteId}, ${dimCategory(e)}, 1, NOW())
            ON CONFLICT (campaign_id, day_bucket, pub_day_bucket, site_id, category) DO UPDATE SET
              cta_clicks = campaign_dim_daily_stats.cta_clicks + 1,
              updated_at = NOW()
          """
            case _ => DBIO.successful(0)
          }

          _ <- e.advertiserId match {
            case Some(advId) if advId.nonEmpty =>
              sqlu"""
            UPDATE advertiser_summary
            SET total_cta_clicks = total_cta_clicks + 1, updated_at = NOW()
            WHERE advertiser_id = $advId
          """
            case _ => DBIO.successful(0)
          }
        } yield Done

        updates.transactionally
      }
    }
  }

  /**
   * Dog-ear fold event. Mirrors processImpression's structure but only
   * bumps fold counters — folds are a free engagement signal, not a
   * billable event, so there is no per-fold spend column. Touches no
   * publisher-day table, so only the advertiser day is resolved.
   */
  private def processFold(e: TrackingEvent): DBIO[Done] = {
    val hourBucket = e.eventTime.truncatedTo(ChronoUnit.HOURS)

    advertiserZone(e).flatMap { advZone =>
      val dayBucket = e.eventTime.atZone(advZone).toLocalDate

      val updates = for {
        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            INSERT INTO campaign_stats (campaign_id, advertiser_id, folds,
                                        last_fold_at, updated_at)
            VALUES ($campId, ${e.advertiserId}, 1, ${e.eventTime}, NOW())
            ON CONFLICT (campaign_id) DO UPDATE SET
              folds = campaign_stats.folds + 1,
              last_fold_at = ${e.eventTime},
              updated_at = NOW()
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            INSERT INTO creative_stats (creative_id, campaign_id, advertiser_id, folds, updated_at)
            VALUES (${e.creativeId}, $campId, ${e.advertiserId}, 1, NOW())
            ON CONFLICT (creative_id, campaign_id) DO UPDATE SET
              folds = creative_stats.folds + 1,
              updated_at = NOW()
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            INSERT INTO campaign_hourly_stats (campaign_id, hour_bucket, folds, updated_at)
            VALUES ($campId, $hourBucket, 1, NOW())
            ON CONFLICT (campaign_id, hour_bucket) DO UPDATE SET
              folds = campaign_hourly_stats.folds + 1,
              updated_at = NOW()
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            INSERT INTO campaign_daily_stats (campaign_id, day_bucket, folds, updated_at)
            VALUES ($campId, $dayBucket, 1, NOW())
            ON CONFLICT (campaign_id, day_bucket) DO UPDATE SET
              folds = campaign_daily_stats.folds + 1,
              updated_at = NOW()
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.advertiserId match {
          case Some(advId) if advId.nonEmpty =>
            sqlu"""
            INSERT INTO advertiser_summary (advertiser_id, total_folds, updated_at)
            VALUES ($advId, 1, NOW())
            ON CONFLICT (advertiser_id) DO UPDATE SET
              total_folds = advertiser_summary.total_folds + 1,
              updated_at = NOW()
          """
          case _ => DBIO.successful(0)
        }
      } yield Done

      updates.transactionally
    }
  }

  /**
   * Dog-ear unfold event. Telemetry only — bumps the unfolds counters so
   * the dashboard can compute pin retention as (folds - unfolds) / folds.
   * No spend impact. UPDATE-only (no INSERT) because every unfold was
   * preceded by a fold, which already created the row.
   */
  private def processUnfold(e: TrackingEvent): DBIO[Done] = {
    val hourBucket = e.eventTime.truncatedTo(ChronoUnit.HOURS)

    advertiserZone(e).flatMap { advZone =>
      val dayBucket = e.eventTime.atZone(advZone).toLocalDate

      val updates = for {
        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            UPDATE campaign_stats
            SET unfolds = unfolds + 1, updated_at = NOW()
            WHERE campaign_id = $campId
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            UPDATE creative_stats
            SET unfolds = unfolds + 1, updated_at = NOW()
            WHERE creative_id = ${e.creativeId} AND campaign_id = $campId
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            UPDATE campaign_hourly_stats
            SET unfolds = unfolds + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND hour_bucket = $hourBucket
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.campaignId match {
          case Some(campId) if campId.nonEmpty =>
            sqlu"""
            UPDATE campaign_daily_stats
            SET unfolds = unfolds + 1, updated_at = NOW()
            WHERE campaign_id = $campId AND day_bucket = $dayBucket
          """
          case _ => DBIO.successful(0)
        }

        _ <- e.advertiserId match {
          case Some(advId) if advId.nonEmpty =>
            sqlu"""
            UPDATE advertiser_summary
            SET total_unfolds = total_unfolds + 1, updated_at = NOW()
            WHERE advertiser_id = $advId
          """
          case _ => DBIO.successful(0)
        }
      } yield Done

      updates.transactionally
    }
  }
}

object DashboardProjectionHandler {
  // Implicit SetParameter instances for SQL interpolation

  given SetParameter[Instant] = SetParameter(using (i, pp) =>
    pp.setTimestamp(java.sql.Timestamp.from(i))
  )

  given SetParameter[LocalDate] = SetParameter(using (d, pp) =>
    pp.setDate(java.sql.Date.valueOf(d))
  )

  given SetParameter[Option[String]] = SetParameter(using (opt, pp) =>
    opt match {
      case Some(s) => pp.setString(s)
      case None    => pp.setNull(java.sql.Types.VARCHAR)
    }
  )

  given SetParameter[BigDecimal] = SetParameter(using (bd, pp) =>
    pp.setBigDecimal(bd.bigDecimal)
  )
}
