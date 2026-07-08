# Taxonomy Ranking System

## Overview

The Taxonomy Ranking system uses **Thompson Sampling** to dynamically score and rank ad categories for each publisher
site. Instead of using fixed rules or simple averages, it employs a probabilistic approach that automatically balances *
*exploration** (trying underperforming categories) and **exploitation** (favoring proven winners).

## Why Thompson Sampling?

Traditional approaches to category ranking have significant drawbacks:

| Approach                     | Problem                                               |
|------------------------------|-------------------------------------------------------|
| Fixed weights                | Can't adapt to changing performance                   |
| Simple averages              | Cold start problem - new categories never get traffic |
| Epsilon-greedy               | Wastes budget on random exploration                   |
| UCB (Upper Confidence Bound) | Overly optimistic, slow convergence                   |

**Thompson Sampling** solves these by:

1. **Natural exploration**: Categories with few opportunities have high variance in their Beta distribution,
   occasionally sampling high even with poor initial data
2. **Automatic exploitation**: Categories with good historical data consistently sample high
3. **No manual tuning**: The algorithm self-adjusts based on observed outcomes
4. **Graceful degradation**: Poor performers naturally fade out but never completely disappear

## Architecture

```
                                     ┌─────────────────────────────────────┐
                                     │         TaxonomyRankerEntity        │
                                     │     (category|siteId sharding)      │
                                     │                                     │
  AuctioneerEntity ──Quote(replyTo)─►│  1. Decay stats (half-life)         │
                                     │  2. Sample from Beta(α, β)          │
                                     │  3. Apply CTR & CPM adjustments     │
                   ◄──Quoted(weight)─│  4. Return sampled score            │
                                     └─────────────────────────────────────┘
                                                      │
                                                      ▼
                                     ┌─────────────────────────────────────┐
                                     │      PostgreSQL (DurableState)      │
                                     │  Persisted: opportunities, wins,    │
                                     │             clicks, revenue         │
                                     └─────────────────────────────────────┘
```

### Entity Sharding

Each `TaxonomyRankerEntity` is sharded by `category|siteId`:

```
"sports|publisher-a.com"  → TaxonomyRankerEntity (sports performance on publisher-a)
"sports|publisher-b.com"  → TaxonomyRankerEntity (sports performance on publisher-b)
"tech|publisher-a.com"    → TaxonomyRankerEntity (tech performance on publisher-a)
```

This per-site sharding ensures:

- Categories perform differently across sites (sports may excel on ESPN but not on cooking blogs)
- No cross-site interference in learning
- Independent exploration/exploitation per publisher

## Thompson Sampling Algorithm

### The Beta Distribution

Thompson Sampling uses the Beta distribution, which is the conjugate prior for Bernoulli trials (win/loss outcomes):

```
Beta(α, β) where:
  α = wins + priorWinsAlpha (successes + prior)
  β = (opportunities - wins) + priorWinsBeta (failures + prior)
```

**Key semantic model** (must be followed for correct sampling):

| Field           | Meaning                  | When Incremented            |
|-----------------|--------------------------|-----------------------------|
| `opportunities` | Auction participations   | Every `RecordFeedback` call |
| `wins`          | Auctions won             | Only when `won=true`        |
| `clicks`        | Clicks on served ads     | Only when `clicked=true`    |
| `revenue`       | Total revenue (currency) | Sum of per-win revenue      |

The Beta distribution has these properties:

- **Mean**: α / (α + β) - the expected win rate
- **Variance**: decreases as α + β increases (more data = more certainty)
- **Shape**: With few observations, the distribution is wide (uncertain); with many, it narrows (confident)

### Sampling Process

On each `Quote` request:

```scala
def sampleThompson(wins: Double, opportunities: Double): Double = {
  val alpha = max(0.01, wins + priorWinsAlpha)  // Successes + prior
  val beta  = max(0.01, (opportunities - wins) + priorWinsBeta)  // Failures + prior
  new BetaDistribution(alpha, beta).sample()
}
```

**Key insight**: Each call returns a DIFFERENT sample. This is intentional:

- High-variance categories (few opportunities) occasionally sample high → exploration
- Low-variance categories (many opportunities) consistently sample near their mean → exploitation

### Score Calculation

The final score combines multiple factors:

```
score = normalizedAvgRevenue × winRateSample × (1 + ctrBonus × ctrAdjustment)
```

Where:

- **winRateSample**: Thompson sample from Beta distribution
- **normalizedAvgRevenue**: `min(avgRevenue, revenueCap) / revenueCap` - revenue potential, capped and normalized
- **ctrAdjustment**: Bayesian CTR estimate
  `(clicks + priorCtrAlpha) / (clicks + priorCtrAlpha + (wins - clicks) + priorCtrBeta)` (CTR = clicks/wins, not
  clicks/opportunities)
- **ctrBonus**: Weight for CTR factor (default 0.3)

## Temporal Decay

To adapt to changing conditions, all statistics decay over time using exponential half-life:

```scala
def decayStats(st: Stats, tNow: Long): Stats = {
  val dt = (tNow - st.updatedMs).toDouble
  val k  = pow(0.5, dt / halfLife.toMillis)  // Decay factor
  st.copy(
    opportunities = st.opportunities * k,
    wins          = st.wins * k,
    clicks        = st.clicks * k,
    revenue       = st.revenue * k
  )
}
```

With default `halfLife = 7 days`:

- After 7 days, stats are worth 50% of original
- After 14 days, stats are worth 25% of original
- After 28 days, stats are worth 6.25% of original

This ensures:

- Recent performance matters more than historical
- Categories can recover from past poor performance
- Seasonal changes are captured

## Persistence Strategy

### Batched Writes

To reduce database load, `RecordFeedback` updates are **buffered in memory** and flushed periodically:

```
┌─────────────────────────────────────────────────────────────┐
│                    TaxonomyRankerEntity                     │
│                                                             │
│  persistedState ◄─── PostgreSQL (recovered on startup)      │
│        │                                                    │
│        ▼                                                    │
│  bufferedState ◄─── RecordFeedback (in-memory updates)      │
│        │                                                    │
│        ▼ (every 5 seconds)                                  │
│  FlushTick ───────► Effect.persist() ───► PostgreSQL        │
└─────────────────────────────────────────────────────────────┘
```

- **Read path**: Uses `effectiveState()` which prefers buffered state
- **Write path**: Buffers updates, flushes on timer
- **Recovery**: On restart, only persisted state is recovered (buffered updates lost)

### Trade-offs

| Approach          | Pros              | Cons                                             |
|-------------------|-------------------|--------------------------------------------------|
| Immediate persist | No data loss      | High DB load, latency                            |
| Batched (current) | Low DB load, fast | Up to `flushEvery` seconds of data loss on crash |

For Thompson Sampling, losing a few seconds of feedback is acceptable - the algorithm is robust to noise.

## Configuration

```hocon
promovolve.taxonomy-ranker {
  # Temporal decay half-life
  half-life = 7 days

  # Revenue normalization (revenue per win above this is capped)
  revenue-cap = 5.0
  default-revenue = 1.5  # Assumed revenue when no wins yet

  # CTR bonus weight in score calculation
  ctr-bonus = 0.3

  # Beta distribution priors for win rate
  prior-wins-alpha = 1.0  # Pseudo-wins (optimistic start)
  prior-wins-beta = 1.0   # Pseudo-losses

  # Beta distribution priors for CTR
  prior-ctr-alpha = 1.0
  prior-ctr-beta = 20.0   # Conservative CTR prior (assumes ~5% CTR)

  # Batched persistence interval
  flush-every = 5 seconds
}
```

### Prior Selection

The priors `(α=1, β=1)` create a **uniform prior** - no initial bias toward winning or losing. This is appropriate when:

- We have no prior knowledge about category performance
- We want fair exploration of all categories initially

For more optimistic exploration, increase `priorWinsAlpha`. For more conservative behavior, increase `priorWinsBeta`.

## Message Flow

### Quote (Auction Request)

```
AuctioneerEntity                    TaxonomyRankerEntity
      │                                     │
      │───── Quote(replyTo) ───────────────►│
      │                                     │ 1. Get effective state
      │                                     │ 2. Decay stats to now
      │                                     │ 3. Sample from Beta(α, β)
      │                                     │ 4. Calculate score
      │◄──── Quoted(category, weight, ts) ──│
      │                                     │
```

### RecordFeedback (Learning)

**CRITICAL**: `RecordFeedback` must be called for EVERY auction opportunity where this
category was considered, not just for wins. This ensures correct Thompson Sampling.

```
AuctionOutcome                      TaxonomyRankerEntity
      │                                     │
      │─ RecordFeedback(revenue, won, clicked)►│
      │                                     │ 1. Get effective state
      │                                     │ 2. Decay stats to now
      │                                     │ 3. opportunities += 1 (always)
      │                                     │ 4. wins += 1 (if won)
      │                                     │ 5. clicks += 1 (if clicked)
      │                                     │ 6. revenue += revenue (0 if lost)
      │                                     │ 7. Buffer in memory
      │                                     │
      │         (later, on FlushTick)       │
      │                                     │ 8. Persist to PostgreSQL
```

**Revenue handling**:

- Pass `revenue = 0` if this category lost the auction
- Pass `revenue = CPM / 1000` (revenue per impression) if this category won

## Integration with Auction

The `AuctioneerEntity` uses taxonomy ranking in Phase 2 of the auction:

```
PageCategoriesClassified(url, categoryScores, slots)
           │
           ▼
   ┌───────────────────────────────────────────────────┐
   │ 1. Select top-K categories by classifier score    │
   │ 2. Fan-out Quote to TaxonomyRankerEntity per cat  │
   │ 3. Aggregate responses (with timeout fallback)    │
   │ 4. Combine: finalScore = classifierScore × weight │
   └───────────────────────────────────────────────────┘
           │
           ▼
   PageCategoriesRanked(url, rankedCategories, slots)
```

### Timeout Handling

If a `TaxonomyRankerEntity` doesn't respond within the timeout:

1. **Check cache**: Use last known weight with half-life decay
2. **Fallback to prior**: Use `priorWeight` (default 0.5) if no cache

This ensures auctions never block on slow category rankers.

## Monitoring

Use `GetStats` to retrieve deterministic metrics for monitoring:

```scala
case class StatsSnapshot(
  categoryId: String,
  siteId: String,
  opportunities: Double,  // Decayed opportunity count
  wins: Double,           // Decayed win count
  clicks: Double,         // Decayed click count
  revenue: Double,        // Decayed revenue
  winRate: Double,        // wins / opportunities
  deterministicScore: Double,  // Non-sampled score for comparison
  timestamp: Instant
)
```

The `deterministicScore` uses the Beta distribution **mean** instead of sampling, useful for:

- Dashboards (consistent values)
- Alerting (detect anomalies)
- Debugging (compare with sampled scores)

## Example: Exploration in Action

Consider a new category "crypto" on publisher-a.com with no history:

```
Initial state: opportunities=0, wins=0
Beta(1, 1) = Uniform distribution [0, 1]

Sample 1: 0.73  ← Could be high! Category gets traffic
Sample 2: 0.21  ← Low, but established categories also sampled low
Sample 3: 0.89  ← High again! More exploration
...
After 100 opportunities with 20 wins:
Beta(21, 81) = Distribution centered around 0.21 with low variance
Samples consistently around 0.18-0.24 → exploitation of known performance
```

This natural transition from exploration to exploitation is the power of Thompson Sampling.