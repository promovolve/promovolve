package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.SiteId

/**
 * PHASE 1 VALIDATION — per-category shadow learning.
 *
 * Mirrors exactly how SiteEntity routes signals to per-category optimizers
 * (PER_CATEGORY_FLOOR_MODE):
 *   - per-category `AuctionOutcomeReport` → `recordObservedBid` /
 *     `recordObservedMinBid` on THAT category's optimizer (its own bounds),
 *   - `ImpressionServed(winnerCategory, revenue)` → `recordServedImpression`
 *     on the winner's optimizer,
 *   - one `observe()` per category per tick.
 *
 * Shows that over ONE mixed-category traffic stream, per-category floors
 * diverge to each category's own value ($3/$4/$5), whereas a single blended
 * optimizer over the same traffic collapses to one compromise floor.
 *
 *   sbt "core/testOnly promovolve.publisher.PerCategoryLearningSim"
 */
class PerCategoryLearningSim extends AnyWordSpec with Matchers {

  private val bidders = Map("A" -> 3.0, "B" -> 4.0, "C" -> 5.0)
  private val ImpsPerTick = 30
  private val Ticks = 400

  private def newOpt(): FloorSweepOptimizer = {
    val o = new FloorSweepOptimizer(SiteId("per-cat-sim"))
    o.setMinFloor(0.50)
    o.setInitialFloor(0.50)
    o
  }

  /**
   * One category's monopoly auction for a window: route bounds + revenue
   * exactly as the SiteEntity handlers do.
   */
  private def feedCategory(opt: FloorSweepOptimizer, bid: Double): Unit = {
    val floor = opt.currentFloorCpm
    var i = 0
    while (i < ImpsPerTick) {
      opt.recordObservedBid(bid) // per-category AuctionOutcomeReport bounds
      opt.recordObservedMinBid(bid)
      if (bid >= floor) opt.recordServedImpression(floor) // ImpressionServed
      i += 1
    }
  }

  "Per-category shadow learning over one mixed traffic stream" should {

    "converge each category's optimizer to its own bidder's value ($3/$4/$5)" in {
      val opts = bidders.keys.map(c => c -> newOpt()).toMap
      // Read the CONVERGED ARGMAX, not `currentFloorCpm`. Mid-sweep the
      // cursor sits on whichever candidate it is probing, so sampling the
      // live floor at an arbitrary tick asserts on a probe rather than on
      // a decision — and which probe you land on depends on the candidate
      // grid, which is quantized to the site's minimum floor. (This test
      // read the live floor until 2026-07-27, when a finer/coarser grid
      // put candidate 6 of 8 under the cursor at the final tick.) The
      // sibling test below has always read decisions for this reason.
      val argmax = bidders.keys.map(c => c -> scala.collection.mutable.ArrayBuffer.empty[Double]).toMap
      for (_ <- 1 to Ticks) {
        opts.foreach { case (cat, opt) =>
          feedCategory(opt, bidders(cat))
          opt.observe().foreach(_.completedCycle.foreach(d => argmax(cat) += d.argmaxFloor))
        }
      }
      def converged(cat: String): Double =
        argmax(cat).lastOption.getOrElse(opts(cat).currentFloorCpm)

      println(f"  per-category floors: A=$$${converged("A")}%.2f " +
        f"B=$$${converged("B")}%.2f C=$$${converged("C")}%.2f  (optimum $$3/$$4/$$5)")

      // Each category's own bounds collapse its sweep range to its own
      // bidder's bid → the argmax lands exactly there. This is the monopoly
      // optimum the single site floor cannot reach.
      converged("A") shouldBe 3.0 +- 0.01
      converged("B") shouldBe 4.0 +- 0.01
      converged("C") shouldBe 5.0 +- 0.01
    }

    "a single blended optimizer over the SAME traffic lands on one compromise floor" in {
      val opt = newOpt()
      // The blended optimizer sweeps 8 candidates, so `currentFloorCpm` at an
      // arbitrary tick is wherever the cursor is probing — read the converged
      // argmax from the completed-cycle decisions instead.
      val decisions = scala.collection.mutable.ArrayBuffer.empty[Double]
      for (_ <- 1 to Ticks) {
        val floor = opt.currentFloorCpm
        bidders.values.foreach { bid =>
          var i = 0
          while (i < ImpsPerTick) {
            opt.recordObservedBid(bid)
            opt.recordObservedMinBid(bid)
            if (bid >= floor) opt.recordServedImpression(floor)
            i += 1
          }
        }
        opt.observe().foreach(_.completedCycle.foreach(d => decisions += d.argmaxFloor))
      }
      val converged = decisions.lastOption.getOrElse(opt.currentFloorCpm)
      println(f"  blended floor over A+B+C: $$${converged}%.2f  (vs per-category $$3/$$4/$$5)")

      // Balanced traffic → the blended argmax sits at the lowest bidder,
      // under-extracting B and C (the gap per-category floors recover).
      converged shouldBe 3.0 +- 0.01
    }
  }
}
