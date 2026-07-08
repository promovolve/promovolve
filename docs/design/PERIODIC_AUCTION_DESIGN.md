# Periodic Auction Architecture

## Overview

Promovolve uses a **periodic auction model** instead of realtime bidding (RTB). Auctions run at crawl time (periodically), and results are cached. Serving is just a fast cache lookup.

## Promovolve's Key Differentiator: Recency-Only Monetization

Unlike traditional ad networks that serve ads on any content (static ad units), **Promovolve only monetizes fresh content**:

- **Content Recency Window**: Only content published within the last **48 hours** receives ads
- **Why**: Content freshness directly impacts contextual relevance and ad quality
- **Business Model**: Premium positioning ("fresh context only") justifies higher CPMs
- **System Benefits**:
  - Bounded cache size (no memory leaks)
  - Simple cleanup logic (time-based, no complex LRU)
  - Natural alignment with news/media publishers

**How it works:**
1. Content classified within 48 hours → Eligible for monetization
2. Content older than 48 hours → No ads served (returns `204 No Content`)
3. Automatic cleanup removes stale classifications every hour

This recency-only model is implemented across three layers:
- **AuctioneerEntity**: Cleans up classifications older than 48h
- **ServeRoutes**: Validates content age before serving ads
- **PeriodicReauction**: Only processes recent pages

## Key Design Decisions

### 1. Auction Timing: Periodic, Not Realtime

- **Auction**: Runs every 4 hours (configurable) at crawl time
- **Serving**: Microsecond cache lookups from ServeIndexDData
- **Performance**: 100,000+ req/sec vs 10 req/sec for RTB
- **Tradeoff**: Up to 4 hours of staleness vs instant results

### 2. Two-Phase Process

> **Where does Thompson Sampling run?** Not in the auction. The periodic
> auction produces and caches a *pool* of eligible candidates. The
> multi-armed-bandit selection (Thompson Sampling) runs at **serve time**,
> once per impression, when a user actually visits the page. See Phase 2.

#### Phase 1: Crawl Time (Offline/Batch)
```
SiteEntity crawls page
  ↓
TaxonomyRanker classifies content → Categories
  ↓
AuctioneerEntity assembles the candidate pool:
  1. Get eligible campaigns from CategoryBidderEntity
  2. Ask each CampaignEntity for creatives
  3. Filter by publisher approval
  4. Order candidates: best creative per campaign first (by CPM),
     then remaining — NO candidate cap is applied
  ↓
Cache ALL eligible candidates in ServeIndexDData
  (Thompson Sampling does NOT run here — it runs at serve time)
```

#### Phase 2: Serve Time (Realtime)
```
User visits page
  ↓
ServeRoutes.GET /v1/serve?url=...&size=300x250
  ↓
Lookup ServeIndex[pageUrl, size] (local, instant)
  ↓
Return creative with tracking URLs
```

## Architecture Flow

### Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  CRAWL TIME (Every 4 hours)                                 │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. SiteEntity.CrawlPage(url)                               │
│     ↓                                                        │
│  2. Fetch HTML, extract text                                │
│     ↓                                                        │
│  3. TaxonomyRankerEntity.ClassifyContent(text)              │
│     → Categories: {Sports: 0.8, News: 0.3}                  │
│     ↓                                                        │
│  4. AuctioneerEntity.RunAuction(                            │
│       pageUrl, category=Sports, sizes={300x250, 728x90}     │
│     )                                                        │
│     ↓                                                        │
│  5. CategoryBidderEntity[Sports].GetCampaigns               │
│     → Returns: [Campaign A, B, C]                           │
│     ↓                                                        │
│  6. For each campaign:                                      │
│     CampaignEntity.CampaignBidRequest(...)                  │
│     → Returns: creatives=[Creative 1, Creative 2, ...]      │
│     ↓                                                        │
│  7. Filter by approval:                                     │
│     approvedCreatives = all.filter(approved)                │
│     ↓                                                        │
│  8. Order candidates (no cap):                              │
│     best-per-campaign first by CPM, then the rest           │
│     (NO Thompson Sampling here — see serve time)            │
│     ↓                                                        │
│  9. Cache the FULL candidate pool in ServeIndexDData:       │
│      ServeIndex[pageUrl, 300x250] = [candidate, ...]        │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  SERVE TIME (Every impression)                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  User visits page → JavaScript loads                        │
│     ↓                                                        │
│  GET /v1/serve?url=...&size=300x250                         │
│     ↓                                                        │
│  ServeRoutes:                                               │
│    pool = serveIndex.lookup(pageUrl, size) // ← Instant!    │
│     ↓                                                        │
│  Thompson Sampling selects the winner from the pool:        │
│    score = sampledCTR × CPM^α   (α = publisher bidWeight)   │
│    winner = pool.maxBy(score)                               │
│     ↓                                                        │
│  Fetch creative metadata:                                   │
│    meta = creativeMetadataRepo.get(winner.creativeId)       │
│     ↓                                                        │
│  Build response:                                            │
│    {                                                         │
│      imageUrl: cdn.resolve(meta.s3Key),                     │
│      clickUrl: trackingBase/click?creative=...,             │
│      impressionUrl: trackingBase/impression?...             │
│    }                                                         │
│     ↓                                                        │
│  Return JSON (< 1ms)                                        │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  TRACKING (Async, fire-and-forget)                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  User sees ad → Impression pixel fires                      │
│     ↓                                                        │
│  POST /v1/track/impression                                  │
│     ↓                                                        │
│  TrackRoutes → LearningEventLog:                            │
│    1. CampaignEntity ! TryReserve (budget reservation)      │
│    2. AdServer ! RecordImpression (Thompson Sampling stats) │
│     ↓                                                        │
│  Update MAB state (for serve-time selection)                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Re-Auction Strategies

### Why Re-Auction Frequently?

Auction results need refreshing because:

1. **Content staleness (RECENCY-ONLY MODEL)** - Content older than 48 hours loses monetization eligibility
2. **Budget depletion** - Winning campaign runs out of money
3. **New campaigns** - Better campaigns start bidding
4. **MAB learning** - Creative quality scores evolve
5. **Creative approvals** - New creatives get approved
6. **Campaign status** - Pause/resume changes

**Note:** With the recency-only model, re-auctions only process pages within the 48-hour window, making the system more efficient.

### Strategy Options

#### Option A: Scheduled Re-Crawl
```scala
// In SiteEntity
crawlerConfig = CrawlerConfig(
  cronSchedule = "0 */4 * * *" // Every 4 hours
)
```
**Good for:** Stable content, predictable load

#### Option B: Event-Driven Re-Auction
```scala
// Trigger re-auction on ecosystem changes
case BudgetDepleted(campaignId) =>
  affectedPages.foreach { page =>
    auctioneer ! ReAuction(page)
  }

case NewCampaign(campaignId, categories, maxCpm) =>
  relevantPages.foreach { page =>
    auctioneer ! ReAuction(page)
  }
```
**Good for:** Fresh results, minimal waste

#### Option C: Hybrid (Recommended)
```scala
// 1. Full re-crawl: Daily at 2am
//    - Fetch HTML (page content might change)
//    - Re-classify content
//    - Run auction
cronSchedule = "0 0 2 * * ?"

// 2. Re-auction only: Every 4 hours
//    - Use cached categories (from last crawl)
//    - Re-run auction (advertiser ecosystem changes)
timers.startTimerAtFixedRate(ReAuctionOnly, 4.hours)

// 3. Event-driven: Immediate re-auction
//    - Budget depletion (high priority)
//    - New high-value campaigns (CPM > $10)
//    - Creative approvals (medium priority)
```

## Implementation Phases

### Phase 1: Basic Periodic Auction ✓
- [x] CampaignEntity returns all eligible creatives
- [x] Publisher-side filtering and selection
- [x] Creative approval workflow (Topic-based)
- [x] AuctioneerEntity implementation
- [x] ServeIndexDData cache (full candidate pool, no cap)
- [x] MAB optimization (Thompson Sampling) — runs at serve time

### Phase 2: Re-Auction Logic ✓
- [x] Scheduled re-auction timer (PeriodicReauction)
- [x] Page classification caching (lastPage in AuctioneerEntity)
- [x] Re-auction without re-crawl (Reevaluate command)

### Phase 3: Event-Driven Triggers ✓
- [x] Budget depletion triggers (CampaignBudgetExhausted/AdvertiserBudgetExhausted)
- [x] New campaign triggers (CampaignChanged events)
- [x] Creative approval triggers (CreativeStatusChanged events)
- [x] Budget exhaustion preserves ServeIndex (TTL refresh instead of removal)

### Phase 4: Optimizations (Partial)
- [x] Auction result TTL management (TTL sweep + RefreshTTLForKey)
- [x] Traffic-aware budget pacing (RateAwarePacing with PI control)
- [ ] Per-page auction frequency
- [ ] High-traffic page prioritization

## Key Components

### SiteEntity
- Crawls pages periodically
- Caches page classifications (categories)
- Triggers re-auctions on schedule or events

### AuctioneerEntity (per publisher)
- Runs auction during crawl / re-auction
- Filters by approval status
- Orders candidates (best-per-campaign first); applies **no cap**
- Caches the full candidate pool in ServeIndex
  (Thompson Sampling runs later, at serve time)

### ServeIndexDData
- Replicated cache (DData)
- Stores auction winners per (pageUrl, adSize)
- Local reads (no network hop)

### ServeRoutes
- Serves ads via cache lookup
- Generates tracking URLs
- Returns creative metadata

## Performance Characteristics

### Serving (Hot Path)
- **Latency**: < 1ms (local cache read)
- **Throughput**: 100,000+ req/sec per node
- **Scalability**: Horizontal (add more nodes)

### Auction (Cold Path)
- **Frequency**: Every 4 hours per page
- **Duration**: ~100ms per page
- **Complexity**: Can use expensive algorithms (MAB, ML)

### Tracking (Async)
- **Latency**: < 10ms (fire-and-forget via Pekko Pub/Sub)
- **Processing**: Async via TrackingEventConsumer
- **Learning**: Updates MAB state for next auction

## Creative Approval Flow

```
Advertiser uploads creative
  ↓
CampaignEntity.UpsertCreatives
  ↓
Publish to Topic: NewCreativesAvailable(
  campaignId, categories, creatives
)
  ↓
All AuctioneerEntity instances receive event
  ↓
Filter by category overlap:
  - Relevant: Submit for approval
  - Not relevant: Ignore
  ↓
ApprovalManager:
  - Automated checks (content policy, brand safety)
  - Manual review (optional)
  ↓
Approved creatives available in next auction
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
    reauctionInterval: FiniteDuration = 30.minutes,  // Periodic backstop; event-driven re-auctions dominate

    // Recency-only model settings (IMPLEMENTED)
    contentRecencyWindow: FiniteDuration = 48.hours,  // Only monetize content within this window
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

**Cache Size Bounds** (Recency-Only Model):
- **Per-publisher page cache capped at `maxPageCacheSize = 10000` URLs**
  (oldest entry evicted when full — `AuctioneerEntity.Settings`)
- In practice, cache size is also bounded by: `publishRate × contentRecencyWindow`
- Example: 100 articles/day × 2 days = 200 entries (~100 KB), well under the cap
- Memory leaks impossible (hard cap + time-based eviction)

**ServeRoutes Configuration:**
```scala
contentRecencyWindowMs: Long = 48 * 60 * 60 * 1000L // 48 hours
```
- Validates content age before serving
- Returns `204 No Content` for stale content

## Status

All core phases are implemented:
- AuctioneerEntity assembles + caches the full candidate pool (no cap)
- ServeIndexDData with TTL sweep, site-scoped removal, and TTL refresh for budget exhaustion
- Periodic re-auction timers and event-driven triggers
- Traffic-aware budget pacing (RateAwarePacing with PI control)
- Serve-time Thompson Sampling for creative selection
  (`score = sampledCTR × CPM^α`, α = publisher-configurable bidWeight)

## References

- `/modules/core/src/main/scala/promovolve/advertiser/CampaignEntity.scala` - Returns all eligible creatives
- `/modules/api/src/main/scala/promovolve/api/Main.scala` - ServeRoutes setup
- `/modules/core/src/main/scala/promovolve/auction/AuctioneerEntity.scala` - candidate pool assembly, `Settings` (no candidate cap; `maxPageCacheSize = 10000`)
- `/modules/core/src/main/scala/promovolve/publisher/delivery/ThompsonSampling.scala` - serve-time MAB selection, `score = sampledCTR × CPM^α`
