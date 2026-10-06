package promovolve.api

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import promovolve.SiteId
import promovolve.browser.UrlNormalizer
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
    pinExpiresAt: Option[Long] = None, // Campaign endAt as epoch millis — bootstrap caps the pin's expiresAt at this value
    frequencyCap: Option[FrequencyCapWire] = None, // Campaign's per-browser cap; None = uncapped
    reach: Option[ReachWire] = None // Where/when to report reach on the viewable impression (GH #238)
)

/**
 * Outcome of attempting to honor a pin hint sent by the client. Reasons:
 *   creative_removed | campaign_inactive | budget_exhausted | dogear_disabled
 */
final case class DogearInfo(honored: Boolean, reason: Option[String] = None)

/**
 * Frequency-cap policy for the winner's campaign, for the BROWSER to enforce
 * from its own storage: at most `n` billed impressions per `windowMs`. The
 * server never counts — it only carries the policy out and honours the
 * browser's `excludeCampaigns` on the way back. docs/design/FREQUENCY_CAPPING.md.
 */
final case class FrequencyCapWire(campaignId: String, n: Int, windowMs: Long)

/**
 * Reach reporting for the winner's campaign (GH #238). `day` is the
 * campaign's advertiser-local epoch day, signed into `url`. On the first
 * viewable impression of a day the tag appends the day it last saw this
 * campaign on this site (`prev`, or "never") and a one-time `fresh` value,
 * then stores `day`. The tag does no date arithmetic.
 */
final case class ReachWire(campaignId: String, day: Long, url: String)

/**
 * Pin hint from the bootstrap. Tells the server "this slot is pinned to
 * creativeId in the reader's IndexedDB; honor it if possible." Server may
 * fall through to a normal auction if the creative is no longer servable.
 */
final case class PinHint(slotId: String, creativeId: String)

/**
 * "Is this campaign still capped?" — one campaign the browser is currently
 * declining, paired with a creative it actually saw from that campaign.
 *
 * The pairing is what makes this answerable AND safe. The server has no
 * campaignId → owner index on the serve path (campaign entities are keyed
 * `advertiserId|campaignId`), but it does have the creative store, so the
 * creative names its own campaign's owner. It is also the authorization:
 * a policy is returned only when the creative really belongs to the
 * campaign asked about, so a client cannot enumerate caps it was never
 * served. See docs/design/FREQUENCY_CAPPING.md.
 */
final case class CapCheck(campaignId: String, creativeId: String)

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
    pins: Option[Vector[PinHint]] = None, // optional dog-ear pin hints, one per pinned slot
    // Campaigns the browser declines because it has reached their frequency
    // cap (computed client-side from its own impression records). Option —
    // spray ignores defaults, and older tags omit it. Bounded server-side
    // (ExcludeCampaigns.MaxEntries); never logged by value.
    excludeCampaigns: Option[Vector[String]] = None,
    // The same campaigns, each with a creative the browser saw from it, so
    // the server can answer with the CURRENT policy. Without this a removed
    // cap could never reach a browser already at it: being excluded is
    // exactly what prevents the impression that would carry the new policy
    // back. Option — older tags omit it.
    capCheck: Option[Vector[CapCheck]] = None
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
    // Publisher-declared PLACE for this page (the WordPress plugin's
    // data-place). Same contract as `section`: an interested, unverified
    // claim the classifier may use to disambiguate and must ignore when the
    // content disagrees. Option, not a defaulted String — spray's
    // jsonFormatN ignores case-class defaults, so an older ad tag that omits
    // the field would fail to parse.
    place: Option[String] = None,
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
    reclassifyInMs: Long = Long.MaxValue,
    // Current policy for campaigns the request asked about via `capCheck`.
    // `n = 0` means the cap was removed — the browser stops declining it.
    // A campaign whose policy could NOT be established is absent rather
    // than reported as uncapped: silence leaves the browser's own record
    // standing, which keeps a cap the advertiser is paying for.
    capPolicies: Option[Vector[FrequencyCapWire]] = None
)

/**
 * Pure derivation of the `stalePins` payload — extracted from the
 * batch route so the safety-critical distinctions are unit-testable:
 * a transient lookup failure must NEVER be reported stale (it would
 * delete a live bookmark client-side), and the slot-existence pass
 * must apply only to off-page pins and only when the site has a
 * non-empty slot config.
 */
/**
 * Pure merge of the two sources of hard campaign exclusion for a batch:
 * off-page dog-ear pins (resolved server-side) and the browser's own
 * frequency-cap list. Bounded so a hostile client cannot blank its auction
 * by sending thousands of ids; blanks dropped, order-preserving dedupe.
 */
private[api] object ExcludeCampaigns {
  val MaxEntries: Int = 32

  def merge(fromPins: Set[promovolve.CampaignId], fromRequest: Option[Vector[String]]): Set[promovolve.CampaignId] =
    fromPins ++
    fromRequest
      .getOrElse(Vector.empty)
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .distinct
      .take(MaxEntries)
      .map(promovolve.CampaignId.apply)
}

/**
 * Pure half of the cap-refresh answer: which `capCheck` entries the server
 * will bother looking up.
 *
 * Bounded by the same MaxEntries as the exclusion list it mirrors — the two
 * carry the same ids, so a client that cannot blank its auction with
 * thousands of exclusions must not be able to spend the server's lookups
 * either. Blanks dropped, order-preserving dedupe by campaign: one answer
 * per campaign is all the browser can use.
 */
private[api] object CapRefresh {
  val MaxEntries: Int = ExcludeCampaigns.MaxEntries

  def wanted(fromRequest: Option[Vector[CapCheck]]): Vector[CapCheck] =
    fromRequest
      .getOrElse(Vector.empty)
      .iterator
      .map(c => CapCheck(c.campaignId.trim, c.creativeId.trim))
      .filter(c => c.campaignId.nonEmpty && c.creativeId.nonEmpty)
      .distinctBy(_.campaignId)
      .take(MaxEntries)
      .toVector
}

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
  given RootJsonFormat[CapCheck] = jsonFormat2(CapCheck.apply)
  given RootJsonFormat[FrequencyCapWire] = jsonFormat3(FrequencyCapWire.apply)
  given RootJsonFormat[ReachWire] = jsonFormat3(ReachWire.apply)
  given RootJsonFormat[ServeRes] = jsonFormat18(ServeRes.apply)
  given RootJsonFormat[BatchImp] = jsonFormat4(BatchImp.apply)
  given RootJsonFormat[BatchServeReq] = jsonFormat7(BatchServeReq.apply)
  given RootJsonFormat[ClassifyImp] = jsonFormat7(ClassifyImp.apply)
  given RootJsonFormat[ClassifyPageTextReq] = jsonFormat6(ClassifyPageTextReq.apply)
  given RootJsonFormat[BatchImpResult] = jsonFormat3(BatchImpResult.apply)
  given RootJsonFormat[BatchServeRes] = jsonFormat5(BatchServeRes.apply)
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
            // No Sec-GPC branch here, deliberately. GPC signals "do not sell
            // or share my personal information" — and there is none to sell:
            // the auction reads the PAGE, never the viewer. No identifier is
            // ingested, no profile is built, no viewer identity is held
            // server-side. Declining to serve on the header would imply the
            // normal path does the thing GPC exists to stop, which is exactly
            // the claim this architecture makes structurally false. It also
            // cost every Brave-desktop and DuckDuckGo viewer a guaranteed
            // zero-fill on every publisher — GPC is ON BY DEFAULT there.
            // See GPC.md for the full reasoning.
            entity(as[BatchServeReq]) { req =>
              // Canonical page identity: strip tracking params (fbclid,
              // gclid, utm_*, …) so referral variants of one article don't
              // fragment into separate classifications, auctions, placements,
              // or tracking rows. Used everywhere the page URL is a key —
              // the auction, the freshness token, and the signed click/imp/
              // cta/fold tokens (so beacons record the canonical URL too).
              val pageUrl = UrlNormalizer.stripTrackingParams(req.url)
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
                // Hard campaign exclusion = off-page pins' campaigns (above)
                // ∪ the browser's frequency-capped campaigns. Merged here so
                // the auction sees one set; AdServer needs no cap logic.
                excludedCampaigns = ExcludeCampaigns.merge(
                  pinLookups.collect {
                    case (_, Some(Some(campaignId))) => promovolve.CampaignId(campaignId)
                  }.toSet,
                  req.excludeCampaigns
                )
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
                  "BatchServe pub={} url={} pins.size={} excludeCampaigns.size={} offPagePinCreatives={} excludedCreatives={} excludedCampaigns={}",
                  req.pub, pageUrl,
                  req.pins.fold(0)(_.size),
                  // Size only: the ids describe the reader's own history.
                  req.excludeCampaigns.fold(0)(_.size),
                  offPagePinCreatives.mkString(","),
                  excludedCreatives.map(_.value).mkString(","),
                  excludedCampaigns.map(_.value).mkString(",")
                )
                adServer = sharding.entityRefFor(AdServer.TypeKey, req.pub)
                batchResult <- adServer.ask[AdServer.BatchSelectResult] { replyTo =>
                  AdServer.BatchSelect(
                    url = promovolve.URL(pageUrl),
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
                          click <- clickUrl(req.pub, pageUrl, outcome.slotId.value, cand.creativeId.value, version,
                            cand.campaignId.value, cand.advertiserId.value, cand.category.value, outcome.requestId,
                            apc, None)
                          imp <- impUrl(
                            req.pub, pageUrl, outcome.slotId.value, cand.creativeId.value, version,
                            cand.campaignId.value, cand.advertiserId.value, cpmDollars, cand.category.value,
                            outcome.requestId, apc, None
                          )
                          cta <- ctaUrl(req.pub, pageUrl, outcome.slotId.value, cand.creativeId.value, version,
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
                            foldTokenFor(req.pub, pageUrl, outcome.slotId.value, cand.creativeId.value, version,
                              cand.campaignId.value, cand.advertiserId.value)
                          (pinExpiresAt, frequencyCap, timezone) <-
                            campaignServeFacts(cand.advertiserId.value, cand.campaignId.value)
                          reachDay = promovolve.common.Timezones.localEpochDay(java.time.Instant.now(), timezone)
                          reach <- reachUrl(req.pub, pageUrl, outcome.slotId.value, cand.creativeId.value, version,
                            cand.campaignId.value, cand.advertiserId.value, reachDay, outcome.requestId)
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
                                  // Fill the advertiser's {curly} attribution
                                  // macros from trusted auction context (no
                                  // user data — Promovolve doesn't track people).
                                  LandingMacros.substitute(
                                    cand.landingUrl,
                                    LandingMacros.valuesFor(
                                      source = "promovolve",
                                      campaignId = cand.campaignId.value,
                                      creativeId = cand.creativeId.value,
                                      site = req.pub,
                                      category = cand.category.value,
                                      slot = outcome.slotId.value
                                    )
                                  ),
                                  pagesJson,
                                  if (pagesJson.isDefined) Some(bannerScriptUrl) else None,
                                  bannerConfigJson,
                                  canFold = foldToken.isDefined,
                                  honorPin = true,
                                  foldToken = foldToken,
                                  dogear = slotDogear,
                                  pinExpiresAt = pinExpiresAt,
                                  frequencyCap = frequencyCap,
                                  reach = reach.map(ReachWire(cand.campaignId.value, reachDay, _))
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
                  // Answered alongside the auction, not inside it: the
                  // browser asked what the CURRENT policy is for campaigns it
                  // is declining, and none of them can win this batch to be
                  // told the normal way.
                  val capPoliciesF = capPoliciesFor(CapRefresh.wanted(req.capCheck))
                  onSuccess(Future.sequence(resFutures).zip(capPoliciesF)) { (results, capPolicies) =>
                    complete(BatchServeRes(
                      results,
                      stalePins = if (stalePins.nonEmpty) Some(stalePins) else None,
                      needText = needText,
                      reclassifyInMs = reclassifyInMs,
                      capPolicies = if (capPolicies.nonEmpty) Some(capPolicies) else None
                    ))
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
                // Canonicalize (strip tracking params) so a Facebook/Google/UTM
                // referral variant classifies the same page as its clean URL.
                url = UrlNormalizer.stripTrackingParams(cReq.url),
                text = cReq.text,
                section = cReq.section,
                place = cReq.place,
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

  // Outer anchor of the SERVE timeout ladder. Everything inside AdServer's
  // batch pipeline (index reads 150ms, cold spend fetch 250ms, reserve chain
  // 2×200ms) must fit inside this window with margin. At the old 300ms the
  // inner budgets were flat or INVERTED (spend fetch alone was 500ms): a
  // cold-cache batch arithmetically could not reply in time, the tag saw a
  // failed request, and the pipeline still committed reservations for a
  // response nobody received.
  private given Timeout = Timeout(800.millis)

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

  /** Signed reach beacon URL for a winner (GH #238); see ReachBeacon. */
  private def reachUrl(
      pub: String, url: String, slot: String, cid: String, ver: Long,
      campaignId: String, advertiserId: String, day: Long, requestId: String
  ): Future[Option[String]] = {
    val b = nowBucket()
    secretsRepo.secretFor(pub).map(_.map(sec =>
      ReachBeacon.url(trackingBase, sec, pub, url, slot, cid, ver, b, campaignId, advertiserId, day, requestId)))
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
  /**
   * Per-winner campaign facts the ad tag needs: the campaign's endAt (caps the
   * dog-ear pin's expiry) and its frequency-cap policy. One GetCampaign ask
   * serves both — a second ask per winner would double the serve-path load
   * for a field that rides in the same reply. Fails soft to (None, None).
   */
  private def campaignServeFacts(
      advertiserId: String,
      campaignId: String
  ): Future[(Option[Long], Option[FrequencyCapWire], String)] = {
    given Timeout = Timeout(300.millis)
    val entityId = s"$advertiserId|$campaignId"
    val ref = sharding.entityRefFor(promovolve.advertiser.CampaignEntity.TypeKey, entityId)
    ref
      .ask[promovolve.advertiser.CampaignEntity.CampaignInfo](
        promovolve.advertiser.CampaignEntity.GetCampaign(_)
      )
      .map(info =>
        (
          info.endAt.map(_.toEpochMilli),
          info.frequencyCap.flatMap(cap =>
            promovolve.advertiser.CampaignEntity.FrequencyCap.windowMs(cap.window)
              .map(ms => FrequencyCapWire(campaignId, cap.impressions, ms))),
          info.timezone
        ))
      .recover { case _ => (None, None, "") }
  }

  /**
   * Current cap policy for the campaigns the browser asked about.
   *
   * The browser cannot learn that a cap was REMOVED on its own: it stamps
   * each impression with the policy in force at the time and reads the most
   * recent stamp, but being at the cap is exactly what stops the campaign
   * winning again, so no newer stamp can ever arrive. This answers without
   * serving anything.
   *
   * `n = 0` means uncapped. A campaign whose policy could not be
   * established is OMITTED rather than reported uncapped — a lookup
   * failure must never lift a cap the advertiser is paying for.
   */
  private def capPoliciesFor(wanted: Vector[CapCheck]): Future[Vector[FrequencyCapWire]] =
    if (wanted.isEmpty) Future.successful(Vector.empty)
    else
      Future
        .sequence(wanted.map { ask =>
          creativeRepo
            .map(_.get(ask.creativeId))
            .getOrElse(Future.successful(None))
            .flatMap {
              // The creative must really belong to the campaign asked
              // about: that is what makes the owner lookup possible AND
              // stops a client reading caps it was never served.
              case Some(cr) if cr.campaignId == ask.campaignId => currentCap(cr.advertiserId, ask.campaignId)
              case _                                           => Future.successful(None)
            }
            .recover { case _ => None }
        })
        .map(_.flatten)

  private def currentCap(advertiserId: String, campaignId: String): Future[Option[FrequencyCapWire]] = {
    given Timeout = Timeout(300.millis)
    sharding
      .entityRefFor(promovolve.advertiser.CampaignEntity.TypeKey, s"$advertiserId|$campaignId")
      .ask[promovolve.advertiser.CampaignEntity.CampaignInfo](
        promovolve.advertiser.CampaignEntity.GetCampaign(_)
      )
      .map(_.frequencyCap match {
        case None      => Some(FrequencyCapWire(campaignId, 0, 0L))
        case Some(cap) =>
          // An unrecognised window is not an answer either — say nothing
          // rather than flatten it into "uncapped".
          promovolve.advertiser.CampaignEntity.FrequencyCap
            .windowMs(cap.window)
            .map(ms => FrequencyCapWire(campaignId, cap.impressions, ms))
      })
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
