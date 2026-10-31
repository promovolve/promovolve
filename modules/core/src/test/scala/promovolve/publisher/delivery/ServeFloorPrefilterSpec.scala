package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType }

/**
 * The batch-serve pre-filter must keep every candidate that some requested
 * slot would accept. A slot's admin floor override beats the category
 * floor (the auctioneer admitted the bid under it), so a bid that clears
 * the override but not the category floor stays in the pool; the picker
 * then applies each slot's own floor.
 */
class ServeFloorPrefilterSpec extends AnyWordSpec with Matchers {

  import promovolve.publisher.delivery.Protocol.*

  private val c = CandidateView(
    creativeId = CreativeId("x"),
    campaignId = CampaignId("k"),
    advertiserId = AdvertiserId("a"),
    assetUrl = CDNPath("/assets/x.png"),
    mime = MimeType.imagePng,
    width = 300,
    height = 250,
    category = CategoryId("sports"),
    cpm = CPM(1.50),
    classifiedAtMs = 0L
  )
  private val categoryFloor = CPM(5.0)
  private val overridden = BatchSlotSpec(SlotId("cheap"), 300, 250)
  private val plain = BatchSlotSpec(SlotId("plain"), 300, 250)

  "clearsAnySlotFloor" should {
    "keep a bid that clears a slot's admin override but not the category floor" in {
      AdServer.clearsAnySlotFloor(c, Vector(plain, overridden), categoryFloor,
        Map(SlotId("cheap") -> CPM(1.0))) shouldBe true
    }
    "drop it when no requested slot carries an override it clears" in {
      AdServer.clearsAnySlotFloor(c, Vector(plain), categoryFloor, Map(SlotId("cheap") -> CPM(1.0))) shouldBe false
      AdServer.clearsAnySlotFloor(c, Vector(overridden), categoryFloor, Map(SlotId("cheap") -> CPM(2.0))) shouldBe false
    }
    "fall back to the category floor when there are no overrides" in {
      AdServer.clearsAnySlotFloor(c, Vector(plain), CPM(1.5), Map.empty) shouldBe true
    }
  }

  "batchAssign" should {
    "serve the override-cleared bid in the overridden slot only" in {
      val view = promovolve.publisher.ServeView(Vector(c), version = 1L, expiresAtMs = Long.MaxValue)
      val out = AdServer.batchAssign(view, Vector(plain, overridden), CPM(0.5), Set.empty, 0.5, Map.empty,
        new scala.util.Random(7L), categoryFloors = Map(CategoryId("sports") -> categoryFloor),
        adminSlotFloors = Map(SlotId("cheap") -> CPM(1.0)))
      out.find(_.slotId == SlotId("cheap")).flatMap(_.winner).map(_.creativeId) shouldBe Some(CreativeId("x"))
      out.find(_.slotId == SlotId("plain")).flatMap(_.winner) shouldBe None
    }
  }
}
