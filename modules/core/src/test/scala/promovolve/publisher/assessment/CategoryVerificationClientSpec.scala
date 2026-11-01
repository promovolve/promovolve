package promovolve.publisher.assessment

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.taxonomy.IABTaxonomy
import spray.json.*

class CategoryVerificationClientSpec extends AnyWordSpec with Matchers {

  private def contentCategoryIds(values: String*) =
    CategoryVerificationClient.contentCategoryIds(values.map(JsString(_)))

  // The prompt lists the taxonomy as "- 58: Business and Finance > …" and the
  // model sometimes echoes the whole line back. Stored as-is, that string is a
  // campaign category no page can ever match (#429).
  "contentCategoryIds" should {

    "keep plain taxonomy ids" in {
      contentCategoryIds("58", "479") shouldBe List("58", "479")
    }

    "strip a name echoed after the id" in {
      contentCategoryIds("58: Business and Finance > Business > Marketing and Advertising", " 479 : Travel")
        .shouldBe(List("58", "479"))
    }

    "drop strings that are not taxonomy ids" in {
      contentCategoryIds("category_id", "Travel", "999999") shouldBe Nil
    }

    "drop duplicates that differ only by the echoed name" in {
      contentCategoryIds("58", "58: Business and Finance") shouldBe List("58")
    }

    // soratobi on the eval came back as [653, 672, 663, …] (#429)
    "accept ids sent as JSON numbers" in {
      CategoryVerificationClient.contentCategoryIds(Seq(JsNumber(653), JsNumber(672))) shouldBe List("653", "672")
    }
  }

  // 2.5 models count thinking against maxOutputTokens: 1963 of 2048 went to
  // thinking on two eval pages and the JSON came back cut off (#429).
  "the Gemini thinking config" should {

    "turn thinking off for the 2.5 family" in {
      IABTaxonomy.geminiThinkingConfig("gemini-2.5-flash") shouldBe
      Some("thinkingConfig" -> JsObject("thinkingBudget" -> JsNumber(0)))
    }

    "use the minimal thinking level for the 3.5 family" in {
      IABTaxonomy.geminiThinkingConfig("gemini-3.5-flash-lite") shouldBe
      Some("thinkingConfig" -> JsObject("thinkingLevel" -> JsString("minimal")))
    }

    "send nothing for an unknown generation" in {
      IABTaxonomy.geminiThinkingConfig("gemini-9-pro") shouldBe None
    }

    "be part of the campaign classifier's text request" in {
      val body = CategoryVerificationClient.textRequestBody("prompt", "gemini-2.5-flash").parseJson.asJsObject
      body.fields("generationConfig").asJsObject.fields.get("thinkingConfig") shouldBe
      Some(JsObject("thinkingBudget" -> JsNumber(0)))
    }
  }
}
