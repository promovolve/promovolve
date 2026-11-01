package promovolve.llm

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.creative.LPExtractor
import promovolve.publisher.assessment.CategoryVerificationClient
import promovolve.taxonomy.IABTaxonomy
import spray.json.*

/**
 * What each Gemini call site sends, recorded from the code before the
 * gateway refactor (#432). Moving request building into GeminiGateway must
 * leave every one of these unchanged: same JSON to the model, same answers.
 * Prompts are stubbed as "PROMPT" — prompt building stays in the call sites
 * and is not part of the move.
 */
class GeminiRequestGoldenSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit()
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  given ec: scala.concurrent.ExecutionContext = system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val safetySettings =
    """[{"category":"HARM_CATEGORY_HARASSMENT","threshold":"BLOCK_NONE"},
      | {"category":"HARM_CATEGORY_HATE_SPEECH","threshold":"BLOCK_NONE"},
      | {"category":"HARM_CATEGORY_SEXUALLY_EXPLICIT","threshold":"BLOCK_NONE"},
      | {"category":"HARM_CATEGORY_DANGEROUS_CONTENT","threshold":"BLOCK_NONE"}]""".stripMargin

  private def sameJson(actual: String, expected: String) = actual.parseJson shouldBe expected.parseJson

  "page classification (IABTaxonomy)" should {

    def request(model: String) =
      new IABTaxonomy(IABTaxonomy.Provider.Gemini("test-key", model))
        .buildGeminiRequest(IABTaxonomy.Provider.Gemini("test-key", model), "PROMPT")

    "send JSON mode, 4096 tokens and minimal thinking on 3.5" in {
      val (url, headers, body) = request("gemini-3.5-flash-lite")
      url shouldBe
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=test-key"
      headers shouldBe empty
      sameJson(body,
        """{"contents":[{"parts":[{"text":"PROMPT"}]}],
          | "generationConfig":{"temperature":0.1,"maxOutputTokens":4096,"responseMimeType":"application/json",
          |   "thinkingConfig":{"thinkingLevel":"minimal"}}}""".stripMargin)
    }

    "turn thinking off on 2.5" in {
      sameJson(request("gemini-2.5-flash")._3,
        """{"contents":[{"parts":[{"text":"PROMPT"}]}],
          | "generationConfig":{"temperature":0.1,"maxOutputTokens":4096,"responseMimeType":"application/json",
          |   "thinkingConfig":{"thinkingBudget":0}}}""".stripMargin)
    }
  }

  "campaign classification (CategoryVerificationClient)" should {

    "send text with safety filters off, JSON mode, 2048 tokens and minimal thinking" in {
      sameJson(CategoryVerificationClient.textRequestBody("PROMPT", "gemini-3.5-flash-lite"),
        s"""{"contents":[{"parts":[{"text":"PROMPT"}]}],
           | "safetySettings":$safetySettings,
           | "generationConfig":{"temperature":0.1,"maxOutputTokens":2048,"responseMimeType":"application/json",
           |   "thinkingConfig":{"thinkingLevel":"minimal"}}}""".stripMargin)
    }

    "send the image before the prompt" in {
      sameJson(CategoryVerificationClient.imageRequestBody("QUJD", "image/png", "PROMPT", "gemini-3.5-flash-lite"),
        s"""{"contents":[{"parts":[{"inline_data":{"mime_type":"image/png","data":"QUJD"}},{"text":"PROMPT"}]}],
           | "safetySettings":$safetySettings,
           | "generationConfig":{"temperature":0.1,"maxOutputTokens":2048,"responseMimeType":"application/json",
           |   "thinkingConfig":{"thinkingLevel":"minimal"}}}""".stripMargin)
    }
  }

  "landing-page extraction (LPExtractor)" should {

    "send only temperature and 8000 tokens: no JSON mode, no thinking setting" in {
      sameJson(GeminiGateway.requestBody("gemini-2.5-flash", Seq(GeminiGateway.Part.Text("PROMPT")),
        LPExtractor.config(0.7)),
        """{"contents":[{"parts":[{"text":"PROMPT"}]}],
          | "generationConfig":{"temperature":0.7,"maxOutputTokens":8000}}""".stripMargin)
      sameJson(GeminiGateway.requestBody("gemini-3.1-flash-lite-preview", Seq(GeminiGateway.Part.Text("PROMPT")),
        LPExtractor.config(0.95)),
        """{"contents":[{"parts":[{"text":"PROMPT"}]}],
          | "generationConfig":{"temperature":0.95,"maxOutputTokens":8000}}""".stripMargin)
    }
  }

  "reading Gemini's reply" should {

    "report the prompt, cached and output token counts" in {
      GeminiGateway.usage(
        """{"candidates":[],"usageMetadata":{"promptTokenCount":11693,"cachedContentTokenCount":11264,
          | "candidatesTokenCount":180,"totalTokenCount":11873}}""".stripMargin) shouldBe
      Some(GeminiGateway.Usage(prompt = 11693, cached = 11264, output = 180))
    }

    "count no cached tokens when Gemini leaves the field out" in {
      GeminiGateway.usage("""{"usageMetadata":{"promptTokenCount":11693,"candidatesTokenCount":180}}""") shouldBe
      Some(GeminiGateway.Usage(prompt = 11693, cached = 0, output = 180))
    }

    "give nothing for a reply without usage" in {
      GeminiGateway.usage("{}") shouldBe None
      GeminiGateway.usage("not json") shouldBe None
    }
  }
}
