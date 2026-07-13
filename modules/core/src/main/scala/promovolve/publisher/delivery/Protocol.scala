package promovolve.publisher.delivery

import com.fasterxml.jackson.annotation.JsonAlias
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import promovolve.*
import promovolve.publisher.AssetPointer
import promovolve.publisher.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Protocol definitions for AdServer actor. */
object Protocol {

  // ==================== Commands ====================

  sealed trait Command extends CborSerializable

  /** Cached spend info for pacing decisions */
  final case class CachedSpendInfo(
      advertiserId: AdvertiserId,
      dailyBudget: Budget,
      todaySpend: Spend,
      dayStart: Instant,
      timestamp: Instant
  )

  /** Context for ad selection pipeline - bundles state needed across pacing/selection stages */
  final case class SelectionContext(
      creativeStats: Map[CreativeId, CreativeStats],
      pacingStrategy: PacingStrategy,
      requestArrivalRate: Double,
      pendingSpendByCampaign: Map[CampaignId, (Double, Instant)],
      dayDurationSeconds: Int,
      spendInfoCache: Map[CampaignId, CachedSpendInfo],
      trafficShapeTracker: TrafficShapeTracker,
      lastDayStart: Option[Instant],
      requestCount: Long,
      msSinceLastRequest: Long,
      lastCampaignSet: Set[CampaignId]
  )

  /** Candidates from AuctioneerEntity after auction completes */
  final case class CandidatesCollected(
      url: URL,
      slotId: SlotId,
      candidates: Vector[Candidate],
      classifiedAt: Instant,
      ttl: FiniteDuration,
      pageCategories: Set[String] = Set.empty, // All content categories for this page
      floorCpm: CPM = CPM(0.50), // Site's floor CPM for clearing price floor (fallback)
      // Per-category floors. Empty in
      // off/shadow → serve-time pricing falls back to `floorCpm` for every
      // category (identical to legacy behavior).
      categoryFloors: Map[CategoryId, CPM] = Map.empty,
      // The slot's admin floor override, resolved by the auctioneer with the
      // SAME precedence it used to admit bids (adminSlotFloorOverrides, then
      // the AdSlotSpec-carried override). Serve-time floor gating MUST honor
      // this — otherwise a candidate that legitimately won under the slot's
      // override wins the auction, passes publisher approval, and is then
      // silently blocked at serve by the higher site/category floor.
      // None = slot has no override; AdServer drops any stale entry.
      slotAdminFloor: Option[CPM] = None,
      // Campaigns whose ABSENCE from `candidates` is authoritative: the
      // advertiser explicitly changed the campaign's config (e.g. lowered
      // maxCpm below the floor) shortly before this auction, so a missing
      // creative means "no longer bids", NOT "temporarily out (budget)".
      // AdServer must not orphan-preserve these campaigns' stale entries —
      // otherwise a bid lowered below the floor keeps serving (and being
      // displayed) at its OLD CPM until the ServeView TTL (120min) expires.
      // Reader-pinned creatives still survive (same product decision as
      // EvictCampaignFromSlots). Empty = every absence is ambiguous, keep
      // legacy orphan preservation for all.
      authoritativeAbsent: Set[CampaignId] = Set.empty
  ) extends Command

  /**
   * Sent by the auctioneer the moment a page is classified (matched OR filler),
   * INDEPENDENT of whether the auction drew a bid — so AdServer can record
   * classifiedAt for no-bidder pages and not treat them as cold. Drives the
   * reclassifyInMs freshness token. See docs/design/ON_DEMAND_CLASSIFICATION.md.
   */
  final case class MarkClassified(url: URL, classifiedAt: Instant) extends Command

  /** Approve a pending creative (from publisher dashboard) */
  final case class Approve(
      url: String,
      slot: String,
      creativeId: String,
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ) extends Command

  /** Reject a pending creative (from publisher dashboard) */
  final case class Reject(
      url: String,
      slot: String,
      creativeId: String,
      reason: Option[String],
      replyTo: ActorRef[StatusReply[Done.type]]
  ) extends Command

  /** Approve all pending creatives for a slot (for testing) */
  final case class ApproveAll(
      url: String,
      slot: String,
      replyTo: ActorRef[ApproveAllResult]
  ) extends Command

  /** List pending creatives for this publisher */
  final case class ListPending(replyTo: ActorRef[PendingList]) extends Command

  /** Flag a creative (quarantine it from pending queue) */
  final case class Flag(
      url: String,
      slot: String,
      creativeId: String,
      reason: String,
      replyTo: ActorRef[StatusReply[FlagResult]]
  ) extends Command

  /** Unflag a creative (return it to pending queue) */
  final case class Unflag(
      creativeId: String,
      replyTo: ActorRef[StatusReply[UnflagResult]]
  ) extends Command

  /** List flagged creatives for this publisher */
  final case class ListFlagged(replyTo: ActorRef[FlaggedList]) extends Command

  /** Record an impression for a creative (for per-creative Thompson Sampling) */
  final case class RecordImpression(creativeId: CreativeId) extends Command

  /** Record a click for a creative (for per-creative Thompson Sampling) */
  final case class RecordClick(creativeId: CreativeId) extends Command

  /**
   * Record a fold for a creative — reader explicitly bookmarked it.
   * Feeds Thompson Sampling alongside CTR via an independent Beta
   * posterior. Free engagement signal, no billing impact.
   */
  final case class RecordFold(creativeId: CreativeId) extends Command

  /** Get all per-creative stats (for monitoring/testing) */
  final case class GetCreativeStats(replyTo: ActorRef[CreativeStatsMap]) extends Command

  /** Get serve outcome stats (for monitoring pacing) */
  final case class GetServeStats(replyTo: ActorRef[ServeStats]) extends Command

  // ==================== Batch Select (multi-slot per-page auction) ====================

  /**
   * One slot in a batch select request. Width / height used to
   * match candidates; floorCpm optional per-slot override (currently
   * unused — publisher's site floor applies to all slots).
   *
   * `pin` is a dog-ear hint from the bootstrap — "this slot is pinned to
   * this creativeId in the reader's IndexedDB, honor it if possible".
   * The auction bypass logic lives in `batchReserveWithRetry`.
   */
  final case class BatchSlotSpec(
      slotId: SlotId,
      width: Int,
      height: Int,
      floorCpm: Option[CPM] = None,
      pin: Option[CreativeId] = None
  )

  /**
   * Outcome of attempting to honor a pin hint. `None` on a slot's outcome
   * means the slot carried no pin hint. Today the only reason emitted on
   * a fallthrough is `creative_removed` (the pinned creativeId is no
   * longer in `persistedApprovedIds`); the bootstrap acts on it by
   * deleting the IDB pin row. See `AdServer.dogearFallthrough`.
   */
  final case class DogearOutcome(honored: Boolean, reason: Option[String] = None)

  /**
   * Multi-slot select. The server sees every slot on the page at
   * once and runs a single joint auction: score each candidate once,
   * greedy-pick (slot, candidate) pairs by descending score,
   * enforce per-page-per-campaign cap across the batch. Avoids the
   * "top slot wins everything, bottom slots go empty" pattern of
   * sequential per-slot Selects on the same page.
   */
  final case class BatchSelect(
      url: URL,
      slots: Vector[BatchSlotSpec],
      // Wire alias: pre-rename senders during a rolling deploy still decode.
      @JsonAlias(Array("contentRecencyWindowMs"))
      classificationFreshnessWindowMs: Long,
      replyTo: ActorRef[BatchSelectResult],
      // Pinned creatives the user has saved for slots NOT on this page.
      // Excluded from every slot's auction so the user's pinned creative
      // never serves accidentally in some other slot site-wide.
      excludedCreatives: Set[CreativeId] = Set.empty,
      // Campaigns belonging to off-page pins. The dog-ear is a "save for
      // later" gesture; surfacing other ads from the same advertiser
      // before the reader engages the bookmark would feel like a
      // recommendation system stalking them. Block at campaign level
      // across the whole site, not just the bookmarked creativeId.
      excludedCampaigns: Set[CampaignId] = Set.empty
  ) extends Command

  sealed trait BatchSelectResult extends CborSerializable

  /**
   * Per-slot outcome in a batch response. `winner = None` means
   * the slot could not be filled (no matching candidate, all in pool
   * blocked, etc.). Clearing price + requestId match the single-slot
   * flow's semantics when a winner is present.
   */
  final case class BatchSlotOutcome(
      slotId: SlotId,
      winner: Option[CandidateView],
      clearingPrice: CPM = CPM.zero,
      requestId: String = "",
      dogear: Option[DogearOutcome] = None
  )

  /**
   * @param reclassifyInMs freshness token: ms until this page's classification
   *   should be refreshed (= classifiedAt + freshnessWindow - now). `> 0` → fresh,
   *   the ad tag does nothing. `<= 0` → the ad tag should extract live-page text
   *   and POST /v1/classify-page (covers BOTH the cold case — never classified,
   *   token defaults to 0 — and the stale case — classification aged out).
   *   Sourced from a per-url classifiedAt that is recorded the moment a page is
   *   classified, INDEPENDENT of whether the auction drew a bid — so a
   *   classified-but-no-bidder page is not treated as cold. Default Long.MaxValue
   *   = "fresh, don't classify" (winner path). See docs/design/ON_DEMAND_CLASSIFICATION.md.
   * @param needText derived legacy view: `reclassifyInMs <= 0`. Kept so the
   *   currently-deployed bootstrap keeps working during the migration.
   */
  final case class BatchSelected(
      outcomes: Vector[BatchSlotOutcome],
      pageCategories: Set[String] = Set.empty,
      reclassifyInMs: Long = Long.MaxValue,
      needText: Boolean = false
  ) extends BatchSelectResult

  case object BatchContentTooOld extends BatchSelectResult
  case object BatchHostNotVerified extends BatchSelectResult

  /** Result of ApproveAll */
  final case class ApproveAllResult(approved: Int, failed: Int) extends CborSerializable

  final case class PendingItem(
      url: String,
      slotId: String,
      creativeId: String,
      cpm: Double,
      category: String,
      s3Key: Option[String] = None, // S3 key for CDN URL building
      matchConfidence: Option[Double] = None, // 0.0-1.0 verification score
      verificationReason: Option[String] = None, // Gemini's explanation
      landingDomain: Option[String] = None, // For domain blocklist filtering
      adProductCategory: Option[String] = None, // For ad product blocklist filtering
      campaignId: Option[String] = None,
      advertiserId: Option[String] = None,
      // Epoch millis when this creative FIRST entered the publisher's
      // approval queue. Survives the pending row's TTL purge / re-queue
      // churn (pending_first_seen table) — honest queue age, unlike the
      // selection's createdAt which resets on every re-auction upsert.
      firstSeenEpochMs: Option[Long] = None,
      // Re-auction waves survived unreviewed since first seen (debounced
      // per wave, not per placement upsert).
      requeueCount: Option[Int] = None
  )

  // Reply to ListPending — crosses nodes (AdServer is sharded by siteId, the
  // HTTP request lands on any api node), so it MUST be CborSerializable or the
  // ask fails with a Java-serialization error and the approval page hangs.
  final case class PendingList(items: Vector[PendingItem]) extends CborSerializable

  /** Result of flagging a creative */
  final case class FlagResult(creativeId: String, flagged: Boolean) extends CborSerializable

  /** Result of unflagging a creative */
  final case class UnflagResult(creativeId: String, unflagged: Boolean) extends CborSerializable

  /** A flagged creative item */
  final case class FlaggedItem(
      url: String,
      slotId: String,
      creativeId: String,
      campaignId: String,
      cpm: Double,
      category: String,
      reason: String,
      flaggedAt: String,
      s3Key: Option[String] = None,
      landingDomain: Option[String] = None,
      adProductCategory: Option[String] = None
  )

  /**
   * List of flagged creatives — crosses nodes like PendingList, so it MUST be
   * CborSerializable (see PendingList).
   */
  final case class FlaggedList(items: Vector[FlaggedItem]) extends CborSerializable

  /**
   * Notifies AdServer that a campaign has been paused - remove its creatives from ServeIndex.
   *
   * `revokeApprovals` MUST be true ONLY for an EXPLICIT advertiser
   * pause/delete (the promovolve.CampaignPaused topic event from
   * CampaignEntity.UpdateStatus): pausing is leaving the site, so approvals
   * are revoked and resume starts from PENDING. It MUST stay false for
   * every other sender — AuctioneerEntity fires this message scope-blind
   * on ANY CampaignChanged(isActive=false), including category
   * re-registration churn during deploys, and revoking approvals there
   * re-creates the 2026-07-03 "approval queue is gone" cascade that
   * 0e1304c4 fixed.
   */
  final case class CampaignPaused(campaignId: CampaignId, revokeApprovals: Boolean = false) extends Command

  /**
   * Notifies AdServer that a campaign has narrowed its media targeting and is
   * no longer allowed to bid on THIS site (the advertiser dropped this site
   * from a non-empty siteAllowlist). Mirrors CampaignPaused removal: the
   * campaign is wiped from every slot on this site, INCLUDING any reader pins
   * on its creatives. A reader's pin on a site the advertiser left dies — that
   * is the product decision for site-narrow eviction. Idempotent: a no-op when
   * the campaign isn't present.
   */
  final case class EvictCampaignFromSite(campaignId: CampaignId) extends Command

  /**
   * Notifies AdServer that a campaign narrowed its TOPIC targeting (dropped a
   * category) and is awarded on specific pages it no longer targets. Removes
   * the campaign's candidates from exactly these ServeIndex slot keys —
   * EXCEPT reader-pinned creatives, which survive a topic drop on a still-
   * served page (product decision). Driven by the narrowing edit only (not the
   * generic rebuild), so it never over-evicts a temporarily-absent campaign.
   * Idempotent: a no-op when the campaign isn't present in a key.
   */
  final case class EvictCampaignFromSlots(campaignId: CampaignId, slotKeys: Set[String]) extends Command

  /**
   * Publisher explicitly revoked a creative's approval on this site (the
   * approval page's Revoke action — soft undo, creative returns to the
   * pending queue on its next auction win). This is the ONLY pause-family
   * command that deletes the persisted approval: it IS the un-approval,
   * an explicit publisher act. CreativePaused (advertiser-side, reversible)
   * deliberately keeps approvals — revoke must not ride that message or
   * the kept approval re-admits the creative at the next rebuild.
   */
  final case class RevokeCreativeApproval(creativeId: CreativeId) extends Command

  /**
   * Auto-approve trust state for the publisher dashboard: the anchors
   * (campaigns / landing registrable-domains the publisher manually
   * approved a creative from) plus which currently-approved creatives got
   * in via auto-approval, for UI badging. Crosses nodes → CborSerializable.
   */
  final case class GetAutoApproveInfo(replyTo: ActorRef[AutoApproveInfo]) extends Command
  final case class AutoApproveInfo(
      trustedCampaigns: Set[String],
      trustedDomains: Set[String],
      autoApprovedCreativeIds: Set[String]
  ) extends CborSerializable

  /**
   * Publisher removes one trust anchor from the dashboard ("stop trusting
   * this campaign/domain"). anchorType is "campaign" | "domain". The
   * in-memory set shrinks synchronously; the reply arrives after the DB
   * delete completes so the UI can trust a refreshed anchor list.
   */
  final case class RemoveTrustAnchor(
      anchorType: String,
      anchorValue: String,
      replyTo: ActorRef[TrustAnchorRemoved]
  ) extends Command
  final case class TrustAnchorRemoved(removed: Boolean) extends CborSerializable

  /** Notifies AdServer that a creative has been paused - remove it from ServeIndex */
  final case class CreativePaused(creativeId: CreativeId) extends Command

  /** Notifies AdServer that a creative has been reactivated - trigger campaign re-auction */
  final case class CreativeReactivated(creativeId: CreativeId, campaignId: CampaignId) extends Command

  /** Notifies AdServer to remove all creatives from an advertiser (used for suspension/closure) */
  final case class RemoveAdvertiser(advertiserId: AdvertiserId) extends Command

  /** Notifies AdServer to refresh TTL for a campaign's entries (used when budget exhausts) */
  final case class RefreshTTLForCampaign(campaignId: CampaignId) extends Command

  /** Notifies AdServer to refresh TTL for an advertiser's entries (used when budget exhausts) */
  final case class RefreshTTLForAdvertiser(advertiserId: AdvertiserId) extends Command

  /** Notifies AdServer that a campaign's effective CPM changed due to RL bid multiplier adjustment */
  final case class BidCpmChanged(campaignId: CampaignId, newCpm: CPM) extends Command

  // ==================== Internal Commands (pipeToSelf results) ====================

  /**
   * Per-creative stats for Thompson Sampling.
   *
   * ═══════════════════════════════════════════════════════════════════════════════
   * TIME-BUCKETED CREATIVE STATS
   * ═══════════════════════════════════════════════════════════════════════════════
   *
   * RECORDING EVENTS (as they happen)
   * ─────────────────────────────────
   *
   * Timeline:  10:00    10:01    10:02    10:03    10:04    ... 10:59    11:00
   *               │        │        │        │        │           │        │
   *               ▼        ▼        ▼        ▼        ▼           ▼        ▼
   *
   * Creative A:
   * Buckets:  ┌──────┬──────┬──────┬──────┬──────┬─────────┬──────┐
   *           │(5,0) │(3,1) │(4,0) │(2,1) │(6,0) │   ...   │(4,0) │
   *           └──────┴──────┴──────┴──────┴──────┴─────────┴──────┘
   *              ↑      ↑
   *              │      └── 3 impressions, 1 click in minute 10:01
   *              └── 5 impressions, 0 clicks in minute 10:00
   *
   * Creative B:
   * Buckets:  ┌──────┬──────┬──────┬──────┬──────┬─────────┬──────┐
   *           │(2,0) │(4,2) │(3,0) │(5,1) │(2,0) │   ...   │(3,1) │
   *           └──────┴──────┴──────┴──────┴──────┴─────────┴──────┘
   *
   * ═══════════════════════════════════════════════════════════════════════════════
   * AD REQUEST ARRIVES (e.g., at 10:47:32)
   * ═══════════════════════════════════════════════════════════════════════════════
   *
   * Step 1: AGGREGATE
   * ────────────────────────────────────────────
   *
   * Creative A:                              Creative B:
   * ┌─────────────────────────┐              ┌─────────────────────────┐
   * │ Sum all buckets:        │              │ Sum all buckets:        │
   * │                         │              │                         │
   * │ impressions = 5+3+4+2+6+...+4 = 847    │ impressions = 2+4+3+5+...+3 = 612
   * │ clicks      = 0+1+0+1+0+...+0 = 42     │ clicks      = 0+2+0+1+...+1 = 51
   * └─────────────────────────┘              └─────────────────────────┘
   *
   * Step 2: THOMPSON SAMPLING
   * ─────────────────────────
   *
   *  Creative A:                              Creative B:
   * ┌─────────────────────────┐              ┌─────────────────────────┐
   * │ α = clicks + 1 = 43     │              │ α = clicks + 1 = 52     │
   * │ β = imps - clicks + 1   │              │ β = imps - clicks + 1   │
   * │   = 847 - 42 + 1 = 806  │              │   = 612 - 51 + 1 = 562  │
   * │                         │              │                         │
   * │ Sample from Beta(43,806)│              │ Sample from Beta(52,562)│
   * │         ↓               │              │         ↓               │
   * │   sample = 0.051        │              │   sample = 0.089        │
   * └─────────────────────────┘              └─────────────────────────┘
   *                                                     │
   *                                                     ▼
   *                                          ┌─────────────────────┐
   *                                          │  WINNER: Creative B │
   *                                          │  (higher sample)    │
   *                                          └─────────────────────┘
   *
   * ═══════════════════════════════════════════════════════════════════════════════
   * PRUNING (on every update)
   * ═══════════════════════════════════════════════════════════════════════════════
   *
   * At 11:01, when new impression recorded:
   *
   *    BEFORE prune (windowMinutes = 60):
   *    ┌──────┬──────┬──────┬──────┬─────────┬──────┬──────┐
   *    │10:00 │10:01 │10:02 │10:03 │   ...   │11:00 │11:01 │
   *    │(5,0) │(3,1) │(4,0) │(2,1) │         │(4,0) │(1,0) │ ← new
   *    └──────┴──────┴──────┴──────┴─────────┴──────┴──────┘
   *       ↑
   *       └── cutoff = 11:01 - 60 min = 10:01
   *        anything ≤ 10:00 gets dropped
   *
   *     AFTER prune:
   *    ┌──────┬──────┬──────┬─────────┬──────┬──────┐
   *    │10:01 │10:02 │10:03 │   ...   │11:00 │11:01 │
   *    │(3,1) │(4,0) │(2,1) │         │(4,0) │(1,0) │
   *    └──────┴──────┴──────┴─────────┴──────┴──────┘
   *
   *     10:00 bucket is gone → old data naturally expires
   *
   * ═══════════════════════════════════════════════════════════════════════════════
   * LATE CLICK HANDLING
   * ═══════════════════════════════════════════════════════════════════════════════
   *
   * User sees ad at 10:15, clicks at 10:22 (7 min later):
   *
   *   10:15: Impression recorded
   *   ┌──────┬──────┬──────┐
   *   │10:14 │10:15 │10:16 │
   *   │(2,0) │(3,0) │      │  ← impression goes to 10:15 bucket
   *   └──────┴──────┴──────┘
   *            ↑
   *            └── (2,0) → (3,0)
   *
   *   10:22: Click recorded
   *   ┌──────┬──────┬──────┬─────────┬──────┐
   *   │10:14 │10:15 │10:16 │   ...   │10:22 │
   *   │(2,0) │(3,0) │(1,0) │         │(0,1) │  ← click goes to 10:22 bucket
   *   └──────┴──────┴──────┴─────────┴──────┘
   *                                    ↑
   *                                    └── new bucket created
   * @param buckets
   * @param windowMinutes
   */
  final case class CreativeStats(
      buckets: Map[Long, (Int, Int, Int)] = Map.empty, // minute -> (impressions, clicks, folds)
      windowMinutes: Int = 60
  ) {
    def impressions: Int = buckets.values.map(_._1).sum
    def clicks: Int = buckets.values.map(_._2).sum
    def folds: Int = buckets.values.map(_._3).sum

    def recordImpression(now: Instant): CreativeStats = {
      val minute = now.getEpochSecond / 60
      val (imps, clks, fds) = buckets.getOrElse(minute, (0, 0, 0))
      copy(buckets = prune(now) + (minute -> (imps + 1, clks, fds)))
    }

    def recordClick(now: Instant): CreativeStats = {
      val minute = now.getEpochSecond / 60
      val (imps, clks, fds) = buckets.getOrElse(minute, (0, 0, 0))
      copy(buckets = prune(now) + (minute -> (imps, clks + 1, fds)))
    }

    /**
     * Folds are a free engagement signal: the reader explicitly bookmarked the
     * creative. They feed Thompson Sampling alongside CTR via an independent
     * Beta posterior in [[ThompsonSampling.scoreCandidate]]. No billing impact.
     */
    def recordFold(now: Instant): CreativeStats = {
      val minute = now.getEpochSecond / 60
      val (imps, clks, fds) = buckets.getOrElse(minute, (0, 0, 0))
      copy(buckets = prune(now) + (minute -> (imps, clks, fds + 1)))
    }

    private def prune(now: Instant): Map[Long, (Int, Int, Int)] = {
      val cutoff = now.getEpochSecond / 60 - windowMinutes
      buckets.filter { case (min, _) => min > cutoff }
    }
  }

  /** Map of all per-creative stats (for monitoring/API) */
  final case class CreativeStatsMap(
      siteId: String,
      stats: Map[String, CreativeStats]
  ) extends CborSerializable

  /** Serve outcome counters - tracks why serves succeeded or failed */
  final case class ServeStats(
      siteId: String,
      selected: Long = 0,
      pacingSkipped: Long = 0, // Skipped by pacing gate (campaign budget pacing)
      budgetExhausted: Long = 0,
      noCandidates: Long = 0,
      contentTooOld: Long = 0,
      warmup: Long = 0, // Requests during warmup mode (traffic recorded, no ads served)
      totalSpend: Double = 0.0, // Actual spend in dollars
      dayStart: Option[Instant] = None, // Campaign day start for elapsed time calculation
      trafficShapeSummary: Option[String] = None, // Summary of learned traffic shape
      hourlyImpressions: Array[Long] = Array.fill(24)(0L), // Impressions per hour bucket
      weekdayShapeVolumes: Option[Array[Double]] = None, // Learned weekday traffic shape (24 hourly values)
      weekendShapeVolumes: Option[Array[Double]] = None // Learned weekend traffic shape (24 hourly values)
  ) extends CborSerializable {
    def total: Long = selected + pacingSkipped + budgetExhausted + noCandidates + contentTooOld + warmup

    def recordSelectedInBucket(bucket: Int, spendDollars: Double): ServeStats = {
      val newHourly = hourlyImpressions.clone()
      val safeBucket = math.max(0, math.min(23, bucket))
      newHourly(safeBucket) += 1
      copy(selected = selected + 1, totalSpend = totalSpend + spendDollars, hourlyImpressions = newHourly)
    }
    def recordPacingSkipped: ServeStats = copy(pacingSkipped = pacingSkipped + 1)
    def recordBudgetExhausted: ServeStats = copy(budgetExhausted = budgetExhausted + 1)
    def recordNoCandidates: ServeStats = copy(noCandidates = noCandidates + 1)
    def recordContentTooOld: ServeStats = copy(contentTooOld = contentTooOld + 1)
    def recordWarmup: ServeStats = copy(warmup = warmup + 1)
  }

  /** Internal: blocklist update from DData */
  private[delivery] final case class BlocklistUpdated(
      blocklist: Option[PublisherEntity.CachedDomainBlocklist]
  ) extends Command

  /**
   * Internal: full snapshot of advertiser-side site-domain blocklists from DData.
   * AdServer keeps this as a per-advertiser map and filters candidates whose
   * advertiser blocked the serving site's domain.
   */
  private[delivery] final case class AdvertiserBlocklistsUpdated(
      blocklists: Map[AdvertiserId, Set[String]]
  ) extends Command

  /** Internal: ad product blocklist update from DData */
  private[delivery] final case class AdProductBlocklistUpdated(
      blocklist: Option[SiteEntity.CachedAdProductBlocklist]
  ) extends Command

  /** Internal: pacing config update from DData */
  private[delivery] final case class PacingConfigUpdated(
      config: Option[SiteEntity.PacingConfig]
  ) extends Command

  /** Internal: spend info update from CampaignEntity via topic */
  private[delivery] final case class SpendInfoUpdated(
      spendUpdate: Option[SpendUpdate]
  ) extends Command

  /** Internal: verified host update from DData (for serve-time host check) */
  private[delivery] final case class VerifiedHostUpdated(
      host: Option[String]
  ) extends Command

  /**
   * Internal: per-(site,pageUrl) winner cache snapshot from DData.
   * Carries the full map; the AdServer extracts just the entries it
   * cares about on update.
   */
  private[delivery] final case class PageWinnersSnapshot(
      data: org.apache.pekko.cluster.ddata.LWWMap[String, AdServer.PageWinners]
  ) extends Command

  /** Internal: periodic sweep of stale page-winner entries. */
  private[delivery] case object SweepPageWinners extends Command

  /** Internal: no-op command for ignoring type-erased DData messages */
  private[delivery] case object NoOp extends Command

  /** Internal: debug logging for DData subscription events - helps trace blocklist update flow */
  private[delivery] final case class DDataSubscriptionDebug(
      eventType: String, // "Changed" or "GetSuccess" or "Other"
      keyId: String, // The key ID from the DData message
      keyMatched: String, // Which key it matched (or "unknown")
      siteIdFound: Boolean, // Whether our siteId was found in the data
      dataSize: Int // Number of entries in the map
  ) extends Command

  /**
   * Internal: response from ServeIndexDData.Get for BatchSelect.
   * Carries the full batch spec forward so the handler can score
   * candidates once and run the joint greedy assignment across
   * all slots.
   */
  private[delivery] final case class BatchSelectViewLoaded(
      view: Option[ServeView],
      url: URL,
      slots: Vector[BatchSlotSpec],
      classificationFreshnessWindowMs: Long,
      replyTo: ActorRef[BatchSelectResult],
      excludedCreatives: Set[CreativeId] = Set.empty,
      excludedCampaigns: Set[CampaignId] = Set.empty
  ) extends Command

  /**
   * Internal: spend info fetched for BatchSelect — cache was empty
   * so we asked CampaignEntity directly.
   */
  private[delivery] final case class BatchSpendInfoFetched(
      fetchedSpendInfo: Map[CampaignId, CachedSpendInfo],
      view: ServeView,
      url: URL,
      slots: Vector[BatchSlotSpec],
      replyTo: ActorRef[BatchSelectResult],
      ctx: SelectionContext,
      excludedCreatives: Set[CreativeId],
      excludedCampaigns: Set[CampaignId]
  ) extends Command

  /**
   * Internal: pacing-gate outcome for BatchSelect. Aggregate
   * throttle decided; if shouldServe, candidates have been
   * filtered to campaigns with valid spend info.
   */
  private[delivery] final case class BatchPacingGateResult(
      shouldServe: Boolean,
      view: ServeView,
      url: URL,
      slots: Vector[BatchSlotSpec],
      eligibleCandidates: Vector[CandidateView],
      pageBlocked: Set[String],
      replyTo: ActorRef[BatchSelectResult],
      currentCampaignSet: Set[CampaignId],
      excludedCreatives: Set[CreativeId],
      excludedCampaigns: Set[CampaignId]
  ) extends Command

  /**
   * Internal: per-winner reservations resolved. Outcomes with
   * failed reservations have already been demoted to winner=None.
   * Carries the per-campaign pending-spend deltas that need
   * recording in AdServer state.
   */
  private[delivery] final case class BatchReservationsResolved(
      outcomes: Vector[BatchSlotOutcome],
      pendingSpendDeltas: Map[CampaignId, Double],
      url: URL,
      pageCategories: Set[String],
      replyTo: ActorRef[BatchSelectResult]
  ) extends Command

  /** Internal: snapshot save completed */
  private[delivery] final case class SnapshotSaveResult(count: Int, error: Option[Throwable]) extends Command

  /** Internal: purge expired completed */
  private[delivery] final case class PurgeExpiredResult(count: Int) extends Command

  /** Internal: remove pending completed (for approval finalization) */
  private[delivery] final case class RemovePendingCompleted(
      url: String,
      slot: String,
      assetPrt: AssetPointer,
      replyTo: ActorRef[StatusReply[AssetPointer]],
      candidateView: Option[CandidateView] = None,
      removedSlots: Vector[(String, String)] = Vector.empty
  ) extends Command

  /** Internal: remove pending completed for batch approval */
  private[delivery] final case class RemovePendingBatchCompleted(
      url: String,
      slot: String,
      approved: Int,
      failed: Int,
      replyTo: ActorRef[ApproveAllResult]
  ) extends Command

  /** Internal: getPending result for approval flow */
  private[delivery] final case class GetPendingForApproveResult(
      url: String,
      slot: String,
      cid: String,
      selectionOpt: Option[Selection],
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ) extends Command

  /**
   * Internal: creative lookup for the idempotent-approve path — the
   * creative is already site-approved but its pending selection row is
   * gone (approving one placement removes the creative from ALL pending
   * rows, and the inbox lists the same creative under several
   * placements). Approving again must succeed as a no-op, not 400.
   */
  private[delivery] final case class AlreadyApprovedLookedUp(
      cid: String,
      creativeOpt: Option[Creative],
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ) extends Command

  /** Internal: category scores fetched for approval */
  private[delivery] final case class CategoryScoresFetched(
      candidate: Candidate,
      selection: Selection,
      assetPrt: AssetPointer,
      creative: Creative,
      url: String,
      slot: String,
      scoreMap: Map[CategoryId, Double],
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ) extends Command

  /** Internal: creative lookup result for single approval */
  private[delivery] final case class CreativeLookedUpForApprove(
      candidate: Candidate,
      selection: Selection,
      url: String,
      slot: String,
      creativeOpt: Option[Creative],
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ) extends Command

  /** Internal: all creative lookups completed for batch approval */
  private[delivery] final case class CreativesLookedUpForApproveAll(
      url: String,
      slot: String,
      selection: Selection,
      lookedUp: Vector[(Candidate, Option[Creative])],
      replyTo: ActorRef[ApproveAllResult]
  ) extends Command

  /** Internal: candidate views built asynchronously for ServeIndex update */
  private[delivery] final case class CandidateViewsBuilt(
      url: URL,
      slotId: SlotId,
      candidateViews: Vector[CandidateView],
      pending: Vector[Candidate],
      ttl: FiniteDuration,
      slotKey: String,
      existingView: Option[ServeView]
  ) extends Command

  /** Internal: getPending result for approveAll flow */
  private[delivery] final case class GetPendingForApproveAllResult(
      url: String,
      slot: String,
      queue: Option[Selection],
      replyTo: ActorRef[ApproveAllResult]
  ) extends Command

  /** Internal: getPending result for reject flow */
  private[delivery] final case class GetPendingForRejectResult(
      url: String,
      slot: String,
      cid: String,
      reason: Option[String],
      selectionOpt: Option[Selection],
      replyTo: ActorRef[StatusReply[Done.type]]
  ) extends Command

  /** Internal: reject and promote completed */
  private[delivery] final case class RejectAndPromoteCompleted(
      url: String,
      slot: String,
      promoted: Option[Selection],
      replyTo: ActorRef[StatusReply[Done.type]]
  ) extends Command

  /** Internal: list pending result */
  private[delivery] final case class ListPendingResult(
      queue: Vector[PendingItem],
      replyTo: ActorRef[PendingList]
  ) extends Command

  /** Internal: flag creative result */
  private[delivery] final case class FlagCreativeResult(
      flagged: Option[FlaggedCreative],
      replyTo: ActorRef[StatusReply[FlagResult]]
  ) extends Command

  /** Internal: unflag creative result */
  private[delivery] final case class UnflagCreativeResult(
      unflagged: Option[FlaggedCreative],
      replyTo: ActorRef[StatusReply[UnflagResult]]
  ) extends Command

  /** Internal: list flagged result */
  private[delivery] final case class ListFlaggedResult(
      items: Vector[FlaggedItem],
      replyTo: ActorRef[FlaggedList]
  ) extends Command

  /** Internal: ServeIndex loaded for candidate processing - determines actual pre-approval status */
  private[delivery] final case class ServeIndexLoadedForCandidates(
      url: URL,
      slotId: SlotId,
      candidates: Vector[Candidate],
      classifiedAt: Instant,
      ttl: FiniteDuration,
      slotKey: String,
      existingCreativeIds: Set[CreativeId],
      existingView: Option[ServeView], // Full view to preserve orphaned approved creatives
      authoritativeAbsent: Set[CampaignId]
  ) extends Command

  /** Internal: category scores fetched for processCandidates */
  private[delivery] final case class CandidateScoresFetched(
      url: URL,
      slotId: SlotId,
      approved: Vector[Candidate],
      pending: Vector[Candidate],
      classifiedAt: Instant,
      ttl: FiniteDuration,
      slotKey: String,
      categoryScores: Map[CategoryId, Double],
      existingView: Option[ServeView] // Full view to preserve orphaned approved creatives
  ) extends Command

  /** Internal: upsert pending completed */
  private[delivery] final case class UpsertPendingCompleted(
      count: Int,
      url: URL,
      slotId: SlotId,
      topCreativeId: String,
      creativeIds: Set[String]
  ) extends Command

  // ==================== Results ====================

  /** Internal: Approval status persisted on AdvertiserEntity for single approval */
  private[delivery] final case class ApprovalStatusPersisted(
      url: String,
      slot: String,
      creativeId: String,
      assetPrt: AssetPointer,
      candidateView: CandidateView,
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ) extends Command

  /** Internal: All approval statuses persisted on AdvertiserEntity for batch approval */
  private[delivery] final case class BatchApprovalStatusPersisted(
      url: String,
      slot: String,
      approved: Int,
      failed: Int,
      replyTo: ActorRef[ApproveAllResult]
  ) extends Command

  /**
   * Internal: creative stats loaded from tracking_events on startup.
   * Bucket value is `(impressions, clicks, folds)`, mirroring
   * [[CreativeStats.buckets]].
   */
  private[delivery] final case class CreativeStatsLoaded(
      stats: Map[String, Map[Long, (Int, Int, Int)]]
  ) extends Command

  /** Internal: traffic shape snapshot loaded on startup */
  private[delivery] final case class TrafficShapeSnapshotLoaded(
      snapshot: Option[TrafficShapeSnapshot]
  ) extends Command

  /**
   * Internal: approved creative IDs loaded from DB on startup. Carries the
   * owning advertiser per creative so the handler can re-announce each
   * approval to its AdvertiserEntity (backfills `Creative.approvedSites`,
   * which the bid path reads to tell approved demand from pending).
   */
  private[delivery] final case class ApprovedCreativeIdsLoaded(
      ids: Set[CreativeId],
      advertiserByCreative: Map[CreativeId, AdvertiserId] = Map.empty,
      // Subset of `ids` whose approval was written by the auto-approve
      // path (approved_via='auto') — rebuilds the badge set on restart.
      autoApproved: Set[CreativeId] = Set.empty
  ) extends Command

  /**
   * Internal: the startup approvals load FAILED. Never coerced to an empty
   * set — approval-by-ServeIndex-membership is circular after a restart, so
   * a silently-empty approved set can never self-repair (incident
   * 2026-07-06). Logged loudly + retried with backoff instead.
   */
  private[delivery] final case class ApprovedCreativeIdsLoadFailed(
      reason: String,
      attempt: Int
  ) extends Command

  /** Internal: scheduled retry of the approvals load. */
  private[delivery] final case class ApprovedCreativeIdsRetryLoad(
      attempt: Int
  ) extends Command

  // ---------- Auto-approve trust anchors (internal) ----------

  /** Internal: auto-approve toggle update from DData (SiteEntity.AutoApproveKey). */
  private[delivery] final case class AutoApproveConfigUpdated(
      config: Option[SiteEntity.CachedAutoApprove]
  ) extends Command

  /**
   * Internal: trust anchors loaded from DB on startup. Like the approvals
   * load, a failure must never become empty sets silently — but the safe
   * degradation here is the opposite direction: with no anchors loaded,
   * candidates simply queue for MANUAL review (never wrongly auto-approve).
   */
  private[delivery] final case class TrustAnchorsLoaded(
      campaigns: Set[CampaignId],
      domains: Set[String]
  ) extends Command

  /** Internal: the startup trust-anchor load failed — retried with backoff. */
  private[delivery] final case class TrustAnchorsLoadFailed(reason: String, attempt: Int) extends Command

  /** Internal: scheduled retry of the trust-anchor load. */
  private[delivery] final case class TrustAnchorsRetryLoad(attempt: Int) extends Command

  /**
   * Internal: a publisher reject/flag/revoke broke auto-approve trust for
   * this campaign (and its landing registrable-domain when known). Deletes
   * the persisted anchors and synchronously shrinks the in-memory sets.
   */
  private[delivery] final case class TrustBroken(
      campaignId: CampaignId,
      domain: Option[String]
  ) extends Command

  /** Internal: DB delete for RemoveTrustAnchor completed — release the reply. */
  private[delivery] final case class TrustAnchorRemovalDone(
      removed: Boolean,
      replyTo: ActorRef[TrustAnchorRemoved]
  ) extends Command

  /**
   * Internal: pages whose PENDING candidates match current trust anchors,
   * found by the sweep that runs when the toggle turns on or a manual
   * approval mints anchors. Each page gets a re-auction; the fresh wave's
   * candidate partition then auto-approves the matches through the normal
   * path (ServeIndex, floor teaching, SSE) — queued creatives must not
   * wait for organic traffic to benefit from newly-granted trust.
   */
  private[delivery] final case class TrustSweepReauction(urls: Vector[String]) extends Command

  /**
   * Internal: the approved creatives to strip from in-memory approval state
   * for a campaign whose approvals were revoked (pause / site-narrow
   * eviction). Carries the campaign's OWN creatives, resolved from the
   * approvals store — the in-memory slot-key index conflates every campaign
   * sharing a slot key, so stripping by index would revoke co-tenant
   * campaigns' approvals too (their DB rows survive → memory/DB divergence,
   * spurious re-queue as pending, voided reader pins).
   */
  private[delivery] final case class CampaignApprovalsRevoked(
      campaignId: CampaignId,
      creativeIds: Set[CreativeId]
  ) extends Command

  /** Internal: traffic shape snapshot save completed */
  private[delivery] final case class TrafficShapeSnapshotSaveResult(
      error: Option[Throwable]
  ) extends Command

  // ==================== Data Models ====================

  /**
   * Acknowledgement for Reject. CborSerializable because it's returned as
   * a StatusReply across nodes (the requesting actor may be on another
   * node in a multi-node cluster); a bare case object has no serializer.
   */
  case object Done extends CborSerializable

  /** Internal command to purge expired pending selections */
  private[delivery] case object PurgeExpired extends Command

  /** Internal command to clean up stale pending spend entries */
  private[delivery] case object CleanupStalePending extends Command

  /** Internal command to snapshot stats to database for dashboard */
  private[delivery] case object SnapshotStats extends Command

  /** Internal command to publish traffic ratio to DData for floor CPM agent */
  private[delivery] case object PublishTrafficRatio extends Command

  /** Remove a campaign's creatives from the ServeView (triggered on CampaignChanged) */
  final case class RemoveCampaign(campaignId: CampaignId) extends Command

  case object Passivate extends Command
}
