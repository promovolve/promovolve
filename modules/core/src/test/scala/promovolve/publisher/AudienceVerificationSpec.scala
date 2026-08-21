package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Tier 3 judging tier 2 — the rule that gives a publisher's audience
 * declaration teeth.
 *
 * Suppression is deliberately lenient (see AudienceVerification): wrongly
 * cutting an honest publisher's demand is worse than letting a marginal
 * claim through, because the advertiser has two other defences and the
 * publisher has none.
 */
class AudienceVerificationSpec extends AnyWordSpec with Matchers {

  private val Kamakura = "GN1860672" // Kamakura -> JP-14 -> JP

  /** An observation map clearing MinSample, split by the given shares. */
  private def observed(pairs: (String, Long)*): Map[String, Long] = pairs.toMap

  "countryOf" should {

    "resolve every level to a country" in {
      AudienceVerification.countryOf("JP") shouldBe Some("JP")
      AudienceVerification.countryOf("JP-14") shouldBe Some("JP")
      AudienceVerification.countryOf(Kamakura) shouldBe Some("JP")
    }

    "say nothing for an unknown code" in {
      AudienceVerification.countryOf("XX-99") shouldBe None
    }
  }

  "effectiveAudience" should {

    // Unverified is not refuted. A quiet site must not lose its demand for
    // being quiet — this is the difference between "we cannot check" and
    // "we checked and it is false".
    "leave the declaration untouched below the sample floor" in {
      AudienceVerification.effectiveAudience(Set("JP"), observed("US" -> 99)) shouldBe Set("JP")
      AudienceVerification.effectiveAudience(Set("JP"), Map.empty) shouldBe Set("JP")
    }

    "keep a declaration the observed traffic supports" in {
      AudienceVerification.effectiveAudience(Set("JP"), observed("JP" -> 900, "US" -> 100)) shouldBe Set("JP")
    }

    "keep a city declaration when its COUNTRY is observed" in {
      AudienceVerification.effectiveAudience(Set(Kamakura), observed("JP" -> 900, "US" -> 100)) shouldBe Set(Kamakura)
    }

    // The case this exists for: five markets claimed, one served.
    "drop declarations with essentially no traffic behind them" in {
      AudienceVerification.effectiveAudience(
        Set("JP", "US", "GB", "DE", "FR"),
        observed("JP" -> 980, "US" -> 20)
      ) shouldBe Set("JP")
    }

    "keep a real minority audience" in {
      // 10% is a genuine readership, not noise — a Japanese-language site
      // read partly by expats abroad still serves JP readers.
      AudienceVerification.effectiveAudience(Set("JP", "US"), observed("JP" -> 900, "US" -> 100)) shouldBe
      Set("JP", "US")
    }

    "drop a code the vocabulary no longer knows" in {
      AudienceVerification.effectiveAudience(Set("JP", "XX-99"), observed("JP" -> 1000)) shouldBe Set("JP")
    }
  }

  "suppressed" should {
    "name exactly what was dropped, for operator review" in {
      AudienceVerification.suppressed(
        Set("JP", "FR"), observed("JP" -> 1000)) shouldBe Set("FR")
    }
  }

  "isVerified" should {

    "be true when every declared place sits in an observed country" in {
      AudienceVerification.isVerified(Set("JP"), observed("JP" -> 1000)) shouldBe true
      AudienceVerification.isVerified(Set(Kamakura), observed("JP" -> 1000)) shouldBe true
    }

    "be false below the sample floor" in {
      AudienceVerification.isVerified(Set("JP"), observed("JP" -> 99)) shouldBe false
    }

    "be false when any declared place is unsupported" in {
      AudienceVerification.isVerified(Set("JP", "FR"), observed("JP" -> 1000)) shouldBe false
    }

    // Nothing declared is not "verified" — there is no claim to back, and
    // audience targeting does not match such a site anyway.
    "be false for a site that declared nothing" in {
      AudienceVerification.isVerified(Set.empty, observed("JP" -> 1000)) shouldBe false
    }
  }

  "verifiable-at-country-granularity" should {

    "accept country-only targeting" in {
      AudienceVerification.verifiableAtCountryGranularity(Set("JP", "FR")) shouldBe true
      AudienceVerification.verifiableAtCountryGranularity(Set.empty) shouldBe true
    }

    // Observation stops at country, so a finer target under
    // requireVerifiedAudience could never be satisfied. Rejecting at save
    // beats silently never serving.
    "reject anything finer than a country" in {
      AudienceVerification.verifiableAtCountryGranularity(Set("JP-14")) shouldBe false
      AudienceVerification.verifiableAtCountryGranularity(Set(Kamakura)) shouldBe false
      AudienceVerification.unverifiableTargets(Set("JP", "JP-14", Kamakura)) shouldBe Set("JP-14", Kamakura)
    }
  }
}
