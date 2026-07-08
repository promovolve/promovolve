package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.taxonomy.TieredCategory

/** "Always keep a Gemini-suggested category as the default."
  *
  * `suggestedCategories` is a durable Gemini fallback for `categories`. When
  * the advertiser clears all explicit topics, the campaign still targets via
  * `effectiveCategories` (= categories if non-empty, else suggestedCategories)
  * so it never silently goes untargeted. These assert the State-level fallback
  * that eligibility / registration / eviction / badge all hang off of, plus the
  * message-shape plumbing onto CampaignReady / CampaignChanged.
  */
class CampaignSuggestedCategoriesSpec extends AnyWordSpec with Matchers {

  private val campaignId   = CampaignId("camp-1")
  private val advertiserId = AdvertiserId("adv-1")
  private val explicit     = CategoryId("100")
  private val suggested    = CategoryId("200")
  private val floor        = CPM(1.0)

  private def base: CampaignEntity.State =
    CampaignEntity.State
      .empty(campaignId, advertiserId)
      .copy(status = CampaignEntity.Status.Active, maxCpm = CPM(5.0))

  "effectiveCategories" should {

    "return the explicit categories when non-empty" in {
      val s = base.copy(categories = Set(explicit), suggestedCategories = Set(suggested))
      s.effectiveCategories shouldBe Set(explicit)
    }

    "fall back to the suggestion when explicit categories are empty" in {
      val s = base.copy(categories = Set.empty, suggestedCategories = Set(suggested))
      s.effectiveCategories shouldBe Set(suggested)
    }

    "be empty when both sets are empty" in {
      base.effectiveCategories shouldBe empty
    }

    "default suggestedCategories to empty on a fresh State" in {
      base.suggestedCategories shouldBe empty
    }
  }

  "canBid / bidRejectReason fallback" should {

    "bid on a suggested page when explicit categories are empty" in {
      val s = base.copy(categories = Set.empty, suggestedCategories = Set(suggested))
      s.canBid(SiteId("site-a"), suggested, floor) shouldBe true
      s.bidRejectReason(SiteId("site-a"), suggested, floor) shouldBe None
    }

    "prefer explicit categories over the suggestion (suggestion shadowed)" in {
      val s = base.copy(categories = Set(explicit), suggestedCategories = Set(suggested))
      // explicit page bids
      s.canBid(SiteId("site-a"), explicit, floor) shouldBe true
      // suggested page does NOT bid: explicit set wins and excludes it
      s.canBid(SiteId("site-a"), suggested, floor) shouldBe false
      s.bidRejectReason(SiteId("site-a"), suggested, floor) shouldBe
        Some(CampaignEntity.BidRejectReason.CategoryMismatch)
    }

    "reject with CategoryMismatch when both sets are empty (genuinely untargeted, no filler)" in {
      val s = base // categories + suggested both empty, bidOnUnmatchedContext=false
      s.canBid(SiteId("site-a"), explicit, floor) shouldBe false
      s.bidRejectReason(SiteId("site-a"), explicit, floor) shouldBe
        Some(CampaignEntity.BidRejectReason.CategoryMismatch)
    }

    "still serve filler when opted in even with both sets empty" in {
      val s = base.copy(bidOnUnmatchedContext = true)
      s.canBid(SiteId("site-a"), CategoryId.Filler, floor) shouldBe true
    }
  }

  "RefineCategoriesFromCreative suggestion accrual (transition logic)" should {

    // Mirrors the handler: union detected into both categories and
    // suggestedCategories, prune each to most-specific tiers.
    def refine(state: CampaignEntity.State, detected: Set[CategoryId]): CampaignEntity.State = {
      val pruned =
        TieredCategory.keepMostSpecific((state.categories ++ detected).map(_.value)).map(CategoryId(_))
      val prunedSuggested =
        TieredCategory.keepMostSpecific((state.suggestedCategories ++ detected).map(_.value)).map(CategoryId(_))
      state.copy(categories = pruned, suggestedCategories = prunedSuggested)
    }

    "populate suggestedCategories and union into categories on first detection" in {
      val detected = Set(CategoryId("497")) // descendant
      val after    = refine(base, detected)
      after.categories should contain(CategoryId("497"))
      after.suggestedCategories should contain(CategoryId("497"))
    }

    "prune ancestors out of the suggestion (most-specific only)" in {
      // 483 Sports is an ancestor of 497 Horse Racing in IAB 3.0.
      val seeded   = base.copy(suggestedCategories = Set(CategoryId("483")))
      val after    = refine(seeded, Set(CategoryId("497")))
      after.suggestedCategories should contain(CategoryId("497"))
      after.suggestedCategories should not contain CategoryId("483")
    }
  }

  "clearing explicit categories keeps the suggestion fallback (transition logic)" should {

    // Mirrors UpdateConfig(categories = Some(empty)): replaces `categories`
    // only; suggestedCategories is never touched by UpdateConfig.
    "leave suggestedCategories intact so effectiveCategories falls back" in {
      val before = base.copy(categories = Set(explicit), suggestedCategories = Set(suggested))
      val after  = before.copy(categories = Set.empty) // UpdateConfig clears explicit only
      after.suggestedCategories shouldBe Set(suggested)
      after.effectiveCategories shouldBe Set(suggested)
    }
  }

  "registration / eviction plumbing carries the effective set" should {

    "thread effectiveCategories onto CampaignReady (and thus targetCategories)" in {
      // Campaign with empty explicit categories but a non-empty suggestion: the
      // notifyDirectory build passes state.effectiveCategories as `categories`,
      // and CampaignReady.targetCategories mirrors that.
      val s = base.copy(categories = Set.empty, suggestedCategories = Set(suggested))
      val ready = CampaignDirectory.CampaignReady(
        campaignId            = s.campaignId,
        advertiserId          = s.advertiserId,
        categories            = s.effectiveCategories,
        sizes                 = Set.empty,
        maxCpm                = s.maxCpm,
        dailyBudget           = s.dailyBudget,
        status                = s.status,
        replyTo               = null,
        bidOnUnmatchedContext = s.bidOnUnmatchedContext,
        siteAllowlist         = s.siteAllowlist,
      )
      ready.categories shouldBe Set(suggested)
      ready.targetCategories shouldBe Set(suggested)
    }
  }

  "Untargeted badge logic" should {

    // Mirrors EndpointRoutes: untargeted only when BOTH sets empty + no filler.
    def untargeted(info: CampaignEntity.CampaignInfo): Boolean =
      info.categories.isEmpty && info.suggestedCategories.isEmpty && !info.bidOnUnmatchedContext

    def info(cats: Set[CategoryId], sug: Set[CategoryId], filler: Boolean): CampaignEntity.CampaignInfo =
      CampaignEntity.CampaignInfo(
        campaignId            = campaignId,
        status                = CampaignEntity.Status.Active,
        categories            = cats,
        maxCpm                = CPM(5.0),
        dailyBudget           = Budget(100),
        creativeIds           = Set.empty,
        bidOnUnmatchedContext = filler,
        suggestedCategories   = sug,
      )

    "be untargeted only when both sets empty and filler off" in {
      untargeted(info(Set.empty, Set.empty, filler = false)) shouldBe true
    }

    "NOT be untargeted when only the suggestion is present" in {
      untargeted(info(Set.empty, Set(suggested), filler = false)) shouldBe false
    }

    "NOT be untargeted when explicit categories are present" in {
      untargeted(info(Set(explicit), Set.empty, filler = false)) shouldBe false
    }

    "NOT be untargeted when filler is enabled even with both sets empty" in {
      untargeted(info(Set.empty, Set.empty, filler = true)) shouldBe false
    }
  }
}
