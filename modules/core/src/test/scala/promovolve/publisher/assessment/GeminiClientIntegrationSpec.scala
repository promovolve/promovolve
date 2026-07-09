package promovolve.publisher.assessment

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Integration test for GeminiClient with real Gemini API.
 *
 * Requires:
 * - GEMINI_API_KEY environment variable
 * - Test image in resources
 *
 * Note: This test makes real API calls. Gemini is fast and cheap.
 */
class GeminiClientIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  def getApiKey: Option[String] = sys.env.get("GEMINI_API_KEY")

  def loadTestImage(): Array[Byte] = {
    val stream = getClass.getResourceAsStream("/ad.jpg")
    if (stream == null) {
      throw new RuntimeException("Test image ad.jpg not found in resources")
    }
    try stream.readAllBytes()
    finally stream.close()
  }

  "GeminiClient integration" should {

    "assess a real image using Gemini API" in {
      val apiKey = getApiKey.getOrElse {
        cancel("GEMINI_API_KEY not set, skipping integration test")
      }

      val imageBytes = try {
        loadTestImage()
      } catch {
        case e: RuntimeException =>
          cancel(s"Test image not available: ${e.getMessage}")
      }

      given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
      given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext

      val client = new GeminiClient(apiKey, "gemini-2.5-flash")

      val context = AssessmentContext(
        advertiserId = "adv-test",
        creativeId = "gemini-integration-test-1",
        width = 300,
        height = 250
      )

      println("Submitting assessment to Gemini...")
      val resultFuture = client.assessCreative(imageBytes, "image/jpeg", context)

      val result = Await.result(resultFuture, 60.seconds)

      println(s"Assessment complete:")
      println(s"  Model: ${result.model}")
      println(s"  Safety score: ${result.safetyScore}")
      println(s"  Quality score: ${result.qualityScore}")
      println(s"  Adult content: ${result.adultContent}")
      println(s"  Violence: ${result.violence}")
      println(s"  Hate speech: ${result.hateSpeech}")
      println(s"  Detected categories: ${result.detectedCategories}")
      println(s"  Category confidence: ${result.categoryConfidence}")
      println(s"  Suggested category: ${result.suggestedCategory}")
      println(s"  Extracted text: ${result.extractedText}")

      // Basic sanity checks
      result.safetyScore should be >= 0.0
      result.safetyScore should be <= 1.0
      result.qualityScore should be >= 0.0
      result.qualityScore should be <= 1.0
      result.categoryConfidence should be >= 0.0
      result.categoryConfidence should be <= 1.0
      result.model shouldBe "gemini-2.5-flash"
    }

    "assess with gemini-2.5-flash" in {
      val apiKey = getApiKey.getOrElse {
        cancel("GEMINI_API_KEY not set, skipping integration test")
      }

      val imageBytes = try {
        loadTestImage()
      } catch {
        case e: RuntimeException =>
          cancel(s"Test image not available: ${e.getMessage}")
      }

      given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
      given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext

      // Use 2.0-flash-exp
      val client = new GeminiClient(apiKey, model = "gemini-2.5-flash")

      val context = AssessmentContext(
        advertiserId = "adv-test",
        creativeId = "gemini-2.0-test-1",
        width = 728,
        height = 90
      )

      println("Submitting assessment to Gemini 2.0 Flash...")
      val resultFuture = client.assessCreative(imageBytes, "image/jpeg", context)

      val result = Await.result(resultFuture, 60.seconds)

      println(s"Assessment complete (gemini-2.5-flash):")
      println(s"  Safety score: ${result.safetyScore}")
      println(s"  Quality score: ${result.qualityScore}")
      println(s"  Detected categories: ${result.detectedCategories}")
      println(s"  Category confidence: ${result.categoryConfidence}")

      result.safetyScore should be >= 0.0
      result.safetyScore should be <= 1.0
      result.model shouldBe "gemini-2.5-flash"
    }
  }
}
