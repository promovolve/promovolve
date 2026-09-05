package promovolve.advertiser.cbo

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import promovolve.advertiser.cbo.CboAllocator.*

import scala.util.Random

/**
 * Property and scenario tests for the pure CBO allocator (GH #57).
 *
 * Everything here runs on a seeded RNG: a failure reproduces exactly.
 */
class CboAllocatorSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  private val Eps = 1e-6

  private val genPrior: Gen[GammaPrior] =
    for {
      mean     <- Gen.choose(0.001, 0.2)
      strength <- Gen.choose(0.5, 50.0)
    } yield GammaPrior(mean * strength, strength)

  private def genInput(id: Int): Gen[Input[Int]] =
    for {
      budget    <- Gen.choose(0.0, 100.0)
      spent     <- Gen.choose(0.0, 120.0).map(s => math.min(s, budget * 1.5))
      ctas      <- Gen.choose(0L, 50L)
      exhausted <- Gen.prob(0.15)
      prior     <- genPrior
    } yield Input(id, spent, ctas, budget, exhausted, prior)

  private val genCase: Gen[(Vector[Input[Int]], Double, Double, Long)] =
    for {
      n        <- Gen.choose(1, 6)
      inputs   <- Gen.sequence[Vector[Input[Int]], Input[Int]]((1 to n).map(genInput))
      account  <- Gen.choose(0.0, 500.0)
      elapsed  <- Gen.choose(0.0, 1.0)
      seed     <- Gen.long
    } yield (inputs, account, elapsed, seed)

  private def floorOf(remaining: Double, n: Int, params: Params): Double =
    params.explorationFloor * remaining / n

  "CboAllocator.allocate" should {

    "conserve the remainder and respect every per-campaign bound" in {
      forAll(genCase) { case (inputs, account, elapsed, seed) =>
        val params = Params()
        val alloc  = allocate(inputs, account, elapsed, new Random(seed), params)
        val n      = inputs.size

        alloc.remaining shouldBe math.max(0.0, account - inputs.map(_.spent).sum) +- Eps
        alloc.results.map(_.id) shouldBe inputs.map(_.id)

        alloc.results.zip(inputs).foreach { case (r, in) =>
          r.forward should be >= -Eps
          r.newDailyBudget shouldBe in.spent + r.forward +- Eps
          r.newDailyBudget should be >= in.spent - Eps // never below spent
          r.moved shouldBe r.forward - in.previousForward +- Eps
          if (alloc.remaining > 0) {
            r.lower should be <= r.upper + Eps
            r.forward should be >= r.lower - Eps
            r.forward should be <= r.upper + Eps
          }
        }

        if (alloc.remaining > 0) {
          val upperSum = alloc.results.map(_.upper).sum
          alloc.forwardTotal shouldBe math.min(alloc.remaining, upperSum) +- 1e-6 * math.max(1.0, alloc.remaining)
        } else {
          alloc.results.foreach(_.forward shouldBe 0.0)
        }
      }
    }

    "keep the exploration floor and the hysteresis band whenever the remainder can afford them" in {
      forAll(genCase) { case (inputs, account, elapsed, seed) =>
        val params = Params()
        val alloc  = allocate(inputs, account, elapsed, new Random(seed), params)
        val n      = inputs.size
        whenever(alloc.remaining > 0) {
          val floor = floorOf(alloc.remaining, n, params)
          alloc.results.zip(inputs).foreach { case (r, in) =>
            val prev = in.previousForward
            // Upper: never more than the band above prev, unless the floor is higher.
            r.forward should be <= math.max(floor, (1 + params.maxMovePerTick) * prev) + Eps
            // Capacity caps the upper bound whenever it is finite.
            if (r.capacity.isFinite) r.forward should be <= r.capacity + Eps
          }
          // Lower bounds only bind when their (unscaled) sum fits into the
          // remainder; the result carries the scaled bound, so recompute.
          val unscaledLowers = alloc.results.zip(inputs).map { case (r, in) =>
            math.min(math.max(floor, (1 - params.maxMovePerTick) * in.previousForward), r.upper)
          }
          if (unscaledLowers.sum <= alloc.remaining + Eps) {
            alloc.results.zip(unscaledLowers).foreach { case (r, lower) =>
              r.forward should be >= lower - Eps
            }
          }
        }
      }
    }

    "return zero forward allocations when the account is spent" in {
      val inputs = Vector(
        Input(1, 60.0, 5L, 70.0, false, GammaPrior(1, 10)),
        Input(2, 50.0, 1L, 55.0, false, GammaPrior(1, 10))
      )
      val alloc = allocate(inputs, 100.0, 0.7, new Random(1))
      alloc.remaining shouldBe 0.0
      alloc.results.map(_.forward) shouldBe Vector(0.0, 0.0)
      alloc.results.map(_.newDailyBudget) shouldBe Vector(60.0, 50.0)
    }

    "handle an empty campaign set" in {
      val alloc = allocate(Vector.empty[Input[Int]], 100.0, 0.5, new Random(1))
      alloc.results shouldBe empty
      alloc.remaining shouldBe 100.0
    }
  }

  "CboAllocator.capacityOf" should {
    val params = Params()

    "be unbounded when exhausted, early in the day, or pace-binding" in {
      capacityOf(Input(1, 10.0, 0L, 20.0, exhausted = true, GammaPrior(1, 1)), 0.5, params) shouldBe
        Double.PositiveInfinity
      capacityOf(Input(1, 0.0, 0L, 20.0, exhausted = false, GammaPrior(1, 1)), 0.01, params) shouldBe
        Double.PositiveInfinity
      // pace = 10 / (20 * 0.5) = 1.0 >= 0.9
      capacityOf(Input(1, 10.0, 0L, 20.0, exhausted = false, GammaPrior(1, 1)), 0.5, params) shouldBe
        Double.PositiveInfinity
    }

    "project the campaign's own remaining day rate when inventory-limited" in {
      // pace = 4 / (20 * 0.5) = 0.4 < 0.9: it would spend 8 over the day, 4 already gone.
      capacityOf(Input(1, 4.0, 0L, 20.0, exhausted = false, GammaPrior(1, 1)), 0.5, params) shouldBe 4.0 +- Eps
    }

    "not cap a campaign that was just raised and is spending at its new rate" in {
      // Budget raised 20 -> 40 at mid-day: cumulative pace = 10 / (40 * 0.5) = 0.5
      // looks inventory-limited, but the last tick (1/96 of the day) spent the
      // full flat-shape slice of the forward it held: instantaneous pace 1.0.
      val dt          = 1.0 / 96
      val lastForward = 30.0
      val slice       = lastForward * dt / (1.0 - (0.5 - dt))
      val in = Input(1, 10.0, 0L, 40.0, exhausted = false, GammaPrior(1, 1), tickSpend = Some(slice))
      capacityOf(in, 0.5, params, dt) shouldBe Double.PositiveInfinity
      // Without the tick reading the old rule would have capped it at its old path.
      capacityOf(in.copy(tickSpend = None), 0.5, params, dt) shouldBe 10.0 +- Eps
    }

    "not slash capacity in a quiet tick when the day so far is on pace" in {
      // On pace cumulatively (pace 1.0), nothing spent in the last tick.
      val in = Input(1, 10.0, 0L, 20.0, exhausted = false, GammaPrior(1, 1), tickSpend = Some(0.0))
      capacityOf(in, 0.5, params, 1.0 / 96) shouldBe Double.PositiveInfinity
    }

    "take the larger projection when genuinely inventory-limited" in {
      // Cumulative: 4 / 0.5 - 4 = 4. Instantaneous: spending 0.05 per tick of
      // 1/96 -> 4.8 per day, 0.5 of the day left -> 2.4. Larger wins: 4.
      val in = Input(1, 4.0, 0L, 20.0, exhausted = false, GammaPrior(1, 1), tickSpend = Some(0.05))
      capacityOf(in, 0.5, params, 1.0 / 96) shouldBe 4.0 +- Eps
    }
  }

  "CboAllocator.drawRate" should {
    "keep the posterior mean and cap the draw's spread at 1/sqrt(minDrawShape)" in {
      val rng   = new Random(3L)
      val post  = GammaPrior(2.0, 40.0) // mean 0.05, only 2 pseudo tap-throughs of evidence
      val draws = Vector.fill(4000)(drawRate(post, rng, Params()))
      val mean  = draws.sum / draws.size
      val cv    = math.sqrt(draws.map(d => (d - mean) * (d - mean)).sum / draws.size) / mean
      mean shouldBe post.mean +- 0.002
      cv should be <= 1.2 / math.sqrt(Params().minDrawShape)
    }

    "recover pure Thompson when minDrawShape is 0" in {
      val rng   = new Random(3L)
      val post  = GammaPrior(2.0, 40.0)
      val draws = Vector.fill(4000)(drawRate(post, rng, Params(minDrawShape = 0)))
      val mean  = draws.sum / draws.size
      val cv    = math.sqrt(draws.map(d => (d - mean) * (d - mean)).sum / draws.size) / mean
      cv shouldBe 1.0 / math.sqrt(2.0) +- 0.1
    }
  }

  "CboAllocator.waterFill" should {

    "hit the target exactly and give a higher rate never less money under shared bounds" in {
      val gen = for {
        n      <- Gen.choose(1, 8)
        rates  <- Gen.listOfN(n, Gen.choose(0.001, 1.0))
        lo     <- Gen.choose(0.0, 5.0)
        hi     <- Gen.choose(5.0, 50.0)
        target <- Gen.choose(0.0, 400.0)
      } yield (rates.toVector, lo, hi, target)

      forAll(gen) { case (rates, lo, hi, target) =>
        val items    = rates.map(r => (r, lo, hi))
        val clamped  = target.max(lo * rates.size).min(hi * rates.size)
        val (_, out) = waterFill(items, target, 0.5)
        out.sum shouldBe clamped +- 1e-6
        out.foreach { x =>
          x should be >= lo - Eps
          x should be <= hi + Eps
        }
        val sortedByRate = rates.zip(out).sortBy(_._1)
        sortedByRate.zip(sortedByRate.tail).foreach { case ((_, x1), (_, x2)) =>
          x2 should be >= x1 - 1e-6
        }
      }
    }

    "equalize marginal return between two unbounded campaigns: split proportional to rate^(1/(1-rho))" in {
      // rho = 0.5 -> x_i proportional to e_i^2; 3x rate gap -> 9:1 split.
      val (_, out) = waterFill(Vector((0.03, 0.0, 1e9), (0.01, 0.0, 1e9)), 100.0, 0.5)
      out(0) shouldBe 90.0 +- 1e-4
      out(1) shouldBe 10.0 +- 1e-4
    }
  }

  /** Knuth's Poisson sampler; lambda is small here. */
  private def poisson(lambda: Double, rng: Random): Long = {
    val l = math.exp(-lambda)
    var k = 0L
    var p = 1.0
    while ({ p *= rng.nextDouble(); p > l }) k += 1
    k
  }

  /**
   * Day simulation: `ticks` allocator ticks over one budget day. Each tick
   * every campaign spends the slice of its forward allocation due by the
   * next tick (it paces to its own wall) and earns tap-throughs at its true
   * rate: Poisson by default, or the deterministic expectation (tracked as
   * a real-valued accumulator, floored to a count) when `noiseless`.
   * Returns the per-tick forward shares of campaign A, the day's
   * tap-through totals and the day's spend. `fixed = true` freezes an
   * equal split; `prior` defaults to a cold day-1 prior.
   */
  private def simulateDay(
      trueRates: Vector[Double],
      accountDaily: Double,
      ticks: Int,
      seed: Long,
      fixed: Boolean,
      params: Params = Params(),
      noiseless: Boolean = false,
      prior: Option[GammaPrior] = None
  ): (Vector[Double], Vector[Long], Vector[Double]) = {
    val rng      = new Random(seed)
    val n        = trueRates.size
    var spent    = Vector.fill(n)(0.0)
    var expected = Vector.fill(n)(0.0)
    var ctas     = Vector.fill(n)(0L)
    var budgets  = Vector.fill(n)(accountDaily / n)
    var lastTick = Vector.fill(n)(Option.empty[Double])
    val p =
      prior.getOrElse(GammaPrior.seed(0L, 0.0, minCtas = 5, fallbackRate = 0.02, strengthSpend = accountDaily / n / 2))
    val shares = Vector.newBuilder[Double]
    val dt     = 1.0 / ticks

    (0 until ticks).foreach { t =>
      val f = t.toDouble / ticks
      if (!fixed) {
        val inputs = (0 until n).toVector.map { i =>
          Input(i, spent(i), ctas(i), budgets(i), exhausted = false, p, tickSpend = lastTick(i))
        }
        val alloc = allocate(inputs, accountDaily, f, rng, params, tickFraction = dt)
        budgets = alloc.results.map(_.newDailyBudget)
      }
      val forward = (0 until n).map(i => math.max(0.0, budgets(i) - spent(i)))
      val total   = forward.sum
      shares += (if (total > 0) forward(0) / total else 0.5)
      val ticksLeft = ticks - t
      (0 until n).foreach { i =>
        val slice = forward(i) / ticksLeft
        spent = spent.updated(i, spent(i) + slice)
        lastTick = lastTick.updated(i, Some(slice))
        if (noiseless) {
          expected = expected.updated(i, expected(i) + trueRates(i) * slice)
          ctas = ctas.updated(i, math.round(expected(i)))
        } else ctas = ctas.updated(i, ctas(i) + poisson(trueRates(i) * slice, rng))
      }
    }
    (shares.result(), ctas, spent)
  }

  "CboAllocator over a simulated day" should {

    "inject no noise of its own: two equal campaigns with equal data stay within 15% of an equal split" in {
      // Noiseless (rounded expected) counts isolate the allocator's own
      // randomness, the Thompson draw, from Poisson noise in the data.
      // Day-2 prior: on a cold prior worth half a pseudo tap-through the
      // FIRST count to land is a threefold rate difference, so integer
      // rounding alone decides the morning; that is data quantization, not
      // the draw, and day 1 is warm-up by design (#38).
      val prior = GammaPrior.seed(25L, 50.0, minCtas = 5, fallbackRate = 0.02, strengthSpend = 25.0)
      val (shares, _, _) =
        simulateDay(Vector(0.5, 0.5), 100.0, ticks = 96, seed = 42L, fixed = false, noiseless = true,
          prior = Some(prior))
      shares.foreach(s => s shouldBe 0.5 +- 0.075)
      val mean = shares.sum / shares.size
      mean shouldBe 0.5 +- 0.02
    }

    "track Poisson noise in the data without running away (symmetric-rate control across seeds)" in {
      // Day 2: prior seeded from yesterday's equal rates at half-day
      // strength; ~25 tap-throughs per campaign per day. The split follows
      // the cumulative count ratio (any responsive allocator does), so per
      // tick it wanders, but across seeds it is centred and bounded.
      val prior = GammaPrior.seed(25L, 50.0, minCtas = 5, fallbackRate = 0.02, strengthSpend = 25.0)
      val endShares = (1 to 30).map { seed =>
        val (shares, _, _) =
          simulateDay(Vector(0.5, 0.5), 100.0, ticks = 96, seed = seed.toLong, fixed = false, prior = Some(prior))
        shares.foreach(s => s shouldBe 0.5 +- 0.35)
        shares.last
      }
      val mean = endShares.sum / endShares.size
      val std  = math.sqrt(endShares.map(s => (s - mean) * (s - mean)).sum / endShares.size)
      mean shouldBe 0.5 +- 0.06
      std should be <= 0.2
    }

    "move budget toward the campaign with the threefold higher rate without starving the other" in {
      val params                 = Params()
      val (shares, ctasAuto, sp) = simulateDay(Vector(1.5, 0.5), 100.0, ticks = 96, seed = 7L, fixed = false, params)
      // Converged by the last quarter of the day: A holds a clear majority.
      val lastQuarter = shares.drop(72)
      lastQuarter.sum / lastQuarter.size should be >= 0.7
      // B never drops below its exploration floor of the equal share.
      shares.foreach(s => (1 - s) should be >= params.explorationFloor / 2 - 1e-9)

      val (_, ctasFixed, _) = simulateDay(Vector(1.5, 0.5), 100.0, ticks = 96, seed = 7L, fixed = true)
      val autoTotal         = ctasAuto.sum.toDouble
      val fixedTotal        = ctasFixed.sum.toDouble
      // Primary gate of #38: tap-throughs per unit of spend up at least 20% over equal split.
      sp.sum shouldBe 100.0 +- 1e-6
      autoTotal / fixedTotal should be >= 1.2
    }
  }

  "GammaPrior.seed" should {
    "use yesterday's rate when it rests on enough tap-throughs, else the fallback" in {
      val fromData = GammaPrior.seed(40L, 200.0, minCtas = 10, fallbackRate = 0.05, strengthSpend = 100.0)
      fromData.mean shouldBe 0.2 +- Eps
      fromData.beta shouldBe 100.0

      val sparse = GammaPrior.seed(3L, 200.0, minCtas = 10, fallbackRate = 0.05, strengthSpend = 100.0)
      sparse.mean shouldBe 0.05 +- Eps
    }
  }

  "decayAtDayRoll" should {
    "fold in the day and halve the evidence" in {
      val next = decayAtDayRoll(GammaPrior(2.0, 10.0), ctas = 6L, spent = 30.0)
      next.alpha shouldBe 4.0 +- Eps
      next.beta shouldBe 20.0 +- Eps
      next.mean shouldBe 0.2 +- Eps
    }
  }

  "dayStartSplit" should {
    "blend yesterday's final split 20/80 with equal split and sum to the account budget" in {
      val split = dayStartSplit(Vector("a", "b"), Map("a" -> 90.0, "b" -> 10.0), 100.0)
      split("a") shouldBe (0.2 * 0.9 + 0.8 * 0.5) * 100 +- Eps
      split("b") shouldBe (0.2 * 0.1 + 0.8 * 0.5) * 100 +- Eps
      split.values.sum shouldBe 100.0 +- Eps
    }

    "give a campaign new today its equal share of the 80%" in {
      val split = dayStartSplit(Vector("a", "b", "c"), Map("a" -> 60.0, "b" -> 40.0), 90.0)
      split("c") shouldBe 0.8 / 3 * 90 +- Eps
      split.values.sum shouldBe 90.0 +- Eps
    }

    "fall back to an equal split when there is no yesterday" in {
      val split = dayStartSplit(Vector("a", "b"), Map.empty[String, Double], 50.0)
      split("a") shouldBe 25.0 +- Eps
      split("b") shouldBe 25.0 +- Eps
    }
  }
}
