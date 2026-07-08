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
│   │  (Account)       │     │   (Crawler)      │     │  (Auction)        │   │
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
| **SiteEntity**      | Site configuration, scheduled crawling, page classification   |
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
    │       ├── Crawler (scheduled)
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

Manages individual site configuration and scheduled crawling.

```scala
State:
├── siteId: SiteId
├── publisherId: Option[PublisherId]
└── config: Option[SiteConfig]
    ├── domain: String
    ├── seedUrl: String
    ├── cronSchedule: String          // Quartz cron expression
    ├── maxDepth: Int
    ├── concurrency: Int
    ├── hostRegex: String
    ├── targetElements: List[String]
    └── taxonomyIds: Set[String]      // IAB taxonomy category IDs
```

**Lifecycle:**

```
Register(publisherId) → Initialize(config) → [Running] → Shutdown
         │                     │
         │                     └── Sets up:
         │                         • Quartz scheduler for crawls
         │                         • IAB taxonomy assistant
         │                         • Category registration
         │
         └── Persists publisherId only (minimal state)
```

**Crawl Flow:**

```
Quartz Timer
    │
    ▼
StartCrawling(crawlerConfig)
    │
    ▼
Crawler actor spawned
    │
    ├── Watches: CrawlerTerminated
    │
    └── Receives: PageContent(url, text)
            │
            ▼
        IABTaxonomy.analyzeTaxonomy(url, text)
            │
            ▼
        ContentAnalyzed(url, text, selections)
            │
            ▼
        AuctioneerEntity ! PageCategoriesClassified(
            url, categoryScores, slots
        )
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
| `Select(url, slotId)`            | Serve-time selection request                  |
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

AdServer uses Thompson Sampling for serve-time creative selection:

```scala
// For each candidate:
1. Normalize CPM to pseudo-CTR in [0.1, 0.9] range
2. Compute Beta parameters:
   - alpha = 1.0 + normalizedCpm × 10.0   // [2, 10]
   - beta  = 1.0 + (1 - normalizedCpm) × 10.0
3. Sample from Beta(alpha, beta) using Gamma trick
4. Select candidate with highest sample

// Gamma trick for Beta sampling:
Beta(α, β) = Gamma(α, 1) / (Gamma(α, 1) + Gamma(β, 1))
```

### Properties

| Property             | Effect                                                                   |
|----------------------|--------------------------------------------------------------------------|
| **Higher CPM**       | Higher pseudo-CTR → higher alpha → higher samples (more likely selected) |
| **Variance**         | Even lower-CPM candidates occasionally win (exploration)                 |
| **Single candidate** | Returned directly (no sampling needed)                                   |

### CPM to Pseudo-CTR Mapping

```
CPM Range        Normalized     Alpha    Beta
─────────────────────────────────────────────
Min CPM          0.1            2.0      10.0
Mid CPM          0.5            6.0      6.0
Max CPM          0.9            10.0     2.0
```

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
GET /serve?site=X&url=Y&slot=Z
       │
       ▼
AdServer ! Select(url, slot, recencyWindow, replyTo)
       │
       ├── ServeIndexDData ! Get(key, adapter)
       │
       │   ┌── async ──┐
       │   │           │
       │   ▼           │
       │  GetResult    │
       │   │           │
       └───┼───────────┘
           │
           ▼
SelectViewLoaded(viewOpt, recencyWindow, replyTo)
       │
       ├── Filter by content recency
       │
       ├── Thompson Sampling selection
       │
       └── replyTo ! Selected(winner)
```

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
  seedUrl = "https://example.com/",
  cronSchedule = "0 0 2 * * ?",   // 2am daily
  maxDepth = 3,
  concurrency = 4,
  hostRegex = ".*\\.example\\.com",
  targetElements = List("article", "main"),
  taxonomyIds = Set("IAB1", "IAB2")  // Target categories
)
```

---

## Summary

The publisher package provides:

| Capability             | Implementation                              |
|------------------------|---------------------------------------------|
| **Account management** | PublisherEntity with domain blocklist DData |
| **Site crawling**      | SiteEntity with Quartz scheduler            |
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