package promovolve.advertiser

import java.time.Instant

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/**
 * The advertiser's bid checker (State.bidVerdict): "would this campaign bid
 * on a page like this?" — answered by the same predicate the auction uses,
 * applied the same way. Pinned here is the part that was live and wrong to
 * read by hand on 2026-08-23: a campaign targeting Hotels (668) did not bid
 * on a Spas (671) page even though both are Travel Type — page categories
 * fan out to their ANCESTORS, never sideways — and the checker must say
 * "category" for that, not "place" or "audience", because those passed.
 */
class BidVerdictSpec extends AnyWordSpec with Matchers {

  private val site = SiteId("programmer-llc")
  private val Hotels = "668" // Travel > Travel Type > Hotels and Motels
  private val Spas = "671" // Travel > Travel Type > Spas
  private val TravelType = "664"
  private val Kanazawa = "GN1860243"
  private val Toyooka = "GN1849831" // -> JP-28 -> JP

  private def campaign(
      categories: Set[String] = Set(Hotels),
      places: Set[String] = Set.empty,
      audience: Set[String] = Set.empty,
      verifiedOnly: Boolean = false,
      status: CampaignEntity.Status = CampaignEntity.Status.Active,
      maxCpm: CPM = CPM(5.0)
  ): CampaignEntity.State =
    CampaignEntity.State(
      campaignId = CampaignId("c1"),
      advertiserId = AdvertiserId("a1"),
      status = status,
      categories = categories.map(CategoryId(_)),
      categoryBlocklist = Set.empty,
      maxCpm = maxCpm,
      dailyBudget = Budget(100.0),
      creativeAssignments = Set.empty,
      spendToday = Spend.zero,
      lastResetInstant = Instant.now(),
      pendingReports = Map.empty,
      processedFilter = Array.emptyByteArray,
      placeTargeting = places,
      audienceTargeting = audience,
      requireVerifiedAudience = verifiedOnly
    )

  private def verdict(
      c: CampaignEntity.State,
      pageCategories: Set[String],
      pagePlaces: Set[String] = Set.empty,
      siteAudience: Set[String] = Set.empty,
      verified: Boolean = false,
      floor: CPM = CPM(1.0)
  ) = c.bidVerdict(site, pageCategories, floor, Map.empty, siteAudience, verified, pagePlaces)

  "State.bidVerdict" should {

    "bid on a page that carries a targeted category natively" in {
      val v = verdict(campaign(), Set(Hotels))
      v.wouldBid shouldBe true
      v.matchedCategory shouldBe Some(Hotels)
      v.categoryHops shouldBe 0
    }

    // The live case. Siblings under Travel Type do not match each other.
    "refuse a sibling category and say so" in {
      val v = verdict(campaign(categories = Set(Hotels)), Set(Spas))
      v.wouldBid shouldBe false
      v.reason shouldBe Some("CategoryMismatch")
    }

    // Fan-out is upward: a campaign targeting the PARENT reaches the page.
    "bid through a page category's ancestor, reporting the hops" in {
      val v = verdict(campaign(categories = Set(TravelType)), Set(Spas))
      v.wouldBid shouldBe true
      v.matchedCategory shouldBe Some(TravelType)
      v.categoryHops shouldBe 1
    }

    "report the gate that stops the bid everywhere rather than category mismatch" in {
      // Category would match; the audience gate refuses on an undeclared site.
      verdict(campaign(audience = Set("JP")), Set(Hotels)).reason shouldBe Some("AudienceNotAllowed")
      // Category would match; the place gate refuses on a page about elsewhere.
      verdict(campaign(places = Set(Kanazawa)), Set(Hotels), pagePlaces = Set(Toyooka)).reason shouldBe
      Some("PlaceNotAllowed")
      verdict(campaign(status = CampaignEntity.Status.Paused), Set(Hotels)).reason shouldBe Some("Paused")
      verdict(campaign(maxCpm = CPM(0.5)), Set(Hotels), floor = CPM(1.0)).reason shouldBe Some("BelowFloor")
    }

    "carry the place distance on a bid through a broader place" in {
      val v = verdict(campaign(places = Set("JP-28")), Set(Hotels), pagePlaces = Set(Toyooka))
      v.wouldBid shouldBe true
      v.placeHops shouldBe 1
    }

    "require the site's declared audience to be verified when asked" in {
      verdict(campaign(audience = Set("JP"), verifiedOnly = true), Set(Hotels),
        siteAudience = Set("JP"), verified = false).reason shouldBe Some("AudienceNotAllowed")
      verdict(campaign(audience = Set("JP"), verifiedOnly = true), Set(Hotels),
        siteAudience = Set("JP"), verified = true).wouldBid shouldBe true
    }

    // A page with no categories is the filler case; an ordinary campaign
    // does not bid there.
    "treat a page with no categories as filler" in {
      verdict(campaign(), Set.empty).wouldBid shouldBe false
    }
  }
}
