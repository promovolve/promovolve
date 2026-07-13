package promovolve.publisher.delivery

import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.cluster.ddata.{ LWWMap, LWWMapKey, ReplicatedData, SelfUniqueAddress }
import org.apache.pekko.cluster.ddata.typed.scaladsl.{ DistributedData, Replicator }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityTypeKey }
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import promovolve.{ CampaignPaused, * }
import promovolve.advertiser.{ AdvertiserEntity, CampaignEntity }
import promovolve.publisher.*
import promovolve.publisher.Keys.key
import promovolve.publisher.AssetPointer
import promovolve.publisher.delivery.Protocol.*
import promovolve.publisher.delivery.candidates.CandidateLogic
import promovolve.publisher.delivery.pacing.{ PacingLogic, TrafficObserver }
import promovolve.publisher.delivery.snapshot.SnapshotLogic
import promovolve.taxonomy.TaxonomyRankerEntity

import java.time.Instant

import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.*

/**
 * Ad server entity - one per publisher.
 *
 * Responsibilities:
 * - Receives candidates from AuctioneerEntity
 * - Manages ServeIndexDData (clear/append on auction results)
 * - Handles approval workflow (approve/reject from publisher dashboard)
 * - MAB selection on serve requests (future)
 * - In-flight ad tracking (future)
 */
object AdServer {

  // Re-export public protocol types for external use
  export Protocol.{
    Approve,
    ApproveAll,
    ApproveAllResult,
    AutoApproveInfo,
    BatchContentTooOld,
    BatchHostNotVerified,
    BatchSelect,
    BatchSelectResult,
    BatchSelected,
    BatchSiteSuspended,
    BatchSlotOutcome,
    BatchSlotSpec,
    CampaignPaused,
    CandidatesCollected,
    Command,
    CreativePaused,
    CreativeReactivated,
    CreativeStats,
    CreativeStatsMap,
    Done,
    EvictCampaignFromSite,
    EvictCampaignFromSlots,
    Flag,
    FlagResult,
    FlaggedItem,
    FlaggedList,
    GetAutoApproveInfo,
    GetCreativeStats,
    GetServeStats,
    ListFlagged,
    ListPending,
    MarkClassified,
    Passivate,
    PendingItem,
    PendingList,
    RecordClick,
    RecordFold,
    RecordImpression,
    RefreshTTLForAdvertiser,
    RefreshTTLForCampaign,
    Reject,
    RemoveAdvertiser,
    RemoveTrustAnchor,
    RevokeCreativeApproval,
    ServeStats,
    TrustAnchorRemoved,
    Unflag,
    UnflagResult
  }

  /**
   * Outcome of `recordRequestArrival`: tells the caller (case Select /
   * case BatchSelect) what to do after the per-request lifecycle ran.
   */
  private[delivery] sealed trait ArrivalAction
  private[delivery] object ArrivalAction {
    case object Proceed extends ArrivalAction
    case object SkipWarmup extends ArrivalAction
    case object SkipRolloverGrace extends ArrivalAction
  }

  /** DData type for domain blocklist */
  private type BlocklistMap = LWWMap[SiteId, PublisherEntity.CachedDomainBlocklist]

  /**
   * Strip the port from a verified host (e.g. "example.com:8080" → "example.com")
   * and lowercase it. Domain blocklists are stored as bare lower-case hostnames,
   * so the comparison must be performed in that shape.
   */
  private[delivery] def mySiteDomainOpt(verifiedHost: Option[String]): Option[String] =
    verifiedHost.map { vh =>
      val lower = vh.toLowerCase.trim
      val i = lower.indexOf(':')
      if (i >= 0) lower.substring(0, i) else lower
    }.filter(_.nonEmpty)

  /**
   * Serve-time host gate: does the ad-tag page URL's host match the site's
   * verified host? Extracted from the BatchSelect handler so the matching
   * rules are unit-testable. Two relaxations on top of an exact compare:
   *   - a leading `www.` is canonicalized off both sides (WordPress www/apex
   *     split), and
   *   - two private/LAN hosts match when their ports agree or either is bare
   *     (dev convenience; a public host still needs an exact host match).
   * Returns false when no verified host is known yet (DData not published).
   */
  private[delivery] def hostMatches(pageUrl: String, verifiedHost: Option[String]): Boolean =
    verifiedHost.exists { vh =>
      val u = pageUrl.toLowerCase
      val noScheme = if (u.startsWith("http://")) u.drop(7) else if (u.startsWith("https://")) u.drop(8) else u
      val requestHost = noScheme.takeWhile(c => c != '/' && c != '?')
      def port(h: String): String = { val i = h.indexOf(':'); if (i >= 0) h.substring(i + 1) else "" }
      val rp = port(requestHost); val vp = port(vh)
      promovolve.publisher.SiteEntity.canonicalHost(requestHost) ==
        promovolve.publisher.SiteEntity.canonicalHost(vh) ||
        (promovolve.publisher.SiteEntity.isPrivateHost(requestHost) &&
        promovolve.publisher.SiteEntity.isPrivateHost(vh) &&
        (rp == vp || rp.isEmpty || vp.isEmpty))
    }

  /**
   * Served winners that must record a per-creative impression: every filled
   * slot EXCEPT honored dog-ear pins (which stay learning-silent, matching the
   * billing/CTR treatment in LearningEventLog). Pure — extracted from the
   * BatchReservationsResolved handler so the "impress winners, skip honored
   * pins" contract is unit-testable.
   */
  private[delivery] def impressedCreatives(outcomes: Vector[Protocol.BatchSlotOutcome]): Vector[CreativeId] =
    outcomes.flatMap(o => o.winner.filterNot(_ => o.dogear.exists(_.honored)).map(_.creativeId))

  /**
   * Pure freshness-token math for a page's classification (see
   * `reclassifyInMsFor`). Returns ms until the page should be re-classified:
   * `> 0` fresh (the ad tag does nothing), `<= 0` needs (re)classification —
   * cold (`None` → 0) or stale (aged past the window). The window is the
   * publisher's classification-freshness window when set (`> 0`), else the 48h default.
   * Extracted so the freshness-window handling is unit-testable — it was once
   * hardcoded to 0 downstream, silently forcing the default and ignoring the
   * publisher's setting.
   */
  private[delivery] def reclassifyInMs(classifiedAtMs: Option[Long], freshnessWindowMs: Long, now: Long): Long = {
    val window = if (freshnessWindowMs > 0) freshnessWindowMs else DefaultReclassifyWindowMs
    classifiedAtMs match {
      case Some(ts) => (ts + window) - now
      case None     => 0L
    }
  }

  /**
   * Partition candidates by the advertiser-side site-domain blocklist.
   * Direction inversion vs. publisher-side: drop the advertiser, not the
   * landing domain. No-op (everything passes through) when the site's
   * verified host is unknown — there's nothing to compare against until
   * DData publishes it.
   *
   * @return (blocked, allowed)
   */
  private[delivery] def partitionByAdvertiserBlocklist(
      candidates: Vector[Candidate],
      blocklists: Map[AdvertiserId, Set[String]],
      siteDomain: Option[String]
  ): (Vector[Candidate], Vector[Candidate]) = siteDomain match {
    case Some(domain) if blocklists.nonEmpty =>
      candidates.partition(c => blocklists.get(c.advertiserId).exists(_.contains(domain)))
    case _ =>
      (Vector.empty, candidates)
  }

  /** Traffic ratio published via DData for floor CPM agent consumption by SiteEntity */
  final case class TrafficRatioData(ratio: Double, isWarmedUp: Boolean) extends CborSerializable
  val TrafficRatioKey: LWWMapKey[SiteId, TrafficRatioData] = LWWMapKey("adserver-traffic-ratio")

  /**
   * Per-page winner cache: which campaigns / creatives have already
   * won a slot on a given (site × pageUrl). `campaigns` is a soft
   * penalty (different creatives from the same campaign may still
   * compete at reduced score); `creatives` is a HARD exclusion —
   * responsive creatives register multiple CandidateView entries
   * under one creativeId (one per size), and serving the same
   * creativeId twice on one page always renders the same visual ad.
   * TTL cleanup (the `pageWinnersTtl` parameter on
   * `AdServer.apply`) evicts entries so reloads and re-readers
   * get a fresh auction.
   */
  final case class PageWinners(
      campaigns: Set[String],
      firstSeenMs: Long,
      creatives: Set[String] = Set.empty
  ) extends CborSerializable
  val PageWinnersKey: LWWMapKey[String, PageWinners] = LWWMapKey("adserver-page-winners")

  /**
   * Default per-page-winners TTL — 15s covers a slow page load + DOM
   * mutations so an advertiser doesn't appear in two slots within
   * one render. Configurable via `promovolve.adserver.page-winners-ttl`
   * in application.conf; lower it (e.g. 1s) when smoke-testing in
   * dev with one advertiser, where rapid reloads otherwise see
   * empty slots.
   */
  val DefaultPageWinnersTtl: FiniteDuration = 15.seconds

  /**
   * Strip query + fragment and lowercase so `?utm=…` variants don't
   * bypass the per-page cap.
   */
  def normalizePageUrl(raw: String): String = {
    val lower = raw.toLowerCase
    val noFrag = lower.indexOf('#') match {
      case -1 => lower
      case i  => lower.substring(0, i)
    }
    val noQuery = noFrag.indexOf('?') match {
      case -1 => noFrag
      case i  => noFrag.substring(0, i)
    }
    noQuery
  }

  def pageWinnersKeyFor(siteId: SiteId, url: URL): String =
    s"${siteId.value}|${normalizePageUrl(url.value)}"

  /**
   * Score multiplier applied to candidates whose campaigns have
   * already won a slot on the current page. Not 0 — that'd be a
   * hard exclusion. Not 1 — that'd remove the cap entirely. 0.3
   * chosen so a repeat-campaign candidate competes but only wins
   * when it's clearly stronger than non-repeat alternatives,
   * gracefully degrading when the pool is narrow.
   */
  val PageCapPenalty: Double = 0.3

  /**
   * Greedy assignment of candidates to slots for batch serve.
   *
   * Algorithm:
   *   1. Score every candidate in the pool once via
   *      ThompsonSampling.scoreCandidate (shared with the per-slot
   *      path, so both auctions produce identical scores).
   *   2. Sort slots by area descending — biggest slot gets first
   *      pick of the pool. Matches publisher expectation that a
   *      billboard is more valuable than a mobile banner.
   *   3. For each slot in order, pick the highest-scoring candidate
   *      that:
   *        - exactly matches the slot's width × height,
   *        - passes the site's floor CPM,
   *        - isn't from a campaign already in pageWinners (prior
   *          page auctions) or already assigned earlier in this
   *          batch.
   *   4. Once picked, record the campaign in the batch-local
   *      winners set so subsequent slots skip it.
   *   5. Compute the slot's clearing price as the quality-adjusted
   *      second price against the runner-up among that slot's
   *      eligible candidates (`ThompsonSampling.qualityAdjustedClearing`).
   *
   * Pure — takes every input as a parameter, returns the outcomes.
   * Easy to unit-test.
   */
  def batchAssign(
      view: ServeView,
      slots: Vector[Protocol.BatchSlotSpec],
      siteFloor: CPM,
      pageWinners: Set[String],
      alpha: Double,
      stats: Map[CreativeId, Protocol.CreativeStats],
      rng: scala.util.Random,
      categoryFloors: Map[CategoryId, CPM] = Map.empty,
      // Per-slot admin floor overrides (slotId → floor). Override wins over
      // category/site floors, mirroring the auctioneer's admission floor.
      adminSlotFloors: Map[SlotId, CPM] = Map.empty
  ): Vector[Protocol.BatchSlotOutcome] = {
    def catFloor(cat: CategoryId): CPM = categoryFloors.getOrElse(cat, siteFloor)
    // Score every candidate once — same scoring function the per-slot
    // exploitation loop uses, so batch winners are comparable to
    // per-slot winners under the same pool.
    val scored: Vector[(CandidateView, ThompsonSampling.CandidateScore)] = view.candidates.map { c =>
      val st = stats.getOrElse(c.creativeId, Protocol.CreativeStats())
      (c, ThompsonSampling.scoreCandidate(c, st, rng, alpha))
    }

    // Sort slots by rendered pixel area descending so the premium
    // slot (biggest) gets first pick from the pool.
    val orderedSlots = slots.sortBy(s => -(s.width.toLong * s.height))

    val usedCampaigns = scala.collection.mutable.Set.empty[String]
    usedCampaigns ++= pageWinners
    // Hard-exclude both creativeIds and campaignIds already assigned
    // in this batch. Same creativeId → visual duplicate (responsive
    // creatives expose one creativeId at many sizes). Same campaignId
    // → same advertiser, which always prefers a single placement over
    // competing with itself on the same page.
    val usedCreatives = scala.collection.mutable.Set.empty[CreativeId]

    orderedSlots.map { slot =>
      // Fluid creatives render at any slot dimension, so the picker
      // applies floor + same-page-campaign / same-creative dedup only.
      // Slot-size policy is a publisher-side concern handled by the
      // auctioneer's slot fan-out, not by serve-time pixel matching.
      // Admin slot override beats category/site floors — same precedence
      // the auctioneer used to admit the bid.
      def slotFloor(cat: CategoryId): CPM =
        adminSlotFloors.get(slot.slotId).getOrElse(catFloor(cat))
      val eligible = scored.collect {
        case (c, s)
            if c.cpm.toDouble >= slotFloor(c.category).toDouble
            && !usedCreatives.contains(c.creativeId)
            && !usedCampaigns.contains(c.campaignId.value) =>
          (c, s)
      }

      if (eligible.isEmpty) {
        Protocol.BatchSlotOutcome(slot.slotId, None)
      } else {
        val sortedEligible = eligible.sortBy { case (_, s) => -s.score }
        val (winner, winnerScore) = sortedEligible.head
        // Same-category second price, clamped to the winner's category floor.
        // Pricing reads the posterior-MEAN side of CandidateScore (meanScore
        // / engagement), never the Thompson draws — sample for allocation,
        // price on means, so the same auction state clears at the same price.
        val bestLoserScore = sortedEligible.tail
          .collectFirst { case (c, s) if c.category == winner.category => s.meanScore }
          .getOrElse(0.0)
        val clearing = ThompsonSampling.qualityAdjustedClearing(
          winnerEngagement = winnerScore.meanEngagement,
          winnerBid = winner.cpm,
          bestLoserScore = bestLoserScore,
          alpha = alpha,
          siteFloor = slotFloor(winner.category)
        )
        usedCampaigns += winner.campaignId.value
        usedCreatives += winner.creativeId
        Protocol.BatchSlotOutcome(
          slotId = slot.slotId,
          winner = Some(winner),
          clearingPrice = clearing,
          requestId = java.util.UUID.randomUUID().toString
        )
      }
    }
  }

  /**
   * Pick the best candidate for a slot together with its quality-
   * adjusted clearing price. Pure helper shared by the batch
   * assignment and the retry loop. Mirrors the size + floor +
   * hard-dedup logic from [[batchAssign]] but scoped to a single
   * slot so the retry path can reuse it unchanged.
   *
   * Clearing is the runner-up's posterior-mean score inverted through
   * the score formula given the winner's posterior-mean engagement
   * (see `ThompsonSampling.qualityAdjustedClearing`) — selection
   * samples, pricing is deterministic given the stats.
   * Returns `siteFloor` clearing when only one candidate fits.
   */
  def pickBestForSlot(
      slot: Protocol.BatchSlotSpec,
      pool: Vector[CandidateView],
      usedCampaigns: Set[String],
      alpha: Double,
      stats: Map[CreativeId, Protocol.CreativeStats],
      siteFloor: CPM,
      rng: scala.util.Random,
      categoryFloors: Map[CategoryId, CPM] = Map.empty,
      // The slot's admin floor override. Takes precedence over category and
      // site floors — same precedence the auctioneer used to admit the bid,
      // so a publisher-approved winner is never blocked at serve by a floor
      // the slot explicitly overrides. Also sets the clearing clamp.
      adminSlotFloor: Option[CPM] = None
  ): Option[(CandidateView, CPM)] = {
    // Admin slot override first, then per-category floor, then site floor.
    // Empty map + no override → `siteFloor`, i.e. legacy single-floor behavior.
    def catFloor(cat: CategoryId): CPM =
      adminSlotFloor.getOrElse(categoryFloors.getOrElse(cat, siteFloor))
    // Pool is the union of every slot's ServeView candidates (deduped by
    // creativeId). Slot-size eligibility was enforced upstream by the
    // auctioneer; fluid creatives render at any dimension. So serve-time
    // filtering is just floor (per the candidate's category) + per-page dedup.
    val fitting = pool.filter { c =>
      c.cpm.toDouble >= catFloor(c.category).toDouble &&
      !usedCampaigns.contains(c.campaignId.value)
    }
    if (fitting.isEmpty) None
    else {
      val scored = fitting.map { c =>
        val st = stats.getOrElse(c.creativeId, Protocol.CreativeStats())
        (c, ThompsonSampling.scoreCandidate(c, st, rng, alpha))
      }
      val sorted = scored.sortBy { case (_, s) => -s.score }
      val (winner, winnerScore) = sorted.head
      // Same-category second price: price the winner against the best loser
      // IN ITS OWN category (cross-category runners-up never set the price),
      // clamped to the winner's category floor. Pricing reads the posterior-
      // MEAN side of CandidateScore, never the Thompson draws.
      val bestLoserScore = sorted.tail
        .collectFirst { case (c, s) if c.category == winner.category => s.meanScore }
        .getOrElse(0.0)
      val clearing = ThompsonSampling.qualityAdjustedClearing(
        winnerEngagement = winnerScore.meanEngagement,
        winnerBid = winner.cpm,
        bestLoserScore = bestLoserScore,
        alpha = alpha,
        siteFloor = catFloor(winner.category)
      )
      Some((winner, clearing))
    }
  }

  /**
   * Reserve budgets for the batch's initial winners with per-slot
   * retry. Pure function of its parameters — `reserve` is injectable
   * so unit tests can mock the reservation primitive (success /
   * failure / per-campaign rules) without spinning up an actor
   * system. The instance method inside AdServer wires in the real
   * `batchReserveOne` (which asks CampaignEntity + AdvertiserEntity).
   *
   * Loop (per iteration):
   *   1. Fire `reserve` for every slot still needing reservation.
   *   2. Successes become confirmed outcomes; failures become retry
   *      slots.
   *   3. For each failed slot, pick the next-best candidate from the
   *      pool minus (already-tried for this slot ∪ already-confirmed
   *      as another slot's winner). Re-score against the current
   *      `usedCampaigns` set so the soft cap reflects in-batch wins.
   *   4. If no next candidate fits: slot becomes winner=None, drop
   *      out.
   *   5. Recurse on the new retry set.
   *
   * Bounded: each iteration removes at least one candidate from each
   * failed slot's eligibility. Terminates in ≤ pool.size iterations.
   *
   * Returns (finalOutcomes, pendingSpendDeltas) — the deltas fold
   * into `pendingSpendByCampaign` so concurrent batch requests don't
   * over-reserve before impression events record the spend.
   */
  /**
   * Dog-ear: produce a fallthrough `DogearOutcome` for a slot whose pin
   * hint couldn't be honored.
   *
   * Returns:
   *   - `None` for slots without a pin, OR for pins whose creative is
   *     still currently approved on this site (transient miss — the
   *     creative isn't in this batch's pool but it's still eligible,
   *     so the client should keep its pin and re-honor next batch).
   *   - `Some(creative_removed)` only for pins whose creative has
   *     been revoked or removed from approval. The bootstrap acts on
   *     this signal to clear the IDB pin.
   *
   * `isApproved` is supplied by the AdServer instance from its
   * `persistedApprovedIds` set — the source of truth for what's
   * eligible to serve right now on this site.
   */
  private[delivery] def dogearFallthrough(
      slot: Protocol.BatchSlotSpec,
      isApproved: CreativeId => Boolean
  ): Option[Protocol.DogearOutcome] =
    slot.pin match {
      case Some(cid) if !isApproved(cid) =>
        Some(Protocol.DogearOutcome(honored = false, reason = Some("creative_removed")))
      case _ =>
        None
    }

  def batchReserveWithRetry(
      slots: Vector[Protocol.BatchSlotSpec],
      pool: Vector[CandidateView],
      pageBlocked: Set[String],
      alpha: Double,
      stats: Map[CreativeId, Protocol.CreativeStats],
      siteFloor: CPM,
      // reserve is given the requestId to reserve under; the SAME id must
      // land on the served slot's BatchSlotOutcome so the impression beacon's
      // RecordSpend dedupes against this reservation (otherwise every
      // delivered impression is charged twice — reserve + record).
      reserve: (CandidateView, CPM, String) => Future[Boolean],
      rng: scala.util.Random,
      excludedCreatives: Set[CreativeId] = Set.empty,
      excludedCampaigns: Set[CampaignId] = Set.empty,
      isApproved: CreativeId => Boolean = _ => true,
      // Pool used for pin-honor lookup. Defaults to `pool` (the
      // floor-filtered auction pool) which is what the existing tests
      // exercise. Callers in the live serve path should pass the
      // pre-floor `eligibleCandidates` set instead — pins are
      // explicit reader bookmarks and shouldn't be voided just because
      // sweep is currently testing a floor candidate above the pinned
      // creative's CPM. See `format/dog-ear.md` ("pins bypass floor")
      // for the design intent.
      pinLookupPool: Vector[CandidateView] = Vector.empty,
      categoryFloors: Map[CategoryId, CPM] = Map.empty,
      // Per-slot admin floor overrides (slotId → floor). Override wins over
      // category/site floors, mirroring the auctioneer's admission floor —
      // an approved winner admitted under a slot override must not be
      // blocked here by the higher site floor.
      adminSlotFloors: Map[SlotId, CPM] = Map.empty
  )(using ExecutionContext): Future[(Vector[Protocol.BatchSlotOutcome], Map[CampaignId, Double])] = {
    val effectivePinPool = if (pinLookupPool.isEmpty) pool else pinLookupPool
    // Initial assignment: largest slot first, greedy. Track what each
    // slot has "tried" so retries don't re-pick a failed creative.
    // Fluid creatives render at any slot size, so once one wins for a
    // page its creativeId is held in `usedCreatives` to prevent the
    // same visual ad from filling another slot on the same page.
    // Process pinned slots BEFORE unpinned ones so the pinned
    // creative's campaign goes into `used` first; otherwise a non-
    // pinned slot could pick another creative from the same campaign
    // before the pin honors, breaking the one-campaign-per-page rule.
    // Pinned slots stay area-sorted (premium gets first pick when
    // multiple pins compete for the same campaign). Unpinned slots
    // are shuffled so a low-supply page doesn't always starve the
    // smaller slots — over time every slot gets some impressions.
    val (pinnedSlots, unpinnedSlots) = slots.partition(_.pin.isDefined)
    val ordered =
      pinnedSlots.sortBy(s => -(s.width.toLong * s.height)) ++
      rng.shuffle(unpinnedSlots)
    var tried: Map[SlotId, Set[CreativeId]] = Map.empty
    // `used` is the HARD exclusion set: off-page-pin campaigns (the
    // dog-ear is a "save for later" gesture — surfacing other creatives
    // from the bookmarked advertiser before the reader engages the pin
    // would feel like stalking) plus campaigns assigned earlier in this
    // batch (competitive separation within a single page load).
    //
    // pageBlocked (campaigns that won this URL on recent reloads, 15s TTL)
    // is deliberately NOT seeded here. It's a SOFT preference applied per
    // pick via `pickWithPagePref`: prefer a campaign that hasn't recently
    // won this page, but never blank a slot just to avoid a repeat. With
    // a single eligible advertiser the alternative to "repeat" is "empty
    // slot" (lost impression) — so a sole advertiser repeats instead.
    var used: Set[String] = excludedCampaigns.map(_.value)

    def pickWithPagePref(
        slot: Protocol.BatchSlotSpec,
        available: Vector[CandidateView],
        hardUsed: Set[String]
    ): Option[(CandidateView, CPM)] =
      pickBestForSlot(slot, available, hardUsed ++ pageBlocked, alpha, stats, siteFloor, rng, categoryFloors,
        adminSlotFloors.get(slot.slotId))
        .orElse(pickBestForSlot(slot, available, hardUsed, alpha, stats, siteFloor, rng, categoryFloors,
          adminSlotFloors.get(slot.slotId)))
    // excludedCreatives: pinned by the user for slots not on this
    // page. Site-wide block — those creatives never appear anywhere
    // except their own pinned slot, so the user's saved selection
    // doesn't get squandered as a normal-auction impression elsewhere.
    var usedCreatives: Set[CreativeId] = excludedCreatives
    val initialConfirmed = Vector.newBuilder[Protocol.BatchSlotOutcome]
    // Each pending-reservation entry carries the slot's clearing price
    // (computed at pick time against that slot's runner-up) so the
    // reservation call, the BatchSlotOutcome, and the pending-spend
    // delta all see the same quality-adjusted price.
    val initialToReserve = Vector.newBuilder[(Protocol.BatchSlotSpec, CandidateView, CPM)]
    ordered.foreach { slot =>
      // Dog-ear pin-honoring: if the slot carries a pin hint AND the pinned
      // creative is still in the auction pool, bypass the auction entirely.
      // Pinned re-encounters are reader bookmarks — no CPM reservation runs
      // for this serve. The slot still consumes a campaign/creative slot in
      // the page-cap state so other slots don't double up on the same advertiser.
      val pinned: Option[CandidateView] =
        slot.pin.flatMap(cid => effectivePinPool.find(_.creativeId == cid))
      pinned match {
        case Some(c) =>
          used = used + c.campaignId.value
          usedCreatives = usedCreatives + c.creativeId
          initialConfirmed += Protocol.BatchSlotOutcome(
            slotId = slot.slotId,
            winner = Some(c),
            clearingPrice = CPM.zero, // Pin re-encounter is free — no CPM clearing
            requestId = java.util.UUID.randomUUID().toString,
            dogear = Some(Protocol.DogearOutcome(honored = true))
          )
        case None =>
          val available = pool.filterNot(c => usedCreatives.contains(c.creativeId))
          pickWithPagePref(slot, available, used) match {
            case None =>
              initialConfirmed += Protocol.BatchSlotOutcome(
                slotId = slot.slotId,
                winner = None,
                dogear = dogearFallthrough(slot, isApproved)
              )
            case Some((c, clearing)) =>
              tried = tried.updated(slot.slotId, Set(c.creativeId))
              used = used + c.campaignId.value
              usedCreatives = usedCreatives + c.creativeId
              initialToReserve += ((slot, c, clearing))
          }
      }
    }

    def loop(
        toReserve: Vector[(Protocol.BatchSlotSpec, CandidateView, CPM)],
        confirmed: Vector[Protocol.BatchSlotOutcome],
        triedMap: Map[SlotId, Set[CreativeId]],
        usedCamps: Set[String],
        pending: Map[CampaignId, Double]
    ): Future[(Vector[Protocol.BatchSlotOutcome], Map[CampaignId, Double])] = {
      if (toReserve.isEmpty) return Future.successful((confirmed, pending))
      val attempts: Vector[Future[(Protocol.BatchSlotSpec, CandidateView, CPM, String, Boolean)]] =
        toReserve.map { case (slot, cand, clearing) =>
          // Reserve at the quality-adjusted clearing price, not the
          // bid. Otherwise pacing budgets see in-flight spend that
          // overstates what the campaign actually owes. The requestId
          // minted here is both the reservation key AND the served
          // slot's outcome id, so the impression's RecordSpend dedupes.
          val rid = java.util.UUID.randomUUID().toString
          reserve(cand, clearing, rid).map(ok => (slot, cand, clearing, rid, ok))
        }
      Future.sequence(attempts).flatMap { results =>
        // Two-pass: record ALL successes first so subsequent retries
        // see the complete set of locked creatives/campaigns. A
        // single-pass interleaving could pick the same creative
        // twice if a failure is processed before a sibling success.
        var newConfirmed = confirmed
        var newPending = pending
        var newTried = triedMap
        var newUsed = usedCamps
        val failures = Vector.newBuilder[(Protocol.BatchSlotSpec, CandidateView)]
        for ((slot, cand, clearing, rid, success) <- results) {
          if (success) {
            newConfirmed = newConfirmed :+ Protocol.BatchSlotOutcome(
              slotId = slot.slotId,
              winner = Some(cand),
              clearingPrice = clearing,
              requestId = rid,
              dogear = dogearFallthrough(slot, isApproved)
            )
            // Pending spend delta tracks the actual reserved amount —
            // clearing price, not bid — so concurrent batches see
            // accurate in-flight totals.
            val delta = clearing.toDouble / 1000.0
            newPending = newPending.updated(
              cand.campaignId,
              newPending.getOrElse(cand.campaignId, 0.0) + delta
            )
          } else {
            failures += ((slot, cand))
          }
        }

        // Second pass: retry failed slots. Now lockedCreatives sees
        // every winner confirmed in this iteration.
        val nextPicks = Vector.newBuilder[(Protocol.BatchSlotSpec, CandidateView, CPM)]
        for ((slot, cand) <- failures.result()) {
          newUsed = newUsed - cand.campaignId.value
          val alreadyTried = newTried.getOrElse(slot.slotId, Set.empty) + cand.creativeId
          newTried = newTried.updated(slot.slotId, alreadyTried)
          val lockedCreatives = newConfirmed.flatMap(_.winner).map(_.creativeId).toSet
          val available = pool
            .filterNot(c => alreadyTried.contains(c.creativeId))
            .filterNot(c => lockedCreatives.contains(c.creativeId))
          pickWithPagePref(slot, available, newUsed) match {
            case None =>
              newConfirmed = newConfirmed :+ Protocol.BatchSlotOutcome(
                slotId = slot.slotId,
                winner = None,
                dogear = dogearFallthrough(slot, isApproved)
              )
            case Some((nextC, nextClearing)) =>
              newTried = newTried.updated(slot.slotId, alreadyTried + nextC.creativeId)
              newUsed = newUsed + nextC.campaignId.value
              nextPicks += ((slot, nextC, nextClearing))
          }
        }
        loop(nextPicks.result(), newConfirmed, newTried, newUsed, newPending)
      }
    }
    loop(initialToReserve.result(), initialConfirmed.result(), tried, used, Map.empty)
  }

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("ad-server")

  // Upper bound on the per-site classified-url memory (pageCategoriesCache).
  // Drives needText cold-detection; bounded so on-demand classification of
  // many distinct urls can't grow it without limit. Mirrors the spirit of
  // SiteEntity.MaxPersistedClassifications / AuctioneerEntity lastPage cap.
  val MaxPageCategoriesCache: Int = 50000

  // Fallback reclassify window when the publisher's classification-freshness window is
  // unset/0. ~48h: a page is re-classified by traffic at most once per window.
  val DefaultReclassifyWindowMs: Long = 48L * 60 * 60 * 1000

  // ---------- Behavior ----------
  def apply(
      publisherId: SiteId,
      store: PendingSelectionStore,
      creativeRepo: CreativeRepo,
      serveIndex: ActorRef[ServeIndexDData.Cmd],
      sharding: ClusterSharding,
      statsSnapshotRepo: CreativeStatsSnapshotRepo,
      trafficShapeSnapshotRepo: TrafficShapeSnapshotRepo,
      budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
      pacingStrategy: PacingStrategy = AdaptivePacing(), // Simple rate-based pacing
      approvedTtl: FiniteDuration = 2.hours,
      purgeInterval: FiniteDuration = 5.minutes,
      snapshotInterval: FiniteDuration = 1.hour,
      pageWinnersTtl: FiniteDuration = DefaultPageWinnersTtl,
      rng: Random = new Random()
  )(using system: ActorSystem[?]): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        given ExecutionContext = ctx.executionContext

        // Schedule periodic purge of expired pending selections
        timers.startTimerAtFixedRate(PurgeExpired, purgeInterval)

        // Schedule periodic stats snapshots for dashboard
        timers.startTimerAtFixedRate(SnapshotStats, snapshotInterval)

        // Schedule periodic cleanup of stale pending spend entries
        timers.startTimerAtFixedRate(CleanupStalePending, 5.seconds)

        // Schedule periodic traffic ratio publish to DData (for floor CPM agent in SiteEntity)
        timers.startTimerAtFixedRate(PublishTrafficRatio, 10.seconds)

        // Sweep expired page-winner entries — the serve-time cap relies
        // on these staying within the page-load window so a revisit after
        // 30 seconds is a clean auction.
        timers.startTimerAtFixedRate(SweepPageWinners, 5.seconds)

        // Subscribe to publisher's domain blacklist from DData via message adapter
        val replicator = DistributedData(system).replicator
        given selfUniqueAddress: SelfUniqueAddress = DistributedData(system).selfUniqueAddress

        // DData update response handler for traffic ratio (ignored)
        val ddataUpdateAdapter: ActorRef[Replicator.UpdateResponse[?]] =
          ctx.messageAdapter[Replicator.UpdateResponse[?]](_ => NoOp)

        ctx.log.info("Setting up DData subscriptions for site {}", publisherId.value)

        // Type aliases for clarity
        type PacingConfigMap = LWWMap[SiteId, SiteEntity.PacingConfig]
        type AdProductBlocklistMap = LWWMap[SiteId, SiteEntity.CachedAdProductBlocklist]
        type AutoApproveMap = LWWMap[SiteId, SiteEntity.CachedAutoApprove]
        type SiteSuspendedMap = LWWMap[SiteId, SiteEntity.CachedSiteSuspended]
        type VerifiedHostMap = LWWMap[SiteId, String]
        type PageWinnersMap = LWWMap[String, PageWinners]
        type AdvertiserBlocklistMap = LWWMap[AdvertiserId, AdvertiserEntity.CachedSiteDomainBlocklist]

        // IMPORTANT: Due to type erasure, we must use a SINGLE messageAdapter for all
        // SubscribeResponse messages. Multiple adapters with the same erased type
        // can cause messages to be routed to the wrong adapter.
        val subscribeAdapter = ctx.messageAdapter[Replicator.SubscribeResponse[ReplicatedData]] {
          case changed: Replicator.Changed[?] =>
            changed.key match {
              case PublisherEntity.DomainBlocklistCacheKey =>
                val dataMap = changed.dataValue.asInstanceOf[BlocklistMap]
                val siteIdFound = dataMap.get(publisherId).isDefined
                ctx.self ! DDataSubscriptionDebug("Changed", changed.key.id, "DomainBlocklistCacheKey", siteIdFound,
                  dataMap.entries.size)
                BlocklistUpdated(dataMap.get(publisherId))
              case AdvertiserEntity.DomainBlocklistCacheKey =>
                val dataMap = changed.dataValue.asInstanceOf[AdvertiserBlocklistMap]
                AdvertiserBlocklistsUpdated(dataMap.entries.map { case (k, v) => k -> v.domains })
              case SiteEntity.PacingConfigKey =>
                PacingConfigUpdated(changed.dataValue.asInstanceOf[PacingConfigMap].get(publisherId))
              case SiteEntity.AdProductBlocklistKey =>
                AdProductBlocklistUpdated(changed.dataValue.asInstanceOf[AdProductBlocklistMap].get(publisherId))
              case SiteEntity.AutoApproveKey =>
                AutoApproveConfigUpdated(changed.dataValue.asInstanceOf[AutoApproveMap].get(publisherId))
              case SiteEntity.SiteSuspendedKey =>
                SiteSuspendedConfigUpdated(changed.dataValue.asInstanceOf[SiteSuspendedMap].get(publisherId))
              case SiteEntity.VerifiedHostKey =>
                VerifiedHostUpdated(changed.dataValue.asInstanceOf[VerifiedHostMap].get(publisherId))
              case PageWinnersKey =>
                PageWinnersSnapshot(changed.dataValue.asInstanceOf[PageWinnersMap])
              case other =>
                ctx.self ! DDataSubscriptionDebug("Changed", other.id, "unknown", false, 0)
                NoOp
            }
          case other =>
            ctx.self ! DDataSubscriptionDebug("Other", other.getClass.getSimpleName, "n/a", false, 0)
            NoOp
        }

        // Similarly, a single adapter for Get responses
        val getAdapter = ctx.messageAdapter[Replicator.GetResponse[ReplicatedData]] {
          case success: Replicator.GetSuccess[?] =>
            success.key match {
              case PublisherEntity.DomainBlocklistCacheKey =>
                val dataMap = success.dataValue.asInstanceOf[BlocklistMap]
                val siteIdFound = dataMap.get(publisherId).isDefined
                ctx.self ! DDataSubscriptionDebug("GetSuccess", success.key.id, "DomainBlocklistCacheKey", siteIdFound,
                  dataMap.entries.size)
                BlocklistUpdated(dataMap.get(publisherId))
              case AdvertiserEntity.DomainBlocklistCacheKey =>
                val dataMap = success.dataValue.asInstanceOf[AdvertiserBlocklistMap]
                AdvertiserBlocklistsUpdated(dataMap.entries.map { case (k, v) => k -> v.domains })
              case SiteEntity.PacingConfigKey =>
                PacingConfigUpdated(success.dataValue.asInstanceOf[PacingConfigMap].get(publisherId))
              case SiteEntity.AdProductBlocklistKey =>
                AdProductBlocklistUpdated(success.dataValue.asInstanceOf[AdProductBlocklistMap].get(publisherId))
              case SiteEntity.AutoApproveKey =>
                AutoApproveConfigUpdated(success.dataValue.asInstanceOf[AutoApproveMap].get(publisherId))
              case SiteEntity.SiteSuspendedKey =>
                SiteSuspendedConfigUpdated(success.dataValue.asInstanceOf[SiteSuspendedMap].get(publisherId))
              case SiteEntity.VerifiedHostKey =>
                VerifiedHostUpdated(success.dataValue.asInstanceOf[VerifiedHostMap].get(publisherId))
              case PageWinnersKey =>
                PageWinnersSnapshot(success.dataValue.asInstanceOf[PageWinnersMap])
              case other =>
                ctx.self ! DDataSubscriptionDebug("GetSuccess", other.id, "unknown", false, 0)
                NoOp
            }
          case notFound: Replicator.NotFound[?] =>
            ctx.self ! DDataSubscriptionDebug("NotFound", notFound.key.id, "n/a", false, 0)
            NoOp // Key not yet written, this is normal
          case other =>
            ctx.self ! DDataSubscriptionDebug("GetOther", other.getClass.getSimpleName, "n/a", false, 0)
            NoOp
        }

        // Subscribe to all DData keys using the shared adapter
        replicator ! Replicator.Subscribe(PublisherEntity.DomainBlocklistCacheKey, subscribeAdapter)
        replicator ! Replicator.Subscribe(AdvertiserEntity.DomainBlocklistCacheKey, subscribeAdapter)
        replicator ! Replicator.Subscribe(SiteEntity.PacingConfigKey, subscribeAdapter)
        replicator ! Replicator.Subscribe(SiteEntity.AdProductBlocklistKey, subscribeAdapter)
        replicator ! Replicator.Subscribe(SiteEntity.AutoApproveKey, subscribeAdapter)
        replicator ! Replicator.Subscribe(SiteEntity.SiteSuspendedKey, subscribeAdapter)
        replicator ! Replicator.Subscribe(SiteEntity.VerifiedHostKey, subscribeAdapter)
        replicator ! Replicator.Subscribe(PageWinnersKey, subscribeAdapter)

        // Get initial values using the shared adapter
        replicator ! Replicator.Get(PublisherEntity.DomainBlocklistCacheKey, Replicator.ReadLocal, getAdapter)
        replicator ! Replicator.Get(AdvertiserEntity.DomainBlocklistCacheKey, Replicator.ReadLocal, getAdapter)
        replicator ! Replicator.Get(SiteEntity.PacingConfigKey, Replicator.ReadLocal, getAdapter)
        replicator ! Replicator.Get(SiteEntity.AdProductBlocklistKey, Replicator.ReadLocal, getAdapter)
        replicator ! Replicator.Get(SiteEntity.AutoApproveKey, Replicator.ReadLocal, getAdapter)
        replicator ! Replicator.Get(SiteEntity.SiteSuspendedKey, Replicator.ReadLocal, getAdapter)
        replicator ! Replicator.Get(SiteEntity.VerifiedHostKey, Replicator.ReadLocal, getAdapter)
        replicator ! Replicator.Get(PageWinnersKey, Replicator.ReadLocal, getAdapter)

        // Subscribe to budget events from CampaignEntity for pacing cache + pause/removal handling
        val budgetEventAdapter = ctx.messageAdapter[BudgetEvent] {
          case su: SpendUpdate                          => SpendInfoUpdated(Some(su))
          case promovolve.CampaignPaused(campaignId, _) =>
            // EXPLICIT advertiser pause/delete (published only by
            // CampaignEntity.UpdateStatus) — the one sender allowed to
            // revoke approvals. See Protocol.CampaignPaused.
            Protocol.CampaignPaused(campaignId, revokeApprovals = true)
          case promovolve.CampaignAdProductChanged(campaignId, _) =>
            // Config edit, not a leave — approvals survive.
            Protocol.CampaignPaused(campaignId)
          case CreativeStatusChanged(creativeId, _, campaignId, isActive, _) =>
            if (!isActive) Protocol.CreativePaused(creativeId)
            else Protocol.CreativeReactivated(creativeId, campaignId)
          case AdvertiserSuspended(advertiserId, _) =>
            Protocol.RemoveAdvertiser(advertiserId)
          case _ => NoOp
        }
        budgetEventTopic ! Topic.Subscribe(budgetEventAdapter)

        // Load traffic shape snapshot on startup (restore learned patterns from previous run)
        ctx.pipeToSelf(trafficShapeSnapshotRepo.get(publisherId.value)) {
          case Success(snapshot) => TrafficShapeSnapshotLoaded(snapshot)
          case Failure(_)        => TrafficShapeSnapshotLoaded(None)
        }

        // Load recent creative stats from tracking_events on startup (restore Thompson Sampling state)
        ctx.pipeToSelf(statsSnapshotRepo.loadRecentStats(publisherId.value)) {
          case Success(stats) => CreativeStatsLoaded(stats)
          case Failure(_)     => CreativeStatsLoaded(Map.empty)
        }

        // Load persisted approved creative IDs on startup (survive restarts).
        // A failed load must NOT become an empty set: the fallback approval
        // check is ServeIndex membership, which is circular after a restart —
        // an entity that boots "unapproved" can never repair itself. Failures
        // log at ERROR and retry with backoff (handler below).
        ctx.pipeToSelf(store.getApprovedCreativeMeta(publisherId.value)) {
          case Success(rows) =>
            ApprovedCreativeIdsLoaded(
              rows.map(r => CreativeId(r.creativeId)).toSet,
              rows.map(r => CreativeId(r.creativeId) -> AdvertiserId(r.advertiserId)).toMap,
              autoApproved = rows.collect { case r if r.approvedVia == "auto" => CreativeId(r.creativeId) }.toSet
            )
          case Failure(e) => ApprovedCreativeIdsLoadFailed(e.getMessage, attempt = 1)
        }

        // Load auto-approve trust anchors on startup. Same retry discipline
        // as the approvals load, but the safe degradation is inverted: with
        // no anchors, candidates queue for MANUAL review — a failed load can
        // delay auto-approvals but never wrongly grant one.
        ctx.pipeToSelf(store.getTrustAnchors(publisherId.value)) {
          case Success(anchors) =>
            TrustAnchorsLoaded(
              anchors.collect { case a if a.anchorType == TrustAnchor.TypeCampaign => CampaignId(a.anchorValue) }.toSet,
              anchors.collect { case a if a.anchorType == TrustAnchor.TypeDomain => a.anchorValue }.toSet
            )
          case Failure(e) => TrustAnchorsLoadFailed(e.getMessage, attempt = 1)
        }

        new AdServer(
          publisherId,
          store,
          creativeRepo,
          serveIndex,
          sharding,
          statsSnapshotRepo,
          trafficShapeSnapshotRepo,
          pacingStrategy,
          approvedTtl,
          pageWinnersTtl,
          ctx,
          rng,
          replicator,
          ddataUpdateAdapter,
          selfUniqueAddress,
          budgetEventTopic,
          budgetEventAdapter
        ).behavior()
      }
    }
}

private[delivery] class AdServer(
    siteId: SiteId,
    store: PendingSelectionStore,
    creativeRepo: CreativeRepo,
    serveIndex: ActorRef[ServeIndexDData.Cmd],
    sharding: ClusterSharding,
    statsSnapshotRepo: CreativeStatsSnapshotRepo,
    trafficShapeSnapshotRepo: TrafficShapeSnapshotRepo,
    initialPacingStrategy: PacingStrategy,
    approvedTtl: FiniteDuration,
    pageWinnersTtl: FiniteDuration,
    ctx: ActorContext[AdServer.Command],
    rng: Random,
    replicator: ActorRef[Replicator.Command],
    ddataUpdateAdapter: ActorRef[Replicator.UpdateResponse[?]],
    selfUniqueAddress: SelfUniqueAddress,
    budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
    budgetEventAdapter: ActorRef[BudgetEvent]
)(using system: ActorSystem[?], ec: ExecutionContext) {

  // Cached as a Long so the hot filter loops don't re-call .toMillis on every entry.
  private val pageWinnersTtlMs: Long = pageWinnersTtl.toMillis

  import AdServer.*

  private val log = LoggerFactory.getLogger(getClass)

  // Traffic observer for rate tracking
  private val trafficObserver = TrafficObserver()

  // Page categories cache: url → all content categories (for affinity learning)
  private var pageCategoriesCache: Map[String, Set[String]] = Map.empty
  // url → classifiedAt epoch-ms. Recorded the MOMENT a page is classified
  // (via MarkClassified from the auctioneer + via CandidatesCollected),
  // INDEPENDENT of whether the auction drew a bid — so a classified-but-no-
  // bidder page is not re-classified on every serve. Drives the reclassifyInMs
  // freshness token / needText. Bounded like pageCategoriesCache. Transient
  // (lost on restart; repopulated by RestoreClassifications -> re-auction).
  private var classifiedAtMsByUrl: Map[String, Long] = Map.empty
  // Serve-miss self-heal throttle: url → last re-auction request time (ms).
  // When a serve finds a slot's ServeView missing/empty, we ask the
  // auctioneer to repopulate the index for that URL — but throttle per-URL so
  // a burst of misses doesn't spam re-auctions (one Reevaluate repopulates all
  // the URL's slots). This makes cold/cleared slots fill in ~1s instead of
  // waiting for the periodic backstop.
  private var lastReauctionRequestMs: Map[String, Long] = Map.empty
  private val selfHealThrottleMs: Long = 15000L
  private def selfHealReauction(url: URL): Unit = {
    val now = System.currentTimeMillis()
    if (now - lastReauctionRequestMs.getOrElse(url.value, 0L) >= selfHealThrottleMs) {
      lastReauctionRequestMs = lastReauctionRequestMs.updated(url.value, now)
      sharding.entityRefFor(promovolve.auction.AuctioneerEntity.TypeKey, siteId.value) !
      promovolve.auction.AuctioneerEntity.Reevaluate(url)
      log.info("Serve-miss self-heal: requested re-auction pub={} url={}", siteId.value, url.value)
    }
  }

  // Record that a url was classified at `ts` (epoch-ms). Bounded.
  private def recordClassified(urlValue: String, ts: Long): Unit = {
    classifiedAtMsByUrl = classifiedAtMsByUrl + (urlValue -> ts)
    if (classifiedAtMsByUrl.size > AdServer.MaxPageCategoriesCache)
      classifiedAtMsByUrl = classifiedAtMsByUrl.drop(classifiedAtMsByUrl.size - AdServer.MaxPageCategoriesCache)
  }

  // Freshness token: ms until this page's classification should be refreshed.
  // <= 0 means "(re)classify now" — covers both never-classified (no record →
  // 0) and stale (aged past the window). > 0 means fresh. The reclassify window
  // is the classification-freshness window when set, else a 48h default.
  private def reclassifyInMsFor(urlValue: String, freshnessWindowMs: Long): Long =
    AdServer.reclassifyInMs(classifiedAtMsByUrl.get(urlValue), freshnessWindowMs, System.currentTimeMillis())
  // Track pending creative IDs per (url|slot) to suppress duplicate SSE events on re-auction
  private var lastPendingCreativeIds: Map[String, Set[String]] = Map.empty
  // Site floor CPM for clearing price floor (synced via DData PacingConfig)
  private var siteFloorCpm: CPM = CPM(0.50)
  // Per-category floors, carried on
  // CandidatesCollected from the auctioneer. Empty in off/shadow →
  // `categoryFloorFor` falls back to `siteFloorCpm` (legacy behavior).
  private var siteCategoryFloors: Map[CategoryId, CPM] = Map.empty
  private def categoryFloorFor(cat: CategoryId): CPM =
    siteCategoryFloors.getOrElse(cat, siteFloorCpm)
  // Per-slot admin floor overrides, carried on CandidatesCollected with the
  // auctioneer's own resolution (admin map, then AdSlotSpec-carried). The
  // serve-time floor gate must honor these: a candidate admitted under the
  // slot's override — and then publisher-approved — must not be silently
  // blocked at serve by the higher site/category floor. Self-heals like
  // siteFloorCpm: every auction delivery refreshes (or clears) its slot.
  private var adminSlotFloors: Map[SlotId, CPM] = Map.empty
  // Bid weight exponent for scoring: CPM^α (updated from PacingConfig via DData)
  private var siteBidWeight: Double = 0.5

  // ═══════════════════════════════════════════════════════════════════════════
  // INVERTED INDEX HELPERS
  // Maintain keysByCampaign / keysByCreative / keysByAdvertiser for targeted removal
  // ═══════════════════════════════════════════════════════════════════════════

  /** Replace index entries for a key with the given candidates (used on Put). */
  private def updateIndexesOnPut(state: State, key: String, candidates: Vector[CandidateView]): State = {
    val cleaned = removeKeyFromIndexes(state, key)
    candidates.foldLeft(cleaned) { (s, c) => addCandidateToIndexes(s, key, c) }
  }

  /** Add one candidate's IDs to the indexes (used on Append). */
  private def addCandidateToIndexes(state: State, key: String, candidate: CandidateView): State = {
    val (camps, creas, advs) = state.idsForKey.getOrElse(key, (Set.empty, Set.empty, Set.empty))
    state.copy(
      keysByCampaign = state.keysByCampaign.updatedWith(candidate.campaignId)(ks =>
        Some(ks.getOrElse(Set.empty) + key)),
      keysByCreative = state.keysByCreative.updatedWith(candidate.creativeId)(ks =>
        Some(ks.getOrElse(Set.empty) + key)),
      keysByAdvertiser = state.keysByAdvertiser.updatedWith(candidate.advertiserId)(ks =>
        Some(ks.getOrElse(Set.empty) + key)),
      idsForKey = state.idsForKey.updated(key,
        (camps + candidate.campaignId, creas + candidate.creativeId, advs + candidate.advertiserId))
    )
  }

  /** Remove a key from all 3 inverted index maps using reverse lookup (O(K) where K = IDs for this key). */
  private def removeKeyFromIndexes(state: State, key: String): State =
    state.idsForKey.get(key) match {
      case None                             => state
      case Some((campIds, creaIds, advIds)) =>
        val s1 = campIds.foldLeft(state.keysByCampaign) { (m, id) =>
          m.updatedWith(id)(_.map(_ - key).filter(_.nonEmpty))
        }
        val s2 = creaIds.foldLeft(state.keysByCreative) { (m, id) =>
          m.updatedWith(id)(_.map(_ - key).filter(_.nonEmpty))
        }
        val s3 = advIds.foldLeft(state.keysByAdvertiser) { (m, id) =>
          m.updatedWith(id)(_.map(_ - key).filter(_.nonEmpty))
        }
        state.copy(
          keysByCampaign = s1,
          keysByCreative = s2,
          keysByAdvertiser = s3,
          idsForKey = state.idsForKey - key
        )
    }

  private[delivery] def behavior(state: State = State()): Behavior[Command] = {
    // Compose partial functions for each concern area
    val handlers: PartialFunction[Command, Behavior[Command]] =
      configUpdates(state).orElse(
        candidateProcessing(state)).orElse(
        approvalWorkflow(state)).orElse(
        selectionPipeline(state)).orElse(
        statsRecording(state)).orElse(
        maintenance(state))

    Behaviors.receiveMessage[Command](handlers).receiveSignal {
      case (_, org.apache.pekko.actor.typed.PostStop) =>
        log.info("AdServer stopping for publisher {}, unsubscribing from topics", siteId.value)
        budgetEventTopic ! Topic.Unsubscribe(budgetEventAdapter)
        // Best-effort shape save: passivation/rebalance/shutdown would
        // otherwise discard up to an hour of learning (the periodic
        // SnapshotStats cadence). Bounded blocking is acceptable in
        // PostStop; failures are non-fatal (the hourly snapshot remains).
        try
          scala.concurrent.Await.ready(
            trafficShapeSnapshotRepo.upsert(siteId.value, state.trafficShapeTracker.toSnapshot),
            scala.concurrent.duration.DurationInt(2).seconds
          )
        catch {
          case scala.util.control.NonFatal(ex) =>
            log.warn("Traffic shape save on stop failed for {}: {}", siteId.value, ex.getMessage)
        }
        Behaviors.same
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CONFIG UPDATES
  // Handles DData subscription updates: spend info, blocklists, pacing config
  // ═══════════════════════════════════════════════════════════════════════════

  private def configUpdates(state: State): PartialFunction[Command, Behavior[Command]] = {
    import state.*
    {
      case SpendInfoUpdated(Some(su)) =>
        // NOTE: We cache ALL SpendUpdates, not just for participating campaigns.
        // SpendUpdates arrive before auctions run, so we can't filter at this point.
        // The participatingCampaigns filtering is applied only when RESETTING campaigns
        // on day rollover, which is where cross-site interference was occurring.

        // Filter out stale SpendUpdates from before the current day
        // This prevents race conditions where old updates arrive after day rollover
        // ONLY for simulated days - for real calendar days (86400), CampaignEntity's dayStart
        // (midnight) is always "before" server start time but is NOT stale
        val staleToleranceMs = 5000L
        val isStale = dayDurationSeconds != 86400 && lastDayStart.exists(currentDayStart =>
          su.dayStart.toEpochMilli < currentDayStart.toEpochMilli - staleToleranceMs
        )
        if (isStale) {
          log.info(
            "Ignoring stale SpendUpdate: campaign={} updateDayStart={} currentDayStart={} (>{}ms behind)",
            su.campaignId.value,
            su.dayStart,
            lastDayStart.getOrElse("none"),
            staleToleranceMs
          )
          Behaviors.same
        } else {
          // Fresh SpendUpdate arrived - clear grace period (cache is now populated)
          val clearedGrace = if (rolloverGraceUntilMs > 0) {
            log.info("Grace period ended: fresh SpendUpdate received for campaign={}", su.campaignId.value)
            0L
          } else rolloverGraceUntilMs
          val cached = CachedSpendInfo(su.advertiserId, su.dailyBudget, su.todaySpend, su.dayStart, su.timestamp)
          // Initialize lastDayStart from SpendUpdate if not yet set
          val updatedDayStart = lastDayStart.orElse(Some(su.dayStart))
          if (lastDayStart.isEmpty) {
            log.info("Initialized lastDayStart from SpendUpdate: campaign={} dayStart={}", su.campaignId.value,
              su.dayStart)
          }
          log.debug(
            "SpendUpdate received: campaign={} spend={} budget={} dayStart={}",
            su.campaignId.value,
            su.todaySpend.value,
            su.dailyBudget.value,
            su.dayStart
          )
          val now = Instant.now()
          behavior(state.copy(
            lastDayStart = updatedDayStart,
            spendInfoCache = spendInfoCache.updated(su.campaignId, cached),
            spendInfoLastUpdated = spendInfoLastUpdated.updated(su.campaignId, now),
            rolloverGraceUntilMs = clearedGrace
          ))
        }

      case SpendInfoUpdated(None) =>
        // Ignore non-SpendUpdate budget events
        Behaviors.same

      case BlocklistUpdated(newBlocklist) =>
        // Find newly blocked domains (in new but not in old)
        val oldDomains = state.cachedDomainBlocklist.map(_.domains).getOrElse(Set.empty)
        val newDomains = newBlocklist.map(_.domains).getOrElse(Set.empty)
        val newlyBlocked = newDomains -- oldDomains

        // Log whether we actually received blocklist data for our site
        if (newBlocklist.isEmpty) {
          log.warn(
            "BlocklistUpdated received with EMPTY data: site={} - this site may not be registered in PublisherEntity's siteIds. " +
            "Check that syncToDData() includes this siteId.",
            siteId.value
          )
        }
        log.info(
          "BlocklistUpdated received: site={} blocklistPresent={} oldDomains={} newDomains={} newlyBlocked={}",
          siteId.value, newBlocklist.isDefined: java.lang.Boolean,
          oldDomains.mkString(","), newDomains.mkString(","), newlyBlocked.mkString(",")
        )

        // Proactively remove creatives with newly blocked landing domains from ServeIndex
        if (newlyBlocked.nonEmpty) {
          log.info(
            "Domain blocklist updated for site {}: {} new domains blocked, removing from ServeIndex",
            siteId.value,
            newlyBlocked.size
          )
          serveIndex ! ServeIndexDData.RemoveByDomains(siteId.value, newlyBlocked)

          // Also remove from pending queue
          newlyBlocked.foreach { domain =>
            store.removeByLandingDomain(siteId.value, domain).foreach { count =>
              if (count > 0) log.info("Removed {} pending selections with blocked domain {}", count, domain)
            }
          }
        }

        behavior(state.copy(cachedDomainBlocklist = newBlocklist))

      case AdvertiserBlocklistsUpdated(snapshot) =>
        // Detect advertisers newly blocking THIS site's domain so we can proactively
        // scrub ServeIndex + pending queue. Removed bindings will be cleaned up
        // organically on the next auction's filter step.
        AdServer.mySiteDomainOpt(state.verifiedHost).foreach { domain =>
          val oldBlockers = state.cachedAdvertiserBlocklists.collect {
            case (advId, domains) if domains.contains(domain) => advId
          }.toSet
          val newBlockers = snapshot.collect {
            case (advId, domains) if domains.contains(domain) => advId
          }.toSet
          val newlyBlocking = newBlockers -- oldBlockers
          if (newlyBlocking.nonEmpty) {
            log.info(
              "Advertiser blocklist update: {} advertisers newly block site domain {} (site={}); scrubbing ServeIndex",
              newlyBlocking.size, domain, siteId.value
            )
            newlyBlocking.foreach { advId =>
              serveIndex ! ServeIndexDData.RemoveAdvertiserBySite(siteId.value, advId)
              store.removeByAdvertiserId(siteId.value, advId.value).foreach { count =>
                if (count > 0) log.info(
                  "Removed {} pending selections for advertiser {} (now blocks site domain)",
                  count, advId.value
                )
              }
              store.deleteApprovedByAdvertiserId(siteId.value, advId.value)
            }
          }
        }
        log.info(
          "Advertiser blocklist snapshot: site={} advertisers_with_blocklists={}",
          siteId.value, snapshot.size
        )
        behavior(state.copy(cachedAdvertiserBlocklists = snapshot))

      case DDataSubscriptionDebug(eventType, keyId, keyMatched, siteIdFound, dataSize) =>
        // Debug logging for DData subscription events - helps trace blocklist update flow
        log.debug(
          "DData subscription event: site={} eventType={} keyId={} keyMatched={} siteIdFound={} dataSize={}",
          siteId.value, eventType, keyId, keyMatched, siteIdFound: java.lang.Boolean, dataSize: java.lang.Integer
        )
        // Log at info level if this looks like a potential issue (blocklist key but our site not found)
        if (keyMatched == "DomainBlocklistCacheKey" && !siteIdFound && dataSize > 0) {
          log.warn(
            "DData blocklist: site {} NOT FOUND in DomainBlocklistCacheKey map (map has {} entries). " +
            "Blocklist updates for this site will be ignored until siteId is added to the map.",
            siteId.value, dataSize: java.lang.Integer
          )
        }
        Behaviors.same

      case NoOp =>
        // Ignore - used for type-erased DData messages that don't match our key
        Behaviors.same

      case AdProductBlocklistUpdated(newBlocklist) =>
        // Find newly blocked categories (in new but not in old)
        val oldCategories = state.cachedAdProductBlocklist.map(_.categories).getOrElse(Set.empty)
        val newCategories = newBlocklist.map(_.categories).getOrElse(Set.empty)
        val newlyBlocked = newCategories -- oldCategories

        // Proactively remove creatives with newly blocked ad product categories from ServeIndex
        if (newlyBlocked.nonEmpty) {
          log.info(
            "Ad product blocklist updated for site {}: {} new categories blocked, removing from ServeIndex",
            siteId.value,
            newlyBlocked.size
          )
          serveIndex ! ServeIndexDData.RemoveByAdProductCategories(siteId.value, newlyBlocked)

          // Also remove from pending queue
          newlyBlocked.foreach { category =>
            store.removeByAdProductCategory(siteId.value, category.value).foreach { count =>
              if (count > 0)
                log.info("Removed {} pending selections with blocked ad product category {}", count, category.value)
            }
          }
        } else {
          newBlocklist.foreach { blocklist =>
            log.info(
              "Ad product blocklist updated for site {}: {} categories blocked",
              siteId.value,
              blocklist.categories.size
            )
          }
        }

        behavior(state.copy(cachedAdProductBlocklist = newBlocklist))

      case AutoApproveConfigUpdated(configOpt) =>
        val wasEnabled = state.cachedAutoApprove.exists(_.enabled)
        val nowEnabled = configOpt.exists(_.enabled)
        if (wasEnabled != nowEnabled) {
          log.info("AdServer {} auto-approve toggle updated: {} -> {}", siteId.value,
            wasEnabled: java.lang.Boolean, nowEnabled: java.lang.Boolean)
        }
        val newState = state.copy(cachedAutoApprove = configOpt)
        // Rising edge: creatives already sitting in the queue must benefit
        // from the opt-in without waiting for organic re-auction traffic.
        if (!wasEnabled && nowEnabled) sweepPendingForTrust(newState)
        behavior(newState)

      case SiteSuspendedConfigUpdated(configOpt) =>
        val was = state.siteSuspended
        val now = configOpt.exists(_.suspended)
        if (was != now) {
          log.info("AdServer {} operator suspension updated: {} -> {}", siteId.value,
            was: java.lang.Boolean, now: java.lang.Boolean)
        }
        behavior(state.copy(siteSuspended = now))

      case TrustSweepReauction(urls) =>
        if (urls.nonEmpty) {
          log.info("Auto-approve trust sweep: re-auctioning {} page(s) with matching pending creatives on site {}",
            urls.size: java.lang.Integer, siteId.value)
          val auctioneerRef =
            sharding.entityRefFor(promovolve.auction.AuctioneerEntity.TypeKey, siteId.value)
          urls.foreach(u => auctioneerRef ! promovolve.auction.AuctioneerEntity.Reevaluate(URL(u)))
        }
        Behaviors.same

      case VerifiedHostUpdated(hostOpt) =>
        log.info("AdServer {} verified host updated: {}", siteId.value, hostOpt.getOrElse("none"))
        behavior(state.copy(verifiedHost = hostOpt))

      case PageWinnersSnapshot(data) =>
        // Mirror only entries scoped to this site so cross-site entries
        // don't bloat local state.
        val prefix = s"${siteId.value}|"
        val nowMs = System.currentTimeMillis()
        val fresh = data.entries.collect {
          case (k, v) if k.startsWith(prefix) && (nowMs - v.firstSeenMs) < pageWinnersTtlMs => k -> v
        }
        behavior(state.copy(pageWinners = fresh))

      case SweepPageWinners =>
        val nowMs = System.currentTimeMillis()
        val retained = state.pageWinners.filter {
          case (_, v) => (nowMs - v.firstSeenMs) < pageWinnersTtlMs
        }
        if (retained.size != state.pageWinners.size) {
          log.debug("PageWinners swept: {} → {} entries", state.pageWinners.size, retained.size)
        }
        behavior(state.copy(pageWinners = retained))

      case PacingConfigUpdated(configOpt) =>
        log.info("AdServer {} received PacingConfigUpdated: configOpt.isDefined={}", siteId.value, configOpt.isDefined)
        configOpt match {
          case Some(config) =>
            log.info(
              "Pacing config updated for site {}: dayDurationSeconds={}, warmupMode={}",
              siteId.value,
              config.dayDurationSeconds,
              config.warmupMode
            )
            val newStrategy = pacingStrategyFromConfig(config)
            val dayDurationChanged = config.dayDurationSeconds != dayDurationSeconds
            // Reset traffic shape tracker if dayDurationSeconds changed (learned shape is invalid)
            val newTrafficShapeTracker = if (dayDurationChanged) {
              log.info("Resetting traffic shape tracker due to dayDurationSeconds change")
              val tracker =
                new TrafficShapeTracker(bucketCount = 24, alpha = trafficShapeTracker.alpha, interpolateVolumes = true)
              tracker.setDayType(java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek)
              tracker
            } else {
              trafficShapeTracker // Keep existing (learned) tracker
            }
            // Reset lastDayStart when dayDurationSeconds changes
            // For simulated days: also reset campaigns to align with AdServer
            // For real days (86400): don't reset campaigns - they handle their own calendar day
            val newLastDayStart = if (dayDurationChanged) {
              log.info(
                "Day duration changed to {}, resetting lastDayStart to now",
                config.dayDurationSeconds
              )
              pacingStrategy.reset()

              // Only reset campaigns that have participated in THIS site's auctions
              // participatingCampaigns is populated from CandidatesCollected, ensuring
              // we only reset campaigns that actually bid on this site
              if (config.dayDurationSeconds != 86400 && participatingCampaigns.nonEmpty) {
                log.info("Resetting {} participating campaigns for simulated day on site {}",
                  participatingCampaigns.size, siteId.value)
                // Reset only campaigns that have participated in this site's auctions
                participatingCampaigns.foreach { campaignId =>
                  spendInfoCache.get(campaignId).foreach { cached =>
                    val campaignEntityId = s"${cached.advertiserId.value}|${campaignId.value}"
                    val campaignRef = sharding.entityRefFor(CampaignEntity.TypeKey, campaignEntityId)
                    campaignRef ! CampaignEntity.ResetDayStart(system.ignoreRef, dayDurationSeconds)
                  }
                }
                // Reset advertisers for participating campaigns (silent to avoid re-auctions during init)
                val participatingAdvertiserIds = participatingCampaigns.flatMap { campaignId =>
                  spendInfoCache.get(campaignId).map(_.advertiserId)
                }
                participatingAdvertiserIds.foreach { advertiserId =>
                  val advertiserRef = sharding.entityRefFor(AdvertiserEntity.TypeKey, advertiserId.value)
                  advertiserRef ! AdvertiserEntity.ResetDayStart(system.ignoreRef, silent = true)
                }
              }
              if (config.dayDurationSeconds == 86400)
                // Real days anchor to UTC midnight (calendar-day budgets);
                // only simulated days anchor to "now".
                Some(java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                  .atStartOfDay(java.time.ZoneOffset.UTC).toInstant)
              else Some(Instant.now())
            } else {
              lastDayStart
            }
            // Reset lastCampaignSet and simulatedDayOfWeek when day duration changes (new pacing cycle)
            val newLastCampaignSet = if (dayDurationChanged) Set.empty[CampaignId] else lastCampaignSet
            val newSimulatedDayOfWeek = if (dayDurationChanged) {
              java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek
            } else simulatedDayOfWeek
            siteBidWeight = config.bidWeight
            if (config.floorCpm > 0) {
              val newFloor = CPM(config.floorCpm)
              if (newFloor != siteFloorCpm) {
                log.info("AdServer {} floor CPM updated: ${} -> ${}", siteId.value, siteFloorCpm.toDouble,
                  newFloor.toDouble)
                siteFloorCpm = newFloor
                // NOTE: deliberately no longer fires ServeIndexDData.RemoveBelowFloor.
                // The floor is enforced at serve time (see `afterFloor` filter in
                // the batch-serve path). Purging ServeIndex on every floor update
                // was a write-time optimization that became a liability once the
                // sweep optimizer started cycling the floor every ~20s — it would
                // shred the index on each high-floor candidate, breaking pin-honor
                // and creating dead auction windows during the recovery period
                // before the next reauction repopulated the index. Memory cost of
                // keeping below-floor entries is bounded by content TTL (48h) and
                // the explicit eligibility-change removals (campaign paused,
                // creative deleted, domain blocked, etc.) which still fire.
              }
            }
            behavior(state.copy(
              lastDayStart = newLastDayStart,
              pacingStrategy = newStrategy,
              dayDurationSeconds = config.dayDurationSeconds,
              trafficShapeTracker = newTrafficShapeTracker,
              warmupMode = config.warmupMode,
              lastCampaignSet = newLastCampaignSet,
              simulatedDayOfWeek = newSimulatedDayOfWeek
            ))
          case None =>
            Behaviors.same
        }

      case GetServeStats(replyTo) =>
        val vols = trafficShapeTracker.volumes
        val maxIdx = vols.zipWithIndex.maxBy(_._1)._2
        val minIdx = vols.zipWithIndex.minBy(_._1)._2
        val maxVol = vols(maxIdx)
        val minVol = vols(minIdx)
        val ratio = if (minVol > 0) maxVol / minVol else 0.0
        val currentBucket = trafficShapeTracker.currentBucketOpt.getOrElse(-1)

        // Warmup status: show progress or "ready"
        val warmupStatus = if (trafficShapeTracker.isWarmedUp) "[ready]"
        else s"[warmup ${trafficShapeTracker.updateCount}/${trafficShapeTracker.bucketCount - 1}]"

        // Compact ASCII histogram (24 chars, one per bucket)
        val bars = "▁▂▃▄▅▆▇█"
        val histogram = if (maxVol > 0) {
          vols.zipWithIndex.map { case (vol, idx) =>
            val barIdx = ((vol / maxVol) * (bars.length - 1)).toInt.max(0).min(bars.length - 1)
            if (idx == currentBucket) s"[${bars(barIdx)}]" else bars(barIdx).toString
          }.mkString
        } else "?" * vols.length

        // Hourly impressions summary
        val hourlyImps = serveStats.hourlyImpressions
        val maxImps = hourlyImps.max
        val hourlyHistogram = if (maxImps > 0) {
          hourlyImps.zipWithIndex.map { case (imps, idx) =>
            val barIdx = ((imps.toDouble / maxImps) * (bars.length - 1)).toInt.max(0).min(bars.length - 1)
            if (idx == currentBucket) s"[${bars(barIdx)}]" else bars(barIdx).toString
          }.mkString
        } else "▁" * hourlyImps.length
        val totalImps = hourlyImps.sum

        // Format with aligned histograms for easy visual comparison
        // Each line needs indentation since \n resets to column 0
        // Extra blank line between histograms for readability
        val shapeSummary = Some(
          f"Hour $currentBucket%d $warmupStatus | Peak: h$maxIdx%d Valley: h$minIdx%d Ratio: ${ratio}%.1fx\n" +
          f"      Shape: $histogram\n\n" +
          f"      Imps:  $hourlyHistogram  ($totalImps%d total)"
        )
        // Include learned shape volumes for export
        val weekdayVols = Some(trafficShapeTracker.weekdayVolumes)
        val weekendVols = Some(trafficShapeTracker.weekendVolumes)
        replyTo ! serveStats.copy(
          dayStart = lastDayStart,
          trafficShapeSummary = shapeSummary,
          weekdayShapeVolumes = weekdayVols,
          weekendShapeVolumes = weekendVols
        )
        Behaviors.same
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CANDIDATE PROCESSING
  // Handles auction results: filtering, scoring, routing to ServeIndex
  // ═══════════════════════════════════════════════════════════════════════════

  private def candidateProcessing(state: State): PartialFunction[Command, Behavior[Command]] = {
    import state.*
    {
      case Protocol.CampaignPaused(campaignId, revokeApprovals) =>
        log.info("Campaign {} paused - removing from all slots for site {}", campaignId.value, siteId.value)
        serveIndex ! ServeIndexDData.RemoveCampaignBySite(siteId.value, campaignId)
        // Also remove from pending queue
        store.removeByCampaignId(siteId.value, campaignId.value).foreach { count =>
          if (count > 0) {
            log.info("Removed {} pending selections for paused campaign {}", count, campaignId.value)
          }
        }
        // EXPLICIT pause/delete (revokeApprovals=true — set ONLY for the
        // promovolve.CampaignPaused topic event from CampaignEntity.UpdateStatus)
        // REVOKES the campaign's approvals (user decision 2026-07-07): pausing
        // is leaving the site — on resume every creative starts over from
        // PENDING, re-entering the approval queue by winning an auction.
        // Every OTHER sender of this message keeps approvals — in particular
        // AuctioneerEntity fires it scope-blind on ANY CampaignChanged(
        // isActive=false), including category re-registration churn during
        // deploys, and revoking there re-creates the 2026-07-03 "approval
        // queue is gone" cascade (0e1304c4). Announce the revoke to each
        // AdvertiserEntity BEFORE deleting the rows (the fetch is the announce
        // source), so Creative.approvedSites clears and the resumed bids read
        // as pending, not floor-teaching.
        if (revokeApprovals) {
          store.getApprovedCreativeAdvertisersByCampaign(siteId.value, campaignId.value).onComplete {
            case Success(rows) =>
              rows.foreach { case (creativeId, advertiserId) =>
                sharding.entityRefFor(AdvertiserEntity.TypeKey, advertiserId) !
                AdvertiserEntity.RevokeCreativeApproval(CreativeId(creativeId), siteId, system.ignoreRef)
              }
              store.deleteApprovedByCampaignId(siteId.value, campaignId.value)
              // Strip ONLY this campaign's creatives from in-memory approval
              // state, resolved from the store rows. The slot-key index
              // (idsForKey) conflates every campaign sharing a slot key, so
              // stripping by index would revoke co-tenant campaigns' approvals.
              ctx.self ! Protocol.CampaignApprovalsRevoked(campaignId, rows.keySet.map(CreativeId(_)))
              if (rows.nonEmpty)
                log.info("Revoked {} approvals for paused campaign {} on site {}", rows.size: java.lang.Integer,
                  campaignId.value, siteId.value)
            case Failure(ex) =>
              // Nothing was revoked: no AdvertiserEntity announce, no DB delete,
              // no in-memory strip — memory and DB stay consistent (both keep
              // the approvals), but the pause did NOT revoke. Surface it.
              log.error("Failed to load approvals for paused campaign {} on site {} — revocation skipped: {}",
                campaignId.value, siteId.value, ex.getMessage)
          }
        }
        behavior(state.copy(
          participatingCampaigns = participatingCampaigns - campaignId,
          keysByCampaign = keysByCampaign - campaignId
        ))

      case Protocol.EvictCampaignFromSite(campaignId) =>
        // Site-narrow eviction: the advertiser dropped this site from a non-empty
        // siteAllowlist, so the campaign is no longer eligible to bid here. Wipe
        // it from the whole site exactly like a pause — INCLUDING any reader pins
        // on its creatives (a pin on a site the advertiser left dies, per the
        // product decision). Idempotent: every step no-ops when the campaign is
        // absent. Body mirrors CampaignPaused.
        log.info("Campaign {} narrowed off site {} - evicting from all slots", campaignId.value, siteId.value)
        serveIndex ! ServeIndexDData.RemoveCampaignBySite(siteId.value, campaignId)
        store.removeByCampaignId(siteId.value, campaignId.value).foreach { count =>
          if (count > 0) {
            log.info("Removed {} pending selections for narrowed-off campaign {}", count, campaignId.value)
          }
        }
        // Strip ONLY this campaign's creatives from in-memory approval state,
        // resolved from the store rows before deletion — not the slot-key
        // index, which conflates co-tenant campaigns sharing a slot key.
        store.getApprovedCreativeAdvertisersByCampaign(siteId.value, campaignId.value).onComplete {
          case Success(rows) =>
            store.deleteApprovedByCampaignId(siteId.value, campaignId.value)
            ctx.self ! Protocol.CampaignApprovalsRevoked(campaignId, rows.keySet.map(CreativeId(_)))
          case Failure(ex) =>
            // Neither the DB delete nor the in-memory strip ran — consistent
            // (both keep the approvals) but the eviction did NOT revoke them.
            log.error("Failed to load approvals for evicted campaign {} on site {} — revocation skipped: {}",
              campaignId.value, siteId.value, ex.getMessage)
        }
        behavior(state.copy(
          participatingCampaigns = participatingCampaigns - campaignId,
          keysByCampaign = keysByCampaign - campaignId
        ))

      case Protocol.CampaignApprovalsRevoked(campaignId, creativeIds) =>
        if (creativeIds.isEmpty) Behaviors.same
        else {
          log.info("Stripping {} approved creatives for revoked campaign {} on site {}",
            creativeIds.size: java.lang.Integer, campaignId.value, siteId.value)
          behavior(state.copy(
            keysByCreative = keysByCreative -- creativeIds,
            persistedApprovedIds = persistedApprovedIds -- creativeIds,
            autoApprovedIds = state.autoApprovedIds -- creativeIds,
            pinnedCreativeIds = pinnedCreativeIds -- creativeIds
          ))
        }

      case Protocol.EvictCampaignFromSlots(campaignId, slotKeys) =>
        // Topic-narrow eviction: the advertiser dropped a category, so this
        // campaign is no longer targeting the categorized pages backing these
        // specific slot keys (computed by the per-site AuctioneerEntity from its
        // awardedCampaigns/lastPage caches). Remove the campaign from exactly
        // these keys — but PIN-AWARE: a reader-pinned creative survives a topic
        // drop on a still-served page (product decision). The authoritative fix
        // is the ServeView update; keysByCampaign is pruned as a hint. Idempotent:
        // RemoveCampaignFromKey no-ops when the campaign isn't in a key.
        if (slotKeys.nonEmpty) {
          log.info(
            "Campaign {} narrowed off topic - evicting from {} slot key(s) (pins preserved): {}",
            campaignId.value, slotKeys.size, slotKeys.mkString(",")
          )
          slotKeys.foreach { slotKey =>
            serveIndex ! ServeIndexDData.RemoveCampaignFromKey(
              slotKey, campaignId, keepCreativeIds = pinnedCreativeIds
            )
          }
        }
        val prunedKeysByCampaign =
          keysByCampaign.get(campaignId) match {
            case Some(keys) =>
              val remaining = keys -- slotKeys
              if (remaining.isEmpty) keysByCampaign - campaignId
              else keysByCampaign.updated(campaignId, remaining)
            case None => keysByCampaign
          }
        behavior(state.copy(keysByCampaign = prunedKeysByCampaign))

      case CreativePaused(creativeId) =>
        log.info("Creative {} paused - removing from all slots for site {}", creativeId.value, siteId.value)
        serveIndex ! ServeIndexDData.RemoveCreativeBySite(siteId.value, creativeId)
        // Also remove from pending queue
        store.removeCreativeFromAll(siteId.value, creativeId.value).foreach { removedSlots =>
          if (removedSlots.nonEmpty) {
            log.info("Removed paused creative {} from {} pending selections", creativeId.value, removedSlots.size)
          }
        }
        // Persisted approval deliberately KEPT — a creative pause is
        // reversible (see CreativeReactivated below); approval is content
        // vetting and survives it. Same rationale as CampaignPaused.
        // Publisher-initiated un-approval goes through RevokeCreativeApproval.
        behavior(state.copy(
          keysByCreative = keysByCreative - creativeId
        ))

      case Protocol.RevokeCreativeApproval(creativeId) =>
        // Explicit publisher revoke: THE un-approval act. Remove from serving
        // AND delete the persisted approval so the creative returns to the
        // pending queue on its next auction win (soft undo — Reject is the
        // permanent block). Also forget the pin record: the dog-ear client
        // cleans its IDB pin on the revoke SSE event, so keeping the
        // server-side pin would protect a creative the viewer can no longer
        // see pinned.
        log.info(
          "Creative {} approval REVOKED by publisher - removing from serving + deleting approval for site {}",
          creativeId.value, siteId.value
        )
        serveIndex ! ServeIndexDData.RemoveCreativeBySite(siteId.value, creativeId)
        store.removeCreativeFromAll(siteId.value, creativeId.value).foreach { removedSlots =>
          if (removedSlots.nonEmpty) {
            log.info("Removed revoked creative {} from {} pending selections", creativeId.value, removedSlots.size)
          }
        }
        store.deleteApproved(siteId.value, creativeId.value)
        // Revoke breaks auto-approve trust — without this, a trust anchor
        // would auto-re-approve the creative on its very next auction win,
        // making revoke a no-op loop. The wire message carries only the
        // creativeId, so resolve campaign/domain from the creative repo.
        ctx.pipeToSelf(creativeRepo.get(creativeId.value)) {
          case Success(Some(creative)) =>
            TrustBroken(CampaignId(creative.campaignId), RegistrableDomain.of(creative.landingDomain))
          case Success(None) =>
            log.warn("Revoked creative {} not in CreativeRepo — trust anchors NOT broken for site {}",
              creativeId.value, siteId.value)
            NoOp
          case Failure(ex) =>
            log.error("Creative lookup for revoke trust-break failed: creative={} site={}: {}",
              creativeId.value, siteId.value, ex.getMessage)
            NoOp
        }
        behavior(state.copy(
          keysByCreative = keysByCreative - creativeId,
          persistedApprovedIds = state.persistedApprovedIds - creativeId,
          autoApprovedIds = state.autoApprovedIds - creativeId,
          pinnedCreativeIds = state.pinnedCreativeIds - creativeId
        ))

      case CreativeReactivated(creativeId, campaignId) =>
        // Creative reactivated - this is handled by AuctioneerEntity triggering campaign re-auction
        // AdServer just logs the event (no direct action needed here)
        log.info(
          "Creative {} reactivated for campaign {} - re-auction will restore it",
          creativeId.value,
          campaignId.value
        )
        Behaviors.same

      case RemoveAdvertiser(advertiserId) =>
        log.info("Advertiser {} removed - removing from all slots for site {}", advertiserId.value, siteId.value)
        serveIndex ! ServeIndexDData.RemoveAdvertiserBySite(siteId.value, advertiserId)
        // Also remove from pending queue
        store.removeByAdvertiserId(siteId.value, advertiserId.value).foreach { count =>
          if (count > 0) {
            log.info("Removed {} pending selections for advertiser {}", count, advertiserId.value)
          }
        }
        // Persisted approvals deliberately KEPT. Every producer of this
        // command today is a REVERSIBLE hold: billing wallet suspension
        // (Settler on empty wallet — resumes minutes later after a top-up)
        // and operator Suspended/Closed. Deleting here meant every wallet
        // dip silently revoked the advertiser's publisher approvals — masked
        // until the next pod restart by the still-populated in-memory sets.
        // A future PERMANENT advertiser-deletion flow should scrub approvals
        // via its own explicit path, not this one.
        behavior(state.copy(keysByAdvertiser = keysByAdvertiser - advertiserId))

      case RefreshTTLForCampaign(campaignId) =>
        // Use inverted index for targeted TTL refresh
        val keys = keysByCampaign.getOrElse(campaignId, Set.empty)
        val newTtlMs = (dayDurationSeconds * 1.1 * 1000).toLong
        log.info(
          "Campaign {} budget exhausted - refreshing TTL for {} keys on site {} (ttlMs={})",
          campaignId.value,
          keys.size,
          siteId.value,
          newTtlMs
        )
        keys.foreach { k =>
          serveIndex ! ServeIndexDData.RefreshTTLForKey(k, newTtlMs)
        }
        Behaviors.same

      case RefreshTTLForAdvertiser(advertiserId) =>
        // Use inverted index for targeted TTL refresh
        val keys = keysByAdvertiser.getOrElse(advertiserId, Set.empty)
        val newTtlMs = (dayDurationSeconds * 1.1 * 1000).toLong
        log.info(
          "Advertiser {} budget exhausted - refreshing TTL for {} keys on site {} (ttlMs={})",
          advertiserId.value,
          keys.size,
          siteId.value,
          newTtlMs
        )
        keys.foreach { k =>
          serveIndex ! ServeIndexDData.RefreshTTLForKey(k, newTtlMs)
        }
        Behaviors.same

      case MarkClassified(url, classifiedAt) =>
        // A page was classified (matched OR filler), regardless of bidders.
        // Record classifiedAt so reclassifyInMs/needText treat it as known.
        recordClassified(url.value, classifiedAt.toEpochMilli)
        Behaviors.same

      case CandidatesCollected(url, slotId, candidates, classifiedAt, ttl, pageCategories, floorCpm, categoryFloors,
            slotAdminFloor, authoritativeAbsent) =>
        // Update site floor CPM + per-category floors from latest auction
        siteFloorCpm = floorCpm
        siteCategoryFloors = categoryFloors
        // Sync this slot's admin floor override (None clears — a removed
        // override must stop shielding the slot at serve time).
        adminSlotFloors = slotAdminFloor match {
          case Some(f) => adminSlotFloors.updated(slotId, f)
          case None    => adminSlotFloors - slotId
        }
        // Record this url as CLASSIFIED — even when pageCategories is empty (a
        // filler page that matched no demand category). This is what flips
        // needText (on-demand cold detection) to false: "classified" means we
        // have the page's text, regardless of whether it matched a category.
        // Without recording the empty case, a no-match page would re-classify on
        // every serve (the ad tag would POST /classify-page on every pageview).
        // Bounded so on-demand traffic can't grow it unboundedly; arbitrary
        // eviction only costs a rare re-classify of a long-cold url.
        pageCategoriesCache = pageCategoriesCache + (url.value -> pageCategories)
        if (pageCategoriesCache.size > AdServer.MaxPageCategoriesCache)
          pageCategoriesCache = pageCategoriesCache.drop(pageCategoriesCache.size - AdServer.MaxPageCategoriesCache)
        // Record classifiedAt for the freshness token (also set directly via
        // MarkClassified, but this covers the restart-reauction repopulation).
        recordClassified(url.value, classifiedAt.toEpochMilli)
        if (candidates.isEmpty) {
          // Zero-candidate delivery = floor-sync only: an admin override or
          // sweep floor rose above all demand and the auction came back
          // empty. The floors updated above take effect at the serve gate
          // immediately, but the ServeIndex is deliberately left untouched —
          // below-floor entries stay pin-only servable (dog-ear design) and
          // age out via their own TTL.
          //
          // EXCEPT for authoritative absences: the advertiser just changed the
          // campaign's config and it no longer bids AT ALL (e.g. sole bidder
          // in the category lowered maxCpm below the floor → the whole auction
          // came back empty). Without this, the stale old-CPM entry keeps
          // serving until the ServeView TTL. Same pin-aware removal as
          // EvictCampaignFromSlots.
          log.info(
            "Floor-sync (empty auction): site={} slot={} floor={} slotAdminFloor={}",
            siteId.value, slotId.value, floorCpm.toDouble, slotAdminFloor.map(_.toDouble)
          )
          if (authoritativeAbsent.nonEmpty) {
            val slotKey = key(siteId, slotId)
            log.info(
              "Evicting {} authoritative-absent campaign(s) from empty-auction slot (pins preserved): slot={} campaigns={}",
              authoritativeAbsent.size, slotKey, authoritativeAbsent.map(_.value).mkString(",")
            )
            authoritativeAbsent.foreach { campaignId =>
              serveIndex ! ServeIndexDData.RemoveCampaignFromKey(
                slotKey, campaignId, keepCreativeIds = pinnedCreativeIds
              )
            }
            val prunedKeysByCampaign = authoritativeAbsent.foldLeft(keysByCampaign) { (acc, campaignId) =>
              acc.get(campaignId) match {
                case Some(keys) =>
                  val remaining = keys - slotKey
                  if (remaining.isEmpty) acc - campaignId else acc.updated(campaignId, remaining)
                case None => acc
              }
            }
            behavior(state.copy(keysByCampaign = prunedKeysByCampaign))
          } else {
            behavior(state)
          }
        } else {
          // Track campaigns that have participated in auctions for this site
          // This allows filtering SpendUpdate events to only relevant campaigns
          val newCampaignIds = candidates.map(_.campaignId).toSet
          val updatedParticipating = participatingCampaigns ++ newCampaignIds
          if (newCampaignIds.nonEmpty && newCampaignIds.exists(!participatingCampaigns.contains(_))) {
            log.info(
              "Tracking {} new participating campaigns for site {}: {}",
              newCampaignIds.size,
              siteId.value,
              newCampaignIds.mkString(", ")
            )
          }
          // Query ServeIndex to determine which creatives are ACTUALLY approved (not just CuckooFilter)
          // This is safer than trusting the preApproved flag from the CuckooFilter
          val slotKey = key(siteId, slotId)
          import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
          given Timeout = Timeout(300.millis)
          val viewFuture = serveIndex.ask[Option[ServeView]](ServeIndexDData.Get(slotKey, _))
          ctx.pipeToSelf(viewFuture) { result =>
            val existingView = result.toOption.flatten
            val existingCreativeIds = existingView
              .map(_.candidates.map(_.creativeId).toSet)
              .getOrElse(Set.empty)
            ServeIndexLoadedForCandidates(url, slotId, candidates, classifiedAt, ttl, slotKey, existingCreativeIds,
              existingView, authoritativeAbsent)
          }
          behavior(state.copy(participatingCampaigns = updatedParticipating))
        }

      case ServeIndexLoadedForCandidates(url, slotId, candidates, classifiedAt, ttl, slotKey, existingCreativeIds,
            existingView, authoritativeAbsent) =>
        // Strip authoritative-absent campaigns' creatives from the existing view
        // BEFORE orphan preservation sees it (pin-aware, mirroring
        // EvictCampaignFromSlots): the advertiser explicitly changed these
        // campaigns, so if a creative doesn't reappear in `candidates` it must
        // NOT be preserved at its old CPM. Creatives that DO reappear are Put
        // fresh from the new result, so stripping here only affects true
        // absences. existingCreativeIds (approval status) is deliberately NOT
        // stripped — approval isn't revoked by a config change.
        val effectiveView =
          if (authoritativeAbsent.isEmpty) existingView
          else existingView.map { v =>
            val (dropped, kept) = v.candidates.partition { c =>
              authoritativeAbsent.contains(c.campaignId) && !state.pinnedCreativeIds.contains(c.creativeId)
            }
            if (dropped.nonEmpty) {
              log.info(
                "Dropping {} stale entr(ies) of authoritative-absent campaigns before orphan preservation: pub={} slot={} dropped={}",
                dropped.size, siteId.value, slotId.value,
                dropped.map(c => s"${c.creativeId.value}@${c.cpm.toDouble}").mkString(",")
              )
            }
            v.copy(candidates = kept)
          }
        // Approval has ONE source of truth: persistedApprovedIds (synchronously
        // updated on approve/revoke/pause, DB-backed across restarts) — the
        // same list the serve gate reads. ServeIndex/keysByCreative membership
        // is deliberately NOT consulted: the replicated, disk-persisted
        // ServeIndex outlives explicit un-approvals (pause/revoke) and even
        // restarts, and inferring "approved because it's still serving
        // somewhere" resurrected approvals the publisher had just deleted
        // (auto-re-approve loop, observed live 2026-07-07). Site-wide scope is
        // preserved: persistedApprovedIds is per-site, so a creative approved
        // at one slot is still recognized at every other slot.
        val siteWideApproved = state.persistedApprovedIds

        // Per-page recent-winner separation (pageWinners) is intentionally
        // NOT applied here. ServeIndex is the per-slot pool of *all* approved
        // candidates; page-level separation ("don't repeat the same advertiser
        // across a page / recent reloads") is a per-request decision made at
        // serve time in batchReserveWithRetry (as a soft preference). Filtering
        // it at population time starved the index: with a sole advertiser it
        // left secondary slots written empty (NOT FOUND), and the gap outlived
        // the 15s pageWinners TTL because serves don't repopulate the index.
        // See the intermittent-fill-pagewinners memory.
        log.info(
          "ServeIndex loaded for candidates: pub={} url={} slot={} candidates={} slotApproved={} siteWideApproved={}",
          siteId.value,
          url.value,
          slotId.value,
          candidates.size,
          existingCreativeIds.size,
          siteWideApproved.size
        )
        val autoApprovedNow = processCandidatesWithApprovalStatus(
          url, slotId, candidates, classifiedAt, ttl, slotKey, siteWideApproved, effectiveView,
          cachedDomainBlocklist, cachedAdProductBlocklist,
          cachedAdvertiserBlocklists, AdServer.mySiteDomainOpt(verifiedHost),
          autoApproveEnabled = cachedAutoApprove.exists(_.enabled),
          trustedCampaigns = trustedCampaigns,
          trustedDomains = trustedDomains
        )
        // Auto-approvals join persistedApprovedIds SYNCHRONOUSLY, same
        // doctrine as manual approve/revoke (see the one-source-of-truth
        // comment above) — the next wave must see them as approved.
        if (autoApprovedNow.isEmpty) Behaviors.same
        else behavior(state.copy(
          persistedApprovedIds = state.persistedApprovedIds ++ autoApprovedNow,
          autoApprovedIds = state.autoApprovedIds ++ autoApprovedNow
        ))

      case CandidateScoresFetched(
            url,
            slotId,
            approved,
            pending,
            classifiedAt,
            ttl,
            slotKey,
            categoryScores,
            existingView
          ) =>
        buildServeViewFromCandidates(
          url,
          slotId,
          approved,
          pending,
          classifiedAt,
          ttl,
          slotKey,
          categoryScores,
          existingView
        )
        Behaviors.same

      case CandidateViewsBuilt(url, slotId, candidateViews, pending, ttl, slotKey, existingView) =>
        val newState = handleCandidateViewsBuilt(
          state, url, slotId, candidateViews, pending, ttl, slotKey, existingView
        )
        behavior(newState)

      case UpsertPendingCompleted(count, url, slotId, topCreativeId, creativeIds) =>
        log.debug(
          "Queued {} creatives for approval: pub={} url={} slot={} top={}",
          count,
          siteId.value,
          url.value,
          slotId.value,
          topCreativeId
        )
        // Only publish SSE event if the pending creative set actually changed
        // (not just CPM updates from re-auction with same candidates)
        val pendingKey = s"${url.value}|${slotId.value}"
        val oldIds = lastPendingCreativeIds.getOrElse(pendingKey, Set.empty)
        lastPendingCreativeIds = lastPendingCreativeIds.updated(pendingKey, creativeIds)
        if (creativeIds != oldIds) {
          val event = promovolve.PendingCreativesQueued(
            siteId = siteId,
            url = url,
            slotId = slotId,
            count = count,
            topCreativeId = CreativeId(topCreativeId),
            timestamp = Instant.now()
          )
          budgetEventTopic ! Topic.Publish(event)
        }
        Behaviors.same
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // APPROVAL WORKFLOW
  // Handles publisher approval/rejection of pending creatives
  // ═══════════════════════════════════════════════════════════════════════════

  private def approvalWorkflow(state: State): PartialFunction[Command, Behavior[Command]] = {
    case Approve(url, slot, cid, replyTo) =>
      processApprove(url, slot, cid, replyTo)
      Behaviors.same

    case Reject(url, slot, cid, reason, replyTo) =>
      processReject(url, slot, cid, reason, replyTo)
      Behaviors.same

    case ApproveAll(url, slot, replyTo) =>
      processApproveAll(url, slot, replyTo)
      Behaviors.same

    case ListPending(replyTo) =>
      processListPending(replyTo)
      Behaviors.same

    case GetPendingForApproveResult(url, slot, cid, selectionOpt, replyTo) =>
      handleGetPendingForApprove(url, slot, cid, selectionOpt, state.persistedApprovedIds.map(_.value), replyTo)
      Behaviors.same

    case AlreadyApprovedLookedUp(cid, creativeOpt, replyTo) =>
      creativeOpt match {
        case Some(meta) =>
          log.info("Approve: creative {} already site-approved — idempotent success", cid)
          replyTo ! StatusReply.Success(AssetPointer(meta.s3Key, new java.net.URI(meta.s3Key)))
        case None =>
          replyTo ! StatusReply.Error("No pending selection for this slot")
      }
      Behaviors.same

    case CreativeLookedUpForApprove(candidate, selection, url, slot, creativeOpt, replyTo) =>
      val cid = candidate.creativeId.value
      creativeOpt match {
        case Some(meta) =>
          // Create AssetPointer directly from creative's s3Key
          val assetPrt = AssetPointer(meta.s3Key, new java.net.URI(meta.s3Key))
          // Fetch category score from TaxonomyRankerEntity
          ctx.pipeToSelf(fetchCategoryScores(Vector(candidate.category))) {
            case Success(scoreMap) =>
              CategoryScoresFetched(
                candidate, selection, assetPrt, meta, url, slot, scoreMap, replyTo
              )
            case Failure(_) =>
              CategoryScoresFetched(
                candidate, selection, assetPrt, meta, url, slot, Map.empty, replyTo
              )
          }
        case None =>
          log.error(
            "APPROVAL BLOCKED: Creative {} not in CreativeRepo. This creative was created before the storage fix. " +
            "The creative participates in auctions (in AdvertiserEntity) but cannot be approved (missing asset metadata). " +
            "Solution: Restart with --fresh and recreate campaigns/creatives.",
            cid
          )
          replyTo ! StatusReply.Error(
            s"Creative $cid cannot be approved - missing asset metadata. " +
            "This creative was created before a code fix. Please restart with --fresh and recreate your campaigns."
          )
      }
      Behaviors.same

    case CategoryScoresFetched(
          candidate,
          selection,
          assetPrt,
          meta,
          url,
          slot,
          scoreMap,
          replyTo
        ) =>
      val newState = completeApprovalWithScore(
        state,
        candidate,
        selection,
        assetPrt,
        meta,
        url,
        slot,
        scoreMap,
        replyTo
      )
      // The approval just minted trust anchors — sweep the queue so
      // already-pending siblings auto-approve now, not at the next
      // organic wave.
      sweepPendingForTrust(newState)
      behavior(newState)

    case ApprovalStatusPersisted(url, slot, creativeId, assetPrt, candidateView, replyTo) =>
      // Approval status persisted - now safe to finalize approval
      // Remove the approved creative from ALL pending selections for this site
      // (the same creative appears in pending for every url/slot combination)
      ctx.pipeToSelf(store.removeCreativeFromAll(siteId.value, creativeId)) {
        case Success(removedSlots) =>
          if (removedSlots.nonEmpty) {
            log.info("Approved creative {} for pub={}, removed from {} pending selections",
              creativeId, siteId.value, removedSlots.size)
          }
          RemovePendingCompleted(url, slot, assetPrt, replyTo, Some(candidateView), removedSlots)
        case Failure(ex) =>
          log.warn("Failed to remove creative {} from all pending for pub={}: {}",
            creativeId, siteId.value, ex.getMessage)
          RemovePendingCompleted(url, slot, assetPrt, replyTo) // Still complete on failure
      }
      Behaviors.same

    case RemovePendingCompleted(approvedUrl, approvedSlot, assetPrt, replyTo, candidateViewOpt, removedSlots) =>
      // Append the approved creative to all other slots where it was pending
      candidateViewOpt.foreach { cv =>
        val approvedKey = Keys.keyUnsafe(siteId.value, approvedSlot)
        val otherSlots = removedSlots.filterNot { case (_, s) => Keys.keyUnsafe(siteId.value, s) == approvedKey }
        if (otherSlots.nonEmpty) {
          log.info("Appending approved creative {} to {} additional slot keys", cv.creativeId.value, otherSlots.size)
          otherSlots.foreach { case (_, s) =>
            val sk = Keys.keyUnsafe(siteId.value, s)
            serveIndex ! ServeIndexDData.Append(sk, cv, approvedTtl.toMillis)
          }
        }
      }
      replyTo ! StatusReply.Success(assetPrt)
      Behaviors.same

    case BatchApprovalStatusPersisted(url, slot, approved, failed, replyTo) =>
      // All approval statuses persisted - now safe to finalize batch approval
      ctx.pipeToSelf(store.removePending(siteId.value, url, slot)) {
        case Success(_)  => RemovePendingBatchCompleted(url, slot, approved, failed, replyTo)
        case Failure(ex) =>
          log.warn("Failed to remove pending batch for pub={} url={} slot={}: {}", siteId.value, url, slot,
            ex.getMessage)
          RemovePendingBatchCompleted(url, slot, approved, failed, replyTo)
      }
      Behaviors.same

    case RemovePendingBatchCompleted(url, slot, approved, failed, replyTo) =>
      log.info(
        "ApproveAll complete: pub={} url={} slot={} approved={} failed={}",
        siteId.value,
        url,
        slot,
        approved,
        failed
      )
      replyTo ! ApproveAllResult(approved, failed)
      Behaviors.same

    case GetPendingForApproveAllResult(url, slot, selectionOpt, replyTo) =>
      handleGetPendingForApproveAll(url, slot, selectionOpt, replyTo)
      Behaviors.same

    case CreativesLookedUpForApproveAll(url, slot, selection, lookedUp, replyTo) =>
      val newState = handleCreativesLookedUpForApproveAll(state, url, slot, selection, lookedUp, replyTo)
      // Bulk approvals mint anchors too — sweep for pending siblings.
      sweepPendingForTrust(newState)
      behavior(newState)

    case GetPendingForRejectResult(url, slot, cid, reason, selectionOpt, replyTo) =>
      handleGetPendingForReject(url, slot, cid, reason, selectionOpt, replyTo)
      Behaviors.same

    case RejectAndPromoteCompleted(url, slot, promoted, replyTo) =>
      handleRejectAndPromoteCompleted(url, slot, promoted, replyTo)
      Behaviors.same

    case ListPendingResult(items, replyTo) =>
      replyTo ! PendingList(items)
      Behaviors.same

    // ==================== Flagging / Quarantine ====================

    case Flag(url, slot, creativeId, reason, replyTo) =>
      processFlagCreative(url, slot, creativeId, reason, replyTo)
      Behaviors.same

    case Unflag(creativeId, replyTo) =>
      processUnflagCreative(creativeId, replyTo)
      Behaviors.same

    case ListFlagged(replyTo) =>
      processListFlagged(replyTo)
      Behaviors.same

    case FlagCreativeResult(flaggedOpt, replyTo) =>
      flaggedOpt match {
        case Some(flagged) =>
          // Update CuckooFilter to mark creative as rejected for this site
          // This prevents the creative from appearing in future auctions
          val advertiserRef = sharding.entityRefFor(
            promovolve.advertiser.AdvertiserEntity.TypeKey,
            flagged.advertiserId
          )
          advertiserRef ! promovolve.advertiser.AdvertiserEntity.UpdateCreativeApproval(
            creativeId = CreativeId(flagged.creativeId),
            siteId = siteId,
            status = ApprovalStatus.Rejected,
            replyTo = system.ignoreRef
          )

          // Remove flagged creative from ServeIndex across all slots
          serveIndex ! ServeIndexDData.RemoveCreativeBySite(siteId.value, CreativeId(flagged.creativeId))

          // Flag breaks auto-approve trust like a reject. FlaggedCreative
          // doesn't carry the landing domain, so resolve it from the
          // creative repo; a failed lookup still breaks the campaign anchor.
          ctx.pipeToSelf(creativeRepo.get(flagged.creativeId)) {
            case Success(Some(creative)) =>
              TrustBroken(CampaignId(flagged.campaignId), RegistrableDomain.of(creative.landingDomain))
            case _ =>
              TrustBroken(CampaignId(flagged.campaignId), None)
          }

          // Trigger re-auction to fill the vacancy left by the flagged creative
          val auctioneerRef = sharding.entityRefFor(
            promovolve.auction.AuctioneerEntity.TypeKey,
            siteId.value
          )
          auctioneerRef ! promovolve.auction.AuctioneerEntity.Reevaluate(URL(flagged.url))

          log.info(
            "Flagged creative {} - removed from ServeIndex and triggered re-auction for {}",
            flagged.creativeId,
            flagged.url
          )
          replyTo ! StatusReply.Success(FlagResult(flagged.creativeId, flagged = true))
        case None =>
          replyTo ! StatusReply.Error("Creative not found in pending queue")
      }
      Behaviors.same

    case UnflagCreativeResult(unflaggedOpt, replyTo) =>
      unflaggedOpt match {
        case Some(unflagged) =>
          // Remove from rejectedSites CuckooFilter so creative is eligible for auction again
          val advertiserRef = sharding.entityRefFor(
            promovolve.advertiser.AdvertiserEntity.TypeKey,
            unflagged.advertiserId
          )
          advertiserRef ! promovolve.advertiser.AdvertiserEntity.UpdateCreativeApproval(
            creativeId = CreativeId(unflagged.creativeId),
            siteId = siteId,
            status = ApprovalStatus.Approved, // Removes from rejectedSites
            replyTo = system.ignoreRef
          )

          // Trigger re-auction so the unflagged creative can compete for the slot
          val auctioneerRef = sharding.entityRefFor(
            promovolve.auction.AuctioneerEntity.TypeKey,
            siteId.value
          )
          auctioneerRef ! promovolve.auction.AuctioneerEntity.Reevaluate(URL(unflagged.url))

          log.info(
            "Unflagged creative {} - removed from rejection filter and triggered re-auction for {}",
            unflagged.creativeId,
            unflagged.url
          )
          replyTo ! StatusReply.Success(UnflagResult(unflagged.creativeId, unflagged = true))
        case None =>
          replyTo ! StatusReply.Error("Creative not found in flagged list")
      }
      Behaviors.same

    case ListFlaggedResult(items, replyTo) =>
      replyTo ! FlaggedList(items)
      Behaviors.same

    // ==================== Auto-Approve Trust ====================

    case GetAutoApproveInfo(replyTo) =>
      replyTo ! AutoApproveInfo(
        trustedCampaigns = state.trustedCampaigns.map(_.value),
        trustedDomains = state.trustedDomains,
        autoApprovedCreativeIds = state.autoApprovedIds.map(_.value)
      )
      Behaviors.same

    case RemoveTrustAnchor(anchorType, anchorValue, replyTo) =>
      log.info("Publisher removing trust anchor for site {}: {}={}", siteId.value, anchorType, anchorValue)
      ctx.pipeToSelf(store.deleteTrustAnchor(siteId.value, anchorType, anchorValue)) {
        case Success(removed) => TrustAnchorRemovalDone(removed, replyTo)
        case Failure(ex)      =>
          log.error("Trust anchor delete failed for site {}: {}={}: {}", siteId.value, anchorType, anchorValue,
            ex.getMessage)
          TrustAnchorRemovalDone(removed = false, replyTo)
      }
      // In-memory removal is synchronous so the very next auction wave
      // stops auto-approving, even before the DB delete lands.
      anchorType match {
        case TrustAnchor.TypeCampaign =>
          behavior(state.copy(trustedCampaigns = state.trustedCampaigns - CampaignId(anchorValue)))
        case TrustAnchor.TypeDomain =>
          behavior(state.copy(trustedDomains = state.trustedDomains - anchorValue))
        case _ =>
          Behaviors.same
      }

    case TrustAnchorRemovalDone(removed, replyTo) =>
      replyTo ! TrustAnchorRemoved(removed)
      Behaviors.same
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SELECTION PIPELINE - Creative Selection Flow Diagram
  // ═══════════════════════════════════════════════════════════════════════════
  //
  //   /serve?pub=X&url=Y&slot=Z
  //           │
  //           ▼
  //   ┌───────────────────┐
  //   │  1. Day Rollover  │  Check if simulated/real day ended
  //   │     Check         │  → Reset budgets if needed (ResetDayStart)
  //   └─────────┬─────────┘
  //             │
  //             ▼
  //   ┌───────────────────┐
  //   │  2. ServeIndex    │  Load approved candidates from DData
  //   │     Lookup        │  Key: siteId|url|slotId
  //   └─────────┬─────────┘
  //             │
  //             ▼
  //   ┌───────────────────┐
  //   │  3. Content       │  Filter out stale content
  //   │     Recency       │  (older than classificationFreshnessWindow)
  //   └─────────┬─────────┘
  //             │
  //             ▼
  //   ┌───────────────────┐
  //   │  4. Frequency     │  Check per-user impression limits
  //   │     Cap Filter    │  via AdvertiserEntity CMS sketch
  //   └─────────┬─────────┘
  //             │
  //             ▼
  //   ┌───────────────────┐
  //   │  5. Pacing Gate   │  Should we serve based on budget pace?
  //   │                   │  Uses PacingStrategy + traffic shape
  //   └─────────┬─────────┘
  //             │
  //        ┌────┴────┐
  //        │ ALLOW?  │
  //        └────┬────┘
  //       NO    │    YES
  //       ┌─────┴─────┐
  //       │           │
  //       ▼           ▼
  //   ┌───────┐   ┌───────────────────┐
  //   │ SKIP  │   │  6. Thompson      │  Sample from Beta(α,β) for each
  //   │(204)  │   │     Sampling      │  candidate, pick highest score
  //   └───────┘   └─────────┬─────────┘
  //                         │
  //                         ▼
  //               ┌───────────────────┐
  //               │  7. Budget        │  Reserve spend with CampaignEntity
  //               │     Reservation   │  + check AdvertiserEntity budget
  //               └─────────┬─────────┘
  //                         │
  //                    ┌────┴────┐
  //                    │RESERVED?│
  //                    └────┬────┘
  //               NO        │        YES
  //               ┌─────────┴─────────┐
  //               │                   │
  //               ▼                   ▼
  //   ┌───────────────────┐   ┌───────────────────┐
  //   │  Try Next         │   │  8. Return        │
  //   │  Candidate        │   │     Creative      │
  //   │  (loop to step 7) │   │     + impUrl      │
  //   └─────────┬─────────┘   └───────────────────┘
  //             │
  //             ▼
  //   ┌───────────────────┐
  //   │  All Exhausted    │  Return NoCandidates (204)
  //   │  (budget out)     │  ServeIndex NOT modified
  //   └───────────────────┘
  //
  // Key: Budget exhaustion does NOT remove entries from ServeIndex.
  // Entries are preserved so approval status survives until day rollover.
  // ═══════════════════════════════════════════════════════════════════════════

  private def selectionPipeline(state: State): PartialFunction[Command, Behavior[Command]] = {
    import state.*
    {

      // ─── Batch select (joint auction across all slots on a page) ───
      //
      // Single entry point for ad serving since the per-slot Select path
      // was retired. Lifecycle bookkeeping (request count, day rollover,
      // ResetDayStart fan-out, traffic shape, warmup gating) lives in
      // recordRequestArrival above.

      case BatchSelect(url, slots, classificationFreshnessWindowMs, replyTo, excludedCreatives, excludedCampaigns) =>
        if (siteSuspended) {
          // Operator suspension: refuse before ANY classification/auction/
          // lifecycle work — no serve means no impressions, so earnings
          // and spend freeze without touching a single record.
          log.info("Batch serve refused: site {} is operator-suspended", siteId.value)
          replyTo ! BatchSiteSuspended
          behavior(state.copy(requestCount = requestCount + 1, lastRequestTimeMs = System.currentTimeMillis()))
        } else {
          val hostOk = AdServer.hostMatches(url.value, verifiedHost)
          if (!hostOk) {
            log.warn("Batch serve rejected: host-mismatch site={} url={}", siteId.value, url.value)
            replyTo ! BatchHostNotVerified
            // Mirror per-slot path: count host-mismatch attempts but skip
            // the rate observer / lifecycle bookkeeping.
            behavior(state.copy(requestCount = requestCount + 1, lastRequestTimeMs = System.currentTimeMillis()))
          } else {
            val (newState, action) = recordRequestArrival(state)
            action match {
              case ArrivalAction.SkipRolloverGrace =>
                // All-null outcomes — clients render unfilled slots, retry next page load.
                replyTo ! BatchSelected(
                  slots.map(s =>
                    BatchSlotOutcome(
                      slotId = s.slotId, winner = None,
                      dogear = AdServer.dogearFallthrough(s, persistedApprovedIds.contains)
                    )),
                  Set.empty
                )
                behavior(newState)
              case ArrivalAction.SkipWarmup =>
                replyTo ! BatchSelected(
                  slots.map(s =>
                    BatchSlotOutcome(
                      slotId = s.slotId, winner = None,
                      dogear = AdServer.dogearFallthrough(s, persistedApprovedIds.contains)
                    )),
                  Set.empty
                )
                behavior(newState)
              case ArrivalAction.Proceed =>
                if (slots.isEmpty) {
                  replyTo ! BatchSelected(Vector.empty)
                  behavior(newState)
                } else {
                  import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
                  given Timeout = Timeout(300.millis)
                  // Fetch each slot's ServeView in parallel so the batch
                  // handler can score against whichever pool is populated.
                  // Unions the pools below by taking the first non-empty view
                  // (simplification — all slots on the same page URL pull from
                  // the same site auction, so representative pools overlap).
                  val viewFutures: Vector[Future[Option[ServeView]]] =
                    slots.map { slot =>
                      val slotKey = key(siteId, slot.slotId)
                      serveIndex
                        .ask[Option[ServeView]](ServeIndexDData.Get(slotKey, _))
                        .recover { case _ => None }
                    }
                  ctx.pipeToSelf(Future.sequence(viewFutures)) {
                    case scala.util.Success(views) =>
                      // Union every slot's pool, deduped by creativeId.
                      // The previous `views.flatten.headOption` worked when
                      // every creative was registered at one fixed pixel
                      // size — picking any one slot's view gave a representative
                      // pool. With fluid creatives whose CandidateView carries
                      // the creative's NATIVE render size (regardless of which
                      // slot it bid for), only the slot whose pixel size matches
                      // that native size could fill. Unioning + dedup hands
                      // every creative to every slot's `pickBestForSlot`,
                      // which then applies fluid vs. exact-size rules.
                      val merged: Option[ServeView] = views.flatten match {
                        case Vector() => None
                        case nonEmpty =>
                          val deduped = nonEmpty.iterator
                            .flatMap(_.candidates)
                            .toVector
                            .distinctBy(_.creativeId)
                          val pageCats = nonEmpty.iterator.flatMap(_.pageCategories).toSet
                          val maxExpiry = nonEmpty.map(_.expiresAtMs).max
                          Some(ServeView(deduped, nonEmpty.head.version, maxExpiry, pageCats))
                      }
                      BatchSelectViewLoaded(
                        view = merged,
                        url, slots, classificationFreshnessWindowMs, replyTo, excludedCreatives, excludedCampaigns
                      )
                    case scala.util.Failure(_) =>
                      BatchSelectViewLoaded(None, url, slots, classificationFreshnessWindowMs, replyTo,
                        excludedCreatives,
                        excludedCampaigns)
                  }
                  behavior(newState)
                }
            }
          }
        }

      case BatchSelectViewLoaded(viewOpt, url, slots, classificationFreshnessWindowMs, replyTo, excludedCreatives,
            excludedCampaigns) =>
        // Whenever a slot carries a pin but the ServeView path can't
        // produce a winner, surface dogearFallthrough so the bootstrap
        // can decide whether to clear the IDB pin (only emits
        // creative_removed when the creativeId is no longer in the
        // approved set — transient empty pools leave the pin alone).
        def emptyOutcomes(cats: Set[String], reclassifyInMs: Long): BatchSelected = BatchSelected(
          slots.map(s =>
            BatchSlotOutcome(
              slotId = s.slotId,
              winner = None,
              dogear = AdServer.dogearFallthrough(s, persistedApprovedIds.contains)
            )),
          cats,
          reclassifyInMs = reclassifyInMs,
          needText = reclassifyInMs <= 0 // legacy derived view for the old bootstrap
        )
        viewOpt match {
          case None =>
            // Index has nothing for this slot — ask the auctioneer to (re)populate
            // so the next serve fills, instead of waiting for the periodic backstop.
            selfHealReauction(url)
            // Freshness token from the recorded classifiedAt: <=0 means never
            // classified OR stale → the ad tag (re)classifies; >0 means known +
            // fresh (ServeView merely lost) → self-heal alone repopulates it.
            // Keying off classifiedAt (recorded the moment a page is classified,
            // independent of bidders) is what stops a classified-but-no-bidder
            // page from re-classifying on every serve.
            val reclassify = reclassifyInMsFor(url.value, classificationFreshnessWindowMs)
            replyTo ! emptyOutcomes(Set.empty, reclassifyInMs = reclassify)
            behavior(state.copy(serveStats = serveStats.recordNoCandidates))
          case Some(view) if view.candidates.isEmpty =>
            selfHealReauction(url)
            val reclassify = reclassifyInMsFor(url.value, classificationFreshnessWindowMs)
            replyTo ! emptyOutcomes(view.pageCategories, reclassifyInMs = reclassify)
            behavior(state.copy(serveStats = serveStats.recordNoCandidates))
          case Some(view) =>
            val nowMs = System.currentTimeMillis()
            // Classification-freshness filter — mirror processSelectViewLoaded.
            // Skip when freshnessWindowMs is 0 (publisher opted out / not
            // configured) to avoid accidentally dropping every candidate.
            val freshnessFiltered =
              if (classificationFreshnessWindowMs > 0)
                view.candidates.filter(c => nowMs - c.classifiedAtMs <= classificationFreshnessWindowMs)
              else view.candidates
            if (classificationFreshnessWindowMs > 0 && freshnessFiltered.isEmpty && view.candidates.nonEmpty) {
              val ages = view.candidates.map(c => nowMs - c.classifiedAtMs)
              log.info(
                "BATCH ContentTooOld: {} candidates all older than freshnessWindow={}ms (ages={}ms)",
                view.candidates.size: java.lang.Integer,
                classificationFreshnessWindowMs: java.lang.Long,
                ages.mkString(","): String
              )
              // Reply with the freshness TOKEN, not the bare BatchContentTooOld
              // 204: a token-less 204 gave the ad tag no reclassify signal, so a
              // page whose candidates all aged past the window stayed dark
              // FOREVER (re-auctions restamp the OLD classifiedAt; only a
              // /v1/classify-page from the tag refreshes it — and the tag only
              // classifies on reclassifyInMs <= 0). Same shape as the
              // empty-candidates branches above; still no winner served (stale
              // context must not serve), still counted as contentTooOld.
              // Re-auction covers the other stale flavor: classification
              // already refreshed but the view not yet repopulated.
              selfHealReauction(url)
              replyTo ! emptyOutcomes(
                view.pageCategories,
                reclassifyInMs = reclassifyInMsFor(url.value, classificationFreshnessWindowMs)
              )
              behavior(state.copy(serveStats = serveStats.recordContentTooOld))
            } else {
              val currentMsSinceLast = if (lastRequestTimeMs > 0) nowMs - lastRequestTimeMs else 0L
              val filteredView = view.copy(candidates = freshnessFiltered)
              val selCtx = SelectionContext(
                creativeStats = creativeStats,
                pacingStrategy = pacingStrategy,
                requestArrivalRate = smoothedReqRate,
                pendingSpendByCampaign = pendingSpendByCampaign,
                dayDurationSeconds = dayDurationSeconds,
                spendInfoCache = spendInfoCache,
                trafficShapeTracker = trafficShapeTracker,
                lastDayStart = lastDayStart,
                requestCount = requestCount,
                msSinceLastRequest = currentMsSinceLast,
                lastCampaignSet = lastCampaignSet
              )
              checkBatchPacingGate(filteredView, url, slots, replyTo, selCtx, state.pageWinners, excludedCreatives,
                excludedCampaigns)
              Behaviors.same
            }
        }

      case BatchSpendInfoFetched(fetchedInfo, view, url, slots, replyTo, selCtx, excludedCreatives,
            excludedCampaigns) =>
        val now = Instant.now()
        val updatedCache = spendInfoCache ++ fetchedInfo
        val currentCampaignSet = view.candidates.map(_.campaignId).toSet
        if (fetchedInfo.isEmpty) {
          // Fetch returned nothing — fail open, serve without pacing
          // gating. Mirrors SpendInfoFetched's dummy path in the per-
          // slot flow.
          log.warn("BATCH PACING: fetch returned no spend info, serving without gate ({} slots)",
            slots.size: java.lang.Integer)
          val pageKey = AdServer.pageWinnersKeyFor(siteId, url)
          val pageBlocked: Set[String] = state.pageWinners.get(pageKey)
            .map(_.campaigns).getOrElse(Set.empty)
          ctx.self ! BatchPacingGateResult(
            shouldServe = true,
            view = view,
            url = url,
            slots = slots,
            eligibleCandidates = view.candidates,
            pageBlocked = pageBlocked,
            replyTo = replyTo,
            currentCampaignSet = currentCampaignSet,
            excludedCreatives = excludedCreatives,
            excludedCampaigns = excludedCampaigns
          )
          behavior(state.copy(
            spendInfoCache = updatedCache,
            lastRequestTimeMs = now.toEpochMilli
          ))
        } else {
          // Mix-change reset if needed.
          if (lastCampaignSet.nonEmpty && currentCampaignSet != lastCampaignSet) {
            log.info("BATCH PACING: campaign mix changed, resetting PI")
            pacingStrategy.reset()
          }
          val validInfos = fetchedInfo.toSeq
          val cpmByCampaign = PacingLogic.computeCpmByCampaign(view.candidates)
          val (totalDailyBudget, totalTodaySpend, avgCpm) = PacingLogic.computeAggregateBudget(
            validInfos, cpmByCampaign, pendingSpendByCampaign
          )
          val cachedDayStart = validInfos.map(_._2.dayStart).minBy(_.toEpochMilli)
          // Real calendar days pace against the CAMPAIGN's day anchor (UTC
          // midnight), never the entity's in-memory anchor: lastDayStart
          // resets to ~boot on every restart, which made expectedSpend start
          // from ~$0 while todaySpend carried the whole day's real spend —
          // spendRatio exploded (12-18x observed live 2026-07-06) and the PI
          // throttled 100% of serves for minutes after every restart, and any
          // spend burst (traffic simulator) re-inflated it indefinitely.
          // lastDayStart still wins for simulated days, whose rollovers are
          // entity-driven.
          val effectiveDayStart =
            if (selCtx.dayDurationSeconds == 86400) cachedDayStart
            else lastDayStart.filter(_.isAfter(cachedDayStart)).getOrElse(cachedDayStart)
          val pacingCtx = PacingContext(
            dailyBudget = totalDailyBudget,
            todaySpend = totalTodaySpend,
            dayStart = effectiveDayStart,
            now = now,
            requestArrivalRate = selCtx.requestArrivalRate,
            competingCampaigns = 1,
            avgCpm = avgCpm,
            dayDurationSeconds = selCtx.dayDurationSeconds,
            trafficShape = Some(selCtx.trafficShapeTracker),
            requestCount = selCtx.requestCount,
            msSinceLastRequest = selCtx.msSinceLastRequest
          )
          val throttle = pacingStrategy.throttleProbability(pacingCtx)
          val requestPasses = rng.nextDouble() >= throttle
          val firstInfo = {
            val h = validInfos.head._2
            CampaignEntity.SpendInfo(h.dailyBudget, h.todaySpend, h.dayStart)
          }
          val eligibleCampIds = validInfos.map(_._1).toSet
          val eligibleCandidates = if (requestPasses)
            view.candidates.filter(c => eligibleCampIds.contains(c.campaignId))
          else Vector.empty
          val pageKey = AdServer.pageWinnersKeyFor(siteId, url)
          val pageBlocked: Set[String] = state.pageWinners.get(pageKey)
            .map(_.campaigns).getOrElse(Set.empty)
          log.info("BATCH PACING: throttle={}% passes={} candidates={} spendRatio={}",
            f"${throttle * 100}%.0f", requestPasses, eligibleCandidates.size: java.lang.Integer,
            f"${pacingCtx.spendRatio}%.2f")
          val _ = firstInfo // reserved for future logging parity
          ctx.self ! BatchPacingGateResult(
            shouldServe = requestPasses,
            view = view,
            url = url,
            slots = slots,
            eligibleCandidates = eligibleCandidates,
            pageBlocked = pageBlocked,
            replyTo = replyTo,
            currentCampaignSet = currentCampaignSet,
            excludedCreatives = excludedCreatives,
            excludedCampaigns = excludedCampaigns
          )
          behavior(state.copy(
            lastDayStart = Some(effectiveDayStart),
            spendInfoCache = updatedCache,
            lastRequestTimeMs = now.toEpochMilli,
            lastCampaignSet = currentCampaignSet
          ))
        }

      case BatchPacingGateResult(shouldServe, view, url, slots, eligibleCandidates, pageBlocked, replyTo,
            newCampaignSet, excludedCreatives, excludedCampaigns) =>
        if (!shouldServe) {
          // Pacing-throttled batches still honor pin hints — the reader
          // explicitly bookmarked this creative and folds are free, so
          // throttling shouldn't void the user's gesture. Walk the slots:
          // if a slot has a pin AND the pinned creativeId is in the
          // candidate pool, emit it as honored at clearingPrice=zero (no
          // CPM spend, mirrors the in-auction pin-honor path). Other
          // slots return winner=None as normal.
          val pinHonoredOutcomes = slots.map { slot =>
            val pinned: Option[CandidateView] =
              slot.pin.flatMap(cid => view.candidates.find(_.creativeId == cid))
            pinned match {
              case Some(c) =>
                BatchSlotOutcome(
                  slotId = slot.slotId,
                  winner = Some(c),
                  clearingPrice = CPM.zero,
                  requestId = java.util.UUID.randomUUID().toString,
                  dogear = Some(DogearOutcome(honored = true))
                )
              case None =>
                // Pin couldn't be honored (creative no longer in pool).
                // Surface the same `creative_removed` signal the normal
                // auction path emits so the client can clean up its IDB
                // pin even when pacing throttled the batch.
                BatchSlotOutcome(
                  slotId = slot.slotId,
                  winner = None,
                  dogear = AdServer.dogearFallthrough(slot, persistedApprovedIds.contains)
                )
            }
          }
          replyTo ! BatchSelected(pinHonoredOutcomes, view.pageCategories)
          behavior(state.copy(
            serveStats = serveStats.recordPacingSkipped,
            lastCampaignSet = newCampaignSet
          ))
        } else {
          val afterFloor = eligibleCandidates.filter(c => c.cpm >= categoryFloorFor(c.category))
          // Only short-circuit to "all empty" when nothing clears the floor
          // AND no slot carries a pin. If a pin is present we must still fall
          // through to batchReserveWithRetry: dog-ear pins bypass the floor
          // (they serve at CPM.zero against the pre-floor `pinLookupPool`), so
          // an empty `afterFloor` during a high-floor sweep cycle must not blank
          // a pinned slot. The reserve loop handles an empty auction pool fine —
          // non-pinned slots resolve to winner=None, pinned slots honor from the
          // pre-floor pool.
          if (afterFloor.isEmpty && !slots.exists(_.pin.isDefined)) {
            replyTo ! BatchSelected(
              slots.map(s =>
                BatchSlotOutcome(
                  slotId = s.slotId, winner = None,
                  dogear = AdServer.dogearFallthrough(s, persistedApprovedIds.contains)
                )),
              view.pageCategories
            )
            behavior(state.copy(
              serveStats = serveStats.recordNoCandidates,
              lastCampaignSet = newCampaignSet
            ))
          } else {
            // Run initial assignment + retry loop as a Future chain.
            // pinLookupPool = eligibleCandidates (pre-floor) so reader
            // bookmarks survive sweep cycles testing high-floor candidates.
            // The auction pool stays floor-filtered; only the pin-honor
            // lookup gets the wider view.
            val outcomesF = batchReserveWithRetry(
              slots = slots,
              pool = afterFloor,
              pageBlocked = pageBlocked,
              alpha = siteBidWeight,
              stats = creativeStats,
              excludedCreatives = excludedCreatives,
              excludedCampaigns = excludedCampaigns,
              isApproved = persistedApprovedIds.contains,
              pinLookupPool = eligibleCandidates
            )
            ctx.pipeToSelf(outcomesF) {
              case Success((outcomes, pending)) =>
                BatchReservationsResolved(outcomes, pending, url, view.pageCategories, replyTo)
              case Failure(ex) =>
                log.warn("BATCH RESERVE: future failed: {}", ex.getMessage)
                BatchReservationsResolved(
                  slots.map(s => BatchSlotOutcome(s.slotId, None)),
                  Map.empty,
                  url,
                  view.pageCategories,
                  replyTo
                )
            }
            behavior(state.copy(lastCampaignSet = newCampaignSet))
          }
        }

      case BatchReservationsResolved(outcomes, pendingDeltas, url, pageCats, replyTo) =>
        // Record pending-spend deltas so concurrent batches don't over-
        // reserve before impression events commit. Key off campaignId;
        // timestamp now so the stale-cleaner sweeps it if never committed.
        val now = Instant.now()
        val updatedPending = pendingDeltas.foldLeft(pendingSpendByCampaign) { case (acc, (campId, delta)) =>
          val (current, _) = acc.getOrElse(campId, (0.0, now))
          acc.updated(campId, (current + delta, now))
        }
        // Record page-winners for each confirmed winner so subsequent
        // serves on the same URL see them in pageBlocked.
        val pageKey = AdServer.pageWinnersKeyFor(siteId, url)
        val nowMs = now.toEpochMilli
        val winningCampaigns: Set[String] = outcomes.flatMap(_.winner).map(_.campaignId.value).toSet
        val winningCreatives: Set[String] = outcomes.flatMap(_.winner).map(_.creativeId.value).toSet
        val updatedPageWinners = if (winningCampaigns.isEmpty) state.pageWinners
        else {
          val prev = state.pageWinners.get(pageKey)
          val existingCamps = prev.map(_.campaigns).getOrElse(Set.empty)
          val existingCreatives = prev.map(_.creatives).getOrElse(Set.empty)
          state.pageWinners.updated(
            pageKey,
            AdServer.PageWinners(
              campaigns = existingCamps ++ winningCampaigns,
              firstSeenMs = prev.map(_.firstSeenMs).getOrElse(nowMs),
              creatives = existingCreatives ++ winningCreatives
            )
          )
        }
        val servedCount = outcomes.count(_.winner.isDefined)
        val emptyCount = outcomes.size - servedCount
        log.info("BATCH SERVED: {} slots filled, {} unfilled",
          servedCount: java.lang.Integer, emptyCount: java.lang.Integer)
        replyTo ! BatchSelected(outcomes, pageCats)
        // Increment serveStats per-winner via recordSelectedInBucket so
        // `totalSpend` and `hourlyImpressions` update alongside `selected`.
        // Earlier code took a shortcut (`serveStats.copy(selected += N)`)
        // that bumped only the count and silently left totalSpend at 0 —
        // breaks the dashboard Revenue tile and stalls traffic-shape
        // learning since hourly buckets never populate.
        val updatedServeStats: ServeStats = if (servedCount == 0) {
          serveStats.recordNoCandidates
        } else {
          val hour = java.time.LocalTime.now(java.time.ZoneOffset.UTC).getHour
          outcomes.foldLeft(serveStats) { (stats, o) =>
            o.winner match {
              case Some(_) =>
                // Use the per-slot clearing price (what the winner actually
                // pays). Falls back to winner.cpm when clearingPrice is
                // zero — that case shouldn't occur in normal serve paths
                // but keeps the counter consistent if it ever does.
                val cpm: Double =
                  if (o.clearingPrice.toDouble > 0) o.clearingPrice.toDouble
                  else o.winner.map(_.cpm.toDouble).getOrElse(0.0)
                stats.recordSelectedInBucket(hour, cpm / 1000.0)
              case None => stats
            }
          }
        }
        // Track creativeIds whose pin this batch honored. Best-effort signal for
        // the topic-narrow orphan-eligibility predicate: a pinned creative must
        // survive eviction when the advertiser drops a DIFFERENT topic. Pins are
        // pruned when the creative leaves approval (CampaignPaused / CreativePaused
        // / EvictCampaignFromSite).
        val honoredPins: Set[CreativeId] =
          outcomes.flatMap(o => o.dogear.filter(_.honored).flatMap(_ => o.winner.map(_.creativeId))).toSet
        val updatedPinned =
          if (honoredPins.isEmpty) state.pinnedCreativeIds else state.pinnedCreativeIds ++ honoredPins
        // Record a per-creative impression for every served winner so
        // Thompson Sampling scores against real Beta posteriors instead
        // of scoring every creative through the cold branch forever.
        // The retired per-slot Select path used to do this via a Reserved
        // handler; the batch path is now the only serve path, so it must
        // record here. Pin re-encounters (dogeared) stay learning-silent,
        // matching the billing/CTR treatment in LearningEventLog.
        val impressedCreatives: Vector[CreativeId] = AdServer.impressedCreatives(outcomes)
        val updatedCreativeStats =
          impressedCreatives.foldLeft(creativeStats) { (acc, cid) =>
            acc.updatedWith(cid)(_.orElse(Some(CreativeStats())).map(_.recordImpression(now)))
          }
        behavior(state.copy(
          pendingSpendByCampaign = updatedPending,
          pageWinners = updatedPageWinners,
          serveStats = updatedServeStats,
          pinnedCreativeIds = updatedPinned,
          creativeStats = updatedCreativeStats
        ))
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // STATS RECORDING
  // Handles impression/click tracking and periodic snapshots
  // ═══════════════════════════════════════════════════════════════════════════

  private def statsRecording(state: State): PartialFunction[Command, Behavior[Command]] = {
    import state.*
    {
      case RecordImpression(creativeId) =>
        val now = Instant.now()
        behavior(state.copy(
          creativeStats = creativeStats.updatedWith(creativeId)(
            _.orElse(Some(CreativeStats())).map(_.recordImpression(now)))
        ))

      case RecordClick(creativeId) =>
        val now = Instant.now()
        behavior(state.copy(
          creativeStats = creativeStats.updatedWith(creativeId)(_.orElse(Some(CreativeStats())).map(_.recordClick(now)))
        ))

      case RecordFold(creativeId) =>
        val now = Instant.now()
        behavior(state.copy(
          creativeStats = creativeStats.updatedWith(creativeId)(_.orElse(Some(CreativeStats())).map(_.recordFold(now)))
        ))

      case GetCreativeStats(replyTo) =>
        val statsMap = creativeStats.map { case (cid, stats) => cid.value -> stats }
        replyTo ! CreativeStatsMap(siteId.value, statsMap)
        Behaviors.same

      case SnapshotStats =>
        processSnapshotStats(creativeStats, trafficShapeTracker)
        Behaviors.same

      case SnapshotSaveResult(count, errorOpt) =>
        errorOpt.fold(
          log.info("Stats snapshot saved: site={} creatives={}", siteId.value, count)
        )(ex =>
          log.error("Failed to save stats snapshot: site={} error={}", siteId.value, ex.getMessage)
        )
        Behaviors.same
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MAINTENANCE
  // Handles periodic cleanup and snapshot persistence
  // ═══════════════════════════════════════════════════════════════════════════

  private def maintenance(state: State): PartialFunction[Command, Behavior[Command]] = {
    import state.*
    {
      case PublishTrafficRatio =>
        val elapsed =
          lastDayStart.map(ds => java.time.Duration.between(ds, Instant.now()).getSeconds.toDouble).getOrElse(0.0)
        val ratio = if (trafficShapeTracker.isWarmedUp) trafficShapeTracker.relativeVolumeAtTime(elapsed) else 1.0
        val data = AdServer.TrafficRatioData(ratio, trafficShapeTracker.isWarmedUp)
        replicator ! Replicator.Update(
          AdServer.TrafficRatioKey,
          LWWMap.empty[SiteId, AdServer.TrafficRatioData],
          Replicator.WriteLocal,
          ddataUpdateAdapter
        )(_.put(selfUniqueAddress, siteId, data))
        Behaviors.same

      case RemoveCampaign(campaignId) =>
        log.info("Removing campaign {} from ServeView for site {}", campaignId.value, siteId.value)
        serveIndex ! ServeIndexDData.RemoveCampaignBySite(siteId.value, campaignId)
        Behaviors.same

      case Passivate =>
        log.info("Passivating AdServer for site {} (admin request)", siteId.value)
        Behaviors.stopped

      case CleanupStalePending =>
        val now = Instant.now()
        val staleThreshold = java.time.Duration.ofSeconds(3)
        val cleaned = pendingSpendByCampaign.filter { case (_, (_, ts)) =>
          java.time.Duration.between(ts, now).compareTo(staleThreshold) < 0
        }
        if (cleaned.size < pendingSpendByCampaign.size) {
          val removed = pendingSpendByCampaign.size - cleaned.size
          log.debug("Cleaned up {} stale pending spend entries for publisher {}", removed, siteId.value)
          behavior(state.copy(pendingSpendByCampaign = cleaned))
        } else {
          Behaviors.same
        }

      case PurgeExpired =>
        processPurgeExpired()
        // Also clean up stale spend cache entries (campaigns that haven't sent updates in 1 hour)
        val staleMaxAge = 1.hour
        val beforeCount = spendInfoCache.size
        val cleanedState = state.cleanupStaleSpendCache(staleMaxAge)
        val removedCount = beforeCount - cleanedState.spendInfoCache.size
        if (removedCount > 0) {
          log.info("Cleaned up {} stale spend cache entries for publisher {}", removedCount, siteId.value)
        }
        // Clean up creativeStats entries whose buckets have all expired
        val now = Instant.now()
        val cutoffMinute = now.getEpochSecond / 60 - 60 // windowMinutes default = 60
        val prunedStats = cleanedState.creativeStats.filter { case (_, stats) =>
          stats.buckets.exists { case (minute, _) => minute > cutoffMinute }
        }
        val statsRemoved = cleanedState.creativeStats.size - prunedStats.size
        if (statsRemoved > 0) {
          log.debug("Cleaned up {} empty creativeStats entries for publisher {}", statsRemoved, siteId.value)
        }
        behavior(cleanedState.copy(creativeStats = prunedStats))

      case PurgeExpiredResult(count) =>
        if (count > 0) {
          log.info("Purged {} expired pending selections for publisher {}", count, siteId.value)
        }
        Behaviors.same

      case CreativeStatsLoaded(rawStats) =>
        if (rawStats.isEmpty) {
          log.debug("No recent creative stats found for site {}", siteId.value)
          Behaviors.same
        } else {
          val restored = rawStats.map { case (cid, buckets) =>
            CreativeId(cid) -> CreativeStats(buckets = buckets)
          }
          log.info("Restored creative stats for site {}: {} creatives from tracking_events", siteId.value,
            restored.size)
          behavior(state.copy(creativeStats = restored ++ creativeStats))
        }

      case TrafficShapeSnapshotLoaded(snapshotOpt) =>
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek
        snapshotOpt.fold {
          log.debug("No traffic shape snapshot found for site {}, starting with uniform distribution", siteId.value)
          trafficShapeTracker.setDayType(today)
          Behaviors.same[Command]
        } { snapshot =>
          log.info(
            "Traffic shape snapshot restored for site {}: {} buckets, updated at {}",
            siteId.value,
            snapshot.bucketCount,
            snapshot.updatedAt
          )
          val restoredTracker = TrafficShapeTracker.fromSnapshot(snapshot, interpolateVolumes = true)
          restoredTracker.setDayType(today)
          behavior(state.copy(trafficShapeTracker = restoredTracker))
        }

      case TrafficShapeSnapshotSaveResult(errorOpt) =>
        errorOpt.fold(
          log.debug("Traffic shape snapshot saved for site {}", siteId.value)
        )(ex =>
          log.warn("Failed to save traffic shape snapshot for site {}: {}", siteId.value, ex.getMessage)
        )
        Behaviors.same

      case ApprovedCreativeIdsLoaded(ids, advertiserByCreative, autoApproved) =>
        log.info("Loaded {} persisted approved creative IDs for site {} ({} auto-approved)",
          ids.size, siteId.value, autoApproved.size)
        // Backfill: re-announce each persisted approval to its AdvertiserEntity
        // so `Creative.approvedSites` converges — the bid path reads it to tell
        // approved (floor-teaching) demand from pending demand. Idempotent: the
        // entity skips the journal write when the approval is already recorded.
        advertiserByCreative.foreach { case (creativeId, advertiserId) =>
          sharding.entityRefFor(AdvertiserEntity.TypeKey, advertiserId.value) !
          AdvertiserEntity.UpdateCreativeApproval(
            creativeId = creativeId,
            siteId = siteId,
            status = ApprovalStatus.Approved,
            replyTo = system.ignoreRef
          )
        }
        behavior(state.copy(persistedApprovedIds = ids, autoApprovedIds = autoApproved))

      case ApprovedCreativeIdsLoadFailed(reason, attempt) =>
        // Keep whatever approved set we already have (never clobber with
        // empty) and retry: the load is a read-only SELECT, and without it
        // every auction result gets filtered as unapproved.
        log.error(
          "Approved-creatives load FAILED for site {} (attempt {}): {} — retrying",
          siteId.value, attempt: java.lang.Integer, reason
        )
        if (attempt < 6) {
          ctx.scheduleOnce(
            (5 * attempt).seconds,
            ctx.self,
            ApprovedCreativeIdsRetryLoad(attempt + 1)
          )
        } else {
          log.error(
            "Approved-creatives load for site {} gave up after {} attempts — approvals may be missing until the next approval event or restart",
            siteId.value, attempt: java.lang.Integer
          )
        }
        Behaviors.same

      case ApprovedCreativeIdsRetryLoad(attempt) =>
        ctx.pipeToSelf(store.getApprovedCreativeMeta(siteId.value)) {
          case Success(rows) =>
            ApprovedCreativeIdsLoaded(
              rows.map(r => CreativeId(r.creativeId)).toSet,
              rows.map(r => CreativeId(r.creativeId) -> AdvertiserId(r.advertiserId)).toMap,
              autoApproved = rows.collect { case r if r.approvedVia == "auto" => CreativeId(r.creativeId) }.toSet
            )
          case Failure(e) => ApprovedCreativeIdsLoadFailed(e.getMessage, attempt)
        }
        Behaviors.same

      case TrustAnchorsLoaded(campaigns, domains) =>
        if (campaigns.nonEmpty || domains.nonEmpty) {
          log.info("Loaded auto-approve trust anchors for site {}: {} campaigns, {} domains",
            siteId.value, campaigns.size: java.lang.Integer, domains.size: java.lang.Integer)
        }
        behavior(state.copy(trustedCampaigns = campaigns, trustedDomains = domains))

      case TrustAnchorsLoadFailed(reason, attempt) =>
        // Unlike the approvals load, failing here is fail-SAFE (candidates
        // just queue for manual review), but still retry so an enabled
        // site's auto-approvals resume without a restart.
        log.error(
          "Trust-anchor load FAILED for site {} (attempt {}): {} — retrying",
          siteId.value, attempt: java.lang.Integer, reason
        )
        if (attempt < 6) {
          ctx.scheduleOnce((5 * attempt).seconds, ctx.self, TrustAnchorsRetryLoad(attempt + 1))
        } else {
          log.error(
            "Trust-anchor load for site {} gave up after {} attempts — auto-approve is dormant until the next anchor event or restart",
            siteId.value, attempt: java.lang.Integer
          )
        }
        Behaviors.same

      case TrustAnchorsRetryLoad(attempt) =>
        ctx.pipeToSelf(store.getTrustAnchors(siteId.value)) {
          case Success(anchors) =>
            TrustAnchorsLoaded(
              anchors.collect { case a if a.anchorType == TrustAnchor.TypeCampaign => CampaignId(a.anchorValue) }.toSet,
              anchors.collect { case a if a.anchorType == TrustAnchor.TypeDomain => a.anchorValue }.toSet
            )
          case Failure(e) => TrustAnchorsLoadFailed(e.getMessage, attempt)
        }
        Behaviors.same

      case TrustBroken(campaignId, domainOpt) =>
        val hadTrust = state.trustedCampaigns.contains(campaignId) ||
          domainOpt.exists(state.trustedDomains.contains)
        if (hadTrust) {
          log.info(
            "Auto-approve trust BROKEN for site {}: campaign={} domain={} — siblings return to the manual queue",
            siteId.value, campaignId.value,
            domainOpt.getOrElse("(unknown)")
          )
        }
        // Delete persisted anchors even when the in-memory sets don't have
        // them (boot-race window) so DB and memory converge on "no trust".
        store.deleteTrustAnchorsFor(siteId.value, campaignId.value, domainOpt).failed.foreach { ex =>
          log.error("Failed to delete trust anchors for site {} campaign {}: {}",
            siteId.value, campaignId.value, ex.getMessage)
        }
        behavior(state.copy(
          trustedCampaigns = state.trustedCampaigns - campaignId,
          trustedDomains = domainOpt.fold(state.trustedDomains)(state.trustedDomains - _)
        ))
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CANDIDATE PROCESSING HELPERS
  // Handles auction results: filtering, scoring, routing to ServeIndex
  // Flow: CandidatesCollected → filter → fetch scores → update ServeIndex
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Process candidates using ServeIndex membership for approval status.
   * A creative is approved if it exists in ServeIndex for any slot on this site.
   *
   * IMPORTANT: Preserves "orphaned" approved creatives - creatives that are in ServeIndex
   * but not in the new auction results. This prevents re-auctions from accidentally
   * removing approved creatives from other campaigns (e.g., when their budget is exhausted).
   */
  /**
   * Re-auction pages whose PENDING candidates match the current trust
   * anchors. Runs when the toggle turns on and after a manual approval
   * mints anchors — creatives already sitting in the queue must not wait
   * for organic re-auction traffic to benefit from newly-granted trust.
   * The resulting wave auto-approves them via the normal candidate
   * partition (same re-auction trick the flag handler uses to fill
   * vacancies), so ServeIndex, floor teaching, and the SSE live update
   * all ride the standard path. No-op while the toggle is off.
   */
  private def sweepPendingForTrust(state: State): Unit =
    if (state.cachedAutoApprove.exists(_.enabled) &&
      (state.trustedCampaigns.nonEmpty || state.trustedDomains.nonEmpty)) {
      val campaigns = state.trustedCampaigns
      val domains = state.trustedDomains
      ctx.pipeToSelf(store.pendingQueue(siteId.value)) {
        case Success(rows) =>
          TrustSweepReauction(rows.collect {
            case (url, _, c)
                if campaigns.contains(c.campaignId) ||
                RegistrableDomain.of(c.landingDomain).exists(domains.contains) =>
              url
          }.distinct)
        case Failure(ex) =>
          log.warn("Auto-approve trust sweep: pending-queue read failed for site {}: {}",
            siteId.value, ex.getMessage)
          TrustSweepReauction(Vector.empty)
      }
    }

  private def processCandidatesWithApprovalStatus(
      url: URL,
      slotId: SlotId,
      candidates: Vector[Candidate],
      classifiedAt: Instant,
      ttl: FiniteDuration,
      slotKey: String,
      existingCreativeIds: Set[CreativeId],
      existingView: Option[ServeView], // Full view to preserve orphaned approved creatives
      cachedDomainBlocklist: Option[PublisherEntity.CachedDomainBlocklist],
      cachedAdProductBlocklist: Option[SiteEntity.CachedAdProductBlocklist],
      cachedAdvertiserBlocklists: Map[AdvertiserId, Set[String]],
      mySiteDomain: Option[String],
      autoApproveEnabled: Boolean,
      trustedCampaigns: Set[CampaignId],
      trustedDomains: Set[String]
  ): Set[CreativeId] = {
    log.info(
      "AdServer[{}] processing {} candidates with ServeIndex approval: url={} slot={} existingApproved={}",
      siteId.value,
      candidates.size,
      url.value,
      slotId.value,
      existingCreativeIds.size
    )

    // Filter out candidates whose landing domain is blocklisted by this publisher
    log.info(
      "🔍 Domain blocklist check: site={} blocklist={} candidates={}",
      siteId.value,
      cachedDomainBlocklist.map(_.domains.mkString(",")).getOrElse("(none)"),
      candidates.map(c => s"${c.creativeId.value}:domain='${c.landingDomain}'").mkString(", ")
    )
    val domainFiltered = cachedDomainBlocklist match {
      case Some(blocklist) =>
        val (blocked, allowed) = candidates.partition(c => blocklist.contains(c.landingDomain))
        if (blocked.nonEmpty) {
          log.info(
            "🚫 Filtered {} candidates by domain blocklist: pub={} blocked_domains={}",
            blocked.size, siteId.value,
            blocked.map(_.landingDomain).distinct.mkString(",")
          )
        }
        log.info("After domain filter: {} allowed, {} blocked", allowed.size, blocked.size)
        allowed
      case None =>
        candidates
    }

    // Filter out candidates whose ad product category is blocklisted by this site
    log.info(
      "🔍 Ad product blocklist check: site={} blocklist={} remaining={}",
      siteId.value,
      cachedAdProductBlocklist.map(_.categories.map(_.value).mkString(",")).getOrElse("(none)"),
      domainFiltered.size
    )
    val productFiltered = cachedAdProductBlocklist match {
      case Some(blocklist) =>
        val (blocked, allowed) = domainFiltered.partition(c => blocklist.contains(c.adProductCategory))
        if (blocked.nonEmpty) {
          log.info(
            "🚫 Filtered {} candidates by ad product blocklist: pub={} blocked_categories={}",
            blocked.size, siteId.value,
            blocked.flatMap(_.adProductCategory.map(_.value)).distinct.mkString(",")
          )
        }
        log.info("After ad product filter: {} allowed, {} blocked", allowed.size, blocked.size)
        allowed
      case None =>
        domainFiltered
    }

    // Filter out candidates whose advertiser has blocked this site's domain.
    val (advBlocked, filteredCandidates) =
      AdServer.partitionByAdvertiserBlocklist(productFiltered, cachedAdvertiserBlocklists, mySiteDomain)
    if (advBlocked.nonEmpty) {
      log.info(
        "🚫 Filtered {} candidates by advertiser site-domain blocklist: site={} domain={} blocked_advertisers={}",
        advBlocked.size, siteId.value, mySiteDomain.getOrElse(""),
        advBlocked.map(_.advertiserId.value).distinct.mkString(",")
      )
    }

    // existingCreativeIds includes creatives from this slot's ServeIndex AND creatives
    // approved at any other slot on this site (via keysByCreative inverted index).
    // This ensures a creative approved at one slot is treated as approved site-wide.
    val (alreadyApproved, rest) = filteredCandidates.partition(c =>
      existingCreativeIds.contains(c.creativeId)
    )

    // Auto-approve: with the site's toggle on, a NEW candidate whose
    // campaign or landing registrable-domain the publisher already manually
    // approved skips the queue and rides the approved batch path below —
    // which gives it the same ServeIndex entry a manual approval gets.
    val (autoApproved, pending) =
      if (autoApproveEnabled && (trustedCampaigns.nonEmpty || trustedDomains.nonEmpty))
        rest.partition(c =>
          trustedCampaigns.contains(c.campaignId) ||
          RegistrableDomain.of(c.landingDomain).exists(trustedDomains.contains)
        )
      else (Vector.empty[Candidate], rest)

    autoApproved.foreach { c =>
      log.info(
        "✅ AUTO-APPROVED creative {} (campaign={} domain={}) via trust anchor: pub={} url={} slot={}",
        c.creativeId.value, c.campaignId.value, c.landingDomain, siteId.value, url.value, slotId.value
      )
      // Replicate manual approval's persistence + floor-teaching side
      // effects (completeApprovalWithScore needs a pending Selection this
      // candidate never had, so the batch path handles ServeIndex instead):
      // 1. Persist the approval (survives restart; via='auto' for badging).
      store.insertApproved(siteId.value, c.creativeId.value, c.campaignId.value, c.advertiserId.value, via = "auto")
      // 2. Terminal outcome — queue-age tracking + stale pending rows from
      //    earlier waves are no longer meaningful.
      store.deleteFirstSeen(siteId.value, Set(c.creativeId.value))
      store.removeCreativeFromAll(siteId.value, c.creativeId.value).failed.foreach { ex =>
        log.warn("Auto-approve pending cleanup failed for creative {} on site {}: {}",
          c.creativeId.value, siteId.value, ex.getMessage)
      }
      // 3. Teach floors: approvedSites on the AdvertiserEntity is what the
      //    bid path reads to tell approved demand from pending. Fire-and-
      //    forget is the sanctioned boot-backfill form (idempotent there).
      sharding.entityRefFor(AdvertiserEntity.TypeKey, c.advertiserId.value) !
      AdvertiserEntity.UpdateCreativeApproval(
        creativeId = c.creativeId,
        siteId = siteId,
        status = ApprovalStatus.Approved,
        replyTo = system.ignoreRef
      )
      // 4. Live-update any open approval dashboard.
      budgetEventTopic ! Topic.Publish(
        promovolve.CreativeAutoApproved(siteId, url, slotId, c.creativeId, c.campaignId, Instant.now())
      )
    }

    val approved = alreadyApproved ++ autoApproved

    log.info(
      "Partitioned candidates by site-wide approval: approved={} (auto={}) pending={}",
      approved.size,
      autoApproved.size,
      pending.size
    )

    if (approved.isEmpty) {
      if (pending.isEmpty) {
        log.warn(
          "⚠️ CLEARING ServeIndex (no candidates): pub={} url={} slot={}",
          siteId, url, slotId
        )
        serveIndex ! ServeIndexDData.Remove(slotKey)
      } else {
        log.info(
          "📋 Queueing pending creatives (keeping existing ServeIndex): pub={} url={} slot={} pending={} existingApproved={}",
          siteId, url, slotId, pending.size, existingCreativeIds.size
        )
        // DON'T filter ServeIndex when approved is empty - keep existing approved creatives
        // Only update CPM for campaigns that appear in the new auction
        val campaignCpms = pending.map(c => c.campaignId -> c.cpm).toMap
        if (campaignCpms.nonEmpty) {
          serveIndex ! ServeIndexDData.UpdateCpm(slotKey, campaignCpms)
        }
        queuePendingCandidates(pending, url, slotId, ttl)
      }
    } else {
      // Fetch category scores for approved candidates
      val uniqueCategories = approved.map(_.category).distinct
      ctx.pipeToSelf(fetchCategoryScores(uniqueCategories)) {
        case Success(categoryScores) =>
          CandidateScoresFetched(url, slotId, approved, pending, classifiedAt, ttl, slotKey, categoryScores,
            existingView)
        case Failure(_) =>
          CandidateScoresFetched(url, slotId, approved, pending, classifiedAt, ttl, slotKey, Map.empty, existingView)
      }
    }
    autoApproved.map(_.creativeId).toSet
  }

  /**
   * Build ServeView from candidates and update ServeIndex + inverted indexes.
   * Kicks off async creative lookups via CandidateLogic.buildCandidateViews
   * and pipes the result back as CandidateViewsBuilt.
   */
  private def buildServeViewFromCandidates(
      url: URL,
      slotId: SlotId,
      approved: Vector[Candidate],
      pending: Vector[Candidate],
      classifiedAt: Instant,
      ttl: FiniteDuration,
      slotKey: String,
      categoryScores: Map[CategoryId, Double],
      existingView: Option[ServeView] // Preserve orphaned approved creatives
  ): Unit = {
    val useDefaultScores = categoryScores.isEmpty
    if (useDefaultScores) {
      log.warn(
        "Failed to fetch category scores, using defaults: pub={} url={} slot={}",
        siteId.value,
        url.value,
        slotId.value
      )
    }

    val effectiveScores = if (useDefaultScores) Map.empty[CategoryId, Double] else categoryScores

    // Build CandidateViews using CandidateLogic helper (async)
    val candidateViewsFuture = CandidateLogic.buildCandidateViews(
      approved,
      creativeLookup = { creativeId =>
        creativeRepo.get(creativeId).map { result =>
          if (result.isEmpty) {
            log.warn(
              "Pre-approved creative missing: pub={} url={} slot={} creative={}",
              siteId.value, url, slotId, creativeId
            )
          }
          result
        }
      },
      effectiveScores,
      classifiedAt,
      defaultScore = 0.5
    )

    ctx.pipeToSelf(candidateViewsFuture) {
      case Success(views) =>
        CandidateViewsBuilt(url, slotId, views, pending, ttl, slotKey, existingView)
      case Failure(ex) =>
        log.warn("Failed to build candidate views: pub={} url={} slot={}: {}",
          siteId.value, url.value, slotId.value, ex.getMessage)
        CandidateViewsBuilt(url, slotId, Vector.empty, pending, ttl, slotKey, existingView)
    }
  }

  /**
   * Handle completion of async candidate view building.
   * Merges with orphaned views and updates ServeIndex + inverted indexes.
   * Returns updated state.
   */
  private def handleCandidateViewsBuilt(
      state: State,
      url: URL,
      slotId: SlotId,
      candidateViews: Vector[CandidateView],
      pending: Vector[Candidate],
      ttl: FiniteDuration,
      slotKey: String,
      existingView: Option[ServeView]
  ): State = {
    // IMPORTANT: Preserve "orphaned" approved creatives - creatives that are in ServeIndex
    // but not in the new auction results. This prevents re-auctions from accidentally
    // removing approved creatives from other campaigns (e.g., when their budget is exhausted).
    val newCreativeIds = candidateViews.map(_.creativeId).toSet
    val orphanedViews = existingView match {
      case Some(view) =>
        // Preserve ALL orphaned approved creatives regardless of the current
        // floor. Floor enforcement happens at serve time, not here: the auction
        // pool is floor-filtered (`afterFloor`) while the pin-honor lookup
        // deliberately uses the pre-floor pool (`pinLookupPool`). Filtering
        // orphans by floor at rebuild time would re-evict a below-floor pinned
        // creative during a high-floor sweep cycle and break dog-ear pin-honor
        // until the floor cycled back down — the same symptom the removed
        // RemoveBelowFloor purge caused (see AdServer.scala:1126). Cache hygiene
        // continues via the periodic TTL sweep and the explicit eligibility-
        // change removals (RemoveCampaignBySite, RemoveByDomains, etc.).
        val orphaned = view.candidates.filterNot(c => newCreativeIds.contains(c.creativeId))
        if (orphaned.nonEmpty) {
          log.info(
            "🔒 Preserving {} orphaned approved creatives (not in new auction): pub={} url={} slot={} orphanedIds={}",
            orphaned.size, siteId.value, url.value, slotId.value,
            orphaned.map(_.creativeId.value).mkString(",")
          )
        }
        orphaned
      case None =>
        Vector.empty
    }

    // Merge new candidateViews with orphaned existing views, deduplicate by creativeId
    val mergedViews = (candidateViews ++ orphanedViews).distinctBy(_.creativeId)

    val updatedState = if (mergedViews.nonEmpty) {
      // Build and cache ServeView using CandidateLogic helper
      val serveView = CandidateLogic.buildServeView(mergedViews, ttl.toMillis)
      serveIndex ! ServeIndexDData.Put(slotKey, serveView)
      log.info(
        "Cached {} candidates to ServeIndex (new={} orphaned={}): pub={} url={} slot={}",
        mergedViews.size, candidateViews.size, orphanedViews.size,
        siteId.value,
        url.value,
        slotId.value
      )
      updateIndexesOnPut(state, slotKey, mergedViews)
    } else {
      // All approved candidates had missing metadata AND no orphaned - clear ServeIndex
      log.warn(
        "⚠️ CLEARING ServeIndex (missing metadata): pub={} url={} slot={} approvedCount={}",
        siteId.value,
        url.value,
        slotId.value,
        candidateViews.size
      )
      serveIndex ! ServeIndexDData.Remove(slotKey)
      removeKeyFromIndexes(state, slotKey)
    }

    // Queue pending creatives for approval (after ServeIndex update)
    queuePendingCandidates(pending, url, slotId, ttl)
    updatedState
  }

  /** Fetch Thompson-sampled CTR scores from TaxonomyRankerEntity for each category. */
  private def fetchCategoryScores(
      categories: Vector[CategoryId]
  ): Future[Map[CategoryId, Double]] =
    if (categories.isEmpty) Future.successful(Map.empty)
    else {
      given Timeout = Timeout(200.millis)

      Future.traverse(categories) { category =>
        val rankerEntityId = s"${category.value}|${siteId.value}"
        val rankerRef = sharding.entityRefFor(TaxonomyRankerEntity.TypeKey, rankerEntityId)
        rankerRef
          .ask[TaxonomyRankerEntity.Quoted](TaxonomyRankerEntity.Quote(_))
          .map(quoted => category -> quoted.weight)
          .recover { case _ => category -> 0.5 }
      }.map(_.toMap)
    }

  /** Queue pending creatives for publisher approval. */
  private def queuePendingCandidates(
      pending: Vector[Candidate],
      url: URL,
      slotId: SlotId,
      ttl: FiniteDuration
  ): Unit = {
    if (pending.isEmpty) {
      log.debug("queuePendingCandidates: no pending candidates to queue for url={} slot={}", url.value, slotId.value)
      return
    }
    log.info("📋 Queuing {} pending candidates for approval: url={} slot={}", pending.size, url.value, slotId.value)
    pending.foreach { c =>
      log.info("   📋 Pending: creative={} campaign={} preApproved={}", c.creativeId.value, c.campaignId.value,
        c.preApproved)
    }
    pending.headOption.foreach { top =>
      val now = Instant.now
      val selection = Selection(
        publisherId = siteId,
        url = url,
        slotId = slotId,
        ordered = pending,
        idx = 0,
        state = SelState.Pending,
        createdAt = now,
        expiresAt = now.plusSeconds(ttl.toSeconds)
      )

      val cids = pending.map(_.creativeId.value).toSet
      // Track true queue age alongside the selection: first_seen is set
      // once per (publisher, creative) and survives the purge/re-queue
      // cycle that resets this selection's createdAt.
      val write = store.upsertPending(selection).flatMap { _ =>
        store.recordQueued(siteId.value, cids, now)
      }
      ctx.pipeToSelf(write) { _ =>
        UpsertPendingCompleted(pending.size, url, slotId, top.creativeId.value, cids)
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // APPROVAL WORKFLOW
  // Handles publisher approval/rejection of pending creatives
  // Flow: Approve/Reject → fetch pending → materialize → update bloom filter
  // ═══════════════════════════════════════════════════════════════════════════

  private def processApprove(
      url: String,
      slot: String,
      cid: String,
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ): Unit =
    ctx.pipeToSelf(store.getPending(siteId.value, url, slot)) {
      case Success(selectionOpt) =>
        GetPendingForApproveResult(url, slot, cid, selectionOpt, replyTo)
      case Failure(ex) =>
        log.warn("Failed to get pending for approve: pub={} url={} slot={}: {}", siteId.value, url, slot, ex.getMessage)
        GetPendingForApproveResult(url, slot, cid, None, replyTo)
    }

  private def handleGetPendingForApprove(
      url: String,
      slot: String,
      cid: String,
      selectionOpt: Option[Selection],
      approvedIds: Set[String],
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ): Unit = {
    // The inbox lists the same creative under EVERY placement it is pending
    // on, but approving one placement removes it from ALL pending rows — so
    // a click on a second listed placement finds no selection. If the
    // creative is already site-approved, succeed idempotently (approve =
    // "ensure approved") instead of erroring at the reviewer.
    def idempotentIfApproved(orElse: => Unit): Unit =
      if (approvedIds.contains(cid))
        ctx.pipeToSelf(creativeRepo.get(cid)) {
          case Success(creativeOpt) => AlreadyApprovedLookedUp(cid, creativeOpt, replyTo)
          case Failure(_)           => AlreadyApprovedLookedUp(cid, None, replyTo)
        }
      else orElse

    selectionOpt match {
      case None =>
        idempotentIfApproved {
          replyTo ! StatusReply.Error("No pending selection for this slot")
        }

      case Some(selection) =>
        // Find the candidate in the ordered list (current + alternatives)
        selection.ordered.find(_.creativeId.value == cid) match {
          case None =>
            idempotentIfApproved {
              replyTo ! StatusReply.Error(
                s"Creative $cid not found in pending selection for this slot"
              )
            }
          case Some(candidate) =>
            // Async creative lookup - pipe result back to actor
            ctx.pipeToSelf(creativeRepo.get(cid)) {
              case Success(creativeOpt) =>
                CreativeLookedUpForApprove(candidate, selection, url, slot, creativeOpt, replyTo)
              case Failure(ex) =>
                log.warn("Failed to lookup creative {} for approve: {}", cid, ex.getMessage)
                CreativeLookedUpForApprove(candidate, selection, url, slot, None, replyTo)
            }
        }
    }
  }

  /**
   * Complete single approval: Append to ServeIndex and update inverted indexes.
   * Returns updated state with index entries.
   */
  private def completeApprovalWithScore(
      state: State,
      candidate: Candidate,
      selection: Selection,
      assetPrt: AssetPointer,
      creative: Creative,
      url: String,
      slot: String,
      scoreMap: Map[CategoryId, Double],
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ): State = {
    val categoryScore = scoreMap.getOrElse(candidate.category, 0.5)
    val candidateView = CandidateView(
      creativeId = candidate.creativeId,
      campaignId = candidate.campaignId,
      advertiserId = candidate.advertiserId,
      assetUrl = CDNPath(assetPrt.cdnUri),
      mime = MimeType(creative.mime),
      width = creative.width,
      height = creative.height,
      category = candidate.category,
      cpm = candidate.cpm,
      classifiedAtMs = selection.createdAt.toEpochMilli,
      categoryScore = categoryScore,
      adProductCategory = candidate.adProductCategory,
      landingDomain = candidate.landingDomain,
      landingUrl = creative.landingUrl
    )

    // Add to serve cache
    val slotKey = Keys.keyUnsafe(siteId.value, slot)
    serveIndex ! ServeIndexDData.Append(
      slotKey,
      candidateView,
      approvedTtl.toMillis
    )

    // Persist approved creative to DB (survives restart)
    store.insertApproved(siteId.value, candidate.creativeId.value, candidate.campaignId.value,
      candidate.advertiserId.value)
    // Terminal outcome — queue-age tracking is no longer meaningful
    store.deleteFirstSeen(siteId.value, Set(candidate.creativeId.value))

    // A MANUAL approval earns auto-approve trust for the campaign and the
    // landing registrable-domain (anchors persist even while the site's
    // toggle is off — dormant until the publisher opts in). Auto-approvals
    // deliberately never write anchors: trust must not chain transitively.
    val domainAnchor = RegistrableDomain.of(candidate.landingDomain)
    store.insertTrustAnchors(
      siteId.value,
      Seq(TrustAnchor.TypeCampaign -> candidate.campaignId.value) ++
      domainAnchor.map(TrustAnchor.TypeDomain -> _),
      candidate.creativeId.value
    )

    // Persist approval status on AdvertiserEntity (wait for confirmation)
    persistApprovalAndComplete(
      candidate.advertiserId,
      candidate.creativeId.value,
      url,
      slot,
      assetPrt,
      candidateView,
      replyTo
    )
    addCandidateToIndexes(state, slotKey, candidateView)
      .copy(
        persistedApprovedIds = state.persistedApprovedIds + candidate.creativeId,
        // Manual approval supersedes any earlier auto-approval badge
        autoApprovedIds = state.autoApprovedIds - candidate.creativeId,
        trustedCampaigns = state.trustedCampaigns + candidate.campaignId,
        trustedDomains = state.trustedDomains ++ domainAnchor
      )
  }

  /** Persist approval status on AdvertiserEntity and pipe completion back to actor. */
  private def persistApprovalAndComplete(
      advertiserId: AdvertiserId,
      creativeId: String,
      url: String,
      slot: String,
      assetPrt: AssetPointer,
      candidateView: CandidateView,
      replyTo: ActorRef[StatusReply[AssetPointer]]
  ): Unit = {
    val advertiserRef = sharding.entityRefFor(
      promovolve.advertiser.AdvertiserEntity.TypeKey,
      advertiserId.value
    )
    given Timeout = Timeout(5.seconds)
    val approvalFuture =
      advertiserRef.ask[promovolve.advertiser.AdvertiserEntity.CreativeApprovalUpdated](ref =>
        promovolve.advertiser.AdvertiserEntity.UpdateCreativeApproval(
          creativeId = CreativeId(creativeId),
          siteId = siteId,
          status = ApprovalStatus.Approved,
          replyTo = ref
        )
      )
    // Pipe result back to actor - completion handled by ApprovalStatusPersisted message
    ctx.pipeToSelf(approvalFuture) {
      case Success(_)  => ApprovalStatusPersisted(url, slot, creativeId, assetPrt, candidateView, replyTo)
      case Failure(ex) =>
        log.warn("Approval status persist failed for pub={} url={} slot={}: {}", siteId.value, url, slot, ex.getMessage)
        ApprovalStatusPersisted(url, slot, creativeId, assetPrt, candidateView, replyTo) // Still complete on failure
    }
  }

  /**
   * Approve all pending creatives for a slot (for testing).
   *
   * Waits for all approval status updates to complete before returning.
   */
  private def processApproveAll(
      url: String,
      slot: String,
      replyTo: ActorRef[ApproveAllResult]
  ): Unit =
    ctx.pipeToSelf(store.getPending(siteId.value, url, slot)) {
      case Success(selectionOpt) => GetPendingForApproveAllResult(url, slot, selectionOpt, replyTo)
      case Failure(ex)           =>
        log.warn("Failed to get pending for approveAll: pub={} url={} slot={}: {}", siteId.value, url, slot,
          ex.getMessage)
        GetPendingForApproveAllResult(url, slot, None, replyTo)
    }

  private def handleGetPendingForApproveAll(
      url: String,
      slot: String,
      selectionOpt: Option[Selection],
      replyTo: ActorRef[ApproveAllResult]
  ): Unit =
    selectionOpt match {
      case None =>
        replyTo ! ApproveAllResult(0, 0)

      case Some(selection) =>
        // Async: look up all creatives first, then pipe back to actor for sync processing
        val lookupFuture = Future.traverse(selection.ordered) { candidate =>
          creativeRepo.get(candidate.creativeId.value).map(candidate -> _)
        }
        ctx.pipeToSelf(lookupFuture) {
          case Success(lookedUp) =>
            CreativesLookedUpForApproveAll(url, slot, selection, lookedUp, replyTo)
          case Failure(ex) =>
            log.warn("Failed to lookup creatives for approveAll: pub={} url={} slot={}: {}",
              siteId.value, url, slot, ex.getMessage)
            CreativesLookedUpForApproveAll(url, slot, selection, Vector.empty, replyTo)
        }
    }

  /**
   * Handle completion of async creative lookups for batch approval.
   * Processes results synchronously within the actor, returns updated state.
   */
  private def handleCreativesLookedUpForApproveAll(
      state: State,
      url: String,
      slot: String,
      selection: Selection,
      lookedUp: Vector[(Candidate, Option[Creative])],
      replyTo: ActorRef[ApproveAllResult]
  ): State = {
    given Timeout = Timeout(5.seconds)

    val slotKey = Keys.keyUnsafe(siteId.value, slot)

    // Process all candidates and collect Bloom filter update futures + index updates
    val (updatedState, results) = lookedUp.foldLeft((state,
      Vector.empty[(Boolean, Option[Future[promovolve.advertiser.AdvertiserEntity.CreativeApprovalUpdated]])])) {
      case ((accState, accResults), (candidate, creativeOpt)) =>
        val cid = candidate.creativeId.value
        creativeOpt match {
          case Some(meta) =>
            val candidateView = CandidateView(
              creativeId = candidate.creativeId,
              campaignId = candidate.campaignId,
              advertiserId = candidate.advertiserId,
              assetUrl = CDNPath(meta.s3Key), // Use s3Key directly
              mime = MimeType(meta.mime),
              width = meta.width,
              height = meta.height,
              category = candidate.category,
              cpm = candidate.cpm,
              classifiedAtMs = selection.createdAt.toEpochMilli,
              categoryScore = 0.5, // Default score for batch approval
              adProductCategory = candidate.adProductCategory,
              landingDomain = candidate.landingDomain,
              landingUrl = meta.landingUrl
            )

            serveIndex ! ServeIndexDData.Append(
              slotKey,
              candidateView,
              approvedTtl.toMillis
            )

            // Persist approved creative to DB (survives restart)
            store.insertApproved(siteId.value, cid, candidate.campaignId.value, candidate.advertiserId.value)
            // Terminal outcome — queue-age tracking is no longer meaningful
            store.deleteFirstSeen(siteId.value, Set(cid))

            // Manual approval earns auto-approve trust (see completeApprovalWithScore)
            val domainAnchor = RegistrableDomain.of(candidate.landingDomain)
            store.insertTrustAnchors(
              siteId.value,
              Seq(TrustAnchor.TypeCampaign -> candidate.campaignId.value) ++
              domainAnchor.map(TrustAnchor.TypeDomain -> _),
              cid
            )

            // Persist approval status on AdvertiserEntity (wait for confirmation)
            val advertiserRef = sharding.entityRefFor(
              promovolve.advertiser.AdvertiserEntity.TypeKey,
              candidate.advertiserId.value
            )
            val approvalFuture = advertiserRef
              .ask[promovolve.advertiser.AdvertiserEntity.CreativeApprovalUpdated](ref =>
                promovolve.advertiser.AdvertiserEntity.UpdateCreativeApproval(
                  creativeId = CreativeId(cid),
                  siteId = siteId,
                  status = ApprovalStatus.Approved,
                  replyTo = ref
                )
              )

            log.info(
              "ApproveAll: cached creative={} to ServeIndex: pub={} url={} slot={}",
              cid,
              siteId.value,
              url,
              slot
            )
            val withIndex = addCandidateToIndexes(accState, slotKey, candidateView)
            val withPersisted =
              withIndex.copy(
                persistedApprovedIds = withIndex.persistedApprovedIds + candidate.creativeId,
                autoApprovedIds = withIndex.autoApprovedIds - candidate.creativeId,
                trustedCampaigns = withIndex.trustedCampaigns + candidate.campaignId,
                trustedDomains = withIndex.trustedDomains ++ domainAnchor
              )
            (withPersisted, accResults :+ (true, Some(approvalFuture)))

          case None =>
            log.warn("ApproveAll: metadata not found for creative={}", cid)
            (accState, accResults :+ (false, None))
        }
    }

    val approved = results.count(_._1)
    val failed = results.count(!_._1)
    val approvalFutures = results.flatMap(_._2)

    // Pipe completion back to actor for safe handling.
    // Individual asks have 5s timeout; wrap with a 10s safety-net timeout.
    val batchFuture = Future.sequence(approvalFutures)
    val scheduler = system.classicSystem.scheduler
    val safeFuture = org.apache.pekko.pattern.after(10.seconds, scheduler)(
      Future.failed(new java.util.concurrent.TimeoutException("Batch approval timed out"))
    )
    ctx.pipeToSelf(Future.firstCompletedOf(Seq(batchFuture, safeFuture))) {
      case Success(_)  => BatchApprovalStatusPersisted(url, slot, approved, failed, replyTo)
      case Failure(ex) =>
        log.warn("Batch approval persist failed for pub={} url={} slot={}: {}", siteId.value, url, slot, ex.getMessage)
        BatchApprovalStatusPersisted(url, slot, approved, failed, replyTo)
    }
    updatedState
  }

  private def processReject(
      url: String,
      slot: String,
      cid: String,
      reason: Option[String],
      replyTo: ActorRef[StatusReply[Done.type]]
  ): Unit =
    ctx.pipeToSelf(store.getPending(siteId.value, url, slot)) {
      case Success(selectionOpt) =>
        GetPendingForRejectResult(url, slot, cid, reason, selectionOpt, replyTo)
      case Failure(ex) =>
        log.warn("Failed to get pending for reject: pub={} url={} slot={}: {}", siteId.value, url, slot, ex.getMessage)
        GetPendingForRejectResult(url, slot, cid, reason, None, replyTo)
    }

  private def handleGetPendingForReject(
      url: String,
      slot: String,
      cid: String,
      reason: Option[String],
      selectionOpt: Option[Selection],
      replyTo: ActorRef[StatusReply[Done.type]]
  ): Unit =
    selectionOpt match {
      case None =>
        replyTo ! StatusReply.Error("No pending selection for this slot")

      case Some(selection) =>
        val candidate = selection.current
        if (candidate.creativeId.value != cid) {
          replyTo ! StatusReply.Error(
            s"Creative mismatch: expected ${candidate.creativeId.value}, got $cid"
          )
        } else {
          // Update AdvertiserEntity creative approval Bloom filter
          val advertiserRef = sharding.entityRefFor(
            promovolve.advertiser.AdvertiserEntity.TypeKey,
            candidate.advertiserId.value
          )
          advertiserRef ! promovolve.advertiser.AdvertiserEntity.UpdateCreativeApproval(
            creativeId = CreativeId(cid),
            siteId = siteId,
            status = ApprovalStatus.Rejected,
            replyTo = system.ignoreRef
          )

          // Immediately remove rejected creative from this slot in ServeIndex
          serveIndex ! ServeIndexDData.RemoveCreativeFromKey(Keys.keyUnsafe(siteId.value, slot), CreativeId(cid))

          // Terminal outcome — queue-age tracking is no longer meaningful
          store.deleteFirstSeen(siteId.value, Set(cid))

          // A reject is direct evidence the auto-approve inference failed
          // for this campaign/domain — break the trust anchors so siblings
          // return to the manual queue.
          ctx.self ! TrustBroken(candidate.campaignId, RegistrableDomain.of(candidate.landingDomain))

          log.info(
            "Rejected creative: campaign={} creative={} publisher={} reason={} - removed from ServeIndex",
            candidate.campaignId.value,
            cid,
            siteId.value,
            reason.getOrElse("none")
          )

          // Promote next candidate or trigger re-auction
          ctx.pipeToSelf(store.rejectAndPromote(siteId.value, url, slot)) {
            case Success(promoted) => RejectAndPromoteCompleted(url, slot, promoted, replyTo)
            case Failure(ex)       =>
              log.warn("Failed to reject and promote: pub={} url={} slot={}: {}", siteId.value, url, slot,
                ex.getMessage)
              RejectAndPromoteCompleted(url, slot, None, replyTo)
          }
        }
    }

  private def handleRejectAndPromoteCompleted(
      url: String,
      slot: String,
      promoted: Option[Selection],
      replyTo: ActorRef[StatusReply[Done.type]]
  ): Unit =
    promoted match {
      case Some(_) =>
        replyTo ! StatusReply.Success(Done)
      case None =>
        // Queue exhausted - remove from serve cache and trigger re-auction
        log.warn(
          "⚠️ CLEARING ServeIndex (queue exhausted): pub={} url={} slot={}",
          siteId.value,
          url,
          slot
        )
        serveIndex ! ServeIndexDData.Remove(Keys.keyUnsafe(siteId.value, slot))

        // Trigger re-auction by sending Reevaluate to AuctioneerEntity
        // This ensures we get fresh candidates instead of waiting for the next crawl cycle
        val auctioneerRef = sharding.entityRefFor(
          promovolve.auction.AuctioneerEntity.TypeKey,
          siteId.value
        )
        auctioneerRef ! promovolve.auction.AuctioneerEntity.Reevaluate(URL(url))
        log.info(
          "Queue exhausted after rejection, triggered re-auction: pub={} url={} slot={}",
          siteId.value,
          url,
          slot
        )

        replyTo ! StatusReply.Success(Done)
    }

  private def processListPending(replyTo: ActorRef[PendingList]): Unit = {
    // First-seen ages are display metadata — a lookup failure must not
    // take down the whole approval queue, so it degrades to empty.
    val firstSeenF = store.getFirstSeen(siteId.value).recover { case ex =>
      log.warn("Failed to load first-seen ages for site {}: {}", siteId.value, ex.getMessage)
      Map.empty[String, promovolve.publisher.FirstSeen]
    }
    val resultFuture = store.pendingQueue(siteId.value).zip(firstSeenF).flatMap { case (queue, firstSeen) =>
      log.info("ListPending: site={} found {} pending items in queue", siteId.value, queue.size)
      // Async lookup all creatives, then build PendingItems
      Future.traverse(queue) { case (u, s, c) =>
        creativeRepo.get(c.creativeId.value).map { creativeOpt =>
          log.info("  Pending item: url={} slot={} creative={} inCreativeRepo={}",
            u, s, c.creativeId.value, creativeOpt.isDefined)
          creativeOpt.filter(_.canParticipate).map { cr =>
            val age = firstSeen.get(c.creativeId.value)
            PendingItem(
              url = u,
              slotId = s,
              creativeId = c.creativeId.value,
              cpm = c.cpm.toDouble,
              category = c.category.value,
              s3Key = Some(cr.s3Key),
              matchConfidence = cr.matchConfidence,
              verificationReason = cr.verificationReason,
              landingDomain = Some(c.landingDomain).filter(_.nonEmpty),
              adProductCategory = c.adProductCategory.map(_.value),
              campaignId = Some(c.campaignId.value),
              advertiserId = Some(c.advertiserId.value),
              firstSeenEpochMs = age.map(_.firstSeen.toEpochMilli),
              requeueCount = age.map(_.requeueCount)
            )
          }
        }
      }.map(_.flatten)
    }
    ctx.pipeToSelf(resultFuture) {
      case Success(items) => ListPendingResult(items, replyTo)
      case Failure(ex)    =>
        log.warn("Failed to get pending queue for site {}: {}", siteId.value, ex.getMessage)
        ListPendingResult(Vector.empty, replyTo)
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FLAGGING / QUARANTINE
  // Handles flagging (quarantining) creatives from the pending queue
  // ═══════════════════════════════════════════════════════════════════════════

  private def processFlagCreative(
      url: String,
      slot: String,
      creativeId: String,
      reason: String,
      replyTo: ActorRef[StatusReply[FlagResult]]
  ): Unit =
    ctx.pipeToSelf(store.flagCreative(siteId.value, url, slot, creativeId, reason)) {
      case Success(flagged) => FlagCreativeResult(flagged, replyTo)
      case Failure(ex)      =>
        log.warn("Failed to flag creative {}: {}", creativeId, ex.getMessage)
        FlagCreativeResult(None, replyTo)
    }

  private def processUnflagCreative(
      creativeId: String,
      replyTo: ActorRef[StatusReply[UnflagResult]]
  ): Unit =
    ctx.pipeToSelf(store.unflagCreative(siteId.value, creativeId)) {
      case Success(unflagged) => UnflagCreativeResult(unflagged, replyTo)
      case Failure(ex)        =>
        log.warn("Failed to unflag creative {}: {}", creativeId, ex.getMessage)
        UnflagCreativeResult(None, replyTo)
    }

  private def processListFlagged(replyTo: ActorRef[FlaggedList]): Unit = {
    val resultFuture = store.getFlagged(siteId.value).flatMap { flagged =>
      Future.traverse(flagged) { f =>
        creativeRepo.get(f.creativeId).map { creative =>
          FlaggedItem(
            url = f.url,
            slotId = f.slotId,
            creativeId = f.creativeId,
            campaignId = f.campaignId,
            cpm = f.cpm.toDouble,
            category = f.category,
            reason = f.reason,
            flaggedAt = f.flaggedAt.toString,
            s3Key = creative.map(_.s3Key)
          )
        }
      }
    }
    ctx.pipeToSelf(resultFuture) {
      case Success(items) => ListFlaggedResult(items, replyTo)
      case Failure(ex)    =>
        log.warn("Failed to get flagged creatives: {}", ex.getMessage)
        ListFlaggedResult(Vector.empty, replyTo)
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PER-REQUEST LIFECYCLE
  // Bookkeeping that runs once per arriving Select / BatchSelect, after host
  // verification. Owns: trafficObserver tick, requestCount/lastRequestTimeMs,
  // post-rollover-grace check, day-rollover detection (real & simulated),
  // pacingStrategy/trafficObserver/trafficShapeTracker reset on rollover,
  // ResetDayStart fan-out to participating campaigns/advertisers in
  // simulated mode, traffic-shape recording, warmup short-circuit.
  // Returns (state', action) — caller dispatches selection logic on Proceed.
  // ═══════════════════════════════════════════════════════════════════════════

  private def recordRequestArrival(state: State): (State, ArrivalAction) = {
    import state.*
    val nowMs = System.currentTimeMillis()
    val newRate = trafficObserver.recordRequest(nowMs)
    val newRequestCount = requestCount + 1

    // Post-rollover grace: skip serving until SpendInfo cache is populated.
    if (rolloverGraceUntilMs > 0 && nowMs < rolloverGraceUntilMs) {
      log.debug("Grace period active: skipping serve until cache is populated")
      val newState = state.copy(
        serveStats = serveStats.recordPacingSkipped,
        smoothedReqRate = newRate,
        requestCount = newRequestCount,
        lastRequestTimeMs = nowMs
      )
      return (newState, ArrivalAction.SkipRolloverGrace)
    }

    val clearedGracePeriod = if (rolloverGraceUntilMs > 0 && nowMs >= rolloverGraceUntilMs) {
      log.info("Grace period expired (timeout)")
      0L
    } else rolloverGraceUntilMs

    // Day rollover detection — unified for real & simulated days.
    val (updatedDayStart, needsReset) = lastDayStart match {
      case Some(dayStart) =>
        val shouldRollover = if (dayDurationSeconds == 86400) {
          val lastDay = dayStart.atZone(java.time.ZoneOffset.UTC).toLocalDate
          val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
          today.isAfter(lastDay)
        } else {
          val elapsedSeconds = (nowMs - dayStart.toEpochMilli) / 1000.0
          elapsedSeconds >= dayDurationSeconds
        }

        if (shouldRollover) {
          val rolloverType = if (dayDurationSeconds == 86400) "Calendar" else "Simulated"
          log.info("{} day rollover detected, resetting pacing for new day", rolloverType)

          // Cross-day learning: was the budget exhausted prematurely?
          val validInfos = participatingCampaigns.toSeq.flatMap { campId =>
            spendInfoCache.get(campId).map(info => (campId, info))
          }
          if (validInfos.nonEmpty) {
            val totalBudget = validInfos.map(_._2.dailyBudget.value).sum
            val totalSpend = validInfos.map(_._2.todaySpend.value).sum +
              validInfos.flatMap(vi => pendingSpendByCampaign.get(vi._1).map(_._1)).sum
            val budgetExhausted = totalSpend >= totalBudget
            val elapsedSeconds = (nowMs - dayStart.toEpochMilli) / 1000.0
            val remainingFraction = math.max(0.0, 1.0 - elapsedSeconds / dayDurationSeconds)

            if (budgetExhausted && remainingFraction > 0.01) {
              log.info("Cross-day learning: budget exhausted with {:.1f}% of day remaining",
                remainingFraction * 100)
            }
            pacingStrategy.prepareForRollover(budgetExhausted, remainingFraction)
          }

          pacingStrategy.reset()
          trafficObserver.reset()
          trafficShapeTracker.rolloverDay(0.2) // 20% today, 80% historical

          val nextDayOfWeek = if (dayDurationSeconds == 86400) {
            java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek
          } else {
            simulatedDayOfWeek.plus(1)
          }
          trafficShapeTracker.setDayType(nextDayOfWeek)
          log.info("Traffic shape now using {} pattern",
            if (nextDayOfWeek.getValue >= 6) "weekend" else "weekday")

          // Persist immediately: rolloverDay just blended today into the
          // learned shape — waiting for the hourly SnapshotStats timer
          // loses the whole day's contribution to any restart in between.
          ctx.pipeToSelf(trafficShapeSnapshotRepo.upsert(siteId.value, trafficShapeTracker.toSnapshot)) {
            _.fold(ex => TrafficShapeSnapshotSaveResult(Some(ex)), _ => TrafficShapeSnapshotSaveResult(None))
          }

          if (dayDurationSeconds != 86400 && participatingCampaigns.nonEmpty) {
            // Simulated days only: campaigns rely on this signal to roll their
            // budget window. Real days: CampaignEntity rolls itself off
            // calendar dates.
            log.info("Day rollover: resetting {} participating campaigns for site {}",
              participatingCampaigns.size, siteId.value)
            participatingCampaigns.foreach { campaignId =>
              spendInfoCache.get(campaignId).foreach { cached =>
                val campaignEntityId = s"${cached.advertiserId.value}|${campaignId.value}"
                val campaignRef = sharding.entityRefFor(CampaignEntity.TypeKey, campaignEntityId)
                campaignRef ! CampaignEntity.ResetDayStart(system.ignoreRef, dayDurationSeconds)
              }
            }
            val participatingAdvertiserIds = participatingCampaigns.flatMap { campaignId =>
              spendInfoCache.get(campaignId).map(_.advertiserId)
            }
            participatingAdvertiserIds.foreach { advertiserId =>
              val advertiserRef = sharding.entityRefFor(AdvertiserEntity.TypeKey, advertiserId.value)
              advertiserRef ! AdvertiserEntity.ResetDayStart(system.ignoreRef)
            }
          }

          val newDayStart = if (dayDurationSeconds == 86400) {
            java.time.LocalDate.now(java.time.ZoneOffset.UTC)
              .atStartOfDay(java.time.ZoneOffset.UTC)
              .toInstant
          } else {
            Instant.now()
          }
          (Some(newDayStart), true)
        } else {
          (Some(dayStart), false)
        }
      case None =>
        // First request — initialize lastDayStart from cached spend info if any.
        val fromCache = spendInfoCache.values.headOption.map(_.dayStart)
        (fromCache, false)
    }

    // Traffic shape: record request bucket so the per-hour histogram learns.
    updatedDayStart.foreach { dayStart =>
      val scaledElapsed = if (dayDurationSeconds == 86400) {
        val time = Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneOffset.UTC).toLocalTime
        time.getHour * 3600.0 + time.getMinute * 60.0 + time.getSecond
      } else {
        val elapsedSec = (nowMs - dayStart.toEpochMilli) / 1000.0
        elapsedSec * (86400.0 / dayDurationSeconds)
      }
      trafficShapeTracker.recordRequest(scaledElapsed)
    }

    val updatedDayOfWeek = if (needsReset && dayDurationSeconds != 86400) {
      simulatedDayOfWeek.plus(1)
    } else simulatedDayOfWeek

    // Warmup mode short-circuit — record traffic but don't serve.
    if (warmupMode) {
      log.debug("Warmup mode active: recording traffic but not serving ads")
      val newDayStart = if (needsReset) Some(Instant.ofEpochMilli(nowMs)) else updatedDayStart
      val updatedCampaignSet = if (needsReset) Set.empty[CampaignId] else lastCampaignSet
      val newState = state.copy(
        serveStats = serveStats.recordWarmup,
        lastDayStart = newDayStart,
        smoothedReqRate = newRate,
        rolloverGraceUntilMs = clearedGracePeriod,
        requestCount = newRequestCount,
        lastRequestTimeMs = nowMs,
        lastCampaignSet = updatedCampaignSet,
        simulatedDayOfWeek = updatedDayOfWeek
      )
      return (newState, ArrivalAction.SkipWarmup)
    }

    // Normal proceed: apply rollover-related cache resets, return Proceed.
    val newDayStart = Instant.ofEpochMilli(nowMs)
    val (clearedPending, resetServeStats, resetCache) = if (needsReset) {
      log.info("Day rollover: resetting cache values (todaySpend=0, dayStart={})", newDayStart)
      val resetCacheEntries = spendInfoCache.map { case (campaignId, cached) =>
        campaignId -> cached.copy(
          todaySpend = Spend.zero,
          dayStart = newDayStart,
          timestamp = newDayStart
        )
      }
      (Map.empty[CampaignId, (Double, Instant)], ServeStats(siteId.value), resetCacheEntries)
    } else {
      (pendingSpendByCampaign, serveStats, spendInfoCache)
    }

    val updatedCampaignSet = if (needsReset) Set.empty[CampaignId] else lastCampaignSet

    val newState = state.copy(
      serveStats = resetServeStats,
      lastDayStart = updatedDayStart,
      smoothedReqRate = newRate,
      pendingSpendByCampaign = clearedPending,
      spendInfoCache = resetCache,
      rolloverGraceUntilMs = 0L,
      requestCount = newRequestCount,
      lastRequestTimeMs = nowMs,
      lastCampaignSet = updatedCampaignSet,
      simulatedDayOfWeek = updatedDayOfWeek
    )
    (newState, ArrivalAction.Proceed)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UTILITIES
  // Helper methods: config conversion
  // ═══════════════════════════════════════════════════════════════════════════

  /** Convert SiteEntity.PacingConfig to PacingStrategy with logging */
  private def pacingStrategyFromConfig(config: SiteEntity.PacingConfig): PacingStrategy =
    PacingLogic.strategyFromConfig(config)

  // ═══════════════════════════════════════════════════════════════════════════
  // BATCH PACING HELPERS
  // Volume throttle, per-winner budget reservation with retry.
  // Mirrors the per-slot flow (checkPacingGate → reserveBudgetDirect)
  // condensed into Future chains so one BatchSelect request performs all
  // reservations + retries atomically from the caller's view.
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * One reservation: gate on the advertiser budget FIRST, then TryReserve
   * on the campaign. Returns Future[true] only when both pass; false on
   * advertiser-over-budget, campaign insufficient budget, or actor timeout.
   *
   * The advertiser check MUST precede the campaign reserve. Running them in
   * parallel (the old design) left a phantom reservation on the campaign
   * whenever the advertiser was over budget: no ad served, but `TryReserve`
   * had already buffered the spend, and there is no release path — so every
   * serve attempt after an advertiser hit its cap kept inflating pacing
   * `spendToday` far past delivered spend, benching the advertiser early.
   * Gating first means we only ever reserve when we will actually serve, so
   * reserved spend tracks served spend. (The advertiser cap stays soft at
   * the exact boundary — a few concurrent serves can slip over by a bounded
   * amount — but those are real served impressions, not phantom.)
   */
  private def batchReserveOne(candidate: CandidateView, clearingPrice: CPM, requestId: String): Future[Boolean] = {
    given Timeout = Timeout(100.millis)
    val baseCpm = clearingPrice.toDouble / 1000.0
    val spendAmount = Spend(baseCpm)
    val campaignRef = sharding.entityRefFor(
      CampaignEntity.TypeKey,
      s"${candidate.advertiserId.value}|${candidate.campaignId.value}"
    )
    val advertiserRef = sharding.entityRefFor(
      AdvertiserEntity.TypeKey, candidate.advertiserId.value
    )
    advertiserRef
      .ask[AdvertiserEntity.AdvertiserBudgetStatus](ref => AdvertiserEntity.GetBudgetStatus(ref))
      .flatMap { advStatus =>
        if (!advStatus.withinBudget) Future.successful(false)
        else
          campaignRef
            .ask[CampaignEntity.ReserveResult](ref => CampaignEntity.TryReserve(requestId, spendAmount, ref))
            .map {
              case CampaignEntity.Reserved(_) => true
              case _                          => false
            }
      }
      .recover { case _ => false }
  }

  /**
   * Pick the best-scoring candidate for a slot from an available pool.
   *
   * Respects: slot dimensions (hard), site floor CPM (hard), and the
   * per-page campaign cap as a soft 0.3× score penalty. Used by both
   * the initial batch assignment and the retry loop below.
   */
  /**
   * Instance-bound delegate: calls the pure companion-object function
   * with the reservation primitive and site-level state captured from
   * the outer actor. The companion function is the one exercised by
   * unit tests (see AdServerBatchRetrySpec).
   */
  private def batchReserveWithRetry(
      slots: Vector[Protocol.BatchSlotSpec],
      pool: Vector[CandidateView],
      pageBlocked: Set[String],
      alpha: Double,
      stats: Map[CreativeId, Protocol.CreativeStats],
      excludedCreatives: Set[CreativeId],
      excludedCampaigns: Set[CampaignId],
      isApproved: CreativeId => Boolean,
      pinLookupPool: Vector[CandidateView]
  ): Future[(Vector[Protocol.BatchSlotOutcome], Map[CampaignId, Double])] =
    AdServer.batchReserveWithRetry(
      slots = slots,
      pool = pool,
      pageBlocked = pageBlocked,
      alpha = alpha,
      stats = stats,
      siteFloor = siteFloorCpm,
      reserve = batchReserveOne,
      rng = rng,
      excludedCreatives = excludedCreatives,
      excludedCampaigns = excludedCampaigns,
      isApproved = isApproved,
      pinLookupPool = pinLookupPool,
      categoryFloors = siteCategoryFloors,
      adminSlotFloors = adminSlotFloors
    )(using ctx.executionContext)

  /**
   * Aggregate volume throttle for batch requests. Mirrors the per-
   * slot `checkPacingGate`: consults `spendInfoCache`, fetches from
   * `CampaignEntity` on cache miss, builds a `PacingContext`, calls
   * `pacingStrategy.throttleProbability`, and emits either
   * `BatchSpendInfoFetched` (cache miss) or `BatchPacingGateResult`
   * directly. One Bernoulli gate per batch request — if throttled,
   * every slot returns winner=None.
   */
  private def checkBatchPacingGate(
      view: ServeView,
      url: URL,
      slots: Vector[Protocol.BatchSlotSpec],
      replyTo: ActorRef[Protocol.BatchSelectResult],
      selCtx: SelectionContext,
      pageWinners: Map[String, AdServer.PageWinners],
      excludedCreatives: Set[CreativeId],
      excludedCampaigns: Set[CampaignId]
  ): Unit = {
    import selCtx.*
    val now = Instant.now()
    val uniqueCampaigns = view.candidates.map(_.campaignId).distinct
    val currentCampaignSet = uniqueCampaigns.toSet

    // Mix-change detection — reset the PI so it doesn't chase phantom
    // traffic caused by a different campaign set.
    if (lastCampaignSet.nonEmpty && currentCampaignSet != lastCampaignSet) {
      log.info("BATCH PACING: campaign mix changed, resetting PI controller")
      pacingStrategy.reset()
    }

    val validInfos: Seq[(CampaignId, CachedSpendInfo)] = uniqueCampaigns.flatMap { campId =>
      spendInfoCache.get(campId).map(info => (campId, info))
    }

    if (validInfos.isEmpty) {
      // Cache-miss path — fetch from CampaignEntity in parallel.
      // Mirrors the per-slot fallback in `checkPacingGate`.
      given Timeout = Timeout(500.millis)
      val campaignRefs = view.candidates
        .groupBy(c => (c.advertiserId, c.campaignId))
        .keys.toVector
      val futures: Vector[Future[(CampaignId, Option[CachedSpendInfo])]] =
        campaignRefs.map { case (advId, campId) =>
          val entityId = s"${advId.value}|${campId.value}"
          val campaignRef = sharding.entityRefFor(CampaignEntity.TypeKey, entityId)
          val cand = view.candidates.find(c => c.advertiserId == advId && c.campaignId == campId).get
          campaignRef.ask[CampaignEntity.SpendInfo](CampaignEntity.GetSpendInfo(_))
            .map { info =>
              (campId,
                Some(CachedSpendInfo(
                  advertiserId = cand.advertiserId,
                  dailyBudget = info.dailyBudget,
                  todaySpend = info.todaySpend,
                  dayStart = info.dayStart,
                  timestamp = now
                )))
            }
            .recover { case _ => (campId, None) }
        }
      ctx.pipeToSelf(Future.sequence(futures)) {
        case Success(results) =>
          val fetchedInfo = results.collect { case (c, Some(i)) => c -> i }.toMap
          Protocol.BatchSpendInfoFetched(fetchedInfo, view, url, slots, replyTo, selCtx, excludedCreatives,
            excludedCampaigns)
        case Failure(ex) =>
          log.warn("BATCH PACING: spend-info fetch failed: {}", ex.getMessage)
          Protocol.BatchSpendInfoFetched(Map.empty, view, url, slots, replyTo, selCtx, excludedCreatives,
            excludedCampaigns)
      }
    } else {
      // Cache hit — compute aggregate + throttle inline, emit pacing
      // gate result immediately (no async actor round-trip).
      val cpmByCampaign = PacingLogic.computeCpmByCampaign(view.candidates)
      val (totalDailyBudget, totalTodaySpend, avgCpm) = PacingLogic.computeAggregateBudget(
        validInfos, cpmByCampaign, pendingSpendByCampaign
      )
      val cachedDayStart = validInfos.map(_._2.dayStart).minBy(_.toEpochMilli)
      // Same anchor rule as the batch path: real days pace against the
      // campaign's UTC day start, never the boot-reset entity anchor.
      val effectiveDayStart =
        if (dayDurationSeconds == 86400) cachedDayStart
        else lastDayStart.filter(_.isAfter(cachedDayStart)).getOrElse(cachedDayStart)
      val pacingCtx = PacingContext(
        dailyBudget = totalDailyBudget,
        todaySpend = totalTodaySpend,
        dayStart = effectiveDayStart,
        now = now,
        requestArrivalRate = requestArrivalRate,
        competingCampaigns = 1,
        avgCpm = avgCpm,
        dayDurationSeconds = dayDurationSeconds,
        trafficShape = Some(trafficShapeTracker),
        requestCount = requestCount,
        msSinceLastRequest = msSinceLastRequest
      )
      val throttle = pacingStrategy.throttleProbability(pacingCtx)
      val requestPasses = rng.nextDouble() >= throttle
      val eligibleCampIds = validInfos.map(_._1).toSet
      val eligibleCandidates = if (requestPasses)
        view.candidates.filter(c => eligibleCampIds.contains(c.campaignId))
      else Vector.empty
      val pageKey = AdServer.pageWinnersKeyFor(siteId, url)
      val pageBlocked: Set[String] = pageWinners.get(pageKey)
        .map(_.campaigns).getOrElse(Set.empty)
      log.info("BATCH PACING: throttle={}% passes={} candidates={} spendRatio={}",
        f"${throttle * 100}%.0f", requestPasses,
        eligibleCandidates.size: java.lang.Integer, f"${pacingCtx.spendRatio}%.2f")
      ctx.self ! Protocol.BatchPacingGateResult(
        shouldServe = requestPasses,
        view = view,
        url = url,
        slots = slots,
        eligibleCandidates = eligibleCandidates,
        pageBlocked = pageBlocked,
        replyTo = replyTo,
        currentCampaignSet = currentCampaignSet,
        excludedCreatives = excludedCreatives,
        excludedCampaigns = excludedCampaigns
      )
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HOUSEKEEPING
  // Periodic tasks: purge expired, snapshot stats
  // Triggered by timers (PurgeExpired, SnapshotStats)
  // ═══════════════════════════════════════════════════════════════════════════

  private def processPurgeExpired(): Unit =
    ctx.pipeToSelf(store.purgeExpired(Instant.now())) {
      _.fold(
        ex => {
          log.warn("Failed to purge expired entries for pub={}: {}", siteId.value, ex.getMessage); PurgeExpiredResult(0)
        },
        PurgeExpiredResult(_)
      )
    }

  private def processSnapshotStats(
      creativeStats: Map[CreativeId, CreativeStats],
      trafficShapeTracker: TrafficShapeTracker
  ): Unit = {
    // Save creative stats using SnapshotLogic helper
    if (SnapshotLogic.hasStatsToSnapshot(creativeStats)) {
      val snapshots = SnapshotLogic.toSnapshots(siteId.value, creativeStats)
      ctx.pipeToSelf(statsSnapshotRepo.save(snapshots)) {
        _.fold(ex => SnapshotSaveResult(snapshots.size, Some(ex)), _ => SnapshotSaveResult(snapshots.size, None))
      }
    }

    // Save traffic shape snapshot (preserves learned patterns across restarts)
    ctx.pipeToSelf(trafficShapeSnapshotRepo.upsert(siteId.value, trafficShapeTracker.toSnapshot)) {
      _.fold(ex => TrafficShapeSnapshotSaveResult(Some(ex)), _ => TrafficShapeSnapshotSaveResult(None))
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // STATE
  // Immutable state passed through behavior transitions
  // Contains: caches, stats, pacing state, tracking data
  // ═══════════════════════════════════════════════════════════════════════════

  /** Bundled state for behavior() to avoid parameter explosion */
  case class State(
      cachedDomainBlocklist: Option[PublisherEntity.CachedDomainBlocklist] = None,
      cachedAdProductBlocklist: Option[SiteEntity.CachedAdProductBlocklist] = None,
      /**
       * Per-advertiser site-domain blocklist snapshot from DData.
       * Filter: drop a candidate if its advertiserId maps to a set
       * containing this site's verified domain.
       */
      cachedAdvertiserBlocklists: Map[AdvertiserId, Set[String]] = Map.empty,
      creativeStats: Map[CreativeId, CreativeStats] = Map.empty,
      serveStats: ServeStats = ServeStats(siteId.value),
      // Anchored to UTC midnight, NOT boot time: campaign budgets are
      // calendar-day scoped, and rollover re-anchors to midnight anyway
      // (see newDayStart) — a boot-time anchor skewed expectedSpend for
      // the whole boot day (the 2026-07-06 incident family; fixed for
      // the boot path 2026-07-13). Simulated days re-anchor explicitly
      // on PacingConfigUpdated.
      lastDayStart: Option[Instant] = Some(
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant),
      pacingStrategy: PacingStrategy = initialPacingStrategy,
      smoothedReqRate: Double = 0.0,
      pendingSpendByCampaign: Map[CampaignId, (Double, Instant)] = Map.empty,
      dayDurationSeconds: Int = 86400,
      spendInfoCache: Map[CampaignId, CachedSpendInfo] = Map.empty,
      spendInfoLastUpdated: Map[CampaignId, Instant] = Map.empty,
      // interpolateVolumes: the pacing rate multiplier ramps across bucket
      // boundaries instead of stepping at each hour.
      trafficShapeTracker: TrafficShapeTracker = new TrafficShapeTracker(interpolateVolumes = true),
      rolloverGraceUntilMs: Long = 0L,
      warmupMode: Boolean = false,
      requestCount: Long = 0L,
      lastRequestTimeMs: Long = 0L,
      lastCampaignSet: Set[CampaignId] = Set.empty,
      simulatedDayOfWeek: java.time.DayOfWeek = java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek,
      participatingCampaigns: Set[CampaignId] = Set.empty,
      // Inverted indexes for targeted ServeIndex removal (O(1) per key)
      keysByCampaign: Map[CampaignId, Set[String]] = Map.empty,
      keysByCreative: Map[CreativeId, Set[String]] = Map.empty,
      keysByAdvertiser: Map[AdvertiserId, Set[String]] = Map.empty,
      // Reverse index: key → IDs it contains (enables O(K) removal instead of O(N) scan)
      idsForKey: Map[String, (Set[CampaignId], Set[CreativeId], Set[AdvertiserId])] = Map.empty,
      // Persisted approved creative IDs (survives restart)
      persistedApprovedIds: Set[CreativeId] = Set.empty,
      // Auto-approve toggle mirrored from SiteEntity via DData
      // (SiteEntity.AutoApproveKey). None/absent = disabled.
      cachedAutoApprove: Option[SiteEntity.CachedAutoApprove] = None,
      // Operator suspension mirrored from SiteEntity via DData
      // (SiteEntity.SiteSuspendedKey). While true every serve is refused
      // before any classification/auction work. Absent = not suspended.
      siteSuspended: Boolean = false,
      // Auto-approve trust anchors, mirrored from site_auto_approve_trust.
      // Written on MANUAL approvals (auto-approvals never widen trust),
      // shrunk on publisher reject/flag/revoke (TrustBroken). Loaded at
      // boot with retry; empty-until-loaded degrades to manual queueing.
      trustedCampaigns: Set[CampaignId] = Set.empty,
      trustedDomains: Set[String] = Set.empty,
      // Subset of persistedApprovedIds that got approved via the
      // auto-approve path — drives the "Auto-approved" dashboard badge.
      autoApprovedIds: Set[CreativeId] = Set.empty,
      // Best-effort set of creativeIds readers currently have pinned
      // (dog-ear). TRANSIENT — not snapshotted; rebuilt as serves
      // arrive. Populated when a serve honors a pin (BatchSelected
      // outcome with dogear.honored), pruned when a creative leaves
      // approval (CampaignPaused / CreativePaused / EvictCampaignFromSite).
      // Used by the topic-narrow orphan-eligibility predicate to exempt
      // pinned creatives from eviction so a reader's pin on a still-
      // served page survives the advertiser dropping a DIFFERENT topic.
      pinnedCreativeIds: Set[CreativeId] = Set.empty,
      // Verified host for serve-time enforcement (populated via DData)
      verifiedHost: Option[String] = None,
      // Per-page winner cache: key = "{siteId}|{normalizedUrl}".
      // Enforces "at most one slot per campaign per page". TTL'd
      // by SweepPageWinners tick; replicated via PageWinnersKey.
      pageWinners: Map[String, PageWinners] = Map.empty
  ) {

    /** Remove stale spend cache entries that haven't been updated in the given duration */
    def cleanupStaleSpendCache(maxAge: scala.concurrent.duration.FiniteDuration, now: Instant = Instant.now())
        : State = {
      val cutoff = now.minusMillis(maxAge.toMillis)
      val staleIds = spendInfoLastUpdated.collect {
        case (campaignId, lastUpdated) if lastUpdated.isBefore(cutoff) => campaignId
      }.toSet

      Option.when(staleIds.nonEmpty)(copy(
        spendInfoCache = spendInfoCache -- staleIds,
        spendInfoLastUpdated = spendInfoLastUpdated -- staleIds,
        pendingSpendByCampaign = pendingSpendByCampaign -- staleIds,
        participatingCampaigns = participatingCampaigns -- staleIds
      )).getOrElse(this)
    }
  }
}
