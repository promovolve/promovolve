package promovolve.advertiser

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
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

import java.time.Instant
import scala.concurrent.duration.*

/**
 * Command-level tests for the ADVERTISER account timezone: SetTimezone /
 * GetTimezone semantics, campaign fan-out, and RecordCampaignSpend rolling
 * the daily window at the account zone's midnight (instant-based needsRoll).
 *
 * Runs a real AdvertiserEntity (DurableStateBehavior on the persistence-
 * testkit state plugin) in a single-node cluster; the sharded CampaignEntity
 * is a probe-forwarder so fan-out tells are observable. Rolls are driven via
 * RecordCampaignSpend's `ts` (a roll stamps lastResetAt = ts), never sleeps.
 *
 * Pure State-level helpers (needsRoll / withTimezone / rollWindow) are
 * covered in AdvertiserEntitySpec, which stays actor-free.
 */
class AdvertiserEntityTimezoneSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

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

  private val cluster = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)

  lazy val sharding: ClusterSharding = ClusterSharding(testKit.system)

  private val JST = "Asia/Tokyo"

  // Every command the advertiser fans out to its (sharded) campaigns lands here.
  val campaignProbe: TestProbe[CampaignEntity.Command] =
    testKit.createTestProbe[CampaignEntity.Command]()

  sharding.init(Entity(CampaignEntity.TypeKey)(_ =>
    Behaviors.receiveMessage[CampaignEntity.Command] { msg =>
      campaignProbe.ref ! msg
      Behaviors.same
    }
  ))

  private val mockBudgetTopic: ActorRef[Topic.Command[BudgetEvent]] =
    testKit.spawn(Behaviors.ignore[Topic.Command[BudgetEvent]])

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // ==================== helpers ====================

  private def spawnAdvertiser(id: String): ActorRef[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse] =
    testKit.spawn(AdvertiserEntity(AdvertiserId(id), sharding, mockBudgetTopic))

  private def setTimezone(
      a: ActorRef[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse],
      tz: String
  ): AdvertiserEntity.TimezoneUpdated = {
    val probe = testKit.createTestProbe[AdvertiserEntity.TimezoneUpdated]()
    a ! AdvertiserEntity.SetTimezone(tz, probe.ref)
    probe.receiveMessage(3.seconds)
  }

  private def getTimezone(
      a: ActorRef[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse]
  ): String = {
    val probe = testKit.createTestProbe[AdvertiserEntity.AdvertiserTimezone]()
    a ! AdvertiserEntity.GetTimezone(probe.ref)
    probe.receiveMessage(3.seconds).timezone
  }

  private def recordSpend(
      a: ActorRef[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse],
      flushId: String,
      amount: Double,
      ts: Instant
  ): AdvertiserEntity.CampaignSpendRecorded = {
    val probe = testKit.createTestProbe[AdvertiserEntity.CampaignSpendRecorded]()
    a ! AdvertiserEntity.RecordCampaignSpend(FlushId(flushId), CampaignId("camp-1"), Spend(amount), ts, probe.ref)
    probe.receiveMessage(3.seconds)
  }

  private def createCampaign(
      a: ActorRef[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse]
  ): Unit = {
    val probe = testKit.createTestProbe[AdvertiserEntity.CreateCampaignResult]()
    a ! AdvertiserEntity.CreateCampaign(probe.ref)
    probe.receiveMessage(3.seconds)
  }

  /** Swallow anything already queued on the shared campaign probe. */
  private def drainCampaignProbe(): Unit = {
    def loop(): Unit =
      try {
        campaignProbe.receiveMessage(200.millis)
        loop()
      } catch { case _: AssertionError => () }
    loop()
  }

  /** A JST midnight (15:00Z) well in the future — deterministic roll anchors. */
  private def futureJstMidnight(): Instant =
    Timezones.nextMidnightAfter(Instant.now().plusSeconds(3 * 86400L), JST)

  private def futureUtcMidnight(): Instant =
    Timezones.nextMidnightAfter(Instant.now().plusSeconds(3 * 86400L), "")

  // ==================== tests ====================

  "AdvertiserEntity.SetTimezone" should {

    "persist the zone and reply TimezoneUpdated with it" in {
      val a = spawnAdvertiser("adv-tz-persist")
      setTimezone(a, JST) shouldBe AdvertiserEntity.TimezoneUpdated(AdvertiserId("adv-tz-persist"), JST)
      getTimezone(a) shouldBe JST

      // Survives a restart → the zone was really persisted, not held in memory.
      testKit.stop(a)
      val revived = spawnAdvertiser("adv-tz-persist")
      getTimezone(revived) shouldBe JST
    }

    "reject an invalid zone: reply carries the zone actually in effect, nothing changes" in {
      val a = spawnAdvertiser("adv-tz-invalid")
      setTimezone(a, "Not/AZone").timezone shouldBe ""
      getTimezone(a) shouldBe ""

      setTimezone(a, JST).timezone shouldBe JST
      setTimezone(a, "Not/AZone").timezone shouldBe JST
      getTimezone(a) shouldBe JST
    }

    "fan out the new zone to its campaigns, but not on an unchanged zone" in {
      val a = spawnAdvertiser("adv-tz-fanout")
      createCampaign(a) // zone is "" at creation → no stamp tell
      drainCampaignProbe()

      setTimezone(a, JST).timezone shouldBe JST
      campaignProbe.expectMessage(3.seconds, CampaignEntity.SetTimezone(JST))

      // Unchanged zone: no persist, no fan-out.
      setTimezone(a, JST).timezone shouldBe JST
      campaignProbe.expectNoMessage(300.millis)
    }

    "stamp the account zone onto a newly created campaign" in {
      val a = spawnAdvertiser("adv-tz-stamp")
      setTimezone(a, JST)
      drainCampaignProbe()

      createCampaign(a)
      campaignProbe.expectMessage(3.seconds, CampaignEntity.SetTimezone(JST))
    }
  }

  "AdvertiserEntity.RecordCampaignSpend" should {

    "roll at the account zone's midnight, not at UTC midnight" in {
      val a = spawnAdvertiser("adv-roll-jst")
      setTimezone(a, JST)

      val m = futureJstMidnight() // 15:00Z day D = 00:00 JST day F
      // First spend on a later JST day than the withTimezone stamp → rolls
      // and anchors lastResetAt = ts (01:00 JST day F).
      recordSpend(a, "f0", 10.0, m.plusSeconds(3600)).totalSpendToday.toDouble shouldBe 10.0
      // 23:00 JST, same JST day — but a LATER UTC day (14:00Z day D+1):
      // crossing UTC midnight must NOT roll a JST account.
      recordSpend(a, "f1", 5.0, m.plusSeconds(23 * 3600)).totalSpendToday.toDouble shouldBe 15.0
      // 00:10 JST day F+1 (same UTC day as the previous spend) → rolls.
      recordSpend(a, "f2", 7.0, m.plusSeconds(24 * 3600 + 600)).totalSpendToday.toDouble shouldBe 7.0
    }

    "roll a default (UTC) account exactly once across UTC midnight" in {
      val a = spawnAdvertiser("adv-roll-utc")
      val um = futureUtcMidnight()
      recordSpend(a, "u0", 10.0, um.minusSeconds(2 * 3600)).totalSpendToday.toDouble shouldBe 10.0
      recordSpend(a, "u1", 5.0, um.minusSeconds(60)).totalSpendToday.toDouble shouldBe 15.0
      recordSpend(a, "u2", 7.0, um.plusSeconds(60)).totalSpendToday.toDouble shouldBe 7.0
      recordSpend(a, "u3", 4.0, um.plusSeconds(120)).totalSpendToday.toDouble shouldBe 11.0
    }

    "relabel (not reset) the current budget day on a zone change after a UTC roll" in {
      val a = spawnAdvertiser("adv-legacy-relabel")
      val um = futureUtcMidnight() // 00:00Z day D+1 = 09:00 JST
      // Roll under UTC ("" zone): stamps lastResetAt (23:30Z day D = 08:30 JST).
      recordSpend(a, "g0", 10.0, um.minusSeconds(1800)).totalSpendToday.toDouble shouldBe 10.0

      // Zone change: lastResetAt is non-EPOCH, so withTimezone keeps it and
      // NEVER touches spend — the day is merely relabeled to end at the next
      // JST midnight.
      setTimezone(a, JST).timezone shouldBe JST
      recordSpend(a, "g1", 5.0, um.minusSeconds(60)).totalSpendToday.toDouble shouldBe 15.0
      // 00:30Z day D+1 = 09:30 JST, SAME JST day as the anchor: the boundary
      // the account used to roll at (UTC midnight) no longer applies.
      recordSpend(a, "g2", 3.0, um.plusSeconds(1800)).totalSpendToday.toDouble shouldBe 18.0
      // First spend past the next JST midnight → the one and only roll.
      val nextJstMidnight = Timezones.nextMidnightAfter(um, JST)
      recordSpend(a, "g3", 2.0, nextJstMidnight.plusSeconds(60)).totalSpendToday.toDouble shouldBe 2.0
    }
  }
}
