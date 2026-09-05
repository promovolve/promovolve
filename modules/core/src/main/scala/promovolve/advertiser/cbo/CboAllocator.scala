package promovolve.advertiser.cbo

import promovolve.publisher.delivery.ThompsonSampling

import scala.util.Random

/**
 * Campaign Budget Optimization allocator (GH #38, #57).
 *
 * Pure function: given the live `auto` campaigns of one advertiser and the
 * account daily budget, re-splits the remaining account budget into FORWARD
 * allocations (money each campaign may still spend today) and the daily
 * budget to push to each campaign (`spent + forward`). No actor, clock,
 * database or money type here; the entity converts at its boundary.
 *
 * Model. Tap-throughs per unit of spend `e_i ~ Gamma(alpha0, beta0)` with
 * `ctas_i ~ Poisson(e_i * spent_i)`, so the posterior is
 * `Gamma(alpha0 + ctas_i, beta0 + spent_i)`. One sample per campaign per
 * tick (Thompson): exploration for free, no epsilon.
 *
 * Objective. Returns are concave in spend (more budget wins a campaign's
 * MARGINAL auctions: higher clearing prices, worse placements), modelled as
 * `f_i(x) = e_i * x^rho`, `0 < rho < 1`. The KKT condition equalizes the
 * marginal return `f_i'(x_i) = lambda` across funded campaigns, which under
 * the box bounds below is a 1-D bisection on `lambda` (water-filling). This
 * is NOT the greedy "sort by e_i and fill to capacity" vertex: greedy on
 * SAMPLED rates flips the whole hysteresis band between two equal campaigns
 * every tick on nothing but sampling noise. `rho -> 1` recovers greedy.
 *
 * Bounds per campaign (all on the forward allocation):
 *   - exploration floor: `explorationFloor * R / N` so a starved campaign
 *     can always prove itself (budget analogue of the newcomer boost);
 *   - hysteresis: within `[1 - maxMove, 1 + maxMove]` of the previous
 *     forward allocation (`dailyBudget - spent`), so a downshift never reads
 *     as a hard over-pace to the campaign's PI controller;
 *   - capacity: a campaign whose pace is below `paceBindingThreshold` is
 *     inventory-limited, and its forward allocation is capped at its own
 *     projected remaining spend; extra budget would buy nothing.
 * Never below spent: forward allocations are >= 0 by construction and the
 * pushed daily budget is `spent + forward` (there is no reservation refund).
 *
 * Conservation: `sum(forward) = min(R, sum(upper))` and
 * `sum(forward) >= min(R, sum(lower))`; when the bounds cannot absorb `R`
 * the remainder stays under the account wall, which remains the backstop
 * it is today, and the hysteresis band widens next tick.
 */
object CboAllocator {

  /** Gamma prior/posterior on tap-throughs per unit of spend: shape `alpha`, rate `beta`. */
  final case class GammaPrior(alpha: Double, beta: Double) {
    require(alpha > 0 && beta > 0, s"GammaPrior needs alpha, beta > 0 (got $alpha, $beta)")
    def mean: Double = alpha / beta

    /** Posterior after observing `ctas` tap-throughs over `spent` units of spend. */
    def posterior(ctas: Long, spent: Double): GammaPrior =
      GammaPrior(alpha + ctas.toDouble, beta + math.max(0.0, spent))
  }

  object GammaPrior {

    /**
     * Seed a prior for a new budget day. Mean = yesterday's observed rate when
     * it rests on at least `minCtas` tap-throughs, else `fallbackRate` (the
     * campaign's engagement per unit of spend scaled by the account's
     * CTA-per-engagement ratio, or the network ratio). Strength `beta0` is
     * the spend equivalent of about half a day at the campaign's rate, so a
     * fresh campaign is neither ignored nor trusted.
     */
    def seed(
        yesterdayCtas: Long,
        yesterdaySpent: Double,
        minCtas: Int,
        fallbackRate: Double,
        strengthSpend: Double
    ): GammaPrior = {
      val observed = if (yesterdaySpent > 0) yesterdayCtas.toDouble / yesterdaySpent else 0.0
      val mean     = if (yesterdayCtas >= minCtas && observed > 0) observed else math.max(fallbackRate, MinRate)
      val beta0    = math.max(strengthSpend, MinStrength)
      GammaPrior(math.max(mean * beta0, MinShape), beta0)
    }
  }

  /** Smallest rate the allocator reasons about; keeps the power law finite. */
  val MinRate: Double = 1e-9

  /** Floor on prior shape/strength so a posterior is always proper. */
  val MinShape: Double    = 1e-3
  val MinStrength: Double = 1e-3

  final case class Params(
      /** Fraction of the equal share `R / N` every live auto campaign keeps. */
      explorationFloor: Double = 0.20,
      /** Max relative move of a forward allocation per tick, both directions. */
      maxMovePerTick: Double = 0.25,
      /** Pace at or above this means the pacer is binding: capacity unbounded. */
      paceBindingThreshold: Double = 0.90,
      /** Below this elapsed fraction of the day, capacity is unknown: treat as unbounded. */
      minElapsedFraction: Double = 0.02,
      /** Concavity of returns in spend: `f(x) = e * x^rho`. */
      returnsExponent: Double = 0.5,
      /**
       * Minimum shape of the Thompson draw. The rate is drawn from
       * `Gamma(s, s / mean)` with `s = max(alpha, minDrawShape)`: same
       * posterior mean, coefficient of variation at most `1 / sqrt(s)`.
       * Creative selection averages thousands of draws per tick; here ONE
       * draw per campaign per tick moves real money, and under the squared
       * response (`rho = 0.5`) a draw with 18% spread already swings the
       * split of two equal campaigns by about 12 points, and even a 4%
       * spread puts the worst of a day's 96 ticks 8 points off. The
       * exploration floor guarantees exploration on its own, so the draw
       * only breaks near-ties: 2048 caps its spread at about 2%.
       * 0 = pure Thompson.
       */
      minDrawShape: Double = 2048.0,
      /** Posterior decay applied at day roll: `alpha <- factor * (alpha + ctas)`. 0.5 = two-day half-life. */
      dayRollDecay: Double = 0.5,
      /** Day-start split = blend * yesterday's final split + (1 - blend) * equal split. */
      dayStartBlend: Double = 0.20
  ) {
    require(explorationFloor >= 0 && explorationFloor <= 1, "explorationFloor in [0,1]")
    require(maxMovePerTick > 0 && maxMovePerTick < 1, "maxMovePerTick in (0,1)")
    require(returnsExponent > 0 && returnsExponent < 1, "returnsExponent in (0,1)")
    require(minDrawShape >= 0, "minDrawShape >= 0")
    require(dayRollDecay > 0 && dayRollDecay <= 1, "dayRollDecay in (0,1]")
    require(dayStartBlend >= 0 && dayStartBlend <= 1, "dayStartBlend in [0,1]")
  }

  /** One live `strategy = auto` campaign as the allocator sees it this tick. */
  final case class Input[K](
      id: K,
      /** Today's spend so far. */
      spent: Double,
      /** Today's fraud-filtered tap-throughs. */
      ctas: Long,
      /** The daily budget currently configured on the campaign (last push, or its own wall). */
      dailyBudget: Double,
      /** Campaign reported `InsufficientBudget` today: the strongest absorb signal. */
      exhausted: Boolean,
      prior: GammaPrior,
      /**
       * Spend during the last allocator tick, when known. Cumulative pace
       * cannot tell "just raised, not caught up yet" from "cannot spend";
       * the last tick's spend against the forward it had is the
       * instantaneous pace that can. `None` on the first tick of a day.
       */
      tickSpend: Option[Double] = None
  ) {
    require(spent >= 0 && dailyBudget >= 0 && ctas >= 0, s"Input($id) needs non-negative spend/budget/ctas")
    require(tickSpend.forall(_ >= 0), s"Input($id) needs non-negative tickSpend")

    /** Forward allocation this campaign currently holds. */
    def previousForward: Double = math.max(0.0, dailyBudget - spent)
  }

  final case class Result[K](
      id: K,
      /** Money the campaign may still spend today. */
      forward: Double,
      /** `spent + forward`: the daily budget to push via UpdateConfig. */
      newDailyBudget: Double,
      /** Change of the forward allocation versus the previous tick. */
      moved: Double,
      sampledRate: Double,
      posteriorMean: Double,
      /** Forward capacity; `Double.PositiveInfinity` when the pacer is binding. */
      capacity: Double,
      lower: Double,
      upper: Double
  )

  final case class Allocation[K](
      /** Remaining account budget this tick: `max(0, accountDaily - sum(spent))`. */
      remaining: Double,
      /** The multiplier the water-fill settled on; informational. */
      lambda: Double,
      results: Vector[Result[K]]
  ) {
    def forwardTotal: Double = results.map(_.forward).sum
  }

  /**
   * Allocate the remaining account budget across the given campaigns.
   *
   * @param accountDaily    the advertiser's daily budget
   * @param elapsedFraction elapsed fraction of the advertiser-local budget day, in [0, 1]
   * @param rng             seeded RNG (one Gamma sample per campaign)
   * @param tickFraction    length of the last allocator tick as a fraction of the day; 0 when
   *                        unknown (then only cumulative pace informs capacity)
   */
  def allocate[K](
      inputs: Vector[Input[K]],
      accountDaily: Double,
      elapsedFraction: Double,
      rng: Random,
      params: Params = Params(),
      tickFraction: Double = 0.0
  ): Allocation[K] = {
    val n         = inputs.size
    val spentSum  = inputs.map(_.spent).sum
    val remaining = math.max(0.0, accountDaily - spentSum)
    if (n == 0 || remaining <= 0.0)
      Allocation(
        remaining,
        0.0,
        inputs.map(i => Result(i.id, 0.0, i.spent, -i.previousForward, 0.0, i.prior.mean, 0.0, 0.0, 0.0))
      )
    else {
      val f     = elapsedFraction.max(0.0).min(1.0)
      val floor = params.explorationFloor * remaining / n

      val prepared = inputs.map { in =>
        val post = in.prior.posterior(in.ctas, in.spent)
        val rate = drawRate(post, rng, params)

        val capacity = capacityOf(in, f, params, tickFraction)
        val prev     = in.previousForward
        // Hysteresis band around the previous forward allocation. The upper
        // bound is never below the floor, so a campaign parked at 0 can grow
        // again; the lower bound is never above the capacity, so an
        // inventory-limited campaign is not force-fed.
        val lowerRaw = math.max(floor, (1.0 - params.maxMovePerTick) * prev)
        val upperRaw = math.max(floor, (1.0 + params.maxMovePerTick) * prev)
        val upper    = math.min(capacity, upperRaw)
        val lower    = math.min(lowerRaw, upper)
        Prep(in, rate, post.mean, capacity, lower, upper)
      }

      // If the lower bounds alone exceed R, scale them down proportionally;
      // the sum of allocations can never exceed the remaining account budget.
      val lowerSum = prepared.map(_.lower).sum
      val scale    = if (lowerSum > remaining && lowerSum > 0) remaining / lowerSum else 1.0
      val bounded  = prepared.map(p => p.copy(lower = p.lower * scale, upper = math.max(p.upper, p.lower * scale)))

      val upperSum = bounded.map(_.upper).sum
      val target   = math.min(remaining, upperSum)

      val (lambda, forwards) =
        waterFill(bounded.map(p => (p.rate, p.lower, p.upper)), target, params.returnsExponent)

      val results = bounded.zip(forwards).map { case (p, fwd) =>
        Result(
          p.in.id,
          fwd,
          p.in.spent + fwd,
          fwd - p.in.previousForward,
          p.rate,
          p.posteriorMean,
          p.capacity,
          p.lower,
          p.upper
        )
      }
      Allocation(remaining, lambda, results)
    }
  }

  private final case class Prep[K](
      in: Input[K],
      rate: Double,
      posteriorMean: Double,
      capacity: Double,
      lower: Double,
      upper: Double
  )

  /**
   * Thompson draw of the tap-through rate from the posterior, with its
   * shape floored at `params.minDrawShape` (see there). Never below
   * `MinRate`.
   */
  def drawRate(posterior: GammaPrior, rng: Random, params: Params): Double = {
    val s = math.max(posterior.alpha, params.minDrawShape)
    math.max(MinRate, ThompsonSampling.sampleGamma(s, rng) * posterior.mean / s)
  }

  /**
   * Forward capacity of one campaign; `PositiveInfinity` when more budget
   * would be spent.
   *
   * Two pace readings, and the campaign is pace-binding if EITHER is at or
   * above the threshold:
   *   - cumulative: `spent / (dailyBudget * F)`. Alone it cannot tell a
   *     campaign that was just raised (spend lags the new ceiling) from one
   *     that cannot spend, and capping the former at its old rate undoes
   *     every raise the allocator makes.
   *   - instantaneous, when `tickSpend` is known: last tick's spend against
   *     the flat-shape slice of the forward it held at the start of that
   *     tick. Alone it would slash capacity in a quiet hour.
   * When neither binds the campaign is inventory-limited and its capacity
   * is the larger of the two projections of its own remaining-day spend.
   */
  def capacityOf[K](in: Input[K], elapsedFraction: Double, params: Params, tickFraction: Double = 0.0): Double =
    if (in.exhausted || elapsedFraction < params.minElapsedFraction || in.dailyBudget <= 0.0)
      Double.PositiveInfinity
    else {
      val f              = elapsedFraction
      val cumulativePace = in.spent / (in.dailyBudget * f)
      val cumulativeProj = math.max(0.0, in.spent / f - in.spent)

      val instantaneous: Option[(Double, Double)] =
        for {
          ts <- in.tickSpend
          dt <- Option(tickFraction).filter(_ > 0)
        } yield {
          val fLast       = math.max(0.0, f - dt)
          val lastForward = in.previousForward + ts
          val expected    = if (fLast < 1.0) lastForward * dt / (1.0 - fLast) else 0.0
          val pace        = if (expected > 0) ts / expected else 0.0
          val projection  = (ts / dt) * (1.0 - f)
          (pace, projection)
        }

      val binding = cumulativePace >= params.paceBindingThreshold ||
        instantaneous.exists(_._1 >= params.paceBindingThreshold)
      if (binding) Double.PositiveInfinity
      else math.max(cumulativeProj, instantaneous.map(_._2).getOrElse(0.0))
    }

  /**
   * Water-fill under box bounds. Each campaign's unconstrained response to
   * the multiplier is `x(lambda) = (rho * e / lambda)^(1 / (1 - rho))`,
   * clamped to `[lower, upper]`; the sum is non-increasing in `lambda`, so
   * bisection on `log(lambda)` finds the multiplier whose clamped sum hits
   * `target`. Returns `(lambda, forwards)`. `target` is clamped into
   * `[sum(lower), sum(upper)]` defensively.
   */
  def waterFill(items: Vector[(Double, Double, Double)], target: Double, rho: Double): (Double, Vector[Double]) = {
    val lowerSum = items.map(_._2).sum
    val upperSum = items.map(_._3).sum
    val t        = target.max(lowerSum).min(upperSum)
    if (items.isEmpty) (0.0, Vector.empty)
    else if (t >= upperSum) (0.0, items.map(_._3))
    else if (t <= lowerSum) (Double.PositiveInfinity, items.map(_._2))
    else {
      val inv = 1.0 / (1.0 - rho)
      def response(rate: Double, lambda: Double): Double = math.pow(rho * rate / lambda, inv)
      def clampedSum(lambda: Double): Double =
        items.iterator.map { case (rate, lo, hi) => response(rate, lambda).max(lo).min(hi) }.sum

      // Bracket lambda in log space: at the bracket's low end every campaign
      // sits at its upper bound, at the high end at its lower bound.
      var lo = -60.0
      var hi = 60.0
      var i  = 0
      while (i < 200) {
        val mid = 0.5 * (lo + hi)
        if (clampedSum(math.exp(mid)) > t) lo = mid else hi = mid
        i += 1
      }
      val lambda   = math.exp(0.5 * (lo + hi))
      val forwards = items.map { case (rate, lo0, hi0) => response(rate, lambda).max(lo0).min(hi0) }
      // Bisection leaves a residual of at most the flat parts of the clamped
      // response; spread it over the campaigns that still have room so the
      // conservation invariant holds exactly.
      val residual = t - forwards.sum
      if (math.abs(residual) < 1e-9) (lambda, forwards)
      else {
        val room =
          if (residual > 0) forwards.zip(items).map { case (x, (_, _, hi0)) => hi0 - x }
          else forwards.zip(items).map { case (x, (_, lo0, _)) => x - lo0 }
        val roomSum = room.sum
        if (roomSum <= 0) (lambda, forwards)
        else (lambda, forwards.zip(room).map { case (x, r) => x + residual * (r / roomSum) })
      }
    }
  }

  /** Day-roll posterior decay: `Gamma(d * (alpha + ctas), d * (beta + spent))`. */
  def decayAtDayRoll(prior: GammaPrior, ctas: Long, spent: Double, params: Params = Params()): GammaPrior = {
    val post = prior.posterior(ctas, spent)
    GammaPrior(
      math.max(MinShape, params.dayRollDecay * post.alpha),
      math.max(MinStrength, params.dayRollDecay * post.beta)
    )
  }

  /**
   * Day-start forward split: yesterday's final split blended with equal
   * split, scaled to `accountDaily`. Campaigns absent from `yesterdayFinal`
   * (new today) count as 0 on the yesterday side and 1/N on the equal side.
   */
  def dayStartSplit[K](
      ids: Vector[K],
      yesterdayFinal: Map[K, Double],
      accountDaily: Double,
      params: Params = Params()
  ): Map[K, Double] = {
    val n = ids.size
    if (n == 0 || accountDaily <= 0) Map.empty
    else {
      val ySum  = ids.map(id => math.max(0.0, yesterdayFinal.getOrElse(id, 0.0))).sum
      val blend = if (ySum > 0) params.dayStartBlend else 0.0
      ids.map { id =>
        val yShare = if (ySum > 0) math.max(0.0, yesterdayFinal.getOrElse(id, 0.0)) / ySum else 0.0
        id -> accountDaily * (blend * yShare + (1.0 - blend) / n)
      }.toMap
    }
  }
}
