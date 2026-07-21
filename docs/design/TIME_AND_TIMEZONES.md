# Time & Timezones

**Status: BUILT INCREMENTALLY, COMPLETE 2026-07-21. Everything an
advertiser or publisher sees dated is denominated in their own account
timezone; UTC survives only in operator/engine-facing surfaces and in
storage. Report history was reset with the final rollout (analytics
rollups only — accounts, credentials, preferences, ledgers, and lifetime
stats were preserved).**

Why this document exists: "what timezone is this number in?" has a
different answer per surface unless the system commits to one rule.
Promovolve's rule, arrived at the same way Google Ads and Meta arrived
at theirs, is:

> **Instants are stored in UTC. Days are labeled in the account's
> timezone.** One clock per org, used everywhere a date appears.

## The two timezones (don't confuse them)

| | Org timezone (`orgs.timezone`) | User preference (`platform_users.timezone`) |
|---|---|---|
| Scope | The whole org — one clock for its money and reports | One user's rendering preference |
| Set by | Seeded from the requester's browser at signup; changed only by the platform operator | The user, on `/account/preferences` |
| Affects | Budget rollover, settlement days, spend-today, imp share, campaign schedule, report day buckets | Display formatting of some timestamps only |
| Empty value | UTC | UTC |

Everything below is about the **org timezone**. The user preference can
never move a bucket, a budget, or a schedule.

## Where the org timezone comes from

1. **Signup seed** — the request-account form silently submits the
   browser's IANA zone (`Intl.DateTimeFormat().resolvedOptions().timeZone`).
   The server validates it (`time.LoadLocation`); anything invalid
   degrades to empty without an error. It rides on the pending user row
   until approval, and when approval creates the org, it becomes the
   org's timezone. Empty falls back to the operator's
   `default_org_timezone` platform setting.
2. **Operator override** — the admin users page can change any org's
   timezone afterward. This is deliberately operator-only: moving the
   clock moves billing days. (Compare: Google Ads allows one timezone
   change ever; Meta closes and recreates the ad account. Our seam-day
   semantics below are the gentle version.)
3. **Cascade** — on approval and on operator change, the zone is pushed
   to the core `AdvertiserEntity` (`pushOrgTimezone`) so pacing follows
   without a restart. Publisher-side consumers read the zone on demand
   (query parameter or the projection's lookup) rather than holding a
   copy.

## What happens when the timezone changes

- **Nothing is re-bucketed.** History keeps the day labels it was
  written under.
- **The change day is a seam**: one local day shorter or longer than
  24 h. Budget windows already behave this way ("the change day gets one
  shortened or extended budget window") — reports and settlement match.
- **Propagation lag ≤ 10 minutes** for report buckets (the projection's
  timezone cache TTL). Billing is unaffected by the lag; it reads the
  zone through its own path at settlement time.
- No event is ever lost or double-counted: every event lands in exactly
  one bucket per owner, before and after the change.

## Surface-by-surface inventory

### Account-local (the rule)

| Surface | Mechanism |
|---|---|
| Budget rollover / pacing day | Core `CampaignEntity` windows on the entity's zone (`PACING_ZONE_AWARE`, pushed from the org) |
| Settlement days (both sides) | Local-day settler: per-entity instant-chained windows; advertiser and publisher sides of the same events settle on each side's own days and meet at the clearing account |
| Spend today / hourly chart (advertiser) | Query-time window over raw `tracking_events` from the account zone's midnight (`advertiserTodayWindow`) |
| Imp share / win rates | Same query-time window; share = campaign imps ÷ all network imps since *your* midnight |
| Revenue today (publisher) | Same idea; platform passes `?tz=` from the org |
| Campaign schedule start/end | Wall-clock input interpreted in the account zone, stored as a UTC instant, displayed back in the account zone with the zone abbreviation |
| **Report day buckets (both sides)** | **Write-time labeling in the projection — see next section** |
| Report range picker / presets / "today" cap | Computed in the account zone server-side |

### Deliberately UTC (engine/operator surfaces)

| Surface | Why |
|---|---|
| All stored timestamps (`event_time`, `hour_bucket`, `*_at`) | Instants are zone-free; UTC is the storage convention |
| Traffic-shape learning | Engine-internal; shapes are learned per UTC hour |
| Floor decision journal / site-observations hourly profile | Operator-facing diagnostics, labeled UTC in the UI |
| Market-rate "last N days" | Rolling window over instants — timezone-irrelevant |
| Admin/ops tooling | Operators correlate across orgs; one clock |

## Report buckets: why write-time, and why two day columns

The "today" tiles shift their window at **query time** — possible only
because they read raw, timestamped events. Reports can't do that:

1. **Aggregation destroys the hour.** Rollup tables store pre-summed
   day rows ("Jul 20: 99 impressions"). Once events are summed into a
   UTC day, no query can recover which of them happened before 09:00
   JST. `AT TIME ZONE` shifts timestamps; it cannot un-sum an aggregate.
2. **Retention forces rollups.** Raw `tracking_events` are deleted
   after 30 days (Timescale retention policy); reports span 92. The
   rollups exist precisely to outlive the raw data — so the day
   boundary must be chosen when the row is written.

Therefore `DashboardProjectionHandler` labels days at write time, in the
**owner's** zone, resolved from the shared `orgs` table (10-minute
cache; a failed lookup logs a warning and falls back to UTC — bucketing
can never stall event processing).

**One event has two owners with two different days.** An impression at
16:30 UTC is *tomorrow* for a Tokyo advertiser and *today* for a
New York publisher. `campaign_daily_stats` (advertiser-owned) carries
the advertiser's day. `campaign_dim_daily_stats` — which serves BOTH
the advertiser breakdowns and the publisher report — carries both:

```
campaign_dim_daily_stats
  day_bucket      DATE  -- the ADVERTISER's local day
  pub_day_bucket  DATE  -- the PUBLISHER's local day
  PRIMARY KEY (campaign_id, day_bucket, pub_day_bucket, site_id, category)
```

Advertiser report queries group by `day_bucket`; publisher report
queries group by `pub_day_bucket`. Each side's report therefore agrees
with its own wallet/earnings statements — the same guarantee, per side,
from one row. Both columns sit in the PK because a single event near
either midnight can split keys that agree on the other side's day.

In the wild (advertiser on platform A, publisher on platform B) this
same split is why cross-platform reconciliation is famously off by a
day at the edges. Here both sides live in one system reading one event
stream, so each side is internally exact.

## Consistency invariants (what "correct" means here)

1. **One clock per org.** Every dated surface an org sees uses
   `orgs.timezone`. No surface mixes clocks.
2. **Instants never move.** Timezone logic only ever chooses a DATE
   label; no stored timestamp is rewritten or stored "in local time".
3. **Reports reconcile with statements.** An org's report day N covers
   the same events as its statement day N (post-2026-07-21 data; the
   seam-day exception around an operator timezone change applies to
   both equally).
4. **Bucketing never blocks serving or billing.** Timezone lookups are
   cached, fall back to UTC, and log — the projection cannot stall on
   them, and billing does not depend on them.

## Operational notes

- **Fresh installs** get the dual-day schema from `docker/init-db.sql`.
  The 2026-07-21 production cutover ran a manual migration (truncate
  `campaign_daily_stats`, rebuild `campaign_dim_daily_stats`) — report
  history was declared disposable; ledgers and lifetime stats untouched.
- **Self-hosters with a split DB**: the projection reads `orgs` and
  `publisher_sites` over the core's JDBC connection. If the platform's
  tables aren't visible there, every bucket silently becomes UTC — the
  handler logs `Owner-timezone lookup failed` once per cache TTL; watch
  for it after topology changes.
- **DST**: handled by the zone database — a local day is whatever the
  zone says it is (23 h or 25 h on transition days). The PK admits the
  resulting splits; sums stay exact.
- **Org-less entities** (legacy/dev) have no org row and bucket in UTC
  by the fallback. That is also the pre-2026-07 behavior, so their
  numbers are continuous.
