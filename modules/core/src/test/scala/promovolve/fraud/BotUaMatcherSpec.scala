package promovolve.fraud

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BotUaMatcherSpec extends AnyWordSpec with Matchers {

  "BotUaMatcher" should {

    "flag self-declared automation" in {
      BotUaMatcher.isBot(Some("Mozilla/5.0 (compatible; Googlebot/2.1)")) shouldBe true
      BotUaMatcher.isBot(Some("curl/8.4.0")) shouldBe true
      BotUaMatcher.isBot(Some("python-requests/2.31.0")) shouldBe true
      BotUaMatcher.isBot(Some("Mozilla/5.0 HeadlessChrome/120.0")) shouldBe true
      BotUaMatcher.isBot(Some("Go-http-client/1.1")) shouldBe true
    }

    "flag absent or degenerate UAs" in {
      BotUaMatcher.isBot(None) shouldBe true
      BotUaMatcher.isBot(Some("")) shouldBe true
      BotUaMatcher.isBot(Some("Mozilla")) shouldBe true
    }

    "pass real browsers and webviews" in {
      BotUaMatcher.isBot(Some(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1")) shouldBe
      false
      BotUaMatcher.isBot(Some(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")) shouldBe
      false
      // In-app webview (Line, common in JP) — must not match.
      BotUaMatcher.isBot(Some(
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36 Line/14.0.0")) shouldBe
      false
    }

    "not substring-match 'bot' inside ordinary words" in {
      // \bbot\b guard: "Abbott" must not match.
      BotUaMatcher.isBot(Some(
        "Mozilla/5.0 (Windows NT 10.0) Chrome/126.0.0.0 Safari/537.36 AbbottKiosk/1.0")) shouldBe false
    }
  }
}
