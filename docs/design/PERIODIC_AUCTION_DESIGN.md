# Periodic Auction Architecture

## Overview

Promovolve uses a **periodic auction model** instead of realtime bidding (RTB). Auctions run when a page's content is classified (on demand, triggered by the first ad request for an unknown URL) and are re-run periodically and on ecosystem events. Results are cached; serving is a fast cache lookup plus serve-time Thompson Sampling.

## Classification Freshness: the 48-Hour Window

The **classification freshness window** (48h default; `0` disables it) is a
**classification-freshness TTL, not a publish-date gate**. Per-publisher
plumbing exists in `PublisherEntity` (valid range 24h–7d) but no API route
exposes the setter yet, so every publisher currently runs the default.
Nothing extracts or checks when an article was published — the window measures
how long ago the page was last *classified*.

- A page serves ads for 48 hours after its most recent classification
- When the window lapses, the serve response's `reclassifyInMs` token goes
  ≤ 0 and the ad tag re-submits the page text on the next visit — a fresh
  classification opens a fresh window
- So content of **any age keeps serving as long as it has live traffic**;
  what actually expires is pages whose traffic stopped

**Why it exists:**
- **Bounded state**: pages nobody visits fall out of the caches automatically
  (no complex LRU)
- **Drift correction**: periodic re-classification picks up content changes
- **Natural fit for news/media**: a story classifies once on its traffic
  spike, serves through it, and quietly expires when readers move on

**How it works:**
1. Classification less than 48h old → eligible for monetization
2. All candidates' classifications older than 48h → no ads served
   (`BatchContentTooOld` → `204 No Content`) — until the next visit
   re-classifies the page
3. Automatic cleanup removes stale classifications every 5 minutes

This is implemented across three layers:
- **AuctioneerEntity**: Cleans up classifications older than 48h
- **AdServer**: Filters candidates by classification age at serve time and
  issues the `reclassifyInMs` token
- **PeriodicReauction**: Only processes recently-classified pages

## Key Design Decisions

### 1. Auction Timing: Periodic + Event-Driven, Not Realtime

- **Auction**: Runs when a page is first classified, then re-runs on a periodic
  tick (**5 minutes** via the deployed config override; code default 30 minutes)
  and on ecosystem events (campaign changes, approvals, budget exhaustion)
- **Serving**: Fast lookups from the cached candidate pool (ServeIndexDData)
- **Tradeoff**: Minutes of staleness vs the cost and latency of per-impression RTB

### 2. Two-Phase Process

> **Where does Thompson Sampling run?** Not in the auction. The periodic
> auction produces and caches a *pool* of eligible candidates. The
> multi-armed-bandit selection (Thompson Sampling) runs at **serve time**,
> once per impression, when a user actually visits the page. See Phase 2.

#### Phase 1: Auction Time (classification or re-auction)
```
Ad request arrives for a URL with no cached classification
  ↓
POST /v1/classify-page → 202 Accepted (async)
  ↓
SiteEntity classifies the page (single-flight per URL, Gemini + IAB taxonomy)
  → Categories persisted in SiteEntity, replayed to the auctioneer on restart
  ↓
AuctioneerEntity assembles the candidate pool:
  1. Get eligible campaigns from CategoryBidderEntity
  2. Ask each CampaignEntity for creatives
  3. Include pending (not-yet-approved) demand — it bids so it can reach
     the publisher approval queue, but approval gates actual delivery
  4. Order candidates: best creative per campaign first (by CPM),
     then remaining — NO candidate cap is applied
  ↓
Cache ALL eligible candidates in ServeIndexDData
  (Thompson Sampling does NOT run here — it runs at serve time)
```

#### Phase 2: Serve Time (Realtime)
```
User visits page → banner script collects all slots
  ↓
POST /v1/serve/batch  (one request per page load, all slots)
  ↓
AdServer(site).BatchSelect:
  verify host → freshness check → ServeIndex lookup → pacing gate
  → Thompson scoring → greedy slot assignment (one campaign per page)
  ↓
Return creatives with tracking URLs
```

## Architecture Flow

### Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  AUCTION TIME (on classification; re-run every 5 min +      │
│  on ecosystem events)                                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. First ad request for an unknown URL                     │
│     → POST /v1/classify-page (202 Accepted)                 │
│     ↓                                                        │
│  2. SiteEntity.ClassifyUrl (single-flight per URL)          │
│     ↓                                                        │
│  3. Gemini + IAB taxonomy classification                    │
│     → Categories: {Sports: 0.8, News: 0.3}                  │
│     → persisted in SiteEntity; replayed to the auctioneer   │
│       at recovery, at AuctioneerStarted handshake, and on   │
│       a 5-min refresh tick (restart self-healing)           │
│     ↓                                                        │
│  4. AuctioneerEntity.RunAuction(                            │
│       pageUrl, category=Sports, sizes={300x250, 728x90}     │
│     )                                                        │
│     ↓                                                        │
│  5. CategoryBidderEntity[category|shard].GetCampaigns       │
│     → Returns: [Campaign A, B, C]                           │
│     ↓                                                        │
│  6. For each campaign:                                      │
│     CampaignEntity.CampaignBidRequest(...)                  │
│     → Returns: creatives=[Creative 1, Creative 2, ...]      │
│     ↓                                                        │
│  7. Order candidates (no cap):                              │
│     best-per-campaign first by CPM, then the rest           │
│     (NO Thompson Sampling here — see serve time)            │
│     ↓                                                        │
│  8. Cache the FULL candidate pool in ServeIndexDData;       │
│     pre-approved creatives can serve, pending ones are      │
│     queued for publisher approval                           │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  SERVE TIME (Every page load)                               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  User visits page → banner script loads                     │
│     ↓                                                        │
│  POST /v1/serve/batch { pub, url, imp: [slots...], pins }   │
│     ↓                                                        │
│  AdServer(site).BatchSelect:                                │
│    1. Host verified? (else 403)                             │
│    2. Classification fresh enough? (else 204)               │
│    3. pool = ServeIndex lookup (local, instant)             │
│    4. Pacing gate (PI controller) — may skip serving        │
│    5. Thompson Sampling scores each candidate:              │
│       engagement = sampledCTR + 2.0 × sampledFoldRate       │
│                    + newcomerBonus                          │
│       score = engagement × CPM^α   (α = publisher bidWeight)│
│    6. Greedy assignment, largest slot first;                │
│       one creative AND one campaign per page                │
│    7. CampaignEntity ! TryReserve (budget reservation)      │
│       — impression recorded server-side on Reserved         │
│    8. Quality-adjusted second-price clearing                │
│     ↓                                                        │
│  Return JSON (creatives + tracking URLs)                    │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  TRACKING (Async, fire-and-forget)                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Impressions are recorded server-side at selection time     │
│  (no dependency on the pixel firing).                       │
│                                                              │
│  View / click / fold / CTA events:                          │
│  POST /v1/track/... → LearningEventLog                      │
│     ↓                                                        │
│  CreativeStats update (60-min sliding window)               │
│  → feeds serve-time Thompson Sampling posteriors            │
│                                                              │
│  Dog-eared (pin-honored) re-views are counted in separate   │
│  dogeared_* counters and EXCLUDED from learning.            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Re-Auction Triggers

### Why Re-Auction Frequently?

Auction results need refreshing because:

1. **Classification staleness** - A classification older than 48 hours loses monetization eligibility (until traffic re-classifies the page)
2. **Budget depletion** - Winning campaign runs out of money
3. **New campaigns** - Better campaigns start bidding
4. **Creative approvals** - New creatives get approved
5. **Campaign status** - Pause/resume changes

**Note:** Re-auctions only process pages classified within the 48-hour window, making the system more efficient.

### What Is Implemented

1. **Periodic tick** — every site re-evaluates its cached pages on the
   `reauctionInterval` timer (5 minutes deployed, 30 minutes code default).
   Uses cached categories; no re-classification.
2. **Event-driven re-auction** — `CampaignChanged`, `CreativeStatusChanged`,
   and floor changes trigger immediate re-evaluation of affected pages.
   Budget exhaustion (`CampaignBudgetExhausted`/`AdvertiserBudgetExhausted`)
   does NOT remove ServeIndex entries — it refreshes their TTL
   (`dayDurationSeconds × 1.1`) so approval status survives until the
   budget resets.
3. **Boot self-healing** — a fresh auctioneer incarnation starts with an
   empty page cache. `SiteEntity` replays its persisted classifications at
   the `AuctioneerStarted` handshake and again on its 5-minute refresh
   tick; `RestoreClassifications` is idempotent (same-or-newer timestamps
   are skipped) and kicks a re-auction only when something was actually
   restored.

## Key Components

### SiteEntity
- Owns on-demand page classification (single-flight per URL)
- Persists classifications; replays them to the auctioneer after restarts
- Manages site verification and slot configuration

### AuctioneerEntity (one per site)
- Runs the auction at classification time and on re-auction ticks/events
- Orders candidates (best-per-campaign first); applies **no cap**
- Caches the full candidate pool in ServeIndex
  (Thompson Sampling runs later, at serve time)
- Routes pending creatives into the publisher approval queue

### ServeIndexDData
- Replicated cache (DData, LMDB-durable)
- Stores the candidate pool per (site, slot)
- Local reads (no network hop)

### AdServer (one per site)
- Serve-time selection: pacing gate, Thompson scoring, batch slot
  assignment, budget reservation, quality-adjusted clearing
- Enforces host verification and classification freshness

### ServeRoutes
- HTTP layer: `POST /v1/serve/batch`, `POST /v1/classify-page`
- Maps actor replies to status codes (403 unverified, 204 stale content)

## Performance Characteristics

### Serving (Hot Path)
- **Latency**: a few ms (sharded actor ask + local cache read)
- **Scalability**: Horizontal (entities rebalance across nodes)

### Auction (Cold Path)
- **Frequency**: On classification + every 5 minutes per site + events
- **Complexity**: Can use expensive algorithms (LLM classification, fan-out asks)

### Tracking (Async)
- **Impressions**: recorded server-side at selection (no pixel dependency)
- **Views/clicks/folds/CTAs**: fire-and-forget via tracking endpoints
- **Learning**: Updates CreativeStats for serve-time selection

## Creative Approval Flow

```
Advertiser uploads creative
  ↓
CampaignEntity.UpsertCreatives
  (RichCreativeProcessor: render → R2 → Gemini verify)
  ↓
Campaign bids in auctions for sites matching its categories
  — pending demand bids so it can reach the approval queue,
    but is invisible to floor optimization and cannot serve
  ↓
AdServer routes winners:
  - Pre-approved creatives → serve immediately
  - Pending creatives → publisher approval queue (live via SSE)
  ↓
Publisher approves → creative serves on that site
Publisher flags/rejects → bid block for that site (reversible)
```

## Configuration

```scala
final case class Settings(
    topK: Int = 3,
    askTimeout: FiniteDuration = 800.millis,
    priorWeight: Double = 0.5,
    minScore: Double = 0.0,
    enableParentFallback: Boolean = false,
    keepCandidatesPerSlot: Int = 3,    // Legacy field; NOT used as a candidate cap (see below)
    ttl: FiniteDuration = 120.minutes,
    priorHalfLife: FiniteDuration = 1.hour,

    // Re-auction settings (implemented)
    reauctionInterval: FiniteDuration = 30.minutes,  // Code default; deployed override = 5 minutes

    // Classification-freshness settings (IMPLEMENTED)
    classificationFreshnessWindow: FiniteDuration = 48.hours,  // Classification-freshness TTL (not publish date)
    cleanupInterval: FiniteDuration = 5.minutes,      // How often to remove old classifications

    floorCpm: CPM = CPM(0.50),    // Minimum CPM floor price (default $0.50)
    maxPageCacheSize: Int = 10000 // Max URLs cached per publisher (prevents unbounded growth)
)
```

**Candidate Ordering with Campaign Diversity (No Cap):**

There is **no artificial candidate cap** — *all* competitive bids pass through
to serve-time Thompson Sampling. The CPM threshold filter in
`CategoryBidderEntity` (top 20%) is the only natural limit on pool size.
The auction does, however, **order** candidates so that each campaign's best
creative comes first:

1. Deduplicate by `creativeId` (same creative can enter via multiple category paths)
2. Sort by CPM descending (pre-approved creatives win ties)
3. Put the **best candidate per campaign** first, then the remaining creatives

```scala
// AuctioneerEntity: "No artificial candidate cap — all competitive bids
// pass through to serve-time Thompson Sampling."
val deduped = candidates.groupBy(_.creativeId).values.map(_.head).toVector
val sorted  = deduped.sortBy(c => (-c.cpm.value, if (c.preApproved) 0 else 1))
val bestPerCampaign = sorted.groupBy(_.campaignId).values.map(_.head).toVector
// (no .take(keepCandidatesPerSlot) — the whole pool is cached)
```

**Why this matters:** Without campaign-diversity ordering, when multiple
campaigns have the same CPM (common when advertisers use the same bidding
strategy), a single campaign could dominate due to stable sort order. Ordering
best-per-campaign first keeps the pool fair before serve-time selection.

**Cache Size Bounds** (classification-freshness window):
- **Per-site page cache capped at `maxPageCacheSize = 10000` URLs**
  (oldest entry evicted when full — `AuctioneerEntity.Settings`)
- In practice, cache size is also bounded by: `publishRate × classificationFreshnessWindow`
- Example: 100 articles/day × 2 days = 200 entries (~100 KB), well under the cap
- Memory leaks impossible (hard cap + time-based eviction)

**Classification Freshness at Serve Time:**
- The freshness window (48h default) is validated inside `AdServer.BatchSelect`
- Stale content returns `204 No Content` (`BatchContentTooOld`)

## Status

All core phases are implemented:
- On-demand classification (`/v1/classify-page`, single-flight in SiteEntity)
  with persistence and restart replay
- AuctioneerEntity assembles + caches the full candidate pool (no cap)
- ServeIndexDData with TTL sweep, site-scoped removal, and TTL refresh for budget exhaustion
- Periodic re-auction timers, event-driven triggers, and boot self-healing
- Traffic-aware budget pacing (RateAwarePacing with PI control)
- Serve-time Thompson Sampling for creative selection
  (`score = (sampledCTR + 2.0 × sampledFoldRate + newcomerBonus) × CPM^α`,
  α = publisher-configurable bidWeight)

## References

- `/modules/core/src/main/scala/promovolve/advertiser/CampaignEntity.scala` - Returns all eligible creatives
- `/modules/api/src/main/scala/promovolve/api/ServeRoutes.scala` - `POST /v1/serve/batch`, `POST /v1/classify-page`
- `/modules/core/src/main/scala/promovolve/auction/AuctioneerEntity.scala` - candidate pool assembly, `Settings` (no candidate cap; `maxPageCacheSize = 10000`)
- `/modules/core/src/main/scala/promovolve/publisher/delivery/AdServer.scala` - serve-time batch selection, pacing, budget reservation
- `/modules/core/src/main/scala/promovolve/publisher/delivery/ThompsonSampling.scala` - serve-time MAB selection
