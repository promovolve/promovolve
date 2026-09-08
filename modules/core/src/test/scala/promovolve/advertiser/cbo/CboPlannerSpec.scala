package promovolve.advertiser.cbo

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.CampaignId
import promovolve.advertiser.cbo.CboAllocator.{ GammaPrior, Params }
import promovolve.advertiser.cbo.CboPlanner.*

import java.time.Instant
import scala.util.Random

/** The advertiser-side planning step, driven with hand-built snapshots (GH #59). */
class CboPlannerSpec extends AnyWordSpec with Matchers {

  private val Eps = 1e-6
  private val ca = CampaignId("a")
  private val cb = CampaignId("b")
  private val cc = CampaignId("c")

  private def snap(
      id: CampaignId,
      budget: Double,
      spent: Double,
      ctas: Long,
      strategy: String = CboStrategy.Auto,
      live: Boolean = true,
      exhausted: Boolean = false
  ): Snapshot = Snapshot(id, strategy, live, budget, spent, ctas, exhausted)

  private def run(
      snaps: Vector[Snapshot],
      priors: Map[CampaignId, GammaPrior] = Map.empty,
      accountDaily: Double = 100.0,
      elapsed: Double = 0.5,
      lastSpent: Map[CampaignId, Double] = Map.empty,
      dayStartPending: Boolean = false,
      seed: Long = 1L
  ): Plan = plan(snaps, priors, accountDaily, elapsed, 1.0 / 96, lastSpent, dayStartPending, new Random(seed))

  "CboPlanner.eligible" should {
    "keep every live campaign regardless of its strategy field (#98)" in {
      val pool = eligible(Vector(
        snap(ca, 50, 10, 1),
        snap(cb, 50, 10, 1, strategy = CboStrategy.Fixed),
        snap(cc, 50, 10, 1, live = false)
      ))
      pool.map(_.campaignId) shouldBe Vector(ca, cb)
    }
  }

  "CboPlanner.plan" should {

    "be inert below two live campaigns and leave priors untouched" in {
      val p = run(Vector(snap(ca, 50, 10, 1), snap(cb, 50, 10, 1, live = false)))
      p.pushes shouldBe empty
      p.priors shouldBe empty
      p.dayStartApplied shouldBe false
      p.note should include("need 2")
    }

    "be inert on a zero account budget" in {
      run(Vector(snap(ca, 50, 10, 1), snap(cb, 50, 10, 1)), accountDaily = 0.0).pushes shouldBe empty
    }

    "seed a prior for every campaign it has not seen, at half a day of its wall" in {
      val p = run(Vector(snap(ca, 40, 0, 0), snap(cb, 60, 0, 0)))
      p.priors.keySet shouldBe Set(ca, cb)
      p.priors(ca).beta shouldBe 20.0 +- Eps
      p.priors(cb).beta shouldBe 30.0 +- Eps
      // No siblings known: one tap-through per day's account budget.
      p.priors(ca).mean shouldBe 0.01 +- Eps
    }

    "seed a newcomer from its siblings' posterior means" in {
      val known = Map(ca -> GammaPrior(4.0, 20.0)) // mean 0.2
      val p = run(Vector(snap(ca, 50, 10, 2), snap(cb, 50, 0, 0)), priors = known)
      p.priors(ca) shouldBe known(ca)
      p.priors(cb).mean shouldBe 0.2 +- Eps
    }

    "push only budgets that actually move, as spent + forward, conserving the remainder" in {
      // a: 3 tap-throughs on 25 spent; b: 0 on 25. Same walls, mid-day, both
      // exactly on pace (25 of 50 at F = 0.5), so neither is capacity-capped.
      val priors = Map(ca -> GammaPrior(2.0, 20.0), cb -> GammaPrior(2.0, 20.0))
      val p = run(Vector(snap(ca, 50, 25, 3), snap(cb, 50, 25, 0)), priors = priors)
      p.pushes.map(_.campaignId).toSet shouldBe Set(ca, cb)
      val pa = p.pushes.find(_.campaignId == ca).get
      val pb = p.pushes.find(_.campaignId == cb).get
      pa.newDailyBudget should be > 50.0
      pb.newDailyBudget should be < 50.0
      pb.newDailyBudget should be >= 25.0 // never below spent
      // Forward allocations sum to the account remainder: 100 - 50.
      (pa.newDailyBudget - 25.0) + (pb.newDailyBudget - 25.0) shouldBe 50.0 +- 1e-6
      pa.moved shouldBe (pa.newDailyBudget - 50.0) +- Eps
    }

    "not push when nothing moves" in {
      // Identical data, identical walls that already sum to the remainder: the
      // draw is a tie-breaker (2% spread) and hysteresis only widens, so the
      // water-fill lands within a hair of the current walls. Whether that
      // hair exceeds PushEpsilon is seed-dependent; what must hold is that
      // any push is tiny.
      val priors = Map(ca -> GammaPrior(50.0, 100.0), cb -> GammaPrior(50.0, 100.0))
      val p = run(Vector(snap(ca, 50, 25, 12), snap(cb, 50, 25, 12)), priors = priors)
      p.pushes.foreach(push => math.abs(push.newDailyBudget - 50.0) should be < 2.0)
    }

    "apply the 20/80 day-start split once when pending and report it" in {
      // Yesterday's final walls 90/10; today spent 0. Blend: 0.2*share + 0.8*equal.
      val p = run(Vector(snap(ca, 90, 0, 0), snap(cb, 10, 0, 0)), dayStartPending = true)
      p.dayStartApplied shouldBe true
      p.note shouldBe "day-start split"
      p.pushes.find(_.campaignId == ca).get.newDailyBudget shouldBe (0.2 * 0.9 + 0.8 * 0.5) * 100 +- Eps
      p.pushes.find(_.campaignId == cb).get.newDailyBudget shouldBe (0.2 * 0.1 + 0.8 * 0.5) * 100 +- Eps
    }

    "feed last tick's spend into the allocator as tickSpend (a just-raised campaign is not capped back)" in {
      // Cumulative pace of `a` looks under-paced (20 of 60 at mid-day) because
      // it was raised last tick, but it spent its whole slice since then; `b`
      // is the weak one. With tickSpend, `a` keeps growing.
      val priors = Map(ca -> GammaPrior(6.0, 20.0), cb -> GammaPrior(1.0, 20.0))
      val snaps = Vector(snap(ca, 60, 20, 6), snap(cb, 40, 20, 1))
      val slice = 40.0 * (1.0 / 96) / 0.5 // last forward 40 over the remaining half day, one tick
      // Shrinkage off: this case is about tickSpend, not evidence weighting.
      val p0 = Params(shrinkageCtas = 0)
      val withTick =
        plan(snaps, priors, 100.0, 0.5, 1.0 / 96, Map(ca -> (20.0 - slice), cb -> 20.0), false, new Random(1), p0)
      val without = plan(snaps, priors, 100.0, 0.5, 1.0 / 96, Map.empty, false, new Random(1), p0)
      val aWith = withTick.pushes.find(_.campaignId == ca).map(_.newDailyBudget).getOrElse(60.0)
      val aWithout = without.pushes.find(_.campaignId == ca).map(_.newDailyBudget).getOrElse(60.0)
      aWith should be >= aWithout - Eps
      aWith should be > 60.0
    }
  }

  "CboPlanner.plan with wall memory (#82)" should {
    "hold a campaign whose wall was pushed within settleTicks and report capped campaigns" in {
      // b is inventory-limited (1 of 40 spent at mid-day) and would be cut when settled.
      val priors = Map(ca -> GammaPrior(6.0, 20.0), cb -> GammaPrior(1.0, 20.0))
      val snaps = Vector(snap(ca, 60, 30, 6), snap(cb, 41, 1, 0))
      val last = Map(ca -> 30.0, cb -> 1.0)
      val settled = plan(snaps, priors, 100.0, 0.5, 1.0 / 96, last, false, new Random(1), Params(), tick = 10L)
      settled.pushes.find(_.campaignId == cb).map(_.newDailyBudget).getOrElse(41.0) should be < 41.0
      settled.capped should contain(cb)

      // Pushed last tick: no capacity cut (not in the capped set), so any cut
      // is rate-driven and stays inside the 25% band (forward 40 -> >= 30).
      val held = plan(snaps, priors, 100.0, 0.5, 1.0 / 96, last, false, new Random(1), Params(),
        tick = 10L, lastPushTick = Map(cb -> 9L))
      held.capped should not contain cb
      held.pushes.find(_.campaignId == cb).foreach(_.newDailyBudget should be >= 1.0 + 30.0 - 1e-6)
    }
  }

  "CboPlanner.material" should {
    "ignore moves under 1% of the wall and keep the absolute epsilon for tiny walls (#85)" in {
      material(10.0, 10.05, Params()) shouldBe false
      material(10.0, 10.2, Params()) shouldBe true
      material(0.001, 0.0012, Params()) shouldBe true
      material(0.0, 0.00005, Params()) shouldBe false
    }
  }

  "CboPlanner.rollPriors" should {
    "decay every campaign that had a snapshot and keep the rest" in {
      val priors = Map(ca -> GammaPrior(2.0, 10.0), cb -> GammaPrior(4.0, 10.0), cc -> GammaPrior(1.0, 5.0))
      val rolled = rollPriors(priors, Vector(snap(ca, 50, 30, 6), snap(cb, 50, 30, 0)))
      rolled(ca).alpha shouldBe 4.0 +- Eps
      rolled(ca).beta shouldBe 20.0 +- Eps
      rolled(cb).alpha shouldBe 2.0 +- Eps
      rolled(cb).beta shouldBe 20.0 +- Eps
      rolled(cc) shouldBe priors(cc)
    }

    "not seed a prior for a campaign that never had one" in {
      rollPriors(Map.empty, Vector(snap(ca, 50, 30, 6))) shouldBe empty
    }
  }

  "CboPlanner.elapsedFraction" should {
    "clamp to [0, 1] and treat a non-positive day as complete" in {
      val start = Instant.parse("2026-09-06T00:00:00Z")
      elapsedFraction(start.plusSeconds(150), start, 300.0) shouldBe 0.5 +- Eps
      elapsedFraction(start.minusSeconds(10), start, 300.0) shouldBe 0.0
      elapsedFraction(start.plusSeconds(1000), start, 300.0) shouldBe 1.0
      elapsedFraction(start, start, 0.0) shouldBe 1.0
    }
  }
}
