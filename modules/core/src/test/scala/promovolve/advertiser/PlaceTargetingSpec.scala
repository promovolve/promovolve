package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.publisher.delivery.candidates.CandidateLogic
import promovolve.taxonomy.Places

import java.time.Instant

/**
 * Tier 1 targeting — what the ARTICLE is about, as opposed to who reads the
 * site. The two axes are independent by design: a Paris hotel buys
 * `placeTargeting={FR}`, Japanese travel insurance buys
 * `audienceTargeting={JP}`, and an airline buys both.
 *
 * Unlike audience, this one is RELEVANCE rather than eligibility, so a
 * broader match stays eligible and loses on score.
 */
class PlaceTargetingSpec extends AnyWordSpec with Matchers {

  private val Kamakura = "GN1860672" // Kamakura -> JP-14 -> JP
  private val siteId = SiteId("site-a")
  private val cat = CategoryId("483")

  private def campaign(places: Set[String] = Set.empty): CampaignEntity.State =
    CampaignEntity.State(
      campaignId = CampaignId("c1"),
      advertiserId = AdvertiserId("a1"),
      status = CampaignEntity.Status.Active,
      categories = Set(cat),
      categoryBlocklist = Set.empty,
      maxCpm = CPM(5.0),
      dailyBudget = Budget(100.0),
      creativeAssignments = Set.empty,
      spendToday = Spend.zero,
      lastResetInstant = Instant.now(),
      pendingReports = Map.empty,
      processedFilter = Array.emptyByteArray,
      placeTargeting = places
    )

  "Places.matchHops" should {

    // Untargeted is a PERFECT fit, not a distant one — otherwise every
    // campaign without place targeting would be quietly penalised on every
    // page that happens to have places.
    "report zero distance for an untargeted campaign" in {
      Places.matchHops(Set.empty, Set(Kamakura)) shouldBe Some(0)
      Places.matchHops(Set.empty, Set.empty) shouldBe Some(0)
    }

    "report zero for a direct hit" in {
      Places.matchHops(Set(Kamakura), Set(Kamakura)) shouldBe Some(0)
    }

    "report the distance for a broader target" in {
      Places.matchHops(Set("JP-14"), Set(Kamakura)) shouldBe Some(1)
      Places.matchHops(Set("JP"), Set(Kamakura)) shouldBe Some(2)
    }

    "report the NEAREST of several targets" in {
      Places.matchHops(Set("JP", "JP-14"), Set(Kamakura)) shouldBe Some(1)
    }

    "refuse an unrelated place" in {
      Places.matchHops(Set("FR"), Set(Kamakura)) shouldBe None
    }

    // No descendant match, consistent with categories and with audience.
    "refuse a narrower target than the page supports" in {
      Places.matchHops(Set(Kamakura), Set("JP")) shouldBe None
    }

    // A page the classifier said nothing about is not a contradiction, but
    // it is also not a match — a geographic buy should not land on a page
    // nobody established is about that place.
    "refuse a targeted campaign on a page about nowhere" in {
      Places.matchHops(Set("JP"), Set.empty) shouldBe None
    }
  }

  "canBid" should {

    "admit an untargeted campaign on any page" in {
      campaign().canBid(siteId, cat, CPM(1.0), pagePlaces = Set(Kamakura)) shouldBe true
      campaign().canBid(siteId, cat, CPM(1.0), pagePlaces = Set.empty) shouldBe true
    }

    "admit a broader target and refuse an unrelated one" in {
      campaign(Set("JP")).canBid(siteId, cat, CPM(1.0), pagePlaces = Set(Kamakura)) shouldBe true
      campaign(Set("FR")).canBid(siteId, cat, CPM(1.0), pagePlaces = Set(Kamakura)) shouldBe false
    }

    "name the place gate when it is what refused" in {
      campaign(Set("FR")).bidRejectReason(siteId, cat, CPM(1.0), pagePlaces = Set(Kamakura)) shouldBe
      Some(CampaignEntity.BidRejectReason.PlaceNotAllowed)
    }

    // The two geographic axes are independent, and both AND with category.
    "compose with audience targeting rather than replacing it" in {
      val c = campaign(Set("JP")).copy(audienceTargeting = Set("FR"))
      c.canBid(siteId, cat, CPM(1.0), siteAudience = Set("JP"), pagePlaces = Set(Kamakura)) shouldBe false
    }
  }

  "the live-ask and bid-book paths" should {
    "agree for every combination of targeting and page places" in {
      val targetings = Vector(Set.empty[String], Set("JP"), Set("JP-14"), Set(Kamakura), Set("FR"))
      val pages = Vector(Set.empty[String], Set("JP"), Set("JP-14"), Set(Kamakura), Set("FR"))
      for (t <- targetings; p <- pages) {
        val viaCanBid = campaign(t).canBid(siteId, cat, CPM(1.0), pagePlaces = p)
        val viaBook = CampaignEntity.placeAdmits(t, p).isDefined
        withClue(s"targeting=$t page=$p: ") { viaCanBid shouldBe viaBook }
      }
    }
  }

  "serve-time scoring" should {

    def score(placeHops: Int, ancestorHops: Int = 0): Double = {
      val candidate = Candidate(
        creativeId = CreativeId("cr1"),
        campaignId = CampaignId("c1"),
        advertiserId = AdvertiserId("a1"),
        cpm = CPM(1.0),
        category = cat,
        ancestorHops = ancestorHops,
        placeHops = placeHops
      )
      val creative = promovolve.publisher.Creative(
        creativeId = "cr1", imageHash = "h", advertiserId = "a1", campaignId = "c1",
        name = "n", landingUrl = "https://x", landingDomain = "x", createdAt = Instant.now(),
        s3Key = "k", mime = "image/png", width = 300, height = 250
      )
      CandidateLogic.buildCandidateView(
        candidate, creative, Map(cat -> 0.8), Instant.now()).categoryScore
    }

    // The point of the decay: both bids are eligible, and the nearer one
    // wins the slot. Reach stays intact; only the prior moves.
    "rank a direct place match above a broader one" in {
      score(placeHops = 0) should be > score(placeHops = 1)
      score(placeHops = 1) should be > score(placeHops = 2)
    }

    "leave an untargeted campaign undecayed" in {
      score(placeHops = 0) shouldBe 0.8 +- 1e-9
    }

    "decay by 0.7 per hop" in {
      score(placeHops = 1) shouldBe (0.8 * 0.7) +- 1e-9
      score(placeHops = 2) shouldBe (0.8 * 0.49) +- 1e-9
    }

    // A bid distant on BOTH axes is discounted on both — the two decays are
    // independent signals and multiply.
    "compound with the taxonomy decay" in {
      score(placeHops = 1, ancestorHops = 1) shouldBe (0.8 * 0.7 * 0.7) +- 1e-9
    }
  }
}
