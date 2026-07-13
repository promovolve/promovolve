# Budget Pacing

Budget pacing spreads ad spend evenly throughout the day instead of exhausting the budget early. This prevents campaigns from going dark in the afternoon after burning through their budget in the morning.

## Overview

Pacing is **always enforced** at the AdServer level using a pluggable `PacingStrategy`. The default strategy is `RateAwarePacing`, which uses PI (Proportional-Integral) control to dynamically adjust throttling based on:

- **Spend ratio**: actual spend vs expected spend (traffic-shaped or linear)
- **Request arrival rate**: observed requests/sec for accurate throttle calculation
- **Traffic shape**: learned or configured hourly traffic patterns

## Architecture

Pacing is modular and configurable:

```
promovolve.publisher.delivery/
  PacingStrategy.scala      -- trait with shouldServe() method + PacingContext
  AdaptivePacing.scala      -- RateAwarePacing factory with PI control
  TrafficShapeTracker.scala -- learns/stores traffic patterns per time bucket

promovolve.publisher.delivery.pacing/
  DayClock.scala            -- real vs simulated day handling (UTC-based)
  TrafficObserver.scala     -- EMA-smoothed request rate tracking
  PacingController.scala    -- coordinates pacing state and day boundaries
```

### Flow Diagram

**IMPORTANT**: Pacing decision (`shouldServe`) happens BEFORE Thompson Sampling selection.
This prevents exploration bias where TS picks an exploration arm that then gets filtered by pacing.

```
  AdServer                          CampaignEntity
     │                                    │
     │  ┌─────────────────────┐           │
     │  │ PacingStrategy      │           │
     │  │ .shouldServe()      │           │
     │  └──────────┬──────────┘           │
     │             │                      │
     │      [if shouldServe=false]        │
     │         → return NoSelection       │
     │         → do NOT run TS            │
     │                                    │
     │      [if shouldServe=true]         │
     │             │                      │
     │  ┌─────────────────┐               │
     │  │ Thompson Sample │               │
     │  │ (pick winner)   │               │
     │  └────────┬────────┘               │
     │           │                        │
     │── TryReserve ─────────────────────▶│
     │◀── Reserved / InsufficientBudget ──│
     │                                    │
     ▼                                    ▼
```

**Key design decisions:**
- Pacing is always enforced (no opt-out)
- `shouldServe()` is called BEFORE Thompson Sampling (critical for correct TS learning)
- Strategies use `PacingContext` for all state (spend, budget, time, traffic shape)
- AdServer orchestrates pacing (not CampaignEntity)
- Easy to swap strategies without code changes

## RateAwarePacing (Default Strategy)

PI-controlled pacing that directly adjusts throttle probability based on spend error.

### How It Works

1. **Base throttle**: Calculate what throttle would achieve perfect pace
   ```
   baseThrottle = 1 - (targetImpsPerSec / requestRate)
   ```

2. **Error calculation**: Positive when under-paced, negative when over-paced
   ```
   error = 1.0 - spendRatio
   ```

3. **PI adjustment**: Directly added/subtracted from throttle
   ```
   adjustment = Kp × error + Ki × integralError
   finalThrottle = baseThrottle - adjustment
   ```

4. **Traffic shape multiplier**: Scale target based on expected traffic volume
   - During peak hours (multiplier > 1): higher target allows more impressions
   - During valley hours (multiplier < 1): lower target prevents overspend

### PI Control Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `Kp` | 0.5 | Proportional gain - immediate response to error |
| `Ki` | 0.3 | Integral gain - accumulated error correction |
| `feedforwardWindow` | 0.0 (disabled) | Proactive adjustment near bucket transitions — off in production |
| `gracePeriodFraction` | 0.01 | Startup period (1% of day) with no PI adjustment |

These are **baseline** gains. At runtime they are modulated by asymmetric over-pacing gains, a self-tuned overpace multiplier, a transition boost, and cross-day learning — see the self-tuning sections below.

### Asymmetric Gains

The controller uses **asymmetric gains** to recover from overspend faster than it accelerates during underspend:

- **Over-pacing** (spendRatio > 1.0): gains multiplied by the overpace multiplier (base 2.0x, self-tuned — see below)
- **Under-pacing** (spendRatio ≤ 1.0): gains unchanged

This is because overspend is costly (budget exhaustion stops delivery), while underspend is recoverable (can catch up later).

The 2.0x is only a **starting point** — the overpace multiplier is self-tuning at runtime and carried across days. See [Self-Tuning PI Gains](#self-tuning-pi-gains) below.

### Self-Tuning PI Gains

The base overpace multiplier (2.0x) is not fixed — `throttleProbability()` records a rolling window of recent (smoothed) spend ratios and periodically adjusts the multiplier based on the campaign's actual pacing behavior. This lets the controller get progressively more aggressive against persistent overspend and relax once it's tracking the target.

**When tuning runs**: every `selfTune()` requires BOTH at least 10 updates since the last run AND at least `MinSelfTuneIntervalMs` (500ms) of wall-clock time. This avoids thrashing at high traffic while staying responsive at low traffic (where the request count is the bottleneck). It also needs at least `SelfTuneWindowSize / 2` (10) samples before it does anything.

**How the overpace multiplier moves** (over the rolling window of `SelfTuneWindowSize` = 20 samples):

- **Persistent overspend** (avg spend ratio > `PersistentOverspendThreshold` = 1.05): multiply current multiplier by `OverspendBoostFactor` = 1.15, capped at `MaxOverpaceGainMultiplier` = 5.0
- **Well-paced** (`WellPacedThreshold` = 1.02 ≥ avg ratio > 0.98): multiply by `WellPacedDecayFactor` = 0.95, floored at `MinOverpaceGainMultiplier` = 1.5

The current multiplier replaces the fixed 2.0x in the asymmetric-gains calculation (`effectiveKp = kp × currentOverpaceMultiplier` when over-pacing).

| Constant | Value | Description |
|----------|-------|-------------|
| `BaseOverpaceGainMultiplier` | 2.0 | Starting overpace multiplier |
| `MinOverpaceGainMultiplier` | 1.5 | Floor when decaying |
| `MaxOverpaceGainMultiplier` | 5.0 | Cap when boosting |
| `SelfTuneWindowSize` | 20 | Rolling spend-ratio history length |
| `PersistentOverspendThreshold` | 1.05 | Avg ratio above this → boost |
| `OverspendBoostFactor` | 1.15 | Per-tune boost when overspending |
| `WellPacedThreshold` | 1.02 | Upper bound of the "well-paced" band |
| `WellPacedDecayFactor` | 0.95 | Per-tune decay when well-paced |
| `MinSelfTuneIntervalMs` | 500 | Minimum ms between tuning runs |

### Oscillation Detection → Smoothing-Alpha Self-Tuning

The raw spend ratio is EMA-smoothed before use to dampen the jitter from bursty `SpendInfo` updates. The smoothing alpha itself is **self-tuned** by `selfTune()` based on the standard deviation of the spend-ratio window:

- **Oscillating** (std dev > `OscillationThreshold` = 0.08): divide alpha by `OscillationSmoothingBoost` = 1.3 (more smoothing → calmer response), floored at `MinSmoothingAlpha` = 0.1
- **Stable** (std dev < `OscillationThreshold / 2` = 0.04): multiply alpha by `StableSmoothingRecoveryFactor` = 1.05 (more responsive), capped at `MaxSmoothingAlpha` = 0.5

The smoothing applied each update is:
```scala
spendRatio = currentSmoothingAlpha × rawSpendRatio + (1 - currentSmoothingAlpha) × prevSmoothed
```

| Constant | Value | Description |
|----------|-------|-------------|
| `SpendRatioSmoothingAlpha` | 0.3 | Initial / reset smoothing alpha |
| `OscillationThreshold` | 0.08 | Std-dev above this → increase smoothing |
| `OscillationSmoothingBoost` | 1.3 | Divisor applied to alpha when oscillating |
| `MinSmoothingAlpha` | 0.1 | Floor (max smoothing) |
| `MaxSmoothingAlpha` | 0.5 | Ceiling (max responsiveness) |
| `StableSmoothingRecoveryFactor` | 1.05 | Multiplier applied to alpha when stable |

### Leaky Integrator

In addition to the anti-windup clamp (integral bounded to [-1.0, 1.0]), the integral term **decays** every update before the new error is accumulated:

```scala
integralError = integralError × IntegralDecayFactor + error × dt   // IntegralDecayFactor = 0.995
integralError = clamp(integralError, -1.0, 1.0)
```

`IntegralDecayFactor` = 0.995 is roughly 0.5% decay per update (half-life ~138 updates). This prevents stale accumulated error from dominating once the campaign returns to pace, complementing the hard anti-windup bounds.

### Transition Boost

When the next traffic bucket is about to change drastically, `Kp`/`Ki` are temporarily boosted so the controller reacts before the transition rather than lagging it. Using `TrafficShapeTracker.upcomingTransition()`:

- Active only in the **last 20%** of the current bucket (`fractionUntil < 0.2`)
- Triggers when the transition severity `|ln(clamp(ratio, 0.1, 10.0))|` exceeds 0.5 (severity ≈ 0.7 for a 2x change, 1.6 for 5x, 2.3 for 10x)
- Boost factor = `1.0 + min(1.0, severity / 2.0)`, i.e. **up to 2x**

The boost multiplies both the (already asymmetric, self-tuned) `effectiveKp` and `effectiveKi`. Outside the transition window, or for mild transitions, the factor is 1.0 (no effect).

### Cross-Day Learning (Early-Exhaustion Boost)

The overpace multiplier persists across day rollovers and is nudged based on whether the prior day exhausted its budget too early. `AdServer` calls `prepareForRollover(budgetExhausted, remainingFraction)` just **before** `reset()`:

```scala
// In RateAwarePacing.prepareForRollover()
if (budgetExhausted && remainingFraction > EarlyExhaustionThreshold) {  // 0.05
  rolloverBoostHint = 1.0 + remainingFraction
}
```

Then `reset()` applies the hint to the persisted multiplier:

```scala
// In RateAwarePacing.reset()
if (rolloverBoostHint > 1.0) {
  currentOverpaceMultiplier = min(MaxOverpaceGainMultiplier, currentOverpaceMultiplier × rolloverBoostHint)
  rolloverBoostHint = 1.0
} else {
  // No early exhaustion: relax the multiplier toward base
  currentOverpaceMultiplier = max(BaseOverpaceGainMultiplier, currentOverpaceMultiplier × WellPacedDecayFactor)
}
currentSmoothingAlpha = SpendRatioSmoothingAlpha   // smoothing alpha resets each day
```

So if a campaign keeps burning its budget with more than `EarlyExhaustionThreshold` (5%) of the day left, the multiplier ratchets up day over day (capped at 5.0) until pacing stabilizes; on a clean day it decays back toward the 2.0 base. The integral error, smoothed spend ratio, spend-ratio history, and tuning timers are all cleared on reset; only the overpace multiplier carries forward.

| Constant | Value | Description |
|----------|-------|-------------|
| `EarlyExhaustionThreshold` | 0.05 | Min remaining day fraction at exhaustion to trigger a boost |

The corresponding `AdServer` rollover path (`AdServer.scala`) computes `budgetExhausted` (total spend ≥ total budget across participating campaigns, including pending spend) and `remainingFraction` before invoking `prepareForRollover` / `reset`.

### Grace Period (Hybrid: Time + Dynamic Request Count + Staleness)

The grace period uses a **hybrid** condition requiring BOTH time AND request count, plus staleness detection. The time threshold scales with day duration, and the request-count threshold is **computed dynamically per-hour** from the observed arrival rate:

```scala
// In AdaptivePacing.throttleProbability()
// Time threshold scales with day duration (min 1s)
val dayScale = ctx.dayDurationSeconds.toDouble / 86400.0
val graceSeconds = max(1.0, MinGraceSeconds * dayScale)   // MinGraceSeconds = 10.0

// Dynamic request count: estimate expected requests during the grace window
// from the observed arrival rate, then clamp to [MinGraceRequests, MaxGraceRequests]
val graceRequests: Long = ctx.trafficShape match {
  case Some(tracker) if currentVolume > 0 && ctx.requestArrivalRate > 0 =>
    val expectedInGrace = (ctx.requestArrivalRate * graceSeconds).toLong
    max(MinGraceRequests, min(MaxGraceRequests, expectedInGrace))   // clamp [10, 50]
  case _ =>
    // No rate/shape data yet — use minimum, scaled for simulated days
    max(5L, (MinGraceRequests * dayScale).toLong)
}

val initialGraceComplete =
  ctx.elapsedSeconds >= graceSeconds &&
  ctx.requestCount >= graceRequests

// Staleness threshold scales with day duration
val scaledStaleThresholdMs = max(
  MinStaleRateThresholdMs,                    // 1000ms minimum
  BaseStaleRateThresholdMs * dayDurationSeconds / 86400
)
val rateIsStale = ctx.msSinceLastRequest > scaledStaleThresholdMs

val inGracePeriod = !initialGraceComplete || rateIsStale
```

The dynamic request count means **low-traffic sites don't wait unnecessarily long** (floor of 10 requests) while **high-traffic sites still gather enough samples** for the EMA rate to stabilize (capped at 50 requests). When no shape/rate data is available yet, it falls back to the minimum (further scaled down for simulated days, never below 5).

**Key constants** (in `AdaptivePacing` object):
```scala
val MinGraceSeconds: Double = 10.0            // Time before PI activates (scaled by day duration)
val MinGraceRequests: Long = 10              // Floor for dynamic request count
val MaxGraceRequests: Long = 50              // Cap for dynamic request count
val EmaStabilizationWindows: Int = 3         // EMA windows needed for rate stabilization
val BaseStaleRateThresholdMs: Long = 30000L   // Base staleness for real days
val MinStaleRateThresholdMs: Long = 1000L     // Minimum staleness threshold
```

**Scaled staleness examples**:
| Day Duration | Scaled Threshold | Effective |
|--------------|------------------|-----------|
| 86400s (real) | 30000ms | 30 seconds |
| 3600s (1 hour) | 1250ms | 1.25 seconds |
| 600s (10 min) | 208ms → 1000ms | 1 second (clamped to min) |

**During grace period**:
- Returns `baseThrottle` (the rate cap), clamped to `MaxThrottleProb` — **serves**, proportional to arrival rate (≈0 when traffic is sparse, capped under a flood)
- Does NOT accumulate integral error (the PI integral warms up on clean data once grace ends)
- The rate cap alone prevents day-start burst overspend; a cold or low-traffic site is no longer forced dark during warmup
- No cold-start cliff: previously grace returned `MaxThrottleProb` (no buying), so a low-traffic or short-simulated-day site could sit at 0.99 throttle indefinitely (it looked like a permanent hard stop)

**Note**: `requestCount` and `msSinceLastRequest` must still be passed through behavior calls so grace completes and the full PI integral engages. If they aren't, the controller stays in grace and paces on `baseThrottle` alone — degraded (no integral correction) but still serving at the rate cap, not silently dark.

**Staleness reset**: After a 30+ second traffic gap (scaled for short days), the rate EMA has decayed, so the PI **integral is reset** to avoid corrections based on stale accumulated error. Serving continues — the gap means the rate data is old, not bad. (This does *not* re-enter a no-buying grace; it only clears stale controller state, then falls through to normal PI control.)

## Traffic Shape Tracking

`TrafficShapeTracker` learns or stores traffic patterns for traffic-aware pacing.

### Problem: Linear Pacing

Linear pacing assumes uniform traffic:
```
Budget: $30/day

Linear Target:
Hour:    0    6    12   18   24
Target:  $0  $7.5  $15  $22.5 $30  (straight line)
```

But real traffic has shape:
```
Traffic Volume:
         ▁▁▂▃▅▆███▇▆▅▄▃▂▁▁
         night  peak  evening
```

This causes over-throttling during peaks and impossible targets during valleys.

### Solution: Traffic-Shaped Targeting

The cumulative distribution function (CDF) of traffic becomes the expected spend curve:
```
Traffic-Shaped Target:
Hour:    0    6    12   18   24
Target:  $1   $5   $18   $26  $30
           ╱      ╱╲
          ╱      ╱  ╲
         ╱──────╱    ╲────────
         └─ matches traffic shape ─┘
```

### Bucket-to-Time Mapping

The `trafficShape` array (24 values) maps to time **relative to `dayStart`**:

| Index | Time from dayStart | Example (dayStart = midnight UTC) |
|-------|-------------------|-----------------------------------|
| 0     | +0h to +1h        | 00:00 - 01:00 UTC                 |
| 6     | +6h to +7h        | 06:00 - 07:00 UTC                 |
| 12    | +12h to +13h      | 12:00 - 13:00 UTC                 |
| 22    | +22h to +23h      | 22:00 - 23:00 UTC (typical peak)  |
| 23    | +23h to +24h      | 23:00 - 00:00 UTC                 |

### Weekday vs Weekend Shapes

Two separate shapes can be configured:
- `weekdayShapeVolumes`: 24 values for Monday-Friday
- `weekendShapeVolumes`: 24 values for Saturday-Sunday

The system automatically selects the appropriate shape based on the current day (UTC).

## DayClock

Handles real vs simulated day timing with **consistent UTC timezone**.

### RealDayClock (dayDurationSeconds = 86400)

- `dayStart` is UTC midnight today
- `elapsedSeconds` = time since midnight UTC
- Traffic shape bucket aligns with UTC wall clock hour

Example at 14:00 UTC:
```
elapsedSeconds = 50400  (14 hours)
bucket = 14
```

### SimulatedDayClock (dayDurationSeconds < 86400)

- `dayStart` is when simulation started (or last day rollover)
- `elapsedSeconds` starts from 0
- Elapsed time is **scaled** to 86400 for traffic shape lookup

Example with `dayDurationSeconds = 600` (10-minute day):
```
After 25 seconds (1/24 of 600):
scaledElapsed = 25 × (86400 / 600) = 3600
bucket = 1
```

### dayDurationSeconds Validation

**`dayDurationSeconds` must not exceed 86400** (24 hours).

- Server rejects values > 86400 with error: `"dayDurationSeconds cannot exceed 86400 (24 hours)"`
- Client (RunScenario) exits with same error

This ensures the traffic shape (24 buckets) maps correctly to time.

## TrafficObserver

Tracks request arrival rate using exponential moving average (EMA).

```scala
// Update on each request
observer.recordRequest(now)

// Get smoothed rate
val reqPerSec = observer.smoothedRate  // e.g., 150.3
```

The smoothed rate is used by `RateAwarePacing` to calculate base throttle:
```
baseThrottle = 1 - (targetRate / observedRate)
```

Without rate tracking, high-traffic scenarios would cause severe throttle oscillation.

## PacingController

Coordinates pacing state across components:

- Tracks `dayStart` for elapsed time calculation
- Detects day boundaries (UTC midnight for real days)
- Manages pacing strategy lifecycle (reset at day boundary)
- Stores/restores traffic shape snapshots

### Day Boundary Detection

```scala
def hasNewDayStarted(newDayStart: Instant): Boolean = {
  val lastDay = LocalDate.ofInstant(lastDayStart, ZoneOffset.UTC)
  val newDay = LocalDate.ofInstant(newDayStart, ZoneOffset.UTC)
  lastDay != newDay
}
```

**All day boundary logic uses UTC** for consistency across server and client.

## UTC Timezone Requirement

**The entire pacing system uses UTC consistently:**

| Component | UTC Usage |
|-----------|-----------|
| `DayClock` | `utcMidnightToday()` for real days |
| `AdServer` | Day boundary detection via `ZoneOffset.UTC` |
| `PacingController` | Day comparison via `ZoneOffset.UTC` |
| `RunScenario` (client) | `LocalTime.now(ZoneOffset.UTC).getHour` for bucket |

This ensures client and server agree on which traffic shape bucket to use.

## PacingContext

Immutable snapshot passed to strategies:

```scala
final case class PacingContext(
    dailyBudget: BigDecimal,
    todaySpend: BigDecimal,
    dayStart: Instant,
    now: Instant,
    requestArrivalRate: Double = 0.0,      // From TrafficObserver
    competingCampaigns: Int = 1,
    avgCpm: Double = 5.0,
    dayDurationSeconds: Int = 86400,       // Must be <= 86400
    trafficShape: Option[TrafficShapeTracker] = None,
    requestCount: Long = 0L,               // Requests seen this day (grace-period exit)
    msSinceLastRequest: Long = 0L          // Staleness signal for low-traffic grace
) {
  def elapsedHours: Double
  def expectedSpendFraction: Double        // Traffic-shaped or linear
  def expectedSpend: BigDecimal
  def spendRatio: Double                   // actual / expected
  def remainingBudget: BigDecimal
  def remainingHours: Double
}
```

## Configuration

### Site Pacing Config

```bash
# Set pacing config for a site
curl -X PUT http://localhost:8080/v1/publishers/pub-1/sites/site-123/pacing \
  -H "Content-Type: application/json" \
  -d '{
    "dayDurationSeconds": 600,
    "warmupMode": false
  }'
```

Traffic shapes are **learn-only** — there is no API field to set them. The tracker
learns the weekday/weekend shapes from observed ad-request arrivals; the learned
shapes are visible (read-only) in `GET /v1/publishers/{pub}/sites/{site}/stats`
as `weekdayShapeVolumes` / `weekendShapeVolumes`, and rendered on the site
observations page.

### Test Throttle Override

For testing, you can force a fixed throttle probability:

```json
{
  "testThrottleOverride": 0.5
}
```

This bypasses PI control and uses `FixedThrottlePacing(0.5)`.

### Changing the Default Strategy

To use a different strategy, modify `AdServer.apply()`:

```scala
AdServer(
  publisherId,
  // ... other params
  pacingStrategy = AdaptivePacing.rateAware(), // or tweak kp/ki/avgCpm via named args
  // or: pacingStrategy = FixedThrottlePacing(0.3),
)
```

## Observing Pacing

### Server-side Stats

```bash
curl http://localhost:8080/test/site-stats/site-123
```

Response:
```json
{
  "siteId": "site-123",
  "selected": 58,
  "pacingSkipped": 42,
  "budgetExhausted": 0,
  "noCandidates": 0,
  "totalSpend": 0.29,
  "elapsedHours": 0.5,
  "expectedSpendFraction": 0.5
}
```

### RunScenario Reports

When running `RunScenario.scala` with `--continuous`, periodic reports include:

```
  ─── Report @ 100 requests (45.2s elapsed) ───

    Requests:         100  (2/sec)
    Selected:          58 (58.0%)
    Pacing skip:       42

    Pacing status:
      Spend ratio:   1.02x → (stable)
      Spend rate:    $0.0064/sec (target: $0.0067/sec)
      Rate status:   ON PACE
```

## Outcome Types

| Outcome | Description |
|---------|-------------|
| `selected` | Ad was served successfully |
| `pacingSkipped` | Rejected by `shouldServe()` to control spend rate |
| `budgetExhausted` | Campaign has no remaining budget |
| `noCandidates` | No eligible ads for this request |

## Impression Tracking

Per-creative impressions are tracked **server-side** in `creativeStats` when `Selected` is returned:

```scala
// In BudgetReserved handler (AdServer.scala)
case CampaignEntity.Reserved(budgetRemaining) =>
  // Record impression immediately - no HTTP call dependency
  val currentCreativeStats = creativeStats.getOrElse(candidate.creativeId, CreativeStats())
  val updatedCreativeStats = creativeStats.updated(candidate.creativeId, currentCreativeStats.recordImpression(now))
  replyTo ! Selected(candidate, requestId)
  behavior(..., updatedCreativeStats, ...)
```

This ensures:
- `serveStats.selected` and `creativeStats.impressions` always match
- No tracking drift from failed HTTP calls
- Thompson Sampling has accurate data

The external `/test/track/impression` endpoint still handles:
- `TaxonomyRankerEntity.RecordImpression` (category-level CTR learning)
- `CampaignEntity.RecordSpend` (budget tracking, idempotent via requestId)

## Testing

Run a simulation with budget constraints to observe pacing:

```bash
# 1. Start the server
sbt "api/run"

# 2. Run pacing test with traffic shapes (10-minute simulated day)
scala-cli scripts/RunScenario.scala -- \
  --scenario scenarios/continuous.json \
  --continuous

# 3. Or run with real-day timing (aligns with UTC wall clock)
# Edit continuous.json: "dayDurationSeconds": 86400
```

The periodic reports will show:
- Spend ratio converging to 1.0x
- Pacing skip rate adjusting to maintain pace
- Traffic shape bucket changes (for short days)

## Custom Strategies

Implement the `PacingStrategy` trait:

```scala
trait PacingStrategy {
  /** Called BEFORE Thompson Sampling - return true to serve, false to skip */
  def shouldServe(ctx: PacingContext): Boolean

  /** Calculate throttle probability [0.0, 1.0] */
  def throttleProbability(ctx: PacingContext): Double

  /** Reset state at day boundary */
  def reset(): Unit

  /** Strategy name for logging */
  def name: String
}
```

Example custom strategy:
```scala
class TimeOfDayPacing extends PacingStrategy {
  def throttleProbability(ctx: PacingContext): Double = {
    val hour = (ctx.elapsedHours % 24).toInt
    if (hour >= 9 && hour <= 17) 0.0  // No throttle during business hours
    else 0.8  // Heavy throttle off-hours
  }
  def name = "time-of-day"
}
```

## Debugging Pacing Issues

### Issue: 0% Pacing Despite Overspend

**Symptoms**: `pacingSkipped = 0` even with 2x-4x overspend ratio

**Root cause**: Usually caused by grace period never completing.

**Check 1: Behavior parameter propagation**

The `behavior()` function in `AdServer.scala` has many parameters with default values:

```scala
def behavior(
  ...
  requestCount: Long = 0L,        // Default: 0
  lastRequestTimeMs: Long = 0L    // Default: 0
)
```

**CRITICAL BUG PATTERN**: If a `behavior()` call doesn't pass these parameters, they silently reset to 0:

```scala
// BAD - missing requestCount and lastRequestTimeMs
behavior(
  cachedDomainBlocklist,
  creativeStats,
  serveStats,
  lastDayStart,
  pacingStrategy,
  smoothedReqRate,
  pendingSpendByCampaign,
  dayDurationSeconds,
  spendInfoCache,
  trafficShapeTracker,
  rolloverGraceUntilMs,
  warmupMode
  // MISSING: requestCount, lastRequestTimeMs → both become 0!
)

// GOOD - all parameters passed
behavior(
  cachedDomainBlocklist,
  creativeStats,
  serveStats,
  lastDayStart,
  pacingStrategy,
  smoothedReqRate,
  pendingSpendByCampaign,
  dayDurationSeconds,
  spendInfoCache,
  trafficShapeTracker,
  rolloverGraceUntilMs,
  warmupMode,
  requestCount,       // Preserve state
  lastRequestTimeMs   // Preserve state
)
```

With `requestCount = 0`, the dynamic grace period condition `requestCount >= graceRequests` (floor 10) is never met, so pacing uses only `baseThrottle` (which is 0 when request rate is low).

**How to find this bug**: Search for all `behavior(` calls in `AdServer.scala` and verify each one passes all 14 parameters.

**Check 2: SpendInfo cache empty**

If `spendInfoCache` is always empty, pacing gate goes through `SpendInfoFetched` path. Check logs for:
```
PACING GATE: Cache empty, fetching spend info from N campaigns
```

If this appears on every request, spend updates aren't being cached.

**Check 3: Stale SpendUpdate filtering**

For simulated days, SpendUpdates are filtered if their `dayStart` is too old:
```scala
val isStale = dayDurationSeconds != 86400 && lastDayStart.exists(currentDayStart =>
  su.dayStart.toEpochMilli < currentDayStart.toEpochMilli - 5000
)
```

Check logs for: `Ignoring stale SpendUpdate`

### Issue: Pacing Too Aggressive

**Symptoms**: Very few impressions served, high pacingSkipped

**Possible causes**:
1. PI gains too high for traffic pattern
2. Traffic shape mismatch
3. Rate estimate too high

### Key Log Messages

Enable debug logging and look for:

```
// Pacing decisions
PACING GATE: Cache empty, fetching spend info from N campaigns
PACING GATE: Request throttled (aggregateThrottle=X%)
PACING GATE: Request passes (aggregateThrottle=X%)

// Day boundary
Day rollover detected, resetting pacing for new day

// Grace period
Grace period ended: fresh SpendUpdate received

// SpendUpdate handling
Ignoring stale SpendUpdate: campaign=X updateDayStart=Y currentDayStart=Z
SpendUpdate received: campaign=X spend=Y budget=Z dayStart=W
```

### AdServer Behavior Parameters Reference

Complete parameter list for `behavior()` (all must be passed):

| # | Parameter | Type | Purpose |
|---|-----------|------|---------|
| 1 | cachedDomainBlocklist | Option[...] | Domain blocklist |
| 2 | creativeStats | Map[...] | Thompson Sampling stats |
| 3 | serveStats | ServeStats | Outcome counters |
| 4 | lastDayStart | Option[Instant] | Current day start |
| 5 | pacingStrategy | PacingStrategy | Throttle algorithm |
| 6 | smoothedReqRate | Double | EMA request rate |
| 7 | pendingSpendByCampaign | Map[...] | In-flight spend buffer |
| 8 | dayDurationSeconds | Int | 86400 or simulated |
| 9 | spendInfoCache | Map[...] | Campaign spend data |
| 10 | trafficShapeTracker | TrafficShapeTracker | Learned patterns |
| 11 | rolloverGraceUntilMs | Long | Grace timeout (currently unused) |
| 12 | warmupMode | Boolean | Learning-only mode |
| 13 | requestCount | Long | **For grace period** |
| 14 | lastRequestTimeMs | Long | **For staleness** |
| 15 | lastCampaignSet | Set[String] | **For mix-change detection** |
| 16 | simulatedDayOfWeek | DayOfWeek | **For weekday/weekend shape** |

### File Quick Reference

| File | Key Functions |
|------|---------------|
| `AdServer.scala` | `behavior()`, `checkPacingGate()`, `processSelectViewLoaded()` |
| `AdaptivePacing.scala` | `throttleProbability()`, `selfTune()`, `prepareForRollover()`, `reset()`, `MinGraceSeconds`, `MinGraceRequests`, `MaxGraceRequests`, `IntegralDecayFactor`, `OverspendBoostFactor` |
| `PacingStrategy.scala` | `PacingContext`, `elapsedSeconds`, `spendRatio` |
| `Protocol.scala` | `SpendInfoFetched`, `PacingGateResult`, `CachedSpendInfo` |

## Version History

| Date | Change |
|------|--------|
| 2026-06 | **Grace period now serves at `baseThrottle` (rate cap) instead of `MaxThrottleProb`**: refusing to serve during grace caused a cold-start cliff — low-traffic and short-simulated-day sites showed no ads until they accumulated enough closely-spaced requests to exit grace, which looked like a permanent hard stop (`throttle=99%, spendRatio=0.00`). The rate cap (`baseThrottle`) already prevents day-start burst overspend, so grace now serves proportional to arrival rate while still withholding the PI integral. Reverses the 2025-01 change below. |
| 2026-05 | **Docs corrected to match code**: `MinGraceRequests` is 10 (not 50); grace request count is now dynamic per-hour (clamped to `MaxGraceRequests` = 50); documented self-tuning overpace multiplier, oscillation-driven smoothing-alpha tuning, leaky integrator decay (`IntegralDecayFactor` = 0.995), transition boost, and cross-day early-exhaustion learning (`prepareForRollover`) |
| 2025-01 | **Campaign diversity fix**: Auction now ensures one candidate per campaign before selecting top K, preventing single campaign from dominating all slots when CPMs are equal |
| 2025-01 | **ServeIndex clearing fix**: Only clear ServeIndex when both approved AND pending candidates are empty; pending candidates are queued for approval without clearing existing entries |
| 2025-01 | **Silent reset during PacingConfigUpdated**: Added `silent` parameter to `AdvertiserEntity.ResetDayStart` to prevent re-auctions during initial pacing setup |
| 2025-01 | **Cross-site interference fix**: Added `participatingAdvertisers` filtering in `AuctioneerEntity` to prevent one site's day rollover from triggering re-auctions on other sites |
| 2025-01 | Grace period now returns MaxThrottleProb (no buying) instead of baseThrottle to prevent day-start overspend |
| 2025-01 | Server-side impression tracking: creativeStats updated in BudgetReserved handler (no HTTP call dependency) |
| 2025-01 | Added `simulatedDayOfWeek` for weekday→weekend transitions in simulated days |
| 2025-01 | Added `lastCampaignSet` for mix-change detection (resets PI when campaigns added/removed) |
| 2025-01 | Fixed `spendRatio` returning 0.0 at day start (now returns 1.0 when both spend and expected are ~0) |
| 2025-01 | Day rollover no longer resets `requestCount` (traffic observation is continuous) |
| 2024-01 | Added hybrid grace period (time + request count + staleness) |
| 2024-01 | Fixed bug: missing `requestCount`/`lastRequestTimeMs` in behavior calls caused grace period to never complete |
| 2024-01 | Added `SpendInfoFetched` path for when cache is empty |
| 2024-01 | Added separate weekday/weekend traffic shapes |
| 2024-01 | Scaled staleness threshold proportionally to day duration (for simulated days) |