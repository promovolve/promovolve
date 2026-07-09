package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pins the helper that extracts user-authored text from the
 * Designer's pagesJson for feeding to Gemini's `verifyText`. This is
 * the bridge between the Designer's audience-signal chip and the
 * server's category classifier — if it returns "", the Designer's
 * suggested-words guidance never reaches the model.
 */
class CreativeProcessorTextSpec extends AnyWordSpec with Matchers {

  "CreativeProcessor.textFromPagesJson" should {

    "concatenate headline, sub, body, caption, tag, ctaLabel from each page" in {
      val json =
        """[
          |  {"tag":"FEATURE","headline":"Fitness That Adapts","sub":"Pilates studio","body":"Train smarter at our gym","caption":"Walk-in welcome","ctaLabel":"Learn more","accent":"#000","bg":"black","imgEmoji":"💪"},
          |  {"tag":"CTA","headline":"Book a class","sub":"","body":"","caption":"","ctaLabel":"Start","accent":"#000","bg":"black","imgEmoji":"📅"}
          |]""".stripMargin
      val out = CreativeProcessor.textFromPagesJson(json)
      out should include("Fitness That Adapts")
      out should include("Pilates studio")
      out should include("Train smarter")
      out should include("Walk-in welcome")
      out should include("Learn more")
      out should include("Book a class")
      out should include("FEATURE")
      out should include("CTA")
    }

    "skip empty string fields silently" in {
      val json = """[{"headline":"Pilates","sub":"","body":"Wellness focused","caption":"","tag":"","ctaLabel":""}]"""
      val out = CreativeProcessor.textFromPagesJson(json)
      out shouldBe "Pilates Wellness focused"
    }

    "return empty string for unparseable JSON" in {
      CreativeProcessor.textFromPagesJson("not json at all") shouldBe ""
      CreativeProcessor.textFromPagesJson("") shouldBe ""
      CreativeProcessor.textFromPagesJson("{not array}") shouldBe ""
      // Object instead of array — schema mismatch.
      CreativeProcessor.textFromPagesJson("""{"foo":"bar"}""") shouldBe ""
    }

    "tolerate missing fields without crashing" in {
      // Pages with no text fields at all (e.g. image-only pages).
      val json = """[{"img":"https://example.com/x.jpg","accent":"#fff","bg":"white","imgEmoji":""}]"""
      val out = CreativeProcessor.textFromPagesJson(json)
      out shouldBe ""
    }

    "join multiple pages with a space, not a newline" in {
      // We feed this to a prompt that already has its own structure;
      // keeping it one-line keeps the prompt clean.
      val json = """[{"headline":"A"},{"headline":"B"}]"""
      val out = CreativeProcessor.textFromPagesJson(json)
      out shouldBe "A B"
    }

    "trim whitespace around individual fields" in {
      val json = """[{"headline":"  Pilates  ","sub":"\nWellness\n"}]"""
      val out = CreativeProcessor.textFromPagesJson(json)
      out shouldBe "Pilates Wellness"
    }
  }
}
