package promovolve.api

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
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
import promovolve.publisher.SiteEntity
import spray.json.*

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Success

/** SSE routes for real-time pending creative notifications.
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

  /** IDOR guard: this stream carries another publisher's live moderation
    * activity (approve/reject/pending), so the {publisherId} path segment
    * must actually own {siteId}. Same ConfigResult check as
    * EndpointRoutes.requireOwnedSite. */
  private def ownsSite(publisherId: String, siteId: String): Future[Boolean] =
    sharding
      .entityRefFor(SiteEntity.TypeKey, siteId)
      .ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_))
      .map {
        case SiteEntity.ConfigResult(Some(cfg)) => cfg.publisherId == PublisherId(publisherId)
        case _                                  => false
      }(using system.executionContext)
      .recover { case _ => false }(using system.executionContext)

  val routes: Route = pathPrefix("v1" / "publishers" / Segment / "sites" / Segment / "events") {
    (publisherId, siteId) =>
      get {
        onComplete(ownsSite(publisherId, siteId)) {
          case Success(true) => complete(createEventSource(siteId))
          case _             => complete(StatusCodes.NotFound -> """{"error":"site_not_found"}""")
        }
      }
  }

  /** Create an SSE source that:
    * 1. Creates an actor to receive PendingEvents
    * 2. Subscribes the actor to the PendingEventHub
    * 3. Converts events to ServerSentEvents
    * 4. Unsubscribes on stream completion
    */
  private def createEventSource(siteId: String): Source[ServerSentEvent, NotUsed] = {
    // Create an actor source that receives PendingEvent messages
    val (actorRef, source) = ActorSource
      .actorRef[PendingEventHub.PendingEvent](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        bufferSize = 100,
        overflowStrategy = OverflowStrategy.dropHead
      )
      .preMaterialize()

    // Subscribe to the event hub
    pendingEventHub ! PendingEventHub.Subscribe(siteId, actorRef)

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
          pendingEventHub ! PendingEventHub.Unsubscribe(siteId, actorRef)
        }(using system.executionContext)
        NotUsed
      }
  }
}
