package promovolve.taxonomy

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.persistence.testkit.PersistenceTestKitDurableStateStorePlugin
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Clock, Instant, ZoneId}
import java.util.UUID
import scala.concurrent.duration.*

class TaxonomyRankerEntitySpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  val testKit: ActorTestKit = ActorTestKit(testConfig)
  // Test configuration with persistence testkit
  private val testConfig = PersistenceTestKitDurableStateStorePlugin.config.withFallback(
    ConfigFactory.parseString(
      """
        |pekko {
        |  loglevel = "WARNING"
        |
        |  actor {
        |    provider = "local"
        |    allow-java-serialization = on
        |
        |    serializers {
        |      jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
        |    }
        |
        |    serialization-bindings {
        |      "promovolve.CborSerializable" = jackson-cbor
        |    }
        |  }
        |}
        |""".stripMargin
    )
  )

  override def afterAll(): Unit = testKit.shutdownTestKit()

  def createTestProbe[T](): TestProbe[T] = testKit.createTestProbe[T]()

  def spawn[T](behavior: org.apache.pekko.actor.typed.Behavior[T]): ActorRef[T] =
    testKit.spawn(behavior)

  // Generate unique entity ID for each test to avoid persistence ID conflicts
  // Format: "category|siteId"
  def uniqueEntityId(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}|test-site"

  "TaxonomyRankerEntity Thompson Sampling" should {

    "sample from Beta distribution with no data (uses priors)" in {
      val quoteProbe = createTestProbe[TaxonomyRankerEntity.Quoted]()

      val entity = spawn(TaxonomyRankerEntity(uniqueEntityId("tech")))

      // With no data, samples from Beta(1, 1) = uniform distribution
      // Multiple samples should show variance
      val samples = (1 to 10).map { _ =>
        entity ! TaxonomyRankerEntity.Quote(quoteProbe.ref)
        quoteProbe.expectMessageType[TaxonomyRankerEntity.Quoted].weight
      }

      // Samples should be between 0 and ~1 (score includes CPM normalization)
      samples.foreach { s =>
        s should be >= 0.0
        s should be <= 1.5 // upper bound with CTR bonus
      }

      // With uniform prior, we expect variance in samples
      val distinctSamples = samples.distinct.size
      distinctSamples should be > 1 // Multiple distinct samples
    }

    "converge to high scores with good performance data" in {
      val quoteProbe = createTestProbe[TaxonomyRankerEntity.Quoted]()

      val entity = spawn(TaxonomyRankerEntity(uniqueEntityId("high-performer")))

      // Record many impressions with clicks
      (1 to 100).foreach { _ =>
        entity ! TaxonomyRankerEntity.RecordImpression(revenue = 5.0)
        entity ! TaxonomyRankerEntity.RecordClick()
      }

      // Sample multiple times - should consistently be high
      val samples = (1 to 10).map { _ =>
        entity ! TaxonomyRankerEntity.Quote(quoteProbe.ref)
        quoteProbe.expectMessageType[TaxonomyRankerEntity.Quoted].weight
      }

      // With 100 wins out of 100 impressions, Beta(101, 1) has very high mean
      // All samples should be high
      samples.foreach { s =>
        s should be > 0.5
      }

      // Variance should be low with many observations
      val mean = samples.sum / samples.size
      val variance = samples.map(s => math.pow(s - mean, 2)).sum / samples.size
      variance should be < 0.1
    }

    "explore with low-data categories (high variance)" in {
      val quoteProbe = createTestProbe[TaxonomyRankerEntity.Quoted]()

      val entity = spawn(TaxonomyRankerEntity(uniqueEntityId("new-category")))

      // Just 3 impressions with no clicks - not much data
      entity ! TaxonomyRankerEntity.RecordImpression(revenue = 3.0)
      entity ! TaxonomyRankerEntity.RecordImpression(revenue = 3.0)
      entity ! TaxonomyRankerEntity.RecordImpression(revenue = 3.0)

      // Sample many times - should show variance
      val samples = (1 to 20).map { _ =>
        entity ! TaxonomyRankerEntity.Quote(quoteProbe.ref)
        quoteProbe.expectMessageType[TaxonomyRankerEntity.Quoted].weight
      }

      // With few observations, variance should be noticeable
      val distinctSamples = samples.distinct.size
      distinctSamples should be > 5 // Significant variance expected
    }

    "poor performers can still occasionally sample high (exploration)" in {
      val quoteProbe = createTestProbe[TaxonomyRankerEntity.Quoted]()

      val settings = TaxonomyRankerEntity.Settings(
        priorAlpha = 1.0,
        priorBeta = 1.0
      )
      val entity = spawn(TaxonomyRankerEntity(uniqueEntityId("poor-performer"), settings))

      // Record some impressions with no clicks (poor CTR)
      (1 to 10).foreach { _ =>
        entity ! TaxonomyRankerEntity.RecordImpression(revenue = 0.5)
      }

      // Verify that multiple samples show variance
      val samples = (1 to 50).map { _ =>
        entity ! TaxonomyRankerEntity.Quote(quoteProbe.ref)
        quoteProbe.expectMessageType[TaxonomyRankerEntity.Quoted].weight
      }

      // Should have some variance even with data
      val min = samples.min
      val max = samples.max
      (max - min) should be > 0.01 // Some spread expected
    }
  }

  "TaxonomyRankerEntity GetStats" should {

    "return deterministic stats snapshot for monitoring" in {
      val statsProbe = createTestProbe[TaxonomyRankerEntity.StatsSnapshot]()

      val entity = spawn(TaxonomyRankerEntity(uniqueEntityId("monitored")))

      // Record some impressions and clicks
      entity ! TaxonomyRankerEntity.RecordImpression(revenue = 3.0)
      entity ! TaxonomyRankerEntity.RecordClick()
      entity ! TaxonomyRankerEntity.RecordImpression(revenue = 2.0)

      // Get stats
      entity ! TaxonomyRankerEntity.GetStats(statsProbe.ref)
      val stats = statsProbe.expectMessageType[TaxonomyRankerEntity.StatsSnapshot]

      stats.categoryId should startWith("monitored")
      stats.siteId shouldBe "test-site"
      stats.wins should be >= 1.9 // Allow for minor floating point
      stats.clicks should be >= 0.9
      stats.revenue should be >= 4.9 // 3.0 + 2.0
      stats.ctr should be >= 0.4 // 1 click / 2 wins = 0.5
      stats.meanCtr should be > 0.0 // Bayesian posterior mean
    }
  }

  "TaxonomyRankerEntity decay" should {

    "decay stats over time" in {
      val statsProbe = createTestProbe[TaxonomyRankerEntity.StatsSnapshot]()

      var currentTime = Instant.now()
      val mutableClock = new Clock {
        def getZone: ZoneId = ZoneId.systemDefault()
        override def withZone(zone: ZoneId): Clock = this
        def instant(): Instant = currentTime
      }

      val settings = TaxonomyRankerEntity.Settings(halfLife = 1.second)
      val entity = spawn(TaxonomyRankerEntity(uniqueEntityId("decaying"), settings, mutableClock))

      // Record impression with click
      entity ! TaxonomyRankerEntity.RecordImpression(revenue = 5.0)
      entity ! TaxonomyRankerEntity.RecordClick()

      // Get initial stats
      entity ! TaxonomyRankerEntity.GetStats(statsProbe.ref)
      val initial = statsProbe.expectMessageType[TaxonomyRankerEntity.StatsSnapshot]
      initial.wins should be >= 1.0

      // Advance time by 2 half-lives
      currentTime = currentTime.plusSeconds(2)

      // Get decayed stats
      entity ! TaxonomyRankerEntity.GetStats(statsProbe.ref)
      val decayed = statsProbe.expectMessageType[TaxonomyRankerEntity.StatsSnapshot]

      // Should be decayed by ~75% (2 half-lives = 0.25 remaining)
      decayed.wins should be < initial.wins
      decayed.wins should be >= 0.2 // At least 20% remaining
      decayed.wins should be <= 0.4 // At most 40% remaining
    }
  }

  "TaxonomyRankerEntity persistence" should {

    "recover state after restart" in {
      val statsProbe = createTestProbe[TaxonomyRankerEntity.StatsSnapshot]()
      val entityId = uniqueEntityId("persisted")

      // Use short flush interval for testing
      val settings = TaxonomyRankerEntity.Settings(flushEvery = 50.millis)

      // Create entity and record data
      val entity1 = spawn(TaxonomyRankerEntity(entityId, settings))
      entity1 ! TaxonomyRankerEntity.RecordImpression(revenue = 5.0)
      entity1 ! TaxonomyRankerEntity.RecordClick()
      entity1 ! TaxonomyRankerEntity.RecordImpression(revenue = 3.0)

      // Give time for flush to occur (flushEvery = 50ms)
      Thread.sleep(150)

      // Get stats from first entity
      entity1 ! TaxonomyRankerEntity.GetStats(statsProbe.ref)
      val initial = statsProbe.expectMessageType[TaxonomyRankerEntity.StatsSnapshot]
      initial.wins should be >= 1.9
      initial.revenue should be >= 7.9 // 5.0 + 3.0

      // Stop the first entity
      testKit.stop(entity1)
      Thread.sleep(100)

      // Create a new entity with the same ID - it should recover the state
      val entity2 = spawn(TaxonomyRankerEntity(entityId, settings))

      // Give time for recovery
      Thread.sleep(100)

      // Get stats from recovered entity
      entity2 ! TaxonomyRankerEntity.GetStats(statsProbe.ref)
      val recovered = statsProbe.expectMessageType[TaxonomyRankerEntity.StatsSnapshot]

      // Stats should be recovered (with some potential decay)
      recovered.wins should be >= 1.0
      recovered.revenue should be >= 5.0
    }
  }

  "TaxonomyRankerEntity sharding" should {

    "parse category and siteId from entityId" in {
      val statsProbe = createTestProbe[TaxonomyRankerEntity.StatsSnapshot]()

      // Entity ID format: "category|siteId"
      val entity = spawn(TaxonomyRankerEntity("tech|fashion-women.com"))

      entity ! TaxonomyRankerEntity.GetStats(statsProbe.ref)
      val stats = statsProbe.expectMessageType[TaxonomyRankerEntity.StatsSnapshot]

      stats.categoryId shouldBe "tech"
      stats.siteId shouldBe "fashion-women.com"
    }

    "use default siteId when not provided" in {
      val statsProbe = createTestProbe[TaxonomyRankerEntity.StatsSnapshot]()

      // Legacy format without siteId
      val entity = spawn(TaxonomyRankerEntity("tech-only"))

      entity ! TaxonomyRankerEntity.GetStats(statsProbe.ref)
      val stats = statsProbe.expectMessageType[TaxonomyRankerEntity.StatsSnapshot]

      stats.categoryId shouldBe "tech-only"
      stats.siteId shouldBe "default"
    }
  }
}
