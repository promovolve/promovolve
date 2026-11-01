package promovolve.api

import java.time.Instant

import promovolve.*
import promovolve.advertiser.{ AdvertiserEntity, CampaignEntity }

/**
 * The Delivery check: an ordered checklist naming what stops a campaign from
 * serving (docs/superpowers/specs/2026-11-01-delivery-check-design.md).
 * Pure: the route gathers [[Inputs]] and this decides. A `None` input means
 * its source did not answer — that item is `unknown`, which never blocks.
 * Copy lives in the dashboard; items carry keys and numbers only.
 */
object DeliveryCheck {

  /** One eligible site: its floors, and the campaign categories it carries. */
  final case class SiteFloors(siteFloor: Double, categoryFloors: Map[String, Double], carried: Set[String])

  final case class Inputs(
      advertiser: AdvertiserEntity.AdvertiserInfo,
      advertiserBudget: Option[AdvertiserEntity.AdvertiserBudgetStatus],
      campaign: CampaignEntity.CampaignInfo,
      campaignBudget: Option[CampaignEntity.BudgetInfo],
      creatives: Option[Set[AdvertiserEntity.Creative]],
      sites: Option[Map[SiteId, SiteFloors]], // eligible only: allowlist and site suspension already applied
      directory: Option[Map[String, Boolean]] // effective category -> campaign listed under it
  )

  final case class Item(key: String, status: String, detail: Map[String, String] = Map.empty)

  private val Ok = "ok"
  private val Blocked = "blocked"
  private val Warning = "warning"
  private val Unknown = "unknown"

  /** Below this share of the daily budget left, the budget items warn. */
  private val LowBudgetShare = 0.10

  /** What the campaign bids under: its categories, else the suggested ones, else Filler when opted in. */
  def effectiveCategories(c: CampaignEntity.CampaignInfo): Set[CategoryId] = {
    val explicit = if (c.categories.nonEmpty) c.categories else c.suggestedCategories
    if (explicit.isEmpty && c.bidOnUnmatchedContext) Set(CategoryId.Filler) else explicit
  }

  def evaluate(in: Inputs, now: Instant): Vector[Item] = {
    val c = in.campaign
    val advertiserActive = in.advertiser.status == AdvertiserEntity.Status.Active

    def budgetItem(key: String, daily: Double, remaining: Double, spentOut: Boolean): Item = {
      val detail = Map("daily" -> daily.toString, "remaining" -> remaining.toString)
      val status =
        if (daily <= 0 || spentOut) Blocked
        else if (remaining < daily * LowBudgetShare) Warning
        else Ok
      Item(key, status, detail)
    }

    val activeCreatives = in.creatives.map(_.filter(cr => c.creativeIds.contains(cr.id) && cr.isActive))

    Vector(
      Item("advertiserActive", if (advertiserActive) Ok else Blocked,
        Map("status" -> in.advertiser.status.toString)),
      // withinBudget is also false while suspended; that is item 1's to report.
      in.advertiserBudget.fold(Item("advertiserBudget", Unknown)) { b =>
        budgetItem("advertiserBudget", b.dailyBudget.toDouble, b.remaining.toDouble,
          spentOut = advertiserActive && !b.withinBudget)
      },
      Item("campaignActive", if (c.status == CampaignEntity.Status.Active) Ok else Blocked,
        Map("status" -> c.status.toString)),
      Item("schedule",
        if (now.isBefore(c.startAt) || c.endAt.exists(e => !now.isBefore(e))) Blocked else Ok,
        Map("startAt" -> c.startAt.toString) ++ c.endAt.map(e => "endAt" -> e.toString)),
      Item("categories", if (effectiveCategories(c).isEmpty) Blocked else Ok),
      activeCreatives.fold(Item("creatives", Unknown)) { cs =>
        Item("creatives", if (cs.isEmpty) Blocked else Ok, Map("active" -> cs.size.toString))
      },
      activeCreatives.fold(Item("approval", Unknown)) { cs =>
        val sites = cs.flatMap(_.approvedSites)
        Item("approval", if (sites.isEmpty) Warning else Ok, Map("sites" -> sites.size.toString))
      },
      in.campaignBudget.fold(Item("campaignBudget", Unknown)) { b =>
        budgetItem("campaignBudget", b.dailyBudget.toDouble, b.remaining.toDouble,
          spentOut = b.remaining.toDouble <= 0)
      },
      in.sites.fold(Item("floors", Unknown))(floorsItem(c.maxCpm.toDouble, _)),
      // Pausing (or ending) takes a campaign out of the directory on purpose.
      if (c.status != CampaignEntity.Status.Active) Item("bidders", Ok, Map("inactive" -> "true"))
      else in.directory.fold(Item("bidders", Unknown)) { listed =>
        val missing = listed.collect { case (cat, false) => cat }.toVector.sorted
        if (missing.isEmpty) Item("bidders", Ok) else Item("bidders", Warning, Map("missing" -> missing.mkString(",")))
      }
    )
  }

  // The auction's floor rule (AuctioneerEntity: currentCategoryFloors.getOrElse(cat, currentFloorCpm));
  // the filler auction prices at the site floor alone.
  private def floorsItem(maxCpm: Double, sites: Map[SiteId, SiteFloors]): Item =
    if (sites.isEmpty) Item("floors", Warning, Map("cleared" -> "0", "eligible" -> "0"))
    else {
      val floors = sites.values.map(s =>
        s.carried.map(cat =>
          if (cat == CategoryId.Filler.value) s.siteFloor else s.categoryFloors.getOrElse(cat, s.siteFloor)))
      val cleared = floors.count(_.exists(maxCpm >= _))
      val all = floors.flatten
      val status = if (cleared == 0) Blocked else if (cleared < sites.size) Warning else Ok
      Item("floors", status,
        Map("cleared" -> cleared.toString, "eligible" -> sites.size.toString)
        ++ all.minOption.map(f => "lowestFloor" -> f.toString)
        ++ all.maxOption.map(f => "highestFloor" -> f.toString))
    }

  def firstBlocked(items: Vector[Item]): Option[String] = items.find(_.status == Blocked).map(_.key)
}
