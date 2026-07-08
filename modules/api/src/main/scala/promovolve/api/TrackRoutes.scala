package promovolve.api

import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import promovolve.api.guard.{ReplayGuard, TrackingReplayGuard}
import promovolve.common.{FoldToken, PublisherSecretsRepo, Signer, hash}
import spray.json.*

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** POST /v1/dogear-event request body. `pub` is needed for the unfold path
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

final class TrackRoutes(
    secrets: PublisherSecretsRepo,
    events: EventLog,
    bucketMs: Long          = 60 * 1000L,
    maxSkew: FiniteDuration = 3.minutes,
    replayGuard: Option[ActorRef[TrackingReplayGuard.Command]] = None
)(using system: ActorSystem[?]) extends DogearEventJson {

  val routes: Route =
    pathPrefix("v1") {
      extractClientIP { ip =>
        headerValueByName("User-Agent") { ua =>
          pathPrefix("imp") {
            get {
              parameters(
                "pub", "url", "slot", "cid", "v".as[Long], "b".as[Long], "tok",
                "camp".?, "adv".?, "cpm".as[Double].?, "cat".?, "rid".?, "apc".?, "pcats".?,
                "dogeared".as[Boolean].?
              ) { (pub, url, slot, cid, v, b, tok, camp, adv, cpm, cat, rid, apc, pcats, dogeared) =>
                onSuccess(validateImp(pub, url, slot, cid, v, b, tok, rid)) {
                  case false => complete(StatusCodes.Forbidden)
                  case true =>
                    // Include rid in replay key to allow multiple impressions of same creative
                    val canonical = Signer.canonical(pub, url, slot, cid, v, b, "imp") + rid.map(r => s"|$r").getOrElse("")
                    onSuccess(checkReplay(canonical)) {
                      case false => complete(StatusCodes.Conflict)
                      case true =>
                        events.logImpression(
                          TrackEvent(
                            pub     = pub,
                            url     = url,
                            slot    = slot,
                            cid     = cid,
                            version = v,
                            bucket  = b,
                            ts      = System.currentTimeMillis(),
                            ip      = ip.toOption.map(_.getHostAddress).getOrElse(""),
                            ua      = ua,
                            campaignId   = camp,
                            advertiserId = adv,
                            cpm          = cpm,
                            category     = cat,
                            requestId    = rid,
                            adProductCategory = apc,
                            pageCategories = pcats,
                            dogeared     = dogeared.getOrElse(false)
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
              parameters("pub", "url", "slot", "cid", "v".as[Long], "b".as[Long], "tok", "cat".?, "camp".?, "adv".?, "rid".?, "apc".?, "pcats".?, "dogeared".as[Boolean].?) {
                (pub, url, slot, cid, v, b, tok, cat, camp, adv, rid, apc, pcats, dogeared) =>
                  onSuccess(validateClick(pub, url, slot, cid, v, b, tok, rid)) {
                    case false => complete(StatusCodes.Forbidden)
                    case true =>
                      val canonical = Signer.canonical(pub, url, slot, cid, v, b, "click") + rid.map(r => s"|$r").getOrElse("")
                      onSuccess(checkReplay(canonical)) {
                        case false => complete(StatusCodes.Conflict)
                        case true =>
                          events.logClick(
                            TrackEvent(
                              pub          = pub,
                              url          = url,
                              slot         = slot,
                              cid          = cid,
                              version      = v,
                              bucket       = b,
                              ts           = System.currentTimeMillis(),
                              ip           = ip.toOption.map(_.getHostAddress).getOrElse(""),
                              ua           = ua,
                              category     = cat,
                              campaignId   = camp,
                              advertiserId = adv,
                              requestId    = rid,
                              adProductCategory = apc,
                              pageCategories = pcats,
                              dogeared     = dogeared.getOrElse(false)
                            )
                          )
                          complete(StatusCodes.NoContent)
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
                                pub          = ctx.pub,
                                url          = ctx.url,
                                slot         = ctx.slot,
                                cid          = ctx.cid,
                                version      = ctx.ver,
                                bucket       = ctx.bucket,
                                ts           = System.currentTimeMillis(),
                                ip           = "",
                                ua           = "",
                                campaignId   = Some(ctx.camp),
                                advertiserId = Some(ctx.adv),
                                requestId    = Some(tokenHash)
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
                        pub  = req.pub,
                        url  = req.url,
                        slot = req.slotId,
                        cid  = req.creativeId,
                        version = 0L,
                        bucket  = 0L,
                        ts   = System.currentTimeMillis(),
                        ip   = "",
                        ua   = ""
                      )
                    )
                    complete(StatusCodes.NoContent)
                  case other =>
                    complete(StatusCodes.BadRequest -> s"unknown event: $other")
                }
              }
            }
          } ~
          pathPrefix("cta") {
            get {
              parameters("pub", "url", "slot", "cid", "v".as[Long], "b".as[Long], "tok", "cat".?, "camp".?, "adv".?, "rid".?, "apc".?, "pcats".?, "dogeared".as[Boolean].?) {
                (pub, url, slot, cid, v, b, tok, cat, camp, adv, rid, apc, pcats, dogeared) =>
                  onSuccess(validateCTA(pub, url, slot, cid, v, b, tok, rid)) {
                    case false => complete(StatusCodes.Forbidden)
                    case true =>
                      val canonical = Signer.canonical(pub, url, slot, cid, v, b, "cta") + rid.map(r => s"|$r").getOrElse("")
                      onSuccess(checkReplay(canonical)) {
                        case false => complete(StatusCodes.Conflict)
                        case true =>
                          events.logCTAClick(
                            TrackEvent(
                              pub          = pub,
                              url          = url,
                              slot         = slot,
                              cid          = cid,
                              version      = v,
                              bucket       = b,
                              ts           = System.currentTimeMillis(),
                              ip           = ip.toOption.map(_.getHostAddress).getOrElse(""),
                              ua           = ua,
                              category     = cat,
                              campaignId   = camp,
                              advertiserId = adv,
                              requestId    = rid,
                              adProductCategory = apc,
                              pageCategories = pcats,
                              dogeared     = dogeared.getOrElse(false)
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

  private given Timeout = Timeout(2.seconds)

  private given Scheduler = system.scheduler

  private given ExecutionContext = system.executionContext

  /** Validate impression with requestId included in HMAC (prevents rid tampering) */
  private def validateImp(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      b: Long,
      tok: String,
      rid: Option[String]
  ): Future[Boolean] =
    secrets.secretFor(pub).map {
      case Some(sec) =>
        val data   = Signer.canonical(pub, url, slot, cid, ver, b, "imp") + rid.map(r => s"|$r").getOrElse("")
        val expect = Signer.hmac256(data, sec)
        Signer.safeEq(expect, tok) && freshBucket(b)
      case None => false
    }

  /** Validate click with requestId included in HMAC (prevents rid tampering) */
  private def validateClick(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      b: Long,
      tok: String,
      rid: Option[String]
  ): Future[Boolean] =
    secrets.secretFor(pub).map {
      case Some(sec) =>
        val data   = Signer.canonical(pub, url, slot, cid, ver, b, "click") + rid.map(r => s"|$r").getOrElse("")
        val expect = Signer.hmac256(data, sec)
        Signer.safeEq(expect, tok) && freshBucket(b)
      case None => false
    }

  /** Validate CTA click with requestId included in HMAC */
  private def validateCTA(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      b: Long,
      tok: String,
      rid: Option[String]
  ): Future[Boolean] =
    secrets.secretFor(pub).map {
      case Some(sec) =>
        val data   = Signer.canonical(pub, url, slot, cid, ver, b, "cta") + rid.map(r => s"|$r").getOrElse("")
        val expect = Signer.hmac256(data, sec)
        Signer.safeEq(expect, tok) && freshBucket(b)
      case None => false
    }

  /** Verify a fold token. Looks up publisher secret, then delegates to
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
