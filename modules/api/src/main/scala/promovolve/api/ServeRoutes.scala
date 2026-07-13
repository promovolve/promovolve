package promovolve.api

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import promovolve.SiteId
import promovolve.common.{ FoldToken, PublisherSecretsRepo, Signer }
import promovolve.publisher.delivery.AdServer
import promovolve.publisher.PublisherSettings
import spray.json.*

import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }

final case class ServeRes(
    assetUrl: String,
    mime: String,
    clickUrl: String,
    impUrl: String,
    ctaUrl: String,
    creativeId: String,
    version: Long,
    landingUrl: String,
    pagesJson: Option[String] = None, // Magazine creative pages JSON for the expandable-magazine-banner
    bannerScriptUrl: Option[String] = None, // Web component URL the bootstrap loads to render the banner
    bannerConfigJson: Option[String] = None, // Banner-level config (animation, duration, etc.) — passed as `config` attr to the banner element
    // Dog-ear fields. Folds are free engagement signals (not billed),
    // so every winner that comes back with a fold token can be folded
    // and pinned. canFold mirrors `foldToken.isDefined`; honorPin is
    // always true for served winners.
    canFold: Boolean = false, // Reader can fold this slot (a fold token was minted)
    honorPin: Boolean = false, // Pinned creative will be honored if a hint is sent
    foldToken: Option[String] = None, // Server-issued fold token, only set when canFold=true
    dogear: Option[DogearInfo] = None, // Pin-honoring outcome; only set when the request carried a pin hint for this slot
    pinExpiresAt: Option[Long] = None // Campaign endAt as epoch millis — bootstrap caps the pin's expiresAt at this value
)

/**
 * Outcome of attempting to honor a pin hint sent by the client. Reasons:
 *   creative_removed | campaign_inactive | budget_exhausted | dogear_disabled
 */
final case class DogearInfo(honored: Boolean, reason: Option[String] = None)

/**
 * Pin hint from the bootstrap. Tells the server "this slot is pinned to
 * creativeId in the reader's IndexedDB; honor it if possible." Server may
 * fall through to a normal auction if the creative is no longer servable.
 */
final case class PinHint(slotId: String, creativeId: String)

/**
 * One impression opportunity in a batch serve request. Shape mirrors
 * the `imp` entry of an OpenRTB BidRequest, pared down to the fields
 * we actually act on today. `id` is the publisher's slot identifier,
 * echoed back in the response so clients can match winners to their
 * DOM placements.
 */
final case class BatchImp(
    id: String, // slot id (echoed in response)
    w: Int,
    h: Int,
    floorCpm: Option[Double] = None // per-slot floor override
)

/**
 * Batch serve request — the shape accepted by POST /v1/serve/batch.
 * One call per page load covering every slot the bootstrap
 * discovered in the DOM. `domain` is the publisher's registered
 * domain for verification.
 */
final case class BatchServeReq(
    pub: String,
    url: String,
    domain: Option[String] = None,
    imp: Vector[BatchImp],
    pins: Option[Vector[PinHint]] = None // optional dog-ear pin hints, one per pinned slot
)

/**
 * One slot in a classify-page request — geometry plus the rendered-position
 * signals (extractSlots) the server folds into a SlotPrior for crawl-free
 * per-slot floor scaling. Signals default to neutral so an old bootstrap that
 * sends geometry only still parses.
 */
final case class ClassifyImp(
    id: String,
    w: Int,
    h: Int,
    aboveFold: Boolean = false,
    viewability: Double = 0.0,
    region: String = "unknown",
    textDensity: Double = 0.0
)

/**
 * On-demand classification request — posted by the ad tag (bootstrap) on a
 * cold serve miss. Carries the live-page text extracted in the browser (the
 * crawl-free text source) plus the slots seen on the page. See
 * docs/design/ON_DEMAND_CLASSIFICATION.md.
 */
final case class ClassifyPageTextReq(
    pub: String,
    url: String,
    text: String,
    section: Option[String] = None,
    imp: Option[Vector[ClassifyImp]] = None
)

/**
 * One winner in a batch serve response — slotId + the same
 * ServeRes payload a single-slot GET would return.
 */
final case class BatchImpResult(
    id: String, // slot id, matches BatchImp.id
    winner: Option[ServeRes], // None if this slot couldn't be filled
    // Dog-ear outcome — surfaced at slot level so it survives the
    // winner=None case. The bootstrap reads this whenever the request
    // carried a pin hint for the slot, regardless of whether a winner
    // was found, so it can clear stale IDB pins (reason="creative_removed")
    // even when no fallback creative filled the slot.
    dogear: Option[DogearInfo] = None
)

/**
 * Batch serve response — an ordered list of (slotId, winner?).
 * Array shape (rather than a Map) matches OpenRTB's `seatbid`
 * structure and keeps wire shape predictable for heterogeneous
 * JS clients.
 *
 * `stalePins`: creativeIds of OFF-PAGE pin hints the client should
 * delete from its IndexedDB store. The per-slot `dogear` field can
 * only reconcile pins whose slot is in THIS batch — a pin whose page
 * is never revisited (or whose slot was renamed away) would
 * otherwise ride along forever, excluding its campaign in that
 * browser with no path to recovery. A pin is reported stale when its
 * creative was looked up successfully and NOT found, or when its
 * slotId no longer exists in the site's slot config. Lookup FAILURES
 * (transient repo errors) are never reported — deleting a live
 * bookmark on a DB hiccup would be destructive.
 */
final case class BatchServeRes(
    seatbid: Vector[BatchImpResult],
    stalePins: Option[Vector[String]] = None,
    // Legacy derived view of reclassifyInMs (<= 0). Kept for the old bootstrap.
    needText: Boolean = false,
    // Freshness token: ms until this page's classification should be refreshed.
    // <= 0 → the ad tag (re)classifies (cold OR stale); > 0 → fresh, do nothing.
    // Preferred over needText. See docs/design/ON_DEMAND_CLASSIFICATION.md.
    reclassifyInMs: Long = Long.MaxValue
)

/**
 * Pure derivation of the `stalePins` payload — extracted from the
 * batch route so the safety-critical distinctions are unit-testable:
 * a transient lookup failure must NEVER be reported stale (it would
 * delete a live bookmark client-side), and the slot-existence pass
 * must apply only to off-page pins and only when the site has a
 * non-empty slot config.
 */
private[api] object StalePins {

  /**
   * @param pinLookups   (creativeId, outcome) for OFF-PAGE pins:
   *                     outer None = lookup FAILED (transient —
   *                     report nothing); Some(None) = looked up OK,
   *                     creative gone (stale); Some(Some(campaignId))
   *                     = alive.
   * @param pins         every pin hint on the request.
   * @param slotIdsOnPage slots present in this batch (their pins are
   *                     reconciled by the per-slot dogear channel).
   * @param siteSlotIds  the site's known slot config; empty = the
   *                     check is skipped (mid-crawl site, ask failed).
   */
  def derive(
      pinLookups: Vector[(String, Option[Option[String]])],
      pins: Vector[PinHint],
      slotIdsOnPage: Set[String],
      siteSlotIds: Set[String]
  ): Vector[String] = {
    // ONLY deleted creatives are reported stale. The slot-existence check
    // (an off-page pin whose slot is absent from the site config) was REMOVED:
    // under on-demand classification the slot inventory is built lazily from
    // real traffic, so a pin's slot may simply not be activated YET. Reporting
    // it stale would delete a live fold client-side — the "fold won't stay
    // where it was folded" regression. A deleted creative is the only reliably
    // safe stale signal. (slotIdsOnPage / siteSlotIds / pins are kept in the
    // signature for call-site + test stability.)
    val _ = (slotIdsOnPage, siteSlotIds, pins)
    pinLookups.collect { case (cid, Some(None)) => cid }.distinct
  }
}

trait ServeJson extends DefaultJsonProtocol {
  given RootJsonFormat[DogearInfo] = jsonFormat2(DogearInfo.apply)
  given RootJsonFormat[PinHint] = jsonFormat2(PinHint.apply)
  given RootJsonFormat[ServeRes] = jsonFormat16(ServeRes.apply)
  given RootJsonFormat[BatchImp] = jsonFormat4(BatchImp.apply)
  given RootJsonFormat[BatchServeReq] = jsonFormat5(BatchServeReq.apply)
  given RootJsonFormat[ClassifyImp] = jsonFormat7(ClassifyImp.apply)
  given RootJsonFormat[ClassifyPageTextReq] = jsonFormat5(ClassifyPageTextReq.apply)
  given RootJsonFormat[BatchImpResult] = jsonFormat3(BatchImpResult.apply)
  given RootJsonFormat[BatchServeRes] = jsonFormat4(BatchServeRes.apply)
}

/** Hot-path JSON responder: AdServer handles selection, freshness filtering, and DData. */
final class ServeRoutes(
    trackingBase: String,
    sharding: ClusterSharding,
    secretsRepo: PublisherSecretsRepo,
    publisherSettings: PublisherSettings, // Per-publisher classification freshness window (24h to 1 week)
    cdnBaseUrl: String,
    bannerScriptUrl: String, // URL of <expandable-magazine-banner> web component
    creativeRepo: Option[promovolve.publisher.CreativeRepo] = None
)(using system: ActorSystem[?])
    extends ServeJson {

  // NOTE: Approval routes moved to Endpoints.scala (OpenAPI documented)
  // See GPC.md for Global Privacy Control documentation
  val routes: Route =
    concat(
      pathPrefix("v1" / "serve") {
        // POST /v1/serve/batch — one request per page load, all slots.
        // Joint auction via AdServer.BatchSelect: candidates are scored
        // once against the shared pool, greedy-picked by slot area
        // descending, per-page-per-campaign cap enforced across the
        // batch.
        path("batch") {
          post {
            optionalHeaderValueByName("Sec-GPC") {
              case Some("1") => complete(StatusCodes.NoContent)
              case _         =>
                entity(as[BatchServeReq]) { req =>
                  val pinByslot: Map[String, String] =
                    req.pins.fold(Map.empty[String, String])(_.iterator.map(p => p.slotId -> p.creativeId).toMap)
                  // Site-wide pin block: pins for slots NOT present on this
                  // page mean the user folded that creative on a different
                  // page. Treat its creativeId as a hard exclusion across
                  // every slot of this batch — the user's own bookmark
                  // shouldn't be burned as a normal-auction impression on
                  // an unrelated page.
                  val slotIdsOnPage: Set[String] = req.imp.map(_.id).toSet
                  val offPagePinCreatives: Set[String] =
                    req.pins.fold(Set.empty[String])(
                      _.iterator
                        .collect { case p if !slotIdsOnPage.contains(p.slotId) => p.creativeId }
                        .toSet
                    )
                  val excludedCreatives: Set[promovolve.CreativeId] =
                    offPagePinCreatives.map(promovolve.CreativeId.apply)
                  val resultF = for {
                    freshnessWindowMs <- publisherSettings.classificationFreshnessWindowMs(SiteId(req.pub))
                    // Resolve pinned creativeIds → campaignIds via the
                    // creative repo so the batch can exclude the whole
                    // advertiser, not just the bookmarked frame. The
                    // dog-ear is a "save for later" gesture; surfacing
                    // other creatives from the same advertiser before
                    // the reader engages the bookmark would feel like a
                    // recommendation system stalking them. Falls back to
                    // creative-only if the repo isn't wired or a creative
                    // can't be found (treat as truly removed).
                    //
                    // Per-creative outcomes are kept (not flattened) so
                    // genuinely-missing creatives can be reported back as
                    // stalePins. The outer Option distinguishes "lookup
                    // FAILED" (None — exclude nothing extra, report
                    // nothing: a transient repo error must never delete a
                    // user's live bookmark) from "looked up OK" (Some,
                    // whose inner Option is found/not-found).
                    pinLookups <- creativeRepo match {
                      case Some(repo) if offPagePinCreatives.nonEmpty =>
                        Future.sequence(offPagePinCreatives.toVector.map(cid =>
                          repo.get(cid)
                            .map(found => cid -> Option(found.map(_.campaignId)))
                            .recover { case _ => cid -> Option.empty[Option[String]] }
                        ))
                      case _ => Future.successful(Vector.empty[(String, Option[Option[String]])])
                    }
                    excludedCampaigns = pinLookups.collect {
                      case (_, Some(Some(campaignId))) => promovolve.CampaignId(campaignId)
                    }.toSet
                    // Slot-existence pass: an off-page pin whose slotId no
                    // longer exists in the site's slot config can never be
                    // reconciled by the per-slot dogear channel (its page
                    // will never be in a batch) — renamed slots, deleted
                    // pages, redesigns. Guarded on a NON-EMPTY slot config
                    // so a freshly-resetting site mid-crawl can't mark
                    // live pins stale; ask failures degrade to "no check".
                    siteSlotIds <- {
                      val offPagePins =
                        req.pins.fold(Vector.empty[PinHint])(_.filterNot(p => slotIdsOnPage.contains(p.slotId)))
                      if (offPagePins.isEmpty) Future.successful(Set.empty[String])
                      else
                        sharding.entityRefFor(promovolve.publisher.SiteEntity.TypeKey, req.pub)
                          .ask[promovolve.publisher.SiteEntity.SlotsResult](promovolve.publisher.SiteEntity.GetSlots(_))
                          .map(_.slots.map(_.slotId).toSet)
                          .recover { case _ => Set.empty[String] }
                    }
                    stalePins = StalePins.derive(
                      pinLookups,
                      req.pins.getOrElse(Vector.empty),
                      slotIdsOnPage,
                      siteSlotIds
                    )
                    _ = system.log.info(
                      "BatchServe pub={} url={} pins.size={} offPagePinCreatives={} excludedCreatives={} excludedCampaigns={}",
                      req.pub, req.url,
                      req.pins.fold(0)(_.size),
                      offPagePinCreatives.mkString(","),
                      excludedCreatives.map(_.value).mkString(","),
                      excludedCampaigns.map(_.value).mkString(",")
                    )
                    adServer = sharding.entityRefFor(AdServer.TypeKey, req.pub)
                    batchResult <- adServer.ask[AdServer.BatchSelectResult] { replyTo =>
                      AdServer.BatchSelect(
                        url = promovolve.URL(req.url),
                        slots = req.imp.map { i =>
                          AdServer.BatchSlotSpec(
                            slotId = promovolve.SlotId(i.id),
                            width = i.w,
                            height = i.h,
                            floorCpm = i.floorCpm.map(promovolve.CPM.apply),
                            pin = pinByslot.get(i.id).map(promovolve.CreativeId.apply)
                          )
                        },
                        classificationFreshnessWindowMs = freshnessWindowMs,
                        replyTo = replyTo,
                        excludedCreatives = excludedCreatives,
                        excludedCampaigns = excludedCampaigns
                      )
                    }
                  } yield (batchResult, stalePins)
                  onSuccess(resultF) {
                    case (AdServer.BatchHostNotVerified, _) =>
                      complete(StatusCodes.Forbidden)
                    case (AdServer.BatchSiteSuspended, _) =>
                      // Operator-suspended org: quiet no-ads, never an error
                      // the page would surface to readers.
                      complete(StatusCodes.NoContent)
                    case (AdServer.BatchContentTooOld, _) =>
                      complete(StatusCodes.NoContent)
                    case (AdServer.BatchSelected(outcomes, _, reclassifyInMs, needText), stalePins) =>
                      // Build per-slot ServeRes for every winner in parallel
                      // (signed URLs are async). Unfilled slots return winner=None.
                      val resFutures: Vector[Future[BatchImpResult]] = outcomes.map { outcome =>
                        // Slot-level dogear info — surfaced regardless of
                        // whether there's a winner so the bootstrap can
                        // clear stale IDB pins on creative_removed even
                        // when no fallback creative filled the slot.
                        val slotDogear: Option[DogearInfo] =
                          outcome.dogear.map(o => DogearInfo(o.honored, o.reason))
                        outcome.winner match {
                          case None       => Future.successful(BatchImpResult(outcome.slotId.value, None, slotDogear))
                          case Some(cand) =>
                            val version = cand.classifiedAtMs
                            val apc = cand.adProductCategory.map(_.value)
                            val cpmDollars =
                              if (outcome.clearingPrice > promovolve.CPM.zero) outcome.clearingPrice.toDouble
                              else cand.cpm.toDouble
                            for {
                              click <- clickUrl(req.pub, req.url, outcome.slotId.value, cand.creativeId.value, version,
                                cand.campaignId.value, cand.advertiserId.value, cand.category.value, outcome.requestId,
                                apc, None)
                              imp <- impUrl(
                                req.pub, req.url, outcome.slotId.value, cand.creativeId.value, version,
                                cand.campaignId.value, cand.advertiserId.value, cpmDollars, cand.category.value,
                                outcome.requestId, apc, None
                              )
                              cta <- ctaUrl(req.pub, req.url, outcome.slotId.value, cand.creativeId.value, version,
                                cand.campaignId.value, cand.advertiserId.value, cand.category.value, outcome.requestId,
                                apc, None)
                              // Fetch the Creative once to pluck both pagesJson
                              // and bannerConfigJson together — one DB hit, two
                              // delivery fields.
                              creativeOpt <- creativeRepo.map(_.get(cand.creativeId.value))
                                .getOrElse(Future.successful(None))
                              // Mint a fold token for every winner — the dog-ear is
                              // part of the magazine creative format, not a per-campaign
                              // opt-in. Folds are free (engagement signal, not billed).
                              foldToken <-
                                foldTokenFor(req.pub, req.url, outcome.slotId.value, cand.creativeId.value, version,
                                  cand.campaignId.value, cand.advertiserId.value)
                              pinExpiresAt <- campaignPinExpiresAt(cand.advertiserId.value, cand.campaignId.value)
                            } yield {
                              val pagesJson = creativeOpt.flatMap(_.pagesJson)
                              val bannerConfigJson = creativeOpt.flatMap(_.bannerConfigJson)
                              (click, imp) match {
                                case (Some(c), Some(i)) =>
                                  BatchImpResult(
                                    id = outcome.slotId.value,
                                    winner = Some(ServeRes(
                                      s"$cdnBaseUrl/${cand.assetUrl.value}",
                                      cand.mime.value,
                                      c, i, cta.getOrElse(""),
                                      cand.creativeId.value,
                                      version,
                                      cand.landingUrl,
                                      pagesJson,
                                      if (pagesJson.isDefined) Some(bannerScriptUrl) else None,
                                      bannerConfigJson,
                                      canFold = foldToken.isDefined,
                                      honorPin = true,
                                      foldToken = foldToken,
                                      dogear = slotDogear,
                                      pinExpiresAt = pinExpiresAt
                                    )),
                                    dogear = slotDogear
                                  )
                                case _ =>
                                  // Can't sign URLs → fall through as unfilled.
                                  BatchImpResult(outcome.slotId.value, None, slotDogear)
                              }
                            }
                        }
                      }
                      onSuccess(Future.sequence(resFutures)) { results =>
                        complete(BatchServeRes(
                          results,
                          stalePins = if (stalePins.nonEmpty) Some(stalePins) else None,
                          needText = needText,
                          reclassifyInMs = reclassifyInMs
                        ))
                      }
                  }
                }
            }
          }
        }
      },
      classifyPageRoute
    )

  // POST /v1/classify-page — on-demand, crawl-free page classification.
  // Called by the ad tag (bootstrap) on a cold serve miss: it extracts the
  // live-page text/slots and posts them here. Fire-and-forget into SiteEntity
  // (single-flighted there); the response is a fast 202 Accepted and never
  // blocks on Gemini. See docs/design/ON_DEMAND_CLASSIFICATION.md.
  private def classifyPageRoute: Route =
    path("v1" / "classify-page") {
      post {
        entity(as[ClassifyPageTextReq]) { cReq =>
          val slots = cReq.imp.getOrElse(Vector.empty).map(i =>
            promovolve.publisher.SiteEntity.ClassifySlot(
              slotId = i.id,
              width = i.w,
              height = i.h,
              aboveFold = i.aboveFold,
              viewability = i.viewability,
              region = i.region,
              textDensity = i.textDensity
            )
          )
          val ackF = sharding
            .entityRefFor(promovolve.publisher.SiteEntity.TypeKey, cReq.pub)
            .ask[promovolve.publisher.SiteEntity.ClassifyAck] { replyTo =>
              promovolve.publisher.SiteEntity.ClassifyUrl(
                url = cReq.url,
                text = cReq.text,
                section = cReq.section,
                slots = slots,
                replyTo = replyTo
              )
            }
          onSuccess(ackF) { ack =>
            system.log.info(
              "ClassifyPage pub={} url={} accepted={} reason={}",
              cReq.pub, cReq.url, ack.accepted, ack.reason
            )
            complete(StatusCodes.Accepted)
          }
        }
      }
    }

  private val BucketMs = 60 * 1000L

  private given Timeout = Timeout(300.millis)

  private given ExecutionContext = system.executionContext

  private def clickUrl(pub: String, url: String, slot: String, cid: String, ver: Long,
      campaignId: String, advertiserId: String, category: String, requestId: String,
      adProductCategory: Option[String], pageCategories: Option[String]): Future[Option[String]] =
    signedUrl("click", pub, url, slot, cid, ver, Some(campaignId), Some(advertiserId), None, Some(category),
      Some(requestId), adProductCategory, pageCategories)

  private def ctaUrl(pub: String, url: String, slot: String, cid: String, ver: Long,
      campaignId: String, advertiserId: String, category: String, requestId: String,
      adProductCategory: Option[String], pageCategories: Option[String]): Future[Option[String]] =
    signedUrl("cta", pub, url, slot, cid, ver, Some(campaignId), Some(advertiserId), None, Some(category),
      Some(requestId), adProductCategory, pageCategories)

  private def signedUrl(
      evt: String,
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      campaignId: Option[String],
      advertiserId: Option[String],
      cpm: Option[Double],
      category: Option[String],
      requestId: Option[String],
      adProductCategory: Option[String],
      pageCategories: Option[String]
  ): Future[Option[String]] = {
    val b = nowBucket()
    secretsRepo.secretFor(pub).map {
      case Some(sec) =>
        // Bind campaign/advertiser/cpm/requestId into the HMAC so none of
        // them can be rewritten on the beacon after serve. cpm is signed as
        // its exact URL string (`p.toString`, matching `&cpm=$p` below).
        val data = Signer.canonical(pub, url, slot, cid, ver, b, evt) +
          Signer.bind(campaignId, advertiserId, cpm.map(_.toString), requestId)
        val tok = Signer.hmac256(data, sec)
        val encU = java.net.URLEncoder.encode(url, "UTF-8")

        // Build base URL with required params
        val baseUrl = s"$trackingBase/$evt?pub=$pub&url=$encU&slot=$slot&cid=$cid&v=$ver&b=$b&tok=$tok"

        // Add optional params for direct tracking
        val optionalParams = List(
          campaignId.map(c => s"&camp=$c"),
          advertiserId.map(a => s"&adv=$a"),
          cpm.map(p => s"&cpm=$p"),
          category.map(cat => s"&cat=$cat"),
          requestId.map(r => s"&rid=$r"),
          adProductCategory.map(apc => s"&apc=$apc"),
          pageCategories.map(cats => s"&pcats=$cats")
        ).flatten.mkString

        Some(baseUrl + optionalParams)

      case None =>
        system.log.warn("No HMAC secret for publisher [{}] — cannot generate tracking URL", pub)
        None
    }
  }

  private def nowBucket(): Long = System.currentTimeMillis() / BucketMs

  /**
   * Mint a fold token for a winning serve when the campaign opted into
   * dog-ear. Returns None if the publisher has no HMAC secret on file
   * (defensive — same fallthrough as signedUrl). The returned token rides
   * back to the client as `data-fold-token`; client redeems it via
   * /v1/dogear-event when the reader folds. campaignId/advertiserId travel
   * inside the signed payload so the fold endpoint can attribute the
   * engagement to the right campaign without a serve-time lookup.
   */
  private def foldTokenFor(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      camp: String,
      adv: String
  ): Future[Option[String]] =
    secretsRepo.secretFor(pub).map {
      case Some(sec) => Some(FoldToken.mint(pub, url, slot, cid, ver, camp, adv, sec))
      case None      =>
        system.log.warn("No HMAC secret for publisher [{}] — cannot mint fold token", pub)
        None
    }

  /**
   * Look up the campaign's endAt so the bootstrap can cap the dog-ear
   * pin's expiry to match. Open-ended campaigns (endAt = None) return
   * None — the bootstrap then falls back to its own 7-day cap.
   *
   * Best-effort: if the entity ask fails (timeout, sharding hiccup),
   * fall through with None instead of failing the entire serve. The
   * pin still works, it just defaults to the bootstrap cap.
   */
  private def campaignPinExpiresAt(
      advertiserId: String,
      campaignId: String
  ): Future[Option[Long]] = {
    given Timeout = Timeout(300.millis)
    val entityId = s"$advertiserId|$campaignId"
    val ref = sharding.entityRefFor(promovolve.advertiser.CampaignEntity.TypeKey, entityId)
    ref
      .ask[promovolve.advertiser.CampaignEntity.CampaignInfo](
        promovolve.advertiser.CampaignEntity.GetCampaign(_)
      )
      .map(_.endAt.map(_.toEpochMilli))
      .recover { case _ => None }
  }

  private def impUrl(
      pub: String, url: String, slot: String, cid: String, ver: Long,
      campaignId: String, advertiserId: String, cpm: Double, category: String, requestId: String,
      adProductCategory: Option[String], pageCategories: Option[String]
  ): Future[Option[String]] =
    signedUrl("imp", pub, url, slot, cid, ver, Some(campaignId), Some(advertiserId), Some(cpm), Some(category),
      Some(requestId), adProductCategory, pageCategories)
}
