package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.SiteId

/** EMPIRICAL VALIDATION HARNESS (not a tuning guard).
  *
  * Drives the REAL production `FloorSweepOptimizer` against a faithful model
  * of a market where three advertisers bid in three DISJOINT categories, so
  * they never compete head-to-head:
  *
  *   A → category "ca", maxCpm $3
  *   B → category "cb", maxCpm $4
  *   C → category "cc", maxCpm $5
  *
  * Each category is therefore a single-bidder MONOPOLY auction. The only
  * thing the optimizer can't observe — who wins and what they pay — is the
  * one thing we model, and we model it exactly as the real auction does:
  *
  *   - A single qualifying bidder pays the FLOOR
  *     (AuctioneerEntity.scala:615-617: `case first +: _ => currentFloorCpm`).
  *   - A bidder qualifies iff its bid ≥ the current floor.
  *   - Every auction reports its bidder's bid as both the observed max and
  *     min CPM, whether or not it cleared the floor — AuctioneerEntity folds
  *     below-floor rejects into the reported min/max so the next sweep range
  *     still spans rejected bidders.
  *
  * Question under test: with ONE site-wide floor maximizing BLENDED revenue
  * across the three monopolies, where does the floor converge, and how much
  * surplus is left vs per-category floors? Run this with:
  *
  *   sbt "core/testOnly promovolve.publisher.FloorMonopolyConvergenceSim"
  */
class FloorMonopolyConvergenceSim extends AnyWordSpec with Matchers {

  private val bidders: Map[String, Double] =
    Map("A" -> 3.0, "B" -> 4.0, "C" -> 5.0)

  private val ImpsPerTick = 60
  private val Ticks       = 600

  private final case class Mix(label: String, w: Map[String, Double])

  private final case class SimResult(
      decisions: Vector[FloorSweepOptimizer.CycleDecision],
      convergedFloor: Double,
      servedCats: Set[String],
      realizedPerImp: Double, // $/impression at the converged floor
      optimumPerImp: Double,  // $/impression under per-category floors
  ) {
    def lostPct: Double = (optimumPerImp - realizedPerImp) / optimumPerImp * 100.0
  }

  /** Run the optimizer through ~`Ticks` observation windows under a fixed
    * per-category traffic mix, returning every completed-cycle decision. */
  private def runMix(mix: Mix): SimResult = {
    val opt = new FloorSweepOptimizer(SiteId("monopoly-sim"))
    opt.setMinFloor(0.50)
    opt.setInitialFloor(0.50)

    val counts: Map[String, Int] =
      mix.w.map { case (cat, w) => cat -> math.round(w * ImpsPerTick).toInt }

    val decisions = scala.collection.mutable.ArrayBuffer.empty[FloorSweepOptimizer.CycleDecision]

    for (_ <- 1 to Ticks) {
      val floor = opt.currentFloorCpm
      bidders.foreach { case (cat, bid) =>
        var i = 0
        val n = counts.getOrElse(cat, 0)
        while (i < n) {
          opt.recordObservedBid(bid)     // observed max (this auction)
          opt.recordObservedMinBid(bid)  // observed min (below-floor rejects too)
          if (bid >= floor) opt.recordServedImpression(floor) // clearing = floor
          i += 1
        }
      }
      opt.observe().foreach(_.completedCycle.foreach(decisions += _))
    }

    val converged = decisions.lastOption.map(_.argmaxFloor).getOrElse(opt.currentFloorCpm)
    val served    = bidders.collect { case (c, b) if b >= converged => c }.toSet
    val realized  = bidders.map { case (c, b) => if (b >= converged) mix.w(c) * converged else 0.0 }.sum
    val optimum   = bidders.map { case (c, b) => mix.w(c) * b }.sum
    SimResult(decisions.toVector, converged, served, realized, optimum)
  }

  private def report(mix: Mix, r: SimResult): Unit = {
    val sb = new StringBuilder
    sb.append(s"\n══════ ${mix.label} ══════\n")
    sb.append(s"  decision trajectory (argmax floor per completed cycle): " +
      r.decisions.map(d => f"$$${d.argmaxFloor}%.2f").mkString(" → ") + "\n")
    r.decisions.lastOption.foreach { d =>
      sb.append("  steady-cycle candidate evidence (floor → revenue / imps):\n")
      d.candidates.foreach { c =>
        val mark = if (math.abs(c.floor - d.argmaxFloor) < 0.005) "  ◄ winner" else ""
        sb.append(f"      $$${c.floor}%.2f → rev $$${c.revenue}%8.2f  imps ${c.impressions}%5d$mark%s\n")
      }
    }
    sb.append(f"  CONVERGED FLOOR        : $$${r.convergedFloor}%.2f\n")
    sb.append(s"  categories still served: ${r.servedCats.toVector.sorted.mkString(",")} " +
      s"(starved: ${(bidders.keySet -- r.servedCats).toVector.sorted.mkString(",")})\n")
    sb.append(f"  realized revenue       : $$${r.realizedPerImp}%.3f /imp\n")
    sb.append(f"  per-category optimum   : $$${r.optimumPerImp}%.3f /imp\n")
    sb.append(f"  SURPLUS LOST           : ${r.lostPct}%.1f%%\n")
    println(sb.toString)
  }

  "FloorSweepOptimizer under 3 non-competing monopoly categories" should {

    "BALANCED traffic → collapse to the LOWEST bidder's floor, under-extracting B and C" in {
      val mix = Mix("BALANCED traffic (A=33% B=33% C=33%)",
        Map("A" -> 1.0 / 3, "B" -> 1.0 / 3, "C" -> 1.0 / 3))
      val r = runMix(mix)
      report(mix, r)

      r.decisions.size should be >= 3            // several cycles completed
      r.convergedFloor shouldBe 3.0 +- 0.01      // lands on the $3 bidder
      r.servedCats shouldBe Set("A", "B", "C")   // everyone admitted...
      r.realizedPerImp shouldBe 3.0 +- 0.01      // ...but each pays only $3
      r.optimumPerImp shouldBe 4.0 +- 0.01       // per-category would be (3+4+5)/3
    }

    "C-HEAVY traffic → climb to the TOP bidder's floor, STARVING A and B entirely" in {
      val mix = Mix("C-HEAVY traffic (A=10% B=10% C=80%)",
        Map("A" -> 0.1, "B" -> 0.1, "C" -> 0.8))
      val r = runMix(mix)
      report(mix, r)

      r.convergedFloor shouldBe 5.0 +- 0.01      // chases the dominant $5 bidder
      r.servedCats shouldBe Set("C")             // A and B get zero fill
      r.optimumPerImp shouldBe 4.7 +- 0.01
    }

    "A-LIGHT, B/C balanced → settle in the MIDDLE, starving only the cheapest bidder" in {
      val mix = Mix("A-LIGHT traffic (A=10% B=45% C=45%)",
        Map("A" -> 0.1, "B" -> 0.45, "C" -> 0.45))
      val r = runMix(mix)
      report(mix, r)

      // The grid is [3.00, 3.29, 3.57, 3.86, 4.14, 4.43, 4.71, 5.00]; the
      // blended-revenue argmax sits at the highest floor B still clears.
      r.convergedFloor shouldBe 3.86 +- 0.01
      r.servedCats shouldBe Set("B", "C")        // A starved, B and C served
    }
  }
}
