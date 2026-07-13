package promovolve.api

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import org.apache.pekko.util.Timeout
import promovolve.PublisherId
import promovolve.publisher.{ PublisherEntity, SiteEntity }
import spray.json.*

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Success

/**
 * SSE routes for real-time pending creative notifications.
 *
 * Provides endpoint: GET /v1/publishers/{publisherId}/sites/{siteId}/events
 * Streams "pending-updated" events when new creatives are queued for approval.
 */
class SseRoutes(
    pendingEventHub: ActorRef[PendingEventHub.Command],
    sharding: ClusterSharding
)(using system: ActorSystem[?]) {

  import org.apache.pekko.http.scaladsl.marshalling.sse.EventStreamMarshalling.*

  private given Timeout = Timeout(5.seconds)

  /**
   * IDOR guard: this stream carries another publisher's live moderation
   * activity (approve/reject/pending), so the {publisherId} path segment
   * must actually own {siteId}. Same ConfigResult check as
   * EndpointRoutes.requireOwnedSite.
   */
  private def ownsSite(publisherId: String, siteId: String): Future[Boolean] =
    sharding
      .entityRefFor(SiteEntity.TypeKey, siteId)
      .ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_))
      .map {
        case SiteEntity.ConfigResult(Some(cfg)) => cfg.publisherId == PublisherId(publisherId)
        case _                                  => false
      }(using system.executionContext)
      .recover { case _ => false }(using system.executionContext)

  /**
   * All of a publisher's siteIds, for the publisher-level stream. Empty on
   * unknown publisher or ask failure (the route 404s on empty).
   */
  private def publisherSiteIds(publisherId: String): Future[Set[String]] =
    sharding
      .entityRefFor(PublisherEntity.TypeKey, publisherId)
      .ask[PublisherEntity.PublisherInfo](PublisherEntity.GetPublisher(_))
      .map(_.siteIds.map(_.value))(using system.executionContext)
      .recover { case _ => Set.empty[String] }(using system.executionContext)

  val routes: Route = pathPrefix("v1" / "publishers" / Segment) { publisherId =>
    concat(
      // Publisher-level stream: events for ALL the publisher's sites on one
      // connection. The approval inbox aggregates every site, but the old
      // per-site stream only carried "the first site with pending" — events
      // for any other site published to a key with zero subscribers, so the
      // inbox never updated live (found on the two-site GKE deployment
      // 2026-07-12; latent since the multi-site inbox shipped).
      path("events") {
        get {
          onComplete(publisherSiteIds(publisherId)) {
            case Success(siteIds) if siteIds.nonEmpty =>
              complete(createEventSource(siteIds))
            case _ =>
              complete(StatusCodes.NotFound -> """{"error":"publisher_not_found"}""")
          }
        }
      },
      // Per-site stream (kept for compatibility; same IDOR guard as before).
      pathPrefix("sites" / Segment / "events") { siteId =>
        get {
          onComplete(ownsSite(publisherId, siteId)) {
            case Success(true) => complete(createEventSource(Set(siteId)))
            case _             => complete(StatusCodes.NotFound -> """{"error":"site_not_found"}""")
          }
        }
      }
    )
  }

  /**
   * Create an SSE source that:
   * 1. Creates an actor to receive PendingEvents
   * 2. Subscribes the actor to the PendingEventHub
   * 3. Converts events to ServerSentEvents
   * 4. Unsubscribes on stream completion
   */
  private def createEventSource(siteIds: Set[String]): Source[ServerSentEvent, NotUsed] = {
    // Create an actor source that receives PendingEvent messages
    val (actorRef, source) = ActorSource
      .actorRef[PendingEventHub.PendingEvent](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        bufferSize = 100,
        overflowStrategy = OverflowStrategy.dropHead
      )
      .preMaterialize()

    // Subscribe to the event hub for every requested site (one watch, one
    // unsubscribe covering the whole set — see PendingEventHub.Subscribe).
    pendingEventHub ! PendingEventHub.Subscribe(siteIds, actorRef)

    // Convert PendingEvents to ServerSentEvents
    source
      .map {
        case PendingEventHub.PendingUpdated(siteId, url, slotId, count, topCreativeId, timestamp) =>
          val data = JsObject(
            "siteId" -> JsString(siteId),
            "url" -> JsString(url),
            "slotId" -> JsString(slotId),
            "count" -> JsNumber(count),
            "topCreativeId" -> JsString(topCreativeId),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "pending-updated")

        case PendingEventHub.Approved(siteId, url, slotId, creativeId, timestamp) =>
          val data = JsObject(
            "siteId" -> JsString(siteId),
            "url" -> JsString(url),
            "slotId" -> JsString(slotId),
            "creativeId" -> JsString(creativeId),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "approved")

        case PendingEventHub.Rejected(siteId, url, slotId, creativeId, timestamp) =>
          val data = JsObject(
            "siteId" -> JsString(siteId),
            "url" -> JsString(url),
            "slotId" -> JsString(slotId),
            "creativeId" -> JsString(creativeId),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "rejected")

        case PendingEventHub.AutoApproved(siteId, url, slotId, creativeId, campaignId, timestamp) =>
          val data = JsObject(
            "siteId" -> JsString(siteId),
            "url" -> JsString(url),
            "slotId" -> JsString(slotId),
            "creativeId" -> JsString(creativeId),
            "campaignId" -> JsString(campaignId),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "auto-approved")

        case PendingEventHub.BulkApproved(siteId, url, slotId, approvedCount, timestamp) =>
          val data = JsObject(
            "siteId" -> JsString(siteId),
            "url" -> JsString(url),
            "slotId" -> JsString(slotId),
            "approvedCount" -> JsNumber(approvedCount),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "bulk-approved")

        case PendingEventHub.Revoked(siteId, creativeId, timestamp) =>
          val data = JsObject(
            "siteId" -> JsString(siteId),
            "creativeId" -> JsString(creativeId),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "revoked")

        case PendingEventHub.CreativeStatusChanged(creativeId, campaignId, newStatus, timestamp) =>
          val data = JsObject(
            "creativeId" -> JsString(creativeId),
            "campaignId" -> JsString(campaignId),
            "status" -> JsString(newStatus),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "creative-status-changed")

        case PendingEventHub.CampaignStatusChanged(campaignId, newStatus, timestamp) =>
          val data = JsObject(
            "campaignId" -> JsString(campaignId),
            "status" -> JsString(newStatus),
            "timestamp" -> JsString(timestamp.toString)
          ).compactPrint
          ServerSentEvent(data, "campaign-status-changed")

        case PendingEventHub.Heartbeat =>
          ServerSentEvent.heartbeat
      }
      .keepAlive(30.seconds, () => ServerSentEvent.heartbeat)
      .watchTermination() { (_, done) =>
        // Unsubscribe when the stream terminates
        done.foreach { _ =>
          pendingEventHub ! PendingEventHub.Unsubscribe(siteIds, actorRef)
        }(using system.executionContext)
        NotUsed
      }
  }
}
