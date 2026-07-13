package promovolve.publisher

import com.google.common.net.InternetDomainName

import scala.util.Try

/**
 * Registrable-domain (eTLD+1) normalization for auto-approve trust anchors.
 *
 * Trust is expressed at the brand level: shop.acme.com, www.acme.com and
 * acme.com all normalize to acme.com. Backed by Guava's bundled public
 * suffix list, so shared platforms that register themselves as public
 * suffixes (e.g. github.io) never collapse into a single trust anchor.
 *
 * Every trust-anchor site (write on manual approve, match at auto-approve,
 * delete on trust break) MUST go through this one normalizer — a mismatch
 * between any two of them silently breaks the feature.
 */
object RegistrableDomain {

  /**
   * eTLD+1 of a raw host as produced by URI.getHost (full host, may carry
   * a port). None for empty/invalid hosts, IP literals, and hosts that are
   * themselves a public suffix — those earn a campaign anchor only.
   */
  def of(host: String): Option[String] = {
    val cleaned = Option(host).getOrElse("").trim.toLowerCase.takeWhile(_ != ':')
    if (cleaned.isEmpty) None
    else
      Try {
        val idn = InternetDomainName.from(cleaned)
        if (idn.isUnderPublicSuffix) Some(idn.topPrivateDomain.toString) else None
      }.toOption.flatten
  }
}
