package promovolve.publisher

import com.fasterxml.jackson.annotation.JsonAlias
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.cluster.ddata.typed.scaladsl.{ DistributedData, Replicator }
import org.apache.pekko.cluster.ddata.{ LWWMap, LWWMapKey, SelfUniqueAddress }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityTypeKey }
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.RecoveryCompleted
import org.apache.pekko.persistence.typed.state.scaladsl.{ DurableStateBehavior, Effect }
import promovolve.{ CborSerializable, PublisherId, SiteId }

/**
 * Sharded entity representing a publisher account.
 *
 * A publisher:
 *   - Owns one or more sites (tracks membership only, sites own their config)
 *   - Has domain blacklist (block ads linking to specific landing page domains)
 *   - Coordinates site lifecycle (register, delete)
 *
 * Uses DurableStateBehavior (not EventSourced) because:
 *   - Simple state with no need for event replay history
 *   - No downstream event consumers
 *   - Direct state persistence is simpler and more efficient
 *
 * Hierarchy: Publisher → Site(s) → AdSlot(s)
 *
 * Note: Site configuration is owned by SiteEntity, not PublisherEntity.
 * This allows sites to be autonomous after initialization and recover
 * independently after node restarts.
 */
object PublisherEntity {

  /** Type alias for DData update responses */
  // Note: DomainBlocklistCacheKey uses SiteId (not PublisherId) because AdServer
  // is sharded by site and needs to look up blacklist by its own ID.
  private type DDataUpdateResponse =
    Replicator.UpdateResponse[LWWMap[SiteId, CachedDomainBlocklist]]

  val TypeKey: EntityTypeKey[Command | DDataUpdateResponse] =
    EntityTypeKey[Command | DDataUpdateResponse]("publisher-entity")

  /**
   * DData key for domain blacklists - replicated across cluster for fast reads by AdServer.
   * Note: Uses SiteId as key because AdServer is sharded by site and looks up by its own ID.
   * Always includes publisherId as a "virtual site" for the default case where siteId == publisherId.
   */
  val DomainBlocklistCacheKey: LWWMapKey[SiteId, CachedDomainBlocklist] =
    LWWMapKey[SiteId, CachedDomainBlocklist]("publisher-domain-blacklist")

  private val MaxDDataRetries = 3

  // ---------- Behavior ----------
  def apply(
      publisherId: PublisherId,
      sharding: ClusterSharding
  )(using system: ActorSystem[?]): Behavior[Command | DDataUpdateResponse] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        given node: SelfUniqueAddress = DistributedData(system).selfUniqueAddress
        val replicator = DistributedData(system).replicator
        val retryTimerKey = "ddata-retry"

        def syncToDData(state: State): Unit = {
          val cached = CachedDomainBlocklist(state.domainBlocklist)

          // Always include publisherId as a "virtual site" - this handles the case where
          // siteId == publisherId in the widget without explicit site registration
          val allSiteKeys = state.siteIds + SiteId(publisherId.value)

          ctx.log.info(
            "syncToDData: publisher={} domains={} siteKeys={}",
            publisherId.value,
            state.domainBlocklist.mkString(","),
            allSiteKeys.map(_.value).mkString(",")
          )

          allSiteKeys.foreach { siteId =>
            replicator ! Replicator.Update(
              DomainBlocklistCacheKey,
              LWWMap.empty[SiteId, CachedDomainBlocklist],
              Replicator.WriteLocal,
              ctx.self
            )(_.put(node, siteId, cached))
          }
        }

        def shutdownSite(siteId: SiteId): Unit = {
          val siteRef = sharding.entityRefFor(SiteEntity.TypeKey, siteId.value)
          // Delete (not Shutdown): clears the site's durable state and its
          // verified-host DData entry, so the id is re-creatable and serving
          // stops. Shutdown alone left both behind.
          siteRef ! SiteEntity.Delete
        }

        def cancelRetryTimer(): Unit = timers.cancel(retryTimerKey)

        def scheduleRetry(attempt: Int): Unit = {
          import scala.concurrent.duration.*
          val delay = (1 << attempt).seconds
          timers.startSingleTimer(retryTimerKey, RetryDDataSync(attempt), delay)
        }

        DurableStateBehavior[Command | DDataUpdateResponse, State](
          persistenceId = PersistenceId.ofUniqueId(s"publisher-${publisherId.value}"),
          emptyState = State.empty(publisherId),
          commandHandler = (state, command) =>
            handleCommand(state, command, sharding, syncToDData, shutdownSite, cancelRetryTimer, scheduleRetry, ctx)
        ).receiveSignal { case (state, RecoveryCompleted) =>
          // Keep DData in sync after recovery
          syncToDData(state)
        }
      }
    }

  private def handleCommand(
      state: State,
      command: Command | DDataUpdateResponse,
      sharding: ClusterSharding,
      syncToDData: State => Unit,
      shutdownSite: SiteId => Unit,
      cancelRetryTimer: () => Unit,
      scheduleRetry: Int => Unit,
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): Effect[State] =
    command match {
      case RegisterSite(siteId, replyTo) =>
        if (state.hasSite(siteId)) {
          // Site already exists - just reply (idempotent)
          Effect.none.thenReply(replyTo)(_ => SiteRegistered(state.publisherId, siteId))
        } else {
          // Don't persist yet - wait for site registration to succeed
          val siteRef = sharding.entityRefFor(SiteEntity.TypeKey, siteId.value)

          import scala.concurrent.duration.*
          given org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(5.seconds)
          ctx.pipeToSelf(
            siteRef.ask[SiteEntity.Registered](ref =>
              SiteEntity.Register(state.publisherId, ref)
            )
          ) {
            case scala.util.Success(_)  => SiteInitialized(siteId, replyTo)
            case scala.util.Failure(ex) => FailedToInitializeSite(siteId, ex.getMessage, replyTo)
          }
          Effect.none
        }

      case SiteInitialized(siteId, replyTo) =>
        ctx.log.info("Site {} registered successfully for publisher {}", siteId.value, state.publisherId.value)
        val newState = state.addSite(siteId)
        Effect
          .persist(newState)
          .thenRun(syncToDData)
          .thenReply(replyTo)(_ => SiteRegistered(state.publisherId, siteId))

      case FailedToInitializeSite(siteId, reason, replyTo) =>
        ctx.log.error("Failed to register site {}: {}", siteId.value, reason)
        Effect.none.thenReply(replyTo)(_ => SiteRegistrationFailed(state.publisherId, siteId, reason))

      case DeleteSite(siteId, replyTo) =>
        if (state.hasSite(siteId)) {
          val newState = state.removeSite(siteId)
          Effect
            .persist(newState)
            .thenRun { _ =>
              ctx.log.info(
                "Deleted site {} from publisher {}, shutting down SiteEntity",
                siteId.value,
                state.publisherId.value
              )
              shutdownSite(siteId)
            }
            .thenReply(replyTo)(_ => SiteDeleted(state.publisherId, siteId))
        } else Effect.none.thenReply(replyTo)(_ => SiteDeleted(state.publisherId, siteId))

      case HasSite(siteId, replyTo) =>
        Effect.none.thenReply(replyTo)(_ => state.hasSite(siteId))

      case BlockDomains(domains, replyTo) =>
        val normalizedDomains = domains.map(_.toLowerCase)
        val newDomains = normalizedDomains -- state.domainBlocklist
        if (newDomains.nonEmpty) {
          val newState = state.blockDomains(newDomains)
          Effect
            .persist(newState)
            .thenRun(syncToDData)
            .thenReply(replyTo)(_ => DomainsBlocked(state.publisherId, newDomains))
        } else Effect.none.thenReply(replyTo)(_ => DomainsBlocked(state.publisherId, Set.empty))

      case UnblockDomains(domains, replyTo) =>
        val normalizedDomains = domains.map(_.toLowerCase)
        val toRemove = normalizedDomains.intersect(state.domainBlocklist)
        if (toRemove.nonEmpty) {
          val newState = state.unblockDomains(toRemove)
          Effect
            .persist(newState)
            .thenRun(syncToDData)
            .thenReply(replyTo)(_ => DomainsUnblocked(state.publisherId, toRemove))
        } else Effect.none.thenReply(replyTo)(_ => DomainsUnblocked(state.publisherId, Set.empty))

      case response: Replicator.UpdateResponse[?] =>
        response match {
          case Replicator.UpdateSuccess(key) =>
            // Success - cancel any pending retry
            ctx.log.debug(
              "DData update success: publisher={} key={}",
              state.publisherId.value, key.id
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

      case RetryDDataSync(attempt) =>
        if (attempt > MaxDDataRetries) {
          ctx.log.error(
            "DData sync exhausted all {} retries for publisher {}",
            MaxDDataRetries,
            state.publisherId.value
          )
        } else {
          ctx.log.info(
            "Retrying DData sync for publisher {} (attempt {}/{})",
            state.publisherId.value,
            attempt,
            MaxDDataRetries
          )
          syncToDData(state)
          // Schedule the next retry in case this one also fails
          // Timer will be cancelled by UpdateSuccess handler if sync succeeds
          scheduleRetry(attempt + 1)
        }
        Effect.none

      case GetPublisher(replyTo) =>
        Effect.none.thenReply(replyTo)(_.toInfo)

      case GetDomainBlocklist(replyTo) =>
        Effect.none.thenReply(replyTo)(s => DomainBlocklist(s.domainBlocklist))

      case UpdateStatus(status, replyTo) =>
        val prev = state.status
        val newState = state.withStatus(status)
        Effect
          .persist(newState)
          .thenRun { (_: State) =>
            // Operator suspension freezes serving on every site; resuming
            // to Active lifts it. SetSuspended is an idempotent flag flip,
            // so a retried suspend re-fanning is harmless. Known edge: a
            // site registered WHILE suspended won't inherit the flag —
            // acceptable because the suspended org's dashboard is locked.
            val freeze = status match {
              case Status.Suspended | Status.Closed       => Some(true)
              case Status.Active if prev != Status.Active => Some(false)
              case _                                      => None
            }
            freeze.foreach { s =>
              if (state.siteIds.nonEmpty) {
                ctx.log.info("Publisher {} status {} -> {}: setting suspended={} on {} site(s)",
                  state.publisherId.value, prev, status, s, state.siteIds.size)
              }
              state.siteIds.foreach { siteId =>
                sharding.entityRefFor(SiteEntity.TypeKey, siteId.value) !
                SiteEntity.SetSuspended(s, ctx.system.ignoreRef)
              }
            }
          }
          .thenReply(replyTo)(_ => StatusUpdated(state.publisherId, status))

      case GetClassificationFreshnessWindow(replyTo) =>
        Effect.none.thenReply(replyTo)(_ => ClassificationFreshnessWindow(state.classificationFreshnessWindowMs))

      case SetClassificationFreshnessWindow(windowMs, replyTo) =>
        // Validate: 24 hours to 7 days
        val minMs = 24L * 60 * 60 * 1000
        val maxMs = 7L * 24 * 60 * 60 * 1000
        val validWindowMs = math.max(minMs, math.min(maxMs, windowMs))
        val newState = state.withClassificationFreshnessWindow(validWindowMs)
        Effect.persist(newState).thenReply(replyTo)(_ =>
          ClassificationFreshnessWindowUpdated(state.publisherId, validWindowMs))

      case GetHmacSecret(replyTo) =>
        Effect.none.thenReply(replyTo)(_ => HmacSecret(state.hmacSecret))

      case SetHmacSecret(secret, replyTo) =>
        val newState = state.withHmacSecret(Some(secret))
        Effect.persist(newState).thenReply(replyTo)(_ => HmacSecretUpdated(state.publisherId))
    }

  private def handleDDataFailure(
      state: State,
      key: String,
      error: String,
      scheduleRetry: Int => Unit,
      ctx: ActorContext[Command | DDataUpdateResponse]
  ): Effect[State] = {
    ctx.log.warn(
      "DData update failed for publisher {} key={} error={}, scheduling retry 1/{}",
      state.publisherId.value,
      key,
      error,
      MaxDDataRetries
    )
    // Schedule first retry; subsequent retries are chained from RetryDDataSync handler
    scheduleRetry(1)
    Effect.none
  }

  // ---------- Protocol ----------
  sealed trait Command extends promovolve.CborSerializable

  sealed trait RegisterSiteResult extends promovolve.CborSerializable

  /** Register a new site under this publisher (site will persist its own config) */
  final case class RegisterSite(
      siteId: SiteId,
      replyTo: ActorRef[RegisterSiteResult]
  ) extends Command

  final case class SiteRegistered(publisherId: PublisherId, siteId: SiteId) extends RegisterSiteResult
  final case class SiteRegistrationFailed(publisherId: PublisherId, siteId: SiteId, reason: String)
      extends RegisterSiteResult

  /** Delete a site from this publisher */
  final case class DeleteSite(
      siteId: SiteId,
      replyTo: ActorRef[SiteDeleted]
  ) extends Command

  final case class SiteDeleted(publisherId: PublisherId, siteId: SiteId) extends promovolve.CborSerializable

  /** Check if a site belongs to this publisher */
  final case class HasSite(
      siteId: SiteId,
      replyTo: ActorRef[Boolean]
  ) extends Command

  /** Block landing page domains */
  final case class BlockDomains(
      domains: Set[String],
      replyTo: ActorRef[DomainsBlocked]
  ) extends Command

  final case class DomainsBlocked(publisherId: PublisherId, domains: Set[String]) extends promovolve.CborSerializable

  /** Unblock landing page domains */
  final case class UnblockDomains(
      domains: Set[String],
      replyTo: ActorRef[DomainsUnblocked]
  ) extends Command

  final case class DomainsUnblocked(publisherId: PublisherId, domains: Set[String]) extends promovolve.CborSerializable

  /** Get publisher info */
  final case class GetPublisher(replyTo: ActorRef[PublisherInfo]) extends Command

  final case class PublisherInfo(
      publisherId: PublisherId,
      status: Status,
      siteIds: Set[SiteId],
      domainBlocklist: Set[String],
      // Wire alias: pre-rename senders during a rolling deploy still decode.
      @JsonAlias(Array("contentRecencyWindowMs"))
      classificationFreshnessWindowMs: Long
  ) extends promovolve.CborSerializable

  /** Get domain blacklist only (for filtering during ad serving) */
  final case class GetDomainBlocklist(replyTo: ActorRef[DomainBlocklist]) extends Command

  final case class DomainBlocklist(domains: Set[String]) extends promovolve.CborSerializable

  /** Update publisher status */
  final case class UpdateStatus(status: Status, replyTo: ActorRef[StatusUpdated]) extends Command

  final case class StatusUpdated(publisherId: PublisherId, status: Status) extends promovolve.CborSerializable

  /** Get classification freshness window */
  final case class GetClassificationFreshnessWindow(replyTo: ActorRef[ClassificationFreshnessWindow]) extends Command

  final case class ClassificationFreshnessWindow(windowMs: Long) extends promovolve.CborSerializable

  /** Set classification freshness window (24 hours to 7 days) */
  final case class SetClassificationFreshnessWindow(windowMs: Long,
      replyTo: ActorRef[ClassificationFreshnessWindowUpdated])
      extends Command

  final case class ClassificationFreshnessWindowUpdated(publisherId: PublisherId, windowMs: Long)
      extends promovolve.CborSerializable

  /** Get HMAC secret for signing tracking URLs */
  final case class GetHmacSecret(replyTo: ActorRef[HmacSecret]) extends Command

  final case class HmacSecret(secret: Option[Array[Byte]]) extends promovolve.CborSerializable

  /** Set HMAC secret */
  final case class SetHmacSecret(secret: Array[Byte], replyTo: ActorRef[HmacSecretUpdated]) extends Command

  final case class HmacSecretUpdated(publisherId: PublisherId) extends promovolve.CborSerializable

  // ---------- Status ----------
  enum Status {
    case Active, Suspended, Closed
  }

  // ---------- Internal Commands ----------

  // ---------- State ----------
  final case class State(
      publisherId: PublisherId,
      status: Status,
      siteIds: Set[SiteId],
      domainBlocklist: Set[String],
      // Persistence alias: durable state written before the rename carries
      // the old field name; recovery must keep reading it forever.
      @JsonAlias(Array("contentRecencyWindowMs"))
      classificationFreshnessWindowMs: Long = 48 * 60 * 60 * 1000, // Default 48 hours
      hmacSecret: Option[Array[Byte]] = None
  ) extends CborSerializable {
    def addSite(siteId: SiteId): State =
      copy(siteIds = siteIds + siteId)

    def removeSite(siteId: SiteId): State =
      copy(siteIds = siteIds - siteId)

    def hasSite(siteId: SiteId): Boolean =
      siteIds.contains(siteId)

    def blockDomains(domains: Set[String]): State =
      copy(domainBlocklist = domainBlocklist ++ domains.map(_.toLowerCase))

    def unblockDomains(domains: Set[String]): State =
      copy(domainBlocklist = domainBlocklist -- domains.map(_.toLowerCase))

    def withStatus(status: Status): State =
      copy(status = status)

    def withClassificationFreshnessWindow(windowMs: Long): State =
      copy(classificationFreshnessWindowMs = windowMs)

    def withHmacSecret(secret: Option[Array[Byte]]): State =
      copy(hmacSecret = secret)

    def toInfo: PublisherInfo =
      PublisherInfo(publisherId, status, siteIds, domainBlocklist, classificationFreshnessWindowMs)
  }

  /** Cached domain blacklist in DData (replicated across cluster for fast reads by AdServer) */
  final case class CachedDomainBlocklist(domains: Set[String]) extends CborSerializable {
    def contains(domain: String): Boolean = domains.contains(domain.toLowerCase)
  }

  /** Internal: site registered successfully */
  private[publisher] final case class SiteInitialized(
      siteId: SiteId,
      replyTo: ActorRef[RegisterSiteResult]
  ) extends Command

  /** Internal: site registration failed */
  private[publisher] final case class FailedToInitializeSite(
      siteId: SiteId,
      reason: String,
      replyTo: ActorRef[RegisterSiteResult]
  ) extends Command

  /** Scheduled retry for DData sync */
  private final case class RetryDDataSync(attempt: Int) extends Command

  object State {
    def empty(publisherId: PublisherId): State =
      State(
        publisherId = publisherId,
        status = Status.Active,
        siteIds = Set.empty,
        domainBlocklist = Set.empty,
        classificationFreshnessWindowMs = 48L * 60 * 60 * 1000 // Default 48 hours
      )
  }

  object CachedDomainBlocklist {
    val empty: CachedDomainBlocklist = CachedDomainBlocklist(Set.empty)
  }
}
