# The Standing Bid Book

**Status: design — not yet implemented.**
Motivated by the 2026-07-25 CAMPAIGN-LATE outage (third silent JRA
failure): a campaign whose bid-reply chain exceeded the bidder's
aggregation window vanished from every auction for five hours while
every alarm stayed green.

## The disease this cures

Today, every slot auction re-executes a three-hop ask chain from
scratch, under stacked deadlines:

```
AuctioneerEntity ──ask 800ms──▶ CategoryBidder ──ask 550ms──▶ CampaignEntity
                                                                │ ask 300ms
                                                                ▼
                                                          AdvertiserEntity
```

Auctions run per-minute per site; keiba alone is ~6 slots × ~5
categories of asks, every minute, forever. The design property that
keeps hurting us: **when any link is slow, a campaign is not degraded —
it is erased**, per auction, silently. Partial aggregation treats late
as absent; `BidsToday` counts work done rather than work delivered;
floors keep reading retained state. Slowness anywhere (a starved pod, a
busy mailbox, GC, a shard mid-move) converts directly into invisible
demand loss.

The fixes so far (descending timeout ladder, CAMPAIGN-LATE / SLOW BID
CHAIN warns) make the erasure *survivable* and *visible*. The bid book
makes it *impossible*, by removing the race entirely.

## The idea

`CategoryBidderEntity` stops asking campaigns at auction time. Instead
it maintains a **standing book** of each registered campaign's current
bid material, and answers `CategoryBidRequest` **synchronously from the
book** — no spawned aggregator, no window, no deadline race. Late
information stops meaning *absence* and starts meaning *staleness*:
a slow campaign's quote updates the next auction instead of evaporating.

```
             (push, event-driven + periodic)
CampaignEntity ────────── BidQuote ──────────▶ CategoryBidder.book
                                                     │  synchronous read
AuctioneerEntity ──ask──▶ (answer from book) ────────┘  (~ms, no race)
```

### The book entry

```scala
final case class BookEntry(
    campaignId: CampaignId,
    advertiserId: AdvertiserId,
    cpm: CPM,                    // bid (maxCpm-derived, as today)
    maxCpm: CPM,
    creatives: Set[Creative],    // incl. per-creative approvedSites
    adProductCategory: Option[AdProductCategoryId],
    landingDomain: String,
    quotedAtMs: Long             // freshness; drives staleness handling
)
```

Site-specific facts are **derived at answer time**, not stored: the
book is per (category, stripe) and a stripe serves many sites, so
`hasApprovedCreative` is computed per request as
`creatives.exists(_.approvedSites.contains(siteId))` — same data the
quote already carries today.

### How the book stays current

Quotes are **pushed by the campaign**, which knows exactly when its
material changes; the bidder never asks on the hot path.

1. **On material change** (status flip, bid edit, creative
   added/paused, approval granted/revoked reaching the campaign,
   budget exhaustion/reset): the campaign broadcasts `BidQuote` to all
   stripes of all its categories (`allEntityIdsFor`) — the same
   broadcast shape CampaignDirectory already uses for membership.
2. **Periodic heartbeat** (`QuoteTick`, ~60s, hash-staggered): heals
   lost tells and covers changes without explicit triggers. Cost:
   `categories × 5 stripes` tells per campaign per minute — tens of
   messages, trivial.
3. **Warm-up handshake**: when a bidder (re)seeds its registry, it
   *tells* each member `RequestQuote`; each campaign *tells* back a
   quote. Tell+tell — no ask, no deadline, warm within milliseconds of
   a restart. (Membership itself remains the existing
   seed + directory-push + category_demand-reconcile machinery,
   untouched.)

### Answering an auction

On `CategoryBidRequest(siteId, floorCpm, …)` the bidder, synchronously:

- partitions live entries by `cpm ≥ floorCpm` → qualifying vs
  floor-rejected (with max/min rejected stats, approved-only variants —
  exactly the fields `CategoryBidResponse` carries today, so floors and
  the auctioneer see an unchanged contract);
- applies the existing top-N selection;
- replies immediately. The auctioneer's 800ms window becomes a
  formality covering one mailbox hop.

**Floor filtering moves wholly into the bidder.** Today the campaign
rejects below-floor itself (it receives `floorCpm` in the request);
with the book, the campaign no longer sees floors at all. One floor
gate, one place, synchronous — and floor-reject statistics become
complete rather than window-shaped.

### Staleness, not absence

- Entries carry `quotedAtMs`. Answering from an entry older than a
  **soft threshold** (~3 min = several missed heartbeats) logs
  `BOOK-STALE` naming the campaign — the repeat-per-auction alarm
  pattern, replacing CAMPAIGN-LATE on the book path.
- Entries older than a **hard TTL** (~10 min) are excluded and counted:
  a campaign whose entity has genuinely stopped quoting *is* absent
  demand, and floors/DEMAND-LIVENESS should see that truthfully.
- Between soft and hard, the marketplace serves on the last known bid.
  Slowness now costs *freshness* (a minute-old bid), never *presence*.

## Why the money stays safe

The book is **advisory candidacy only**. Every dollar-moving decision
keeps its authoritative gate:

- **Reservation**: `TryReserve` at serve time remains the budget gate.
  A stale-eligible entry (campaign exhausted 30s ago) produces a
  candidate whose reservation is declined — today's semantics exactly.
- **Recording**: impression beacons → `RecordSpend`, unchanged.
- **Approval**: the pending/approved partition at AdServer, unchanged;
  the book's `approvedSites` only routes queue-vs-serve as today.
- Staleness in the other direction (campaign resumed, book not yet
  updated) costs at most one heartbeat of missed auctions — bounded,
  and the resume itself triggers an immediate quote push anyway.

## What each failure mode becomes

| Failure | Today | With the book |
|---|---|---|
| Slow campaign/advertiser chain | silently erased per auction | serves on last quote; `BOOK-STALE` if it persists |
| Campaign entity dead | erased + green dashboards | stale → hard-TTL exclusion → floors/liveness see real absence |
| Bidder restart | re-ask storm within windows | seed + tell/tell warm-up, ~ms |
| Lost message | that auction loses the campaign | next heartbeat heals (≤60s) |
| Starved pod hosting any link | per-auction erasure | freshness lag only |

`CATEGORY-SILENT` (bidder itself unreachable) and `DEMAND-LIVENESS`
(outcome invariant) remain, unchanged, as the outer nets.

## Migration

1. Ship the book alongside the live-ask path behind
   `promovolve.auction.bid-book.enabled` (default **off**). When
   enabled, entries present in the book answer synchronously; registry
   members with no entry yet fall back to the live ask (hybrid), so
   the cutover has no dark window.
2. Enable on the staging cluster; success criteria: CAMPAIGN-LATE
   count drops to zero; auction candidate assembly p99 < 10ms;
   floors/serving behavior unchanged on healthy campaigns.
3. Retire the campaign-side BelowFloor branch and the live-ask path
   once the book has soaked; CAMPAIGN-LATE becomes vestigial.

## Consciously rejected alternatives

- **Raise the windows** — treats latency as an SLA problem; any budget
  loses to a sufficiently slow chain, and higher budgets slow every
  auction for everyone.
- **Cache at the auctioneer** — wrong owner: the auctioneer is
  per-site; the same campaign's material would be cached N times and
  invalidation would need site-blind campaign events anyway.
- **DB-backed book** — the fabric already delivers events; a table adds
  a polling loop, write amplification on the hot path, and a second
  source of truth (same reasoning that shaped DemandLivenessMonitor).
