package promovolve.auction

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.publisher.CategoryDemandRepo

import scala.concurrent.{Future, Promise}

/** Regression for the CategoryBidder seed/push race.
  *
  * The bidder starts in `seeding`, async-loading demand from category_demand.
  * If a live `ActiveCampaigns` push arrives first, it promotes straight to
  * `serving` (the push is authoritative). The in-flight seed result then lands
  * in `serving` — which previously had no case for it, so `SeedLoaded` hit a
  * non-exhaustive match → scala.MatchError → StopSupervisor stopped the entity
  * → demand for the whole category died (surfaced as "registering a site does
  * nothing / no ads"). `serving` must now drop the late seed instead.
  */
class CategoryBidderSeedRaceSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

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
      |}
      |""".stripMargin
  )

  private val testKit = ActorTestKit(testConfig)
  private given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  private val cluster = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)
  private lazy val sharding: ClusterSharding = ClusterSharding(testKit.system)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  /** Repo whose seed load we complete on demand, so the test controls the race. */
  private class ControllableRepo(seed: Promise[Vector[(String, String)]]) extends CategoryDemandRepo {
    def upsertCampaign(categoryIds: Set[String], campaignId: String, advertiserId: String): Future[Unit] = Future.unit
    def removeCampaign(campaignId: String): Future[Unit] = Future.unit
    def listByCategory(categoryId: String): Future[Vector[(String, String)]] = seed.future
  }

  "CategoryBidderEntity.serving" should {
    "drop a late demand seed that lands after a live ActiveCampaigns push (no MatchError crash)" in {
      import CategoryBidderEntity.*
      val seed   = Promise[Vector[(String, String)]]()
      val bidder = testKit.spawn(CategoryBidderEntity("IAB1|0", sharding, new ControllableRepo(seed)))
      val ack    = testKit.createTestProbe[ActiveCampaignsAck]()

      // 1) Live push beats the still-pending seed → entity promotes to `serving`.
      bidder ! ActiveCampaigns(Map(CampaignId("c1") -> AdvertiserId("a1")), ack.ref)
      ack.expectMessageType[ActiveCampaignsAck]

      // 2) NOW complete the seed → SeedLoaded is delivered into `serving`.
      seed.success(Vector("c2" -> "a2"))

      // 3) The entity must still be alive and responsive. Pre-fix it MatchError-
      //    crashed on the late seed and this second push would get no ack.
      bidder ! ActiveCampaigns(Map(CampaignId("c3") -> AdvertiserId("a3")), ack.ref)
      ack.expectMessageType[ActiveCampaignsAck]
    }
  }
}
