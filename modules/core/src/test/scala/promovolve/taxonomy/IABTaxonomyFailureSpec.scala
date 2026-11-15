package promovolve.taxonomy

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.TokenBucketLimiter

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A failed classification must surface as a failure (#421). It used to be
 * turned into every demand category at confidence 0.25, which SiteEntity
 * stored as a real classification — unrelated ads for the whole freshness
 * window. Failing lets SiteEntity store nothing and retry on the next visit.
 */
class IABTaxonomyFailureSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit()
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  given ec: scala.concurrent.ExecutionContext = system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "IABTaxonomy.analyze" should {

    "fail, not invent categories, when the provider call cannot be made" in {
      // The rate limiter refusing a token fails the call before any network I/O.
      val limiter = testKit.createTestProbe[TokenBucketLimiter.Command]()
      val taxonomy = new IABTaxonomy(IABTaxonomy.Provider.Gemini("unused"), Some(limiter.ref))

      val result = taxonomy.analyze("https://example.com/a", "some page text")
      limiter.expectMessageType[TokenBucketLimiter.Acquire].replyTo ! TokenBucketLimiter.Permit.QueueFull

      val outcome = Try(Await.result(result, 5.seconds))
      outcome.isFailure shouldBe true
    }
  }
}
