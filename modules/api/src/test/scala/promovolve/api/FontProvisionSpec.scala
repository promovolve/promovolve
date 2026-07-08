package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.publisher.assets.GoogleFontCatalog

/** Guards the publish-time font self-hosting inputs: catalog slug/weight
  * derivation (which decides what gets fetched + where it's stored) and
  * the extraction of (font family, weight) from the creative's pages JSON
  * (which decides what to provision). */
class FontProvisionSpec extends AnyWordSpec with Matchers {

  "GoogleFontCatalog.slugFor" should {
    "map an allow-listed family stack to its base R2 slug" in {
      GoogleFontCatalog.slugFor("Montserrat, sans-serif") shouldBe Some("montserrat")
      GoogleFontCatalog.slugFor("Playfair Display, serif") shouldBe Some("playfair-display")
    }
    "be case- and quote-insensitive on the primary family" in {
      GoogleFontCatalog.slugFor("\"DM Sans\", sans-serif") shouldBe Some("dm-sans")
      GoogleFontCatalog.slugFor("INTER") shouldBe Some("inter")
    }
    "match a weight-suffixed named instance to its base family" in {
      GoogleFontCatalog.slugFor("Montserrat Thin, sans-serif") shouldBe Some("montserrat")
      GoogleFontCatalog.slugFor("Montserrat Extra Bold") shouldBe Some("montserrat")
    }
    "match a variable-font family to its base (Montserrat Variable)" in {
      GoogleFontCatalog.slugFor("Montserrat Variable, ui-sans-serif, sans-serif") shouldBe Some("montserrat")
      GoogleFontCatalog.resolve("Montserrat Variable, sans-serif", Some(100)) shouldBe
        Some(("montserrat-100", 100, "Montserrat"))
    }
    "not strip a real multi-word family's tail" in {
      GoogleFontCatalog.slugFor("Open Sans") shouldBe Some("open-sans")
      GoogleFontCatalog.slugFor("DM Serif Display") shouldBe Some("dm-serif-display")
    }
    "return None for non-catalog families (system fallback)" in {
      GoogleFontCatalog.slugFor("Helvetica Neue, sans-serif") shouldBe None
      GoogleFontCatalog.slugFor("sans-serif") shouldBe None
    }
  }

  "GoogleFontCatalog.resolve" should {
    "use the weight parsed from a named instance over CSS weight" in {
      GoogleFontCatalog.resolve("Montserrat Thin, sans-serif", Some(700)) shouldBe
        Some(("montserrat-100", 100, "Montserrat"))
      GoogleFontCatalog.resolve("Montserrat Extra Bold") shouldBe
        Some(("montserrat-800", 800, "Montserrat"))
    }
    "fall back to the explicit CSS weight, then 400" in {
      GoogleFontCatalog.resolve("Poppins, sans-serif", Some(600)) shouldBe
        Some(("poppins-600", 600, "Poppins"))
      GoogleFontCatalog.resolve("Inter, sans-serif") shouldBe
        Some(("inter-400", 400, "Inter"))
    }
    "preserve canonical casing for the css2 family param" in {
      GoogleFontCatalog.resolve("\"DM Sans Medium\", sans-serif") shouldBe
        Some(("dm-sans-500", 500, "DM Sans"))
    }
    "ignore invalid CSS weights (round to 400)" in {
      GoogleFontCatalog.resolve("Lato, sans-serif", Some(123)) shouldBe Some(("lato-400", 400, "Lato"))
    }
    "return None for non-catalog families" in {
      GoogleFontCatalog.resolve("Helvetica Neue", Some(300)) shouldBe None
    }
  }

  "CreativeProcessor.fontsFromPagesJson" should {
    "collect (family, weight) from layout and EVERY banner bucket" in {
      val json =
        """[
          | {"layout":[
          |   {"type":"text","fontFamily":"Montserrat, sans-serif","fontWeight":"100","text":"Hi"},
          |   {"type":"image","src":"x","fontFamily":"Ignored"},
          |   {"type":"text","fontFamily":"sans-serif"}
          | ],
          |  "banners":{
          |    "mobile-expanded":[{"type":"text","fontFamily":"Poppins, sans-serif","fontWeight":600}],
          |    "300x250":[{"type":"text","fontFamily":"Noto Sans JP, sans-serif","fontWeight":700}]
          |  }}
          |]""".stripMargin
      CreativeProcessor.fontsFromPagesJson(json) shouldBe
        Set(
          ("Montserrat, sans-serif", Some(100)),
          ("sans-serif", None),
          ("Poppins, sans-serif", Some(600)),
          ("Noto Sans JP, sans-serif", Some(700)),
        )
    }
    "map bold/normal keywords and ignore junk weights" in {
      val json =
        """[{"layout":[
          | {"type":"text","fontFamily":"Inter, sans-serif","fontWeight":"bold"},
          | {"type":"text","fontFamily":"Lato, sans-serif","fontWeight":"lighter"}
          |]}]""".stripMargin
      CreativeProcessor.fontsFromPagesJson(json) shouldBe
        Set(("Inter, sans-serif", Some(700)), ("Lato, sans-serif", None))
    }
    "return empty on malformed JSON" in {
      CreativeProcessor.fontsFromPagesJson("not json") shouldBe empty
      CreativeProcessor.fontsFromPagesJson("{}") shouldBe empty
    }
  }

  "GoogleFontCatalog (no allow-list)" should {
    "derive a slug for ANY non-generic family — incl. ones never curated" in {
      GoogleFontCatalog.resolve("Noto Sans JP, sans-serif", Some(700)) shouldBe
        Some(("noto-sans-jp-700", 700, "Noto Sans JP"))
      GoogleFontCatalog.resolve("Zen Old Mincho, serif") shouldBe
        Some(("zen-old-mincho-400", 400, "Zen Old Mincho"))
      GoogleFontCatalog.resolve("M PLUS 1, sans-serif", Some(500)) shouldBe
        Some(("m-plus-1-500", 500, "M PLUS 1"))
    }
    "skip generic / system families (no css2 attempt)" in {
      GoogleFontCatalog.resolve("sans-serif") shouldBe None
      GoogleFontCatalog.resolve("Helvetica Neue, sans-serif") shouldBe None
      GoogleFontCatalog.resolve("Georgia, serif") shouldBe None
      GoogleFontCatalog.isGeneric("monospace") shouldBe true
      GoogleFontCatalog.isGeneric("montserrat") shouldBe false
    }
  }

  "GoogleFontCatalog.hasCjk" should {
    "detect JP/KR/CN text, not latin (drives latin vs text= subset)" in {
      GoogleFontCatalog.hasCjk("日本語") shouldBe true     // kanji
      GoogleFontCatalog.hasCjk("こんにちは") shouldBe true  // hiragana
      GoogleFontCatalog.hasCjk("カタカナ") shouldBe true    // katakana
      GoogleFontCatalog.hasCjk("한국어") shouldBe true      // hangul
      GoogleFontCatalog.hasCjk("Hello, ABC 123!") shouldBe false
      GoogleFontCatalog.hasCjk("") shouldBe false
    }
  }

  // These literals are computed by the banner's subsetKey (FNV-1a over the
  // UTF-8 of the sorted-unique-codepoint canonical string). Asserting the SAME
  // values here locks the server↔banner contract — if either drifts, the CJK
  // woff2 URL won't match and serving silently falls back to the system font.
  "GoogleFontCatalog.subsetKey" should {
    "match the banner's fixtures and be order/dup-insensitive" in {
      GoogleFontCatalog.subsetKey("日本語ABC") shouldBe "a42feb55"
      GoogleFontCatalog.subsetKey("ABC日本語") shouldBe "a42feb55" // sorted-unique → same
      GoogleFontCatalog.subsetKey("こんにちは世界") shouldBe "c78ba161"
      GoogleFontCatalog.subsetKey("") shouldBe "811c9dc5"          // FNV offset basis
      GoogleFontCatalog.subsetKey("A") shouldBe "c40bf6cc"
    }
  }

  "CreativeProcessor.subsetTextFromPagesJson" should {
    "concatenate headline/sub/body/tag/caption across pages" in {
      val json =
        """[
          | {"tag":"特集","headline":"日本語","sub":"sub","body":"本文","img":"x"},
          | {"headline":"H2","caption":"説明"}
          |]""".stripMargin
      val text = CreativeProcessor.subsetTextFromPagesJson(json)
      Seq("特集", "日本語", "本文", "H2", "説明").foreach(text should include(_))
      // non-content fields (img) are excluded
      text should not include "x"
    }
    "return empty on malformed JSON" in {
      CreativeProcessor.subsetTextFromPagesJson("nope") shouldBe ""
    }
    // Mirror contract with the banner's collectSubsetText — the same fixture
    // lives in banner-component/tests/font-catalog.test.ts. A size-specific
    // baked `text` override renders characters the page fields don't have;
    // both sides must include them or the CJK subset misses those glyphs.
    "include baked text-item overrides from layout AND banner buckets" in {
      val json =
        """[{"headline":"日本語",
          |  "layout":[{"type":"text","text":"上書き"},{"type":"image","src":"x"}],
          |  "banners":{"300x250":[{"type":"text","text":"別枠"}]}}]""".stripMargin
      val text = CreativeProcessor.subsetTextFromPagesJson(json)
      Seq("日本語", "上書き", "別枠").foreach(text should include(_))
      text should not include "x"
    }
  }

  "GoogleFontProvisioner.firstFontUrl" should {
    // The `text=` subset response (ALWAYS used for CJK) — the exact shape a
    // live css2 fetch returned. Its src has NO `.woff2` suffix; the old
    // `\.woff2`-anchored regex missed it, so no CJK font was ever provisioned.
    "extract the subset-delivery URL (/l/font?kit=...&v=vN, no .woff2)" in {
      val css =
        """@font-face {
          |  font-family: 'Noto Sans JP';
          |  font-style: normal;
          |  font-weight: 700;
          |  src: url(https://fonts.gstatic.com/l/font?kit=-F6jfjtqLzI2JPCgQBnw7HFyzSD-Asreg&skey=72472b0eb8793570&v=v56) format('woff2');
          |}""".stripMargin
      GoogleFontProvisioner.firstFontUrl(css) shouldBe
        Some("https://fonts.gstatic.com/l/font?kit=-F6jfjtqLzI2JPCgQBnw7HFyzSD-Asreg&skey=72472b0eb8793570&v=v56")
    }
    "still prefer the /* latin */ block for a full (non-subset) response" in {
      val css =
        """/* cyrillic */
          |@font-face { src: url(https://fonts.gstatic.com/s/x/cyr.woff2) format('woff2'); }
          |/* latin */
          |@font-face { src: url(https://fonts.gstatic.com/s/x/latin.woff2) format('woff2'); }""".stripMargin
      GoogleFontProvisioner.firstFontUrl(css) shouldBe
        Some("https://fonts.gstatic.com/s/x/latin.woff2")
    }
    "return None when no gstatic url is present" in {
      GoogleFontProvisioner.firstFontUrl("/* empty */") shouldBe None
    }
  }
}
