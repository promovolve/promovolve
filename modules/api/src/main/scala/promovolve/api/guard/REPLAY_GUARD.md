# Replay Guard: Tracking URL Protection

## Overview

The replay guard prevents duplicate impression/click events from being counted multiple times. It uses a **two-layer
protection system** that combines time-based URL expiry with probabilistic deduplication.

```
Tracking Request (imp/click)
         │
         ▼
┌─────────────────────────────┐
│  Layer 1: Bucket Check      │  ← No memory cost
│  (Time-based URL expiry)    │
│                             │
│  Token expired? → 403       │
└─────────────────────────────┘
         │ Valid
         ▼
┌─────────────────────────────┐
│  Layer 2: Bloom Filter      │  ← Fixed O(1) memory
│  (Deduplication)            │
│                             │
│  Already seen? → 409        │
└─────────────────────────────┘
         │ New
         ▼
    Log Event (204)
```

## Why Two Layers?

| Layer        | Purpose                   | Memory                 | Speed |
|--------------|---------------------------|------------------------|-------|
| Bucket Check | Reject expired URLs       | Zero                   | O(1)  |
| Bloom Filter | Deduplicate within window | Fixed ~240KB/partition | O(1)  |

**Layer 1** is a coarse filter that rejects expired tokens without any memory cost. **Layer 2** is a fine-grained filter
that catches replays within the validity window.

## Layer 1: Bucket-Based Time Expiry

### How Buckets Work

When generating a tracking URL, the server embeds a **bucket number** derived from the current time:

```
bucket = currentTimeMs / bucketMs   (e.g., minute granularity)
```

The bucket serves two purposes:

1. **Time-limited URLs**: Tracking URLs become invalid after `maxSkew` duration
2. **Unique per window**: Same creative on same page gets different URLs each minute

### Token Generation (ServeRoutes)

```scala
val bucketMs = 60 * 1000L  // 1-minute buckets
val currentBucket = System.currentTimeMillis() / bucketMs

val canonical = Signer.canonical(pub, url, slot, cid, version, bucket, "imp")
val token = Signer.hmac256(canonical, publisherSecret)

// URL: /imp?pub=X&url=Y&slot=Z&cid=C&v=1&b={bucket}&tok={token}
```

### Token Validation (TrackRoutes)

```scala
def freshBucket(b: Long): Boolean = {
  val nowB = System.currentTimeMillis() / bucketMs
  math.abs(nowB - b) <= (maxSkew.toMillis / bucketMs)
}
```

With `bucketMs = 60s` and `maxSkew = 3.minutes`:

- Tokens are valid for buckets within ±3 of current bucket
- Effective validity window: ~6-7 minutes (centered on current time)

### Bucket Check Flow

```
Request: b=1000, maxSkew=3min (3 buckets)

Timeline:
─────────────────────────────────────────────────►
     997   998   999  1000  1001  1002  1003  1004
      │     │     │     │     │     │     │     │
      └─────┴─────┴─────┴─────┴─────┴─────┘     │
              Valid range (±3)                   │
                                           Now (1004)

Result: |1004 - 1000| = 4 > 3 → EXPIRED (403)
```

## Layer 2: Bloom Filter Deduplication

### Why Bloom Filters?

Traditional deduplication stores every seen key → **O(n) memory growth**.

Bloom filters use a fixed-size bit array → **O(1) constant memory**.

Trade-off: Small false positive rate (may reject ~0.1% of legitimate requests), but **no false negatives** (never allows
duplicates through).

### Windowed Bloom Filter Design

Uses dual-bucket bloom filters for time-based expiration:

```
┌─────────────────────────────────────────────────┐
│         WindowedBloomReplayGuard                │
│                                                 │
│   ┌──────────────────┐  ┌──────────────────┐    │
│   │  Current Bucket  │  │  Previous Bucket │    │
│   │  (active adds)   │  │  (read-only)     │    │
│   │                  │  │                  │    │
│   │  ████░░░░░░░░░░  │  │  ███████░░░░░░░  │    │
│   │  bit array       │  │  bit array       │    │
│   └──────────────────┘  └──────────────────┘    │
│           │                      │              │
│           └──────────┬───────────┘              │
│                      ▼                          │
│              check BOTH buckets                 │
│              add to CURRENT only                │
└─────────────────────────────────────────────────┘
```

### Bucket Rotation

Every `bucketMs` milliseconds:

1. Previous bucket is discarded
2. Current bucket becomes previous
3. New empty bucket becomes current

```
Time →

Bucket 0:  ████████████████
                          │ rotate
Bucket 1:  ░░░░░░░░░░░░░░░░  →  ████████████████
                                              │ rotate
Bucket 2:  ░░░░░░░░░░░░░░░░      ░░░░░░░░░░░░  →  ████████
```

### Validation Algorithm

```scala
def processValidation(nonce: Long): (State, Boolean) = {
  // Check BOTH buckets
  if (bloomCurrent.maybeContains(nonce) || bloomPrevious.maybeContains(nonce)) {
    (state, false)  // Replay detected
  } else {
    // Add to current bucket only
    bloomCurrent.add(nonce)
    (state.copy(addsSincePublish = addsSincePublish + 1), true)
  }
}
```

### Nonce Generation

Canonical URL string is hashed to 64-bit nonce using FNV-1a:

```scala
def hash(canonical: String): Long = {
  var h = 0xcbf29ce484222325L  // FNV offset basis
  for (b <- canonical.getBytes(UTF_8)) {
    h ^= (b & 0xff)
    h *= 0x100000001b3L  // FNV prime
  }
  h
}
```

## Cluster Architecture

### Sharded Entities

Bloom filters are partitioned across 64 sharded entities:

```
                     TrackingReplayGuard (router)
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
         ▼                    ▼                    ▼
   ┌───────────┐        ┌───────────┐        ┌───────────┐
   │ replay|0  │        │ replay|1  │   ...  │ replay|63 │
   │           │        │           │        │           │
   │ Bloom     │        │ Bloom     │        │ Bloom     │
   │ Filter    │        │ Filter    │        │ Filter    │
   └───────────┘        └───────────┘        └───────────┘
```

Partition selection:

```scala
def computePartition(nonce: Long, partitions: Int): Int =
  Math.floorMod(nonce.toInt ^ (nonce >> 32).toInt, partitions)
```

### DData Replication

Bloom filter state is replicated via Distributed Data (LWWRegister):

```
┌────────────┐              ┌────────────┐              ┌────────────┐
│   Node A   │  ──gossip──► │   Node B   │  ──gossip──► │   Node C   │
│            │              │            │              │            │
│ replay|5   │              │ replay|5   │              │ replay|5   │
│ (primary)  │              │ (replica)  │              │ (replica)  │
└────────────┘              └────────────┘              └────────────┘
```

On startup, entities load from DData before accepting requests:

1. Send `Get(key, ReadLocal)` to replicator
2. Wait up to `bootMaxWait` (500ms) for response
3. If found, restore from snapshot
4. If timeout/not found, start fresh

Periodic publish (every 10s or 100 adds):

```scala
if (addsSincePublish >= publishMinAdds || forceInitialPublish) {
  val snapshot = SnapshotEnvelope(
    mBits, k, rotatedAtNanos, currentBucket, prevBucket,
    current = bloomCurrent.snapshotWords,
    previous = bloomPrevious.snapshotWords,
    crc32 = computeCrc32(...)
  )
  replicator ! Update(key, LWWRegister(node, snapshot), WriteLocal)
}
```

## Configuration: Single Source of Truth

The timing configuration follows a derivation pattern where bloom filter settings are derived from the URL validity
window:

```scala
// HttpBootstrap.scala

// Single source of truth: URL validity window
val urlValidityWindow = 3.minutes

// TrackRoutes uses it as maxSkew
val trackRoutes = new TrackRoutes(
  secretsRepo,
  eventLog,
  maxSkew = urlValidityWindow,  // ← Derived
  replayGuard = replayGuard
)

// TrackingReplayGuard derives bloom bucket size from it
val replayGuard = TrackingReplayGuard(
  sharding,
  urlValidityWindow = urlValidityWindow,  // ← Same source
  partitions = 64
)
```

Inside TrackingReplayGuard:

```scala
// Bloom bucket = urlValidityWindow + 1 min safety margin
val bloomBucketMs = urlValidityWindow.toMillis + 60_000L

val config = GuardConfiguration(
  bucketMs = bloomBucketMs,  // 4 min (with 3 min validity)
  maxSkew  = urlValidityWindow,
  ...
)
```

### Timing Relationship

```
URL Validity Window = 3 minutes (maxSkew)
URL Validity Span   = 6 minutes (±3 min for clock skew)
Bloom Bucket Size   = 4 minutes (validity + 1 min margin)
Bloom Coverage      = 8 minutes (2 buckets × 4 min)

Timeline:
─────────────────────────────────────────────────────────────────────►
        │←────────── URL Valid Span ──────────►│
        │              6 min (±3)              │
        │                                      │
        │←─── Bloom Current ───►│←─── Bloom Previous ───►│
        │        4 min          │         4 min          │
        │                                                │
        │←─────────────── Bloom Coverage ───────────────►│
                              8 min

Key: Bloom coverage (8 min) > URL validity span (6 min)
     2 minute safety margin for clock skew and rotation timing
```

## Memory Characteristics

### Per-Partition Memory

With default settings (`expectedPerPart = 100,000`, 0.1% FPR):

```
Bits per element: -log₂(0.001) / ln(2) ≈ 14.4 bits
Total bits: 100,000 × 14.4 ≈ 1.44 million bits
Words (64-bit): 1.44M / 64 ≈ 22,500 longs
Bytes: 22,500 × 8 = 180 KB per bucket
Both buckets: 360 KB per partition
```

### Cluster-Wide Memory

```
64 partitions × 360 KB = 23 MB total (across cluster)
```

Memory is **fixed and bounded** regardless of traffic volume.

### Comparison with Stateful Approaches

| Approach             | Memory at 1M requests | Memory at 10M requests |
|----------------------|-----------------------|------------------------|
| HashMap (store keys) | ~100 MB               | ~1 GB                  |
| PNCounterMap (DData) | ~50 MB                | ~500 MB                |
| **Bloom Filter**     | **23 MB**             | **23 MB**              |

## Configuration Reference

### GuardConfiguration

| Parameter         | Default         | Description                                     |
|-------------------|-----------------|-------------------------------------------------|
| `expectedPerPart` | 100,000         | Expected unique nonces per partition per bucket |
| `bucketMs`        | 180,000 (3 min) | Time window per bloom bucket                    |
| `maxSkew`         | 3 minutes       | Maximum timestamp skew tolerance                |
| `publishEvery`    | 10 seconds      | DData publish interval                          |
| `publishMinAdds`  | 100             | Minimum adds before publish                     |
| `bootMaxWait`     | 500 ms          | Max wait for DData on startup                   |

### application.conf

```hocon
promovolve {
  replay-guard {
    enabled = true
    enabled = ${?REPLAY_GUARD_ENABLED}
  }
}
```

## Request Flow Summary

```
1. Client sends: GET /imp?pub=X&url=Y&slot=Z&cid=C&v=1&b=500&tok=abc123

2. TrackRoutes.validate():
   a. Look up publisher secret
   b. Verify HMAC signature
   c. Check bucket freshness: |currentBucket - 500| <= 3?
      └─ No  → 403 Forbidden
      └─ Yes → Continue

3. TrackRoutes.checkReplay():
   a. Hash canonical string to nonce
   b. Ask TrackingReplayGuard.Validate(nonce)

4. TrackingReplayGuard:
   a. Compute partition: nonce → partition 0-63
   b. Forward to WindowedBloomReplayGuard entity

5. WindowedBloomReplayGuard (promovolve.api.limiter):
   a. Check bucket rotation (rotate if needed)
   b. Check bloom filters (current + previous)
      └─ Found → reply false (409 Conflict)
      └─ Not found → add to current, reply true

6. TrackRoutes:
   a. false → 409 Conflict
   b. true  → Log event, 204 No Content
```

## Error Responses

| Status         | Meaning | Cause                           |
|----------------|---------|---------------------------------|
| 204 No Content | Success | Event logged                    |
| 403 Forbidden  | Invalid | Bad signature or expired bucket |
| 409 Conflict   | Replay  | Already seen in bloom filter    |

## Integration Points

### Inbound

- `GET /imp?...` - Impression tracking
- `GET /click?...` - Click tracking

### Outbound

- `EventLog.logImpression()` - Record impression event
- `EventLog.logClick()` - Record click event
- DData gossip for bloom filter replication
