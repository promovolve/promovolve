package promovolve.publisher.store

import promovolve.*
import promovolve.publisher.{ ApprovedCreativeMeta, FirstSeen, FlaggedCreative, PendingSelectionStore, TrustAnchor }
import slick.jdbc.PostgresProfile.api.*
import spray.json.*

import java.time.Instant
import scala.concurrent.{ ExecutionContext, Future }

/**
 * PostgreSQL-backed implementation of PendingSelectionStore using Slick.
 *
 * Stores pending selections in a database table for persistence across restarts.
 */
final class SlickPendingSelectionStore(db: Database)(using ec: ExecutionContext) extends PendingSelectionStore {

  import SlickPendingSelectionStore.{ given, * }

  private val selections = TableQuery[PendingSelectionTable]

  def ensureSchema(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration.*
    // Use raw SQL to avoid Slick trying to add duplicate primary key
    val createTableSql = sqlu"""
      CREATE TABLE IF NOT EXISTS pending_selection (
        publisher_id VARCHAR(255) NOT NULL,
        url TEXT NOT NULL,
        slot_id VARCHAR(255) NOT NULL,
        selection_json TEXT NOT NULL,
        expires_at TIMESTAMP NOT NULL,
        PRIMARY KEY (publisher_id, url, slot_id)
      )
    """
    Await.result(db.run(createTableSql), 10.seconds)
    val createFirstSeenSql = sqlu"""
      CREATE TABLE IF NOT EXISTS pending_first_seen (
        publisher_id  VARCHAR(255) NOT NULL,
        creative_id   VARCHAR(255) NOT NULL,
        first_seen    TIMESTAMP NOT NULL,
        last_queued   TIMESTAMP NOT NULL,
        requeue_count INT NOT NULL DEFAULT 0,
        PRIMARY KEY (publisher_id, creative_id)
      )
    """
    Await.result(db.run(createFirstSeenSql), 10.seconds)
  }

  def upsertPending(sel: Selection): Future[Unit] = {
    val row = PendingSelectionRow(
      publisherId = sel.publisherId.value,
      url = sel.url.value,
      slotId = sel.slotId.value,
      selectionJson = sel.toJson.compactPrint,
      expiresAt = sel.expiresAt
    )
    val action = selections.insertOrUpdate(row)
    db.run(action).map(_ => ())
  }

  def getPending(publisherId: String, url: String, slotId: String): Future[Option[Selection]] = {
    val query = selections
      .filter(r => r.publisherId === publisherId && r.url === url && r.slotId === slotId)
      .result
      .headOption
    db.run(query).map(_.map(rowToSelection))
  }

  def pendingQueue(publisherId: String): Future[Vector[(String, String, Candidate)]] = {
    val query = selections.filter(_.publisherId === publisherId).result
    db.run(query).map { rows =>
      rows.flatMap { row =>
        val sel = rowToSelection(row)
        sel.ordered.map(candidate => (sel.url.value, sel.slotId.value, candidate))
      }.toVector
    }
  }

  def removePending(publisherId: String, url: String, slotId: String): Future[Boolean] = {
    val action = selections
      .filter(r => r.publisherId === publisherId && r.url === url && r.slotId === slotId)
      .delete
    db.run(action).map(_ > 0)
  }

  def removeCreativeFromPending(
      publisherId: String,
      url: String,
      slotId: String,
      creativeId: String
  ): Future[Option[Selection]] = {
    val query = selections
      .filter(r => r.publisherId === publisherId && r.url === url && r.slotId === slotId)
      .result
      .headOption

    db.run(query).flatMap {
      case None      => Future.successful(None)
      case Some(row) =>
        val sel = rowToSelection(row)
        val remaining = sel.ordered.filterNot(_.creativeId.value == creativeId)
        if (remaining.isEmpty) {
          // No more candidates - remove from queue
          removePending(publisherId, url, slotId).map(_ => None)
        } else {
          // Update selection with remaining candidates
          val updated = sel.copy(ordered = remaining, idx = 0, state = SelState.Pending)
          upsertPending(updated).map(_ => Some(updated))
        }
    }
  }

  def rejectAndPromote(publisherId: String, url: String, slotId: String): Future[Option[Selection]] = {
    val query = selections
      .filter(r => r.publisherId === publisherId && r.url === url && r.slotId === slotId)
      .result
      .headOption

    db.run(query).flatMap {
      case None      => Future.successful(None)
      case Some(row) =>
        val sel = rowToSelection(row)
        sel.promoteNext match {
          case Some(next) =>
            val promoted = next.copy(state = SelState.Pending)
            upsertPending(promoted).map(_ => Some(promoted))
          case None =>
            // No more candidates - remove from queue
            removePending(publisherId, url, slotId).map(_ => None)
        }
    }
  }

  def purgeExpired(now: Instant): Future[Int] = {
    val action = selections.filter(_.expiresAt < now).delete
    db.run(action)
  }

  def removeByCampaignId(publisherId: String, campaignId: String): Future[Int] = {
    // Get all pending selections for this publisher
    val query = selections.filter(_.publisherId === publisherId).result

    db.run(query).flatMap { rows =>
      // Campaign is leaving the marketplace on this publisher — its
      // creatives' first-seen tracking goes with it.
      val removedIds = rows
        .flatMap(r => rowToSelection(r).ordered.filter(_.campaignId.value == campaignId).map(_.creativeId.value))
        .toSet
      // Process each selection: remove candidates from the specified campaign
      val updates = rows.map { row =>
        val sel = rowToSelection(row)
        val remaining = sel.ordered.filterNot(_.campaignId.value == campaignId)

        if (remaining.isEmpty) {
          // No candidates left - delete the selection
          selections
            .filter(r => r.publisherId === publisherId && r.url === row.url && r.slotId === row.slotId)
            .delete
        } else if (remaining.size < sel.ordered.size) {
          // Some candidates removed - update the selection
          val updated = sel.copy(ordered = remaining, idx = 0, state = SelState.Pending)
          val updatedRow = row.copy(selectionJson = updated.toJson.compactPrint)
          selections.insertOrUpdate(updatedRow).map(_ => 1)
        } else {
          // No change for this selection
          DBIO.successful(0)
        }
      }

      db.run(DBIO.sequence(updates))
        .flatMap(counts => deleteFirstSeen(publisherId, removedIds).map(_ => counts.sum))
    }
  }

  def removeByAdvertiserId(publisherId: String, advertiserId: String): Future[Int] = {
    // Get all pending selections for this publisher
    val query = selections.filter(_.publisherId === publisherId).result

    db.run(query).flatMap { rows =>
      val removedIds = rows
        .flatMap(r => rowToSelection(r).ordered.filter(_.advertiserId.value == advertiserId).map(_.creativeId.value))
        .toSet
      // Process each selection: remove candidates from the specified advertiser
      val updates = rows.map { row =>
        val sel = rowToSelection(row)
        val remaining = sel.ordered.filterNot(_.advertiserId.value == advertiserId)

        if (remaining.isEmpty) {
          // No candidates left - delete the selection
          selections
            .filter(r => r.publisherId === publisherId && r.url === row.url && r.slotId === row.slotId)
            .delete
        } else if (remaining.size < sel.ordered.size) {
          // Some candidates removed - update the selection
          val updated = sel.copy(ordered = remaining, idx = 0, state = SelState.Pending)
          val updatedRow = row.copy(selectionJson = updated.toJson.compactPrint)
          selections.insertOrUpdate(updatedRow).map(_ => 1)
        } else {
          // No change for this selection
          DBIO.successful(0)
        }
      }

      db.run(DBIO.sequence(updates))
        .flatMap(counts => deleteFirstSeen(publisherId, removedIds).map(_ => counts.sum))
    }
  }

  def removeCreativeFromAll(publisherId: String, creativeId: String): Future[Vector[(String, String)]] = {
    // ONE transaction with row locks. Two removals routinely run within the
    // same second (a manual approval mints trust anchors and the sweep
    // auto-approves a queued sibling right after), and each rewrites FULL
    // selection JSON — with an unlocked read-modify-write, each write
    // resurrected the creative the other had just removed (ghost "pending"
    // duplicates of approved creatives, observed live 2026-07-14).
    // FOR UPDATE makes the second removal wait and re-read committed rows.
    val txn = (for {
      rows <- selections.filter(_.publisherId === publisherId).forUpdate.result
      results <- DBIO.sequence(rows.map { row =>
        val sel = rowToSelection(row)
        val remaining = sel.ordered.filterNot(_.creativeId.value == creativeId)
        val affected = remaining.size < sel.ordered.size

        val action = if (remaining.isEmpty) {
          // No candidates left - delete the selection
          selections
            .filter(r => r.publisherId === publisherId && r.url === row.url && r.slotId === row.slotId)
            .delete
        } else if (affected) {
          // Creative was removed - update the selection
          val updated = sel.copy(ordered = remaining, idx = 0, state = SelState.Pending)
          val updatedRow = row.copy(selectionJson = updated.toJson.compactPrint)
          selections.insertOrUpdate(updatedRow).map(_ => 1)
        } else {
          // No change for this selection
          DBIO.successful(0)
        }

        action.map(_ => if (affected) Some((row.url, row.slotId)) else None)
      })
    } yield results.flatten.toVector).transactionally

    db.run(txn).flatMap { affected =>
      // Creative paused/removed — drop its queue-age tracking too.
      val cleanup =
        if (affected.nonEmpty) deleteFirstSeen(publisherId, Set(creativeId))
        else Future.successful(())
      cleanup.map(_ => affected)
    }
  }

  def removeByLandingDomain(publisherId: String, landingDomain: String): Future[Int] = {
    // Get all pending selections for this publisher
    val query = selections.filter(_.publisherId === publisherId).result

    db.run(query).flatMap { rows =>
      val removedIds = rows
        .flatMap(r => rowToSelection(r).ordered.filter(_.landingDomain == landingDomain).map(_.creativeId.value))
        .toSet
      // Process each selection: remove candidates with the blocked landing domain
      val updates = rows.map { row =>
        val sel = rowToSelection(row)
        val remaining = sel.ordered.filterNot(_.landingDomain == landingDomain)

        if (remaining.isEmpty) {
          // No candidates left - delete the selection
          selections
            .filter(r => r.publisherId === publisherId && r.url === row.url && r.slotId === row.slotId)
            .delete
        } else if (remaining.size < sel.ordered.size) {
          // Some candidates removed - update the selection
          val updated = sel.copy(ordered = remaining, idx = 0, state = SelState.Pending)
          val updatedRow = row.copy(selectionJson = updated.toJson.compactPrint)
          selections.insertOrUpdate(updatedRow).map(_ => 1)
        } else {
          // No change for this selection
          DBIO.successful(0)
        }
      }

      db.run(DBIO.sequence(updates))
        .flatMap(counts => deleteFirstSeen(publisherId, removedIds).map(_ => counts.sum))
    }
  }

  def removeByAdProductCategory(publisherId: String, adProductCategory: String): Future[Int] = {
    // Get all pending selections for this publisher
    val query = selections.filter(_.publisherId === publisherId).result

    db.run(query).flatMap { rows =>
      val removedIds = rows
        .flatMap(r =>
          rowToSelection(r).ordered.filter(_.adProductCategory.exists(_.value == adProductCategory)).map(
            _.creativeId.value))
        .toSet
      // Process each selection: remove candidates with the blocked ad product category
      val updates = rows.map { row =>
        val sel = rowToSelection(row)
        val remaining = sel.ordered.filterNot(_.adProductCategory.exists(_.value == adProductCategory))

        if (remaining.isEmpty) {
          // No candidates left - delete the selection
          selections
            .filter(r => r.publisherId === publisherId && r.url === row.url && r.slotId === row.slotId)
            .delete
        } else if (remaining.size < sel.ordered.size) {
          // Some candidates removed - update the selection
          val updated = sel.copy(ordered = remaining, idx = 0, state = SelState.Pending)
          val updatedRow = row.copy(selectionJson = updated.toJson.compactPrint)
          selections.insertOrUpdate(updatedRow).map(_ => 1)
        } else {
          // No change for this selection
          DBIO.successful(0)
        }
      }

      db.run(DBIO.sequence(updates))
        .flatMap(counts => deleteFirstSeen(publisherId, removedIds).map(_ => counts.sum))
    }
  }

  private def rowToSelection(row: PendingSelectionRow): Selection =
    row.selectionJson.parseJson.convertTo[Selection]

  // ==================== First-Seen Tracking ====================

  private val firstSeenRows = TableQuery[PendingFirstSeenTable]

  def recordQueued(publisherId: String, creativeIds: Set[String], now: Instant): Future[Unit] =
    if (creativeIds.isEmpty) Future.successful(())
    else {
      val ts = java.sql.Timestamp.from(now)
      // first_seen is written once and deliberately never touched on
      // conflict — that single rule is what makes queue age honest across
      // the purge/re-queue cycle.
      // requeue_count is debounced to one increment per re-auction WAVE:
      // a wave upserts every (url, slot) placement within milliseconds, so
      // counting raw upserts would scale with placement count (~9×/wave)
      // and read as noise. Only a last_queued older than 60s counts as a
      // new wave — the number means "re-queue waves survived unreviewed".
      val actions = creativeIds.toSeq.map { cid =>
        sqlu"""
          INSERT INTO pending_first_seen (publisher_id, creative_id, first_seen, last_queued, requeue_count)
          VALUES ($publisherId, $cid, $ts, $ts, 0)
          ON CONFLICT (publisher_id, creative_id)
          DO UPDATE SET last_queued   = EXCLUDED.last_queued,
                        requeue_count = pending_first_seen.requeue_count +
                          CASE WHEN pending_first_seen.last_queued < EXCLUDED.last_queued - INTERVAL '60 seconds'
                               THEN 1 ELSE 0 END
        """
      }
      db.run(DBIO.sequence(actions)).map(_ => ())
    }

  def getFirstSeen(publisherId: String): Future[Map[String, FirstSeen]] = {
    val query = firstSeenRows.filter(_.publisherId === publisherId).result
    db.run(query).map { rows =>
      rows.map(r => r.creativeId -> FirstSeen(r.firstSeen, r.lastQueued, r.requeueCount)).toMap
    }
  }

  def deleteFirstSeen(publisherId: String, creativeIds: Set[String]): Future[Unit] =
    if (creativeIds.isEmpty) Future.successful(())
    else {
      val action = firstSeenRows
        .filter(r => r.publisherId === publisherId && r.creativeId.inSet(creativeIds))
        .delete
      db.run(action).map(_ => ())
    }

  // ==================== Approved Creatives Persistence ====================

  private val approvedCreatives = TableQuery[ApprovedCreativeTable]

  def ensureApprovedSchema(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration.*
    val createTableSql = sqlu"""
      CREATE TABLE IF NOT EXISTS approved_creative (
        publisher_id  VARCHAR(255) NOT NULL,
        creative_id   VARCHAR(255) NOT NULL,
        campaign_id   VARCHAR(255) NOT NULL,
        advertiser_id VARCHAR(255) NOT NULL,
        approved_at   TIMESTAMP    NOT NULL,
        approved_via  VARCHAR(16)  NOT NULL DEFAULT 'manual',
        PRIMARY KEY (publisher_id, creative_id)
      )
    """
    Await.result(db.run(createTableSql), 10.seconds)
    val addViaColumnSql = sqlu"""
      ALTER TABLE approved_creative ADD COLUMN IF NOT EXISTS approved_via VARCHAR(16) NOT NULL DEFAULT 'manual'
    """
    Await.result(db.run(addViaColumnSql), 10.seconds)
  }

  def ensureTrustSchema(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration.*
    val createTableSql = sqlu"""
      CREATE TABLE IF NOT EXISTS site_auto_approve_trust (
        publisher_id       VARCHAR(255) NOT NULL,
        anchor_type        VARCHAR(16)  NOT NULL,
        anchor_value       VARCHAR(512) NOT NULL,
        source_creative_id VARCHAR(255) NOT NULL,
        created_at         TIMESTAMP    NOT NULL,
        PRIMARY KEY (publisher_id, anchor_type, anchor_value)
      )
    """
    Await.result(db.run(createTableSql), 10.seconds)
  }

  def getApprovedCreativeIds(publisherId: String): Future[Set[String]] = {
    val query = approvedCreatives
      .filter(_.publisherId === publisherId)
      .map(_.creativeId)
      .result
    db.run(query).map(_.toSet)
  }

  def getApprovedCreativeAdvertisers(publisherId: String): Future[Map[String, String]] = {
    val query = approvedCreatives
      .filter(_.publisherId === publisherId)
      .map(r => (r.creativeId, r.advertiserId))
      .result
    db.run(query).map(_.toMap)
  }

  def getApprovedCreativeAdvertisersByCampaign(publisherId: String, campaignId: String): Future[Map[String, String]] = {
    val query = approvedCreatives
      .filter(r => r.publisherId === publisherId && r.campaignId === campaignId)
      .map(r => (r.creativeId, r.advertiserId))
      .result
    db.run(query).map(_.toMap)
  }

  def insertApproved(publisherId: String, creativeId: String, campaignId: String, advertiserId: String, via: String)
      : Future[Unit] = {
    val row = ApprovedCreativeRow(
      publisherId = publisherId,
      creativeId = creativeId,
      campaignId = campaignId,
      advertiserId = advertiserId,
      approvedAt = Instant.now(),
      approvedVia = via
    )
    db.run(approvedCreatives.insertOrUpdate(row)).map(_ => ())
  }

  def getApprovedCreativeMeta(publisherId: String): Future[Vector[ApprovedCreativeMeta]] = {
    val query = approvedCreatives
      .filter(_.publisherId === publisherId)
      .map(r => (r.creativeId, r.advertiserId, r.approvedVia))
      .result
    db.run(query).map(_.map(ApprovedCreativeMeta.apply.tupled).toVector)
  }

  // ==================== Auto-Approve Trust Anchors ====================

  private val trustAnchors = TableQuery[TrustAnchorTable]

  def insertTrustAnchors(publisherId: String, anchors: Seq[(String, String)], sourceCreativeId: String)
      : Future[Unit] = {
    if (anchors.isEmpty) Future.successful(())
    else {
      val now = Instant.now()
      val rows = anchors.map { case (anchorType, anchorValue) =>
        TrustAnchorRow(publisherId, anchorType, anchorValue, sourceCreativeId, now)
      }
      // insertOrUpdate keeps the ORIGINAL createdAt semantics irrelevant here:
      // re-approval refreshing the row is fine, the anchor key is what matters.
      db.run(DBIO.sequence(rows.map(trustAnchors.insertOrUpdate))).map(_ => ())
    }
  }

  def deleteTrustAnchorsFor(publisherId: String, campaignId: String, domain: Option[String]): Future[Int] = {
    val action = trustAnchors
      .filter(r =>
        r.publisherId === publisherId &&
        ((r.anchorType === TrustAnchor.TypeCampaign && r.anchorValue === campaignId) ||
        (r.anchorType === TrustAnchor.TypeDomain && r.anchorValue === domain.getOrElse("")))
      )
      .delete
    db.run(action)
  }

  def deleteTrustAnchor(publisherId: String, anchorType: String, anchorValue: String): Future[Boolean] = {
    val action = trustAnchors
      .filter(r => r.publisherId === publisherId && r.anchorType === anchorType && r.anchorValue === anchorValue)
      .delete
    db.run(action).map(_ > 0)
  }

  def getTrustAnchors(publisherId: String): Future[Vector[TrustAnchor]] = {
    val query = trustAnchors.filter(_.publisherId === publisherId).result
    db.run(query).map(_.map { row =>
      TrustAnchor(row.anchorType, row.anchorValue, row.sourceCreativeId, row.createdAt)
    }.toVector)
  }

  def deleteApproved(publisherId: String, creativeId: String): Future[Boolean] = {
    val action = approvedCreatives
      .filter(r => r.publisherId === publisherId && r.creativeId === creativeId)
      .delete
    db.run(action).map(_ > 0)
  }

  def deleteApprovedByCampaignId(publisherId: String, campaignId: String): Future[Int] = {
    val action = approvedCreatives
      .filter(r => r.publisherId === publisherId && r.campaignId === campaignId)
      .delete
    db.run(action)
  }

  def deleteApprovedByAdvertiserId(publisherId: String, advertiserId: String): Future[Int] = {
    val action = approvedCreatives
      .filter(r => r.publisherId === publisherId && r.advertiserId === advertiserId)
      .delete
    db.run(action)
  }

  // ==================== Flagging / Quarantine ====================

  private val flaggedCreatives = TableQuery[FlaggedCreativeTable]

  def ensureFlaggedSchema(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration.*
    val createTableSql = sqlu"""
      CREATE TABLE IF NOT EXISTS flagged_creative (
        publisher_id VARCHAR(255) NOT NULL,
        creative_id VARCHAR(255) NOT NULL,
        url TEXT NOT NULL,
        slot_id VARCHAR(255) NOT NULL,
        campaign_id VARCHAR(255) NOT NULL,
        advertiser_id VARCHAR(255) NOT NULL,
        category VARCHAR(255) NOT NULL,
        cpm DECIMAL(18,6) NOT NULL,
        reason TEXT NOT NULL,
        flagged_at TIMESTAMP NOT NULL,
        PRIMARY KEY (publisher_id, creative_id)
      )
    """
    Await.result(db.run(createTableSql), 10.seconds)
  }

  def flagCreative(
      publisherId: String,
      url: String,
      slotId: String,
      creativeId: String,
      reason: String
  ): Future[Option[FlaggedCreative]] = {
    // First, get the pending selection to find the candidate
    val query = selections
      .filter(r => r.publisherId === publisherId && r.url === url && r.slotId === slotId)
      .result
      .headOption

    db.run(query).flatMap {
      case None      => Future.successful(None)
      case Some(row) =>
        val sel = rowToSelection(row)
        sel.ordered.find(_.creativeId.value == creativeId) match {
          case None            => Future.successful(None)
          case Some(candidate) =>
            val now = Instant.now()
            // Insert into flagged table
            val flaggedRow = FlaggedCreativeRow(
              publisherId = publisherId,
              creativeId = creativeId,
              url = url,
              slotId = slotId,
              campaignId = candidate.campaignId.value,
              advertiserId = candidate.advertiserId.value,
              category = candidate.category.value,
              cpm = candidate.cpm.value,
              reason = reason,
              flaggedAt = now
            )
            val insertAction = flaggedCreatives.insertOrUpdate(flaggedRow)
            db.run(insertAction).flatMap { _ =>
              // Remove from pending selection
              removeCreativeFromPending(publisherId, url, slotId, creativeId).map { _ =>
                Some(FlaggedCreative(
                  creativeId = creativeId,
                  url = url,
                  slotId = slotId,
                  campaignId = candidate.campaignId.value,
                  advertiserId = candidate.advertiserId.value,
                  category = candidate.category.value,
                  cpm = candidate.cpm.value,
                  reason = reason,
                  flaggedAt = now
                ))
              }
            }
        }
    }
  }

  def unflagCreative(publisherId: String, creativeId: String): Future[Option[FlaggedCreative]] = {
    // Get the flagged creative to restore it
    val query = flaggedCreatives
      .filter(r => r.publisherId === publisherId && r.creativeId === creativeId)
      .result
      .headOption

    db.run(query).flatMap {
      case None             => Future.successful(None)
      case Some(flaggedRow) =>
        // Build the FlaggedCreative to return
        val flaggedCreative = FlaggedCreative(
          creativeId = flaggedRow.creativeId,
          url = flaggedRow.url,
          slotId = flaggedRow.slotId,
          campaignId = flaggedRow.campaignId,
          advertiserId = flaggedRow.advertiserId,
          category = flaggedRow.category,
          cpm = flaggedRow.cpm,
          reason = flaggedRow.reason,
          flaggedAt = flaggedRow.flaggedAt
        )

        // Delete from flagged table
        val deleteAction = flaggedCreatives
          .filter(r => r.publisherId === publisherId && r.creativeId === creativeId)
          .delete

        db.run(deleteAction).flatMap { _ =>
          // Get or create pending selection for this url/slot
          val pendingQuery = selections
            .filter(r => r.publisherId === publisherId && r.url === flaggedRow.url && r.slotId === flaggedRow.slotId)
            .result
            .headOption

          db.run(pendingQuery).flatMap { existingOpt =>
            // Create a candidate from the flagged info
            val candidate = Candidate(
              creativeId = CreativeId(flaggedRow.creativeId),
              campaignId = CampaignId(flaggedRow.campaignId),
              advertiserId = AdvertiserId(flaggedRow.advertiserId),
              cpm = CPM(flaggedRow.cpm),
              category = CategoryId(flaggedRow.category),
              landingDomain = "", // Not stored in flagged - will be looked up during approval
              preApproved = false,
              adProductCategory = None
            )

            val now = Instant.now()
            val selection = existingOpt match {
              case Some(row) =>
                // Add to existing pending selection
                val existing = rowToSelection(row)
                existing.copy(ordered = candidate +: existing.ordered)
              case None =>
                // Create new pending selection
                Selection(
                  publisherId = SiteId(publisherId),
                  url = URL(flaggedRow.url),
                  slotId = SlotId(flaggedRow.slotId),
                  ordered = Vector(candidate),
                  idx = 0,
                  state = SelState.Pending,
                  createdAt = now,
                  expiresAt = now.plusSeconds(7200) // 2 hours TTL
                )
            }
            upsertPending(selection).map(_ => Some(flaggedCreative))
          }
        }
    }
  }

  def getFlagged(publisherId: String): Future[Vector[FlaggedCreative]] = {
    val query = flaggedCreatives.filter(_.publisherId === publisherId).result
    db.run(query).map { rows =>
      rows.map { row =>
        FlaggedCreative(
          creativeId = row.creativeId,
          url = row.url,
          slotId = row.slotId,
          campaignId = row.campaignId,
          advertiserId = row.advertiserId,
          category = row.category,
          cpm = row.cpm,
          reason = row.reason,
          flaggedAt = row.flaggedAt
        )
      }.toVector
    }
  }
}

object SlickPendingSelectionStore {

  case class PendingSelectionRow(
      publisherId: String,
      url: String,
      slotId: String,
      selectionJson: String,
      expiresAt: Instant
  )

  class PendingSelectionTable(tag: Tag) extends Table[PendingSelectionRow](tag, "pending_selection") {
    def publisherId = column[String]("publisher_id")
    def url = column[String]("url")
    def slotId = column[String]("slot_id")
    def selectionJson = column[String]("selection_json")
    def expiresAt = column[Instant]("expires_at")

    def pk = primaryKey("pk_pending_selection", (publisherId, url, slotId))

    def * = (publisherId, url, slotId, selectionJson, expiresAt).mapTo[PendingSelectionRow]
  }

  case class PendingFirstSeenRow(
      publisherId: String,
      creativeId: String,
      firstSeen: Instant,
      lastQueued: Instant,
      requeueCount: Int
  )

  class PendingFirstSeenTable(tag: Tag) extends Table[PendingFirstSeenRow](tag, "pending_first_seen") {
    def publisherId = column[String]("publisher_id")
    def creativeId = column[String]("creative_id")
    def firstSeen = column[Instant]("first_seen")
    def lastQueued = column[Instant]("last_queued")
    def requeueCount = column[Int]("requeue_count")

    def pk = primaryKey("pk_pending_first_seen", (publisherId, creativeId))

    def * = (publisherId, creativeId, firstSeen, lastQueued, requeueCount).mapTo[PendingFirstSeenRow]
  }

  case class ApprovedCreativeRow(
      publisherId: String,
      creativeId: String,
      campaignId: String,
      advertiserId: String,
      approvedAt: Instant,
      approvedVia: String
  )

  class ApprovedCreativeTable(tag: Tag) extends Table[ApprovedCreativeRow](tag, "approved_creative") {
    def publisherId = column[String]("publisher_id")
    def creativeId = column[String]("creative_id")
    def campaignId = column[String]("campaign_id")
    def advertiserId = column[String]("advertiser_id")
    def approvedAt = column[Instant]("approved_at")
    def approvedVia = column[String]("approved_via")

    def pk = primaryKey("pk_approved_creative", (publisherId, creativeId))

    def * = (publisherId, creativeId, campaignId, advertiserId, approvedAt, approvedVia).mapTo[ApprovedCreativeRow]
  }

  case class TrustAnchorRow(
      publisherId: String,
      anchorType: String,
      anchorValue: String,
      sourceCreativeId: String,
      createdAt: Instant
  )

  class TrustAnchorTable(tag: Tag) extends Table[TrustAnchorRow](tag, "site_auto_approve_trust") {
    def publisherId = column[String]("publisher_id")
    def anchorType = column[String]("anchor_type")
    def anchorValue = column[String]("anchor_value")
    def sourceCreativeId = column[String]("source_creative_id")
    def createdAt = column[Instant]("created_at")

    def pk = primaryKey("pk_site_auto_approve_trust", (publisherId, anchorType, anchorValue))

    def * = (publisherId, anchorType, anchorValue, sourceCreativeId, createdAt).mapTo[TrustAnchorRow]
  }

  case class FlaggedCreativeRow(
      publisherId: String,
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

  class FlaggedCreativeTable(tag: Tag) extends Table[FlaggedCreativeRow](tag, "flagged_creative") {
    def publisherId = column[String]("publisher_id")
    def creativeId = column[String]("creative_id")
    def url = column[String]("url")
    def slotId = column[String]("slot_id")
    def campaignId = column[String]("campaign_id")
    def advertiserId = column[String]("advertiser_id")
    def category = column[String]("category")
    def cpm = column[BigDecimal]("cpm")
    def reason = column[String]("reason")
    def flaggedAt = column[Instant]("flagged_at")

    def pk = primaryKey("pk_flagged_creative", (publisherId, creativeId))

    def * = (publisherId, creativeId, url, slotId, campaignId, advertiserId, category, cpm, reason, flaggedAt).mapTo[
      FlaggedCreativeRow]
  }

  // JSON serialization for Selection
  import DefaultJsonProtocol.*

  given selStateFormat: JsonFormat[SelState] = new JsonFormat[SelState] {
    def write(state: SelState): JsValue = JsString(state.toString)
    def read(json: JsValue): SelState = json match {
      case JsString("Pending")  => SelState.Pending
      case JsString("Approved") => SelState.Approved
      case JsString("Rejected") => SelState.Rejected
      case _                    => deserializationError("Expected SelState")
    }
  }

  given instantFormat: JsonFormat[Instant] = new JsonFormat[Instant] {
    def write(instant: Instant): JsValue = JsNumber(instant.toEpochMilli)
    def read(json: JsValue): Instant = json match {
      case JsNumber(n) => Instant.ofEpochMilli(n.toLong)
      case _           => deserializationError("Expected epoch millis")
    }
  }

  given candidateFormat: JsonFormat[Candidate] = jsonFormat10(Candidate.apply)
  given selectionFormat: JsonFormat[Selection] = jsonFormat8(Selection.apply)
}
