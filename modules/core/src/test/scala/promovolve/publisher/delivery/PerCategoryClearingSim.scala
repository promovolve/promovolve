package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.CPM

/**
 * PHASE 0 VALIDATION HARNESS — serve-time clearing under per-category floors.
 *
 * Drives the REAL `ThompsonSampling.cpmScore` + `qualityAdjustedClearing`
 * (no model of the pricing math — the production functions ARE the math).
 *
 * Proves two things before any prod change:
 *   1. UNDERCUT — today's serve-time pricing pools candidates CROSS-category
 *      and prices the winner against the GLOBAL runner-up clamped only to a
 *      single `siteFloor`. A high-category winner can therefore clear BELOW
 *      its own reserve, dragged down by a cheap other-category bidder.
 *   2. FIX — same-category second price (best loser IN the winner's category)
 *      clamped to the WINNER's category floor closes the undercut, while a
 *      genuinely-competitive category still gets a real within-category
 *      second price.
 *
 *   sbt "core/testOnly promovolve.publisher.delivery.PerCategoryClearingSim"
 */
class PerCategoryClearingSim extends AnyWordSpec with Matchers {

  /**
   * One bidder in the pooled slot auction. `ctr` stands in for the
   * engagement term; in prod pricing uses the posterior-MEAN engagement
   * (`CandidateScore.engagement` / `meanScore`) — irrelevant to the
   * PRICING question, which is deterministic given scores.
   */
  private final case class Bid(category: String, cpm: Double, ctr: Double)

  private def scoreOf(b: Bid, alpha: Double): Double =
    b.ctr * ThompsonSampling.cpmScore(b.cpm, alpha)

  /**
   * TODAY: cross-category pool, global runner-up, single `siteFloor`.
   * Mirrors `AdServer.pickBestForSlot` as it stands.
   */
  private def clearToday(pool: Vector[Bid], alpha: Double, siteFloor: Double): (Bid, Double) = {
    val sorted = pool.sortBy(b => -scoreOf(b, alpha))
    val winner = sorted.head
    val bestLoser = if (sorted.size > 1) scoreOf(sorted(1), alpha) else 0.0
    val clearing = ThompsonSampling.qualityAdjustedClearing(
      winnerEngagement = winner.ctr,
      winnerBid = CPM(winner.cpm),
      bestLoserScore = bestLoser,
      alpha = alpha,
      siteFloor = CPM(siteFloor)
    )
    (winner, clearing.toDouble)
  }

  /**
   * PROPOSED: cross-category SELECTION (one ad per slot) but same-category
   * PRICING — best loser within the winner's category, clamped to the
   * winner's category floor. Mirrors the Phase 2 `pickBestForSlot` change.
   */
  private def clearPerCategory(pool: Vector[Bid], alpha: Double, categoryFloor: String => Double): (Bid, Double) = {
    val sorted = pool.sortBy(b => -scoreOf(b, alpha))
    val winner = sorted.head
    val bestLoser = sorted.tail
      .collectFirst { case b if b.category == winner.category => scoreOf(b, alpha) }
      .getOrElse(0.0)
    val clearing = ThompsonSampling.qualityAdjustedClearing(
      winnerEngagement = winner.ctr,
      winnerBid = CPM(winner.cpm),
      bestLoserScore = bestLoser,
      alpha = alpha,
      siteFloor = CPM(categoryFloor(winner.category))
    )
    (winner, clearing.toDouble)
  }

  private val Alpha = 0.5

  // sports = single monopoly bidder C ($5, learned floor $5)
  // tech   = two competing bidders A ($3) / A' ($3.50), learned floor $1
  // site fallback floor = $1
  private val sportsFloor = 5.0
  private val techFloor = 1.0
  private val siteFloor = 1.0
  private def catFloor(c: String): Double = c match {
    case "sports" => sportsFloor
    case "tech"   => techFloor
    case _        => siteFloor
  }

  "Serve-time clearing on a mixed competing+monopoly page" should {

    "TODAY: undercut — sports winner clears BELOW its $5 reserve via a cheap tech runner-up" in {
      val pool = Vector(Bid("sports", 5.0, 0.5), Bid("tech", 3.0, 0.5))
      val (winner, price) = clearToday(pool, Alpha, siteFloor)

      println(
        f"  [today]        winner=${winner.category}%-6s bid=$$${winner.cpm}%.2f → clears $$${price}%.2f  (sports floor $$${sportsFloor}%.2f)")
      winner.category shouldBe "sports" // C wins the slot on score
      price should be < sportsFloor // ← UNDERCUT: pays below its reserve
      price shouldBe 3.0 +- 0.01 // dragged to the tech-derived second price
    }

    "FIXED: same-category pricing — sports winner clears at its $5 floor, tech untouched" in {
      val pool = Vector(Bid("sports", 5.0, 0.5), Bid("tech", 3.0, 0.5))
      val (winner, price) = clearPerCategory(pool, Alpha, catFloor)

      println(
        f"  [per-category] winner=${winner.category}%-6s bid=$$${winner.cpm}%.2f → clears $$${price}%.2f  (sports floor $$${sportsFloor}%.2f)")
      winner.category shouldBe "sports"
      price shouldBe sportsFloor +- 0.01 // ← monopoly category pays exactly its floor
      price should be >= catFloor(winner.category) // never below the winner's reserve
    }

    "FIXED: a genuinely-competitive category still gets a real within-category second price" in {
      val pool = Vector(Bid("tech", 3.50, 0.5), Bid("tech", 3.0, 0.5))
      val (winner, price) = clearPerCategory(pool, Alpha, catFloor)

      println(
        f"  [per-category] winner=${winner.category}%-6s bid=$$${winner.cpm}%.2f → clears $$${price}%.2f  (tech floor $$${techFloor}%.2f)")
      winner.cpm shouldBe 3.50 +- 0.01 // higher-quality tech bidder wins
      price should be > techFloor // priced by competition, not just the floor
      price should be <= winner.cpm // never above its own bid
      price shouldBe 3.0 +- 0.01 // second price against the $3 tech runner-up
    }

    "FIXED: a monopoly category alone on the page pays exactly its category floor" in {
      val pool = Vector(Bid("sports", 5.0, 0.5))
      val (_, price) = clearPerCategory(pool, Alpha, catFloor)

      println(f"  [per-category] monopoly sports alone → clears $$${price}%.2f  (sports floor $$${sportsFloor}%.2f)")
      price shouldBe sportsFloor +- 0.01
    }
  }
}
