package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/**
 * Media targeting = a bid/no-bid eligibility filter on top of contextual
 * matching. Empty allowlist bids everywhere; a non-empty allowlist restricts
 * the campaign to its listed siteIds, enforced in canBid / bidRejectReason.
 */
class CampaignMediaTargetingSpec extends AnyWordSpec with Matchers {

  private val campaignId = CampaignId("camp-1")
  private val advertiserId = AdvertiserId("adv-1")
  private val cat = CategoryId("100")
  private val floor = CPM(1.0)

  /**
   * Active campaign that targets `cat`, maxCpm 5 (clears the floor), with the
   * given media allowlist.
   */
  private def stateWith(allowlist: Set[String]): CampaignEntity.State =
    CampaignEntity.State
      .empty(campaignId, advertiserId)
      .copy(
        status = CampaignEntity.Status.Active,
        categories = Set(cat),
        maxCpm = CPM(5.0),
        siteAllowlist = allowlist
      )

  "Campaign media targeting" should {

    "bid on any site when the allowlist is empty (no restriction)" in {
      val s = stateWith(Set.empty)
      s.canBid(SiteId("site-a"), cat, floor) shouldBe true
      s.canBid(SiteId("site-b"), cat, floor) shouldBe true
      s.bidRejectReason(SiteId("site-b"), cat, floor) shouldBe None
    }

    "bid on a site that is in the allowlist" in {
      val s = stateWith(Set("site-a"))
      s.canBid(SiteId("site-a"), cat, floor) shouldBe true
      s.bidRejectReason(SiteId("site-a"), cat, floor) shouldBe None
    }

    "refuse to bid on a site not in a non-empty allowlist" in {
      val s = stateWith(Set("site-a"))
      s.canBid(SiteId("site-b"), cat, floor) shouldBe false
      s.bidRejectReason(SiteId("site-b"), cat, floor) shouldBe
      Some(CampaignEntity.BidRejectReason.SiteNotAllowed)
    }

    "report SiteNotAllowed even when the category matches (site gate runs first)" in {
      val s = stateWith(Set("site-a"))
      // category matches, but the site does not — still rejected on site
      s.bidRejectReason(SiteId("site-b"), cat, floor) shouldBe
      Some(CampaignEntity.BidRejectReason.SiteNotAllowed)
    }

    "still apply category matching on an allowlisted site (additive, not override)" in {
      val s = stateWith(Set("site-a"))
      // allowlisted site but a category the campaign does not target → mismatch,
      // proving the allowlist does NOT bypass contextual matching.
      s.bidRejectReason(SiteId("site-a"), CategoryId("999"), floor) shouldBe
      Some(CampaignEntity.BidRejectReason.CategoryMismatch)
    }
  }

  /**
   * Eviction-on-narrow (Case 1) depends on CampaignChanged carrying the
   * campaign's siteAllowlist so per-site auctioneers can detect a site they've
   * been narrowed off of. These assert the event/message shape used to thread
   * the allowlist from CampaignEntity.State → CampaignDirectory.CampaignReady
   * → CampaignChanged.
   */
  "CampaignChanged eviction-on-narrow plumbing" should {

    "carry the narrowed siteAllowlist on the published event" in {
      val narrowed = stateWith(Set("site-a"))
      val event = CampaignEntity.CampaignChanged(
        campaignId = campaignId,
        categories = narrowed.categories,
        isActive = true,
        siteAllowlist = narrowed.siteAllowlist
      )
      event.siteAllowlist shouldBe Set("site-a")
    }

    "default siteAllowlist to empty (bid everywhere) when unset" in {
      val event = CampaignEntity.CampaignChanged(
        campaignId = campaignId,
        categories = Set(cat),
        isActive = true
      )
      event.siteAllowlist shouldBe empty
    }

    "thread the State's siteAllowlist onto CampaignReady" in {
      val narrowed = stateWith(Set("site-a", "site-b"))
      val ready = CampaignDirectory.CampaignReady(
        campaignId = narrowed.campaignId,
        advertiserId = narrowed.advertiserId,
        categories = narrowed.categories,
        sizes = Set.empty,
        maxCpm = narrowed.maxCpm,
        dailyBudget = narrowed.dailyBudget,
        status = narrowed.status,
        replyTo = null,
        bidOnUnmatchedContext = narrowed.bidOnUnmatchedContext,
        siteAllowlist = narrowed.siteAllowlist
      )
      ready.siteAllowlist shouldBe Set("site-a", "site-b")
    }
  }

  /**
   * Eviction-on-narrow (Case 2, topic) depends on CampaignChanged carrying the
   * campaign's FULL target category set so per-site auctioneers can detect a
   * categorized page the campaign no longer targets. These assert the
   * targetCategories field on the event and on CampaignReady.
   */
  "CampaignChanged topic-narrow plumbing" should {

    "carry the campaign's full target categories on the published event" in {
      val s = stateWith(Set.empty).copy(categories = Set(CategoryId("100"), CategoryId("200")))
      val event = CampaignEntity.CampaignChanged(
        campaignId = campaignId,
        categories = s.categories,
        isActive = true,
        siteAllowlist = s.siteAllowlist,
        targetCategories = s.categories
      )
      event.targetCategories shouldBe Set(CategoryId("100"), CategoryId("200"))
    }

    "default targetCategories to empty when unset" in {
      val event = CampaignEntity.CampaignChanged(
        campaignId = campaignId,
        categories = Set(cat),
        isActive = true
      )
      event.targetCategories shouldBe empty
    }

    "expose CampaignReady.targetCategories as the full category set" in {
      val ready = CampaignDirectory.CampaignReady(
        campaignId = campaignId,
        advertiserId = advertiserId,
        categories = Set(CategoryId("100"), CategoryId("200")),
        sizes = Set.empty,
        maxCpm = CPM(5.0),
        dailyBudget = Budget(100),
        status = CampaignEntity.Status.Active,
        replyTo = null,
        bidOnUnmatchedContext = false,
        siteAllowlist = Set.empty
      )
      ready.targetCategories shouldBe Set(CategoryId("100"), CategoryId("200"))
    }
  }
}
