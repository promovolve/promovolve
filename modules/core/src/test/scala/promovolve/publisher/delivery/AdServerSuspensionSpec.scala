package promovolve.publisher.delivery

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.publisher.{
  ApprovedCreativeMeta,
  Creative,
  CreativeRepo,
  CreativeStatus,
  FirstSeen,
  FlaggedCreative,
  NoOpCreativeStatsSnapshotRepo,
  NoOpTrafficShapeSnapshotRepo,
  PendingSelectionStore,
  SiteEntity,
  TrustAnchor
}
import promovolve.publisher.delivery.Protocol.*

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Random

/**
 * Operator org suspension: while the site's suspended flag (mirrored from
 * SiteEntity.SiteSuspendedKey) is set, every BatchSelect is refused with
 * BatchSiteSuspended BEFORE any classification/auction work — no serve, no
 * impressions, no earnings. Lifting the flag restores normal handling.
 */
class AdServerSuspensionSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

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

  private lazy val sharding = org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding(testKit.system)

  // The unsuspended serve path self-heals an empty ServeIndex by poking the
  // auctioneer; the shard type must exist or entityRefFor throws and kills
  // the actor. Sink probes, same harness shape as AdServerAutoApproveSpec.
  private val auctioneerProbe = testKit.createTestProbe[promovolve.auction.AuctioneerEntity.Command]()
  sharding.init(
    org.apache.pekko.cluster.sharding.typed.scaladsl.Entity(promovolve.auction.AuctioneerEntity.TypeKey)(_ =>
      Behaviors.receiveMessage[promovolve.auction.AuctioneerEntity.Command] { msg =>
        auctioneerProbe.ref ! msg
        Behaviors.same
      }
    ))
  private val advertiserProbe = testKit.createTestProbe[promovolve.advertiser.AdvertiserEntity.Command]()
  sharding.init(
    org.apache.pekko.cluster.sharding.typed.scaladsl.Entity(promovolve.advertiser.AdvertiserEntity.TypeKey)(_ =>
      Behaviors.receiveMessage[
        promovolve.advertiser.AdvertiserEntity.Command | promovolve.advertiser.AdvertiserEntity.DDataUpdateResponse
      ] {
        case cmd: promovolve.advertiser.AdvertiserEntity.Command =>
          advertiserProbe.ref ! cmd
          Behaviors.same
        case _ =>
          Behaviors.same
      }
    ))

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // Empty-successful stubs: AdServer's boot pipeToSelf loads run against
  // them; the suspension paths under test never touch them afterwards.
  private class EmptyStore extends PendingSelectionStore {
    def upsertPending(sel: Selection): Future[Unit] = Future.successful(())
    def getPending(p: String, u: String, s: String): Future[Option[Selection]] = Future.successful(None)
    def removePending(p: String, u: String, s: String): Future[Boolean] = Future.successful(true)
    def removeCreativeFromPending(p: String, u: String, s: String, c: String): Future[Option[Selection]] =
      Future.successful(None)
    def rejectAndPromote(p: String, u: String, s: String): Future[Option[Selection]] = Future.successful(None)
    def pendingQueue(p: String): Future[Vector[(String, String, Candidate)]] = Future.successful(Vector.empty)
    def purgeExpired(now: Instant): Future[Int] = Future.successful(0)
    def removeByCampaignId(p: String, c: String): Future[Int] = Future.successful(0)
    def removeByAdvertiserId(p: String, a: String): Future[Int] = Future.successful(0)
    def removeCreativeFromAll(p: String, c: String): Future[Vector[(String, String)]] = Future.successful(Vector.empty)
    def removeByLandingDomain(p: String, d: String): Future[Int] = Future.successful(0)
    def removeByAdProductCategory(p: String, c: String): Future[Int] = Future.successful(0)
    def recordQueued(p: String, cs: Set[String], now: Instant): Future[Unit] = Future.successful(())
    def getFirstSeen(p: String): Future[Map[String, FirstSeen]] = Future.successful(Map.empty)
    def deleteFirstSeen(p: String, cs: Set[String]): Future[Unit] = Future.successful(())
    def flagCreative(p: String, u: String, s: String, c: String, r: String): Future[Option[FlaggedCreative]] =
      Future.successful(None)
    def unflagCreative(p: String, c: String): Future[Option[FlaggedCreative]] = Future.successful(None)
    def getFlagged(p: String): Future[Vector[FlaggedCreative]] = Future.successful(Vector.empty)
    def insertApproved(p: String, c: String, ca: String, a: String, via: String): Future[Unit] =
      Future.successful(())
    def getApprovedCreativeIds(p: String): Future[Set[String]] = Future.successful(Set.empty)
    def getApprovedCreativeAdvertisers(p: String): Future[Map[String, String]] = Future.successful(Map.empty)
    def getApprovedCreativeMeta(p: String): Future[Vector[ApprovedCreativeMeta]] = Future.successful(Vector.empty)
    def getApprovedCreativeAdvertisersByCampaign(p: String, c: String): Future[Map[String, String]] =
      Future.successful(Map.empty)
    def deleteApproved(p: String, c: String): Future[Boolean] = Future.successful(true)
    def deleteApprovedByCampaignId(p: String, c: String): Future[Int] = Future.successful(0)
    def deleteApprovedByAdvertiserId(p: String, a: String): Future[Int] = Future.successful(0)
    def insertTrustAnchors(p: String, anchors: Seq[(String, String)], src: String): Future[Unit] =
      Future.successful(())
    def deleteTrustAnchorsFor(p: String, c: String, d: Option[String]): Future[Int] = Future.successful(0)
    def deleteTrustAnchor(p: String, t: String, v: String): Future[Boolean] = Future.successful(false)
    def getTrustAnchors(p: String): Future[Vector[TrustAnchor]] = Future.successful(Vector.empty)
  }

  private class EmptyCreativeRepo extends CreativeRepo {
    def put(creative: Creative): Future[Unit] = Future.successful(())
    def get(creativeId: String): Future[Option[Creative]] = Future.successful(None)
    def getByImageHash(hash: String): Future[Vector[Creative]] = Future.successful(Vector.empty)
    def getByCampaign(campaignId: String): Future[Vector[Creative]] = Future.successful(Vector.empty)
    def updateVerification(
        creativeId: String, confidence: Double, reason: String,
        adultContent: Boolean, violence: Boolean, hateSpeech: Boolean,
        safetyScore: Option[Double], suggestedContentCategories: List[String]
    ): Future[Unit] = Future.successful(())
    def updateStatus(creativeId: String, status: CreativeStatus): Future[Unit] = Future.successful(())
    def delete(creativeId: String): Future[Unit] = Future.successful(())
    def getPendingRender(): Future[Vector[Creative]] = Future.successful(Vector.empty)
  }

  private def spawnAdServer(siteId: String) = {
    val serveIndexProbe = testKit.createTestProbe[ServeIndexDData.Cmd]()
    val adServer = testKit.spawn(
      AdServer(
        publisherId = SiteId(siteId),
        store = new EmptyStore,
        creativeRepo = new EmptyCreativeRepo,
        serveIndex = serveIndexProbe.ref,
        sharding = sharding,
        statsSnapshotRepo = NoOpCreativeStatsSnapshotRepo,
        trafficShapeSnapshotRepo = NoOpTrafficShapeSnapshotRepo,
        budgetEventTopic = testKit.spawn(Behaviors.ignore[Topic.Command[BudgetEvent]]),
        pacingStrategy = FixedThrottlePacing(0.0),
        rng = new Random(42)
      )
    )
    adServer ! VerifiedHostUpdated(Some("test.example.com"))
    adServer
  }

  private def batchSelect(adServer: org.apache.pekko.actor.typed.ActorRef[Command]) = {
    val probe = testKit.createTestProbe[BatchSelectResult]()
    adServer ! BatchSelect(
      url = URL("http://test.example.com/page"),
      slots = Vector(BatchSlotSpec(SlotId("slot-1"), 300, 250)),
      classificationFreshnessWindowMs = 0L,
      replyTo = probe.ref
    )
    probe.receiveMessage(3.seconds)
  }

  "AdServer under operator suspension" should {

    "refuse every serve with BatchSiteSuspended while the flag is set, and recover when lifted" in {
      val adServer = spawnAdServer("site-suspend")

      adServer ! SiteSuspendedConfigUpdated(Some(SiteEntity.CachedSiteSuspended(true)))
      batchSelect(adServer) shouldBe BatchSiteSuspended
      // Suspension outranks everything, including host mismatch checks —
      // asserted by it being the FIRST gate (no warn about hosts fired).
      batchSelect(adServer) shouldBe BatchSiteSuspended

      // Lift: serving resumes through the normal path (warmup/empty pool
      // replies are fine — anything but the suspension refusal).
      adServer ! SiteSuspendedConfigUpdated(Some(SiteEntity.CachedSiteSuspended(false)))
      val after = batchSelect(adServer)
      after should not be BatchSiteSuspended
    }

    "serve normally when the flag was never published (absent = not suspended)" in {
      val adServer = spawnAdServer("site-not-suspended")
      batchSelect(adServer) should not be BatchSiteSuspended
    }
  }
}
