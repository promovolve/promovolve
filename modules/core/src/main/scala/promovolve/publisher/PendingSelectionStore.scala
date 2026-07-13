package promovolve.publisher

import promovolve.{ Candidate, Selection }

import java.time.Instant
import scala.concurrent.Future

/**
 * Pending queue for publisher creative approval workflow.
 *
 * This store tracks PENDING selections awaiting publisher review.
 * Approval status is owned by CampaignEntity (Bloom filter).
 * Approved creatives are cached in ServeIndexDData for serving.
 */
trait PendingSelectionStore {

  /** Write/overwrite a pending selection for (publisher,url,slot). */
  def upsertPending(sel: Selection): Future[Unit]

  /** Get the pending selection for (publisher,url,slot). Returns None if not pending. */
  def getPending(publisherId: String, url: String, slotId: String): Future[Option[Selection]]

  /** For UI/ops: list all pending items for a publisher. */
  def pendingQueue(publisherId: String): Future[Vector[(String, String, Candidate)]] // (url, slot, candidate)

  /**
   * Remove entire pending selection for a slot (after bulk approval).
   * Returns true if removed, false if not found.
   */
  def removePending(publisherId: String, url: String, slotId: String): Future[Boolean]

  /**
   * Remove a specific creative from the pending selection (after individual approval).
   * Keeps remaining candidates in the queue.
   * Returns the updated selection if candidates remain, None if the selection is now empty/removed.
   */
  def removeCreativeFromPending(publisherId: String, url: String, slotId: String, creativeId: String)
      : Future[Option[Selection]]

  /**
   * Reject current candidate and promote next (if any).
   * Returns the new pending selection if promoted, None if queue exhausted.
   */
  def rejectAndPromote(publisherId: String, url: String, slotId: String): Future[Option[Selection]]

  /** Drop selections whose expiresAt < now. Returns number purged. */
  def purgeExpired(now: Instant): Future[Int]

  // ==================== Budget Exhaustion ====================

  /**
   * Remove all pending creatives for a campaign (when budget exhausted).
   * Returns number of selections affected.
   */
  def removeByCampaignId(publisherId: String, campaignId: String): Future[Int]

  /**
   * Remove all pending creatives for an advertiser (when budget exhausted).
   * Returns number of selections affected.
   */
  def removeByAdvertiserId(publisherId: String, advertiserId: String): Future[Int]

  /**
   * Remove a specific creative from all pending selections (when creative is paused).
   * Returns the (url, slotId) pairs from which the creative was removed.
   */
  def removeCreativeFromAll(publisherId: String, creativeId: String): Future[Vector[(String, String)]]

  /**
   * Remove all pending creatives with a specific landing domain (when domain is blocked).
   * Returns number of selections affected.
   */
  def removeByLandingDomain(publisherId: String, landingDomain: String): Future[Int]

  /**
   * Remove all pending creatives with a specific ad product category (when category is blocked).
   * Returns number of selections affected.
   */
  def removeByAdProductCategory(publisherId: String, adProductCategory: String): Future[Int]

  // ==================== Approved Creatives Persistence ====================

  /** Get all approved creative IDs for a publisher. */
  def getApprovedCreativeIds(publisherId: String): Future[Set[String]]

  /**
   * (creativeId -> advertiserId) for every approved creative. Used by
   * AdServer's boot backfill, which re-announces each approval to its
   * AdvertiserEntity so `Creative.approvedSites` converges (the bid path
   * reads it to tell approved demand from pending demand).
   */
  def getApprovedCreativeAdvertisers(publisherId: String): Future[Map[String, String]]

  /**
   * Full approval metadata for the boot backfill: advertiser (for the
   * re-announce) plus how the approval happened ("manual" | "auto"), so
   * AdServer can rebuild its auto-approved set for UI badging.
   */
  def getApprovedCreativeMeta(publisherId: String): Future[Vector[ApprovedCreativeMeta]]

  /**
   * Same, restricted to one campaign. Used when an explicit campaign
   * pause/delete revokes the campaign's approvals on this site.
   */
  def getApprovedCreativeAdvertisersByCampaign(publisherId: String, campaignId: String): Future[Map[String, String]]

  /** Record a creative as approved for a publisher. `via` is "manual" or "auto". */
  def insertApproved(
      publisherId: String,
      creativeId: String,
      campaignId: String,
      advertiserId: String,
      via: String = "manual"
  ): Future[Unit]

  /**
   * Delete a specific approved creative for a publisher.
   * Returns true if deleted, false if not found.
   */
  def deleteApproved(publisherId: String, creativeId: String): Future[Boolean]

  /**
   * Delete all approved creatives for a campaign.
   * Returns number of rows deleted.
   */
  def deleteApprovedByCampaignId(publisherId: String, campaignId: String): Future[Int]

  /**
   * Delete all approved creatives for an advertiser.
   * Returns number of rows deleted.
   */
  def deleteApprovedByAdvertiserId(publisherId: String, advertiserId: String): Future[Int]

  // ==================== First-Seen Tracking ====================
  // A pending_selection row is destroyed by the TTL purge and recreated by
  // the next re-auction, so its createdAt can never say how long a creative
  // has really been waiting for review. First-seen rows are keyed
  // (publisher, creative) and survive that churn: first_seen is written
  // once and never updated afterwards.

  /**
   * Record that creatives were (re)queued for review. Inserts first_seen
   * once per (publisher, creative); on conflict only refreshes last_queued
   * and bumps requeue_count — debounced to one increment per re-auction
   * wave (60s), not per placement upsert.
   */
  def recordQueued(publisherId: String, creativeIds: Set[String], now: Instant): Future[Unit]

  /**
   * First-seen info for every creative this publisher has ever had queued
   * (and not yet resolved), keyed by creativeId.
   */
  def getFirstSeen(publisherId: String): Future[Map[String, FirstSeen]]

  /**
   * Forget first-seen tracking after a terminal outcome: approve, reject,
   * or removal from the marketplace (campaign/advertiser/domain/category
   * removals call this internally).
   */
  def deleteFirstSeen(publisherId: String, creativeIds: Set[String]): Future[Unit]

  // ==================== Auto-Approve Trust Anchors ====================
  // A trust anchor records that the publisher manually approved a creative
  // from a campaign / landing registrable-domain on this site. When the
  // site's auto-approve toggle is on, later candidates matching an anchor
  // skip the manual queue. Anchors are written on MANUAL approval only
  // (auto-approval consumes trust, never widens it) and deleted when the
  // publisher rejects/flags/revokes a creative from that campaign/domain.

  /**
   * Record trust anchors earned by a manual approval. `anchors` are
   * (anchorType, anchorValue) pairs — anchorType is "campaign" | "domain",
   * domain values already normalized to eTLD+1. Idempotent.
   */
  def insertTrustAnchors(publisherId: String, anchors: Seq[(String, String)], sourceCreativeId: String): Future[Unit]

  /**
   * Break trust after a publisher reject/flag/revoke: delete the campaign
   * anchor and (when known) the domain anchor the creative belongs to.
   * Returns number of anchors deleted.
   */
  def deleteTrustAnchorsFor(publisherId: String, campaignId: String, domain: Option[String]): Future[Int]

  /** Delete one anchor by key (publisher UI "remove trust" action). */
  def deleteTrustAnchor(publisherId: String, anchorType: String, anchorValue: String): Future[Boolean]

  /** All trust anchors for a publisher site. */
  def getTrustAnchors(publisherId: String): Future[Vector[TrustAnchor]]

  // ==================== Flagging / Quarantine ====================

  /**
   * Flag a creative (quarantine it from pending queue).
   * Returns the flagged creative if found, None if not found in pending queue.
   */
  def flagCreative(
      publisherId: String,
      url: String,
      slotId: String,
      creativeId: String,
      reason: String
  ): Future[Option[FlaggedCreative]]

  /**
   * Unflag a creative (return it to pending queue).
   * Returns the unflagged creative if found, None if not found in flagged list.
   */
  def unflagCreative(publisherId: String, creativeId: String): Future[Option[FlaggedCreative]]

  /** Get all flagged creatives for a publisher. */
  def getFlagged(publisherId: String): Future[Vector[FlaggedCreative]]
}

/**
 * How long a creative has been waiting in a publisher's approval queue.
 * Survives the pending row's TTL purge / re-queue cycle — firstSeen is the
 * honest queue age; requeueCount is how many re-auction WAVES it has
 * survived unreviewed (debounced: a wave's per-placement upserts land
 * within milliseconds and count once).
 */
case class FirstSeen(firstSeen: Instant, lastQueued: Instant, requeueCount: Int)

/** Boot-backfill row: who to re-announce to, and how the approval happened. */
case class ApprovedCreativeMeta(creativeId: String, advertiserId: String, approvedVia: String)

/**
 * One unit of auto-approve trust on a site. anchorType is "campaign" or
 * "domain" (plain strings — Jackson sealed-trait rule); domain values are
 * eTLD+1 normalized.
 */
case class TrustAnchor(anchorType: String, anchorValue: String, sourceCreativeId: String, createdAt: Instant)

object TrustAnchor {
  val TypeCampaign = "campaign"
  val TypeDomain = "domain"
}

/** A creative that has been flagged/quarantined by the publisher. */
case class FlaggedCreative(
    creativeId: String,
    url: String,
    slotId: String,
    campaignId: String,
    advertiserId: String,
    category: String,
    cpm: BigDecimal,
    reason: String,
    flaggedAt: Instant
)
