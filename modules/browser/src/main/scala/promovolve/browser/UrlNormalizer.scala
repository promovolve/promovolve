package promovolve.browser

import java.net.URI
import scala.util.Try

object UrlNormalizer:

  // Query params that identify a click/campaign, never page content — safe to
  // strip so referral variants of the same article collapse to one page.
  // Compared case-insensitively (keys are lowercased before lookup), so keep
  // these lowercase. Deliberately excludes ambiguous keys like `ref`/`id`
  // that some sites use functionally.
  private val TrackingParams: Set[String] = Set(
    // Google Analytics / GA4 UTM family
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "utm_id", "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
    // Google Ads / DoubleClick click ids (gclid + the newer privacy ones)
    "gclid", "gclsrc", "dclid", "wbraid", "gbraid", "gad_source",
    // Meta / Facebook / Instagram
    "fbclid", "igshid", "igsh",
    // Microsoft / Bing
    "msclkid",
    // TikTok, Twitter/X, Snapchat, Reddit, Yandex
    "ttclid", "twclid", "sccid", "rdt_cid", "yclid", "_openstat",
    // Mailchimp, HubSpot, Marketo (email marketing)
    "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok"
  )

  /**
   * Strip ONLY tracking params, preserving the path (incl. trailing slash),
   * host case, param order, and fragment EXACTLY. This is the right transform
   * for PAGE IDENTITY: `normalize` additionally canonicalizes slash/case/order
   * (fine for the single-flight dedup KEY), but applying that to identity
   * rewrites `/food/`→`/food` and orphans every already-classified page —
   * which took serving down 2026-07-24. Here `/food/` stays `/food/`; only a
   * `?fbclid=…`-style variant collapses onto its clean URL.
   */
  def stripTrackingParams(url: String): String = {
    val qIdx = url.indexOf('?')
    if (qIdx < 0) url
    else {
      val base = url.substring(0, qIdx)
      val afterQ = url.substring(qIdx + 1)
      val hashIdx = afterQ.indexOf('#')
      val (queryStr, fragment) =
        if (hashIdx >= 0) (afterQ.substring(0, hashIdx), afterQ.substring(hashIdx)) else (afterQ, "")
      val kept = queryStr
        .split("&")
        .filter(_.nonEmpty)
        .filterNot { p =>
          val k = p.takeWhile(_ != '=').toLowerCase
          TrackingParams.contains(k)
        }
      val newQuery = if (kept.isEmpty) "" else "?" + kept.mkString("&")
      base + newQuery + fragment
    }
  }

  def normalize(url: String): String =
    Try {
      val uri = new URI(url.trim)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("https")
      Option(uri.getHost).map(_.toLowerCase) match {
        case None       => url
        case Some(host) =>
          val port = uri.getPort match {
            case -1                       => ""
            case 80 if scheme == "http"   => ""
            case 443 if scheme == "https" => ""
            case p                        => s":$p"
          }
          val path = Option(uri.getRawPath) match {
            case None | Some("") => "/"
            case Some(p)         =>
              if (p.length > 1 && p.endsWith("/")) p.dropRight(1) else p
          }
          // Sort query params and strip tracking params
          val query = Option(uri.getRawQuery).map { q =>
            q.split("&")
              .filter(_.nonEmpty)
              .map { param =>
                val parts = param.split("=", 2)
                (parts(0), if (parts.length > 1) parts(1) else "")
              }
              .filterNot { case (key, _) => TrackingParams.contains(key.toLowerCase) }
              .sortBy(_._1)
              .map { case (k, v) => if (v.isEmpty) k else s"$k=$v" }
              .mkString("&")
          }.filter(_.nonEmpty).map("?" + _).getOrElse("")

          // Drop fragment
          s"$scheme://$host$port$path$query"
      }
    }.getOrElse(url)
