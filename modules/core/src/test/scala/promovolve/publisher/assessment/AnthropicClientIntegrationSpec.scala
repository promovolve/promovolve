package promovolve.publisher.assessment

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Integration test for AnthropicClient.
  *
  * Run with: sbt "testOnly *AnthropicClientIntegrationSpec"
  *
  * Requires ANTHROPIC_API_KEY environment variable.
  * Skipped by default - remove @Ignore to run.
  */
//@Ignore  // Remove this to run integration tests
class AnthropicClientIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit()
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  val apiKey: Option[String] = sys.env.get("ANTHROPIC_API_KEY")

  "AnthropicClient" should {

    "assess a simple test image" in {
      assume(apiKey.isDefined, "ANTHROPIC_API_KEY not set - skipping integration test")

      val client = new AnthropicClient(apiKey.get, model = "claude-sonnet-4-20250514")

      // Load test image from resources
      val imageBytes = loadTestImage()

      val context = AssessmentContext(
        advertiserId = "test-adv",
        creativeId = "test-creative",
        
        width = 300,
        height = 250
      )

      val result = Await.result(
        client.assessCreative(imageBytes, "image/jpeg", context),
        30.seconds
      )

      // Basic sanity checks
      result.safetyScore should be >= 0.0
      result.safetyScore should be <= 1.0
      result.qualityScore should be >= 0.0
      result.qualityScore should be <= 1.0
      result.model shouldBe "claude-sonnet-4-20250514"

      println(s"Assessment result: $result")
    }

    "handle invalid API key gracefully" in {
      val client = new AnthropicClient("invalid-key")

      val imageBytes = loadTestImage()
      val context = AssessmentContext("adv", "cr", 300, 250)

      val exception = intercept[RuntimeException] {
        Await.result(
          client.assessCreative(imageBytes, "image/jpeg", context),
          30.seconds
        )
      }

      exception.getMessage should include("401")
    }
  }

  /** Load simba.jpg test image from resources */
  private def loadTestImage(): Array[Byte] = {
    val stream = getClass.getResourceAsStream("download-1.jpg")
    if (stream == null) {
      throw new RuntimeException("Test image simba.jpg not found in resources")
    }
    try stream.readAllBytes()
    finally stream.close()
  }
}