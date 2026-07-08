# DData and Remember-Entities Configuration

This document explains the configuration options for Pekko Cluster Sharding's remember-entities feature and Distributed Data (DData) persistence.

## Overview

When using `remember-entities = on`, Pekko Sharding tracks which entity IDs have been active so they can be automatically restarted after a cluster restart. This is separate from the entity's actual state (which is stored via DurableStateBehavior in PostgreSQL).

## Storage Options

### Option 1: DData + LMDB (Current Configuration)

```hocon
pekko.cluster.sharding {
  remember-entities = on
  remember-entities-store = ddata
}

pekko.cluster.distributed-data.durable {
  keys = ["shard-*"]  # Pattern for remember-entities keys
  store-actor-class = "org.apache.pekko.cluster.ddata.LmdbDurableStore"
  lmdb {
    dir = "target/ddata"
    map-size = 1 GiB  # env-overridable (DDATA_LMDB_MAP_SIZE); the ServeIndex
                      # (sites × urls × slots × candidates) fills 100 MiB fast
  }
}
```

**How it works:**
- Entity IDs are stored in Distributed Data (replicated across cluster nodes)
- DData is persisted to LMDB (an embedded key-value store)
- Keys like `shard-campaign-entity-54-4`, `shard-category-bidder-29-4` are stored in `data.mdb`

**LMDB file location:**
```
target/ddata-{system-name}-replicator-{port}/data.mdb
# Example: target/ddata-promovolve-replicator-25520/data.mdb
```

**JVM flags required (Java 21+):**
```bash
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```

**Pros:**
- Fast local reads
- Works independently of external database
- Replicates across cluster nodes via gossip

**Cons:**
- Requires persistent storage per node (PVC in Kubernetes)
- Each node maintains its own LMDB file

### Option 2: EventSourced (Journal-backed)

```hocon
pekko.cluster.sharding {
  remember-entities = on
  remember-entities-store = eventsourced
}

# Requires journal plugin configuration
pekko.persistence.journal.plugin = "jdbc-journal"
```

**How it works:**
- Entity IDs are stored as events in the persistence journal
- Uses the same database as your event-sourced actors

**Pros:**
- No local storage needed - uses existing PostgreSQL
- Simpler Kubernetes deployment (no PVCs needed)

**Cons:**
- Requires EventSourced journal plugin (not just DurableState)
- Additional database load

### Option 3: In-Memory Only (No Persistence)

```hocon
pekko.cluster.sharding {
  remember-entities = on
  remember-entities-store = ddata
}

pekko.cluster.distributed-data.durable {
  keys = []  # Empty - nothing persisted
}
```

**How it works:**
- Entity IDs only exist in memory
- Replicated across cluster nodes while running
- Lost on full cluster restart

**Pros:**
- No persistent storage needed
- Simplest configuration

**Cons:**
- Entities not auto-started after cluster restart
- Must wait for external access to wake entities

## Kubernetes Deployment Considerations

### With LMDB (Option 1)

Use a **StatefulSet** with PersistentVolumeClaims:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: promovolve
spec:
  serviceName: promovolve
  replicas: 3
  template:
    spec:
      containers:
        - name: promovolve
          volumeMounts:
            - name: ddata
              mountPath: /app/target/ddata
          env:
            - name: DDATA_DIR
              value: "/app/target/ddata"
  volumeClaimTemplates:
    - metadata:
        name: ddata
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 1Gi
```

### Without Local Storage (Option 2 or 3)

Can use a **Deployment** instead of StatefulSet:
- Option 2: Configure jdbc-journal for remember-entities
- Option 3: Accept entities wake on-demand after restart

## What Gets Stored Where

| Data | Storage | Survives Restart |
|------|---------|------------------|
| Entity State (campaigns, advertisers) | PostgreSQL (DurableState) | Yes |
| Entity IDs (remember-entities) | LMDB (DData) or Journal | Depends on config |
| CampaignDirectory State | PostgreSQL (DurableState) | Yes |
| CategoryBidder campaign lists | In-memory (ephemeral) | No - rebuilt from CampaignDirectory |

## Aggregator Pattern Pitfall

When using the Aggregator pattern with DurableStateBehavior, be careful about ActorContext access:

```scala
// WRONG - ctx.log accessed from Aggregator's thread
ctx.spawnAnonymous(
  Aggregator[Reply, Command](
    sendRequests = { replyAdapter =>
      ctx.log.info(...)  // Will throw: "Unsupported access to ActorContext"
      categories.foreach { cat =>
        bidderRef(cat) ! Message(replyAdapter)
      }
    },
    ...
  )
)

// CORRECT - pre-compute outside the callback
val refs = categories.map(c => bidderRef(c))  // Compute on actor's thread
ctx.spawnAnonymous(
  Aggregator[Reply, Command](
    sendRequests = { replyAdapter =>
      // Only use pre-computed values here
      refs.foreach { ref =>
        ref ! Message(replyAdapter)
      }
    },
    ...
  )
)
```

The `sendRequests` callback is executed by the Aggregator actor, not the spawning actor. Any closure capturing `ctx` (ActorContext) will fail at runtime.

## Testing Remember-Entities

To verify entities are remembered:

1. Start the server, create campaigns via PlatformSetup
2. Stop the server
3. Restart the server
4. Check logs for entities recovering without external access:
   ```
   Campaign[...] recovered: status=Active, creatives=2
   ```

To clear remembered entities:
```bash
rm -rf target/ddata-promovolve-replicator-*/
```