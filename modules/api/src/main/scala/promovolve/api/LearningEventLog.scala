package promovolve.api

import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import promovolve.advertiser.CampaignEntity
import promovolve.api.projection.TrackingEventJournal
import promovolve.publisher.SiteEntity
import promovolve.publisher.delivery.AdServer
import promovolve.taxonomy.{ AffinityRegistryDData, ContentProductAffinityEntity, TaxonomyRankerEntity }
import promovolve.{ CreativeId, SiteId, Spend }

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

/**
 * EventLog implementation that routes impression/click events to learning entities.
 *
 * == Learning Flow ==
 * {{{
 * logImpression(event) → TaxonomyRankerEntity ! RecordImpression(revenue)  // category-level
 *                      → CampaignEntity ! RecordSpend(amount)              // budget tracking
 *                      → TrackingEventJournal.writeImpression()            // dashboard projection
 *
 * logClick(event)      → TaxonomyRankerEntity ! RecordClick()              // category-level
 *                      → AdServer ! RecordClick(creativeId)                // per-creative
 *                      → TrackingEventJournal.writeClick()                 // dashboard projection
 * }}}
 *
 * Per-creative impressions are NOT dispatched from here: AdServer records
 * them itself at serve time (BatchReservationsResolved), so the beacon only
 * carries category/spend/journal signal.
 *
 * == Two-Level Thompson Sampling ==
 * - TaxonomyRankerEntity: Category-level CTR learning (Beta posterior per category per site)
 * - AdServer: Per-creative CTR learning (in-memory, real-time feedback loop)
 *
 * All tracking events require campaign info to be provided directly in the TrackEvent.
 * Budget is reserved atomically via TryReserve; RecordSpend is idempotent via requestId.
 */
final class LearningEventLog(
    sharding: ClusterSharding,
    trackingJournal: Option[TrackingEventJournal] = None,
    affinityRegistry: Option[ActorRef[AffinityRegistryDData.Cmd]] = None,
    creativeRepo: Option[promovolve.publisher.CreativeRepo] = None
)(using system: ActorSystem[?])
    extends EventLog {

  private val log = org.slf4j.LoggerFactory.getLogger(classOf[LearningEventLog])

  def logImpression(e: TrackEvent): Unit = {
    (e.campaignId, e.advertiserId, e.cpm, e.category) match {
      case (Some(campId), Some(advId), Some(cpmVal), Some(cat)) if campId.nonEmpty && advId.nonEmpty =>
        log.debug(
          "Impression: cid={} campaign={} advertiser={} cpm={}",
          e.cid, campId, advId, cpmVal.toString
        )
        processImpression(e, campId, advId, cpmVal, cat)

      case _ =>
        // Missing required params - cannot track spend accurately
        log.error(
          "IMPRESSION_REJECTED: Missing required params: cid={} campaignId={} advertiserId={} cpm={} category={}",
          e.cid,
          e.campaignId.getOrElse("NONE"),
          e.advertiserId.getOrElse("NONE"),
          e.cpm.map(_.toString).getOrElse("NONE"),
          e.category.getOrElse("NONE")
        )
    }
  }

  /**
   * Process impression with directly-provided campaign info.
   *
   * When `e.dogeared` is true the serve was a pin-honor (the user had
   * already folded this creative). Bookmark fulfillment is not a
   * billable re-impression and the user is the same one who already
   * generated CTR/fold/category signal on the original view, so all
   * entity-level dispatches are skipped and only the journal write
   * runs. The journal entry carries the `dogeared` flag so the
   * dashboard projection can bump the parallel `dogeared_*` counters
   * (separate metric surface) without inflating primary totals.
   */
  private def processImpression(
      e: TrackEvent,
      campaignId: String,
      advertiserId: String,
      cpm: Double,
      category: String
  ): Unit = {
    val siteId = SiteId(e.pub)

    if (e.dogeared) {
      // Re-encounter via honored pin: silent on the auction + billing,
      // visible only on the dogeared_* counters via the journal write.
      trackingJournal.foreach(_.writeImpression(e))
      system.log.debug(
        "Dogeared impression (silent): pub={} cid={} campaign={}",
        e.pub, e.cid, campaignId
      )
    } else {
      // 1. Send impression to TaxonomyRankerEntity for category-level CTR learning
      val rankerEntityId = s"$category|${siteId.value}"
      val rankerRef = sharding.entityRefFor(TaxonomyRankerEntity.TypeKey, rankerEntityId)
      rankerRef ! TaxonomyRankerEntity.RecordImpression(
        revenue = cpm / 1000.0
      )

      // 1b. Send impression to ContentProductAffinityEntity for affinity learning
      //     Record for ALL page categories (not just the matched one) to discover cross-category affinities
      e.adProductCategory.foreach { apc =>
        val allCategories = e.pageCategories.map(_.split(",").toSet).getOrElse(Set(category))
        allCategories.foreach { cat =>
          val affinityEntityId = s"$cat|$apc"
          val affinityRef = sharding.entityRefFor(ContentProductAffinityEntity.TypeKey, affinityEntityId)
          affinityRef ! ContentProductAffinityEntity.RecordImpression(revenue = cpm / 1000.0)
          affinityRegistry.foreach(_ ! AffinityRegistryDData.Register(cat, apc))
        }
      }

      // 2. Per-creative impression tracking is done server-side in AdServer at
      // serve time (BatchReservationsResolved records recordImpression for each
      // served winner except honored pins), eliminating a separate HTTP call
      // that could fail. Note creativeStats.impressions therefore counts serves,
      // not rendered beacons, and (unlike serveStats.selected) excludes
      // dogeared pin-honors.

      // 3. Use requestId from TryReserve (budget already deducted)
      // RecordSpend will see this as duplicate and skip deduction
      val requestId = e.requestId match {
        case Some(rid) => rid // ULID string passed directly
        case None      =>
          // Should not happen with current serve flow, but generate deterministic fallback
          log.warn("Missing requestId in impression event: cid={} pub={}", e.cid, e.pub)
          jkugiya.ulid.ULID.getGenerator().base32()
      }

      // 4. Record spend to CampaignEntity for budget tracking
      // If requestId came from TryReserve, this will be idempotent (no double-deduct)
      val campaignEntityId = s"$advertiserId|$campaignId"
      val campaignRef = sharding.entityRefFor(CampaignEntity.TypeKey, campaignEntityId)
      campaignRef ! CampaignEntity.RecordSpend(
        requestId = requestId,
        amount = Spend(cpm / 1000.0),
        ts = Instant.now(),
        replyTo = system.ignoreRef
      )

      // 5. Notify SiteEntity of served impression for floor CPM optimization RL.
      //    Tag with the winning creative's category so the per-category floor
      //    optimizer (per-category floors) can attribute revenue; the
      //    site-wide optimizer ignores the tag.
      val siteRef = sharding.entityRefFor(SiteEntity.TypeKey, siteId.value)
      val impCategory = Option(category).map(_.trim).filter(_.nonEmpty)
      // Slot rides along so SiteEntity can exclude admin-overridden slots
      // from floor learning (their price is human-set, not sweep-governed).
      siteRef ! SiteEntity.ImpressionServed(cpm / 1000.0, impCategory, Option(e.slot).filter(_.nonEmpty))

      // 6. Write to journal for dashboard projection (fire-and-forget)
      trackingJournal.foreach(_.writeImpression(e))

      system.log.debug(
        "Recorded impression (direct): pub={} cid={} campaign={} cpm={} requestId={} fromReserve={}",
        e.pub,
        e.cid,
        campaignId,
        cpm,
        requestId,
        e.requestId.isDefined
      )
    }
  }

  def logClick(e: TrackEvent): Unit = {
    e.category match {
      case Some(cat) if cat.nonEmpty =>
        processClick(e, cat)

      case _ =>
        // Missing required category - cannot track click
        log.error(
          "CLICK_REJECTED: Missing category: cid={} pub={} slot={}",
          e.cid, e.pub, e.slot
        )
    }
  }

  private def processClick(e: TrackEvent, category: String): Unit = {
    val siteId = SiteId(e.pub)

    if (e.dogeared) {
      // Re-encounter click via honored pin: same user re-engaging with
      // a creative they already folded. Not a fresh CTR signal — skip
      // every entity-level dispatch and write only the journal so the
      // dogeared_clicks counter still moves.
      trackingJournal.foreach(_.writeClick(e))
      system.log.debug(
        "Dogeared click (silent): pub={} cid={} category={}",
        e.pub, e.cid, category
      )
    } else {
      // 1. Send click to TaxonomyRankerEntity for category-level CTR learning
      val rankerEntityId = s"$category|${siteId.value}"
      val rankerRef = sharding.entityRefFor(TaxonomyRankerEntity.TypeKey, rankerEntityId)
      rankerRef ! TaxonomyRankerEntity.RecordClick()

      // 1b. Send click to ContentProductAffinityEntity for affinity learning
      //     Record for ALL page categories to discover cross-category affinities
      e.adProductCategory.foreach { apc =>
        val allCategories = e.pageCategories.map(_.split(",").toSet).getOrElse(Set(category))
        allCategories.foreach { cat =>
          val affinityEntityId = s"$cat|$apc"
          val affinityRef = sharding.entityRefFor(ContentProductAffinityEntity.TypeKey, affinityEntityId)
          affinityRef ! ContentProductAffinityEntity.RecordClick()
        }
      }

      // 2. Send click to AdServer for per-creative Thompson Sampling
      val adServerRef = sharding.entityRefFor(AdServer.TypeKey, siteId.value)
      adServerRef ! AdServer.RecordClick(CreativeId(e.cid))

      // 4. Write to journal for dashboard projection (fire-and-forget)
      trackingJournal.foreach(_.writeClick(e))

      system.log.debug(
        "Recorded click: pub={} cid={} category={}",
        e.pub,
        e.cid,
        category
      )
    }
  }

  def logCTAClick(e: TrackEvent): Unit = {
    e.category match {
      case Some(cat) if cat.nonEmpty =>
        processCTAClick(e, cat)
      case _ =>
        log.error(
          "CTA_REJECTED: Missing category: cid={} pub={} slot={}",
          e.cid, e.pub, e.slot
        )
    }
  }

  private def processCTAClick(e: TrackEvent, category: String): Unit = {
    // Write to journal for dashboard projection. CTA has no entity-
    // level dispatches today, so the only effect of `e.dogeared` is in
    // DashboardProjectionHandler, which gates the primary
    // total_cta_clicks counter on !e.dogeared and bumps a parallel
    // dogeared_cta_clicks instead.
    trackingJournal.foreach(_.writeCTAClick(e))

    system.log.debug(
      "Recorded CTA click: pub={} cid={} category={} dogeared={}",
      e.pub,
      e.cid,
      category,
      e.dogeared.toString
    )
  }

  /**
   * Record a fold event. Fold tokens have already been verified upstream;
   * campaignId/advertiserId come from the signed payload. Folds are
   * **free** — no per-fold billing. The fold is a quality / engagement
   * signal (like Facebook likes), not a monetized event. We still
   * write the tracking_events row so the
   * dashboard projection + Thompson Sampling can use the fold rate
   * as a creative-quality input alongside CTR.
   *
   * Idempotency rides on `e.requestId` (the fold-token hash) — same
   * token can't fire two journal rows.
   */
  def logFold(e: TrackEvent): Unit = {
    (e.campaignId, e.advertiserId, e.requestId) match {
      case (Some(campId), Some(advId), Some(rid)) if campId.nonEmpty && advId.nonEmpty =>
        // Folds are an engagement signal, not a billing event.
        trackingJournal.foreach(_.writeFold(e))
        // Feed AdServer's per-creative Thompson Sampling posterior. Folds are
        // sampled as an independent Beta alongside CTR — see
        // ThompsonSampling.scoreCandidate.
        val adServerRef = sharding.entityRefFor(AdServer.TypeKey, e.pub)
        adServerRef ! AdServer.RecordFold(CreativeId(e.cid))
        log.debug(
          "Recorded fold (free, signal-only): pub={} cid={} campaign={}",
          e.pub, e.cid, campId
        )
      case _ =>
        log.error(
          "FOLD_REJECTED: Missing required params: cid={} campaignId={} advertiserId={} requestId={}",
          e.cid,
          e.campaignId.getOrElse("NONE"),
          e.advertiserId.getOrElse("NONE"),
          e.requestId.getOrElse("NONE")
        )
    }
  }

  /**
   * Record an unfold event. Telemetry only — no billing impact, no
   * idempotency requirement (multiple unfolds of the same slot are
   * uncommon and harmless to log). Looks up campaignId/advertiserId
   * from the creative because the unfold endpoint receives only
   * slotId+creativeId; the dashboard projection needs them to bump the
   * campaign-level retention metrics.
   */
  def logUnfold(e: TrackEvent): Unit = {
    creativeRepo match {
      case Some(repo) =>
        repo.get(e.cid).onComplete {
          case Success(Some(creative)) =>
            val enriched = e.copy(
              campaignId = Some(creative.campaignId),
              advertiserId = Some(creative.advertiserId)
            )
            trackingJournal.foreach(_.writeUnfold(enriched))
            log.debug(
              "Recorded unfold: pub={} cid={} slot={} campaign={}",
              e.pub, e.cid, e.slot, creative.campaignId
            )
          case Success(None) =>
            // Creative deleted between fold and unfold. Journal anyway so
            // the raw event count is correct; campaign-level retention
            // metrics will under-count this unfold (UPDATE finds no row),
            // which is acceptable for a deleted creative.
            trackingJournal.foreach(_.writeUnfold(e))
            log.warn("Unfold for unknown creative: cid={}", e.cid)
          case Failure(ex) =>
            trackingJournal.foreach(_.writeUnfold(e))
            log.warn("Creative lookup failed for unfold cid={}: {}", e.cid, ex.getMessage)
        }
      case None =>
        trackingJournal.foreach(_.writeUnfold(e))
    }
  }

  private given ExecutionContext = system.executionContext
}
