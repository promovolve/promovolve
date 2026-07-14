package promovolve.advertiser

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ ActorTestKit, FishingOutcomes }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.common.Timezones
import promovolve.publisher.CategoryDemandRepo

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

/**
 * Budget-day rollover at the ADVERTISER account zone's midnight ("" = UTC),
 * exercised against a real CampaignEntity (DurableStateBehavior on the
 * persistence-testkit state plugin).
 *
 * Rolls are driven entirely through RecordSpend's `ts` parameter — never via
 * sleeps: a roll happens when `ts` lands on a later zone-local calendar day
 * than `state.lastResetInstant`, and a roll stamps `lastResetInstant = ts`,
 * so choosing future anchors makes every subsequent boundary deterministic
 * regardless of the wall clock.
 *
 * The sharded AdvertiserEntity is a stub: it answers the campaign's
 * GetTimezone self-heal ask only for advertisers registered in
 * `advertiserZones` (otherwise the ask times out and no-ops), so each test
 * controls the zone explicitly via SetTimezone tells or the pre-seeded map.
 */
class CampaignEntityRolloverSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

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
  given system: ActorSystem[?] = testKit.system
  given ec: ExecutionContext = system.executionContext

  private val cluster = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)

  lazy val sharding: ClusterSharding = ClusterSharding(testKit.system)

  private val JST = "Asia/Tokyo"

  /** Account zones served by the advertiser stub; absent = GetTimezone times out (no-op self-heal). */
  private val advertiserZones = new java.util.concurrent.ConcurrentHashMap[String, String]()

  sharding.init(Entity(AdvertiserEntity.TypeKey)(entityCtx =>
    Behaviors.receiveMessage[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse] {
      case AdvertiserEntity.GetTimezone(replyTo) =>
        Option(advertiserZones.get(entityCtx.entityId)).foreach { tz =>
          replyTo ! AdvertiserEntity.AdvertiserTimezone(AdvertiserId(entityCtx.entityId), tz)
        }
        Behaviors.same
      case AdvertiserEntity.RecordCampaignSpend(_, campaignId, amount, _, replyTo) =>
        // Ack flush reports so at-least-once delivery settles quietly.
        replyTo ! AdvertiserEntity.CampaignSpendRecorded(
          AdvertiserId(entityCtx.entityId), campaignId, amount, Budget(1000.0)
        )
        Behaviors.same
      case _ =>
        Behaviors.same
    }
  ))

  private val directory: ActorRef[CampaignDirectory.Command] =
    testKit.spawn(Behaviors.ignore[CampaignDirectory.Command])

  private val budgetTopic: ActorRef[Topic.Command[BudgetEvent]] =
    testKit.spawn(Topic[BudgetEvent]("campaign-rollover-budget-events"))

  private object NoopDemandRepo extends CategoryDemandRepo {
    def upsertCampaign(categoryIds: Set[String], campaignId: String, advertiserId: String): Future[Unit] =
      Future.successful(())
    def removeCampaign(campaignId: String): Future[Unit] = Future.successful(())
    def listByCategory(categoryId: String): Future[Vector[(String, String)]] = Future.successful(Vector.empty)
  }

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // ==================== helpers ====================

  private def spawnCampaign(campaignId: String, advertiserId: String): ActorRef[CampaignEntity.Command] =
    testKit.spawn(
      CampaignEntity(
        campaignId = CampaignId(campaignId),
        advertiserId = AdvertiserId(advertiserId),
        directory = directory,
        sharding = sharding,
        categoryDemandRepo = NoopDemandRepo,
        budgetEventTopic = budgetTopic
      )
    )

  private def recordSpend(
      c: ActorRef[CampaignEntity.Command],
      amount: Double,
      ts: Instant
  ): CampaignEntity.SpendRecorded = {
    val probe = testKit.createTestProbe[CampaignEntity.SpendRecorded]()
    c ! CampaignEntity.RecordSpend(UUID.randomUUID().toString, Spend(amount), ts, probe.ref)
    probe.receiveMessage(3.seconds)
  }

  private def spendInfo(c: ActorRef[CampaignEntity.Command]): CampaignEntity.SpendInfo = {
    val probe = testKit.createTestProbe[CampaignEntity.SpendInfo]()
    c ! CampaignEntity.GetSpendInfo(probe.ref)
    probe.receiveMessage(3.seconds)
  }

  private def budgetInfo(c: ActorRef[CampaignEntity.Command]): CampaignEntity.BudgetInfo = {
    val probe = testKit.createTestProbe[CampaignEntity.BudgetInfo]()
    c ! CampaignEntity.GetBudgetInfo(probe.ref)
    probe.receiveMessage(3.seconds)
  }

  /**
   * A JST midnight (15:00Z) comfortably in the future relative to the test's
   * wall clock, so an anchoring RecordSpend at/after it always lands on a
   * later local day than the entity's spawn-time lastResetInstant.
   */
  private def futureJstMidnight(): Instant =
    Timezones.nextMidnightAfter(Instant.now().plusSeconds(3 * 86400L), JST)

  private def futureUtcMidnight(): Instant =
    Timezones.nextMidnightAfter(Instant.now().plusSeconds(3 * 86400L), "")

  // ==================== tests ====================

  "CampaignEntity budget rollover" should {

    "roll a JST campaign's window at JST midnight (15:00Z), within one UTC day" in {
      val c = spawnCampaign("camp-jst-utc-day-roll", "adv-jst-1")
      c ! CampaignEntity.SetTimezone(JST)
      spendInfo(c).timezone shouldBe JST

      val m = futureJstMidnight() // 15:00Z on some UTC day D
      // Anchor the budget day: 14:00Z day D = 23:00 JST — a later JST day
      // than spawn time, so this rolls and stamps lastResetInstant.
      recordSpend(c, 10.0, m.minusSeconds(3600)).spendToday.toDouble shouldBe 10.0
      // 14:59Z, still 23:59 JST of the same JST day → accumulates.
      recordSpend(c, 5.0, m.minusSeconds(60)).spendToday.toDouble shouldBe 15.0
      // 15:01Z, SAME UTC day but 00:01 JST of the next JST day → rolls.
      recordSpend(c, 7.0, m.plusSeconds(60)).spendToday.toDouble shouldBe 7.0
    }

    "NOT roll a JST campaign across UTC midnight (23:59Z → 00:01Z, same JST day)" in {
      val c = spawnCampaign("camp-jst-no-utc-roll", "adv-jst-2")
      c ! CampaignEntity.SetTimezone(JST)
      spendInfo(c).timezone shouldBe JST

      val m = futureJstMidnight() // 15:00Z day D = 00:00 JST
      // Anchor at 16:00Z day D (01:00 JST) — rolls onto the new JST day.
      recordSpend(c, 10.0, m.plusSeconds(3600)).spendToday.toDouble shouldBe 10.0
      // 14:00Z day D+1 (23:00 JST, SAME JST day): crossed UTC midnight → no roll.
      recordSpend(c, 5.0, m.plusSeconds(23 * 3600)).spendToday.toDouble shouldBe 15.0
      // 15:10Z day D+1 (00:10 JST next JST day, same UTC day as the previous
      // spend): JST midnight crossed → rolls.
      recordSpend(c, 7.0, m.plusSeconds(24 * 3600 + 600)).spendToday.toDouble shouldBe 7.0
    }

    "roll a default (UTC) campaign exactly once across UTC midnight" in {
      val c = spawnCampaign("camp-utc-roll", "adv-utc-1")
      spendInfo(c).timezone shouldBe ""

      val um = futureUtcMidnight()
      // Anchor at 22:00Z the day before the boundary under test.
      recordSpend(c, 10.0, um.minusSeconds(2 * 3600)).spendToday.toDouble shouldBe 10.0
      // 23:59Z same UTC day → accumulates.
      recordSpend(c, 5.0, um.minusSeconds(60)).spendToday.toDouble shouldBe 15.0
      // 00:01Z → rolls.
      recordSpend(c, 7.0, um.plusSeconds(60)).spendToday.toDouble shouldBe 7.0
      // 00:02Z, same new day → no second roll.
      recordSpend(c, 4.0, um.plusSeconds(120)).spendToday.toDouble shouldBe 11.0
    }

    "keep spendToday on a mid-day zone change and roll exactly once on the next later-JST-day spend" in {
      val c = spawnCampaign("camp-zone-change", "adv-zone-change")
      val m = futureJstMidnight() // 15:00Z day D
      // Anchor under UTC at 14:30Z day D (23:30 JST).
      recordSpend(c, 10.0, m.minusSeconds(1800)).spendToday.toDouble shouldBe 10.0
      budgetInfo(c).spent.toDouble shouldBe 10.0

      c ! CampaignEntity.SetTimezone(JST)
      spendInfo(c).timezone shouldBe JST
      // The zone change itself never touches spend or lastResetInstant.
      budgetInfo(c).spent.toDouble shouldBe 10.0

      // 15:10Z day D = 00:10 JST — a later JST day than lastResetInstant → one roll.
      recordSpend(c, 4.0, m.plusSeconds(600)).spendToday.toDouble shouldBe 4.0
      // 15:20Z day D, same JST day → no second roll (the re-derived
      // double-roll guard neither blocks the roll above nor allows two).
      recordSpend(c, 2.0, m.plusSeconds(1200)).spendToday.toDouble shouldBe 6.0
    }

    "treat SetTimezone with the current zone as a no-op" in {
      val c = spawnCampaign("camp-same-zone", "adv-same-zone")
      c ! CampaignEntity.SetTimezone(JST)
      spendInfo(c).timezone shouldBe JST

      val m = futureJstMidnight()
      recordSpend(c, 10.0, m.plusSeconds(3600)).spendToday.toDouble shouldBe 10.0

      // Same zone again: state (zone + spend) must be unchanged. Persist-count
      // is not observable through the entity's protocol, so this asserts the
      // no-op at the behavior level only.
      c ! CampaignEntity.SetTimezone(JST)
      spendInfo(c).timezone shouldBe JST
      budgetInfo(c).spent.toDouble shouldBe 10.0
    }

    "ignore an invalid timezone" in {
      val c = spawnCampaign("camp-invalid-zone", "adv-invalid-zone")
      c ! CampaignEntity.SetTimezone("Not/AZone")
      spendInfo(c).timezone shouldBe ""

      c ! CampaignEntity.SetTimezone(JST)
      spendInfo(c).timezone shouldBe JST

      c ! CampaignEntity.SetTimezone("Not/AZone")
      spendInfo(c).timezone shouldBe JST
    }

    "persist the timezone (survives entity restart)" in {
      val c = spawnCampaign("camp-tz-persist", "adv-tz-persist")
      c ! CampaignEntity.SetTimezone(JST)
      // The reply round-trip guarantees the SetTimezone persist completed
      // (DurableStateBehavior stashes commands while persisting).
      spendInfo(c).timezone shouldBe JST

      testKit.stop(c)
      val revived = spawnCampaign("camp-tz-persist", "adv-tz-persist")
      spendInfo(revived).timezone shouldBe JST
    }

    "publish a SpendUpdate carrying the new timezone on zone change" in {
      val campaignId = CampaignId("camp-tz-publish")
      val c = spawnCampaign(campaignId.value, "adv-tz-publish")

      val probe = testKit.createTestProbe[BudgetEvent]()
      budgetTopic ! Topic.Subscribe(probe.ref)
      // Topic subscription registers asynchronously — wait until it's live.
      val statsProbe = testKit.createTestProbe[Topic.TopicStats]()
      probe.awaitAssert(
        {
          budgetTopic ! Topic.GetTopicStats(statsProbe.ref)
          statsProbe.receiveMessage(1.second).localSubscriberCount should be >= 1
        },
        5.seconds
      )

      c ! CampaignEntity.SetTimezone(JST)

      probe.fishForMessage(5.seconds) {
        case u: SpendUpdate if u.campaignId == campaignId && u.timezone == JST =>
          FishingOutcomes.complete
        case _ =>
          FishingOutcomes.continueAndIgnore
      }
      budgetTopic ! Topic.Unsubscribe(probe.ref)
    }

    "self-heal the account timezone from AdvertiserEntity on recovery" in {
      advertiserZones.put("adv-self-heal", JST)
      val c = spawnCampaign("camp-self-heal", "adv-self-heal")
      val probe = testKit.createTestProbe[CampaignEntity.SpendInfo]()
      probe.awaitAssert(
        {
          c ! CampaignEntity.GetSpendInfo(probe.ref)
          probe.receiveMessage(1.second).timezone shouldBe JST
        },
        5.seconds
      )
    }
  }
}
