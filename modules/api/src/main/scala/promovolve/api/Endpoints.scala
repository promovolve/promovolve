package promovolve.api

import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.json.spray.*

/** Tapir endpoint definitions (pure descriptions, no logic) */
object Endpoints extends ApiJsonFormats {
  import ApiModels.*
  import ApiSchemas.given

  // ----------------- Path Segments -----------------

  lazy val openApiYaml: String = openApiDocs.toYaml
  val listAdvertisers: PublicEndpoint[(Int, Int), ErrorResponse, AdvertiserList, Any] =
    endpoint
      .tag("Advertisers")
      .summary("List advertisers")
      .description("Returns a paginated list of advertisers")
      .get
      .in(advertisersBase)
      .in(query[Int]("limit").default(20).description("Max items to return"))
      .in(query[Int]("offset").default(0).description("Offset for pagination"))
      .out(jsonBody[AdvertiserList])
      .errorOut(jsonBody[ErrorResponse])
  val getAdvertiser: PublicEndpoint[String, ErrorResponse, AdvertiserDetail, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Get advertiser")
      .description("Returns advertiser details including budget status")
      .get
      .in(advertisersBase / path[String]("advertiserId").description("Advertiser ID"))
      .out(jsonBody[AdvertiserDetail])
      .errorOut(
        oneOf[ErrorResponse](
          oneOfVariant(sttp.model.StatusCode.NotFound, jsonBody[ErrorResponse])
        )
      )
  val updateAdvertiserBudget: PublicEndpoint[(String, UpdateBudgetRequest), ErrorResponse, AdvertiserDetail, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Update advertiser budget")
      .description("Updates the daily budget for an advertiser")
      .put
      .in(advertisersBase / path[String]("advertiserId") / "budget")
      .in(jsonBody[UpdateBudgetRequest])
      .out(jsonBody[AdvertiserDetail])
      .errorOut(jsonBody[ErrorResponse])
  val blockSites: PublicEndpoint[(String, BlockDomainsRequest), ErrorResponse, AdvertiserDetail, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Block site domains")
      .description("Adds site domains to advertiser blocklist")
      .post
      .in(advertisersBase / path[String]("advertiserId") / "blocklist")
      .in(jsonBody[BlockDomainsRequest])
      .out(jsonBody[AdvertiserDetail])
      .errorOut(jsonBody[ErrorResponse])

  val getServedSites: PublicEndpoint[(String, Int), ErrorResponse, ServedSitesResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("List sites where this advertiser has served impressions")
      .description("Returns site_id + domain + impression count, sorted by impressions desc. Used to populate the blocklist dropdown.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "served-sites")
      .in(query[Int]("limit").default(50))
      .out(jsonBody[ServedSitesResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Advertiser Endpoints -----------------
  val unblockSites: PublicEndpoint[(String, UnblockDomainsRequest), ErrorResponse, AdvertiserDetail, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Unblock site domains")
      .description("Removes site domains from advertiser blocklist")
      .delete
      .in(advertisersBase / path[String]("advertiserId") / "blocklist")
      .in(jsonBody[UnblockDomainsRequest])
      .out(jsonBody[AdvertiserDetail])
      .errorOut(jsonBody[ErrorResponse])
  val listCampaigns: PublicEndpoint[(String, Int, Int), ErrorResponse, CampaignList, Any] =
    endpoint
      .tag("Campaigns")
      .summary("List campaigns")
      .description("Returns campaigns for an advertiser")
      .get
      .in(campaignsBase)
      .in(query[Int]("limit").default(20))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[CampaignList])
      .errorOut(jsonBody[ErrorResponse])
  val getCampaign: PublicEndpoint[(String, String), ErrorResponse, Campaign, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Get campaign")
      .description("Returns campaign details")
      .get
      .in(campaignsBase / path[String]("campaignId"))
      .out(jsonBody[Campaign])
      .errorOut(
        oneOf[ErrorResponse](
          oneOfVariant(sttp.model.StatusCode.NotFound, jsonBody[ErrorResponse])
        )
      )
  val createCampaign: PublicEndpoint[(String, CreateCampaignRequest), ErrorResponse, Campaign, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Create campaign")
      .description("Creates a new campaign for an advertiser")
      .post
      .in(campaignsBase)
      .in(jsonBody[CreateCampaignRequest])
      .out(statusCode(sttp.model.StatusCode.Created).and(jsonBody[Campaign]))
      .errorOut(jsonBody[ErrorResponse])
  val updateCampaign: PublicEndpoint[(String, String, UpdateCampaignRequest), ErrorResponse, Campaign, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Update campaign")
      .description("Updates an existing campaign")
      .patch
      .in(campaignsBase / path[String]("campaignId"))
      .in(jsonBody[UpdateCampaignRequest])
      .out(jsonBody[Campaign])
      .errorOut(jsonBody[ErrorResponse])
  val getCampaignBudget: PublicEndpoint[(String, String), ErrorResponse, CampaignBudgetInfo, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Get campaign budget")
      .description("Returns campaign budget and spend information")
      .get
      .in(campaignsBase / path[String]("campaignId") / "budget")
      .out(jsonBody[CampaignBudgetInfo])
      .errorOut(jsonBody[ErrorResponse])

  val getCampaignWinRate: PublicEndpoint[(String, String), ErrorResponse, CampaignWinRateInfo, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Get campaign win rate")
      .description("Returns auction participation count and win rate (impressions / bids today)")
      .get
      .in(campaignsBase / path[String]("campaignId") / "win-rate")
      .out(jsonBody[CampaignWinRateInfo])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Campaign Endpoints -----------------
  val unassignCreatives: PublicEndpoint[(String, String, AssignCreativesRequest), ErrorResponse, Campaign, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Unassign creatives")
      .description("Removes creatives from a campaign")
      .delete
      .in(campaignsBase / path[String]("campaignId") / "creatives")
      .in(jsonBody[AssignCreativesRequest])
      .out(jsonBody[Campaign])
      .errorOut(jsonBody[ErrorResponse])
  val listCreatives: PublicEndpoint[(String, String, Int, Int), ErrorResponse, CreativeList, Any] =
    endpoint
      .tag("Creatives")
      .summary("List creatives")
      .description("Returns creatives for a campaign")
      .get
      .in(creativesBase)
      .in(query[Int]("limit").default(20))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[CreativeList])
      .errorOut(jsonBody[ErrorResponse])
  val getPublisher: PublicEndpoint[String, ErrorResponse, Publisher, Any] =
    endpoint
      .tag("Publishers")
      .summary("Get publisher")
      .description("Returns publisher details")
      .get
      .in(publishersBase / path[String]("publisherId"))
      .out(jsonBody[Publisher])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Creative Endpoints -----------------
  val blockDomains: PublicEndpoint[(String, BlockDomainsRequest), ErrorResponse, Publisher, Any] =
    endpoint
      .tag("Publishers")
      .summary("Block domains")
      .description("Adds domains to publisher blocklist")
      .post
      .in(publishersBase / path[String]("publisherId") / "blocklist")
      .in(jsonBody[BlockDomainsRequest])
      .out(jsonBody[Publisher])
      .errorOut(jsonBody[ErrorResponse])
  val unblockDomains: PublicEndpoint[(String, UnblockDomainsRequest), ErrorResponse, Publisher, Any] =
    endpoint
      .tag("Publishers")
      .summary("Unblock domains")
      .description("Removes domains from publisher blocklist")
      .delete
      .in(publishersBase / path[String]("publisherId") / "blocklist")
      .in(jsonBody[UnblockDomainsRequest])
      .out(jsonBody[Publisher])
      .errorOut(jsonBody[ErrorResponse])
  val listSites: PublicEndpoint[(String, Int, Int), ErrorResponse, SiteList, Any] =
    endpoint
      .tag("Sites")
      .summary("List sites")
      .description("Returns sites for a publisher")
      .get
      .in(sitesBase)
      .in(query[Int]("limit").default(20))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[SiteList])
      .errorOut(jsonBody[ErrorResponse])
  val getSite: PublicEndpoint[(String, String), ErrorResponse, Site, Any] =
    endpoint
      .tag("Sites")
      .summary("Get site")
      .description("Returns site details")
      .get
      .in(sitesBase / path[String]("siteId"))
      .out(jsonBody[Site])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Publisher Endpoints -----------------
  val createSite: PublicEndpoint[(String, CreateSiteRequest), ErrorResponse, Site, Any] =
    endpoint
      .tag("Sites")
      .summary("Create site")
      .description("Registers a new site for a publisher")
      .post
      .in(sitesBase)
      .in(jsonBody[CreateSiteRequest])
      .out(statusCode(sttp.model.StatusCode.Created).and(jsonBody[Site]))
      .errorOut(jsonBody[ErrorResponse])
  val updateSite: PublicEndpoint[(String, String, UpdateSiteRequest), ErrorResponse, Site, Any] =
    endpoint
      .tag("Sites")
      .summary("Update site")
      .description("Updates site configuration")
      .patch
      .in(sitesBase / path[String]("siteId"))
      .in(jsonBody[UpdateSiteRequest])
      .out(jsonBody[Site])
      .errorOut(jsonBody[ErrorResponse])
  val deleteSite: PublicEndpoint[(String, String), ErrorResponse, Unit, Any] =
    endpoint
      .tag("Sites")
      .summary("Delete site")
      .description("Removes a site from the publisher")
      .delete
      .in(sitesBase / path[String]("siteId"))
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(jsonBody[ErrorResponse])
  // ---------- Site Verification ----------

  val getVerificationToken: PublicEndpoint[(String, String), ErrorResponse, VerificationTokenResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Get verification token")
      .description("Returns the verification token and instructions for proving domain ownership")
      .get
      .in(sitesBase / path[String]("siteId") / "verification-token")
      .out(jsonBody[VerificationTokenResponse])
      .errorOut(jsonBody[ErrorResponse])

  val verifySite: PublicEndpoint[(String, String), ErrorResponse, VerificationResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Verify site ownership")
      .description("Triggers HTTP-file verification check for the site's domain")
      .post
      .in(sitesBase / path[String]("siteId") / "verify")
      .out(jsonBody[VerificationResponse])
      .errorOut(jsonBody[ErrorResponse])

  val forceVerifySite: PublicEndpoint[(String, String, String), ErrorResponse, VerificationResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Force-verify site (dev/testing)")
      .description("Bypasses HTTP verification and sets the verified host directly")
      .post
      .in(sitesBase / path[String]("siteId") / "force-verify")
      .in(query[String]("host"))
      .out(jsonBody[VerificationResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ---------- Pacing ----------

  val getSitePacingConfig: PublicEndpoint[(String, String), ErrorResponse, PacingConfig, Any] =
    endpoint
      .tag("Sites")
      .summary("Get site pacing config")
      .description("Returns the pacing configuration for a site. Pacing controls budget-aware throttling with PI controller parameters and peak/off-peak multipliers.")
      .get
      .in(sitesBase / path[String]("siteId") / "pacing")
      .out(jsonBody[PacingConfig])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Site Endpoints -----------------
  val updateSitePacingConfig: PublicEndpoint[(String, String, UpdatePacingConfigRequest), ErrorResponse, PacingConfig, Any] =
    endpoint
      .tag("Sites")
      .summary("Update site pacing config")
      .description("Updates the pacing configuration for a site. windowSeconds controls the sliding window size for rate calculation. testThrottleOverride can force a fixed throttle probability for testing.")
      .put
      .in(sitesBase / path[String]("siteId") / "pacing")
      .in(jsonBody[UpdatePacingConfigRequest])
      .out(jsonBody[PacingConfig])
      .errorOut(jsonBody[ErrorResponse])

  val updateSlotFloorOverride: PublicEndpoint[(String, String, String, UpdateSlotFloorRequest), ErrorResponse, SiteSlotConfig, Any] =
    endpoint
      .tag("Sites")
      .summary("Set or clear per-slot floor override (admin escape hatch)")
      .description("Per-slot manual floor. Beats RL and prior. Pass floorCpm=null (or omit) to clear and let the slot fall back to RL/prior.")
      .put
      .in(sitesBase / path[String]("siteId") / "slots" / path[String]("slotId") / "floor")
      .in(jsonBody[UpdateSlotFloorRequest])
      .out(jsonBody[SiteSlotConfig])
      .errorOut(jsonBody[ErrorResponse])

  val getFloorObservations: PublicEndpoint[(String, String, Int), ErrorResponse, RecentFloorObservationsResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Get recent floor-RL decisions for a site")
      .description("Returns the in-memory ring buffer of recent observation windows (most-recent first). Each entry shows what the floor-CPM agent did or skipped that window. Not persisted; available only while the SiteEntity actor is running.")
      .get
      .in(sitesBase / path[String]("siteId") / "floor-observations")
      .in(query[Int]("limit").default(100))
      .out(jsonBody[RecentFloorObservationsResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getFloorSweepEvidence: PublicEndpoint[(String, String), ErrorResponse, FloorSweepEvidenceResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Get the floor-sweep optimizer's per-candidate evidence table")
      .description("Returns the live snapshot of the in-memory FloorSweepOptimizer: which floors it has tried during the current cycle, the realized revenue and impression count for each, and the chosen argmax.")
      .get
      .in(sitesBase / path[String]("siteId") / "sweep-evidence")
      .out(jsonBody[FloorSweepEvidenceResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getFloorSweepHistory: PublicEndpoint[(String, String, Int, Option[String]), ErrorResponse, FloorSweepHistoryResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Get persisted argmax history for the sweep optimizer")
      .description("Returns one row per completed sweep cycle from the floor_decisions table, newest-first. Backed by persistent storage so history survives cluster restarts. Default limit 200. Optional `date` (YYYY-MM-DD UTC) filters to a single day; when set, limit is effectively unbounded for that day's cycles.")
      .get
      .in(sitesBase / path[String]("siteId") / "sweep-history")
      .in(query[Int]("limit").default(200))
      .in(query[Option[String]]("date").description("UTC date in YYYY-MM-DD format. When set, returns all cycles from that calendar day instead of the most-recent N."))
      .out(jsonBody[FloorSweepHistoryResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getCategoryDemand: PublicEndpoint[(String, String), ErrorResponse, CategoryDemandResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Live per-category floors + current demand (bidder counts)")
      .description("Returns each demand category's learned floor together with the LIVE bidder count and top observed bid from the site's latest auction rounds. Bidder count 0 (or a category absent from recent auctions) marks a historical floor nobody currently bids into. Counts are rebuilt from auction traffic after a restart.")
      .get
      .in(sitesBase / path[String]("siteId") / "category-demand")
      .out(jsonBody[CategoryDemandResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getSiteRevenueToday: PublicEndpoint[(String, String), ErrorResponse, SiteRevenueTodayResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Today's revenue for a site, sourced from tracking_events")
      .description("Aggregates impression revenue from the tracking_events table since UTC midnight. Used by the publisher dashboard's Revenue tile so it aligns with the advertiser-side spend numbers (both query the same projection). Returns zeros if the projection DB isn't configured.")
      .get
      .in(sitesBase / path[String]("siteId") / "revenue-today")
      .out(jsonBody[SiteRevenueTodayResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserSpendToday: PublicEndpoint[String, ErrorResponse, AdvertiserSpendTodayResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Today's spend for an advertiser, sourced from tracking_events")
      .description("Mirror of /sites/{id}/revenue-today filtered by advertiser_id. Used by the advertiser dashboard's Today's Spend tile to align with publisher Revenue.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "spend-today")
      .out(jsonBody[AdvertiserSpendTodayResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserWinRates: PublicEndpoint[String, ErrorResponse, AdvertiserWinRatesResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Win rates (impression share) for all of an advertiser's campaigns in one call")
      .description("Batch form of /campaigns/{id}/win-rate — one entity fan-out and one projection query instead of a round-trip per campaign. The dashboard's campaigns page uses this.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "win-rates")
      .out(jsonBody[AdvertiserWinRatesResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserCampaignSpendToday: PublicEndpoint[String, ErrorResponse, AdvertiserCampaignSpendTodayResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Today's spend per campaign, sourced from tracking_events")
      .description("Per-campaign slice of /spend-today (same source + UTC midnight boundary), so campaign rows reconcile with the account-level tile. Campaigns with no impressions today are omitted.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "campaign-spend-today")
      .out(jsonBody[AdvertiserCampaignSpendTodayResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserHourlyToday: PublicEndpoint[String, ErrorResponse, AdvertiserHourlyTodayResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Today's delivery bucketed by UTC hour")
      .description("Impressions + spend per UTC hour since midnight, from tracking_events. Drives the hourly delivery chart on the advertiser stats page.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "hourly-today")
      .out(jsonBody[AdvertiserHourlyTodayResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserReport: PublicEndpoint[(String, Option[String], Option[String]), ErrorResponse, AdvertiserReportResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Date-ranged daily report, one row per (UTC day, campaign)")
      .description("Funnel rows from campaign_daily_stats for the inclusive UTC date range. Defaults to the last 7 days including today; the range is capped at 92 days. Days without delivery have no row. Drives the advertiser report page and its CSV export.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "report")
      .in(query[Option[String]]("from").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[Option[String]]("to").description("UTC day, YYYY-MM-DD, inclusive"))
      .out(jsonBody[AdvertiserReportResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserReportBreakdown: PublicEndpoint[(String, Option[String], Option[String], String), ErrorResponse, AdvertiserReportBreakdownResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Range report broken down by site, category, or publisher")
      .description("Aggregates campaign_dim_daily_stats over the inclusive UTC range per dimension value. The rollup accrues from its ship date plus a bounded backfill, so coverageFrom reports the earliest day with data for this advertiser — the platform surfaces it when later than the requested from.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "report" / "breakdown")
      .in(query[Option[String]]("from").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[Option[String]]("to").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[String]("dim").description("site | category | publisher"))
      .out(jsonBody[AdvertiserReportBreakdownResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserReportBreakdownByCampaign: PublicEndpoint[(String, Option[String], Option[String], String), ErrorResponse, AdvertiserReportBreakdownByCampaignResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Range breakdown split by campaign within each dimension value")
      .description("One row per (site|category value, campaign) aggregated over the inclusive UTC range — drives the nested site→campaign and category→campaign report tables. Groups are ordered by total dimension spend, campaigns within a group by spend.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "report" / "breakdown-by-campaign")
      .in(query[Option[String]]("from").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[Option[String]]("to").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[String]("dim").description("site | category"))
      .out(jsonBody[AdvertiserReportBreakdownByCampaignResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getAdvertiserReportBreakdownDaily: PublicEndpoint[(String, Option[String], Option[String], String), ErrorResponse, AdvertiserReportBreakdownDailyResponse, Any] =
    endpoint
      .tag("Advertisers")
      .summary("Day-level breakdown rows for per-dimension time-series charts")
      .description("Same aggregation as the range breakdown but keyed by (UTC day, dimension value) — one row per day per value with delivery. Days without delivery have no row; the platform zero-fills the calendar. Same range semantics as the range breakdown.")
      .get
      .in(advertisersBase / path[String]("advertiserId") / "report" / "breakdown-daily")
      .in(query[Option[String]]("from").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[Option[String]]("to").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[String]("dim").description("site | category | publisher"))
      .out(jsonBody[AdvertiserReportBreakdownDailyResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getPublisherSiteCategoryReportDaily: PublicEndpoint[(String, Option[String], Option[String]), ErrorResponse, PublisherSiteCategoryDailyReportResponse, Any] =
    endpoint
      .tag("Publishers")
      .summary("Day-level (site, category) rows for the publisher report's time-series charts")
      .description("Same aggregation as the range site-category report but keyed by (UTC day, site, category). Days without delivery have no row; the platform zero-fills the calendar. Revenue is gross advertiser spend.")
      .get
      .in(publishersBase / path[String]("publisherId") / "report" / "site-categories-daily")
      .in(query[Option[String]]("from").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[Option[String]]("to").description("UTC day, YYYY-MM-DD, inclusive"))
      .out(jsonBody[PublisherSiteCategoryDailyReportResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getPublisherSiteCategoryReport: PublicEndpoint[(String, Option[String], Option[String]), ErrorResponse, PublisherSiteCategoryReportResponse, Any] =
    endpoint
      .tag("Publishers")
      .summary("Range report per (site, category) for a publisher's sites")
      .description("Aggregates campaign_dim_daily_stats over the inclusive UTC range for every site owned by the publisher, one row per (site, category). Revenue is gross advertiser spend — the platform applies its margin for display. Same range semantics and coverageFrom caveat as the advertiser report breakdown.")
      .get
      .in(publishersBase / path[String]("publisherId") / "report" / "site-categories")
      .in(query[Option[String]]("from").description("UTC day, YYYY-MM-DD, inclusive"))
      .in(query[Option[String]]("to").description("UTC day, YYYY-MM-DD, inclusive"))
      .out(jsonBody[PublisherSiteCategoryReportResponse])
      .errorOut(jsonBody[ErrorResponse])

  val resetFloorAgent: PublicEndpoint[(String, String), ErrorResponse, Unit, Any] =
    endpoint
      .tag("Sites")
      .summary("Reset the floor-RL agent for a site (admin escape hatch)")
      .description("Wipes the persisted agent snapshot and force-reloads the shipped warm-start default. Use when a site's agent has drifted to a pathological state. Logs a warning.")
      .post
      .in(sitesBase / path[String]("siteId") / "reset-floor-agent")
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Internal (platform-only) endpoints -----------------
  // Consumed by the Go platform's billing settlement job (docs/design/
  // BILLING.md). Gated by INTERNAL_API_KEY when set (X-Internal-Key header);
  // when unset they rely on network isolation like the other escape hatches.

  val getMeteringDaily: PublicEndpoint[(String, Option[Boolean], Option[String]), ErrorResponse, MeteringDailyResponse, Any] =
    endpoint
      .tag("Internal")
      .summary("Billable metering aggregate for one UTC day")
      .description("Per (advertiser, campaign, site) impression count and gross dollars from tracking_events for the given UTC day, with publisherId joined from publisher_sites. Dog-eared impressions are excluded — they never debit campaign budget, so they are not billable. Must be called within the tracking_events 30-day retention window; the settlement job runs daily and catches up on startup.")
      .get
      .in(v1 / "internal" / "metering" / "daily")
      .in(query[String]("date").description("UTC day in YYYY-MM-DD format"))
      .in(query[Option[Boolean]]("allowPartial").description("Operator/test only: settle an in-progress (non-final) UTC day. Still requires the internal key. The scheduled job never sets this."))
      .in(header[Option[String]]("X-Internal-Key"))
      .out(jsonBody[MeteringDailyResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getMeteringIntraday: PublicEndpoint[(String, Option[String]), ErrorResponse, MeteringIntradayResponse, Any] =
    endpoint
      .tag("Internal")
      .summary("Unsettled gross per advertiser since a UTC date")
      .description("Per-advertiser billable gross from tracking_events between the given UTC date's midnight and now — spend not yet booked to the platform ledger. The settlement job uses this to project wallet balances between daily settlements and suspend unfunded advertisers early. Same billing rules as /metering/daily: impressions only, dog-eared excluded.")
      .get
      .in(v1 / "internal" / "metering" / "intraday")
      .in(query[String]("since").description("start of the unsettled window (UTC day, YYYY-MM-DD), typically the day after the last settled day"))
      .in(header[Option[String]]("X-Internal-Key"))
      .out(jsonBody[MeteringIntradayResponse])
      .errorOut(jsonBody[ErrorResponse])

  val suspendAdvertiser: PublicEndpoint[(String, Option[String]), ErrorResponse, Unit, Any] =
    endpoint
      .tag("Internal")
      .summary("Suspend an advertiser (unfunded wallet) — stops serving")
      .description("Sets the advertiser's status to Suspended, which publishes AdvertiserSuspended so every AdServer purges the advertiser from its ServeIndex, and makes the advertiser bid with zero budget at re-auctions. Called by the platform when a prepaid wallet hits zero.")
      .post
      .in(v1 / "internal" / "advertisers" / path[String]("advertiserId") / "suspend")
      .in(header[Option[String]]("X-Internal-Key"))
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(jsonBody[ErrorResponse])

  val resumeAdvertiser: PublicEndpoint[(String, Option[String]), ErrorResponse, Unit, Any] =
    endpoint
      .tag("Internal")
      .summary("Resume a suspended advertiser (wallet funded again)")
      .description("Sets the advertiser's status back to Active and publishes AdvertiserBudgetReset so auctioneers re-run site auctions with the advertiser participating. Called by the platform after a top-up brings a suspended wallet above zero.")
      .post
      .in(v1 / "internal" / "advertisers" / path[String]("advertiserId") / "resume")
      .in(header[Option[String]]("X-Internal-Key"))
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(jsonBody[ErrorResponse])
  val listTaxonomyCategories: PublicEndpoint[(Option[String], Int, Int), ErrorResponse, TaxonomyCategoryList, Any] =
    endpoint
      .tag("Taxonomy")
      .summary("List content categories")
      .description("Returns IAB Content Taxonomy 2.1 categories for page classification")
      .get
      .in(v1 / "taxonomy" / "categories")
      .in(query[Option[String]]("q").description("Search query to filter categories by name or ID"))
      .in(query[Int]("limit").default(100))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[TaxonomyCategoryList])
      .errorOut(jsonBody[ErrorResponse])

  // All registered (verified) publisher sites — for campaign media targeting.
  // Server-side `q` filter keeps the payload small as site count grows.
  val listRegisteredSites: PublicEndpoint[(Option[String], Int, Int), ErrorResponse, VerifiedSiteList, Any] =
    endpoint
      .tag("Sites")
      .summary("List registered (verified) sites")
      .description("Returns verified publisher sites (siteId + domain) for campaign media targeting")
      .get
      .in(v1 / "sites")
      .in(query[Option[String]]("q").description("Filter by domain substring"))
      .in(query[Int]("limit").default(100))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[VerifiedSiteList])
      .errorOut(jsonBody[ErrorResponse])

  // Distinct advertiser landing-page domains — for the publisher's advertiser-
  // domain block picker. Server-side `q` filter.
  val listAdvertiserDomains: PublicEndpoint[(Option[String], Int, Int), ErrorResponse, AdvertiserDomainList, Any] =
    endpoint
      .tag("Sites")
      .summary("List advertiser landing-page domains")
      .description("Distinct advertiser LP domains, for the publisher's domain-block picker")
      .get
      .in(v1 / "advertiser-domains")
      .in(query[Option[String]]("q").description("Filter by domain substring"))
      .in(query[Int]("limit").default(100))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[AdvertiserDomainList])
      .errorOut(jsonBody[ErrorResponse])

  val listAdProductCategories: PublicEndpoint[(Option[String], Int, Int), ErrorResponse, TaxonomyCategoryList, Any] =
    endpoint
      .tag("Taxonomy")
      .summary("List ad product categories")
      .description("Returns IAB Ad Product Taxonomy 2.0 categories for campaign targeting")
      .get
      .in(v1 / "taxonomy" / "ad-products")
      .in(query[Option[String]]("q").description("Search query to filter categories by name or ID"))
      .in(query[Int]("limit").default(100))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[TaxonomyCategoryList])
      .errorOut(jsonBody[ErrorResponse])
  val listPendingCreatives: PublicEndpoint[(String, String, Int, Int), ErrorResponse, PendingCreativeGroupList, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("List pending creatives")
      .description("Returns creatives awaiting publisher approval for a site, grouped by creative with all placements")
      .get
      .in(approvalBase / "pending")
      .in(query[Int]("limit").default(50))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[PendingCreativeGroupList])
      .errorOut(jsonBody[ErrorResponse])

  val listServingCreatives: PublicEndpoint[(String, String, Int), ErrorResponse, ServingCreativeGroupList, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("List serving creatives")
      .description(
        "Returns currently-serving creatives for a site, each with the (url, slot) places "
          + "they have actually been delivered to over the lookback window. ServeIndex is "
          + "slot-keyed with no URL, so the placement list is sourced from tracking_events."
      )
      .get
      .in(approvalBase / "serving")
      .in(query[Int]("hours").default(24).description("Impression lookback window in hours"))
      .out(jsonBody[ServingCreativeGroupList])
      .errorOut(jsonBody[ErrorResponse])
  val approveCreative: PublicEndpoint[(String, String, ApproveCreativeRequest), ErrorResponse, ApproveCreativeResponse, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("Approve creative")
      .description("Approves a creative for serving on this site")
      .post
      .in(approvalBase / "approve")
      .in(jsonBody[ApproveCreativeRequest])
      .out(jsonBody[ApproveCreativeResponse])
      .errorOut(jsonBody[ErrorResponse])
  val rejectCreative: PublicEndpoint[(String, String, RejectCreativeRequest), ErrorResponse, RejectCreativeResponse, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("Reject creative")
      .description("Rejects a creative from serving on this site")
      .post
      .in(approvalBase / "reject")
      .in(jsonBody[RejectCreativeRequest])
      .out(jsonBody[RejectCreativeResponse])
      .errorOut(jsonBody[ErrorResponse])

  val flagCreative: PublicEndpoint[(String, String, FlagCreativeRequest), ErrorResponse, FlagCreativeResponse, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("Flag creative")
      .description("Flags a creative (quarantines it from pending queue) for later review")
      .post
      .in(approvalBase / "flag")
      .in(jsonBody[FlagCreativeRequest])
      .out(jsonBody[FlagCreativeResponse])
      .errorOut(jsonBody[ErrorResponse])

  val unflagCreative: PublicEndpoint[(String, String, UnflagCreativeRequest), ErrorResponse, UnflagCreativeResponse, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("Unflag creative")
      .description("Unflags a creative (returns it to pending queue)")
      .post
      .in(approvalBase / "unflag")
      .in(jsonBody[UnflagCreativeRequest])
      .out(jsonBody[UnflagCreativeResponse])
      .errorOut(jsonBody[ErrorResponse])

  val revokeCreative: PublicEndpoint[(String, String, RevokeCreativeRequest), ErrorResponse, RevokeCreativeResponse, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("Revoke creative approval")
      .description("Revokes a creative's approval status (removes from ServeIndex, clears both approved and rejected filters). Creative returns to pending queue.")
      .post
      .in(approvalBase / "revoke")
      .in(jsonBody[RevokeCreativeRequest])
      .out(jsonBody[RevokeCreativeResponse])
      .errorOut(jsonBody[ErrorResponse])

  val listFlaggedCreatives: PublicEndpoint[(String, String, Int, Int), ErrorResponse, FlaggedCreativeList, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("List flagged creatives")
      .description("Returns creatives that have been flagged/quarantined by the publisher")
      .get
      .in(approvalBase / "flagged")
      .in(query[Int]("limit").default(50))
      .in(query[Int]("offset").default(0))
      .out(jsonBody[FlaggedCreativeList])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Campaign Status -----------------
  val updateCampaignStatus: PublicEndpoint[(String, String, UpdateCampaignStatusRequest), ErrorResponse, Campaign, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Update campaign status")
      .description("Activates or pauses a campaign")
      .put
      .in(campaignsBase / path[String]("campaignId") / "status")
      .in(jsonBody[UpdateCampaignStatusRequest])
      .out(jsonBody[Campaign])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Campaign Ad Product Category -----------------
  val updateCampaignAdProduct: PublicEndpoint[(String, String, UpdateAdProductCategoryRequest), ErrorResponse, UpdateAdProductCategoryResponse, Any] =
    endpoint
      .tag("Campaigns")
      .summary("Update campaign ad product category")
      .description("Sets the IAB Ad Product Taxonomy 2.0 category for a campaign (what the campaign is advertising)")
      .put
      .in(campaignsBase / path[String]("campaignId") / "ad-product")
      .in(jsonBody[UpdateAdProductCategoryRequest])
      .out(jsonBody[UpdateAdProductCategoryResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Page Classification -----------------
  val classifyPage: PublicEndpoint[(String, String, ClassifyPageRequest), ErrorResponse, ClassifyPageResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Classify page")
      .description("Triggers auction for a classified page (called by crawler)")
      .post
      .in(sitesBase / path[String]("siteId") / "classify")
      .in(jsonBody[ClassifyPageRequest])
      .out(jsonBody[ClassifyPageResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Bulk Approval -----------------
  val bulkApprove: PublicEndpoint[(String, String, BulkApproveRequest), ErrorResponse, BulkApproveResponse, Any] =
    endpoint
      .tag("Approval Queue")
      .summary("Bulk approve creatives")
      .description("Approves all pending creatives for a URL/slot")
      .post
      .in(approvalBase / "bulk")
      .in(jsonBody[BulkApproveRequest])
      .out(jsonBody[BulkApproveResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Site Stats -----------------
  val getSiteStats: PublicEndpoint[(String, String), ErrorResponse, SiteStats, Any] =
    endpoint
      .tag("Sites")
      .summary("Get site stats")
      .description("Returns serve outcome statistics and pacing info for a site")
      .get
      .in(sitesBase / path[String]("siteId") / "stats")
      .out(jsonBody[SiteStats])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- AdServer Stats -----------------
  val getAdServerStats: PublicEndpoint[(String, String), ErrorResponse, AdServerStatsResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Get AdServer stats")
      .description("Returns per-creative Thompson Sampling statistics")
      .get
      .in(sitesBase / path[String]("siteId") / "adserver-stats")
      .out(jsonBody[AdServerStatsResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Passivate AdServer -----------------
  val passivateAdServer: PublicEndpoint[(String, String), ErrorResponse, Unit, Any] =
    endpoint
      .tag("Sites")
      .summary("Passivate AdServer entity")
      .description("Forces the AdServer entity to stop. Next request will restart it with fresh state loaded from DB.")
      .post
      .in(sitesBase / path[String]("siteId") / "passivate")
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Site Ad Product Blocklist -----------------
  val getAdProductBlocklist: PublicEndpoint[(String, String), ErrorResponse, AdProductBlocklistResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Get ad product blocklist")
      .description("Returns IAB Ad Product categories blocked for this site")
      .get
      .in(sitesBase / path[String]("siteId") / "ad-product-blocklist")
      .out(jsonBody[AdProductBlocklistResponse])
      .errorOut(jsonBody[ErrorResponse])

  val blockAdProducts: PublicEndpoint[(String, String, AdProductBlocklistRequest), ErrorResponse, AdProductBlocklistResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Block ad product categories")
      .description("Adds IAB Ad Product categories to the site blocklist. Campaigns with these categories will not serve.")
      .post
      .in(sitesBase / path[String]("siteId") / "ad-product-blocklist")
      .in(jsonBody[AdProductBlocklistRequest])
      .out(jsonBody[AdProductBlocklistResponse])
      .errorOut(jsonBody[ErrorResponse])

  val unblockAdProducts: PublicEndpoint[(String, String, AdProductUnblockRequest), ErrorResponse, AdProductUnblockResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Unblock ad product categories")
      .description("Removes IAB Ad Product categories from the site blocklist")
      .delete
      .in(sitesBase / path[String]("siteId") / "ad-product-blocklist")
      .in(jsonBody[AdProductUnblockRequest])
      .out(jsonBody[AdProductUnblockResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Serve Index -----------------
  val getServeIndex: PublicEndpoint[(String, String, Option[String]), ErrorResponse, ServeIndexResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Get serve index")
      .description("Returns candidates in ServeIndex for a slot (or all slots if slotId omitted)")
      .get
      .in(sitesBase / path[String]("siteId") / "serve-index")
      .in(query[Option[String]]("slotId").description("Slot ID (optional — omit to get all slots merged)"))
      .out(jsonBody[ServeIndexResponse])
      .errorOut(jsonBody[ErrorResponse])

  val getServeIndexKeys: PublicEndpoint[(String, String), ErrorResponse, ServeIndexKeysResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("List serve index keys")
      .description("Returns all slot IDs with ServeIndex entries for a site")
      .get
      .in(sitesBase / path[String]("siteId") / "serve-index" / "keys")
      .out(jsonBody[ServeIndexKeysResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Pacing Reset -----------------
  val resetPacing: PublicEndpoint[(String, String, PacingResetRequest), ErrorResponse, PacingResetResponse, Any] =
    endpoint
      .tag("Sites")
      .summary("Reset pacing")
      .description("Resets campaign day starts to current time for pacing synchronization")
      .post
      .in(sitesBase / path[String]("siteId") / "pacing" / "reset")
      .in(jsonBody[PacingResetRequest])
      .out(jsonBody[PacingResetResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Taxonomy Stats -----------------
  // Owner-scoped: publisherId+siteId path segments (pinned by the BFF) gate
  // access to a site's per-category Thompson-sampling stats. Was
  // /v1/taxonomy/categories/{category}/sites/{siteId}/stats with no owner
  // segment (IDOR — any caller could read a competitor's per-site CTR).
  val getTaxonomyStats: PublicEndpoint[(String, String, String), ErrorResponse, TaxonomyStatsResponse, Any] =
    endpoint
      .tag("Taxonomy")
      .summary("Get taxonomy stats")
      .description("Returns Thompson Sampling statistics for a category on a site")
      .get
      .in(v1 / "publishers" / path[String]("publisherId") / "sites" / path[String]("siteId") / "categories" / path[String]("category") / "stats")
      .out(jsonBody[TaxonomyStatsResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Auction Category Registration -----------------
  val registerCampaigns: PublicEndpoint[(String, RegisterCampaignsRequest), ErrorResponse, RegisterCampaignsResponse, Any] =
    endpoint
      .tag("Auction")
      .summary("Register campaigns with category")
      .description("Registers campaigns with a CategoryBidder for auction participation")
      .post
      .in(v1 / "auction" / "categories" / path[String]("category") / "campaigns")
      .in(jsonBody[RegisterCampaignsRequest])
      .out(jsonBody[RegisterCampaignsResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Category Verification -----------------
  val verifyCategory: PublicEndpoint[VerifyCategoryRequest, ErrorResponse, VerifyCategoryResponse, Any] =
    endpoint
      .tag("Assessment")
      .summary("Verify creative category")
      .description("Verifies if an image matches a declared IAB category using LLM assessment")
      .post
      .in(v1 / "assessment" / "verify-category")
      .in(jsonBody[VerifyCategoryRequest])
      .out(jsonBody[VerifyCategoryResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Magazine Creative (expandable banner) -----------------
  val createCreative: PublicEndpoint[(String, String, CreateCreativeRequest), ErrorResponse, CreateCreativeResponse, Any] =
    endpoint
      .tag("Creatives")
      .summary("Create creative from extracted LP pages")
      .description("Creates an expandable magazine banner creative from pages JSON (extracted via /extract-from-lp or hand-crafted)")
      .post
      .in(creativesBase)
      .in(jsonBody[CreateCreativeRequest])
      .out(statusCode(sttp.model.StatusCode.Created).and(jsonBody[CreateCreativeResponse]))
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Creative Status -----------------
  val updateCreativeStatus: PublicEndpoint[(String, String, String, UpdateCreativeStatusRequest), ErrorResponse, UpdateCreativeStatusResponse, Any] =
    endpoint
      .tag("Creatives")
      .summary("Update creative status")
      .description("Pause or reactivate a creative. When paused, the creative is immediately removed from ad serving. When reactivated, a re-auction is triggered to restore it.")
      .patch
      .in(creativesBase / path[String]("creativeId") / "status")
      .in(jsonBody[UpdateCreativeStatusRequest])
      .out(jsonBody[UpdateCreativeStatusResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Login -----------------
  val login: PublicEndpoint[LoginRequest, ErrorResponse, LoginResponse, Any] =
    endpoint
      .tag("Auth")
      .summary("Login with email")
      .description("Login with email to access advertiser account. Creates new advertiser if email not found.")
      .post
      .in("v1" / "login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[LoginResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- Publisher Login -----------------
  val publisherLogin: PublicEndpoint[PublisherLoginRequest, ErrorResponse, PublisherLoginResponse, Any] =
    endpoint
      .tag("Auth")
      .summary("Publisher login with email")
      .description("Login with email to access publisher account. Creates new publisher if email not found.")
      .post
      .in("v1" / "publisher-login")
      .in(jsonBody[PublisherLoginRequest])
      .out(jsonBody[PublisherLoginResponse])
      .errorOut(jsonBody[ErrorResponse])

  // ----------------- All Endpoints -----------------
  val all: List[AnyEndpoint] = List(
    // Auth
    login,
    publisherLogin,
    // Advertisers
    listAdvertisers,
    getAdvertiser,
    updateAdvertiserBudget,
    blockSites,
    unblockSites,
    getServedSites,
    // Campaigns
    listCampaigns,
    getCampaign,
    createCampaign,
    updateCampaign,
    getCampaignBudget,
    getCampaignWinRate,
    unassignCreatives,
    updateCampaignStatus,
    updateCampaignAdProduct,
    // Creatives
    listCreatives,
    createCreative,
    // Publishers
    getPublisher,
    blockDomains,
    unblockDomains,
    // Sites
    listSites,
    getSite,
    createSite,
    updateSite,
    deleteSite,
    classifyPage,
    getSiteStats,
    getAdServerStats,
    passivateAdServer,
    getAdProductBlocklist,
    blockAdProducts,
    unblockAdProducts,
    getServeIndex,
    getServeIndexKeys,
    // Pacing
    getSitePacingConfig,
    updateSitePacingConfig,
    resetPacing,
    // Taxonomy
    listTaxonomyCategories,
    listRegisteredSites,
    listAdvertiserDomains,
    getTaxonomyStats,
    // Approval Queue
    listPendingCreatives,
    listServingCreatives,
    approveCreative,
    rejectCreative,
    flagCreative,
    unflagCreative,
    revokeCreative,
    listFlaggedCreatives,
    bulkApprove,
    // Auction
    registerCampaigns,
    // Assessment
    verifyCategory,
    // Creative Status
    updateCreativeStatus
  )
  val openApiDocs: sttp.apispec.openapi.OpenAPI =
    OpenAPIDocsInterpreter().toOpenAPI(all, "Promovolve API", "1.0.0")

  // ----------------- Path Base Definitions -----------------
  // Must be lazy to avoid null references during object initialization
  private lazy val v1              = "v1"
  private lazy val advertisersBase = v1 / "advertisers"
  private lazy val campaignsBase   = advertisersBase / path[String]("advertiserId") / "campaigns"
  private lazy val creativesBase   = campaignsBase / path[String]("campaignId") / "creatives"
  private lazy val publishersBase  = v1 / "publishers"
  private lazy val sitesBase       = publishersBase / path[String]("publisherId") / "sites"
  private lazy val approvalBase    = sitesBase / path[String]("siteId") / "approval"
}
