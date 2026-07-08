package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CreativeGuardsSpec extends AnyWordSpec with Matchers {

  "CreativeGuards.landingUrlChanged" should {

    "allow a brand-new creative (no existing row)" in {
      CreativeGuards.landingUrlChanged(None, "https://example.com/lp") shouldBe false
    }

    "allow a re-save that keeps the same landing URL" in {
      CreativeGuards.landingUrlChanged(
        Some("https://example.com/lp"), "https://example.com/lp") shouldBe false
    }

    "reject a re-save that changes the landing URL" in {
      CreativeGuards.landingUrlChanged(
        Some("https://example.com/lp"), "https://evil.com/lp") shouldBe true
    }

    "treat a trailing-slash difference as a change (exact match, no normalization)" in {
      CreativeGuards.landingUrlChanged(
        Some("https://example.com/lp"), "https://example.com/lp/") shouldBe true
    }

    "reject clearing the landing URL to empty" in {
      CreativeGuards.landingUrlChanged(Some("https://example.com/lp"), "") shouldBe true
    }
  }
}
