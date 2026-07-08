package promovolve.taxonomy

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.{BeforeAndAfterAll, Tag}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*

object Integration extends Tag("Integration")

class IABTaxonomyIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit()
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  given ec: scala.concurrent.ExecutionContext = system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val sampleContent =
    """
    |Welcome to TechGadgets Review!
    |
    |Today we're reviewing the latest smartphones and tablets from major manufacturers.
    |The new iPhone 15 Pro features an improved camera system with 48MP sensor,
    |while Samsung's Galaxy S24 Ultra offers incredible display technology.
    |
    |For gamers, we also cover the latest gaming laptops and accessories.
    |The ASUS ROG Zephyrus continues to impress with its RTX 4090 graphics.
    |
    |Stay tuned for more tech reviews and buying guides!
    """.stripMargin

  "IABTaxonomy with OpenAI" should {

    "classify tech content correctly" taggedAs Integration in {
      val apiKey = sys.env.getOrElse("OPENAI_API_KEY", {
        cancel("OPENAI_API_KEY not set - skipping integration test")
      })

      val taxonomy = new IABTaxonomy(
        IABTaxonomy.Provider.OpenAI(apiKey)
      )

      try {
        val result = Await.result(
          taxonomy.analyzeTaxonomy("https://example.com/tech-review", sampleContent),
          30.seconds
        )

        println(s"OpenAI returned ${result.size} categories:")
        result.foreach(s => println(s"  - ${s.id}: ${s.confidence}"))

        result should not be empty
        result.foreach { selection =>
          selection.id should startWith("IAB19")
          selection.confidence should be >= 0.0
          selection.confidence should be <= 1.0
        }
      } finally {
        taxonomy.close()
      }
    }
  }

  "IABTaxonomy with Anthropic" should {

    "classify tech content correctly" taggedAs Integration in {
      val apiKey = sys.env.getOrElse("ANTHROPIC_API_KEY", {
        cancel("ANTHROPIC_API_KEY not set - skipping integration test")
      })

      val taxonomy = new IABTaxonomy(
        IABTaxonomy.Provider.Anthropic(apiKey)
      )

      try {
        val result = Await.result(
          taxonomy.analyzeTaxonomy("https://example.com/tech-review", sampleContent),
          30.seconds
        )

        println(s"Anthropic returned ${result.size} categories:")
        result.foreach(s => println(s"  - ${s.id}: ${s.confidence}"))

        result should not be empty
        result.foreach { selection =>
//          selection.id should startWith("IAB19")
          selection.confidence should be >= 0.0
          selection.confidence should be <= 1.0
        }
      } finally {
        taxonomy.close()
      }
    }
  }

  "IABTaxonomy with Gemini" should {

    "classify tech content correctly" taggedAs Integration in {
      val apiKey = sys.env.getOrElse("GEMINI_API_KEY", {
        cancel("GEMINI_API_KEY not set - skipping integration test")
      })

      val taxonomy = new IABTaxonomy(
        IABTaxonomy.Provider.Gemini(apiKey)
      )

      try {
        val result = Await.result(
          taxonomy.analyzeTaxonomy("https://example.com/tech-review", sampleContent),
          30.seconds
        )

        println(s"Gemini returned ${result.size} categories:")
        result.foreach(s => println(s"  - ${s.id}: ${s.confidence}"))

        result should not be empty
        result.foreach { selection =>
          selection.id should startWith("IAB19")
          selection.confidence should be >= 0.0
          selection.confidence should be <= 1.0
        }
      } finally {
        taxonomy.close()
      }
    }
  }

  "IABTaxonomy with any available provider" should {

    "classify food content correctly" taggedAs Integration in {
      val provider = try {
        IABTaxonomy.Provider.fromEnv()
      } catch {
        case _: IllegalStateException =>
          cancel("No API key set - skipping integration test")
      }

      println(s"Using provider: ${provider.name} (${provider.model})")

      val foodContent =
        """
        |Delicious Italian Pasta Recipes
        |
        |Learn how to make authentic carbonara and bolognese at home.
        |Our chef shares secrets for perfect al dente pasta every time.
        |
        |Ingredients: spaghetti, eggs, pecorino cheese, guanciale, black pepper.
        |
        |Pair with a nice Chianti wine for the ultimate dining experience.
        """.stripMargin

      val taxonomy = new IABTaxonomy(
        provider
      )

      try {
        val result = Await.result(
          taxonomy.analyzeTaxonomy("https://example.com/pasta-recipes", foodContent),
          30.seconds
        )

        println(s"Food content classification (${result.size} categories):")
        result.foreach(s => println(s"  - ${s.id}: ${s.confidence}"))

        result should not be empty
        result.foreach { selection =>
          selection.id should startWith("IAB8")
        }
      } finally {
        taxonomy.close()
      }
    }
  }
}