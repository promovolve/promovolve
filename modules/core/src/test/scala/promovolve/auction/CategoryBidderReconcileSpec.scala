package promovolve.auction

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ ActorTestKit, LoggingTestKit }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.publisher.CategoryDemandRepo

import scala.concurrent.Future

/**
 * Regression for the PARTIAL PUSH failure mode: an `ActiveCampaigns` push
 * REPLACES the bidder's registry, so a push built from a directory that
 * lost state in a roll silently shrinks the registry — the dropped
 * campaigns stop bidding in this category with no trace (live incident:
 * a bidder seeded 3 campaigns from category_demand, then a partial push
 * cut it to 1). A shrinking push now cross-checks category_demand and
 * merges back any campaign the durable table still lists — a REAL leave
 * is distinguishable because CampaignEntity deletes its rows on pause.
 */
class CategoryBidderReconcileSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

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
  private val cluster = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)
  private lazy val sharding: ClusterSharding = ClusterSharding(testKit.system)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private class ConstRepo(rows: Vector[(String, String)]) extends CategoryDemandRepo {
    def upsertCampaign(categoryIds: Set[String], campaignId: String, advertiserId: String): Future[Unit] =
      Future.unit
    def removeCampaign(campaignId: String): Future[Unit] = Future.unit
    def listByCategory(categoryId: String): Future[Vector[(String, String)]] =
      Future.successful(rows)
  }

  import CategoryBidderEntity.*

  "CategoryBidderEntity registry reconcile" should {

    "restore campaigns a shrinking push dropped while category_demand still lists them" in {
      // Durable table lists TWO campaigns throughout — the shrink is a lie.
      val repo = new ConstRepo(Vector("c1" -> "a1", "c2" -> "a2"))
      val bidder = testKit.spawn(CategoryBidderEntity("IAB2|0", sharding, repo))
      val ack = testKit.createTestProbe[ActiveCampaignsAck]()

      // Deterministic starting registry {c1,c2}: whether the seed or this
      // push lands first, both carry the same two campaigns.
      bidder ! ActiveCampaigns(
        Map(CampaignId("c1") -> AdvertiserId("a1"), CampaignId("c2") -> AdvertiserId("a2")), ack.ref)
      ack.expectMessageType[ActiveCampaignsAck]

      // PARTIAL push drops c2 → shrink WARN + cross-check → c2 restored
      // (the table still lists it) → "PARTIAL PUSH detected" WARN.
      LoggingTestKit
        .warn("PARTIAL PUSH detected")
        .expect {
          bidder ! ActiveCampaigns(Map(CampaignId("c1") -> AdvertiserId("a1")), ack.ref)
          ack.expectMessageType[ActiveCampaignsAck]
        }(testKit.system)
    }

    "accept a real leave (rows deleted from category_demand) without restoring or crashing" in {
      // Durable table only lists the SURVIVOR — c2's rows are gone, as
      // CampaignEntity does on pause/delete. Nothing must be restored,
      // and the reconcile result landing in `serving` must not crash the
      // entity (MatchError → entity stop was the seed-race failure shape).
      val repo = new ConstRepo(Vector("c1" -> "a1"))
      val bidder = testKit.spawn(CategoryBidderEntity("IAB3|0", sharding, repo))
      val ack = testKit.createTestProbe[ActiveCampaignsAck]()

      bidder ! ActiveCampaigns(
        Map(CampaignId("c1") -> AdvertiserId("a1"), CampaignId("c2") -> AdvertiserId("a2")), ack.ref)
      ack.expectMessageType[ActiveCampaignsAck]

      // Legitimate shrink: c2 left and its demand rows are gone.
      bidder ! ActiveCampaigns(Map(CampaignId("c1") -> AdvertiserId("a1")), ack.ref)
      ack.expectMessageType[ActiveCampaignsAck]

      // Entity must survive the ReconcileLoaded(no-op) message and stay
      // responsive.
      bidder ! ActiveCampaigns(Map(CampaignId("c1") -> AdvertiserId("a1")), ack.ref)
      ack.expectMessageType[ActiveCampaignsAck]
    }
  }
}
