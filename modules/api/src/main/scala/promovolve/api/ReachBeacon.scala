package promovolve.api

import promovolve.common.Signer

/**
 * The signed reach beacon URL (GH #238), minted by ServeRoutes and checked
 * by TrackRoutes. Same canonical HMAC as the impression with event "reach";
 * the advertiser-local day rides in the bound field the impression uses for
 * cpm, so it can't be rewritten. `prev` and `fresh` are appended by the tag
 * and are browser-reported, so deliberately unsigned.
 */
object ReachBeacon {

  private def data(
      pub: String, url: String, slot: String, cid: String, ver: Long, b: Long,
      camp: String, adv: String, day: Long, rid: String
  ): String =
    Signer.canonical(pub, url, slot, cid, ver, b, "reach") +
      Signer.bind(Some(camp), Some(adv), Some(day.toString), Some(rid))

  def url(
      trackingBase: String, secret: Array[Byte],
      pub: String, url: String, slot: String, cid: String, ver: Long, b: Long,
      camp: String, adv: String, day: Long, rid: String
  ): String = {
    val tok = Signer.hmac256(data(pub, url, slot, cid, ver, b, camp, adv, day, rid), secret)
    val encU = java.net.URLEncoder.encode(url, "UTF-8")
    s"$trackingBase/reach?pub=$pub&url=$encU&slot=$slot&cid=$cid&v=$ver&b=$b&tok=$tok" +
      s"&camp=$camp&adv=$adv&day=$day&rid=$rid"
  }

  /** Signature only; the caller also checks the time bucket. */
  def verify(
      secret: Array[Byte], tok: String,
      pub: String, url: String, slot: String, cid: String, ver: Long, b: Long,
      camp: String, adv: String, day: Long, rid: String
  ): Boolean =
    Signer.safeEq(Signer.hmac256(data(pub, url, slot, cid, ver, b, camp, adv, day, rid), secret), tok)
}
