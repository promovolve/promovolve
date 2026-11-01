package promovolve.api

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.advertiser.{ AdvertiserEntity, CampaignEntity }

/**
 * The Delivery check's rules, one per row of the spec's table
 * (docs/superpowers/specs/2026-11-01-delivery-check-design.md).
 */
class DeliveryCheckSpec extends AnyWordSpec with Matchers {
  import DeliveryCheck.*

  private val now = Instant.parse("2026-11-01T12:00:00Z")
  private val cr = CreativeId("cr-1")

  private val healthy = Inputs(
    advertiser = AdvertiserEntity.AdvertiserInfo(
      AdvertiserId("adv"), Name("Adv"), AdvertiserEntity.Status.Active, Set(CampaignId("c")), Set.empty),
    advertiserBudget = Some(AdvertiserEntity.AdvertiserBudgetStatus(
      AdvertiserId("adv"), Budget(1000.0), Spend(100.0), Budget(900.0), withinBudget = true, 0L)),
    campaign = CampaignEntity.CampaignInfo(
      CampaignId("c"), CampaignEntity.Status.Active, Set(CategoryId("483")), CPM(2.0), Budget(500.0), Set(cr),
      startAt = now.minus(1, ChronoUnit.DAYS)),
    campaignBudget = Some(CampaignEntity.BudgetInfo(Budget(500.0), Spend(50.0), Budget(450.0))),
    creatives = Some(Set(AdvertiserEntity.Creative(cr, approvedSites = Set("site-a")))),
    sites = Some(Map(SiteId("site-a") -> SiteFloors(1.0, Map.empty, Set("483")))),
    directory = Some(Map("483" -> true))
  )

  private def status(in: Inputs, key: String): String =
    evaluate(in, now).find(_.key == key).map(_.status).getOrElse(fail(s"no item $key"))

  private def withCampaign(f: CampaignEntity.CampaignInfo => CampaignEntity.CampaignInfo) =
    healthy.copy(campaign = f(healthy.campaign))

  "DeliveryCheck.evaluate" should {

    "report a healthy campaign with all ten checks ok, in order" in {
      val items = evaluate(healthy, now)
      items.map(_.key) shouldBe Vector("advertiserActive", "advertiserBudget", "campaignActive", "schedule",
        "categories", "creatives", "approval", "campaignBudget", "floors", "bidders")
      items.map(_.status).distinct shouldBe Vector("ok")
      firstBlocked(items) shouldBe None
    }

    "block on a suspended advertiser, and only there" in {
      val in = healthy.copy(
        advertiser = healthy.advertiser.copy(status = AdvertiserEntity.Status.Suspended),
        advertiserBudget = healthy.advertiserBudget.map(_.copy(withinBudget = false)))
      status(in, "advertiserActive") shouldBe "blocked"
      status(in, "advertiserBudget") shouldBe "ok"
      firstBlocked(evaluate(in, now)) shouldBe Some("advertiserActive")
    }

    "block on an unset advertiser daily budget" in {
      status(healthy.copy(advertiserBudget = healthy.advertiserBudget.map(_.copy(dailyBudget = Budget(0.0)))),
        "advertiserBudget") shouldBe "blocked"
    }

    "block when the advertiser's budget is spent for today" in {
      status(healthy.copy(advertiserBudget = healthy.advertiserBudget.map(_.copy(withinBudget = false))),
        "advertiserBudget") shouldBe "blocked"
    }

    "warn under 10% of the advertiser budget left, not at 10%" in {
      def left(r: Double) = healthy.copy(advertiserBudget = healthy.advertiserBudget.map(_.copy(remaining = Budget(r))))
      status(left(99.0), "advertiserBudget") shouldBe "warning"
      status(left(100.0), "advertiserBudget") shouldBe "ok"
    }

    "block on a paused campaign" in {
      status(withCampaign(_.copy(status = CampaignEntity.Status.Paused)), "campaignActive") shouldBe "blocked"
    }

    "block before startAt and at endAt" in {
      status(withCampaign(_.copy(startAt = now.plusSeconds(60))), "schedule") shouldBe "blocked"
      status(withCampaign(_.copy(endAt = Some(now))), "schedule") shouldBe "blocked"
      status(withCampaign(_.copy(endAt = Some(now.plusSeconds(60)))), "schedule") shouldBe "ok"
    }

    "block a campaign with no categories" in {
      status(withCampaign(_.copy(categories = Set.empty)), "categories") shouldBe "blocked"
    }

    "count suggested categories when none are set" in {
      status(withCampaign(_.copy(categories = Set.empty, suggestedCategories = Set(CategoryId("483")))),
        "categories") shouldBe "ok"
    }

    "not block a filler-only campaign on categories" in {
      val in = withCampaign(_.copy(categories = Set.empty, bidOnUnmatchedContext = true))
        .copy(directory = Some(Map(CategoryId.Filler.value -> true)))
      status(in, "categories") shouldBe "ok"
      effectiveCategories(in.campaign) shouldBe Set(CategoryId.Filler)
    }

    "block when no assigned creative is active" in {
      status(healthy.copy(creatives = Some(Set(AdvertiserEntity.Creative(cr, isActive = false)))),
        "creatives") shouldBe "blocked"
      status(healthy.copy(creatives = Some(Set(AdvertiserEntity.Creative(CreativeId("other"))))),
        "creatives") shouldBe "blocked"
    }

    "warn, not block, when no site has approved a creative yet" in {
      status(healthy.copy(creatives = Some(Set(AdvertiserEntity.Creative(cr)))), "approval") shouldBe "warning"
    }

    "block when the campaign budget is spent, warn under 10%" in {
      def left(r: Double) = healthy.copy(campaignBudget = healthy.campaignBudget.map(_.copy(remaining = Budget(r))))
      status(left(0.0), "campaignBudget") shouldBe "blocked"
      status(left(49.0), "campaignBudget") shouldBe "warning"
      status(left(50.0), "campaignBudget") shouldBe "ok"
    }

    "judge floors per site with the category floor, else the site floor" in {
      val sites = Map(
        SiteId("a") -> SiteFloors(1.0, Map("483" -> 3.0), Set("483")), // category floor above max 2.0
        SiteId("b") -> SiteFloors(3.0, Map("483" -> 1.5), Set("483")), // category floor below max
        SiteId("c") -> SiteFloors(1.0, Map.empty, Set("483")) // site floor below max
      )
      val items = evaluate(healthy.copy(sites = Some(sites)), now)
      val floors = items.find(_.key == "floors").get
      floors.status shouldBe "warning"
      floors.detail shouldBe Map("cleared" -> "2", "eligible" -> "3", "lowestFloor" -> "1.0", "highestFloor" -> "3.0")
    }

    "block when no eligible site clears" in {
      status(healthy.copy(sites = Some(Map(SiteId("a") -> SiteFloors(5.0, Map.empty, Set("483"))))),
        "floors") shouldBe "blocked"
    }

    "warn, not block, when no site carries the categories" in {
      status(healthy.copy(sites = Some(Map.empty)), "floors") shouldBe "warning"
    }

    "warn while a category is missing from the bidders" in {
      val items = evaluate(healthy.copy(directory = Some(Map("483" -> false))), now)
      val bidders = items.find(_.key == "bidders").get
      bidders.status shouldBe "warning"
      bidders.detail shouldBe Map("missing" -> "483")
    }

    "mark unanswered sources unknown, and never block on them" in {
      val in = healthy.copy(advertiserBudget = None, campaignBudget = None, creatives = None, sites = None,
        directory = None)
      val items = evaluate(in, now)
      Seq("advertiserBudget", "creatives", "approval", "campaignBudget", "floors", "bidders")
        .foreach(k => status(in, k) shouldBe "unknown")
      firstBlocked(items) shouldBe None
    }
  }
}
