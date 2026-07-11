package promovolve.api

/** API DTOs - Clean data transfer objects for JSON serialization */
object ApiModels {

  // ----------------- Common -----------------

  case class ErrorResponse(
      code: String,
      message: String,
      details: Option[Map[String, String]] = None
  )

  case class Meta(
      total: Int,
      limit: Int,
      offset: Int
  )

  // ----------------- Advertiser -----------------

  case class Advertiser(
      id: String,
      name: String,
      status: String, // active, paused, suspended
      createdAt: String,
      updatedAt: String
  )

  case class AdvertiserList(
      data: Vector[Advertiser],
      meta: Meta
  )

  // ----------------- Budget Status -----------------

  case class BudgetStatus(
      dailyBudget: String,
      spendToday: String,
      remaining: String,
      withinBudget: Boolean
  )

  case class AdvertiserDetail(
      id: String,
      name: String,
      status: String,
      campaignIds: Vector[String],
      siteDomainBlocklist: Vector[String],
      budget: BudgetStatus,
      createdAt: String,
      updatedAt: String
  )

  // ----------------- Campaign -----------------

  case class CampaignBudget(
      daily: String, // "100.0000"
      lifetime: Option[String] = None
  )

  case class CampaignSchedule(
      startAt: String, // ISO 8601
      endAt: Option[String] = None
  )

  case class CampaignBidding(
      strategy: String, // "fixed" | "auto"
      maxCpm: String // "5.0000"
  )

  case class Campaign(
      id: String,
      advertiserId: String,
      name: String,
      status: String, // draft, active, paused, ended
      budget: CampaignBudget,
      schedule: CampaignSchedule,
      adProductCategory: String, // IAB Ad Product Taxonomy 2.0 ID (e.g., "1529" for Travel)
      bidding: CampaignBidding,
      landingUrl: String, // Landing URL for all creatives
      creativeIds: Vector[String],
      createdAt: String,
      updatedAt: String,
      // Budget state (inline to avoid per-campaign API calls)
      spent: Option[String] = None,
      remaining: Option[String] = None,
      // Opt in to bid on pages with no contextual match (filler
      // auction). Default false — campaigns must explicitly say yes.
      bidOnUnmatchedContext: Boolean = false,
      // Content categories this campaign targets (IAB 3.0 ids). Populated
      // either by explicit advertiser input on create or auto-derived
      // from creative LP via Gemini classification (see
      // CreativeProcessor.RefineCategoriesFromCreative). Surfaced so
      // the dashboard can warn when targeting is empty.
      targetCategories: Vector[String] = Vector.empty,
      // Derived: true when the campaign has no targeting AND won't
      // accept filler traffic — i.e., the auction will never deliver
      // it a bid request. Almost always indicates a silent failure
      // in the categorization pipeline (Gemini returned no usable
      // suggestedContentCategories) that the advertiser needs to fix.
      untargeted: Boolean = false,
      // Media targeting: publisher siteIds this campaign is restricted to.
      // Empty = no restriction (bids everywhere, contextual default).
      siteAllowlist: Vector[String] = Vector.empty,
      // Human-readable taxonomy names resolved server-side so the
      // dashboard/edit form never shows raw IAB numbers. *Name fields
      // are display-only; the ids above are the source of truth.
      // targetCategoryNames is index-aligned with targetCategories.
      adProductCategoryName: Option[String] = None,
      targetCategoryNames: Vector[String] = Vector.empty,
      // Durable Gemini-derived default categories (IAB 3.0 ids) — the
      // fallback `effectiveCategories` uses when explicit targetCategories
      // is empty. Surfaced so the edit form can show what an "empty" field
      // actually targets (restoring these chips) instead of rendering blank.
      // suggestedCategoryNames is index-aligned with suggestedCategories.
      suggestedCategories: Vector[String] = Vector.empty,
      suggestedCategoryNames: Vector[String] = Vector.empty
  )

  case class CampaignList(
      data: Vector[Campaign],
      meta: Meta
  )

  case class CreateCampaignRequest(
      name: String,
      budget: CampaignBudget,
      schedule: CampaignSchedule,
      adProductCategory: String, // IAB Ad Product Taxonomy 2.0 ID — what the ad sells
      bidding: CampaignBidding,
      landingUrl: String, // Landing URL for all creatives in this campaign
      // Advertiser-declared target content categories (IAB Content Taxonomy 3.0
      // ids). Drives the auction match: a campaign bids only on pages whose
      // detected category is in this set (or shares a 3.0 ancestor with it).
      // Empty = no contextual targeting; combine with bidOnUnmatchedContext for
      // run-of-network. Previously derived from adProductCategory via a static
      // bridge — now declared explicitly.
      targetCategories: Option[Seq[String]] = None,
      // Media targeting: publisher siteIds to restrict bidding to. None/empty
      // = no restriction (bid everywhere). An additive filter on top of
      // category matching — does not bypass contextual matching.
      siteAllowlist: Option[Seq[String]] = None
  )

  case class UpdateCampaignRequest(
      name: Option[String] = None,
      status: Option[String] = None,
      budget: Option[CampaignBudget] = None,
      adProductCategory: Option[String] = None, // IAB Ad Product Taxonomy 2.0 ID
      bidding: Option[CampaignBidding] = None,
      landingUrl: Option[String] = None, // Landing URL for all creatives
      bidOnUnmatchedContext: Option[Boolean] = None, // Opt in/out of filler auction (no contextual match)
      // Schedule. None = no change. To clear endAt explicitly, pass
      // CampaignSchedule with endAt = None alongside the desired startAt.
      schedule: Option[CampaignSchedule] = None,
      // Target content categories (IAB 3.0). None = no change, Some(empty) =
      // clear, Some(set) = replace. See CreateCampaignRequest.targetCategories.
      targetCategories: Option[Seq[String]] = None,
      // Media targeting: publisher siteIds. None = no change, Some(empty) =
      // clear (no restriction), Some(set) = replace.
      siteAllowlist: Option[Seq[String]] = None
  )

  case class CampaignBudgetStatus(
      dailyBudget: String,
      spent: String,
      remaining: String
  )

  // ----------------- Creative -----------------

  case class CreativeAsset(
      `type`: String, // "image" | "html" | "video"
      url: String
  )

  case class CreativeContent(
      headline: Option[String] = None,
      body: Option[String] = None,
      cta: Option[String] = None,
      landingUrl: String,
      landingDomain: String
  )

  case class Creative(
      id: String,
      campaignId: String,
      name: String,
      status: String, // pending, verified (verification status)
      activeStatus: String = "Active", // Active, Paused (serving status)
      asset: CreativeAsset,
      content: CreativeContent,
      cpm: String,
      createdAt: String,
      updatedAt: String,
      matchConfidence: Option[Double] = None, // 0.0-1.0 category match score
      verificationReason: Option[String] = None // Gemini's explanation
  )

  case class CreativeList(
      data: Vector[Creative],
      meta: Meta
  )

  // ----------------- Publisher -----------------

  case class Publisher(
      id: String,
      status: String, // active, suspended, closed
      siteIds: Vector[String],
      domainBlocklist: Vector[String],
      createdAt: String,
      updatedAt: String
  )

  case class PublisherList(
      data: Vector[Publisher],
      meta: Meta
  )

  case class BlockDomainsRequest(
      domains: Vector[String]
  )

  case class UnblockDomainsRequest(
      domains: Vector[String]
  )

  // ----------------- Site -----------------

  case class SiteCrawlConfig(
      seedUrl: String,
      cronSchedule: String,
      maxDepth: Int,
      concurrency: Int,
      hostRegex: String,
      targetElements: Vector[String]
  )

  case class SiteSlotConfig(
      slotId: String,
      width: Int,
      height: Int,
      floorOverride: Option[String] = None, // admin escape hatch — overrides RL + prior
      // Crawler-derived prior (read-only, surfaced for UI explainability)
      priorQualityScore: Option[Double] = None,
      priorRegion: Option[String] = None,
      priorAboveFold: Option[Boolean] = None,
      // Human-readable top IAB content category for the page(s) where this
      // slot was discovered; "Filler" when the page matched no demand
      // category, None when the page is not yet classified.
      matchedCategory: Option[String] = None
  )

  case class Site(
      id: String,
      publisherId: String,
      domain: String,
      status: String, // active, paused
      crawlConfig: SiteCrawlConfig,
      slots: Vector[SiteSlotConfig],
      taxonomyIds: Vector[String],
      createdAt: String,
      updatedAt: String,
      verificationStatus: String, // unverified, verified
      floorCpm: String = "0.50", // current floor CPM (auto-optimized by RL)
      minFloorCpm: String = "0.10", // publisher-set minimum floor
      bidWeight: String = "0.50", // scoring exponent: discovery=0.3, balanced=0.5, revenue=0.7
      // Whether pages with no contextual match route to the filler
      // auction. Default true; flip to false to silence filler
      // creatives for this site without needing to reject them
      // one-by-one in the approval queue.
      acceptsFillerTraffic: Boolean = true
  )

  // ---------- Site Verification ----------

  case class VerificationTokenResponse(
      token: String,
      domain: String,
      fileUrl: String,
      fileContent: String,
      // DNS TXT alternative — for hosts that can't serve the .well-known file.
      dnsRecordName: String,
      dnsRecordValue: String
  )

  case class VerificationResponse(
      verified: Boolean,
      host: Option[String] = None,
      reason: Option[String] = None
  )

  case class SiteList(
      data: Vector[Site],
      meta: Meta
  )

  case class CreateSiteRequest(
      id: String, // Publisher chooses site ID
      domain: String,
      crawlConfig: SiteCrawlConfig,
      slots: Vector[SiteSlotConfig] = Vector.empty,
      taxonomyIds: Vector[String] = Vector.empty,
      minFloorCpm: String // Required: publisher must set minimum floor on site creation
  )

  /**
   * Body for the per-slot admin floor override endpoint. floorCpm=None
   * clears the override; otherwise sets it (string parses as decimal).
   */
  case class UpdateSlotFloorRequest(floorCpm: Option[String] = None)

  /**
   * One row in the floor-RL decision log. ISO timestamps + floors as
   * strings to keep the JSON inspectable.
   */
  case class FloorObservationResponse(
      ts: String,
      hour: Int,
      trafficShape: Double,
      floorBefore: String,
      floorAfter: String,
      epsilon: Double,
      observed: Boolean,
      trainingLoss: Option[Double],
      slotOverrideCount: Int
  )

  case class RecentFloorObservationsResponse(
      siteId: String,
      observations: Vector[FloorObservationResponse]
  )

  /**
   * One row of the sweep optimizer's evidence table: a candidate floor
   * and the realized revenue + impression count measured while that floor
   * was held. The dashboard renders this so publishers can see exactly
   * which floors the optimizer tried and how each one performed.
   */
  case class FloorSweepCandidateResponse(
      floor: String, // dollars, formatted like other money fields
      revenue: String, // dollars
      impressions: Long
  )

  /** One persisted decision row from the floor_decisions table. */
  case class FloorDecisionRow(
      ts: String, // ISO timestamp
      argmaxFloor: String, // dollars, %.4f
      prevArgmax: Option[String],
      cycleRevenue: Option[String],
      cycleImps: Option[Long],
      candidates: Option[String], // raw JSON blob (frontend can parse if needed)
      category: Option[String] // None = site-wide sweep; Some = per-category
  )

  /**
   * One demand category's live floor + demand context for the publisher's
   * Floor-by-category table. `bidders`/`topBid` come from the site's
   * ephemeral auction reports: 0 bidders = no auction has observed demand
   * in this category since the last restart (historical row).
   */
  /**
   * Live per-category sweep-optimizer state (raw counters; the dashboard
   * renders language + wall-clock estimates from them).
   */
  case class CategorySweep(
      phase: String, // "init" | "sweep" | "exploit"
      cursor: Int,
      candidateCount: Int,
      ticksThisCandidate: Int,
      ticksPerCandidate: Int,
      exploitTicksRemaining: Int,
      currentFloor: Double
  )

  case class CategoryDemand(
      categoryId: String,
      categoryName: String,
      floor: Option[Double],
      bidders: Int,
      topBid: Double,
      sweep: Option[CategorySweep]
  )

  case class CategoryDemandResponse(
      categories: Vector[CategoryDemand],
      observationIntervalSeconds: Int
  )

  case class FloorSweepHistoryResponse(
      siteId: String,
      decisions: Vector[FloorDecisionRow] // newest-first
  )

  /**
   * Per-site revenue summary sourced from tracking_events (the durable
   * read side). Used by the publisher dashboard's Revenue tile so it
   * stays aligned with the advertiser-side spend numbers — both pages
   * read from the same time-series projection, with the same UTC-midnight
   * "today" boundary.
   */
  case class SiteRevenueTodayResponse(
      siteId: String,
      sinceUtc: String, // ISO timestamp of today's UTC midnight
      revenue: String, // dollars, %.4f
      impressions: Long,
      eCpm: String // dollars per 1k imps, %.4f
  )

  /**
   * Per-advertiser spend summary, mirror of SiteRevenueTodayResponse.
   * Used by the advertiser dashboard's Today's Spend tile so it matches
   * the publisher's Revenue Today. Both read tracking_events with the
   * same UTC-midnight boundary.
   */
  case class AdvertiserSpendTodayResponse(
      advertiserId: String,
      sinceUtc: String,
      spend: String,
      impressions: Long,
      eCpm: String
  )

  /**
   * One billable metering cell for the platform's settlement job
   * (docs/design/BILLING.md): everything the ledger needs to charge the
   * advertiser and credit the publisher for one UTC day on one site.
   * Dog-eared impressions are excluded — they never debit campaign
   * budget, so they are not billable.
   */
  case class MeteringDailyRow(
      advertiserId: String,
      campaignId: String,
      siteId: String,
      publisherId: String, // empty when the site has no publisher_sites row
      impressions: Long,
      gross: String // dollars, %.6f — micro-dollar precision
  )

  case class MeteringDailyResponse(
      date: String, // the aggregated UTC day, YYYY-MM-DD
      rows: Vector[MeteringDailyRow]
  )

  /**
   * Per-advertiser unsettled gross since a date — the settlement job's
   * intraday wallet check. Spend that exists in tracking_events but has
   * not been booked to the ledger yet, so the platform can suspend an
   * advertiser BEFORE the daily settlement would drive their wallet
   * negative (bounds the prepaid overdraft to hours, not a day).
   */
  case class MeteringIntradayRow(
      advertiserId: String,
      gross: String // dollars, %.6f
  )

  case class MeteringIntradayResponse(
      since: String, // start of the unsettled window, YYYY-MM-DD
      rows: Vector[MeteringIntradayRow]
  )

  /**
   * Sweep optimizer snapshot exposed to the dashboard. All optional
   * fields are None/empty only in the brief window before the entity
   * has finished recovery and installed its optimizer.
   */
  case class FloorSweepEvidenceResponse(
      siteId: String,
      phase: Option[String] = None, // "init" / "sweep" / "exploit"
      currentFloor: Option[String] = None,
      bestFloor: Option[String] = None,
      cursor: Option[Int] = None,
      ticksThisCandidate: Option[Int] = None,
      exploitTicksRemaining: Option[Int] = None,
      candidates: Vector[FloorSweepCandidateResponse] = Vector.empty,
      // Per-candidate results from the last-completed cycle. Empty
      // until the optimizer has run at least one full sweep + the
      // current cycle has begun. Dashboard uses these for
      // this-cycle-vs-last-cycle revenue deltas.
      previousBestFloor: Option[String] = None,
      previousCandidates: Vector[FloorSweepCandidateResponse] = Vector.empty
  )

  case class UpdateSiteRequest(
      domain: Option[String] = None,
      status: Option[String] = None,
      crawlConfig: Option[SiteCrawlConfig] = None,
      slots: Option[Vector[SiteSlotConfig]] = None,
      taxonomyIds: Option[Vector[String]] = None,
      floorCpm: Option[String] = None, // override current floor (normally auto-managed)
      minFloorCpm: Option[String] = None, // publisher minimum floor (RL cannot go below this)
      bidWeight: Option[String] = None, // scoring exponent: discovery=0.3, balanced=0.5, revenue=0.7
      acceptsFillerTraffic: Option[Boolean] = None // opt in/out of filler auction for this site
  )

  // ----------------- Pacing Config -----------------

  case class PacingConfig(
      windowSeconds: Int, // Sliding window for rate calculation (default 60s)
      testThrottleOverride: Option[Double] = None, // For testing: fixed throttle probability (0.0-1.0)
      dayDurationSeconds: Int = 86400, // Simulated day length (default: 24h = 86400s)
      warmupMode: Boolean = false // When true, record traffic patterns but don't serve ads
  )

  case class UpdatePacingConfigRequest(
      windowSeconds: Option[Int] = None,
      testThrottleOverride: Option[Double] = None, // Set to force fixed throttle (0.0-1.0), null to disable
      dayDurationSeconds: Option[Int] = None, // Simulated day length in seconds
      warmupMode: Option[Boolean] = None // When true, record traffic patterns but don't serve ads
  )

  // ----------------- Taxonomy -----------------

  case class TaxonomyCategory(
      id: String,
      name: String, // Category name
      fullPath: String = "" // Full path: "Child -> Parent -> Grandparent"
  )

  case class TaxonomyCategoryList(
      data: Vector[TaxonomyCategory],
      meta: Meta
  )

  // A registered (verified) publisher site, for campaign media targeting.
  // siteId is the canonical identity (the ad unit's data-pub); domain is shown
  // to the advertiser. Sourced from the verified-host DData map.
  case class VerifiedSite(
      siteId: String,
      domain: String
  )

  case class VerifiedSiteList(
      data: Vector[VerifiedSite],
      meta: Meta
  )

  // Distinct advertiser landing-page (LP) domains, for the publisher's
  // advertiser-domain block picker. Sourced from the advertiser-domain DData
  // map (each campaign publishes its LP domain).
  case class AdvertiserDomainList(
      data: Vector[String],
      meta: Meta
  )

  // ----------------- Additional Advertiser Operations -----------------

  case class UpdateBudgetRequest(
      dailyBudget: String // "1000.0000"
  )

  case class ServedSite(
      siteId: String,
      domain: String,
      impressions: Long
  )

  case class ServedSitesResponse(
      data: Vector[ServedSite]
  )

  // ----------------- LP Creative Extraction -----------------

  case class ExtractFromLPRequest(
      url: String
  )

  // LP Analysis (structural section extraction)
  case class AnalyzeLPRequest(
      url: String,
      strategy: Option[String] = None,
      // Bypass the (url, strategy) result cache. The editor sets this on
      // MANUAL Analyze clicks — "re-read my LP, I just changed it" must
      // always work — while the auto-start on editor open stays cached.
      force: Option[Boolean] = None
  )

  case class AnalyzeLPImage(
      src: String,
      width: Int,
      height: Int,
      alt: String
  )

  case class AnalyzeLPSection(
      heading: String,
      text: String,
      images: Vector[AnalyzeLPImage]
  )

  case class AnalyzeLPResponse(
      url: String,
      sections: Vector[AnalyzeLPSection],
      // Dominant background colour of the LP. Forwarded from
      // LPAnalyzer.extractDominantColor and applied verbatim to all
      // creative pages — the creative must match the LP's colour
      // scheme. None when the LP returned no resolvable colour.
      dominantColor: Option[String] = None,
      // Dominant body text colour of the LP. Applied to creative text
      // so typography reads as a continuation of the LP.
      textColor: Option[String] = None,
      // Brand palette of the LP — up to ~6 #rrggbb swatches ordered by
      // prominence. Seeds the creative brand kit in the editor.
      palette: Vector[String] = Vector.empty,
      // Font faces used by the LP (heading family first, then body).
      // Seeds the brand-kit font list.
      fonts: Vector[String] = Vector.empty,
      // The LP's OWN font files, quarantined in R2 at analysis time
      // (fonts/orig/<hash>.woff2 — no serving URL derives from that key).
      // Offered to the advertiser in the wizard; only an explicit license
      // opt-in at publish copies them to the live catalog key.
      originalFonts: Vector[OriginalFontOffer] = Vector.empty
  )

  /** One quarantined LP font face on offer: family + weight + parked hash. */
  case class OriginalFontOffer(family: String, weight: Int, hash: String)

  // Async LP analysis: start returns a jobId; the client polls status until
  // done. Avoids a long synchronous request blocking the Designer transition.
  case class AnalyzeLPJob(jobId: String)
  case class AnalyzeLPStatusResponse(
      status: String, // pending | done | failed | not_found
      result: Option[AnalyzeLPResponse] = None,
      error: Option[String] = None,
      // True once the early viewport preview has been captured (can be set
      // while status is still "pending"); the client fetches it from the
      // analyze-lp/screenshot endpoint to show as the loading background.
      screenshotReady: Boolean = false
  )

  // Rewrite sections into creative copy
  case class RewriteSectionInput(
      heading: String,
      text: String
  )

  case class RewriteSectionsRequest(
      sections: Vector[RewriteSectionInput],
      // Alternative generation path (A/B): when true, distill the WHOLE
      // landing page into a 3-page booklet (synthesizeSections) instead of
      // one rewritten page per section. `arc` selects the persuasion arc
      // (CreativeArc id) that assigns each page its role; defaults to the
      // Hook→Proof→Call arc. Defaults preserve the per-section behavior.
      synthesize: Option[Boolean] = None,
      arc: Option[String] = None,
      // Optional advertiser free-text brief woven into the synthesis prompt
      // (tone, angle, or facts about their own product).
      brief: Option[String] = None,
      // When true, ONLY curate the arc menu (recommendArcs) and return it with
      // no pages — used on load so the picker is ready before the user clicks
      // Generate (no copy is produced, so the brief is never wasted).
      recommend: Option[Boolean] = None
  )

  case class RewriteSectionsResponse(
      pages: Vector[ExtractedBannerPage],
      // The arc actually used. On a no-arc (auto) request these carry the
      // AI-curated menu: `arcOptions` = arc ids that fit this product (the
      // picker shows only these), `arcReason` = one line on why `arc` fits.
      arc: Option[String] = None,
      arcOptions: Option[Vector[String]] = None,
      arcReason: Option[String] = None
  )

  case class ExtractedBannerPage(
      tag: String,
      headline: String,
      sub: String,
      body: String,
      accent: String,
      bg: String,
      imgEmoji: String,
      caption: String,
      img: Option[String] = None, // CDN URL of extracted/uploaded image
      // Editor-authored fields (opaque JSON pass-through to the web component).
      layout: Option[spray.json.JsValue] = None, // percent-positioned items for the expanded view
      banners: Option[spray.json.JsValue] = None, // per-banner-size layout map
      designAspect: Option[String] = None, // e.g. "16/9"
      // Full-bleed per-page video background. Opaque pass-through: the
      // Scala layer never reads the shape; it just round-trips so the
      // banner web component can render the <video>. Without this field
      // spray-json's typed decoder silently strips it on save.
      videoBg: Option[spray.json.JsValue] = None,
      // Full-bleed per-page image texture background. Same opaque
      // pass-through as videoBg — without this field the typed decoder
      // strips it on save and the texture never reaches the renderer.
      textureBg: Option[spray.json.JsValue] = None
  )

  case class ExtractFromLPResponse(
      pages: Vector[ExtractedBannerPage],
      sourceUrl: String
  )

  /**
   * Sub-rectangle of an image's natural pixels to show, in percent of
   * the source. Matches the designer's CropRect shape; renderer wraps
   * the <img> in a clipping div and offsets the inner image so only
   * this sub-rectangle fills the item's bounding box.
   */
  case class CropRect(
      x: Double, // 0-100, top-left of the crop within the source
      y: Double,
      w: Double, // 0-100, crop dimensions
      h: Double
  )

  /**
   * Single item in a layout descriptor — percent-positioned with optional
   * type-specific fields. Matches the schema the web component consumes.
   */
  case class LayoutItem(
      `type`: String, // text | image | rect | circle
      left: Double, // 0-100
      top: Double, // 0-100
      width: Option[Double] = None, // 0-100 (not for circle)
      height: Option[Double] = None, // 0-100 (not for circle)
      radius: Option[Double] = None, // 0-100 of min dim (circle only)
      fontSize: Option[Double] = None, // 0-100 of height (text only, maps to cqh)
      fontFamily: Option[String] = None,
      fontWeight: Option[String] = None,
      textAlign: Option[String] = None,
      color: Option[String] = None,
      fill: Option[String] = None,
      stroke: Option[String] = None,
      rotation: Option[Double] = None,
      text: Option[String] = None,
      // Instead of hardcoding the text string, reference a field on
      // the page (e.g. "headline" / "sub" / "body" / "tag"). The
      // renderer resolves page[field] at delivery so the copy can't
      // drift from rewriteSections' output.
      field: Option[String] = None,
      src: Option[String] = None,
      entrance: Option[String] = None, // fade-up | fade-in | slide-left | slide-right | scale | none
      // Legacy per-item CTA-target marker. Delivery no longer hotspots
      // individual items — the whole expanded ad is the link (opens the
      // banner's landing-url). Survives only as a styling hint for the
      // designer (see banner.ts).
      ctaTarget: Option[Boolean] = None,
      // Image-only: sub-rectangle of the source to show. Set by the
      // designer's crop modal; consumed by both render paths
      // (collapsed + expanded). Without this, spray-json drops the
      // crop on persist and the published banner ignores it.
      crop: Option[CropRect] = None
  )

  case class GenerateLayoutRequest(
      page: ExtractedBannerPage,
      aspect: String, // "16/9" or "WxH" for banner sizes (e.g. "300/250")
      mode: String, // "expanded" | "banner"
      // Optional pre-generation context from the LP-to-Creative first
      // step. Both nudge the Gemini prompt — empty/None means "no
      // pre-set choice, let Gemini pick freely". Mirror the
      // window.__DESIGNER__ shape: brandKitJson is the serialized
      // {name, colors[], fonts[]} object, templateId is one of the
      // catalog ids served by /v1/layout-templates.
      brandKitJson: Option[String] = None,
      templateId: Option[String] = None
  )

  case class GenerateLayoutResponse(
      layout: Vector[LayoutItem]
  )

  case class GenerateLayoutPairRequest(
      page: ExtractedBannerPage
  )

  case class GenerateLayoutPairResponse(
      pc: Vector[LayoutItem],
      mobile: Vector[LayoutItem]
  )

  // Rewrite the page's tag/headline/sub/body to a fresh phrasing —
  // user-initiated alongside Regenerate. lpContext is the original LP
  // text snapshot the page was extracted from; passing it anchors
  // Gemini so the rewrite stays faithful to the source instead of
  // hallucinating claims the LP doesn't support. May be empty when
  // the creative was authored without LP extraction (direct upload).
  case class RewriteCopyRequest(
      page: ExtractedBannerPage,
      lpContext: String = ""
  )

  case class RewriteCopyResponse(
      tag: String,
      headline: String,
      sub: String,
      body: String
  )

  // Advertiser asset library (owned by advertiser, shared binary via image_asset)
  case class AdvertiserAssetView(
      id: String,
      filename: String,
      cdnUrl: String,
      mime: String,
      width: Int,
      height: Int,
      createdAt: String
  )

  case class AdvertiserAssetListResponse(
      assets: Vector[AdvertiserAssetView]
  )

  case class UploadAssetRequest(
      filename: String,
      mimeType: String,
      imageBase64: String,
      width: Option[Int] = None,
      height: Option[Int] = None
  )

  case class UploadAssetResponse(
      asset: AdvertiserAssetView
  )

  // ─── Presigned (browser-direct) upload flow ─────────────────────
  //
  // Two-step replacement for the byte-shipping UploadAssetRequest path:
  //   1. Browser hashes file → POST /presigned-upload → server returns
  //      (uploadUrl, s3Key, alreadyExists).
  //   2. If !alreadyExists, browser PUTs bytes directly to uploadUrl
  //      (R2 receives them, no dashboard/core hop).
  //   3. Browser POST /register → server inserts the AdvertiserAsset
  //      row (and image_asset row if the hash is new).

  case class PresignedUploadRequest(
      filename: String,
      mimeType: String,
      hash: String, // SHA-256 hex of the file bytes, computed by the browser
      sizeBytes: Long // for future quota / sanity checks
  )

  case class PresignedUploadResponse(
      uploadUrl: String, // presigned PUT URL with TTL — empty when alreadyExists
      s3Key: String, // assets/{hash}.{ext}
      alreadyExists: Boolean // existing image_asset row found; skip the PUT
  )

  case class RegisterAssetRequest(
      filename: String,
      mimeType: String,
      hash: String,
      width: Option[Int] = None,
      height: Option[Int] = None
  )

  case class RegisterAssetResponse(
      asset: AdvertiserAssetView
  )

  case class ImportAssetUrlsRequest(
      urls: Vector[ImportAssetUrl]
  )

  case class ImportAssetUrl(
      url: String,
      filename: Option[String] = None
  )

  /**
   * One entry in an import-urls response. `sourceUrl` echoes back
   * the input URL so callers can build a map from external URL to
   * R2 cdnUrl (used by the Go shell to rewrite pagesJSON image
   * references before handing them to the designer). `asset` is
   * None when that single URL failed (404, network, decode error)
   * — caller leaves the original URL in place. Order matches input.
   */
  case class ImportAssetUrlResult(
      sourceUrl: String,
      asset: Option[AdvertiserAssetView]
  )

  case class ImportAssetUrlsResponse(
      results: Vector[ImportAssetUrlResult]
  )

  // ----------------- Additional Campaign Operations -----------------

  case class UpdateCampaignStatusRequest(
      status: String // "active" | "paused"
  )

  case class AssignCreativesRequest(
      creativeIds: Vector[String]
  )

  case class CampaignBudgetInfo(
      campaignId: String,
      dailyBudget: String,
      spent: String,
      remaining: String
  )

  case class CampaignWinRateInfo(
      campaignId: String,
      bidsToday: Long,
      impressionsToday: Long,
      winRate: Double // impressions / bids (0.0 if no bids)
  )

  /**
   * Batch form of CampaignWinRateInfo: one row per live campaign of the
   * advertiser. The dashboard previously called the single-campaign
   * endpoint once per campaign (N+1 round-trips before the campaigns
   * page rendered); this answers them all with one entity fan-out and
   * one projection query.
   */
  case class AdvertiserWinRatesResponse(
      advertiserId: String,
      totalImpressionsToday: Long, // global denominator (impression share)
      rates: Vector[CampaignWinRateInfo]
  )

  /**
   * Per-campaign slice of today's spend from tracking_events (same UTC
   * midnight boundary + source as AdvertiserSpendTodayResponse), so the
   * campaign rows reconcile with the account-level Today's Spend tile.
   * Campaigns with no impressions today have no row.
   */
  case class CampaignSpendTodayRow(
      campaignId: String,
      spend: String, // dollars, %.4f
      impressions: Long
  )
  case class AdvertiserCampaignSpendTodayResponse(
      advertiserId: String,
      sinceUtc: String,
      campaigns: Vector[CampaignSpendTodayRow]
  )

  /**
   * One UTC hour bucket of today's delivery for the stats page's
   * hourly chart. Only hours with impressions are returned.
   */
  case class HourlyDeliveryRow(
      hour: Int, // 0-23, UTC
      impressions: Long,
      spend: String // dollars, %.4f
  )
  case class AdvertiserHourlyTodayResponse(
      advertiserId: String,
      sinceUtc: String,
      hours: Vector[HourlyDeliveryRow]
  )

  /**
   * One (UTC day, campaign) row of the advertiser report, from
   * campaign_daily_stats. Days without delivery have no row.
   */
  case class ReportDailyRow(
      day: String, // YYYY-MM-DD, UTC bucket
      campaignId: String,
      impressions: Long,
      clicks: Long,
      ctaClicks: Long,
      spend: String, // dollars, %.4f
      dogearedImpressions: Long
  )
  case class AdvertiserReportResponse(
      advertiserId: String,
      from: String, // YYYY-MM-DD, UTC, inclusive
      to: String,
      rows: Vector[ReportDailyRow]
  )

  /**
   * One dimension-value row of the report breakdown, aggregated over the
   * range from campaign_dim_daily_stats. key semantics per dim:
   * site → site_id, category → category ('' = uncategorized),
   * publisher → publisher_id ('' = site with no publisher_sites row).
   * label = display name where the projection has one (site host); ''
   * otherwise — the platform falls back to the key.
   */
  case class ReportBreakdownRow(
      key: String,
      label: String,
      impressions: Long,
      clicks: Long,
      ctaClicks: Long,
      spend: String, // dollars, %.4f
      dogearedImpressions: Long
  )
  case class AdvertiserReportBreakdownResponse(
      advertiserId: String,
      from: String,
      to: String,
      dim: String, // site | category | publisher
      rows: Vector[ReportBreakdownRow],
      coverageFrom: String // earliest rollup day for this advertiser; '' = none
  )

  /**
   * One (dimension value, campaign) row — the range breakdown split by
   * campaign, for the nested site→campaign / category→campaign tables.
   */
  case class ReportBreakdownByCampaignRow(
      key: String,
      label: String,
      campaignId: String,
      impressions: Long,
      clicks: Long,
      ctaClicks: Long,
      spend: String, // dollars, %.4f
      dogearedImpressions: Long
  )
  case class AdvertiserReportBreakdownByCampaignResponse(
      advertiserId: String,
      from: String,
      to: String,
      dim: String, // site | category
      rows: Vector[ReportBreakdownByCampaignRow]
  )

  /**
   * One (UTC day, dimension value) row — the day-level variant of
   * ReportBreakdownRow, for time-series charts.
   */
  case class ReportBreakdownDailyRow(
      day: String, // YYYY-MM-DD, UTC
      key: String,
      label: String,
      impressions: Long,
      clicks: Long,
      ctaClicks: Long,
      spend: String, // dollars, %.4f
      dogearedImpressions: Long
  )
  case class AdvertiserReportBreakdownDailyResponse(
      advertiserId: String,
      from: String,
      to: String,
      dim: String, // site | category | publisher
      rows: Vector[ReportBreakdownDailyRow]
  )

  /**
   * One (UTC day, site, category) row — the day-level variant of
   * PublisherSiteCategoryRow, for time-series charts.
   */
  case class PublisherSiteCategoryDailyRow(
      day: String, // YYYY-MM-DD, UTC
      siteId: String,
      host: String,
      category: String,
      impressions: Long,
      clicks: Long,
      ctaClicks: Long,
      grossRevenue: String, // dollars, %.4f
      dogearedImpressions: Long
  )
  case class PublisherSiteCategoryDailyReportResponse(
      publisherId: String,
      from: String,
      to: String,
      rows: Vector[PublisherSiteCategoryDailyRow]
  )

  /**
   * One (site, category) row of the publisher report. grossRevenue is
   * advertiser spend before platform margin, dollars %.4f — the platform
   * nets it down for display. category '' = uncategorized.
   */
  case class PublisherSiteCategoryRow(
      siteId: String,
      host: String, // '' when publisher_sites has no healed host
      category: String, // IAB taxonomy id; '' = uncategorized
      impressions: Long,
      clicks: Long,
      ctaClicks: Long,
      grossRevenue: String, // dollars, %.4f
      dogearedImpressions: Long
  )
  case class PublisherSiteCategoryReportResponse(
      publisherId: String,
      from: String,
      to: String,
      rows: Vector[PublisherSiteCategoryRow],
      coverageFrom: String // earliest rollup day for this publisher; '' = none
  )

  case class ReplenishBudgetRequest(
      newBudget: String // "100.0000"
  )

  case class ReplenishBudgetResponse(
      campaignId: String,
      newBudget: String,
      message: String
  )

  // ----------------- Approval Queue (Publisher) -----------------

  case class PendingCreative(
      url: String,
      slotId: String,
      creativeId: String,
      cpm: String,
      category: String,
      assetUrl: Option[String] = None,
      width: Option[Int] = None,
      height: Option[Int] = None,
      adProductCategory: Option[String] = None,
      campaignId: Option[String] = None,
      advertiserId: Option[String] = None,
      landingDomain: Option[String] = None
  )

  case class PendingCreativeList(
      data: Vector[PendingCreative],
      meta: Meta
  )

  /** A placement where a creative won an auction */
  case class PendingPlacement(
      url: String,
      slotId: String,
      cpm: String,
      // Per-placement IAB content category. The approval UI shows
      // "Your pages were classified as …" — since the same creative
      // can win on pages with different classifications, we need the
      // category per placement, not just one representative.
      category: Option[String] = None,
      categoryName: Option[String] = None,
      // True when this placement came from a filler auction — the
      // page had no honest match against the demand pool, so the
      // creative is serving because the campaign opted in to
      // unmatched-context traffic, not because of topic affinity.
      filler: Boolean = false
  )

  /** A creative grouped with all its placements — used for the Creative Inbox UI */
  case class PendingCreativeGroup(
      creativeId: String,
      campaignId: Option[String],
      advertiserId: Option[String],
      cpm: String,
      category: String,
      assetUrl: Option[String] = None,
      adProductCategory: Option[String] = None,
      landingDomain: Option[String] = None,
      placements: Vector[PendingPlacement],
      // Full expandable-banner pagesJson, opaque to the API layer.
      // Lets the publisher approval UI render the live
      // <expandable-magazine-banner> (click-to-expand magazine view)
      // instead of just the static PNG thumbnail in assetUrl.
      pagesJson: Option[String] = None,
      // Human-readable IAB taxonomy names for the "why was this ad
      // matched" explainer in the approval UI. None when the id
      // isn't known to the local taxonomy.
      categoryName: Option[String] = None,
      adProductCategoryName: Option[String] = None,
      // True when at least one placement came from a filler auction
      // (no contextual match). The UI uses this to render a distinct
      // badge so the publisher knows the match reason is "advertiser
      // opted in to unmatched traffic", not topic alignment.
      filler: Boolean = false,
      // ISO-8601 creation time of the underlying creative (from the
      // CreativeRepo). The Creative Inbox orders by this DESC (newest
      // first) and auto-selects the most recent. "" when the repo has
      // no record yet — those sort last.
      createdAt: String = "",
      // Banner-level config (expandAnimation, duration, reading dir, …),
      // the same blob the serve path passes to the banner as the `config`
      // attribute. The approval preview needs it so the creative's chosen
      // expand effect (e.g. CRT power-on) plays — without it the preview
      // falls back to the default fade. None → preview uses banner defaults.
      bannerConfigJson: Option[String] = None,
      // Full landing-page URL the creative's CTA points to (from the
      // CreativeRepo). The approval preview wires this onto the banner as
      // `landing-url` so the publisher can click the CTA and open the
      // actual destination LP in a new tab. None → CTA stays inert.
      landingUrl: Option[String] = None,
      // Epoch millis when the creative FIRST entered this publisher's
      // approval queue (min across placements). Unlike createdAt this
      // survives the pending row's TTL purge / re-auction churn, so it is
      // the honest "waiting since" the inbox should display. None when
      // tracking predates the feature.
      firstSeenAt: Option[Long] = None,
      // Re-auction waves survived unreviewed since first seen (max across
      // placements; debounced per wave, not per placement upsert).
      requeueCount: Option[Int] = None
  )

  case class PendingCreativeGroupList(
      data: Vector[PendingCreativeGroup],
      meta: Meta
  )

  /**
   * A delivered placement for a currently-serving creative — backed by
   * `tracking_events` impressions, not the in-memory ServeIndex (which
   * is slot-keyed and carries no URL).
   */
  case class ServingPlacement(
      url: String,
      slotId: String,
      impressions: Long,
      // Per-placement IAB category captured at impression time; lets the
      // UI label what kind of page each delivery was on. None when the
      // event row had no category recorded.
      category: Option[String] = None,
      categoryName: Option[String] = None
  )

  /**
   * A currently-serving creative grouped with the (url, slot) places
   * it has actually been delivered to over the lookback window.
   */
  case class ServingCreativeGroup(
      creativeId: String,
      campaignId: Option[String],
      advertiserId: Option[String],
      cpm: String,
      assetUrl: Option[String] = None,
      width: Option[Int] = None,
      height: Option[Int] = None,
      // True when the creative's max CPM is below the current site
      // floor — eligible only via dog-ear pins, not normal auctions.
      // Same flag the existing Serving tab badges as "Pin-only".
      belowFloor: Boolean = false,
      floorCpm: String = "0.00",
      placements: Vector[ServingPlacement] = Vector.empty,
      // Raw banner pagesJson + config so the unified approval inbox can
      // render the live <magazine-preview> for serving creatives too
      // (same as pending) — publisher sees how the ad looks post-approval.
      pagesJson: Option[String] = None,
      bannerConfigJson: Option[String] = None,
      // Landing domain (used as the inbox row name, same as pending).
      landingDomain: Option[String] = None,
      // Full landing-page URL for the CTA → opens the LP in a new tab from
      // the approval preview (same as pending).
      landingUrl: Option[String] = None
  )

  case class ServingCreativeGroupList(
      data: Vector[ServingCreativeGroup],
      meta: Meta
  )

  case class ApproveCreativeRequest(
      url: String,
      slot: String,
      creativeId: String
  )

  case class ApproveCreativeResponse(
      url: String,
      slot: String,
      assetUrl: String
  )

  case class RejectCreativeRequest(
      url: String,
      slot: String,
      creativeId: String,
      reason: Option[String] = None
  )

  case class RejectCreativeResponse(
      url: String,
      slot: String,
      creativeId: String,
      status: String
  )

  // ----------------- Flagging / Quarantine -----------------

  /** Request to flag (quarantine) a creative */
  case class FlagCreativeRequest(
      url: String,
      slot: String,
      creativeId: String,
      reason: String
  )

  /** Response from flagging a creative */
  case class FlagCreativeResponse(
      creativeId: String,
      flagged: Boolean
  )

  /** Request to unflag a creative */
  case class UnflagCreativeRequest(
      creativeId: String
  )

  /** Response from unflagging a creative */
  case class UnflagCreativeResponse(
      creativeId: String,
      unflagged: Boolean
  )

  /** Request to revoke creative approval (removes from ServeIndex, clears both approved and rejected filters) */
  case class RevokeCreativeRequest(
      creativeId: String
  )

  /** Response from revoking creative approval */
  case class RevokeCreativeResponse(
      creativeId: String,
      revoked: Boolean
  )

  /** A flagged/quarantined creative */
  case class FlaggedCreative(
      url: String,
      slotId: String,
      creativeId: String,
      campaignId: String,
      cpm: String,
      category: String,
      reason: String,
      flaggedAt: String,
      assetUrl: Option[String] = None,
      // Raw banner pagesJson + config so the unified approval inbox renders
      // the live <magazine-preview> for flagged creatives too.
      pagesJson: Option[String] = None,
      bannerConfigJson: Option[String] = None,
      // Advertiser + landing domain so flagged shows the same detail row as
      // pending/serving; landing domain is the inbox row name.
      advertiserId: Option[String] = None,
      landingDomain: Option[String] = None,
      // Full landing-page URL for the CTA → opens the LP in a new tab from
      // the approval preview (same as pending/serving).
      landingUrl: Option[String] = None
  )

  /** List of flagged creatives */
  case class FlaggedCreativeList(
      data: Vector[FlaggedCreative],
      meta: Meta
  )

  // ----------------- Page Classification (Crawler) -----------------

  case class SlotSpec(
      slotId: String,
      width: Int,
      height: Int
  )

  case class ClassifyPageRequest(
      url: String,
      categories: Map[String, Double], // category -> confidence score
      slots: Vector[SlotSpec]
  )

  case class ClassifyPageResponse(
      url: String,
      status: String,
      categoriesCount: Int,
      slotsCount: Int
  )

  // ----------------- Bulk Approval -----------------

  case class BulkApproveRequest(
      url: String,
      slotId: String
  )

  case class BulkApproveResponse(
      url: String,
      slotId: String,
      approved: Int,
      failed: Int
  )

  // ----------------- Site Stats -----------------

  case class SiteStats(
      siteId: String,
      total: Long,
      selected: Long,
      pacingSkipped: Long,
      budgetExhausted: Long,
      noCandidates: Long,
      contentTooOld: Long,
      totalSpend: Double,
      elapsedHours: Double,
      expectedSpendFraction: Double,
      pacingNote: String,
      trafficShapeSummary: Option[String] = None,
      weekdayShapeVolumes: Option[Vector[Double]] = None,
      weekendShapeVolumes: Option[Vector[Double]] = None
  )

  // ----------------- AdServer Stats -----------------

  case class CreativeStats(
      creativeId: String,
      impressions: Long,
      clicks: Long,
      folds: Long,
      ctr: Double,
      foldRate: Double
  )

  case class AdServerStatsResponse(
      siteId: String,
      creatives: Vector[CreativeStats]
  )

  // ----------------- Taxonomy Stats -----------------

  case class TaxonomyStatsResponse(
      category: String,
      siteId: String,
      wins: Double,
      clicks: Double,
      revenue: Double,
      ctr: Double,
      meanCtr: Double
  )

  // ----------------- Pacing Reset -----------------

  case class CampaignRef(
      advertiserId: String,
      campaignId: String
  )

  case class PacingResetRequest(
      campaigns: Vector[CampaignRef]
  )

  case class CampaignResetResult(
      campaignId: String,
      newDayStart: Option[String] = None,
      error: Option[String] = None
  )

  case class PacingResetResponse(
      resetCount: Int,
      campaigns: Vector[CampaignResetResult]
  )

  // ----------------- Serve Index -----------------

  case class ServeIndexCandidate(
      creativeId: String,
      campaignId: String,
      advertiserId: String,
      cpm: Double,
      category: String,
      assetUrl: Option[String] = None,
      landingDomain: Option[String] = None
  )

  case class ServeIndexResponse(
      slotId: Option[String],
      found: Boolean,
      candidateCount: Int,
      candidates: Vector[ServeIndexCandidate]
  )

  case class ServeIndexKeysResponse(
      siteId: String,
      keys: Vector[String]
  )

  // ----------------- Auction / Category Registration -----------------

  case class RegisterCampaignsRequest(
      campaigns: Map[String, String] // campaignId -> advertiserId
  )

  case class RegisterCampaignsResponse(
      category: String,
      campaignCount: Int,
      status: String
  )

  // Magazine Creative (expandable banner from LP extraction)
  case class CreateCreativeRequest(
      name: String,
      pages: Vector[ExtractedBannerPage],
      landingUrl: String,
      // Full extracted LP text (all sections, not just what the advertiser
      // picked for pages). Fed to Gemini category verification instead of
      // a rendered banner image — text carries denser signal and avoids
      // base64 payload bloat.
      lpTextSnapshot: Option[String] = None,
      // Work-in-progress save. Skips CreativeProcessor (no banner
      // render, no Gemini verify) and writes the creative with
      // status=Draft so it's excluded from delivery until published.
      draft: Option[Boolean] = None,
      // If set, update the existing creative rather than creating a new
      // one. Lets the advertiser come back to a draft, keep editing, and
      // save repeatedly without producing orphan rows.
      creativeId: Option[String] = None,
      // Banner-level config blob from the designer's banner-config-panel
      // (expandAnimation, expandDurationMs, etc.). Opaque JSON, stored
      // verbatim and forwarded to the runtime banner element via the
      // `config` attribute. Optional for legacy clients that don't yet
      // emit this field — null = banner uses hardcoded defaults.
      bannerConfigJson: Option[String] = None,
      // Simulation/test escape hatch. When true (and not draft), the
      // creative is registered + assigned but the CreativeProcessor
      // (banner render + Gemini verification) is skipped — RunScenario
      // and other synthetic-load drivers don't have GCP keys and don't
      // need real banners. Production clients leave this unset.
      skipVerify: Option[Boolean] = None,
      // LP-original font faces the advertiser OPTED IN to re-hosting
      // ("I hold the license"). Presence = consent: the platform only
      // forwards this after the wizard checkbox, and CreativeProcessor
      // copies each quarantined file (fonts/orig/<hash>) to the live
      // catalog key before rendering. Absent for everything else.
      lpFonts: Vector[OriginalFontOffer] = Vector.empty
  )

  case class CreateCreativeResponse(
      id: String,
      campaignId: String,
      status: String
  )

  // ----------------- IAB Ad Product Taxonomy -----------------

  /** Update ad product category for a campaign */
  case class UpdateAdProductCategoryRequest(
      adProductCategory: Option[String] // None = clear, Some(value) = set
  )

  case class UpdateAdProductCategoryResponse(
      campaignId: String,
      adProductCategory: Option[String]
  )

  /** Block ad product categories on a site */
  case class AdProductBlocklistRequest(
      categories: Vector[String]
  )

  case class AdProductBlocklistResponse(
      siteId: String,
      categories: Vector[String]
  )

  case class AdProductUnblockRequest(
      categories: Vector[String]
  )

  case class AdProductUnblockResponse(
      siteId: String,
      unblocked: Vector[String]
  )

  // ----------------- Category Verification -----------------

  /** Request to verify if an image matches a declared category */
  case class VerifyCategoryRequest(
      imageBase64: String,
      mimeType: String,
      declaredCategory: Option[String]
  )

  /** Response from category verification */
  case class VerifyCategoryResponse(
      matchConfidence: Double,
      reason: String
  )

  // ----------------- Creative Status -----------------

  /** Request to update creative status (pause/activate) */
  case class UpdateCreativeStatusRequest(
      status: String // "Active" or "Paused"
  )

  /** Response from creative status update */
  case class UpdateCreativeStatusResponse(
      creativeId: String,
      status: String,
      previousStatus: String
  )

  // ----------------- Login -----------------

  /** Login request with email */
  case class LoginRequest(email: String)

  /** Login response with advertiser info */
  case class LoginResponse(
      advertiserId: String,
      name: String,
      isNew: Boolean // true if new advertiser was created
  )

  // ----------------- Publisher Login -----------------

  /** Publisher login request with email */
  case class PublisherLoginRequest(email: String)

  /** Publisher login response with publisher info */
  case class PublisherLoginResponse(
      publisherId: String,
      name: String,
      isNew: Boolean // true if new publisher was created
  )
}
