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

    "flag crawlers the maintained list names but the hand-written terms miss" in {
      BotUaMatcher.isBot(Some("Mediapartners-Google")) shouldBe true
      BotUaMatcher.isBot(Some("meta-externalagent/1.1")) shouldBe true
      BotUaMatcher.isBot(Some(
        "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 (compatible; Google-InspectionTool/1.0)")) shouldBe
      true
    }

    "flag every example UA the maintained list ships" in {
      val source = scala.io.Source.fromResource("fraud/crawler-user-agents.json", getClass.getClassLoader)
      val examples =
        try {
          import spray.json.*
          source.mkString.parseJson.asInstanceOf[JsArray].elements.map(_.asJsObject.fields)
            .filterNot(f => f.get("pattern").exists { case JsString(p) => BotUaMatcher.Excluded(p); case _ => false })
            .flatMap(_.get("instances")).flatMap {
              case JsArray(xs) => xs.collect { case JsString(ua) => ua }
              case _           => Nil
            }
        } finally source.close()
      examples.size should be > 2000
      BotUaMatcher.MaintainedPatterns.size shouldBe 1502
      examples.filterNot(ua => BotUaMatcher.isBot(Some(ua))) shouldBe empty
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
      // More in-app webviews readers arrive through.
      Seq(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 357.0.0.25.101 (iPhone15,2; iOS 18_1; ja_JP; ja; scale=3.00; 1179x2556; 658257832)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/489.0.0.38.109;FBBV/658257832;FBDV/iPhone15,2;FBMD/iPhone;FBSN/iOS;FBSV/18.1;FBSS/3;FBID/phone;FBLC/ja_JP;FBOP/5]",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240905.003; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.39 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/489.0.0.53.109;]",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Twitter for iPhone/10.70",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/343.0.695551749 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1 YJApp-IOS jp.co.yahoo.ipn.appli/4.56.0",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 SmartNews/24.10.0 (iPhone; iOS 18.1)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.50(0x1800323a) NetType/WIFI Language/zh_CN",
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36",
        // The upstream list's three excluded entries, each on a real reader.
        "Mozilla/5.0 (Linux; Android 15; CPH2557 Build/AP3A.240617.008; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.142 Mobile Safari/537.36 Instagram 406.0.0.58.159 Android (35/15; 480dpi; 1080x2400; OPPO; CPH2557; OP573DL1; mt6833; en_MY; 822918295; IABMV/1) NV/1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Viber/24.9.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Code/1.115.0 Chrome/142.0.7444.265 Electron/39.8.5 Safari/537.36"
      ).filter(ua => BotUaMatcher.isBot(Some(ua))) shouldBe empty
    }

    "not substring-match 'bot' inside ordinary words" in {
      // \bbot\b guard: "Abbott" must not match.
      BotUaMatcher.isBot(Some(
        "Mozilla/5.0 (Windows NT 10.0) Chrome/126.0.0.0 Safari/537.36 AbbottKiosk/1.0")) shouldBe false
    }
  }
}
