package promovolve.serialization

import com.fasterxml.jackson.databind.node.ObjectNode
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.serialization.{ SerializationExtension, SerializerWithStringManifest }
import org.apache.pekko.serialization.jackson.JacksonObjectMapperProvider
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import promovolve.*
import promovolve.advertiser.{ AdvertiserEntity, CampaignEntity }

import java.time.Instant

/**
 * Jackson recovery compatibility for the per-advertiser-timezone fields.
 *
 * CampaignEntity.State / AdvertiserEntity.State are persisted (and
 * SpendUpdate published cross-node) via jackson-cbor. Snapshots written
 * BEFORE the timezone feature lack `timezone` (and, for the advertiser,
 * `lastResetAt`) — recovery of those bytes must default cleanly
 * ("" / Instant.EPOCH), never throw.
 *
 * Old-shape payloads are produced by serializing a current object through
 * the REAL registered serializer, deleting the new fields from the CBOR
 * tree with the serializer's own ObjectMapper, and deserializing the result
 * through the same serializer + manifest — byte-for-byte the recovery path.
 */
class TimezoneJacksonCompatSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit(
    "timezone-jackson-compat",
    ConfigFactory.parseString(
      """
      pekko.actor.provider = cluster
      pekko.remote.artery.canonical.port = 0
      pekko.actor.serializers.jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
      pekko.actor.serialization-bindings {
        "promovolve.CborSerializable" = jackson-cbor
      }
      """
    )
  )

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val serialization = SerializationExtension(testKit.system.toClassic)
  private val cborMapper =
    JacksonObjectMapperProvider(testKit.system.toClassic).getOrCreate("jackson-cbor", None)

  /**
   * Serialize with the registered persistence serializer, drop `fields` from
   * the encoded tree (asserting they were present — i.e. we really removed
   * something), and deserialize the old-shape bytes with the same manifest.
   */
  private def roundTripWithoutFields(obj: AnyRef, fields: String*): AnyRef = {
    val serializer = serialization.findSerializerFor(obj)
    serializer shouldBe a[SerializerWithStringManifest]
    val s = serializer.asInstanceOf[SerializerWithStringManifest]
    val manifest = s.manifest(obj)
    val bytes = s.toBinary(obj)

    val tree = cborMapper.readTree(bytes)
    tree shouldBe an[ObjectNode]
    val node = tree.asInstanceOf[ObjectNode]
    fields.foreach { f =>
      withClue(s"new-shape payload should carry '$f': ") { node.has(f) shouldBe true }
      node.remove(f)
    }
    val legacyBytes = cborMapper.writeValueAsBytes(node)

    serialization.deserialize(legacyBytes, s.identifier, manifest).get
  }

  "CampaignEntity.State recovery" should {

    "default timezone to \"\" (UTC) when the snapshot predates the field" in {
      val current = CampaignEntity.State
        .empty(CampaignId("camp-compat"), AdvertiserId("adv-compat"))
        .copy(
          spendToday = Spend(12.5),
          lastResetInstant = Instant.parse("2026-07-13T14:30:00Z"),
          timezone = "Asia/Tokyo"
        )

      val restored = roundTripWithoutFields(current, "timezone").asInstanceOf[CampaignEntity.State]

      restored.timezone shouldBe ""
      // Everything else survives untouched.
      restored.campaignId shouldBe current.campaignId
      restored.advertiserId shouldBe current.advertiserId
      restored.spendToday shouldBe current.spendToday
      restored.lastResetInstant shouldBe current.lastResetInstant
      restored.dailyBudget shouldBe current.dailyBudget
    }

    "keep a present timezone across a plain round trip" in {
      val current = CampaignEntity.State
        .empty(CampaignId("camp-compat-rt"), AdvertiserId("adv-compat-rt"))
        .copy(timezone = "Asia/Tokyo")
      val restored = roundTripWithoutFields(current).asInstanceOf[CampaignEntity.State]
      restored.timezone shouldBe "Asia/Tokyo"
    }
  }

  "AdvertiserEntity.State recovery" should {

    "default timezone to \"\" and lastResetAt to EPOCH when the snapshot predates them" in {
      val current = AdvertiserEntity.State
        .empty(AdvertiserId("adv-compat"))
        .copy(
          spendToday = Spend(3.25),
          lastResetEpochDay = 20_600L,
          timezone = "Asia/Tokyo",
          lastResetAt = Instant.parse("2026-07-13T15:00:00Z")
        )

      val restored =
        roundTripWithoutFields(current, "timezone", "lastResetAt").asInstanceOf[AdvertiserEntity.State]

      restored.timezone shouldBe ""
      restored.lastResetAt shouldBe Instant.EPOCH // legacy marker → needsRoll day-number fallback
      restored.advertiserId shouldBe current.advertiserId
      restored.spendToday shouldBe current.spendToday
      restored.lastResetEpochDay shouldBe current.lastResetEpochDay
    }

    "keep present timezone fields across a plain round trip" in {
      val at = Instant.parse("2026-07-13T15:00:00Z")
      val current = AdvertiserEntity.State
        .empty(AdvertiserId("adv-compat-rt"))
        .copy(timezone = "Asia/Tokyo", lastResetAt = at)
      val restored = roundTripWithoutFields(current).asInstanceOf[AdvertiserEntity.State]
      restored.timezone shouldBe "Asia/Tokyo"
      restored.lastResetAt shouldBe at
    }
  }

  "SpendUpdate deserialization" should {

    "default timezone to \"\" for in-flight events from pre-timezone senders" in {
      val current = SpendUpdate(
        campaignId = CampaignId("camp-ev"),
        advertiserId = AdvertiserId("adv-ev"),
        dailyBudget = Budget(100.0),
        todaySpend = Spend(42.0),
        dayStart = Instant.parse("2026-07-13T15:00:00Z"),
        timestamp = Instant.parse("2026-07-13T18:00:00Z"),
        timezone = "Asia/Tokyo"
      )

      val restored = roundTripWithoutFields(current, "timezone").asInstanceOf[SpendUpdate]

      restored shouldBe current.copy(timezone = "")
    }
  }
}
