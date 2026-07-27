package promovolve.auction

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityRef, EntityTypeKey }
import org.apache.pekko.util.Timeout
import promovolve.*
import promovolve.advertiser.{ AdvertiserEntity, CampaignEntity }
import promovolve.common.Aggregator
import promovolve.publisher.CategoryDemandRepo

import scala.concurrent.duration.*

/**
 * CategoryBidderEntity
 *
 * Virtual shards per taxonomy category. Acts as a router/aggregator over active campaigns that
 * target this category. On BidRequest, it fans out to CampaignEntity actors and returns the
 * highest eligible bids within a CPM threshold.
 *
 * == Virtual Sharding ==
 * Entity ID format: "categoryId|shardIndex" (e.g., "IAB1|0", "IAB1|1", "IAB1|2")
 * This distributes load for hot categories across N actors instead of one.
 * All virtual shards for a category receive the same campaign list from CampaignDirectory.
 */
object CategoryBidderEntity {

  // ----------- Sharding key ------------
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("category-bidder")

  /** Number of virtual shards per category (distributes load for hot categories) */
  val NumVirtualShards: Int = 5

  /** Compute entity ID for a category and site combination */
  def entityIdFor(category: String, siteId: SiteId): String = {
    val shardIndex = math.abs(siteId.value.hashCode % NumVirtualShards)
    s"$category|$shardIndex"
  }

  /** Compute all entity IDs for a category (for broadcasting) */
  def allEntityIdsFor(category: String): Seq[String] = (0 until NumVirtualShards).map(i => s"$category|$i")

  /** Parse entity ID to extract category (strips shard index) */
  def parseCategoryId(entityId: String): CategoryId = {
    val idx = entityId.lastIndexOf('|')
    if (idx > 0) CategoryId(entityId.substring(0, idx))
    else CategoryId(entityId) // Fallback for legacy format
  }

  // ----------- Behavior ------------
  def apply(
      entityId: String, // Compound ID like "IAB1|2" (category|shardIndex)
      sharding: ClusterSharding,
      categoryDemandRepo: CategoryDemandRepo,
      // MUST be strictly below AuctioneerEntity.Settings.askTimeout (800ms):
      // this window starts a hop later, so an equal budget meant one silent
      // campaign pushed the bidder's partial reply past the auctioneer's
      // window — the whole category silently vanished from the auction.
      // 550ms leaves ~250ms of hop/mailbox margin for the partial to land.
      askTimeout: FiniteDuration = 550.millis,
      cpmThresholdPct: Double = 0.80, // Return campaigns within 80% of winner (widened: quality-adjusted pricing handles diverse bids)
      maxCampaignsPerCategory: Int = 50, // Limit campaigns to bound downstream creative evaluation
      // STANDING BID BOOK (docs/design/bid-book.md): answer auctions
      // synchronously from pushed quotes instead of racing live asks
      // against deadlines. Off → legacy live-ask path only.
      bidBookEnabled: Boolean = false
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      given Timeout = Timeout(askTimeout)
      given scala.concurrent.ExecutionContext = ctx.executionContext

      // Parse category from compound entity ID
      val categoryId = parseCategoryId(entityId)

      val entity = new CategoryBidderEntity(
        categoryId,
        sharding,
        categoryDemandRepo,
        askTimeout,
        cpmThresholdPct,
        maxCampaignsPerCategory,
        bidBookEnabled,
        ctx
      )

      // Seed demand from the durable table on startup so this bidder is
      // biddable immediately after a restart, instead of empty until the
      // CampaignDirectory singleton re-pushes (the post-restart ad-dark-window).
      entity.seeding()
    }

  // ----------- Public API ------------
  sealed trait Command extends CborSerializable

  final case class CategoryBidRequest(
      siteId: SiteId,
      url: String,
      slotId: SlotId,
      sizes: Set[AdSize],
      floorCpm: CPM,
      replyTo: ActorRef[CategoryBidResponse]
  ) extends Command

  final case class CampaignBid(
      campaignId: CampaignId,
      advertiserId: AdvertiserId,
      creatives: Set[AdvertiserEntity.Creative],
      cpm: CPM, // quoted bid = max(maxCpm, floor) — equals maxCpm, see Candidate.cpm
      maxCpm: CPM = CPM.zero, // advertiser's max CPM (for ServeIndex)
      adProductCategory: Option[AdProductCategoryId] = None,
      landingDomain: String = "",
      // Campaign has ≥1 publisher-APPROVED creative on this site (from
      // CampaignBidResponse). Approved bids teach the floor; pending
      // bids only compete for the approval queue.
      hasApprovedCreative: Boolean = false
  )

  final case class CategoryBidResponse(
      categoryId: CategoryId,
      campaigns: Vector[CampaignBid],
      rejectedByFloor: Int = 0,
      // Highest CPM among campaigns whose bid was rejected for being
      // below the current floor. 0.0 when no below-floor rejections. Used
      // by AuctioneerEntity to report a pre-floor max-bid signal to the
      // publisher floor optimizer, which would otherwise see only bids
      // that already cleared the floor.
      maxRejectedCpm: Double = 0.0,
      // Lowest CPM among below-floor rejects. 0.0 when none. Symmetric
      // counterpart to `maxRejectedCpm`: it lets the floor optimizer's
      // next-cycle sweep range extend downward to include rejected
      // bidders, otherwise the range collapses to the qualifying-min
      // and the rejected campaigns are permanently excluded from
      // future cycles.
      minRejectedCpm: Double = 0.0,
      // APPROVED-ONLY variants of the reject stats above, restricted to
      // campaigns with ≥1 publisher-approved creative on this site. The
      // floor optimizer reads THESE (pending demand must not teach the
      // floor); the unfiltered fields keep their original semantics for
      // the eviction-evidence machinery, which cares that *anyone* was
      // priced out, approved or not.
      approvedRejectedByFloor: Int = 0,
      maxApprovedRejectedCpm: Double = 0.0,
      minApprovedRejectedCpm: Double = 0.0
  ) extends promovolve.CborSerializable {
    def eligible: Boolean = campaigns.nonEmpty
  }

  /** Active campaigns with their advertiser IDs (for entity sharding lookup) */
  final case class ActiveCampaigns(
      campaigns: Map[CampaignId, AdvertiserId],
      replyTo: ActorRef[ActiveCampaignsAck]
  ) extends Command

  /** Acknowledgment that campaigns have been received */
  final case class ActiveCampaignsAck(categoryId: CategoryId) extends CborSerializable

  /** Internal: durable demand seed loaded on startup (campaignId -> advertiserId, as strings). */
  private case class SeedLoaded(seed: Map[String, String]) extends Command

  /** Internal: durable demand seed failed to load — start empty, await the push. */
  private case class SeedFailed(reason: String) extends Command

  /** Internal: category_demand cross-check after a registry-shrinking push. */
  private case class ReconcileLoaded(rows: Map[String, String]) extends Command

  /** Internal: cross-check read failed — keep the pushed registry as-is. */
  private case class ReconcileFailed(reason: String) extends Command

  /**
   * STANDING BID BOOK quote, pushed by CampaignEntity (on material change,
   * on a 60s heartbeat, and in reply to RequestQuote). Site-agnostic: the
   * per-site facts (creative approval, allowlist) are derived at answer
   * time from the carried creatives/allowlist. quotedAtMs drives the
   * staleness ladder — slow campaigns cost freshness, never presence.
   */
  final case class BidQuote(
      campaignId: CampaignId,
      advertiserId: AdvertiserId,
      maxCpm: CPM,
      creatives: Set[AdvertiserEntity.Creative],
      adProductCategory: Option[AdProductCategoryId],
      landingDomain: String,
      siteAllowlist: Set[String],
      eligible: Boolean, // status Active && schedule live, at quote time
      quotedAtMs: Long
  ) extends Command

  /** Quote older than this logs BOOK-STALE (still served). */
  val BookSoftStaleMs: Long = 3L * 60 * 1000

  /** Quote older than this is excluded — a silent campaign IS absent demand. */
  val BookHardTtlMs: Long = 10L * 60 * 1000

  // (campaignId, advertiserId, creatives, cpm, maxCpm, adProductCategory, landingDomain, hasApprovedCreative)
  private type Collected = Vector[(CampaignId, AdvertiserId, Set[AdvertiserEntity.Creative], CPM, CPM,
      Option[AdProductCategoryId], String, Boolean)]

  extension (collected: Collected) {

    /**
     * Select campaigns within CPM threshold, limited to top N by CPM.
     *
     * - Find maximum CPM across collected campaigns.
     * - Keep campaigns whose CPM is within (1 - cpmThresholdPct) of the winner.
     * - Sort by CPM descending and take top `maxCampaigns`.
     */
    def selectCampaigns(
        cpmThresholdPct: Double,
        maxCampaigns: Int
    ): Vector[CampaignBid] =
      if (collected.isEmpty) Vector.empty
      else {
        val topCpm = collected.map(_._4.value).max
        val threshold = topCpm * (1.0 - cpmThresholdPct)

        collected
          .filter { case (_, _, creatives, cpm, _, _, _, _) => cpm.value >= threshold && creatives.nonEmpty }
          .sortBy { case (_, _, _, cpm, _, _, _, _) => -cpm.value }
          .take(maxCampaigns)
          .map { case (campaignId, advertiserId, creatives, cpm, maxCpm, adProductCat, landingDomain, hasApproved) =>
            CampaignBid(campaignId, advertiserId, creatives, cpm, maxCpm, adProductCat, landingDomain, hasApproved)
          }
      }
  }
}

private final class CategoryBidderEntity(
    categoryId: CategoryId,
    sharding: ClusterSharding,
    categoryDemandRepo: CategoryDemandRepo,
    askTimeout: FiniteDuration,
    cpmThresholdPct: Double,
    maxCampaignsPerCategory: Int,
    bidBookEnabled: Boolean,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[CategoryBidderEntity.Command]
)(using timeout: Timeout, ec: scala.concurrent.ExecutionContext) {

  import CategoryBidderEntity.*

  /**
   * Startup state: load this category's demand from the durable table, stashing
   * bid requests until it lands, then transition to `serving`. A live
   * `ActiveCampaigns` push arriving first wins (it's authoritative and fresher
   * than the table). Either way the dark window is gone: we never sit empty
   * waiting for the singleton.
   */
  /**
   * Synchronous auction answer from the standing bid book. Replicates the
   * legacy per-campaign eligibility exactly, minus the race:
   *  - allowlist: nonEmpty allowlist must contain the requesting site
   *  - creatives: isEligibleFor(siteId) (active + not rejected there)
   *  - floor: qualify when maxCpm ≥ floor; bid = max(maxCpm, floor)
   *    (same formula the campaign used); below-floor entries feed the
   *    approved/unfiltered reject stats the floor optimizer reads
   *  - budget is deliberately NOT gated here: TryReserve at serve time is
   *    the authoritative money gate; a stale-eligible quote costs one
   *    declined reservation, never a wrong charge
   * Staleness ladder: entries older than BookHardTtlMs are excluded (a
   * silent campaign IS absent demand — floors and liveness must see it);
   * older than BookSoftStaleMs serve but WARN (repeat-per-auction alarm).
   */
  private def answerFromBook(
      siteId: SiteId,
      floorCpm: CPM,
      replyTo: ActorRef[CategoryBidResponse],
      activeCampaigns: Map[CampaignId, AdvertiserId],
      bidBook: Map[CampaignId, BidQuote]
  ): Unit = {
    val nowMs = System.currentTimeMillis()
    val entries = activeCampaigns.keysIterator.flatMap(bidBook.get).toVector

    val (live, expired) = entries.partition(e => nowMs - e.quotedAtMs <= BookHardTtlMs)
    if (expired.nonEmpty)
      ctx.log.warn(
        "BOOK-EXPIRED: cat={} dropping {} quote(s) older than {}m [{}] — campaigns silent, treating as absent demand",
        categoryId.value,
        expired.size: java.lang.Integer,
        (BookHardTtlMs / 60000): java.lang.Long,
        expired.map(_.campaignId.value).mkString(",")
      )
    val stale = live.filter(e => nowMs - e.quotedAtMs > BookSoftStaleMs)
    if (stale.nonEmpty)
      ctx.log.warn(
        "BOOK-STALE: cat={} serving {} quote(s) older than {}m [{}] — check the owning campaign entities",
        categoryId.value,
        stale.size: java.lang.Integer,
        (BookSoftStaleMs / 60000): java.lang.Long,
        stale.map(_.campaignId.value).mkString(",")
      )

    val admissible = live.filter { e =>
      e.eligible && (e.siteAllowlist.isEmpty || e.siteAllowlist.contains(siteId.value))
    }
    def eligibleCreatives(e: BidQuote) = e.creatives.filter(_.isEligibleFor(siteId))
    def hasApproved(e: BidQuote) =
      e.creatives.exists(c => c.isActive && c.isApprovedFor(siteId))

    val (qualifying0, belowFloor) = admissible.partition(_.maxCpm.toDouble >= floorCpm.toDouble)

    val collected: Collected = qualifying0.flatMap { e =>
      val creatives = eligibleCreatives(e)
      if (creatives.isEmpty) None // NoCreatives — same exclusion as legacy
      else
        Some((e.campaignId, e.advertiserId, creatives, CPM.max(e.maxCpm, floorCpm), e.maxCpm,
          e.adProductCategory, e.landingDomain, hasApproved(e)))
    }
    val qualifying = collected.selectCampaigns(cpmThresholdPct, maxCampaignsPerCategory)

    val rejCpms = belowFloor.map(_.maxCpm.toDouble).filter(_ > 0.0)
    val approvedBelow = belowFloor.filter(hasApproved)
    val apprCpms = approvedBelow.map(_.maxCpm.toDouble).filter(_ > 0.0)

    replyTo ! CategoryBidResponse(
      categoryId,
      qualifying,
      rejectedByFloor = belowFloor.size,
      maxRejectedCpm = rejCpms.maxOption.getOrElse(0.0),
      minRejectedCpm = rejCpms.minOption.getOrElse(0.0),
      approvedRejectedByFloor = approvedBelow.size,
      maxApprovedRejectedCpm = apprCpms.maxOption.getOrElse(0.0),
      minApprovedRejectedCpm = apprCpms.minOption.getOrElse(0.0)
    )
  }

  def seeding(): Behavior[Command] =
    Behaviors.withStash(1000) { buffer =>
      ctx.pipeToSelf(categoryDemandRepo.listByCategory(categoryId.value)) {
        case scala.util.Success(rows) => SeedLoaded(rows.toMap)
        case scala.util.Failure(e)    => SeedFailed(e.getMessage)
      }
      // SEED DEADLINE: a hung seed query (starved pod, exhausted pool)
      // used to leave this state stashing bid requests silently until
      // stash overflow — an actor alive-but-unhandleable, the wedge
      // shape. Fail open to an empty registry instead: the directory's
      // per-minute reconcile push repopulates it. If the real seed (or
      // this timer's twin) arrives after promotion, serving() drops the
      // stale message safely.
      ctx.scheduleOnce(
        scala.concurrent.duration.DurationInt(10).seconds,
        ctx.self,
        SeedFailed("seed deadline (10s) exceeded — repo call still pending")
      )
      Behaviors.receiveMessage {
        case SeedLoaded(seed) =>
          ctx.log.info(
            "CategoryBidder[{}] seeded {} campaigns from category_demand",
            categoryId.value, seed.size: java.lang.Integer
          )
          val members = seed.map { case (c, a) => CampaignId(c) -> AdvertiserId(a) }
          // Bid-book warm-up: tell+tell handshake — each member pushes a
          // quote back within milliseconds; until quotes land, requests
          // fall back to the legacy live-ask path (hybrid, no dark window).
          if (bidBookEnabled) members.foreach { case (cid, adv) =>
            scala.util.Try(entityFor(adv, cid) ! CampaignEntity.RequestQuote)
          }
          buffer.unstashAll(serving(members))
        case SeedFailed(reason) =>
          ctx.log.warn(
            "CategoryBidder[{}] demand seed failed ({}); starting empty until pushed",
            categoryId.value, reason
          )
          buffer.unstashAll(serving(Map.empty))
        case ac: ActiveCampaigns =>
          // A live push beat the seed — it's authoritative; use it, drop the seed.
          ac.replyTo ! ActiveCampaignsAck(categoryId)
          buffer.unstashAll(serving(ac.campaigns))
        case other =>
          buffer.stash(other)
          Behaviors.same
      }
    }

  /** @param activeCampaigns Map of campaignId -> advertiserId for entity sharding lookup */
  def serving(
      activeCampaigns: Map[CampaignId, AdvertiserId] = Map.empty,
      bidBook: Map[CampaignId, BidQuote] = Map.empty
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case q: BidQuote =>
        // Standing bid book update. Accept quotes even for campaigns not
        // (yet) in the registry — membership can lag a quote by a push
        // cycle; the answer path intersects with the registry anyway.
        serving(activeCampaigns, bidBook.updated(q.campaignId, q))

      case ActiveCampaigns(campaigns, replyTo) =>
        replyTo ! ActiveCampaignsAck(categoryId)
        val removed = activeCampaigns.keySet -- campaigns.keySet
        // Registry replaced: campaigns newly added without a quote get one
        // requested immediately (tell+tell — no ask, no deadline).
        if (bidBookEnabled) {
          (campaigns.keySet -- bidBook.keySet).foreach { cid =>
            campaigns.get(cid).foreach { adv =>
              scala.util.Try(entityFor(adv, cid) ! CampaignEntity.RequestQuote)
            }
          }
        }
        if (removed.nonEmpty) {
          // A shrinking push is either a real leave (pause/delete — the
          // CampaignEntity also removes its category_demand rows) or a
          // PARTIAL push from a directory that lost state in a roll —
          // the silent no-bid failure mode. WARN (visible at the prod
          // log level) and cross-check the durable table: campaigns it
          // still lists get merged back. Over-inclusion is safe (a
          // paused campaign answers ineligible at bid time);
          // under-inclusion silently kills its delivery in this category.
          ctx.log.warn(
            "CategoryBidder[{}] push shrinks registry {} -> {} (removed: {}); cross-checking category_demand",
            categoryId.value,
            activeCampaigns.size: java.lang.Integer,
            campaigns.size: java.lang.Integer,
            removed.map(_.value).mkString(",")
          )
          ctx.pipeToSelf(categoryDemandRepo.listByCategory(categoryId.value)) {
            case scala.util.Success(rows) => ReconcileLoaded(rows.toMap)
            case scala.util.Failure(e)    => ReconcileFailed(e.getMessage)
          }
        } else if (campaigns.keySet != activeCampaigns.keySet) {
          ctx.log.debug(
            "CategoryBidder[{}] push grows registry {} -> {}",
            categoryId.value,
            activeCampaigns.size: java.lang.Integer,
            campaigns.size: java.lang.Integer
          )
        }
        serving(campaigns, bidBook)

      case ReconcileLoaded(rows) =>
        val restored = rows.collect {
          case (c, a) if !activeCampaigns.contains(CampaignId(c)) =>
            CampaignId(c) -> AdvertiserId(a)
        }
        if (restored.nonEmpty) {
          ctx.log.warn(
            "CategoryBidder[{}] PARTIAL PUSH detected — restored {} campaign(s) still in category_demand: {}",
            categoryId.value,
            restored.size: java.lang.Integer,
            restored.keys.map(_.value).mkString(",")
          )
          serving(activeCampaigns ++ restored, bidBook)
        } else Behaviors.same

      case ReconcileFailed(reason) =>
        ctx.log.warn(
          "CategoryBidder[{}] category_demand cross-check failed ({}); keeping pushed registry",
          categoryId.value, reason
        )
        Behaviors.same

      case CategoryBidRequest(siteId, url, slotId, sizes, floorCpm, replyTo)
          if bidBookEnabled && activeCampaigns.nonEmpty &&
          activeCampaigns.keySet.forall(bidBook.contains) =>
        // STANDING BID BOOK: answer synchronously — no aggregator, no
        // window, no race. Late information is staleness (disclosed),
        // never absence (docs/design/bid-book.md).
        answerFromBook(siteId, floorCpm, replyTo, activeCampaigns, bidBook)
        Behaviors.same

      case CategoryBidRequest(siteId, url, slotId, sizes, floorCpm, replyTo) =>
        // Hybrid fallback: book disabled, or registry members still missing
        // quotes (fresh bidder, brand-new campaign). Request the missing
        // quotes so the NEXT auction answers from the book, and serve this
        // one on the legacy live-ask path.
        if (bidBookEnabled) {
          (activeCampaigns.keySet -- bidBook.keySet).foreach { cid =>
            activeCampaigns.get(cid).foreach(adv =>
              scala.util.Try(entityFor(adv, cid) ! CampaignEntity.RequestQuote))
          }
        }
        if (activeCampaigns.isEmpty) {
          ctx.log.debug(
            "CategoryBidder[{}] has no active campaigns for site={}",
            categoryId,
            siteId
          )
          replyTo ! CategoryBidResponse(categoryId, Vector.empty)
          Behaviors.same
        } else {
          ctx.log.debug(
            "CategoryBidder[{}] processing bid: site={} slot={} campaigns={}",
            categoryId,
            siteId,
            slotId,
            activeCampaigns.size
          )
          val campaignRefs = activeCampaigns.map { case (campaignId, advertiserId) =>
            entityFor(advertiserId, campaignId)
          }
          // Capture logger on actor thread — safe to use from Aggregator's thread
          val log = ctx.log
          // For CAMPAIGN-LATE detection: registry membership + window start,
          // captured for the aggregator thread.
          val expectedCampaigns = activeCampaigns.keySet
          val windowStartMs = System.currentTimeMillis()
          val windowMs = askTimeout.toMillis
          val bidderCategory = categoryId
          ctx.spawnAnonymous(
            Aggregator[CampaignEntity.CampaignBidResponse, CategoryBidResponse](
              sendRequests = { aggReply =>
                campaignRefs.foreach { ref =>
                  ref ! CampaignEntity.CampaignBidRequest(
                    siteId = siteId,
                    url = url,
                    slotId = slotId,
                    pageCategory = categoryId,
                    floorCpm = floorCpm,
                    replyTo = aggReply
                  )
                }
              },
              expectedReplies = activeCampaigns.size,
              replyTo = replyTo,
              aggregateReplies = { results =>
                // ---- Scorecard: per-campaign outcome ----
                val entries = results.map {
                  case r: CampaignEntity.CampaignBidResponse =>
                    if (r.eligible) s"${r.campaignId.value}:pass(cpm=${r.cpm.toDouble})"
                    else s"${r.campaignId.value}:${r.rejectReason.fold("unknown")(_.toString)}"
                }
                log.debug(
                  "CategoryScorecard cat={} total={} [{}]",
                  categoryId.value, results.size: java.lang.Integer,
                  entries.mkString(", ")
                )

                // CAMPAIGN-LATE: the per-campaign sibling of CATEGORY-SILENT.
                // A campaign whose reply chain exceeds this bidder's window is
                // silently absent from the partial aggregate — it "bids"
                // (BidsToday counts its work) but never reaches an auction,
                // with every dashboard green (5h dark JRA, 2026-07-25: replies
                // formed 1.6s after the auction closed, every cycle). Repeat-
                // per-auction WARN = the alarm; the missing campaign's own
                // late reply lands in dead letters.
                if (results.size < expectedCampaigns.size) {
                  val replied = results.iterator.map(_.campaignId).toSet
                  val missing = expectedCampaigns -- replied
                  log.warn(
                    "CAMPAIGN-LATE: cat={} {}/{} replies within {}ms window (started {}ms ago) — missing [{}]; their demand is absent from this auction",
                    bidderCategory.value,
                    results.size: java.lang.Integer,
                    expectedCampaigns.size: java.lang.Integer,
                    windowMs: java.lang.Long,
                    (System.currentTimeMillis() - windowStartMs): java.lang.Long,
                    missing.map(_.value).mkString(",")
                  )
                }
                val collected: Collected =
                  results.collect {
                    case r: CampaignEntity.CampaignBidResponse if r.eligible =>
                      (r.campaignId, r.advertiserId, r.creatives, r.cpm, r.maxCpm, r.adProductCategory, r.landingDomain,
                        r.hasApprovedCreative)
                  }.toVector

                val qualifying =
                  collected.selectCampaigns(cpmThresholdPct, maxCampaignsPerCategory)

                // ---- Scorecard: CPM threshold drops ----
                if (collected.size > qualifying.size) {
                  val qualIds = qualifying.map(_.campaignId).toSet
                  val dropped = collected.filterNot(c => qualIds.contains(c._1))
                  log.debug(
                    "CategoryScorecard cat={} cpmThreshold dropped=[{}]",
                    categoryId.value,
                    dropped.map(c => s"${c._1.value}(cpm=${c._4.toDouble})").mkString(", ")
                  )
                }

                val belowFloor = results.collect {
                  case r: CampaignEntity.CampaignBidResponse
                      if r.rejectReason.contains(CampaignEntity.BidRejectReason.BelowFloor) => r
                }
                val floorRejects = belowFloor.size
                val (maxRejectedCpm, minRejectedCpm) =
                  if (belowFloor.isEmpty) (0.0, 0.0)
                  else {
                    // A BelowFloor rejection replies with cpm = ZERO (it
                    // submits no bid) — the campaign's true willingness to
                    // pay is maxCpm. Reading cpm here reported every reject
                    // as $0, which silently defeated the whole "span
                    // below-floor rejects" design: the monopoly path saw
                    // (1 bidder, bid $0) and refused to compute, so a sole
                    // bidder who lowered their bid under the learned floor
                    // froze that floor at the OLD bid forever (observed
                    // live: floor stuck at $5/$3 while the bid was $2.50).
                    val cpms = belowFloor.iterator.map(_.maxCpm.toDouble).toVector
                    (cpms.max, cpms.min)
                  }
                // Approved-only reject stats: what the floor optimizer reads.
                // A pending campaign priced out by the floor is not demand the
                // floor could ever monetize, so it must not stretch the sweep
                // range or count as a bidder.
                val approvedBelowFloor = belowFloor.filter(_.hasApprovedCreative)
                val (maxApprovedRejCpm, minApprovedRejCpm) =
                  if (approvedBelowFloor.isEmpty) (0.0, 0.0)
                  else {
                    val cpms = approvedBelowFloor.iterator.map(_.maxCpm.toDouble).toVector
                    (cpms.max, cpms.min)
                  }
                CategoryBidResponse(
                  categoryId, qualifying, floorRejects, maxRejectedCpm, minRejectedCpm,
                  approvedRejectedByFloor = approvedBelowFloor.size,
                  maxApprovedRejectedCpm = maxApprovedRejCpm,
                  minApprovedRejectedCpm = minApprovedRejCpm
                )
              },
              timeout = askTimeout
            )
          )

          Behaviors.same
        }

      case _: SeedLoaded | _: SeedFailed =>
        // A late async seed result that arrived AFTER a live ActiveCampaigns
        // push already promoted us to `serving`. The live push is authoritative
        // (see the `loading` state above), so drop the stale seed. Without this
        // case the message hits `serving`'s non-exhaustive match → MatchError →
        // StopSupervisor stops the entity → demand for this whole category dies
        // (surfaces as "registering a site does nothing / no ads").
        Behaviors.same
    }

  /** Entity ID format: advertiserId|campaignId (matches AdvertiserEntity.CreateCampaign) */
  private def entityFor(
      advertiserId: AdvertiserId,
      campaignId: CampaignId
  ): EntityRef[CampaignEntity.Command] =
    sharding.entityRefFor(CampaignEntity.TypeKey, s"${advertiserId.value}|${campaignId.value}")
}
