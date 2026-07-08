package promovolve.browser

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/** Regression: case objects on CborSerializable protocols must survive an
  * Artery-style serialize→deserialize round trip WITHOUT coming back null.
  *
  * Root cause of the 2026-06/07 multi-node self-down cascades: the LP-worker
  * keep-alive daemon tells `KeepAlive` (a case object) to its worker entity
  * via cluster sharding. Whenever daemon and worker are on different nodes
  * the message is Jackson-CBOR serialized; if deserialization yields null,
  * Artery's inbound stream dies with InvalidMessageException("Message is
  * null") — taking cluster heartbeats down with it → unreachability → SBR
  * downing. Reproduced on both Docker Desktop and a real GKE cluster.
  */
class LPWorkerSerializationSpec
    extends AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  private val testKit = ActorTestKit(
    "lpworker-serialization",
    ConfigFactory.parseString(
      """
      pekko.actor.provider = cluster
      pekko.remote.artery.canonical.port = 0
      pekko.actor.serializers.jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
      pekko.actor.serializers.proto-scalapb = "promovolve.serialization.ScalapbSerializer"
      # Mirrors application.conf: NO "scala.Tuple2" binding — see the tuple
      # tests below for why it must never come back.
      pekko.actor.serialization-bindings {
        "promovolve.CborSerializable" = jackson-cbor
        "scalapb.GeneratedMessage"    = proto-scalapb
        "java.lang.Double"      = jackson-cbor
        "java.lang.Boolean"     = jackson-cbor
        "scala.math.BigDecimal" = jackson-cbor
      }
      """,
    ),
  )

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private def roundTrip(msg: AnyRef): AnyRef = {
    val serialization = SerializationExtension(testKit.system.toClassic)
    val serializer    = serialization.findSerializerFor(msg)
    val manifest = serializer match {
      case s: org.apache.pekko.serialization.SerializerWithStringManifest => s.manifest(msg)
      case _                                                              => ""
    }
    val bytes = serialization.serialize(msg).get
    serialization.deserialize(bytes, serializer.identifier, manifest).get
  }

  "LPWorker cross-node messages" should {
    "round-trip KeepAlive without becoming null" in {
      val back = roundTrip(LPWorker.KeepAlive)
      back should not be null
      back shouldBe LPWorker.KeepAlive
    }
  }

  "other cross-node case objects" should {
    "round-trip CampaignEntity.Stop without becoming null" in {
      val back = roundTrip(promovolve.advertiser.CampaignEntity.Stop)
      back should not be null
    }
    "round-trip CampaignDirectory.Stop without becoming null" in {
      val back = roundTrip(promovolve.advertiser.CampaignDirectory.Stop)
      back should not be null
    }
    "round-trip SiteEntity.Shutdown without becoming null" in {
      val back = roundTrip(promovolve.publisher.SiteEntity.Shutdown)
      back should not be null
    }
    "round-trip delivery Protocol.Done without becoming null" in {
      val back = roundTrip(promovolve.publisher.delivery.Protocol.Done)
      back should not be null
    }
  }

  "proto ask replies (formerly bare tuples/Maps — the /publisher/sites poison)" should {
    // SiteEntity ask replies used to be bare tuples/Maps; at runtime a
    // (Double, Double) is the SPECIALIZED class Tuple2$mcDD$sp, which
    // jackson-cbor silently deserializes to NULL — and Artery kills its
    // whole inbound stream (cluster heartbeats included) on a null message.
    // Caught live on GKE 2026-07-02: artery received [null] for such a
    // reply and the node was downed. Replies are now protobuf messages;
    // the tuple binding was removed.
    "round-trip proto CategoryFloors via ScalapbSerializer without becoming null (formerly a bare Map — unbindable, timed out cross-node)" in {
      val msg  = promovolve.proto.site.CategoryFloors(floors = Map("sports" -> 2.5, "health" -> 1.0))
      val serialization = SerializationExtension(testKit.system.toClassic)
      serialization.findSerializerFor(msg).getClass.getSimpleName shouldBe "ScalapbSerializer"
      val back = roundTrip(msg)
      back should not be null
      back shouldBe msg
    }
    "REJECT bare specialized tuples loudly instead of null-poisoning the transport" in {
      val serialization = SerializationExtension(testKit.system.toClassic)
      val msg: AnyRef   = ((0.5, 2.0)): (Double, Double)
      // With no Tuple2 binding, serializer lookup / serialization must FAIL
      // (loud, sender-side) rather than round-trip to null (silent,
      // receiver-side transport kill).
      serialization.serialize(msg).isFailure shouldBe true
    }
  }
}
