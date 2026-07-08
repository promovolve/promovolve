package promovolve.publisher.assessment

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{AdProductCategoryId, CategoryId}
import promovolve.publisher.{AssessmentResult, CreativeMeta, CreativeMetadataRepo}

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, duration}
import scala.concurrent.duration.*

/** Integration test for BatchCreativeAssessor with real Anthropic API.
  *
  * Requires:
  * - ANTHROPIC_API_KEY environment variable
  * - Test image in resources (download-1.jpg)
  *
  * Note: This test makes real API calls and may take several minutes
  * due to batch processing latency.
  */
class BatchCreativeAssessorIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  def getApiKey: Option[String] = sys.env.get("ANTHROPIC_API_KEY")

  def loadTestImage(): Array[Byte] = {
    val stream = getClass.getResourceAsStream("/ad.jpg")
    if (stream == null) {
      throw new RuntimeException("Test image download-1.jpg not found in resources")
    }
    try stream.readAllBytes()
    finally stream.close()
  }

  /** In-memory repo for testing */
  class TestMetaRepo extends CreativeMetadataRepo {
    private val store = mutable.Map.empty[String, CreativeMeta]
    private val hashIndex = mutable.Map.empty[String, String]
    val updates = mutable.ListBuffer.empty[(String, AssessmentResult, CategoryVerification.VerificationResult)]

    def put(meta: CreativeMeta): Future[Unit] = {
      store.put(meta.creativeId, meta)
      if (!hashIndex.contains(meta.hash)) {
        hashIndex.put(meta.hash, meta.creativeId)
      }
      Future.successful(())
    }

    def get(creativeId: String): Future[Option[CreativeMeta]] =
      Future.successful(store.get(creativeId))
    def getByHash(hash: String): Future[Option[CreativeMeta]] =
      Future.successful(hashIndex.get(hash).flatMap(store.get))
    def updateAssessment(creativeId: String, result: AssessmentResult): Future[Unit] =
      Future.successful(())
    def updateAssessmentWithVerification(
        creativeId: String,
        result: AssessmentResult,
        verification: CategoryVerification.VerificationResult
    ): Future[Unit] = {
      updates += ((creativeId, result, verification))
      store.get(creativeId).foreach { existing =>
        store.put(creativeId, existing.copy(
          assessedAt = Some(Instant.now()),
          assessmentStatus = "assessed",
          safetyScore = Some(result.safetyScore),
          qualityScore = Some(result.qualityScore),
          detectedCategories = result.detectedCategories.mkString(","),
          categoryConfidence = Some(result.categoryConfidence),
          categoryVerificationStatus = Some(verification.statusString)
        ))
      }
      Future.successful(())
    }

    def markAssessmentFailed(creativeId: String, error: String): Future[Unit] = {
      store.get(creativeId).foreach { existing =>
        store.put(creativeId, existing.copy(
          assessedAt = Some(Instant.now()),
          assessmentStatus = "failed",
          assessmentError = Some(error),
          safetyScore = Some(0.5),
          qualityScore = Some(0.5),
          categoryConfidence = Some(0.0)
        ))
      }
      Future.successful(())
    }
  }

  def makeMeta(creativeId: String): CreativeMeta = CreativeMeta(
    advertiserId = "adv-test",
    creativeId = creativeId,
    s3Key = s"test/$creativeId/image.jpg",
    hash = s"hash-$creativeId",
    mime = "image/jpeg",
    width = 300,
    height = 250,
    landingDomain = "example.com"
  )

  "BatchCreativeAssessor integration" should {

    "assess a real image using Anthropic batch API" in {
      val apiKey = getApiKey.getOrElse {
        cancel("ANTHROPIC_API_KEY not set, skipping integration test")
      }

      val imageBytes = try {
        loadTestImage()
      } catch {
        case e: RuntimeException =>
          cancel(s"Test image not available: ${e.getMessage}")
      }

      val repo = new TestMetaRepo()
      val responseProbe = testKit.createTestProbe[BatchCreativeAssessor.Response]()

      // Use short intervals for testing
      val config = BatchAnthropicClient.Config(
        model = "claude-sonnet-4-20250514",
        batchThreshold = 1,        // Submit immediately after 1 request
        submitInterval = 5.seconds, // Or after 5 seconds
        pollInterval = 10.seconds,
        maxPollInterval = 30.seconds
      )

      val assessor = testKit.spawn(
        BatchCreativeAssessor.withApiKey(apiKey, repo, config),
        "batch-assessor-integration"
      )

      val meta = makeMeta("integration-test-1")
      repo.put(meta)

      // Submit assessment
      assessor ! BatchCreativeAssessor.Assess(
        meta = meta,
        imageBytes = imageBytes,
        adProductCategory = None,
        replyTo = responseProbe.ref
      )

      // Should get Queued immediately
      val queuedResponse = responseProbe.expectMessageType[BatchCreativeAssessor.Queued](5.seconds)
      queuedResponse.creativeId shouldBe "integration-test-1"

      println(s"Assessment queued for ${queuedResponse.creativeId}")

      // Force batch submission
      assessor ! BatchCreativeAssessor.FlushBatch

      // Wait for batch to complete (can take up to several minutes)
      // Poll the repo for updates
      var attempts = 0
      val maxAttempts = 60 // 5 minutes max
      while (repo.updates.isEmpty && attempts < maxAttempts) {
        Thread.sleep(5000) // Check every 5 seconds
        attempts += 1
        println(s"Waiting for batch result... attempt $attempts/$maxAttempts")
      }

      if (repo.updates.isEmpty) {
        fail("Batch did not complete within timeout")
      }

      // Verify results
      val (creativeId, result, verification) = repo.updates.head
      creativeId shouldBe "integration-test-1"

      println(s"Assessment complete:")
      println(s"  Safety score: ${result.safetyScore}")
      println(s"  Quality score: ${result.qualityScore}")
      println(s"  Adult content: ${result.adultContent}")
      println(s"  Detected categories: ${result.detectedCategories}")
      println(s"  Category confidence: ${result.categoryConfidence}")
      println(s"  Verification status: ${verification.status}")

      // Basic sanity checks
      result.safetyScore should be >= 0.0
      result.safetyScore should be <= 1.0
      result.qualityScore should be >= 0.0
      result.qualityScore should be <= 1.0
      result.categoryConfidence should be >= 0.0
      result.categoryConfidence should be <= 1.0

      // Creative should now be assessed
      val updated = scala.concurrent.Await.result(repo.get("integration-test-1"), 5.seconds)
      updated shouldBe defined
      updated.get.isAssessed shouldBe true
    }

    "assess with adProductCategory and compute verification" in {
      val apiKey = getApiKey.getOrElse {
        cancel("ANTHROPIC_API_KEY not set, skipping integration test")
      }

      val imageBytes = try {
        loadTestImage()
      } catch {
        case e: RuntimeException =>
          cancel(s"Test image not available: ${e.getMessage}")
      }

      val repo = new TestMetaRepo()
      val responseProbe = testKit.createTestProbe[BatchCreativeAssessor.Response]()

      val config = BatchAnthropicClient.Config(
        model = "claude-sonnet-4-20250514",
        batchThreshold = 1,
        submitInterval = 5.seconds,
        pollInterval = 10.seconds,
        maxPollInterval = 30.seconds
      )

      val assessor = testKit.spawn(
        BatchCreativeAssessor.withApiKey(apiKey, repo, config),
        "batch-assessor-verification"
      )

      val meta = makeMeta("verification-test-1")
      repo.put(meta)

      // Submit with adProductCategory + explicit target topics (3.0). The
      // verification step now compares LLM-detected creative categories
      // against `expectedCategories` directly — no more static IAB bridge
      // from adProductCategory → content. 653 = Travel tier-1 in IAB 3.0.
      assessor ! BatchCreativeAssessor.Assess(
        meta = meta,
        imageBytes = imageBytes,
        adProductCategory = Some(AdProductCategoryId("travel")), // Claim it's travel
        expectedCategories = Set(CategoryId("653")),
        replyTo = responseProbe.ref
      )

      responseProbe.expectMessageType[BatchCreativeAssessor.Queued](5.seconds)
      assessor ! BatchCreativeAssessor.FlushBatch

      // Wait for completion
      var attempts = 0
      while (repo.updates.isEmpty && attempts < 60) {
        Thread.sleep(5000)
        attempts += 1
        println(s"Waiting for verification result... attempt $attempts/60")
      }

      if (repo.updates.isEmpty) {
        fail("Batch did not complete within timeout")
      }

      val (_, result, verification) = repo.updates.head

      println(s"Verification result:")
      println(s"  Status: ${verification.status}")
      println(s"  Match score: ${verification.matchScore}")
      println(s"  LLM confidence: ${verification.llmConfidence}")
      println(s"  Detected: ${verification.detectedCategories}")
      println(s"  Expected: ${verification.expectedCategories}")

      // Verification should have been computed
      verification.llmConfidence should be >= 0.0
      verification.llmConfidence should be <= 1.0

      // If LLM was confident, we should have a definitive status
      if (verification.llmConfidence >= 0.7) {
        verification.status should (
          be(CategoryVerification.VerificationStatus.Verified) or
          be(CategoryVerification.VerificationStatus.Mismatch)
        )
      }
    }
  }
}
