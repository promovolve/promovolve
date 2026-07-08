package promovolve.api

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.time.Instant
import scala.concurrent.duration._

/** Event hub for pending creative notifications via SSE.
  *
  * Manages SSE subscribers per site and broadcasts pending updates
  * when new creatives are queued for approval.
  */
object PendingEventHub {

  sealed trait Command

  /** Subscribe to pending events for a site */
  final case class Subscribe(
      siteId: String,
      subscriber: ActorRef[PendingEvent]
  ) extends Command

  /** Unsubscribe from pending events */
  final case class Unsubscribe(
      siteId: String,
      subscriber: ActorRef[PendingEvent]
  ) extends Command

  /** Publish a pending update event */
  final case class PublishPendingUpdate(
      siteId: String,
      url: String,
      slotId: String,
      count: Int,
      topCreativeId: String
  ) extends Command

  /** Publish an approval event (creative was approved and moved to serving) */
  final case class PublishApproved(
      siteId: String,
      url: String,
      slotId: String,
      creativeId: String
  ) extends Command

  /** Publish a rejection event */
  final case class PublishRejected(
      siteId: String,
      url: String,
      slotId: String,
      creativeId: String
  ) extends Command

  /** Publish a bulk approval event */
  final case class PublishBulkApproved(
      siteId: String,
      url: String,
      slotId: String,
      approvedCount: Int
  ) extends Command

  /** Publish a revoke event */
  final case class PublishRevoked(
      siteId: String,
      creativeId: String
  ) extends Command

  /** Publish a creative status change event (broadcasts to all site subscribers) */
  final case class PublishCreativeStatusChanged(
      creativeId: String,
      campaignId: String,
      newStatus: String
  ) extends Command

  /** Publish a campaign status change event (broadcasts to all site subscribers) */
  final case class PublishCampaignStatusChanged(
      campaignId: String,
      newStatus: String
  ) extends Command

  /** Internal: cleanup stale subscribers */
  private case object Cleanup extends Command

  /** Events sent to SSE subscribers */
  sealed trait PendingEvent
  final case class PendingUpdated(
      siteId: String,
      url: String,
      slotId: String,
      count: Int,
      topCreativeId: String,
      timestamp: Instant
  ) extends PendingEvent
  final case class Approved(
      siteId: String,
      url: String,
      slotId: String,
      creativeId: String,
      timestamp: Instant
  ) extends PendingEvent
  final case class Rejected(
      siteId: String,
      url: String,
      slotId: String,
      creativeId: String,
      timestamp: Instant
  ) extends PendingEvent
  final case class BulkApproved(
      siteId: String,
      url: String,
      slotId: String,
      approvedCount: Int,
      timestamp: Instant
  ) extends PendingEvent
  final case class Revoked(
      siteId: String,
      creativeId: String,
      timestamp: Instant
  ) extends PendingEvent
  final case class CreativeStatusChanged(
      creativeId: String,
      campaignId: String,
      newStatus: String,
      timestamp: Instant
  ) extends PendingEvent
  final case class CampaignStatusChanged(
      campaignId: String,
      newStatus: String,
      timestamp: Instant
  ) extends PendingEvent
  case object Heartbeat extends PendingEvent

  /** State: subscribers grouped by siteId */
  private final case class State(
      subscribers: Map[String, Set[ActorRef[PendingEvent]]] = Map.empty
  ) {
    def addSubscriber(siteId: String, ref: ActorRef[PendingEvent]): State = {
      val existing = subscribers.getOrElse(siteId, Set.empty)
      copy(subscribers = subscribers + (siteId -> (existing + ref)))
    }

    def removeSubscriber(siteId: String, ref: ActorRef[PendingEvent]): State = {
      subscribers.get(siteId) match {
        case Some(refs) =>
          val updated = refs - ref
          if (updated.isEmpty) copy(subscribers = subscribers - siteId)
          else copy(subscribers = subscribers + (siteId -> updated))
        case None => this
      }
    }

    def getSubscribers(siteId: String): Set[ActorRef[PendingEvent]] =
      subscribers.getOrElse(siteId, Set.empty)

    def allSubscribers: Set[ActorRef[PendingEvent]] =
      subscribers.values.flatten.toSet
  }

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      // Send heartbeats every 30 seconds to keep connections alive
      timers.startTimerAtFixedRate("heartbeat", Cleanup, 30.seconds)

      behavior(State())
    }
  }

  private def behavior(state: State): Behavior[Command] = Behaviors.receive { (ctx, msg) =>
    msg match {
      case Subscribe(siteId, subscriber) =>
        ctx.log.info("SSE subscriber connected for site {}", siteId)
        // Watch the subscriber to auto-remove on termination
        ctx.watchWith(subscriber, Unsubscribe(siteId, subscriber))
        behavior(state.addSubscriber(siteId, subscriber))

      case Unsubscribe(siteId, subscriber) =>
        ctx.log.info("SSE subscriber disconnected from site {}", siteId)
        behavior(state.removeSubscriber(siteId, subscriber))

      case PublishPendingUpdate(siteId, url, slotId, count, topCreativeId) =>
        val event = PendingUpdated(siteId, url, slotId, count, topCreativeId, Instant.now())
        val subscribers = state.getSubscribers(siteId)
        if (subscribers.nonEmpty) {
          ctx.log.info(
            "Broadcasting pending-updated to {} subscribers: site={} count={}",
            subscribers.size, siteId, count
          )
          subscribers.foreach(_ ! event)
        }
        Behaviors.same

      case PublishApproved(siteId, url, slotId, creativeId) =>
        val event = Approved(siteId, url, slotId, creativeId, Instant.now())
        val subscribers = state.getSubscribers(siteId)
        if (subscribers.nonEmpty) {
          ctx.log.info(
            "Broadcasting approved to {} subscribers: site={} creativeId={}",
            subscribers.size, siteId, creativeId
          )
          subscribers.foreach(_ ! event)
        }
        Behaviors.same

      case PublishRejected(siteId, url, slotId, creativeId) =>
        val event = Rejected(siteId, url, slotId, creativeId, Instant.now())
        val subscribers = state.getSubscribers(siteId)
        if (subscribers.nonEmpty) {
          ctx.log.info(
            "Broadcasting rejected to {} subscribers: site={} creativeId={}",
            subscribers.size, siteId, creativeId
          )
          subscribers.foreach(_ ! event)
        }
        Behaviors.same

      case PublishBulkApproved(siteId, url, slotId, approvedCount) =>
        val event = BulkApproved(siteId, url, slotId, approvedCount, Instant.now())
        val subscribers = state.getSubscribers(siteId)
        if (subscribers.nonEmpty) {
          ctx.log.info(
            "Broadcasting bulk-approved to {} subscribers: site={} approvedCount={}",
            subscribers.size, siteId, approvedCount
          )
          subscribers.foreach(_ ! event)
        }
        Behaviors.same

      case PublishRevoked(siteId, creativeId) =>
        val event = Revoked(siteId, creativeId, Instant.now())
        val subscribers = state.getSubscribers(siteId)
        if (subscribers.nonEmpty) {
          ctx.log.info(
            "Broadcasting revoked to {} subscribers: site={} creativeId={}",
            subscribers.size, siteId, creativeId
          )
          subscribers.foreach(_ ! event)
        }
        Behaviors.same

      case PublishCreativeStatusChanged(creativeId, campaignId, newStatus) =>
        // Broadcast to ALL site subscribers since creative may be serving on multiple sites
        val event = CreativeStatusChanged(creativeId, campaignId, newStatus, Instant.now())
        val allSubs = state.allSubscribers
        if (allSubs.nonEmpty) {
          ctx.log.info(
            "Broadcasting creative-status-changed to {} subscribers: creativeId={} status={}",
            allSubs.size, creativeId, newStatus
          )
          allSubs.foreach(_ ! event)
        }
        Behaviors.same

      case PublishCampaignStatusChanged(campaignId, newStatus) =>
        // Broadcast to ALL site subscribers since campaign may be serving on multiple sites
        val event = CampaignStatusChanged(campaignId, newStatus, Instant.now())
        val allSubs = state.allSubscribers
        if (allSubs.nonEmpty) {
          ctx.log.info(
            "Broadcasting campaign-status-changed to {} subscribers: campaignId={} status={}",
            allSubs.size, campaignId, newStatus
          )
          allSubs.foreach(_ ! event)
        }
        Behaviors.same

      case Cleanup =>
        // Send heartbeat to all subscribers to keep connections alive
        state.allSubscribers.foreach(_ ! Heartbeat)
        Behaviors.same
    }
  }
}
