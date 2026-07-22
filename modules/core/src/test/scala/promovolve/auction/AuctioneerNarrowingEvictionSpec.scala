package promovolve.auction

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.advertiser.CampaignEntity
import promovolve.publisher.delivery.AdServer

import scala.concurrent.duration.*

/**
 * Eviction-on-narrow, Case 1 (site-narrow) at the AuctioneerEntity boundary.
 *
 * AuctioneerEntity is PER-SITE. When it receives a CampaignChanged whose
 * non-empty siteAllowlist excludes THIS site, it must tell its AdServer to
 * evict the campaign (EvictCampaignFromSite) and still re-auction so others
 * refill. When the allowlist includes the site (or is empty), it behaves as a
 * normal active config change — no eviction.
 */
class AuctioneerNarrowingEvictionSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val testConfig = ConfigFactory.parseString(
    """
      |pekko {
      |  loglevel = "WARNING"
      |  actor {
      |    provider = "cluster"
      |    serializers {
      |      jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
      |    }
      |    serialization-bindings {
      |      "promovolve.CborSerializable" = jackson-cbor
      |    }
      |  }
      |  remote.artery {
      |    canonical.hostname = "127.0.0.1"
      |    canonical.port = 0
      |  }
      |  cluster {
      |    seed-nodes = []
      |    downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
      |  }
      |  persistence {
      |    state.plugin = "pekko.persistence.testkit.state"
      |    journal.plugin = "pekko.persistence.journal.inmem"
      |  }
      |}
      |""".stripMargin
  )

  val testKit: ActorTestKit = ActorTestKit(testConfig)
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system

  private val cluster = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)

  lazy val sharding: ClusterSharding = ClusterSharding(testKit.system)

  // Capture every command the auctioneer sends to its AdServer shard.
  val adServerProbe: TestProbe[AdServer.Command] =
    testKit.createTestProbe[AdServer.Command]()
  sharding.init(Entity(AdServer.TypeKey)(_ =>
    Behaviors.receiveMessage[AdServer.Command] { msg =>
      adServerProbe.ref ! msg
      Behaviors.same
    }
  ))

  // Stub the SiteEntity shard: the auctioneer's startup sends
  // SiteEntity.AuctioneerStarted, and without the shard started the resulting
  // "must be started first" error destabilizes the entity under test. A no-op
  // sink is enough — these tests drive classifications in directly.
  sharding.init(Entity(promovolve.publisher.SiteEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[promovolve.publisher.SiteEntity.Command](_ => Behaviors.same)
  ))

  private val budgetTopic: ActorRef[Topic.Command[BudgetEvent]] =
    testKit.spawn(Behaviors.ignore[Topic.Command[BudgetEvent]])

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val thisSite = "site-a"

  /**
   * Per-test isolated wiring: a fresh CampaignChanged topic (so a prior test's
   * still-subscribed auctioneer can't react to this test's publish) and the
   * site-a auctioneer subscribed to it. The auctioneer subscribes `ctx.self`
   * to the topic on startup — publishing to it is the real delivery path.
   * Returns a publish fn scoped to this test's topic.
   */
  private def newAuctioneer(): CampaignEntity.CampaignChanged => Unit = {
    val topic = testKit.spawn(
      Topic[CampaignEntity.CampaignChanged](s"campaign-changed-${java.util.UUID.randomUUID()}")
    )
    testKit.spawn(
      AuctioneerEntity(SiteId(thisSite), sharding, budgetTopic, topic)
    )
    // Give the entity's Topic.Subscribe time to register before publishing.
    Thread.sleep(300)
    (event: CampaignEntity.CampaignChanged) => topic ! Topic.Publish(event)
  }

  "AuctioneerEntity site-narrow eviction" should {

    "send EvictCampaignFromSite when a non-empty allowlist excludes this site" in {
      val publish = newAuctioneer()
      val campaignId = CampaignId("camp-excluded")

      // Advertiser narrowed to site-b/site-c only; this entity is site-a.
      publish(CampaignEntity.CampaignChanged(
        campaignId = campaignId,
        categories = Set(CategoryId("100")),
        isActive = true,
        siteAllowlist = Set("site-b", "site-c")
      ))

      adServerProbe.expectMessage(2.seconds, AdServer.EvictCampaignFromSite(campaignId))
    }

    "NOT evict when the allowlist includes this site (normal active change)" in {
      val publish = newAuctioneer()

      publish(CampaignEntity.CampaignChanged(
        campaignId = CampaignId("camp-included"),
        categories = Set(CategoryId("100")),
        isActive = true,
        siteAllowlist = Set("site-a", "site-b")
      ))

      // Only re-auctions (debounced timer, no AdServer message); never evicts.
      adServerProbe.expectNoMessage(1.second)
    }

    "NOT evict when the allowlist is empty (bid everywhere)" in {
      val publish = newAuctioneer()

      publish(CampaignEntity.CampaignChanged(
        campaignId = CampaignId("camp-everywhere"),
        categories = Set(CategoryId("100")),
        isActive = true,
        siteAllowlist = Set.empty
      ))

      adServerProbe.expectNoMessage(1.second)
    }

    "not crash on a CampaignChanged for an unknown, non-excluded campaign" in {
      val publish = newAuctioneer()
      // Unknown campaign, allowlist includes this site → no eviction, no crash.
      publish(CampaignEntity.CampaignChanged(
        campaignId = CampaignId("ghost"),
        categories = Set(CategoryId("100")),
        isActive = true,
        siteAllowlist = Set("site-a")
      ))
      adServerProbe.expectNoMessage(1.second)
    }
  }

  // Freshness-eviction ↔ serve-token sync (the two-sided-freshness deadlock
  // that darkened the demo site 2026-07-22). When CleanupStaleContent evicts a
  // classification from the auction cache, it MUST also invalidate the
  // AdServer freshness token — otherwise serving keeps reporting the page
  // classified, the ad tag never re-sends text, and the page is a no-fill
  // dead-end until the token expires on its own.
  "AuctioneerEntity classification-freshness cleanup" should {

    "invalidate the AdServer freshness token for pages it evicts as stale" in {
      import java.time.Instant
      import promovolve.publisher.SiteEntity.{ ClassificationEntry, PersistedSlot }

      // Short windows so the cleanup timer fires immediately and the seeded
      // page is already older than the freshness TTL.
      val ref = testKit.spawn(
        AuctioneerEntity(
          SiteId(thisSite),
          sharding,
          budgetTopic,
          testKit.spawn(Topic[CampaignEntity.CampaignChanged](s"cc-${java.util.UUID.randomUUID()}")),
          settings = AuctioneerEntity.Settings(
            classificationFreshnessWindow = 1.second,
            cleanupInterval = 300.millis
          )
        )
      )

      Thread.sleep(300) // let the entity finish setup + subscriptions

      val staleUrl = "https://pub.example/stale-page"
      val staleTs = Instant.now().minusSeconds(60).toEpochMilli // well past a 1s TTL
      ref ! AuctioneerEntity.RestoreClassifications(
        Map(staleUrl -> ClassificationEntry(
          categories = Map("100" -> 0.9),
          slots = Vector(PersistedSlot("SLOT-A", 300, 250, Vector.empty, None, None)),
          classifiedAt = staleTs
        ))
      )

      // Restore marks it classified; the cleanup tick then evicts it and, with
      // the fix, invalidates the token. Fish past the MarkClassified.
      adServerProbe.fishForMessage(3.seconds) {
        case AdServer.InvalidateClassification(u) if u.value == staleUrl =>
          org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.complete
        case _ =>
          org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.continueAndIgnore
      }

      testKit.stop(ref)
    }
  }

  /**
   * Eviction-on-narrow, Case 2 (topic-narrow). The slot-key computation is a
   * pure function on the auctioneer's awardedCampaigns/lastPage caches:
   * `AuctioneerEntity.topicEvictionSlotKeys`. We test it directly — driving a
   * full live auction to seed those caches would add no coverage to the
   * decision logic, which is what topic eviction hinges on.
   */
  "AuctioneerEntity.topicEvictionSlotKeys (topic-narrow)" should {
    import java.time.Instant
    import AuctioneerEntity.AdSlotSpec

    val now = Instant.now
    def slot(id: String): AdSlotSpec =
      AdSlotSpec(SlotId(id), declaredSizes = Nil, computedSize = AdSize(300, 250))

    // url -> (categoryScores keyed by category id, slots, classifiedAt)
    val sportsUrl = URL("https://pub.example/sports")
    val techUrl = URL("https://pub.example/tech")
    val fillerUrl = URL("https://pub.example/misc")
    val lastPage: Map[URL, (Map[String, Double], List[AdSlotSpec], Instant)] = Map(
      sportsUrl -> (Map("100" -> 0.9), List(slot("SLOT-A"), slot("SLOT-B")), now),
      techUrl -> (Map("200" -> 0.8), List(slot("SLOT-C")), now),
      fillerUrl -> (Map.empty[String, Double], List(slot("SLOT-D")), now)
    )

    "evict the slot keys of a categorized page the campaign no longer targets" in {
      // Campaign now targets only "200" (tech). It is awarded on the sports
      // page (cat "100"), which it no longer targets → evict sports slots.
      val keys = AuctioneerEntity.topicEvictionSlotKeys(
        siteId = thisSite,
        awardedUrls = Set(sportsUrl),
        lastPage = lastPage,
        targetCategories = Set(CategoryId("200"))
      )
      keys shouldBe Set(s"$thisSite|SLOT-A", s"$thisSite|SLOT-B")
    }

    "NOT include slot keys for a page the campaign still matches" in {
      // Awarded on both pages; still targets "200" (tech) → tech page kept,
      // only the now-unmatched sports page is evicted.
      val keys = AuctioneerEntity.topicEvictionSlotKeys(
        siteId = thisSite,
        awardedUrls = Set(sportsUrl, techUrl),
        lastPage = lastPage,
        targetCategories = Set(CategoryId("200"))
      )
      keys shouldBe Set(s"$thisSite|SLOT-A", s"$thisSite|SLOT-B")
      keys should not contain s"$thisSite|SLOT-C"
    }

    "skip filler/uncategorized awarded pages (category narrowing does not affect them)" in {
      // Awarded only on the filler page (empty categoryScores). Even though the
      // campaign targets neither of its (nonexistent) categories, filler pages
      // are not affected by a category narrow → no eviction.
      val keys = AuctioneerEntity.topicEvictionSlotKeys(
        siteId = thisSite,
        awardedUrls = Set(fillerUrl),
        lastPage = lastPage,
        targetCategories = Set(CategoryId("999"))
      )
      keys shouldBe empty
    }

    "return empty for an unknown campaign (no awarded URLs) without crashing" in {
      val keys = AuctioneerEntity.topicEvictionSlotKeys(
        siteId = thisSite,
        awardedUrls = Set.empty,
        lastPage = lastPage,
        targetCategories = Set(CategoryId("100"))
      )
      keys shouldBe empty
    }

    "evict ALL awarded categorized pages when the campaign dropped every matching topic" in {
      // Campaign narrowed to a category it isn't awarded on anywhere → both the
      // sports and tech pages are now unmatched; filler is still skipped.
      val keys = AuctioneerEntity.topicEvictionSlotKeys(
        siteId = thisSite,
        awardedUrls = Set(sportsUrl, techUrl, fillerUrl),
        lastPage = lastPage,
        targetCategories = Set(CategoryId("777"))
      )
      keys shouldBe Set(s"$thisSite|SLOT-A", s"$thisSite|SLOT-B", s"$thisSite|SLOT-C")
    }
  }
}
