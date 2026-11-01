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
 * A cleared admin slot floor must stop applying everywhere. The admin
 * floor map pushed by SiteEntity is the only source of truth; a copy saved
 * with a page's slots (persisted classification) must never revive it.
 */
class AuctioneerClearedSlotFloorSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val testConfig = ConfigFactory.parseString(
    """
      |pekko {
      |  loglevel = "WARNING"
      |  # Stub sharding regions push cluster teardown past the testkit's 10s
      |  # default — same fix as AdServerLifecycleSpec.
      |  actor.testkit.typed.system-shutdown-default = 60s
      |  actor {
      |    provider = "cluster"
      |    serializers { jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer" }
      |    serialization-bindings { "promovolve.CborSerializable" = jackson-cbor }
      |  }
      |  remote.artery {
      |    canonical.hostname = "127.0.0.1"
      |    canonical.port = 0
      |  }
      |  cluster {
      |    seed-nodes = []
      |    downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
      |    sharding.number-of-shards = 10
      |  }
      |}
      |""".stripMargin
  )

  val testKit: ActorTestKit = ActorTestKit(testConfig)
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  private val cluster = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)
  lazy val sharding: ClusterSharding = ClusterSharding(testKit.system)

  // Every floor the auction asks bidders to beat lands here.
  val bidRequests: TestProbe[CategoryBidderEntity.CategoryBidRequest] =
    testKit.createTestProbe[CategoryBidderEntity.CategoryBidRequest]()
  sharding.init(Entity(CategoryBidderEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[CategoryBidderEntity.Command] {
      case r: CategoryBidderEntity.CategoryBidRequest => bidRequests.ref ! r; Behaviors.same
      case _                                          => Behaviors.same
    }
  ))
  // Filler auctions ask campaigns directly; their floors land here.
  val campaignBidRequests: TestProbe[CampaignEntity.CampaignBidRequest] =
    testKit.createTestProbe[CampaignEntity.CampaignBidRequest]()
  sharding.init(Entity(CampaignEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[CampaignEntity.Command] {
      case r: CampaignEntity.CampaignBidRequest => campaignBidRequests.ref ! r; Behaviors.same
      case _                                    => Behaviors.same
    }
  ))
  sharding.init(Entity(AdServer.TypeKey)(_ => Behaviors.ignore[AdServer.Command]))
  sharding.init(Entity(promovolve.publisher.SiteEntity.TypeKey)(_ =>
    Behaviors.ignore[promovolve.publisher.SiteEntity.Command]))

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "AuctioneerEntity" should {
    "ignore an admin floor saved with a page's slots once the admin map no longer has it" in {
      import java.time.Instant
      import promovolve.publisher.SiteEntity.{ ClassificationEntry, PersistedSlot }

      val ref = testKit.spawn(AuctioneerEntity(
        SiteId("site-a"), sharding, testKit.spawn(Behaviors.ignore[Topic.Command[BudgetEvent]]),
        testKit.spawn(Topic[CampaignEntity.CampaignChanged](s"cc-${java.util.UUID.randomUUID()}"))))
      Thread.sleep(300)
      ref ! AuctioneerEntity.UpdateFloorCpm(CPM(2.0))
      // The admin cleared the slot's $0.10 override: the pushed map is empty.
      ref ! AuctioneerEntity.UpdateAdminSlotFloors(Map.empty)

      val url = "https://pub.example/page"
      ref ! AuctioneerEntity.RestoreClassifications(Map(url -> ClassificationEntry(
        categories = Map("100" -> 0.9),
        // Saved when the override was still set.
        slots = Vector(PersistedSlot("SLOT-A", 300, 250, Vector.empty, None, Some(CPM(0.10)))),
        classifiedAt = Instant.now().toEpochMilli
      )))
      ref ! AuctioneerEntity.Reevaluate(URL(url))

      val req = bidRequests.fishForMessage(3.seconds) { r =>
        if (r.slotId == SlotId("SLOT-A")) org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.complete
        else org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.continueAndIgnore
      }.last
      req.floorCpm shouldBe CPM(2.0)
      testKit.stop(ref)
    }

    "admit filler bids at the slot's admin floor, the same floor serving checks" in {
      import java.time.Instant
      import promovolve.advertiser.CampaignDirectory

      val directory = testKit.spawn(Behaviors.receiveMessage[CampaignDirectory.Command] {
        case CampaignDirectory.GetFillerCampaigns(replyTo) =>
          replyTo ! CampaignDirectory.FillerCampaignsResult(Map(CampaignId("filler") -> AdvertiserId("adv")))
          Behaviors.same
        case _ => Behaviors.same
      })
      val ref = testKit.spawn(AuctioneerEntity(
        SiteId("site-b"), sharding, testKit.spawn(Behaviors.ignore[Topic.Command[BudgetEvent]]),
        testKit.spawn(Topic[CampaignEntity.CampaignChanged](s"cc-${java.util.UUID.randomUUID()}")),
        campaignDirectory = Some(directory)))
      Thread.sleep(300)
      ref ! AuctioneerEntity.UpdateFloorCpm(CPM(2.0))
      ref ! AuctioneerEntity.UpdateAdminSlotFloors(Map(SlotId("SLOT-F") -> CPM(0.5)))
      ref ! AuctioneerEntity.FillerAuctionRequested(
        URL("https://pub.example/filler"),
        List(AuctioneerEntity.AdSlotSpec(SlotId("SLOT-F"), declaredSizes = Nil, computedSize = AdSize(300, 250))),
        Instant.now())

      campaignBidRequests.receiveMessage(3.seconds).floorCpm shouldBe CPM(0.5)
      testKit.stop(ref)
    }
  }
}
