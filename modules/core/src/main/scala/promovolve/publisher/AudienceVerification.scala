package promovolve.publisher

import promovolve.taxonomy.{ PlaceKind, Places }

/**
 * The rule that turns an unverifiable claim into a checkable one — tier 3
 * of docs/design/GEOGRAPHIC_CONTEXT.md judging tier 2.
 *
 * A publisher gains demand by declaring an audience, so the claim is
 * interested. The quality-adjusted auction reduces what an inflated claim
 * EARNS but never makes it unprofitable — the site still went from none of
 * that demand to some of it, and the advertiser eats the difference. So the
 * auction is not the answer; suppression is.
 *
 * Deliberately lenient. Wrongly suppressing an honest publisher is worse
 * than letting a marginal claim through, because the advertiser has two
 * other defences (the blocklist, and `requireVerifiedAudience`) and the
 * publisher has none. This catches the site claiming five markets it has no
 * readers in, not the one whose traffic drifted.
 */
object AudienceVerification {

  /**
   * Observations in the window before the distribution is evidence.
   *
   * Two independent reasons, and the second must not be traded away for
   * coverage: a handful of observations is statistically meaningless, AND a
   * distribution over three readers approaches a per-reader disclosure.
   * Mirrors the MinSample discipline the market-rates work already uses.
   */
  val MinSample: Long = 100L

  /**
   * Share of the window a country must reach for a declaration naming it to
   * survive.
   *
   * 5% is low on purpose. A real audience that happens to be a minority of
   * pageviews is still a real audience — a Japanese-language site read
   * mostly by expats abroad genuinely serves JP readers. What this cuts is
   * the claim with essentially no traffic behind it.
   */
  val MinShare: Double = 0.05

  /**
   * The country a declared code belongs to: itself for a country, its
   * country ancestor for a subdivision or city.
   *
   * Everything above resolves to country granularity because that is all
   * the observation data has. A city declaration can be checked as far as
   * "are these readers even in the right country" and no further — stated
   * plainly rather than papered over.
   */
  def countryOf(code: String): Option[String] =
    Places.get(code).flatMap { p =>
      if (p.kind == PlaceKind.Country) Some(p.code)
      else Places.ancestors(code).find(_.kind == PlaceKind.Country).map(_.code)
    }

  /** Countries holding at least [[MinShare]] of the window. */
  def observedCountries(observed: Map[String, Long]): Set[String] = {
    val total = observed.valuesIterator.sum
    if (total <= 0) Set.empty
    else observed.collect { case (cc, n) if n.toDouble / total.toDouble >= MinShare => cc }.toSet
  }

  /**
   * The declaration as the auction should read it.
   *
   * Below [[MinSample]] the declaration stands untouched — unverified is
   * not the same as refuted, and a quiet site must not lose its demand for
   * being quiet. Above it, a declared place whose country carries no
   * meaningful share is dropped.
   *
   * A code the vocabulary no longer knows is dropped too: it can never be
   * matched or verified, so keeping it would only inflate the set.
   */
  def effectiveAudience(declared: Set[String], observed: Map[String, Long]): Set[String] = {
    val total = observed.valuesIterator.sum
    if (total < MinSample) declared
    else {
      val live = observedCountries(observed)
      declared.filter(code => countryOf(code).exists(live.contains))
    }
  }

  /** Declared places dropped by [[effectiveAudience]] — for operator review. */
  def suppressed(declared: Set[String], observed: Map[String, Long]): Set[String] =
    declared -- effectiveAudience(declared, observed)

  /**
   * Is this site's declaration backed by observation?
   *
   * True only when the sample clears the floor AND every surviving declared
   * place sits in an observed country. This is what
   * `requireVerifiedAudience` buys: an advertiser opting in trades reach for
   * the guarantee that something measured stands behind the claim.
   *
   * A site that declared nothing is never "verified" — there is no claim to
   * back, and audience targeting does not match it anyway.
   */
  def isVerified(declared: Set[String], observed: Map[String, Long]): Boolean = {
    val total = observed.valuesIterator.sum
    declared.nonEmpty && total >= MinSample && effectiveAudience(declared, observed) == declared
  }

  /**
   * Can a targeting set ever be satisfied under `requireVerifiedAudience`?
   *
   * No, when it names anything finer than a country: observation stops at
   * country granularity, so a city or subdivision target can never be
   * backed by measurement. Matching anyway would quietly deliver something
   * weaker than the advertiser asked for, so the campaign is rejected at
   * save instead — a clear error beats silent under-delivery.
   */
  def verifiableAtCountryGranularity(targeting: Set[String]): Boolean =
    targeting.forall(code => Places.get(code).forall(_.kind == PlaceKind.Country))

  /** Targets that make `requireVerifiedAudience` unsatisfiable. */
  def unverifiableTargets(targeting: Set[String]): Set[String] =
    targeting.filter(code => Places.get(code).exists(_.kind != PlaceKind.Country))
}
