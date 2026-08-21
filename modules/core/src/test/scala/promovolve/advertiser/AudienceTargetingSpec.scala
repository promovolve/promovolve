package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.taxonomy.Places

import java.time.Instant

/**
 * Campaign-side audience targeting — tier 2 of
 * docs/design/GEOGRAPHIC_CONTEXT.md becoming load-bearing.
 *
 * The gate is additive on top of category matching (topic decides WHICH
 * pages, this decides WHOSE) and binary rather than decayed, which is what
 * separates it from tier-1 content-place relevance.
 */
class AudienceTargetingSpec extends AnyWordSpec with Matchers {

  private val Kamakura = "GN1860672" // Kamakura -> JP-14 -> JP
  private val siteId = SiteId("site-a")
  private val cat = CategoryId("483")

  private def campaign(
      audience: Set[String] = Set.empty,
      allowlist: Set[String] = Set.empty,
      requireVerified: Boolean = false
  ): CampaignEntity.State =
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
      siteAllowlist = allowlist,
      audienceTargeting = audience,
      requireVerifiedAudience = requireVerified
    )

  "Places.targetingMatches" should {

    "match everything when the campaign targets no audience" in {
      Places.targetingMatches(Set.empty, Set.empty) shouldBe true
      Places.targetingMatches(Set.empty, Set(Kamakura)) shouldBe true
    }

    // The strict-unknown rule. An undeclared site is UNKNOWN, not
    // "everywhere" — flipping this is the most likely well-meaning
    // regression, and it silently sells audience targeting the
    // publisher never claimed.
    "NOT match a site that has declared nothing" in {
      Places.targetingMatches(Set("JP"), Set.empty) shouldBe false
    }

    "match the declared place exactly" in {
      Places.targetingMatches(Set(Kamakura), Set(Kamakura)) shouldBe true
    }

    "match a broader target through the inventory's ancestors" in {
      Places.targetingMatches(Set("JP"), Set(Kamakura)) shouldBe true
      Places.targetingMatches(Set("JP-14"), Set(Kamakura)) shouldBe true
    }

    // No descendant match, consistent with how category targeting behaves.
    // An advertiser wanting both writes {JP-13, JP}.
    "NOT match a narrower target against a broader declaration" in {
      Places.targetingMatches(Set("JP-13"), Set("JP")) shouldBe false
      Places.targetingMatches(Set(Kamakura), Set("JP-14")) shouldBe false
    }

    "match when any member of the target set matches" in {
      Places.targetingMatches(Set("FR", "JP"), Set(Kamakura)) shouldBe true
    }

    "not match an unrelated place" in {
      Places.targetingMatches(Set("FR"), Set(Kamakura)) shouldBe false
    }
  }

  "canBid" should {

    "admit an untargeted campaign on an undeclared site" in {
      campaign().canBid(siteId, cat, CPM(1.0), Set.empty) shouldBe true
    }

    "admit a targeted campaign on a matching site" in {
      campaign(audience = Set("JP")).canBid(siteId, cat, CPM(1.0), Set(Kamakura)) shouldBe true
    }

    "refuse a targeted campaign on a non-matching site" in {
      campaign(audience = Set("FR")).canBid(siteId, cat, CPM(1.0), Set(Kamakura)) shouldBe false
    }

    "refuse a targeted campaign on an undeclared site" in {
      campaign(audience = Set("JP")).canBid(siteId, cat, CPM(1.0), Set.empty) shouldBe false
    }

    // Additive, not an override: audience narrows WITHIN the topic match,
    // it never rescues a category mismatch.
    "still require the category to match" in {
      campaign(audience = Set("JP"))
        .canBid(siteId, CategoryId("999"), CPM(1.0), Set(Kamakura)) shouldBe false
    }

    "compose with siteAllowlist as AND, not OR" in {
      val c = campaign(audience = Set("JP"), allowlist = Set("other-site"))
      c.canBid(siteId, cat, CPM(1.0), Set(Kamakura)) shouldBe false
    }
  }

  "bidRejectReason" should {

    "name the audience gate so the floor optimizer's stats stay readable" in {
      campaign(audience = Set("FR")).bidRejectReason(siteId, cat, CPM(1.0), Set(Kamakura)) shouldBe
      Some(CampaignEntity.BidRejectReason.AudienceNotAllowed)
    }

    // Allowlist is checked first; both being wrong must not report the
    // audience, or an advertiser debugging media targeting is misled.
    "report SiteNotAllowed ahead of the audience when both fail" in {
      campaign(audience = Set("FR"), allowlist = Set("other-site"))
        .bidRejectReason(siteId, cat, CPM(1.0), Set(Kamakura)) shouldBe
      Some(CampaignEntity.BidRejectReason.SiteNotAllowed)
    }

    "say nothing when the campaign is eligible" in {
      campaign(audience = Set("JP")).bidRejectReason(siteId, cat, CPM(1.0), Set(Kamakura)) shouldBe None
    }
  }

  "the live-ask and bid-book paths" should {

    // The two paths decide eligibility independently — canBid asks the
    // campaign, answerFromBook replicates it from a standing quote. Every
    // previous set-valued filter was reimplemented in both and could drift.
    // This one is a single shared predicate, and this test is what says so.
    "agree for every combination of targeting and declaration" in {
      val targetings = Vector(Set.empty[String], Set("JP"), Set("JP-14"), Set(Kamakura), Set("FR"), Set("FR", "JP"))
      val declarations = Vector(Set.empty[String], Set("JP"), Set("JP-14"), Set(Kamakura), Set("FR"))
      for (t <- targetings; d <- declarations) {
        val viaCanBid = campaign(audience = t).canBid(siteId, cat, CPM(1.0), d)
        val viaBook = CampaignEntity.audienceAdmits(t, d)
        withClue(s"targeting=$t declaration=$d: ") {
          viaCanBid shouldBe viaBook
        }
      }
    }
  }

  "requireVerifiedAudience" should {

    "admit a matching site whose declaration observation backs" in {
      campaign(audience = Set("JP"), requireVerified = true)
        .canBid(siteId, cat, CPM(1.0), Set(Kamakura), siteAudienceVerified = true) shouldBe true
    }

    // The whole point of the flag: the declaration may well be true, but
    // nothing measured stands behind it, and this advertiser paid reach to
    // avoid exactly that.
    "refuse a matching site whose declaration is unverified" in {
      campaign(audience = Set("JP"), requireVerified = true)
        .canBid(siteId, cat, CPM(1.0), Set(Kamakura), siteAudienceVerified = false) shouldBe false
    }

    "not narrow a campaign that is not targeting an audience at all" in {
      // The flag is meaningless without a target, and must not silently
      // restrict a campaign that never asked for a region.
      campaign(audience = Set.empty, requireVerified = true)
        .canBid(siteId, cat, CPM(1.0), Set.empty, siteAudienceVerified = false) shouldBe true
    }

    "leave unflagged campaigns free to use declared inventory" in {
      campaign(audience = Set("JP"), requireVerified = false)
        .canBid(siteId, cat, CPM(1.0), Set(Kamakura), siteAudienceVerified = false) shouldBe true
    }

    "report the audience gate when verification is what failed" in {
      campaign(audience = Set("JP"), requireVerified = true)
        .bidRejectReason(siteId, cat, CPM(1.0), Set(Kamakura), siteAudienceVerified = false) shouldBe
      Some(CampaignEntity.BidRejectReason.AudienceNotAllowed)
    }

    // The book path replicates eligibility from a standing quote, so the
    // verified dimension must agree there too or a verified-only campaign
    // serves on unverified inventory whenever the book answers.
    "agree between the live-ask and bid-book paths" in {
      val declarations = Vector(Set.empty[String], Set("JP"), Set(Kamakura), Set("FR"))
      for {
        targeting <- Vector(Set.empty[String], Set("JP"), Set("FR"))
        declared <- declarations
        requireVerified <- Vector(true, false)
        verified <- Vector(true, false)
      } {
        val viaCanBid = campaign(audience = targeting, requireVerified = requireVerified)
          .canBid(siteId, cat, CPM(1.0), declared, verified)
        val viaBook = CampaignEntity.audienceAdmits(targeting, declared, requireVerified, verified)
        withClue(s"targeting=$targeting declared=$declared require=$requireVerified verified=$verified: ") {
          viaCanBid shouldBe viaBook
        }
      }
    }
  }
}
