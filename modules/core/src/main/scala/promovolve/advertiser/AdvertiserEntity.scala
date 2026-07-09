package promovolve.advertiser

import jkugiya.ulid.*
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.cluster.ddata.typed.scaladsl.{ DistributedData, Replicator }
import org.apache.pekko.cluster.ddata.{ LWWMap, LWWMapKey, SelfUniqueAddress }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityTypeKey }
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.RecoveryCompleted
import org.apache.pekko.persistence.typed.state.scaladsl.{ DurableStateBehavior, Effect }
import promovolve.*
import promovolve.common.{ addRejected, mightContain, remove }

import java.time.{ Instant, LocalDate, ZoneOffset }

/**
 * Sharded entity representing an advertiser account.
 *
 * An advertiser:
 * - Owns one or more campaigns
 * - Has account-level site blacklist (applies to all campaigns)
 * - Manages billing/payment (future)
 *
 * Uses DurableStateBehavior because:
 * - Simple state with no need for event replay history
 * - No downstream event consumers
 * - Direct state persistence is simpler and more efficient
 *
 * Hierarchy: Advertiser → Campaign(s) → Creative(s)
 */
object AdvertiserEntity {

  /** Type alias for DData update responses */
  type DDataUpdateResponse =
    Replicator.UpdateResponse[LWWMap[AdvertiserId, CachedSiteDomainBlocklist]]

  val TypeKey: EntityTypeKey[Command | DDataUpdateResponse] =
    EntityTypeKey[Command | DDataUpdateResponse]("advertiser-entity")

  /**
   * DData key for advertiser-side site-domain blocklists. AdServer subscribes to this map
   * and filters bids whose serving site domain appears in the candidate advertiser's set.
   * Keyed by advertiserId — single entry per advertiser.
   */
  val DomainBlocklistCacheKey: LWWMapKey[AdvertiserId, CachedSiteDomainBlocklist] =
    LWWMapKey[AdvertiserId, CachedSiteDomainBlocklist]("advertiser-site-blocklist")

  /** Max number of flush IDs to retain for idempotency (prevents unbounded growth) */
  private val MaxProcessedFlushIds = 1000

  private val MaxDDataRetries = 3

  /**
   * Permissive hostname check applied before persisting a blocklist entry.
   * Accepts bare lowercased hostnames (lookup form): letters/digits/dot/hyphen,
   * no leading/trailing dot or hyphen, no consecutive dots, ≤253 chars. Permits
   * single-label hosts (e.g. "localhost") for dev parity. Caller is expected to
   * have already lowercased + trimmed.
   */
  private[advertiser] def isValidDomain(d: String): Boolean =
    d.nonEmpty &&
    d.length <= 253 &&
    d.forall(c =>
      (c >= 'a' && c <= 'z') ||
      (c >= '0' && c <= '9') || c == '-' || c == '.'
    ) &&
    !d.startsWith(".") && !d.endsWith(".") &&
    !d.startsWith("-") && !d.endsWith("-") &&
    !d.contains("..")

  /**
   * Normalize + validate a set of domain inputs. Lowercase, trim, drop empties
   * and anything that fails [[isValidDomain]]. Invalid entries are silently
   * filtered; the command response then reports the set that was actually
   * persisted (mirrors the existing add/remove semantics).
   */
  private[advertiser] def normalizeDomains(input: Set[String]): Set[String] =
    input.iterator.map(_.toLowerCase.trim).filter(isValidDomain).toSet

  // ---------- Behavior ----------
  def apply(
      advertiserId: AdvertiserId,
      sharding: ClusterSharding,
      budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]]
  )(using system: ActorSystem[?]): Behavior[Command | DDataUpdateResponse] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        given node: SelfUniqueAddress = DistributedData(system).selfUniqueAddress
        val replicator = DistributedData(system).replicator
        val retryTimerKey = "ddata-retry"

        def syncToDData(state: State): Unit = {
          val cached = CachedSiteDomainBlocklist(state.siteDomainBlocklist)
          replicator ! Replicator.Update(
            DomainBlocklistCacheKey,
            LWWMap.empty[AdvertiserId, CachedSiteDomainBlocklist],
            Replicator.WriteLocal,
            ctx.self
          )(_.put(node, advertiserId, cached))
        }

        def cancelRetryTimer(): Unit = timers.cancel(retryTimerKey)

        def scheduleRetry(attempt: Int): Unit = {
          import scala.concurrent.duration.*
          val delay = (1 << attempt).seconds
          timers.startSingleTimer(retryTimerKey, RetryDDataSync(attempt), delay)
        }

        DurableStateBehavior[Command | DDataUpdateResponse, State](
          persistenceId = PersistenceId.ofUniqueId(s"advertiser-$advertiserId"),
          emptyState = State.empty(advertiserId),
          commandHandler = (state, command) =>
            handleCommand(state, command, sharding, budgetEventTopic, syncToDData, cancelRetryTimer, scheduleRetry, ctx)
        ).receiveSignal { case (state, RecoveryCompleted) =>
          ctx.log.info(
            "AdvertiserEntity[{}] recovered: campaigns={}, blocklist={}",
            advertiserId,
            state.campaignIds.size,
            state.siteDomainBlocklist.size
          )
          // Keep DData in sync after recovery
          syncToDData(state)
        }
      }
    }

  private def handleCommand(
      state: State,
      command: Command | DDataUpdateResponse,
      sharding: ClusterSharding,
      budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
      syncToDData: State => Unit,
      cancelRetryTimer: () => Unit,
      scheduleRetry: Int => Unit,
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): Effect[State] = command match {
    case cmd: Command =>
      val handlers: PartialFunction[Command, Effect[State]] =
        campaignManagement(state, sharding, ctx).orElse(
          siteBlocklist(state, syncToDData)).orElse(
          budgetAndSpend(state, budgetEventTopic, ctx)).orElse(
          advertiserInfo(state, budgetEventTopic, ctx)).orElse(
          creativeManagement(state, budgetEventTopic, ctx)).orElse(
          ddataRetry(state, syncToDData, scheduleRetry, ctx))
      handlers(cmd)

    case response: Replicator.UpdateResponse[?] =>
      response match {
        case Replicator.UpdateSuccess(key) =>
          ctx.log.debug(
            "DData update success: advertiser={} key={}",
            state.advertiserId.value, key.id
          )
          cancelRetryTimer()
          Effect.none

        case Replicator.UpdateTimeout(key) =>
          handleDDataFailure(state, key.id, "timeout", scheduleRetry, ctx)

        case f: Replicator.ModifyFailure[?] =>
          handleDDataFailure(state, f.key.id, s"modify failure: ${f.errorMessage}", scheduleRetry, ctx)

        case Replicator.StoreFailure(key) =>
          handleDDataFailure(state, key.id, "store failure", scheduleRetry, ctx)

        case _ =>
          Effect.none
      }
  }

  private def handleDDataFailure(
      state: State,
      key: String,
      error: String,
      scheduleRetry: Int => Unit,
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): Effect[State] = {
    ctx.log.warn(
      "DData update failed for advertiser {} key={} error={}, scheduling retry 1/{}",
      state.advertiserId.value,
      key,
      error,
      MaxDDataRetries
    )
    scheduleRetry(1)
    Effect.none
  }

  private def ddataRetry(
      state: State,
      syncToDData: State => Unit,
      scheduleRetry: Int => Unit,
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): PartialFunction[Command, Effect[State]] = {
    case RetryDDataSync(attempt) =>
      if (attempt > MaxDDataRetries) {
        ctx.log.error(
          "DData sync exhausted all {} retries for advertiser {}",
          MaxDDataRetries,
          state.advertiserId.value
        )
      } else {
        ctx.log.info(
          "Retrying DData sync for advertiser {} (attempt {}/{})",
          state.advertiserId.value,
          attempt,
          MaxDDataRetries
        )
        syncToDData(state)
        scheduleRetry(attempt + 1)
      }
      Effect.none
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CAMPAIGN MANAGEMENT
  // Create and remove campaigns for this advertiser
  // ═══════════════════════════════════════════════════════════════════════════

  private def campaignManagement(
      state: State,
      sharding: ClusterSharding,
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): PartialFunction[Command, Effect[State]] = {
    case CreateCampaign(replyTo) =>
      val campaignId = CampaignId(ULID.getGenerator().base32())
      ctx.log.info(
        "Campaign {} created for advertiser {}",
        campaignId,
        state.advertiserId
      )
      Effect
        .persist(state.addCampaign(campaignId))
        .thenReply(replyTo)(_ => CampaignCreated(state.advertiserId, campaignId))

    case RemoveCampaign(campaignId, replyTo) =>
      if (state.campaignIds.contains(campaignId))
        Effect
          .persist(state.removeCampaign(campaignId))
          .thenReply(replyTo)(state => CampaignRemoved(state.advertiserId, campaignId))
      else Effect.none.thenReply(replyTo)(_ => CampaignRemoved(state.advertiserId, campaignId))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SITE BLOCKLIST
  // Manage advertiser-level site-domain blocklist (applies to all campaigns).
  // Filter is enforced at AdServer serve time via DData; no per-bid check.
  // ═══════════════════════════════════════════════════════════════════════════

  private def siteBlocklist(
      state: State,
      syncToDData: State => Unit
  ): PartialFunction[Command, Effect[State]] = {
    case BlockDomains(domains, replyTo) =>
      val newDomains = normalizeDomains(domains) -- state.siteDomainBlocklist
      if (newDomains.nonEmpty)
        Effect
          .persist(state.blockDomains(newDomains))
          .thenRun(syncToDData)
          .thenReply(replyTo)(state => DomainsBlocked(state.advertiserId, newDomains))
      else Effect.none.thenReply(replyTo)(state => DomainsBlocked(state.advertiserId, Set.empty))

    case UnblockDomains(domains, replyTo) =>
      // Unblock skips the syntax check — if a bad domain somehow ended up in state
      // (older code path, manual ops), the user should still be able to remove it.
      val normalized = domains.iterator.map(_.toLowerCase.trim).filter(_.nonEmpty).toSet
      val toRemove = normalized.intersect(state.siteDomainBlocklist)
      if (toRemove.nonEmpty)
        Effect
          .persist(state.unblockDomains(toRemove))
          .thenRun(syncToDData)
          .thenReply(replyTo)(state => DomainsUnblocked(state.advertiserId, toRemove))
      else Effect.none.thenReply(replyTo)(state => DomainsUnblocked(state.advertiserId, Set.empty))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // BUDGET AND SPEND
  // Track daily budget, record spend, handle day rollover
  // ═══════════════════════════════════════════════════════════════════════════

  private def budgetAndSpend(
      state: State,
      budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): PartialFunction[Command, Effect[State]] = {
    case UpdateDailyBudget(newBudget, replyTo) =>
      val wasWithin = state.withinBudget
      val newState = state.withDailyBudget(newBudget)
      val nowWithin = newState.withinBudget

      Effect.persist(newState).thenRun { _ =>
        val ts = Instant.now()
        if (wasWithin && !nowWithin) {
          // Budget lowered below spend → stop serving
          budgetEventTopic ! Topic.Publish(
            promovolve.AdvertiserBudgetExhausted(state.advertiserId, ts)
          )
          ctx.log.info("Budget reduced below spend for advertiser {}, publishing exhaustion", state.advertiserId)
        } else if (!wasWithin && nowWithin) {
          // Budget raised above spend → can serve again
          budgetEventTopic ! Topic.Publish(
            promovolve.AdvertiserBudgetReset(state.advertiserId, newBudget, ts)
          )
          ctx.log.info("Budget increased above spend for advertiser {}, publishing reset", state.advertiserId)
        }
      }.thenReply(replyTo)(_ => DailyBudgetUpdated(state.advertiserId, newBudget))

    case RecordCampaignSpend(flushId, campaignId, amount, ts, replyTo) =>
      // Idempotency check for at-least-once delivery from CampaignEntity.
      // Duplicates can occur when:
      //   - Response was lost and CampaignEntity retried
      //   - This entity crashed after persist but before reply
      //   - Network delivered same message twice
      // We return the current state (safe because spend was already applied).
      if (state.processedFlushIds.contains(flushId)) {
        ctx.log.debug("Duplicate flush ignored: {}", flushId)
        Effect.none.thenReply(replyTo)(state =>
          CampaignSpendRecorded(state.advertiserId, campaignId, state.spendToday, state.remaining)
        )
      } else {
        // Check if daily window needs rolling
        val today = LocalDate.ofInstant(ts, ZoneOffset.UTC).toEpochDay
        val needsRoll = state.lastResetEpochDay != today
        val wasExhausted = !state.withinBudget
        val rolledState = if (needsRoll) state.rollWindow(today) else state

        // Record spend and track flushId for idempotency
        val wasWithin = rolledState.withinBudget
        val newState = rolledState.addSpend(amount).trackFlush(flushId)
        val nowWithin = newState.withinBudget

        Effect
          .persist(newState)
          .thenRun { persistedState =>
            // Publish budget reset event if window rolled and was exhausted
            if (needsRoll && wasExhausted) {
              budgetEventTopic ! Topic.Publish(
                promovolve.AdvertiserBudgetReset(state.advertiserId, persistedState.dailyBudget, ts)
              )
              ctx.log.info(
                "Published AdvertiserBudgetReset event for advertiser {}",
                state.advertiserId
              )
            }
            // Detect budget exhaustion
            if (wasWithin && !nowWithin) {
              budgetEventTopic ! Topic.Publish(
                promovolve.AdvertiserBudgetExhausted(state.advertiserId, ts)
              )
              ctx.log.warn("Budget exhausted for advertiser {}", state.advertiserId)
            }
          }
          .thenReply(replyTo)(state =>
            CampaignSpendRecorded(
              state.advertiserId,
              campaignId,
              state.spendToday,
              state.remaining
            )
          )
      }

    case GetBudgetStatus(replyTo) =>
      Effect.none.thenReply(replyTo)(state =>
        AdvertiserBudgetStatus(
          state.advertiserId,
          state.dailyBudget,
          state.spendToday,
          state.remaining,
          // A suspended/closed advertiser is "not within budget" for the
          // serve path: reservation gates on this flag, and without it
          // suspension would rest entirely on the one-shot (at-most-once)
          // AdvertiserSuspended purge. Folding status in here closes that
          // gap without changing the reply's wire shape.
          state.withinBudget && state.status == Status.Active,
          state.lastResetEpochDay
        )
      )

    case ResetDayStart(replyTo, silent) =>
      // Reset daily spend for simulated day rollover (testing)
      val today = LocalDate.now(ZoneOffset.UTC).toEpochDay
      ctx.log.info(
        "ResetDayStart: advertiser={} oldSpend={} oldEpochDay={} newEpochDay={} silent={}",
        state.advertiserId.value,
        state.spendToday.value,
        state.lastResetEpochDay,
        today,
        silent
      )
      val newState = state.rollWindow(today)
      // Publish budget reset event so AdServer knows budget is available
      // Skip publishing when silent=true (used by PacingConfigUpdated to avoid re-auctions)
      if (!silent) {
        budgetEventTopic ! Topic.Publish(
          promovolve.AdvertiserBudgetReset(state.advertiserId, newState.dailyBudget, Instant.now())
        )
      }
      Effect
        .persist(newState)
        .thenReply(replyTo)(_ => DayStartReset(state.advertiserId, today))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ADVERTISER INFO
  // Get/update advertiser metadata and bid context
  // ═══════════════════════════════════════════════════════════════════════════

  private def advertiserInfo(
      state: State,
      budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): PartialFunction[Command, Effect[State]] = {
    case GetBidContext(creativeIds, replyTo) =>
      // Use day-aware budget check to handle race condition where AdvertiserBudgetReset
      // triggers re-auction before AdvertiserEntity processes its own ResetDayStart.
      // Site-domain blocklist is enforced at AdServer (via DData), not at bid time.
      // A non-Active advertiser bids with zero budget: the AdvertiserSuspended
      // event only purges the ServeIndex once, so without this gate the
      // advertiser's campaigns would re-enter at the next periodic re-auction.
      Effect.none.thenReply(replyTo)(state =>
        BidContext(
          remaining =
            if (state.status == Status.Active) state.remainingForBidding else Budget.zero,
          creatives = state.getCreatives(creativeIds)
        )
      )

    case GetAdvertiserInfo(replyTo) =>
      Effect.none.thenReply(replyTo)(_.toInfo)

    case UpdateName(name, replyTo) =>
      Effect
        .persist(state.withName(name))
        .thenReply(replyTo)(state => NameUpdated(state.advertiserId, name))

    case UpdateStatus(status, replyTo) =>
      val wasActive = state.status == Status.Active
      val nowSuspended = status == Status.Suspended || status == Status.Closed
      val resumed = !wasActive && status == Status.Active

      Effect.persist(state.withStatus(status)).thenRun { newState =>
        // Publish whenever the TARGET status is suspended/closed, not only
        // on the Active→Suspended transition: the topic is at-most-once, so
        // if the original publish was lost (crash between persist and
        // publish, no live subscriber), a platform retry of /suspend must
        // republish. Downstream removal is idempotent.
        if (nowSuspended) {
          val ts = Instant.now()
          budgetEventTopic ! Topic.Publish(
            promovolve.AdvertiserSuspended(state.advertiserId, ts)
          )
          ctx.log.info(
            "Advertiser {} status changed to {}, publishing suspension event",
            state.advertiserId,
            status
          )
        }
        // Re-admission reuses AdvertiserBudgetReset: AuctioneerEntity reacts
        // with a full site re-auction in which the advertiser participates,
        // and GetBidContext returns real budget again now that status is
        // Active. No new cross-node event type needed.
        if (resumed) {
          budgetEventTopic ! Topic.Publish(
            promovolve.AdvertiserBudgetReset(
              state.advertiserId,
              newState.dailyBudget,
              Instant.now()
            )
          )
          ctx.log.info(
            "Advertiser {} resumed to Active, publishing budget reset to trigger re-auction",
            state.advertiserId
          )
        }
      }.thenReply(replyTo)(_ => StatusUpdated(state.advertiserId, status))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CREATIVE MANAGEMENT
  // Add/remove creatives, approval status, frequency capping
  // ═══════════════════════════════════════════════════════════════════════════

  private def creativeManagement(
      state: State,
      budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): PartialFunction[Command, Effect[State]] = {
    case AddCreative(creative, replyTo) =>
      Effect
        .persist(state.addCreative(creative))
        .thenReply(replyTo)(_ => CreativeAdded(state.advertiserId, creative.id))

    case RemoveCreative(creativeId, replyTo) =>
      Effect
        .persist(state.removeCreative(creativeId))
        .thenReply(replyTo)(_ => CreativeRemoved(state.advertiserId, creativeId))

    case UpdateCreativeApproval(creativeId, siteId, approvalStatus, replyTo) =>
      val updated = state.updateCreativeApproval(creativeId, siteId, approvalStatus)
      // No-op guard: AdServer re-announces every persisted approval on boot
      // (backfill for Creative.approvedSites) — skip the journal write when
      // the state wouldn't change so restarts don't churn the event log.
      // `eq` (not ==): the helper returns `this` on a no-op, and value
      // equality is unreliable here (Creative.rejectedSites is an Array).
      if (updated eq state)
        Effect.none.thenReply(replyTo)(_ => CreativeApprovalUpdated(state.advertiserId, creativeId, siteId))
      else
        Effect
          .persist(updated)
          .thenReply(replyTo)(_ => CreativeApprovalUpdated(state.advertiserId, creativeId, siteId))

    case GetCreatives(replyTo) =>
      Effect.none.thenReply(replyTo)(state =>
        CreativesResponse(state.advertiserId, state.creatives.values.toSet)
      )

    case UpdateCreativeActiveStatus(creativeId, campaignId, isActive, replyTo) =>
      state.creatives.get(creativeId) match {
        case Some(creative) =>
          val updated = creative.withActiveStatus(isActive)
          Effect
            .persist(state.copy(creatives = state.creatives.updated(creativeId, updated)))
            .thenRun { _ =>
              val ts = Instant.now()
              budgetEventTopic ! Topic.Publish(
                promovolve.CreativeStatusChanged(creativeId, state.advertiserId, campaignId, isActive, ts)
              )
              ctx.log.info(
                "Creative {} {} for campaign {}, publishing status change",
                creativeId.value,
                if (isActive) "reactivated" else "paused",
                campaignId.value
              )
            }
            .thenReply(replyTo)(_ => CreativeActiveStatusUpdated(state.advertiserId, creativeId, isActive))
        case None =>
          // Creative not found - still acknowledge to avoid blocking caller
          Effect.none.thenReply(replyTo)(_ => CreativeActiveStatusUpdated(state.advertiserId, creativeId, isActive))
      }

    case UpdateCreativeUnrejection(creativeId, siteId, replyTo) =>
      state.creatives.get(creativeId) match {
        case Some(creative) =>
          val updated = creative.withUnrejection(siteId)
          Effect
            .persist(state.copy(creatives = state.creatives.updated(creativeId, updated)))
            .thenReply(replyTo)(_ => CreativeUnrejectionUpdated(state.advertiserId, creativeId, siteId))
        case None =>
          // Creative not found - still acknowledge
          Effect.none.thenReply(replyTo)(_ => CreativeUnrejectionUpdated(state.advertiserId, creativeId, siteId))
      }

    case RevokeCreativeApproval(creativeId, siteId, replyTo) =>
      // Revoke = soft undo, back to PENDING. Clears approvedSites (the bid
      // stops teaching the floor — floors read approved demand only) but
      // deliberately does NOT block bidding: AdServer's revoke removes the
      // creative from serving AND pending selections, so re-winning an
      // auction is its only way back into the approval queue. Reject/flag
      // is the bidding block.
      state.creatives.get(creativeId) match {
        case Some(creative) =>
          val updated = creative.withRevocation(siteId)
          Effect
            .persist(state.copy(creatives = state.creatives.updated(creativeId, updated)))
            .thenReply(replyTo)(_ => CreativeApprovalRevoked(state.advertiserId, creativeId, siteId))
        case None =>
          // Creative not found - still acknowledge to avoid blocking caller.
          Effect.none.thenReply(replyTo)(_ => CreativeApprovalRevoked(state.advertiserId, creativeId, siteId))
      }
  }

  // ---------- Protocol ----------
  sealed trait Command extends promovolve.CborSerializable

  sealed trait CreateCampaignResult extends promovolve.CborSerializable

  /** Create a new campaign for this advertiser (generates unique CampaignId internally) */
  case class CreateCampaign(
      replyTo: ActorRef[CreateCampaignResult]
  ) extends Command

  // NB: deliberately does NOT carry the campaign's EntityRef — an EntityRef
  // is not Jackson-serializable, so including it breaks this reply when the
  // ask crosses cluster nodes. Callers resolve the ref from (advertiserId,
  // campaignId) via sharding instead.
  case class CampaignCreated(
      advertiserId: AdvertiserId,
      campaignId: CampaignId
  ) extends CreateCampaignResult

  /** Remove a campaign from this advertiser */
  case class RemoveCampaign(
      campaignId: CampaignId,
      replyTo: ActorRef[CampaignRemoved]
  ) extends Command

  case class CampaignRemoved(advertiserId: AdvertiserId, campaignId: CampaignId) extends promovolve.CborSerializable

  /** Add site domains to blocklist (advertiser blocks ads from serving on these site domains) */
  case class BlockDomains(
      domains: Set[String],
      replyTo: ActorRef[DomainsBlocked]
  ) extends Command

  case class DomainsBlocked(advertiserId: AdvertiserId, domains: Set[String]) extends promovolve.CborSerializable

  /** Remove site domains from blocklist */
  case class UnblockDomains(
      domains: Set[String],
      replyTo: ActorRef[DomainsUnblocked]
  ) extends Command

  case class DomainsUnblocked(advertiserId: AdvertiserId, domains: Set[String]) extends promovolve.CborSerializable

  /** Get advertiser info */
  case class GetAdvertiserInfo(replyTo: ActorRef[AdvertiserInfo]) extends Command

  case class AdvertiserInfo(
      advertiserId: AdvertiserId,
      name: Name,
      status: Status,
      campaignIds: Set[CampaignId],
      siteDomainBlocklist: Set[String]
  ) extends promovolve.CborSerializable

  /** Update advertiser name */
  case class UpdateName(name: Name, replyTo: ActorRef[NameUpdated]) extends Command

  case class NameUpdated(advertiserId: AdvertiserId, name: Name) extends promovolve.CborSerializable

  /** Update advertiser status */
  case class UpdateStatus(status: Status, replyTo: ActorRef[StatusUpdated]) extends Command

  case class StatusUpdated(advertiserId: AdvertiserId, status: Status) extends promovolve.CborSerializable

  /** Update advertiser daily budget */
  case class UpdateDailyBudget(newBudget: Budget, replyTo: ActorRef[DailyBudgetUpdated])
      extends Command

  case class DailyBudgetUpdated(advertiserId: AdvertiserId, newBudget: Budget) extends promovolve.CborSerializable

  // ---------- Budget Commands ----------

  /** Get current budget status */
  case class GetBudgetStatus(replyTo: ActorRef[AdvertiserBudgetStatus]) extends Command

  case class AdvertiserBudgetStatus(
      advertiserId: AdvertiserId,
      dailyBudget: Budget,
      spendToday: Spend,
      remaining: Budget,
      withinBudget: Boolean,
      lastResetEpochDay: Long
  ) extends promovolve.CborSerializable

  /**
   * Reset day start for testing (simulated day rollover)
   * @param silent if true, don't publish AdvertiserBudgetReset (used by PacingConfigUpdated to avoid re-auctions)
   */
  case class ResetDayStart(replyTo: ActorRef[DayStartReset], silent: Boolean = false) extends Command

  case class DayStartReset(advertiserId: AdvertiserId, newEpochDay: Long) extends promovolve.CborSerializable

  /** Get bid context (blocklist + budget + creatives) for campaign eligibility checks */
  case class GetBidContext(
      creativeIds: Set[CreativeId],
      replyTo: ActorRef[BidContext]
  ) extends Command

  case class BidContext(
      remaining: Budget,
      creatives: Set[Creative]
  ) extends promovolve.CborSerializable

  /** Add or update a creative */
  case class AddCreative(creative: Creative, replyTo: ActorRef[CreativeAdded]) extends Command

  case class CreativeAdded(advertiserId: AdvertiserId, creativeId: CreativeId) extends promovolve.CborSerializable

  /** Update creative approval status (called by publisher) */
  case class UpdateCreativeApproval(
      creativeId: CreativeId,
      siteId: SiteId,
      status: publisher.ApprovalStatus,
      replyTo: ActorRef[CreativeApprovalUpdated]
  ) extends Command

  case class CreativeApprovalUpdated(
      advertiserId: AdvertiserId,
      creativeId: CreativeId,
      siteId: SiteId
  ) extends promovolve.CborSerializable

  /** Get all creatives for this advertiser */
  case class GetCreatives(replyTo: ActorRef[CreativesResponse]) extends Command

  case class CreativesResponse(advertiserId: AdvertiserId, creatives: Set[Creative]) extends promovolve.CborSerializable

  /**
   * Update creative active/paused status (for auction eligibility).
   * Publishes CreativeStatusChanged event to notify AuctioneerEntity/AdServer.
   */
  case class UpdateCreativeActiveStatus(
      creativeId: CreativeId,
      campaignId: CampaignId,
      isActive: Boolean,
      replyTo: ActorRef[CreativeActiveStatusUpdated]
  ) extends Command

  case class CreativeActiveStatusUpdated(
      advertiserId: AdvertiserId,
      creativeId: CreativeId,
      isActive: Boolean
  ) extends promovolve.CborSerializable

  /** Un-reject a creative for a site (remove from rejectedSites filter) */
  case class UpdateCreativeUnrejection(
      creativeId: CreativeId,
      siteId: SiteId,
      replyTo: ActorRef[CreativeUnrejectionUpdated]
  ) extends Command

  case class CreativeUnrejectionUpdated(
      advertiserId: AdvertiserId,
      creativeId: CreativeId,
      siteId: SiteId
  ) extends promovolve.CborSerializable

  /**
   * Revoke creative approval for a site (removes from BOTH approved and rejected filters).
   * Creative returns to "undecided" state and can go back to pending queue.
   */
  case class RevokeCreativeApproval(
      creativeId: CreativeId,
      siteId: SiteId,
      replyTo: ActorRef[CreativeApprovalRevoked]
  ) extends Command

  case class CreativeApprovalRevoked(
      advertiserId: AdvertiserId,
      creativeId: CreativeId,
      siteId: SiteId
  ) extends promovolve.CborSerializable

  // ---------- Creative Commands ----------

  // ---------- Status ----------
  enum Status {
    case Active, Suspended, Closed
  }

  // ---------- Creative ----------
  // Note: The SERVE gate stays in ServeIndex/persistedApprovedIds (AdServer).
  // `approvedSites` mirrors those approvals here so the BID path can tell
  // approved demand from pending demand: only approved creatives' bids teach
  // the publisher's floor (a pending creative bids — that's how it enters the
  // approval queue — but its bid must not peg a floor nothing can fulfil).
  // Kept in sync by UpdateCreativeApproval (approve/reject) and backfilled at
  // AdServer boot from the approved_creative table.
  case class Creative(
      id: CreativeId,
      rejectedSites: Array[Byte] = Array.empty,
      isActive: Boolean = true, // Active/Paused status for auction eligibility
      approvedSites: Set[String] = Set.empty // siteIds where the publisher approved this creative
  ) extends CborSerializable {

    /** Check if creative is new for a site (not rejected). */
    def isNewFor(siteId: SiteId): Boolean =
      !isRejectedFor(siteId)

    private def isRejectedFor(siteId: SiteId): Boolean =
      if (rejectedSites.isEmpty) false
      else rejectedSites.mightContain(siteId)

    /**
     * Check if creative can participate in auction.
     * Must be active AND not rejected by the site. Fluid creatives
     * render at any slot dimension, so there's no per-creative size
     * gate; slot-size policy is a publisher-side concern handled by
     * the auctioneer's slot fan-out.
     *
     * NOTE: pending (not-yet-approved) creatives ARE eligible — winning
     * an auction is how they reach the publisher's approval queue. See
     * `isApprovedFor` for the approved-demand distinction.
     */
    def isEligibleFor(siteId: SiteId): Boolean =
      isActive && !isRejectedFor(siteId)

    /**
     * Publisher-approved (servable) on this site. Distinct from
     * `isEligibleFor`: eligible-but-not-approved = pending demand, which
     * bids into auctions but must not teach the site's floor.
     */
    def isApprovedFor(siteId: SiteId): Boolean =
      approvedSites.contains(siteId.value)

    def withApproval(siteId: SiteId, status: publisher.ApprovalStatus): Creative =
      status match {
        case publisher.ApprovalStatus.Approved =>
          // Remove from rejected if previously rejected (reversal) and
          // record the approval so the bid path counts this as approved
          // (floor-teaching) demand.
          copy(
            rejectedSites = rejectedSites.remove(siteId),
            approvedSites = approvedSites + siteId.value
          )
        case publisher.ApprovalStatus.Rejected =>
          // Add to rejected filter to block from future auctions; a
          // rejected creative is by definition no longer approved.
          copy(
            rejectedSites = rejectedSites.addRejected(siteId),
            approvedSites = approvedSites - siteId.value
          )
      }

    /**
     * Remove rejection for a site (un-reject). Creative can enter auctions
     * again — as PENDING demand: un-flag does NOT restore approval, the
     * publisher must approve it from the queue.
     */
    def withUnrejection(siteId: SiteId): Creative =
      copy(rejectedSites = rejectedSites.remove(siteId))

    /**
     * Revoke approval for a site — SOFT undo, back to PENDING. Clears the
     * approval (so the bid stops teaching the floor) but does NOT touch
     * rejectedSites: the creative must keep bidding, because winning an
     * auction is its only path back into the publisher's approval queue
     * (AdServer's revoke removes it from serving AND pending selections).
     * Reject/flag is the bidding block; revoke is re-evaluatable.
     */
    def withRevocation(siteId: SiteId): Creative =
      copy(approvedSites = approvedSites - siteId.value)

    /** Update active status (pause/reactivate) */
    def withActiveStatus(active: Boolean): Creative =
      copy(isActive = active)
  }

  final case class State(
      advertiserId: AdvertiserId,
      name: Name,
      status: Status,
      campaignIds: Set[CampaignId],
      creatives: Map[CreativeId, Creative],
      siteDomainBlocklist: Set[String],
      dailyBudget: Budget,
      spendToday: Spend,
      lastResetEpochDay: Long,
      processedFlushIds: Set[FlushId] = Set.empty,
      flushIdQueue: Vector[FlushId] = Vector.empty
  ) extends CborSerializable {
    def addCampaign(campaignId: CampaignId): State =
      copy(campaignIds = campaignIds + campaignId)

    def removeCampaign(campaignId: CampaignId): State =
      copy(campaignIds = campaignIds - campaignId)

    def addCreative(creative: Creative): State =
      copy(creatives = creatives.updated(creative.id, creative))

    def removeCreative(creativeId: CreativeId): State =
      copy(creatives = creatives - creativeId)

    def updateCreativeApproval(
        creativeId: CreativeId,
        siteId: SiteId,
        approvalStatus: publisher.ApprovalStatus
    ): State =
      creatives.get(creativeId) match {
        // No-op short-circuit (returns `this` — the handler skips the persist
        // on reference equality): already approved with nothing to un-reject.
        // This is the boot-backfill steady state, re-announced every restart.
        case Some(creative)
            if approvalStatus == publisher.ApprovalStatus.Approved &&
            creative.isApprovedFor(siteId) && creative.rejectedSites.isEmpty =>
          this
        case Some(creative) =>
          copy(creatives =
            creatives.updated(creativeId,
              creative.withApproval(siteId, approvalStatus))
          )
        case None => this
      }

    def getCreatives(ids: Set[CreativeId]): Set[Creative] =
      ids.flatMap(creatives.get)

    def blockDomains(domains: Set[String]): State =
      copy(siteDomainBlocklist = siteDomainBlocklist ++ domains.map(_.toLowerCase))

    def unblockDomains(domains: Set[String]): State =
      copy(siteDomainBlocklist = siteDomainBlocklist -- domains.map(_.toLowerCase))

    def withName(name: Name): State =
      copy(name = name)

    def withStatus(status: Status): State =
      copy(status = status)

    def withDailyBudget(budget: Budget): State =
      copy(dailyBudget = budget)

    def addSpend(amount: Spend): State =
      copy(spendToday = Spend(spendToday.value + amount.value))

    /**
     * Track a processed flush ID for idempotency, with bounded FIFO retention.
     * Uses Set for O(1) lookup + Queue for FIFO eviction order.
     */
    def trackFlush(flushId: FlushId): State = {
      val newSet = processedFlushIds + flushId
      val newQueue = flushIdQueue :+ flushId
      if (newQueue.size > MaxProcessedFlushIds) {
        val oldest = newQueue.head
        copy(
          processedFlushIds = newSet - oldest,
          flushIdQueue = newQueue.tail
        )
      } else {
        copy(processedFlushIds = newSet, flushIdQueue = newQueue)
      }
    }

    def rollWindow(newEpochDay: Long): State =
      // Clear processed flush IDs on window roll (new day = new idempotency window)
      copy(
        spendToday = Spend.zero,
        lastResetEpochDay = newEpochDay,
        processedFlushIds = Set.empty,
        flushIdQueue = Vector.empty
      )

    def remaining: Budget =
      Budget((dailyBudget.value - spendToday.value).max(BigDecimal(0)))

    /**
     * Day-aware remaining budget: if day has changed since last reset, treat as fresh budget.
     * This handles the race condition where AdvertiserBudgetReset triggers re-auction before
     * AdvertiserEntity processes its own ResetDayStart command.
     */
    def remainingForBidding: Budget = {
      val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay
      if (today > lastResetEpochDay) {
        // Day has changed but reset not yet processed - treat as fresh budget
        dailyBudget
      } else {
        remaining
      }
    }

    def withinBudget: Boolean =
      spendToday.value < dailyBudget.value

    def toInfo: AdvertiserInfo =
      AdvertiserInfo(advertiserId, name, status, campaignIds, siteDomainBlocklist)
  }

  /** Remove a creative */
  case class RemoveCreative(creativeId: CreativeId, replyTo: ActorRef[CreativeRemoved])
      extends Command

  case class CreativeRemoved(advertiserId: AdvertiserId, creativeId: CreativeId) extends promovolve.CborSerializable

  /** Record spend from a campaign (aggregated at advertiser level) */
  private[advertiser] case class RecordCampaignSpend(
      flushId: FlushId,
      campaignId: CampaignId,
      amount: Spend,
      timestamp: Instant,
      replyTo: ActorRef[CampaignSpendRecorded]
  ) extends Command

  private[advertiser] case class CampaignSpendRecorded(
      advertiserId: AdvertiserId,
      campaignId: CampaignId,
      totalSpendToday: Spend,
      remaining: Budget
  ) extends promovolve.CborSerializable

  object State {
    def empty(advertiserId: AdvertiserId): State =
      State(
        advertiserId = advertiserId,
        name = Name.empty,
        status = Status.Active,
        campaignIds = Set.empty,
        creatives = Map.empty,
        siteDomainBlocklist = Set.empty,
        dailyBudget = Budget.zero,
        spendToday = Spend.zero,
        lastResetEpochDay = 0L
      )
  }

  /** Cached site-domain blocklist in DData (replicated across cluster for fast reads by AdServer). */
  final case class CachedSiteDomainBlocklist(domains: Set[String]) extends CborSerializable {
    def contains(domain: String): Boolean = domains.contains(domain.toLowerCase)
  }

  object CachedSiteDomainBlocklist {
    val empty: CachedSiteDomainBlocklist = CachedSiteDomainBlocklist(Set.empty)
  }

  /** Scheduled retry for DData sync */
  private final case class RetryDDataSync(attempt: Int) extends Command
}
