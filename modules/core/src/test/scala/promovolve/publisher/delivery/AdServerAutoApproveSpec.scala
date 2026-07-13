package promovolve.publisher.delivery

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.apache.pekko.pattern.StatusReply
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.advertiser.AdvertiserEntity
import promovolve.publisher.{
  ApprovalStatus,
  ApprovedCreativeMeta,
  AssetPointer,
  Creative,
  CreativeRepo,
  CreativeStatus,
  FirstSeen,
  FlaggedCreative,
  NoOpCreativeStatsSnapshotRepo,
  NoOpTrafficShapeSnapshotRepo,
  PendingSelectionStore,
  TrustAnchor
}
import promovolve.publisher.delivery.Protocol.*
import promovolve.taxonomy.TaxonomyRankerEntity

import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Random

/**
 * Auto-approve trust anchors: with a site's toggle on, a new candidate whose
 * campaign or landing registrable-domain the publisher already MANUALLY
 * approved skips the pending queue. Manual approvals mint the anchors;
 * publisher reject/revoke breaks them; auto-approvals never widen trust
 * (no transitive chaining).
 *
 * Uses the AdServerLifecycleSpec harness pattern: probe-forwarding sharded
 * entities + recording store stubs, driving the AdServer behavior directly.
 */
class AdServerAutoApproveSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with Eventually {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds))

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

  val advertiserProbe: TestProbe[AdvertiserEntity.Command] =
    testKit.createTestProbe[AdvertiserEntity.Command]()

  // Auto-reply to UpdateCreativeApproval so the manual-approve flow's ask
  // completes immediately instead of waiting out its 5s timeout.
  sharding.init(Entity(AdvertiserEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse] {
      case cmd: AdvertiserEntity.UpdateCreativeApproval =>
        advertiserProbe.ref ! cmd
        cmd.replyTo ! AdvertiserEntity.CreativeApprovalUpdated(AdvertiserId("adv-1"), cmd.creativeId, cmd.siteId)
        Behaviors.same
      case cmd: AdvertiserEntity.Command =>
        advertiserProbe.ref ! cmd
        Behaviors.same
      case _ =>
        Behaviors.same
    }
  ))
  // fetchCategoryScores asks the ranker; an unregistered type key would
  // throw. The forwarder never replies — the 200ms ask recovers to 0.5.
  val rankerProbe: TestProbe[TaxonomyRankerEntity.Command] =
    testKit.createTestProbe[TaxonomyRankerEntity.Command]()
  sharding.init(Entity(TaxonomyRankerEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[TaxonomyRankerEntity.Command] { msg =>
      rankerProbe.ref ! msg
      Behaviors.same
    }
  ))
  val auctioneerProbe: TestProbe[promovolve.auction.AuctioneerEntity.Command] =
    testKit.createTestProbe[promovolve.auction.AuctioneerEntity.Command]()
  sharding.init(Entity(promovolve.auction.AuctioneerEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[promovolve.auction.AuctioneerEntity.Command] { msg =>
      auctioneerProbe.ref ! msg
      Behaviors.same
    }
  ))

  override def afterAll(): Unit = testKit.shutdownTestKit()

  /** Store stub that records approval + trust-anchor traffic. */
  private class RecordingStore(initialAnchors: Vector[TrustAnchor] = Vector.empty) extends PendingSelectionStore {
    val insertedApproved = TrieMap.empty[String, String] // creativeId -> via
    val insertedAnchors = TrieMap.empty[(String, String), String] // (type, value) -> sourceCreativeId
    val deletedAnchorsFor = TrieMap.empty[String, Option[String]] // campaignId -> domain
    val queuedPending = TrieMap.empty[String, Int] // creativeId -> times queued
    @volatile var pendingSelection: Option[Selection] = None

    def upsertPending(sel: Selection): Future[Unit] = {
      sel.ordered.foreach(c => queuedPending.updateWith(c.creativeId.value)(n => Some(n.getOrElse(0) + 1)))
      Future.successful(())
    }
    def getPending(p: String, u: String, s: String): Future[Option[Selection]] =
      Future.successful(pendingSelection)
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
    def insertApproved(p: String, c: String, ca: String, a: String, via: String): Future[Unit] = {
      insertedApproved.put(c, via)
      Future.successful(())
    }
    def getApprovedCreativeIds(p: String): Future[Set[String]] = Future.successful(Set.empty)
    def getApprovedCreativeAdvertisers(p: String): Future[Map[String, String]] = Future.successful(Map.empty)
    def getApprovedCreativeMeta(p: String): Future[Vector[ApprovedCreativeMeta]] = Future.successful(Vector.empty)
    def getApprovedCreativeAdvertisersByCampaign(p: String, c: String): Future[Map[String, String]] =
      Future.successful(Map.empty)
    def deleteApproved(p: String, c: String): Future[Boolean] = Future.successful(true)
    def deleteApprovedByCampaignId(p: String, c: String): Future[Int] = Future.successful(0)
    def deleteApprovedByAdvertiserId(p: String, a: String): Future[Int] = Future.successful(0)
    def insertTrustAnchors(p: String, anchors: Seq[(String, String)], src: String): Future[Unit] = {
      anchors.foreach { case (t, v) => insertedAnchors.put((t, v), src) }
      Future.successful(())
    }
    def deleteTrustAnchorsFor(p: String, c: String, d: Option[String]): Future[Int] = {
      deletedAnchorsFor.put(c, d)
      Future.successful(1)
    }
    def deleteTrustAnchor(p: String, t: String, v: String): Future[Boolean] = Future.successful(true)
    def getTrustAnchors(p: String): Future[Vector[TrustAnchor]] = Future.successful(initialAnchors)
  }

  private def testCreative(cid: String, campaignId: String, landingDomain: String): Creative =
    Creative(
      creativeId = cid,
      imageHash = "hash",
      advertiserId = "adv-1",
      campaignId = campaignId,
      name = "test creative",
      landingUrl = s"https://$landingDomain/lp",
      landingDomain = landingDomain,
      createdAt = Instant.now(),
      s3Key = "assets/test.webp",
      mime = "image/webp",
      width = 300,
      height = 250
    )

  private class FixedCreativeRepo(creatives: Map[String, Creative]) extends CreativeRepo {
    def put(creative: Creative): Future[Unit] = Future.successful(())
    def get(creativeId: String): Future[Option[Creative]] = Future.successful(creatives.get(creativeId))
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

  private def makeCandidate(
      creativeId: String,
      campaignId: String,
      landingDomain: String
  ): Candidate =
    Candidate(
      creativeId = CreativeId(creativeId),
      campaignId = CampaignId(campaignId),
      advertiserId = AdvertiserId("adv-1"),
      cpm = CPM(1.0),
      category = CategoryId("test"),
      landingDomain = landingDomain
    )

  /** Spawn an AdServer + drive one auction wave; the probe answers the ServeIndex Get. */
  private case class Fixture(
      adServer: ActorRef[Command],
      serveIndexProbe: TestProbe[ServeIndexDData.Cmd],
      budgetEventProbe: TestProbe[BudgetEvent],
      store: RecordingStore
  ) {
    def sendWave(candidates: Vector[Candidate], slot: String = "slot-1"): Unit = {
      adServer ! CandidatesCollected(
        url = URL("http://test.example.com/page"),
        slotId = SlotId(slot),
        candidates = candidates,
        classifiedAt = Instant.now(),
        ttl = 1.hour
      )
      // Earlier actions (approve/reject/revoke) leave their own ServeIndex
      // messages in the probe queue — fish past them to the wave's Get.
      import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
      val get = serveIndexProbe.fishForMessage(3.seconds) {
        case _: ServeIndexDData.Get => FishingOutcomes.complete
        case _                      => FishingOutcomes.continueAndIgnore
      }.last.asInstanceOf[ServeIndexDData.Get]
      get.replyTo ! None
    }

    /** Wait until the boot trust-anchor load reflects `campaigns`/`domains`. */
    def awaitAnchors(campaigns: Set[String], domains: Set[String]): Unit =
      eventually {
        val probe = testKit.createTestProbe[AutoApproveInfo]()
        adServer ! GetAutoApproveInfo(probe.ref)
        val info = probe.receiveMessage(1.second)
        info.trustedCampaigns shouldBe campaigns
        info.trustedDomains shouldBe domains
      }
  }

  private def spawnFixture(
      siteId: String,
      store: RecordingStore,
      creativeRepo: CreativeRepo = new FixedCreativeRepo(Map.empty),
      autoApproveEnabled: Boolean = true
  ): Fixture = {
    val serveIndexProbe = testKit.createTestProbe[ServeIndexDData.Cmd]()
    // A REAL topic (not a probe): Topic.Publish resolves to a pekko-internal
    // command type that tests can't pattern-match, so we subscribe a probe
    // to the topic and assert on the delivered BudgetEvents instead.
    val budgetTopic = testKit.spawn(Topic[BudgetEvent](s"budget-events-$siteId"))
    val budgetEventProbe = testKit.createTestProbe[BudgetEvent]()
    budgetTopic ! Topic.Subscribe(budgetEventProbe.ref)
    val adServer = testKit.spawn(
      AdServer(
        publisherId = SiteId(siteId),
        store = store,
        creativeRepo = creativeRepo,
        serveIndex = serveIndexProbe.ref,
        sharding = sharding,
        statsSnapshotRepo = NoOpCreativeStatsSnapshotRepo,
        trafficShapeSnapshotRepo = NoOpTrafficShapeSnapshotRepo,
        budgetEventTopic = budgetTopic,
        pacingStrategy = FixedThrottlePacing(0.0),
        rng = new Random(42)
      )
    )
    adServer ! AutoApproveConfigUpdated(
      if (autoApproveEnabled) Some(promovolve.publisher.SiteEntity.CachedAutoApprove(true)) else None
    )
    Fixture(adServer, serveIndexProbe, budgetEventProbe, store)
  }

  "Auto-approve" should {

    "approve a candidate whose campaign has a trust anchor (boot-loaded), publish SSE, and never mint new anchors" in {
      val store = new RecordingStore(initialAnchors =
        Vector(TrustAnchor(TrustAnchor.TypeCampaign, "camp-1", "seed-cid", Instant.now())))
      val fx = spawnFixture("site-auto-campaign", store)
      fx.awaitAnchors(Set("camp-1"), Set.empty)

      fx.sendWave(Vector(makeCandidate("cr-new", "camp-1", "shop.acme.com")))

      eventually {
        store.insertedApproved.get("cr-new") shouldBe Some("auto")
      }
      store.queuedPending.get("cr-new") shouldBe None
      // Floor teaching: approval announced to the AdvertiserEntity.
      val announce = advertiserProbe.expectMessageType[AdvertiserEntity.UpdateCreativeApproval](3.seconds)
      announce.creativeId shouldBe CreativeId("cr-new")
      announce.status shouldBe ApprovalStatus.Approved
      // Live-update event for the approval dashboard, via the real topic.
      val published = fx.budgetEventProbe.fishForMessage(3.seconds) {
        case _: CreativeAutoApproved => org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.complete
        case _                       => org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.continueAndIgnore
      }.last.asInstanceOf[CreativeAutoApproved]
      published.creativeId shouldBe CreativeId("cr-new")
      published.campaignId shouldBe CampaignId("camp-1")
      // Auto-approval consumes trust, never widens it.
      store.insertedAnchors shouldBe empty
    }

    "approve a candidate from a DIFFERENT campaign whose landing registrable-domain is trusted" in {
      val store = new RecordingStore(initialAnchors =
        Vector(TrustAnchor(TrustAnchor.TypeDomain, "acme.com", "seed-cid", Instant.now())))
      val fx = spawnFixture("site-auto-domain", store)
      fx.awaitAnchors(Set.empty, Set("acme.com"))

      // Full-host landingDomain must normalize to the trusted eTLD+1.
      fx.sendWave(Vector(makeCandidate("cr-dom", "camp-other", "www.acme.com")))

      eventually {
        store.insertedApproved.get("cr-dom") shouldBe Some("auto")
      }
      store.queuedPending.get("cr-dom") shouldBe None
    }

    "queue candidates for manual review when the toggle is off, even with matching anchors" in {
      val store = new RecordingStore(initialAnchors =
        Vector(TrustAnchor(TrustAnchor.TypeCampaign, "camp-1", "seed-cid", Instant.now())))
      val fx = spawnFixture("site-auto-off", store, autoApproveEnabled = false)
      fx.awaitAnchors(Set("camp-1"), Set.empty)

      fx.sendWave(Vector(makeCandidate("cr-off", "camp-1", "shop.acme.com")))

      eventually {
        store.queuedPending.get("cr-off") shouldBe Some(1)
      }
      store.insertedApproved.get("cr-off") shouldBe None
    }

    "queue candidates with no matching anchor even when the toggle is on" in {
      val store = new RecordingStore(initialAnchors =
        Vector(TrustAnchor(TrustAnchor.TypeCampaign, "camp-1", "seed-cid", Instant.now())))
      val fx = spawnFixture("site-auto-nomatch", store)
      fx.awaitAnchors(Set("camp-1"), Set.empty)

      fx.sendWave(Vector(makeCandidate("cr-strange", "camp-unknown", "other.example.org")))

      eventually {
        store.queuedPending.get("cr-strange") shouldBe Some(1)
      }
      store.insertedApproved.get("cr-strange") shouldBe None
    }

    "mint campaign + eTLD+1 domain anchors on a MANUAL approval" in {
      val cid = "cr-manual"
      val store = new RecordingStore()
      val repo = new FixedCreativeRepo(Map(cid -> testCreative(cid, "camp-m", "shop.acme.com")))
      val fx = spawnFixture("site-manual-anchor", store, creativeRepo = repo)
      val candidate = makeCandidate(cid, "camp-m", "shop.acme.com")
      val now = Instant.now()
      store.pendingSelection = Some(Selection(
        publisherId = SiteId("site-manual-anchor"),
        url = URL("http://test.example.com/page"),
        slotId = SlotId("slot-1"),
        ordered = Vector(candidate),
        idx = 0,
        state = SelState.Pending,
        createdAt = now,
        expiresAt = now.plusSeconds(3600)
      ))

      val replyProbe = testKit.createTestProbe[StatusReply[AssetPointer]]()
      fx.adServer ! Approve("http://test.example.com/page", "slot-1", cid, replyProbe.ref)

      replyProbe.expectMessageType[StatusReply[AssetPointer]](5.seconds).isSuccess shouldBe true
      eventually {
        store.insertedApproved.get(cid) shouldBe Some("manual")
        store.insertedAnchors.get((TrustAnchor.TypeCampaign, "camp-m")) shouldBe Some(cid)
        store.insertedAnchors.get((TrustAnchor.TypeDomain, "acme.com")) shouldBe Some(cid)
      }
      // The freshly-minted anchors are live: a sibling auto-approves.
      fx.sendWave(Vector(makeCandidate("cr-sibling", "camp-m", "www.acme.com")), slot = "slot-2")
      eventually {
        store.insertedApproved.get("cr-sibling") shouldBe Some("auto")
      }
    }

    "break trust on publisher REJECT — siblings return to the manual queue" in {
      val cid = "cr-rejected"
      val store = new RecordingStore(initialAnchors = Vector(
        TrustAnchor(TrustAnchor.TypeCampaign, "camp-r", "seed", Instant.now()),
        TrustAnchor(TrustAnchor.TypeDomain, "acme.com", "seed",
          Instant.now())
      ))
      val fx = spawnFixture("site-reject-break", store)
      fx.awaitAnchors(Set("camp-r"), Set("acme.com"))

      val candidate = makeCandidate(cid, "camp-r", "shop.acme.com")
      val now = Instant.now()
      store.pendingSelection = Some(Selection(
        publisherId = SiteId("site-reject-break"),
        url = URL("http://test.example.com/page"),
        slotId = SlotId("slot-1"),
        ordered = Vector(candidate),
        idx = 0,
        state = SelState.Pending,
        createdAt = now,
        expiresAt = now.plusSeconds(3600)
      ))

      val replyProbe = testKit.createTestProbe[StatusReply[Done.type]]()
      fx.adServer ! Reject("http://test.example.com/page", "slot-1", cid, Some("nope"), replyProbe.ref)
      replyProbe.expectMessageType[StatusReply[Done.type]](5.seconds)

      eventually {
        store.deletedAnchorsFor.get("camp-r") shouldBe Some(Some("acme.com"))
      }
      fx.awaitAnchors(Set.empty, Set.empty)
      // A sibling from the formerly-trusted campaign now queues manually.
      fx.sendWave(Vector(makeCandidate("cr-after-reject", "camp-r", "www.acme.com")), slot = "slot-2")
      eventually {
        store.queuedPending.get("cr-after-reject") shouldBe Some(1)
      }
      store.insertedApproved.get("cr-after-reject") shouldBe None
    }

    "break trust on publisher REVOKE — no auto-re-approve loop" in {
      val cid = "cr-revoked"
      val store = new RecordingStore(initialAnchors = Vector(
        TrustAnchor(TrustAnchor.TypeCampaign, "camp-v", "seed", Instant.now()),
        TrustAnchor(TrustAnchor.TypeDomain, "acme.com", "seed",
          Instant.now())
      ))
      val repo = new FixedCreativeRepo(Map(cid -> testCreative(cid, "camp-v", "shop.acme.com")))
      val fx = spawnFixture("site-revoke-break", store, creativeRepo = repo)
      fx.awaitAnchors(Set("camp-v"), Set("acme.com"))

      fx.adServer ! RevokeCreativeApproval(CreativeId(cid))

      eventually {
        store.deletedAnchorsFor.get("camp-v") shouldBe Some(Some("acme.com"))
      }
      fx.awaitAnchors(Set.empty, Set.empty)
      // The revoked creative's next auction win queues manually — not an
      // instant auto-re-approve (which would make revoke a no-op loop).
      fx.sendWave(Vector(makeCandidate(cid, "camp-v", "shop.acme.com")), slot = "slot-2")
      eventually {
        store.queuedPending.get(cid) shouldBe Some(1)
      }
      store.insertedApproved.get(cid) shouldBe None
    }
  }
}
