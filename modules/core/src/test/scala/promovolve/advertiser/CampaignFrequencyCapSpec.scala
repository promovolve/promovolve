package promovolve.advertiser

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.publisher.CategoryDemandRepo

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

/**
 * Frequency cap on the campaign (docs/design/FREQUENCY_CAPPING.md): the pure
 * API-validation rules, and the UpdateConfig tri-state against a REAL
 * CampaignEntity (DurableStateBehavior on the persistence-testkit state
 * plugin) — set, no-change, clear — read back through GetCampaign. This is
 * the first spec to drive UpdateConfig end to end; the scaffold mirrors
 * CampaignEntityRolloverSpec (advertiser stub answers only the warm-up, directory
 * ignores everything — a paused campaign never registers anyway).
 */
class CampaignFrequencyCapSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import CampaignEntity.FrequencyCap

  "FrequencyCap rules" should {
    "know exactly three windows, in milliseconds" in {
      FrequencyCap.windowMs("hour") shouldBe Some(3_600_000L)
      FrequencyCap.windowMs("day") shouldBe Some(86_400_000L)
      FrequencyCap.windowMs("week") shouldBe Some(604_800_000L)
      FrequencyCap.windowMs("month") shouldBe None
      FrequencyCap.windowMs("") shouldBe None
    }
    "accept 0 as 'no cap' whatever the window says" in {
      FrequencyCap.validate(0, "day") shouldBe None
      FrequencyCap.validate(0, "") shouldBe None
      FrequencyCap.fromApi(0, "day") shouldBe None
    }
    "accept 1..100 with a known window" in {
      FrequencyCap.validate(1, "hour") shouldBe None
      FrequencyCap.validate(100, "week") shouldBe None
      FrequencyCap.fromApi(3, "day") shouldBe Some(FrequencyCap(3, "day"))
    }
    "reject out-of-range impressions and unknown windows, and say why" in {
      FrequencyCap.validate(-1, "day").exists(_.contains("between 1 and 100")) shouldBe true
      FrequencyCap.validate(101, "day").exists(_.contains("between 1 and 100")) shouldBe true
      FrequencyCap.validate(2, "fortnight").exists(_.contains("window must be one of")) shouldBe true
    }
  }

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

  sharding.init(Entity(AdvertiserEntity.TypeKey)(entityCtx =>
    Behaviors.receiveMessage[AdvertiserEntity.Command | AdvertiserEntity.DDataUpdateResponse] {
      case AdvertiserEntity.GetTimezone(replyTo) if entityCtx.entityId == "warm-up" =>
        replyTo ! AdvertiserEntity.AdvertiserTimezone(AdvertiserId("warm-up"), "UTC")
        Behaviors.same
      case _ =>
        Behaviors.same
    }
  ))

  // Don't start until the shard coordinator answers. Every campaign tells the
  // advertiser entity on recovery and on each spend flush; if the suite ends
  // before this one-node cluster is Up (a busy CI JVM), the node leaves without
  // ever starting the coordinator, those messages sit in the region's buffer,
  // and graceful shutdown waits them out past shutdownTestKit's 10 seconds.
  locally {
    val probe = testKit.createTestProbe[AdvertiserEntity.AdvertiserTimezone]()
    sharding.entityRefFor(AdvertiserEntity.TypeKey, "warm-up") ! AdvertiserEntity.GetTimezone(probe.ref)
    probe.receiveMessage(20.seconds)
  }

  private val directory: ActorRef[CampaignDirectory.Command] =
    testKit.spawn(Behaviors.ignore[CampaignDirectory.Command])
  private val budgetTopic: ActorRef[Topic.Command[BudgetEvent]] =
    testKit.spawn(Topic[BudgetEvent]("campaign-frequency-cap-budget-events"))

  private object NoopDemandRepo extends CategoryDemandRepo {
    def upsertCampaign(categoryIds: Set[String], campaignId: String, advertiserId: String): Future[Unit] =
      Future.successful(())
    def removeCampaign(campaignId: String): Future[Unit] = Future.successful(())
    def listByCategory(categoryId: String): Future[Vector[(String, String)]] = Future.successful(Vector.empty)
  }

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private def spawnCampaign(campaignId: String): ActorRef[CampaignEntity.Command] =
    testKit.spawn(
      CampaignEntity(
        campaignId = CampaignId(campaignId),
        advertiserId = AdvertiserId("adv-freq"),
        directory = directory,
        sharding = sharding,
        categoryDemandRepo = NoopDemandRepo,
        budgetEventTopic = budgetTopic
      )
    )

  private def updateCap(
      c: ActorRef[CampaignEntity.Command],
      cap: Option[Option[FrequencyCap]]
  ): Unit = {
    val probe = testKit.createTestProbe[CampaignEntity.ConfigUpdated]()
    c ! CampaignEntity.UpdateConfig(maxCpm = None, dailyBudget = None, frequencyCap = cap, replyTo = probe.ref)
    probe.receiveMessage(5.seconds)
  }

  private def info(c: ActorRef[CampaignEntity.Command]): CampaignEntity.CampaignInfo = {
    val probe = testKit.createTestProbe[CampaignEntity.CampaignInfo]()
    c ! CampaignEntity.GetCampaign(probe.ref)
    probe.receiveMessage(5.seconds)
  }

  "CampaignEntity.UpdateConfig.frequencyCap" should {
    "default to uncapped" in {
      val c = spawnCampaign("camp-cap-default")
      info(c).frequencyCap shouldBe None
    }
    "set, leave alone on None, and clear on Some(None)" in {
      val c = spawnCampaign("camp-cap-tristate")
      updateCap(c, Some(Some(FrequencyCap(3, "day"))))
      info(c).frequencyCap shouldBe Some(FrequencyCap(3, "day"))

      updateCap(c, None) // an unrelated config edit must not touch it
      info(c).frequencyCap shouldBe Some(FrequencyCap(3, "day"))

      updateCap(c, Some(Some(FrequencyCap(1, "hour"))))
      info(c).frequencyCap shouldBe Some(FrequencyCap(1, "hour"))

      updateCap(c, Some(None))
      info(c).frequencyCap shouldBe None
    }
  }

  // The cap edit did not fail on its own — it never ran. The campaign edit
  // form resubmits EVERY field, so an untouched budget arrives as a
  // ReplenishBudget on every save; the API applies the patch in sequence and
  // a budget rejection aborts it before the config update (which is where
  // the cap lives) is ever reached. Once the day's spend caught up with the
  // budget, that made the whole panel unsaveable — the cap being the part
  // the advertiser noticed.
  "CampaignEntity.ReplenishBudget" should {

    def replenish(c: ActorRef[CampaignEntity.Command], amount: BigDecimal): CampaignEntity.ReplenishResult = {
      val probe = testKit.createTestProbe[CampaignEntity.ReplenishResult]()
      c ! CampaignEntity.ReplenishBudget(Budget(amount), probe.ref)
      probe.receiveMessage(5.seconds)
    }

    def spend(c: ActorRef[CampaignEntity.Command], requestId: String, amount: BigDecimal): Unit = {
      val probe = testKit.createTestProbe[CampaignEntity.SpendRecorded]()
      c ! CampaignEntity.RecordSpend(requestId, Spend(amount), java.time.Instant.now(), probe.ref)
      probe.receiveMessage(5.seconds)
    }

    "accept the budget the campaign already has, even when the day's spend has reached it" in {
      val c = spawnCampaign("camp-budget-unchanged")
      replenish(c, BigDecimal(10)) shouldBe a[CampaignEntity.BudgetReplenished]
      spend(c, "req-exhaust", BigDecimal(10))

      // The exact value the edit form re-renders. Nothing is being asked
      // for, so there is nothing to refuse.
      replenish(c, BigDecimal(10)) shouldBe a[CampaignEntity.BudgetReplenished]
      info(c).dailyBudget shouldBe Budget(BigDecimal(10))
    }

    // Scale, not identity: the form renders "10" and may submit "10.00".
    "treat a differently-scaled but equal amount as unchanged" in {
      val c = spawnCampaign("camp-budget-scale")
      replenish(c, BigDecimal(10)) shouldBe a[CampaignEntity.BudgetReplenished]
      spend(c, "req-exhaust-scale", BigDecimal(10))
      replenish(c, BigDecimal("10.00")) shouldBe a[CampaignEntity.BudgetReplenished]
    }

    // The guard still has a job: a genuinely NEW budget that the campaign has
    // already spent past is a real mistake and must still be refused.
    "still refuse a new budget that today's spend has already passed" in {
      val c = spawnCampaign("camp-budget-lowered")
      replenish(c, BigDecimal(10)) shouldBe a[CampaignEntity.BudgetReplenished]
      spend(c, "req-exhaust-lower", BigDecimal(8))

      replenish(c, BigDecimal(5)) shouldBe a[CampaignEntity.ReplenishRejected]
      info(c).dailyBudget shouldBe Budget(BigDecimal(10))
    }

    "still accept a raise" in {
      val c = spawnCampaign("camp-budget-raise")
      replenish(c, BigDecimal(10)) shouldBe a[CampaignEntity.BudgetReplenished]
      spend(c, "req-exhaust-raise", BigDecimal(10))
      replenish(c, BigDecimal(20)) shouldBe a[CampaignEntity.BudgetReplenished]
      info(c).dailyBudget shouldBe Budget(BigDecimal(20))
    }
  }
}
