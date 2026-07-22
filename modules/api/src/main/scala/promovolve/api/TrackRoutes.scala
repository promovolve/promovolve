package promovolve.api

import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Scheduler }
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import promovolve.api.guard.{ ReplayGuard, TrackingReplayGuard }
import promovolve.common.{ hash, FoldToken, PublisherSecretsRepo, Signer }
import spray.json.*

import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }

/**
 * POST /v1/dogear-event request body. `pub` is needed for the unfold path
 * (no foldToken to derive it from); for fold, the foldToken's payload
 * carries pub redundantly, but a body field keeps both paths uniform.
 * `foldToken` is required for fold, ignored for unfold.
 */
final case class DogearEventReq(
    pub: String,
    url: String,
    creativeId: String,
    slotId: String,
    event: String, // "fold" | "unfold"
    foldToken: Option[String] = None,
    page: Option[Int] = None
)

trait DogearEventJson extends DefaultJsonProtocol {
  given RootJsonFormat[DogearEventReq] = jsonFormat7(DogearEventReq.apply)
}

/**
 * POST /v1/beacon/mount request body — the mount heartbeat the publisher
 * ad tag fires once per pageview (banner-bootstrap/src/heartbeat.ts).
 * Anonymous by design: site id + lifecycle stage + counts, nothing else.
 */
final case class MountBeaconReq(
    v: Int,
    pub: String,
    stage: String, // "script" | "slot" | "serve" | "render"
    ok: Boolean,
    slots: Int,
    served: Int,
    mounted: Int,
    reason: Option[String] = None
)

trait MountBeaconJson extends DefaultJsonProtocol {
  given RootJsonFormat[MountBeaconReq] = jsonFormat8(MountBeaconReq.apply)
}

final class TrackRoutes(
    secrets: PublisherSecretsRepo,
    events: EventLog,
    bucketMs: Long = 60 * 1000L,
    maxSkew: FiniteDuration = 3.minutes,
    replayGuard: Option[ActorRef[TrackingReplayGuard.Command]] = None,
    mountBeacons: Option[promovolve.publisher.MountBeaconRepo] = None,
    // Layer-0 request hygiene (docs/design/FRAUD_PREVENTION.md): classifies
    // the beacon's own IP/UA — the real reader's — and marks money/learning
    // events from datacenter/bot/over-rate sources. Default = disabled
    // (fail-open) until the ASN db is provisioned.
    hygiene: promovolve.fraud.RequestHygiene = promovolve.fraud.RequestHygiene.disabled,
    // Layer-1 engagement-chain guard (fraud): impression→click→cta ordering +
    // sub-human server-side timing. None = off (chain/timing marking absent).
    engagement: Option[promovolve.api.guard.EngagementChecker] = None
)(using system: ActorSystem[?]) extends DogearEventJson with MountBeaconJson {

  val routes: Route =
    pathPrefix("v1") {
      extractClientIP { ip =>
        headerValueByName("User-Agent") { ua =>
          // Classify the beacon's own client ONCE per request (the rate
          // gate consumes a token per call). This is the real reader's
          // IP/UA — a datacenter/bot/over-rate source here marks every
          // billable + learnable event it fires as suspect, excluding it
          // from money and Thompson/floor learning downstream. Mount
          // beacons ignore the mark (they're the honest denominator).
          val ipAddr = ip.toOption.map(_.getHostAddress).getOrElse("")
          val suspectReason = hygiene.classify(ipAddr, Some(ua))
          pathPrefix("imp") {
            get {
              parameters(
                "pub", "url", "slot", "cid", "v".as[Long], "b".as[Long], "tok",
                "camp".?, "adv".?, "cpm".?, "cat".?, "rid".?, "apc".?, "pcats".?,
                "dogeared".as[Boolean].?
              ) { (pub, url, slot, cid, v, b, tok, camp, adv, cpm, cat, rid, apc, pcats, dogeared) =>
                // cpm kept as its raw string for signature verification; the
                // signature binds camp/adv/cpm so a beacon can't redirect
                // spend to another campaign or inflate the amount.
                onSuccess(validateImp(pub, url, slot, cid, v, b, tok, camp, adv, cpm, rid)) {
                  case false => complete(StatusCodes.Forbidden)
                  case true  =>
                    // Include rid in replay key to allow multiple impressions of same creative
                    val canonical = Signer.canonical(pub, url, slot, cid, v, b, "imp") +
                      rid.map(r => s"|$r").getOrElse("")
                    onSuccess(checkReplay(canonical)) {
                      case false => complete(StatusCodes.Conflict)
                      case true  =>
                        // Record the impression's server-arrival so a later
                        // click/cta on this rid can be timing/chain-checked
                        // (Layer 1). The impression itself is the chain root —
                        // no predecessor to check — so it carries only the
                        // Layer-0 hygiene mark.
                        val impAt = System.currentTimeMillis()
                        rid.foreach(r => engagement.foreach(_.recordImpression(r, impAt)))
                        events.logImpression(
                          TrackEvent(
                            pub = pub,
                            url = url,
                            slot = slot,
                            cid = cid,
                            version = v,
                            bucket = b,
                            ts = impAt,
                            ip = ip.toOption.map(_.getHostAddress).getOrElse(""),
                            ua = ua,
                            campaignId = camp,
                            advertiserId = adv,
                            cpm = cpm.flatMap(_.toDoubleOption),
                            category = cat,
                            requestId = rid,
                            adProductCategory = apc,
                            pageCategories = pcats,
                            dogeared = dogeared.getOrElse(false),
                            suspectReason = suspectReason
                          )
                        )
                        complete(StatusCodes.NoContent)
                    }
                }
              }
            }
          } ~
          pathPrefix("click") {
            get {
              parameters("pub", "url", "slot", "cid", "v".as[Long], "b".as[Long], "tok", "cat".?, "camp".?, "adv".?,
                "rid".?, "apc".?, "pcats".?, "dogeared".as[Boolean].?) {
                (pub, url, slot, cid, v, b, tok, cat, camp, adv, rid, apc, pcats, dogeared) =>
                  onSuccess(validateClick(pub, url, slot, cid, v, b, tok, camp, adv, rid)) {
                    case false => complete(StatusCodes.Forbidden)
                    case true  =>
                      val canonical = Signer.canonical(pub, url, slot, cid, v, b, "click") +
                        rid.map(r => s"|$r").getOrElse("")
                      onSuccess(checkReplay(canonical)) {
                        case false => complete(StatusCodes.Conflict)
                        case true  =>
                          // Layer-1 chain/timing check against this rid's
                          // recorded impression. Hygiene (Layer 0) wins if it
                          // already marked; else the chain/timing verdict
                          // applies. Fail-open when the guard is off / errors.
                          val clickAt = System.currentTimeMillis()
                          val chainF = rid match {
                            case Some(r) => engagement.map(_.checkClick(r, clickAt)).getOrElse(Future.successful(None))
                            case None    => Future.successful(None)
                          }
                          onSuccess(chainF) { chainReason =>
                            events.logClick(
                              TrackEvent(
                                pub = pub,
                                url = url,
                                slot = slot,
                                cid = cid,
                                version = v,
                                bucket = b,
                                ts = clickAt,
                                ip = ip.toOption.map(_.getHostAddress).getOrElse(""),
                                ua = ua,
                                category = cat,
                                campaignId = camp,
                                advertiserId = adv,
                                requestId = rid,
                                adProductCategory = apc,
                                pageCategories = pcats,
                                dogeared = dogeared.getOrElse(false),
                                suspectReason = suspectReason.orElse(chainReason)
                              )
                            )
                            complete(StatusCodes.NoContent)
                          }
                      }
                  }
              }
            }
          } ~
          pathPrefix("dogear-event") {
            post {
              entity(as[DogearEventReq]) { req =>
                req.event match {
                  case "fold" =>
                    req.foldToken match {
                      case None =>
                        complete(StatusCodes.BadRequest -> "missing foldToken")
                      case Some(token) =>
                        onSuccess(verifyFold(req.pub, req.slotId, req.creativeId, token)) {
                          case Left(reason) =>
                            system.log.warn(
                              "FOLD_REJECTED: pub={} slot={} cid={} reason={}",
                              req.pub, req.slotId, req.creativeId, reason
                            )
                            complete(StatusCodes.Forbidden -> reason)
                          case Right(ctx) =>
                            // requestId is a 16-char hex hash of the full fold
                            // token — fits the tracking_events.request_id
                            // VARCHAR(26) column AND stays deterministic so
                            // replays of the same token produce the same key.
                            // Same hash function the bloom filter uses, so
                            // CampaignEntity dedups consistently.
                            val tokenHash = "%016x".format(token.hash)
                            events.logFold(
                              TrackEvent(
                                pub = ctx.pub,
                                url = ctx.url,
                                slot = ctx.slot,
                                cid = ctx.cid,
                                version = ctx.ver,
                                bucket = ctx.bucket,
                                ts = System.currentTimeMillis(),
                                ip = "",
                                ua = "",
                                campaignId = Some(ctx.camp),
                                advertiserId = Some(ctx.adv),
                                requestId = Some(tokenHash),
                                suspectReason = suspectReason
                              )
                            )
                            complete(StatusCodes.NoContent)
                        }
                    }
                  case "unfold" =>
                    // No token, no billing — telemetry only. The journal entry
                    // captures slot/cid for the pin retention metric in
                    // DashboardProjection ((folds - unfolds) / folds).
                    events.logUnfold(
                      TrackEvent(
                        pub = req.pub,
                        url = req.url,
                        slot = req.slotId,
                        cid = req.creativeId,
                        version = 0L,
                        bucket = 0L,
                        ts = System.currentTimeMillis(),
                        ip = "",
                        ua = "",
                        suspectReason = suspectReason
                      )
                    )
                    complete(StatusCodes.NoContent)
                  case other =>
                    complete(StatusCodes.BadRequest -> s"unknown event: $other")
                }
              }
            }
          } ~
          pathPrefix("beacon" / "mount") {
            post {
              entity(as[MountBeaconReq]) { req =>
                // Always 204 — the tag treats this as fire-and-forget, and a
                // uniform reply gives probes nothing to enumerate site ids
                // with. Persist only when the pub is a real publisher (has an
                // HMAC secret) and the payload is shaped like the tag sends
                // it; junk is dropped silently.
                val validStage = Set("script", "slot", "serve", "render")
                val sane = req.v == 1 &&
                  req.pub.nonEmpty && req.pub.length <= 128 &&
                  validStage.contains(req.stage) &&
                  Seq(req.slots, req.served, req.mounted).forall(n => n >= 0 && n <= 10000)
                if (sane) {
                  mountBeacons.foreach { repo =>
                    secrets.secretFor(req.pub).foreach {
                      case Some(_) =>
                        repo
                          .record(
                            req.pub,
                            req.stage,
                            req.ok,
                            req.reason.map(_.take(120)),
                            req.slots,
                            req.served,
                            req.mounted
                          )
                          .failed
                          .foreach(e => system.log.warn("mount beacon insert failed: {}", e.toString))
                      case None => // unknown site id — drop
                    }
                  }
                }
                complete(StatusCodes.NoContent)
              }
            }
          } ~
          pathPrefix("cta") {
            get {
              parameters("pub", "url", "slot", "cid", "v".as[Long], "b".as[Long], "tok", "cat".?, "camp".?, "adv".?,
                "rid".?, "apc".?, "pcats".?, "dogeared".as[Boolean].?) {
                (pub, url, slot, cid, v, b, tok, cat, camp, adv, rid, apc, pcats, dogeared) =>
                  onSuccess(validateCTA(pub, url, slot, cid, v, b, tok, camp, adv, rid)) {
                    case false => complete(StatusCodes.Forbidden)
                    case true  =>
                      val canonical = Signer.canonical(pub, url, slot, cid, v, b, "cta") +
                        rid.map(r => s"|$r").getOrElse("")
                      onSuccess(checkReplay(canonical)) {
                        case false => complete(StatusCodes.Conflict)
                        case true  =>
                          // Layer-1: a CTA must follow this rid's click.
                          val ctaAt = System.currentTimeMillis()
                          val chainF = rid match {
                            case Some(r) => engagement.map(_.checkCta(r, ctaAt)).getOrElse(Future.successful(None))
                            case None    => Future.successful(None)
                          }
                          onSuccess(chainF) { chainReason =>
                            events.logCTAClick(
                              TrackEvent(
                                pub = pub,
                                url = url,
                                slot = slot,
                                cid = cid,
                                version = v,
                                bucket = b,
                                ts = ctaAt,
                                ip = ip.toOption.map(_.getHostAddress).getOrElse(""),
                                ua = ua,
                                category = cat,
                                campaignId = camp,
                                advertiserId = adv,
                                requestId = rid,
                                adProductCategory = apc,
                                pageCategories = pcats,
                                dogeared = dogeared.getOrElse(false),
                                suspectReason = suspectReason.orElse(chainReason)
                              )
                            )
                            complete(StatusCodes.NoContent)
                          }
                      }
                  }
              }
            }
          }
        }
      }
    }

  private given Timeout = Timeout(2.seconds)

  private given Scheduler = system.scheduler

  private given ExecutionContext = system.executionContext

  /**
   * Validate impression. The HMAC binds camp/adv/cpm/rid (fixed order,
   * matching `ServeRoutes.signedUrl`) so a client can't rewrite the beacon's
   * campaign/advertiser (spend redirection) or cpm (amount inflation). cpm is
   * verified as its raw URL string to stay byte-identical to the mint.
   */
  private def validateImp(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      b: Long,
      tok: String,
      camp: Option[String],
      adv: Option[String],
      cpm: Option[String],
      rid: Option[String]
  ): Future[Boolean] =
    secrets.secretFor(pub).map {
      case Some(sec) =>
        val data = Signer.canonical(pub, url, slot, cid, ver, b, "imp") + Signer.bind(camp, adv, cpm, rid)
        val expect = Signer.hmac256(data, sec)
        Signer.safeEq(expect, tok) && freshBucket(b)
      case None => false
    }

  /** Validate click. HMAC binds camp/adv/rid (click carries no cpm). */
  private def validateClick(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      b: Long,
      tok: String,
      camp: Option[String],
      adv: Option[String],
      rid: Option[String]
  ): Future[Boolean] =
    secrets.secretFor(pub).map {
      case Some(sec) =>
        val data = Signer.canonical(pub, url, slot, cid, ver, b, "click") + Signer.bind(camp, adv, None, rid)
        val expect = Signer.hmac256(data, sec)
        Signer.safeEq(expect, tok) && freshBucket(b)
      case None => false
    }

  /** Validate CTA click. HMAC binds camp/adv/rid (cta carries no cpm). */
  private def validateCTA(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      b: Long,
      tok: String,
      camp: Option[String],
      adv: Option[String],
      rid: Option[String]
  ): Future[Boolean] =
    secrets.secretFor(pub).map {
      case Some(sec) =>
        val data = Signer.canonical(pub, url, slot, cid, ver, b, "cta") + Signer.bind(camp, adv, None, rid)
        val expect = Signer.hmac256(data, sec)
        Signer.safeEq(expect, tok) && freshBucket(b)
      case None => false
    }

  /**
   * Verify a fold token. Looks up publisher secret, then delegates to
   * FoldToken.verify which checks signature, freshness, and slot/cid
   * binding. Returns Left(reason) on any failure (string matches the
   * reasons documented on FoldToken.verify), Right(Context) on success.
   */
  private def verifyFold(
      pub: String,
      slot: String,
      cid: String,
      token: String
  ): Future[Either[String, FoldToken.Context]] =
    secrets.secretFor(pub).map {
      case Some(sec) => FoldToken.verify(token, slot, cid, sec)
      case None      => Left("no_secret")
    }

  private def freshBucket(b: Long): Boolean = {
    val nowB = System.currentTimeMillis() / bucketMs
    math.abs(nowB - b) <= (maxSkew.toMillis / bucketMs)
  }

  private def checkReplay(canonical: String): Future[Boolean] =
    replayGuard match {
      case Some(guard) =>
        val nonce = ReplayGuard.hash(canonical)
        guard.ask[Boolean](ref => TrackingReplayGuard.Validate(nonce, ref))
      case None =>
        Future.successful(true)
    }
}
