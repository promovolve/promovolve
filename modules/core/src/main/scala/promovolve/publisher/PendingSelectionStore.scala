package promovolve.publisher

import promovolve.{Candidate, Selection}

import java.time.Instant
import scala.concurrent.Future

/** Pending queue for publisher creative approval workflow.
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

  /** Remove entire pending selection for a slot (after bulk approval).
    * Returns true if removed, false if not found.
    */
  def removePending(publisherId: String, url: String, slotId: String): Future[Boolean]

  /** Remove a specific creative from the pending selection (after individual approval).
    * Keeps remaining candidates in the queue.
    * Returns the updated selection if candidates remain, None if the selection is now empty/removed.
    */
  def removeCreativeFromPending(publisherId: String, url: String, slotId: String, creativeId: String): Future[Option[Selection]]

  /** Reject current candidate and promote next (if any).
    * Returns the new pending selection if promoted, None if queue exhausted.
    */
  def rejectAndPromote(publisherId: String, url: String, slotId: String): Future[Option[Selection]]

  /** Drop selections whose expiresAt < now. Returns number purged. */
  def purgeExpired(now: Instant): Future[Int]

  // ==================== Budget Exhaustion ====================

  /** Remove all pending creatives for a campaign (when budget exhausted).
    * Returns number of selections affected.
    */
  def removeByCampaignId(publisherId: String, campaignId: String): Future[Int]

  /** Remove all pending creatives for an advertiser (when budget exhausted).
    * Returns number of selections affected.
    */
  def removeByAdvertiserId(publisherId: String, advertiserId: String): Future[Int]

  /** Remove a specific creative from all pending selections (when creative is paused).
    * Returns the (url, slotId) pairs from which the creative was removed.
    */
  def removeCreativeFromAll(publisherId: String, creativeId: String): Future[Vector[(String, String)]]

  /** Remove all pending creatives with a specific landing domain (when domain is blocked).
    * Returns number of selections affected.
    */
  def removeByLandingDomain(publisherId: String, landingDomain: String): Future[Int]

  /** Remove all pending creatives with a specific ad product category (when category is blocked).
    * Returns number of selections affected.
    */
  def removeByAdProductCategory(publisherId: String, adProductCategory: String): Future[Int]

  // ==================== Approved Creatives Persistence ====================

  /** Get all approved creative IDs for a publisher. */
  def getApprovedCreativeIds(publisherId: String): Future[Set[String]]

  /** (creativeId -> advertiserId) for every approved creative. Used by
    * AdServer's boot backfill, which re-announces each approval to its
    * AdvertiserEntity so `Creative.approvedSites` converges (the bid path
    * reads it to tell approved demand from pending demand). */
  def getApprovedCreativeAdvertisers(publisherId: String): Future[Map[String, String]]

  /** Same, restricted to one campaign. Used when an explicit campaign
    * pause/delete revokes the campaign's approvals on this site. */
  def getApprovedCreativeAdvertisersByCampaign(publisherId: String, campaignId: String): Future[Map[String, String]]

  /** Record a creative as approved for a publisher. */
  def insertApproved(publisherId: String, creativeId: String, campaignId: String, advertiserId: String): Future[Unit]

  /** Delete a specific approved creative for a publisher.
    * Returns true if deleted, false if not found.
    */
  def deleteApproved(publisherId: String, creativeId: String): Future[Boolean]

  /** Delete all approved creatives for a campaign.
    * Returns number of rows deleted.
    */
  def deleteApprovedByCampaignId(publisherId: String, campaignId: String): Future[Int]

  /** Delete all approved creatives for an advertiser.
    * Returns number of rows deleted.
    */
  def deleteApprovedByAdvertiserId(publisherId: String, advertiserId: String): Future[Int]

  // ==================== First-Seen Tracking ====================
  // A pending_selection row is destroyed by the TTL purge and recreated by
  // the next re-auction, so its createdAt can never say how long a creative
  // has really been waiting for review. First-seen rows are keyed
  // (publisher, creative) and survive that churn: first_seen is written
  // once and never updated afterwards.

  /** Record that creatives were (re)queued for review. Inserts first_seen
    * once per (publisher, creative); on conflict only refreshes last_queued
    * and bumps requeue_count — debounced to one increment per re-auction
    * wave (60s), not per placement upsert.
    */
  def recordQueued(publisherId: String, creativeIds: Set[String], now: Instant): Future[Unit]

  /** First-seen info for every creative this publisher has ever had queued
    * (and not yet resolved), keyed by creativeId.
    */
  def getFirstSeen(publisherId: String): Future[Map[String, FirstSeen]]

  /** Forget first-seen tracking after a terminal outcome: approve, reject,
    * or removal from the marketplace (campaign/advertiser/domain/category
    * removals call this internally).
    */
  def deleteFirstSeen(publisherId: String, creativeIds: Set[String]): Future[Unit]

  // ==================== Flagging / Quarantine ====================

  /** Flag a creative (quarantine it from pending queue).
    * Returns the flagged creative if found, None if not found in pending queue.
    */
  def flagCreative(
      publisherId: String,
      url: String,
      slotId: String,
      creativeId: String,
      reason: String
  ): Future[Option[FlaggedCreative]]

  /** Unflag a creative (return it to pending queue).
    * Returns the unflagged creative if found, None if not found in flagged list.
    */
  def unflagCreative(publisherId: String, creativeId: String): Future[Option[FlaggedCreative]]

  /** Get all flagged creatives for a publisher. */
  def getFlagged(publisherId: String): Future[Vector[FlaggedCreative]]
}

/** How long a creative has been waiting in a publisher's approval queue.
  * Survives the pending row's TTL purge / re-queue cycle — firstSeen is the
  * honest queue age; requeueCount is how many re-auction WAVES it has
  * survived unreviewed (debounced: a wave's per-placement upserts land
  * within milliseconds and count once).
  */
case class FirstSeen(firstSeen: Instant, lastQueued: Instant, requeueCount: Int)

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
