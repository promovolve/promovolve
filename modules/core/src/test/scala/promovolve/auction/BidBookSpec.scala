package promovolve.auction

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.advertiser.AdvertiserEntity
import promovolve.publisher.CategoryDemandRepo

import scala.concurrent.Future

/**
 * Standing bid book (docs/design/bid-book.md): auctions answered
 * SYNCHRONOUSLY from campaign-pushed quotes — no aggregator, no window,
 * no race. Pins the answer-path semantics: floor split with reject
 * stats, per-site approval/allowlist derivation, hard-TTL staleness
 * exclusion, and the no-quote → legacy-fallback hybrid.
 */
class BidBookSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val testConfig = ConfigFactory.parseString(
    """
      |pekko {
      |  loglevel = "WARNING"
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
    def listByCategory(categoryId: String): Future[Vector[(String, String)]] = Future.successful(rows)
  }

  import CategoryBidderEntity.*

  private def creative(id: String, approvedOn: Set[String] = Set.empty) =
    AdvertiserEntity.Creative(id = CreativeId(id), isActive = true, approvedSites = approvedOn)

  private def quote(
      camp: String,
      adv: String,
      maxCpm: Double,
      creatives: Set[AdvertiserEntity.Creative],
      allowlist: Set[String] = Set.empty,
      eligible: Boolean = true,
      ageMs: Long = 0L
  ) = BidQuote(
    campaignId = CampaignId(camp),
    advertiserId = AdvertiserId(adv),
    maxCpm = CPM(maxCpm),
    creatives = creatives,
    adProductCategory = None,
    landingDomain = camp + ".example",
    siteAllowlist = allowlist,
    eligible = eligible,
    quotedAtMs = System.currentTimeMillis() - ageMs
  )

  private def spawnBookBidder(id: String, seed: Vector[(String, String)]) =
    testKit.spawn(CategoryBidderEntity(id, sharding, new ConstRepo(seed), bidBookEnabled = true))

  private def bidRequest(replyTo: org.apache.pekko.actor.typed.ActorRef[CategoryBidResponse], floor: Double) =
    CategoryBidRequest(SiteId("site-a"), "https://s/a", SlotId("S1"), Set.empty, CPM(floor),
      siteAudience = Set.empty, replyTo = replyTo)

  "the standing bid book" should {

    "answer synchronously from quotes: floor split, bid=max(maxCpm,floor), per-site approval" in {
      val bidder = spawnBookBidder("BB1|0", Vector("jra" -> "advA", "low" -> "advB"))
      val probe = testKit.createTestProbe[CategoryBidResponse]()
      bidder ! quote("jra", "advA", 10.0, Set(creative("c1", approvedOn = Set("site-a"))))
      bidder ! quote("low", "advB", 3.0, Set(creative("c2", approvedOn = Set("site-a"))))

      bidder ! bidRequest(probe.ref, floor = 9.9)
      val resp = probe.receiveMessage()
      resp.campaigns.map(_.campaignId.value) shouldBe Vector("jra")
      resp.campaigns.head.cpm.toDouble shouldBe 10.0 // max(maxCpm, floor)
      resp.campaigns.head.hasApprovedCreative shouldBe true
      // The $3 bidder is a below-floor reject teaching the sweep range down.
      resp.rejectedByFloor shouldBe 1
      resp.maxRejectedCpm shouldBe 3.0
      resp.approvedRejectedByFloor shouldBe 1
    }

    "exclude quotes past the hard TTL — a silent campaign is absent demand" in {
      val bidder = spawnBookBidder("BB2|0", Vector("dead" -> "advA", "live" -> "advB"))
      val probe = testKit.createTestProbe[CategoryBidResponse]()
      bidder ! quote("dead", "advA", 12.0, Set(creative("c3", approvedOn = Set("site-a"))),
        ageMs = BookHardTtlMs + 1000)
      bidder ! quote("live", "advB", 8.0, Set(creative("c4", approvedOn = Set("site-a"))))

      bidder ! bidRequest(probe.ref, floor = 0.1)
      val resp = probe.receiveMessage()
      resp.campaigns.map(_.campaignId.value) shouldBe Vector("live")
      resp.rejectedByFloor shouldBe 0 // expired ≠ floor-rejected
    }

    "respect the site allowlist and ineligible quotes at answer time" in {
      val bidder = spawnBookBidder("BB3|0", Vector("allowB" -> "advA", "paused" -> "advB"))
      val probe = testKit.createTestProbe[CategoryBidResponse]()
      bidder ! quote("allowB", "advA", 10.0, Set(creative("c5")), allowlist = Set("site-b"))
      bidder ! quote("paused", "advB", 10.0, Set(creative("c6")), eligible = false)

      bidder ! bidRequest(probe.ref, floor = 0.1) // requesting site-a
      val resp = probe.receiveMessage()
      resp.campaigns shouldBe empty
      resp.rejectedByFloor shouldBe 0
    }

    "quotes for campaigns outside the registry never answer" in {
      val bidder = spawnBookBidder("BB4|0", Vector("member" -> "advA"))
      val probe = testKit.createTestProbe[CategoryBidResponse]()
      bidder ! quote("member", "advA", 6.0, Set(creative("c7", approvedOn = Set("site-a"))))
      bidder ! quote("stranger", "advX", 20.0, Set(creative("c8", approvedOn = Set("site-a"))))

      bidder ! bidRequest(probe.ref, floor = 0.1)
      val resp = probe.receiveMessage()
      resp.campaigns.map(_.campaignId.value) shouldBe Vector("member")
    }
  }
}
