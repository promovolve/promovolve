# Delivery Module: Detailed Algorithms

This document provides mathematical specifications and flowcharts for the algorithms in `promovolve.publisher.delivery`.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Rate Tracking](#2-rate-tracking)
3. [Traffic Shape Learning](#3-traffic-shape-learning)
4. [Pacing Algorithm (PI Controller)](#4-pacing-algorithm-pi-controller)
5. [Thompson Sampling](#5-thompson-sampling)
6. [Budget Reservation](#6-budget-reservation)
7. [Complete Flow State Machine](#7-complete-flow-state-machine)

---

## 1. System Overview

### High-Level Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            AD SERVING PIPELINE                                   │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│   HTTP Request                                                                  │
│        │                                                                        │
│        ▼                                                                        │
│   ┌─────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐      │
│   │  Rate   │────▶│   Traffic   │────▶│   Pacing    │────▶│  Thompson   │      │
│   │Tracking │     │   Shape     │     │    Gate     │     │  Sampling   │      │
│   └─────────┘     └─────────────┘     └─────────────┘     └─────────────┘      │
│        │                │                   │                   │              │
│        │                │                   │                   │              │
│        ▼                ▼                   ▼                   ▼              │
│   smoothedRate    trafficCDF(t)      throttleProb         winner ad           │
│                                            │                   │              │
│                                            │                   │              │
│                                     ┌──────┴──────┐            │              │
│                                     │             │            │              │
│                                     ▼             ▼            ▼              │
│                                  SKIP         ┌─────────────────┐             │
│                              (91% of reqs)    │     Budget      │             │
│                                               │   Reservation   │             │
│                                               └─────────────────┘             │
│                                                       │                       │
│                                                       ▼                       │
│                                                   SELECTED                    │
│                                                  (9% of reqs)                 │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Key Principle

> **"Pacing gates volume, not choice."**
>
> The pacing decision (serve or skip) happens BEFORE Thompson Sampling.
> This prevents exploration bias where TS picks an exploration arm that then gets filtered.

---

## 2. Rate Tracking

### Purpose

Track real-time request arrival rate for the pacing controller's base throttle calculation.

### Mathematical Model

**Exponential Moving Average (EMA)** with sliding window:

```
Let:
  W = window duration (1000 ms)
  α = smoothing factor (0.3)
  n = requests in current window
  Δt = actual window duration when closed

Instant rate when window closes:
  r_instant = n / Δt

Smoothed rate update:
  r_smoothed(t) = α · r_instant + (1 - α) · r_smoothed(t-1)
```

### Algorithm

```
┌─────────────────────────────────────────────────────────────┐
│                    RATE TRACKING                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  State:                                                     │
│    windowStartMs: Long = 0                                  │
│    requestsInWindow: Long = 0                               │
│    smoothedRate: Double = 0.0                               │
│                                                             │
│  Constants:                                                 │
│    WINDOW_MS = 1000                                         │
│    ALPHA = 0.3                                              │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  function updateRate(nowMs):                                │
│    │                                                        │
│    ├─► requestsInWindow += 1                                │
│    │                                                        │
│    ├─► if windowStartMs == 0:                               │
│    │       windowStartMs = nowMs                            │
│    │                                                        │
│    ├─► elapsed = nowMs - windowStartMs                      │
│    │                                                        │
│    └─► if elapsed >= WINDOW_MS:                             │
│            │                                                │
│            ├─► instantRate = requestsInWindow / (elapsed/1000)
│            │                                                │
│            ├─► smoothedRate = ALPHA × instantRate           │
│            │                  + (1-ALPHA) × smoothedRate    │
│            │                                                │
│            ├─► windowStartMs = nowMs                        │
│            │                                                │
│            └─► requestsInWindow = 0                         │
│                                                             │
│    return (windowStartMs, requestsInWindow, smoothedRate)   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Pseudocode

```python
def update_request_rate(now_ms, window_start_ms, requests_in_window, current_smoothed_rate):
    WINDOW_MS = 1000
    ALPHA = 0.3

    # Initialize window on first call
    effective_window_start = now_ms if window_start_ms == 0 else window_start_ms

    # Count this request
    new_requests_in_window = requests_in_window + 1

    # Check if window has elapsed
    window_elapsed = now_ms - effective_window_start

    if window_elapsed >= WINDOW_MS:
        # Calculate instant rate and update EMA
        window_sec = window_elapsed / 1000.0
        instant_rate = new_requests_in_window / window_sec
        new_smoothed_rate = ALPHA * instant_rate + (1 - ALPHA) * current_smoothed_rate

        # Reset window
        return (now_ms, 0, new_smoothed_rate)
    else:
        # Continue accumulating
        return (effective_window_start, new_requests_in_window, current_smoothed_rate)
```

---

## 3. Traffic Shape Learning

### Purpose

Learn hourly traffic patterns to shape the expected spend curve. Without this, linear pacing over-throttles during peaks and sets impossible targets during valleys.

### Mathematical Model

**Per-bucket EMA** with scale-invariant normalization:

```
Let:
  B = number of buckets (24 for hourly)
  α = learning rate (0.1, ~10 days to converge)
  V[b] = relative volume for bucket b
  E = EMA of absolute request counts (for normalization)

Bucket assignment:
  b(t) = floor(t / (86400/B))

When bucket b completes with n requests:
  E ← α · n + (1-α) · E                    // Update scale estimate
  observation = n / max(1, E)              // Normalized observation
  V[b] ← α · observation + (1-α) · V[b]    // Update bucket volume

Cumulative Distribution Function (CDF):
  F(t) = Σ(V[0..b-1]) + V[b] · fraction_into_bucket
         ─────────────────────────────────────────────
                        Σ(V[0..B-1])

Relative volume (for base throttle multiplier):
  R(t) = V[b(t)] / (Σ V / B)
```

### Visual: Traffic Shape Effect

```
Linear Pacing (no shape):                Traffic-Shaped Pacing:

Budget: $30/day                          Budget: $30/day

Expected │                               Expected │        ___
Spend    │              ___              Spend    │    ___/
($)      │          ___/                 ($)      │   /
         │      ___/                              │  /
         │  ___/                                  │ /
         │_/                                      │/___
         └──────────────────▶                     └──────────────────▶
         0    6   12   18  24 hr                  0    6   12   18  24 hr

Result: Over-throttle at peaks,          Result: Budget follows traffic,
        impossible targets at valleys             smooth pacing throughout
```

### Algorithm

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TRAFFIC SHAPE TRACKER                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  State:                                                                     │
│    bucketVolume[24]: Array[Double] = [1.0, 1.0, ..., 1.0]  // Uniform init │
│    cachedTotal: Double = 24.0                                               │
│    currentBucket: Int = -1                                                  │
│    requestsInBucket: Long = 0                                               │
│    emaBucketRequests: Double = 1.0                                          │
│    frozen: Boolean = false                                                  │
│                                                                             │
│  Constants:                                                                 │
│    BUCKET_COUNT = 24                                                        │
│    ALPHA = 0.1                                                              │
│    BUCKET_DURATION_SEC = 3600                                               │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function recordRequest(elapsedSeconds):                                    │
│    │                                                                        │
│    ├─► bucket = floor(elapsedSeconds / BUCKET_DURATION_SEC)                 │
│    │   bucket = clamp(bucket, 0, 23)                                        │
│    │                                                                        │
│    └─► if bucket ≠ currentBucket:                                           │
│            │                                                                │
│            ├─► if !frozen AND currentBucket >= 0 AND requestsInBucket > 0:  │
│            │       applyBucketUpdate(currentBucket, requestsInBucket)       │
│            │                                                                │
│            ├─► currentBucket = bucket                                       │
│            └─► requestsInBucket = (frozen ? 0 : 1)                          │
│        else if !frozen:                                                     │
│            requestsInBucket += 1                                            │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function applyBucketUpdate(bucket, requests):                              │
│    │                                                                        │
│    ├─► // Update scale estimate                                             │
│    │   emaBucketRequests = α × requests + (1-α) × emaBucketRequests         │
│    │                                                                        │
│    ├─► // Normalize observation (scale-invariant)                           │
│    │   scale = max(1.0, emaBucketRequests)                                  │
│    │   observation = requests / scale                                       │
│    │                                                                        │
│    ├─► // EMA update                                                        │
│    │   oldValue = bucketVolume[bucket]                                      │
│    │   newValue = α × observation + (1-α) × oldValue                        │
│    │   bucketVolume[bucket] = newValue                                      │
│    │                                                                        │
│    └─► // Maintain cached total incrementally                               │
│        cachedTotal += (newValue - oldValue)                                 │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function cumulativeFractionAtTime(elapsedSeconds):                         │
│    │                                                                        │
│    ├─► bucket = floor(elapsedSeconds / BUCKET_DURATION_SEC)                 │
│    │   bucket = clamp(bucket, 0, 23)                                        │
│    │                                                                        │
│    ├─► bucketStart = bucket × BUCKET_DURATION_SEC                           │
│    │   fractionIntoBucket = (elapsedSeconds - bucketStart) / BUCKET_DURATION│
│    │   fractionIntoBucket = clamp(fractionIntoBucket, 0, 1)                 │
│    │                                                                        │
│    ├─► prevCumulative = Σ bucketVolume[0..bucket-1]                         │
│    │   currentContribution = bucketVolume[bucket] × fractionIntoBucket      │
│    │                                                                        │
│    └─► return (prevCumulative + currentContribution) / cachedTotal          │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function relativeVolumeAtTime(elapsedSeconds):                             │
│    │                                                                        │
│    ├─► bucket = floor(elapsedSeconds / BUCKET_DURATION_SEC)                 │
│    │   avgVol = cachedTotal / BUCKET_COUNT                                  │
│    │                                                                        │
│    └─► return bucketVolume[bucket] / avgVol                                 │
│        // Returns multiplier: >1 = peak hour, <1 = valley hour              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Convergence Analysis

```
With α = 0.1:

Initial weight after n updates:
  w_initial = (1 - α)^n

After 10 bucket updates: w = 0.9^10 ≈ 0.35 (35% initial, 65% learned)
After 20 bucket updates: w = 0.9^20 ≈ 0.12 (12% initial, 88% learned)
After 30 bucket updates: w = 0.9^30 ≈ 0.04 (4% initial, 96% learned)

For hourly buckets: ~30 days to 96% convergence
For 15-min buckets: ~8 days to 96% convergence
```

---

## 4. Pacing Algorithm (PI Controller)

### Purpose

Control delivery rate to spread budget evenly across the day, accounting for traffic patterns.

### Mathematical Model

**Direct Throttle PI Controller** with asymmetric gains:

```
Given:
  B_daily = daily budget ($)
  S_today = spend so far today ($)
  t = elapsed seconds since day start
  T = day duration (86400s or simulated)
  r = observed request rate (req/sec)
  CPM = average cost per mille ($)
  F(t) = traffic shape CDF at time t
  V(t) = relative traffic volume at time t

Expected spend fraction (traffic-shaped):
  f_expected = F(t)                        // From traffic shape CDF

Spend ratio:
  ρ = S_today / (B_daily × f_expected)
  // ρ > 1: over-pacing (spending too fast)
  // ρ < 1: under-pacing (spending too slow)

Base target impression rate:
  r_base = (B_daily / T) / (CPM / 1000)    // Even distribution
  r_target = r_base × V(t)                  // Traffic-shaped target

Base throttle (rate-based):
  θ_base = max(0, 1 - r_target / r)        // 0 if r ≤ r_target

PI error signal:
  e = 1 - ρ                                 // Positive = under-pacing

Asymmetric gains (2x for over-pacing recovery):
  K_p' = K_p × (2 if e < 0 else 1)
  K_i' = K_i × (2 if e < 0 else 1)

Integral with anti-windup:
  I ← clamp(I + e × Δt, -1, +1)

PI adjustment:
  a = K_p' × e + K_i' × I                   // Positive = reduce throttle

Final throttle probability:
  θ = clamp(θ_base - a, 0, 1)

Serve decision:
  serve = (random() ≥ θ)
```

### Visual: PI Controller Response

```
                    Spend Ratio (ρ)
                         │
        Under-pacing     │     Over-pacing
            ◄────────────┼────────────►
                         │
     ┌───────────────────┼───────────────────┐
     │                   │                   │
     │  e > 0            │  e < 0            │
     │  Reduce throttle  │  Increase throttle│
     │  (catch up)       │  (slow down)      │
     │                   │                   │
     │  Kp = 0.5         │  Kp = 1.0 (2×)    │
     │  Ki = 0.3         │  Ki = 0.6 (2×)    │
     │                   │                   │
     └───────────────────┼───────────────────┘
                         │
                      ρ = 1.0
                    (on pace)
```

### Algorithm

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      RATE-AWARE PACING (PI Controller)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  State:                                                                     │
│    integralError: Double = 0.0                                              │
│    lastTimestamp: Option[Instant] = None                                    │
│                                                                             │
│  Constants:                                                                 │
│    Kp = 0.5              // Proportional gain                               │
│    Ki = 0.3              // Integral gain                                   │
│    OVERPACE_MULT = 2.0   // Asymmetric multiplier for over-pacing           │
│    GRACE_FRAC = 0.01     // 1% of day grace period                          │
│    MIN_GRACE_SEC = 10.0  // Minimum grace period                            │
│    MAX_INTEGRAL = 1.0    // Anti-windup bound                               │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function throttleProbability(ctx: PacingContext):                          │
│    │                                                                        │
│    ├─► // Hard stops                                                        │
│    │   if ctx.dailyBudget ≤ 0: return 1.0                                   │
│    │   if ctx.remainingHours ≤ 0: return 1.0                                │
│    │   if ctx.remainingBudget ≤ 0: return 1.0                               │
│    │                                                                        │
│    ├─► // Base target rate (traffic-shaped)                                 │
│    │   evenTarget = (dailyBudget / dayDurationSec) / (avgCpm / 1000)        │
│    │   shapeMultiplier = trafficShape.relativeVolumeAtTime(elapsed)         │
│    │   baseTarget = evenTarget × shapeMultiplier                            │
│    │                                                                        │
│    ├─► // Base throttle from rate                                           │
│    │   if requestRate > baseTarget AND baseTarget > 0:                      │
│    │       baseThrottle = 1.0 - (baseTarget / requestRate)                  │
│    │   else:                                                                │
│    │       baseThrottle = 0.0                                               │
│    │                                                                        │
│    ├─► // Grace period check                                                │
│    │   graceFrac = max(GRACE_FRAC, MIN_GRACE_SEC / dayDurationSec)          │
│    │   if elapsedFraction < graceFrac:                                      │
│    │       return clamp(baseThrottle, 0, 1)  // Base only during grace      │
│    │                                                                        │
│    ├─► // PI error calculation                                              │
│    │   spendRatio = todaySpend / expectedSpend  // expectedSpend uses CDF   │
│    │   error = 1.0 - spendRatio                                             │
│    │   isOverPacing = (error < 0)                                           │
│    │                                                                        │
│    ├─► // Asymmetric gains                                                  │
│    │   effectiveKp = Kp × (OVERPACE_MULT if isOverPacing else 1)            │
│    │   effectiveKi = Ki × (OVERPACE_MULT if isOverPacing else 1)            │
│    │                                                                        │
│    ├─► // Integral update with anti-windup                                  │
│    │   dt = secondsSince(lastTimestamp)                                     │
│    │   integralError += error × dt                                          │
│    │   integralError = clamp(integralError, -MAX_INTEGRAL, MAX_INTEGRAL)    │
│    │                                                                        │
│    ├─► // PI output                                                         │
│    │   pTerm = effectiveKp × error                                          │
│    │   iTerm = effectiveKi × integralError                                  │
│    │   adjustment = pTerm + iTerm  // Positive = reduce throttle            │
│    │                                                                        │
│    ├─► // Apply adjustment                                                  │
│    │   adjustedThrottle = baseThrottle - adjustment                         │
│    │                                                                        │
│    └─► return clamp(adjustedThrottle, 0, 1)                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Aggregate Pacing (Multi-Campaign)

When multiple campaigns compete for the same traffic:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        AGGREGATE PACING DECISION                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Instead of:  θ = max(θ_campaign1, θ_campaign2, ...)  // Too conservative   │
│                                                                             │
│  We use:      Sum budgets and spends across all campaigns                   │
│               Make ONE throttle decision for the request                    │
│                                                                             │
│  Algorithm:                                                                 │
│    totalBudget = Σ campaign.dailyBudget                                     │
│    totalSpend = Σ (campaign.todaySpend + campaign.pendingSpend)             │
│    avgCpm = mean(campaign.cpm for all campaigns)                            │
│    earliestDayStart = min(campaign.dayStart)                                │
│                                                                             │
│    aggregateCtx = PacingContext(totalBudget, totalSpend, ...)               │
│    θ_aggregate = pacingStrategy.throttleProbability(aggregateCtx)           │
│                                                                             │
│    if random() >= θ_aggregate:                                              │
│        // Request passes → ALL campaigns eligible for Thompson Sampling     │
│        proceed to TS                                                        │
│    else:                                                                    │
│        // Request skipped → no campaign serves                              │
│        return NoCandidates                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Thompson Sampling

### Purpose

Select which ad creative to serve using multi-armed bandit exploration/exploitation.

### Mathematical Model

**Dual Beta-Bernoulli Model** (CTR + fold-rate):

The bandit tracks TWO engagement signals per creative — clicks and folds
(intentional "save for later" / dog-ear saves). Each has its own independent
Beta posterior, sampled separately and combined linearly before the CPM
weighting.

```
For each warm creative c with:
  n_c = impressions (window of last 1000)
  k_c = clicks
  f_c = folds

Posterior distributions (independent):
  CTR_c      ~ Beta(k_c + 1,  n_c - k_c + 1)   // Prior: Beta(1,1) = Uniform
  FoldRate_c ~ Beta(f_c + 1,  n_c - f_c + 1)   // Prior: Beta(1,1) = Uniform

Sampling (two independent draws):
  sampledCtr_c  ~ Beta(k_c + 1,  n_c - k_c + 1)
  foldRate_c    ~ Beta(f_c + 1,  n_c - f_c + 1)

Combined engagement signal:
  engagement_c = sampledCtr_c + FoldWeight × foldRate_c + newcomerBonus(n_c)
  where FoldWeight = 2.0   // folds signal stronger intent, weighted 2×

Newcomer bonus (additive, linearly decaying UCB-style boost):
  newcomerBonus(n) = NewcomerBoost × (1 - n / NewcomerDecayImpressions)  for n < 50
                   = 0                                                    for n ≥ 50
  where NewcomerBoost = 0.5, NewcomerDecayImpressions = 50

Score (quality-adjusted auction score):
  score_c = engagement_c × CPM_c^α

  where α (alpha) is the publisher's bidWeight exponent:
    α = 0.3 (Discovery): quality dominates, small advertisers compete
    α = 0.5 (Balanced):  effectively sqrt(CPM), default
    α = 0.7 (Revenue):   higher bids win more often

  CPM_c^α is computed as math.pow(max(CPM_c, 0.001), α).

Selection:
  winner = argmax_c(score_c)
```

> **Note:** The score is multiplicative — `engagement × CPM^α` — NOT the old
> `θ × log(1 + CPM)` form. The CPM exponent α is the single publisher-facing
> knob that trades quality (low α) against revenue (high α).

### Selection Decision Tree

`select()` short-circuits through a series of phases in priority order. Only
if none of the earlier phases fire does it reach the Thompson exploitation
loop. Each phase tags the result with a `SelectionReason` that downstream
pricing uses.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      SELECTION DECISION TREE                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  0. SOLO: only 1 (valid) candidate?       → reason = Solo                   │
│                                                                             │
│  1. ALL candidates cold (0 imps)?         → reason = ColdStart              │
│        score = (categoryScore ± 0.1 noise) × CPM^α                          │
│        argmax (categoryScore-weighted, NOT uniform random)                  │
│                                                                             │
│  2. ALL candidates under WarmupImps (10)? → reason = Warmup                 │
│        serve the least-explored (min impressions) — round robin            │
│                                                                             │
│  3. SOME cold AND random() < 0.30?        → reason = Exploration            │
│        ε-greedy: pick a random cold candidate                              │
│                                                                             │
│  4. IMPRESSION SHARE GUARANTEE fires?     → reason = ImpressionShare        │
│        a campaign is below its dynamic fair share → force-serve it         │
│        (see "Impression Share Guarantee" below)                            │
│                                                                             │
│  5. OTHERWISE                             → reason = Exploitation           │
│        full dual-signal Thompson Sampling, argmax(score)                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Cold-start sampling detail:** A cold creative (0 impressions) has no click
history, so its CTR draw uses the page-classification `categoryScore` as a
prior with `±0.15` uniform jitter. Its fold-rate has no comparable prior, so
it is sampled from `Beta(1,1)` (uniform) for proper Thompson exploration on
the fold dimension — without this, a cold creative's fold component would be
stuck at 0 and it could never out-compete a warm fold-rich creative.

### Impression Share Guarantee

After the cold-start / warmup / ε-greedy phases, before exploitation, the
selector enforces a minimum exposure floor per campaign so a single high-score
campaign cannot fully starve the others.

```
Fires only when:  totalImps > WarmupImpressions (10)  AND  #campaigns > 1

Dynamic fair-share threshold (per campaign):
  fairShare = MinImpressionShareFactor / numCampaigns
            = 0.50 / numCampaigns       // half the natural 1/N fair share

A campaign is "starved" if:
  campaignImps / totalImps < fairShare

If any starved campaign exists:
  worst = starved campaign with the fewest impressions
  winner = highest-CPM candidate of `worst`     → reason = ImpressionShare
```

> The factor is `0.50/N`, NOT a fixed 15%. With 10 campaigns the threshold is
> 5% (half of the natural 10% fair share), which keeps the guarantee from
> firing constantly in dense markets.

### Algorithm

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         THOMPSON SAMPLING                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Constants:                                                                 │
│    EXPLORATION_RATE = 0.30          // ε for epsilon-greedy                 │
│    WARMUP_IMPRESSIONS = 10          // Minimum before "warm"                │
│    FOLD_WEIGHT = 2.0                // fold-rate weight in engagement       │
│    NEWCOMER_BOOST = 0.5             // additive boost at 0 imps             │
│    NEWCOMER_DECAY_IMPRESSIONS = 50  // boost decays to 0 here              │
│    MIN_IMPRESSION_SHARE_FACTOR = 0.50  // → fairShare = 0.50/numCampaigns   │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function select(candidates, creativeStats, rng, alpha):                    │
│    │                                                                        │
│    ├─► // Filter invalid CPMs                                               │
│    │   validCandidates = candidates.filter(c => c.cpm > 0)                  │
│    │   if validCandidates.size ≤ 1: return (head, reason=Solo)              │
│    │                                                                        │
│    ├─► // Identify cold/warm candidates                                     │
│    │   coldCandidates = validCandidates.filter(c =>                         │
│    │       creativeStats[c.id].impressions == 0)                            │
│    │   underExplored = validCandidates.filter(c =>                          │
│    │       creativeStats[c.id].impressions < WARMUP_IMPRESSIONS)            │
│    │                                                                        │
│    ├─► // FULL COLD START: categoryScore-weighted argmax (reason=ColdStart)│
│    │   if coldCandidates.size == validCandidates.size:                      │
│    │       scored = validCandidates.map(c =>                                │
│    │           (c.categoryScore + Uniform(-0.1,+0.1)) × c.cpm^alpha)        │
│    │       return maxBy(scored)                                            │
│    │                                                                        │
│    ├─► // WARMUP PHASE: round-robin to least explored (reason=Warmup)       │
│    │   if ALL validCandidates under WARMUP_IMPRESSIONS:                     │
│    │       return minBy(underExplored, c => stats[c.id].impressions)        │
│    │                                                                        │
│    ├─► // PARTIAL COLD START: ε-greedy exploration (reason=Exploration)     │
│    │   if coldCandidates.nonEmpty AND random() < EXPLORATION_RATE:          │
│    │       return randomChoice(coldCandidates)                              │
│    │                                                                        │
│    ├─► // IMPRESSION SHARE GUARANTEE (reason=ImpressionShare)               │
│    │   impsByCampaign = group validCandidates by campaign, sum imps         │
│    │   totalImps = Σ impsByCampaign                                         │
│    │   if totalImps > WARMUP_IMPRESSIONS AND #campaigns > 1:                │
│    │       fairShare = MIN_IMPRESSION_SHARE_FACTOR / #campaigns             │
│    │       starved = campaigns with imps/totalImps < fairShare              │
│    │       if starved.nonEmpty:                                            │
│    │           worst = starved.minBy(imps)                                 │
│    │           return highest-CPM candidate of worst                       │
│    │                                                                        │
│    ├─► // EXPLOITATION: dual-signal Thompson Sampling (reason=Exploitation) │
│    │   scored = validCandidates.map { c =>                                  │
│    │       stats = creativeStats[c.id]                                      │
│    │       if stats.impressions == 0:                                       │
│    │           sampledCtr = c.categoryScore + Uniform(-0.15, +0.15)         │
│    │           foldRate   = sampleBeta(1, 1)        // uniform prior        │
│    │       else:                                                            │
│    │           sampledCtr = sampleBeta(clicks+1, imps-clicks+1)             │
│    │           foldRate   = sampleBeta(folds+1,  imps-folds+1)              │
│    │       bonus = newcomerBonus(stats.impressions)                         │
│    │       engagement = sampledCtr + FOLD_WEIGHT × foldRate + bonus         │
│    │       score = engagement × c.cpm^alpha                                 │
│    │       (c, score, sampledCtr)                                           │
│    │   }                                                                    │
│    │   sorted = scored.sortBy(-score)                                       │
│    │   winner = sorted.head                                                 │
│    │   bestLoserScore = sorted(1).score (or 0 if no runner-up)              │
│    │                                                                        │
│    └─► return (winner, reason=Exploitation, score, sampledCtr,             │
│                bestLoserScore)   // bestLoserScore feeds clearing price     │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function newcomerBonus(impressions):                                       │
│    if impressions >= NEWCOMER_DECAY_IMPRESSIONS: return 0.0                 │
│    return NEWCOMER_BOOST × (1 - impressions / NEWCOMER_DECAY_IMPRESSIONS)   │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  function sampleBeta(α, β):                                                 │
│    // Gamma trick: Beta(α,β) = X/(X+Y) where X~Gamma(α,1), Y~Gamma(β,1)     │
│    x = sampleGamma(α)                                                       │
│    y = sampleGamma(β)                                                       │
│    return x / (x + y)                                                       │
│                                                                             │
│  function sampleGamma(shape):                                               │
│    // Marsaglia-Tsang method for shape ≥ 1                                  │
│    if shape < 1:                                                            │
│        return sampleGamma(shape + 1) × uniform()^(1/shape)                  │
│    d = shape - 1/3                                                          │
│    c = 1 / sqrt(9d)                                                         │
│    loop:                                                                    │
│        x = gaussian()                                                       │
│        v = (1 + c×x)³                                                       │
│        if v > 0 AND log(uniform()) < 0.5x² + d(1 - v + log(v)):             │
│            return d × v                                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Exploration vs Exploitation

```
                    Impressions
                        │
    0   10              │                    1000+
    │────┼──────────────┼──────────────────────│
    │    │              │                      │
    ▼    ▼              ▼                      ▼

 EXPLORE          WARMUP              EXPLOIT
 (uniform)     (round-robin)      (Thompson)

 High variance   Forced fair       Low variance
 No preference   exploration       Best arm wins
```

### Pricing: Quality-Adjusted Clearing

A winning bid is rarely charged in full. What a winner pays depends on WHY it
was selected.

**Exploitation winners pay a quality-adjusted second price.** The score is
`engagement × CPM^α`, so the clearing price inverts that formula against the
runner-up's score: charge the lowest CPM at which the winner's score would
still beat the runner-up given the winner's *own* sampled CTR.

```
qualityAdjustedClearing(winnerSampledCtr, winnerBid, bestLoserScore, α, siteFloor):

  if winnerSampledCtr > 0 AND bestLoserScore > 0 AND α > 0:
      clearingCpm = (bestLoserScore / winnerSampledCtr) ^ (1/α)
      return clamp(clearingCpm, siteFloor, winnerBid)
  else:
      return siteFloor   // no runner-up, degenerate CTR, or α ≤ 0
```

- `bestLoserScore` is the second-highest score among the slot's eligible
  candidates (0 when only one candidate fits the slot).
- The result is clamped to `[siteFloor, winnerBid]` — a winner never pays
  below the publisher's floor nor more than its own bid.
- The reservation, the `BatchSlotOutcome.clearingPrice`, and the pending-spend
  delta all use this same clearing CPM. **Pending/reserved spend is recorded
  at the clearing price, not the bid** — otherwise pacing would see in-flight
  spend that overstates what the campaign actually owes.

**Dog-ear pin re-encounters clear at zero.** When a slot carries a pin hint
and the pinned creative is in the pool, it bypasses the auction and serves at
`clearingPrice = CPM.zero` (folds are free, reader bookmark). This holds even
when the request was pacing-throttled.

### Pricing: Dynamic Exploration Fraction

For NON-exploitation selections (cold start / warmup / ε-greedy), the system
does not run a second-price auction. Instead it charges a fraction of the bid
that scales with how confident the Beta posterior is — fresh creatives explore
cheaply, well-explored ones pay closer to full price.

```
explorationPriceFraction(impressions, clicks):
  α = clicks + 1
  β = impressions - clicks + 1
  n = α + β
  variance   = (α·β) / (n² · (n+1))          // Beta posterior variance
  confidence = clamp(1 - variance/0.25, 0, 1) // Beta(1,1) has max var 0.25
  return MinExplorationFraction
       + (MaxExplorationFraction - MinExplorationFraction) × confidence

  where MinExplorationFraction = 0.50   // fresh creative pays 50% of bid
        MaxExplorationFraction = 0.95   // confident creative pays 95% of bid
```

- Beta(1,1) (no data) → variance 0.25 → confidence 0 → pays the 50% floor.
- As impressions accumulate, variance shrinks → confidence → 1 → pays up to 95%.

---

## 6. Budget Reservation

### Purpose

Atomically reserve budget before serving to prevent overspend.

### Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        BUDGET RESERVATION FLOW                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│    Selected Winner (from Thompson Sampling)                                 │
│           │                                                                 │
│           ▼                                                                 │
│    ┌─────────────────────────────────────────────────┐                      │
│    │ Add to pendingSpendByCampaign                   │                      │
│    │ (tracks in-flight spend before confirmation)    │                      │
│    └─────────────────────────────────────────────────┘                      │
│           │                                                                 │
│           ▼                                                                 │
│    ┌─────────────────┐     ┌──────────────────────┐                         │
│    │ CampaignEntity  │     │ AdvertiserEntity     │                         │
│    │ .TryReserve()   │     │ .GetBudgetStatus()   │                         │
│    └────────┬────────┘     └──────────┬───────────┘                         │
│             │                         │                                     │
│             └────────────┬────────────┘                                     │
│                          │                                                  │
│                          ▼                                                  │
│              ┌───────────────────────┐                                      │
│              │   Both succeed?       │                                      │
│              └───────────┬───────────┘                                      │
│                          │                                                  │
│           ┌──────────────┴──────────────┐                                   │
│           │ YES                         │ NO                                │
│           ▼                             ▼                                   │
│    ┌─────────────┐              ┌─────────────────────┐                     │
│    │  RESERVED   │              │  Try next candidate │                     │
│    │  (serve ad) │              │  from TS ranking    │                     │
│    └─────────────┘              └─────────────────────┘                     │
│           │                             │                                   │
│           ▼                             ▼                                   │
│    Clear pending spend          If no candidates left:                      │
│    Record actual spend             return NoCandidates                      │
│    Return Selected                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Pending Spend Buffer

```
Purpose: Prevent burst overspend when async spend updates lag

Example scenario without buffer:
  t=0:    todaySpend = $0,     10 concurrent requests arrive
  t=0:    All 10 see spendRatio ≈ 0, all pass pacing gate
  t=100ms: All 10 reserve budget → overspent by 10× CPM!

With pending spend buffer:
  t=0:    Request 1 arrives, adds $0.005 to pendingSpend
  t=0:    Request 2 sees $0.005 pending, adjusts pacing calculation
  t=0:    Request 3 sees $0.010 pending, etc.
  t=100ms: Confirmations come back, pending cleared, actual spend updated

pendingSpendByCampaign[campaignId] += estimatedSpend  // Before reservation
pendingSpendByCampaign[campaignId] -= estimatedSpend  // After confirmation
```

---

## 7. Complete Flow State Machine

### State Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      AD SERVER STATE MACHINE                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│                          ┌───────────────┐                                  │
│                          │     IDLE      │                                  │
│                          │ (waiting for  │                                  │
│                          │   messages)   │                                  │
│                          └───────┬───────┘                                  │
│                                  │                                          │
│        ┌─────────────────────────┼─────────────────────────┐                │
│        │                         │                         │                │
│        ▼                         ▼                         ▼                │
│  ┌──────────┐            ┌──────────────┐          ┌───────────────┐        │
│  │ SELECT   │            │ CANDIDATES   │          │ APPROVE/      │        │
│  │ REQUEST  │            │ COLLECTED    │          │ REJECT        │        │
│  └────┬─────┘            └──────┬───────┘          └───────┬───────┘        │
│       │                         │                          │                │
│       ▼                         ▼                          ▼                │
│  ┌──────────────┐        ┌──────────────┐          ┌───────────────┐        │
│  │Update Rate   │        │Filter by     │          │Update Bloom   │        │
│  │Update Traffic│        │blocklist     │          │filter &       │        │
│  │Shape Tracker │        │              │          │ServeIndex     │        │
│  └──────┬───────┘        └──────┬───────┘          └───────┬───────┘        │
│         │                       │                          │                │
│         ▼                       ▼                          │                │
│  ┌──────────────┐        ┌──────────────┐                  │                │
│  │Query         │        │Partition:    │                  │                │
│  │ServeIndex    │        │approved vs   │                  │                │
│  │(DData Get)   │        │pending       │                  │                │
│  └──────┬───────┘        └──────┬───────┘                  │                │
│         │                       │                          │                │
│         ▼                       ├───────────┐              │                │
│  ┌──────────────┐               ▼           ▼              │                │
│  │SelectView    │        ┌──────────┐ ┌───────────┐        │                │
│  │Loaded        │        │Update    │ │Queue      │        │                │
│  └──────┬───────┘        │ServeIndex│ │Pending    │        │                │
│         │                └──────────┘ └───────────┘        │                │
│         ▼                       │                          │                │
│  ┌──────────────┐               │                          │                │
│  │Filter by     │               │                          │                │
│  │recency       │               │                          │                │
│  └──────┬───────┘               │                          │                │
│         │                       │                          │                │
│         ▼                       │                          │                │
│  ┌──────────────┐               │                          │                │
│  │Pacing Gate   │               │                          │                │
│  │Check         │               │                          │                │
│  └──────┬───────┘               │                          │                │
│         │                       │                          │                │
│    ┌────┴────┐                  │                          │                │
│    │         │                  │                          │                │
│    ▼         ▼                  │                          │                │
│ [SKIP]   [SERVE]                │                          │                │
│    │         │                  │                          │                │
│    │         ▼                  │                          │                │
│    │   ┌──────────────┐         │                          │                │
│    │   │Thompson      │         │                          │                │
│    │   │Sampling      │         │                          │                │
│    │   └──────┬───────┘         │                          │                │
│    │          │                 │                          │                │
│    │          ▼                 │                          │                │
│    │   ┌──────────────┐         │                          │                │
│    │   │Budget        │         │                          │                │
│    │   │Reservation   │         │                          │                │
│    │   └──────┬───────┘         │                          │                │
│    │          │                 │                          │                │
│    │     ┌────┴────┐            │                          │                │
│    │     │         │            │                          │                │
│    │     ▼         ▼            │                          │                │
│    │ [DENIED]  [RESERVED]       │                          │                │
│    │     │         │            │                          │                │
│    │     │         ▼            │                          │                │
│    │     │    Record spend      │                          │                │
│    │     │    Clear pending     │                          │                │
│    │     │         │            │                          │                │
│    └─────┴─────────┴────────────┴──────────────────────────┘                │
│                    │                                                        │
│                    ▼                                                        │
│              ┌───────────────┐                                              │
│              │    RESPOND    │                                              │
│              │   to caller   │                                              │
│              └───────────────┘                                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Message Flow Sequence

```
┌────────────────────────────────────────────────────────────────────────────┐
│                     SELECT REQUEST SEQUENCE                                │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  External        AdServer         ServeIndex       Campaign      Advertiser│
│     │               │                 │               │              │     │
│     │   Select      │                 │               │              │     │
│     │──────────────▶│                 │               │              │     │
│     │               │                 │               │              │     │
│     │               │─── Get ────────▶│               │              │     │
│     │               │                 │               │              │     │
│     │               │◀── ServeView ───│               │              │     │
│     │               │                 │               │              │     │
│     │               │─[Pacing Check]  │               │              │     │
│     │               │  (internal)     │               │              │     │
│     │               │                 │               │              │     │
│     │               │─[Thompson       │               │              │     │
│     │               │  Sampling]      │               │              │     │
│     │               │  (internal)     │               │              │     │
│     │               │                 │               │              │     │
│     │               │─── TryReserve ──────────────────▶│              │     │
│     │               │                 │               │              │     │
│     │               │─── GetBudgetStatus ─────────────────────────────▶    │
│     │               │                 │               │              │     │
│     │               │◀── Reserved ────────────────────│              │     │
│     │               │                 │               │              │     │
│     │               │◀── BudgetStatus ───────────────────────────────│     │
│     │               │                 │               │              │     │
│     │   Selected    │                 │               │              │     │
│     │◀──────────────│                 │               │              │     │
│     │               │                 │               │              │     │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Appendix: Key Formulas Summary

### Rate Tracking
```
r_smoothed = α × r_instant + (1-α) × r_smoothed
where α = 0.3, window = 1000ms
```

### Traffic Shape CDF
```
F(t) = (Σ V[0..b-1] + V[b] × fraction) / Σ V
where b = floor(t / 3600)
```

### Spend Ratio
```
ρ = S_today / (B_daily × F(t))
```

### PI Controller
```
θ = clamp(θ_base - (Kp × e + Ki × I), 0, 1)
where e = 1 - ρ, Kp/Ki doubled when over-pacing
```

### Thompson Sampling Score
```
sampledCtr = Beta(clicks+1, imps-clicks+1).sample()
foldRate   = Beta(folds+1,  imps-folds+1).sample()
engagement = sampledCtr + 2.0 × foldRate + newcomerBonus(imps)
score      = engagement × max(CPM, 0.001)^α        // α = publisher bidWeight
```

### Quality-Adjusted Clearing Price (Exploitation)
```
clearingCpm = clamp((bestLoserScore / winnerSampledCtr)^(1/α), siteFloor, winnerBid)
// reserved/pending spend is recorded at clearingCpm, not the bid
```

### Exploration Price Fraction (Cold/Warmup/ε-greedy)
```
fraction = 0.50 + 0.45 × clamp(1 - var/0.25, 0, 1)   // var = Beta posterior variance
```

---

## Appendix: Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `RateEmaAlpha` | 0.3 | Rate tracking smoothing |
| `RateWindowMs` | 1000 | Rate tracking window |
| `TrafficShapeAlpha` | 0.1 | Traffic learning rate |
| `TrafficShapeBuckets` | 24 | Hourly buckets |
| `PacingKp` | 0.5 | Proportional gain |
| `PacingKi` | 0.3 | Integral gain |
| `OverpaceGainMult` | 2.0 | Asymmetric gain |
| `GracePeriodFrac` | 0.01 | 1% of day |
| `MinGraceSec` | 10.0 | Minimum grace |
| `MaxIntegral` | 1.0 | Anti-windup bound |
| `ExplorationRate` | 0.30 | TS epsilon |
| `WarmupImpressions` | 10 | TS warmup threshold |
| `FoldWeight` | 2.0 | Fold-rate weight in engagement signal |
| `NewcomerBoost` | 0.5 | Additive newcomer bonus at 0 imps |
| `NewcomerDecayImpressions` | 50 | Imps at which newcomer bonus → 0 |
| `MinImpressionShareFactor` | 0.50 | Fair share = 0.50 / numCampaigns |
| `MinExplorationFraction` | 0.50 | Fresh creative pays 50% of bid |
| `MaxExplorationFraction` | 0.95 | Confident creative pays 95% of bid |
| `bidWeight` (α) | 0.5 | Publisher CPM exponent (0.3/0.5/0.7) |
| `StatsWindowSize` | 1000 | Creative stats ring buffer |