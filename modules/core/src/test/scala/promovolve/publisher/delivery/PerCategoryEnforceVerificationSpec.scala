package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType }
import promovolve.publisher.delivery.Protocol.BatchSlotSpec

/**
 * VERIFICATION — the "3 campaigns at $1/$2/$3, each its own category" test.
 *
 * Drives the REAL `AdServer.pickBestForSlot` once per category page (each a
 * single-bidder monopoly auction) under OFF vs ENFORCE floor config, and
 * prints the per-category clearing price side by side.
 *
 * The point: "all three delivered" alone does NOT prove the fix — under a
 * balanced site floor the legacy single floor ALSO delivers all three (just
 * all at the same price). The discriminator is the PER-CATEGORY CLEARING
 * PRICE: OFF clears everyone at one site floor; ENFORCE clears each at its
 * own floor. (The delivery test only proves it in the starvation regime —
 * shown last.)
 *
 *   sbt "core/testOnly promovolve.publisher.delivery.PerCategoryEnforceVerificationSpec"
 */
class PerCategoryEnforceVerificationSpec extends AnyWordSpec with Matchers {

  // 3 campaigns, distinct publisher categories, bids $1/$2/$3.
  private val bidders = Vector("cat-a" -> 1.0, "cat-b" -> 2.0, "cat-c" -> 3.0)

  private def candidate(category: String, cpm: Double): CandidateView =
    CandidateView(
      creativeId = CreativeId(s"cr-$category"),
      campaignId = CampaignId(s"camp-$category"),
      advertiserId = AdvertiserId(s"adv-$category"),
      assetUrl = CDNPath(s"/a/$category.png"),
      mime = MimeType.imagePng,
      width = 300,
      height = 250,
      category = CategoryId(category),
      cpm = CPM(cpm),
      classifiedAtMs = 0L
    )

  private val slot = BatchSlotSpec(SlotId("s"), 300, 250)
  private def rng() = new scala.util.Random(1L)

  /** Clearing price for a single-bidder category page; NaN = not delivered. */
  private def clearing(category: String, cpm: Double, siteFloor: CPM, floors: Map[CategoryId, CPM]): Double =
    AdServer
      .pickBestForSlot(slot, Vector(candidate(category, cpm)), Set.empty, 0.5, Map.empty, siteFloor, rng(), floors)
      .map(_._2.toDouble)
      .getOrElse(Double.NaN)

  private def fmt(d: Double): String = if (d.isNaN) "  —  (starved)" else f"$$$d%.2f"

  private val enforceFloors = Map(
    CategoryId("cat-a") -> CPM(1.0),
    CategoryId("cat-b") -> CPM(2.0),
    CategoryId("cat-c") -> CPM(3.0)
  )

  "Per-category floor enforcement (3 campaigns at $1/$2/$3)" should {

    "price each category at its OWN floor under ENFORCE, not the single site floor" in {
      // Balanced traffic → the legacy site sweep collapses to the lowest bid ($1).
      val siteFloor = CPM(1.0)
      val off = bidders.map { case (c, cpm) => c -> clearing(c, cpm, siteFloor, Map.empty) }
      val enforce = bidders.map { case (c, cpm) => c -> clearing(c, cpm, siteFloor, enforceFloors) }

      val sb = new StringBuilder
      sb.append("\n  Balanced traffic — clearing price per category:\n")
      sb.append(f"    ${"mode"}%-22s ${"cat-a($1)"}%-12s ${"cat-b($2)"}%-12s ${"cat-c($3)"}%-12s  total/round\n")
      sb.append(
        f"    ${"OFF (one site floor)"}%-22s ${fmt(off(0)._2)}%-12s ${fmt(off(1)._2)}%-12s ${fmt(off(2)._2)}%-12s  $$${off.map(_._2).sum}%.2f\n")
      sb.append(
        f"    ${"ENFORCE (per-category)"}%-22s ${fmt(enforce(0)._2)}%-12s ${fmt(enforce(1)._2)}%-12s ${fmt(enforce(2)._2)}%-12s  $$${enforce.map(_._2).sum}%.2f\n")
      println(sb.toString)

      // OFF: all delivered, but B and C underpay — everyone clears the $1 site floor.
      off.map(_._2) shouldBe Vector(1.0, 1.0, 1.0)
      // ENFORCE: each clears its OWN floor → full extraction.
      enforce.map(_._2) shouldBe Vector(1.0, 2.0, 3.0)
    }

    "DELIVERY proof (starvation regime): a high single floor starves $1/$2; enforce fills all" in {
      // If the blended floor sits high ($3 — e.g. skewed traffic), legacy
      // admits only the $3 bidder; the cheap categories are starved. This is
      // the regime where "all three delivered" alone proves the fix.
      val highSite = CPM(3.0)
      val off = bidders.map { case (c, cpm) => c -> clearing(c, cpm, highSite, Map.empty) }
      val enforce = bidders.map { case (c, cpm) => c -> clearing(c, cpm, highSite, enforceFloors) }

      val sb = new StringBuilder
      sb.append("\n  High site floor ($3) — who delivers:\n")
      sb.append(
        f"    ${"OFF (one site floor)"}%-22s ${fmt(off(0)._2)}%-14s ${fmt(off(1)._2)}%-14s ${fmt(off(2)._2)}%-14s\n")
      sb.append(
        f"    ${"ENFORCE (per-category)"}%-22s ${fmt(enforce(0)._2)}%-14s ${fmt(enforce(1)._2)}%-14s ${fmt(enforce(2)._2)}%-14s\n")
      println(sb.toString)

      // OFF: only cat-c delivers; cat-a and cat-b are starved.
      off(0)._2.isNaN shouldBe true
      off(1)._2.isNaN shouldBe true
      off(2)._2 shouldBe 3.0
      // ENFORCE: all three deliver, each at its own floor.
      enforce.map(_._2) shouldBe Vector(1.0, 2.0, 3.0)
    }
  }
}
