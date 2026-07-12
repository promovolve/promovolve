# Creative Selection with Thompson Sampling

## Overview

Promovolve uses **Thompson Sampling** for multi-armed bandit creative selection at serve time. When multiple approved creatives are available for a slot, the system balances exploration (trying new creatives) with exploitation (showing proven performers).

## Current Implementation

### Architecture

Thompson Sampling happens directly in `AdServer` at serve time:

```
AdServer.scala
├── creativeStats: Map[CreativeId, CreativeStats]  // In-memory per-creative stats
├── ThompsonSampling.scoreCandidate()               // MAB scoring (argmax picks winner)
└── RecordImpression / RecordClick                  // Stats updates
```

**Key files:**
- `ThompsonSampling.scala` - Core algorithm implementation
- `AdServer.scala` - Integration with ad serving
- `Protocol.scala` - `CreativeStats` definition

### Selection Flow

```
1. Select request arrives at AdServer
   ↓
2. Load candidates from ServeIndexDData
   ↓
3. Check pacing gate (shouldServe?)
   ↓
4. If serving:
   └─→ ThompsonSampling.scoreCandidate(candidate, stats, rng, α) for each candidate
       └─ argmax of sampled score wins (batchAssign / pickBestForSlot in AdServer)
   ↓
5. Reserve budget and return selected creative
```

### Thompson Sampling Algorithm

For each creative, model CTR as Beta distribution:
- **Prior**: Beta(1, 1) = Uniform(0, 1)
- **Posterior**: Beta(clicks + 1, impressions - clicks + 1)

Selection score combines a sampled *engagement* signal with a CPM power term:
```
engagement = sampledCTR + FoldWeight × sampledFoldRate     (FoldWeight = 2.0)
score      = engagement × CPM^α
```
`α` is the publisher-configurable `bidWeight` (0.3 = Discovery, 0.5 = Balanced,
0.7 = Revenue) — see `cpmScore` in `ThompsonSampling.scala`. Fold-rate (dog-ear
pins) is sampled from its own Beta posterior, so engagement is a dual signal,
not CTR alone. New creatives also get a `NewcomerBoost` (additive 0.5,
linearly decaying over the first 50 impressions) so they aren't starved while
their posterior is still wide.

### Cold Start Handling

There are no separate cold-start/warmup/ε-greedy phases — every candidate goes
through the same `scoreCandidate`. A creative with 0 impressions samples its
CTR from the page-classification `categoryScore` prior with ±0.15 uniform
jitter (clamped positive) and its fold-rate from a `Beta(1,3)` cold prior; the
additive `NewcomerBoost` (0.5, decaying to 0 over 50 impressions) is what
guarantees new creatives exposure. Warm creatives sample from their Beta
posteriors.

### Stats Tracking

`CreativeStats` is a minute-bucketed sliding window (`Protocol.scala`):

```scala
final case class CreativeStats(
    buckets: Map[Long, (Int, Int, Int)] = Map.empty, // minute -> (impressions, clicks, folds)
    windowMinutes: Int = 60
)
```

Stats are updated via `RecordImpression` / `RecordClick` / `RecordFold`;
buckets older than `windowMinutes` naturally age out, so the posteriors track
*recent* performance rather than all-time averages.

**Note**: Stats are NOT purely ephemeral — AdServer persists snapshots via
`CreativeStatsSnapshotRepo` and reloads them on startup, so learned posteriors
survive a restart.

## Key Design Decisions

### 1. Serve-Time Selection (Not Auction-Time)

Thompson Sampling runs at serve time, not during auction. This allows:
- Pre-computed auction results (fast cache lookup)
- Multiple candidates cached per slot
- Fresh CTR data used at selection time

### 2. In-Memory Stats, Snapshot-Backed

Creative stats are kept in memory for performance:
- No DB round-trip during hot path
- Periodic snapshots to `CreativeStatsSnapshotRepo`, reloaded on boot
- The 60-minute bucket window means stale history ages out on its own

### 3. Pacing Before Selection

**Critical**: Pacing decision happens BEFORE Thompson Sampling.

```scala
if (pacing.shouldServe(ctx)) {
  winner = argmax(candidates.map(scoreCandidate(_, stats, rng, alpha)))  // TS runs only when serving
  serve(winner)
} else {
  skip()  // Do NOT run TS
}
```

This prevents exploration bias where TS picks an exploration arm that then gets filtered by pacing.

## Configuration

Constants in `ThompsonSampling.scala`:

| Parameter | Value | Description |
|-----------|-------|-------------|
| `FoldWeight` | 2.0 | Fold-rate weight in the engagement combiner |
| `NewcomerBoost` | 0.5 | Additive bonus at 0 impressions |
| `NewcomerDecayImpressions` | 50 | Impressions at which the bonus reaches 0 |
| `ColdFoldPriorAlpha/Beta` | 1 / 3 | Cold-start fold-rate prior Beta(1,3) |

## Example: Multi-Creative Selection

**Campaign A** has 3 creatives assigned to a slot:
- Creative 1: 100 impressions, 8 clicks → Beta(9, 93)
- Creative 2: 50 impressions, 2 clicks → Beta(3, 49)
- Creative 3: 5 impressions, 1 click → Beta(2, 5)

**Selection process:**
1. Sample from each Beta distribution
2. Creative 3 has high variance (few impressions) → might sample high
3. Score = engagement × CPM^α
4. Highest score wins

Over time:
- Best performers (high CTR) win more often
- Poor performers still get occasional traffic (exploration)
- New creatives get fair initial exploration (newcomer bonus + wide posteriors)

## Monitoring

Inspect the serve index (candidates + per-creative state) via the production
endpoint:

```bash
curl http://localhost:8080/v1/publishers/{publisherId}/sites/{siteId}/serve-index
```

## References

- Thompson Sampling: "A Tutorial on Thompson Sampling" (Russo et al., 2018)
- Beta Distribution: Conjugate prior for Bernoulli (click/no-click)
- Multi-Armed Bandits: "Bandit Algorithms" (Lattimore & Szepesvári, 2020)