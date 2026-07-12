# Auction System Architecture

## Overview

The auction system runs a **multi-candidate ranking auction** for ad placements on publisher pages. Each publisher (
site) has its own `AuctioneerEntity` that orchestrates the auction pipeline. All competitive bids for a slot are
cached for serve-time selection via Thompson Sampling — there is **no artificial candidate cap**.

**Pricing:** This is a **quality-adjusted second-price auction**, settled at serve time (not at candidate-collection
time). Each candidate is scored as

```
score = engagement × CPM^α        where engagement = sampledCTR + FoldWeight × foldRate (+ newcomerBonus)
```

α is the publisher-configurable `bidWeight`. The **winner** is chosen by the Thompson-*sampled* score, but the
**clearing price** is computed from posterior *means* — "sample for allocation, price on means":

```
clearingCpm = (bestLoserScore / winnerMeanEngagement)^(1/α)
```

where `bestLoserScore` is the posterior-mean score of the best runner-up **in the winner's own category**
(cross-category runners-up never set the price). The result is clamped to `[floor, winnerBid]`. When there is no
same-category runner-up (sole eligible candidate, exploration of a cold pool), the winner pays the **floor**.
See `ThompsonSampling.qualityAdjustedClearing` and `AdServer.pickBestForSlot` / `AdServer.batchAssign`.

### Why Multi-Candidate Shortlisting?

| Benefit                  | Description                                                                              |
|--------------------------|------------------------------------------------------------------------------------------|
| **Serve-time learning**  | Thompson Sampling explores which creatives perform best (CTR), not just who bid highest |
| **Graceful degradation** | If candidate #1's budget exhausts, the next candidates serve without re-auction         |
| **Low serve latency**    | Pre-computed auction + DData cache = sub-millisecond serve                              |
| **Pacing integration**   | Pacing gate at serve time, no re-auction needed                                         |
| **Publisher alignment**  | TS optimizes for engagement, better user experience                                     |
| **Price discovery**      | Second-price clearing against the same-category runner-up at serve time                 |

**Trade-offs:** Candidates can go stale (mitigated by event-driven re-auction + TTL), and clearing prices depend on
same-category competition being present in the cached pool — thin pools clear at the floor.

## Entity Hierarchy

```
                    ┌─────────────────────────────────────────┐
                    │           ClusterBootstrap              │
                    │  (initializes topics & entity sharding) │
                    └─────────────────────────────────────────┘
                                        │
            ┌───────────────────────────┼───────────────────────────┐
            ▼                           ▼                           ▼
   ┌─────────────────┐        ┌─────────────────┐         ┌─────────────────┐
   │ AuctioneerEntity │        │ CampaignDirectory│         │ AdvertiserEntity │
   │   (per site)     │        │   (singleton)    │         │ (per advertiser) │
   └────────┬────────┘        └────────┬────────┘         └────────┬────────┘
            │                          │                           │
            ▼                          ▼                           ▼
   ┌─────────────────┐        ┌─────────────────┐         ┌─────────────────┐
   │CategoryBidder   │◄───────│  ActiveCampaigns │         │ CampaignEntity  │
   │Entity (per cat) │        │  Map[CampaignId, │         │(per campaign)   │
   └────────┬────────┘        │   AdvertiserId]  │         └─────────────────┘
            │                 └─────────────────┘                  │
            │                                                      │
            └──────────────── CampaignBidRequest ─────────────────►│
                                                                   │
                              ◄──── CampaignBidResponse ───────────┘
```

## Auction Flow

### Phase 1: Page Classification (On-Demand)

There is no crawler. Classification is on-demand: the ad tag posts the live page's text and slot geometry to
`POST /v1/classify-page` on a cold serve miss; `SiteEntity` single-flights the Gemini taxonomy call, persists the
result, and announces it to the auctioneer:

```scala
AuctioneerEntity.PageCategoriesClassified(
  url           = URL("https://publisher.com/article"),
  categoryScores = Map("sports" -> 0.9, "news" -> 0.7, "politics" -> 0.3),
  slots         = List(AdSlotSpec(slotId, declaredSizes, computedSize)),
  ts            = Instant.now()
)
```

Pages Gemini honestly classifies as matching no demand category arrive as `FillerAuctionRequested` instead and go
straight to the filler pool (campaigns with `bidOnUnmatchedContext = true`).

### Phase 2: Category Ranking (startRanking)

1. **Select top-K categories** by confidence score (default K=3)
2. **Fan-out to TaxonomyRankerEntity** (per category|siteId)
    - Uses Thompson Sampling to weight categories by historical performance
3. **Aggregator collects responses** with timeout (800ms default)
4. **Timeout fallback**: For categories that don't respond:
    - Use cached weight from `lastQuote` with half-life decay
    - Or fall back to `priorWeight` (0.5) if no cache
5. **Combine scores**: `finalScore = classifierConfidence × rankerWeight`

```
                    ┌─────────────────────────────────────┐
                    │         TaxonomyRankerEntity        │
                    │     (category|siteId sharding)      │
                    │                                     │
  Quote(replyTo) ──►│  Thompson Sampling on historical   │──► Quoted(category, weight, ts)
                    │  click/conversion data per site    │
                    └─────────────────────────────────────┘
```

### Phase 3: Bid Collection (PageCategoriesRanked)

For each ad slot on the page:

1. **Filter categories** by `minScore` threshold
2. **Expand each category** to itself ∪ its taxonomy ancestors (so parent-targeting campaigns are reached)
3. **Fan-out to CategoryBidderEntity** (per category|siteId)
4. Each CategoryBidderEntity:
    - Has a list of active campaigns for that category
    - Fans out `CampaignBidRequest` to each campaign
    - Aggregates responses within CPM threshold (top 20%)
5. **Aggregator collects** all category responses

```
CategoryBidRequest                          CampaignBidResponse
     │                                             ▲
     ▼                                             │
┌─────────────────┐                    ┌─────────────────┐
│CategoryBidder   │───CampaignBid───►  │ CampaignEntity  │
│   Entity        │    Request         │                 │
│                 │◄──CampaignBid────  │ Checks:         │
│  activeCampaigns│    Response        │ - Budget        │
│  Map[CampaignId,│                    │ - Site blocks   │
│    AdvertiserId]│                    │ - Creative fit  │
└─────────────────┘                    └─────────────────┘
```

### Phase 4: Candidate Ordering (CandidatesCollected)

1. **Deduplicate by creativeId** (the same creative can enter via multiple category paths)
2. **Campaign-fair ordering**: the best creative per campaign (by CPM, pre-approved first on ties) comes first,
   then the remaining creatives by CPM
3. **No artificial candidate cap** — all competitive bids pass through to serve-time Thompson Sampling. The CPM
   threshold filter in `CategoryBidderEntity` (top 20%) provides the natural limit on candidate pool size.
   (`Settings.keepCandidatesPerSlot` still exists but is a **legacy field with no usages** — it does not cap
   anything.)
4. **Track awarded campaigns** for targeted re-auction
5. **Send to AdServer** for caching (ServeIndex) and serve-time Thompson Sampling selection

```scala
AdServer.CandidatesCollected(url, slotId, candidates, classifiedAt, ttl,
  pageCategories, siteFloor, categoryFloors, slotAdminFloor, authoritativeAbsent)
```

Empty auctions still deliver a zero-candidate `CandidatesCollected` — AdServer treats it as floor-sync only and
leaves the ServeIndex untouched (unless below-floor rejects make a marked campaign's absence authoritative).

## Entity ID Formats

| Entity               | ID Format                      | Example                   |
|----------------------|--------------------------------|---------------------------|
| AuctioneerEntity     | `{siteId}`                     | `"publisher-123"`         |
| CategoryBidderEntity | `{categoryId}\|{siteId}`       | `"sports\|publisher-123"` |
| TaxonomyRankerEntity | `{category}\|{siteId}`         | `"sports\|publisher-123"` |
| CampaignEntity       | `{advertiserId}\|{campaignId}` | `"adv-1\|camp-1"`         |
| AdvertiserEntity     | `{advertiserId}`               | `"adv-1"`                 |
| AdServer             | `{siteId}`                     | `"publisher-123"`         |

## Ephemeral State (Not Persisted)

AuctioneerEntity maintains bounded caches:

### 1. lastPage (URL → Classification)

```scala
Map[URL, (categoryScores, slots, classifiedAt)]
```

- **Purpose**: Enable re-auction without re-classification
- **Eviction**: oldest by `classifiedAt` when full
- **Cleanup**: `CleanupStaleContent` removes entries older than `contentRecencyWindow`
- **Max size**: `maxPageCacheSize` (default 10,000)
- **Restart**: repopulated from SiteEntity's persisted classifications via `RestoreClassifications`

### 2. lastQuote (Category → Weight)

```scala
Map[String, (weight, timestamp)]
```

- **Purpose**: Timeout fallback for TaxonomyRanker
- **Eviction**: oldest by timestamp when full
- **Max size**: `maxCategoryQuotes` (default 500)

### 3. awardedCampaigns (Campaign → URLs)

```scala
Map[CampaignId, Set[URL]]
```

- **Purpose**: Targeted re-auction on campaign/budget changes
- **Cleanup**: Campaigns removed when all their URLs expire from `lastPage`

## Event-Driven Re-Auction

**Event-driven re-auction is the primary fill mechanism.** Floor updates, campaign changes, and budget events all
call `scheduleReauction()`, which coalesces rapid-fire events on a **1-second debounce** into a single re-auction of
recent pages. The periodic timer is only a backstop. The system reacts to external events via pub/sub topics:

### Campaign Events (Targeted)

```
CampaignBudgetExhausted ──► RefreshTTLForCampaign + reauctionForCampaign() ──► only awarded URLs
CampaignBudgetReset     ──► reauctionForCampaign()
CampaignChanged(paused) ──► RemoveCampaign + reauctionForCampaign()
CreativeStatusChanged   ──► reauctionForCampaign() (pause also removes the creative from ServeIndex)
```

`CampaignChanged` for an active campaign triggers scoped **evictions** first (site-narrow when the campaign's
`siteAllowlist` dropped this site; topic-narrow slot-key eviction on a config edit that dropped categories), marks
the campaign authoritative-absent for the next auction rounds, then schedules a debounced full-site re-auction.
Boot/recovery re-registrations re-auction with **no** eviction marks.

### Advertiser Events (Full Site)

```
AdvertiserBudgetExhausted ──► RefreshTTLForAdvertiser + scheduleReauction() ──► all recent pages
AdvertiserBudgetReset     ──► scheduleReauction()
AdvertiserSuspended       ──► RemoveAdvertiser + scheduleReauction()
```

**Why full re-auction for advertiser events?**

- No `AdvertiserId → CampaignId` mapping maintained
- Advertiser events are rare (at most once per day)
- "Full" is scoped to this site only, not cluster-wide
- Filtered to pages within `contentRecencyWindow` (48h)

## Timers

| Timer                 | Interval                                     | Purpose                                            |
|-----------------------|----------------------------------------------|----------------------------------------------------|
| `PeriodicReauction`   | 30 min code default; **5 min** in deployed `application.conf` (`promovolve.auction.reauction-interval`) | **Backstop only** — keeps ServeIndex entries warm under their 120-min TTL and reconciles dropped events. Event-driven re-auction (1s debounce) is primary. |
| `CleanupStaleContent` | 5 minutes                                    | Remove old classifications                         |

## Message Flow Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                         AuctioneerEntity                             │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐   │
│  │     public      │    │    pipeline     │    │     events      │   │
│  │                 │    │                 │    │                 │   │
│  │ PageCategories  │    │ PageCategories  │    │ CampaignChanged │   │
│  │ Classified      │    │ Ranked          │    │ BudgetExhausted │   │
│  │     │           │    │     │           │    │ BudgetReset     │   │
│  │     ▼           │    │     ▼           │    │     │           │   │
│  │ startRanking()──┼────┼─►Aggregator     │    │     ▼           │   │
│  │                 │    │     │           │    │ reauctionFor    │   │
│  │ Reevaluate ─────┼────┼─►   │           │    │ Campaign() /    │   │
│  │                 │    │     ▼           │    │ scheduleReauction│   │
│  └─────────────────┘    │ Candidates      │    └─────────────────┘   │
│                         │ Collected       │                          │
│                         │     │           │                          │
│                         │     ▼           │                          │
│                         │ dedup + order   │                          │
│                         │     │           │                          │
│                         │     ▼           │                          │
│                         │ AdServer !      │                          │
│                         └─────────────────┘                          │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## Configuration (Settings)

| Parameter                   | Default    | Description                                                              |
|-----------------------------|------------|--------------------------------------------------------------------------|
| `topK`                      | 3          | Categories to consider per page                                          |
| `askTimeout`                | 800ms      | Timeout for aggregator requests                                          |
| `priorWeight`               | 0.5        | Fallback weight when no cache                                            |
| `minScore`                  | 0.0        | Minimum category score threshold                                         |
| `keepCandidatesPerSlot`     | 3          | **Legacy, unused** — candidates are NOT capped per slot                  |
| `ttl`                       | 120min     | TTL for ad candidates                                                    |
| `priorHalfLife`             | 1 hour     | Decay rate for cached weights                                            |
| `reauctionInterval`         | 30 minutes | Periodic re-auction **backstop** (deployed config overrides to 5 min via `promovolve.auction.reauction-interval`) |
| `contentRecencyWindow`      | 48 hours   | Only monetize recent content                                             |
| `cleanupInterval`           | 5 minutes  | Stale content cleanup interval                                           |
| `floorCpm`                  | $0.50      | Minimum CPM floor price                                                  |
| `maxPageCacheSize`          | 10,000     | Max URLs to cache                                                        |
| `maxCategoryQuotes`         | 500        | Max category weights to cache                                            |

## Handler Pattern

The entity uses `pipeline orElse public` pattern for clean separation:

```scala
Behaviors.receiveMessage[Messages] { msg =>
  (pipeline orElse public).applyOrElse(msg, _ => Behaviors.same)
}
```

- **`public`**: External commands (`PageCategoriesClassified`, `Reevaluate`, floor updates)
- **`pipeline`**: Internal state machine (`PageCategoriesRanked`, `CandidatesCollected`, timers, topic events)

## Integration Points

### Inbound

- `PageCategoriesClassified` / `FillerAuctionRequested` from `SiteEntity` (on-demand classification via the ad tag)
- `RestoreClassifications` from `SiteEntity` after cluster restart
- `CampaignChanged` from `CampaignDirectory` via topic
- `BudgetEvent` subtypes from `CampaignEntity` via topic
- Floor updates (`UpdateFloorCpm`, `UpdateCategoryFloors`, `UpdateSlotFloors`, `UpdateAdminSlotFloors`) from `SiteEntity`

### Outbound

- `Quote` to `TaxonomyRankerEntity`
- `CategoryBidRequest` to `CategoryBidderEntity`
- `CandidatesCollected` to `AdServer`

## Failure Handling

| Failure                  | Handling                              |
|--------------------------|---------------------------------------|
| TaxonomyRanker timeout   | Use decayed cached weight or prior    |
| CategoryBidder timeout   | Aggregator returns partial results    |
| Campaign doesn't respond | Excluded from auction round           |
| Empty auction            | Retried up to 3× (30s apart), then left to the periodic backstop |
| Lost classification announce | `Reevaluate` miss recovers the persisted copy from SiteEntity (single-flight per URL) |
| Cache full               | Eviction (oldest entry)               |

## Performance Characteristics

- **Latency**: Bounded by `askTimeout` (800ms) × 2 phases
- **Throughput**: Parallel fan-out to categories/campaigns
- **Memory**: Bounded caches prevent unbounded growth
- **Re-auction**: Targeted (campaign) or full-site debounced (advertiser/floor) based on event type
