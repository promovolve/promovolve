package promovolve.common

import java.security.SecureRandom
import java.util.Base64
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/**
 * Fold-token primitive for the dog-ear feature.
 *
 * Stateless HMAC-signed credential that authenticates a fold gesture against a
 * specific auction win. Minted at serve time, verified at /v1/dogear-event.
 *
 * Token format: `<base64url(payload)>.<base64url(hmac)>` where payload is
 * `pub|url|slot|cid|ver|bucket|nonce`. The payload travels inside the token,
 * so the client only sends the opaque string back — unlike /v1/imp, where the
 * canonical fields are separate query params.
 *
 * Freshness window is 30 min (vs the 3 min /v1/imp window) to give readers
 * time to expand the spread, browse pages, and decide to fold.
 *
 * Redemption (one-shot consumption) is enforced downstream by the billing
 * path's idempotency layer; this object only signs and verifies.
 */
object FoldToken {
  val EventType: String = "fold"
  val MaxSkew: FiniteDuration = 30.minutes
  val BucketMs: Long = 60 * 1000L

  private val rng = new SecureRandom()
  private val b64 = Base64.getUrlEncoder.withoutPadding()
  private val b64dec = Base64.getUrlDecoder

  final case class Context(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      bucket: Long,
      camp: String,
      adv: String,
      nonce: String
  )

  def nowBucket(nowMs: Long = System.currentTimeMillis()): Long =
    nowMs / BucketMs

  def mint(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      camp: String,
      adv: String,
      secret: Array[Byte],
      nowMs: Long = System.currentTimeMillis()
  ): String = {
    val bucket = nowBucket(nowMs)
    val nonceBytes = new Array[Byte](16)
    rng.nextBytes(nonceBytes)
    val nonce = b64.encodeToString(nonceBytes)
    // camp/adv ride inside the signed payload so the fold endpoint can
    // attribute the engagement to the right campaign without a lookup.
    val payload = s"$pub|$url|$slot|$cid|$ver|$bucket|$camp|$adv|$nonce"
    val payloadEnc = b64.encodeToString(payload.getBytes("UTF-8"))
    val canonical = Signer.canonical(pub, url, slot, cid, ver, bucket, EventType) + s"|$camp|$adv|$nonce"
    val mac = Signer.hmac256(canonical, secret)
    s"$payloadEnc.$mac"
  }

  /**
   * Verify a token. Returns Right(context) if signature, freshness, and
   * slot/cid match the expected values from the fold POST body. Otherwise
   * returns Left(reason) where reason is one of:
   *   bad_format | bad_payload | slot_mismatch | creative_mismatch |
   *   bad_signature | stale
   */
  def verify(
      token: String,
      expectedSlot: String,
      expectedCid: String,
      secret: Array[Byte],
      nowMs: Long = System.currentTimeMillis()
  ): Either[String, Context] =
    token.split('.') match {
      case Array(payloadEnc, providedMac) =>
        decodePayload(payloadEnc).flatMap { case (pub, url, slot, cid, ver, bucket, camp, adv, nonce) =>
          if (slot != expectedSlot) Left("slot_mismatch")
          else if (cid != expectedCid) Left("creative_mismatch")
          else {
            val canonical = Signer.canonical(pub, url, slot, cid, ver, bucket, EventType) + s"|$camp|$adv|$nonce"
            val expect = Signer.hmac256(canonical, secret)
            if (!Signer.safeEq(expect, providedMac)) Left("bad_signature")
            else if (!fresh(bucket, nowMs)) Left("stale")
            else Right(Context(pub, url, slot, cid, ver, bucket, camp, adv, nonce))
          }
        }
      case _ => Left("bad_format")
    }

  def fresh(bucket: Long, nowMs: Long = System.currentTimeMillis()): Boolean = {
    val nowB = nowBucket(nowMs)
    math.abs(nowB - bucket) <= (MaxSkew.toMillis / BucketMs)
  }

  private def decodePayload(
      payloadEnc: String
  ): Either[String, (String, String, String, String, Long, Long, String, String, String)] =
    try {
      val payload = new String(b64dec.decode(payloadEnc), "UTF-8")
      payload.split('|') match {
        case Array(pub, url, slot, cid, verStr, bucketStr, camp, adv, nonce) =>
          try Right((pub, url, slot, cid, verStr.toLong, bucketStr.toLong, camp, adv, nonce))
          catch { case _: NumberFormatException => Left("bad_payload") }
        case _ => Left("bad_payload")
      }
    } catch {
      case _: IllegalArgumentException => Left("bad_payload")
    }
}
