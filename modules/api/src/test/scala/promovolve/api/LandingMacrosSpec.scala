package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pins the advertiser-facing {curly} attribution-macro contract on the
 * landing URL. Context only — no user/device macro exists (PromoVolve does
 * not track people); this just lets the advertiser see which ad drove a
 * click in their own analytics.
 */
class LandingMacrosSpec extends AnyWordSpec with Matchers {

  private val values = LandingMacros.valuesFor(
    source = "promovolve",
    campaignId = "01KY4SVN",
    creativeId = "01KXPVMY",
    site = "publisher-programmer-llc",
    category = "210",
    slot = "FOOD-SLOT-04"
  )

  "LandingMacros.substitute" should {

    "fill every documented macro" in {
      val url = "https://shop.com/deal?utm_source={source}&utm_campaign={campaign_id}" +
        "&cr={creative_id}&s={site}&cat={category}&sl={slot}"
      LandingMacros.substitute(url, values) shouldBe
      "https://shop.com/deal?utm_source=promovolve&utm_campaign=01KY4SVN" +
      "&cr=01KXPVMY&s=publisher-programmer-llc&cat=210&sl=FOOD-SLOT-04"
    }

    "leave a URL without macros untouched" in {
      val url = "https://shop.com/deal?utm_source=newsletter"
      LandingMacros.substitute(url, values) shouldBe url
    }

    "leave an unknown macro intact (typo stays visible, not blanked)" in {
      val url = "https://shop.com/deal?x={campaign_id}&y={unknown_macro}"
      LandingMacros.substitute(url, values) shouldBe
      "https://shop.com/deal?x=01KY4SVN&y={unknown_macro}"
    }

    "URL-encode values that need it" in {
      val v = LandingMacros.valuesFor("promovolve", "c", "cr", "site with space", "2 & 3", "slot")
      LandingMacros.substitute("https://x.com/?s={site}&c={category}", v) shouldBe
      "https://x.com/?s=site%20with%20space&c=2%20%26%203"
    }
  }
}
