package promovolve.advertiser.cbo

import promovolve.CampaignId
import promovolve.advertiser.cbo.CboAllocator.{ GammaPrior, Params }

import java.time.Instant
import scala.util.Random

/**
 * The advertiser-side planning step of Campaign Budget Optimization
 * (GH #38, #59): turns one tick's campaign snapshots into the daily
 * budgets to push, and rolls the priors at the day boundary. Pure, so the
 * entity only does the asks, the tells and the persistence around it.
 */
object CboPlanner {

  /** One campaign as reported by `CampaignEntity.GetCboSnapshot`. */
  final case class Snapshot(
      campaignId: CampaignId,
      strategy: String,
      /** Active and within schedule. */
      live: Boolean,
      dailyBudget: Double,
      spent: Double,
      ctas: Long,
      exhausted: Boolean
  )

  /** A daily budget to push to one campaign, with the "why" for the log line. */
  final case class Push(
      campaignId: CampaignId,
      newDailyBudget: Double,
      /** Change of the forward allocation versus the previous tick. */
      moved: Double,
      sampledRate: Double,
      posteriorMean: Double
  )

  final case class Plan(
      pushes: Vector[Push],
      /** Priors after this tick: unchanged ones plus any seeded for new campaigns. */
      priors: Map[CampaignId, GammaPrior],
      /** True when this plan applied the day-start split (so the pending flag can clear). */
      dayStartApplied: Boolean,
      /** Human-readable reason when nothing was pushed. */
      note: String,
      /** Campaigns whose raise was capacity-capped this tick (next tick probes gently). */
      capped: Set[CampaignId] = Set.empty
  )

  /** Minimum number of live auto campaigns for the allocator to do anything. */
  val MinCampaigns: Int = 2

  /** Budget moves below this are not pushed (money is 4-decimal). */
  val PushEpsilon: Double = 1e-4

  /** A move is pushed only when it is at least `minPushFraction` of the current wall (and above PushEpsilon). */
  def material(current: Double, target: Double, params: Params): Boolean =
    math.abs(target - current) >= math.max(PushEpsilon, params.minPushFraction * current)

  /** Minimum tap-throughs yesterday for the observed rate to seed the prior on its own. */
  val SeedMinCtas: Int = 5

  /** The campaigns the allocator may move money between. */
  def eligible(snapshots: Vector[Snapshot]): Vector[Snapshot] =
    snapshots.filter(s => s.live && s.strategy == CboStrategy.Auto)

  /**
   * Prior for a campaign the allocator has not seen before. Mean = the
   * average posterior mean of the account's other campaigns (their
   * engagement is the best guess for a sibling), else one tap-through per
   * day's account budget, a scale-free default. Strength = half a day at
   * the campaign's own wall.
   */
  def seedPrior(
      snapshot: Snapshot,
      known: Map[CampaignId, GammaPrior],
      accountDaily: Double
  ): GammaPrior = {
    val siblingMeans = known.values.map(_.mean).toVector
    val fallback =
      if (siblingMeans.nonEmpty) siblingMeans.sum / siblingMeans.size
      else if (accountDaily > 0) 1.0 / accountDaily
      else CboAllocator.MinRate
    GammaPrior.seed(
      yesterdayCtas = 0L,
      yesterdaySpent = 0.0,
      minCtas = SeedMinCtas,
      fallbackRate = fallback,
      strengthSpend = math.max(snapshot.dailyBudget / 2.0, CboAllocator.MinStrength)
    )
  }

  /**
   * Plan one tick.
   *
   * @param lastSpent       each campaign's spend as of the previous tick (for instantaneous pace)
   * @param dayStartPending true on the first tick after a budget-day roll: push the 20/80 split
   * @param tick            this tick's ordinal in the entity's incarnation
   * @param lastPushTick    the tick each campaign's wall was last pushed (for `wallSettled`)
   * @param cappedLast      campaigns capacity-capped on the previous tick (for `cappedLastTick`)
   */
  def plan(
      snapshots: Vector[Snapshot],
      priors: Map[CampaignId, GammaPrior],
      accountDaily: Double,
      elapsedFraction: Double,
      tickFraction: Double,
      lastSpent: Map[CampaignId, Double],
      dayStartPending: Boolean,
      rng: Random,
      params: Params = Params(),
      tick: Long = 0L,
      lastPushTick: Map[CampaignId, Long] = Map.empty,
      cappedLast: Set[CampaignId] = Set.empty
  ): Plan = {
    val pool = eligible(snapshots)
    // Live fixed-strategy siblings keep their own walls, so their walls are
    // reserved off the account budget and the allocator pools what is left.
    // Handed the whole account budget it over-promised the auto campaigns:
    // fixed siblings spent the same money first-come-first-served and the
    // auto walls were then stopped by the account cap, not by the split (#99).
    val reservedFixed = snapshots.filter(s => s.live && s.strategy != CboStrategy.Auto).map(_.dailyBudget).sum
    val poolBudget = math.max(0.0, accountDaily - reservedFixed)
    if (pool.size < MinCampaigns)
      Plan(Vector.empty, priors, dayStartApplied = false, s"${pool.size} live auto campaign(s), need $MinCampaigns")
    else if (accountDaily <= 0.0)
      Plan(Vector.empty, priors, dayStartApplied = false, "account daily budget is zero")
    else if (poolBudget <= 0.0)
      Plan(
        Vector.empty,
        priors,
        dayStartApplied = false,
        f"fixed walls ($reservedFixed%.4f) reach the account budget ($accountDaily%.4f); nothing to allocate"
      )
    else {
      // Seed priors for campaigns joining the pool; known ones are untouched.
      val seeded = pool.foldLeft(priors) { (acc, s) =>
        if (acc.contains(s.campaignId)) acc else acc.updated(s.campaignId, seedPrior(s, acc, poolBudget))
      }

      if (dayStartPending) {
        val ids = pool.map(_.campaignId)
        val split =
          CboAllocator.dayStartSplit(ids, pool.map(s => s.campaignId -> s.dailyBudget).toMap, poolBudget, params)
        val pushes = pool.flatMap { s =>
          val target = s.spent + split.getOrElse(s.campaignId, 0.0)
          if (!material(s.dailyBudget, target, params)) None
          else
            Some(Push(
              s.campaignId,
              target,
              (target - s.spent) - math.max(0.0, s.dailyBudget - s.spent),
              seeded(s.campaignId).mean,
              seeded(s.campaignId).mean
            ))
        }
        Plan(pushes, seeded, dayStartApplied = true, "day-start split")
      } else {
        val inputs = pool.map { s =>
          CboAllocator.Input(
            id = s.campaignId,
            spent = s.spent,
            ctas = s.ctas,
            dailyBudget = s.dailyBudget,
            exhausted = s.exhausted,
            prior = seeded(s.campaignId),
            tickSpend = lastSpent.get(s.campaignId).map(prev => math.max(0.0, s.spent - prev)),
            cappedLastTick = cappedLast.contains(s.campaignId),
            wallSettled = lastPushTick.get(s.campaignId).forall(t => tick - t >= params.settleTicks)
          )
        }
        val alloc = CboAllocator.allocate(inputs, poolBudget, elapsedFraction, rng, params, tickFraction)
        val bySnapshot = pool.map(s => s.campaignId -> s).toMap
        val pushes = alloc.results.flatMap { r =>
          val current = bySnapshot(r.id).dailyBudget
          if (!material(current, r.newDailyBudget, params)) None
          else Some(Push(r.id, r.newDailyBudget, r.moved, r.sampledRate, r.posteriorMean))
        }
        val note = if (pushes.isEmpty) f"no move (remaining ${alloc.remaining}%.4f)" else ""
        Plan(pushes, seeded, dayStartApplied = false, note, alloc.results.filter(_.capped).map(_.id).toSet)
      }
    }
  }

  /**
   * Day roll: fold the day's observations into each prior and decay
   * (two-day half-life by default). Campaigns without a snapshot keep
   * their prior unchanged; campaigns without a prior stay unseeded.
   */
  def rollPriors(
      priors: Map[CampaignId, GammaPrior],
      lastSnapshots: Vector[Snapshot],
      params: Params = Params()
  ): Map[CampaignId, GammaPrior] =
    lastSnapshots.foldLeft(priors) { (acc, s) =>
      acc.get(s.campaignId) match {
        case Some(p) => acc.updated(s.campaignId, CboAllocator.decayAtDayRoll(p, s.ctas, s.spent, params))
        case None    => acc
      }
    }

  /** Elapsed fraction of a budget day of `dayLengthSeconds` that began at `dayStart`, clamped to [0, 1]. */
  def elapsedFraction(now: Instant, dayStart: Instant, dayLengthSeconds: Double): Double =
    if (dayLengthSeconds <= 0) 1.0
    else {
      val elapsed = (now.toEpochMilli - dayStart.toEpochMilli) / 1000.0
      (elapsed / dayLengthSeconds).max(0.0).min(1.0)
    }
}
