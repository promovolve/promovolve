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
   * Append optional binding fields to a canonical string in a fixed order.
   * The mint site and the verify site MUST pass the same fields in the same
   * order. Used to bind campaign / advertiser / cpm / requestId into the
   * tracking-token signature so a client can't rewrite them after serve
   * (e.g. redirecting a beacon's spend to a competitor's campaign). Pass cpm
   * as its exact URL string form so the signature is byte-identical without
   * a Double round-trip.
   *
   * The rendering is injective for a fixed arity: every field emits its own
   * `|` separator (absent ones included), present values are URL-encoded so
   * they cannot contain a literal `|`, and the `=` marker distinguishes
   * `Some("")` from `None` (`=` itself is never produced by the encoding).
   * Skipping absent fields or joining raw values would let a client shift
   * bytes across field boundaries — e.g. `camp=c|a` re-verifying as
   * `camp=c, adv=a` — and forge a beacon the mint never signed.
   */
  def bind(fields: Option[String]*): String =
    fields.map {
      case Some(v) => "|=" + java.net.URLEncoder.encode(v, "UTF-8")
      case None    => "|"
    }.mkString

  def hmac256(data: String, secret: Array[Byte]): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding().encodeToString(mac.doFinal(data.getBytes("UTF-8")))
  }

  def safeEq(a: String, b: String): Boolean =
    if (a.length != b.length) false else a.zip(b).foldLeft(0)((z, p) => z | (p._1 ^ p._2)).==(0)
}
