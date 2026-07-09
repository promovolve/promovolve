package promovolve.api

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import promovolve.advertiser.CampaignEntity
import promovolve.auction.{AuctioneerEntity, CategoryBidderEntity}
import promovolve.publisher.delivery.{AdServer, ServeIndexDData}
import promovolve.taxonomy.TaxonomyRankerEntity
import promovolve.*
import spray.json.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.Future

// ---------- JSON Protocol ----------
// NOTE: Approval queue models (PendingItemRes, ApprovalReq, etc.) moved to Endpoints.scala
trait AuctionJsonProtocol extends DefaultJsonProtocol {
  // Selection

  // Re-auction
  given RootJsonFormat[ReevaluateReq]      = jsonFormat1(ReevaluateReq.apply)

  // Test bid request
  given RootJsonFormat[TestBidReq]         = jsonFormat5(TestBidReq.apply)
  given RootJsonFormat[CampaignBidRes]     = jsonFormat3(CampaignBidRes.apply)
  given RootJsonFormat[TestBidRes]         = jsonFormat3(TestBidRes.apply)

  // Direct campaign bid
  given RootJsonFormat[DirectBidReq] = jsonFormat6(DirectBidReq.apply)
  given RootJsonFormat[DirectBidRes] = jsonFormat4(DirectBidRes.apply)

  // Record spend
  given RootJsonFormat[RecordSpendReq] = jsonFormat3(RecordSpendReq.apply)
  given RootJsonFormat[RecordSpendRes] = jsonFormat3(RecordSpendRes.apply)

  // Creative metadata check
  given RootJsonFormat[CreativeMetaRes] = jsonFormat6(CreativeMetaRes.apply)

  // Test tracking (bypasses HMAC)
  given RootJsonFormat[TestTrackReq] = jsonFormat9(TestTrackReq.apply)

  // Direct tracking (bypasses EventLog entirely)
  given RootJsonFormat[DirectTrackReq] = jsonFormat4(DirectTrackReq.apply)

  // Add candidate directly to ServeIndex
  given RootJsonFormat[AddCandidateReq] = jsonFormat11(AddCandidateReq.apply)
  given RootJsonFormat[AddCandidateRes] = jsonFormat5(AddCandidateRes.apply)
}

// ---------- Request/Response Models ----------
// NOTE: Approval queue models (PendingItemRes, ApprovalReq, etc.) moved to Endpoints.scala

/** Test tracking request (bypasses HMAC validation and deduplication)
  * If campaignId, advertiserId, and cpm are provided, spend tracking uses these directly
  * instead of looking up the ServeView (avoids race condition).
  */
final case class TestTrackReq(
    pub: String,
    url: String,
    slot: String,
    cid: String,
    category: Option[String] = None,     // Optional: directly specify category
    campaignId: Option[String] = None,   // Optional: for direct spend tracking
    advertiserId: Option[String] = None, // Optional: for direct spend tracking
    cpm: Option[Double] = None,          // Optional: for direct spend tracking
    requestId: Option[String] = None     // Optional: from TryReserve for idempotent spend tracking
)

/** Re-evaluate auction request */
final case class ReevaluateReq(url: String)

/** Test bid request - simulates a bid request to a category */
final case class TestBidReq(
    siteId: String,
    url: String,
    slotId: String,
    category: String,
    floorCpm: Double
)

/** Campaign bid in response */
final case class CampaignBidRes(
    campaignId: String,
    cpm: Double,
    creativeCount: Int
)

/** Test bid response */
final case class TestBidRes(
    category: String,
    eligible: Boolean,
    bids: Vector[CampaignBidRes]
)

/** Direct bid request to a specific campaign */
final case class DirectBidReq(
    advertiserId: String,
    campaignId: String,
    siteId: String,
    slotId: String,
    category: String,
    floorCpm: Double
)

/** Direct bid response */
final case class DirectBidRes(
    campaignId: String,
    eligible: Boolean,
    cpm: Double,
    creativeCount: Int
)

/** Record spend request (simulates win notification) */
final case class RecordSpendReq(
    advertiserId: String,
    campaignId: String,
    amount: Double
)

/** Record spend response */
final case class RecordSpendRes(
    campaignId: String,
    spent: Double,
    remaining: Double
)

/** Check creative metadata */
final case class CreativeMetaRes(
    found: Boolean,
    creativeId: String,
    advertiserId: Option[String],
    s3Key: Option[String],
    width: Option[Int],
    height: Option[Int]
)

/** Add candidate directly to ServeIndex (bypasses approval workflow) */
final case class AddCandidateReq(
    siteId: String,
    url: String,
    slotId: String,
    creativeId: String,
    campaignId: String,
    advertiserId: String,
    category: String,
    cpm: Double,
    width: Int = 300,
    height: Int = 250,
    ttlMs: Long = 7200000  // 2 hours default
)

/** Add candidate response */
final case class AddCandidateRes(
    siteId: String,
    url: String,
    slotId: String,
    creativeId: String,
    status: String
)

/** Direct tracking request (bypasses EventLog entirely, sends directly to entities) */
final case class DirectTrackReq(
    siteId: String,
    category: String,
    creativeId: String,
    cpm: Double = 5.0  // CPM for revenue calculation
)

// ---------- Routes ----------
final class AuctionRoutes(
    sharding: ClusterSharding,
    serveIndex: ActorRef[ServeIndexDData.Cmd],
    creativeRepo: promovolve.publisher.CreativeRepo,
    eventLog: EventLog,
    enableTestRoutes: Boolean = false
)(using system: ActorSystem[?])
    extends AuctionJsonProtocol {

  lazy val routes: Route =
    // auctioneerRoutes is an unauthenticated, owner-segment-less re-auction
    // trigger (/auctioneer/{siteId}/reevaluate) — a debug/admin tool with no
    // production caller (re-auction is event-driven inside the entities). It
    // rides with the test routes so it isn't part of the default-exposed
    // surface where any caller could churn a competitor's auctions.
    if enableTestRoutes then
      adServerRoutes ~ auctioneerRoutes ~ testBidRoutes
    else
      adServerRoutes
  // ---------- AdServer Routes (per site/publisher) ----------
  // NOTE: pending, approve, reject routes moved to Endpoints.scala (OpenAPI documented).
  // The single-slot admin /adserver/{siteId}/select route was removed when serve
  // consolidated on /v1/serve/batch — admin tooling can post a one-slot batch
  // request to the public batch endpoint instead.
  private lazy val adServerRoutes: Route = reject
  // ---------- Auctioneer Routes (per site) ----------
  private lazy val auctioneerRoutes: Route =
    pathPrefix("auctioneer" / Segment) { siteId =>
      // POST /auctioneer/{siteId}/reevaluate - Re-run auction for a URL
      path("reevaluate") {
        post {
          entity(as[ReevaluateReq]) { req =>
            // Reevaluate is fire-and-forget (no reply)
            auctioneerRef(siteId) ! AuctioneerEntity.Reevaluate(URL(req.url))
            complete(JsObject(
              "siteId" -> JsString(siteId),
              "url"    -> JsString(req.url),
              "status" -> JsString("reevaluation_triggered")
            ))
          }
        }
      }
    }
  // ---------- Test Bid Routes ----------
  private lazy val testBidRoutes: Route =
    pathPrefix("test") {
      // POST /test/bid - Send a test bid request to a category
      path("bid") {
        post {
          entity(as[TestBidReq]) { req =>
            val bidFuture: Future[CategoryBidderEntity.CategoryBidResponse] =
              categoryBidderRef(req.category).ask(CategoryBidderEntity.CategoryBidRequest(
                siteId   = SiteId(req.siteId),
                url      = req.url,
                slotId   = SlotId(req.slotId),
                sizes    = Set(AdSize(300, 250)), // Default size
                floorCpm = CPM(req.floorCpm),
                _
              ))
            onSuccess(bidFuture) { response =>
              complete(TestBidRes(
                category = response.categoryId.value,
                eligible = response.eligible,
                bids = response.campaigns.map { bid =>
                  CampaignBidRes(
                    campaignId    = bid.campaignId.value,
                    cpm           = bid.cpm.toDouble,
                    creativeCount = bid.creatives.size
                  )
                }
              ))
            }
          }
        }
      } ~
      // POST /test/direct-bid - Send a bid request directly to a campaign (bypasses CategoryBidder)
      path("direct-bid") {
        post {
          entity(as[DirectBidReq]) { req =>
            val bidFuture: Future[CampaignEntity.CampaignBidResponse] =
              campaignRef(req.advertiserId, req.campaignId).ask(ref =>
                CampaignEntity.CampaignBidRequest(
                  siteId       = SiteId(req.siteId),
                  url          = "https://test.com",
                  slotId       = SlotId(req.slotId),
                  pageCategory = CategoryId(req.category),
                  floorCpm     = CPM(req.floorCpm),
                  replyTo      = ref,
                )
              )
            onSuccess(bidFuture) { response =>
              complete(DirectBidRes(
                campaignId    = response.campaignId.value,
                eligible      = response.eligible,
                cpm           = response.cpm.toDouble,
                creativeCount = response.creatives.size
              ))
            }
          }
        }
      } ~
      // POST /test/spend - Record spend (simulates win/impression)
      path("spend") {
        post {
          entity(as[RecordSpendReq]) { req =>
            val requestId = jkugiya.ulid.ULID.getGenerator().base32()
            val spendFuture: Future[CampaignEntity.SpendRecorded] =
              campaignRef(req.advertiserId, req.campaignId).ask(CampaignEntity.RecordSpend(
                requestId = requestId,
                amount    = Spend(req.amount),
                ts        = Instant.now(),
                _
              ))
            onSuccess(spendFuture) { response =>
              complete(RecordSpendRes(
                campaignId = req.campaignId,
                spent      = response.spendToday.toDouble,
                remaining  = response.remaining.toDouble
              ))
            }
          }
        }
      } ~
      // GET /test/creative-meta/{creativeId} - Check if creative metadata exists
      path("creative-meta" / Segment) { creativeId =>
        get {
          onComplete(creativeRepo.get(creativeId)) {
            case scala.util.Success(Some(creative)) =>
              complete(CreativeMetaRes(
                found        = true,
                creativeId   = creativeId,
                advertiserId = Some(creative.advertiserId),
                s3Key        = Some(creative.s3Key),
                width        = Some(creative.width),
                height       = Some(creative.height)
              ))
            case _ =>
              complete(CreativeMetaRes(
                found        = false,
                creativeId   = creativeId,
                advertiserId = None,
                s3Key        = None,
                width        = None,
                height       = None
              ))
          }
        }
      } ~
      // POST /test/track/impression - Record impression (bypasses HMAC, for testing)
      path("track" / "impression") {
        post {
          entity(as[TestTrackReq]) { req =>
            val now = System.currentTimeMillis()
            val bucket = now / (60 * 1000L) // 1-minute buckets
            eventLog.logImpression(TrackEvent(
              pub          = req.pub,
              url          = req.url,
              slot         = req.slot,
              cid          = req.cid,
              version      = now,
              bucket       = bucket,
              ts           = now,
              ip           = "127.0.0.1",
              ua           = "PromoVolve-Simulator/1.0",
              campaignId   = req.campaignId,
              advertiserId = req.advertiserId,
              cpm          = req.cpm,
              category     = req.category,
              requestId    = req.requestId  // From TryReserve for idempotent spend tracking
            ))
            complete(JsObject(
              "status" -> JsString("impression_recorded"),
              "pub"    -> JsString(req.pub),
              "cid"    -> JsString(req.cid)
            ))
          }
        }
      } ~
      // POST /test/track/click - Record click (bypasses HMAC, for testing)
      path("track" / "click") {
        post {
          entity(as[TestTrackReq]) { req =>
            val now = System.currentTimeMillis()
            val bucket = now / (60 * 1000L)
            eventLog.logClick(TrackEvent(
              pub          = req.pub,
              url          = req.url,
              slot         = req.slot,
              cid          = req.cid,
              version      = now,
              bucket       = bucket,
              ts           = now,
              ip           = "127.0.0.1",
              ua           = "PromoVolve-Simulator/1.0",
              campaignId   = req.campaignId,
              advertiserId = req.advertiserId,
              cpm          = req.cpm,
              category     = req.category
            ))
            complete(JsObject(
              "status" -> JsString("click_recorded"),
              "pub"    -> JsString(req.pub),
              "cid"    -> JsString(req.cid)
            ))
          }
        }
      } ~
      // POST /test/add-candidate - Add candidate directly to ServeIndex (bypasses approval workflow)
      path("add-candidate") {
        post {
          entity(as[AddCandidateReq]) { req =>
            import promovolve.publisher.{CDNPath, CandidateView, MimeType}

            // Look up creative
            onComplete(creativeRepo.get(req.creativeId)) {
              case scala.util.Success(Some(creative)) =>
                val candidateView = CandidateView(
                  creativeId     = CreativeId(req.creativeId),
                  campaignId     = CampaignId(req.campaignId),
                  advertiserId   = AdvertiserId(req.advertiserId),
                  assetUrl       = CDNPath(creative.s3Key),
                  mime           = MimeType(creative.mime),
                  width          = creative.width,
                  height         = creative.height,
                  category       = CategoryId(req.category),
                  cpm            = CPM(req.cpm),
                  classifiedAtMs = System.currentTimeMillis(),
                  categoryScore  = 0.5  // Neutral prior
                )

                val slotKey = serveIndexKey(req.siteId, req.slotId)
                serveIndex ! ServeIndexDData.Append(slotKey, candidateView, req.ttlMs)

                complete(AddCandidateRes(
                  siteId     = req.siteId,
                  url        = req.url,
                  slotId     = req.slotId,
                  creativeId = req.creativeId,
                  status     = "added"
                ))

              case scala.util.Success(None) =>
                complete(StatusCodes.NotFound -> s"Creative not found: ${req.creativeId}")
              case scala.util.Failure(ex) =>
                complete(StatusCodes.InternalServerError -> s"Error: ${ex.getMessage}")
            }
          }
        }
      } ~
      // POST /test/direct/impression - Record impression directly to entities (bypasses EventLog)
      path("direct" / "impression") {
        post {
          entity(as[DirectTrackReq]) { req =>
            // Send directly to TaxonomyRankerEntity for category-level CTR learning
            val rankerEntityId = s"${req.category}|${req.siteId}"
            val rankerRef = sharding.entityRefFor(TaxonomyRankerEntity.TypeKey, rankerEntityId)
            rankerRef ! TaxonomyRankerEntity.RecordImpression(revenue = req.cpm / 1000.0)

            // Send to AdServer for per-creative Thompson Sampling
            adServerRef(req.siteId) ! AdServer.RecordImpression(CreativeId(req.creativeId))

            complete(JsObject(
              "status"   -> JsString("direct_impression_recorded"),
              "category" -> JsString(req.category),
              "siteId"   -> JsString(req.siteId),
              "cid"      -> JsString(req.creativeId)
            ))
          }
        }
      } ~
      // POST /test/direct/click - Record click directly to entities (bypasses EventLog)
      path("direct" / "click") {
        post {
          entity(as[DirectTrackReq]) { req =>
            // Send directly to TaxonomyRankerEntity for category-level CTR learning
            val rankerEntityId = s"${req.category}|${req.siteId}"
            val rankerRef = sharding.entityRefFor(TaxonomyRankerEntity.TypeKey, rankerEntityId)
            rankerRef ! TaxonomyRankerEntity.RecordClick()

            // Send to AdServer for per-creative Thompson Sampling
            adServerRef(req.siteId) ! AdServer.RecordClick(CreativeId(req.creativeId))

            complete(JsObject(
              "status"   -> JsString("direct_click_recorded"),
              "category" -> JsString(req.category),
              "siteId"   -> JsString(req.siteId),
              "cid"      -> JsString(req.creativeId)
            ))
          }
        }
      }
    }

  private given Timeout = Timeout(3.seconds) // Longer timeout for auction operations

  private def adServerRef(siteId: String): EntityRef[AdServer.Command] =
    sharding.entityRefFor(AdServer.TypeKey, siteId)

  private def auctioneerRef(siteId: String): EntityRef[AuctioneerEntity.Command] =
    sharding.entityRefFor(AuctioneerEntity.TypeKey, siteId)

  private def categoryBidderRef(categoryId: String): EntityRef[CategoryBidderEntity.Command] =
    sharding.entityRefFor(CategoryBidderEntity.TypeKey, categoryId)

  private def campaignRef(advertiserId: String, campaignId: String): EntityRef[CampaignEntity.Command] =
    sharding.entityRefFor(CampaignEntity.TypeKey, s"$advertiserId|$campaignId")

  private def serveIndexKey(siteId: String, slotId: String): String =
    s"$siteId|$slotId"
}
