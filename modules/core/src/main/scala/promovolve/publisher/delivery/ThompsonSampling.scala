package promovolve.publisher.delivery

import promovolve.CPM
import promovolve.publisher.CandidateView

/**
 * Thompson Sampling implementation for multi-armed bandit creative selection.
 *
 * Uses Beta-Bernoulli model where each creative's CTR is modeled as:
 * - Prior: Beta(1, 1) = Uniform(0, 1)
 * - Posterior: Beta(clicks + 1, non-clicks + 1)
 *
 * Selection score combines sampled engagement with CPM:
 *   score = (sampledCTR + FoldWeight × sampledFoldRate + newcomerBonus) * CPM^α
 *
 * Where α (bidWeight) is publisher-configurable:
 *   - α=0.3 (Discovery): quality dominates, small advertisers compete
 *   - α=0.5 (Balanced): sqrt(CPM), default
 *   - α=0.7 (Revenue): higher bids win more often
 *
 * This balances exploration (uncertain creatives get sampled more widely)
 * with exploitation (high CTR + high CPM creatives win more often).
 */
object ThompsonSampling {

  /**
   * Per-candidate score plus the inputs that produced it. The batch
   * path computes this once per candidate against the joint pool
   * then greedy-picks across slots.
   *
   * `score` and `sampledCtr` come from the Thompson draws — allocation
   * explores. `engagement` and `meanScore` are the same combiner
   * evaluated at the posterior MEANS — deterministic given the
   * creative's stats — and exist solely for pricing: sample for
   * allocation, price on means (see [[qualityAdjustedClearing]]).
   */
  final case class CandidateScore(
      score: Double,
      sampledCtr: Double,
      engagement: Double,
      meanScore: Double,
      debugInfo: String
  )

  /**
   * CPM scoring component using the publisher's bid-weight exponent.
   * Separated out so per-slot and batch scoring produce identical
   * CPM behaviour. `alpha` is clamped through the same call shape
   * everywhere to keep auctions reproducible.
   */
  def cpmScore(cpm: Double, alpha: Double): Double =
    math.pow(math.max(cpm, 0.001), alpha)

  /**
   * Quality-adjusted clearing price for an Exploitation winner.
   *
   * Inverts the full score formula `score = engagement × CPM^α`
   * (engagement = ctr + FoldWeight × foldRate + newcomerBonus) against
   * the runner-up's score: the winner pays the minimum CPM at which
   * their score still beats the runner-up given their own engagement.
   *
   * {{{
   *   clearingCpm = (bestLoserScore / winnerEngagement) ^ (1/α)
   * }}}
   *
   * Both inputs must come from the posterior-MEAN side of
   * [[CandidateScore]] (`engagement` for the winner, `meanScore` for
   * the runner-up), never from the Thompson draws: sample for
   * allocation, price on means. This keeps the price reproducible
   * from observable state (same auction state → same price) and
   * symmetric — the winner's fold posterior and newcomer bonus
   * discount its price exactly as the runner-up's raise it.
   *
   * Clamped to `[siteFloor, winnerBid]`. Returns `siteFloor` when
   * there is no runner-up (`bestLoserScore = 0`, e.g. only one
   * eligible candidate fits the slot), when the winner's engagement
   * is zero (degenerate), or when α is non-positive.
   *
   * Pure — depends only on its arguments. Used by both the per-slot
   * exploitation path and the batch-serve assignment so prices stay
   * comparable under the same pool.
   */
  def qualityAdjustedClearing(
      winnerEngagement: Double,
      winnerBid: CPM,
      bestLoserScore: Double,
      alpha: Double,
      siteFloor: CPM
  ): CPM = {
    if (winnerEngagement > 0 && bestLoserScore > 0 && alpha > 0) {
      val q = math.pow(bestLoserScore / winnerEngagement, 1.0 / alpha)
      CPM.max(CPM.min(CPM(q), winnerBid), siteFloor)
    } else {
      siteFloor
    }
  }

  /**
   * Weight on the fold-rate posterior in the engagement combiner.
   * CTR carries weight 1.0; folds are rarer and signal stronger intent
   * (intentional save vs. impulse expand), so the default doubles their
   * influence per unit rate. Tunable per-publisher would be a future
   * config knob; today it's a constant so the auction stays predictable.
   */
  val FoldWeight: Double = 2.0

  /**
   * Additive engagement bonus given to newly introduced creatives so
   * they actually get exposure rather than being out-competed before
   * their first impression accrues. Decays linearly from full boost
   * at impressions=0 down to zero at NewcomerDecayImpressions, after
   * which the creative competes on its own Beta posterior. UCB-flavored
   * exploration: the system over-prefers under-sampled candidates
   * until they've had a chance to prove (or disprove) themselves.
   */
  val NewcomerBoost: Double = 0.5

  /**
   * Impressions at which the newcomer bonus fully decays to zero. Large
   * enough that the boost continues to nudge selection during the early
   * exploitation period when a few imps have started shaping the
   * posterior.
   */
  val NewcomerDecayImpressions: Int = 50

  /**
   * Cold-start fold-rate prior: Beta(1, 3), mean 0.25. A uniform
   * Beta(1,1) prior (mean 0.5) gave cold creatives an expected fold
   * component of FoldWeight × 0.5 = 1.0 — an order of magnitude above
   * typical warm fold components (0.02–0.1) — so a newly introduced
   * creative won its slot near-deterministically. Beta(1,3) keeps
   * genuine Thompson exploration on the fold dimension while
   * `newcomerBonus` stays the primary exploration knob.
   */
  val ColdFoldPriorAlpha: Double = 1.0
  val ColdFoldPriorBeta: Double = 3.0

  /**
   * Linearly-decaying newcomer bonus. Zero once the creative has
   * accumulated NewcomerDecayImpressions impressions.
   */
  def newcomerBonus(impressions: Int): Double = {
    if (impressions >= NewcomerDecayImpressions) 0.0
    else NewcomerBoost * (1.0 - impressions.toDouble / NewcomerDecayImpressions.toDouble)
  }

  /**
   * Score one candidate given its current stats, the alpha weight,
   * and an RNG for Beta sampling. Cold creatives (no impressions)
   * score against a jittered `categoryScore` prior; warm creatives
   * sample CTR and fold-rate from independent Beta posteriors over
   * their click and fold history. The two engagement signals are
   * combined linearly (`ctr + FoldWeight * foldRate`) before being
   * multiplied by `cpm^alpha` to produce the auction score.
   *
   * Alongside the sampled score, the same combiner is evaluated at
   * the posterior MEANS into `engagement` / `meanScore` — the
   * deterministic pricing inputs for [[qualityAdjustedClearing]].
   *
   * Used by the batch-serve greedy assignment.
   */
  def scoreCandidate(
      candidate: CandidateView,
      stats: Protocol.CreativeStats,
      rng: scala.util.Random,
      alpha: Double
  ): CandidateScore = {
    val (sampledCtr, meanCtr, ctrInfo, foldComponent, meanFoldComponent, foldInfo) =
      if (stats.impressions == 0) {
        // Cold start. CTR uses the categoryScore prior (page-classification
        // signal we DO have), clamped positive — a low categoryScore minus
        // jitter must not produce a negative CTR sample. Fold-rate has no
        // comparable prior, so we sample from the Beta(1,3) cold prior for
        // proper Thompson exploration on the fold dimension. Without this,
        // cold creatives can never beat warm fold-rich ones (their
        // foldComponent stays at 0 while a warm creative accrues up to
        // FoldWeight×foldRate), so newly introduced creatives never win
        // the auction.
        val ctr = math.max(0.001, candidate.categoryScore + (rng.nextDouble() * 0.3 - 0.15))
        val foldRate = sampleBeta(ColdFoldPriorAlpha, ColdFoldPriorBeta, rng)
        val meanFoldRate = ColdFoldPriorAlpha / (ColdFoldPriorAlpha + ColdFoldPriorBeta)
        (
          ctr,
          math.max(0.001, candidate.categoryScore),
          s"cold(catScore=${candidate.categoryScore})",
          FoldWeight * foldRate,
          FoldWeight * meanFoldRate,
          s"fold=cold-Beta(${ColdFoldPriorAlpha.toInt},${ColdFoldPriorBeta.toInt})×$FoldWeight"
        )
      } else {
        val cA = stats.clicks.toDouble + 1.0
        val cB = (stats.impressions - stats.clicks).toDouble + 1.0
        val ctr = sampleBeta(cA, cB, rng)
        val fA = stats.folds.toDouble + 1.0
        val fB = (stats.impressions - stats.folds).toDouble + 1.0
        val foldRate = sampleBeta(fA, fB, rng)
        (
          ctr,
          cA / (cA + cB),
          s"Beta(${cA.toInt},${cB.toInt})",
          FoldWeight * foldRate,
          FoldWeight * (fA / (fA + fB)),
          s"FoldBeta(${fA.toInt},${fB.toInt})×$FoldWeight"
        )
      }
    val bonus = newcomerBonus(stats.impressions)
    val sampledEngagement = sampledCtr + foldComponent + bonus
    val meanEngagement = meanCtr + meanFoldComponent + bonus
    val cpmFactor = cpmScore(candidate.cpm.toDouble, alpha)
    val score = sampledEngagement * cpmFactor
    val bonusInfo = if (bonus > 0) f"|newBonus=$bonus%.2f" else ""
    CandidateScore(
      score = score,
      sampledCtr = sampledCtr,
      engagement = meanEngagement,
      meanScore = meanEngagement * cpmFactor,
      debugInfo = s"$ctrInfo|$foldInfo$bonusInfo"
    )
  }

  /**
   * Minimum impression share factor per campaign. The actual threshold is
   * MinImpressionShareFactor / numCampaigns — so with 10 campaigns the threshold
   * is 5% (half of the natural 10% fair share), not a fixed 15%.
   * This prevents the guarantee from firing constantly in dense markets.
   */
  val MinImpressionShareFactor: Double = 0.50 // half of natural fair share

  /**
   * Sample from Beta distribution using the Gamma trick.
   * Beta(α, β) = X / (X + Y) where X ~ Gamma(α, 1), Y ~ Gamma(β, 1)
   */
  def sampleBeta(alpha: Double, beta: Double, rng: scala.util.Random): Double = {
    val x = sampleGamma(alpha, rng)
    val y = sampleGamma(beta, rng)
    if (x + y == 0) 0.5 else x / (x + y)
  }

  /** Sample from Gamma(shape, 1) using Marsaglia-Tsang method. */
  def sampleGamma(shape: Double, rng: scala.util.Random): Double = {
    if (shape < 1.0) {
      // For shape < 1: Gamma(shape) = Gamma(shape + 1) * U^(1/shape)
      val u = rng.nextDouble()
      sampleGamma(shape + 1.0, rng) * math.pow(u, 1.0 / shape)
    } else {
      // Marsaglia-Tsang for shape >= 1
      val d = shape - 1.0 / 3.0
      val c = 1.0 / math.sqrt(9.0 * d)

      var result = 0.0
      var done = false

      while (!done) {
        var x = rng.nextGaussian()
        var v = 1.0 + c * x

        while (v <= 0) {
          x = rng.nextGaussian()
          v = 1.0 + c * x
        }

        v = v * v * v
        val u = rng.nextDouble()

        if (u < 1.0 - 0.0331 * x * x * x * x) {
          result = d * v
          done = true
        } else if (math.log(u) < 0.5 * x * x + d * (1.0 - v + math.log(v))) {
          result = d * v
          done = true
        }
      }

      result
    }
  }
}
