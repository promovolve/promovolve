package promovolve.advertiser

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.cluster.ddata.{ LWWMap, LWWMapKey, SelfUniqueAddress }
import org.apache.pekko.cluster.ddata.typed.scaladsl.{ DistributedData, Replicator }
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.*
import org.apache.pekko.persistence.typed.state.scaladsl.{ DurableStateBehavior, Effect }
import org.apache.pekko.util.Timeout
import promovolve.*
import promovolve.advertiser.AdvertiserEntity.CampaignSpendRecorded
import promovolve.taxonomy.{ Iab2xTo3xMigration, TieredCategory }
import promovolve.common.{ hash, BloomFilter, Timezones }
import promovolve.publisher.CategoryDemandRepo

import java.time
import java.time.{ Instant, LocalDate, ZoneOffset }
import java.util.concurrent.TimeUnit.NANOSECONDS
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.util.{ Failure, Success }

/**
 * Sharded entity representing a single advertising campaign.
 *
 * == Architecture ==
 * Uses DurableStateBehavior (JDBC-backed) for persistence with ephemeral in-memory buffering
 * for high-throughput spend tracking.
 *
 * == State Management ==
 *   - '''Persistent State''' (`State`): Targeting config, budget, accumulated spend, pending reports
 *   - '''Ephemeral State''' (`EphemeralState`): Spend buffer, flush sequence (lost on restart)
 *
 * == Spend Flow ==
 * {{{
 *   RecordSpend → buffer (ephemeral) → FlushBuffer → persist + report to AdvertiserEntity
 *                     ↓                                    ↓
 *              (batch/timer)                    (at-least-once delivery)
 * }}}
 *
 * == Key Features ==
 *   - '''Buffered Spend''': Batches spend events to reduce persistence overhead
 *   - '''Idempotency''': Scaffeine cache prevents duplicate spend recording
 *   - '''At-Least-Once Delivery''': Pending reports survive restarts via `pendingReports` in State
 *   - '''Probabilistic Throttling''': Gradually reduces bid probability as budget depletes
 *   - '''Daily Budget Windows''': Auto-resets at the advertiser account
 *     timezone's midnight (`State.timezone`, "" = UTC)
 */
object CampaignEntity {

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("campaign-entity")

  /**
   * DData key: campaignId -> registered landing-page (LP) domain. Each campaign
   * publishes its LP domain so the publisher's advertiser-domain block picker
   * can list all advertiser domains. Mirrors SiteEntity.VerifiedHostKey.
   */
  val AdvertiserDomainKey: LWWMapKey[CampaignId, String] = LWWMapKey("advertiser-lp-domain")

  // ---------- Behavior ----------
  def apply(
      campaignId: CampaignId,
      advertiserId: AdvertiserId,
      directory: ActorRef[CampaignDirectory.Command],
      sharding: ClusterSharding,
      categoryDemandRepo: CategoryDemandRepo,
      budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
      flushInterval: FiniteDuration = 500.millis, // Very tight: flush every 500ms
      maxBatchSize: Int = 20, // Very tight: flush every 20 events
      simDayDurationSeconds: Double = 86400.0 // Simulated day length (compresses budget days for scenario testing; 86400 = real day)
  )(using system: ActorSystem[?], ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        given askTimeout: Timeout = Timeout(500.millis)

        val advertiserRef = sharding.entityRefFor(AdvertiserEntity.TypeKey, advertiserId.value)

        // ========== Ephemeral state (not persisted, lost on restart) ==========
        var ephemeral: EphemeralState = EphemeralState.empty

        // Per-incarnation nonce baked into every flushId this actor mints.
        // flushSeq resets to 0 on restart (it lives in ephemeral state), so
        // without this a same-day restart would regenerate flushIds the
        // advertiser has already recorded (campId:day:0, :1, …) — and the
        // advertiser's PERSISTED processedFlushIds would dedupe them,
        // FREEZING its spendToday. A fresh nonce per actor start guarantees
        // post-restart flushIds never collide with pre-restart ones, while
        // retries within one incarnation still reuse the same id (correct
        // dedup). See AdvertiserEntity.RecordCampaignSpend.
        val flushIncarnation: String = java.util.UUID.randomUUID().toString.take(8)

        // Publish this campaign's landing-page domain to DData so the publisher
        // advertiser-domain block picker can list it. Lightweight: the replicator
        // reply is adapted to an ignored internal command (mirrors SiteEntity).
        val replicator = DistributedData(system).replicator
        given selfUniqueAddress: SelfUniqueAddress = DistributedData(system).selfUniqueAddress
        val ddataResponseAdapter =
          ctx.messageAdapter[Replicator.UpdateResponse[?]](_ => IgnoreDDataResponse)
        def lpDomainOf(s: State): Option[String] =
          s.landingUrl.flatMap(u =>
            scala.util.Try(java.net.URI.create(u).getHost).toOption.flatMap(Option(_)).map(_.toLowerCase)
          )
        def publishLpDomain(s: State): Unit =
          lpDomainOf(s).foreach { domain =>
            replicator ! Replicator.Update(
              AdvertiserDomainKey,
              LWWMap.empty[CampaignId, String],
              Replicator.WriteLocal,
              ddataResponseAdapter
            )(_.put(selfUniqueAddress, campaignId, domain))
          }

        // Directory registration is fire-and-forget (tell) + async ack: we send
        // CampaignReady and the CampaignRegistered ack arrives via this adapter.
        // No ask → no 10s ask-timeout, and a late ack still completes (the old
        // ask path dropped replies that landed after its timeout). So the
        // activate/save API never blocks on the singleton.
        val directoryAckAdapter: ActorRef[CampaignDirectory.CampaignRegistered] =
          ctx.messageAdapter(r => DirectoryRegistered(r.campaignId))
        val RegistrationRetryKey = "directory-registration-retry"
        // True from sending CampaignReady until the ack lands. While true, a
        // backoff timer re-tells (10s→2min) so a down/recovering singleton
        // reconciles without a fixed-interval retry storm across all campaigns.
        var registrationPending: Boolean = false
        var directoryRetryCount: Int = 0

        // ---- Idempotency config (safe defaults, overridable via application.conf)
        val config: Config = ctx.system.settings.config

        val idempotencyWindowMs: Long =
          if (config.hasPath("promovolve.campaign.idempotency.window"))
            config.getDuration("promovolve.campaign.idempotency.window").toMillis
          else 5.minutes.toMillis

        val idempotencyMaxEntries: Long =
          if (config.hasPath("promovolve.campaign.idempotency.max-entries"))
            config.getLong("promovolve.campaign.idempotency.max-entries")
          else 50_000L

        // Idempotency cache (bounded + time-based eviction)
        val processed: Cache[String, Boolean] =
          Scaffeine()
            .expireAfterWrite(idempotencyWindowMs.millis)
            .maximumSize(idempotencyMaxEntries)
            .build[String, Boolean]()

        ctx.log.info(
          "Campaign[{}] idempotency config: window={}ms maxEntries={}",
          campaignId.value,
          idempotencyWindowMs,
          idempotencyMaxEntries
        )

        // ----- Ephemeral state accessors (pure reads) -----
        /** Current buffered spend not yet persisted */
        def bufferedSpend: Spend = ephemeral.spend

        /** Number of spend events in buffer */
        def bufferedCount: Int = ephemeral.count

        // ----- Ephemeral state mutations (update var via pure transformations) -----
        /** Drain buffer and return accumulated spend. Resets buffer to empty. */
        def drainBuffer(): Spend = {
          val (amount, next) = ephemeral.drain
          ephemeral = next
          amount
        }

        /** Add spend to buffer */
        def bufferSpend(amount: Spend): Unit =
          ephemeral = ephemeral.addSpend(amount)

        /** Mark requestId as processed in bloom filter */
        def markProcessed(requestId: String): Unit =
          ephemeral.markProcessed(requestId)

        /** Get serialized bloom filter for persistence */
        def serializeFilter(): Array[Byte] =
          ephemeral.serializeFilter

        /** Reset ephemeral filter for new day */
        def resetFilter(): Unit =
          ephemeral = ephemeral.withFreshFilter

        /**
         * Try to mark a day roll. Returns true if already rolled (guard prevents double roll).
         * This prevents the race condition where both RecordSpend and CheckWindowRoll
         * try to roll to the same day, causing the bloom filter to reset twice.
         */
        def tryMarkDayRoll(epochDay: Long): Boolean = {
          val (alreadyRolled, updated) = ephemeral.tryMarkRolled(epochDay)
          ephemeral = updated
          alreadyRolled
        }

        /** Generate unique flush ID and advance sequence */
        def nextFlushId(epochDay: Long): FlushId = {
          val (id, next) = ephemeral.nextFlushId(campaignId, epochDay, flushIncarnation)
          ephemeral = next
          id
        }

        /**
         * Check if request was already processed (idempotency).
         *
         * Checks both:
         * 1. In-memory Scaffeine cache (fast path for normal operation)
         * 2. Ephemeral bloom filter (includes recovery data + current session)
         *
         * Semantics: sliding window — every observed requestId refreshes its TTL.
         */
        def isDuplicate(requestId: String): Boolean = {
          // Fast path: check in-memory cache first
          val inCache = processed.getIfPresent(requestId).isDefined

          // Check ephemeral bloom filter (O(1) - no deserialization needed)
          val isDup = inCache || ephemeral.mightContain(requestId)

          // Always write to cache to refresh expireAfterWrite TTL
          processed.put(requestId, true)
          isDup
        }

        // ========== At-least-once delivery to AdvertiserEntity ==========
        // Spend reports are persisted in pendingReports and retried until acknowledged.
        // On restart, pending reports are re-driven from recovered state.
        val maxRetries = 5
        val initialBackoff = 100.millis
        val maxBackoff = 5.seconds
        val maxPendingAge = 24.hours // Drop stale pending reports (e.g., from previous day)

        /** Send spend report to AdvertiserEntity via ask pattern */
        def attemptSpendReport(
            flushId: FlushId,
            amount: Spend,
            ts: Instant
        ): Future[AdvertiserEntity.CampaignSpendRecorded] =
          advertiserRef.ask[AdvertiserEntity.CampaignSpendRecorded](
            AdvertiserEntity.RecordCampaignSpend(flushId, campaignId, amount, ts, _)
          )

        /** Exponential backoff: 100ms, 200ms, 400ms, 800ms, 1600ms (capped at 5s) */
        def backoffFor(retryCount: Int): FiniteDuration = {
          val backoff = initialBackoff * math.pow(2, retryCount - 1).toLong
          if (backoff > maxBackoff) maxBackoff else FiniteDuration(backoff.toNanos, NANOSECONDS)
        }

        /** Fire spend report and pipe result back to self as Success/Failure */
        def initiateSpendReport(
            flushId: FlushId,
            amount: Spend,
            ts: Instant,
            retryCount: Int = 1
        ): Unit =
          ctx.pipeToSelf(attemptSpendReport(flushId, amount, ts)) {
            case Success(recorded) => SpendReportSuccess(recorded, flushId)
            case Failure(ex)       => SpendReportFailure(flushId, amount, ts, retryCount, ex.getMessage)
          }

        // ========== Directory & Budget helpers ==========
        /**
         * Send CampaignReady as a fire-and-forget tell. The CampaignRegistered
         * ack returns asynchronously via directoryAckAdapter — no ask, no
         * timeout. Shared by notifyDirectory and the backoff re-tell.
         */
        def sendCampaignReady(state: State, configEdit: Boolean): Unit =
          directory ! CampaignDirectory.CampaignReady(
            campaignId = campaignId,
            advertiserId = state.advertiserId,
            // Effective set so a campaign with empty explicit categories but a
            // non-empty Gemini suggestion is still registered/invited to bid.
            categories = state.effectiveCategories,
            // Fluid creatives have no per-creative size gate — empty = any size.
            sizes = Set.empty[AdSize],
            maxCpm = state.maxCpm,
            dailyBudget = state.dailyBudget,
            status = state.status,
            replyTo = directoryAckAdapter,
            bidOnUnmatchedContext = state.bidOnUnmatchedContext,
            siteAllowlist = state.siteAllowlist,
            configEdit = configEdit
          )

        /**
         * Mirror this campaign's demand into the durable `category_demand` table
         * (best-effort) so CategoryBidders can SEED from it after a restart
         * instead of waiting on the singleton's re-push. Replace-all semantics:
         * removes the campaign's old rows and writes one per current category;
         * `active=false` removes all its rows.
         */
        def writeDemandTable(state: State, active: Boolean): Unit = {
          val f =
            if (!active) categoryDemandRepo.removeCampaign(campaignId.value)
            else categoryDemandRepo.upsertCampaign(
              state.effectiveCategories.map(_.value),
              campaignId.value,
              state.advertiserId.value
            )
          ctx.pipeToSelf(f)(t => DemandTableResult(t.failed.toOption.map(_.getMessage)))
        }

        /**
         * Notify CampaignDirectory when a campaign becomes active/inactive or
         * its demand-relevant config changes. Fire-and-forget: registration
         * completes asynchronously (DirectoryRegistered flips the campaign to
         * biddable); the API reply is sent after persist by the caller, so
         * nothing blocks on the singleton.
         */
        def notifyDirectory(state: State, previousState: Option[State]): Unit = {
          val wasActive = previousState.exists(_.status == Status.Active)
          val isActive = state.status == Status.Active

          val configChanged = previousState.exists { prev =>
            prev.maxCpm != state.maxCpm ||
            // Compare the EFFECTIVE set (explicit-or-suggested fallback): a
            // change here alters auction eligibility, so it must trigger
            // re-registration even when the explicit `categories` is unchanged.
            prev.effectiveCategories != state.effectiveCategories ||
            prev.creativeAssignments != state.creativeAssignments ||
            prev.bidOnUnmatchedContext != state.bidOnUnmatchedContext ||
            prev.siteAllowlist != state.siteAllowlist
          }

          (wasActive, isActive, configChanged) match {
            case (true, false, _) =>
              ctx.log.info("Campaign[{}] became inactive, notifying directory", campaignId)
              registrationPending = false
              timers.cancel(RegistrationRetryKey)
              directory ! CampaignDirectory.CampaignGone(campaignId)
              writeDemandTable(state, active = false)

            case (wasAct, true, cfg) if !wasAct || cfg =>
              // Distinguish a REAL config edit (already-active campaign whose
              // demand-relevant config changed) from boot/recovery
              // (re)registration: only the former may arm eviction downstream.
              val isEdit = wasAct && cfg
              ctx.log.info(
                "Campaign[{}] ready (cpm={}, categories={}, configEdit={})",
                campaignId,
                state.maxCpm.toDouble,
                state.effectiveCategories.mkString(","),
                isEdit
              )
              // Fire-and-forget. The ack (DirectoryRegistered) marks the
              // campaign biddable; until then a backoff timer re-tells so a
              // down/recovering singleton reconciles without a lockstep storm.
              registrationPending = true
              directoryRetryCount = 0
              sendCampaignReady(state, configEdit = isEdit)
              timers.startSingleTimer(RegistrationRetryKey, RetryDirectoryRegistration, 10.seconds)
              writeDemandTable(state, active = true)

            case _ =>
              () // no directory change needed
          }
        }

        def fetchBidContext(creativeIds: Set[CreativeId]): Future[AdvertiserEntity.BidContext] =
          advertiserRef.ask[AdvertiserEntity.BidContext](
            AdvertiserEntity.GetBidContext(creativeIds, _)
          )

        // Self-heal for the account timezone: the advertiser's SetTimezone
        // fan-out is an at-most-once tell, so refresh from the source on
        // recovery and every CheckWindowRoll tick (5 min). SetTimezone is a
        // no-op when unchanged, so the steady-state cost is one ask.
        def refreshTimezoneFromAdvertiser(): Unit =
          ctx.pipeToSelf(
            advertiserRef.ask[AdvertiserEntity.AdvertiserTimezone](AdvertiserEntity.GetTimezone(_))
          ) {
            case Success(t) => SetTimezone(t.timezone)
            case Failure(_) => TimezoneRefreshFailed
          }

        // Total spend = persisted + buffered
        def totalSpend(state: State): Spend = state.spendToday + bufferedSpend

        // Remaining budget accounting for buffer
        def remainingBudget(state: State): Budget =
          state.dailyBudget - totalSpend(state)

        // Day-aware remaining budget: if day has changed since last reset, treat as fresh budget.
        // This handles the race condition where AdvertiserBudgetReset triggers re-auction before
        // CampaignEntity processes its own ResetDayStart command.
        def remainingBudgetForBidding(state: State, now: Instant): Budget = {
          val today = Timezones.localEpochDay(now, state.timezone)
          val lastResetDay = Timezones.localEpochDay(state.lastResetInstant, state.timezone)
          if (today > lastResetDay) {
            // Day has changed but reset not yet processed - treat as fresh budget
            state.dailyBudget
          } else {
            remainingBudget(state)
          }
        }

        // Budget check accounting for buffer
        def withinBudget(state: State): Boolean = totalSpend(state) < state.dailyBudget

        // UTC epoch day — feeds flush IDs ONLY. FlushIds must stay
        // zone-independent: they're a persisted dedup key on the advertiser
        // side, and making them follow an operator-changeable zone would add
        // a second variable to the (already delicate) dedup scheme. Budget
        // ROLL decisions use Timezones.localEpochDay(_, state.timezone).
        def epochDayOf(instant: Instant): Long =
          LocalDate.ofInstant(instant, ZoneOffset.UTC).toEpochDay

        // Note: Timers started in RecoveryCompleted signal to ensure proper initialization

        // ═══════════════════════════════════════════════════════════════════════
        // BIDDING
        // Handle bid requests from AuctioneerEntity
        // ═══════════════════════════════════════════════════════════════════════

        def bidding(state: State): PartialFunction[Command, Effect[State]] = {
          case CampaignBidRequest(
                siteId,
                url,
                slotId,
                pageCategory,
                floorCpm,
                replyTo
              ) =>
            ctx.log.debug(
              "Bid request for campaign {} from site {} slot {} url={}",
              campaignId.value,
              siteId.value,
              slotId.value,
              url
            )
            ctx.pipeToSelf(fetchBidContext(state.creativeAssignments)) {
              case Success(bidContext) =>
                BidRequest(
                  siteId = siteId,
                  pageCategory = pageCategory,
                  floorCpm = floorCpm,
                  replyTo = replyTo,
                  advRemaining = bidContext.remaining,
                  creatives = bidContext.creatives
                )
              case Failure(ex) =>
                ctx.log.error(
                  "fetchBidContext failed for advertiser {}: {}",
                  state.advertiserId,
                  ex.getMessage
                )
                BidRequest(
                  siteId = siteId,
                  pageCategory = pageCategory,
                  floorCpm = floorCpm,
                  replyTo = replyTo,
                  advRemaining = Budget.zero,
                  creatives = Set.empty
                )
            }
            Effect.none

          case BidRequest(
                siteId,
                pageCategory,
                floorCpm,
                replyTo,
                advRemaining,
                creatives
              ) =>
            ctx.log.debug(
              "BidRequest[{}]: creatives={}, pageCategory={}, advRemaining={}",
              campaignId.value,
              creatives.size,
              pageCategory.value,
              advRemaining.toDouble
            )
            // Deterministic budget check - AdServer's PI controller handles gradual slowdown
            // Use day-aware budget check to handle race condition where AdvertiserBudgetReset
            // triggers re-auction before CampaignEntity processes its own ResetDayStart.
            // Site-domain blocklist is enforced at AdServer (via DData), not here.
            val campaignRemaining = remainingBudgetForBidding(state, Instant.now())
            val rejection = state.bidRejectReason(siteId, pageCategory, floorCpm)
            val (eligible, reason) = rejection match {
              case Some(r) => (Set.empty[AdvertiserEntity.Creative], Some(r))
              case None    =>
                if (campaignRemaining <= Budget.zero || advRemaining <= Budget.zero)
                  (Set.empty[AdvertiserEntity.Creative], Some(BidRejectReason.BudgetExhausted))
                else {
                  val filtered = creatives.filter(_.isEligibleFor(siteId))
                  if (filtered.isEmpty) {
                    // Diagnostic: log why each creative was rejected.
                    creatives.foreach { c =>
                      ctx.log.info(
                        "  Creative {} rejected: isActive={} hasRejections={}",
                        c.id.value, c.isActive, c.rejectedSites.nonEmpty
                      )
                    }
                    (filtered, Some(BidRejectReason.NoCreatives))
                  } else (filtered, None)
                }
            }

            // Quality-adjusted auction: bid true value (maxCPM).
            // Clearing price is determined by competition and CTR, not just bids.
            val bidCpm = CPM.max(state.maxCpm, floorCpm)

            // Extract domain from landing URL
            val landingDomain = state.landingUrl.flatMap { url =>
              scala.util.Try(java.net.URI.create(url).getHost).toOption
            }.getOrElse("")

            if (eligible.nonEmpty) {
              ephemeral = ephemeral.incrementBids
              ctx.log.info("BidsToday incremented to {} for campaign {}", ephemeral.bidsToday: java.lang.Long,
                campaignId.value)
            }

            replyTo ! CampaignBidResponse(
              campaignId,
              state.advertiserId,
              eligible,
              cpm = if (eligible.nonEmpty) bidCpm else CPM.zero,
              maxCpm = state.maxCpm,
              adProductCategory = state.adProductCategory,
              landingDomain = landingDomain,
              rejectReason = reason,
              // Computed from the full fetched set (not `eligible`) so
              // below-floor rejects still carry it — an approved bidder
              // priced out by the current floor must keep teaching the
              // sweep range downward.
              hasApprovedCreative = creatives.exists(c => c.isActive && c.isApprovedFor(siteId))
            )
            Effect.none
        }

        // ═══════════════════════════════════════════════════════════════════════
        // SPEND RECORDING
        // Buffer spend events and flush periodically
        // ═══════════════════════════════════════════════════════════════════════

        def spendRecording(state: State): PartialFunction[Command, Effect[State]] = {
          case RecordSpend(requestId, amount, ts, replyTo) =>
            if (isDuplicate(requestId)) {
              ctx.log.debug(
                "Duplicate spend request ignored: campaign={} requestId={}",
                campaignId.value,
                requestId
              )
              replyTo ! SpendRecorded(
                totalSpend(state),
                remainingBudget(state),
                Timezones.localEpochDay(state.lastResetInstant, state.timezone)
              )
              Effect.none
            } else {
              // Check daily window roll (skip in simulated mode — day resets driven by ResetDayStart).
              // Day boundary = the advertiser account zone's midnight, not UTC.
              val simulatedMode = simDayDurationSeconds < 86400.0
              val today = Timezones.localEpochDay(ts, state.timezone)
              val needsRoll =
                !simulatedMode && Timezones.localEpochDay(state.lastResetInstant, state.timezone) != today

              // Guard against double roll: if CheckWindowRoll already rolled to this day, skip
              val alreadyRolled = if (needsRoll) tryMarkDayRoll(today) else false

              if (needsRoll && !alreadyRolled) {
                // Flush and roll window - persist new state.
                val wasExhausted = !withinBudget(state)
                val flushedAmount = drainBuffer()
                val cpmFlushId =
                  if (flushedAmount.isPositive)
                    Some(nextFlushId(epochDayOf(state.lastResetInstant)))
                  else None

                // Start fresh buffer and filter for new day
                resetFilter()
                markProcessed(requestId)
                bufferSpend(amount)

                val updatedPending =
                  cpmFlushId.map(id => id -> flushedAmount).toList
                    .foldLeft(state.pendingReports) { case (p, (id, amt)) =>
                      p.updated(id, PendingReport(amt, ts, 1))
                    }

                val newState = state.copy(
                  spendToday = Spend.zero, // New day starts at zero
                  lastResetInstant = ts, // Reset to actual time (not midnight)
                  pendingReports = updatedPending,
                  processedFilter = Array.emptyByteArray // Fresh filter persisted on next flush
                )

                replyTo ! SpendRecorded(totalSpend(newState), remainingBudget(newState), today)

                Effect.persist(newState).thenRun { _ =>
                  cpmFlushId.foreach(id => initiateSpendReport(id, flushedAmount, ts))
                  if (wasExhausted && state.status == Status.Active) {
                    budgetEventTopic ! Topic.Publish(
                      promovolve.CampaignBudgetReset(campaignId, newState.dailyBudget, ts)
                    )
                    ctx.log.info("Budget reset for campaign {}", campaignId.value)
                  }
                  // Publish spend update after window roll
                  budgetEventTopic ! Topic.Publish(
                    promovolve.SpendUpdate(
                      campaignId = campaignId,
                      advertiserId = advertiserId,
                      dailyBudget = newState.dailyBudget,
                      todaySpend = totalSpend(newState),
                      dayStart = newState.lastResetInstant,
                      timestamp = ts,
                      timezone = newState.timezone
                    )
                  )
                }
              } else {
                // Normal spend - mark as processed and buffer
                markProcessed(requestId)
                bufferSpend(amount)

                replyTo ! SpendRecorded(
                  totalSpend(state),
                  remainingBudget(state),
                  Timezones.localEpochDay(state.lastResetInstant, state.timezone)
                )

                // Flush if batch size reached
                if (bufferedCount >= maxBatchSize) {
                  ctx.log.debug(
                    "Flushing buffer for campaign {} (batch size): {} events",
                    campaignId,
                    maxBatchSize
                  )
                  ctx.self ! FlushBuffer(ts)
                }

                Effect.none
              }
            }

          case FlushBuffer(ts) =>
            val amount = drainBuffer()
            if (amount.isPositive) {
              val flushId = nextFlushId(epochDayOf(state.lastResetInstant))
              val newSpendToday = state.spendToday + amount

              // Serialize ephemeral bloom filter for persistence
              val updatedFilter = serializeFilter()

              val pending = PendingReport(amount = amount, ts = ts, retryCount = 1)
              val newState = state.copy(
                spendToday = newSpendToday,
                pendingReports = state.pendingReports.updated(flushId, pending),
                processedFilter = updatedFilter
              )

              Effect.persist(newState).thenRun { _ =>
                initiateSpendReport(flushId, amount, ts)
                // Publish spend update for AdServer pacing cache
                budgetEventTopic ! Topic.Publish(
                  promovolve.SpendUpdate(
                    campaignId = campaignId,
                    advertiserId = advertiserId,
                    dailyBudget = newState.dailyBudget,
                    todaySpend = newState.spendToday,
                    dayStart = newState.lastResetInstant,
                    timestamp = ts,
                    timezone = newState.timezone
                  )
                )
              }
            } else {
              Effect.none
            }

          case FlushTick =>
            if (bufferedSpend.isPositive) {
              ctx.log.debug(
                "Periodic flush for campaign {}: {}",
                campaignId,
                bufferedSpend
              )
              ctx.self ! FlushBuffer(Instant.now())
            }
            Effect.none

          case CheckWindowRoll =>
            // Piggyback the timezone self-heal on the roll tick.
            refreshTimezoneFromAdvertiser()
            // Auto-flip Active → Ended when endAt has passed. This is a
            // pure UI / housekeeping transition — canBid already returns
            // false from isWithinSchedule regardless of status, so the
            // serve path doesn't depend on this flip happening.
            val nowForEnd = Instant.now()
            val needsEndFlip = state.status == Status.Active && state.endAt.exists(nowForEnd.isAfter)
            if (needsEndFlip) {
              ctx.log.info(
                "Campaign {} reached endAt {}, flipping status to Ended",
                campaignId.value,
                state.endAt.get
              )
              val endedState = state.copy(status = Status.Ended)
              Effect.persist(endedState).thenRun { _ =>
                budgetEventTopic ! Topic.Publish(
                  promovolve.CampaignEnded(campaignId, advertiserId, nowForEnd)
                )
              }
            } else {
              // Skip in simulated mode — day resets driven by ResetDayStart.
              // Day boundary = the advertiser account zone's midnight, not UTC.
              val simulatedMode = simDayDurationSeconds < 86400.0
              val today = Timezones.localEpochDay(Instant.now(), state.timezone)
              val needsRoll =
                !simulatedMode && Timezones.localEpochDay(state.lastResetInstant, state.timezone) != today
              // Guard against double roll: if RecordSpend already rolled to this day, skip
              val alreadyRolled = if (needsRoll) tryMarkDayRoll(today) else false

              if (needsRoll && !alreadyRolled) {
                ctx.log.info(
                  "Rolling budget window for campaign {} to epoch day {}",
                  campaignId.value,
                  today
                )
                // Flush before rolling.
                val amount = drainBuffer()

                // Reset ephemeral filter for new day (old day's filter discarded)
                resetFilter()

                val now = Instant.now()
                val cpmFlushId =
                  if (amount.isPositive)
                    Some(nextFlushId(epochDayOf(state.lastResetInstant)))
                  else None

                val updatedPending =
                  cpmFlushId.map(id => id -> amount).toList
                    .foldLeft(state.pendingReports) { case (p, (id, amt)) =>
                      p.updated(id, PendingReport(amt, now, 1))
                    }

                val newState = state.copy(
                  spendToday = Spend.zero,
                  lastResetInstant = now, // Reset to actual time
                  pendingReports = updatedPending,
                  processedFilter = Array.emptyByteArray // Fresh filter for new day
                )

                Effect.persist(newState).thenRun { _ =>
                  cpmFlushId.foreach(id => initiateSpendReport(id, amount, now))
                }
              } else Effect.none
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // SPEND REPORT DELIVERY
        // At-least-once delivery of spend reports to AdvertiserEntity
        // ═══════════════════════════════════════════════════════════════════════

        def spendReportDelivery(state: State): PartialFunction[Command, Effect[State]] = {
          case SpendReportSuccess(recorded, flushId) =>
            ctx.log.debug("Spend report acknowledged for flush {}, {}", flushId, recorded)
            Effect.persist(state.copy(pendingReports = state.pendingReports - flushId))

          case SpendReportFailure(flushId, amount, ts, retryCount, error) =>
            ctx.log.warn(
              "Spend report failed for flush {} (attempt {}): {}",
              flushId.value,
              retryCount,
              error
            )

            val nextAttempt = retryCount + 1
            if (nextAttempt <= maxRetries) {
              Effect
                .persist(
                  state.copy(
                    pendingReports = state.pendingReports.updated(
                      flushId,
                      PendingReport(amount = amount, ts = ts, retryCount = nextAttempt)
                    )
                  )
                )
                .thenRun { _ =>
                  ctx.scheduleOnce(
                    backoffFor(nextAttempt),
                    ctx.self,
                    SpendReportRetry(flushId, amount, ts, nextAttempt)
                  )
                }
            } else {
              ctx.log.error(
                "SPEND_TRACKING_FAILURE: Failed to report spend after {} retries for flush {}. Amount: {}",
                maxRetries,
                flushId,
                amount
              )
              Effect.none
            }

          case SpendReportRetry(flushId, amount, ts, retryCount) =>
            if (!state.pendingReports.contains(flushId)) Effect.none
            else if (time.Duration.between(ts, Instant.now()).toMillis > maxPendingAge.toMillis) {
              ctx.log.warn(
                "STALE_PENDING_REPORT: Dropping spend report older than {} for flush {}. Amount: {}",
                maxPendingAge,
                flushId,
                amount
              )
              Effect.persist(state.copy(pendingReports = state.pendingReports - flushId))
            } else if (retryCount <= maxRetries) {
              ctx.log.info(
                "Retrying spend report for flush {} (attempt {})",
                flushId.value,
                retryCount
              )
              initiateSpendReport(flushId, amount, ts, retryCount)
              Effect.none
            } else {
              ctx.log.error(
                "SPEND_TRACKING_FAILURE: Failed to report spend after {} retries for flush {}. Amount: {}",
                maxRetries,
                flushId,
                amount
              )
              Effect.none
            }

          case DirectoryRegistered(cid) =>
            ctx.log.debug("Campaign[{}] registered in directory", cid.value)
            registrationPending = false
            directoryRetryCount = 0
            timers.cancel(RegistrationRetryKey)
            // Campaign is now biddable. (A campaign-SSE event would fire here to
            // flip the UI live — deferred follow-up; status is already persisted.)
            Effect.none

          case RetryDirectoryRegistration =>
            // No ack yet — re-tell CampaignReady if still pending and active.
            // Backoff 20s, 40s, 80s, … capped at 2 min (the initial 10s was
            // scheduled at send time) so a down/recovering singleton reconciles
            // without all campaigns hammering it in lockstep. The ack cancels it.
            if (registrationPending && state.status == Status.Active) {
              directoryRetryCount += 1
              // Re-tells never claim configEdit: if the original edit event
              // was lost, failing to arm eviction is the safe direction.
              sendCampaignReady(state, configEdit = false)
              val delay = math.min(10L * (1L << math.min(directoryRetryCount, 6)), 120L).seconds
              ctx.log.warn(
                "Campaign[{}] directory ack pending — re-told (attempt #{}), next in {}",
                campaignId.value, directoryRetryCount + 1, delay
              )
              timers.startSingleTimer(RegistrationRetryKey, RetryDirectoryRegistration, delay)
            }
            Effect.none

          case DemandTableResult(err) =>
            err.foreach(e =>
              ctx.log.warn(
                "Campaign[{}] category_demand write failed (best-effort, heals on next change): {}",
                campaignId.value, e
              ))
            Effect.none
        }

        // ═══════════════════════════════════════════════════════════════════════
        // CONFIG MANAGEMENT
        // Query and update campaign configuration
        // ═══════════════════════════════════════════════════════════════════════

        def configManagement(state: State): PartialFunction[Command, Effect[State]] = {
          case GetCampaign(replyTo) =>
            replyTo ! CampaignInfo(
              campaignId = campaignId,
              status = state.status,
              categories = state.categories,
              maxCpm = state.maxCpm,
              dailyBudget = state.dailyBudget,
              creativeIds = state.creativeAssignments,
              adProductCategory = state.adProductCategory,
              landingUrl = state.landingUrl,
              bidOnUnmatchedContext = state.bidOnUnmatchedContext,
              startAt = state.startAt,
              endAt = state.endAt,
              siteAllowlist = state.siteAllowlist,
              suggestedCategories = state.suggestedCategories,
              name = state.name
            )
            Effect.none

          case UpdateStatus(newStatus, replyTo) =>
            val newState = state.copy(status = newStatus)
            // Defer reply until directory registration completes
            notifyDirectory(newState, Some(state))
            Effect.persist(newState).thenRun { _ =>
              replyTo ! StatusUpdated(campaignId, newStatus)
              // Publish initial spend state when campaign becomes Active
              if (state.status != Status.Active && newStatus == Status.Active) {
                budgetEventTopic ! Topic.Publish(
                  promovolve.SpendUpdate(
                    campaignId = campaignId,
                    advertiserId = advertiserId,
                    dailyBudget = newState.dailyBudget,
                    todaySpend = totalSpend(newState),
                    dayStart = newState.lastResetInstant,
                    timestamp = Instant.now(),
                    timezone = newState.timezone
                  )
                )
              }
              // Publish CampaignPaused to budget topic so all AdServers handle
              // locally. A delete stops serving like a pause does, so emit it
              // for Deleted too — otherwise AdServers keep the campaign in
              // their local candidate set until the next refresh.
              if (state.status == Status.Active &&
                (newStatus == Status.Paused || newStatus == Status.Deleted)) {
                budgetEventTopic ! Topic.Publish(
                  promovolve.CampaignPaused(campaignId,
                    Instant.now())
                )
              }
            }

          case UpdateConfig(maxCpm, dailyBudget, adProductCat, categoriesOpt, landingUrlOpt, bidOnUnmatchedCtx,
                startAtOpt, endAtOpt, siteAllowlistOpt, nameOpt, replyTo) =>
            val newAdProductCategory = adProductCat.getOrElse(state.adProductCategory)
            // Target categories are now an explicit advertiser declaration —
            // no longer derived from adProductCategory. Any 2.x ids the
            // caller passes are routed through the 3.0 migration table.
            val newCategories: Set[CategoryId] = categoriesOpt match {
              case Some(cs) => Iab2xTo3xMigration.migrateSet(cs.map(_.value)).map(CategoryId(_))
              case None     => state.categories
            }

            val wasWithin = withinBudget(state)
            val budgetChanged = dailyBudget.exists(_ != state.dailyBudget)
            val adProductChanged = adProductCat.exists(_ != state.adProductCategory)
            val newState = state.copy(
              maxCpm = maxCpm.getOrElse(state.maxCpm),
              dailyBudget = dailyBudget.getOrElse(state.dailyBudget),
              adProductCategory = newAdProductCategory,
              landingUrl = landingUrlOpt.getOrElse(state.landingUrl),
              categories = newCategories,
              bidOnUnmatchedContext = bidOnUnmatchedCtx.getOrElse(state.bidOnUnmatchedContext),
              // Schedule. startAt = Some(value) sets, None = no change.
              // endAt = Some(Some(value)) sets, Some(None) clears, None = no change.
              startAt = startAtOpt.getOrElse(state.startAt),
              endAt = endAtOpt.getOrElse(state.endAt),
              siteAllowlist = siteAllowlistOpt.getOrElse(state.siteAllowlist),
              name = nameOpt.map(_.trim).filter(_.nonEmpty).getOrElse(state.name)
            )
            val nowWithin = withinBudget(newState)
            // Defer reply until directory registration completes
            notifyDirectory(newState, Some(state))
            Effect.persist(newState).thenRun { _ =>
              replyTo ! ConfigUpdated(campaignId)
              // Keep the advertiser-domain directory current if the LP changed.
              publishLpDomain(newState)
              val ts = Instant.now()

              // Check for budget transition (only if budget actually changed)
              if (budgetChanged) {
                if (wasWithin && !nowWithin) {
                  budgetEventTopic ! Topic.Publish(
                    promovolve.CampaignBudgetExhausted(campaignId, ts)
                  )
                  ctx.log.info("Budget reduced below spend for campaign {}, publishing exhaustion", campaignId.value)
                } else if (!wasWithin && nowWithin) {
                  budgetEventTopic ! Topic.Publish(
                    promovolve.CampaignBudgetReset(campaignId, newState.dailyBudget, ts)
                  )
                  ctx.log.info("Budget increased above spend for campaign {}, publishing reset", campaignId.value)
                }

                budgetEventTopic ! Topic.Publish(
                  promovolve.SpendUpdate(
                    campaignId = campaignId,
                    advertiserId = advertiserId,
                    dailyBudget = newState.dailyBudget,
                    todaySpend = totalSpend(newState),
                    dayStart = newState.lastResetInstant,
                    timestamp = ts,
                    timezone = newState.timezone
                  )
                )
              }

              if (adProductChanged) {
                budgetEventTopic ! Topic.Publish(
                  promovolve.CampaignAdProductChanged(campaignId, ts)
                )
                ctx.log.info("Ad product category changed for campaign {}, publishing removal", campaignId.value)
              }
            }

          case SetTimezone(tz) =>
            if ((tz.nonEmpty && !Timezones.isValid(tz)) || tz == state.timezone)
              Effect.none
            else {
              val newState = state.copy(timezone = tz)
              Effect.persist(newState).thenRun { persisted =>
                // Re-derive the double-roll guard in the NEW zone so it
                // neither blocks the legitimate next roll nor allows a double
                // roll (the old guard value is a day number in the old zone).
                ephemeral = ephemeral.copy(
                  lastRolledEpochDay = Timezones.localEpochDay(persisted.lastResetInstant, tz)
                )
                // lastResetInstant is deliberately untouched: the current
                // budget day simply ends at the next new-zone midnight, or
                // rolls on the next spend/tick if the last reset already
                // falls on an earlier new-zone day. Spend only ever resets
                // via the normal roll paths; this warn is the operator trace
                // for the boundary jump.
                ctx.log.warn(
                  "Timezone change for campaign {}: '{}' -> '{}' spendToday={} lastResetInstant={} immediateRollPending={}",
                  campaignId.value,
                  state.timezone,
                  tz,
                  totalSpend(persisted).value,
                  persisted.lastResetInstant,
                  Timezones.localEpochDay(persisted.lastResetInstant, tz) !=
                    Timezones.localEpochDay(Instant.now(), tz)
                )
                // Nudge AdServer pacing onto the new window right away
                // instead of waiting for the next flush.
                budgetEventTopic ! Topic.Publish(
                  promovolve.SpendUpdate(
                    campaignId = campaignId,
                    advertiserId = advertiserId,
                    dailyBudget = persisted.dailyBudget,
                    todaySpend = totalSpend(persisted),
                    dayStart = persisted.lastResetInstant,
                    timestamp = Instant.now(),
                    timezone = tz
                  )
                )
              }
            }

          case RefineCategoriesFromCreative(detectedCategories, replyTo) =>
            if (detectedCategories.isEmpty) {
              replyTo ! CategoriesRefined(campaignId)
              Effect.none
            } else {
              // Refinement touches ONLY suggestedCategories (the durable
              // Gemini default that effectiveCategories falls back to when
              // the explicit set is empty). The explicit `categories` field
              // is advertiser-owned and is NEVER mutated here: the old
              // union-into-explicit semantics let a boot-time creative
              // re-verification silently retarget a live serving campaign
              // (Gemini nondeterminism + keepMostSpecific dropping an
              // ancestor the advertiser chose) — the trigger of the
              // approval-purge cascade. Deploys must not re-derive
              // targeting the advertiser set by hand.
              val prunedSuggested =
                TieredCategory
                  .keepMostSpecific((state.suggestedCategories ++ detectedCategories).map(_.value))
                  .map(CategoryId(_))
              val newState = state.copy(suggestedCategories = prunedSuggested)
              if (newState.suggestedCategories == state.suggestedCategories) {
                replyTo ! CategoriesRefined(campaignId)
                Effect.none
              } else {
                ctx.log.info(
                  "Campaign[{}] refined suggested categories: added {} (suggested total {}, explicit untouched: {})",
                  campaignId.value,
                  (newState.suggestedCategories -- state.suggestedCategories).mkString(","),
                  newState.suggestedCategories.size,
                  state.categories.size
                )
                // Re-registration only happens when the EFFECTIVE set moved,
                // i.e. the explicit set is empty and the suggestion is the
                // live targeting. With explicit targeting present this is a
                // pure bookkeeping persist — no directory churn, no
                // re-auction, no eviction wave.
                if (newState.effectiveCategories != state.effectiveCategories) {
                  notifyDirectory(newState, Some(state))
                }
                Effect.persist(newState).thenRun { _ =>
                  replyTo ! CategoriesRefined(campaignId)
                }
              }
            }

          case AssignCreatives(creativeIds, replyTo) =>
            val newState = state.assignCreatives(creativeIds)
            // Defer reply until directory registration completes
            notifyDirectory(newState, Some(state))
            Effect.persist(newState).thenRun { _ =>
              replyTo ! CreativesAssigned(campaignId, creativeIds)
            }

          case UnassignCreatives(creativeIds, replyTo) =>
            val newState = state.unassignCreatives(creativeIds)
            // Defer reply until directory registration completes
            notifyDirectory(newState, Some(state))
            Effect.persist(newState).thenRun { _ =>
              replyTo ! CreativesUnassigned(campaignId, creativeIds)
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // BUDGET MANAGEMENT
        // Query budget info, reserve budget, replenish budget
        // ═══════════════════════════════════════════════════════════════════════

        def budgetManagement(state: State): PartialFunction[Command, Effect[State]] = {
          case GetBudgetInfo(replyTo) =>
            replyTo ! BudgetInfo(
              dailyBudget = state.dailyBudget,
              spent = totalSpend(state),
              remaining = remainingBudget(state)
            )
            Effect.none

          case GetCampaignStats(replyTo) =>
            replyTo ! CampaignStats(
              campaignId = campaignId,
              bidsToday = ephemeral.bidsToday,
              spendToday = totalSpend(state),
              dailyBudget = state.dailyBudget
            )
            Effect.none

          case GetSpendInfo(replyTo) =>
            // Return actual reset time (not midnight) for accurate pacing
            replyTo ! SpendInfo(
              dailyBudget = state.dailyBudget,
              todaySpend = totalSpend(state),
              dayStart = state.lastResetInstant,
              timezone = state.timezone
            )
            Effect.none

          case ResetDayStart(replyTo, dayDuration) =>
            val now = Instant.now()
            val sinceLast = java.time.Duration.between(state.lastResetInstant, now).toMillis
            // Use the caller's day duration (from pacing config) for the min interval,
            // not the global simDayDurationSeconds which may not reflect per-site config
            val effectiveDaySeconds = math.min(dayDuration.toDouble, simDayDurationSeconds)
            val minInterval = (effectiveDaySeconds * 500).toLong // half a simulated day in ms

            if (sinceLast < minInterval) {
              // Duplicate reset — too soon after last one, treat as no-op
              ctx.log.info(
                "ResetDayStart ignored (duplicate): campaign={} sinceLastReset={}ms minInterval={}ms",
                campaignId.value,
                sinceLast,
                minInterval
              )
              replyTo ! DayStartReset(campaignId, state.lastResetInstant)
              Effect.none
            } else {
              val wasExhausted = !withinBudget(state)
              // Flush buffered spend and create pending reports — don't discard silently.
              val flushedAmount = drainBuffer()
              ctx.log.info(
                "ResetDayStart: campaign={} oldStart={} newStart={} oldSpend={} flushed={} wasExhausted={}",
                campaignId.value,
                state.lastResetInstant,
                now,
                state.spendToday.value,
                flushedAmount.value,
                wasExhausted
              )
              // Reset ephemeral filter for new day
              resetFilter()

              val cpmFlushId =
                if (flushedAmount.isPositive)
                  Some(nextFlushId(epochDayOf(state.lastResetInstant)))
                else None

              val updatedPending =
                cpmFlushId.map(id => id -> flushedAmount).toList
                  .foldLeft(state.pendingReports) { case (p, (id, amt)) =>
                    p.updated(id, PendingReport(amt, now, 1))
                  }

              val newState = state.copy(
                spendToday = Spend.zero,
                lastResetInstant = now,
                pendingReports = updatedPending,
                processedFilter = Array.emptyByteArray
              )

              Effect.persist(newState).thenRun { _ =>
                cpmFlushId.foreach(id => initiateSpendReport(id, flushedAmount, now))
                // If campaign was exhausted, notify AdServer to un-mark it
                if (wasExhausted && state.status == Status.Active) {
                  budgetEventTopic ! Topic.Publish(
                    promovolve.CampaignBudgetReset(campaignId, newState.dailyBudget, now)
                  )
                  ctx.log.info("Budget reset for campaign {} (simulated day rollover)", campaignId.value)
                }
                // Publish updated spend info so AdServer gets new dayStart with zero spend
                budgetEventTopic ! Topic.Publish(
                  promovolve.SpendUpdate(
                    campaignId = campaignId,
                    advertiserId = advertiserId,
                    dailyBudget = newState.dailyBudget,
                    todaySpend = Spend.zero,
                    dayStart = now,
                    timestamp = now,
                    timezone = newState.timezone
                  )
                )
              }.thenReply(replyTo)(_ => DayStartReset(campaignId, now))
            }

          case TryReserve(requestId, amount, replyTo) =>
            if (isDuplicate(requestId)) {
              // Already processed - treat as success (idempotent)
              ctx.log.debug(
                "TryReserve duplicate: campaign={} requestId={}",
                campaignId.value,
                requestId
              )
              replyTo ! Reserved(remainingBudget(state))
              Effect.none
            } else {
              val remaining = remainingBudget(state)
              if (amount <= remaining) { // Spend <= Budget comparison
                // Have budget - reserve it NOW (before reply)
                markProcessed(requestId)
                val wasWithin = withinBudget(state)
                bufferSpend(amount)
                val nowWithin = withinBudget(state)
                val newRemaining = remainingBudget(state)

                ctx.log.info(
                  "TryReserve SUCCESS: campaign={} amount={} remaining={} buffered={}",
                  campaignId.value,
                  amount.toDouble,
                  newRemaining.toDouble,
                  bufferedSpend.toDouble
                )

                replyTo ! Reserved(newRemaining)

                // Publish budget exhaustion event when transitioning from within-budget to exhausted
                if (wasWithin && !nowWithin) {
                  budgetEventTopic ! Topic.Publish(
                    promovolve.CampaignBudgetExhausted(campaignId,
                      Instant.now())
                  )
                  ctx.log.warn("Budget exhausted for campaign {}", campaignId.value)
                }

                // Flush if batch size reached (publishes SpendUpdate for pacing)
                if (bufferedCount >= maxBatchSize) {
                  ctx.log.debug(
                    "TryReserve flushing buffer for campaign {} (batch size): {} events",
                    campaignId.value,
                    maxBatchSize
                  )
                  ctx.self ! FlushBuffer(Instant.now())
                }

                Effect.none
              } else {
                // Insufficient budget
                ctx.log.info(
                  "TryReserve DENIED: campaign={} amount={} remaining={}",
                  campaignId.value,
                  amount.toDouble,
                  remaining.toDouble
                )
                replyTo ! InsufficientBudget
                Effect.none
              }
            }

          case ReplenishBudget(newBudget, replyTo) =>
            val spendTotal = state.spendToday + bufferedSpend
            ctx.log.info(
              "ReplenishBudget: campaign={} newBudget={} totalSpend={}",
              campaignId.value,
              newBudget.toDouble,
              spendTotal.toDouble
            )

            // Reject if new budget doesn't exceed current spend
            if (newBudget.value <= spendTotal.value) {
              ctx.log.warn(
                "ReplenishBudget rejected: campaign={} newBudget={} <= totalSpend={}",
                campaignId.value,
                newBudget.toDouble,
                spendTotal.toDouble
              )
              Effect.reply(replyTo)(ReplenishRejected(campaignId, spendTotal, newBudget))
            } else {
              val newState = state.copy(dailyBudget = newBudget)

              // Publish CampaignBudgetReset so AuctioneerEntity triggers re-auction
              val ts = java.time.Instant.now()
              budgetEventTopic ! Topic.Publish(
                promovolve.CampaignBudgetReset(campaignId, newBudget, ts)
              )
              // Publish spend update with new budget
              budgetEventTopic ! Topic.Publish(
                promovolve.SpendUpdate(
                  campaignId = campaignId,
                  advertiserId = advertiserId,
                  dailyBudget = newBudget,
                  todaySpend = spendTotal,
                  dayStart = state.lastResetInstant,
                  timestamp = ts,
                  timezone = state.timezone
                )
              )

              Effect
                .persist(newState)
                .thenReply(replyTo)(_ => BudgetReplenished(campaignId, newBudget))
            }

        }

        // ═══════════════════════════════════════════════════════════════════════
        // LIFECYCLE
        // Stop command handling
        // ═══════════════════════════════════════════════════════════════════════

        def lifecycle(state: State): PartialFunction[Command, Effect[State]] = {
          case IgnoreDDataResponse =>
            // Adapted DData Update reply — fire-and-forget publish, nothing to do.
            Effect.none

          case TimezoneRefreshFailed =>
            // Advertiser ask timed out/failed — next CheckWindowRoll retries.
            Effect.none

          case MigrateLegacyCategories =>
            // Triggered once from RecoveryCompleted when persisted ids look
            // pre-3.0. Pekko DurableState already wrote the snapshot, so we
            // only re-persist when migration actually changed the set.
            val migratedCategories = Iab2xTo3xMigration.migrateSet(state.categories.map(_.value)).map(CategoryId(_))
            val migratedBlocklist =
              Iab2xTo3xMigration.migrateSet(state.categoryBlocklist.map(_.value)).map(CategoryId(_))
            if (migratedCategories == state.categories && migratedBlocklist == state.categoryBlocklist) {
              Effect.none
            } else {
              ctx.log.info(
                "Campaign[{}] migrating categories to IAB 3.0: {} → {} ({} blocklist)",
                campaignId,
                state.categories.size,
                migratedCategories.size,
                migratedBlocklist.size
              )
              Effect.persist(state.copy(
                categories = migratedCategories,
                categoryBlocklist = migratedBlocklist
              ))
            }

          case Stop =>
            if (state.status == Status.Active) {
              directory ! CampaignDirectory.CampaignGone(campaignId)
            }
            // Flush buffer before stopping
            val amount = drainBuffer()
            if (amount.isPositive) {
              val now = Instant.now()
              val cpmFlushId = Some(nextFlushId(epochDayOf(state.lastResetInstant)))

              // Serialize ephemeral bloom filter for persistence
              val updatedFilter = serializeFilter()

              val updatedPending =
                cpmFlushId.map(id => id -> amount).toList
                  .foldLeft(state.pendingReports) { case (p, (id, amt)) =>
                    p.updated(id, PendingReport(amt, now, 1))
                  }

              Effect
                .persist(
                  state.copy(
                    spendToday = state.spendToday + amount,
                    pendingReports = updatedPending,
                    processedFilter = updatedFilter
                  )
                )
                .thenStop()
            } else {
              Effect.stop()
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // DURABLE STATE BEHAVIOR
        // Main behavior definition - composes all partial functions
        // ═══════════════════════════════════════════════════════════════════════

        DurableStateBehavior[Command, State](
          persistenceId = PersistenceId.ofUniqueId(s"campaign-$campaignId"),
          emptyState = State.empty(campaignId, advertiserId),
          commandHandler = (state, command) => {
            // Compose partial functions for each concern area
            val handlers: PartialFunction[Command, Effect[State]] =
              bidding(state).orElse(
                spendRecording(state)).orElse(
                spendReportDelivery(state)).orElse(
                configManagement(state)).orElse(
                budgetManagement(state)).orElse(
                lifecycle(state))

            handlers(command)
          }
        ).receiveSignal { case (state, RecoveryCompleted) =>
          ctx.log.info(
            "Campaign[{}] recovered: status={}, creatives={}, spent={}",
            campaignId,
            state.status,
            state.creativeAssignments.size,
            state.spendToday
          )

          // Republish the LP domain to the advertiser-domain DData directory so
          // it survives restarts (mirrors SiteEntity's verified-host republish).
          publishLpDomain(state)

          // Start timers AFTER recovery to prevent processing timer messages before state is ready
          timers.startTimerWithFixedDelay(FlushTick, flushInterval)
          timers.startTimerWithFixedDelay(CheckWindowRoll, 5.minutes)

          // Adopt the account timezone in case the advertiser's fan-out tell
          // was lost while this entity was passivated (no-op when unchanged).
          refreshTimezoneFromAdvertiser()
          // RL disabled — quality-adjusted auction makes bid shading counterproductive.
          // Bidding true value (maxCPM) is always optimal in Vickrey auction.

          // Initialize ephemeral bloom filter from persisted filter
          ephemeral = EphemeralState.fromPersistedFilter(state.processedFilter)

          // If snapshot still carries legacy 2.x ids, normalize and re-persist.
          // Cheap to test (Iab2xTo3xMigration.resolve is a Map.get) — only fires
          // an Effect.persist when something actually changes.
          val needsMigration = (state.categories ++ state.categoryBlocklist).exists { c =>
            Iab2xTo3xMigration.resolve(c.value) match {
              case Iab2xTo3xMigration.Resolution.Mapped(v30, _) => v30 != c.value
              case Iab2xTo3xMigration.Resolution.Dropped        => true
              case Iab2xTo3xMigration.Resolution.Unknown        => false
            }
          }
          if (needsMigration) ctx.self ! MigrateLegacyCategories

          // Re-drive any pending spend reports after restart
          state.pendingReports.foreach { case (flushId, pending) =>
            ctx.scheduleOnce(
              backoffFor(pending.retryCount),
              ctx.self,
              SpendReportRetry(flushId, pending.amount, pending.ts, pending.retryCount)
            )
          }

          // Re-register with the directory on recovery if active. Fire-and-
          // forget tell + backoff re-tell — so a cluster restart where every
          // active campaign recovers at once does NOT stampede the singleton.
          if (state.status == Status.Active) {
            notifyDirectory(state, None)
          }
        }
      }
    }

  // ---------- Protocol ----------
  sealed trait Command extends promovolve.CborSerializable

  sealed trait ReserveResult extends promovolve.CborSerializable

  sealed trait ReplenishResult extends promovolve.CborSerializable

  private sealed trait Internal extends Command

  /**
   * Self-message sent once after recovery if persisted state still carries
   * legacy IAB 2.x category ids. Handler in `lifecycle` normalizes the
   * set through [[Iab2xTo3xMigration]] and persists if anything changed.
   */
  private case object MigrateLegacyCategories extends Internal
  private case object IgnoreDDataResponse extends Internal

  /** Timezone self-heal ask failed — benign, retried on the next roll tick. */
  private case object TimezoneRefreshFailed extends Internal

  private sealed trait SpendReportResult extends Internal

  /** Immutable spend buffer - pure state transitions */
  final case class SpendBuffer(spend: Spend, count: Int) {
    def add(amount: Spend): SpendBuffer = SpendBuffer(spend + amount, count + 1)
    def drain: (Spend, SpendBuffer) = (spend, SpendBuffer.empty)
    def isPositive: Boolean = spend.isPositive
  }

  /** Ephemeral state - not persisted, lost on actor restart. Pure transformations. */
  final case class EphemeralState(
      buffer: SpendBuffer,
      flushSeq: Long,
      idempotencyFilter: BloomFilter, // Bloom filter for idempotency (serialized on flush)
      lastRolledEpochDay: Long = -1L, // Guard against double day-roll (prevents race between RecordSpend and CheckWindowRoll)
      bidsToday: Long = 0 // Eligible bid count today (for win rate: impressions / bidsToday)
  ) {
    def spend: Spend = buffer.spend
    def count: Int = buffer.count

    def incrementBids: EphemeralState = copy(bidsToday = bidsToday + 1)

    def addSpend(amount: Spend): EphemeralState =
      copy(buffer = buffer.add(amount))

    def drain: (Spend, EphemeralState) = {
      val (amount, newBuffer) = buffer.drain
      (amount, copy(buffer = newBuffer))
    }

    def nextFlushId(campaignId: CampaignId, epochDay: Long, incarnation: String): (FlushId, EphemeralState) = {
      val id = FlushId.generate(campaignId, epochDay, incarnation, flushSeq)
      (id, copy(flushSeq = flushSeq + 1))
    }

    /** Check if requestId might be a duplicate */
    def mightContain(requestId: String): Boolean =
      idempotencyFilter.maybeContains(requestId.hash)

    /** Mark requestId as processed */
    def markProcessed(requestId: String): Unit =
      idempotencyFilter.add(requestId.hash)

    /** Serialize the filter for persistence */
    def serializeFilter: Array[Byte] =
      BloomFilter.serialize(idempotencyFilter)

    /** Reset filter for new day */
    def withFreshFilter: EphemeralState =
      copy(idempotencyFilter = EphemeralState.createFilter(), bidsToday = 0)

    /**
     * Check if we've already rolled to the given epoch day (guards against double roll).
     * Returns (alreadyRolled, updatedState).
     * If not already rolled, updates lastRolledEpochDay to prevent future double rolls.
     */
    def tryMarkRolled(epochDay: Long): (Boolean, EphemeralState) =
      if (lastRolledEpochDay >= epochDay) (true, this)
      else (false, copy(lastRolledEpochDay = epochDay))
  }

  /**
   * Refine targeting categories from creative image analysis.
   * Unions detected categories with existing campaign categories (additive).
   */
  final case class RefineCategoriesFromCreative(
      detectedCategories: Set[CategoryId],
      replyTo: ActorRef[CategoriesRefined]
  ) extends Command

  final case class CategoriesRefined(campaignId: CampaignId) extends promovolve.CborSerializable

  /** Assign creatives to this campaign */
  final case class AssignCreatives(
      creativeIds: Set[CreativeId],
      replyTo: ActorRef[CreativesAssigned]
  ) extends Command

  final case class CreativesAssigned(campaignId: CampaignId, creativeIds: Set[CreativeId])
      extends promovolve.CborSerializable

  /** Unassign creatives from this campaign */
  final case class UnassignCreatives(
      creativeIds: Set[CreativeId],
      replyTo: ActorRef[CreativesUnassigned]
  ) extends Command

  final case class CreativesUnassigned(campaignId: CampaignId, creativeIds: Set[CreativeId])
      extends promovolve.CborSerializable

  final case class GetCampaign(replyTo: ActorRef[CampaignInfo]) extends Command

  final case class CampaignInfo(
      campaignId: CampaignId,
      status: Status,
      categories: Set[CategoryId],
      maxCpm: CPM,
      dailyBudget: Budget,
      creativeIds: Set[CreativeId],
      adProductCategory: Option[AdProductCategoryId] = None,
      landingUrl: Option[String] = None,
      bidOnUnmatchedContext: Boolean = false,
      startAt: Instant = Instant.EPOCH, // populated from State.startAt by the GetCampaign handler
      endAt: Option[Instant] = None,
      siteAllowlist: Set[String] = Set.empty,
      // Durable Gemini suggestion (fallback default). Exposed so the dashboard
      // can compute the Untargeted badge against the EFFECTIVE set: a campaign
      // is untargeted only when BOTH categories and suggestedCategories are
      // empty (and it won't accept filler).
      suggestedCategories: Set[CategoryId] = Set.empty,
      // Advertiser-provided display name; "" for legacy campaigns
      // created before the name was persisted (API falls back to id).
      name: String = ""
  ) extends promovolve.CborSerializable

  final case class UpdateStatus(status: Status, replyTo: ActorRef[StatusUpdated]) extends Command

  final case class StatusUpdated(campaignId: CampaignId, status: Status) extends promovolve.CborSerializable

  // Note: UpdatePacing removed - pacing is now always enforced at AdServer level
  // using pluggable PacingStrategy (see promovolve.pacing package)

  final case class UpdateConfig(
      maxCpm: Option[CPM],
      dailyBudget: Option[Budget],
      adProductCategory: Option[Option[AdProductCategoryId]] = None, // None = no change, Some(None) = clear, Some(Some(x)) = set
      // Advertiser-declared target content categories (IAB 3.0 ids). None = no
      // change, Some(set) = replace. Previously categories were auto-derived
      // from adProductCategory via a static IAB Content↔AdProduct mapping;
      // that derivation was removed because the mapping was sparse and made
      // most pages unbiddable. Advertisers now declare targets explicitly.
      categories: Option[Set[CategoryId]] = None,
      landingUrl: Option[Option[String]] = None, // None = no change, Some(None) = clear, Some(Some(url)) = set
      bidOnUnmatchedContext: Option[Boolean] = None, // None = no change, Some(true|false) = set
      // Schedule overrides. None = no change. Some(value) = set.
      // Some(None) on endAt clears the end (open-ended again).
      startAt: Option[Instant] = None,
      endAt: Option[Option[Instant]] = None,
      // Media targeting. None = no change, Some(set) = replace the allowlist
      // of publisher siteIds (empty set = clear → no restriction).
      siteAllowlist: Option[Set[String]] = None,
      // Display name. None = no change; Some(s) sets the trimmed value
      // (blank-after-trim is ignored — a name can't be unset to empty).
      name: Option[String] = None,
      replyTo: ActorRef[ConfigUpdated]
  ) extends Command

  final case class ConfigUpdated(campaignId: CampaignId) extends promovolve.CborSerializable

  final case class CampaignBidRequest(
      siteId: SiteId,
      url: String,
      slotId: SlotId,
      pageCategory: CategoryId,
      floorCpm: CPM,
      replyTo: ActorRef[CampaignBidResponse]
  ) extends Command

  final case class CampaignBidResponse(
      campaignId: CampaignId,
      advertiserId: AdvertiserId,
      creatives: Set[AdvertiserEntity.Creative],
      cpm: CPM, // bid price (for auction ranking)
      maxCpm: CPM = CPM.zero, // advertiser's max CPM (for ServeIndex/Thompson Sampling)
      adProductCategory: Option[AdProductCategoryId] = None,
      landingDomain: String = "",
      rejectReason: Option[BidRejectReason] = None,
      // True when the campaign has ≥1 publisher-APPROVED creative on this
      // site. Approved bids teach the floor; pending bids compete for the
      // approval queue but are invisible to floor pricing. Set on rejects
      // too (a below-floor APPROVED bidder must still widen the sweep
      // range; a below-floor pending one must not).
      hasApprovedCreative: Boolean = false
  ) extends promovolve.CborSerializable {
    def eligible: Boolean = creatives.nonEmpty
  }

  enum BidRejectReason:
    case Paused, CategoryMismatch, CategoryBlocked, SizeMismatch,
      BelowFloor, BudgetExhausted, NoCreatives, SiteNotAllowed

  /** Record spend (buffered, flushed periodically) */
  final case class RecordSpend(
      requestId: String,
      amount: Spend,
      ts: Instant,
      replyTo: ActorRef[SpendRecorded]
  ) extends Command

  final case class SpendRecorded(
      spendToday: Spend,
      remaining: Budget,
      // Budget-day number of the current window in the ADVERTISER's zone
      // (not UTC). Informational only — the sole consumer (AuctionRoutes'
      // test route) ignores it.
      epochDay: Long
  ) extends promovolve.CborSerializable

  /** Get budget info */
  final case class GetBudgetInfo(replyTo: ActorRef[BudgetInfo]) extends Command

  final case class BudgetInfo(dailyBudget: Budget, spent: Spend, remaining: Budget) extends promovolve.CborSerializable

  /** Get campaign stats including win rate data */
  final case class GetCampaignStats(replyTo: ActorRef[CampaignStats]) extends Command

  final case class CampaignStats(
      campaignId: CampaignId,
      bidsToday: Long,
      spendToday: Spend,
      dailyBudget: Budget
  ) extends promovolve.CborSerializable

  /** Get spend info for pacing decisions (used by AdServer) */
  final case class GetSpendInfo(replyTo: ActorRef[SpendInfo]) extends Command

  /** Spend info for pacing - contains data needed by PacingStrategy */
  final case class SpendInfo(
      dailyBudget: Budget,
      todaySpend: Spend,
      dayStart: Instant,
      // Advertiser account zone ("" = UTC); the budget window ends at this
      // zone's next midnight after dayStart. Defaulted for cross-node
      // compat with pre-timezone senders.
      timezone: String = ""
  ) extends promovolve.CborSerializable

  /**
   * Set the campaign's effective budget-day timezone (IANA id, "" = UTC).
   * Tell-only: pushed by AdvertiserEntity on account-zone change / campaign
   * creation, and self-healed from it on recovery + every roll tick. No-op
   * persist when unchanged.
   */
  final case class SetTimezone(timezone: String) extends Command

  /** Reset day start to current time (for testing - syncs server time with client) */
  final case class ResetDayStart(replyTo: ActorRef[DayStartReset], dayDurationSeconds: Int = 86400) extends Command

  final case class DayStartReset(campaignId: CampaignId, newDayStart: Instant) extends promovolve.CborSerializable

  /**
   * Atomic budget reservation - checks AND deducts in one message to eliminate race conditions.
   * Use this instead of HasBudget + RecordSpend for serve-time budget enforcement.
   */
  final case class TryReserve(
      requestId: String,
      amount: Spend,
      replyTo: ActorRef[ReserveResult]
  ) extends Command

  final case class Reserved(remaining: Budget) extends ReserveResult

  /**
   * Replenish campaign budget (e.g., daily reset or manual top-up).
   * Publishes CampaignBudgetReset event so AuctioneerEntity triggers re-auction.
   */
  final case class ReplenishBudget(
      newBudget: Budget,
      replyTo: ActorRef[ReplenishResult]
  ) extends Command

  final case class BudgetReplenished(campaignId: CampaignId, newBudget: Budget) extends ReplenishResult

  final case class ReplenishRejected(campaignId: CampaignId, currentSpend: Spend, requestedBudget: Budget)
      extends ReplenishResult

  /**
   * Published when campaign changes affect auction eligibility.
   *
   * `siteAllowlist` mirrors `State.siteAllowlist`: empty = bid everywhere,
   * non-empty = bid ONLY on these siteIds. Per-site AuctioneerEntities use it
   * to detect a site-narrow exclusion (`siteAllowlist.nonEmpty &&
   * !siteAllowlist.contains(thisSite)`) and evict the campaign from a site the
   * advertiser dropped. Carried on the ephemeral pub/sub event; the default
   * keeps deserialization of any older in-flight events safe.
   */
  final case class CampaignChanged(
      campaignId: CampaignId,
      categories: Set[CategoryId],
      isActive: Boolean,
      siteAllowlist: Set[String] = Set.empty,
      // The campaign's FULL new target category set (mirrors State.categories).
      // Per-site AuctioneerEntities use it to detect a TOPIC-narrow: a campaign
      // awarded on a categorized page it no longer targets is evicted from just
      // that page's slot keys (EvictCampaignFromSlots). Distinct from
      // `categories`, which on a CampaignReady-driven change carries only the
      // AFFECTED (added/removed) categories. Carried on the ephemeral pub/sub
      // event; the default keeps deserialization of any older in-flight events
      // safe.
      targetCategories: Set[CategoryId] = Set.empty,
      // True ONLY for a genuine config edit on an already-active campaign
      // (CPM/categories/creatives/allowlist changed). False for boot/recovery
      // (re)registration and directory re-tells. Consumers must not treat a
      // non-edit event as authoritative for eviction: boot registrations
      // re-fire on every entity wake, and arming eviction on them turned
      // ordinary restarts into serve-index purges (incident 2026-07-06).
      // Default keeps older in-flight events deserializable.
      configEdit: Boolean = false
  ) extends CborSerializable

  /** Persistent state */
  final case class State(
      campaignId: CampaignId,
      advertiserId: AdvertiserId,
      status: Status,
      categories: Set[CategoryId],
      categoryBlocklist: Set[CategoryId],
      maxCpm: CPM,
      dailyBudget: Budget,
      creativeAssignments: Set[CreativeId],
      spendToday: Spend,
      lastResetInstant: Instant, // Actual time when budget was last reset (for pacing)
      pendingReports: Map[FlushId, PendingReport],
      processedFilter: Array[Byte], // Bloom filter for idempotency (survives recovery)
      adProductCategory: Option[AdProductCategoryId] = None, // IAB Ad Product Taxonomy 2.0 category
      landingUrl: Option[String] = None, // Landing URL for all creatives in this campaign
      // When true, this campaign opts into filler auctions — pages
      // where Gemini found no contextual match against our demand
      // pool. Bids from filler campaigns always flow through
      // publisher approval before serving; they never auto-deliver.
      // Default false keeps existing campaigns strict-contextual.
      bidOnUnmatchedContext: Boolean = false,
      // Note: pacingEnabled removed - pacing is now always enforced at AdServer level
      //
      // Schedule. startAt defaults to creation time so legacy
      // campaigns without an explicit start are biddable immediately.
      // endAt = None = open-ended (run forever). When endAt is set
      // and now >= endAt, canBid returns false (without flipping
      // status — the housekeeping pass elsewhere flips status to
      // Ended for UI purposes).
      startAt: Instant = Instant.now(),
      endAt: Option[Instant] = None,
      // Campaign media targeting: advertiser-selected publisher siteIds
      // this campaign may bid on. Empty = no restriction (bid everywhere,
      // the contextual default). Non-empty = bid ONLY on these sites — an
      // additive filter on top of normal category matching, NOT an override
      // (topic still gates which pages on those sites it serves on).
      siteAllowlist: Set[String] = Set.empty,
      // Durable Gemini-derived default targeting (IAB 3.0 ids). Acts as a
      // FALLBACK for `categories`: when the advertiser clears all explicit
      // topics (categories empty), the campaign still targets this suggestion
      // instead of going silently untargeted. Populated by
      // RefineCategoriesFromCreative; never cleared by an empty
      // UpdateConfig(categories). Default-empty is Jackson-safe — no migration
      // needed for older persisted State.
      suggestedCategories: Set[CategoryId] = Set.empty,
      // Advertiser-provided display name. Was NEVER persisted before —
      // the API echoed the create request's name once and then returned
      // the campaign id as the "name" on every read, which is why ULIDs
      // leaked all over the dashboard. Default-empty is Jackson-safe;
      // legacy campaigns read back "" and the API falls back to the id.
      name: String = "",
      // Advertiser account timezone (IANA id); "" = UTC. Budget days roll at
      // this zone's midnight. Mirrored from AdvertiserEntity (fan-out on
      // change + periodic self-heal); plain String so Jackson recovery of
      // pre-timezone snapshots defaults cleanly. FlushIds stay UTC-based.
      timezone: String = ""
  ) extends CborSerializable {

    /**
     * Effective targeting set used for eligibility/registration/eviction.
     * Advertiser's explicit `categories` win when present; otherwise the
     * durable Gemini suggestion is the fallback so an empty explicit set
     * does not silently untarget the campaign. `categories` itself keeps
     * its own meaning/visibility unchanged.
     */
    def effectiveCategories: Set[CategoryId] =
      if (categories.nonEmpty) categories else suggestedCategories

    /**
     * True when `now` is within the campaign's [startAt, endAt) window.
     * Open-ended endAt = always after start. Used both for canBid
     * eligibility and for deriving the dog-ear pin TTL ceiling.
     */
    def isWithinSchedule(now: Instant): Boolean =
      !now.isBefore(startAt) && endAt.forall(now.isBefore(_))

    /**
     * Deterministic campaign eligibility checks (no probabilistic budget throttling).
     * Fluid creatives render at any slot dimension, so there's no
     * size gate here — the auctioneer's slot fan-out picks which
     * categories' campaigns to ask, and the creative-level isEligibleFor
     * handles per-creative active/rejection state.
     *
     * Site-domain blocklist is enforced at AdServer via DData; not checked here.
     */
    def canBid(
        siteId: SiteId,
        pageCategory: CategoryId,
        floorCpm: CPM
    ): Boolean = {
      // Filler auctions carry a sentinel category; campaigns opted
      // into `bidOnUnmatchedContext` bypass the normal category
      // containment check for these. They're still subject to every
      // other eligibility rule (status, CPM floor).
      val categoryOk = (pageCategory == CategoryId.Filler && bidOnUnmatchedContext) ||
        effectiveCategories.contains(pageCategory)
      // Media targeting: empty allowlist = bid anywhere; otherwise only the
      // listed sites. Purely a "where" filter on top of category matching.
      val siteOk = siteAllowlist.isEmpty || siteAllowlist.contains(siteId.value)
      status == Status.Active &&
      isWithinSchedule(Instant.now()) &&
      siteOk &&
      categoryOk &&
      !categoryBlocklist.contains(pageCategory) &&
      maxCpm >= floorCpm
    }

    def bidRejectReason(
        siteId: SiteId,
        pageCategory: CategoryId,
        floorCpm: CPM
    ): Option[BidRejectReason] = {
      val isFillerOptIn = pageCategory == CategoryId.Filler && bidOnUnmatchedContext
      if (status != Status.Active) Some(BidRejectReason.Paused)
      else if (siteAllowlist.nonEmpty && !siteAllowlist.contains(siteId.value)) Some(BidRejectReason.SiteNotAllowed)
      else if (!isFillerOptIn && !effectiveCategories.contains(pageCategory)) Some(BidRejectReason.CategoryMismatch)
      else if (categoryBlocklist.contains(pageCategory)) Some(BidRejectReason.CategoryBlocked)
      else if (maxCpm < floorCpm) Some(BidRejectReason.BelowFloor)
      else None
    }

    def assignCreatives(creativeIds: Set[CreativeId]): State =
      copy(creativeAssignments = creativeAssignments ++ creativeIds)

    def unassignCreatives(creativeIds: Set[CreativeId]): State =
      copy(creativeAssignments = creativeAssignments -- creativeIds)
  }

  /** Persisted marker for spend reports that must be delivered at-least-once to AdvertiserEntity. */
  final case class PendingReport(amount: Spend, ts: Instant, retryCount: Int)
      extends CborSerializable

  enum Status {
    case Active, Paused, Ended, Deleted
  }

  // ----- Internal commands -----
  private case class FlushBuffer(ts: Instant) extends Internal

  private case class BidRequest(
      siteId: SiteId,
      pageCategory: CategoryId,
      floorCpm: CPM,
      replyTo: ActorRef[CampaignBidResponse],
      advRemaining: Budget,
      creatives: Set[AdvertiserEntity.Creative]
  ) extends Internal

  private case class SpendReportSuccess(recorded: CampaignSpendRecorded, flushId: FlushId)
      extends SpendReportResult

  private case class SpendReportFailure(
      flushId: FlushId,
      amount: Spend,
      ts: Instant,
      retryCount: Int,
      error: String
  ) extends SpendReportResult

  private case class SpendReportRetry(
      flushId: FlushId,
      amount: Spend,
      ts: Instant,
      retryCount: Int
  ) extends Internal

  /**
   * Internal: Acknowledgment from CampaignDirectory that registration completed
   * (demand propagated to the category bidders). Flips the campaign biddable.
   */
  private case class DirectoryRegistered(campaignId: CampaignId) extends Internal

  /** Self-message: re-tell CampaignReady while still awaiting the ack. */
  private case object RetryDirectoryRegistration extends Internal

  /** Internal: result of a best-effort category_demand table write. */
  private case class DemandTableResult(error: Option[String]) extends Internal

  case object InsufficientBudget extends ReserveResult

  case object Throttled extends ReserveResult // Pacing rejection (has budget, but pacing)

  case object Stop extends Command

  object State {
    def empty(campaignId: CampaignId, advertiserId: AdvertiserId): State = State(
      campaignId = campaignId,
      advertiserId = advertiserId,
      status = Status.Paused,
      categories = Set.empty,
      categoryBlocklist = Set.empty,
      maxCpm = CPM(5.0),
      dailyBudget = Budget(100.0),
      creativeAssignments = Set.empty,
      spendToday = Spend.zero,
      lastResetInstant = Instant.now(),
      pendingReports = Map.empty,
      processedFilter = Array.emptyByteArray
    )
  }

  private object SpendBuffer {
    val empty: SpendBuffer = SpendBuffer(Spend.zero, 0)
  }

  private object EphemeralState {
    // Bloom filter sizing for idempotency
    private val FilterCapacity = 50000 // Expected unique requestIds per day
    private val FilterFpp = 0.0001 // 0.01% false positive rate (lower than before for fewer false skips)

    def empty: EphemeralState =
      EphemeralState(SpendBuffer.empty, 0L, createFilter())

    def fromPersistedFilter(filter: Array[Byte]): EphemeralState = {
      val bf =
        if (filter.nonEmpty)
          scala.util.Try(BloomFilter.deserialize(filter)).getOrElse(createFilter())
        else
          createFilter()
      EphemeralState(SpendBuffer.empty, 0L, bf)
    }

    def createFilter(): BloomFilter = BloomFilter.create(FilterCapacity, FilterFpp)
  }

  private case object FlushTick extends Internal

  private case object CheckWindowRoll extends Internal

}
