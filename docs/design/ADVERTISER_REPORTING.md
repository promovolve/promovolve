# Advertiser Reporting

**Status: BUILT (2026-07-06) — not yet deployed.** Scoped at the end of the
sweep-validation session; revised same day (site/category/publisher breakdowns
pulled INTO v1 via the dimensional rollup, by-URL dropped, Chart.js adopted);
built the same evening. SQL semantics verified against the dev DB with
synthetic fixtures (ownership gate, dogeared split, category '' fallback,
publisher host-agg, backfill reconciliation). Deploy = api image (projection +
endpoints) + platform image (page) + `scripts/migrations/
2026-07-06-advertiser-report-dim-rollup.sql` run AFTER the api roll.

## Problem

Advertisers currently get only *now*-views: per-campaign lifetime cards on the
stats page (spend today, lifetime impressions/clicks/CTR/eCPM), today's hourly
delivery bars, and lifetime impression/click counters on each creative card.
There is no way to answer "how did my campaign do last week?", compare days,
or take the numbers anywhere else. That's the whole reporting inventory — and
for a self-serve ad platform, a date-ranged report is table stakes.

## Data already available (no new collection needed)

`campaign_daily_stats` (projection DB) holds one row per campaign per UTC day
with the complete funnel:

- `impressions`, `clicks`, `cta_clicks` — the three-tier engagement model
  (viewable → expand → navigate)
- `spend` (DECIMAL 12,4 dollars)
- `folds`, `unfolds`, `dogeared_impressions/_clicks/_cta_clicks` — dog-ear
  engagement (folds are free/unbilled; dog-eared serves are excluded from
  billing, so showing them separately also explains spend-vs-delivery gaps)
- `unique_sites`

Per-site or per-category splits are NOT in this table. `tracking_events` has
`site_id`, `category`, and `url` per event, but it is a TimescaleDB hypertable
with a **30-day retention policy** — raw events cannot serve a 92-day report.
Hence the dimensional rollup below.

## Dimensional rollup (decided 2026-07-06)

Advertisers need splits **by site, by category, and by publisher**. (By-URL
was considered and **dropped** — unbounded cardinality, and raw-event queries
would cap it at the 30-day retention window.)

New projection table, written by `DashboardProjectionHandler` alongside the
existing stats tables:

```sql
CREATE TABLE campaign_dim_daily_stats (
    campaign_id  VARCHAR(100) NOT NULL,
    day_bucket   DATE NOT NULL,
    site_id      VARCHAR(100) NOT NULL,
    category     VARCHAR(100) NOT NULL DEFAULT '',  -- '' = uncategorized serve
    impressions  BIGINT NOT NULL DEFAULT 0,
    clicks       BIGINT NOT NULL DEFAULT 0,
    cta_clicks   BIGINT NOT NULL DEFAULT 0,
    spend        DECIMAL(12, 4) NOT NULL DEFAULT 0,
    dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (campaign_id, day_bucket, site_id, category)
);
```

- One table serves all three cuts: `GROUP BY category`, `GROUP BY site_id`,
  and by-publisher via the `publisher_sites` join (`GROUP BY publisher_id`).
  Site×category comes for free. Cardinality is bounded
  (campaigns × sites-served × matched-categories per day).
- **Spend reconciliation by construction**: written in the same handler, from
  the same event, with the same derivation as `campaign_daily_stats`
  (`spend = cpm / 1000`, `DashboardProjectionHandler.scala:42`; dogeared
  serves bump only `dogeared_impressions`, never spend). Splits always sum to
  the campaign daily row.
- `category` is nullable on the event (unmatched serve) → store `''`, render
  as "Uncategorized".
- **Site display names**: `publisher_sites` maps ids only, and siteId is a
  lossy slug — the report needs the site host. Resolve platform-side (or add
  a `host` column to `publisher_sites` filled by the projection) — decide at
  build time.
- **Backfill**: one-off `INSERT … SELECT … GROUP BY` from whatever is still
  inside the 30-day `tracking_events` window at ship time, bounded by the
  projection's current offset (event_time cutoff) to avoid double-counting
  with the live handler. Gives up to 30 days of dimensional history on day
  one; full 92-day depth accrues from launch.
- Publisher visibility decision: yes, advertisers see real site hosts and
  publisher identities — consistent with the platform's transparency stance,
  and it feeds the existing advertiser site-domain blocklist workflow
  (report row → "block this domain").

## V1 scope

### Core API

`GET /v1/advertisers/{advertiserId}/report?from=YYYY-MM-DD&to=YYYY-MM-DD`

- Resolve the advertiser's campaign ids from the AdvertiserEntity (same ask
  the detail endpoint uses), then one query:
  `SELECT campaign_id, day_bucket, impressions, clicks, cta_clicks, spend,
   folds, dogeared_impressions FROM campaign_daily_stats WHERE campaign_id IN
   (…) AND day_bucket BETWEEN :from AND :to ORDER BY day_bucket DESC`.
  Sanitize ids to `[A-Z0-9]` before interpolation (ULIDs).
- Response: `{advertiserId, from, to, rows:[{day, campaignId, impressions,
  clicks, ctaClicks, spend, dogearedImpressions}]}`. Folds were dropped from
  the row (never shown in the table; dead API fields are hard to remove).
  Campaign *names* resolve platform-side from the campaigns list it already
  fetches — keeps the core row lean and avoids N entity asks.
- Ownership gate: daily/dim stats carry no advertiser_id — rows are filtered
  with the EXISTS-against-campaign_stats idiom (same as DashboardRoutes), so
  the endpoint never leaks cross-tenant rows even if called directly.
- Range guard: cap at 92 days; default last 7 (platform applies the default).

### Platform (dashboard)

New page `/advertiser/report` + nav entry:

- Date-range picker (from/to, default last 7 days including today; presets:
  7d / 30d / this month).
- Totals tiles across the range: Spend, Impressions, Clicks, CTR, CTA clicks,
  eCPM.
- **Range chart** (decided 2026-07-06): daily Spend + Impressions above the
  table, rendered with **Chart.js** — first chart library in the dashboard
  (existing charts are hand-rolled CSS bars / inline SVG; fine at their size,
  but multi-series + tooltips + 92-day axes justify a library). Same rows the
  table shows, no extra query. **Vendor the minified build into
  `platform/static/`** — no CDN (self-hosted OSS), and mind the static/
  deploy trap (build+fanout+commit or Docker cache serves the old image).
  Per-dimension series stay v2; reuse Chart.js for them.
- Table: Day × Campaign rows — Impressions, Clicks, CTR, CTA, Spend, eCPM,
  with dog-eared impressions as a muted "(+N dog-eared)" annotation since
  they're unbilled. Group by day (newest first), subtotal per day when the
  advertiser has >1 campaign.
- **Breakdown tabs** (from the dimensional rollup): By Site, By Category,
  By Publisher — same range, same totals-tiles semantics, each row =
  dimension value × metrics (Impressions, Clicks, CTR, CTA, Spend, eCPM,
  muted dog-eared annotation). Site rows show the site host with a
  "block domain" affordance linking to the existing blocklist.
- **CSV export**: same handler, `?format=csv` streams
  `day,campaign,impressions,clicks,ctr,cta_clicks,spend,ecpm` with the same
  range — no separate endpoint. Breakdown tabs export their own CSV with a
  leading dimension column.
- Empty state: "No delivery in this range."
- Breakdown data before the rollup's backfill horizon is simply absent —
  the tabs state their coverage start date when it's later than the
  requested `from`.

### Consistency rules

- All days are UTC buckets (matches settlement and the billing statements);
  say "UTC" in the header so the advertiser isn't surprised.
- eCPM = spend / impressions × 1000, computed from the row's own numbers.
- CTR = clicks / impressions (expand rate), consistent with the stats page.

## V2 candidates (explicitly out of v1)

- Per-creative daily series (needs a `creative_daily_stats` projection —
  today only lifetime counters exist on creatives).
- Per-dimension time-series (per-category / per-site lines on the range
  chart) — basic spend/impressions chart moved INTO v1 with Chart.js.
- Scheduled export / email.
- Publisher-side equivalent (their stats page has similar gaps).

## Build order (next session)

1. `campaign_dim_daily_stats` table (init-db.sql + migration on cluster) +
   `DashboardProjectionHandler` writes for impression/click/cta_click —
   ship this FIRST so accrual starts even if the UI lags.
2. Backfill script from the surviving `tracking_events` window (offset-bounded).
3. Core endpoints (+ jsonFormat/schema/route wiring) — day×campaign report
   mirrors the category-demand endpoint's shape; breakdown endpoint takes a
   `dim=site|category|publisher` param over the rollup, ~1-2h.
4. Platform handler + template + nav + tabs + CSV + Chart.js range chart
   (vendor lib into static/) — ~2-3h.
5. Deploy needs its own api roll (the benched-badges vehicle already shipped,
   `43f39493`); coordinate with the parked attribution-fix pin.
