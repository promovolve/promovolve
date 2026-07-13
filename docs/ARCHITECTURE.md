# Promovolve Architecture

Promovolve is an **ad serving platform with pre-computed candidate ranking** built on Apache Pekko with cluster-sharded entities. It orchestrates multi-candidate auctions between publishers (websites) and internal campaigns, using Thompson Sampling for both category ranking and serve-time creative selection, with traffic-shaped budget pacing.

> **Scope:** This document covers the Scala ad-serving core (`modules/core`,
> `modules/api`, `modules/browser`). The Go platform BFF (dashboards under
> `platform/`), the banner ad-tag component, and the GKE deployment
> (`k8s-gke/`) are documented elsewhere.

## Table of Contents

- [System Overview](#system-overview)
- [Component Architecture](#component-architecture)
- [Request Lifecycle](#request-lifecycle)
- [Data Flow Diagrams](#data-flow-diagrams)
- [Key Algorithms](#key-algorithms)
- [Traffic Shape Learning](#traffic-shape-learning)
- [Budget Pacing](#budget-pacing)
- [Persistence & Caching](#persistence--caching)
- [Event-Driven Re-Auction](#event-driven-re-auction)
- [Sharding & Distribution](#sharding--distribution)
- [Configuration Reference](#configuration-reference)
- [Test Scripts](#test-scripts)

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PROMOVOLVE PLATFORM                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐                   │
│   │  PUBLISHER  │     │   AUCTION   │     │ ADVERTISER  │                   │
│   │   DOMAIN    │◄───►│   DOMAIN    │◄───►│   DOMAIN    │                   │
│   └─────────────┘     └─────────────┘     └─────────────┘                   │
│         │                   │                   │                           │
│         │                   ▼                   │                           │
│         │           ┌─────────────┐             │                           │
│         └──────────►│  TAXONOMY   │◄────────────┘                           │
│                     │   DOMAIN    │                                         │
│                     └─────────────┘                                         │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  Infrastructure: Pekko Cluster │ DData │ JDBC Persistence │ Pub/Sub Topics  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Design Principles

| Principle | Implementation |
|-----------|----------------|
| **Actor-based** | Pekko cluster sharding for distribution |
| **Event-driven** | Pub/Sub topics for cross-entity coordination |
| **Probabilistic** | Thompson Sampling, Bloom filters for efficiency |
| **Eventually consistent** | DData for low-latency distributed caching |
| **Durable** | JDBC/PostgreSQL for persistent state |

---

## Component Architecture

### Domain Overview

```
promovolve/
├── advertiser/          # Advertiser accounts, campaigns, creatives
│   ├── AdvertiserEntity
│   ├── CampaignEntity
│   └── CampaignDirectory (Singleton)
│
├── auction/             # Real-time bidding orchestration
│   ├── AuctioneerEntity
│   └── CategoryBidderEntity
│
├── taxonomy/            # Category scoring with Thompson Sampling
│   ├── TaxonomyRankerEntity
│   └── CategoryRegistry (Singleton)
│
├── publisher/           # Publisher sites, ad serving, approval
│   ├── PublisherEntity
│   ├── SiteEntity
│   ├── delivery/        # Ad serving and pacing
│   │   ├── AdServer
│   │   ├── Protocol
│   │   ├── TrafficShapeTracker    # Traffic pattern learning
│   │   ├── PacingStrategy         # Budget pacing algorithms
│   │   └── pacing/
│   │       ├── DayClock           # Time abstraction for testing
│   │       ├── TrafficObserver    # Rate tracking
│   │       └── PacingController   # Pacing state management
│   └── ServeIndexDData
│
└── common/              # Shared utilities
    ├── BloomFilter
    └── CountMinSketch
```

The `promovolve/` package tree above lives in `modules/core`. Two sibling
modules complete the Scala side:

```
modules/
├── core/                # The package tree above (entities, auction, delivery)
├── api/                 # HTTP layer: /serve, /v1/classify-page, dashboards, Main
└── browser/             # Playwright runtime for advertiser LANDING-PAGE analysis
    ├── LPAnalyzer       #   (renamed from the retired content crawler)
    └── BrowserSessionPool
```

`modules/browser` is driven by `LPWorker` shards (in core) that are pinned to
the `crawler` cluster role — see [Cluster Roles](#cluster-roles). Content
crawling itself has been removed; pages are classified on demand from real
traffic (see [SiteEntity](#siteentity)).

---

### Advertiser Domain

#### AdvertiserEntity
**Sharding:** Per `advertiserId`

Manages advertiser accounts including budget, site blocklists, and creative inventory.

```
State:
├── campaignIds: Set[CampaignId]     # Owned campaigns
├── dailyBudget: Budget              # Account-level budget
├── spendToday: Spend                # Aggregated from campaigns
├── siteBlocklist: Set[SiteId]       # Excluded publishers
└── creatives: Map[CreativeId, Creative]
    ├── approvedSites: Set[SiteId]   # Publisher-approved (servable) sites
    └── rejectedSites: BloomFilter   # Publisher-rejected/flagged → bid block
```

**Key Commands:**
- `CreateCampaign` / `RemoveCampaign` - Campaign lifecycle
- `BlockSites` / `UnblockSites` - Publisher exclusions
- `UpdateDailyBudget` - Budget management
- `RecordCampaignSpend` - Spend aggregation from campaigns
- `GetBidContext` - Returns budget + blocklist for bidding
- `UpdateCreativeApproval` / `RevokeCreativeApproval` - Per-site approval mirror

**Per-site approval lifecycle:**

Approvals are **per creative × site**. The SERVE gate lives in the AdServer /
ServeIndex; `approvedSites` mirrors it here so the BID path can tell approved
demand from pending demand (floor optimization reads approved demand only).

- **Pending creatives DO bid** — winning an auction is how a creative reaches
  the publisher's approval queue in the first place.
- **Reject / flag → bid block** on that site (`rejectedSites` Bloom filter);
  the creative stops bidding there entirely.
- **Revoke = soft undo → back to PENDING.** Clears `approvedSites` (the bid
  stops teaching floors) but deliberately does **not** block bidding — winning
  another auction is the creative's only way back into the approval queue.
- **Explicit campaign pause/delete revokes its approvals** on each site; on
  resume every creative starts over from PENDING. (Only the explicit
  advertiser action revokes — auctioneer-originated deactivation churn during
  deploys keeps approvals.)
- **Creative pause keeps approvals** (deliberate) — un-pausing resumes serving
  without re-review.

---

#### CampaignEntity
**Sharding:** Per `advertiserId|campaignId`

Individual campaign management with spend tracking and bidding logic.

```
State:
├── Persistent:
│   ├── status: Active/Paused/Stopped
│   ├── categories: Set[CategoryId]    # Target categories
│   ├── maxCpm: CPM                    # Maximum bid
│   ├── dailyBudget: Budget
│   ├── spendToday: Spend              # Persisted on flush
│   └── creativeAssignments: Map[CreativeId, Set[AdSize]]
│
└── Ephemeral:
    ├── bufferedSpend: Spend           # In-memory buffer
    ├── idempotencyFilter: BloomFilter # Prevent duplicate spend
    └── pendingReports: Queue          # At-least-once to advertiser
```

**Key Features:**
- **Probabilistic Throttling:** Gradually reduces bid probability as budget depletes
- **Idempotency:** Bloom filter + cache prevents duplicate spend recording
- **Buffered Persistence:** Spend flushed every 10s (reduces DB writes)

---

#### CampaignDirectory (Singleton)
**Type:** Cluster Singleton

Central registry mapping categories to active campaigns.

```
State:
├── categoryIndex: Map[CategoryId → Map[CampaignId → AdvertiserId]]
└── campaignIndex: Map[CampaignId → Set[CategoryId]]  # Ephemeral reverse index
```

**Responsibilities:**
- Receives `CampaignReady` / `CampaignGone` from campaigns
- Publishes incremental updates to `CategoryBidderEntity`
- Periodic reconciliation (60s) ensures consistency

---

### Auction Domain

#### Design Rationale: Multi-Candidate Shortlisting

The auction system uses **multi-candidate shortlisting** rather than single-winner selection. This design has significant advantages for an internal ad platform:

| Benefit | How It Works |
|---------|--------------|
| **Serve-time learning** | Keeping all competitive candidates lets Thompson Sampling explore which creatives actually perform (CTR), not just who bid highest |
| **Graceful degradation** | If candidate #1's budget exhausts between auction and serve, #2/#3 are ready without re-auction |
| **Low serve latency** | Auction is pre-computed and cached. Serve is DData lookup + TS sample (sub-millisecond) |
| **Pacing integration** | Pacing gate applied at serve time without re-running expensive auction pipeline |
| **Publisher alignment** | TS optimizes for clicks/engagement, benefiting publishers with better user experience |

**Trade-offs accepted:**
- Candidates can go stale (mitigated by event-driven re-auction on budget/config changes)

#### Pricing: Quality-Adjusted Second Price

Despite the pre-computed shortlist, serve-time clearing **is** a real
quality-adjusted second-price mechanism
(`ThompsonSampling.qualityAdjustedClearing`, invoked from the AdServer's
`batchAssign` / `pickBestForSlot` paths):

```
score       = engagement × CPM^α          (α = publisher bidWeight)
clearingCpm = (bestLoserScore / winnerMeanEngagement)^(1/α)
```

The winner pays the minimum CPM at which its score still beats the best
runner-up **in its own category** (cross-category runners-up never set the
price), clamped to `[floor, winner's bid]`.

**Sample for allocation, price on means:** allocation uses Thompson-*sampled*
scores (exploration), but both pricing inputs come from the posterior-**mean**
side of the candidate score — the winner's mean engagement (ctr +
fold-rate weight + newcomer bonus) and the runner-up's mean score. This keeps
the price deterministic and reproducible from observable state: the same
auction state always clears at the same price, and the winner's fold posterior
and newcomer bonus discount its price exactly as the runner-up's raise it.

When there is no same-category runner-up (sole eligible candidate), the
winner's engagement is degenerate, or α ≤ 0, the winner pays the **floor**.

#### Sequence Diagram: Auction → Serve Flow

```
┌─────────┐ ┌───────────┐ ┌──────────────┐ ┌──────────────┐    ┌──────────┐ ┌─────────┐ ┌───────────┐
│ Ad Tag  │ │Auctioneer │ │TaxonomyRanker│ │CategoryBidder│    │ Campaign │ │AdServer │ │ServeIndex │
└────┬────┘ └─────┬─────┘ └──────┬───────┘ └──────┬───────┘    └────┬─────┘ └────┬────┘ └─────┬─────┘
     │            │              │                │                 │            │            │
═════════════════════════════════════════════════════════════════════════════════════════════════
     │  AUCTION PHASE (pre-computed, ~1.6s)                         │            │            │
═════════════════════════════════════════════════════════════════════════════════════════════════
     │            │              │                │                 │            │            │
     │ PageCategoriesClassified  │                │                 │            │            │
     │───────────►│              │                │                 │            │            │
     │            │              │                │                 │            │            │
     │            │  ┌───────────────────────────────────────┐      │            │            │
     │            │  │ Phase 2: Category Ranking             │      │            │            │
     │            │  └───────────────────────────────────────┘      │            │            │
     │            │ Quote        │                │                 │            │            │
     │            │─────────────►│                │                 │            │            │
     │            │              │ Thompson       │                 │            │            │
     │            │              │ Sampling       │                 │            │            │
     │            │◄─────────────│                │                 │            │            │
     │            │ Quoted(weight)                │                 │            │            │
     │            │              │                │                 │            │            │
     │            │  ┌───────────────────────────────────────┐      │            │            │
     │            │  │ Phase 3: Bid Collection               │      │            │            │
     │            │  └───────────────────────────────────────┘      │            │            │
     │            │ CategoryBidRequest            │                 │            │            │
     │            │───────────────────────────►   │                 │            │            │
     │            │              │                │ CampaignBid     │            │            │
     │            │              │                │────────────────►│            │            │
     │            │              │                │                 │            │            │
     │            │              │                │◄────────────────│            │            │
     │            │              │                │  BidResponse    │            │            │
     │            │◄───────────────────────────   │                 │            │            │
     │            │ CategoryBidResponse           │                 │            │            │
     │            │  (multiple candidates)        │                 │            │            │
     │            │              │                │                 │            │            │
     │            │  ┌───────────────────────────────────────┐      │            │            │
     │            │  │ Phase 4: Shortlist & Cache            │      │            │            │
     │            │  └───────────────────────────────────────┘      │            │            │
     │            │ CandidatesCollected (all competitive bids)      │            │            │
     │            │─────────────────────────────────────────────────────────────►│            │
     │            │              │              │                   │            │   Put(     │
     │            │              │              │                   │            │ candidates)│
     │            │              │              │                   │            │───────────►│
     │            │              │              │                   │            │            │
═════════════════════════════════════════════════════════════════════════════════════════════════
     │  SERVE PHASE (real-time, <1ms)                               │            │            │
═════════════════════════════════════════════════════════════════════════════════════════════════
     │            │              │              │                   │     Select │            │
     │            │              │              │                   │ ──────────►│            │
     │            │              │              │                   │            │  Get(key)  │
     │            │              │              │                   │            │───────────►│
     │            │              │              │                   │            │◄───────────│
     │            │              │              │                   │            │[candidates]│
     │            │              │              │                   │            │            │
     │            │              │              │   ┌─────────────────────┐      │            │
     │            │              │              │   │ 1. Pacing Gate      │      │            │
     │            │              │              │   │    shouldServe()?   │      │            │
     │            │              │              │   │                     │      │            │
     │            │              │              │   │ 2. Thompson Sampling│      │            │
     │            │              │              │   │    pick winner from │      │            │
     │            │              │              │   │    the shortlist    │      │            │
     │            │              │              │   │                     │      │            │
     │            │              │              │   │ 3. Reserve Budget   │      │            │
     │            │              │              │   └─────────────────────┘      │            │
     │            │              │              │              │                 │            │
     │            │              │              │              │  Selected       │            │
     │            │              │              │              │ ◄───────────────│            │
     │            │              │              │              │ (1 winner)      │            │
```

**The "Ad Tag" column:** there is no scraper/crawler. On a cold serve miss the
ad tag extracts the live page's text + slot geometry and POSTs it to
`POST /v1/classify-page` (fast `202 Accepted`); `SiteEntity` single-flights the
LLM call and delivers `PageCategoriesClassified` to the auctioneer. See
[SiteEntity](#siteentity).

**Key insight:** The expensive auction runs once (ahead of time). Serve is just cache lookup + TS sample.

**Two uses of Thompson Sampling:**
1. **Category ranking** (TaxonomyRanker) — weights categories by historical performance per site
2. **Creative selection** (AdServer) — picks winner from shortlist based on per-creative CTR

#### AuctioneerEntity
**Sharding:** Per `siteId` (one per publisher)

Orchestrates multi-candidate ranking auctions for a site, producing a shortlist for serve-time selection.

```
Ephemeral Caches:
├── lastPage: Map[URL → (categoryScores, slots, ts)]     # Max 10k URLs
├── lastQuote: Map[Category → (weight, ts)]              # Max 500
└── participatingCampaigns: Map[Campaign → Set[URL]]     # Max 1k
```

**Four-Phase Auction:**

```
Phase 1: CLASSIFICATION
    PageCategoriesClassified(url, scores, slots)
    └── Cache classification for re-auction

Phase 2: CATEGORY RANKING (Thompson Sampling)
    ├── Select top-K categories by classifier confidence
    ├── Fan-out Quote to TaxonomyRankerEntity[category|siteId]
    ├── Aggregate with timeout
    └── Final score = classifierConfidence × sampledWeight

Phase 3: BID COLLECTION
    ├── For each slot × eligible category:
    │   └── CategoryBidderEntity ! CategoryBidRequest
    ├── Aggregate with timeout
    ├── Filter by CPM threshold (top 20%, in CategoryBidderEntity)
    └── Keep ALL competitive bids — no artificial candidate cap;
        dedupe by creativeId, order best-per-campaign first

Phase 4: SHORTLIST CACHING
    ├── Send all competitive candidates to AdServer
    ├── Cache in ServeIndexDData for fast lookup
    └── Track participating campaigns for re-auction
```

There is **no top-K truncation of candidates**: every bid surviving the
CategoryBidder's 20%-of-winner filter passes through to serve-time Thompson
Sampling (that CPM threshold is the natural limit on pool size). The
`keep-candidates-per-slot` setting still exists but is legacy — declared,
never used to truncate.

**Filler Auction (no-category-match path):**

When the classifier returns *no* matching demand categories for a page (an
honest "nothing fits"), the standard category fan-out has nothing to fan out
to. Rather than leaving the slot empty, the auctioneer fires a
`FillerAuctionRequested` to itself, which bypasses category ranking entirely:

```
FillerAuctionRequested(url, slots, ts)
    ├── Cache url in lastPage with EMPTY categoryScores
    │   (this empty-map marker is how Reevaluate / PeriodicReauction
    │    later tell a filler page apart from a classified one)
    ├── Ask CampaignDirectory for campaigns opted into the filler pool
    │   (those with bidOnUnmatchedContext = true)
    ├── Fan out CampaignBidRequest with CategoryId.Filler directly to each
    │   ├── opted-in campaign  → accepts sentinel, replies with a bid
    │   └── all other campaigns → reject with CategoryMismatch
    └── Survivors flow into the usual CandidatesCollected path
        (preApproved = false — publisher still approves per creative)
```

This makes advertiser-side targeting *additive*: a campaign only competes for
unmatched inventory if it explicitly opts in. Toggling
`bidOnUnmatchedContext` on a campaign republishes it, and the re-auction path
revisits filler URLs (detected via the empty-categoryScores marker) so the
flag propagates to pages classified before the advertiser opted in.

---

#### CategoryBidderEntity
**Sharding:** Per `categoryId`

Routes bid requests to all campaigns targeting a category.

```
State:
└── campaigns: Map[CampaignId → AdvertiserId]  # Updated by CampaignDirectory
```

**Bid Flow:**
1. Receive `CategoryBidRequest` from AuctioneerEntity
2. Fan-out to all active campaigns
3. Collect responses
4. Filter: Keep bids within 20% of winner (`cpm ≥ maxCPM × 0.80`)
   If the highest bid is $10 CPM, it keeps all bids ≥ $8 CPM (within 20% of winner). 
   This ensures close competitors stay in the shortlist rather than being eliminated by tiny CPM differences.
5. Return top candidates

---

### Taxonomy Domain

#### TaxonomyRankerEntity
**Sharding:** Per `category|siteId`

Thompson Sampling-based category scoring with learning.

```
State:
└── stats: Stats
    ├── opportunities: Double    # Total auction participations
    ├── wins: Double            # Auctions won
    ├── clicks: Double          # Clicks on served ads
    ├── revenue: Double         # Total revenue earned
    └── updatedMs: Long         # Last update timestamp
```

**Thompson Sampling Algorithm:**
```scala
// Sample from Beta distribution
alpha = wins + priorAlpha
beta = (opportunities - wins) + priorBeta
winRateSample = BetaDistribution(alpha, beta).sample()

// Final score
score = normalizedRevenue × winRateSample × (1 + ctrBonus × ctrAdjustment)
```

**Properties:**
- High variance → exploration (few opportunities)
- Low variance → exploitation (many opportunities)
- Exponential decay with 7-day half-life

See [TAXONOMY_RANKING.md](../modules/core/src/main/scala/promovolve/taxonomy/TAXONOMY_RANKING.md) for details.

---

#### CategoryRegistry (Singleton)
**Type:** Cluster Singleton

Maps categories to publishers/sites for inventory discovery.

```
State:
├── categories: LWWMap[CategoryId → SiteSet]  # DData replicated
└── derivedState: DerivedState                # Precomputed indices
```

---

### Publisher Domain

#### PublisherEntity
**Sharding:** Per `publisherId`

Publisher account management.

```
State:
├── siteIds: Set[SiteId]              # Owned sites
├── domainBlocklist: Set[String]      # Blocked landing page domains
└── status: Active/Suspended/Closed
```

**DData Integration:**
- Domain blocklist replicated via DData
- AdServer subscribes for ad filtering

---

#### SiteEntity
**Sharding:** Per `siteId`

Individual site configuration, on-demand page classification, and floor
optimization (see [Floor CPM Optimizer](#floor-cpm-optimizer)).

```
State:
├── publisherId: PublisherId
├── config: SiteConfig
│   ├── domain
│   ├── taxonomyIds: Set[String]      # Target IAB categories
│   ├── slots: List[AdSlotConfig]     # SlotPrior + admin floorOverride
│   └── seedUrl, cronSchedule, maxDepth, concurrency   # INERT — crawler vestiges
├── pageClassifications: Map[URL → ClassificationEntry]  # Persisted, capped
└── floorCpm / floorCpmByCategory / sweep snapshots
```

**On-demand classification (crawl-free):**

Site content crawling has been **removed** — nothing schedules crawls anymore
(the `seedUrl` / `cronSchedule` / `maxDepth` fields in `SiteConfig` are inert
vestiges kept for serialization compatibility). Pages are classified from real
traffic instead:

1. On a **cold serve miss**, the ad tag extracts the live page's text and slot
   geometry and POSTs them to `POST /v1/classify-page`. The route replies with
   a fast `202 Accepted` and never blocks on the LLM (`ServeRoutes`).
2. `SiteEntity.ClassifyUrl` **single-flights** per normalized URL — a traffic
   burst on a new page fires exactly ONE LLM call — and only accepts once the
   classifier and demand categories are ready.
3. The slot signals measured by the tag in the visitor's real viewport
   (above-fold, viewability, page region, text density) are folded into a
   `SlotPrior` — the crawl-free replacement for the crawler's slot prior,
   feeding the per-slot effective floor.
4. The LLM maps page text to **IAB Content Taxonomy 3.0** categories
   (`TieredCategory` loads `3_0.tsv`; legacy IAB 1.0 / 2.x ids are normalized
   forward). The result is persisted in `pageClassifications` and announced
   to `AuctioneerEntity` as `PageCategoriesClassified`.

**Restart resilience:**

- **Boot backfill:** on recovery, SiteEntity replays its persisted
  `pageClassifications` to the AuctioneerEntity
  (`RestoreClassifications`), repopulating the auctioneer's `lastPage`
  cache and the AdServer's freshness tokens, then kicks an immediate
  re-auction — no re-crawl, no post-restart serve drought.
- **Reevaluate-miss recovery:** when AuctioneerEntity receives a
  `Reevaluate` for a URL missing from `lastPage` (e.g. the classification
  announce was lost while the shard rehomed during a rollout), it asks
  `SiteEntity.GetPageClassification`, merges the persisted entry back, and
  re-dispatches — single-flighted per URL so retries don't stampede.

**LLM Classification Provider (configurable):**

Page-to-IAB-category classification (`IABTaxonomy`) runs through a pluggable
`Provider` selector — the LLM backend is *not* hardcoded. `ClusterBootstrap`
picks the provider from config in priority order, falling back to the next if
no API key is set:

| Provider | Default model | Config key | Notes |
|----------|---------------|-----------|-------|
| **Gemini** (current default) | `gemini-2.5-flash` | `promovolve.gemini.api-key` | Cheapest; rate-limited via the `GeminiRateLimiter` singleton |
| OpenAI | `gpt-4o-mini` | `promovolve.openai.api-key` | Structured-output JSON schema |
| Anthropic | `claude-3-haiku-20240307` | `promovolve.anthropic.api-key` | `tool_use` for structured output |

If none of `GEMINI_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` is set,
startup fails fast. Only the Gemini path acquires a token from the
`GeminiRateLimiter` before each call; the other providers pass through.

---

#### AdServer
**Sharding:** Per `siteId`

Creative approval workflow, serve-time selection, and budget pacing.

```
Components:
├── CreativeStore                 # Pending approval queue
├── ServeIndexDData subscription  # Approved ad cache
├── TrafficShapeTracker           # Traffic pattern learning
├── PacingStrategy                # Budget pacing (AdaptivePacing)
├── spendInfoCache                # Cached campaign spend from DData
└── Domain blocklist subscription # Blocked landing pages
```

**Key Features:**
- **Warmup Mode:** Record traffic patterns without serving ads (for shape learning)
- **Traffic-Shaped Pacing:** Learn weekday/weekend patterns for accurate budget distribution
- **Thompson Sampling:** Per-creative MAB selection for CTR optimization
- **Frequency Capping:** Per-user impression limits (via AdvertiserEntity)
- **Per-page dedup:** PageWinners tracking prevents one advertiser (or one
  creative) from carpeting every slot on a single page

**Per-Page Dedup (PageWinners):**

To stop a single advertiser from filling every slot on a page — and to stop
the *same* creative appearing twice — the AdServer tracks recent winners per
normalized page URL in a `PageWinners` DData map (`adserver-page-winners`,
keyed `siteId|url` with query/fragment stripped). Each entry records the
winning `campaignId`s and `creativeId`s with a `firstSeenMs` timestamp; a
`SweepPageWinners` timer (every 5s) and a default 15s TTL
(`promovolve.adserver.page-winners-ttl`) evict stale entries so reloads get a
fresh auction.

Dedup is intentionally graded — never blank a slot just to avoid a repeat:

- **Same creativeId → hard exclusion.** A creative already placed on the page
  (or earlier in a batch serve) is removed from the candidate pool entirely —
  responsive creatives expose one `creativeId` at many sizes, so this avoids a
  visual duplicate.
- **Same campaign within the same render → hard exclusion.** Campaigns already
  assigned earlier in a batch are excluded for competitive separation within a
  single page load.
- **Recent repeat campaign (cross-reload) → soft preference / penalty.** A
  campaign that won this URL on a *recent reload* (the `pageBlocked` set within
  the 15s TTL) is de-prioritized but not blanked. The batch greedy path tries
  to fill each slot excluding recent winners first, then falls back to allowing
  a repeat if that's the only eligible bid (a sole advertiser repeats rather
  than leaving an empty slot). The shared scoring path expresses the same
  preference as a `PageCapPenalty` (≈ 0.3) score multiplier via
  `ThompsonSampling.scoreCandidate`'s `penaltyFor` — the candidate still
  competes but only wins when clearly stronger than non-repeat alternatives.

**Dog-Ear Pins (cost-per-fold):**

A reader can "dog-ear" a creative — a save-for-later bookmark. When a slot
carries a `pin` hint (`Option[CreativeId]` on the batch slot spec), the
AdServer honors it: if the pinned creative is still in the candidate pool it
serves at `clearingPrice = zero` (a fold is free; CPM spend is recorded only
on the fold event itself), and pins bypass both the floor and the pacing gate
since they're an explicit user gesture. A slot's `DogearOutcome` reports
whether the pin was honored or fell through (e.g. `creative_removed` when the
pinned creative is no longer eligible, which signals the client to drop its
IDB pin row). Fold gestures are authenticated by `FoldToken` — a stateless
HMAC-signed credential minted at serve time and verified at
`/v1/dogear-event`, with a 30-minute freshness window and one-shot redemption
enforced by the billing idempotency layer.

**Approval Workflow:**
```
CandidatesCollected
    │
    ├── Pre-approved? ──Yes──► ServeIndexDData (cache)
    │
    └── No ──► CreativeStore (pending queue)
                    │
                    ├── Publisher Approve ──► ServeIndexDData
                    └── Publisher Reject ──► Promote next candidate
```

Approvals are per creative × site and mirrored to `AdvertiserEntity` so the
bid path can distinguish approved from pending demand. Reject/flag blocks the
creative's bids on this site; **revoke** is a soft undo back to PENDING (the
creative keeps bidding — winning again is its way back into the queue); an
explicit campaign pause/delete revokes the campaign's approvals (resume starts
from PENDING), while a creative pause deliberately keeps them. See
[AdvertiserEntity](#advertiserentity).

**Select Flow (with Pacing):**
```
Select(url, slotId)
    │
    ├── Warmup mode? ──Yes──► Record traffic, return WarmupModeActive
    │
    ├── Load ServeView from DData
    │
    ├── Check frequency caps (query AdvertiserEntity)
    │
    ├── Pacing gate (BEFORE Thompson Sampling)
    │   │
    │   └── shouldServe(ctx)? ──No──► Return NoCandidates (pacingSkipped++)
    │
    ├── Thompson Sampling: pick winner
    │
    ├── Reserve budget (campaign + advertiser)
    │
    └── Return Selected(candidate, requestId)
```

---

#### ServeIndexDData
**Type:** DData (Bucketed LWWMap)

High-performance distributed cache for approved ads.

```
Key: "publisher|url|slot"
Value: ServeView
    ├── candidates: Vector[CandidateView]
    ├── version: Long
    └── expiresAtMs: Long
```

**Consistency:**
- Writes: `WriteLocal` (fast)
- Removes: `WriteMajority` with retry (ensures takedown)
- Reads: `ReadLocal` (stale-ok for serving)

---

## Request Lifecycle

### Complete Flow: Page Load → Ad Served

```
┌─ 1. PAGE CLASSIFICATION (on-demand) ─────────────────────────────────────────┐
│                                                                              │
│   Ad tag, on a cold serve miss: extracts page text + slot geometry           │
│         │                                                                    │
│         ▼                                                                    │
│   POST /v1/classify-page  ──► 202 Accepted (never blocks on the LLM)         │
│         │                                                                    │
│         ▼                                                                    │
│   SiteEntity[siteId].ClassifyUrl  ── single-flight ──► LLM (Taxonomy 3.0)    │
│         │                                                                    │
│         ▼                                                                    │
│   PageCategoriesClassified(url, categoryScores, slots)                       │
│         │                                                                    │
│         ▼                                                                    │
│   AuctioneerEntity[siteId]  ◄── Cache classification (persisted copy in      │
│                                 SiteEntity survives restarts)                │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─ 2. CATEGORY RANKING (Thompson Sampling) ────────────────────────────────────┐
│                                                                              │
│   Select top-K categories by classifier confidence                           │
│         │                                                                    │
│         ├──► TaxonomyRankerEntity[cat1|site] ──► Quote ──► Quoted(weight1)   │
│         ├──► TaxonomyRankerEntity[cat2|site] ──► Quote ──► Quoted(weight2)   │
│         └──► TaxonomyRankerEntity[cat3|site] ──► Quote ──► Quoted(weight3)   │
│         │                                                                    │
│         ▼                                                                    │
│   Aggregate (800ms timeout)                                                  │
│   finalScore[cat] = classifierConfidence × sampledWeight                     │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─ 3. BID COLLECTION ──────────────────────────────────────────────────────────┐
│                                                                              │
│   For each slot × eligible category:                                         │
│         │                                                                    │
│         └──► CategoryBidderEntity[category]                                  │
│                   │                                                          │
│                   ├──► CampaignEntity[adv1|camp1] ──► CampaignBidResponse    │
│                   ├──► CampaignEntity[adv2|camp2] ──► CampaignBidResponse    │
│                   └──► CampaignEntity[adv3|camp3] ──► CampaignBidResponse    │
│                   │                                                          │
│                   ▼                                                          │
│              Filter: Keep bids ≥ (maxCPM × 80%)                              │
│              Return: CategoryBidResponse(candidates)                         │
│         │                                                                    │
│         ▼                                                                    │
│   Aggregate all categories (800ms timeout)                                   │
│   Keep all competitive candidates per slot (no cap; dedupe by creative,      │
│   best-per-campaign ordered first)                                           │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─ 4. APPROVAL & CACHING ──────────────────────────────────────────────────────┐
│                                                                              │
│   AdServer[siteId] ! CandidatesCollected(url, slot, candidates, ttl)         │
│         │                                                                    │
│         ├── Creative pre-approved? ──Yes──► ServeIndexDData.put(...)         │
│         │                                                                    │
│         └── No ──► CreativeStore.queue(pending)                              │
│                         │                                                    │
│                    Publisher reviews...                                      │
│                         │                                                    │
│                    Approve ──► ServeIndexDData.put(...)                      │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─ 5. SERVE TIME (/serve endpoint) ────────────────────────────────────────────┐
│                                                                              │
│   GET /serve?site=X&url=Y&slot=Z                                             │
│         │                                                                    │
│         ▼                                                                    │
│   ServeIndexDData.get(key) ──► Option[ServeView]                             │
│         │                                                                    │
│         ▼                                                                    │
│   Select candidate (Thompson Sampling / MAB)                                 │
│         │                                                                    │
│         ▼                                                                    │
│   Return: { creativeId, assetUrl, clickUrl }                                 │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─ 6. POST-IMPRESSION ─────────────────────────────────────────────────────────┐
│                                                                              │
│   On Impression:                                                             │
│     CampaignEntity ! RecordSpend(requestId, cpm/1000, ts)                    │
│         └── Buffer → Flush → AdvertiserEntity.RecordCampaignSpend            │
│                                                                              │
│   On Category Win:                                                           │
│     TaxonomyRankerEntity ! RecordFeedback(revenue, won=true, clicked)        │
│                                                                              │
│   On Category Loss:                                                          │
│     TaxonomyRankerEntity ! RecordFeedback(0, won=false, clicked=false)       │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Data Flow Diagrams

### Bidding Chain

```
AuctioneerEntity[site-123]
       │
       ├──► TaxonomyRankerEntity[sports|site-123]
       │         │
       │         └── Quote ──► Quoted(weight=0.72)
       │
       └──► CategoryBidderEntity[sports]
                 │
                 ├──► CampaignEntity[adv-1|camp-1]
                 │         │
                 │         ├── Check: status==Active ✓
                 │         ├── Check: category match ✓
                 │         ├── Check: size match ✓
                 │         ├── Check: budget OK ✓
                 │         ├── Check: site not blocked ✓
                 │         │
                 │         └── CampaignBidResponse(cpm=$5.00, creatives=[...])
                 │
                 └──► CampaignEntity[adv-2|camp-3]
                           │
                           ├── Check: budget OK ✗ (exhausted)
                           │
                           └── CampaignBidResponse(eligible=false)
```

### Spend Tracking Flow

```
AdServer (Impression Served)
       │
       ▼
CampaignEntity ! RecordSpend(requestId, $0.005, ts)
       │
       ├── Idempotency Check:
       │   ├── Cache lookup (5min TTL) ──► HIT? Return cached
       │   └── Bloom filter check ──► HIT? Reject duplicate
       │
       ├── Buffer spend in memory
       │
       └── Timer: Flush every 10s
           │
           ├── Drain buffer → Persist to DB
           ├── Serialize Bloom filter → Persist
           │
           └── AdvertiserEntity ! RecordCampaignSpend(...)
                     │
                     ├── Check daily window roll
                     │   └── If rolled: Publish BudgetReset event
                     │
                     └── Detect exhaustion threshold
                         └── Publish BudgetExhausted event
```

---

## Key Algorithms

### 1. Thompson Sampling

Used by `TaxonomyRankerEntity` for category scoring.

**Model:**
```
opportunities: Total times category was considered in auction
wins:          Times category's ad won the auction
clicks:        Times served ad was clicked
revenue:       Total revenue earned

Beta distribution: Beta(wins + α, (opportunities - wins) + β)
```

**Scoring:**
```scala
winRateSample = BetaDistribution(alpha, beta).sample()
score = normalizedRevenue × winRateSample × (1 + ctrBonus × ctrAdjustment)
```

**Properties:**
| Scenario | Variance | Behavior |
|----------|----------|----------|
| Few opportunities | High | Occasionally samples high → **exploration** |
| Many opportunities | Low | Consistently samples near mean → **exploitation** |

### 2. Probabilistic Budget Throttling

Used by `CampaignEntity` to smooth budget depletion.

```
bidCpm = max(maxCpm, floorCpm)
hardFloor = bidCpm × 0.5      # Stop bidding
softCeiling = bidCpm × 2.0    # Start throttling

remaining >= softCeiling     → always bid (100%)
remaining < hardFloor        → never bid (0%)
between                      → linear probability
```

### 3. Bloom Filters

Used for memory-efficient probabilistic membership testing.

**Use Cases:**
- `CampaignEntity`: Idempotent spend tracking (prevent double-counting)
- `AdvertiserEntity`: Creative approval status per site

**Properties:**
- No false negatives (if added, always found)
- ~0.1% false positive rate
- ~50KB for 50,000 elements (vs ~600KB for HashMap)

See [BloomFilter.scala](../modules/core/src/main/scala/promovolve/common/BloomFilter.scala).

---

## Traffic Shape Learning

### Problem: Linear Pacing Fails

Linear pacing assumes uniform traffic throughout the day:

```
Budget: $30/day

Linear Target:
Hour:    0    6    12   18   24
         |----|----|----|----|
Target:  $0  $7.5  $15  $22.5 $30
         └─────────────────────┘ straight line
```

But real traffic has shape — peaks during day, valleys at night:

```
Traffic Volume:
         ▁▁▂▃▅▆███▇▆▅▄▃▂▁▁
         night  peak  evening
```

This causes:
- **Over-throttling during peaks** — wasted inventory when traffic is abundant
- **Under-delivery during valleys** — impossible targets when traffic is scarce
- **Integral windup** — PI controller fights against reality

### Solution: TrafficShapeTracker

Track relative traffic volume per time bucket using EMA. The cumulative distribution function (CDF) of learned traffic becomes the expected spend curve:

```
Traffic-Shaped Target:
Hour:    0    6    12   18   24
         |----|----|----|----|
Target:  $1   $5   $18   $26  $30
           ╱      ╱╲
          ╱      ╱  ╲
         ╱──────╱    ╲────────
         └─ matches traffic shape ─┘
```

### Weekday/Weekend Separation

Traffic patterns differ between weekdays and weekends. TrafficShapeTracker maintains separate shapes:

```scala
// Internal state
weekdayShape: Array[Double]  // Mon-Fri pattern
weekendShape: Array[Double]  // Sat-Sun pattern
todayIsWeekend: Boolean      // Current day type
```

The appropriate shape is selected automatically based on the day of week. Both shapes are learned independently and persisted via `TrafficShapeSnapshot`.

### Warmup Mode

Sites can enter **warmup mode** to learn traffic patterns before serving ads:

```
Warmup Mode:
┌──────────────────────────────────────────────────────────┐
│  Select requests arrive                                   │
│        │                                                  │
│        ▼                                                  │
│  TrafficShapeTracker.recordRequest(elapsed)               │
│        │                                                  │
│        ▼                                                  │
│  Return WarmupModeActive (no ads served)                  │
│                                                           │
│  After sufficient data:                                   │
│    1. Export learned shapes via API                       │
│    2. Configure site with shapes                          │
│    3. Exit warmup mode                                    │
│    4. Normal serving begins with accurate pacing          │
└──────────────────────────────────────────────────────────┘
```

**API Endpoints:**
- `GET /sites/{id}/stats` — Returns learned `weekdayShapeVolumes` and `weekendShapeVolumes` (read-only export)
- `PUT /sites/{id}/pacing` — Exit warmup mode (`warmupMode: false`)

Shapes are **learn-only**: there is deliberately no import/configure
endpoint — a site's shape comes from its own traffic, starting from a
uniform distribution (= linear pacing) and converging over ~5 days of
20/80 daily blending. Both shapes persist per site (weekday and weekend
independently) and are restored on restart.

### Day Rollover

At day boundary, today's observations blend into the appropriate shape:

```scala
tracker.rolloverDay(dayAlpha = 0.2)
// Shape = 0.2 × today's pattern + 0.8 × historical shape
```

This provides:
- **Stability:** Historical patterns dominate (80%)
- **Adaptation:** Recent changes reflected (20%)
- **Weekday/Weekend:** Each day type evolves independently

---

## Budget Pacing

### Spend Ratio Pacing

The pacing system uses a **spend ratio** to determine throttle probability:

```
spendRatio = actualSpend / expectedSpend

expectedSpend = dailyBudget × trafficShapeCDF(elapsedTime)
```

| Spend Ratio | Meaning | Action |
|-------------|---------|--------|
| > 1.0 | Ahead of target (over-pacing) | Increase throttle |
| = 1.0 | Perfectly on pace | Maintain |
| < 1.0 | Behind target (under-pacing) | Decrease throttle |

### AdaptivePacing (PI Controller)

The default `AdaptivePacing` strategy uses a PI controller:

```scala
// Error signal
error = spendRatio - 1.0  // Positive = over-pacing

// PI control
proportional = kp × error
integral = ki × Σ(error × dt)  // Accumulated over time

// Throttle probability
throttle = baseThrottle + proportional + integral
```

**Traffic-Shape Awareness:**
The controller adjusts for current traffic volume:

```scala
// During peak hours (relativeVolume > 1.0): expect more traffic
// During valley hours (relativeVolume < 1.0): expect less traffic
adjustedTarget = baseTarget × relativeVolume
```

### Pacing Before Selection (Critical)

**IMPORTANT:** Pacing gates volume, not choice.

Pacing must be applied **BEFORE** Thompson Sampling selection:

```scala
// Correct flow:
if (pacing.shouldServe(ctx)) {
  winner = ThompsonSampling.pick(candidates)  // TS runs only when serving
  serve(winner)
} else {
  skip()  // Do NOT run TS, do NOT update posteriors
}

// Wrong flow (biases exploration):
winner = ThompsonSampling.pick(candidates)
if (!pacing.shouldThrottle(ctx)) serve(winner)  // Exploration arms get censored!
```

### ServeStats Tracking

AdServer tracks pacing outcomes for monitoring:

```scala
ServeStats(
  siteId: String,
  selected: Long,          // Successful serves
  pacingSkipped: Long,     // Throttled by pacing gate
  budgetExhausted: Long,   // Campaign/advertiser out of budget
  noCandidates: Long,      // No approved ads
  contentTooOld: Long,     // Content beyond recency window
  warmup: Long,            // Warmup mode requests
  totalSpend: Double,      // Actual spend in dollars
  hourlyImpressions: Array[Long]  // Per-hour breakdown
)
```

---

## Persistence & Caching

### Persistence Strategy

| Entity | Type | Storage | Recovery |
|--------|------|---------|----------|
| AdvertiserEntity | DurableStateBehavior | PostgreSQL | Auto-recover |
| CampaignEntity | DurableStateBehavior | PostgreSQL | Auto-recover + replay pending |
| CampaignDirectory | DurableStateBehavior | PostgreSQL | Auto-recover + rebuild index |
| TaxonomyRankerEntity | DurableStateBehavior | PostgreSQL | Auto-recover (5s data loss OK) |
| SiteEntity | DurableStateBehavior | PostgreSQL | Auto-recover; replays pageClassifications to Auctioneer |
| AuctioneerEntity | Ephemeral | None | Rebuilt on traffic + `RestoreClassifications` backfill from SiteEntity |
| AdServer | Ephemeral | None | DData replication |
| ServeIndexDData | DData | In-memory + replicated | Converges across nodes |

### DData Usage

| Component | Key Type | Value Type | Consistency |
|-----------|----------|------------|-------------|
| ServeIndexDData | `pub\|url\|slot` | ServeView | WriteLocal / WriteMajority (removes) |
| CategoryRegistry | CategoryId | SiteSet | WriteLocal |
| Domain Blocklist | SiteId | CachedDomainBlocklist | WriteLocal |

---

## Event-Driven Re-Auction

### Event Types & Triggers

| Event | Source | Scope | Action |
|-------|--------|-------|--------|
| `CampaignChanged` | CampaignEntity | Targeted | Re-auction affected URLs only |
| `CampaignBudgetExhausted` | CampaignEntity | Targeted | Re-auction campaign's URLs |
| `CampaignBudgetReset` | CampaignEntity | Targeted | Re-auction campaign's URLs |
| `AdvertiserBudgetExhausted` | AdvertiserEntity | Full site | Re-auction all recent pages |
| `AdvertiserBudgetReset` | AdvertiserEntity | Full site | Re-auction all recent pages |
| `DebouncedReauction` | Any event-driven trigger | Coalesced | 1s debounce — bursts of triggers collapse into one full re-auction |
| `PeriodicReauction` | Timer (30min code default; 5min in deployed application.conf) | Full site | **Backstop only** — keeps ServeIndex entries warm and reconciles dropped events |

Primary re-auctions are **event-driven**: campaign/budget/floor changes call
`scheduleReauction()`, which fires on a 1-second debounce
(`DebouncedReauction`). Serve misses additionally self-heal on demand (the
AdServer requests a `Reevaluate`), so the periodic timer is no longer the
primary fill mechanism — it is kept well under the ServeView TTL (120min) as a
reconciliation backstop.

### Event Flow

```
CampaignEntity (config change)
       │
       ▼
Topic.Publish(CampaignChanged(campaignId, categories))
       │
       ▼
AuctioneerEntity[siteId] (subscriber)
       │
       ├── Lookup: participatingCampaigns[campaignId]
       │
       └── For each URL:
           └── Reevaluate(url) → Full auction pipeline
```

---

## Sharding & Distribution

### Entity Distribution

| Entity | Sharding Key | Example | Cardinality |
|--------|--------------|---------|-------------|
| AdvertiserEntity | `advertiserId` | `"adv-123"` | 1 per advertiser |
| CampaignEntity | `advertiserId\|campaignId` | `"adv-1\|camp-5"` | 1 per campaign |
| CategoryBidderEntity | `categoryId` | `"sports"` | ~600 (IAB taxonomy) |
| TaxonomyRankerEntity | `category\|siteId` | `"sports\|site-a"` | categories × sites |
| AuctioneerEntity | `siteId` | `"pub-123"` | 1 per site |
| AdServer | `siteId` | `"pub-123"` | 1 per site |
| PublisherEntity | `publisherId` | `"pub-456"` | 1 per publisher |
| SiteEntity | `siteId` | `"site-789"` | 1 per site |

### Cluster Roles

The historical three-tier split (dedicated API / entity / singleton nodes) has
been **consolidated**: the deployed cluster runs 2 identical `api` pods, each
carrying all roles (`k8s/application-app.conf`):

```hocon
pekko.cluster.roles = ["singleton", "entity", "api", "crawler"]
```

```
┌─ api pod (×2, all roles) ─────────────────────────────┐
│ Roles: ["singleton", "entity", "api", "crawler"]      │
│ • HTTP server (/serve, /v1/classify-page, dashboards) │
│ • All sharded entities + topic instances              │
│ • Singletons (CampaignDirectory, CategoryRegistry)    │
│ • LPWorker shards (Playwright landing-page analysis)  │
└───────────────────────────────────────────────────────┘
```

Role notes:

- **`crawler` no longer means content crawling** (that was removed — pages
  are classified on demand). The role exists solely to **pin `LPWorker`
  shards** — the Playwright workers that analyze advertiser *landing pages*
  (`modules/browser` `LPAnalyzer`) — so Chromium only runs on crawler-role
  nodes. The role is load-bearing: with no carrier in the cluster, LP
  creative generation breaks silently.
- A dedicated **singleton StatefulSet is kept at `replicas: 0`** — the better
  shape for real multi-node infrastructure (odd entity count for
  split-brain-resolver majorities) — and the dedicated crawler tier was
  deleted.

---

## Configuration Reference

### Auction Settings

```hocon
promovolve.auction {
  top-k = 3                        # Top K categories to consider
  ask-timeout = 800ms              # Aggregator timeout
  prior-weight = 0.5               # Fallback weight on timeout
  min-score = 0.0                  # Minimum category score
  keep-candidates-per-slot = 3     # LEGACY — declared but never used; there is
                                   # no candidate cap (see Bid Collection)
  ttl = 120m                       # Candidate TTL
  reauction-interval = 30m         # Periodic BACKSTOP re-auction (code default;
                                   # the deployed application.conf sets 5 minutes).
                                   # Primary re-auctions are event-driven with a
                                   # 1s debounce — see Event-Driven Re-Auction.
  content-recency-window = 48h     # Classification-freshness window (code default;
                                   # not set in application.conf). Publisher-
                                   # overridable per site (valid 24h–7d)
  floor-cpm = 0.50                 # Base site floor (see Floor CPM Optimizer)
  max-page-cache-size = 10000      # Max URLs cached
}
```

### Floor CPM Optimizer

The site-level `floor-cpm` is only the **base**. Per-category and per-slot
floors layer on top of it, tuned automatically by a direct sweep optimizer.

`AuctioneerEntity` resolves each slot's effective floor in priority order:

```
1. Admin override        (publisher escape hatch — adminSlotFloorOverrides,
                          or an override carried on the slot spec via the
                          classify-page path; always wins)
2. Sweep-learned override (learned per-slot floor — slotFloorOverrides)
3. (Per-category floor, else site floor) × slot-prior scaling
                          — SlotPrior: floor × (0.5 + qualityScore), a
                            0.5×–1.5× band. The prior is measured by the AD
                            TAG in the visitor's real viewport (above-fold,
                            viewability, region, text density) — the crawler
                            that used to produce it is gone
4. Site floor            (fallback — the base floor-cpm above)
```

**FloorSweepOptimizer — the sole floor mechanism.** The former DQN agent was
removed; there is no mode switch. `FloorSweepOptimizer` is a direct optimizer:
hold each candidate floor for one observation window, measure **realized
(post-pacing) served revenue**, pick the argmax, then exploit the winner
before re-sweeping to track market drift. No value function is needed because
changing the floor already triggers a re-auction, so each candidate floor
naturally gets its own real revenue measurement. Candidate bounds are capped
by the min/max bids actually observed in the current cycle (including
below-floor rejects, so priced-out campaigns can re-enter).

```hocon
promovolve.floor-optimizer {
  candidate-count     = 8     # Floors probed per sweep
  ticks-per-candidate = 4     # Observation windows held per candidate
  exploit-ticks       = 60    # Windows parked on the argmax winner (~65% duty)
  min-imps-for-argmax = 3     # Evidence gate before a candidate can win
  tie-tolerance       = 0.05  # Near-tie band: prefer the HIGHER floor on a
                              # revenue plateau (no flip-flop on noise)
}
```

**Per-category floors (always on, enforce mode).** Alongside the site-level
sweep, `SiteEntity` runs one sweep optimizer per serving category and pushes
the learned floors to `AuctioneerEntity` (`UpdateCategoryFloors`); the
AdServer applies the same map at serve/clearing time. Unlearned categories
fall back to the site floor. Two shortcuts skip the sweep:

- **Monopoly:** a category with exactly one bidder sets its floor to that bid
  directly — instant adaptation, no drift.
- **Explicit zero demand:** when a category's demand drops to an explicit
  zero bidders (e.g. the sole bidder's creative was flagged/rejected/revoked),
  the floor collapses to the publisher minimum and sweep state is wiped — a
  reserve above bids that no longer exist would only block re-entry.

**Floors read APPROVED demand only.** Pending (not-yet-approved) creatives DO
bid — winning is how they reach the approval queue — but their bids never
teach the floor: a pending bid must not peg a reserve that nothing servable
can fulfil.

`SiteEntity` emits two per-slot messages to its `AuctioneerEntity`:

- `UpdateSlotFloors(Map[SlotId, CPM])` — sweep-learned per-slot overrides.
- `UpdateAdminSlotFloors(Map[SlotId, CPM])` — the publisher escape hatch; a
  manually pinned floor that always wins over the learned value.

Floor overrides only re-shape the floor; everything else (auction, pacing,
Thompson Sampling) is unchanged. Note: in a homogeneous market (all bids
equal) the optimal floor converges to the minimum — correct but useless; the
floor only earns money when the bid spread is wide.

### Taxonomy Ranker Settings

```hocon
promovolve.taxonomy-ranker {
  half-life = 7d                   # Exponential decay
  revenue-cap = 5.0                # Revenue normalization ceiling
  default-revenue = 1.50           # Assumed revenue for new categories
  ctr-bonus = 0.3                  # CTR weight in score
  prior-wins-alpha = 1.0           # Beta prior (wins)
  prior-wins-beta = 1.0            # Beta prior (losses)
  flush-every = 5s                 # Batched persistence interval
}
```

### Campaign Settings

```hocon
promovolve.campaign {
  flush-interval = 10s             # Spend buffer flush
  max-batch-size = 1000            # Batch trigger
  idempotency-window = 5m          # Cache TTL
  idempotency-max-entries = 50000  # Cache size
}
```

---

## Related Documentation

- [Auction Architecture](../modules/core/src/main/scala/promovolve/auction/AUCTION.md)
- [Publisher & Ad Serving](../modules/core/src/main/scala/promovolve/publisher/PUBLISHER.md)
- [Taxonomy Ranking (Thompson Sampling)](../modules/core/src/main/scala/promovolve/taxonomy/TAXONOMY_RANKING.md)
- [Auction Test Walkthrough](../dev_docs/auction-test-walkthrough.md)
- [TrafficShapeTracker](../modules/core/src/main/scala/promovolve/publisher/delivery/TrafficShapeTracker.scala) — Detailed EMA-based traffic pattern learning
- [PacingStrategy](../modules/core/src/main/scala/promovolve/publisher/delivery/PacingStrategy.scala) — Budget pacing algorithms

---

## Test Scripts

### RunScenario (Unified Test Script)

All test scenarios are consolidated into a single script: `scripts/RunScenario.scala`

**Modes:**

| Mode | Description |
|------|-------------|
| `pacing` (default) | Budget pacing with traffic shapes |
| `auction` | Basic Thompson Sampling feedback loop |
| `category-race` | Multi-category Thompson Sampling race |
| `creative-race` | Multi-creative Thompson Sampling race |

**Usage:**

```bash
# Pacing test with scenario file
scala-cli scripts/RunScenario.scala -- --scenario scenarios/continuous.json

# Category race with custom CTRs
scala-cli scripts/RunScenario.scala -- \
  --mode category-race \
  --categories "sports:0.08,tech:0.05,gaming:0.02"

# Creative race with Thompson Sampling selection
scala-cli scripts/RunScenario.scala -- \
  --mode creative-race \
  --creatives "alpha:0.10,beta:0.05,gamma:0.02" \
  --thompson-selection
```

**Key Configuration:**

```scala
Config(
  mode: String = "pacing",
  dayDurationSeconds: Int = 86400,     // Simulated day length
  weekdayShapeVolumes: Option[Array[Double]],  // 24 hourly values
  weekendShapeVolumes: Option[Array[Double]],  // 24 hourly values
  continuous: Boolean = false,         // Run until interrupted
  maxRequests: Int = 1000,
  clickRate: Double = 0.05,
  thompsonSelection: Boolean = false   // Use TS for race modes
)
```

### Other Scripts

| Script | Purpose |
|--------|---------|
| `TrafficShapeSim.scala` | Offline visualization of traffic shape effects |
| `Test.scala` | Quick ad-hoc testing |

### Scenario Files

Scenario files (JSON) configure complex test setups:

```json
{
  "siteId": "site-1",
  "budget": 100.0,
  "dayDurationSeconds": 600,
  "weekdayShapeVolumes": [0.2, 0.3, 0.5, 1.0, 2.0, 3.0, 2.5, 1.5, 0.8, 0.4],
  "continuous": true
}
```

See `scripts/scenarios/` for example scenario files.

---

## Summary

Promovolve achieves:

| Goal | How |
|------|-----|
| **Horizontal scale** | Pekko cluster sharding |
| **Low latency (<2s)** | 800ms Phase 2 + 800ms Phase 3 |
| **Intelligent learning** | Thompson Sampling for category ranking and creative selection |
| **Correctness** | Idempotency, JDBC persistence, DData replication |
| **Budget control** | Traffic-shaped pacing, PI controller, buffered spend tracking |
| **Multi-candidate auctions** | Uncapped shortlisting with CPM thresholds, serve-time MAB selection, quality-adjusted second-price clearing |
| **Traffic adaptation** | Weekday/weekend shape learning, warmup mode |