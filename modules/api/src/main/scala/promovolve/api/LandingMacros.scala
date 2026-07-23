package promovolve.api

/**
 * Serve-time substitution of `{curly}` attribution macros in an advertiser's
 * landing-page URL. This is the deliberate mirror-image of publisher-page URL
 * canonicalization: on the PUBLISHER page we strip tracking params (referral
 * noise); on the ADVERTISER's landing URL we FILL the macros the advertiser
 * chose to add, so their own analytics can attribute the visit to the ad.
 *
 * Privacy: context only — campaign / creative / site / category / slot. There
 * is deliberately NO macro that identifies the person or device; PromoVolve
 * does not track people across sites. This just tells the advertiser, in their
 * own tools, which of their ads drove a click.
 *
 * Values are filled from the trusted auction context (not client input), so an
 * advertiser can't spoof another's ids. Unknown `{…}` tokens are left intact so
 * a typo stays visible instead of silently blanking. Values are URL-encoded
 * because macros sit in query positions.
 */
object LandingMacros {

  /**
   * The macro → value map for one served impression. Public so it can back
   * both substitution and any advertiser-facing "available macros" help.
   */
  def valuesFor(
      source: String,
      campaignId: String,
      creativeId: String,
      site: String,
      category: String,
      slot: String
  ): Map[String, String] = Map(
    "{source}" -> source,
    "{campaign_id}" -> campaignId,
    "{creative_id}" -> creativeId,
    "{site}" -> site,
    "{category}" -> category,
    "{slot}" -> slot
  )

  def substitute(url: String, values: Map[String, String]): String =
    if (url.indexOf('{') < 0) url // fast path: no macros to fill
    else
      values.foldLeft(url) { case (acc, (token, value)) =>
        if (acc.contains(token)) acc.replace(token, encode(value)) else acc
      }

  private def encode(s: String): String =
    java.net.URLEncoder
      .encode(s, java.nio.charset.StandardCharsets.UTF_8)
      .replace("+", "%20") // %20 is valid in both query and path; "+" is not, in a path
}
