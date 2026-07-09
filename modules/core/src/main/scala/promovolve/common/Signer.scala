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

  def hmac256(data: String, secret: Array[Byte]): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding().encodeToString(mac.doFinal(data.getBytes("UTF-8")))
  }

  def safeEq(a: String, b: String): Boolean =
    if (a.length != b.length) false else a.zip(b).foldLeft(0)((z, p) => z | (p._1 ^ p._2)).==(0)
}
