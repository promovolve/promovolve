package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pins the eTLD+1 normalization that every auto-approve trust-anchor
 * site (write, match, break) shares. A behavior change here silently
 * splits or merges publishers' trust anchors.
 */
class RegistrableDomainSpec extends AnyWordSpec with Matchers {

  "RegistrableDomain.of" should {

    "collapse subdomains to the registrable domain" in {
      RegistrableDomain.of("shop.acme.com") shouldBe Some("acme.com")
      RegistrableDomain.of("www.acme.com") shouldBe Some("acme.com")
      RegistrableDomain.of("acme.com") shouldBe Some("acme.com")
    }

    "respect multi-label public suffixes" in {
      RegistrableDomain.of("www.acme.co.uk") shouldBe Some("acme.co.uk")
      RegistrableDomain.of("acme.co.uk") shouldBe Some("acme.co.uk")
    }

    "keep private-registry suffixes apart (shared platforms)" in {
      // github.io is on the PSL's private section: each user site is its
      // own registrable domain, so one approval never trusts the platform.
      RegistrableDomain.of("alice.github.io") shouldBe Some("alice.github.io")
      RegistrableDomain.of("docs.alice.github.io") shouldBe Some("alice.github.io")
      RegistrableDomain.of("alice.github.io") should not be RegistrableDomain.of("bob.github.io")
    }

    "be case-insensitive and strip ports" in {
      RegistrableDomain.of("Shop.ACME.com") shouldBe Some("acme.com")
      RegistrableDomain.of("shop.acme.com:8443") shouldBe Some("acme.com")
    }

    "return None for hosts that cannot anchor domain trust" in {
      RegistrableDomain.of("") shouldBe None
      RegistrableDomain.of("   ") shouldBe None
      RegistrableDomain.of(null) shouldBe None
      RegistrableDomain.of("localhost") shouldBe None
      RegistrableDomain.of("192.168.0.1") shouldBe None
      RegistrableDomain.of("com") shouldBe None // bare public suffix
      RegistrableDomain.of("not a host") shouldBe None
    }
  }
}
