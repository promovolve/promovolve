# Auction System Architecture

## Overview

The auction system runs a **multi-candidate ranking auction** for ad placements on publisher pages. Each publisher (
site) has its own `AuctioneerEntity` that orchestrates the auction pipeline, selecting the **top K candidates** (default
K=3) per slot which are then cached for serve-time selection via Thompson Sampling.

**Note:** This is not a second-price auction. Campaigns pay their bid price, and multiple candidates are kept for
MAB-based selection at serve time.

### Why Multi-Candidate Shortlisting?

| Benefit                  | Description                                                                             |
|--------------------------|-----------------------------------------------------------------------------------------|
| **Serve-time learning**  | Thompson Sampling explores which creatives perform best (CTR), not just who bid highest |
| **Graceful degradation** | If candidate #1's budget exhausts, #2/#3 serve without re-auction                       |
| **Low serve latency**    | Pre-computed auction + DData cache = sub-millisecond serve                              |
| **Pacing integration**   | Pacing gate at serve time, no re-auction needed                                         |
| **Publisher alignment**  | TS optimizes for engagement, better user experience                                     |

**Trade-offs:** No price discovery (acceptable for internal campaigns), candidates can go stale (mitigated by
event-driven re-auction).

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

### Phase 1: Page Classification

External system (scraper) classifies a page and sends:

```scala
AuctioneerEntity.PageCategoriesClassified(
  url           = URL("https://publisher.com/article"),
  categoryScores = Map("sports" -> 0.9, "news" -> 0.7, "politics" -> 0.3),
  slots         = List(AdSlotSpec(slotId, declaredSizes, computedSize)),
  ts            = Instant.now()
)
```

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
2. **Fan-out to CategoryBidderEntity** (per category)
3. Each CategoryBidderEntity:
    - Has a list of active campaigns for that category
    - Fans out `CampaignBidRequest` to each campaign
    - Aggregates responses within CPM threshold (top 20%)
4. **Aggregator collects** all category responses

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

### Phase 4: Candidate Shortlisting (CandidatesCollected)

1. **Sort candidates by CPM** descending
2. **Take top K** per slot (default K=3) — multiple candidates are kept, not just one winner
3. **Track participating campaigns** for targeted re-auction
4. **Send to AdServer** for caching and serve-time Thompson Sampling selection

```scala
AdServer.CandidatesCollected(url, slotId, candidates, classifiedAt, ttl)
```

## Entity ID Formats

| Entity               | ID Format                      | Example                   |
|----------------------|--------------------------------|---------------------------|
| AuctioneerEntity     | `{siteId}`                     | `"publisher-123"`         |
| CategoryBidderEntity | `{categoryId}`                 | `"sports"`                |
| TaxonomyRankerEntity | `{category}\|{siteId}`         | `"sports\|publisher-123"` |
| CampaignEntity       | `{advertiserId}\|{campaignId}` | `"adv-1\|camp-1"`         |
| AdvertiserEntity     | `{advertiserId}`               | `"adv-1"`                 |
| AdServer             | `{siteId}`                     | `"publisher-123"`         |

## Ephemeral State (Not Persisted)

AuctioneerEntity maintains three bounded caches:

### 1. lastPage (URL → Classification)

```scala
Map[URL, (categoryScores, slots, classifiedAt)]
```

- **Purpose**: Enable re-auction without re-scraping
- **Eviction**: LRU by `classifiedAt` when full
- **Cleanup**: `CleanupStaleContent` removes entries older than `contentRecencyWindow`
- **Max size**: `maxPageCacheSize` (default 10,000)

### 2. lastQuote (Category → Weight)

```scala
Map[String, (weight, timestamp)]
```

- **Purpose**: Timeout fallback for TaxonomyRanker
- **Eviction**: LRU by timestamp when full
- **Max size**: `maxCategoryQuotes` (default 500)

### 3. participatingCampaigns (Campaign → URLs)

```scala
Map[CampaignId, Set[URL]]
```

- **Purpose**: Targeted re-auction on campaign/budget changes
- **Cleanup**: Campaigns removed when all their URLs expire from `lastPage`

## Event-Driven Re-Auction

The system reacts to external events via pub/sub topics:

### Campaign Events (Targeted)

```
CampaignChanged ──► reauctionForCampaign() ──► only affected URLs
CampaignBudgetExhausted ──► reauctionForCampaign()
CampaignBudgetReset ──► reauctionForCampaign()
```

### Advertiser Events (Full Site)

```
AdvertiserBudgetExhausted ──► PeriodicReauction ──► all recent pages
AdvertiserBudgetReset ──► PeriodicReauction
```

**Why full re-auction for advertiser events?**

- No `AdvertiserId → CampaignId` mapping maintained
- Advertiser events are rare (at most once per day)
- "Full" is scoped to this site only, not cluster-wide
- Filtered to pages within `contentRecencyWindow` (48h)

## Timers

| Timer                 | Interval  | Purpose                     |
|-----------------------|-----------|-----------------------------|
| `PeriodicReauction`   | 4 hours   | Re-auction all recent pages |
| `CleanupStaleContent` | 5 minutes | Remove old classifications  |

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
│  │ Reevaluate ─────┼────┼─►   │           │    │ Campaign()      │   │
│  │                 │    │     ▼           │    │                 │   │
│  └─────────────────┘    │ Candidates      │    └─────────────────┘   │
│                         │ Collected       │                          │
│                         │     │           │                          │
│                         │     ▼           │                          │
│                         │ persistPending()│                          │
│                         │     │           │                          │
│                         │     ▼           │                          │
│                         │ AdServer !      │                          │
│                         └─────────────────┘                          │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## Configuration (Settings)

| Parameter                   | Default   | Description                                                  |
|-----------------------------|-----------|--------------------------------------------------------------|
| `topK`                      | 3         | Categories to consider per page                              |
| `askTimeout`                | 800ms     | Timeout for aggregator requests                              |
| `priorWeight`               | 0.5       | Fallback weight when no cache                                |
| `minScore`                  | 0.0       | Minimum category score threshold                             |
| `keepCandidatesPerSlot`     | 3         | Candidates to keep per ad slot (for serve-time TS selection) |
| `ttl`                       | 120min    | TTL for ad candidates                                        |
| `priorHalfLife`             | 1 hour    | Decay rate for cached weights                                |
| `reauctionInterval`         | 4 hours   | Periodic re-auction interval                                 |
| `contentRecencyWindow`      | 48 hours  | Only monetize recent content                                 |
| `cleanupInterval`           | 5 minutes | Stale content cleanup interval                               |
| `floorCpm`                  | $0.50     | Minimum CPM floor price                                      |
| `maxPageCacheSize`          | 10,000    | Max URLs to cache                                            |
| `maxCategoryQuotes`         | 500       | Max category weights to cache                                |

## Handler Pattern

The entity uses `pipeline orElse public` pattern for clean separation:

```scala
Behaviors.receiveMessage[Messages] { msg =>
  (pipeline orElse public).applyOrElse(msg, _ => Behaviors.same)
}
```

- **`public`**: External commands (`PageCategoriesClassified`, `Reevaluate`)
- **`pipeline`**: Internal state machine (`PageCategoriesRanked`, `CandidatesCollected`, timers, topic events)

## Integration Points

### Inbound

- `PageCategoriesClassified` from scraper/classifier
- `CampaignChanged` from `CampaignDirectory` via topic
- `BudgetEvent` subtypes from `CampaignEntity` via topic

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
| Cache full               | LRU eviction (oldest or least active) |

## Performance Characteristics

- **Latency**: Bounded by `askTimeout` (800ms) × 2 phases
- **Throughput**: Parallel fan-out to categories/campaigns
- **Memory**: Bounded caches prevent unbounded growth
- **Re-auction**: Targeted (campaign) or full (advertiser) based on event type