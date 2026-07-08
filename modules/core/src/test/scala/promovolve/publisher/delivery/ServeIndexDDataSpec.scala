package promovolve.publisher.delivery

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.publisher.{CandidateView, CDNPath, MimeType, ServeView}

import java.net.URI
import scala.concurrent.duration.*

/** RemoveCampaignFromKey pin-awareness (eviction-on-narrow, Case 2).
  *
  * Topic-narrow eviction removes a campaign's candidates from a slot key EXCEPT
  * reader-pinned creatives, so a pin on a still-served page survives a category
  * drop. The default (empty keepCreativeIds) preserves the original behaviour:
  * drop ALL of the campaign's candidates.
  */
class ServeIndexDDataSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val testConfig = ConfigFactory.parseString(
    """
      |pekko {
      |  loglevel = "WARNING"
      |  actor {
      |    provider = "cluster"
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

  val testKit: ActorTestKit = ActorTestKit(testConfig)
  private val cluster       = Cluster(testKit.system)
  cluster.manager ! Join(cluster.selfMember.address)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private def candidate(cid: String, campaign: String): CandidateView =
    CandidateView(
      creativeId     = CreativeId(cid),
      campaignId     = CampaignId(campaign),
      advertiserId   = AdvertiserId(s"adv-$campaign"),
      assetUrl       = CDNPath(new URI(s"/assets/$cid.png")),
      mime           = MimeType.imagePng,
      width          = 300,
      height         = 250,
      category       = CategoryId("100"),
      cpm            = CPM(5.0),
      classifiedAtMs = 0L,
    )

  private def viewWith(cs: CandidateView*): ServeView =
    ServeView(candidates = cs.toVector, version = 1L, expiresAtMs = Long.MaxValue)

  private def getView(idx: org.apache.pekko.actor.typed.ActorRef[ServeIndexDData.Cmd], key: String): Option[ServeView] = {
    val probe: TestProbe[Option[ServeView]] = testKit.createTestProbe[Option[ServeView]]()
    idx ! ServeIndexDData.Get(key, probe.ref)
    probe.receiveMessage(2.seconds)
  }

  "ServeIndexDData.RemoveCampaignFromKey" should {

    "drop ALL of a campaign's candidates by default (empty keepCreativeIds)" in {
      val idx = testKit.spawn(ServeIndexDData())
      val key = "pub-a|SLOT-1"
      idx ! ServeIndexDData.Put(
        key,
        viewWith(
          candidate("c1", "campA"),
          candidate("c2", "campA"),
          candidate("c3", "campB"),
        ),
      )
      eventually(getView(idx, key).map(_.candidates.size) shouldBe Some(3))

      idx ! ServeIndexDData.RemoveCampaignFromKey(key, CampaignId("campA"))

      eventually {
        val v = getView(idx, key)
        v.map(_.candidates.map(_.creativeId.value).toSet) shouldBe Some(Set("c3"))
      }
    }

    "retain pinned creatives of the campaign while dropping its others" in {
      val idx = testKit.spawn(ServeIndexDData())
      val key = "pub-b|SLOT-1"
      idx ! ServeIndexDData.Put(
        key,
        viewWith(
          candidate("c1", "campA"), // pinned -> kept
          candidate("c2", "campA"), // not pinned -> dropped
          candidate("c3", "campB"), // other campaign -> kept
        ),
      )
      eventually(getView(idx, key).map(_.candidates.size) shouldBe Some(3))

      idx ! ServeIndexDData.RemoveCampaignFromKey(
        key,
        CampaignId("campA"),
        keepCreativeIds = Set(CreativeId("c1")),
      )

      eventually {
        val v = getView(idx, key)
        v.map(_.candidates.map(_.creativeId.value).toSet) shouldBe Some(Set("c1", "c3"))
      }
    }

    "remove the whole key when filtering leaves no candidates" in {
      val idx = testKit.spawn(ServeIndexDData())
      val key = "pub-c|SLOT-1"
      idx ! ServeIndexDData.Put(key, viewWith(candidate("c1", "campA")))
      eventually(getView(idx, key).map(_.candidates.size) shouldBe Some(1))

      idx ! ServeIndexDData.RemoveCampaignFromKey(key, CampaignId("campA"))

      eventually(getView(idx, key).shouldBe(None))
    }
  }

  /** Tiny poll-until-true helper (DData updates are async/eventually consistent). */
  private def eventually(assertion: => org.scalatest.Assertion): org.scalatest.Assertion = {
    val deadline = System.nanoTime() + 3.seconds.toNanos
    var last: Throwable = null
    while (System.nanoTime() < deadline) {
      try return assertion
      catch { case t: Throwable => last = t; Thread.sleep(50) }
    }
    if (last != null) throw last else assertion
  }
}
