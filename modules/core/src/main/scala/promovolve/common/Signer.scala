package promovolve.common

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Signer {
  def canonical(
      pub: String,
      url: String,
      slot: String,
      cid: String,
      ver: Long,
      bucket: Long,
      evt: String
  ): String =
    s"$pub|$url|$slot|$cid|$ver|$bucket|$evt"

  /**
   * Append optional binding fields to a canonical string in a fixed order,
   * each rendered as `|value` and absent ones skipped. The mint site and the
   * verify site MUST pass the same fields in the same order. Used to bind
   * campaign / advertiser / cpm / requestId into the tracking-token signature
   * so a client can't rewrite them after serve (e.g. redirecting a beacon's
   * spend to a competitor's campaign). Pass cpm as its exact URL string form
   * so the signature is byte-identical without a Double round-trip.
   */
  def bind(fields: Option[String]*): String =
    fields.flatten.map(f => s"|$f").mkString

  def hmac256(data: String, secret: Array[Byte]): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding().encodeToString(mac.doFinal(data.getBytes("UTF-8")))
  }

  def safeEq(a: String, b: String): Boolean =
    if (a.length != b.length) false else a.zip(b).foldLeft(0)((z, p) => z | (p._1 ^ p._2)).==(0)
}
