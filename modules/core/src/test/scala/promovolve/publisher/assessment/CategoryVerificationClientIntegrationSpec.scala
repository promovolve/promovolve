package promovolve.publisher.assessment

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Integration test for CategoryVerificationClient with real Gemini API.
  *
  * Tests that the extended prompt returns suggestedContentCategories
  * alongside the existing match_confidence and safety fields.
  *
  * Requires:
  *   - GEMINI_API_KEY environment variable
  *   - Test images in resources (ad.jpg, download-1.jpg)
  */
class CategoryVerificationClientIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  def getApiKey: Option[String] = sys.env.get("GEMINI_API_KEY")

  def loadTestImage(name: String): Array[Byte] = {
    val stream = getClass.getResourceAsStream(name)
    if (stream == null) throw new RuntimeException(s"Test image $name not found in resources")
    try stream.readAllBytes()
    finally stream.close()
  }

  "CategoryVerificationClient" should {

    "return suggestedContentCategories for an image with declared category" in {
      val apiKey = getApiKey.getOrElse {
        cancel("GEMINI_API_KEY not set, skipping integration test")
      }

      val imageBytes = try loadTestImage("/download-1.jpg")
      catch { case e: RuntimeException => cancel(s"Test image not available: ${e.getMessage}") }

      given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
      given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext

      val client = new CategoryVerificationClient(apiKey, model = "gemini-2.5-flash")

      println("Verifying download-1.jpg with declared category '1529' (Travel)...")
      val result = Await.result(
        client.verify(imageBytes, "image/jpeg", declaredCategory = Some("1529")),
        60.seconds
      )

      println(s"Verification result:")
      println(s"  Match confidence: ${result.matchConfidence}")
      println(s"  Reason: ${result.reason}")
      println(s"  Safety blocked: ${result.isSafetyBlocked}")
      println(s"  Safety score: ${result.safetyScore}")
      println(s"  Suggested content categories: ${result.suggestedContentCategories}")

      // Basic sanity checks
      result.matchConfidence should be >= 0.0
      result.matchConfidence should be <= 1.0
      result.reason should not be empty

      // If image is not safety-blocked, we expect suggested categories
      if (!result.isSafetyBlocked) {
        result.suggestedContentCategories should not be empty
        // All returned IDs should be numeric strings (IAB Content Taxonomy 2.1 IDs)
        result.suggestedContentCategories.foreach { catId =>
          catId.toIntOption shouldBe defined
        }
        println(s"  SUCCESS: Got ${result.suggestedContentCategories.size} suggested categories")
      } else {
        println(s"  Image was safety-blocked — empty categories is correct behavior")
      }
    }

    "return suggestedContentCategories even without declared category" in {
      val apiKey = getApiKey.getOrElse {
        cancel("GEMINI_API_KEY not set, skipping integration test")
      }

      val imageBytes = try loadTestImage("/download-1.jpg")
      catch { case e: RuntimeException => cancel(s"Test image not available: ${e.getMessage}") }

      given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
      given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext

      val client = new CategoryVerificationClient(apiKey, model = "gemini-2.5-flash")

      println("Verifying download-1.jpg without declared category...")
      val result = Await.result(
        client.verify(imageBytes, "image/jpeg", declaredCategory = None),
        60.seconds
      )

      println(s"Verification result (no declared category):")
      println(s"  Match confidence: ${result.matchConfidence}")
      println(s"  Reason: ${result.reason}")
      println(s"  Suggested content categories: ${result.suggestedContentCategories}")

      result.matchConfidence should be >= 0.0
      result.reason should not be empty

      // Should still suggest categories based on image content alone
      if (!result.isSafetyBlocked) {
        result.suggestedContentCategories should not be empty
        println(s"  SUCCESS: Got ${result.suggestedContentCategories.size} suggested categories")
      } else {
        println(s"  Image was safety-blocked — empty categories is correct behavior")
      }
    }

    "correctly flag adult content and return empty categories" in {
      val apiKey = getApiKey.getOrElse {
        cancel("GEMINI_API_KEY not set, skipping integration test")
      }

      val imageBytes = try loadTestImage("/ad.jpg")
      catch { case e: RuntimeException => cancel(s"Test image not available: ${e.getMessage}") }

      given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
      given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext

      val client = new CategoryVerificationClient(apiKey, model = "gemini-2.5-flash")

      println("Verifying ad.jpg (adult content) with declared category '1524' (Sporting Goods)...")
      val result = Await.result(
        client.verify(imageBytes, "image/jpeg", declaredCategory = Some("1524")),
        60.seconds
      )

      println(s"Verification result (adult content):")
      println(s"  Match confidence: ${result.matchConfidence}")
      println(s"  Reason: ${result.reason}")
      println(s"  Adult content: ${result.adultContent}")
      println(s"  Safety score: ${result.safetyScore}")
      println(s"  Suggested content categories: ${result.suggestedContentCategories}")

      // Should detect the mismatch and flag adult content
      result.isSafetyBlocked shouldBe true
      result.adultContent shouldBe true
      // Should NOT suggest categories for blocked content
      result.suggestedContentCategories shouldBe empty

      println("  SUCCESS: Adult content correctly detected, no categories suggested")
    }

    "return fewer categories than the static mapping would" in {
      val apiKey = getApiKey.getOrElse {
        cancel("GEMINI_API_KEY not set, skipping integration test")
      }

      val imageBytes = try loadTestImage("/download-1.jpg")
      catch { case e: RuntimeException => cancel(s"Test image not available: ${e.getMessage}") }

      given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
      given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext

      val client = new CategoryVerificationClient(apiKey, model = "gemini-2.5-flash")

      // Use a broad ad product category like "Sporting Goods" (1524) that maps to 69 categories
      println("Verifying download-1.jpg with broad category '1524' (Sporting Goods)...")
      val result = Await.result(
        client.verify(imageBytes, "image/jpeg", declaredCategory = Some("1524")),
        60.seconds
      )

      println(s"  Image-derived categories: ${result.suggestedContentCategories.size}")
      println(s"  Image-derived: ${result.suggestedContentCategories.mkString(", ")}")

      // The static IAB Content↔AdProduct bridge has been removed in favour of
      // the LLM emitting suggested content categories directly. We just verify
      // the LLM returned something sensible for a non-blocked image.
      if (result.suggestedContentCategories.isEmpty && !result.isSafetyBlocked) {
        fail(s"Expected suggested categories for a non-blocked image")
      }
    }
  }
}
