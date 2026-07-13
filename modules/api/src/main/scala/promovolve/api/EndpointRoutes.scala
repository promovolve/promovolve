package promovolve.api

import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ ContentTypes, HttpEntity }
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout
import promovolve.BudgetEvent
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }

/** Server implementation - wires endpoints to business logic */
class EndpointRoutes(
    sharding: ClusterSharding,
    serveIndex: ActorRef[promovolve.publisher.delivery.ServeIndexDData.Cmd],
    categoryVerificationClient: Option[promovolve.publisher.assessment.CategoryVerificationClient] = None,
    creativeAssessor: Option[ActorRef[promovolve.publisher.assessment.CreativeAssessor.Command]] = None,
    imageAssetRepo: Option[promovolve.publisher.ImageAssetRepo] = None,
    creativeRepo: Option[promovolve.publisher.CreativeRepo] = None,
    imageStorage: Option[promovolve.publisher.assets.ImageStorage] = None,
    cdnBaseUrl: String,
    advertiserEmailRepo: Option[promovolve.advertiser.AdvertiserEmailRepo] = None,
    publisherEmailRepo: Option[promovolve.publisher.PublisherEmailRepo] = None,
    budgetEventTopic: ActorRef[org.apache.pekko.actor.typed.pubsub.Topic.Command[BudgetEvent]] = null,
    imageValidator: promovolve.publisher.ImageValidator = promovolve.publisher.IABImageValidator.Default,
    pendingEventHub: Option[ActorRef[PendingEventHub.Command]] = None,
    dashboardDb: Option[slick.jdbc.PostgresProfile.backend.Database] = None,
    lpExtractor: Option[promovolve.creative.LPExtractor] = None,
    lpAnalyzer: Option[promovolve.browser.LPAnalyzer] = None,
    creativeProcessor: Option[ActorRef[CreativeProcessor.Command]] = None,
    advertiserAssetRepo: Option[promovolve.publisher.AdvertiserAssetRepo] = None,
    floorDecisionJournal: Option[promovolve.api.projection.FloorDecisionJournal] = None,
    trackingEventJournal: Option[promovolve.api.projection.TrackingEventJournal] = None,
    // Phase-1 LP offload: when true, analyze-lp dispatches to the
    // crawler-tier LPWorker instead of the in-process LPAnalyzer.
    lpWorkerEnabled: Boolean = false,
    lpWorkerNumWorkers: Int = 4
)(using system: ActorSystem[?])
    extends ApiJsonFormats {

  import ApiModels.*
  import promovolve.*
  import promovolve.advertiser.{ AdvertiserEntity, CampaignEntity }
  import promovolve.auction.{ AuctioneerEntity, CategoryBidderEntity }
  import promovolve.publisher.{ ImageAsset, PublisherEntity, ServeView, SiteEntity }
  import promovolve.publisher.{ Creative as PublisherCreative, CreativeStatus }
  import promovolve.publisher.delivery.{ AdServer, ServeIndexDData, TrafficShapeTracker }
  import promovolve.taxonomy.TaxonomyRankerEntity

  lazy val routes: Route = {
    import org.apache.pekko.http.scaladsl.server.Directives.*
    concat(
      openApiRoute,
      concat(authRoutes*),
      concat(advertiserRoutes*),
      concat(additionalAdvertiserRoutes*),
      concat(campaignRoutes*),
      concat(additionalCampaignRoutes*),
      concat(campaignStatusRoutes*),
      concat(creativeRoutes*),
      concat(publisherRoutes*),
      concat(siteRoutes*),
      concat(newSiteRoutes*),
      concat(adProductBlocklistRoutes*),
      concat(pacingRoutes*),
      concat(internalRoutes*),
      concat(taxonomyRoutes*),
      concat(approvalQueueRoutes*),
      concat(auctionRoutes*),
      concat(assessmentRoutes*),
      concat(imageRoutes*),
      concat(swaggerRoutes*),
      deleteCreativeRoute,
      deleteCampaignRoute,
      lpAnalysisRoute,
      rewriteSectionsRoute,
      generateLayoutRoute,
      generateLayoutPairRoute,
      rewriteCopyRoute,
      advertiserAssetsRoute,
      reprocessCreativeRoute,
      getDraftRoute,
      layoutTemplatesRoute
    )
  }

  /**
   * Catalog of layout templates the LP-to-Creative flow exposes as
   * a picker on its first step. Server is the source of truth for
   * the Gemini-side slot specs; the dashboard renders the picker
   * from this list and posts the chosen template id back through
   * the create-creative form so the auto-layout call can incorporate
   * it. Returns the slot spec verbatim so a client could also
   * render it for debugging without an extra round-trip.
   */
  private def layoutTemplatesRoute: Route = {
    import org.apache.pekko.http.scaladsl.server.Directives.*
    import spray.json.{ JsArray, JsObject, JsString, JsValue, JsNull }
    import promovolve.creative.LayoutTemplates as LT
    path("v1" / "layout-templates") {
      get {
        val body = JsObject(
          "templates" -> JsArray(LT.all.map { t =>
            JsObject(
              "id" -> JsString(t.id),
              "name" -> JsString(t.name),
              "description" -> JsString(t.description),
              "orientation" -> JsString(LT.orientationToWire(t.orientation)),
              "slots" -> JsArray(t.slots.map { s =>
                JsObject(
                  "role" -> JsString(LT.roleToWire(s.role)),
                  "region" -> JsString(LT.regionToWire(s.region)),
                  "prominence" -> s.prominence.fold[JsValue](JsNull)(p => JsString(p.toString.toLowerCase))
                )
              })
            )
          })
        ).compactPrint
        complete(HttpEntity(ContentTypes.`application/json`, body))
      }
    }
  }

  /**
   * Fetch the raw author-facing state of a creative — used when the
   * advertiser reopens a draft in the designer. Returns just the
   * fields the designer needs to rehydrate; avoids widening the public
   * listCreatives response with pagesJson.
   */
  private def getDraftRoute: Route = {
    import org.apache.pekko.http.scaladsl.server.Directives.*
    import org.apache.pekko.http.scaladsl.model.StatusCodes
    import spray.json.{ JsObject, JsString, JsValue, JsNull }
    // IDOR guard: keyed by an owner path segment (advertiserId+campaignId,
    // pinned by the BFF) and verified against the creative's own owner —
    // mirrors deleteCreativeRoute. The old owner-less /v1/creatives/{id}/draft
    // leaked pagesJson/landingUrl/config for any creative.
    pathPrefix("v1" / "advertisers" / Segment / "campaigns" / Segment / "creatives" / Segment / "draft") {
      (advertiserId, campaignId, creativeId) =>
        get {
          creativeRepo match {
            case Some(repo) =>
              onComplete(repo.get(creativeId)) {
                case scala.util.Success(Some(c)) if c.advertiserId == advertiserId && c.campaignId == campaignId =>
                  val body = JsObject(
                    "creativeId" -> JsString(c.creativeId),
                    "name" -> JsString(c.name),
                    "landingUrl" -> JsString(c.landingUrl),
                    "pagesJson" -> c.pagesJson.fold[JsValue](JsNull)(JsString(_)),
                    "bannerConfigJson" -> c.bannerConfigJson.fold[JsValue](JsNull)(JsString(_)),
                    "lpTextSnapshot" -> c.lpTextSnapshot.fold[JsValue](JsNull)(JsString(_)),
                    "status" -> JsString(c.status.toString)
                  ).compactPrint
                  complete(HttpEntity(ContentTypes.`application/json`, body))
                case scala.util.Success(_) =>
                  // Missing OR owned-by-someone-else — same 404 so we never
                  // disclose the existence of a foreign creative id.
                  complete(StatusCodes.NotFound -> """{"error":"not_found"}""")
                case scala.util.Failure(ex) =>
                  complete(StatusCodes.InternalServerError -> s"""{"error":"${ex.getMessage}"}""")
              }
            case None =>
              complete(StatusCodes.ServiceUnavailable -> """{"error":"repo_unavailable"}""")
          }
        }
    }
  }

  import org.apache.pekko.http.scaladsl.server.Directives.*
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  private def deleteCreativeRoute: Route =
    pathPrefix("v1" / "advertisers" / Segment / "campaigns" / Segment / "creatives" / Segment) {
      (advertiserId, campaignId, creativeId) =>
        delete {
          val cid = CreativeId(creativeId)

          // IDOR guard: the delete below removes the creative row by
          // creative_id alone. Without this ownership check any advertiser
          // could delete another's creative. Verify the creative actually
          // belongs to this advertiser+campaign first (mirrors the same check
          // updateCreativeStatusLogic already enforces); 404 otherwise so we
          // don't disclose foreign creative ids.
          val ownedF: Future[Boolean] = creativeRepo match {
            case Some(repo) => repo.get(creativeId).map {
                case Some(cr) => cr.advertiserId == advertiserId && cr.campaignId == campaignId
                case None     => false
              }
            case None => Future.successful(false)
          }

          // IMPORTANT: build the mutating Futures *inside* the owned branch.
          // A `val xF = …Future…` starts running the moment it is defined, so
          // creating them before `ownedF` resolves would unassign/delete even
          // for a non-owner — exactly the IDOR this guard exists to prevent.
          val deleteType = "async" // determined at runtime
          val resultF: Future[Either[Unit, Unit]] = ownedF.flatMap {
            case false => Future.successful(Left(()))
            case true  =>
              // Unassign from campaign entity
              val unassignF = campaignRef(advertiserId, campaignId)
                .ask(CampaignEntity.UnassignCreatives(Set(cid), _))(using Timeout(5.seconds))

              // Check if creative has impressions, then delete accordingly
              val hasImpressionsF: Future[Boolean] = dashboardDb match {
                case Some(db) =>
                  import slick.jdbc.PostgresProfile.api.*
                  val query =
                    sql"""SELECT impressions FROM creative_stats WHERE creative_id = $creativeId AND campaign_id = $campaignId LIMIT 1"""
                      .as[Long].headOption
                  db.run(query).map(_.exists(_ > 0))
                case None => Future.successful(false)
              }

              val deleteF = hasImpressionsF.flatMap { hasImpressions =>
                if (hasImpressions) {
                  // Soft delete: mark as Deleted, keep DB row for tracking
                  creativeRepo.map(_.delete(creativeId)).getOrElse(Future.successful(()))
                } else {
                  // Hard delete: remove DB row entirely (never delivered).
                  // Scope by advertiser_id + campaign_id as defense-in-depth so the
                  // statement can't touch a foreign row even if the guard regresses.
                  dashboardDb match {
                    case Some(db) =>
                      import slick.jdbc.PostgresProfile.api.*
                      db.run(
                        sqlu"""DELETE FROM creative WHERE creative_id = $creativeId AND advertiser_id = $advertiserId AND campaign_id = $campaignId""").map(
                        _ => ())
                    case None => Future.successful(())
                  }
                }
              }

              unassignF.zip(deleteF).map(_ => Right(()))
          }
          onComplete(resultF) {
            case scala.util.Success(Left(_)) =>
              complete(org.apache.pekko.http.scaladsl.model.StatusCodes.NotFound,
                org.apache.pekko.http.scaladsl.model.HttpEntity(
                  org.apache.pekko.http.scaladsl.model.ContentTypes.`application/json`,
                  s"""{"error":"not_found","creativeId":"$creativeId"}"""))
            case scala.util.Success(Right(_)) =>
              complete(org.apache.pekko.http.scaladsl.model.StatusCodes.OK,
                org.apache.pekko.http.scaladsl.model.HttpEntity(
                  org.apache.pekko.http.scaladsl.model.ContentTypes.`application/json`,
                  s"""{"status":"deleted","type":"$deleteType","creativeId":"$creativeId"}"""))
            case scala.util.Failure(ex) =>
              system.log.warn("Creative {} delete ({}) entity unassign failed: {}", creativeId, deleteType,
                ex.getMessage)
              complete(org.apache.pekko.http.scaladsl.model.StatusCodes.OK,
                org.apache.pekko.http.scaladsl.model.HttpEntity(
                  org.apache.pekko.http.scaladsl.model.ContentTypes.`application/json`,
                  s"""{"status":"deleted","type":"$deleteType","creativeId":"$creativeId"}"""))
          }
        }
    }

  /**
   * Logically delete a campaign. Mirrors the creative delete contract:
   * the campaign is marked Deleted (which stops it bidding — canBid
   * requires Active) and its creatives are soft-deleted, but every
   * tracking/report row is left intact so historical reports survive.
   * `path` (not `pathPrefix`) so this never shadows the longer
   * `.../campaigns/{id}/creatives/{id}` delete route.
   */
  private def deleteCampaignRoute: Route =
    path("v1" / "advertisers" / Segment / "campaigns" / Segment) { (advertiserId, campaignId) =>
      delete {
        // IDOR guard: this route soft-deletes every creative returned by
        // getByCampaign(campaignId) (keyed by campaign alone), so without an
        // ownership check one advertiser could wipe another's creatives.
        // Confirm ownership before mutating anything; 404 otherwise.
        val deleteF: Future[Either[ErrorResponse, Unit]] =
          requireOwnedCampaign(advertiserId, campaignId).flatMap {
            case Left(err) => Future.successful(Left(err))
            case Right(_)  =>
              // Mark the campaign Deleted (idempotent — UpdateStatus just persists).
              val statusF =
                campaignRef(advertiserId, campaignId)
                  .ask(CampaignEntity.UpdateStatus(CampaignEntity.Status.Deleted, _))(using Timeout(5.seconds))

              // Soft-delete the campaign's creatives so they leave auctions and
              // creative lists; DB rows stay (report data is preserved).
              val creativesF: Future[Unit] = creativeRepo match {
                case Some(repo) =>
                  repo.getByCampaign(campaignId).flatMap { creatives =>
                    Future.sequence(creatives.map(c => repo.delete(c.creativeId))).map(_ => ())
                  }
                case None => Future.successful(())
              }
              statusF.zip(creativesF).map(_ => Right(()))
          }

        onComplete(deleteF) {
          case scala.util.Success(Right(_)) =>
            complete(org.apache.pekko.http.scaladsl.model.StatusCodes.OK,
              org.apache.pekko.http.scaladsl.model.HttpEntity(
                org.apache.pekko.http.scaladsl.model.ContentTypes.`application/json`,
                s"""{"status":"deleted","campaignId":"$campaignId"}"""))
          case scala.util.Success(Left(_)) =>
            complete(org.apache.pekko.http.scaladsl.model.StatusCodes.NotFound,
              org.apache.pekko.http.scaladsl.model.HttpEntity(
                org.apache.pekko.http.scaladsl.model.ContentTypes.`application/json`,
                s"""{"error":"not_found","campaignId":"$campaignId"}"""))
          case scala.util.Failure(ex) =>
            system.log.warn("Campaign {} delete failed: {}", campaignId, ex.getMessage)
            complete(org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError,
              org.apache.pekko.http.scaladsl.model.HttpEntity(
                org.apache.pekko.http.scaladsl.model.ContentTypes.`application/json`,
                s"""{"error":"delete_failed"}"""))
        }
      }
    }

  // Async LP-analysis job store: `start` launches analyze() in the background
  // and returns a jobId; the client polls `status`. Avoids a long synchronous
  // request blocking the Designer transition. Bounded by lazy TTL eviction.
  private sealed trait AnalyzeJobState
  private case object AnalyzePending extends AnalyzeJobState
  private final case class AnalyzeDone(result: AnalyzeLPResponse) extends AnalyzeJobState
  private final case class AnalyzeFailed(error: String) extends AnalyzeJobState
  private val analyzeJobs = scala.collection.concurrent.TrieMap.empty[String, (AnalyzeJobState, Long)]
  private val AnalyzeJobTtlMs = 10 * 60 * 1000L

  // Early viewport PNG per job, captured by analyze() before extraction
  // finishes and served via analyze-lp/screenshot — see AnalyzeLP loading
  // background. Keyed by jobId; evicted on the same TTL as analyzeJobs.
  private val analyzeScreenshots = scala.collection.concurrent.TrieMap.empty[String, (Array[Byte], Long)]

  // Result cache + in-flight coalescing, keyed by (url, strategy).
  // The editor auto-analyzes on every open, so the same LP was getting
  // a fresh Playwright navigation (full page + subresource fetch
  // against the advertiser's site) per visit. LPs change rarely: serve
  // a completed analysis for AnalyzeCacheTtlMs, and share one running
  // analyze() between concurrent starts for the same LP. Manual
  // Analyze clicks send force=true and bypass the cache (then refresh
  // it), so "re-read my LP, I just changed it" always works. Cache is
  // per-api-node — a miss on another node just re-analyzes.
  private val analyzeCache = scala.collection.concurrent.TrieMap.empty[(String, String), (AnalyzeLPResponse, Long)]
  private val analyzeInFlight = scala.collection.concurrent.TrieMap.empty[(String, String), Future[AnalyzeLPResponse]]
  private val AnalyzeCacheTtlMs = 10 * 60 * 1000L

  // Phase-1 LP offload: with lp-workers enabled the worker uploads the hero
  // screenshot to R2 and the result carries its URL (not bytes); /screenshot
  // redirects to it. Keyed by jobId, same TTL as analyzeScreenshots.
  private val analyzeScreenshotUrls = scala.collection.concurrent.TrieMap.empty[String, (String, Long)]
  // Cache the hero screenshot per (url, strategy) too. Without this a CACHED
  // re-analysis (subsequent attempt for the same LP) resolves from analyzeCache
  // WITHOUT running, so no screenshot exists for its new jobId — the loading
  // backdrop only showed the very first time. Right(url) for the worker path,
  // Left(bytes) for in-process; same TTL as analyzeCache.
  private val analyzeShotCache =
    scala.collection.concurrent.TrieMap.empty[(String, String), (Either[Array[Byte], String], Long)]
  private val lpAskTimeout: Timeout =
    Timeout(scala.concurrent.duration.Duration(120, java.util.concurrent.TimeUnit.SECONDS))

  /**
   * Dispatch the analysis to the crawler-tier LPWorker (off the bid/serve
   * JVMs) and adapt its reply. A per-job progress sink stamps the EARLY
   * screenshot URL the worker streams the moment it lands, so screenshotReady
   * flips true mid-analysis and the "Opening your landing page…" backdrop
   * shows DURING the run (not only at completion); the finished result
   * re-confirms it.
   */
  private def runViaWorker(url: String, strat: String, jobId: String): Future[AnalyzeLPResponse] = {
    val idx = promovolve.browser.LPWorker.workerIndexFor(url, lpWorkerNumWorkers)
    val ref = sharding.entityRefFor(promovolve.browser.LPWorker.TypeKey, idx.toString)
    // systemActorOf is async (Future); the sink stamps the screenshot URL then
    // stops. LPAnalyzer screenshots once near the start, so it's short-lived.
    val sink = system.systemActorOf(
      org.apache.pekko.actor.typed.scaladsl.Behaviors.receiveMessage[promovolve.browser.LPWorker.AnalyzeLPProgress] {
        p =>
          analyzeScreenshotUrls.update(jobId, (p.screenshotUrl, System.currentTimeMillis()))
          org.apache.pekko.actor.typed.scaladsl.Behaviors.stopped
      },
      s"lp-progress-$jobId"
    )
    ref.ask[promovolve.browser.LPWorker.AnalyzeLPDone] { replyTo =>
      promovolve.browser.LPWorker.AnalyzeLP(url, strat, replyTo, sink)
    }(using lpAskTimeout).map { done =>
      done.screenshotUrl.foreach(u => analyzeScreenshotUrls.update(jobId, (u, System.currentTimeMillis())))
      done.error match {
        case Some(e) => throw new RuntimeException(e)
        case None    => toAnalyzeLPResponse(done.result)
      }
    }
  }

  private def toAnalyzeLPResponse(result: promovolve.browser.LPAnalysisResult): AnalyzeLPResponse =
    AnalyzeLPResponse(
      url = result.url,
      sections = result.sections.map(s =>
        AnalyzeLPSection(
          heading = s.heading,
          text = s.text,
          images = s.images.map(i => AnalyzeLPImage(i.src, i.width, i.height, i.alt))
        )),
      dominantColor = result.dominantColor,
      textColor = result.textColor,
      palette = result.palette,
      fonts = result.fonts,
      originalFonts = result.originalFonts.map(f => OriginalFontOffer(f.family, f.weight, f.hash))
    )

  private val lpStoreLog = org.slf4j.LoggerFactory.getLogger("promovolve.api.LPAnalysisStore")

  /**
   * Compress, dedup-by-content-hash, store to R2 and register an
   * ImageAsset row. Returns (hash, s3Key, width, height). Shared by
   * the asset import-urls route and the LP-analysis byte-capture path
   * below.
   */
  private def storeImageAsset(rawBytes: Array[Byte], rawMime: String): Future[(String, String, Int, Int)] = {
    (imageStorage, imageAssetRepo) match {
      case (Some(storage), Some(imgRepo)) =>
        val (bytes, mime, w, h) = ImageCompression.compress(rawBytes, rawMime)
        val hash = java.security.MessageDigest.getInstance("SHA-256")
          .digest(bytes).map("%02x".format(_)).mkString
        imgRepo.get(hash).flatMap {
          case Some(a) => Future.successful((hash, a.s3Key, a.width, a.height))
          case None    =>
            storage.store(hash, bytes, mime).flatMap { s3Key =>
              imgRepo.put(promovolve.publisher.ImageAsset(hash, s3Key, mime, w, h, Instant.now()))
                .map(_ => (hash, s3Key, w, h))
            }
        }
      case _ =>
        Future.failed(new RuntimeException("image storage not configured"))
    }
  }

  /**
   * Store the image bytes LPAnalyzer captured (from the context that
   * beat the origin's bot manager) and rewrite each section `src` to
   * the resulting CDN URL. Net effect: downstream `import-urls` and
   * CreativeProcessor see a pub-CDN URL and never re-fetch the
   * tarpitting origin server-side — the cause of the Designer
   * timeout. Only images actually referenced by a section are stored
   * (skips tracking pixels and other page assets). Best-effort: any
   * store failure leaves that one src on the original URL.
   */
  private def persistCapturedImages(
      result: promovolve.browser.LPAnalysisResult,
      captured: Map[String, promovolve.browser.LPCapturedImage]
  ): Future[promovolve.browser.LPAnalysisResult] = {
    if (captured.isEmpty || imageStorage.isEmpty || imageAssetRepo.isEmpty)
      Future.successful(result)
    else {
      val referenced: Set[String] = result.sections.flatMap(_.images.map(_.src)).toSet
      val toStore = captured.filter { case (url, _) => referenced.contains(url) }.toVector
      if (toStore.isEmpty) Future.successful(result)
      else {
        // Bound R2 upload concurrency. storeImageAsset issues one R2 upload per
        // image; the R2 HTTP client's pool is capped (pekko-http
        // max-open-requests, default 32). An image-heavy LP that fired ALL
        // uploads at once (the old Future.traverse) overflowed the pool and
        // every store failed. Process in batches so we never exceed it — costs
        // a few extra seconds on a big LP instead of failing.
        val MaxConcurrentUploads = 8
        toStore
          .grouped(MaxConcurrentUploads)
          .foldLeft(Future.successful(Vector.empty[Option[(String, String)]])) { (accF, batch) =>
            accF.flatMap { acc =>
              Future.traverse(batch) { case (url, img) =>
                storeImageAsset(img.bytes, img.mime)
                  .map { case (_, s3Key, _, _) => Some(url -> s"$cdnBaseUrl/$s3Key") }
                  .recover { case e =>
                    lpStoreLog.warn("LP image store failed url={} err={}", url, e.getMessage)
                    None
                  }
              }.map(acc ++ _)
            }
          }
          .map { pairs =>
            val urlToCdn = pairs.flatten.toMap
            lpStoreLog.info("LP analysis: stored {}/{} referenced images for {} → CDN-rewritten srcs",
              Integer.valueOf(urlToCdn.size), Integer.valueOf(toStore.size), result.url)
            if (urlToCdn.isEmpty) result
            else result.copy(sections = result.sections.map { sec =>
              sec.copy(images = sec.images.map { im =>
                urlToCdn.get(im.src).fold(im)(cdn => im.copy(src = cdn))
              })
            })
          }
      }
    }
  }

  private def lpAnalysisRoute: Route =
    pathPrefix("v1" / "creatives") {
      // Legacy synchronous analyze (kept for compatibility).
      path("analyze-lp") {
        post {
          entity(as[AnalyzeLPRequest]) { req =>
            lpAnalyzer match {
              case Some(analyzer) =>
                val strat = req.strategy.getOrElse("heading")
                val f =
                  if (lpWorkerEnabled) runViaWorker(req.url, strat, java.util.UUID.randomUUID().toString)
                  else analyzer.analyze(req.url, strat)
                    .flatMap { case (result, captured) =>
                      persistCapturedImages(result, captured.images).flatMap { r =>
                        // Quarantine the LP's own fonts on the in-process path too
                        // (the LPWorker runner does its own; see LPFontQuarantine).
                        imageStorage.fold(Future.successful(r))(st => LPFontQuarantine(r, captured, st))
                      }
                    }
                    .map(toAnalyzeLPResponse)
                onComplete(f) {
                  case scala.util.Success(response) => complete(response)
                  case scala.util.Failure(ex)       =>
                    complete(org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError,
                      ErrorResponse("analysis_failed", ex.getMessage))
                }
              case None =>
                complete(org.apache.pekko.http.scaladsl.model.StatusCodes.ServiceUnavailable,
                  ErrorResponse("not_configured", "LP analysis requires Playwright (crawler module)"))
            }
          }
        }
      } ~
      // Async start: kicks off analyze() in the background, returns a jobId now.
      // Cached + coalesced per (url, strategy) — see analyzeCache above.
      path("analyze-lp" / "start") {
        post {
          entity(as[AnalyzeLPRequest]) { req =>
            lpAnalyzer match {
              case Some(analyzer) =>
                val now = System.currentTimeMillis()
                analyzeJobs.filterInPlace { case (_, (_, ts)) => ts >= now - AnalyzeJobTtlMs }
                analyzeScreenshots.filterInPlace { case (_, (_, ts)) => ts >= now - AnalyzeJobTtlMs }
                analyzeScreenshotUrls.filterInPlace { case (_, (_, ts)) => ts >= now - AnalyzeJobTtlMs }
                analyzeCache.filterInPlace { case (_, (_, ts)) => ts >= now - AnalyzeCacheTtlMs }
                analyzeShotCache.filterInPlace { case (_, (_, ts)) => ts >= now - AnalyzeCacheTtlMs }
                val jobId = java.util.UUID.randomUUID().toString
                val strat = req.strategy.getOrElse("heading")
                val key = (req.url, strat)
                val force = req.force.contains(true)

                analyzeCache.get(key) match {
                  case Some((cached, _)) if !force =>
                    // Fresh enough — resolve the job immediately; the
                    // client's first status poll sees "done".
                    analyzeJobs.update(jobId, (AnalyzeDone(cached), now))
                    // Re-attach the cached hero screenshot to this jobId so the
                    // loading backdrop shows on a CACHED re-run too (not just
                    // the first analysis).
                    analyzeShotCache.get(key).foreach {
                      case (Right(url), _)  => analyzeScreenshotUrls.update(jobId, (url, now))
                      case (Left(bytes), _) => analyzeScreenshots.update(jobId, (bytes, now))
                    }
                  case _ =>
                    analyzeJobs.update(jobId, (AnalyzePending, now))
                    // Coalesce: if an identical analysis is already
                    // running (editor reopened mid-analysis, double
                    // submit), this job rides it instead of launching a
                    // second browser session. A forced refresh replaces
                    // the in-flight slot so it really re-fetches.
                    val run: Future[AnalyzeLPResponse] = {
                      if (force) analyzeInFlight.remove(key)
                      analyzeInFlight.getOrElseUpdate(key, {
                          val f =
                            if (lpWorkerEnabled) runViaWorker(req.url, strat, jobId)
                            else analyzer.analyze(req.url, strat,
                              onScreenshot = bytes =>
                                analyzeScreenshots.update(jobId, (bytes, System.currentTimeMillis())))
                              .flatMap { case (result, captured) =>
                                persistCapturedImages(result, captured.images).flatMap { r =>
                                  // Quarantine the LP's own fonts on the in-process path too
                                  // (the LPWorker runner does its own; see LPFontQuarantine).
                                  imageStorage.fold(Future.successful(r))(st => LPFontQuarantine(r, captured, st))
                                }
                              }
                              .map(toAnalyzeLPResponse)
                          f.onComplete { _ => analyzeInFlight.remove(key) }
                          f.foreach { resp =>
                            val ts = System.currentTimeMillis()
                            analyzeCache.update(key, (resp, ts))
                            // Snapshot this run's hero screenshot (URL from the
                            // worker path, bytes from in-process) keyed by
                            // (url, strategy) so a cached re-run can reuse it.
                            val shot: Option[Either[Array[Byte], String]] =
                              analyzeScreenshotUrls.get(jobId).map(s => Right(s._1))
                                .orElse(analyzeScreenshots.get(jobId).map(s => Left(s._1)))
                            shot.foreach(ref => analyzeShotCache.update(key, (ref, ts)))
                          }
                          f
                        })
                    }
                    run.onComplete {
                      case scala.util.Success(resp) =>
                        analyzeJobs.update(jobId, (AnalyzeDone(resp), System.currentTimeMillis()))
                      case scala.util.Failure(ex) =>
                        analyzeJobs.update(jobId,
                          (AnalyzeFailed(Option(ex.getMessage).getOrElse(ex.toString)), System.currentTimeMillis()))
                    }
                }
                complete(AnalyzeLPJob(jobId))
              case None =>
                complete(org.apache.pekko.http.scaladsl.model.StatusCodes.ServiceUnavailable,
                  ErrorResponse("not_configured", "LP analysis requires Playwright (crawler module)"))
            }
          }
        }
      } ~
      // Async status poll.
      path("analyze-lp" / "status" / Segment) { jobId =>
        get {
          val shotReady = analyzeScreenshots.contains(jobId) || analyzeScreenshotUrls.contains(jobId)
          analyzeJobs.get(jobId) match {
            case Some((AnalyzePending, _)) => complete(AnalyzeLPStatusResponse("pending", screenshotReady = shotReady))
            case Some((AnalyzeDone(r), _)) =>
              complete(AnalyzeLPStatusResponse("done", result = Some(r), screenshotReady = shotReady))
            case Some((AnalyzeFailed(e), _)) => complete(AnalyzeLPStatusResponse("failed", error = Some(e)))
            case None                        => complete(AnalyzeLPStatusResponse("not_found", error = Some("unknown or expired job")))
          }
        }
      } ~
      // Early viewport PNG for a job, captured before extraction finished.
      // Referenced as an <img>/background-image by the editor's loading
      // state; 404 until the screenshot lands (or after TTL eviction).
      path("analyze-lp" / "screenshot" / Segment) { jobId =>
        get {
          // LPWorker path: the screenshot is a CDN URL → redirect. Else the
          // in-process path stored PNG bytes → serve them.
          analyzeScreenshotUrls.get(jobId) match {
            case Some((url, _)) =>
              redirect(
                org.apache.pekko.http.scaladsl.model.Uri(url),
                org.apache.pekko.http.scaladsl.model.StatusCodes.Found)
            case None =>
              analyzeScreenshots.get(jobId) match {
                case Some((bytes, _)) =>
                  complete(org.apache.pekko.http.scaladsl.model.HttpEntity(
                    org.apache.pekko.http.scaladsl.model.MediaTypes.`image/png`, bytes))
                case None =>
                  complete(org.apache.pekko.http.scaladsl.model.StatusCodes.NotFound,
                    ErrorResponse("not_found", "no screenshot for job"))
              }
          }
        }
      }
    }

  private def generateLayoutRoute: Route = {
    import spray.json.{ JsArray, JsObject, JsString, JsValue, enrichString }
    pathPrefix("v1" / "creatives") {
      path("generate-layout") {
        post {
          entity(as[GenerateLayoutRequest]) { req =>
            lpExtractor match {
              case Some(extractor) =>
                val bp = promovolve.creative.BannerPage(
                  tag = req.page.tag, headline = req.page.headline, sub = req.page.sub,
                  body = req.page.body, accent = req.page.accent, bg = req.page.bg,
                  imgEmoji = req.page.imgEmoji, caption = req.page.caption,
                  img = req.page.img
                )
                // Resolve the dashboard's pre-generation choices into
                // prompt-ready bits before calling Gemini. Failures
                // (malformed brandKitJson, unknown templateId) fall
                // through silently — generation still works, it just
                // doesn't get the nudge.
                val brandColors: Vector[(String, String)] =
                  req.brandKitJson.filter(_.nonEmpty).flatMap { json =>
                    scala.util.Try {
                      val colorsArr: Vector[JsValue] = json.parseJson.asJsObject
                        .fields.get("colors")
                        .collect { case a: JsArray => a.elements }
                        .getOrElse(Vector.empty)
                      colorsArr.collect {
                        case obj: JsObject =>
                          val n = obj.fields.get("name").collect { case JsString(s) => s }.getOrElse("")
                          val v = obj.fields.get("value").collect { case JsString(s) => s }.getOrElse("")
                          (n, v)
                      }.collect { case (n, v) if n.nonEmpty && v.nonEmpty => (n, v) }
                    }.toOption
                  }.getOrElse(Vector.empty)
                val slotLine: Option[String] = req.templateId
                  .filter(_.nonEmpty)
                  .flatMap(promovolve.creative.LayoutTemplates.findById)
                  .map(promovolve.creative.LayoutTemplates.slotsAsPromptLine)
                  .filter(_.nonEmpty)
                val f = extractor.generateLayout(bp, req.aspect, req.mode, brandColors, slotLine).map { raw =>
                  val items = raw match {
                    case arr: JsArray => arr.elements.map(_.convertTo[LayoutItem])
                    case _            => Vector.empty[LayoutItem]
                  }
                  GenerateLayoutResponse(layout = items)
                }
                onComplete(f) {
                  case scala.util.Success(response) => complete(response)
                  case scala.util.Failure(ex)       =>
                    complete(org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError,
                      ErrorResponse("generate_layout_failed", ex.getMessage))
                }
              case None =>
                complete(org.apache.pekko.http.scaladsl.model.StatusCodes.ServiceUnavailable,
                  ErrorResponse("not_configured", "Layout generation requires GEMINI_API_KEY"))
            }
          }
        }
      }
    }
  }

  private def rewriteCopyRoute: Route = {
    import spray.json.{ JsObject, JsString }
    pathPrefix("v1" / "creatives") {
      path("rewrite-copy") {
        post {
          entity(as[RewriteCopyRequest]) { req =>
            lpExtractor match {
              case Some(extractor) =>
                val bp = promovolve.creative.BannerPage(
                  tag = req.page.tag, headline = req.page.headline, sub = req.page.sub,
                  body = req.page.body, accent = req.page.accent, bg = req.page.bg,
                  imgEmoji = req.page.imgEmoji, caption = req.page.caption,
                  img = req.page.img
                )
                val rewriteLog = org.slf4j.LoggerFactory.getLogger("rewriteCopy")
                val f = extractor.rewriteCopy(bp, req.lpContext).map { raw =>
                  // Diagnostic: surface what Gemini actually returned so we
                  // can spot wrapper objects, missing keys, or verbatim
                  // copies when the user reports "rewrite did nothing".
                  rewriteLog.info("[rewrite-copy] Gemini raw → {}", raw.compactPrint.take(800))
                  val obj = raw match { case o: JsObject => o; case _ => JsObject.empty }
                  val str = (key: String) =>
                    obj.fields.get(key) match {
                      case Some(JsString(s)) => s
                      case _                 => ""
                    }
                  // Empty strings on missing keys keep the response shape stable;
                  // the client falls back to the existing copy when a field comes
                  // back blank.
                  RewriteCopyResponse(
                    tag = str("tag"),
                    headline = str("headline"),
                    sub = str("sub"),
                    body = str("body")
                  )
                }
                onComplete(f) {
                  case scala.util.Success(response) => complete(response)
                  case scala.util.Failure(ex)       =>
                    complete(org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError,
                      ErrorResponse("rewrite_copy_failed", ex.getMessage))
                }
              case None =>
                complete(org.apache.pekko.http.scaladsl.model.StatusCodes.ServiceUnavailable,
                  ErrorResponse("not_configured", "Copy rewriting requires GEMINI_API_KEY"))
            }
          }
        }
      }
    }
  }

  private def generateLayoutPairRoute: Route = {
    import spray.json.{ JsArray, JsObject }
    pathPrefix("v1" / "creatives") {
      path("generate-layout-pair") {
        post {
          entity(as[GenerateLayoutPairRequest]) { req =>
            lpExtractor match {
              case Some(extractor) =>
                val bp = promovolve.creative.BannerPage(
                  tag = req.page.tag, headline = req.page.headline, sub = req.page.sub,
                  body = req.page.body, accent = req.page.accent, bg = req.page.bg,
                  imgEmoji = req.page.imgEmoji, caption = req.page.caption,
                  img = req.page.img
                )
                val f = extractor.generateLayoutPair(bp).map { raw =>
                  val obj = raw match { case o: JsObject => o; case _ => JsObject.empty }
                  val pickArr = (key: String) =>
                    obj.fields.get(key) match {
                      case Some(arr: JsArray) => arr.elements.map(_.convertTo[LayoutItem])
                      case _                  => Vector.empty[LayoutItem]
                    }
                  GenerateLayoutPairResponse(pc = pickArr("pc"), mobile = pickArr("mobile"))
                }
                onComplete(f) {
                  case scala.util.Success(response) => complete(response)
                  case scala.util.Failure(ex)       =>
                    complete(org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError,
                      ErrorResponse("generate_layout_pair_failed", ex.getMessage))
                }
              case None =>
                complete(org.apache.pekko.http.scaladsl.model.StatusCodes.ServiceUnavailable,
                  ErrorResponse("not_configured", "Layout generation requires GEMINI_API_KEY"))
            }
          }
        }
      }
    }
  }

  // Advertiser-owned image library. List / upload (base64) / import-from-URL / delete.
  // Binary goes to R2 via ImageStorage (deduped by SHA-256 in image_asset); ownership
  // rows live in advertiser_asset. Advertiser ID comes from path segment for now.
  private def advertiserAssetsRoute: Route = {
    import java.time.Instant
    import org.apache.pekko.http.scaladsl.model.*

    // Compress + dimension-clamp before hashing so the R2 object and
    // the dedup hash both reflect the optimised bytes. See
    // ImageCompression for the format policy (JPEG re-encode at
    // quality 0.82, PNG-alpha kept as PNG, GIF/WebP passed through).
    // Direct uploads from the designer already arrive as WebP via
    // the client-side asset-modal::prepareForUpload; URL imports and
    // LP-to-Creative Playwright scrapes come in raw and this is what
    // compresses them.
    // Delegates to the class-level storeImageAsset (shared with the
    // LP-analysis byte-capture path) — same compress → hash → dedup →
    // store pipeline.
    def storeIfNew(rawBytes: Array[Byte], rawMime: String): Future[(String, String, Int, Int)] =
      storeImageAsset(rawBytes, rawMime)

    def viewOf(a: promovolve.publisher.AdvertiserAsset, s3Key: String, mime: String, w: Int, h: Int)
        : AdvertiserAssetView =
      AdvertiserAssetView(
        id = a.id, filename = a.filename,
        cdnUrl = s"$cdnBaseUrl/$s3Key",
        mime = mime, width = w, height = h,
        createdAt = a.createdAt.toString
      )

    def buildView(a: promovolve.publisher.AdvertiserAsset): Future[Option[AdvertiserAssetView]] =
      imageAssetRepo match {
        case Some(imgRepo) =>
          imgRepo.get(a.imageHash).map {
            case Some(ia) => Some(viewOf(a, ia.s3Key, ia.mime, ia.width, ia.height))
            case None     => None
          }
        case None => Future.successful(None)
      }

    val log = org.slf4j.LoggerFactory.getLogger("promovolve.api.AssetRoutes")
    pathPrefix("v1" / "advertisers" / Segment / "assets") { advertiserId =>
      extractRequest { req =>
        log.info("assets route: {} {} advertiserId={} repoConfigured={}",
          req.method.value, req.uri.path, advertiserId, Boolean.box(advertiserAssetRepo.isDefined))
        advertiserAssetRepo match {
          case None =>
            complete(StatusCodes.ServiceUnavailable,
              ErrorResponse("not_configured", "Advertiser asset repository not configured"))
          case Some(repo) =>
            concat(
              // GET: list this advertiser's assets
              pathEndOrSingleSlash {
                get {
                  val f = repo.list(advertiserId).flatMap { rows =>
                    Future.sequence(rows.map(buildView)).map(_.flatten)
                  }.map(AdvertiserAssetListResponse(_))
                  onComplete(f) {
                    case scala.util.Success(r) => complete(r)
                    case scala.util.Failure(e) =>
                      complete(StatusCodes.InternalServerError,
                        ErrorResponse("list_failed", e.getMessage))
                  }
                } ~
                // POST: upload base64-encoded image
                post {
                  entity(as[UploadAssetRequest]) { req =>
                    val bytes = java.util.Base64.getDecoder.decode(req.imageBase64)
                    val f = storeIfNew(bytes, req.mimeType).flatMap { case (hash, s3Key, w, h) =>
                      repo.existsForHash(advertiserId, hash).flatMap {
                        case Some(existing) =>
                          Future.successful(UploadAssetResponse(viewOf(existing, s3Key, req.mimeType, w, h)))
                        case None =>
                          val a = promovolve.publisher.AdvertiserAsset(
                            id = promovolve.publisher.AdvertiserAsset.newId(),
                            advertiserId = advertiserId,
                            imageHash = hash,
                            filename = req.filename,
                            createdAt = Instant.now()
                          )
                          repo.put(a).map(_ =>
                            UploadAssetResponse(viewOf(a, s3Key, req.mimeType, w, h)))
                      }
                    }
                    onComplete(f) {
                      case scala.util.Success(r) => complete(r)
                      case scala.util.Failure(e) =>
                        complete(StatusCodes.InternalServerError,
                          ErrorResponse("upload_failed", e.getMessage))
                    }
                  }
                }
              },
              // POST /import-urls: download each URL and import
              path("import-urls") {
                post {
                  entity(as[ImportAssetUrlsRequest]) { req =>
                    log.info("import-urls: route hit advertiserId={} urls.size={} urls={}",
                      advertiserId, Integer.valueOf(req.urls.size), req.urls.map(_.url).mkString(", "))
                    // Each result echoes the input URL so the caller can
                    // build a sourceUrl → cdnUrl map. Failures land as
                    // (url, None) instead of being dropped, keeping the
                    // response order aligned with the input.
                    val importOne: ImportAssetUrl => Future[ImportAssetUrlResult] = u => {
                      // Accept: image/webp first so CDNs running format
                      // negotiation (Cloudinary f_auto, Imgix auto=format,
                      // Cloudflare Polish, …) hand us pre-compressed WebP
                      // instead of larger JPEG defaults. Mirrors
                      // CreativeProcessor's downloadAndStoreImage.
                      val reqHttp = HttpRequest(HttpMethods.GET, u.url).withHeaders(
                        org.apache.pekko.http.scaladsl.model.headers.`User-Agent`(
                          "Mozilla/5.0 (compatible; Promovolve/1.0)"),
                        org.apache.pekko.http.scaladsl.model.headers.Accept(
                          org.apache.pekko.http.scaladsl.model.MediaRange(
                            org.apache.pekko.http.scaladsl.model.MediaTypes.`image/webp`
                          ),
                          org.apache.pekko.http.scaladsl.model.MediaRange(
                            org.apache.pekko.http.scaladsl.model.MediaTypes.`image/jpeg`
                          ),
                          org.apache.pekko.http.scaladsl.model.MediaRange(
                            org.apache.pekko.http.scaladsl.model.MediaTypes.`image/png`
                          ),
                          org.apache.pekko.http.scaladsl.model.MediaRanges.`image/*`
                        )
                      )
                      // Pekko's singleRequest doesn't follow redirects by
                      // default; many customer image URLs 301/302 to a CDN
                      // and returning the redirect body got stored as a
                      // bogus "image" (18-byte text "Permanent Redirect"
                      // landed at the R2 key, with .bin extension since
                      // text/plain isn't an image MIME). Follow up to 5
                      // hops manually, then validate the final response
                      // is actually image bytes before storing.
                      def fetchFollowingRedirects(
                          req: org.apache.pekko.http.scaladsl.model.HttpRequest,
                          hopsLeft: Int
                      ): Future[org.apache.pekko.http.scaladsl.model.HttpResponse] = {
                        Http(system.toClassic).singleRequest(req).flatMap { r =>
                          val sc = r.status.intValue
                          if (sc >= 300 && sc < 400 && hopsLeft > 0) {
                            r.header[org.apache.pekko.http.scaladsl.model.headers.Location] match {
                              case Some(loc) =>
                                r.entity.discardBytes()
                                val next = loc.uri.resolvedAgainst(req.uri)
                                fetchFollowingRedirects(req.withUri(next), hopsLeft - 1)
                              case None =>
                                Future.successful(r) // no Location → bail with the 3xx
                            }
                          } else Future.successful(r)
                        }
                      }
                      fetchFollowingRedirects(reqHttp, 5).flatMap { resp =>
                        val mime = resp.entity.contentType.mediaType.toString
                        // Only image/* is a valid asset. Anything else
                        // (text/html error pages, text/plain redirect
                        // bodies, application/* downloads) is rejected
                        // here so we don't pollute the asset library.
                        val isImage = mime.startsWith("image/")
                        if (resp.status.isSuccess() && isImage) {
                          resp.entity.dataBytes
                            .runFold(org.apache.pekko.util.ByteString.empty)(_ ++ _)
                            .map(_.toArray[Byte])
                            .flatMap { bytes =>
                              storeIfNew(bytes, mime).flatMap { case (hash, s3Key, w, h) =>
                                val fname = u.filename.getOrElse {
                                  val idx = u.url.lastIndexOf('/')
                                  if (idx >= 0 && idx + 1 < u.url.length) u.url.substring(idx + 1).takeWhile(_ != '?')
                                  else "image"
                                }
                                repo.existsForHash(advertiserId, hash).flatMap {
                                  case Some(existing) =>
                                    log.info("import-urls: already owned url={} hash={}", u.url, hash)
                                    Future.successful(ImportAssetUrlResult(u.url,
                                      Some(viewOf(existing, s3Key, mime, w, h))))
                                  case None =>
                                    val a = promovolve.publisher.AdvertiserAsset(
                                      id = promovolve.publisher.AdvertiserAsset.newId(),
                                      advertiserId = advertiserId,
                                      imageHash = hash,
                                      filename = fname,
                                      createdAt = Instant.now()
                                    )
                                    repo.put(a).map { _ =>
                                      log.info("import-urls: imported url={} hash={} id={}", u.url, hash, a.id)
                                      ImportAssetUrlResult(u.url, Some(viewOf(a, s3Key, mime, w, h)))
                                    }
                                }
                              }
                            }
                        } else {
                          // Either non-2xx (couldn't follow further) or
                          // the bytes aren't an image. Log enough to
                          // diagnose without the body — bytes could be
                          // megabytes of binary if something genuinely
                          // 200'd with non-image content.
                          log.warn("import-urls: rejected url={} status={} mime={}",
                            u.url, resp.status, mime)
                          resp.entity.discardBytes()
                          Future.successful(ImportAssetUrlResult(u.url, None))
                        }
                      }.recover { case ex: Exception =>
                        log.warn("import-urls: failed url={} err={}", u.url, ex.getMessage)
                        ImportAssetUrlResult(u.url, None)
                      }
                    }
                    val f = Future.sequence(req.urls.map(importOne))
                      .map(ImportAssetUrlsResponse(_))
                    onComplete(f) {
                      case scala.util.Success(r) => complete(r)
                      case scala.util.Failure(e) =>
                        complete(StatusCodes.InternalServerError,
                          ErrorResponse("import_failed", e.getMessage))
                    }
                  }
                }
              },
              // POST /presigned-upload: get a short-lived PUT URL the
              // browser uploads bytes to directly. Skips dashboard +
              // core entirely on the data path. Pairs with /register
              // below — the browser calls register only after the PUT
              // succeeds, so partial / abandoned uploads leave no
              // advertiser_asset row (the orphan binary in R2 is
              // harmless and content-addressed).
              path("presigned-upload") {
                post {
                  entity(as[PresignedUploadRequest]) { req =>
                    (imageStorage, imageAssetRepo) match {
                      case (Some(storage), Some(imgRepo)) =>
                        val f = imgRepo.get(req.hash).flatMap {
                          case Some(existing) =>
                            // Hash already known — no upload needed,
                            // /register will use the existing s3Key.
                            Future.successful(PresignedUploadResponse(
                              uploadUrl = "",
                              s3Key = existing.s3Key,
                              alreadyExists = true
                            ))
                          case None =>
                            storage.presignPutUrl(req.hash, req.mimeType, ttlSeconds = 600)
                              .map { case (url, key) =>
                                PresignedUploadResponse(uploadUrl = url, s3Key = key, alreadyExists = false)
                              }
                        }
                        onComplete(f) {
                          case scala.util.Success(r) => complete(r)
                          case scala.util.Failure(e) =>
                            complete(StatusCodes.InternalServerError,
                              ErrorResponse("presign_failed", e.getMessage))
                        }
                      case _ =>
                        complete(StatusCodes.ServiceUnavailable,
                          ErrorResponse("not_configured", "Image storage or asset repo not configured"))
                    }
                  }
                }
              },
              // POST /register: record the asset row after the browser
              // PUTs to the presigned URL. Inserts the image_asset row
              // if the hash is new (the bytes are now at assets/{hash})
              // and the advertiser_asset row that grants this advertiser
              // ownership. Width/height are optional — if absent, the
              // server skips them in the image_asset record (decoder
              // had no bytes to inspect server-side).
              path("register") {
                post {
                  entity(as[RegisterAssetRequest]) { req =>
                    (imageStorage, imageAssetRepo) match {
                      case (Some(_), Some(imgRepo)) =>
                        val ext = req.mimeType match {
                          case "image/png"  => "png"
                          case "image/jpeg" => "jpg"
                          case "image/gif"  => "gif"
                          case "image/webp" => "webp"
                          case "video/mp4"  => "mp4"
                          case "video/webm" => "webm"
                          case _            => "bin"
                        }
                        val s3Key = s"assets/${req.hash}.$ext"
                        val w = req.width.getOrElse(0)
                        val h = req.height.getOrElse(0)
                        val f = imgRepo.get(req.hash).flatMap {
                          case Some(existing) =>
                            Future.successful((existing.s3Key, existing.mime, existing.width, existing.height))
                          case None =>
                            imgRepo.put(promovolve.publisher.ImageAsset(req.hash, s3Key, req.mimeType, w, h,
                              Instant.now()))
                              .map(_ => (s3Key, req.mimeType, w, h))
                        }.flatMap { case (key, mime, ww, hh) =>
                          repo.existsForHash(advertiserId, req.hash).flatMap {
                            case Some(existing) =>
                              Future.successful(RegisterAssetResponse(viewOf(existing, key, mime, ww, hh)))
                            case None =>
                              val a = promovolve.publisher.AdvertiserAsset(
                                id = promovolve.publisher.AdvertiserAsset.newId(),
                                advertiserId = advertiserId,
                                imageHash = req.hash,
                                filename = req.filename,
                                createdAt = Instant.now()
                              )
                              repo.put(a).map(_ => RegisterAssetResponse(viewOf(a, key, mime, ww, hh)))
                          }
                        }
                        onComplete(f) {
                          case scala.util.Success(r) => complete(r)
                          case scala.util.Failure(e) =>
                            complete(StatusCodes.InternalServerError,
                              ErrorResponse("register_failed", e.getMessage))
                        }
                      case _ =>
                        complete(StatusCodes.ServiceUnavailable,
                          ErrorResponse("not_configured", "Image storage or asset repo not configured"))
                    }
                  }
                }
              },
              // DELETE /{id}: remove ownership (keeps binary)
              path(Segment) { id =>
                delete {
                  val f = repo.delete(id, advertiserId)
                  onComplete(f) {
                    case scala.util.Success(_) => complete(StatusCodes.NoContent)
                    case scala.util.Failure(e) =>
                      complete(StatusCodes.InternalServerError,
                        ErrorResponse("delete_failed", e.getMessage))
                  }
                }
              }
            )
        }
      }
    }
  }

  private def rewriteSectionsRoute: Route =
    pathPrefix("v1" / "creatives") {
      path("rewrite-sections") {
        post {
          entity(as[RewriteSectionsRequest]) { req =>
            lpExtractor match {
              case Some(extractor) =>
                val sections = req.sections.map(s => (s.heading, s.text))
                def toPage(p: promovolve.creative.BannerPage) = ExtractedBannerPage(
                  tag = p.tag, headline = p.headline, sub = p.sub,
                  body = p.body, accent = p.accent, bg = p.bg,
                  imgEmoji = p.imgEmoji, caption = p.caption, img = p.img)
                // Synthesis path: with no chosen arc, ask Gemini which arcs FIT
                // this product (drops e.g. Problem→Solution for sweets), then
                // synthesize with the best — returning the curated menu + reason.
                // Explicit arc → just synthesize. Legacy → per-section rewrite.
                val f: scala.concurrent.Future[RewriteSectionsResponse] =
                  if (req.recommend.getOrElse(false))
                    // Curate-only: arc menu + reason, no copy generated.
                    extractor.recommendArcs(sections).map { case (best, fit, reason) =>
                      RewriteSectionsResponse(Vector.empty,
                        arc = Some(best), arcOptions = Some(fit), arcReason = Some(reason))
                    }
                  else if (req.synthesize.getOrElse(false))
                    req.arc match {
                      case Some(arcId) =>
                        extractor.synthesizeSections(sections, arcId, req.brief).map { pages =>
                          RewriteSectionsResponse(pages.map(toPage), arc = Some(arcId))
                        }
                      case None =>
                        extractor.recommendArcs(sections).flatMap { case (best, fit, reason) =>
                          extractor.synthesizeSections(sections, best, req.brief).map { pages =>
                            RewriteSectionsResponse(pages.map(toPage),
                              arc = Some(best), arcOptions = Some(fit), arcReason = Some(reason))
                          }
                        }
                    }
                  else
                    extractor.rewriteSections(sections).map { pages =>
                      RewriteSectionsResponse(pages.map(toPage))
                    }
                onComplete(f) {
                  case scala.util.Success(response) => complete(response)
                  case scala.util.Failure(ex)       =>
                    complete(org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError,
                      ErrorResponse("rewrite_failed", ex.getMessage))
                }
              case None =>
                complete(org.apache.pekko.http.scaladsl.model.StatusCodes.ServiceUnavailable,
                  ErrorResponse("not_configured", "Rewrite requires GEMINI_API_KEY"))
            }
          }
        }
      }
    }

  private val listAdvertisersLogic: ((Int, Int)) => Future[Either[ErrorResponse, AdvertiserList]] = {
    case (limit, offset) =>
      // Note: Listing all advertisers requires a database query. Use login endpoint instead.
      // This returns an empty list - users should login via POST /v1/login to get their advertiser.
      Future.successful(Right(AdvertiserList(
        data = Vector.empty,
        meta = Meta(total = 0, limit = limit, offset = offset)
      )))
  }

  // ----------------- Login Logic -----------------
  private val loginLogic: LoginRequest => Future[Either[ErrorResponse, LoginResponse]] = { req =>
    advertiserEmailRepo match {
      case None =>
        Future.successful(Left(ErrorResponse("not_configured", "Email login not configured")))

      case Some(emailRepo) =>
        emailRepo.findByEmail(req.email).flatMap {
          case Some(advertiserId) =>
            // Email found - fetch advertiser info
            val infoF: Future[AdvertiserEntity.AdvertiserInfo] =
              advertiserRef(advertiserId).ask(AdvertiserEntity.GetAdvertiserInfo(_))
            infoF
              .map { info =>
                Right(LoginResponse(
                  advertiserId = info.advertiserId.value,
                  name = info.name.value,
                  isNew = false
                ))
              }
              .recover { case _ =>
                Left(ErrorResponse("advertiser_not_found", s"Advertiser $advertiserId not found"))
              }

          case None =>
            // Email not found - create new advertiser
            import jkugiya.ulid.ULID
            val newId = ULID.getGenerator().base32()
            val name = req.email.split("@").headOption.getOrElse("New Advertiser")

            val updateF: Future[AdvertiserEntity.NameUpdated] =
              advertiserRef(newId).ask(AdvertiserEntity.UpdateName(Name(name), _))
            updateF
              .flatMap { _ =>
                // Associate email with new advertiser
                emailRepo.associate(req.email, newId).map { _ =>
                  Right(LoginResponse(
                    advertiserId = newId,
                    name = name,
                    isNew = true
                  ))
                }
              }
              .recover { case ex =>
                Left(ErrorResponse("create_failed", ex.getMessage))
              }
        }
    }
  }

  // ----------------- Publisher Login Logic -----------------
  private val publisherLoginLogic: PublisherLoginRequest => Future[Either[ErrorResponse, PublisherLoginResponse]] = {
    req =>
      publisherEmailRepo match {
        case None =>
          Future.successful(Left(ErrorResponse("not_configured", "Publisher email login not configured")))

        case Some(emailRepo) =>
          emailRepo.findByEmail(req.email).flatMap {
            case Some(publisherId) =>
              // Email found - fetch publisher info
              val infoF: Future[PublisherEntity.PublisherInfo] =
                publisherRef(publisherId).ask(PublisherEntity.GetPublisher(_))
              infoF
                .map { info =>
                  Right(PublisherLoginResponse(
                    publisherId = info.publisherId.value,
                    name = req.email.split("@").headOption.getOrElse(publisherId),
                    isNew = false
                  ))
                }
                .recover { case _ =>
                  Left(ErrorResponse("publisher_not_found", s"Publisher $publisherId not found"))
                }

            case None =>
              // Email not found - create new publisher
              import jkugiya.ulid.ULID
              val newId = ULID.getGenerator().base32()
              val name = req.email.split("@").headOption.getOrElse("New Publisher")

              // Just accessing the publisher entity will create it (DurableStateBehavior)
              val getF: Future[PublisherEntity.PublisherInfo] =
                publisherRef(newId).ask(PublisherEntity.GetPublisher(_))
              getF
                .flatMap { _ =>
                  // Associate email with new publisher
                  emailRepo.associate(req.email, newId).map { _ =>
                    Right(PublisherLoginResponse(
                      publisherId = newId,
                      name = name,
                      isNew = true
                    ))
                  }
                }
                .recover { case ex =>
                  Left(ErrorResponse("create_failed", ex.getMessage))
                }
          }
      }
  }

  // ----------------- Entity Refs -----------------
  private val getAdvertiserLogic: String => Future[Either[ErrorResponse, AdvertiserDetail]] =
    advertiserId => {
      val infoF: Future[AdvertiserEntity.AdvertiserInfo] =
        advertiserRef(advertiserId).ask(AdvertiserEntity.GetAdvertiserInfo(_))
      val budgetF: Future[AdvertiserEntity.AdvertiserBudgetStatus] =
        advertiserRef(advertiserId).ask(AdvertiserEntity.GetBudgetStatus(_))

      (for {
        info <- infoF
        budget <- budgetF
      } yield Right(
        AdvertiserDetail(
          id = info.advertiserId.value,
          name = info.name.value,
          status = info.status.toString.toLowerCase,
          campaignIds = info.campaignIds.map(_.value).toVector,
          siteDomainBlocklist = info.siteDomainBlocklist.toVector.sorted,
          budget = BudgetStatus(
            dailyBudget = formatMoney(budget.dailyBudget.value),
            spendToday = formatMoney(budget.spendToday.value),
            remaining = formatMoney(budget.remaining.value),
            withinBudget = budget.withinBudget
          ),
          createdAt = nowIso,
          updatedAt = nowIso
        )
      )).recover { case _: java.util.concurrent.TimeoutException =>
        Left(ErrorResponse("advertiser_not_found", s"Advertiser $advertiserId not found"))
      }
    }
  private val updateAdvertiserBudgetLogic
      : ((String, UpdateBudgetRequest)) => Future[Either[ErrorResponse, AdvertiserDetail]] = {
    case (advertiserId, req) =>
      val budget = Budget(BigDecimal(req.dailyBudget))
      val updateF: Future[AdvertiserEntity.DailyBudgetUpdated] =
        advertiserRef(advertiserId).ask(AdvertiserEntity.UpdateDailyBudget(budget, _))

      updateF
        .flatMap { _ => getAdvertiserLogic(advertiserId) }
        .recover { case ex =>
          Left(ErrorResponse("update_failed", ex.getMessage))
        }
  }

  // ----------------- Helpers -----------------
  private val blockSitesLogic: ((String, BlockDomainsRequest)) => Future[Either[ErrorResponse, AdvertiserDetail]] = {
    case (advertiserId, req) =>
      val domains = req.domains.toSet
      (for {
        _ <- advertiserRef(advertiserId).ask[AdvertiserEntity.DomainsBlocked](AdvertiserEntity.BlockDomains(domains, _))
        info <- advertiserRef(advertiserId).ask[AdvertiserEntity.AdvertiserInfo](AdvertiserEntity.GetAdvertiserInfo(_))
        budget <-
          advertiserRef(advertiserId).ask[AdvertiserEntity.AdvertiserBudgetStatus](AdvertiserEntity.GetBudgetStatus(_))
      } yield Right(AdvertiserDetail(
        id = info.advertiserId.value,
        name = info.name.value,
        status = info.status.toString.toLowerCase,
        campaignIds = info.campaignIds.map(_.value).toVector,
        siteDomainBlocklist = info.siteDomainBlocklist.toVector.sorted,
        budget = BudgetStatus(
          dailyBudget = formatMoney(budget.dailyBudget.value),
          spendToday = formatMoney(budget.spendToday.value),
          remaining = formatMoney(budget.remaining.value),
          withinBudget = budget.withinBudget
        ),
        createdAt = nowIso,
        updatedAt = nowIso
      ))).recover { case ex => Left(ErrorResponse("block_domains_failed", ex.getMessage)) }
  }
  private val unblockSitesLogic
      : ((String, UnblockDomainsRequest)) => Future[Either[ErrorResponse, AdvertiserDetail]] = {
    case (advertiserId, req) =>
      val domains = req.domains.toSet
      (for {
        _ <- advertiserRef(advertiserId).ask[AdvertiserEntity.DomainsUnblocked](AdvertiserEntity.UnblockDomains(domains,
          _))
        info <- advertiserRef(advertiserId).ask[AdvertiserEntity.AdvertiserInfo](AdvertiserEntity.GetAdvertiserInfo(_))
        budget <-
          advertiserRef(advertiserId).ask[AdvertiserEntity.AdvertiserBudgetStatus](AdvertiserEntity.GetBudgetStatus(_))
      } yield Right(AdvertiserDetail(
        id = info.advertiserId.value,
        name = info.name.value,
        status = info.status.toString.toLowerCase,
        campaignIds = info.campaignIds.map(_.value).toVector,
        siteDomainBlocklist = info.siteDomainBlocklist.toVector.sorted,
        budget = BudgetStatus(
          dailyBudget = formatMoney(budget.dailyBudget.value),
          spendToday = formatMoney(budget.spendToday.value),
          remaining = formatMoney(budget.remaining.value),
          withinBudget = budget.withinBudget
        ),
        createdAt = nowIso,
        updatedAt = nowIso
      ))).recover { case ex => Left(ErrorResponse("unblock_domains_failed", ex.getMessage)) }
  }

  private val getServedSitesLogic: ((String, Int)) => Future[Either[ErrorResponse, ServedSitesResponse]] = {
    case (advertiserId, limit) =>
      import slick.jdbc.PostgresProfile.api.*
      val capped = math.max(1, math.min(limit, 100))
      dashboardDb match {
        case None =>
          Future.successful(Right(ServedSitesResponse(Vector.empty)))
        case Some(db) =>
          val rowsF: Future[Vector[(String, Long)]] =
            db.run(
              sql"""SELECT site_id, COUNT(*) AS impressions
                    FROM tracking_events
                    WHERE advertiser_id = $advertiserId AND event_type = 'impression'
                    GROUP BY site_id
                    ORDER BY impressions DESC
                    LIMIT $capped""".as[(String, Long)]
            ).map(_.toVector)

          rowsF.flatMap { rows =>
            // Look up each site's domain in parallel. Sites that no longer exist
            // (deleted by publisher) or whose config can't be fetched are dropped
            // — they wouldn't be useful as blocklist targets.
            Future
              .sequence(rows.map { case (siteId, imps) =>
                siteRef(siteId)
                  .ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_)).map(_.config)
                  .map(_.map(cfg => ServedSite(siteId, cfg.domain, imps)))
                  .recover { case _ => None }
              })
              .map(items => Right(ServedSitesResponse(items.flatten)))
          }.recover { case ex =>
            Left(ErrorResponse("served_sites_failed", ex.getMessage))
          }
      }
  }

  // ----------------- Advertiser Logic -----------------
  private val listCampaignsLogic: ((String, Int, Int)) => Future[Either[ErrorResponse, CampaignList]] = {
    case (advertiserId, limit, offset) =>
      val infoF: Future[AdvertiserEntity.AdvertiserInfo] =
        advertiserRef(advertiserId).ask(AdvertiserEntity.GetAdvertiserInfo(_))

      infoF.flatMap { info =>
        // Fetch every campaign so deleted ones can be filtered out before
        // paginating — a logically-deleted campaign (status=Deleted) is
        // hidden from the management list but its tracking/report rows stay.
        val campaignFs = info.campaignIds.toVector.map { campId =>
          getCampaignLogic((advertiserId, campId.value))
            .map(_.toOption)
            .recover { case _ => None }
        }

        Future.sequence(campaignFs).map { results =>
          val live = results.flatten.filterNot(_.status == "deleted")
          val total = live.size
          val paged = live.drop(offset).take(limit)
          Right(CampaignList(
            data = paged,
            meta = Meta(total = total, limit = limit, offset = offset)
          ))
        }
      }.recover { case _ =>
        Left(ErrorResponse("advertiser_not_found", s"Advertiser $advertiserId not found"))
      }
  }
  private val getCampaignLogic: ((String, String)) => Future[Either[ErrorResponse, Campaign]] = {
    case (advertiserId, campaignId) =>
      val infoF: Future[CampaignEntity.CampaignInfo] =
        campaignRef(advertiserId, campaignId).ask(CampaignEntity.GetCampaign(_))
      val budgetF: Future[CampaignEntity.BudgetInfo] =
        campaignRef(advertiserId, campaignId).ask(CampaignEntity.GetBudgetInfo(_))

      (for {
        info <- infoF
        budget <- budgetF
      } yield {
        // Log warning if adProductCategory is missing (campaign created before this field was added)
        if (info.adProductCategory.isEmpty) {
          system.log.warn(
            "Campaign {} has no adProductCategory - likely created before field was added. Update the campaign to set it.",
            campaignId
          )
        }

        // Resolve taxonomy ids → human names so the dashboard never shows
        // raw IAB numbers. targetCategoryNames stays index-aligned with
        // targetCategories; fall back to the id when a name can't resolve.
        val sortedCats = info.categories.iterator.map(_.value).toVector.sorted
        val sortedCatNames = sortedCats.map(id =>
          promovolve.taxonomy.TieredCategory.get(id).map(_.name).getOrElse(id))
        // Gemini-derived fallback set (index-aligned name resolution, same
        // as the explicit set) so the edit form can render the suggested
        // chips when explicit targeting is empty.
        val sortedSuggested = info.suggestedCategories.iterator.map(_.value).toVector.sorted
        val sortedSuggestedNames = sortedSuggested.map(id =>
          promovolve.taxonomy.TieredCategory.get(id).map(_.name).getOrElse(id))
        val adProductName = info.adProductCategory.flatMap(apc =>
          promovolve.taxonomy.AdProductTaxonomy.get(apc.value).map(_.name))

        Right(
          Campaign(
            id = campaignId,
            advertiserId = advertiserId,
            // Persisted display name; legacy campaigns (created before
            // the name was backed by the entity) read back "" and fall
            // through to the id.
            name = if (info.name.nonEmpty) info.name else campaignId,
            status = info.status.toString.toLowerCase,
            budget = CampaignBudget(
              daily = formatMoney(info.dailyBudget.value),
              lifetime = None
            ),
            schedule = CampaignSchedule(
              startAt = info.startAt.toString,
              endAt = info.endAt.map(_.toString)
            ),
            adProductCategory = info.adProductCategory.map(_.value).getOrElse(""),
            bidding = CampaignBidding(
              strategy = "fixed",
              maxCpm = formatMoney(info.maxCpm.value)
            ),
            landingUrl = info.landingUrl.getOrElse(""),
            creativeIds = info.creativeIds.map(_.value).toVector,
            createdAt = nowIso,
            updatedAt = nowIso,
            spent = Some(formatMoney(budget.spent.value)),
            remaining = Some(formatMoney(budget.remaining.value)),
            bidOnUnmatchedContext = info.bidOnUnmatchedContext,
            targetCategories = sortedCats,
            // Silent-failure signal: campaign has no content targeting
            // AND won't accept filler. The auction will never invite
            // this campaign to bid. Almost always means the auto-derive
            // path (RefineCategoriesFromCreative from Gemini) returned
            // an empty set and the advertiser didn't notice. Dashboard
            // shows a warning chip when this is true.
            untargeted = info.categories.isEmpty && info.suggestedCategories.isEmpty && !info.bidOnUnmatchedContext,
            siteAllowlist = info.siteAllowlist.toVector.sorted,
            adProductCategoryName = adProductName,
            targetCategoryNames = sortedCatNames,
            suggestedCategories = sortedSuggested,
            suggestedCategoryNames = sortedSuggestedNames
          )
        )
      }).recover { case ex: java.util.concurrent.TimeoutException =>
        Left(ErrorResponse("campaign_not_found", s"Campaign $campaignId not found (timeout)"))
      }
  }

  /**
   * Public `GET /campaigns/{id}` entry point. Unlike the internal
   * `getCampaignLogic` (which the campaign-list path calls for ids it has
   * already proven the advertiser owns), this enforces ownership so a
   * direct id lookup can't return a phantom of another advertiser's
   * campaign.
   */
  private val getCampaignOwnedLogic: ((String, String)) => Future[Either[ErrorResponse, Campaign]] = {
    case (advertiserId, campaignId) =>
      requireOwnedCampaign(advertiserId, campaignId).flatMap {
        case Left(err) => Future.successful(Left(err))
        case Right(_)  => getCampaignLogic((advertiserId, campaignId))
      }
  }

  /**
   * A campaign's landing page is its identity anchor and is fixed at
   * creation, so it must be a well-formed absolute http(s) URL up front.
   * Returns the validation error, or None when the URL is acceptable.
   */
  private def validateLandingUrl(landingUrl: String): Option[ErrorResponse] = {
    val trimmed = landingUrl.trim
    if (trimmed.isEmpty)
      Some(ErrorResponse("landing_url_required", "Landing page URL is required"))
    else
      scala.util.Try(java.net.URI.create(trimmed)).toOption
        .filter(u => (u.getScheme == "http" || u.getScheme == "https") && u.getHost != null) match {
        case Some(_) => None
        case None    => Some(ErrorResponse("invalid_landing_url", "Landing page URL must be a valid http(s) URL"))
      }
  }

  private val createCampaignLogic: ((String, CreateCampaignRequest)) => Future[Either[ErrorResponse, Campaign]] = {
    case (advertiserId, req) =>
      // Name is required server-side, not just by the form's `required`
      // attribute — API/script callers used to be able to create
      // nameless campaigns, which then rendered as raw ULIDs.
      if (req.name.trim.isEmpty) {
        Future.successful(Left(ErrorResponse("invalid_name", "Campaign name is required")))
      } else
        validateLandingUrl(req.landingUrl) match {
          case Some(err) => Future.successful(Left(err))
          case None      =>
            val createF: Future[AdvertiserEntity.CreateCampaignResult] =
              advertiserRef(advertiserId).ask(AdvertiserEntity.CreateCampaign(_))

            createF
              .flatMap { case AdvertiserEntity.CampaignCreated(_, campaignId) =>
                // Resolve the campaign EntityRef locally from its id rather than
                // receiving it in the reply — an EntityRef can't be Jackson-
                // serialized, so carrying it on CampaignCreated breaks the ask
                // across cluster nodes (multi-node entity tier).
                val configF: Future[CampaignEntity.ConfigUpdated] =
                  campaignRef(advertiserId, campaignId.value).ask(ref =>
                    CampaignEntity.UpdateConfig(
                      maxCpm = Some(CPM(BigDecimal(req.bidding.maxCpm))),
                      dailyBudget = Some(Budget(BigDecimal(req.budget.daily))),
                      adProductCategory = Some(Some(AdProductCategoryId(req.adProductCategory))),
                      categories = req.targetCategories.map(_.toSet.map(CategoryId(_))),
                      landingUrl = Some(Some(req.landingUrl)),
                      siteAllowlist = req.siteAllowlist.map(_.toSet),
                      name = Some(req.name),
                      replyTo = ref
                    )
                  )

                configF.map { _ =>
                  Right(
                    Campaign(
                      id = campaignId.value,
                      advertiserId = advertiserId,
                      name = req.name,
                      status = "paused", // New campaigns start as paused
                      budget = req.budget,
                      schedule = req.schedule,
                      adProductCategory = req.adProductCategory,
                      bidding = req.bidding,
                      landingUrl = req.landingUrl,
                      creativeIds = Vector.empty,
                      createdAt = nowIso,
                      updatedAt = nowIso,
                      siteAllowlist = req.siteAllowlist.map(_.toVector).getOrElse(Vector.empty)
                    )
                  )
                }
              }
              .recover { case ex =>
                Left(ErrorResponse("create_failed", ex.getMessage))
              }
        }
  }
  private val updateCampaignLogic
      : ((String, String, UpdateCampaignRequest)) => Future[Either[ErrorResponse, Campaign]] = {
    case (advertiserId, campaignId, req) =>
      // Handle budget update if provided
      val budgetUpdateF: Future[Either[ErrorResponse, Unit]] = req.budget match {
        case Some(newBudget) =>
          val budget = Budget(BigDecimal(newBudget.daily))
          campaignRef(advertiserId, campaignId)
            .ask(CampaignEntity.ReplenishBudget(budget, _))
            .map {
              case CampaignEntity.BudgetReplenished(_, _)                             => Right(())
              case CampaignEntity.ReplenishRejected(_, currentSpend, requestedBudget) =>
                Left(ErrorResponse(
                  "invalid_budget",
                  s"New budget (${formatMoney(requestedBudget.value)}) must exceed current spend (${formatMoney(currentSpend.value)})"
                ))
            }
        case None => Future.successful(Right(()))
      }

      // Handle config updates (adProductCategory, bidding, bidOnUnmatchedContext, dog-ear, schedule) if provided
      val scheduleStartAt: Option[Instant] = req.schedule.flatMap(s =>
        scala.util.Try(Instant.parse(s.startAt)).toOption
      )
      // endAt is Option-of-Option in UpdateConfig: None = no change,
      // Some(None) = clear (open-ended), Some(Some(t)) = set to t.
      // Translate from the API's flat schedule.endAt: Option[String].
      val scheduleEndAt: Option[Option[Instant]] = req.schedule.map { s =>
        s.endAt.flatMap(str => scala.util.Try(Instant.parse(str)).toOption)
      }
      val configUpdateF: Future[Either[ErrorResponse, Unit]] =
        if (req.adProductCategory.isDefined || req.bidding.isDefined || req.bidOnUnmatchedContext.isDefined ||
          scheduleStartAt.isDefined || scheduleEndAt.isDefined || req.targetCategories.isDefined ||
          req.siteAllowlist.isDefined ||
          req.landingUrl.isDefined || req.name.exists(_.trim.nonEmpty)) {
          campaignRef(advertiserId, campaignId)
            .ask(ref =>
              CampaignEntity.UpdateConfig(
                maxCpm = req.bidding.map(b => CPM(BigDecimal(b.maxCpm))),
                dailyBudget = None,
                adProductCategory = req.adProductCategory.map(apc => Some(AdProductCategoryId(apc))),
                categories = req.targetCategories.map(_.toSet.map(CategoryId(_))),
                // Campaign-level default LP, applied to NEW creatives only —
                // existing creatives' LPs are frozen by the lp_immutable guard.
                landingUrl = req.landingUrl.map(Some(_)),
                bidOnUnmatchedContext = req.bidOnUnmatchedContext,
                startAt = scheduleStartAt,
                endAt = scheduleEndAt,
                siteAllowlist = req.siteAllowlist.map(_.toSet),
                // Rename. Blank-after-trim is treated as "no change" both
                // here and in the entity, so a name can never become empty.
                name = req.name,
                replyTo = ref
              ))
            .map(_ => Right(()))
            .recover { case ex => Left(ErrorResponse("update_failed", ex.getMessage)) }
        } else {
          Future.successful(Right(()))
        }

      // Combine updates and return updated campaign
      for {
        budgetResult <- budgetUpdateF
        configResult <- if (budgetResult.isRight) configUpdateF else Future.successful(budgetResult)
        campaign <- if (configResult.isRight) getCampaignLogic((advertiserId, campaignId))
        else Future.successful(configResult.asInstanceOf[Either[ErrorResponse, Campaign]])
      } yield campaign
  }
  private val getCampaignWinRateLogic: ((String, String)) => Future[Either[ErrorResponse, CampaignWinRateInfo]] = {
    case (advertiserId, campaignId) =>
      requireOwnedCampaign(advertiserId, campaignId).flatMap {
        case Left(err) => Future.successful(Left(err))
        case Right(_)  =>
          import slick.jdbc.PostgresProfile.api.*
          val statsF: Future[CampaignEntity.CampaignStats] =
            campaignRef(advertiserId, campaignId).ask(CampaignEntity.GetCampaignStats(_))
          // Query today's impressions (not cumulative) to match bidsToday which resets daily
          val impressionsF: Future[Long] = dashboardDb match {
            case Some(db) =>
              db.run(
                sql"SELECT COALESCE(impressions, 0) FROM campaign_daily_stats WHERE campaign_id = $campaignId AND day_bucket = CURRENT_DATE"
                  .as[Long].headOption
              ).map(_.getOrElse(0L))
            case None => Future.successful(0L)
          }

          // Total impressions across ALL campaigns today (for impression share)
          val totalImpsF: Future[Long] = dashboardDb match {
            case Some(db) =>
              db.run(
                sql"SELECT COALESCE(SUM(impressions), 0) FROM campaign_daily_stats WHERE day_bucket = CURRENT_DATE"
                  .as[Long].headOption
              ).map(_.getOrElse(0L))
            case None => Future.successful(0L)
          }

          (for {
            stats <- statsF
            impressions <- impressionsF
            totalImps <- totalImpsF
          } yield {
            // Impression share: this campaign's impressions / total impressions today
            val winRate = if (totalImps > 0) impressions.toDouble / totalImps else 0.0
            Right(CampaignWinRateInfo(
              campaignId = campaignId,
              bidsToday = stats.bidsToday,
              impressionsToday = impressions,
              winRate = winRate
            ))
          }).recover { case _ =>
            Left(ErrorResponse("not_found", s"Campaign $campaignId not found"))
          }
      }
  }

  private val getCampaignBudgetLogic: ((String, String)) => Future[Either[ErrorResponse, CampaignBudgetInfo]] = {
    case (advertiserId, campaignId) =>
      requireOwnedCampaign(advertiserId, campaignId).flatMap {
        case Left(err) => Future.successful(Left(err))
        case Right(_)  =>
          val budgetF: Future[CampaignEntity.BudgetInfo] =
            campaignRef(advertiserId, campaignId).ask(CampaignEntity.GetBudgetInfo(_))

          budgetF
            .map { info =>
              Right(
                CampaignBudgetInfo(
                  campaignId = campaignId,
                  dailyBudget = formatMoney(info.dailyBudget.value),
                  spent = formatMoney(info.spent.value),
                  remaining = formatMoney(info.remaining.value)
                )
              )
            }
            .recover { case _ =>
              Left(ErrorResponse("not_found", s"Campaign $campaignId not found"))
            }
      }
  }
  // ----------------- Campaign Logic -----------------
  private val unassignCreativesLogic
      : ((String, String, AssignCreativesRequest)) => Future[Either[ErrorResponse, Campaign]] = {
    case (advertiserId, campaignId, req) =>
      val creativeIds = req.creativeIds.map(CreativeId(_)).toSet
      val unassignF: Future[CampaignEntity.CreativesUnassigned] =
        campaignRef(advertiserId, campaignId).ask(CampaignEntity.UnassignCreatives(creativeIds, _))

      unassignF
        .flatMap { _ => getCampaignLogic((advertiserId, campaignId)) }
        .recover { case ex =>
          Left(ErrorResponse("unassign_failed", ex.getMessage))
        }
  }
  private val listCreativesLogic: ((String, String, Int, Int)) => Future[Either[ErrorResponse, CreativeList]] = {
    case (advertiserId, campaignId, limit, offset) =>
      // IDOR guard: the projection read below is keyed by campaignId alone,
      // so without this check any advertiser could list another's creatives
      // (and leak their brand/landing-domain). Scope to the owner first.
      requireOwnedCampaign(advertiserId, campaignId).flatMap {
        case Left(err) => Future.successful(Left(err))
        case Right(_)  =>
          val creativesF = creativeRepo.map(_.getByCampaign(campaignId)).getOrElse(Future.successful(Vector.empty))
          creativesF.flatMap { creatives =>
            val paged = creatives.drop(offset).take(limit)
            Future.traverse(paged) { c =>
              val imgF = imageAssetRepo.map(_.get(c.imageHash)).getOrElse(Future.successful(None))
              imgF.map { imgOpt =>
                Creative(
                  id = c.creativeId,
                  campaignId = campaignId,
                  name = c.name,
                  status = if (c.isVerified) "verified" else "pending",
                  activeStatus = c.status.toString,
                  asset = imgOpt match {
                    case Some(img) => CreativeAsset(
                        `type` = img.mime,
                        url = if (img.s3Key.nonEmpty) s"$cdnBaseUrl/${img.s3Key}" else ""
                      )
                    case None => CreativeAsset(
                        `type` = c.mime,
                        url = ""
                      )
                  },
                  content = CreativeContent(
                    landingUrl = c.landingUrl,
                    landingDomain = c.landingDomain
                  ),
                  cpm = "0.00",
                  createdAt = c.createdAt.toString,
                  updatedAt = c.createdAt.toString,
                  matchConfidence = c.matchConfidence,
                  verificationReason = c.verificationReason
                )
              }
            }.map { allCreatives =>
              Right(CreativeList(
                data = allCreatives,
                meta = Meta(total = creatives.size, limit = limit, offset = offset)
              ))
            }
          }
      }
  }
  private val getPublisherLogic: String => Future[Either[ErrorResponse, Publisher]] =
    publisherId =>
      publisherRef(publisherId)
        .ask[PublisherEntity.PublisherInfo](PublisherEntity.GetPublisher(_))
        .map { info =>
          Right(
            Publisher(
              id = info.publisherId.value,
              status = info.status.toString.toLowerCase,
              siteIds = info.siteIds.map(_.value).toVector,
              domainBlocklist = info.domainBlocklist.toVector,
              createdAt = nowIso,
              updatedAt = nowIso
            )
          )
        }
        .recover { case ex =>
          Left(ErrorResponse("get_publisher_failed", ex.getMessage))
        }

  private val blockDomainsLogic: ((String, BlockDomainsRequest)) => Future[Either[ErrorResponse, Publisher]] = {
    case (publisherId, req) =>
      val normalizedDomains = req.domains.map(_.toLowerCase).toSet
      publisherRef(publisherId)
        .ask[PublisherEntity.DomainsBlocked](ref =>
          PublisherEntity.BlockDomains(normalizedDomains, ref)
        )
        .flatMap { result =>
          // Also directly notify ServeIndex to remove blocked domains
          // This works around the DData sync issue when sites aren't registered with publisher
          if (result.domains.nonEmpty) {
            // Get all sites for this publisher and notify each
            publisherRef(publisherId)
              .ask[PublisherEntity.PublisherInfo](PublisherEntity.GetPublisher(_))
              .foreach { info =>
                info.siteIds.foreach { siteId =>
                  serveIndex ! ServeIndexDData.RemoveByDomains(siteId.value, result.domains)
                }
                // Also try with publisherId as siteId (fallback for test setup)
                if (!info.siteIds.map(_.value).contains(publisherId)) {
                  serveIndex ! ServeIndexDData.RemoveByDomains(publisherId, result.domains)
                }
              }
          }
          // Return updated publisher info
          getPublisherLogic(publisherId)
        }
        .recover { case ex =>
          Left(ErrorResponse("block_domains_failed", ex.getMessage))
        }
  }

  // ----------------- Creative Logic -----------------
  private val unblockDomainsLogic: ((String, UnblockDomainsRequest)) => Future[Either[ErrorResponse, Publisher]] = {
    case (publisherId, req) =>
      publisherRef(publisherId)
        .ask[PublisherEntity.DomainsUnblocked](ref =>
          PublisherEntity.UnblockDomains(req.domains.toSet, ref)
        )
        .flatMap { _ =>
          // Return updated publisher info
          getPublisherLogic(publisherId)
        }
        .recover { case ex =>
          Left(ErrorResponse("unblock_domains_failed", ex.getMessage))
        }
  }
  private val listSitesLogic: ((String, Int, Int)) => Future[Either[ErrorResponse, SiteList]] = {
    case (publisherId, limit, offset) =>
      // Dashboard read resilience: a SHORT ask timeout plus per-site .recover so
      // one slow/recovering SiteEntity shard drops from the list (partial page
      // in seconds) instead of hanging the whole dashboard on the default 30s
      // timeout. Mirrors listCampaignsLogic's per-row recover. Dropped sites
      // reappear on the next refresh.
      given Timeout = Timeout(6.seconds)
      // Get publisher's siteIds
      publisherRef(publisherId)
        .ask[PublisherEntity.PublisherInfo](PublisherEntity.GetPublisher(_))
        .flatMap { info =>
          val siteIds = info.siteIds.toVector.drop(offset).take(limit)

          // Fetch config and verification status for each site
          val sitesFutures: Vector[Future[Option[Site]]] = siteIds.map { siteId =>
            val configF = siteRef(siteId.value).ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_)).map(_.config)
            val verifyF =
              siteRef(siteId.value).ask[SiteEntity.VerificationStatusResult](SiteEntity.GetVerificationStatus(_))
            val floorF = siteRef(siteId.value).ask[CPM](SiteEntity.GetFloorCpm(_))
            val minFloorF = siteRef(siteId.value).ask[CPM](SiteEntity.GetMinFloorCpm(_))
            val bidWeightF = siteRef(siteId.value).ask[Double](SiteEntity.GetBidWeight(_))
            val fillerF = siteRef(siteId.value).ask[Boolean](SiteEntity.GetAcceptsFillerTraffic(_))
            val classesF = siteRef(siteId.value)
              .ask[SiteEntity.PageClassificationsResult](SiteEntity.GetPageClassifications(_)).map(_.byUrl)
            (for {
              config <- configF
              vs <- verifyF
              floor <- floorF
              minFloor <- minFloorF
              bw <- bidWeightF
              acceptsFiller <- fillerF
              classes <- classesF
            } yield config.map { cfg =>
              val vStatus = if (vs.verified) "verified" else "unverified"
              buildSiteResponse(siteId.value, publisherId, cfg,
                slotCategories = slotCategoryMap(classes),
                floorCpm = Some(floor.toDouble.toString),
                minFloorCpm = Some(minFloor.toDouble.toString),
                bidWeight = Some(bw.toString),
                acceptsFillerTraffic = Some(acceptsFiller),
                verificationStatus = vStatus)
            }.orElse {
              Some(Site(
                id = siteId.value,
                publisherId = publisherId,
                domain = "",
                status = "pending",
                crawlConfig = SiteCrawlConfig("", "", 0, 0, "", Vector.empty),
                slots = Vector.empty,
                taxonomyIds = Vector.empty,
                createdAt = nowIso,
                updatedAt = nowIso,
                verificationStatus = "unverified"
              ))
            }).recover { case _ => None }
          }

          Future.sequence(sitesFutures).map { sites =>
            Right(SiteList(
              data = sites.flatten,
              meta = Meta(total = info.siteIds.size, limit = limit, offset = offset)
            ))
          }
        }
        .recover { case ex =>
          Left(ErrorResponse("list_sites_failed", ex.getMessage))
        }
  }
  private val getSiteLogic: ((String, String)) => Future[Either[ErrorResponse, Site]] = {
    case (publisherId, siteId) =>
      val configF = siteRef(siteId).ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_)).map(_.config)
      val verifyF = siteRef(siteId).ask[SiteEntity.VerificationStatusResult](SiteEntity.GetVerificationStatus(_))
      val floorF = siteRef(siteId).ask[CPM](SiteEntity.GetFloorCpm(_))
      val minFloorF = siteRef(siteId).ask[CPM](SiteEntity.GetMinFloorCpm(_))
      val bidWeightF = siteRef(siteId).ask[Double](SiteEntity.GetBidWeight(_))
      val fillerF = siteRef(siteId).ask[Boolean](SiteEntity.GetAcceptsFillerTraffic(_))
      val classesF = siteRef(siteId)
        .ask[SiteEntity.PageClassificationsResult](SiteEntity.GetPageClassifications(_)).map(_.byUrl)
      (for {
        config <- configF
        vs <- verifyF
        floor <- floorF
        minFloor <- minFloorF
        bw <- bidWeightF
        acceptsFiller <- fillerF
        classes <- classesF
      } yield config match {
        case Some(cfg) =>
          val vStatus = if (vs.verified) "verified" else "unverified"
          Right(buildSiteResponse(siteId, publisherId, cfg,
            slotCategories = slotCategoryMap(classes),
            floorCpm = Some(floor.toDouble.toString),
            minFloorCpm = Some(minFloor.toDouble.toString),
            bidWeight = Some(bw.toString),
            acceptsFillerTraffic = Some(acceptsFiller),
            verificationStatus = vStatus))
        case None =>
          Left(ErrorResponse("not_found", s"Site $siteId not found"))
      }).recover { case ex =>
        Left(ErrorResponse("get_site_failed", ex.getMessage))
      }
  }
  // Durable site→publisher mapping for billing settlement: the metering
  // endpoint joins publisher_sites to attribute revenue, and entity state
  // is not queryable from SQL. Best-effort — a miss surfaces as a skipped
  // (alerted, unbilled) settlement row, never a failed site creation.
  private def upsertPublisherSite(siteId: String, publisherId: String, host: String): Unit =
    dashboardDb.foreach { db =>
      import slick.jdbc.PostgresProfile.api.*
      db.run(
        sqlu"""INSERT INTO publisher_sites (publisher_id, site_id, host)
               VALUES ($publisherId, $siteId, $host)
               ON CONFLICT (site_id) DO UPDATE SET publisher_id = $publisherId, host = $host"""
      ).failed.foreach { ex =>
        system.log.warn("publisher_sites upsert failed for site {}: {}", siteId, ex.getMessage)
      }
    }

  private val createSiteLogic: ((String, CreateSiteRequest)) => Future[Either[ErrorResponse, Site]] = {
    case (publisherId, req) =>
      val siteId = SiteId(req.id)
      val pubId = PublisherId(publisherId)

      // Build SiteConfig from request
      val siteConfig = SiteEntity.SiteConfig(
        publisherId = pubId,
        domain = req.domain,
        seedUrl = req.crawlConfig.seedUrl,
        cronSchedule = req.crawlConfig.cronSchedule,
        maxDepth = req.crawlConfig.maxDepth,
        concurrency = req.crawlConfig.concurrency,
        hostRegex = req.crawlConfig.hostRegex,
        targetElements = req.crawlConfig.targetElements.toList,
        taxonomyIds = req.taxonomyIds.toSet,
        slots = req.slots.map(s => SiteEntity.AdSlotConfig(s.slotId, s.width, s.height)).toList
      )

      // Creating a siteId that already exists must never re-Initialize it:
      // for another publisher's site that would be a silent revenue takeover
      // (siteIds are lossy slugs, so collisions are plausible even without
      // malice), and for the caller's own site it would replace the whole
      // SiteConfig — a slot-less re-create wipes slots/taxonomy and the
      // UpdateMinFloorCpm below clobbers the configured floor.
      def register(): Future[Either[ErrorResponse, Site]] =
        publisherRef(publisherId)
          .ask[PublisherEntity.RegisterSiteResult](ref =>
            PublisherEntity.RegisterSite(siteId, ref)
          )
          .flatMap {
            case PublisherEntity.SiteRegistered(_, _) =>
              // Initialize the site entity with its config
              val minFloor = CPM(BigDecimal(req.minFloorCpm).toDouble)
              siteRef(req.id)
                .ask[SiteEntity.Initialized](ref => SiteEntity.Initialize(siteConfig, ref))
                .flatMap { _ =>
                  // Set the publisher's minimum floor
                  siteRef(req.id).ask[SiteEntity.MinFloorCpmUpdated](SiteEntity.UpdateMinFloorCpm(minFloor, _))
                }
                .map { _ =>
                  upsertPublisherSite(req.id, publisherId, req.domain)
                  Right(buildSiteResponse(req.id, publisherId, siteConfig, minFloorCpm = Some(req.minFloorCpm)))
                }
            case PublisherEntity.SiteRegistrationFailed(_, _, reason) =>
              Future.successful(Left(ErrorResponse("site_registration_failed", reason)))
          }

      siteRef(req.id)
        .ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_))
        .flatMap { existing =>
          existing.config match {
            case Some(cfg) if cfg.publisherId != pubId =>
              Future.successful(
                Left(ErrorResponse("site_id_taken", s"site '${req.id}' is registered to another publisher"))
              )
            case Some(cfg) =>
              // Idempotent create: the site already belongs to this
              // publisher, so return it as-is. Config changes go through
              // the update endpoint.
              siteRef(req.id)
                .ask[CPM](SiteEntity.GetMinFloorCpm(_))
                .map { minFloor =>
                  Right(
                    buildSiteResponse(req.id, publisherId, cfg,
                      minFloorCpm = Some(minFloor.toDouble.toString))
                  )
                }
            case None => register()
          }
        }
        .recover { case ex =>
          Left(ErrorResponse("create_site_failed", ex.getMessage))
        }
  }

  // ----------------- Publisher Logic -----------------
  private val updateSiteLogic: ((String, String, UpdateSiteRequest)) => Future[Either[ErrorResponse, Site]] = {
    case (publisherId, siteId, req) =>
      siteRef(siteId)
        .ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_)).map(_.config)
        .flatMap {
          case None =>
            Future.successful(Left(ErrorResponse("not_found", s"Site $siteId not found")))
          case Some(current) =>
            val mergedCrawl = req.crawlConfig.getOrElse(SiteCrawlConfig(
              seedUrl = current.seedUrl,
              cronSchedule = current.cronSchedule,
              maxDepth = current.maxDepth,
              concurrency = current.concurrency,
              hostRegex = current.hostRegex,
              targetElements = current.targetElements.toVector
            ))
            val updated = SiteEntity.SiteConfig(
              publisherId = current.publisherId,
              domain = req.domain.getOrElse(current.domain),
              seedUrl = mergedCrawl.seedUrl,
              cronSchedule = mergedCrawl.cronSchedule,
              maxDepth = mergedCrawl.maxDepth,
              concurrency = mergedCrawl.concurrency,
              hostRegex = mergedCrawl.hostRegex,
              targetElements = mergedCrawl.targetElements.toList,
              taxonomyIds = req.taxonomyIds.map(_.toSet).getOrElse(current.taxonomyIds),
              slots = req.slots.map(_.map(s => SiteEntity.AdSlotConfig(s.slotId, s.width, s.height)).toList).getOrElse(
                current.slots)
            )
            siteRef(siteId)
              .ask[SiteEntity.ConfigUpdated](SiteEntity.UpdateConfig(updated, _))
              .flatMap { _ =>
                // Update floor CPM if requested
                val floorFuture = req.floorCpm match {
                  case Some(floorStr) =>
                    val floorValue = BigDecimal(floorStr).toDouble
                    val floorCpm = promovolve.CPM(floorValue)
                    for {
                      _ <- siteRef(siteId).ask[SiteEntity.FloorCpmUpdated](SiteEntity.UpdateFloorCpm(floorCpm, _))
                      _ = auctioneerRef(siteId) ! promovolve.auction.AuctioneerEntity.UpdateFloorCpm(floorCpm)
                    } yield ()
                  case None =>
                    Future.successful(())
                }
                // Update bid weight if requested
                val bidWeightFuture = req.bidWeight match {
                  case Some(weightStr) =>
                    val weight = BigDecimal(weightStr).toDouble
                    siteRef(siteId).ask[SiteEntity.BidWeightUpdated](SiteEntity.UpdateBidWeight(weight, _)).map(_ => ())
                  case None =>
                    Future.successful(())
                }
                // Update minimum floor CPM if requested
                val minFloorFuture = req.minFloorCpm match {
                  case Some(minStr) =>
                    val minValue = BigDecimal(minStr).toDouble
                    val minCpm = promovolve.CPM(minValue)
                    siteRef(siteId).ask[SiteEntity.MinFloorCpmUpdated](SiteEntity.UpdateMinFloorCpm(minCpm, _)).map(_ =>
                      ())
                  case None =>
                    Future.successful(())
                }
                // Update filler-traffic opt-in if requested
                val fillerFuture = req.acceptsFillerTraffic match {
                  case Some(accept) =>
                    siteRef(siteId).ask[SiteEntity.AcceptsFillerTrafficUpdated](
                      SiteEntity.UpdateAcceptsFillerTraffic(accept, _)).map(_ => ())
                  case None =>
                    Future.successful(())
                }
                for {
                  _ <- floorFuture
                  _ <- bidWeightFuture
                  _ <- minFloorFuture
                  _ <- fillerFuture
                } yield Right(buildSiteResponse(siteId, publisherId, updated,
                  floorCpm = req.floorCpm,
                  minFloorCpm = req.minFloorCpm,
                  bidWeight = req.bidWeight,
                  acceptsFillerTraffic = req.acceptsFillerTraffic
                ))
              }
        }
        .recover { case ex =>
          Left(ErrorResponse("update_site_failed", ex.getMessage))
        }
  }
  private val deleteSiteLogic: ((String, String)) => Future[Either[ErrorResponse, Unit]] = {
    case (publisherId, siteId) =>
      publisherRef(publisherId)
        .ask[PublisherEntity.SiteDeleted](ref => PublisherEntity.DeleteSite(SiteId(siteId), ref))
        .map(_ => Right(()))
        .recover { case ex => Left(ErrorResponse("delete_site_failed", ex.getMessage)) }
  }
  private val getSitePacingConfigLogic: ((String, String)) => Future[Either[ErrorResponse, PacingConfig]] = {
    case (publisherId, siteId) =>
      val pacingF: Future[SiteEntity.PacingConfig] =
        siteRef(siteId).ask(SiteEntity.GetPacingConfig(_))

      pacingF
        .map { config =>
          Right(
            PacingConfig(
              windowSeconds = config.windowSeconds,
              testThrottleOverride = config.testThrottleOverride,
              dayDurationSeconds = config.dayDurationSeconds,
              warmupMode = config.warmupMode
            )
          )
        }
        .recover { case ex =>
          Left(ErrorResponse("get_pacing_failed", ex.getMessage))
        }
  }
  private val updateSitePacingConfigLogic
      : ((String, String, UpdatePacingConfigRequest)) => Future[Either[ErrorResponse, PacingConfig]] = {
    case (publisherId, siteId, req) =>
      // First get current config, then merge with updates
      val currentF: Future[SiteEntity.PacingConfig] =
        siteRef(siteId).ask(SiteEntity.GetPacingConfig(_))

      currentF.flatMap { current =>
        // Validate dayDurationSeconds: must not exceed 86400 (real day)
        val requestedDayDuration = req.dayDurationSeconds.getOrElse(current.dayDurationSeconds)
        if (requestedDayDuration > 86400) {
          Future.successful(Left(ErrorResponse(
            "invalid_day_duration",
            s"dayDurationSeconds ($requestedDayDuration) cannot exceed 86400 (24 hours)"
          )))
        } else {
          // For testThrottleOverride: if explicitly set in request, use it; otherwise keep current
          val newOverride =
            if (req.testThrottleOverride.isDefined) req.testThrottleOverride else current.testThrottleOverride

          val updated = SiteEntity.PacingConfig(
            windowSeconds = req.windowSeconds.getOrElse(current.windowSeconds),
            testThrottleOverride = newOverride,
            dayDurationSeconds = requestedDayDuration,
            warmupMode = req.warmupMode.getOrElse(current.warmupMode)
          )

          val updateF: Future[SiteEntity.PacingConfigUpdated] =
            siteRef(siteId).ask(SiteEntity.UpdatePacingConfig(updated, _))

          updateF.map { _ =>
            Right(
              PacingConfig(
                windowSeconds = updated.windowSeconds,
                testThrottleOverride = updated.testThrottleOverride,
                dayDurationSeconds = updated.dayDurationSeconds,
                warmupMode = updated.warmupMode
              )
            )
          }
        }
      }.recover { case ex =>
        Left(ErrorResponse("update_pacing_failed", ex.getMessage))
      }
  }

  // ----------------- Site Logic -----------------
  private val listTaxonomyCategoriesLogic
      : ((Option[String], Int, Int)) => Future[Either[ErrorResponse, TaxonomyCategoryList]] = {
    case (queryOpt, limit, offset) =>
      import promovolve.taxonomy.TieredCategory

      Future.successful {
        // Get all categories from the taxonomy
        val allCategories = TieredCategory.getAll
          .sortBy(_.id.toIntOption.getOrElse(Int.MaxValue))
          .toVector

        // Filter by search query if provided
        val filtered = queryOpt match {
          case Some(q) if q.nonEmpty =>
            val lowerQ = q.toLowerCase
            allCategories.filter { cat =>
              cat.id.toLowerCase.contains(lowerQ) ||
              cat.name.toLowerCase.contains(lowerQ) ||
              cat.toString.toLowerCase.contains(lowerQ)
            }
          case _ => allCategories
        }

        // Apply pagination
        val total = filtered.size
        val paginated = filtered.drop(offset).take(limit)

        // Convert to API model
        val data = paginated.map { cat =>
          TaxonomyCategory(
            id = cat.id,
            name = cat.name,
            fullPath = cat.toString // Full path: "Name(ID) -> Parent(ID) -> ..."
          )
        }

        Right(TaxonomyCategoryList(
          data = data,
          meta = Meta(total = total, limit = limit, offset = offset)
        ))
      }
  }

  // List all registered (verified) sites for campaign media targeting. Reads
  // the verified-host DData map (siteId -> domain) with ReadLocal — DData
  // gossips every key to every node, so the API node's local replica holds it.
  // Filters by `q` (domain substring) server-side so the payload stays small
  // as the number of publishers grows.
  private val listRegisteredSitesLogic
      : ((Option[String], Int, Int)) => Future[Either[ErrorResponse, VerifiedSiteList]] = {
    case (queryOpt, limit, offset) =>
      import org.apache.pekko.cluster.ddata.LWWMap
      import org.apache.pekko.cluster.ddata.typed.scaladsl.{
        DistributedData => ClusterDData,
        Replicator => DDReplicator
      }
      val replicator = ClusterDData(system).replicator
      replicator
        .ask[DDReplicator.GetResponse[LWWMap[SiteId, String]]](
          DDReplicator.Get(SiteEntity.VerifiedHostKey, DDReplicator.ReadLocal, _)
        )
        .map {
          case rsp @ DDReplicator.GetSuccess(SiteEntity.VerifiedHostKey) =>
            rsp.get(SiteEntity.VerifiedHostKey).entries
          case _ =>
            Map.empty[SiteId, String]
        }
        .map { entries =>
          val needle = queryOpt.map(_.trim.toLowerCase).filter(_.nonEmpty)
          val filtered = entries.toVector
            .filter { case (_, domain) => needle.forall(domain.toLowerCase.contains) }
            .sortBy(_._2)
          val total = filtered.size
          val page = filtered.drop(offset).take(limit)
          Right(VerifiedSiteList(
            data = page.map { case (sid, domain) => VerifiedSite(sid.value, domain) },
            meta = Meta(total = total, limit = limit, offset = offset)
          ))
        }
        .recover { case ex => Left(ErrorResponse("list_sites_failed", ex.getMessage)) }
  }

  // Distinct advertiser landing-page domains for the publisher's block picker.
  // Reads the advertiser-domain DData map (campaignId -> LP domain) with
  // ReadLocal, dedups + filters by `q` server-side.
  private val listAdvertiserDomainsLogic
      : ((Option[String], Int, Int)) => Future[Either[ErrorResponse, AdvertiserDomainList]] = {
    case (queryOpt, limit, offset) =>
      import org.apache.pekko.cluster.ddata.LWWMap
      import org.apache.pekko.cluster.ddata.typed.scaladsl.{
        DistributedData => ClusterDData,
        Replicator => DDReplicator
      }
      val replicator = ClusterDData(system).replicator
      replicator
        .ask[DDReplicator.GetResponse[LWWMap[CampaignId, String]]](
          DDReplicator.Get(CampaignEntity.AdvertiserDomainKey, DDReplicator.ReadLocal, _)
        )
        .map {
          case rsp @ DDReplicator.GetSuccess(CampaignEntity.AdvertiserDomainKey) =>
            rsp.get(CampaignEntity.AdvertiserDomainKey).entries.values.toVector
          case _ =>
            Vector.empty[String]
        }
        .map { domains =>
          val needle = queryOpt.map(_.trim.toLowerCase).filter(_.nonEmpty)
          val distinct = domains.map(_.toLowerCase).distinct
            .filter(d => needle.forall(d.contains))
            .sorted
          val total = distinct.size
          val page = distinct.drop(offset).take(limit)
          Right(AdvertiserDomainList(data = page, meta = Meta(total = total, limit = limit, offset = offset)))
        }
        .recover { case ex => Left(ErrorResponse("list_advertiser_domains_failed", ex.getMessage)) }
  }

  private val listAdProductCategoriesLogic
      : ((Option[String], Int, Int)) => Future[Either[ErrorResponse, TaxonomyCategoryList]] = {
    case (queryOpt, limit, offset) =>
      import promovolve.taxonomy.AdProductTaxonomy

      Future.successful {
        // Get categories, optionally filtered by search query
        val allCategories = queryOpt match {
          case Some(q) if q.nonEmpty => AdProductTaxonomy.search(q).toVector
          case _                     => AdProductTaxonomy.getAll.toVector
        }

        // Apply pagination
        val total = allCategories.size
        val paginated = allCategories.drop(offset).take(limit)

        // Convert to API model
        val data = paginated.map { cat =>
          val path = (List(cat.tier1) ++ cat.tier2.toList ++ cat.tier3.toList).mkString(" > ")
          TaxonomyCategory(
            id = cat.id,
            name = cat.name,
            fullPath = path
          )
        }

        Right(TaxonomyCategoryList(
          data = data,
          meta = Meta(total = total, limit = limit, offset = offset)
        ))
      }
  }

  private val listPendingCreativesLogic
      : ((String, String, Int, Int)) => Future[Either[ErrorResponse, PendingCreativeGroupList]] = {
    case (publisherId, siteId, limit, offset) =>
      // Fetch pending list, domain blocklist, and ad product blocklist in parallel
      val pendingF: Future[AdServer.PendingList] =
        adServerRef(siteId).ask(AdServer.ListPending(_))
      val domainBlocklistF: Future[Set[String]] = {
        val f: Future[PublisherEntity.PublisherInfo] =
          publisherRef(publisherId).ask(PublisherEntity.GetPublisher(_))
        f.map(_.domainBlocklist)
      }
      val adProductBlocklistF: Future[Set[AdProductCategoryId]] = {
        val f: Future[SiteEntity.AdProductBlocklistResponse] =
          siteRef(siteId).ask(SiteEntity.GetAdProductBlocklist(_))
        f.map(_.categories)
      }

      (for {
        list <- pendingF
        domainBlocklist <- domainBlocklistF
        adProductBlocklist <- adProductBlocklistF
        // Filter out items with blocked domains or ad product categories
        filteredItems = list.items.filterNot { item =>
          val domainBlocked = item.landingDomain.exists(domainBlocklist.contains)
          val categoryBlocked =
            item.adProductCategory.exists(cat => adProductBlocklist.contains(AdProductCategoryId(cat)))
          domainBlocked || categoryBlocked
        }
        // Group by creativeId and collect all placements
        baseGroups = filteredItems
          .groupBy(_.creativeId)
          .values
          .toVector
          .map { items =>
            val representative = items.head
            val fillerSentinel = promovolve.CategoryId.Filler.value
            val placements = items.map { item =>
              val isFiller = item.category == fillerSentinel
              PendingPlacement(
                url = item.url,
                slotId = item.slotId,
                cpm = f"${item.cpm}%.4f",
                // Suppress the raw __filler__ sentinel on the wire —
                // it's a backend routing id, not a user-facing label.
                // The `filler` flag is the one the UI should read.
                category = if (isFiller) None else Some(item.category),
                categoryName =
                  if (isFiller) None
                  else promovolve.taxonomy.TieredCategory.get(item.category).map(_.name),
                filler = isFiller
              )
            }
            val maxCpm = items.map(_.cpm).max
            val groupFiller = placements.exists(_.filler)
            PendingCreativeGroup(
              creativeId = representative.creativeId,
              campaignId = representative.campaignId,
              advertiserId = representative.advertiserId,
              cpm = f"$maxCpm%.4f",
              category = representative.category,
              assetUrl = representative.s3Key.map(key => s"$cdnBaseUrl/$key"),
              adProductCategory = representative.adProductCategory,
              landingDomain = representative.landingDomain,
              placements = placements,
              // IAB taxonomy name lookups so the approval UI can show
              // human-readable categories instead of raw numeric IDs.
              // Filler rep → None (UI renders the filler badge instead).
              categoryName =
                if (representative.category == fillerSentinel) None
                else promovolve.taxonomy.TieredCategory.get(representative.category).map(_.name),
              adProductCategoryName = representative.adProductCategory
                .flatMap(id => promovolve.taxonomy.AdProductTaxonomy.get(id).map(_.name)),
              filler = groupFiller,
              // Honest queue age: earliest first-seen across placements,
              // largest re-queue count as the "cycles ignored" hint.
              firstSeenAt = items.flatMap(_.firstSeenEpochMs).minOption,
              requeueCount = items.flatMap(_.requeueCount).maxOption
            )
          }
        // Enrich with pagesJson so the approval UI can render the
        // live <expandable-magazine-banner> and let the publisher
        // click through the expanded magazine view, not just the
        // static thumbnail. Missing creative / no pagesJson / no
        // repo configured → stays None, UI falls back to assetUrl.
        enrichedGroups <- creativeRepo match {
          case Some(repo) =>
            Future.traverse(baseGroups) { g =>
              repo.get(g.creativeId)
                .map(c =>
                  g.copy(
                    pagesJson = c.flatMap(_.pagesJson),
                    createdAt = c.map(_.createdAt.toString).getOrElse(""),
                    bannerConfigJson = c.flatMap(_.bannerConfigJson),
                    landingUrl = c.map(_.landingUrl).filter(_.nonEmpty)
                  ))
                .recover { case _ => g }
            }
          case None => Future.successful(baseGroups)
        }
      } yield {
        // Newest first (the Creative Inbox auto-selects the most recent),
        // CPM desc as the tiebreaker. ISO-8601 instant strings sort
        // chronologically; "" (no repo record) is smallest → sorts last.
        val groups = enrichedGroups.sortBy(g => (g.createdAt, g.cpm.toDouble)).reverse
        Right(
          PendingCreativeGroupList(
            data = groups,
            meta = Meta(total = groups.size, limit = limit, offset = offset)
          )
        )
      }).recover { case ex =>
        Left(ErrorResponse("list_pending_failed", ex.getMessage))
      }
  }
  private val listServingCreativesLogic
      : ((String, String, Int)) => Future[Either[ErrorResponse, ServingCreativeGroupList]] = {
    case (publisherId, siteId, hours) =>
      val lookback = math.max(1, math.min(hours, 24 * 14)).hours
      val since = java.time.Instant.now().minusSeconds(lookback.toSeconds)

      // Mirror getServeIndexLogic(None): collect all slot keys, fetch
      // each ServeView, dedup candidates by creativeId keeping the
      // highest-CPM entry. CandidateView (not ServeIndexCandidate) is
      // kept so width/height land in the response too.
      val candidatesF: Future[Vector[promovolve.publisher.CandidateView]] =
        serveIndex
          .ask[ServeIndexDData.SiteKeys](ServeIndexDData.GetKeysBySite(siteId, _))
          .flatMap { siteKeys =>
            val viewFs = siteKeys.keys.map { sid =>
              val key = serveIndexKey(siteId, sid)
              serveIndex.ask[Option[ServeView]](ref => ServeIndexDData.Get(key, ref))
            }
            Future.sequence(viewFs).map { views =>
              views.flatten
                .flatMap(_.candidates)
                .groupBy(_.creativeId.value)
                .values
                .map(_.maxBy(_.cpm.value))
                .toVector
            }
          }

      val floorF = siteRef(siteId).ask[CPM](SiteEntity.GetFloorCpm(_))
      val catFloorsF =
        siteRef(siteId).ask[promovolve.proto.site.CategoryFloors](SiteEntity.GetCategoryFloors(_)).map(_.floors)
      // Admin per-slot floor overrides — a creative serving on an overridden
      // slot competes against THAT floor, so the "Pin-only" (belowFloor)
      // badge must judge it the same way the serve gate does.
      val slotFloorsF = siteRef(siteId).ask[SiteEntity.SlotsResult](SiteEntity.GetSlots(_))
        .map(_.slots.flatMap(s => s.floorOverride.map(f => s.slotId -> f.toDouble)).toMap)

      (for {
        candidates <- candidatesF
        floorCpm <- floorF
        catFloors <- catFloorsF
        slotFloors <- slotFloorsF
        creativeIds = candidates.iterator.map(_.creativeId.value).toSet
        placementRows <- trackingEventJournal match {
          case Some(j) => j.listServingPlacements(siteId, creativeIds, since)
          case None    => Future.successful(Vector.empty[promovolve.api.projection.ServingPlacementRow])
        }
        // Enrich with assetUrl from CreativeRepo (mirrors buildCandidates).
        enriched <- Future.traverse(candidates) { c =>
          val creativeF = creativeRepo.map(_.get(c.creativeId.value)).getOrElse(Future.successful(None))
          creativeF.map(creative => (c, creative))
        }
      } yield {
        val byCreative = placementRows.groupBy(_.creativeId)
        val groups = enriched.map { case (c, creative) =>
          val plRows = byCreative.getOrElse(c.creativeId.value, Vector.empty)
          val placements = plRows
            .sortBy(-_.impressions)
            .map { r =>
              ServingPlacement(
                url = r.url,
                slotId = r.slot,
                impressions = r.impressions,
                category = r.category,
                categoryName =
                  r.category.flatMap(promovolve.taxonomy.TieredCategory.get).map(_.name)
              )
            }
          val cpmDouble = c.cpm.toDouble
          // A creative competes against its placement's floor: the slot's
          // admin override when one exists, else the category floor (enforce
          // mode), else the site floor — the same precedence the serve gate
          // uses. Judge "below floor" against the LOWEST floor among its
          // placements: it's pin-only only if it clears NONE of them. Fall
          // back to the site floor when there are no placements (nothing
          // served, no slot/category context).
          val servingFloors = placements.map { p =>
            slotFloors.getOrElse(
              p.slotId,
              p.category.map(cat => catFloors.getOrElse(cat, floorCpm.toDouble)).getOrElse(floorCpm.toDouble)
            )
          }.distinct
          val effFloor = if (servingFloors.nonEmpty) servingFloors.min else floorCpm.toDouble
          ServingCreativeGroup(
            creativeId = c.creativeId.value,
            campaignId = Some(c.campaignId.value),
            advertiserId = Some(c.advertiserId.value),
            cpm = f"$cpmDouble%.4f",
            assetUrl = creative.map(cr => s"$cdnBaseUrl/${cr.s3Key}"),
            width = Some(c.width),
            height = Some(c.height),
            belowFloor = effFloor > 0.0 && cpmDouble < effFloor,
            floorCpm = f"$effFloor%.2f",
            placements = placements,
            pagesJson = creative.flatMap(_.pagesJson),
            bannerConfigJson = creative.flatMap(_.bannerConfigJson),
            landingDomain = creative.map(_.landingDomain),
            landingUrl = creative.map(_.landingUrl).filter(_.nonEmpty)
          )
        }.sortBy(-_.cpm.toDouble)
        Right(ServingCreativeGroupList(data = groups,
          meta = Meta(total = groups.size, limit = groups.size, offset = 0)))
      }).recover { case ex =>
        Left(ErrorResponse("list_serving_failed", ex.getMessage))
      }
  }

  private val approveCreativeLogic
      : ((String, String, ApproveCreativeRequest)) => Future[Either[ErrorResponse, ApproveCreativeResponse]] = {
    case (publisherId, siteId, req) =>
      import promovolve.publisher.AssetPointer
      val approveF: Future[StatusReply[AssetPointer]] =
        adServerRef(siteId).ask(AdServer.Approve(req.url, req.slot, req.creativeId, _))

      approveF
        .map {
          case StatusReply.Success(assetPointer: AssetPointer) =>
            // Publish SSE event for real-time UI updates
            pendingEventHub.foreach(_ ! PendingEventHub.PublishApproved(siteId, req.url, req.slot, req.creativeId))
            Right(
              ApproveCreativeResponse(
                url = req.url,
                slot = req.slot,
                assetUrl = assetPointer.cdnUri.toString
              )
            )
          case StatusReply.Error(ex) =>
            Left(ErrorResponse("approve_failed", ex.getMessage))
        }
        .recover { case ex =>
          Left(ErrorResponse("approve_failed", ex.getMessage))
        }
  }
  private val rejectCreativeLogic
      : ((String, String, RejectCreativeRequest)) => Future[Either[ErrorResponse, RejectCreativeResponse]] = {
    case (publisherId, siteId, req) =>
      val rejectF: Future[StatusReply[AdServer.Done.type]] =
        adServerRef(siteId).ask(AdServer.Reject(req.url, req.slot, req.creativeId, req.reason, _))

      rejectF
        .map {
          case StatusReply.Success(_) =>
            // Publish SSE event for real-time UI updates
            pendingEventHub.foreach(_ ! PendingEventHub.PublishRejected(siteId, req.url, req.slot, req.creativeId))
            Right(
              RejectCreativeResponse(
                url = req.url,
                slot = req.slot,
                creativeId = req.creativeId,
                status = "rejected"
              )
            )
          case StatusReply.Error(ex) =>
            Left(ErrorResponse("reject_failed", ex.getMessage))
        }
        .recover { case ex =>
          Left(ErrorResponse("reject_failed", ex.getMessage))
        }
  }

  // ----------------- Flagging / Quarantine Logic -----------------

  private val flagCreativeLogic
      : ((String, String, FlagCreativeRequest)) => Future[Either[ErrorResponse, FlagCreativeResponse]] = {
    case (publisherId, siteId, req) =>
      val flagF: Future[StatusReply[AdServer.FlagResult]] =
        adServerRef(siteId).ask(AdServer.Flag(req.url, req.slot, req.creativeId, req.reason, _))

      flagF
        .map {
          case StatusReply.Success(result: AdServer.FlagResult) =>
            if (result.flagged) {
              creativeRepo.foreach { repo =>
                repo.get(req.creativeId).foreach {
                  case Some(creative) =>
                    // Mark creative as rejected for this site in the Cuckoo filter
                    advertiserRef(creative.advertiserId).ask[AdvertiserEntity.CreativeApprovalUpdated](ref =>
                      AdvertiserEntity.UpdateCreativeApproval(
                        CreativeId(req.creativeId),
                        SiteId(siteId),
                        publisher.ApprovalStatus.Rejected,
                        ref
                      )
                    )
                    system.log.info(
                      "Flag: marked creative={} as rejected for site={} in Cuckoo filter",
                      req.creativeId, siteId
                    )

                    // Trigger re-auction to fill the vacancy left by the flagged creative
                    if (budgetEventTopic != null) {
                      import org.apache.pekko.actor.typed.pubsub.Topic
                      val event = promovolve.CreativeStatusChanged(
                        creativeId = CreativeId(req.creativeId),
                        advertiserId = AdvertiserId(creative.advertiserId),
                        campaignId = CampaignId(creative.campaignId),
                        isActive = false,
                        timestamp = java.time.Instant.now()
                      )
                      budgetEventTopic ! Topic.Publish(event)
                    }
                  case None => ()
                }
              }
            }
            Right(FlagCreativeResponse(
              creativeId = result.creativeId,
              flagged = result.flagged
            ))
          case StatusReply.Error(ex) =>
            Left(ErrorResponse("flag_failed", ex.getMessage))
        }
        .recover { case ex =>
          Left(ErrorResponse("flag_failed", ex.getMessage))
        }
  }

  private val unflagCreativeLogic
      : ((String, String, UnflagCreativeRequest)) => Future[Either[ErrorResponse, UnflagCreativeResponse]] = {
    case (publisherId, siteId, req) =>
      val unflagF: Future[StatusReply[AdServer.UnflagResult]] =
        adServerRef(siteId).ask(AdServer.Unflag(req.creativeId, _))

      unflagF
        .map {
          case StatusReply.Success(result: AdServer.UnflagResult) =>
            if (result.unflagged) {
              creativeRepo.foreach { repo =>
                repo.get(req.creativeId).foreach {
                  case Some(creative) =>
                    // Remove rejection from Cuckoo filter (reversible!)
                    advertiserRef(creative.advertiserId).ask[AdvertiserEntity.CreativeUnrejectionUpdated](ref =>
                      AdvertiserEntity.UpdateCreativeUnrejection(
                        CreativeId(req.creativeId),
                        SiteId(siteId),
                        ref
                      )
                    )
                    system.log.info(
                      "Unflag: removed rejection for creative={} site={} from Cuckoo filter",
                      req.creativeId, siteId
                    )

                    // Trigger re-auction so unflagged creative can compete again
                    if (budgetEventTopic != null) {
                      import org.apache.pekko.actor.typed.pubsub.Topic
                      val event = promovolve.CreativeStatusChanged(
                        creativeId = CreativeId(req.creativeId),
                        advertiserId = AdvertiserId(creative.advertiserId),
                        campaignId = CampaignId(creative.campaignId),
                        isActive = true,
                        timestamp = java.time.Instant.now()
                      )
                      budgetEventTopic ! Topic.Publish(event)
                    }
                  case None => ()
                }
              }
            }
            Right(UnflagCreativeResponse(
              creativeId = result.creativeId,
              unflagged = result.unflagged
            ))
          case StatusReply.Error(ex) =>
            Left(ErrorResponse("unflag_failed", ex.getMessage))
        }
        .recover { case ex =>
          Left(ErrorResponse("unflag_failed", ex.getMessage))
        }
  }

  private val revokeCreativeLogic
      : ((String, String, RevokeCreativeRequest)) => Future[Either[ErrorResponse, RevokeCreativeResponse]] = {
    case (publisherId, siteId, req) =>
      creativeRepo match {
        case Some(repo) =>
          repo.get(req.creativeId).flatMap {
            case Some(creative) =>
              // Revoke = soft undo. Accidental delivery or "stop it for
              // now, I'll re-evaluate later." Clear both approved and
              // rejected filter state so the creative returns to
              // pending; publisher re-approves / rejects on next
              // appearance. Reject (separate action) is the permanent
              // block. MUST be the dedicated revoke command: CreativePaused
              // deliberately KEEPS the persisted approval (reversible
              // advertiser state), which would re-admit the creative as
              // approved at the next rebuild — revoke is the explicit
              // publisher un-approval and deletes it.
              adServerRef(siteId) ! AdServer.RevokeCreativeApproval(CreativeId(req.creativeId))

              advertiserRef(creative.advertiserId).ask[AdvertiserEntity.CreativeApprovalRevoked](ref =>
                AdvertiserEntity.RevokeCreativeApproval(
                  CreativeId(req.creativeId),
                  SiteId(siteId),
                  ref
                )
              )
              system.log.info(
                "Revoke: removed from ServeIndex and cleared filters for creative={} site={}",
                req.creativeId, siteId
              )

              // Trigger re-auction so the vacated slot refills from
              // remaining demand immediately, instead of waiting for
              // the next periodic reauction tick. Mirrors the flag
              // path — publisher-driven state changes close the loop
              // end-to-end.
              if (budgetEventTopic != null) {
                import org.apache.pekko.actor.typed.pubsub.Topic
                val event = promovolve.CreativeStatusChanged(
                  creativeId = CreativeId(req.creativeId),
                  advertiserId = AdvertiserId(creative.advertiserId),
                  campaignId = CampaignId(creative.campaignId),
                  isActive = false,
                  timestamp = java.time.Instant.now()
                )
                budgetEventTopic ! Topic.Publish(event)
              }

              // Publish SSE event for real-time UI updates
              pendingEventHub.foreach(_ ! PendingEventHub.PublishRevoked(siteId, req.creativeId))

              Future.successful(Right(RevokeCreativeResponse(
                creativeId = req.creativeId,
                revoked = true
              )))

            case None =>
              Future.successful(Left(ErrorResponse("not_found", s"Creative ${req.creativeId} not found")))
          }
        case None =>
          Future.successful(Left(ErrorResponse("not_found", s"Creative ${req.creativeId} not found")))
      }
  }

  private val listFlaggedCreativesLogic
      : ((String, String, Int, Int)) => Future[Either[ErrorResponse, FlaggedCreativeList]] = {
    case (publisherId, siteId, limit, offset) =>
      val flaggedF: Future[AdServer.FlaggedList] =
        adServerRef(siteId).ask(AdServer.ListFlagged(_))

      flaggedF
        .flatMap { list =>
          // Enrich each flagged item with its creative's pagesJson/config
          // (mirrors serving) so the approval inbox can render the live
          // <magazine-preview> for flagged creatives too.
          Future.traverse(list.items) { item =>
            val creativeF = creativeRepo.map(_.get(item.creativeId)).getOrElse(Future.successful(None))
            creativeF.map { creative =>
              FlaggedCreative(
                url = item.url,
                slotId = item.slotId,
                creativeId = item.creativeId,
                campaignId = item.campaignId,
                cpm = f"${item.cpm}%.4f",
                category = item.category,
                reason = item.reason,
                flaggedAt = item.flaggedAt,
                assetUrl = item.s3Key.map(key => s"$cdnBaseUrl/$key"),
                pagesJson = creative.flatMap(_.pagesJson),
                bannerConfigJson = creative.flatMap(_.bannerConfigJson),
                advertiserId = creative.map(_.advertiserId),
                landingDomain = creative.map(_.landingDomain),
                landingUrl = creative.map(_.landingUrl).filter(_.nonEmpty)
              )
            }
          }.map { items =>
            Right(
              FlaggedCreativeList(
                data = items.drop(offset).take(limit),
                meta = Meta(total = items.size, limit = limit, offset = offset)
              )
            )
          }
        }
        .recover { case ex =>
          Left(ErrorResponse("list_flagged_failed", ex.getMessage))
        }
  }

  // ----------------- Campaign Status Logic -----------------
  private val updateCampaignStatusLogic
      : ((String, String, UpdateCampaignStatusRequest)) => Future[Either[ErrorResponse, Campaign]] = {
    case (advertiserId, campaignId, req) =>
      given Timeout = Timeout(15.seconds) // Longer timeout for activation
      val status = req.status.toLowerCase match {
        case "active" => CampaignEntity.Status.Active
        case "paused" => CampaignEntity.Status.Paused
        case _        => CampaignEntity.Status.Paused // Default to paused for unknown status
      }

      // CampaignPaused event is published by CampaignEntity to budget topic,
      // so all AdServers handle removal locally using their inverted index.

      val activateF: Future[CampaignEntity.StatusUpdated] =
        campaignRef(advertiserId, campaignId).ask(CampaignEntity.UpdateStatus(status, _))

      activateF
        .flatMap { _ => getCampaignLogic((advertiserId, campaignId)) }
        .map {
          case Right(campaign) =>
            pendingEventHub.foreach(_ ! PendingEventHub.PublishCampaignStatusChanged(campaignId, status.toString))
            Right(campaign)
          case other => other
        }
        .recover { case ex =>
          Left(ErrorResponse("status_update_failed", ex.getMessage))
        }
  }

  // ----------------- Campaign Ad Product Category Logic -----------------
  private val updateCampaignAdProductLogic: ((String, String, UpdateAdProductCategoryRequest)) => Future[Either[
    ErrorResponse, UpdateAdProductCategoryResponse]] = {
    case (advertiserId, campaignId, req) =>
      val adProductCat = req.adProductCategory.map(AdProductCategoryId(_))
      val updateF: Future[CampaignEntity.ConfigUpdated] =
        campaignRef(advertiserId, campaignId).ask(replyTo =>
          CampaignEntity.UpdateConfig(
            maxCpm = None,
            dailyBudget = None,
            adProductCategory = Some(adProductCat), // Some(None) clears, Some(Some(x)) sets
            replyTo = replyTo
          )
        )

      updateF
        .map { _ =>
          Right(UpdateAdProductCategoryResponse(
            campaignId = campaignId,
            adProductCategory = req.adProductCategory
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("ad_product_update_failed", ex.getMessage))
        }
  }

  // ----------------- Site Ad Product Blocklist Logic -----------------
  private val getAdProductBlocklistLogic
      : ((String, String)) => Future[Either[ErrorResponse, AdProductBlocklistResponse]] = {
    case (publisherId, siteId) =>
      val getF: Future[SiteEntity.AdProductBlocklistResponse] =
        siteRef(siteId).ask(SiteEntity.GetAdProductBlocklist(_))

      getF
        .map { resp =>
          Right(AdProductBlocklistResponse(
            siteId = resp.siteId.value,
            categories = resp.categories.map(_.value).toVector
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("blocklist_get_failed", ex.getMessage))
        }
  }

  private val blockAdProductsLogic
      : ((String, String, AdProductBlocklistRequest)) => Future[Either[ErrorResponse, AdProductBlocklistResponse]] = {
    case (publisherId, siteId, req) =>
      val categories = req.categories.map(AdProductCategoryId(_)).toSet
      val blockF: Future[SiteEntity.AdProductCategoriesBlocked] =
        siteRef(siteId).ask(SiteEntity.BlockAdProductCategories(categories, _))

      blockF
        .flatMap { result =>
          // Directly notify ServeIndex to remove blocked categories
          if (result.blocked.nonEmpty) {
            serveIndex ! ServeIndexDData.RemoveByAdProductCategories(siteId, result.blocked)
          }
          // Return current blocklist after update
          getAdProductBlocklistLogic((publisherId, siteId))
        }
        .recover { case ex =>
          Left(ErrorResponse("blocklist_update_failed", ex.getMessage))
        }
  }

  private val unblockAdProductsLogic
      : ((String, String, AdProductUnblockRequest)) => Future[Either[ErrorResponse, AdProductUnblockResponse]] = {
    case (publisherId, siteId, req) =>
      val categories = req.categories.map(AdProductCategoryId(_)).toSet
      val unblockF: Future[SiteEntity.AdProductCategoriesUnblocked] =
        siteRef(siteId).ask(SiteEntity.UnblockAdProductCategories(categories, _))

      unblockF
        .map { resp =>
          Right(AdProductUnblockResponse(
            siteId = resp.siteId.value,
            unblocked = resp.unblocked.map(_.value).toVector
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("blocklist_update_failed", ex.getMessage))
        }
  }

  // ----------------- Creative Creation Logic -----------------
  private val createCreativeLogic
      : ((String, String, CreateCreativeRequest)) => Future[Either[ErrorResponse, CreateCreativeResponse]] = {
    case (advertiserId, campaignId, req) =>
      import jkugiya.ulid.ULID
      import java.security.MessageDigest
      import spray.json.enrichAny

      val isDraft = req.draft.contains(true)
      // Reuse existing ULID when resuming a draft so Save Draft → Save Draft
      // → Publish all target the same row. New ULID for first-time saves.
      val id = CreativeId(req.creativeId.filter(_.nonEmpty).getOrElse(ULID.getGenerator().base32()))

      // The create/persist work. Wrapped in a def so the LP-immutability
      // guard below can short-circuit before any row is written.
      def build(): Future[Either[ErrorResponse, CreateCreativeResponse]] = {

        // Save immediately with the pages JSON as-is (images may still be external URLs)
        // TEMP diagnostic: log whether videoBg made it through decoding.
        org.slf4j.LoggerFactory.getLogger("createCreative").info(
          "INCOMING pages.size={}, videoBg counts={}, first videoBg={}",
          req.pages.size,
          req.pages.count(_.videoBg.isDefined),
          req.pages.flatMap(_.videoBg).headOption.map(_.compactPrint.take(300)).getOrElse("(none)")
        )
        val pagesJson = req.pages.map(p => {
          import promovolve.creative.BannerPage
          BannerPage(p.tag, p.headline, p.sub, p.body, p.accent, p.bg,
            p.imgEmoji, p.caption, p.img,
            p.layout, p.banners, p.designAspect, p.videoBg, p.textureBg)
        }).toJson.compactPrint

        val hash = MessageDigest.getInstance("SHA-256")
          .digest(pagesJson.getBytes("UTF-8"))
          .map("%02x".format(_)).mkString

        val landingDomain = scala.util.Try {
          java.net.URI.create(req.landingUrl).getHost
        }.getOrElse("unknown")

        // ImageAsset row needs intrinsic image dimensions; for the
        // placeholder (no actual image yet, just a json+expandable
        // marker) we write 0×0 — CreativeProcessor overwrites this row
        // when it actually renders the thumbnail.
        val imgAssetF =
          imageAssetRepo.map(_.put(ImageAsset(hash, "", "application/json+expandable", 0, 0, Instant.now())))
            .getOrElse(Future.successful(()))

        val creative = PublisherCreative(
          creativeId = id.value,
          imageHash = hash,
          advertiserId = advertiserId,
          campaignId = campaignId,
          name = req.name,
          landingUrl = req.landingUrl,
          landingDomain = landingDomain,
          createdAt = Instant.now(),
          s3Key = "",
          mime = "application/json+expandable",
          // No intrinsic dimensions yet — banner is JSON, not an image.
          // CreativeProcessor backfills these when it renders the
          // thumbnail (same shape as the ImageAsset row above).
          width = 0,
          height = 0,
          pagesJson = Some(pagesJson),
          bannerConfigJson = req.bannerConfigJson.filter(_.nonEmpty),
          lpTextSnapshot = req.lpTextSnapshot.filter(_.nonEmpty),
          status = if (isDraft) CreativeStatus.Draft else CreativeStatus.Active
        )
        val creativeF = creativeRepo.map(_.put(creative)).getOrElse(Future.successful(()))

        if (isDraft) {
          // Drafts get a thumbnail render so the advertiser can spot
          // them in the creatives list, but skip entity wiring, campaign
          // assignment, and Gemini verification. The render pass uses
          // skipVerify=true; on success it writes the s3Key and PNG hash
          // into the creative row while leaving status=Draft.
          val responseF = (for {
            _ <- imgAssetF
            _ <- creativeF
          } yield Right(CreateCreativeResponse(
            id = id.value,
            campaignId = campaignId,
            status = "draft_saved"
          ))).recover { case ex =>
            Left(ErrorResponse("draft_save_failed", ex.getMessage))
          }

          responseF.foreach { _ =>
            creativeProcessor.foreach { processor =>
              processor ! CreativeProcessor.Process(
                creativeId = id.value,
                advertiserId = advertiserId,
                campaignId = campaignId,
                name = req.name,
                landingUrl = req.landingUrl,
                pages = req.pages.map(p =>
                  CreativeProcessor.PageData(
                    p.tag, p.headline, p.sub, p.body, p.accent, p.bg,
                    p.imgEmoji, p.caption, p.img,
                    p.layout, p.banners, p.designAspect, p.videoBg, p.textureBg
                  )),
                originalPagesJson = pagesJson,
                originalHash = hash,
                skipVerify = true,
                lpFonts = req.lpFonts.getOrElse(Vector.empty).map(f =>
                  CreativeProcessor.LPFontGrant(f.family, f.weight, f.hash))
              )
            }
          }
          responseF
        } else {
          val entityCreative = AdvertiserEntity.Creative(id = id)

          val addF: Future[AdvertiserEntity.CreativeAdded] =
            advertiserRef(advertiserId).ask(AdvertiserEntity.AddCreative(entityCreative, _))

          val assignF: Future[CampaignEntity.CreativesAssigned] =
            campaignRef(advertiserId, campaignId).ask(CampaignEntity.AssignCreatives(Set(id), _))

          // Wait for DB writes + entity asks before responding
          val responseF = (for {
            _ <- imgAssetF
            _ <- creativeF
            _ <- addF
            _ <- assignF
          } yield Right(CreateCreativeResponse(
            id = id.value,
            campaignId = campaignId,
            status = "created_and_assigned"
          ))).recover { case ex =>
            Left(ErrorResponse("create_failed", ex.getMessage))
          }

          // Delegate background processing to the CreativeProcessor actor.
          // Synthetic-load drivers (RunScenario, RotateCreative) set
          // `skipVerify=true` so simulation doesn't depend on Playwright
          // or Gemini availability.
          if (!req.skipVerify.contains(true)) {
            responseF.foreach { _ =>
              creativeProcessor.foreach { processor =>
                processor ! CreativeProcessor.Process(
                  creativeId = id.value,
                  advertiserId = advertiserId,
                  campaignId = campaignId,
                  name = req.name,
                  landingUrl = req.landingUrl,
                  pages = req.pages.map(p =>
                    CreativeProcessor.PageData(
                      p.tag, p.headline, p.sub, p.body, p.accent, p.bg,
                      p.imgEmoji, p.caption, p.img,
                      p.layout, p.banners, p.designAspect, p.videoBg, p.textureBg
                    )),
                  originalPagesJson = pagesJson,
                  originalHash = hash,
                  lpFonts = req.lpFonts.getOrElse(Vector.empty).map(f =>
                    CreativeProcessor.LPFontGrant(f.family, f.weight, f.hash))
                )
              }
            }
          }

          responseF
        }
      } // end build()

      // LP-immutability guard: the landing page is the creative's fixed
      // identity anchor (copy/images/layout are editable, the LP is not).
      // The LP is owned by the campaign — a creative must inherit it and
      // cannot point elsewhere — so enforce req.landingUrl == campaign LP
      // for every create. Additionally reject a re-save (same creativeId)
      // that changes the existing creative's landingUrl.
      val campaignLpF: Future[Option[String]] = {
        val infoF: Future[CampaignEntity.CampaignInfo] =
          campaignRef(advertiserId, campaignId).ask(CampaignEntity.GetCampaign(_))
        infoF.map(_.landingUrl).recover { case _ => None }
      }

      campaignLpF.flatMap { campaignLp =>
        if (campaignLp.exists(lp => lp.trim.nonEmpty && CreativeGuards.landingUrlChanged(Some(lp), req.landingUrl)))
          Future.successful(Left(ErrorResponse(
            "lp_immutable",
            "A creative's landing page is fixed by its campaign and cannot be changed")))
        else (creativeRepo, req.creativeId.filter(_.nonEmpty)) match {
          case (Some(repo), Some(_)) =>
            repo.get(id.value).flatMap { existing =>
              if (CreativeGuards.landingUrlChanged(existing.map(_.landingUrl), req.landingUrl))
                Future.successful(Left(ErrorResponse(
                  "lp_immutable",
                  "Landing page cannot be changed after a creative is created")))
              else build()
            }
          case _ => build()
        }
      }
  }

  // Magazine creative background processing delegated to CreativeProcessor actor

  /** Reprocess an existing creative (re-render banner, re-verify). */
  private val reprocessCreativeRoute: Route = {
    import org.apache.pekko.http.scaladsl.server.Directives.*
    import org.apache.pekko.http.scaladsl.model.*
    import spray.json.*

    pathPrefix("v1" / "advertisers" / Segment / "creatives" / Segment / "reprocess") { (advertiserId, creativeId) =>
      post {
        (creativeRepo, creativeProcessor) match {
          case (Some(repo), Some(processor)) =>
            onComplete(repo.get(creativeId)) {
              // IDOR guard: reprocessing is a write side-effect on the
              // creative. Only the owning advertiser may trigger it; a
              // foreign or missing creative is reported as not-found so we
              // don't disclose other advertisers' creative ids.
              case scala.util.Success(Some(creative)) if creative.advertiserId != advertiserId =>
                complete(StatusCodes.NotFound -> s"""{"error":"Creative $creativeId not found"}""")
              case scala.util.Success(Some(creative)) if creative.pagesJson.isDefined =>
                import promovolve.creative.BannerPage
                val pages = creative.pagesJson.get.parseJson.convertTo[Vector[BannerPage]]
                processor ! CreativeProcessor.Process(
                  creativeId = creative.creativeId,
                  advertiserId = creative.advertiserId,
                  campaignId = creative.campaignId,
                  name = creative.name,
                  landingUrl = creative.landingUrl,
                  pages = pages.map(p =>
                    CreativeProcessor.PageData(
                      p.tag, p.headline, p.sub, p.body, p.accent, p.bg,
                      p.imgEmoji, p.caption, p.img,
                      p.layout, p.banners, p.designAspect, p.videoBg, p.textureBg
                    )),
                  originalPagesJson = creative.pagesJson.get,
                  originalHash = creative.imageHash
                )
                complete(StatusCodes.Accepted -> s"""{"status":"reprocessing","creativeId":"$creativeId"}""")
              case scala.util.Success(Some(_)) =>
                complete(StatusCodes.BadRequest -> s"""{"error":"Creative $creativeId has no pages JSON"}""")
              case scala.util.Success(None) =>
                complete(StatusCodes.NotFound -> s"""{"error":"Creative $creativeId not found"}""")
              case scala.util.Failure(ex) =>
                complete(StatusCodes.InternalServerError -> s"""{"error":"${ex.getMessage}"}""")
            }
          case _ =>
            complete(StatusCodes.ServiceUnavailable -> """{"error":"Processor not available"}""")
        }
      }
    }
  }

  // ----------------- Page Classification Logic -----------------
  private val classifyPageLogic
      : ((String, String, ClassifyPageRequest)) => Future[Either[ErrorResponse, ClassifyPageResponse]] = {
    case (publisherId, siteId, req) =>
      val slots = req.slots.map { s =>
        val sz = AdSize(s.width, s.height)
        // TODO multi-size source — ClassifyPageRequest.slots carries
        // one (width,height) today; expand declaredSizes when the
        // request grows a candidate list.
        AuctioneerEntity.AdSlotSpec(
          slotId = SlotId(s.slotId),
          declaredSizes = List(sz),
          computedSize = sz
        )
      }.toList

      // Trigger the auction
      auctioneerRef(siteId) ! AuctioneerEntity.PageCategoriesClassified(
        url = URL(req.url),
        categoryScores = req.categories,
        slots = slots,
        ts = Instant.now()
      )

      // Poll until candidates appear
      val maxAttempts = 20
      val pollInterval = 100.millis
      val slotId = req.slots.headOption.map(_.slotId).getOrElse("slot-1")

      def pollForCandidates(attempt: Int): Future[String] = {
        if (attempt >= maxAttempts) {
          Future.successful("timeout")
        } else {
          val pendingF = adServerRef(siteId)
            .ask[AdServer.PendingList](AdServer.ListPending(_))
            .map { pending =>
              pending.items.exists(item => item.url == req.url && item.slotId == slotId)
            }
            .recover { case _ => false }

          val key = serveIndexKey(siteId, slotId)
          val serveIndexF = serveIndex
            .ask[Option[ServeView]](ref => ServeIndexDData.Get(key, ref))
            .map(_.exists(_.candidates.nonEmpty))
            .recover { case _ => false }

          for {
            hasPending <- pendingF
            hasServeIndex <- serveIndexF
            result <- if (hasPending || hasServeIndex) {
              Future.successful("auction_complete")
            } else {
              org.apache.pekko.pattern.after(pollInterval)(pollForCandidates(attempt + 1))
            }
          } yield result
        }
      }

      pollForCandidates(0).map { status =>
        Right(ClassifyPageResponse(
          url = req.url,
          status = status,
          categoriesCount = req.categories.size,
          slotsCount = req.slots.size
        ))
      }
  }

  // ----------------- Bulk Approval Logic -----------------
  private val bulkApproveLogic
      : ((String, String, BulkApproveRequest)) => Future[Either[ErrorResponse, BulkApproveResponse]] = {
    case (publisherId, siteId, req) =>
      val approveF: Future[AdServer.ApproveAllResult] =
        adServerRef(siteId).ask(AdServer.ApproveAll(req.url, req.slotId, _))

      approveF
        .map { result =>
          // Publish SSE event for real-time UI updates
          if (result.approved > 0) {
            pendingEventHub.foreach(_ ! PendingEventHub.PublishBulkApproved(siteId, req.url, req.slotId,
              result.approved))
          }
          Right(BulkApproveResponse(
            url = req.url,
            slotId = req.slotId,
            approved = result.approved,
            failed = result.failed
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("bulk_approve_failed", ex.getMessage))
        }
  }

  // ----------------- Site Stats Logic -----------------
  private val getSiteStatsLogic: ((String, String)) => Future[Either[ErrorResponse, SiteStats]] = {
    case (publisherId, siteId) =>
      val statsFuture: Future[AdServer.ServeStats] =
        adServerRef(siteId).ask(AdServer.GetServeStats(_))
      val pacingConfigFuture: Future[SiteEntity.PacingConfig] =
        siteRef(siteId).ask(SiteEntity.GetPacingConfig(_))

      (for {
        stats <- statsFuture
        pacingConfig <- pacingConfigFuture
      } yield {
        val now = Instant.now()
        val elapsedSeconds = stats.dayStart match {
          case Some(dayStart) => java.time.Duration.between(dayStart, now).getSeconds.toDouble
          case None           => 0.0
        }
        val elapsedHours = elapsedSeconds / 3600.0
        val dayDurationHours = pacingConfig.dayDurationSeconds / 3600.0

        // Use learned traffic shape from AdServer stats for expected spend calculation
        val isWeekend = {
          val dayOfWeek = java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek
          dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY
        }
        val learnedShape = if (isWeekend) stats.weekendShapeVolumes else stats.weekdayShapeVolumes
        val expectedFraction = learnedShape match {
          case Some(volumes) if volumes.length == 24 =>
            val scaledElapsed = elapsedSeconds * (86400.0 / pacingConfig.dayDurationSeconds)
            val tracker = TrafficShapeTracker(bucketCount = 24)
            tracker.restore(volumes)
            tracker.cumulativeFractionAtTime(scaledElapsed)
          case _ =>
            elapsedHours / dayDurationHours
        }

        val hasTrafficShape = learnedShape.exists(_.length == 24)
        val shapeInfo = if (hasTrafficShape) " (traffic-shaped)" else " (linear)"
        val pacingNote =
          f"Elapsed ${elapsedHours}%.3fh since campaign start (${pacingConfig.dayDurationSeconds}s day). " +
          f"Expected spend$shapeInfo = ${expectedFraction * 100}%.3f%% of daily budget."

        Right(SiteStats(
          siteId = stats.siteId,
          total = stats.total,
          selected = stats.selected,
          pacingSkipped = stats.pacingSkipped,
          budgetExhausted = stats.budgetExhausted,
          noCandidates = stats.noCandidates,
          contentTooOld = stats.contentTooOld,
          totalSpend = stats.totalSpend,
          elapsedHours = elapsedHours,
          expectedSpendFraction = expectedFraction,
          pacingNote = pacingNote,
          trafficShapeSummary = stats.trafficShapeSummary,
          weekdayShapeVolumes = stats.weekdayShapeVolumes.map(_.toVector),
          weekendShapeVolumes = stats.weekendShapeVolumes.map(_.toVector)
        ))
      }).recover { case ex =>
        Left(ErrorResponse("get_stats_failed", ex.getMessage))
      }
  }

  // ----------------- Passivate AdServer Logic -----------------
  private val passivateAdServerLogic: ((String, String)) => Future[Either[ErrorResponse, Unit]] = {
    case (publisherId, siteId) =>
      adServerRef(siteId) ! AdServer.Passivate
      Future.successful(Right(()))
  }

  // ----------------- AdServer Stats Logic -----------------
  private val getAdServerStatsLogic: ((String, String)) => Future[Either[ErrorResponse, AdServerStatsResponse]] = {
    case (publisherId, siteId) =>
      val statsFuture: Future[AdServer.CreativeStatsMap] =
        adServerRef(siteId).ask(AdServer.GetCreativeStats(_))

      statsFuture
        .map { snapshot =>
          val creatives = snapshot.stats.map { case (cid, stats) =>
            val ctr = if (stats.impressions > 0) stats.clicks.toDouble / stats.impressions else 0.0
            val foldRate = if (stats.impressions > 0) stats.folds.toDouble / stats.impressions else 0.0
            CreativeStats(
              creativeId = cid,
              impressions = stats.impressions,
              clicks = stats.clicks,
              folds = stats.folds,
              ctr = ctr,
              foldRate = foldRate
            )
          }.toVector.sortBy(-_.impressions)

          Right(AdServerStatsResponse(
            siteId = snapshot.siteId,
            creatives = creatives
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("get_adserver_stats_failed", ex.getMessage))
        }
  }

  // ----------------- Serve Index Logic -----------------
  private def buildCandidates(view: ServeView): Future[Vector[ServeIndexCandidate]] =
    Future.traverse(view.candidates) { c =>
      val creativeF = creativeRepo.map(_.get(c.creativeId.value)).getOrElse(Future.successful(None))
      creativeF.map { creative =>
        ServeIndexCandidate(
          creativeId = c.creativeId.value,
          campaignId = c.campaignId.value,
          advertiserId = c.advertiserId.value,
          cpm = c.cpm.toDouble,
          category = c.category.value,
          assetUrl = creative.map(cr => s"$cdnBaseUrl/${cr.s3Key}"),
          landingDomain = Some(c.landingDomain)
        )
      }
    }

  private val getServeIndexLogic
      : ((String, String, Option[String])) => Future[Either[ErrorResponse, ServeIndexResponse]] = {
    case (publisherId, siteId, Some(slotId)) =>
      val key = serveIndexKey(siteId, slotId)
      val getFuture: Future[Option[ServeView]] =
        serveIndex.ask[Option[ServeView]](ref => ServeIndexDData.Get(key, ref))

      getFuture
        .flatMap {
          case Some(view) =>
            buildCandidates(view).map { candidates =>
              Right(ServeIndexResponse(
                slotId = Some(slotId),
                found = true,
                candidateCount = candidates.size,
                candidates = candidates
              ))
            }
          case None =>
            Future.successful(Right(ServeIndexResponse(
              slotId = Some(slotId),
              found = false,
              candidateCount = 0,
              candidates = Vector.empty
            )))
        }
        .recover { case ex =>
          Left(ErrorResponse("get_serve_index_failed", ex.getMessage))
        }

    case (publisherId, siteId, None) =>
      // Get all slot keys for this site, query each, merge and deduplicate by creativeId (keep highest CPM)
      serveIndex
        .ask[ServeIndexDData.SiteKeys](ServeIndexDData.GetKeysBySite(siteId, _))
        .flatMap { siteKeys =>
          val slotIds = siteKeys.keys
          if (slotIds.isEmpty) {
            Future.successful(Right(ServeIndexResponse(
              slotId = None,
              found = false,
              candidateCount = 0,
              candidates = Vector.empty
            )))
          } else {
            val viewFutures = slotIds.map { sid =>
              val key = serveIndexKey(siteId, sid)
              serveIndex.ask[Option[ServeView]](ref => ServeIndexDData.Get(key, ref))
            }
            Future.sequence(viewFutures).flatMap { views =>
              Future.traverse(views.flatten)(buildCandidates).map { allCandidatesNested =>
                val allCandidates = allCandidatesNested.flatten
                // Deduplicate by creativeId, keeping the entry with the highest CPM
                val deduped = allCandidates
                  .groupBy(_.creativeId)
                  .values
                  .map(_.maxBy(_.cpm))
                  .toVector
                  .sortBy(-_.cpm)
                Right(ServeIndexResponse(
                  slotId = None,
                  found = deduped.nonEmpty,
                  candidateCount = deduped.size,
                  candidates = deduped
                ))
              }
            }
          }
        }
        .recover { case ex =>
          Left(ErrorResponse("get_serve_index_failed", ex.getMessage))
        }
  }

  private val getServeIndexKeysLogic: ((String, String)) => Future[Either[ErrorResponse, ServeIndexKeysResponse]] = {
    case (publisherId, siteId) =>
      serveIndex
        .ask[ServeIndexDData.SiteKeys](ServeIndexDData.GetKeysBySite(siteId, _))
        .map { result =>
          Right(ServeIndexKeysResponse(
            siteId = siteId,
            keys = result.keys
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("get_serve_index_keys_failed", ex.getMessage))
        }
  }

  // ----------------- Pacing Reset Logic -----------------
  private val resetPacingLogic
      : ((String, String, PacingResetRequest)) => Future[Either[ErrorResponse, PacingResetResponse]] = {
    case (publisherId, siteId, req) =>
      if (req.campaigns.nonEmpty) {
        val resetFutures = req.campaigns.map { camp =>
          campaignRef(camp.advertiserId, camp.campaignId)
            .ask[CampaignEntity.DayStartReset](CampaignEntity.ResetDayStart(_))
            .map { result =>
              CampaignResetResult(
                campaignId = result.campaignId.value,
                newDayStart = Some(result.newDayStart.toString)
              )
            }
            .recover { case ex =>
              CampaignResetResult(
                campaignId = camp.campaignId,
                error = Some(ex.getMessage)
              )
            }
        }

        // Fire-and-forget reset on each unique AdvertiserEntity so the
        // parent-level `spendToday` rollup zeroes alongside the campaigns.
        // Without this, the dashboard's "Spent Today" stays at the
        // advertiser's accumulated total even after every campaign was
        // reset.
        req.campaigns.map(_.advertiserId).distinct.foreach { advId =>
          advertiserRef(advId) ! AdvertiserEntity.ResetDayStart(system.ignoreRef, silent = false)
        }

        Future.sequence(resetFutures).map { results =>
          Right(PacingResetResponse(
            resetCount = results.size,
            campaigns = results
          ))
        }
      } else {
        Future.successful(Right(PacingResetResponse(
          resetCount = 0,
          campaigns = Vector.empty
        )))
      }
  }

  // ----------------- Taxonomy Stats Logic -----------------
  private val getTaxonomyStatsLogic
      : ((String, String, String)) => Future[Either[ErrorResponse, TaxonomyStatsResponse]] = {
    case (_publisherId, siteId, category) =>
      val rankerEntityId = s"$category|$siteId"
      val rankerRef = sharding.entityRefFor(TaxonomyRankerEntity.TypeKey, rankerEntityId)
      val statsFuture: Future[TaxonomyRankerEntity.StatsSnapshot] =
        rankerRef.ask(TaxonomyRankerEntity.GetStats(_))

      statsFuture
        .map { stats =>
          Right(TaxonomyStatsResponse(
            category = stats.categoryId,
            siteId = stats.siteId,
            wins = stats.wins,
            clicks = stats.clicks,
            revenue = stats.revenue,
            ctr = stats.ctr,
            meanCtr = stats.meanCtr
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("get_taxonomy_stats_failed", ex.getMessage))
        }
  }

  // ----------------- Register Campaigns Logic -----------------
  private val registerCampaignsLogic
      : ((String, RegisterCampaignsRequest)) => Future[Either[ErrorResponse, RegisterCampaignsResponse]] = {
    case (category, req) =>
      val campaigns = req.campaigns.map { case (campId, advId) =>
        CampaignId(campId) -> AdvertiserId(advId)
      }
      val ackFuture = categoryBidderRef(category).ask[CategoryBidderEntity.ActiveCampaignsAck](ref =>
        CategoryBidderEntity.ActiveCampaigns(campaigns, ref)
      )

      ackFuture
        .map { _ =>
          Right(RegisterCampaignsResponse(
            category = category,
            campaignCount = req.campaigns.size,
            status = "registered"
          ))
        }
        .recover { case ex =>
          Left(ErrorResponse("register_failed", ex.getMessage))
        }
  }

  // ----------------- Routes -----------------
  private val authRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.login.serverLogic(loginLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.publisherLogin.serverLogic(publisherLoginLogic))
  )

  private val advertiserRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.listAdvertisers.serverLogic(listAdvertisersLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getAdvertiser.serverLogic(getAdvertiserLogic))
  )

  // ----------------- Pacing Config Logic -----------------
  private val additionalAdvertiserRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.updateAdvertiserBudget.serverLogic(updateAdvertiserBudgetLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.blockSites.serverLogic(blockSitesLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.unblockSites.serverLogic(unblockSitesLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getServedSites.serverLogic(getServedSitesLogic))
  )
  private val campaignRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.listCampaigns.serverLogic(listCampaignsLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getCampaign.serverLogic(getCampaignOwnedLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.createCampaign.serverLogic(createCampaignLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.updateCampaign.serverLogic(updateCampaignLogic))
  )

  // ----------------- Taxonomy Logic -----------------
  private val additionalCampaignRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.getCampaignBudget.serverLogic(getCampaignBudgetLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getCampaignWinRate.serverLogic(getCampaignWinRateLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.unassignCreatives.serverLogic(unassignCreativesLogic))
  )

  private val publisherRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.getPublisher.serverLogic(getPublisherLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.blockDomains.serverLogic(blockDomainsLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.unblockDomains.serverLogic(unblockDomainsLogic))
  )
  // =============== Site Verification ===============

  private val getVerificationTokenLogic
      : ((String, String)) => Future[Either[ErrorResponse, VerificationTokenResponse]] = {
    case (publisherId, siteId) =>
      siteRef(siteId)
        .ask[SiteEntity.VerificationTokenResult](SiteEntity.GetVerificationToken(_))
        .map {
          case SiteEntity.VerificationTokenFound(_, token, domain) =>
            val fileUrl = s"http://$domain/.well-known/promovolve.txt"
            val fileContent = SiteEntity.verificationRecord(token.value)
            Right(VerificationTokenResponse(
              token.value, domain, fileUrl, fileContent,
              dnsRecordName = SiteEntity.dnsVerificationName(domain),
              dnsRecordValue = fileContent
            ))
          case SiteEntity.VerificationTokenNotAvailable(_) =>
            Left(ErrorResponse("token_not_available", "Site not initialized or no verification token"))
        }
        .recover { case ex =>
          Left(ErrorResponse("get_token_failed", ex.getMessage))
        }
  }

  private val verifySiteLogic: ((String, String)) => Future[Either[ErrorResponse, VerificationResponse]] = {
    case (publisherId, siteId) =>
      siteRef(siteId)
        .ask[SiteEntity.VerificationResult](SiteEntity.RequestVerification(_))
        .map {
          case SiteEntity.VerificationSucceeded(_, host) =>
            Right(VerificationResponse(verified = true, host = Some(host)))
          case SiteEntity.VerificationFailed(_, reason) =>
            Right(VerificationResponse(verified = false, reason = Some(reason)))
          case SiteEntity.VerificationNotReady(_, reason) =>
            Left(ErrorResponse("verification_not_ready", reason))
        }
        .recover { case ex =>
          Left(ErrorResponse("verification_failed", ex.getMessage))
        }
  }

  /** Force-verify a site's host (dev/testing only — bypasses HTTP check) */
  private val forceVerifySiteLogic
      : ((String, String, String)) => Future[Either[ErrorResponse, VerificationResponse]] = {
    case (publisherId, siteId, host) =>
      siteRef(siteId)
        .ask[SiteEntity.VerificationSucceeded](SiteEntity.ForceVerifyHost(host, _))
        .map { case SiteEntity.VerificationSucceeded(_, h) =>
          Right(VerificationResponse(verified = true, host = Some(h)))
        }
        .recover { case ex =>
          Left(ErrorResponse("force_verify_failed", ex.getMessage))
        }
  }

  private val siteRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.listSites.serverLogic(listSitesLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getSite.serverLogic(gateSite2(getSiteLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.createSite.serverLogic(createSiteLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.updateSite.serverLogic(gateSite3(updateSiteLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.deleteSite.serverLogic(deleteSiteLogic)),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getVerificationToken.serverLogic(gateSite2(getVerificationTokenLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.verifySite.serverLogic(gateSite2(verifySiteLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.forceVerifySite.serverLogic(gateSite3(forceVerifySiteLogic)))
  )

  private val resetFloorAgentLogic: ((String, String)) => Future[Either[ErrorResponse, Unit]] = {
    case (_publisherId, siteId) =>
      siteRef(siteId)
        .ask[SiteEntity.FloorAgentReset](SiteEntity.ResetFloorAgent(_))
        .map(_ => Right(()))
        .recover { case ex => Left(ErrorResponse("reset_floor_agent_failed", ex.getMessage)) }
  }

  private val getFloorObservationsLogic
      : ((String, String, Int)) => Future[Either[ErrorResponse, RecentFloorObservationsResponse]] = {
    case (_publisherId, siteId, limit) =>
      val cappedLimit = math.max(1, math.min(1000, limit))
      siteRef(siteId)
        .ask[SiteEntity.FloorObservationsResult](SiteEntity.GetRecentFloorObservations(cappedLimit, _)).map(
          _.observations)
        .map { obs =>
          Right(RecentFloorObservationsResponse(
            siteId = siteId,
            observations = obs.map { o =>
              FloorObservationResponse(
                ts = o.ts.toString,
                hour = o.hour,
                trafficShape = o.trafficShape,
                floorBefore = f"${o.floorBefore}%.4f",
                floorAfter = f"${o.floorAfter}%.4f",
                epsilon = o.epsilon,
                observed = o.observed,
                trainingLoss = o.trainingLoss,
                slotOverrideCount = o.slotOverrideCount
              )
            }
          ))
        }
        .recover { case ex => Left(ErrorResponse("get_floor_observations_failed", ex.getMessage)) }
  }

  private val getAdvertiserSpendTodayLogic: String => Future[Either[ErrorResponse, AdvertiserSpendTodayResponse]] = {
    advertiserId =>
      val sinceUtcLabel = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        .toLocalDate.atStartOfDay().toString + "Z"
      val zero = AdvertiserSpendTodayResponse(
        advertiserId = advertiserId,
        sinceUtc = sinceUtcLabel,
        spend = "0.0000",
        impressions = 0L,
        eCpm = "0.0000"
      )
      dashboardDb match {
        case None     => Future.successful(Right(zero))
        case Some(db) =>
          import slick.jdbc.PostgresProfile.api.*
          import slick.jdbc.GetResult
          given GetResult[(Long, Double)] = GetResult(using r => (r.nextLong(), r.nextDouble()))
          val q = sql"""
            SELECT COUNT(*)::bigint, COALESCE(SUM(cpm) / 1000.0, 0.0)::double precision
            FROM tracking_events
            WHERE advertiser_id = $advertiserId
              AND event_type = 'impression'
              AND event_time >= date_trunc('day', NOW() AT TIME ZONE 'UTC')
          """.as[(Long, Double)].headOption
          db.run(q).map { opt =>
            val (imps, spend) = opt.getOrElse((0L, 0.0))
            val eCpm = if (imps > 0L) spend / imps.toDouble * 1000.0 else 0.0
            Right(AdvertiserSpendTodayResponse(
              advertiserId = advertiserId,
              sinceUtc = sinceUtcLabel,
              spend = f"$spend%.4f",
              impressions = imps,
              eCpm = f"$eCpm%.4f"
            ))
          }.recover { case ex =>
            Left(ErrorResponse("spend_today_failed", ex.getMessage))
          }
      }
  }

  private val getAdvertiserWinRatesLogic: String => Future[Either[ErrorResponse, AdvertiserWinRatesResponse]] = {
    advertiserId =>
      // One entity fan-out (bidsToday lives on each CampaignEntity) +
      // one projection query (per-campaign impressions today, plus the
      // global total as the impression-share denominator — the same
      // denominator the single-campaign endpoint uses).
      val infoF: Future[AdvertiserEntity.AdvertiserInfo] =
        advertiserRef(advertiserId).ask(AdvertiserEntity.GetAdvertiserInfo(_))
      infoF.flatMap { info =>
        val statsF: Future[Map[String, CampaignEntity.CampaignStats]] =
          Future.sequence(
            info.campaignIds.toVector.map { campId =>
              campaignRef(advertiserId, campId.value)
                .ask[CampaignEntity.CampaignStats](CampaignEntity.GetCampaignStats(_))
                .map(s => Some(campId.value -> s))
                .recover { case _ => None } // unreachable entity → skip its row
            }
          ).map(_.flatten.toMap)

        import slick.jdbc.PostgresProfile.api.*
        import slick.jdbc.GetResult
        given GetResult[(String, Long)] = GetResult(using r => (r.nextString(), r.nextLong()))
        // All of today's rows, filtered to this advertiser in memory —
        // campaign_daily_stats holds one row per campaign per day, and
        // the global SUM is needed anyway for the share denominator.
        val impsF: Future[Vector[(String, Long)]] = dashboardDb match {
          case Some(db) =>
            db.run(
              sql"SELECT campaign_id, COALESCE(impressions, 0) FROM campaign_daily_stats WHERE day_bucket = CURRENT_DATE"
                .as[(String, Long)]
            ).map(_.toVector)
          case None => Future.successful(Vector.empty)
        }

        for {
          stats <- statsF
          impRows <- impsF
        } yield {
          val totalImps = impRows.iterator.map(_._2).sum
          val impsById = impRows.toMap
          val rates = stats.toVector.map { case (cid, s) =>
            val today = impsById.getOrElse(cid, 0L)
            CampaignWinRateInfo(
              campaignId = cid,
              bidsToday = s.bidsToday,
              impressionsToday = today,
              winRate = if (totalImps > 0) today.toDouble / totalImps else 0.0
            )
          }
          Right(AdvertiserWinRatesResponse(advertiserId, totalImps, rates))
        }
      }.recover { case ex =>
        Left(ErrorResponse("win_rates_failed", ex.getMessage))
      }
  }

  private val getAdvertiserCampaignSpendTodayLogic
      : String => Future[Either[ErrorResponse, AdvertiserCampaignSpendTodayResponse]] = {
    advertiserId =>
      val sinceUtcLabel = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        .toLocalDate.atStartOfDay().toString + "Z"
      val zero = AdvertiserCampaignSpendTodayResponse(advertiserId, sinceUtcLabel, Vector.empty)
      dashboardDb match {
        case None     => Future.successful(Right(zero))
        case Some(db) =>
          import slick.jdbc.PostgresProfile.api.*
          import slick.jdbc.GetResult
          given GetResult[(String, Long, Double)] =
            GetResult(using r => (r.nextString(), r.nextLong(), r.nextDouble()))
          val q = sql"""
            SELECT campaign_id, COUNT(*)::bigint, COALESCE(SUM(cpm) / 1000.0, 0.0)::double precision
            FROM tracking_events
            WHERE advertiser_id = $advertiserId
              AND event_type = 'impression'
              AND event_time >= date_trunc('day', NOW() AT TIME ZONE 'UTC')
            GROUP BY campaign_id
          """.as[(String, Long, Double)]
          db.run(q).map { rows =>
            Right(AdvertiserCampaignSpendTodayResponse(
              advertiserId = advertiserId,
              sinceUtc = sinceUtcLabel,
              campaigns = rows.toVector.map { case (cid, imps, spend) =>
                CampaignSpendTodayRow(cid, f"$spend%.4f", imps)
              }
            ))
          }.recover { case ex =>
            Left(ErrorResponse("campaign_spend_today_failed", ex.getMessage))
          }
      }
  }

  private val getAdvertiserHourlyTodayLogic: String => Future[Either[ErrorResponse, AdvertiserHourlyTodayResponse]] = {
    advertiserId =>
      val sinceUtcLabel = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        .toLocalDate.atStartOfDay().toString + "Z"
      val zero = AdvertiserHourlyTodayResponse(advertiserId, sinceUtcLabel, Vector.empty)
      dashboardDb match {
        case None     => Future.successful(Right(zero))
        case Some(db) =>
          import slick.jdbc.PostgresProfile.api.*
          import slick.jdbc.GetResult
          given GetResult[(Int, Long, Double)] =
            GetResult(using r => (r.nextInt(), r.nextLong(), r.nextDouble()))
          val q = sql"""
            SELECT EXTRACT(HOUR FROM event_time AT TIME ZONE 'UTC')::int,
                   COUNT(*)::bigint,
                   COALESCE(SUM(cpm) / 1000.0, 0.0)::double precision
            FROM tracking_events
            WHERE advertiser_id = $advertiserId
              AND event_type = 'impression'
              AND event_time >= date_trunc('day', NOW() AT TIME ZONE 'UTC')
            GROUP BY 1
            ORDER BY 1
          """.as[(Int, Long, Double)]
          db.run(q).map { rows =>
            Right(AdvertiserHourlyTodayResponse(
              advertiserId = advertiserId,
              sinceUtc = sinceUtcLabel,
              hours = rows.toVector.map { case (h, imps, spend) =>
                HourlyDeliveryRow(h, imps, f"$spend%.4f")
              }
            ))
          }.recover { case ex =>
            Left(ErrorResponse("hourly_today_failed", ex.getMessage))
          }
      }
  }

  /**
   * Resolve the report date range: default = last 7 days including today
   * (UTC), inclusive bounds, capped at 92 days. Dates arrive as
   * YYYY-MM-DD strings and are bound as strings + ::date casts so this
   * file needs no SetParameter[LocalDate].
   */
  private def resolveReportRange(
      fromOpt: Option[String],
      toOpt: Option[String]
  ): Either[ErrorResponse, (String, String)] =
    try {
      val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
      val to = toOpt.filter(_.nonEmpty).map(java.time.LocalDate.parse).getOrElse(today)
      val from = fromOpt.filter(_.nonEmpty).map(java.time.LocalDate.parse).getOrElse(to.minusDays(6))
      if (from.isAfter(to))
        Left(ErrorResponse("invalid_range", s"from $from is after to $to"))
      else if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 92)
        Left(ErrorResponse("range_too_wide", "report range is capped at 92 days"))
      else Right((from.toString, to.toString))
    } catch {
      case _: java.time.format.DateTimeParseException =>
        Left(ErrorResponse("invalid_date", "dates must be YYYY-MM-DD"))
    }

  private val getAdvertiserReportLogic
      : ((String, Option[String], Option[String])) => Future[Either[ErrorResponse, AdvertiserReportResponse]] = {
    case (advertiserId, fromOpt, toOpt) =>
      resolveReportRange(fromOpt, toOpt) match {
        case Left(err)         => Future.successful(Left(err))
        case Right((from, to)) =>
          dashboardDb match {
            case None     => Future.successful(Right(AdvertiserReportResponse(advertiserId, from, to, Vector.empty)))
            case Some(db) =>
              import slick.jdbc.PostgresProfile.api.*
              import slick.jdbc.GetResult
              given GetResult[(String, String, Long, Long, Long, Double, Long, Long, Long, Long, Long)] =
                GetResult(using r =>
                  (r.nextString(), r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(), r.nextDouble(),
                    r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong()))
              // Ownership gate: daily stats carry no advertiser_id — same
              // EXISTS-against-campaign_stats idiom as DashboardRoutes.
              val q = sql"""
                SELECT d.day_bucket::text, d.campaign_id, d.impressions, d.clicks, d.cta_clicks,
                       d.spend::double precision, d.dogeared_impressions,
                       d.folds, d.unfolds, d.dogeared_clicks, d.dogeared_cta_clicks
                FROM campaign_daily_stats d
                WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                              WHERE cs.campaign_id = d.campaign_id AND cs.advertiser_id = $advertiserId)
                  AND d.day_bucket BETWEEN $from::date AND $to::date
                ORDER BY d.day_bucket DESC, d.campaign_id
              """.as[(String, String, Long, Long, Long, Double, Long, Long, Long, Long, Long)]
              db.run(q).map { rows =>
                Right(AdvertiserReportResponse(
                  advertiserId = advertiserId,
                  from = from,
                  to = to,
                  rows = rows.toVector.map {
                    case (day, cid, imps, clicks, ctas, spend, dogeared, folds, unfolds, dgClicks, dgCtas) =>
                      ReportDailyRow(day, cid, imps, clicks, ctas, f"$spend%.4f", dogeared,
                        folds, unfolds, dgClicks, dgCtas)
                  }
                ))
              }.recover { case ex =>
                Left(ErrorResponse("report_failed", ex.getMessage))
              }
          }
      }
  }

  private val getAdvertiserReportBreakdownLogic: ((String, Option[String], Option[String], String)) => Future[Either[
    ErrorResponse, AdvertiserReportBreakdownResponse]] = {
    case (advertiserId, fromOpt, toOpt, dim) =>
      resolveReportRange(fromOpt, toOpt) match {
        case Left(err)         => Future.successful(Left(err))
        case Right((from, to)) =>
          dashboardDb match {
            case None =>
              Future.successful(Right(AdvertiserReportBreakdownResponse(advertiserId, from, to, dim, Vector.empty, "")))
            case Some(db) =>
              import slick.jdbc.PostgresProfile.api.*
              import slick.jdbc.GetResult
              given GetResult[(String, String, Long, Long, Long, Double, Long)] =
                GetResult(using r =>
                  (r.nextString(), r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(), r.nextDouble(),
                    r.nextLong()))
              val rowsQ = dim match {
                case "site" =>
                  // label = real host from publisher_sites where known;
                  // '' falls back to the site_id slug platform-side.
                  Some(sql"""
                    SELECT s.site_id, COALESCE(ps.host, ''),
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    LEFT JOIN publisher_sites ps ON ps.site_id = s.site_id
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY s.site_id, ps.host
                    ORDER BY SUM(s.spend) DESC, SUM(s.impressions) DESC
                  """.as[(String, String, Long, Long, Long, Double, Long)])
                case "category" =>
                  Some(sql"""
                    SELECT s.category, '',
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY s.category
                    ORDER BY SUM(s.spend) DESC, SUM(s.impressions) DESC
                  """.as[(String, String, Long, Long, Long, Double, Long)])
                case "publisher" =>
                  // '' key = sites with no publisher_sites row (shouldn't
                  // happen for billed traffic, but never drop the spend).
                  // label = the publisher's site hosts — an advertiser-safe
                  // identity (their properties are public; emails are not).
                  Some(sql"""
                    SELECT COALESCE(ps.publisher_id, ''),
                           COALESCE(string_agg(DISTINCT NULLIF(ps.host, ''), ', '), ''),
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    LEFT JOIN publisher_sites ps ON ps.site_id = s.site_id
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY COALESCE(ps.publisher_id, '')
                    ORDER BY SUM(s.spend) DESC, SUM(s.impressions) DESC
                  """.as[(String, String, Long, Long, Long, Double, Long)])
                case _ => None
              }
              rowsQ match {
                case None =>
                  Future.successful(Left(ErrorResponse("invalid_dim", "dim must be site, category, or publisher")))
                case Some(q) =>
                  val coverageQ = sql"""
                    SELECT COALESCE(MIN(s.day_bucket)::text, '')
                    FROM campaign_dim_daily_stats s
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                  """.as[String].head
                  db.run(q.zip(coverageQ)).map { case (rows, coverageFrom) =>
                    Right(AdvertiserReportBreakdownResponse(
                      advertiserId = advertiserId,
                      from = from,
                      to = to,
                      dim = dim,
                      rows = rows.toVector.map { case (key, label, imps, clicks, ctas, spend, dogeared) =>
                        ReportBreakdownRow(key, label, imps, clicks, ctas, f"$spend%.4f", dogeared)
                      },
                      coverageFrom = coverageFrom
                    ))
                  }.recover { case ex =>
                    Left(ErrorResponse("report_breakdown_failed", ex.getMessage))
                  }
              }
          }
      }
  }

  private val getAdvertiserReportBreakdownByCampaignLogic: ((String, Option[String], Option[String], String)) => Future[
    Either[ErrorResponse, AdvertiserReportBreakdownByCampaignResponse]] = {
    case (advertiserId, fromOpt, toOpt, dim) =>
      resolveReportRange(fromOpt, toOpt) match {
        case Left(err)         => Future.successful(Left(err))
        case Right((from, to)) =>
          dashboardDb match {
            case None =>
              Future.successful(Right(AdvertiserReportBreakdownByCampaignResponse(advertiserId, from, to, dim,
                Vector.empty)))
            case Some(db) =>
              import slick.jdbc.PostgresProfile.api.*
              import slick.jdbc.GetResult
              given GetResult[(String, String, String, Long, Long, Long, Double, Long)] =
                GetResult(using r =>
                  (r.nextString(), r.nextString(), r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(),
                    r.nextDouble(), r.nextLong()))
              // Groups ordered by whole-dimension spend (window over the
              // aggregate), campaigns within a group by their own spend —
              // so the platform can group in one pass.
              val rowsQ = dim match {
                case "site" =>
                  Some(sql"""
                    SELECT s.site_id, COALESCE(ps.host, ''), s.campaign_id,
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    LEFT JOIN publisher_sites ps ON ps.site_id = s.site_id
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY s.site_id, ps.host, s.campaign_id
                    ORDER BY SUM(SUM(s.spend)) OVER (PARTITION BY s.site_id) DESC,
                             s.site_id, SUM(s.spend) DESC
                  """.as[(String, String, String, Long, Long, Long, Double, Long)])
                case "category" =>
                  Some(sql"""
                    SELECT s.category, '', s.campaign_id,
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY s.category, s.campaign_id
                    ORDER BY SUM(SUM(s.spend)) OVER (PARTITION BY s.category) DESC,
                             s.category, SUM(s.spend) DESC
                  """.as[(String, String, String, Long, Long, Long, Double, Long)])
                case _ => None
              }
              rowsQ match {
                case None =>
                  Future.successful(Left(ErrorResponse("invalid_dim", "dim must be site or category")))
                case Some(q) =>
                  db.run(q).map { rows =>
                    Right(AdvertiserReportBreakdownByCampaignResponse(
                      advertiserId = advertiserId,
                      from = from,
                      to = to,
                      dim = dim,
                      rows = rows.toVector.map { case (key, label, cid, imps, clicks, ctas, spend, dogeared) =>
                        ReportBreakdownByCampaignRow(key, label, cid, imps, clicks, ctas, f"$spend%.4f", dogeared)
                      }
                    ))
                  }.recover { case ex =>
                    Left(ErrorResponse("report_breakdown_by_campaign_failed", ex.getMessage))
                  }
              }
          }
      }
  }

  private val getAdvertiserReportBreakdownDailyLogic: ((String, Option[String], Option[String], String)) => Future[
    Either[ErrorResponse, AdvertiserReportBreakdownDailyResponse]] = {
    case (advertiserId, fromOpt, toOpt, dim) =>
      resolveReportRange(fromOpt, toOpt) match {
        case Left(err)         => Future.successful(Left(err))
        case Right((from, to)) =>
          dashboardDb match {
            case None =>
              Future.successful(Right(AdvertiserReportBreakdownDailyResponse(advertiserId, from, to, dim,
                Vector.empty)))
            case Some(db) =>
              import slick.jdbc.PostgresProfile.api.*
              import slick.jdbc.GetResult
              given GetResult[(String, String, String, Long, Long, Long, Double, Long)] =
                GetResult(using r =>
                  (r.nextString(), r.nextString(), r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(),
                    r.nextDouble(), r.nextLong()))
              // Same dimension keys/labels as the range breakdown, plus the
              // day bucket. Ordered by day so the platform can walk the
              // calendar once when zero-filling series.
              val rowsQ = dim match {
                case "site" =>
                  Some(sql"""
                    SELECT s.day_bucket::text, s.site_id, COALESCE(ps.host, ''),
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    LEFT JOIN publisher_sites ps ON ps.site_id = s.site_id
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY s.day_bucket, s.site_id, ps.host
                    ORDER BY s.day_bucket, SUM(s.spend) DESC
                  """.as[(String, String, String, Long, Long, Long, Double, Long)])
                case "category" =>
                  Some(sql"""
                    SELECT s.day_bucket::text, s.category, '',
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY s.day_bucket, s.category
                    ORDER BY s.day_bucket, SUM(s.spend) DESC
                  """.as[(String, String, String, Long, Long, Long, Double, Long)])
                case "publisher" =>
                  Some(sql"""
                    SELECT s.day_bucket::text, COALESCE(ps.publisher_id, ''),
                           COALESCE(string_agg(DISTINCT NULLIF(ps.host, ''), ', '), ''),
                           SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                           SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                    FROM campaign_dim_daily_stats s
                    LEFT JOIN publisher_sites ps ON ps.site_id = s.site_id
                    WHERE EXISTS (SELECT 1 FROM campaign_stats cs
                                  WHERE cs.campaign_id = s.campaign_id AND cs.advertiser_id = $advertiserId)
                      AND s.day_bucket BETWEEN $from::date AND $to::date
                    GROUP BY s.day_bucket, COALESCE(ps.publisher_id, '')
                    ORDER BY s.day_bucket, SUM(s.spend) DESC
                  """.as[(String, String, String, Long, Long, Long, Double, Long)])
                case _ => None
              }
              rowsQ match {
                case None =>
                  Future.successful(Left(ErrorResponse("invalid_dim", "dim must be site, category, or publisher")))
                case Some(q) =>
                  db.run(q).map { rows =>
                    Right(AdvertiserReportBreakdownDailyResponse(
                      advertiserId = advertiserId,
                      from = from,
                      to = to,
                      dim = dim,
                      rows = rows.toVector.map { case (day, key, label, imps, clicks, ctas, spend, dogeared) =>
                        ReportBreakdownDailyRow(day, key, label, imps, clicks, ctas, f"$spend%.4f", dogeared)
                      }
                    ))
                  }.recover { case ex =>
                    Left(ErrorResponse("report_breakdown_daily_failed", ex.getMessage))
                  }
              }
          }
      }
  }

  private val getPublisherSiteCategoryReportDailyLogic: ((String, Option[String], Option[String])) => Future[Either[
    ErrorResponse, PublisherSiteCategoryDailyReportResponse]] = {
    case (publisherId, fromOpt, toOpt) =>
      resolveReportRange(fromOpt, toOpt) match {
        case Left(err)         => Future.successful(Left(err))
        case Right((from, to)) =>
          dashboardDb match {
            case None =>
              Future.successful(Right(PublisherSiteCategoryDailyReportResponse(publisherId, from, to, Vector.empty)))
            case Some(db) =>
              import slick.jdbc.PostgresProfile.api.*
              import slick.jdbc.GetResult
              given GetResult[(String, String, String, String, Long, Long, Long, Double, Long)] =
                GetResult(using r =>
                  (r.nextString(), r.nextString(), r.nextString(), r.nextString(), r.nextLong(), r.nextLong(),
                    r.nextLong(), r.nextDouble(), r.nextLong()))
              // Same ownership gate as the range report: INNER JOIN on
              // publisher_id. Ordered site-first so the platform can group
              // per site in one pass.
              val q = sql"""
                SELECT s.day_bucket::text, s.site_id, ps.host, s.category,
                       SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                       SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                FROM campaign_dim_daily_stats s
                JOIN publisher_sites ps
                  ON ps.site_id = s.site_id AND ps.publisher_id = $publisherId
                WHERE s.day_bucket BETWEEN $from::date AND $to::date
                GROUP BY s.day_bucket, s.site_id, ps.host, s.category
                ORDER BY s.site_id, s.day_bucket
              """.as[(String, String, String, String, Long, Long, Long, Double, Long)]
              db.run(q).map { rows =>
                Right(PublisherSiteCategoryDailyReportResponse(
                  publisherId = publisherId,
                  from = from,
                  to = to,
                  rows = rows.toVector.map { case (day, siteId, host, category, imps, clicks, ctas, gross, dogeared) =>
                    PublisherSiteCategoryDailyRow(day, siteId, host, category, imps, clicks, ctas, f"$gross%.4f",
                      dogeared)
                  }
                ))
              }.recover { case ex =>
                Left(ErrorResponse("publisher_report_daily_failed", ex.getMessage))
              }
          }
      }
  }

  private val getPublisherSiteCategoryReportLogic: ((String, Option[String], Option[String])) => Future[Either[
    ErrorResponse, PublisherSiteCategoryReportResponse]] = {
    case (publisherId, fromOpt, toOpt) =>
      resolveReportRange(fromOpt, toOpt) match {
        case Left(err)         => Future.successful(Left(err))
        case Right((from, to)) =>
          dashboardDb match {
            case None =>
              Future.successful(Right(PublisherSiteCategoryReportResponse(publisherId, from, to, Vector.empty, "")))
            case Some(db) =>
              import slick.jdbc.PostgresProfile.api.*
              import slick.jdbc.GetResult
              given GetResult[(String, String, String, Long, Long, Long, Double, Long)] =
                GetResult(using r =>
                  (r.nextString(), r.nextString(), r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(),
                    r.nextDouble(), r.nextLong()))
              // Ownership gate = the INNER JOIN on publisher_id: rows from
              // sites the publisher doesn't own never match. Revenue stays
              // gross here — margin is platform display policy.
              val rowsQ = sql"""
                SELECT s.site_id, ps.host, s.category,
                       SUM(s.impressions), SUM(s.clicks), SUM(s.cta_clicks),
                       SUM(s.spend)::double precision, SUM(s.dogeared_impressions)
                FROM campaign_dim_daily_stats s
                JOIN publisher_sites ps
                  ON ps.site_id = s.site_id AND ps.publisher_id = $publisherId
                WHERE s.day_bucket BETWEEN $from::date AND $to::date
                GROUP BY s.site_id, ps.host, s.category
                ORDER BY s.site_id, SUM(s.spend) DESC, SUM(s.impressions) DESC
              """.as[(String, String, String, Long, Long, Long, Double, Long)]
              val coverageQ = sql"""
                SELECT COALESCE(MIN(s.day_bucket)::text, '')
                FROM campaign_dim_daily_stats s
                JOIN publisher_sites ps
                  ON ps.site_id = s.site_id AND ps.publisher_id = $publisherId
              """.as[String].head
              db.run(rowsQ.zip(coverageQ)).map { case (rows, coverageFrom) =>
                Right(PublisherSiteCategoryReportResponse(
                  publisherId = publisherId,
                  from = from,
                  to = to,
                  rows = rows.toVector.map { case (siteId, host, category, imps, clicks, ctas, gross, dogeared) =>
                    PublisherSiteCategoryRow(siteId, host, category, imps, clicks, ctas, f"$gross%.4f", dogeared)
                  },
                  coverageFrom = coverageFrom
                ))
              }.recover { case ex =>
                Left(ErrorResponse("publisher_report_failed", ex.getMessage))
              }
          }
      }
  }

  private val getSiteRevenueTodayLogic
      : ((String, String)) => Future[Either[ErrorResponse, SiteRevenueTodayResponse]] = {
    case (_publisherId, siteId) =>
      // Aggregate impression revenue from the `tracking_events` projection
      // since UTC midnight. This is the same source the advertiser dashboard
      // uses for "Today's Spend" — so publisher Revenue and advertiser Spend
      // will reconcile when both refer to "today".
      //
      // Falls back to zeros if the dashboard DB isn't configured (e.g. in a
      // dev cluster without the projection). Doesn't error — the publisher
      // page should render even when the projection is offline.
      // "Today" boundary computed server-side by postgres (date_trunc on
      // NOW() AT TIME ZONE 'UTC'), so we avoid needing a custom Slick
      // SetParameter[Instant] in this file.
      val sinceUtcLabel = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        .toLocalDate.atStartOfDay().toString + "Z"
      val zero = SiteRevenueTodayResponse(
        siteId = siteId,
        sinceUtc = sinceUtcLabel,
        revenue = "0.0000",
        impressions = 0L,
        eCpm = "0.0000"
      )
      dashboardDb match {
        case None     => Future.successful(Right(zero))
        case Some(db) =>
          import slick.jdbc.PostgresProfile.api.*
          import slick.jdbc.GetResult
          given GetResult[(Long, Double)] = GetResult(using r => (r.nextLong(), r.nextDouble()))
          val q = sql"""
            SELECT COUNT(*)::bigint, COALESCE(SUM(cpm) / 1000.0, 0.0)::double precision
            FROM tracking_events
            WHERE site_id = $siteId
              AND event_type = 'impression'
              AND event_time >= date_trunc('day', NOW() AT TIME ZONE 'UTC')
          """.as[(Long, Double)].headOption
          db.run(q).map { opt =>
            val (imps, rev) = opt.getOrElse((0L, 0.0))
            val eCpm = if (imps > 0L) rev / imps.toDouble * 1000.0 else 0.0
            Right(SiteRevenueTodayResponse(
              siteId = siteId,
              sinceUtc = sinceUtcLabel,
              revenue = f"$rev%.4f",
              impressions = imps,
              eCpm = f"$eCpm%.4f"
            ))
          }.recover { case ex =>
            Left(ErrorResponse("revenue_today_failed", ex.getMessage))
          }
      }
  }

  private val getFloorSweepHistoryLogic
      : ((String, String, Int, Option[String])) => Future[Either[ErrorResponse, FloorSweepHistoryResponse]] = {
    case (_publisherId, siteId, limit, dateOpt) =>
      floorDecisionJournal match {
        case None =>
          Future.successful(Right(FloorSweepHistoryResponse(siteId = siteId, decisions = Vector.empty)))
        case Some(journal) =>
          // When a date is provided, scope to that UTC calendar day and
          // bump the effective limit so we return the whole day's cycles
          // (a fast-sim day = ~360 cycles, well under the 1000 cap).
          val fetchF = dateOpt.flatMap { d => scala.util.Try(java.time.LocalDate.parse(d)).toOption } match {
            case Some(date) => journal.recentInDay(siteId, date, 1000)
            case None       => journal.recent(siteId, limit)
          }
          fetchF.map { decisions =>
            Right(FloorSweepHistoryResponse(
              siteId = siteId,
              decisions = decisions.toVector.map { d =>
                FloorDecisionRow(
                  ts = d.ts.toString,
                  argmaxFloor = f"${d.argmaxFloor}%.4f",
                  prevArgmax = d.prevArgmax.map(v => f"${v}%.4f"),
                  cycleRevenue = d.cycleRevenue.map(v => f"${v}%.4f"),
                  cycleImps = d.cycleImps,
                  candidates = d.candidatesJson,
                  category = d.category
                )
              }
            ))
          }.recover { case ex =>
            Left(ErrorResponse("get_sweep_history_failed", ex.getMessage))
          }
      }
  }

  private val getCategoryDemandLogic: ((String, String)) => Future[Either[ErrorResponse, CategoryDemandResponse]] = {
    case (_publisherId, siteId) =>
      siteRef(siteId)
        .ask[promovolve.proto.site.CategoryFloors](SiteEntity.GetCategoryFloors(_))
        .map { cf =>
          // Union of learned floors and live-observed categories: a floor
          // with no current bidders is a historical row; a category with
          // bidders but no learned floor yet is mid-convergence.
          val ids = (cf.floors.keySet ++ cf.bidderCounts.keySet).toVector.sorted
          Right(CategoryDemandResponse(
            categories = ids.map { id =>
              CategoryDemand(
                categoryId = id,
                categoryName = promovolve.taxonomy.TieredCategory.get(id).map(_.name).getOrElse(id),
                floor = cf.floors.get(id),
                bidders = cf.bidderCounts.getOrElse(id, 0),
                topBid = cf.observedBids.getOrElse(id, 0.0),
                sweep = cf.sweepStates.get(id).map(st =>
                  CategorySweep(
                    phase = st.phase,
                    cursor = st.cursor,
                    candidateCount = st.candidateCount,
                    ticksThisCandidate = st.ticksThisCandidate,
                    ticksPerCandidate = st.ticksPerCandidate,
                    exploitTicksRemaining = st.exploitTicksRemaining,
                    currentFloor = st.currentFloor
                  ))
              )
            },
            observationIntervalSeconds = cf.observationIntervalSeconds
          ))
        }
        .recover { case ex => Left(ErrorResponse("get_category_demand_failed", ex.getMessage)) }
  }

  private val getFloorSweepEvidenceLogic
      : ((String, String)) => Future[Either[ErrorResponse, FloorSweepEvidenceResponse]] = {
    case (_publisherId, siteId) =>
      siteRef(siteId)
        .ask[SiteEntity.FloorSweepEvidenceResponse](SiteEntity.GetFloorSweepEvidence(_))
        .map { resp =>
          resp.snapshot match {
            case None =>
              // Entity hasn't finished recovery yet — empty table.
              Right(FloorSweepEvidenceResponse(siteId = siteId))
            case Some(snap) =>
              // Candidates sorted by floor ascending so the table renders
              // as a clean low-to-high sweep regardless of measurement order.
              val rows = snap.results.sortBy(_.floor).map { c =>
                FloorSweepCandidateResponse(
                  floor = f"${c.floor}%.4f",
                  revenue = f"${c.revenue}%.4f",
                  impressions = c.impressions
                )
              }
              val prevRows = snap.previousResults.sortBy(_.floor).map { c =>
                FloorSweepCandidateResponse(
                  floor = f"${c.floor}%.4f",
                  revenue = f"${c.revenue}%.4f",
                  impressions = c.impressions
                )
              }
              Right(FloorSweepEvidenceResponse(
                siteId = siteId,
                phase = Some(snap.phase),
                currentFloor = Some(f"${snap.currentFloor}%.4f"),
                bestFloor = snap.bestFloor.map(b => f"${b}%.4f"),
                cursor = Some(snap.cursor),
                ticksThisCandidate = Some(snap.ticksThisCandidate),
                exploitTicksRemaining = Some(snap.exploitTicksRemaining),
                candidates = rows,
                previousBestFloor = snap.previousBestFloor.map(b => f"${b}%.4f"),
                previousCandidates = prevRows
              ))
          }
        }
        .recover { case ex => Left(ErrorResponse("get_sweep_evidence_failed", ex.getMessage)) }
  }

  private val updateSlotFloorOverrideLogic
      : ((String, String, String, UpdateSlotFloorRequest)) => Future[Either[ErrorResponse, SiteSlotConfig]] = {
    case (_publisherId, siteId, slotId, req) =>
      // Parse / validate floor. Empty string and `null` both mean clear.
      val parsed: Either[ErrorResponse, Option[promovolve.CPM]] = req.floorCpm match {
        case None                      => Right(None)
        case Some(s) if s.trim.isEmpty => Right(None)
        case Some(s)                   =>
          scala.util.Try(BigDecimal(s).toDouble) match {
            case scala.util.Success(v) if v > 0 => Right(Some(promovolve.CPM(v)))
            case scala.util.Success(_)          => Left(ErrorResponse("invalid_floor", "floorCpm must be positive"))
            case scala.util.Failure(_)          => Left(ErrorResponse("invalid_floor", s"floorCpm '$s' is not a number"))
          }
      }
      parsed match {
        case Left(err)    => Future.successful(Left(err))
        case Right(floor) =>
          siteRef(siteId)
            .ask[SiteEntity.SlotFloorOverrideUpdated](SiteEntity.UpdateSlotFloorOverride(slotId, floor, _))
            .flatMap { _ =>
              // Re-read slot config so the response reflects current state.
              siteRef(siteId)
                .ask[SiteEntity.SlotsResult](SiteEntity.GetSlots(_))
                .map(_.slots)
                .map { slots =>
                  slots.find(_.slotId == slotId) match {
                    case Some(s) =>
                      Right(SiteSlotConfig(
                        slotId = s.slotId,
                        width = s.width,
                        height = s.height,
                        floorOverride = s.floorOverride.map(_.toDouble.toString),
                        priorQualityScore = s.prior.map(_.qualityScore),
                        priorRegion = s.prior.map(_.region),
                        priorAboveFold = s.prior.map(_.aboveFold)
                      ))
                    case None =>
                      Left(ErrorResponse("slot_not_found", s"Slot $slotId not found on site $siteId"))
                  }
                }
            }
            .recover { case ex => Left(ErrorResponse("update_slot_floor_failed", ex.getMessage)) }
      }
  }

  // ----------------- Routes -----------------
  // ----------------- Internal (platform-only) endpoints -----------------
  // Billing settlement support (docs/design/BILLING.md). When
  // INTERNAL_API_KEY is set, callers must present it as X-Internal-Key;
  // when unset (dev, network-isolated deployments) the endpoints are open,
  // matching the trust model of the rest of the core API (the Go platform
  // is the auth boundary).

  private val internalApiKey: Option[String] = {
    val key = sys.env.get("INTERNAL_API_KEY").filter(_.nonEmpty)
    if (key.isEmpty)
      system.log.warn(
        "INTERNAL_API_KEY is not set — /v1/internal billing endpoints are UNGATED (network isolation only)"
      )
    key
  }

  private def requireInternalKey(presented: Option[String]): Either[ErrorResponse, Unit] =
    internalApiKey match {
      case Some(expected) =>
        // Constant-time comparison; the endpoint is reachable by anything
        // on the cluster network.
        val ok = presented.exists(p =>
          java.security.MessageDigest.isEqual(
            p.getBytes("UTF-8"),
            expected.getBytes("UTF-8")
          )
        )
        if (ok) Right(()) else Left(ErrorResponse("forbidden", "missing or invalid X-Internal-Key"))
      case None => Right(())
    }

  private val getMeteringDailyLogic
      : ((String, Option[Boolean], Option[String])) => Future[Either[ErrorResponse, MeteringDailyResponse]] = {
    case (dateStr, allowPartial, key) =>
      requireInternalKey(key) match {
        case Left(err) => Future.successful(Left(err))
        case Right(()) =>
          scala.util.Try(java.time.LocalDate.parse(dateStr)).toOption match {
            case None =>
              Future.successful(Left(ErrorResponse("bad_date", s"date must be YYYY-MM-DD, got '$dateStr'")))
            case Some(day)
                if !day.isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC)) && !allowPartial.contains(true) =>
              // Settling a partial day locks its short numbers in forever
              // (the settlement keys already exist) — refuse, even if the
              // caller's clock is wrong. The internal-key-gated allowPartial
              // opt-in bypasses this for operator/test settles (settle-day
              // CLI) where the caller accepts partial-day numbers.
              Future.successful(Left(ErrorResponse("day_not_final", s"$dateStr is not a completed UTC day")))
            case Some(day) =>
              dashboardDb match {
                case None =>
                  // Zero rows here would read as "no spend" and the day
                  // would be marked settled — a misconfigured pod must be
                  // an error the settlement job retries, never a silent $0.
                  Future.successful(Left(ErrorResponse("metering_unavailable",
                    "dashboard DB not configured on this pod")))
                case Some(db) =>
                  import slick.jdbc.PostgresProfile.api.*
                  import slick.jdbc.GetResult
                  given GetResult[(String, String, String, Option[String], Long, Double)] =
                    GetResult(using r =>
                      (r.nextString(), r.nextString(), r.nextString(), r.nextStringOption(), r.nextLong(),
                        r.nextDouble())
                    )
                  val dayStr = day.toString
                  // Half-open UTC-day range keeps the predicate sargable
                  // against the hypertable's event_time partitioning.
                  // Dog-eared impressions never debit campaign budget
                  // (LearningEventLog skips RecordSpend for them), so they
                  // are excluded from billing even though the display
                  // endpoints above count them.
                  val q = sql"""
                    SELECT te.advertiser_id, te.campaign_id, te.site_id, ps.publisher_id,
                           COUNT(*)::bigint,
                           COALESCE(SUM(te.cpm) / 1000.0, 0.0)::double precision
                    FROM tracking_events te
                    LEFT JOIN publisher_sites ps ON ps.site_id = te.site_id
                    WHERE te.event_type = 'impression'
                      AND NOT te.dogeared
                      AND te.advertiser_id IS NOT NULL
                      AND te.campaign_id IS NOT NULL
                      AND te.event_time >= ($dayStr::date)::timestamp AT TIME ZONE 'UTC'
                      AND te.event_time <  ($dayStr::date + INTERVAL '1 day') AT TIME ZONE 'UTC'
                    GROUP BY te.advertiser_id, te.campaign_id, te.site_id, ps.publisher_id
                    ORDER BY te.advertiser_id, te.campaign_id, te.site_id
                  """.as[(String, String, String, Option[String], Long, Double)]
                  db.run(q).map { rows =>
                    Right(MeteringDailyResponse(
                      date = dayStr,
                      rows = rows.toVector.map { case (adv, camp, site, pub, imps, gross) =>
                        MeteringDailyRow(adv, camp, site, pub.getOrElse(""), imps, f"$gross%.6f")
                      }
                    ))
                  }.recover { case ex =>
                    Left(ErrorResponse("metering_daily_failed", ex.getMessage))
                  }
              }
          }
      }
  }

  private val getMeteringIntradayLogic
      : ((String, Option[String])) => Future[Either[ErrorResponse, MeteringIntradayResponse]] = {
    case (sinceStr, key) =>
      requireInternalKey(key) match {
        case Left(err) => Future.successful(Left(err))
        case Right(()) =>
          scala.util.Try(java.time.LocalDate.parse(sinceStr)).toOption match {
            case None =>
              Future.successful(Left(ErrorResponse("bad_date", s"since must be YYYY-MM-DD, got '$sinceStr'")))
            case Some(since) =>
              dashboardDb match {
                case None =>
                  Future.successful(Left(ErrorResponse("metering_unavailable",
                    "dashboard DB not configured on this pod")))
                case Some(db) =>
                  import slick.jdbc.PostgresProfile.api.*
                  import slick.jdbc.GetResult
                  given GetResult[(String, Double)] =
                    GetResult(using r => (r.nextString(), r.nextDouble()))
                  val sinceDay = since.toString
                  // Same billing predicate as /metering/daily, open-ended to
                  // now: this is the not-yet-settled tail of spend.
                  val q = sql"""
                    SELECT te.advertiser_id,
                           COALESCE(SUM(te.cpm) / 1000.0, 0.0)::double precision
                    FROM tracking_events te
                    WHERE te.event_type = 'impression'
                      AND NOT te.dogeared
                      AND te.advertiser_id IS NOT NULL
                      AND te.campaign_id IS NOT NULL
                      AND te.event_time >= ($sinceDay::date)::timestamp AT TIME ZONE 'UTC'
                    GROUP BY te.advertiser_id
                  """.as[(String, Double)]
                  db.run(q).map { rows =>
                    Right(MeteringIntradayResponse(
                      since = sinceDay,
                      rows = rows.toVector.map { case (adv, gross) =>
                        MeteringIntradayRow(adv, f"$gross%.6f")
                      }
                    ))
                  }.recover { case ex =>
                    Left(ErrorResponse("metering_intraday_failed", ex.getMessage))
                  }
              }
          }
      }
  }

  // A never-provisioned advertiser entity recovers as empty state (status
  // Active, no name, no campaigns) — asking it UpdateStatus would persist a
  // phantom entity and return success for a typo'd id. Both internal
  // endpoints therefore resolve the entity first and 404 unprovisioned ids.
  private def provisionedAdvertiser(
      advertiserId: String): Future[Either[ErrorResponse, AdvertiserEntity.AdvertiserInfo]] =
    advertiserRef(advertiserId)
      .ask[AdvertiserEntity.AdvertiserInfo](AdvertiserEntity.GetAdvertiserInfo(_))
      .map { info =>
        // State.empty carries Name.empty (the "Undefined" sentinel), not an
        // empty string — compare against the sentinel itself.
        if (info.name == Name.empty && info.campaignIds.isEmpty)
          Left(ErrorResponse("advertiser_not_found", s"no advertiser '$advertiserId'"))
        else Right(info)
      }

  private val suspendAdvertiserLogic: ((String, Option[String])) => Future[Either[ErrorResponse, Unit]] = {
    case (advertiserId, key) =>
      requireInternalKey(key) match {
        case Left(err) => Future.successful(Left(err))
        case Right(()) =>
          provisionedAdvertiser(advertiserId)
            .flatMap {
              case Left(err)                                                    => Future.successful(Left(err))
              case Right(info) if info.status == AdvertiserEntity.Status.Closed =>
                // Closed is an operator decision and already never serves
                // (GetBidContext + budget-status gates) — don't overwrite
                // it with a billing-level Suspended.
                Future.successful(Right(()))
              case Right(_) =>
                advertiserRef(advertiserId)
                  .ask[AdvertiserEntity.StatusUpdated](
                    AdvertiserEntity.UpdateStatus(AdvertiserEntity.Status.Suspended, _)
                  )
                  .map(_ => Right(()))
            }
            .recover { case ex => Left(ErrorResponse("suspend_failed", ex.getMessage)) }
      }
  }

  private val resumeAdvertiserLogic: ((String, Option[String])) => Future[Either[ErrorResponse, Unit]] = {
    case (advertiserId, key) =>
      requireInternalKey(key) match {
        case Left(err) => Future.successful(Left(err))
        case Right(()) =>
          provisionedAdvertiser(advertiserId)
            .flatMap {
              case Left(err)                                                    => Future.successful(Left(err))
              case Right(info) if info.status == AdvertiserEntity.Status.Closed =>
                // A wallet top-up must never resurrect an operator-closed
                // advertiser. The platform treats this as success (its own
                // billing status may go active) but core stays Closed.
                Future.successful {
                  system.log.warn(
                    "billing resume ignored for CLOSED advertiser {}", advertiserId
                  )
                  Right(())
                }
              case Right(info) if info.status == AdvertiserEntity.Status.Active =>
                Future.successful(Right(())) // idempotent no-op
              case Right(_) =>
                advertiserRef(advertiserId)
                  .ask[AdvertiserEntity.StatusUpdated](
                    AdvertiserEntity.UpdateStatus(AdvertiserEntity.Status.Active, _)
                  )
                  .map(_ => Right(()))
            }
            .recover { case ex => Left(ErrorResponse("resume_failed", ex.getMessage)) }
      }
  }

  private val internalRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.getMeteringDaily.serverLogic(getMeteringDailyLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getMeteringIntraday.serverLogic(getMeteringIntradayLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.suspendAdvertiser.serverLogic(suspendAdvertiserLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.resumeAdvertiser.serverLogic(resumeAdvertiserLogic))
  )

  private val pacingRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getSitePacingConfig.serverLogic(gateSite2(getSitePacingConfigLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.updateSitePacingConfig.serverLogic(gateSite3(updateSitePacingConfigLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.resetPacing.serverLogic(gateSite3(resetPacingLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.updateSlotFloorOverride.serverLogic(gateSite4(updateSlotFloorOverrideLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getFloorObservations.serverLogic(gateSite3(getFloorObservationsLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getFloorSweepEvidence.serverLogic(gateSite2(getFloorSweepEvidenceLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getFloorSweepHistory.serverLogic(gateSite4(getFloorSweepHistoryLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getCategoryDemand.serverLogic(gateSite2(getCategoryDemandLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getSiteRevenueToday.serverLogic(gateSite2(getSiteRevenueTodayLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getAdvertiserSpendToday.serverLogic(getAdvertiserSpendTodayLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getAdvertiserWinRates.serverLogic(getAdvertiserWinRatesLogic)),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getAdvertiserCampaignSpendToday.serverLogic(getAdvertiserCampaignSpendTodayLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getAdvertiserHourlyToday.serverLogic(getAdvertiserHourlyTodayLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getAdvertiserReport.serverLogic(getAdvertiserReportLogic)),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getAdvertiserReportBreakdown.serverLogic(getAdvertiserReportBreakdownLogic)),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getPublisherSiteCategoryReport.serverLogic(getPublisherSiteCategoryReportLogic)),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getAdvertiserReportBreakdownDaily.serverLogic(getAdvertiserReportBreakdownDailyLogic)),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getAdvertiserReportBreakdownByCampaign.serverLogic(getAdvertiserReportBreakdownByCampaignLogic)),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getPublisherSiteCategoryReportDaily.serverLogic(getPublisherSiteCategoryReportDailyLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.resetFloorAgent.serverLogic(gateSite2(resetFloorAgentLogic)))
  )
  private val taxonomyRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.listTaxonomyCategories.serverLogic(listTaxonomyCategoriesLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.listRegisteredSites.serverLogic(listRegisteredSitesLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.listAdvertiserDomains.serverLogic(listAdvertiserDomainsLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.listAdProductCategories.serverLogic(listAdProductCategoriesLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getTaxonomyStats.serverLogic(gateSite3(getTaxonomyStatsLogic)))
  )
  private val approvalQueueRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.listPendingCreatives.serverLogic(gateSite4(listPendingCreativesLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.listServingCreatives.serverLogic(gateSite3(listServingCreativesLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.approveCreative.serverLogic(gateSite3(approveCreativeLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.rejectCreative.serverLogic(gateSite3(rejectCreativeLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.flagCreative.serverLogic(gateSite3(flagCreativeLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.unflagCreative.serverLogic(gateSite3(unflagCreativeLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.revokeCreative.serverLogic(gateSite3(revokeCreativeLogic))),
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.listFlaggedCreatives.serverLogic(gateSite4(listFlaggedCreativesLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.bulkApprove.serverLogic(gateSite3(bulkApproveLogic)))
  )
  private val newSiteRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.classifyPage.serverLogic(gateSite3(classifyPageLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getSiteStats.serverLogic(gateSite2(getSiteStatsLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getAdServerStats.serverLogic(gateSite2(getAdServerStatsLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.passivateAdServer.serverLogic(gateSite2(passivateAdServerLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getServeIndex.serverLogic(gateSite3(getServeIndexLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.getServeIndexKeys.serverLogic(gateSite2(getServeIndexKeysLogic)))
  )
  private val campaignStatusRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.updateCampaignStatus.serverLogic(updateCampaignStatusLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.updateCampaignAdProduct.serverLogic(updateCampaignAdProductLogic))
  )
  private val adProductBlocklistRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(
      Endpoints.getAdProductBlocklist.serverLogic(gateSite2(getAdProductBlocklistLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.blockAdProducts.serverLogic(gateSite3(blockAdProductsLogic))),
    PekkoHttpServerInterpreter().toRoute(Endpoints.unblockAdProducts.serverLogic(gateSite3(unblockAdProductsLogic)))
  )
  private val creativeRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.listCreatives.serverLogic(listCreativesLogic)),
    PekkoHttpServerInterpreter().toRoute(Endpoints.createCreative.serverLogic(createCreativeLogic))
  )
  private val auctionRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.registerCampaigns.serverLogic(registerCampaignsLogic))
  )

  // ----------------- Category Verification -----------------
  private val verifyCategoryLogic: VerifyCategoryRequest => Future[Either[ErrorResponse, VerifyCategoryResponse]] =
    request => {
      categoryVerificationClient match {
        case Some(client) =>
          val imageBytes = java.util.Base64.getDecoder.decode(request.imageBase64)
          client.verify(imageBytes, request.mimeType, request.declaredCategory)
            .map { result =>
              Right(VerifyCategoryResponse(
                matchConfidence = result.matchConfidence,
                reason = result.reason
              ))
            }
            .recover { case ex =>
              Left(ErrorResponse("VERIFICATION_ERROR", s"Verification failed: ${ex.getMessage}"))
            }
        case None =>
          Future.successful(Left(ErrorResponse("NOT_CONFIGURED",
            "Category verification not configured (GEMINI_API_KEY not set)")))
      }
    }

  private val assessmentRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.verifyCategory.serverLogic(verifyCategoryLogic))
  )

  // ----------------- Creative Status Update -----------------
  private val updateCreativeStatusLogic: ((String, String, String, UpdateCreativeStatusRequest)) => Future[Either[
    ErrorResponse, UpdateCreativeStatusResponse]] = {
    case (advertiserId, campaignId, creativeId, request) =>
      creativeRepo match {
        case Some(crRepo) =>
          crRepo.get(creativeId).flatMap {
            case Some(creative) =>
              // Validate that the creative belongs to this advertiser/campaign
              if (creative.advertiserId != advertiserId || creative.campaignId != campaignId) {
                Future.successful(Left(ErrorResponse("NOT_FOUND",
                  s"Creative $creativeId not found for this advertiser/campaign")))
              } else {
                // Parse and validate the requested status
                val newStatus = request.status.toLowerCase match {
                  case "active" => Some(publisher.CreativeStatus.Active)
                  case "paused" => Some(publisher.CreativeStatus.Paused)
                  case _        => None
                }

                newStatus match {
                  case Some(status) =>
                    val previousStatus = creative.status.toString
                    val isActive = status == publisher.CreativeStatus.Active

                    // Update the status in the database
                    crRepo.updateStatus(creativeId, status)

                    // CreativeStatusChanged event is published by AdvertiserEntity to budget topic,
                    // so all AdServers handle removal locally using their inverted index.

                    // Update AdvertiserEntity.Creative so auction eligibility check works
                    // The entity now publishes CreativeStatusChanged event internally
                    val advertiserUpdate =
                      advertiserRef(advertiserId).ask[AdvertiserEntity.CreativeActiveStatusUpdated](ref =>
                        AdvertiserEntity.UpdateCreativeActiveStatus(CreativeId(creativeId), CampaignId(campaignId),
                          isActive, ref)
                      )

                    // Wait for AdvertiserEntity update to complete before returning
                    advertiserUpdate.map { _ =>
                      // Publish SSE event for real-time UI updates
                      pendingEventHub.foreach(_ ! PendingEventHub.PublishCreativeStatusChanged(
                        creativeId, campaignId, status.toString
                      ))
                      Right(UpdateCreativeStatusResponse(
                        creativeId = creativeId,
                        status = status.toString,
                        previousStatus = previousStatus
                      ))
                    }.recover { case ex =>
                      system.log.warn("Failed to update AdvertiserEntity creative status: {}", ex.getMessage)
                      // Still return success since DB was updated and event was published
                      // Also publish SSE event since the DB update succeeded
                      pendingEventHub.foreach(_ ! PendingEventHub.PublishCreativeStatusChanged(
                        creativeId, campaignId, status.toString
                      ))
                      Right(UpdateCreativeStatusResponse(
                        creativeId = creativeId,
                        status = status.toString,
                        previousStatus = previousStatus
                      ))
                    }

                  case None =>
                    Future.successful(Left(ErrorResponse("INVALID_STATUS",
                      s"Invalid status: ${request.status}. Must be 'Active' or 'Paused'")))
                }
              }

            case None =>
              Future.successful(Left(ErrorResponse("NOT_FOUND", s"Creative $creativeId not found")))
          }

        case None =>
          Future.successful(Left(ErrorResponse("NOT_CONFIGURED", "CreativeRepo not configured")))
      }
  }

  /**
   * Serves images from ImageStorage for local development.
   *
   * In production, assets are served from CDN (Cloudflare R2 public bucket).
   */
  private val assetServingRoute: Route = {
    import org.apache.pekko.http.scaladsl.server.Directives.*
    import org.apache.pekko.http.scaladsl.model.*

    pathPrefix("v1" / "assets") {
      get {
        path(Segment) { filename =>
          // Extract hash from filename (e.g., "abc123.png" -> "abc123")
          val hash = filename.split('.').head
          imageStorage match {
            case Some(storage) =>
              onComplete(storage.fetch(hash)) {
                case scala.util.Success(Some(bytes)) =>
                  val contentType = filename.split('.').lastOption match {
                    case Some("png")                => ContentType(MediaTypes.`image/png`)
                    case Some("jpg") | Some("jpeg") => ContentType(MediaTypes.`image/jpeg`)
                    case Some("gif")                => ContentType(MediaTypes.`image/gif`)
                    case Some("webp")               => ContentType(MediaTypes.`image/webp`)
                    case _                          => ContentTypes.`application/octet-stream`
                  }
                  complete(HttpEntity(contentType, bytes))
                case scala.util.Success(None) =>
                  complete(StatusCodes.NotFound -> "Asset not found")
                case scala.util.Failure(ex) =>
                  complete(StatusCodes.InternalServerError -> s"Storage error: ${ex.getMessage}")
              }
            case None =>
              complete(StatusCodes.ServiceUnavailable -> "Image storage not configured")
          }
        }
      }
    }
  }

  private val imageRoutes: List[Route] = List(
    PekkoHttpServerInterpreter().toRoute(Endpoints.updateCreativeStatus.serverLogic(updateCreativeStatusLogic)),
    assetServingRoute
  )
  private val swaggerRoutes: List[Route] =
    SwaggerInterpreter()
      .fromEndpoints[Future](Endpoints.all, "Promovolve API", "1.0.0")
      .map(e => PekkoHttpServerInterpreter().toRoute(e))
  private val openApiRoute: Route = {
    import org.apache.pekko.http.scaladsl.server.Directives.*
    path("openapi.yaml") {
      get {
        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, Endpoints.openApiYaml))
      }
    }
  }

  private given Timeout = Timeout(30.seconds)

  private given ExecutionContext = system.executionContext

  private def advertiserRef(id: String) =
    sharding.entityRefFor(AdvertiserEntity.TypeKey, id)

  private def campaignRef(advertiserId: String, campaignId: String) =
    sharding.entityRefFor(CampaignEntity.TypeKey, s"$advertiserId|$campaignId")

  /**
   * Ownership gate for campaign-scoped object access (IDOR guard).
   *
   * The `advertiserId` path segment is the authenticated advertiser — the
   * BFF injects it from the session and the core proxy pins it — so a
   * campaignId is only "owned" when this advertiser's entity actually lists
   * it. This is the same source of truth the campaign-list query uses, so
   * direct-object routes (creatives, budget, win-rate, delete, …) can no
   * longer be used to reach another advertiser's campaign. Resolves to a
   * 404-style error on mismatch so we never disclose the existence of
   * another advertiser's ids.
   */
  private def requireOwnedCampaign(advertiserId: String, campaignId: String): Future[Either[ErrorResponse, Unit]] =
    advertiserRef(advertiserId)
      .ask[AdvertiserEntity.AdvertiserInfo](AdvertiserEntity.GetAdvertiserInfo(_))
      .map { info =>
        if (info.campaignIds.contains(CampaignId(campaignId))) Right(())
        else Left(ErrorResponse("campaign_not_found", s"Campaign $campaignId not found"))
      }
      .recover { case _ => Left(ErrorResponse("campaign_not_found", s"Campaign $campaignId not found")) }

  /**
   * Ownership gate for site-scoped object access (IDOR guard) — the
   * publisher analogue of `requireOwnedCampaign`.
   *
   * Site handlers key on `siteRef(siteId)`/`adServerRef(siteId)` (siteId
   * alone), so the `{publisherId}` path segment — the authenticated
   * publisher, pinned by the BFF — was previously ignored, letting any
   * caller read/mutate another publisher's site. A site is "owned" only
   * when its persisted `SiteConfig.publisherId` matches; same source of
   * truth `createSiteLogic`'s takeover check uses. 404-style error on
   * mismatch so we never disclose another publisher's site ids.
   */
  private def requireOwnedSite(publisherId: String, siteId: String): Future[Either[ErrorResponse, Unit]] =
    siteRef(siteId)
      .ask[SiteEntity.ConfigResult](SiteEntity.GetConfig(_))
      .map {
        case SiteEntity.ConfigResult(Some(cfg)) if cfg.publisherId == PublisherId(publisherId) => Right(())
        case _                                                                                 => Left(ErrorResponse("site_not_found", s"Site $siteId not found"))
      }
      .recover { case _ => Left(ErrorResponse("site_not_found", s"Site $siteId not found")) }

  /**
   * Run `body` only if the site is owned by `publisherId`, else short-circuit
   * with the not-found error. `body` is BY-NAME on purpose: a handler's eager
   * `val xF = …Future…` starts running the instant it's defined, so building
   * the mutating Futures must be deferred to the owned branch (the exact trap
   * the June IDOR fix hit in deleteCreativeRoute).
   */
  private def withOwnedSite[T](publisherId: String, siteId: String)(
      body: => Future[Either[ErrorResponse, T]]): Future[Either[ErrorResponse, T]] =
    requireOwnedSite(publisherId, siteId).flatMap {
      case Left(e)  => Future.successful(Left(e))
      case Right(_) => body
    }

  // serverLogic gates: wrap a site-scoped logic so the ownership check runs
  // before it. Every site endpoint's input tuple is (publisherId, siteId, …);
  // `f(in)` is passed BY-NAME into withOwnedSite, so a handler's eager
  // `val xF = …Future…` only fires in the owned branch. Applied at the
  // registration site (serverLogic(gateSiteN(fooLogic))) so handler bodies
  // stay untouched.
  private def gateSite2[O](f: ((String, String)) => Future[Either[ErrorResponse, O]])
      : ((String, String)) => Future[Either[ErrorResponse, O]] =
    in => withOwnedSite(in._1, in._2)(f(in))
  private def gateSite3[B, O](f: ((String, String, B)) => Future[Either[ErrorResponse, O]])
      : ((String, String, B)) => Future[Either[ErrorResponse, O]] =
    in => withOwnedSite(in._1, in._2)(f(in))
  private def gateSite4[B, C, O](f: ((String, String, B, C)) => Future[Either[ErrorResponse, O]])
      : ((String, String, B, C)) => Future[Either[ErrorResponse, O]] =
    in => withOwnedSite(in._1, in._2)(f(in))

  private def adServerRef(siteId: String) =
    sharding.entityRefFor(AdServer.TypeKey, siteId)

  private def siteRef(siteId: String) =
    sharding.entityRefFor(SiteEntity.TypeKey, siteId)

  private def publisherRef(publisherId: String) =
    sharding.entityRefFor(PublisherEntity.TypeKey, publisherId)

  private def auctioneerRef(siteId: String) =
    sharding.entityRefFor(AuctioneerEntity.TypeKey, siteId)

  private def categoryBidderRef(categoryId: String) =
    sharding.entityRefFor(CategoryBidderEntity.TypeKey, categoryId)

  private def serveIndexKey(siteId: String, slotId: String): String =
    s"$siteId|$slotId"

  private def formatMoney(value: BigDecimal): String =
    f"${value}%.4f"

  private def nowIso: String =
    java.time.Instant.now().toString

  private def siteConfigToSlots(
      config: SiteEntity.SiteConfig,
      slotCategories: Map[String, String]
  ): Vector[SiteSlotConfig] =
    config.slots.map { s =>
      SiteSlotConfig(
        slotId = s.slotId,
        width = s.width,
        height = s.height,
        floorOverride = s.floorOverride.map(_.toDouble.toString),
        priorQualityScore = s.prior.map(_.qualityScore),
        priorRegion = s.prior.map(_.region),
        priorAboveFold = s.prior.map(_.aboveFold),
        matchedCategory = slotCategories.get(s.slotId)
      )
    }.toVector

  /**
   * Build a slotId → human-readable category-name map from a site's
   * per-page classifications. Each slot is attributed the top (highest
   * confidence) IAB content category of the page(s) it was discovered on;
   * a slot seen only on filler pages (no demand category) maps to "Filler".
   * Slots on not-yet-classified pages are simply absent from the map.
   */
  private def slotCategoryMap(
      classifications: Map[String, SiteEntity.ClassificationEntry]
  ): Map[String, String] = {
    val FillerKey = "__filler__"
    val best = scala.collection.mutable.Map.empty[String, (String, Double)]
    classifications.valuesIterator.foreach { entry =>
      val candidate: (String, Double) =
        entry.categories.maxByOption(_._2) match {
          case Some((catId, score)) => (catId, score)
          case None                 => (FillerKey, Double.NegativeInfinity)
        }
      entry.slots.foreach { ps =>
        best.get(ps.slotId) match {
          case Some((_, curScore)) if curScore >= candidate._2 => ()
          case _                                               => best.update(ps.slotId, candidate)
        }
      }
    }
    best.view.mapValues {
      case (FillerKey, _) => "Filler"
      case (catId, _)     =>
        promovolve.taxonomy.TieredCategory.get(catId).map(_.name).getOrElse(catId)
    }.toMap
  }

  private def buildSiteResponse(
      siteId: String, publisherId: String, config: SiteEntity.SiteConfig,
      slotCategories: Map[String, String] = Map.empty,
      floorCpm: Option[String] = None, minFloorCpm: Option[String],
      bidWeight: Option[String] = None,
      acceptsFillerTraffic: Option[Boolean] = None,
      status: String = "active", verificationStatus: String = "unverified"
  ): Site =
    Site(
      id = siteId,
      publisherId = publisherId,
      domain = config.domain,
      status = status,
      crawlConfig = SiteCrawlConfig(
        seedUrl = config.seedUrl,
        cronSchedule = config.cronSchedule,
        maxDepth = config.maxDepth,
        concurrency = config.concurrency,
        hostRegex = config.hostRegex,
        targetElements = config.targetElements.toVector
      ),
      slots = siteConfigToSlots(config, slotCategories),
      taxonomyIds = config.taxonomyIds.toVector,
      createdAt = nowIso,
      updatedAt = nowIso,
      verificationStatus = verificationStatus,
      floorCpm = floorCpm.getOrElse("0.50"),
      minFloorCpm = minFloorCpm.getOrElse("0.10"),
      bidWeight = bidWeight.getOrElse("0.50"),
      acceptsFillerTraffic = acceptsFillerTraffic.getOrElse(true)
    )

}
