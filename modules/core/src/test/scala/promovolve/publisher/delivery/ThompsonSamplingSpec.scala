package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType }

/**
 * Unit tests for `ThompsonSampling.scoreCandidate`. Focused on the
 * engagement combiner — that fold rate is sampled from its own Beta
 * posterior and contributes to the score with `FoldWeight`.
 *
 * Beta sampling is stochastic. To get deterministic assertions we either:
 *  1) use a fixed seeded `Random` and snapshot a single sample, or
 *  2) run many samples and assert distributional properties (mean ordering).
 *
 * The latter is more robust to library changes; that's what the
 * "fold-rich vs fold-poor" test does. The other tests use single-sample
 * checks where the structural property (e.g. cold vs warm) is robust
 * across any RNG seed.
 */
class ThompsonSamplingSpec extends AnyWordSpec with Matchers {

  import promovolve.publisher.delivery.Protocol.CreativeStats

  private def candidate(cpm: Double, categoryScore: Double = 0.5): CandidateView =
    CandidateView(
      creativeId = CreativeId("cid"),
      campaignId = CampaignId("camp"),
      advertiserId = AdvertiserId("adv"),
      assetUrl = CDNPath("/x.png"),
      mime = MimeType.imagePng,
      width = 300,
      height = 250,
      category = CategoryId("cat"),
      cpm = CPM(cpm),
      classifiedAtMs = 0L,
      categoryScore = categoryScore
    )

  /**
   * Build CreativeStats with N impressions, K clicks, F folds, all in
   * a single recent minute bucket.
   */
  private def stats(impressions: Int, clicks: Int, folds: Int): CreativeStats =
    CreativeStats(buckets = Map(0L -> (impressions, clicks, folds)))

  private def avg(samples: Seq[Double]): Double = samples.sum / samples.size

  "ThompsonSampling.scoreCandidate" should {

    "use the cold-start category prior when impressions == 0" in {
      // CTR derives from categoryScore + jitter; fold-rate samples from
      // the Beta(1,3) cold prior so cold creatives have a real fold
      // component (not 0) without dominating warm ones.
      val cold = stats(impressions = 0, clicks = 0, folds = 0)
      val rng = new scala.util.Random(42L)
      val s = ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), cold, rng, alpha = 0.5)
      s.score should be > 0.0
      s.debugInfo should include("cold")
      s.debugInfo should include("fold=cold")
    }

    "give newcomers a bonus that decays with impressions" in {
      // Engagement bonus is full at impressions=0 and falls linearly to
      // zero at NewcomerDecayImpressions. Compare same-stats creatives
      // at three points along the curve — earlier-stage should always
      // outscore later-stage on average.
      val brandNew = stats(impressions = 0, clicks = 0, folds = 0)
      val midDecay = stats(impressions = 25, clicks = 0, folds = 0)
      val warm = stats(impressions = 60, clicks = 0, folds = 0) // past decay window
      val rng = new scala.util.Random(0L)
      val draws = (s: CreativeStats) =>
        (1 to 1000).map(_ =>
          ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), s, rng, alpha = 0.5).score
        )
      avg(draws(brandNew)) should be > avg(draws(midDecay))
      avg(draws(midDecay)) should be > avg(draws(warm))
    }

    "stop boosting once impressions reach the decay window" in {
      // Past NewcomerDecayImpressions the bonus must read zero so the
      // creative is competing purely on its Beta posteriors.
      ThompsonSampling.newcomerBonus(0) shouldBe ThompsonSampling.NewcomerBoost
      ThompsonSampling.newcomerBonus(ThompsonSampling.NewcomerDecayImpressions) shouldBe 0.0
      ThompsonSampling.newcomerBonus(ThompsonSampling.NewcomerDecayImpressions * 10) shouldBe 0.0
    }

    "let cold creatives compete with warm fold-rich ones" in {
      // Regression: previously cold creatives' foldComponent was hard-
      // coded to 0, so a warm creative with even moderate fold rate
      // dominated cold candidates and newly introduced creatives could
      // never win. With the Beta(1,3) cold-start fold prior plus the
      // newcomer bonus, cold creatives routinely produce a competitive
      // fold component without hijacking the slot outright.
      // Distributional check: cold creatives' average engagement should
      // sit above the warm fold-rich ceiling.
      val cold = stats(impressions = 0, clicks = 0, folds = 0)
      val warmFoldRich = stats(impressions = 100, clicks = 5, folds = 30)
      val rng = new scala.util.Random(0L)
      val coldSamples = (1 to 1000).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0, categoryScore = 0.5), cold, rng, alpha = 0.5).score
      )
      val warmSamples = (1 to 1000).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), warmFoldRich, rng, alpha = 0.5).score
      )
      avg(coldSamples) should be > avg(warmSamples)
    }

    "score warm fold-rich creatives higher than fold-poor ones, all else equal" in {
      // Same CTR, different fold rate. Fold-rich creative wins on the
      // engagement combiner because FoldWeight × foldRate dominates the
      // score gap. Distributional assertion over many samples.
      val foldRich = stats(impressions = 100, clicks = 5, folds = 30) // 5% CTR, 30% fold
      val foldPoor = stats(impressions = 100, clicks = 5, folds = 1) //  5% CTR,  1% fold
      val rng = new scala.util.Random(0L)
      val richSamples = (1 to 500).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), foldRich, rng, alpha = 0.5).score
      )
      val poorSamples = (1 to 500).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), foldPoor, rng, alpha = 0.5).score
      )
      avg(richSamples) should be > avg(poorSamples)
    }

    "score warm fold-rich creatives higher than fold-zero creatives at the same CTR" in {
      // Sanity: a creative with zero folds shouldn't score equal to one
      // with positive folds when CTR is identical.
      val withFolds = stats(impressions = 100, clicks = 10, folds = 20)
      val noFolds = stats(impressions = 100, clicks = 10, folds = 0)
      val rng = new scala.util.Random(0L)
      val a = (1 to 500).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), withFolds, rng, alpha = 0.5).score
      )
      val b = (1 to 500).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), noFolds, rng, alpha = 0.5).score
      )
      avg(a) should be > avg(b)
    }

    "clamp the cold-start sampled CTR at a small positive value" in {
      // A low categoryScore minus the ±0.15 jitter used to go negative,
      // letting a creative win purely on its fold draw while its CTR
      // sample was < 0. The clamp keeps every cold CTR sample ≥ 0.001.
      val cold = stats(impressions = 0, clicks = 0, folds = 0)
      val rng = new scala.util.Random(0L)
      val samples = (1 to 1000).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0, categoryScore = 0.0), cold, rng, alpha = 0.5).sampledCtr
      )
      all(samples) should be >= 0.001
    }

    "tighten the cold fold prior to Beta(1,3) so cold creatives don't dominate outright" in {
      // With the old Beta(1,1) prior the cold fold component averaged
      // FoldWeight × 0.5 = 1.0 and a new creative won near-
      // deterministically. Beta(1,3) pulls the cold mean engagement to
      // catScore + FoldWeight×0.25 + NewcomerBoost. Distributional
      // check: at cpm=1/α=0.5 the average cold score must sit near that
      // mean (≈1.5 at catScore=0.5), well below the ≈2.0 the uniform
      // prior produced.
      val cold = stats(impressions = 0, clicks = 0, folds = 0)
      val rng = new scala.util.Random(0L)
      val avgScore = avg((1 to 2000).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0, categoryScore = 0.5), cold, rng, alpha = 0.5).score
      ))
      val expectedMean = 0.5 +
        ThompsonSampling.FoldWeight * ThompsonSampling.ColdFoldPriorAlpha /
        (ThompsonSampling.ColdFoldPriorAlpha + ThompsonSampling.ColdFoldPriorBeta) +
        ThompsonSampling.NewcomerBoost
      avgScore shouldBe expectedMean +- 0.05
      avgScore should be < 1.7 // the uniform-prior mean was 2.0
    }

    "stamp the fold posterior shape into debugInfo for warm creatives" in {
      val warm = stats(impressions = 50, clicks = 3, folds = 7)
      val rng = new scala.util.Random(42L)
      val s = ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), warm, rng, alpha = 0.5)
      // Beta(clicks+1, imps-clicks+1) = Beta(4, 48)
      s.debugInfo should include("Beta(4,48)")
      // FoldBeta(folds+1, imps-folds+1) = Beta(8, 44)
      s.debugInfo should include("FoldBeta(8,44)")
    }

    "be linear in CPM via cpm^alpha — fold rate doesn't change the CPM lever" in {
      // Two candidates: same stats, different CPM. The CPM lever
      // (`cpm^alpha`) still scales the engagement output linearly.
      val s = stats(impressions = 100, clicks = 10, folds = 5)
      val rng = new scala.util.Random(0L)
      val low = (1 to 200).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 1.0), s, rng, alpha = 1.0).score
      )
      val high = (1 to 200).map(_ =>
        ThompsonSampling.scoreCandidate(candidate(cpm = 4.0), s, rng, alpha = 1.0).score
      )
      // alpha = 1 → score scales linearly in CPM. 4× CPM → ~4× score.
      val ratio = avg(high) / avg(low)
      ratio shouldBe 4.0 +- 0.5 // wide band tolerates Beta variance
    }
  }

  "CreativeStats.recordFold" should {

    "increment the per-minute fold count" in {
      val now = java.time.Instant.ofEpochSecond(60L) // minute bucket = 1
      val s0 = CreativeStats()
      val s1 = s0.recordFold(now)
      val s2 = s1.recordFold(now)
      s2.folds shouldBe 2
      s2.impressions shouldBe 0
      s2.clicks shouldBe 0
    }

    "share a bucket with impressions and clicks at the same minute" in {
      val now = java.time.Instant.ofEpochSecond(60L)
      val s = CreativeStats()
        .recordImpression(now)
        .recordImpression(now)
        .recordClick(now)
        .recordFold(now)
      s.impressions shouldBe 2
      s.clicks shouldBe 1
      s.folds shouldBe 1
      s.buckets.keys.toSet shouldBe Set(1L) // single bucket
      s.buckets(1L) shouldBe (2, 1, 1)
    }
  }
}
