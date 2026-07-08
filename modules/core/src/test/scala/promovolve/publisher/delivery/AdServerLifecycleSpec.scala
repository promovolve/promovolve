package promovolve.publisher.delivery

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.advertiser.{AdvertiserEntity, CampaignEntity}
import promovolve.publisher.{
  Creative, CreativeRepo, CreativeStatus, FirstSeen, FlaggedCreative,
  NoOpCreativeStatsSnapshotRepo, NoOpTrafficShapeSnapshotRepo,
  PendingSelectionStore,
}
import promovolve.publisher.delivery.Protocol.{
  BatchSelect, BatchSelectResult, BatchSlotSpec, CandidatesCollected,
  PacingConfigUpdated, SpendInfoUpdated, VerifiedHostUpdated,
}

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Random

/** Smoke test for AdServer's per-request lifecycle (recordRequestArrival)
  * routed through case BatchSelect. Targets the highest-risk regression:
  * simulated-day rollover failing to fan out ResetDayStart to participating
  * campaigns / advertisers (would hang RunScenario simulations).
  *
  * Setup pattern: probe-forwarding sharding entities so every command
  * routed through `sharding.entityRefFor(...) ! ...` lands in a TestProbe
  * for assertion.
  */
class AdServerLifecycleSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

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
  given ec: scala.concurrent.ExecutionContext = system.executionContext

  private val cluster = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)

  lazy val sharding: ClusterSharding = ClusterSharding(testKit.system)

  // Probes capturing every command sent to sharded entities. Entities are
  // initialized with forwarder behaviors that push incoming messages into
  // the corresponding probes.
  val campaignProbe: TestProbe[CampaignEntity.Command] =
    testKit.createTestProbe[CampaignEntity.Command]()
  val advertiserProbe: TestProbe[AdvertiserEntity.Command] =
    testKit.createTestProbe[AdvertiserEntity.Command]()

  sharding.init(Entity(CampaignEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[CampaignEntity.Command] { msg =>
      campaignProbe.ref ! msg
      Behaviors.same
    }
  ))
  sharding.init(Entity(AdvertiserEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse] {
      case cmd: AdvertiserEntity.Command =>
        advertiserProbe.ref ! cmd
        Behaviors.same
      case _ =>
        Behaviors.same
    }
  ))
  // AdServer now sends AuctioneerEntity.Reevaluate on a serve-miss (self-heal),
  // so the auctioneer shard type must be registered or the message would route
  // to an unstarted region. Forward to a probe like the other entities.
  val auctioneerProbe: TestProbe[promovolve.auction.AuctioneerEntity.Command] =
    testKit.createTestProbe[promovolve.auction.AuctioneerEntity.Command]()
  sharding.init(Entity(promovolve.auction.AuctioneerEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[promovolve.auction.AuctioneerEntity.Command] { msg =>
      auctioneerProbe.ref ! msg
      Behaviors.same
    }
  ))

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val mockBudgetTopic: ActorRef[Topic.Command[BudgetEvent]] =
    testKit.spawn(Behaviors.ignore[Topic.Command[BudgetEvent]])

  // Minimal store/repo stubs — every method returns an empty/successful
  // Future. AdServer doesn't exercise their real semantics in this test
  // because we never reach the candidate selection path.
  private class IgnoringPendingStore extends PendingSelectionStore {
    def upsertPending(sel: Selection): Future[Unit] = Future.successful(())
    def getPending(p: String, u: String, s: String): Future[Option[Selection]] = Future.successful(None)
    def removePending(p: String, u: String, s: String): Future[Boolean] = Future.successful(true)
    def removeCreativeFromPending(p: String, u: String, s: String, c: String): Future[Option[Selection]] = Future.successful(None)
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
    def flagCreative(p: String, u: String, s: String, c: String, r: String): Future[Option[FlaggedCreative]] = Future.successful(None)
    def unflagCreative(p: String, c: String): Future[Option[FlaggedCreative]] = Future.successful(None)
    def getFlagged(p: String): Future[Vector[FlaggedCreative]] = Future.successful(Vector.empty)
    def insertApproved(p: String, c: String, ca: String, a: String): Future[Unit] = Future.successful(())
    def getApprovedCreativeIds(p: String): Future[Set[String]] = Future.successful(Set.empty)
    def getApprovedCreativeAdvertisers(p: String): Future[Map[String, String]] = Future.successful(Map.empty)
    def getApprovedCreativeAdvertisersByCampaign(p: String, c: String): Future[Map[String, String]] = Future.successful(Map.empty)
    def deleteApproved(p: String, c: String): Future[Boolean] = Future.successful(true)
    def deleteApprovedByCampaignId(p: String, c: String): Future[Int] = Future.successful(0)
    def deleteApprovedByAdvertiserId(p: String, a: String): Future[Int] = Future.successful(0)
  }

  private class IgnoringCreativeRepo extends CreativeRepo {
    def put(creative: Creative): Future[Unit]                       = Future.successful(())
    def get(creativeId: String): Future[Option[Creative]]           = Future.successful(None)
    def getByImageHash(hash: String): Future[Vector[Creative]]      = Future.successful(Vector.empty)
    def getByCampaign(campaignId: String): Future[Vector[Creative]] = Future.successful(Vector.empty)
    def updateVerification(
        creativeId: String, confidence: Double, reason: String,
        adultContent: Boolean, violence: Boolean, hateSpeech: Boolean,
        safetyScore: Option[Double], suggestedContentCategories: List[String],
    ): Future[Unit] = Future.successful(())
    def updateStatus(creativeId: String, status: CreativeStatus): Future[Unit] = Future.successful(())
    def delete(creativeId: String): Future[Unit] = Future.successful(())
    def getPendingRender(): Future[Vector[Creative]] = Future.successful(Vector.empty)
  }

  private def makeCandidate(creativeId: String, campaignId: String, advertiserId: String): Candidate =
    Candidate(
      creativeId   = CreativeId(creativeId),
      campaignId   = CampaignId(campaignId),
      advertiserId = AdvertiserId(advertiserId),
      cpm          = CPM(1.0),
      category     = CategoryId("test"),
    )

  "BatchSelect lifecycle" should {

    "fan out ResetDayStart to participating campaigns + advertisers on simulated-day rollover" in {
      val serveIndexProbe = testKit.createTestProbe[ServeIndexDData.Cmd]()
      val selectProbe     = testKit.createTestProbe[BatchSelectResult]()
      val pubSiteId       = SiteId("test-pub-rollover")
      val campaignId      = CampaignId("camp-1")
      val advertiserId    = AdvertiserId("adv-1")

      val adServer = testKit.spawn(
        AdServer(
          publisherId              = pubSiteId,
          store                    = new IgnoringPendingStore,
          creativeRepo             = new IgnoringCreativeRepo,
          serveIndex               = serveIndexProbe.ref,
          sharding                 = sharding,
          statsSnapshotRepo        = NoOpCreativeStatsSnapshotRepo,
          trafficShapeSnapshotRepo = NoOpTrafficShapeSnapshotRepo,
          budgetEventTopic         = mockBudgetTopic,
          pacingStrategy           = FixedThrottlePacing(0.0),
          rng                      = new Random(42),
        )
      )

      // Configure simulated day length: 2 seconds — short enough to elapse
      // mid-test without the slow real-clock dependency dominating.
      val config = promovolve.publisher.SiteEntity.PacingConfig(
        dayDurationSeconds = 2,
      )
      adServer ! PacingConfigUpdated(Some(config))

      // Authorize the test URL.
      adServer ! VerifiedHostUpdated(Some("test.example.com"))

      // Prime spendInfoCache so lastDayStart is initialized from the
      // SpendUpdate's dayStart on the first BatchSelect arrival. Without
      // this, lastDayStart stays None and the rollover detector sees
      // "first request" rather than "we crossed a day boundary".
      val initialDayStart = Instant.now()
      adServer ! SpendInfoUpdated(Some(SpendUpdate(
        campaignId   = campaignId,
        advertiserId = advertiserId,
        dailyBudget  = Budget(BigDecimal(100.0)),
        todaySpend   = Spend(BigDecimal(0.0)),
        dayStart     = initialDayStart,
        timestamp    = initialDayStart,
      )))

      // Register the campaign so the rollover fan-out targets it.
      adServer ! CandidatesCollected(
        url          = URL("http://test.example.com/page"),
        slotId       = SlotId("slot-1"),
        candidates   = Vector(makeCandidate("creative-1", campaignId.value, advertiserId.value)),
        classifiedAt = Instant.now(),
        ttl          = 1.hour,
      )

      // Drain anything the entity probes received during setup.
      campaignProbe.receiveMessages(0, 100.millis)
      advertiserProbe.receiveMessages(0, 100.millis)

      // First BatchSelect: lifecycle runs, no rollover yet (we're inside
      // the simulated day window).
      adServer ! BatchSelect(
        url                    = URL("http://test.example.com/page"),
        slots                  = Vector(BatchSlotSpec(SlotId("slot-1"), 300, 250)),
        contentRecencyWindowMs = 0L,
        replyTo                = selectProbe.ref,
      )
      // Lifecycle is synchronous before the view fetch; absence of
      // ResetDayStart confirms no rollover detection.
      campaignProbe.expectNoMessage(200.millis)
      advertiserProbe.expectNoMessage(200.millis)

      // Sleep past the simulated day boundary. Real-clock dependency —
      // unavoidable until/unless the helper takes an injectable clock.
      Thread.sleep(2_500)

      // Second BatchSelect: lifecycle detects rollover, fans out
      // ResetDayStart to the participating campaign + advertiser.
      adServer ! BatchSelect(
        url                    = URL("http://test.example.com/page"),
        slots                  = Vector(BatchSlotSpec(SlotId("slot-1"), 300, 250)),
        contentRecencyWindowMs = 0L,
        replyTo                = selectProbe.ref,
      )

      val campaignReset = campaignProbe.expectMessageType[CampaignEntity.ResetDayStart](2.seconds)
      campaignReset.dayDurationSeconds shouldBe 2

      // Don't assert on `silent` — the helper sends with the default value.
      advertiserProbe.expectMessageType[AdvertiserEntity.ResetDayStart](2.seconds)

      // Drain any trailing ServeIndex query so the test ends cleanly.
      serveIndexProbe.receiveMessages(0, 200.millis)
    }

    "evict a narrowed-off campaign from the whole site (mirrors CampaignPaused)" in {
      val serveIndexProbe = testKit.createTestProbe[ServeIndexDData.Cmd]()
      val pubSiteId       = SiteId("test-pub-evict")
      val campaignId      = CampaignId("camp-evict")

      val adServer = testKit.spawn(
        AdServer(
          publisherId              = pubSiteId,
          store                    = new IgnoringPendingStore,
          creativeRepo             = new IgnoringCreativeRepo,
          serveIndex               = serveIndexProbe.ref,
          sharding                 = sharding,
          statsSnapshotRepo        = NoOpCreativeStatsSnapshotRepo,
          trafficShapeSnapshotRepo = NoOpTrafficShapeSnapshotRepo,
          budgetEventTopic         = mockBudgetTopic,
          pacingStrategy           = FixedThrottlePacing(0.0),
          rng                      = new Random(42),
        )
      )

      // Site-narrow eviction: the advertiser dropped this site from a non-empty
      // siteAllowlist, so the campaign must be wiped from every slot here —
      // pins included. The handler mirrors CampaignPaused: RemoveCampaignBySite
      // is the ServeIndex-side removal we assert on.
      adServer ! Protocol.EvictCampaignFromSite(campaignId)

      val msgs = serveIndexProbe.receiveMessages(1, 2.seconds)
      msgs should contain(ServeIndexDData.RemoveCampaignBySite(pubSiteId.value, campaignId))
    }

    "treat EvictCampaignFromSite as idempotent for an unknown campaign" in {
      val serveIndexProbe = testKit.createTestProbe[ServeIndexDData.Cmd]()
      val pubSiteId       = SiteId("test-pub-evict-idem")
      val unknown         = CampaignId("never-registered")

      val adServer = testKit.spawn(
        AdServer(
          publisherId              = pubSiteId,
          store                    = new IgnoringPendingStore,
          creativeRepo             = new IgnoringCreativeRepo,
          serveIndex               = serveIndexProbe.ref,
          sharding                 = sharding,
          statsSnapshotRepo        = NoOpCreativeStatsSnapshotRepo,
          trafficShapeSnapshotRepo = NoOpTrafficShapeSnapshotRepo,
          budgetEventTopic         = mockBudgetTopic,
          pacingStrategy           = FixedThrottlePacing(0.0),
          rng                      = new Random(42),
        )
      )

      // No crash; the ServeIndex removal is still sent (DData no-ops when the
      // campaign isn't present in any key).
      adServer ! Protocol.EvictCampaignFromSite(unknown)
      val msgs = serveIndexProbe.receiveMessages(1, 2.seconds)
      msgs should contain(ServeIndexDData.RemoveCampaignBySite(pubSiteId.value, unknown))
    }

    "evict a topic-narrowed campaign from each given slot key, pin-aware" in {
      val serveIndexProbe = testKit.createTestProbe[ServeIndexDData.Cmd]()
      val pubSiteId       = SiteId("test-pub-slots")
      val campaignId      = CampaignId("camp-topic")
      val keyA            = s"${pubSiteId.value}|SLOT-A"
      val keyB            = s"${pubSiteId.value}|SLOT-B"

      val adServer = testKit.spawn(
        AdServer(
          publisherId              = pubSiteId,
          store                    = new IgnoringPendingStore,
          creativeRepo             = new IgnoringCreativeRepo,
          serveIndex               = serveIndexProbe.ref,
          sharding                 = sharding,
          statsSnapshotRepo        = NoOpCreativeStatsSnapshotRepo,
          trafficShapeSnapshotRepo = NoOpTrafficShapeSnapshotRepo,
          budgetEventTopic         = mockBudgetTopic,
          pacingStrategy           = FixedThrottlePacing(0.0),
          rng                      = new Random(42),
        )
      )

      // Topic-narrow eviction: one RemoveCampaignFromKey per slot key, each
      // carrying keepCreativeIds = pinnedCreativeIds (empty on a fresh actor).
      adServer ! Protocol.EvictCampaignFromSlots(campaignId, Set(keyA, keyB))

      val msgs = serveIndexProbe.receiveMessages(2, 2.seconds)
      msgs should contain(
        ServeIndexDData.RemoveCampaignFromKey(keyA, campaignId, keepCreativeIds = Set.empty)
      )
      msgs should contain(
        ServeIndexDData.RemoveCampaignFromKey(keyB, campaignId, keepCreativeIds = Set.empty)
      )
    }

    "treat EvictCampaignFromSlots with an empty key set as a no-op" in {
      val serveIndexProbe = testKit.createTestProbe[ServeIndexDData.Cmd]()
      val pubSiteId       = SiteId("test-pub-slots-empty")

      val adServer = testKit.spawn(
        AdServer(
          publisherId              = pubSiteId,
          store                    = new IgnoringPendingStore,
          creativeRepo             = new IgnoringCreativeRepo,
          serveIndex               = serveIndexProbe.ref,
          sharding                 = sharding,
          statsSnapshotRepo        = NoOpCreativeStatsSnapshotRepo,
          trafficShapeSnapshotRepo = NoOpTrafficShapeSnapshotRepo,
          budgetEventTopic         = mockBudgetTopic,
          pacingStrategy           = FixedThrottlePacing(0.0),
          rng                      = new Random(42),
        )
      )

      adServer ! Protocol.EvictCampaignFromSlots(CampaignId("camp-none"), Set.empty)
      serveIndexProbe.expectNoMessage(500.millis)
    }
  }
}
