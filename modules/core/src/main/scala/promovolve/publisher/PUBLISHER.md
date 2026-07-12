# Publisher Package Architecture

The publisher package manages the **ad serving pipeline** from auction completion to impression delivery. It handles
creative approval workflows, distributed caching, and serve-time selection.

## Table of Contents

- [Overview](#overview)
- [Component Hierarchy](#component-hierarchy)
- [Entity Details](#entity-details)
- [Creative Approval Workflow](#creative-approval-workflow)
- [ServeIndexDData Architecture](#serveindexddata-architecture)
- [Serve-Time Selection (Thompson Sampling)](#serve-time-selection-thompson-sampling)
- [Data Models](#data-models)
- [Message Flows](#message-flows)
- [Configuration](#configuration)

---

## Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PUBLISHER PACKAGE                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐   │
│   │ PublisherEntity  │────►│   SiteEntity     │────►│  AuctioneerEntity │   │
│   │  (Account)       │     │ (Classification) │     │  (Auction)        │   │
│   └──────────────────┘     └──────────────────┘     └────────┬─────────┘   │
│           │                                                   │              │
│           │ DData                                             │              │
│           │ (blocklist)                                       ▼              │
│           │                                         ┌──────────────────┐    │
│           └────────────────────────────────────────►│    AdServer      │    │
│                                                     │  (Approval +     │    │
│                                                     │   Selection)     │    │
│                                                     └────────┬─────────┘    │
│                                                              │              │
│                           ┌──────────────────────────────────┼──────────┐   │
│                           │                                  │          │   │
│                           ▼                                  ▼          │   │
│                   ┌──────────────┐                   ┌──────────────┐   │   │
│                   │CreativeStore │                   │ServeIndexDData│   │   │
│                   │ (Pending)    │                   │ (Approved)    │   │   │
│                   └──────────────┘                   └──────────────┘   │   │
│                                                                         │   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Responsibilities

| Component           | Responsibility                                                |
|---------------------|---------------------------------------------------------------|
| **PublisherEntity** | Publisher account management, domain blocklist, site registry |
| **SiteEntity**      | Site configuration, on-demand page classification (single-flighted Gemini) |
| **AdServer**        | Creative approval workflow, serve-time MAB selection          |
| **ServeIndexDData** | Distributed cache for approved ads (hot path)                 |
| **CreativeStore**   | Ephemeral queue for pending creative approvals                |

---

## Component Hierarchy

```
Publisher Account (PublisherEntity)
    │
    ├── Site 1 (SiteEntity)
    │       │
    │       ├── On-demand classification (ad tag → POST /v1/classify-page → ClassifyUrl)
    │       │       └── PageCategoriesClassified → AuctioneerEntity
    │       │
    │       └── AdServer (sharded by siteId)
    │               ├── CreativeStore (pending queue)
    │               └── ServeIndexDData subscription
    │
    └── Site 2 (SiteEntity)
            └── ...
```

---

## Entity Details

### PublisherEntity

**Sharding Key:** `publisherId`
**Persistence:** DurableStateBehavior (PostgreSQL)

Manages publisher accounts with domain blocklists replicated via DData.

```scala
State:
├── publisherId: PublisherId
├── status: Active | Suspended | Closed
├── siteIds: Set[SiteId]              // Owned sites
└── domainBlocklist: Set[String]      // Blocked landing page domains
```

**Key Commands:**

- `RegisterSite(siteId)` - Register a new site under this publisher
- `DeleteSite(siteId)` - Remove site and shutdown its SiteEntity
- `BlockDomains(domains)` - Block landing page domains (replicated via DData)
- `UnblockDomains(domains)` - Unblock domains
- `GetDomainBlocklist` - Query current blocklist

**DData Replication:**

```scala
// Domain blocklist replicated across cluster for fast reads by AdServer
DomainBlocklistCacheKey: LWWMapKey[SiteId, CachedDomainBlocklist]

// On blocklist change:
replicator ! Replicator.Update(
  DomainBlocklistCacheKey,
  LWWMap.empty,
  WriteLocal,
  ctx.self
)(_.put(node, siteId, cached))
```

---

### SiteEntity

**Sharding Key:** `siteId`
**Persistence:** DurableStateBehavior (PostgreSQL)

Manages individual site configuration and **on-demand page classification**. Site crawling was removed
(2026-07-02): there is no crawler and no scheduled classification — pages are classified when a real visitor
hits them.

```scala
State:
├── siteId: SiteId
├── publisherId: Option[PublisherId]
└── config: Option[SiteConfig]
    ├── domain: String
    ├── seedUrl: String               // INERT crawl-era vestige
    ├── cronSchedule: String          // INERT crawl-era vestige (no Quartz scheduler runs)
    ├── maxDepth: Int                 // INERT crawl-era vestige
    ├── concurrency: Int              // INERT crawl-era vestige
    ├── hostRegex: String             // INERT crawl-era vestige
    ├── targetElements: List[String]  // INERT crawl-era vestige
    └── taxonomyIds: Set[String]      // IAB taxonomy category IDs
```

The crawl-related `SiteConfig` fields are retained only for persisted-state compatibility; nothing schedules
or spawns a crawler anymore.

**Lifecycle:**

```
Register(publisherId) → Initialize(config) → [Running] → Shutdown
         │                     │
         │                     └── Sets up:
         │                         • IAB taxonomy assistant
         │                         • Category registration
         │
         └── Persists publisherId only (minimal state)
```

**On-Demand Classification Flow:**

```
Ad tag (browser) — cold serve miss on a page
    │
    ▼
POST /v1/classify-page  { pub, url, text, section, slots }   → 202 Accepted
    │                     (fast ack; never blocks on Gemini)
    ▼
SiteEntity ! ClassifyUrl(url, text, section, slots, replyTo)
    │
    ├── Single-flight per URL: concurrent requests for the same page
    │   coalesce (ClassifyAck accepted=false, reason=in_flight);
    │   site not initialized → reason=not_ready
    │
    ▼
Gemini taxonomy analysis (full IAB taxonomy 3.0, off the actor thread)
    │
    ├── Classification persisted (survives restart; replayed to the
    │   auctioneer via RestoreClassifications)
    │
    ├── Categories matched:
    │       AuctioneerEntity ! PageCategoriesClassified(url, categoryScores, slots, ts)
    │
    └── Honestly no category match:
            AuctioneerEntity ! FillerAuctionRequested(url, slots, ts)
            (only bidOnUnmatchedContext campaigns are invited)
```

---

### AdServer

**Sharding Key:** `siteId`
**Persistence:** None (ephemeral)

Handles the approval workflow and serve-time candidate selection.

```scala
Dependencies:
├── CreativeStore         // Pending approval queue
├── CreativeMaterializer  // Asset copying to publisher CDN
├── CreativeMetadataRepo  // Creative metadata lookup
├── ServeIndexDData       // Approved ad cache
└── ClusterSharding       // For AdvertiserEntity updates
```

**Key Commands:**

| Command                          | Description                                   |
|----------------------------------|-----------------------------------------------|
| `CandidatesCollected`            | Auction results from AuctioneerEntity         |
| `Approve(url, slot, creativeId)` | Publisher approves a creative                 |
| `Reject(url, slot, creativeId)`  | Publisher rejects; promote next or re-auction |
| `BatchSelect(url, slots, ...)`   | Serve-time selection: one request per page load, all slots (the single-slot `Select` command was removed when serve consolidated on `/v1/serve/batch`) |
| `ListPending`                    | List all pending creatives for dashboard      |

**Domain Blocklist Integration:**

```scala
// Subscribe to DData changes
replicator ! Replicator.Subscribe(
  PublisherEntity.DomainBlocklistCacheKey,
  ctx.messageAdapter { changed =>
    BlocklistUpdated(changed.dataValue.get(publisherId))
  }
)

// On CandidatesCollected, filter by blocklist
val filteredCandidates = cachedDomainBlocklist match {
  case Some(blocklist) =>
    candidates.filterNot(c => blocklist.contains(c.landingDomain))
  case None => candidates
}
```

---

## Creative Approval Workflow

### State Machine

```
                                  ┌─────────────────────┐
                                  │    AUCTION WIN      │
                                  └──────────┬──────────┘
                                             │
                                             ▼
                              ┌──────────────────────────────┐
                              │   Creative Pre-Approved?     │
                              │   (Bloom filter check)       │
                              └──────────────┬───────────────┘
                                             │
                         ┌───────────────────┴───────────────────┐
                         │                                       │
                        Yes                                      No
                         │                                       │
                         ▼                                       ▼
              ┌──────────────────┐                   ┌──────────────────┐
              │ ServeIndexDData  │                   │  CreativeStore   │
              │   (Approved)     │                   │   (Pending)      │
              └──────────────────┘                   └────────┬─────────┘
                                                              │
                                             Publisher Review via Dashboard
                                                              │
                                      ┌───────────────────────┴────────────────────┐
                                      │                                            │
                                   Approve                                      Reject
                                      │                                            │
                                      ▼                                            ▼
                          ┌──────────────────────┐                   ┌──────────────────────┐
                          │ 1. Materialize asset │                   │ 1. Update Bloom      │
                          │ 2. Update ServeIndex │                   │    (Rejected status) │
                          │ 3. Update Bloom      │                   │ 2. Promote next      │
                          │    (Approved status) │                   │    candidate OR      │
                          │ 4. Remove from queue │                   │    trigger re-auction│
                          └──────────────────────┘                   └──────────────────────┘
```

### Selection Queue Model

The `Selection` type represents a pending approval queue for one slot:

```scala
case class Selection(
  publisherId: SiteId,
  url: URL,
  slotId: SlotId,
  ordered: Vector[Candidate],  // Top candidates, ranked by CPM
  idx: Int,                    // Current candidate pointer
  state: SelState,             // Always Pending in CreativeStore
  createdAt: Instant,
  expiresAt: Instant           // TTL for re-auction
) {
  def current: Candidate = ordered(idx)

  def promoteNext: Option[Selection] =
    if (idx + 1 < ordered.size) Some(copy(idx = idx + 1))
    else None
}
```

**Promote Logic:**

- On rejection, `idx` advances to next candidate
- If `idx >= ordered.size`, queue is exhausted → trigger re-auction

**Queue Exhaustion Cleanup:**

When all candidates are rejected, `rejectAndPromote` returns `None` and the cleanup sequence is:

```
rejectAndPromote() returns None
       │
       ├── 1. CreativeStore.table.remove(key)  // Clears pending queue (inside rejectAndPromote)
       │
       └── (AdServer on receiving None)
           │
           ├── 2. ServeIndexDData.Remove(key)  // Clears approved cache
           │
           └── 3. AuctioneerEntity.Reevaluate(url)  // Triggers fresh auction
```

This ensures:

- No stale pending entries remain in CreativeStore
- No stale approved entries remain in ServeIndexDData
- Fresh candidates are fetched via re-auction

### Approval Updates Bloom Filter

When a publisher approves/rejects, the decision is recorded in AdvertiserEntity:

```scala
// On Approve:
advertiserRef ! UpdateCreativeApproval(
  creativeId = creativeId,
  siteId = publisherId,
  status = ApprovalStatus.Approved,
  replyTo = system.ignoreRef
)

// Future bids from this campaign to this site will be pre-approved
// (Bloom filter lookup returns "maybe contains" = pre-approved)
```

---

## ServeIndexDData Architecture

### Design Goals

1. **Low latency** - ReadLocal for serve requests (~1ms)
2. **Safe takedown** - WriteMajority for removes (ensures ad stops serving)
3. **Scalable** - Bucketed LWWMap keeps CRDT deltas small
4. **Per-publisher isolation** - Namespace by publisher for targeted purges

### Data Structure

```scala
// Key format: "publisherId|url|slotId"
// Example: "pub-123|https://site.com/article|header-slot"

// Bucketing: 32 buckets (power of 2)
// Distribution: rotateLeft(hash, 13) & (Buckets - 1)

// Per-namespace keys:
LWWMapKey[String, ServeView]("serve-views-{publisher}-{bucket}")
```

### Consistency Model

| Operation | Consistency   | Rationale                           |
|-----------|---------------|-------------------------------------|
| `Put`     | WriteLocal    | Fast path; eventual consistency OK  |
| `Append`  | WriteLocal    | Fast path; append to candidate list |
| `Remove`  | WriteMajority | Safety; ensures takedown propagates |
| `Get`     | ReadLocal     | Hot path; stale-ok for serving      |

### Remove Retry Strategy

Removes use exponential backoff to ensure reliable takedown:

```scala
// Initial: 200ms
// Backoff: 2^attempt × 200ms
// Max: 5 retries, capped at 5s

def backoff(attempt: Int): FiniteDuration =
  InitialRetryBackoff * math.pow(2.0, (attempt - 1)) min 5.seconds

// On UpdateTimeout or StoreFailure:
timers.startSingleTimer(
  s"retry-remove-$ns-$b-$k",
  RetryRemove(ns, b, k, attempt = 1),
  backoff(1)
)
```

### TTL Sweep

Periodic sweep removes expired entries:

```scala
// Configuration:
SweepInterval = 2.minutes        // How often to sweep
MaxKeysRemovePerRun = 500        // Max removals per bucket per run

// Sweep process:
1. For each known namespace (publisher):
2. For each bucket (0..31):
3. ReadLocal to get all entries
4. Filter: entry.expiresAtMs <= now
5. Remove expired (up to MaxKeysRemovePerRun)
```

---

## Serve-Time Selection (Thompson Sampling)

### Algorithm Overview

AdServer uses Thompson Sampling over **real engagement posteriors** for serve-time creative selection
(`ThompsonSampling.scoreCandidate`):

```scala
// For each candidate:
1. Sample CTR       from Beta(clicks + 1, impressions - clicks + 1)
2. Sample fold-rate from Beta(folds + 1,  impressions - folds + 1)
   // cold (0 imps): CTR ← categoryScore prior ± 0.15 jitter; fold ← Beta(1,3)
3. engagement = sampledCTR + 2.0 × foldRate + newcomerBonus(imps)
4. score      = engagement × CPM^α        // α = publisher bidWeight
5. Winner = argmax(score); clearing price from posterior MEANS
   ("sample for allocation, price on means" — see docs/design/DELIVERY_ALGORITHMS.md §5)

// Gamma trick for Beta sampling:
Beta(α, β) = Gamma(α, 1) / (Gamma(α, 1) + Gamma(β, 1))
```

### Properties

| Property             | Effect                                                                    |
|----------------------|---------------------------------------------------------------------------|
| **Higher CPM**       | Larger `CPM^α` factor → more likely selected (α trades quality vs revenue) |
| **Variance**         | Uncertain (under-sampled) creatives occasionally win (exploration)        |
| **Newcomer bonus**   | +0.5 engagement at 0 imps, decaying to 0 over 50 imps                     |
| **Pricing**          | Quality-adjusted second price vs the same-category runner-up, clamped `[floor, bid]`; floor when no runner-up |

### Content Recency Filtering

Before selection, candidates are filtered by content recency:

```scala
val recentCandidates = view.candidates.filter { c =>
  val ageMs = now - c.classifiedAtMs
  ageMs <= contentRecencyWindowMs
}

if (recentCandidates.isEmpty) {
  replyTo ! ContentTooOld  // Trigger re-auction
} else {
  val winner = thompsonSampleSelect(recentCandidates)
  replyTo ! Selected(winner)
}
```

---

## Data Models

### CandidateView

Stored in ServeIndexDData for serve-time rendering:

```scala
case class CandidateView(
  creativeId: CreativeId,
  campaignId: CampaignId,
  advertiserId: AdvertiserId,
  assetUrl: CDNPath,            // CDN URL for rendering
  mime: MimeType,               // image/png, video/mp4, etc.
  width: Int,
  height: Int,
  category: CategoryId,         // Winning category
  cpm: CPM,                     // Bid price
  classifiedAtMs: Long          // For recency validation
)
```

### ServeView

Collection of candidates for a slot:

```scala
case class ServeView(
  candidates: Vector[CandidateView],  // All approved candidates
  version: Long,                      // Auction timestamp
  expiresAtMs: Long                   // TTL for re-auction
)
```

### Key Format

```scala
object Keys {
  // Standard key format: "publisher|url|slot"
  def key(pub: SiteId, url: URL, slot: SlotId): String =
    s"${pub.value}|${url.value}|${slot.value}"
}
```

---

## Message Flows

### Auction → Serve Cache

```
AuctioneerEntity
       │
       │ CandidatesCollected(url, slot, candidates, classifiedAt, ttl)
       ▼
AdServer
       │
       ├── Filter by domain blocklist (DData subscription)
       │
       ├── Partition: pre-approved vs pending
       │
       ├── Pre-approved:
       │       │
       │       └── ServeIndexDData ! Append(key, candidateView, ttl)
       │
       └── Pending:
               │
               └── CreativeStore.upsertPending(selection)
```

### Approval → Serve Cache

```
Publisher Dashboard
       │
       │ POST /approve { url, slot, creativeId }
       ▼
AdServer ! Approve(url, slot, creativeId)
       │
       ├── CreativeStore.getPending(pub, url, slot)
       │
       ├── CreativeMaterializer.ensureOnPublisher(pub, cid)
       │
       ├── ServeIndexDData ! Append(key, candidateView, ttl)
       │
       ├── AdvertiserEntity ! UpdateCreativeApproval(Approved)
       │
       └── CreativeStore.removePending(pub, url, slot)
```

### Serve Request Flow

```
POST /v1/serve/batch  { pub, url, imp: [slots], pins? }
       │
       ▼
AdServer ! BatchSelect(url, slots, recencyWindow, replyTo, excludedCreatives, excludedCampaigns)
       │
       ├── ServeIndexDData ! Get (all slot keys for the page)
       │
       │   ┌── async ──┐
       │   │           │
       │   ▼           │
       │  GetResult    │
       │   │           │
       └───┼───────────┘
           │
           ▼
BatchSelectViewLoaded(views, recencyWindow, replyTo)
       │
       ├── Filter by content recency (miss/stale → MarkClassified check;
       │   cold page → ad tag posts /v1/classify-page)
       │
       ├── Thompson Sampling scoring + greedy per-slot assignment
       │   (batchAssign: page-level creative/campaign dedup, floors,
       │    quality-adjusted second-price clearing)
       │
       └── replyTo ! BatchSelected(outcomes)
```

There is no single-slot `GET /serve` endpoint anymore — admin tooling posts a one-slot batch request instead.

---

## Configuration

### AdServer

```scala
AdServer(
  publisherId = siteId,
  store = creativeStore,
  materializer = creativeMaterializer,
  metaRepo = creativeMetadataRepo,
  serveIndex = serveIndexActor,
  sharding = clusterSharding,
  approvedTtl = 2.hours,      // TTL for approved ads
  purgeInterval = 5.minutes   // Interval for expired pending purge
)
```

### ServeIndexDData

```scala
// Tunables (compile-time constants)
Buckets             = 32          // Number of LWWMap buckets
MajorityTimeout     = 800.millis  // WriteMajority ack timeout
MaxRemoveRetries    = 5           // Retry attempts for Remove
InitialRetryBackoff = 200.millis  // Backoff base
SweepInterval       = 2.minutes   // TTL sweep interval
MaxKeysRemovePerRun = 500         // Max removals per sweep per bucket
```

### SiteEntity

```scala
SiteConfig(
  publisherId = publisherId,
  domain = "example.com",
  seedUrl = "https://example.com/",   // inert crawl-era vestige
  cronSchedule = "0 0 2 * * ?",       // inert crawl-era vestige (nothing schedules crawls)
  maxDepth = 3,                       // inert crawl-era vestige
  concurrency = 4,                    // inert crawl-era vestige
  hostRegex = ".*\\.example\\.com",   // inert crawl-era vestige
  targetElements = List("article", "main"),  // inert crawl-era vestige
  taxonomyIds = Set("IAB1", "IAB2")  // Target categories
)
```

---

## Summary

The publisher package provides:

| Capability             | Implementation                              |
|------------------------|---------------------------------------------|
| **Account management** | PublisherEntity with domain blocklist DData |
| **Page classification**| On-demand: ad tag → `/v1/classify-page` → SiteEntity single-flight → Gemini |
| **Approval workflow**  | AdServer + CreativeStore queue              |
| **Distributed cache**  | ServeIndexDData (bucketed LWWMap)           |
| **Safe takedown**      | WriteMajority removes with retry            |
| **MAB selection**      | Thompson Sampling at serve time             |
| **Content freshness**  | Recency filtering + TTL sweep               |

The design prioritizes:

- **Low latency** (ReadLocal for serves)
- **Safety** (WriteMajority for takedowns)
- **Scalability** (bucketing, per-publisher namespaces)
- **Exploration** (Thompson Sampling enables testing lower-CPM creatives)