package promovolve.publisher.assessment

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.publisher.AssessmentResult

import scala.concurrent.duration.*

class BatchAnthropicClientSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  def makeContext(creativeId: String): AssessmentContext =
    AssessmentContext(
      advertiserId = "adv-1",
      creativeId = creativeId,

      width = 300,
      height = 250
    )

  /**
   * Mock BatchAnthropicClient that captures requests and allows manual response injection.
   * This tests the protocol without actual HTTP calls.
   */
  object MockBatchClient {
    sealed trait Command
    case class Assess(
        creativeId: String,
        imageBytes: Array[Byte],
        mimeType: String,
        context: AssessmentContext,
        replyTo: ActorRef[BatchAnthropicClient.Response]
    ) extends Command
    case object FlushBatch extends Command

    // Test control commands
    case class GetPending(replyTo: ActorRef[List[String]]) extends Command
    case class SimulateSuccess(creativeId: String, result: AssessmentResult) extends Command
    case class SimulateFailure(creativeId: String, error: String) extends Command

    def apply(): Behavior[Command] = Behaviors.setup { _ =>
      var pending = Map.empty[String, ActorRef[BatchAnthropicClient.Response]]

      Behaviors.receiveMessage {
        case Assess(creativeId, _, _, _, replyTo) =>
          pending = pending + (creativeId -> replyTo)
          Behaviors.same

        case FlushBatch =>
          Behaviors.same

        case GetPending(replyTo) =>
          replyTo ! pending.keys.toList
          Behaviors.same

        case SimulateSuccess(creativeId, result) =>
          pending.get(creativeId).foreach { replyTo =>
            replyTo ! BatchAnthropicClient.AssessmentComplete(creativeId, result)
          }
          pending = pending - creativeId
          Behaviors.same

        case SimulateFailure(creativeId, error) =>
          pending.get(creativeId).foreach { replyTo =>
            replyTo ! BatchAnthropicClient.AssessmentFailed(creativeId, error)
          }
          pending = pending - creativeId
          Behaviors.same
      }
    }
  }

  val testResult: AssessmentResult = AssessmentResult(
    safetyScore = 0.95,
    qualityScore = 0.88,
    adultContent = false,
    violence = false,
    hateSpeech = false,
    detectedCategories = List("IAB1", "IAB3-1"),
    categoryConfidence = 0.85,
    suggestedCategory = Some("IAB1"),
    extractedText = Some("Buy Now!"),
    model = "test-model"
  )

  "BatchAnthropicClient protocol" should {

    "queue assessment requests" in {
      val mockClient = testKit.spawn(MockBatchClient())
      val probe = testKit.createTestProbe[BatchAnthropicClient.Response]()
      val pendingProbe = testKit.createTestProbe[List[String]]()

      // Send assessment request
      mockClient ! MockBatchClient.Assess(
        creativeId = "creative-1",
        imageBytes = "fake-image".getBytes,
        mimeType = "image/jpeg",
        context = makeContext("creative-1"),
        replyTo = probe.ref
      )

      // Verify it's queued
      mockClient ! MockBatchClient.GetPending(pendingProbe.ref)
      pendingProbe.expectMessage(List("creative-1"))
    }

    "route success response to correct caller" in {
      val mockClient = testKit.spawn(MockBatchClient())
      val probe1 = testKit.createTestProbe[BatchAnthropicClient.Response]()
      val probe2 = testKit.createTestProbe[BatchAnthropicClient.Response]()

      // Queue two requests with different callers
      mockClient ! MockBatchClient.Assess(
        "creative-1", "img1".getBytes, "image/jpeg", makeContext("creative-1"), probe1.ref
      )
      mockClient ! MockBatchClient.Assess(
        "creative-2", "img2".getBytes, "image/jpeg", makeContext("creative-2"), probe2.ref
      )

      // Simulate success for creative-1
      mockClient ! MockBatchClient.SimulateSuccess("creative-1", testResult)

      // Only probe1 should receive the response
      val response = probe1.expectMessageType[BatchAnthropicClient.AssessmentComplete]
      response.creativeId shouldBe "creative-1"
      response.result.safetyScore shouldBe 0.95

      // probe2 should not receive anything yet
      probe2.expectNoMessage(100.millis)
    }

    "route failure response to correct caller" in {
      val mockClient = testKit.spawn(MockBatchClient())
      val probe = testKit.createTestProbe[BatchAnthropicClient.Response]()

      mockClient ! MockBatchClient.Assess(
        "creative-fail", "img".getBytes, "image/jpeg", makeContext("creative-fail"), probe.ref
      )

      mockClient ! MockBatchClient.SimulateFailure("creative-fail", "API rate limited")

      val response = probe.expectMessageType[BatchAnthropicClient.AssessmentFailed]
      response.creativeId shouldBe "creative-fail"
      response.error shouldBe "API rate limited"
    }

    "handle multiple concurrent requests" in {
      val mockClient = testKit.spawn(MockBatchClient())
      val probes = (1 to 5).map(_ => testKit.createTestProbe[BatchAnthropicClient.Response]())

      // Queue 5 requests
      probes.zipWithIndex.foreach { case (probe, i) =>
        mockClient ! MockBatchClient.Assess(
          s"creative-$i", s"img$i".getBytes, "image/jpeg", makeContext(s"creative-$i"), probe.ref
        )
      }

      // Verify all queued
      val pendingProbe = testKit.createTestProbe[List[String]]()
      mockClient ! MockBatchClient.GetPending(pendingProbe.ref)
      val pending = pendingProbe.expectMessageType[List[String]]
      pending should have size 5

      // Complete all with success
      probes.zipWithIndex.foreach { case (probe, i) =>
        mockClient ! MockBatchClient.SimulateSuccess(s"creative-$i",
          testResult.copy(
            extractedText = Some(s"Text $i")
          ))
        val response = probe.expectMessageType[BatchAnthropicClient.AssessmentComplete]
        response.creativeId shouldBe s"creative-$i"
      }
    }
  }

  "BatchAnthropicClient.Response" should {

    "provide assessment result on success" in {
      val response: BatchAnthropicClient.Response =
        BatchAnthropicClient.AssessmentComplete("cr-1", testResult)

      response match {
        case BatchAnthropicClient.AssessmentComplete(id, result) =>
          id shouldBe "cr-1"
          result.safetyScore shouldBe 0.95
          result.detectedCategories shouldBe List("IAB1", "IAB3-1")
          result.categoryConfidence shouldBe 0.85
        case _ => fail("Expected AssessmentComplete")
      }
    }

    "provide error message on failure" in {
      val response: BatchAnthropicClient.Response =
        BatchAnthropicClient.AssessmentFailed("cr-1", "Batch processing timeout")

      response match {
        case BatchAnthropicClient.AssessmentFailed(id, error) =>
          id shouldBe "cr-1"
          error shouldBe "Batch processing timeout"
        case _ => fail("Expected AssessmentFailed")
      }
    }
  }

  "AssessmentResult from batch" should {

    "include all required fields" in {
      testResult.safetyScore shouldBe 0.95
      testResult.qualityScore shouldBe 0.88
      testResult.adultContent shouldBe false
      testResult.violence shouldBe false
      testResult.hateSpeech shouldBe false
      testResult.detectedCategories shouldBe List("IAB1", "IAB3-1")
      testResult.categoryConfidence shouldBe 0.85
      testResult.suggestedCategory shouldBe Some("IAB1")
      testResult.extractedText shouldBe Some("Buy Now!")
      testResult.model shouldBe "test-model"
    }

    "handle empty detected categories" in {
      val emptyResult = testResult.copy(
        detectedCategories = Nil,
        categoryConfidence = 0.3,
        suggestedCategory = None
      )

      emptyResult.detectedCategories shouldBe empty
      emptyResult.categoryConfidence shouldBe 0.3
      emptyResult.suggestedCategory shouldBe None
    }
  }
}
