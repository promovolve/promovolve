package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.browser.UrlNormalizer

/**
 * Pure pieces of the on-demand (crawl-free) classification change:
 * the single-flight + readiness decision precedence, and that the
 * single-flight key (a normalized url) collapses tracking-param variants
 * of the same page onto one key — so a story's traffic burst fires ONE
 * Gemini call. The live serve-miss -> ClassifyUrl -> auctioneer loop needs
 * the cluster; that's verified at run time. See
 * docs/design/ON_DEMAND_CLASSIFICATION.md.
 */
class OnDemandClassificationSpec extends AnyWordSpec with Matchers {

  import SiteEntity.ClassifyDecision
  import SiteEntity.ClassifyDecision.{ Accept, InFlight, NotReady }

  "ClassifyDecision.decide" should {

    "accept a fresh request when the site is ready" in {
      ClassifyDecision.decide(alreadyPending = false, ready = true) shouldBe Accept
      Accept.accepted shouldBe true
      Accept.reason shouldBe "accepted"
    }

    "coalesce a request already in flight (no second Gemini call)" in {
      ClassifyDecision.decide(alreadyPending = true, ready = true) shouldBe InFlight
      InFlight.accepted shouldBe false
      InFlight.reason shouldBe "in_flight"
    }

    "reject when the site is not ready (no assistant / demand categories)" in {
      ClassifyDecision.decide(alreadyPending = false, ready = false) shouldBe NotReady
      NotReady.accepted shouldBe false
      NotReady.reason shouldBe "not_ready"
    }

    "let pending take precedence over not-ready" in {
      // A coalesced request must never report not_ready — the in-flight
      // call is what determines readiness, and it already passed the gate.
      ClassifyDecision.decide(alreadyPending = true, ready = false) shouldBe InFlight
    }
  }

  "the single-flight key (normalized url)" should {

    "collapse tracking-param variants of the same page onto one key" in {
      val a = UrlNormalizer.normalize("https://news.example.com/sports/game?utm_source=twitter")
      val b = UrlNormalizer.normalize("https://news.example.com/sports/game?utm_source=email")
      a shouldBe b
    }

    "keep genuinely different pages on different keys" in {
      val sports = UrlNormalizer.normalize("https://news.example.com/sports/game")
      val finance = UrlNormalizer.normalize("https://news.example.com/finance/rates")
      sports should not be finance
    }

    "preserve content-bearing query params (not strip-all)" in {
      // ?p= selects the article on classic CMS urls — collapsing these would
      // serve one page's categories to every article. Must stay distinct.
      val one = UrlNormalizer.normalize("https://blog.example.com/?p=123")
      val two = UrlNormalizer.normalize("https://blog.example.com/?p=456")
      one should not be two
    }

    "strip every known click/campaign tracker" in {
      val clean = "https://news.example.com/food/farm-to-table.html"
      val trackers = Seq(
        "fbclid=IwY2xjawTN", "gclid=abc", "gclsrc=aw.ds", "dclid=xyz",
        "wbraid=w1", "gbraid=g1", "gad_source=1", "msclkid=m1",
        "ttclid=t1", "twclid=tw1", "igshid=ig1", "yclid=y1",
        "mc_cid=c1", "mc_eid=e1", "_hsenc=h1", "mkt_tok=mt1",
        "utm_source=fb", "utm_id=42"
      )
      trackers.foreach { p =>
        withClue(s"param $p should be stripped: ") {
          UrlNormalizer.normalize(s"$clean?$p") shouldBe clean
        }
      }
    }

    "strip trackers but keep a content param alongside them" in {
      // ?id= is content; ?fbclid= is a Facebook referral tag. Keep the first,
      // drop the second — so a shared-on-Facebook article is one page.
      val withTracker = UrlNormalizer.normalize("https://shop.example.com/p?id=99&fbclid=IwABC")
      val cleanOnly = UrlNormalizer.normalize("https://shop.example.com/p?id=99")
      withTracker shouldBe cleanOnly
    }
  }
}
