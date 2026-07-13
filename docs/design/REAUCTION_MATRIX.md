# Re-auction and ServeIndex Update Matrix

This document describes what happens when various events occur in the system.

## Design Principle: Budget Exhaustion Preserves ServeIndex

Budget exhaustion is **temporary** — budgets reset at day rollover. To avoid losing publisher approval status, exhausted campaigns/advertisers are NOT removed from ServeIndex. Instead:

1. TTL is refreshed (entries stay alive through day rollover)
2. The Select path checks budget before serving — exhausted campaigns won't be served
3. On day rollover, budget resets and creatives resume serving immediately

Only **permanent** removal events (pause, suspension) delete from ServeIndex.

## Creative Status Changes

| Event | Remove from ServeIndex | Re-auction | Notes |
|-------|------------------------|------------|-------|
| **Creative Paused** | ✅ Yes | ✅ Yes | `AdServer.CreativePaused` removes creative via `RemoveCreativeBySite`, `reauctionForCampaign` triggers re-auction |
| **Creative Reactivated** | ❌ No | ✅ Yes | `reauctionForCampaign` - creative rejoins auction |

## Campaign Events

| Event | Remove from ServeIndex | Re-auction | Notes |
|-------|------------------------|------------|-------|
| **Campaign Paused** | ✅ Yes (all creatives) | ✅ Yes | `AdServer.CampaignPaused` via `RemoveCampaignBySite` + `reauctionForCampaign` |
| **Campaign Resumed** | ❌ No | ✅ Yes | `PeriodicReauction` (full site re-auction) |
| **Campaign Budget Exhausted** | ❌ No (TTL refreshed) | ✅ Yes | `AdServer.RefreshTTLForCampaign` + `reauctionForCampaign` |
| **Campaign Budget Reset** | ❌ No | ✅ Yes | `reauctionForCampaign` for targeted URLs |
| **Campaign Budget Lowered Below Spend** | ❌ No (TTL refreshed) | ✅ Yes | `CampaignBudgetExhausted` via `UpdateConfig` |
| **Campaign Budget Raised Above Spend** | ❌ No | ✅ Yes | `CampaignBudgetReset` via `UpdateConfig` triggers `reauctionForCampaign` |
| **Campaign CPM Changed** | ❌ No | ✅ Yes | Via `CampaignChanged` event |
| **Campaign Category Changed** | ❌ No | ✅ Yes | Via `CampaignChanged` event |
| **Campaign Narrowed Off Site** (siteAllowlist no longer contains this site) | ✅ Yes (all creatives, INCLUDING reader pins) | ✅ Yes | `AdServer.EvictCampaignFromSite` via `RemoveCampaignBySite`; mirrors Campaign Paused but pins die too (advertiser left the site). Topic-narrow (category dropped, still on site) instead re-evaluates awarded pages per category intersection |

## Advertiser Events

| Event | Remove from ServeIndex | Re-auction | Notes |
|-------|------------------------|------------|-------|
| **Advertiser Budget Exhausted** | ❌ No (TTL refreshed) | ✅ Yes | `AdServer.RefreshTTLForAdvertiser` + `PeriodicReauction` |
| **Advertiser Budget Reset** | ❌ No | ✅ Yes | `PeriodicReauction` (full site re-auction) |
| **Advertiser Budget Lowered Below Spend** | ❌ No (TTL refreshed) | ✅ Yes | `AdvertiserBudgetExhausted` via `UpdateDailyBudget` |
| **Advertiser Budget Raised Above Spend** | ❌ No | ✅ Yes | `AdvertiserBudgetReset` via `UpdateDailyBudget` triggers `PeriodicReauction` |
| **Advertiser Suspended** | ✅ Yes (all campaigns) | ✅ Yes | `AdServer.RemoveAdvertiser` via `RemoveAdvertiserBySite` + `PeriodicReauction` (permanent) |

## Publisher Events

| Event | Remove from ServeIndex | Re-auction | Notes |
|-------|------------------------|------------|-------|
| **Creative Approved** | ❌ No (added) | ❌ No | Creative added to ServeIndex directly |
| **Creative Rejected** | ✅ Yes | ✅ Yes (if queue exhausted) | Next pending promoted, or re-auction |
| **Creative Flagged** | ✅ Yes | ✅ Yes | Added to cuckoo filter (blocked for this site until unflagged), re-auction fills vacancy |
| **Creative Unflagged** | ❌ No | ✅ Yes | Removed from cuckoo filter, can compete in re-auction |
| **Creative Revoked** | ✅ Yes | ❌ No | Removed from ServeIndex only (creative goes back to pending on next auction). Does NOT modify cuckoo filter |
| **Domain Blocked** | ✅ Yes (affected) | ✅ Yes | Blocked creatives removed via `RemoveByDomains` |
| **Ad Product Blocked** | ✅ Yes (affected) | ✅ Yes | Blocked categories removed via `RemoveByAdProductCategories` |

## Code References

### Event Definitions
`modules/core/src/main/scala/promovolve/events/package.scala`
```scala
// Budget events that trigger re-auctions
sealed trait BudgetEvent

final case class CampaignBudgetExhausted(campaignId: CampaignId, timestamp: Instant) extends BudgetEvent
final case class CampaignBudgetReset(campaignId: CampaignId, newBudget: Budget, timestamp: Instant) extends BudgetEvent
final case class AdvertiserBudgetExhausted(advertiserId: AdvertiserId, timestamp: Instant) extends BudgetEvent
final case class AdvertiserBudgetReset(advertiserId: AdvertiserId, newBudget: Budget, timestamp: Instant) extends BudgetEvent
final case class AdvertiserSuspended(advertiserId: AdvertiserId, timestamp: Instant) extends BudgetEvent
final case class CreativeStatusChanged(creativeId: CreativeId, advertiserId: AdvertiserId, campaignId: CampaignId, isActive: Boolean, timestamp: Instant) extends BudgetEvent
```

### AuctioneerEntity Event Handlers
`modules/core/src/main/scala/promovolve/auction/AuctioneerEntity.scala`

**CampaignChanged Handler**
```scala
case event: CampaignEntity.CampaignChanged =>
  if (event.isActive) {
    // Campaign became active or reactivated - trigger full site re-auction
    ctx.self ! PeriodicReauction
  } else {
    // Campaign was paused - remove from ServeIndex and re-auction
    adServer ! AdServer.CampaignPaused(event.campaignId)
    reauctionForCampaign(event.campaignId, "Campaign paused")
  }
```

**CampaignBudgetExhausted Handler**
```scala
case event: CampaignBudgetExhausted =>
  // NOTE: Do NOT remove from ServeIndex. The Select path checks budget before
  // serving. Keeping entries preserves approval status for day rollover.
  // Refresh TTL so entries don't expire before budget resets
  adServer ! AdServer.RefreshTTLForCampaign(event.campaignId)
  // Trigger re-auction so other campaigns can fill the slot
  reauctionForCampaign(event.campaignId, "Campaign budget exhausted")
```

**CampaignBudgetReset Handler**
```scala
case event: CampaignBudgetReset =>
  reauctionForCampaign(event.campaignId, "Campaign budget reset")
```

**AdvertiserBudgetExhausted Handler**
```scala
case event: AdvertiserBudgetExhausted =>
  if (participatingAdvertisers.contains(event.advertiserId)) {
    // NOTE: Do NOT remove from ServeIndex. Refresh TTL instead.
    adServer ! AdServer.RefreshTTLForAdvertiser(event.advertiserId)
    // Trigger full re-auction so other advertisers can fill the slots
    ctx.self ! PeriodicReauction
  }
```

**AdvertiserBudgetReset Handler**
```scala
case event: AdvertiserBudgetReset =>
  if (participatingAdvertisers.contains(event.advertiserId)) {
    ctx.self ! PeriodicReauction
  }
```

**AdvertiserSuspended Handler**
```scala
case event: AdvertiserSuspended =>
  if (participatingAdvertisers.contains(event.advertiserId)) {
    // Permanent removal - remove all advertiser's creatives from ServeIndex
    adServer ! AdServer.RemoveAdvertiser(event.advertiserId)
    ctx.self ! PeriodicReauction
  }
```

**CreativeStatusChanged Handler**
```scala
case event: CreativeStatusChanged =>
  if (participatingCampaigns.contains(event.campaignId)) {
    if (event.isActive) {
      // Creative reactivated - trigger campaign re-auction
      reauctionForCampaign(event.campaignId, "Creative reactivated")
    } else {
      // Creative paused - remove from ServeIndex AND trigger re-auction
      adServer ! AdServer.CreativePaused(event.creativeId)
      reauctionForCampaign(event.campaignId, "Creative paused")
    }
  }
```

### AdServer Protocol Commands
`modules/core/src/main/scala/promovolve/publisher/delivery/Protocol.scala`
```scala
/** Notifies AdServer that a campaign has been paused - remove its creatives from ServeIndex */
final case class CampaignPaused(campaignId: CampaignId) extends Command

/** Notifies AdServer that a creative has been paused - remove it from ServeIndex */
final case class CreativePaused(creativeId: CreativeId) extends Command

/** Notifies AdServer that a creative has been reactivated - trigger campaign re-auction */
final case class CreativeReactivated(creativeId: CreativeId, campaignId: CampaignId) extends Command

/** Notifies AdServer to remove all creatives from an advertiser (used for suspension/closure) */
final case class RemoveAdvertiser(advertiserId: AdvertiserId) extends Command

/** Notifies AdServer to refresh TTL for a campaign's entries (used when budget exhausts) */
final case class RefreshTTLForCampaign(campaignId: CampaignId) extends Command

/** Notifies AdServer to refresh TTL for an advertiser's entries (used when budget exhausts) */
final case class RefreshTTLForAdvertiser(advertiserId: AdvertiserId) extends Command
```

### AdServer Command Handlers
`modules/core/src/main/scala/promovolve/publisher/delivery/AdServer.scala`

**CampaignPaused Handler**
```scala
case Protocol.CampaignPaused(campaignId, revokeApprovals) =>
  // Remove creatives from paused campaign from ALL slots for this site
  serveIndex ! ServeIndexDData.RemoveCampaignBySite(siteId.value, campaignId)
  // Also remove from pending queue
  store.removeByCampaignId(siteId.value, campaignId.value)
  // Approvals are revoked ONLY on an explicit user pause
  // (revokeApprovals=true, set by the CampaignEntity.UpdateStatus topic
  // event) — revoke routes via AdvertiserEntity.RevokeCreativeApproval so
  // the creative returns to PENDING on resume. Every other sender keeps
  // approvals; revoking on scope-blind CampaignChanged churn re-creates
  // the "approval queue is gone" cascade (0e1304c4).
  if (revokeApprovals) { /* announce + revoke, see AdServer.scala */ }
```

**CreativePaused Handler**
```scala
case CreativePaused(creativeId) =>
  // Remove specific creative from ALL slots for this site
  serveIndex ! ServeIndexDData.RemoveCreativeBySite(siteId.value, creativeId)
  // Also remove from pending queue and persisted approvals
  store.removeCreativeFromAll(siteId.value, creativeId.value)
  store.deleteApproved(siteId.value, creativeId.value)
```

**RemoveAdvertiser Handler** (permanent - for suspension)
```scala
case RemoveAdvertiser(advertiserId) =>
  // Remove all creatives from ALL slots for this site
  serveIndex ! ServeIndexDData.RemoveAdvertiserBySite(siteId.value, advertiserId)
  // Also remove from pending queue and persisted approvals
  store.removeByAdvertiserId(siteId.value, advertiserId.value)
  store.deleteApprovedByAdvertiserId(siteId.value, advertiserId.value)
```

**RefreshTTLForCampaign Handler** (temporary - for budget exhaustion)
```scala
case RefreshTTLForCampaign(campaignId) =>
  // Use inverted index for targeted TTL refresh
  val keys = keysByCampaign.getOrElse(campaignId, Set.empty)
  val newTtlMs = (dayDurationSeconds * 1.1 * 1000).toLong
  keys.foreach { key =>
    serveIndex ! ServeIndexDData.RefreshTTLForKey(key, newTtlMs)
  }
```

**RefreshTTLForAdvertiser Handler** (temporary - for budget exhaustion)
```scala
case RefreshTTLForAdvertiser(advertiserId) =>
  // Use inverted index for targeted TTL refresh
  val keys = keysByAdvertiser.getOrElse(advertiserId, Set.empty)
  val newTtlMs = (dayDurationSeconds * 1.1 * 1000).toLong
  keys.foreach { key =>
    serveIndex ! ServeIndexDData.RefreshTTLForKey(key, newTtlMs)
  }
```

### CampaignEntity Budget Event Publishing
`modules/core/src/main/scala/promovolve/advertiser/CampaignEntity.scala`

**Budget Exhaustion**
```scala
// In TryReserve handler, after successful reservation:
if (wasWithin && !nowWithin) {
  budgetEventTopic ! Topic.Publish(
    events.CampaignBudgetExhausted(campaignId, Instant.now())
  )
}
```

**Budget Reset**
```scala
budgetEventTopic ! Topic.Publish(
  events.CampaignBudgetReset(campaignId, newBudget, ts)
)
```

### AdvertiserEntity Budget Event Publishing
`modules/core/src/main/scala/promovolve/advertiser/AdvertiserEntity.scala`

**Budget Exhaustion**
```scala
if (wasWithin && !nowWithin) {
  budgetEventTopic ! Topic.Publish(
    events.AdvertiserBudgetExhausted(state.advertiserId, ts)
  )
}
```

**Budget Reset**
```scala
budgetEventTopic ! Topic.Publish(
  events.AdvertiserBudgetReset(state.advertiserId, newState.dailyBudget, Instant.now())
)
```

## Flow Diagrams

### Creative Paused Flow
```
User clicks "Pause" in UI
       │
       ▼
API: PATCH /creatives/{id}/status
       │
       ├─► CreativeRepo.updateStatus(Paused)
       │
       └─► AdvertiserEntity.UpdateCreativeActiveStatus(campaignId, isActive=false)
                 │
                 ├─► Creative.isActive = false
                 │   (Excluded from future auctions via isEligibleFor)
                 │
                 └─► Publish CreativeStatusChanged event (from entity)
                           │
                           ▼
                     AuctioneerEntity receives event
                           │
                           ├─► AdServer.CreativePaused(creativeId)
                           │         │
                           │         ├─► ServeIndexDData.RemoveCreativeBySite
                           │         │
                           │         └─► PendingSelectionStore.removeCreativeFromAll
                           │             (removes from pending queue)
                           │
                           └─► reauctionForCampaign
                                     │
                                     ▼
                               Re-auction for affected URLs
                               (Other campaigns can win)
```

### Campaign Paused Flow
```
User pauses campaign
       │
       ▼
CampaignEntity: status = Paused
       │
       └─► Publish CampaignChanged event (isActive=false)
                 │
                 ▼
           AuctioneerEntity receives event
                 │
                 ├─► AdServer.CampaignPaused(campaignId)
                 │         │
                 │         ├─► ServeIndexDData.RemoveCampaignBySite
                 │         │   (Removes ALL creatives for campaign from ALL slots)
                 │         │
                 │         └─► PendingSelectionStore.removeByCampaignId
                 │             (removes from pending queue)
                 │
                 └─► reauctionForCampaign
                           │
                           ▼
                     Re-auction for affected URLs
```

### Campaign Resumed Flow
```
User resumes campaign
       │
       ▼
CampaignEntity: status = Active
       │
       └─► Publish CampaignChanged event (isActive=true)
                 │
                 ▼
           AuctioneerEntity receives event
                 │
                 └─► ctx.self ! PeriodicReauction
                           │
                           ▼
                     Full site re-auction
                     (Campaign can bid on all recent pages)
```

### Campaign Budget Exhausted Flow (TTL Refresh, NOT removal)
```
CampaignEntity.TryReserve
       │
       ├─► Reserve succeeds, but budget now exhausted
       │
       └─► Publish CampaignBudgetExhausted event
                 │
                 ▼
           AuctioneerEntity receives event
                 │
                 ├─► AdServer.RefreshTTLForCampaign(campaignId)
                 │         │
                 │         └─► ServeIndexDData.RefreshTTLForKey (per key)
                 │             Entries STAY in ServeIndex with extended TTL
                 │             (Select path checks budget → won't serve)
                 │
                 └─► reauctionForCampaign
                           │
                           ▼
                     Re-auction for affected URLs
                     (Other campaigns can fill the slots)
```

### Advertiser Budget Exhausted Flow (TTL Refresh, NOT removal)
```
AdvertiserEntity.RecordCampaignSpend
       │
       ├─► Spend recorded, budget now exhausted
       │
       └─► Publish AdvertiserBudgetExhausted event
                 │
                 ▼
           AuctioneerEntity receives event
           (only if participatingAdvertisers contains advertiserId)
                 │
                 ├─► AdServer.RefreshTTLForAdvertiser(advertiserId)
                 │         │
                 │         └─► ServeIndexDData.RefreshTTLForKey (per key)
                 │             Entries STAY in ServeIndex with extended TTL
                 │             (Select path checks budget → won't serve)
                 │
                 └─► ctx.self ! PeriodicReauction
                           │
                           ▼
                     Full site re-auction
                     (Other advertisers can fill the slots)
```

### Advertiser Suspended Flow (permanent removal)
```
Admin suspends advertiser
       │
       ▼
Publish AdvertiserSuspended event
       │
       ▼
AuctioneerEntity receives event
       │
       ├─► AdServer.RemoveAdvertiser(advertiserId)
       │         │
       │         ├─► ServeIndexDData.RemoveAdvertiserBySite
       │         │   (Removes ALL creatives for ALL campaigns, permanently)
       │         │
       │         └─► PendingSelectionStore.removeByAdvertiserId
       │
       └─► ctx.self ! PeriodicReauction
```

### Manual Budget Lowered Below Spend Flow (Advertiser)
```
User lowers advertiser budget via API
       │
       ▼
AdvertiserEntity.UpdateDailyBudget
       │
       ├─► wasWithin = spendToday < oldBudget
       │
       ├─► nowWithin = spendToday < newBudget
       │
       └─► If wasWithin && !nowWithin:
                 │
                 └─► Publish AdvertiserBudgetExhausted event
                           │
                           ▼
                     AuctioneerEntity receives event
                           │
                           ├─► AdServer.RefreshTTLForAdvertiser
                           │         │
                           │         └─► ServeIndexDData.RefreshTTLForKey (per key)
                           │             (Entries stay, TTL extended)
                           │
                           └─► ctx.self ! PeriodicReauction
```

### Manual Budget Raised Above Spend Flow (Advertiser)
```
User raises advertiser budget via API
       │
       ▼
AdvertiserEntity.UpdateDailyBudget
       │
       ├─► wasWithin = spendToday < oldBudget (false when exhausted)
       │
       ├─► nowWithin = spendToday < newBudget (true after raise)
       │
       └─► If !wasWithin && nowWithin:
                 │
                 └─► Publish AdvertiserBudgetReset event
                           │
                           ▼
                     AuctioneerEntity receives event
                           │
                           └─► ctx.self ! PeriodicReauction
                                     │
                                     ▼
                               Full site re-auction
                               (Advertiser can bid again)
```

### Manual Budget Lowered Below Spend Flow (Campaign)
```
User lowers campaign budget via API
       │
       ▼
CampaignEntity.UpdateConfig(dailyBudget = Some(newLowerBudget))
       │
       ├─► wasWithin = totalSpend < oldBudget
       │
       ├─► nowWithin = totalSpend < newBudget
       │
       └─► If budgetChanged && wasWithin && !nowWithin:
                 │
                 └─► Publish CampaignBudgetExhausted event
                           │
                           ▼
                     AuctioneerEntity receives event
                           │
                           ├─► AdServer.RefreshTTLForCampaign
                           │         │
                           │         └─► ServeIndexDData.RefreshTTLForKey (per key)
                           │             (Entries stay, TTL extended)
                           │
                           └─► reauctionForCampaign
```

### Manual Budget Raised Above Spend Flow (Campaign)
```
User raises campaign budget via API
       │
       ▼
CampaignEntity.UpdateConfig(dailyBudget = Some(newHigherBudget))
       │
       ├─► wasWithin = totalSpend < oldBudget (false when exhausted)
       │
       ├─► nowWithin = totalSpend < newBudget (true after raise)
       │
       └─► If budgetChanged && !wasWithin && nowWithin:
                 │
                 └─► Publish CampaignBudgetReset event
                           │
                           ▼
                     AuctioneerEntity receives event
                           │
                           └─► reauctionForCampaign
                                     │
                                     ▼
                               Re-auction for affected URLs
                               (Campaign can bid again)
```

## Day Rollover

When a new day begins, budgets reset and exhausted campaigns can bid again.

### Two Modes of Operation

| Mode | Day Duration | Rollover Detection | Who Resets Campaigns |
|------|--------------|-------------------|---------------------|
| **Real Calendar Days** | 86400 seconds | Midnight UTC crossing | CampaignEntity/AdvertiserEntity (self-managed via `CheckWindowRoll`) |
| **Simulated Short Days** | < 86400 seconds | Elapsed time >= dayDurationSeconds | AdServer sends `ResetDayStart` to participating campaigns |

### Real Calendar Day Rollover Flow
```
Midnight UTC passes
       │
       ├─► CampaignEntity.CheckWindowRoll (periodic timer)
       │         │
       │         ├─► Detects epochDay changed
       │         │
       │         ├─► Resets: spendToday = 0, lastResetInstant = now
       │         │
       │         └─► If budget WAS exhausted:
       │                   │
       │                   └─► Publish CampaignBudgetReset event
       │                             │
       │                             ▼
       │                       AuctioneerEntity receives event
       │                             │
       │                             └─► reauctionForCampaign
       │                                 (ServeIndex entries still valid - TTL was refreshed)
       │
       └─► AdvertiserEntity.RecordCampaignSpend (on next spend)
                 │
                 ├─► Detects epochDay changed
                 │
                 ├─► Resets: spendToday = 0, lastResetEpochDay = today
                 │
                 └─► If budget WAS exhausted:
                           │
                           └─► Publish AdvertiserBudgetReset event
                                     │
                                     ▼
                               AuctioneerEntity receives event
                                     │
                                     └─► ctx.self ! PeriodicReauction
                                         (ServeIndex entries still valid - TTL was refreshed)
```

### AdServer Day Rollover Detection (During Select)
```
Select request arrives
       │
       ├─► Check: has day rolled over?
       │     │
       │     ├─► Real days: today.isAfter(lastDay) in UTC
       │     └─► Simulated: elapsedSeconds >= dayDurationSeconds
       │
       └─► If rollover detected:
                 │
                 ├─► pacingStrategy.reset()
                 ├─► trafficObserver.reset()
                 ├─► trafficShapeTracker.rolloverDay(0.2)  // Blend into history
                 ├─► Reset local spendInfoCache (todaySpend = 0)
                 ├─► Reset serveStats
                 │
                 └─► Simulated days only:
                           │
                           ├─► CampaignEntity.ResetDayStart (for each participating campaign)
                           └─► AdvertiserEntity.ResetDayStart (for each participating advertiser)
```

### What Gets Reset at Day Rollover

| Component | What Resets | Notes |
|-----------|-------------|-------|
| **CampaignEntity** | `spendToday = 0`, `lastResetInstant = now` | Publishes `CampaignBudgetReset` if was exhausted |
| **AdvertiserEntity** | `spendToday = 0`, `lastResetEpochDay = today` | Publishes `AdvertiserBudgetReset` if was exhausted |
| **AdServer.spendInfoCache** | `todaySpend = 0`, `dayStart = now` | Local cache reset |
| **AdServer.serveStats** | All counters to 0 | Fresh stats for new day |
| **AdServer.pendingSpendByCampaign** | Cleared to empty | In-flight spend tracking |
| **PacingStrategy** | Internal state | Fresh pacing for new day |
| **TrafficObserver** | Request rate tracking | Fresh rate tracking |
| **TrafficShapeTracker** | Blends today → history (20%/80%) | Learns traffic patterns over time |

### Budget Reset Events and Re-auctions

When a budget that was **exhausted** yesterday resets:

1. **CampaignBudgetReset** → `reauctionForCampaign` (targeted re-auction for affected URLs)
2. **AdvertiserBudgetReset** → `PeriodicReauction` (full site re-auction)

Creatives resume serving immediately because their ServeIndex entries were preserved (TTL was refreshed during exhaustion). No re-approval needed.

### Code References

**CampaignEntity CheckWindowRoll**
```scala
case CheckWindowRoll =>
  val today = LocalDate.now(ZoneOffset.UTC).toEpochDay
  val needsRoll = epochDayOf(state.lastResetInstant) != today

  if (needsRoll && !alreadyRolled) {
    val wasExhausted = !withinBudget(state)
    // ... roll window, reset spend ...
    if (wasExhausted && state.status == Status.Active) {
      budgetEventTopic ! Topic.Publish(
        events.CampaignBudgetReset(campaignId, newState.dailyBudget, ts)
      )
    }
  }
```

**AdvertiserEntity RecordCampaignSpend**
```scala
case RecordCampaignSpend(campaignId, amount, flushId, ts, replyTo) =>
  val today = LocalDate.ofInstant(ts, ZoneOffset.UTC).toEpochDay
  val needsRoll = state.lastResetEpochDay != today
  val wasExhausted = !state.withinBudget
  val rolledState = if (needsRoll) state.rollWindow(today) else state

  // After persist:
  if (needsRoll && wasExhausted) {
    budgetEventTopic ! Topic.Publish(
      events.AdvertiserBudgetReset(state.advertiserId, persistedState.dailyBudget, ts)
    )
  }
```

**AdServer Select day rollover detection**
```scala
val shouldRollover = if (dayDurationSeconds == 86400) {
  // Real calendar days: check if wall clock crossed midnight UTC
  val lastDay = dayStart.atZone(java.time.ZoneOffset.UTC).toLocalDate
  val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
  today.isAfter(lastDay)
} else {
  // Simulated short days: check elapsed time
  val elapsedSeconds = (nowMs - dayStart.toEpochMilli) / 1000.0
  elapsedSeconds >= dayDurationSeconds
}
```

## Re-auction Scoping

### reauctionForCampaign (Targeted)
Used when only a single campaign is affected. Re-auctions only URLs where that campaign has previously participated.

```scala
private def reauctionForCampaign(campaignId: CampaignId, reason: String): Unit =
  participatingCampaigns.get(campaignId) match {
    case Some(affectedUrls) =>
      val validUrls = affectedUrls.filter(lastPage.contains)
      validUrls.foreach { url => ctx.self ! Reevaluate(url) }
    case None => // Campaign not tracked, skip
  }
```

### PeriodicReauction (Full Site)
Used when multiple campaigns may be affected or when a campaign has no tracked URLs.

```scala
case PeriodicReauction =>
  val recentPages = lastPage.filter { case (_, (_, _, classifiedAt)) =>
    classifiedAt.isAfter(cutoff) // Within classificationFreshnessWindow
  }
  recentPages.foreach { case (url, _) => ctx.self ! Reevaluate(url) }
```

Note: "Full site" is scoped to THIS publisher only (AuctioneerEntity is per-site) and filtered to pages within `classificationFreshnessWindow` (default 48h).

## Testing Checklist

### Creative/Campaign/Advertiser Events
- [ ] Pause creative → Removed from ServeIndex via `RemoveCreativeBySite`
- [ ] Pause creative → Re-auction triggers for affected URLs
- [ ] Pause creative → Other campaign's creative now serves
- [ ] Reactivate creative → Re-auction triggers
- [ ] Reactivate creative → Creative can win and serve again
- [ ] Pause campaign → All creatives removed via `RemoveCampaignBySite`
- [ ] Pause campaign → Re-auction triggers for affected URLs
- [ ] Resume campaign → Full site re-auction via `PeriodicReauction`
- [ ] Campaign budget exhausted → TTL refreshed (NOT removed), re-auction triggers
- [ ] Campaign budget reset → Re-auction triggers, creatives resume serving immediately
- [ ] Advertiser budget exhausted → TTL refreshed (NOT removed), full site re-auction triggers
- [ ] Advertiser budget reset → Full site re-auction triggers
- [ ] Advertiser suspended → All creatives removed via `RemoveAdvertiserBySite` (permanent)

### Manual Budget Changes
- [ ] Lower advertiser budget below spend → `AdvertiserBudgetExhausted` published
- [ ] Lower advertiser budget below spend → TTL refreshed (entries stay in ServeIndex)
- [ ] Raise advertiser budget above spend (when was exhausted) → `AdvertiserBudgetReset` published
- [ ] Raise advertiser budget above spend → Full site re-auction triggers
- [ ] Lower campaign budget below spend → `CampaignBudgetExhausted` published
- [ ] Lower campaign budget below spend → TTL refreshed (entries stay in ServeIndex)
- [ ] Raise campaign budget above spend (when was exhausted) → `CampaignBudgetReset` published
- [ ] Raise campaign budget above spend → `reauctionForCampaign` triggers

### Publisher Approval Actions
- [ ] Revoke creative → Removed from ServeIndex only (no cuckoo filter change)
- [ ] Flag creative → Added to cuckoo filter (blocked for this site until unflagged)
- [ ] Unflag creative → Removed from cuckoo filter, eligible for re-auction

### Day Rollover
- [ ] Real day rollover → CampaignEntity detects via `CheckWindowRoll`
- [ ] Real day rollover → AdvertiserEntity detects via `RecordCampaignSpend`
- [ ] Exhausted campaign budget resets → `CampaignBudgetReset` published
- [ ] Exhausted advertiser budget resets → `AdvertiserBudgetReset` published
- [ ] Budget reset events → Trigger re-auctions
- [ ] Exhausted campaigns resume serving immediately (ServeIndex entries preserved)
- [ ] AdServer spendInfoCache reset (todaySpend = 0)
- [ ] AdServer serveStats reset for new day
- [ ] PacingStrategy reset for new day
- [ ] TrafficShapeTracker blends today → history
- [ ] Simulated day rollover → AdServer sends `ResetDayStart` to campaigns
