package promovolve.auction

import org.apache.pekko.actor.typed.pubsub.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{Settings as _, *}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, EntityTypeKey}
import org.apache.pekko.util.Timeout
import promovolve.*
import promovolve.advertiser.*
import promovolve.auction.AuctioneerEntity.*
import promovolve.auction.CategoryBidderEntity.{CategoryBidRequest, CategoryBidResponse}
import promovolve.common.Aggregator
import promovolve.publisher.SiteEntity
import promovolve.publisher.delivery.AdServer
import promovolve.publisher.FloorSweepOptimizer
import promovolve.taxonomy.{TaxonomyRankerEntity, TieredCategory}
import promovolve.taxonomy.TaxonomyRankerEntity.{Quote, Quoted}

import java.time.Instant
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/** AuctioneerEntity - Per-site real-time ad auction orchestrator.
  *
  * == Lifecycle ==
  * One instance per publisher site, sharded by siteId. Subscribes to budget and
  * campaign change topics on startup, unsubscribes on stop.
  *
  * == Auction Pipeline ==
  * {{{
  * PageCategoriesClassified
  *   → startRanking (fan-out to TaxonomyRankerEntity)
  *   → PageCategoriesRanked (aggregate, apply timeout fallback)
  *   → fan-out to CategoryBidderEntity per slot
  *   → CandidatesCollected (aggregate bids)
  *   → completeAuction (send to AdServer)
  * }}}
  *
  * == Ephemeral State ==
  *  - `lastPage`: URL → (categories, slots, classifiedAt) for re-auction without re-scrape
  *  - `lastQuote`: category → (weight, timestamp) for timeout fallback with decay
  *  - `participatingCampaigns`: campaignId → URLs for targeted re-auction
  *
  * == Event-Driven Re-Auction ==
  *  - CampaignChanged/BudgetReset → targeted re-auction (affected URLs only)
  *  - AdvertiserBudgetExhausted/Reset → full site re-auction (recent pages)
  *
  * @see [[AUCTION.md]] for detailed architecture documentation
  */
object AuctioneerEntity {
  /** Union type for all messages this actor handles (public + internal + topic events) */
  private[auction] type Messages = Command | Internal | Quoted | BudgetEvent |
    CampaignEntity.CampaignChanged | AffinityExpander.AffinityExpansionResult
  val TypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("auctioneer-entity")

  def apply(
      siteId: SiteId,
      sharding: ClusterSharding,
      budgetEventTopic: ActorRef[
        Topic.Command[BudgetEvent]
      ],
      campaignChangedTopic: ActorRef[
        Topic.Command[
          CampaignEntity.CampaignChanged
        ]
      ],
      settings: Settings = Settings(),
      affinityRegistry: Option[ActorRef[promovolve.taxonomy.AffinityRegistryDData.Cmd]] = None,
      campaignDirectory: Option[ActorRef[promovolve.advertiser.CampaignDirectory.Command]] = None
  ): Behavior[Command] =
    Behaviors
      .setup[Messages] { ctx =>
        // Subscribe to budget events for cache invalidation (use ctx.self with union types)
        budgetEventTopic ! Topic.Subscribe(ctx.self)

        // Subscribe to campaign changed events for immediate auction (use ctx.self with union types)
        campaignChangedTopic ! Topic.Subscribe(ctx.self)

        // Re-arm volatile per-slot admin floors after a (re)start. The map
        // lives only in this actor's memory and SiteEntity re-pushes it
        // only on ITS OWN restart or on edits — an auctioneer-only restart
        // (shard rebalance, crash) would otherwise re-admit bids below an
        // admin override until the next edit. Tell+tell handshake: never
        // ask from a setup block.
        sharding.entityRefFor(promovolve.publisher.SiteEntity.TypeKey, siteId.value) !
          promovolve.publisher.SiteEntity.AuctioneerStarted

        new AuctioneerEntity(
          siteId,
          sharding,
          settings,
          budgetEventTopic,
          campaignChangedTopic,
          ctx,
          affinityRegistry,
          campaignDirectory
        ).serving()
      }
      .narrow

  sealed trait Command extends promovolve.CborSerializable

  private[auction] sealed trait Internal

  final case class PageCategoriesClassified(
      url: URL,
      categoryScores: Map[String, Double],
      slots: List[AdSlotSpec],
      ts: Instant
  ) extends Command

  /** Sent by `SiteEntity` when Gemini honestly classified a page as
    * matching none of the current demand categories. The auctioneer
    * skips the category-bidder fan-out entirely and invites only the
    * filler pool (campaigns with `bidOnUnmatchedContext = true`) to
    * bid directly. Survivors land in ServeIndex with
    * `preApproved = false`, so the publisher sees them in the
    * approval queue before they ever serve. */
  final case class FillerAuctionRequested(
      url: URL,
      slots: List[AdSlotSpec],
      ts: Instant
  ) extends Command

  /** Re-run the auction for a URL using the last cached classification & slots. */
  final case class Reevaluate(url: URL) extends Command

  /** Update the floor CPM for this site (from publisher settings). */
  final case class UpdateFloorCpm(cpm: CPM) extends Command

  /** Per-category floors from SiteEntity.
    * Replaces the single floor with a per-category map for bid collection;
    * categories absent from the map fall back to `currentFloorCpm`. */
  final case class UpdateCategoryFloors(floors: Map[CategoryId, CPM]) extends Command

  /** Per-slot floor overrides emitted by the RL agent once a slot
    * accumulates enough auctions to diverge meaningfully from the
    * site average. Sent alongside `UpdateFloorCpm`. Slots not in the
    * map fall through to the site floor (with crawler prior). */
  final case class UpdateSlotFloors(floors: Map[SlotId, CPM]) extends Command

  /** Admin-set per-slot floor overrides (escape hatch for publishers).
    * Take precedence over RL slot floors. Replaces the full admin map
    * each call — slots not in the map have no admin override. */
  final case class UpdateAdminSlotFloors(floors: Map[SlotId, CPM]) extends Command

  /** Replay persisted page classifications from SiteEntity on cluster
    * restart. SiteEntity sends this once after its DurableState
    * recovery so the auctioneer's in-memory `lastPage` cache is ready
    * before any traffic arrives — eliminates the bootstrap-crawl
    * thundering herd that previously hit Playwright + Gemini on every
    * restart. */
  final case class RestoreClassifications(
      classifications: Map[String, SiteEntity.ClassificationEntry]
  ) extends Command

  final case class AdSlotSpec(
      slotId: SlotId,
      declaredSizes: List[AdSize],
      computedSize: AdSize,
      // Per-slot floor knobs — see SiteEntity.SlotPrior / effectiveFloor.
      // prior is crawler-derived; floorOverride is RL/admin-set.
      // Both default to None so existing call sites compile unchanged
      // and the system falls through to the site-level floor.
      prior: Option[SiteEntity.SlotPrior] = None,
      floorOverride: Option[CPM]          = None,
  )

  /** Topic-narrow eviction: given the URLs a campaign is currently awarded on
    * and the per-URL classification cache, return the ServeIndex slot keys the
    * campaign must be evicted from after it narrowed its target categories.
    *
    * A page contributes its slot keys iff it is CATEGORIZED (non-empty
    * categoryScores) AND none of its categories are in the campaign's new
    * target set — i.e. the campaign no longer targets that page's topic.
    * Filler/uncategorized awarded pages (empty categoryScores) are skipped:
    * category narrowing does not affect filler eligibility.
    *
    * Pure + side-effect-free for unit testing. An unknown campaign maps to an
    * empty `awardedUrls` set → empty result (no crash).
    */
  private[auction] def topicEvictionSlotKeys(
      siteId: String,
      awardedUrls: Set[URL],
      lastPage: Map[URL, (Map[String, Double], List[AdSlotSpec], Instant)],
      targetCategories: Set[CategoryId],
  ): Set[String] = {
    val newTargets = targetCategories.map(_.value)
    awardedUrls.flatMap { url =>
      lastPage.get(url) match {
        case Some((categoryScores, slots, _)) =>
          val pageCats = categoryScores.keySet
          if (pageCats.nonEmpty && pageCats.intersect(newTargets).isEmpty)
            slots.iterator.map(s => promovolve.publisher.Keys.keyUnsafe(siteId, s.slotId.value))
          else Iterator.empty
        case None => Iterator.empty
      }
    }
  }

  /** How long a CampaignChanged mark makes the campaign's absence from an
    * auction result authoritative (see authoritativeRefresh). Must outlive
    * the 1s re-auction debounce + the bid fanout; kept short so the
    * budget-exhaustion orphan protection resumes quickly. */
  private[auction] val AuthoritativeRefreshWindow: FiniteDuration = 5.minutes

  final case class Settings(
      topK: Int                         = 3,
      askTimeout: FiniteDuration        = 800.millis,
      priorWeight: Double               = 0.5,
      minScore: Double                  = 0.0,
      enableParentFallback: Boolean     = false,
      keepCandidatesPerSlot: Int        = 3,
      ttl: FiniteDuration               = 120.minutes,
      priorHalfLife: FiniteDuration     = 1.hour,
      // Periodic backstop only. Event-driven re-auctions (campaign/budget/
      // floor changes) fire on a 1s debounce via scheduleReauction, and
      // serve-misses self-heal on demand (AdServer requests a Reevaluate),
      // so this timer no longer needs to be the primary fill mechanism. Kept
      // well under the ServeView TTL (120min) so periodic re-Puts keep entries
      // warm and reconcile any dropped removal-events. Override via
      // promovolve.auction.reauction-interval.
      reauctionInterval: FiniteDuration = 30.minutes,
      contentRecencyWindow: FiniteDuration =
        48.hours, // Only monetize content published within this window
      cleanupInterval: FiniteDuration = 5.minutes, // More aggressive cleanup (was 1 hour)
      floorCpm: CPM                   = CPM(0.50), // Minimum CPM floor price (default $0.50)
      maxPageCacheSize: Int  = 10000, // Max URLs to cache per publisher (prevents unbounded growth)
      maxCategoryQuotes: Int = 500, // Max category weights to cache
      // Content-product affinity expansion
      enableAffinityExpansion: Boolean      = false, // Off by default for safe rollout
      minAffinityScore: Double              = 0.05,  // Min sampled CTR to include affinity pair
      maxAffinityExpansion: Int             = 3,     // Max additional categories from affinity
      affinityTimeout: FiniteDuration       = 300.millis
  )

  private final case class PageCategoriesRanked(
      url: URL,
      ranked: List[(String, Double)],
      slots: List[AdSlotSpec],
      quotedReplies: immutable.IndexedSeq[Quoted]
  ) extends Internal

  private final case class CandidatesCollected(
      url: URL,
      slotId: SlotId,
      candidates: Vector[Candidate],
      rejectedByFloor: Int = 0,
      // Highest / lowest CPM among below-floor rejections across all
      // category responses, carried through so the AuctionOutcome
      // reported to SiteEntity is unbiased by the current floor.
      maxRejectedCpm: Double = 0.0,
      minRejectedCpm: Double = 0.0,
      // Per-category bid bounds (maxObservedCpm, minObservedCpm,
      // rejectedByFloor), preserved un-aggregated for the per-category floor
      // so each category's optimizer gets its OWN sweep range. Empty in the
      // filler path and when the mode is off.
      // APPROVED-ONLY: computed from campaigns with ≥1 publisher-approved
      // creative — pending demand bids (to reach the approval queue) but
      // must not teach the floor.
      perCategoryBounds: Map[CategoryId, (Double, Double, Int, Int)] = Map.empty,
      // APPROVED-ONLY aggregate demand for the site-level AuctionOutcome
      // report. The raw rejectedByFloor/max/minRejectedCpm above keep their
      // all-bidders semantics for the eviction-evidence machinery
      // (hadFloorRejects), which cares that *anyone* was priced out.
      approvedCampaignIds: Set[CampaignId] = Set.empty,
      approvedFloorRejects: Int = 0,
      maxApprovedRejectedCpm: Double = 0.0,
      minApprovedRejectedCpm: Double = 0.0,
  ) extends Internal

  /** Reply from `CampaignDirectory.GetFillerCampaigns`. Adapted into
    * the actor via `ctx.messageAdapter` so the core auctioneer flow
    * stays typed against its own command set. */
  private final case class FillerCampaignsList(
      url: URL,
      slots: List[AdSlotSpec],
      campaigns: Map[CampaignId, AdvertiserId]
  ) extends Internal

  /** Periodic cleanup and re-auction trigger */
  private case object PeriodicReauction extends Internal

  /** Debounced re-auction: coalesces rapid-fire budget/campaign events into a single re-auction */
  private case object DebouncedReauction extends Internal

  /** Retry auction for a URL that got 0 candidates (startup race condition) */
  private final case class RetryEmptyAuction(url: URL, attempt: Int) extends Internal

  /** Remove old content classifications (recency-only model) */
  private case object CleanupStaleContent extends Internal

  /** Max retries for empty auctions before giving up (waits for periodic re-auction) */
  val MaxEmptyAuctionRetries: Int = 3

  /** Delay between empty auction retries */
  val EmptyAuctionRetryDelay: FiniteDuration = 30.seconds
}

private final class AuctioneerEntity private (
    siteId: SiteId,
    sharding: ClusterSharding,
    cfg: Settings,
    budgetEventTopic: ActorRef[
      Topic.Command[BudgetEvent]
    ],
    campaignChangedTopic: ActorRef[
      Topic.Command[
        CampaignEntity.CampaignChanged
      ]
    ],
    ctx: ActorContext[Messages],
    affinityRegistry: Option[ActorRef[promovolve.taxonomy.AffinityRegistryDData.Cmd]],
    campaignDirectory: Option[ActorRef[promovolve.advertiser.CampaignDirectory.Command]]
) {

  // Pending affinity expansion state
  private var pendingExpansion: Option[(URL, List[String], List[AdSlotSpec])] = None

  private val public: PartialFunction[Messages, Behavior[Messages]] = {
    case PageCategoriesClassified(url, categoryScores, slots, ts) =>
      // Tell AdServer the page is classified NOW (before/independent of the
      // auction outcome) so its freshness token is set even if no campaign
      // bids — otherwise a no-bidder page re-classifies on every serve.
      adServer ! AdServer.MarkClassified(url, ts)
      ctx.log.debug(
        "🏁 Auction START: site={} url={} categories={} slots={}",
        siteId,
        url,
        categoryScores.size,
        slots.size
      )
      ctx.log.debug(
        "Auction categories: {}, slots: {}",
        categoryScores.keys.mkString(","),
        slots.map(_.slotId).mkString(",")
      )
      // Remember the last classification to enable re-auction without re-scrape
      // Enforce max size to prevent unbounded memory growth; evict the oldest by classifiedAt
      lastPage = {
        if (lastPage.size >= cfg.maxPageCacheSize && !lastPage.contains(url)) {
          lastPage.minByOption { case (_, (_, _, classifiedAt)) => classifiedAt } match {
            case Some((oldUrl, _)) =>
              ctx.log.debug("Page cache full ({}), evicted oldest: {}", cfg.maxPageCacheSize, oldUrl)
              lastPage.removed(oldUrl)
            case None => lastPage
          }
        } else lastPage
      }.updated(url, (categoryScores, slots, ts))
      startRanking(url, categoryScores, slots)
      Behaviors.same

    case UpdateFloorCpm(cpm) =>
      ctx.log.info("Floor CPM updated: site={} floor={}", siteId, cpm.toDouble)
      currentFloorCpm = cpm
      scheduleReauction()
      Behaviors.same

    case UpdateCategoryFloors(floors) =>
      ctx.log.info("Per-category floors updated: site={} count={}", siteId, floors.size: java.lang.Integer)
      currentCategoryFloors = floors
      scheduleReauction()
      Behaviors.same

    case UpdateSlotFloors(floors) =>
      ctx.log.info(
        "Per-slot floors updated (RL): site={} count={} floors={}",
        siteId, floors.size: java.lang.Integer,
        floors.iterator.map { case (sid, cpm) => f"${sid.value}=$$${cpm.toDouble}%.3f" }.mkString(",")
      )
      slotFloorOverrides = floors
      scheduleReauction()
      Behaviors.same

    case UpdateAdminSlotFloors(floors) =>
      ctx.log.info(
        "Per-slot floors updated (admin): site={} count={} floors={}",
        siteId, floors.size: java.lang.Integer,
        floors.iterator.map { case (sid, cpm) => f"${sid.value}=$$${cpm.toDouble}%.3f" }.mkString(",")
      )
      adminSlotFloorOverrides = floors
      scheduleReauction()
      Behaviors.same

    case RestoreClassifications(classifications) =>
      // Merge with whatever lastPage already holds (typically empty on
      // restart, but be defensive if SiteEntity sends twice). Persisted
      // entries don't go through eviction here — CleanupStaleContent
      // will prune anything older than contentRecencyWindow on its
      // first tick, so the load stays bounded.
      classifications.foreach { case (urlStr, entry) =>
        val url    = URL(urlStr)
        val slots  = entry.slots.iterator.map(_.toAdSlotSpec).toList
        val tsInst = Instant.ofEpochMilli(entry.classifiedAt)
        lastPage = lastPage.updated(url, (entry.categories, slots, tsInst))
        // Repopulate AdServer's freshness token after a restart so restored
        // pages (incl. no-bidder ones) aren't treated as cold on first serve.
        adServer ! AdServer.MarkClassified(url, tsInst)
      }
      ctx.log.info(
        "Restored {} page classifications from SiteEntity (lastPage size now {})",
        classifications.size: java.lang.Integer,
        lastPage.size: java.lang.Integer,
      )
      // Kick off an immediate re-auction so ServeIndex repopulates
      // within ~1 second instead of waiting up to reauctionInterval
      // for the periodic tick. Without this, skipping the bootstrap
      // crawl creates a serve drought after restart even though
      // lastPage is full.
      scheduleReauction()
      Behaviors.same

    case Reevaluate(url) =>
      lastPage.get(url) match {
        case Some((scores, slots, _)) if scores.isEmpty =>
          // Filler-classified URL (no contextual match). Re-fire the
          // filler auction so opted-in campaigns get a fresh shot —
          // this is what makes the advertiser's flag toggle propagate
          // to pages classified before they opted in.
          ctx.log.debug("🔁🫙 Filler re-auction: site={} url={}", siteId, url)
          ctx.self ! FillerAuctionRequested(url, slots, Instant.now)
          Behaviors.same
        case Some((scores, slots, _)) =>
          ctx.log.debug("🔁 Re-auction triggered: site={} url={}", siteId, url)
          startRanking(url, scores, slots)
          Behaviors.same
        case None =>
          ctx.log.debug("Reevaluate ignored for url={} (no cached classification)", url)
          Behaviors.same
      }
  }
  private val pipeline: PartialFunction[Messages, Behavior[Messages]] = {
    case PageCategoriesRanked(url, ranked, slots, quotedReplies) =>
      // Update cache with fresh weights from this auction round (only if newer)
      quotedReplies.foreach { case Quoted(category, w, ts) =>
        updateQuote(category, w, ts)
      }
      val categoryCandidates = ranked.collect {
        case (category, score) if score >= cfg.minScore => category
      }
      ctx.log.debug(
        "📊 Categories ranked: url={} eligible={} (minScore={})",
        url,
        categoryCandidates.size,
        cfg.minScore
      )
      ctx.log.debug("Eligible categories: {}", categoryCandidates.mkString(","))
      if (categoryCandidates.nonEmpty) {
        // Check if affinity expansion is enabled and registry is available
        if (cfg.enableAffinityExpansion && affinityRegistry.isDefined) {
          // Store pending state and spawn AffinityExpander
          pendingExpansion = Some((url, categoryCandidates, slots))
          val expanderReplyAdapter = ctx.messageAdapter[AffinityExpander.AffinityExpansionResult](identity)
          ctx.spawnAnonymous(AffinityExpander(
            contentCategories = categoryCandidates.toSet,
            affinityRegistry  = affinityRegistry.get,
            sharding          = sharding,
            minAffinityScore  = cfg.minAffinityScore,
            maxExpansion      = cfg.maxAffinityExpansion,
            replyTo           = expanderReplyAdapter,
            timeout           = cfg.affinityTimeout
          ))
        } else {
          fanOutBidRequests(url, categoryCandidates, slots)
        }
      }
      Behaviors.same

    case result: AffinityExpander.AffinityExpansionResult =>
      pendingExpansion match {
        case Some((url, originalCategories, slots)) =>
          val allCategories = (originalCategories ++ result.expandedCategories.toList).distinct
          if (result.expandedCategories.nonEmpty) {
            ctx.log.info(
              "🔗 Affinity expansion: site={} original={} expanded={} total={}",
              siteId, originalCategories.size, result.expandedCategories.size, allCategories.size
            )
          }
          fanOutBidRequests(url, allCategories, slots)
          pendingExpansion = None
        case None =>
          ctx.log.warn("Received AffinityExpansionResult with no pending expansion")
      }
      Behaviors.same

    case FillerAuctionRequested(url, slots, ts) =>
      // Mark classified even for a filler (no-category) page, so its freshness
      // token is set and it isn't re-classified every serve.
      adServer ! AdServer.MarkClassified(url, ts)
      // Gemini honestly said "no category matches this page." Route
      // to the filler auction: ask CampaignDirectory for opted-in
      // campaigns, fan out CampaignBidRequest with CategoryId.Filler
      // directly to each. Campaigns with `bidOnUnmatchedContext =
      // true` accept the sentinel and reply with a bid; all other
      // campaigns reject with CategoryMismatch. Survivors flow into
      // the usual CandidatesCollected path — marked preApproved=false
      // like every other bid, so the publisher still decides per
      // creative whether filler gets to serve.
      //
      // Also cache the URL in lastPage with an empty categoryScores
      // map — that's how Reevaluate / PeriodicReauction tell a filler
      // page apart from a category-classified one. Without this,
      // re-auctions (including the ones triggered when an advertiser
      // toggles bidOnUnmatchedContext on) never revisit filler URLs.
      lastPage = {
        if (lastPage.size >= cfg.maxPageCacheSize && !lastPage.contains(url)) {
          lastPage.minByOption { case (_, (_, _, classifiedAt)) => classifiedAt } match {
            case Some((oldUrl, _)) =>
              ctx.log.debug("Page cache full ({}), evicted oldest: {}", cfg.maxPageCacheSize, oldUrl)
              lastPage.removed(oldUrl)
            case None => lastPage
          }
        } else lastPage
      }.updated(url, (Map.empty[String, Double], slots, ts))

      campaignDirectory match {
        case Some(directory) =>
          ctx.log.debug(
            "🫙 Filler auction requested: site={} url={} slots={}",
            siteId, url, slots.size
          )
          val directoryAdapter = ctx.messageAdapter[promovolve.advertiser.CampaignDirectory.FillerCampaignsResult] { r =>
            FillerCampaignsList(url, slots, r.campaigns)
          }
          directory ! promovolve.advertiser.CampaignDirectory.GetFillerCampaigns(directoryAdapter)
        case None =>
          ctx.log.debug(
            "Filler auction requested but campaignDirectory not wired in — skipping. url={}",
            url
          )
          // Complete (empty) anyway so AdServer marks the url classified and
          // needText flips false (avoids re-classify-every-pageview).
          slots.foreach(s => completeAuction(url, s.slotId, Vector.empty))
      }
      Behaviors.same

    case FillerCampaignsList(url, slots, campaigns) =>
      if (campaigns.isEmpty) {
        ctx.log.debug(
          "🫙 Filler auction: site={} url={} — no opted-in campaigns, slot stays empty",
          siteId, url
        )
        // Still complete the auction (empty) so AdServer records the url as
        // CLASSIFIED — otherwise a no-match page with no filler demand would
        // never flip needText=false and the ad tag would re-classify it on
        // every pageview. See docs/design/ON_DEMAND_CLASSIFICATION.md.
        slots.foreach(s => completeAuction(url, s.slotId, Vector.empty))
      } else {
        ctx.log.debug(
          "🫙 Filler auction: site={} url={} — {} opted-in campaigns, fanning out bids",
          siteId, url, campaigns.size
        )
        fanOutFillerBidRequests(url, campaigns, slots)
      }
      Behaviors.same

    case CandidatesCollected(url, slotId, candidates, rejectedByFloor, maxRejectedCpm, minRejectedCpm, perCategoryBounds, approvedCampaignIds, approvedFloorRejects, maxApprovedRejectedCpm, minApprovedRejectedCpm) =>
      // INFO only when a campaign actually won a spot; an empty auction
      // (periodic re-auction of a page nobody bids on) stays at debug.
      if (candidates.nonEmpty)
        ctx.log.debug(
          "🏆 Auction filled: url={} slot={} winners={}",
          url,
          slotId,
          candidates.size
        )
      else
        ctx.log.debug("Empty auction: url={} slot={} (no winners)", url, slotId)
      // Log each candidate for debugging
      candidates.foreach { c =>
        ctx.log.debug(
          "   Candidate: creative={} campaign={} cpm={} preApproved={} category={}",
          c.creativeId.value,
          c.campaignId.value,
          c.cpm.value,
          c.preApproved,
          c.category.value
        )
      }
      // Campaign-fair ordering: best creative per campaign first (by CPM), then remaining.
      // No artificial candidate cap — all competitive bids pass through to serve-time
      // Thompson Sampling. The CPM threshold filter in CategoryBidderEntity (top 20%)
      // provides the natural limit on candidate pool size.
      //
      // Deduplicate by creativeId — same creative can enter via multiple category paths
      val deduped = candidates
        .groupBy(_.creativeId)
        .values
        .map(_.head) // keep first (arbitrary, same creative = same CPM)
        .toVector
      val sorted = deduped
        .sortBy(c => (-c.cpm.value, if (c.preApproved) 0 else 1))

      // Best per campaign first, then remaining creatives by CPM
      val bestPerCampaign = sorted
        .groupBy(_.campaignId)
        .values
        .map(_.head)
        .toVector
        .sortBy(c => (-c.cpm.value, if (c.preApproved) 0 else 1))
      val usedCreativeIds = bestPerCampaign.map(_.creativeId).toSet
      val rest = sorted.filterNot(c => usedCreativeIds.contains(c.creativeId))
      val topCandidates = bestPerCampaign ++ rest

      if (deduped.nonEmpty) {
        val kept = topCandidates.map(c =>
          s"${c.campaignId.value}/${c.creativeId.value}(cpm=${c.cpm.toDouble})"
        ).mkString(", ")
        val cutCandidates = sorted.drop(topCandidates.size)
        val cutStr = if (cutCandidates.nonEmpty)
          cutCandidates.map(c =>
            s"${c.campaignId.value}/${c.creativeId.value}(cpm=${c.cpm.toDouble})"
          ).mkString(", ")
        else "none"
        ctx.log.debug(
          "AuctionScorecard site={} slot={} total={} deduped={} kept={} winners=[{}] cut=[{}]",
          siteId.value, slotId.value,
          candidates.size: java.lang.Integer,
          deduped.size: java.lang.Integer,
          topCandidates.size: java.lang.Integer,
          kept, cutStr
        )
      }

      ctx.log.debug(
        "   After sorting: passing {} candidates to AdServer",
        topCandidates.size
      )

      if (topCandidates.nonEmpty) {
        completeAuction(url, slotId, topCandidates)
        emptyAuctionRetries -= s"${url.value}|${slotId.value}" // clear retry counter on success
      } else {
        // Schedule retry — campaigns may not be registered yet (startup race condition)
        val retryKey = s"${url.value}|${slotId.value}"
        val attempt = emptyAuctionRetries.getOrElse(retryKey, 0)
        if (attempt < AuctioneerEntity.MaxEmptyAuctionRetries) {
          emptyAuctionRetries = emptyAuctionRetries.updated(retryKey, attempt + 1)
          _timers.foreach { timers =>
            ctx.log.debug("⏳ Empty auction for url={} slot={}, scheduling retry {}/{} in {}",
              url, slotId, (attempt + 1): java.lang.Integer,
              AuctioneerEntity.MaxEmptyAuctionRetries: java.lang.Integer,
              AuctioneerEntity.EmptyAuctionRetryDelay)
            timers.startSingleTimer(
              s"retry-empty-$retryKey",
              AuctioneerEntity.RetryEmptyAuction(url, attempt + 1),
              AuctioneerEntity.EmptyAuctionRetryDelay
            )
          }
        }
        // Floor-sync even with no candidates. CandidatesCollected is the
        // only transport by which AdServer learns floors — an admin
        // override or sweep floor raised ABOVE all demand makes every
        // auction empty, and without this delivery the stale ServeIndex
        // entry (admitted under the old floor) keeps serving at the old
        // price for its full TTL. Empty deliveries update floors only;
        // AdServer leaves the ServeIndex untouched so dog-ear pins keep
        // honoring the (now pin-only) below-floor creatives.
        //
        // hadFloorRejects gates the authoritative-absent eviction: an empty
        // result WITH below-floor rejects is real evidence (the marked
        // campaign declined — the exact case the mark exists for). An empty
        // result with NO rejects is indistinguishable from a bid-collection
        // timeout, and evicting on it let transport hiccups purge healthy
        // serve-index entries (incident 2026-07-06, 660 evictions/25min).
        notifyEmptyAuction(url, slotId, hadFloorRejects = rejectedByFloor > 0)
      }

      // Report auction outcome to SiteEntity for floor CPM optimization
      val winnerCpm = topCandidates.headOption.map(_.cpm.toDouble)
      val clearingPrice = topCandidates match {
        case first +: second +: _ => Some(second.cpm.toDouble + 0.01)
        case first +: _           => Some(currentFloorCpm.toDouble)
        case _                    => None
      }
      // Span both qualifying bids and below-floor rejects so the floor
      // optimizer sees an unbiased range — otherwise a high current
      // floor blinds it to bids that would clear a lower floor, and
      // the next-cycle sweep range collapses around the qualifying
      // bids, permanently excluding rejected campaigns.
      //
      // APPROVED-ONLY: the outcome the floor optimizer learns from is
      // restricted to campaigns with a publisher-approved creative.
      // Pending demand competes for the approval queue but must not
      // teach a floor nothing can currently fulfil. (The eviction
      // gating above deliberately keeps the RAW reject counts.)
      val approvedDeduped = deduped.filter(c => approvedCampaignIds.contains(c.campaignId))
      val qualifyingMax = if (approvedDeduped.nonEmpty) approvedDeduped.map(_.cpm.toDouble).max else 0.0
      val qualifyingMin = if (approvedDeduped.nonEmpty) approvedDeduped.map(_.cpm.toDouble).min else 0.0
      val maxObserved   = math.max(qualifyingMax, maxApprovedRejectedCpm)
      val minObserved   = (qualifyingMin, minApprovedRejectedCpm) match {
        case (q, r) if q > 0.0 && r > 0.0 => math.min(q, r)
        case (q, _) if q > 0.0            => q
        case (_, r) if r > 0.0            => r
        case _                            => 0.0
      }
      val outcome = FloorSweepOptimizer.AuctionOutcome(
        totalBidders    = approvedDeduped.size + approvedFloorRejects,
        rejectedByFloor = approvedFloorRejects,
        winnerCpm       = winnerCpm,
        clearingPrice   = clearingPrice,
        maxObservedCpm  = maxObserved,
        minObservedCpm  = minObserved,
        slotId          = Some(slotId.value),
      )
      val siteRef = sharding.entityRefFor(SiteEntity.TypeKey, siteId.value)
      // Admin-overridden slots are invisible to floor LEARNING: their
      // admission floor is human-set, so their bid bounds and outcomes say
      // nothing about what floor the sweep-governed inventory can support —
      // reporting them would stretch the sweep's candidate range toward
      // demand that only exists behind the override.
      val slotIsOverridden =
        adminSlotFloorOverrides.contains(slotId) || slotSpecAdminFloors.contains(slotId)
      if (!slotIsOverridden) {
        siteRef ! SiteEntity.AuctionOutcomeReport(outcome)
      }

      // Per-category reports: one per category so
      // each per-category optimizer sees its OWN bid bounds. Only the bounds
      // matter to the optimizer (recordAuctionOutcome reads max/minObservedCpm
      // only); the other fields are filler. The aggregate report above still
      // feeds the site-level optimizer, so this never double-counts.
      // Overridden slots are excluded here for the same reason as above.
      if (!slotIsOverridden) {
        perCategoryBounds.foreach { case (cat, (maxObs, minObs, rejects, bidderCount)) =>
          val catOutcome = FloorSweepOptimizer.AuctionOutcome(
            totalBidders    = bidderCount,
            rejectedByFloor = rejects,
            winnerCpm       = None,
            clearingPrice   = None,
            maxObservedCpm  = maxObs,
            minObservedCpm  = minObs,
            slotId          = Some(slotId.value),
          )
          siteRef ! SiteEntity.AuctionOutcomeReport(catOutcome, Some(cat.value))
        }
      }

      Behaviors.same

    case PeriodicReauction =>
      // Recency-only model: Only reauction recent content within contentRecencyWindow
      // (Defensive filter - CleanupStaleContent should have already removed old entries)
      val now    = Instant.now()
      val cutoff = now.minus(java.time.Duration.ofSeconds(cfg.contentRecencyWindow.toSeconds))

      val recentPages = lastPage.filter { case (_, (_, _, classifiedAt)) =>
        classifiedAt.isAfter(cutoff)
      }

      // Trigger re-auction - AdServer will clear cache when receiving new candidates
      recentPages.foreach { case (url, _) =>
        ctx.self ! Reevaluate(url)
      }

      val skipped = lastPage.size - recentPages.size
      if (skipped > 0) {
        ctx.log.warn(
          "Periodic reauction: skipped {} stale pages (should have been cleaned up already)",
          skipped
        )
      }

      if (lastPage.isEmpty) {
        // No page cache — nothing to re-auction. Pages are (re)populated
        // on-demand when real visitors hit them (serve -> classify-page);
        // there is no crawl to request.
        ctx.log.debug("Periodic reauction: page cache empty, nothing to re-auction")
      } else {
        ctx.log.debug(
          "Periodic reauction triggered for {} recent pages (within {} window)",
          recentPages.size,
          cfg.contentRecencyWindow
        )
      }
      Behaviors.same

    case CleanupStaleContent =>
      // Recency-only model: Remove classifications older than contentRecencyWindow
      val now        = Instant.now()
      val cutoff     = now.minus(java.time.Duration.ofSeconds(cfg.contentRecencyWindow.toSeconds))
      val sizeBefore = lastPage.size

      // Find URLs being removed
      val removedUrls = lastPage.collect {
        case (url, (_, _, classifiedAt)) if !classifiedAt.isAfter(cutoff) => url
      }.toSet

      lastPage = lastPage.filter { case (_, (_, _, classifiedAt)) =>
        classifiedAt.isAfter(cutoff)
      }

      // Clean up awardedCampaigns - remove stale URLs and empty entries
      if (removedUrls.nonEmpty) {
        val campaignsBefore = awardedCampaigns.keySet
        awardedCampaigns = awardedCampaigns.view
          .mapValues(urls => urls -- removedUrls)
          .filter { case (_, urls) => urls.nonEmpty }
          .toMap
        val removedCampaigns = campaignsBefore -- awardedCampaigns.keySet

        // Clean up campaignToAdvertiser and decrement advertiser ref counts
        if (removedCampaigns.nonEmpty) {
          removedCampaigns.foreach { campaignId =>
            campaignToAdvertiser.get(campaignId).foreach { advertiserId =>
              advertiserCampaignCount = advertiserCampaignCount.updatedWith(advertiserId) {
                case Some(n) if n > 1 => Some(n - 1)
                case _                => None
              }
            }
          }
          campaignToAdvertiser = campaignToAdvertiser -- removedCampaigns
        }
      }

      val removed = sizeBefore - lastPage.size

      if (removed > 0) {
        ctx.log.info(
          "Recency cleanup: removed {} old classifications (cutoff={}, kept {} recent pages)",
          removed,
          cutoff,
          lastPage.size
        )
      }

      Behaviors.same

    // Handle campaign changed events directly (no wrapper needed with union types)
    case event: CampaignEntity.CampaignChanged =>
      ctx.log.info(
        "📬 Received CampaignChanged: campaign={} categories={} isActive={}",
        event.campaignId,
        event.categories.size,
        event.isActive
      )
      ctx.log.debug("CampaignChanged categories: {}", event.categories.mkString(","))

      if (event.isActive) {
        // Site-narrow eviction: an advertiser with a non-empty siteAllowlist
        // that no longer lists THIS site has dropped us. Mirror the
        // CampaignEntity.canBid `siteOk` predicate exactly (empty allowlist =
        // bid everywhere = never excluded). When excluded, wipe the campaign
        // from this whole site — pins included — since the advertiser left the
        // site. Still re-auction so other campaigns refill the freed slots.
        val excludedHere =
          event.siteAllowlist.nonEmpty && !event.siteAllowlist.contains(siteId.value)
        if (excludedHere) {
          ctx.log.info(
            "Campaign {} narrowed off site {} (allowlist={}) - evicting + re-auctioning",
            event.campaignId.value,
            siteId.value,
            event.siteAllowlist.mkString(",")
          )
          adServer ! AdServer.EvictCampaignFromSite(event.campaignId)
          scheduleReauction()
        } else if (event.configEdit) {
          // Topic-narrow eviction (still on this site). The advertiser may have
          // dropped a category. For every CATEGORIZED page this campaign is
          // currently awarded on whose categories no longer intersect the
          // campaign's full target set, the campaign no longer targets that
          // page → evict it from just that page's slot keys. Filler/
          // uncategorized awarded pages (empty categoryScores) are NOT affected
          // by a category narrow and are skipped. Pins are preserved downstream
          // (RemoveCampaignFromKey keepCreativeIds).
          //
          // Gated on configEdit — this comment always claimed "fires ONLY on
          // the narrowing edit" but the code fired on EVERY active
          // CampaignChanged, including boot/recovery re-registrations, whose
          // awarded-vs-classification view can be transiently inconsistent
          // (incident 2026-07-06: boot re-registrations evicted serving slots).
          val slotKeysToEvict: Set[String] =
            AuctioneerEntity.topicEvictionSlotKeys(
              siteId           = siteId.value,
              awardedUrls      = awardedCampaigns.getOrElse(event.campaignId, Set.empty),
              lastPage         = lastPage,
              targetCategories = event.targetCategories,
            )
          if (slotKeysToEvict.nonEmpty) {
            ctx.log.info(
              "Campaign {} narrowed off topic on site {} - evicting from {} slot key(s): {}",
              event.campaignId.value,
              siteId.value,
              slotKeysToEvict.size: java.lang.Integer,
              slotKeysToEvict.mkString(",")
            )
            adServer ! AdServer.EvictCampaignFromSlots(event.campaignId, slotKeysToEvict)
          }
          // Active config change (e.g. a CPM/budget bump). Do NOT eagerly clear
          // the campaign's ServeIndex entries first: RemoveCampaign blanks all of
          // its slots — and breaks dog-ear pins, which are only honored while the
          // creative is in the live pool — for the ~1-2s until the async
          // re-auction repopulates. The re-auction replaces entries atomically
          // (Put, with the new CPM) and orphan-preservation keeps them serving in
          // the meantime, so a refresh needs no clear-first gap.
          //
          // BUT the rebuild must be authoritative for THIS campaign: if its
          // creative doesn't come back (advertiser lowered maxCpm below the
          // floor → CampaignEntity rejects with BelowFloor and submits no bid),
          // orphan preservation would keep the OLD-CPM entry serving and
          // showing on the publisher dashboard until the ServeView TTL. Mark
          // it so AdServer drops (pin-aware) instead of preserving.
          // (configEdit-only, like this whole branch: boot re-registrations
          // marking campaigns turned restart turbulence into serve-index
          // purges — incident 2026-07-06.)
          authoritativeRefresh = authoritativeRefresh.updated(event.campaignId, Instant.now)
          ctx.log.info(
            "Campaign {} active - re-auctioning to refresh (authoritative for this campaign)",
            event.campaignId.value
          )
          scheduleReauction()
        } else {
          // Boot/recovery (re)registration — NOT a config edit. Refresh the
          // auctions so the index repopulates, but arm nothing: no
          // authoritative mark, no topic-narrow eviction. A restart must
          // never be able to delete serving entries.
          ctx.log.info(
            "Campaign {} registered (boot/recovery) - re-auctioning, no eviction marks",
            event.campaignId.value
          )
          scheduleReauction()
        }
      } else {
        // Campaign was paused/deactivated - remove its creatives from ServeIndex
        // now so it stops serving immediately, then re-auction so others fill.
        import promovolve.publisher.delivery.Protocol.RemoveCampaign
        ctx.log.info(
          "Campaign {} paused - removing creatives + re-auctioning",
          event.campaignId.value
        )
        adServer ! RemoveCampaign(event.campaignId)
        adServer ! AdServer.CampaignPaused(event.campaignId)
        reauctionForCampaign(event.campaignId, "Campaign paused")
      }
      Behaviors.same

    // ═══════════════════════════════════════════════════════════════════════════════
    // BUDGET EXHAUSTION & DAY ROLLOVER - ServeIndex Preservation Flow
    // ═══════════════════════════════════════════════════════════════════════════════
    //
    // When budget exhausts:
    //   1. CampaignBudgetExhausted / AdvertiserBudgetExhausted event fires
    //   2. AuctioneerEntity sends RefreshTTLForCampaign / RefreshTTLForAdvertiser to AdServer
    //   3. AdServer refreshes TTL to dayDurationSeconds * 1.1 (e.g., 660s for 600s day)
    //   4. Entries STAY in ServeIndex (not removed) - preserves approval status
    //   5. Re-auction triggers so other campaigns can fill the slot
    //
    // During Select (serving):
    //   - Budget checked before serving → exhausted campaigns won't serve
    //   - But entries remain in ServeIndex → approval status preserved
    //
    // On day rollover:
    //   1. AdServer detects rollover, sends ResetDayStart to campaigns/advertisers
    //   2. Budgets reset to full daily amount
    //   3. ServeIndex entries still valid (TTL was refreshed)
    //   4. Creatives resume serving immediately - no re-approval needed
    //
    // Key: Budget exhaustion is temporary. Suspension (AdvertiserSuspended) is permanent
    // and DOES remove entries from ServeIndex via RemoveAdvertiser command.
    // ═══════════════════════════════════════════════════════════════════════════════

    // Handle campaign-level budget events
    case event: CampaignBudgetReset =>
      ctx.log.info(
        "💰 CampaignBudgetReset: campaign={} newBudget={}",
        event.campaignId,
        event.newBudget
      )
      reauctionForCampaign(event.campaignId, "Campaign budget reset")
      Behaviors.same

    case event: CampaignBudgetExhausted =>
      ctx.log.warn(
        "💰 CampaignBudgetExhausted: campaign={}",
        event.campaignId.value
      )
      // NOTE: We do NOT remove from ServeIndex here. The Select path checks budget
      // before serving, so exhausted campaigns won't be served. Keeping them in
      // ServeIndex preserves approval status so they can resume when budget resets.
      // Refresh TTL so entries don't expire before budget resets at day rollover
      adServer ! AdServer.RefreshTTLForCampaign(event.campaignId)
      // Trigger re-auction so other campaigns can fill the slot
      reauctionForCampaign(event.campaignId, "Campaign budget exhausted")
      Behaviors.same

    // Handle advertiser-level budget events (affects all campaigns for that advertiser)
    //
    // Why full re-auction instead of targeted?
    // -----------------------------------------
    // Unlike campaign-level events where we track CampaignId -> URLs in `participatingCampaigns`,
    // we don't maintain an AdvertiserId -> CampaignId mapping here. Adding one would increase
    // memory usage and complexity for marginal benefit since:
    //
    // 1. Advertiser budget events are rare (at most once per day per advertiser)
    // 2. This "full" re-auction is scoped to THIS publisher only (AuctioneerEntity is per-site)
    // 3. Further filtered to pages within contentRecencyWindow (default 48h)
    // 4. Uses the existing PeriodicReauction mechanism which handles this efficiently
    //
    // So "full re-auction" here means: re-auction recent pages for this single publisher,
    // not a cluster-wide operation.
    //
    case event: AdvertiserBudgetExhausted =>
      // Only trigger re-auction if this advertiser has campaigns on this site
      if (advertiserCampaignCount.contains(event.advertiserId)) {
        ctx.log.warn(
          "💰 AdvertiserBudgetExhausted: advertiser={} - triggering full re-auction (keeping in ServeIndex)",
          event.advertiserId
        )
        // NOTE: We do NOT remove from ServeIndex here. The Select path checks budget
        // before serving, so exhausted advertisers won't be served. Keeping them in
        // ServeIndex preserves approval status so they can resume when budget resets.
        // Refresh TTL so entries don't expire before budget resets at day rollover
        adServer ! AdServer.RefreshTTLForAdvertiser(event.advertiserId)
        // Trigger full re-auction so other advertisers can fill the slots
        scheduleReauction()
      } else {
        ctx.log.debug(
          "💰 AdvertiserBudgetExhausted: advertiser={} - ignoring (no campaigns on this site)",
          event.advertiserId
        )
      }
      Behaviors.same

    case event: AdvertiserBudgetReset =>
      // Only trigger re-auction if this advertiser has campaigns on this site
      if (advertiserCampaignCount.contains(event.advertiserId)) {
        ctx.log.info(
          "💰 AdvertiserBudgetReset: advertiser={} newBudget={} - triggering full re-auction (advertiser participates)",
          event.advertiserId,
          event.newBudget
        )
        scheduleReauction()
      } else {
        ctx.log.debug(
          "💰 AdvertiserBudgetReset: advertiser={} - ignoring (no campaigns on this site)",
          event.advertiserId
        )
      }
      Behaviors.same

    case event: AdvertiserSuspended =>
      // Only trigger cleanup if this advertiser has campaigns on this site
      if (advertiserCampaignCount.contains(event.advertiserId)) {
        ctx.log.warn(
          "🚫 AdvertiserSuspended: advertiser={} - removing from ServeIndex and triggering full re-auction",
          event.advertiserId
        )
        // Remove all advertiser's creatives from ServeIndex (permanent removal)
        adServer ! AdServer.RemoveAdvertiser(event.advertiserId)
        // Trigger full re-auction so other advertisers can fill the slots
        scheduleReauction()
      } else {
        ctx.log.debug(
          "🚫 AdvertiserSuspended: advertiser={} - ignoring (no campaigns on this site)",
          event.advertiserId
        )
      }
      Behaviors.same

    case event: CreativeStatusChanged =>
      if (awardedCampaigns.contains(event.campaignId)) {
        if (event.isActive) {
          // Creative reactivated - trigger campaign re-auction to restore it
          ctx.log.info(
            "🎨 CreativeReactivated: creative={} campaign={} - triggering campaign re-auction",
            event.creativeId.value,
            event.campaignId.value
          )
          reauctionForCampaign(event.campaignId, "Creative reactivated")
        } else {
          // Creative paused - remove from ServeIndex AND trigger re-auction
          // so other campaigns/creatives can fill the slot
          ctx.log.info(
            "🎨 CreativePaused: creative={} campaign={} - removing from ServeIndex and triggering re-auction",
            event.creativeId.value,
            event.campaignId.value
          )
          adServer ! AdServer.CreativePaused(event.creativeId)
          reauctionForCampaign(event.campaignId, "Creative paused")
        }
      } else {
        ctx.log.debug(
          "🎨 CreativeStatusChanged: creative={} campaign={} isActive={} - ignoring (campaign not on this site)",
          event.creativeId.value,
          event.campaignId.value,
          event.isActive
        )
      }
      Behaviors.same

  }
  // Cache the last classification & slots per URL so we can re-auction without a re-scrape
  private var lastPage: Map[URL, (Map[String, Double], List[AdSlotSpec], Instant)] = Map.empty
  // Cache last known ranker weights per category (weight, when observed)
  // Used for timeout fallback during auction ranking
  private var lastQuote: Map[String, (Double, Instant)] = Map.empty
  // Track which campaigns have won auction slots on this site (campaignId -> URLs awarded)
  // Used to filter CampaignChanged/CreativeStatusChanged events - only re-auction if the campaign has won here
  private var awardedCampaigns: Map[CampaignId, Set[URL]] = Map.empty

  // Campaigns whose config the advertiser explicitly changed recently
  // (CampaignChanged received). For the next few auction rounds their absence
  // from a result is AUTHORITATIVE — carried to AdServer on
  // CandidatesCollected.authoritativeAbsent so orphan preservation doesn't
  // keep a stale entry (old CPM) serving after e.g. a bid drop below the
  // floor. Time-bounded: the window only needs to outlive the 1s re-auction
  // debounce + bid fanout; expiring it keeps the budget-exhaustion
  // protection (the reason orphan preservation exists) intact afterwards.
  // In-memory only — lost on entity restart, backstopped by the ServeView TTL.
  private var authoritativeRefresh: Map[CampaignId, Instant] = Map.empty

  /** Prune expired marks and return the campaigns whose absence from the
    * current auction result is authoritative. */
  private def currentAuthoritativeAbsent(): Set[CampaignId] = {
    val cutoff = Instant.now.minusSeconds(AuctioneerEntity.AuthoritativeRefreshWindow.toSeconds)
    authoritativeRefresh = authoritativeRefresh.filter { case (_, at) => at.isAfter(cutoff) }
    authoritativeRefresh.keySet
  }
  // Track campaign -> advertiser mapping for cleanup (when campaign evicted, check if advertiser still has campaigns)
  private var campaignToAdvertiser: Map[CampaignId, AdvertiserId] = Map.empty
  // Ref-counted set of advertisers with awarded campaigns on this site
  // Used to filter AdvertiserBudgetReset/Exhausted events - only re-auction if advertiser is relevant
  // Incremented when a new campaign is awarded, decremented on cleanup
  private var advertiserCampaignCount: Map[AdvertiserId, Int] = Map.empty
  // Timer scheduler reference, set when serving() initializes timers
  private var _timers: Option[org.apache.pekko.actor.typed.scaladsl.TimerScheduler[Messages]] = None
  // Floor CPM — starts from config, can be updated at runtime by publisher
  private var currentFloorCpm: CPM = cfg.floorCpm
  // Per-category floors from SiteEntity. Empty until learned →
  // every category falls back to `currentFloorCpm` (legacy single-floor).
  private var currentCategoryFloors: Map[CategoryId, CPM] = Map.empty
  // Per-slot floor overrides. Formerly populated by a removed RL agent's
  // learned per-slot multipliers; the sweep optimizer emits no per-slot
  // overrides, so this stays empty and the per-slot floor falls through
  // to the crawler prior / admin override. Retained as a no-op hook
  // rather than threading a removal through the floor-resolution path.
  private var slotFloorOverrides: Map[SlotId, CPM] = Map.empty
  // Admin-set per-slot overrides (escape hatch). SiteEntity pushes
  // these on every admin edit and on startup from persisted state.
  // Take precedence over RL overrides at scoring time.
  private var adminSlotFloorOverrides: Map[SlotId, CPM] = Map.empty
  // Admin overrides carried on AdSlotSpec (the classify-page path's
  // equivalent source). Recorded at fan-out so finishAuction can resolve
  // the slot's effective admin floor with the same precedence the bid
  // admission used, and forward it to AdServer — the serve-time floor
  // gate must agree with the auction-time one or an approved winner
  // gets silently blocked at serve.
  private var slotSpecAdminFloors: Map[SlotId, CPM] = Map.empty
  // Track empty auction retry attempts per URL+slot
  private var emptyAuctionRetries: Map[String, Int] = Map.empty

  /** Schedule a debounced re-auction. Multiple calls within 1 second are coalesced into one. */
  private def scheduleReauction(): Unit =
    _timers.foreach(_.startSingleTimer(DebouncedReauction, DebouncedReauction, 1.second))

  private def fanOutBidRequests(url: URL, categoryCandidates: List[String], slots: List[AdSlotSpec]): Unit = {
    if (categoryCandidates.nonEmpty) {
      // Expand each detected category to (itself ∪ its 3.0 ancestors) and
      // dedup. Campaigns register their declared targets (e.g. "Sports" 483)
      // and the LLM often returns more-specific descendants (e.g. "Baseball"
      // 545). Without ancestor fan-out the Bidder[545] shard is asked but has
      // no campaigns, while the Bidder[483] shard — where the campaign
      // actually lives — never gets a request. Expanding here delivers the
      // bid request to every shard a parent-targeting campaign could be
      // registered under.
      val expandedCandidates: List[String] = categoryCandidates.flatMap { c =>
        c +: TieredCategory.getAncestors(c).map(_.id)
      }.distinct
      slots.foreach { slot =>
        val slotSizes = normalizeSizes((slot.declaredSizes :+ slot.computedSize).toSet)
        // Per-slot effective floor priority (most authoritative first):
        //   1. Admin override   (publisher escape hatch — adminSlotFloorOverrides)
        //   2. Admin override carried on the AdSlotSpec (path used by
        //      the external classify-page endpoint; equivalent source)
        //   3. RL-learned override (slotFloorOverrides)
        //   4. Site floor scaled by crawler prior
        //   5. Site floor
        val adminOverride = adminSlotFloorOverrides.get(slot.slotId)
                              .orElse(slot.floorOverride)
        // Remember the spec-carried override (or its absence) so
        // finishAuction resolves the same effective admin floor this
        // admission used. Presence updates, absence clears — a removed
        // override must stop shielding the slot at serve time.
        slotSpecAdminFloors = slot.floorOverride match {
          case Some(f) => slotSpecAdminFloors.updated(slot.slotId, f)
          case None    => slotSpecAdminFloors - slot.slotId
        }
        val rlOverride    = slotFloorOverrides.get(slot.slotId)
        // Per-category effective floor: the category's learned floor (or the
        // site floor when absent) scaled by the slot's quality prior. The
        // admin/RL override still takes precedence inside `effectiveFloor`.
        def floorForCategory(category: String): CPM =
          SiteEntity.effectiveFloor(
            SiteEntity.AdSlotConfig(
              slotId        = slot.slotId.value,
              width         = slot.computedSize.width,
              height        = slot.computedSize.height,
              prior         = slot.prior,
              floorOverride = adminOverride.orElse(rlOverride),
            ),
            currentCategoryFloors.getOrElse(CategoryId(category), currentFloorCpm),
          )

        ctx.log.debug(
          "📨 Sending bid requests: url={} slot={} categories={} (expanded from {}) site={} perCat={} (prior={})",
          url,
          slot.slotId,
          expandedCandidates.size,
          categoryCandidates.size,
          currentFloorCpm,
          currentCategoryFloors.size: java.lang.Integer,
          slot.prior.map(p => f"q=${p.qualityScore}%.2f region=${p.region}").getOrElse("none"),
        )

        ctx.spawnAnonymous(
          Aggregator[CategoryBidResponse, CandidatesCollected](
            sendRequests = { aggReply =>
              expandedCandidates.foreach { category =>
                val entityId = CategoryBidderEntity.entityIdFor(category, siteId)
                shardRef(CategoryBidderEntity.TypeKey, entityId) ! CategoryBidRequest(
                  siteId   = siteId,
                  url      = url.value,
                  slotId   = slot.slotId,
                  sizes    = slotSizes,
                  floorCpm = floorForCategory(category),
                  replyTo  = aggReply
                )
              }
            },
            expectedReplies = expandedCandidates.size,
            replyTo         = ctx.self,
            aggregateReplies = { responses =>
              val candidates = for {
                r           <- responses
                campaignBid <- r.campaigns
                creative    <- campaignBid.creatives
              } yield Candidate(
                creativeId        = creative.id,
                campaignId        = campaignBid.campaignId,
                advertiserId      = campaignBid.advertiserId,
                cpm               = campaignBid.cpm,
                category          = r.categoryId,
                preApproved       = false,
                adProductCategory = campaignBid.adProductCategory,
                landingDomain     = campaignBid.landingDomain,
                maxCpm            = campaignBid.maxCpm,
              )
              val totalFloorRejects = responses.collect {
                case r: CategoryBidResponse => r.rejectedByFloor
              }.sum
              val maxRejectedCpm = responses.iterator.collect {
                case r: CategoryBidResponse => r.maxRejectedCpm
              }.maxOption.getOrElse(0.0)
              // Lowest across categories, ignoring zero sentinels (categories
              // with no below-floor rejects report 0.0).
              val minRejectedCpm = responses.iterator.collect {
                case r: CategoryBidResponse if r.minRejectedCpm > 0.0 => r.minRejectedCpm
              }.minOption.getOrElse(0.0)
              // APPROVED-ONLY aggregates for the floor-learning outcome:
              // campaigns with a publisher-approved creative on this site.
              // Pending bids still become candidates above (that's how they
              // reach the approval queue) but are invisible to floors.
              val approvedCampaignIds = responses.iterator.flatMap {
                case r: CategoryBidResponse =>
                  r.campaigns.iterator.filter(_.hasApprovedCreative).map(_.campaignId)
              }.toSet
              val approvedFloorRejects = responses.collect {
                case r: CategoryBidResponse => r.approvedRejectedByFloor
              }.sum
              val maxApprovedRejectedCpm = responses.iterator.collect {
                case r: CategoryBidResponse => r.maxApprovedRejectedCpm
              }.maxOption.getOrElse(0.0)
              val minApprovedRejectedCpm = responses.iterator.collect {
                case r: CategoryBidResponse if r.minApprovedRejectedCpm > 0.0 => r.minApprovedRejectedCpm
              }.minOption.getOrElse(0.0)
              // Per-category bounds: mirror the
              // aggregate computation above but keyed by category, folding
              // each category's qualifying bids with its own below-floor
              // rejects so the per-category sweep range stays unbiased by the
              // current floor. APPROVED-ONLY throughout — this is what each
              // category's floor optimizer (monopoly path + sweep range)
              // reads, and a pending sole bidder must read as ZERO demand.
              val perCategoryBounds =
                responses.collect { case r: CategoryBidResponse =>
                  val approved = r.campaigns.filter(_.hasApprovedCreative)
                  val qual = approved.map(_.cpm.toDouble)
                  val qMax = qual.maxOption.getOrElse(0.0)
                  val qMin = qual.filter(_ > 0.0).minOption.getOrElse(0.0)
                  val maxObs = math.max(qMax, r.maxApprovedRejectedCpm)
                  val minObs = (qMin, r.minApprovedRejectedCpm) match {
                    case (q, rj) if q > 0.0 && rj > 0.0 => math.min(q, rj)
                    case (q, _) if q > 0.0              => q
                    case (_, rj) if rj > 0.0            => rj
                    case _                              => 0.0
                  }
                  // Distinct APPROVED campaigns bidding in this category
                  // (qualifying + below-floor rejects). Lets the floor logic
                  // tell a MONOPOLY category (1 bidder → floor = its bid,
                  // computed directly) from a COMPETITIVE one (≥2 → sweep).
                  // 0 = explicit no-servable-demand → SiteEntity collapses
                  // the floor to the publisher minimum.
                  val bidderCount = approved.map(_.campaignId).distinct.size + r.approvedRejectedByFloor
                  r.categoryId -> (maxObs, minObs, r.approvedRejectedByFloor, bidderCount)
                }.toMap
              CandidatesCollected(
                url, slot.slotId, candidates.toVector, totalFloorRejects, maxRejectedCpm, minRejectedCpm, perCategoryBounds,
                approvedCampaignIds    = approvedCampaignIds,
                approvedFloorRejects   = approvedFloorRejects,
                maxApprovedRejectedCpm = maxApprovedRejectedCpm,
                minApprovedRejectedCpm = minApprovedRejectedCpm,
              )
            },
            timeout = cfg.askTimeout
          )
        )
      }
    }
  }

  /** Filler auction: ask each opted-in campaign directly for a bid,
    * bypassing the category-based CategoryBidderEntity cascade. The
    * sentinel `CategoryId.Filler` is passed as `pageCategory` so
    * CampaignEntity.canBid takes the opt-in carve-out. Survivors
    * flow into the regular `CandidatesCollected` path — they share
    * the `preApproved=false` semantic with every other fresh bid, so
    * the approval queue is what actually gates delivery. */
  private def fanOutFillerBidRequests(
      url: URL,
      fillerCampaigns: Map[CampaignId, AdvertiserId],
      slots: List[AdSlotSpec]
  ): Unit = {
    slots.foreach { slot =>
      val slotSizes = normalizeSizes((slot.declaredSizes :+ slot.computedSize).toSet)
      val floor     = currentFloorCpm

      ctx.log.debug(
        "📨 Filler bid requests: url={} slot={} campaigns={} floor={}",
        url, slot.slotId, fillerCampaigns.size, floor
      )

      ctx.spawnAnonymous(
        Aggregator[CampaignEntity.CampaignBidResponse, CandidatesCollected](
          sendRequests = { aggReply =>
            fillerCampaigns.foreach { case (campaignId, advertiserId) =>
              // CampaignEntity's shard key is "advertiserId|campaignId"
              // (matching how AdvertiserEntity.CreateCampaign registers
              // it). Passing just campaignId.value crashes the entity
              // startup when the ID split fails.
              val entityId = s"${advertiserId.value}|${campaignId.value}"
              shardRef(CampaignEntity.TypeKey, entityId) ! CampaignEntity.CampaignBidRequest(
                siteId       = siteId,
                url          = url.value,
                slotId       = slot.slotId,
                pageCategory = CategoryId.Filler,
                floorCpm     = floor,
                replyTo      = aggReply
              )
            }
          },
          expectedReplies = fillerCampaigns.size,
          replyTo         = ctx.self,
          aggregateReplies = { responses =>
            val candidates = for {
              response <- responses
              if response.eligible
              creative <- response.creatives
            } yield Candidate(
              creativeId        = creative.id,
              campaignId        = response.campaignId,
              advertiserId      = response.advertiserId,
              cpm               = response.cpm,
              category          = CategoryId.Filler,
              preApproved       = false,
              adProductCategory = response.adProductCategory,
              landingDomain     = response.landingDomain,
              maxCpm            = response.maxCpm,
            )
            CandidatesCollected(
              url, slot.slotId, candidates.toVector, 0,
              // Approved-only aggregate for the floor outcome — same
              // semantics as the category path.
              approvedCampaignIds = responses.iterator
                .filter(r => r.eligible && r.hasApprovedCreative)
                .map(_.campaignId)
                .toSet,
            )
          },
          timeout = cfg.askTimeout
        )
      )
    }
  }

  given ExecutionContext = ctx.executionContext

  given Timeout = Timeout(cfg.askTimeout)

  private[auction] def serving(): Behavior[Messages] =
    Behaviors.withTimers { timers =>
      _timers = Some(timers)
      // Start a periodic reauction timer
      timers.startTimerAtFixedRate(PeriodicReauction, cfg.reauctionInterval)

      // Start a cleanup timer (recency-only: remove old content)
      timers.startTimerAtFixedRate(CleanupStaleContent, cfg.cleanupInterval)

      ctx.log.info(
        "Started periodic timers for publisher {}: reauction={}, cleanup={}, contentWindow={}",
        siteId,
        cfg.reauctionInterval,
        cfg.cleanupInterval,
        cfg.contentRecencyWindow
      )

      Behaviors
        .receiveMessage[Messages] {
          case DebouncedReauction =>
            // Debounce expired — fire the actual re-auction
            ctx.self ! PeriodicReauction
            Behaviors.same
          case AuctioneerEntity.RetryEmptyAuction(url, attempt) =>
            ctx.log.debug("🔄 Retrying empty auction for url={} (attempt {}/{})",
              url, attempt, AuctioneerEntity.MaxEmptyAuctionRetries)
            ctx.self ! Reevaluate(url)
            Behaviors.same
          case msg =>
            (pipeline orElse public).applyOrElse(msg, _ => Behaviors.same)
        }
        .receiveSignal { case (_, PostStop) =>
          // Unsubscribe from budget events (use ctx.self with union types)
          budgetEventTopic ! Topic.Unsubscribe(ctx.self)
          // Unsubscribe from campaign changed events (use ctx.self with union types)
          campaignChangedTopic ! Topic.Unsubscribe(ctx.self)
          Behaviors.same
        }
    }

  /** Update category weight cache, evicting oldest if at capacity */
  private def updateQuote(category: String, weight: Double, timestamp: Instant): Unit = {
    lastQuote = {
      if (lastQuote.size >= cfg.maxCategoryQuotes && !lastQuote.contains(category)) {
        lastQuote.minByOption { case (_, (_, ts)) => ts } match {
          case Some((oldCat, _)) => lastQuote.removed(oldCat)
          case None              => lastQuote
        }
      } else lastQuote
    }.updatedWith(category) {
      case existing @ Some((_, existingTs)) if existingTs.isAfter(timestamp) => existing
      case _ => Some((weight, timestamp))
    }
  }

  private def normalizeSizes(sizes: Set[AdSize]): Set[AdSize] = sizes

  /** Launch category ranking via scatter-gather to TaxonomyRankerEntity actors.
    *
    * == Design ==
    * Each category is sharded by (category, siteId) for per-site Thompson Sampling.
    * We send Quote requests to topK categories and aggregate responses within askTimeout.
    *
    * == Timeout Handling ==
    * The Aggregator may return partial results if some TaxonomyRankerEntity actors
    * don't respond in time. This typically happens when:
    *   - Entity was passivated and must replay events from journal to rebuild state
    *   - Shard rebalancing during cluster scaling
    *   - Network partition or node failure
    *   - GC pause or backpressure on remote node
    *
    * Rather than fail the auction or exclude timed-out categories (which would bias
    * results toward faster-responding categories), we use a fallback strategy:
    *
    * == Fallback Strategy for Timed-out Categories ==
    *   1. If we have a cached quote from a previous response, use it with time-decay
    *   2. If no cache exists, use cfg.priorWeight as an uninformed prior
    *
    * The decay (see [[decayed]]) models our uncertainty about stale data: older cached
    * weights are trusted less, converging toward the prior as age approaches infinity.
    *
    * Note: If the timeout is due to passivation, the cached weight is likely still
    * accurate (the entity had no activity while dormant). However, we can't distinguish
    * passivation from other causes, so we conservatively apply decay regardless.
    */
  private def startRanking(
      url: URL,
      categoryScores: Map[String, Double],
      slots: List[AdSlotSpec]
  ): Unit = {
    val topK = categoryScores.toList.sortBy(-_._2).take(cfg.topK).toMap
    if (topK.nonEmpty) {
      ctx.spawnAnonymous(
        Aggregator[Quoted, PageCategoriesRanked](
          sendRequests = { replyQuoted =>
            topK.foreach { case (category, _) =>
              // Shard by (category, siteId) for per-site category performance
              val entityId = s"$category|${siteId.value}"
              shardRef(TaxonomyRankerEntity.TypeKey, entityId) ! Quote(replyQuoted)
            }
          },
          expectedReplies = topK.size,
          replyTo         = ctx.self,
          aggregateReplies = { replies =>
            val now               = Instant.now
            val categoriesReplied = replies.iterator.map(_.category).toSet
            val timeoutCategories = topK.iterator.map(_._1).filterNot(categoriesReplied.contains)
            val scoredFromReplies = replies.iterator.map { case Quoted(category, w, _) =>
              category -> (topK.getOrElse(category, 0.0) * w)
            }.toList

            // For timed-out categories, compute fallback weights using cached quotes or prior
            val scoredTimeouts = timeoutCategories.map { category =>
              val conf = topK.getOrElse(category, 0.0)
              val fallbackW = lastQuote.get(category) match {
                case Some((w, seenAt)) => decayed(w, seenAt, now)
                case None              => cfg.priorWeight
              }
              category -> (conf * fallbackW)
            }.toList

            // Log a summary of timeouts if any occurred
            // Timeouts affect auction quality - frequent occurrences indicate TaxonomyRanker latency issues
            val timeoutCategoryList = timeoutCategories.toList
            if (timeoutCategoryList.nonEmpty) {
              ctx.log.warn(
                "TaxonomyRanker timeout for {} categories",
                timeoutCategoryList.size
              )
              ctx.log.debug(
                "Timed out categories: {}",
                timeoutCategoryList.mkString(",")
              )
            }
            val scored = (scoredFromReplies ++ scoredTimeouts).sortBy(-_._2)
            PageCategoriesRanked(url, scored, slots, replies)
          },
          timeout = cfg.askTimeout
        )
      )
    }
  }

  private def shardRef[A](key: EntityTypeKey[A], id: String): EntityRef[A] =
    sharding.entityRefFor(key, id)

  /** Apply exponential half-life decay to a cached weight.
    *
    * Models diminishing trust in stale data: a cached Thompson Sampling weight
    * was accurate when recorded, but the underlying distribution evolves as new
    * impressions/clicks arrive. Older weights are less likely to reflect current state.
    *
    * Formula: weight * 0.5^(age / halfLife)
    *   - At age = 0: returns full weight (1.0x)
    *   - At age = halfLife: returns half weight (0.5x)
    *   - At age = 2*halfLife: returns quarter weight (0.25x)
    *   - As age → ∞: returns → 0 (effectively falls back to prior)
    *
    * @param weight the cached Thompson Sampling weight
    * @param seenAt when the weight was recorded
    * @param now    current timestamp
    * @return the decayed weight, reflecting uncertainty proportional to age
    */
  private def decayed(weight: Double, seenAt: Instant, now: Instant): Double = {
    val ageSeconds = java.time.Duration.between(seenAt, now).getSeconds.max(0)
    val hl         = cfg.priorHalfLife.toSeconds.max(1)
    weight * math.pow(0.5, ageSeconds.toDouble / hl.toDouble)
  }

  /** Candidate-free delivery to AdServer for an auction that produced no
    * winners. Carries the same floor context as a normal delivery (site,
    * per-category, and the slot's resolved admin override) so the serve-time
    * floor gate stays in sync with admission even when admission rejects
    * everything. AdServer treats zero-candidate deliveries as floor-sync
    * only and does not modify the ServeIndex.
    */
  private def notifyEmptyAuction(url: URL, slotId: SlotId, hadFloorRejects: Boolean): Unit = {
    val classifiedAt = lastPage.get(url) match {
      case Some((_, _, ts)) => ts
      case None             => Instant.now
    }
    val pageCategories = lastPage.get(url).map(_._1.keySet).getOrElse(Set.empty)
    val slotAdminFloor = adminSlotFloorOverrides.get(slotId).orElse(slotSpecAdminFloors.get(slotId))
    adServer ! AdServer.CandidatesCollected(
      url, slotId, Vector.empty, classifiedAt, cfg.ttl, pageCategories,
      currentFloorCpm, currentCategoryFloors, slotAdminFloor,
      // Marks ride ONLY on evidence-bearing emptiness (below-floor rejects
      // collected). Silent emptiness attaches no marks → AdServer evicts
      // nothing → transport failures can't purge the index.
      authoritativeAbsent = if (hadFloorRejects) currentAuthoritativeAbsent() else Set.empty)
  }

  private def completeAuction(url: URL, slotId: SlotId, ordered: Vector[Candidate]): Unit = {
    // Get classification timestamp from cache (for recency validation)
    val classifiedAt = lastPage.get(url) match {
      case Some((_, _, ts)) => ts
      case None             => Instant.now
    }

    // Track which campaigns won slots for this URL (for targeted re-auction filtering).
    // Memory is naturally bounded: campaigns are removed when all their URLs expire from lastPage.
    ordered.foreach { candidate =>
      val isNew = !campaignToAdvertiser.contains(candidate.campaignId)
      awardedCampaigns = awardedCampaigns.updatedWith(candidate.campaignId) {
        case Some(urls) => Some(urls + url)
        case None       => Some(Set(url))
      }
      campaignToAdvertiser = campaignToAdvertiser + (candidate.campaignId -> candidate.advertiserId)
      if (isNew) {
        advertiserCampaignCount = advertiserCampaignCount.updatedWith(candidate.advertiserId) {
          case Some(n) => Some(n + 1)
          case None    => Some(1)
        }
      }
    }

    // Send candidates to AdServer for processing (fire-and-forget)
    // AdServer handles: clearing serve cache, routing pre-approved to DData, queuing pending for approval
    ctx.log.info(
      "🎯 Auction COMPLETE: site={} url={} slot={} winners={} (top cpm={})",
      siteId,
      url,
      slotId,
      ordered.size,
      ordered.headOption.map(_.cpm).getOrElse(CPM(0.0))
    )
    val pageCategories = lastPage.get(url).map(_._1.keySet).getOrElse(Set.empty)
    // Resolve the slot's admin floor with the SAME precedence bid admission
    // used (admin map, then spec-carried) so AdServer's serve-time gate
    // agrees with the auction-time one for this slot.
    val slotAdminFloor = adminSlotFloorOverrides.get(slotId).orElse(slotSpecAdminFloors.get(slotId))
    adServer ! AdServer.CandidatesCollected(url, slotId, ordered, classifiedAt, cfg.ttl, pageCategories, currentFloorCpm, currentCategoryFloors, slotAdminFloor,
      authoritativeAbsent = currentAuthoritativeAbsent())
  }

  // AdServer entity for this publisher - receives candidates
  private def adServer: EntityRef[AdServer.Command] =
    sharding.entityRefFor(AdServer.TypeKey, siteId.value)

  /** Re-auction pages where a specific campaign has been awarded slots */
  private def reauctionForCampaign(campaignId: CampaignId, reason: String): Unit =
    awardedCampaigns.get(campaignId) match {
      case Some(affectedUrls) =>
        // Filter to URLs that are still in lastPage (not cleaned up)
        val validUrls = affectedUrls.filter(lastPage.contains)
        if (validUrls.nonEmpty) {
          validUrls.foreach { url =>
            ctx.self ! Reevaluate(url)
          }
          ctx.log.info(
            "{}: campaign={}, triggering re-auction for {} affected pages",
            reason,
            campaignId,
            validUrls.size
          )
        }
      case None =>
        ctx.log.debug(
          "{}: campaign={} not tracked, skipping re-auction",
          reason,
          campaignId
        )
    }
}
