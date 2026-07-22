-- PromoVolve PostgreSQL Schema
-- Run on first startup via docker-entrypoint-initdb.d

-- ========================================
-- TimescaleDB Extension
-- ========================================
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ========================================
-- Pekko Persistence: Durable State
-- ========================================
CREATE TABLE IF NOT EXISTS durable_state (
    global_offset BIGSERIAL,
    persistence_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    state_payload BYTEA NOT NULL,
    state_ser_id INTEGER NOT NULL,
    state_ser_manifest VARCHAR(255),
    tag VARCHAR(255),
    state_timestamp BIGINT NOT NULL,
    PRIMARY KEY (persistence_id)
);

CREATE INDEX IF NOT EXISTS durable_state_tag_idx ON durable_state(tag);
CREATE INDEX IF NOT EXISTS durable_state_global_offset_idx ON durable_state(global_offset);
CREATE INDEX IF NOT EXISTS durable_state_persistence_id_prefix_idx ON durable_state(persistence_id varchar_pattern_ops);
CREATE INDEX IF NOT EXISTS durable_state_timestamp_idx ON durable_state(state_timestamp DESC);

-- ========================================
-- Pekko Persistence: Snapshots
-- ========================================
CREATE TABLE IF NOT EXISTS snapshot (
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    created BIGINT NOT NULL,
    snapshot BYTEA NOT NULL,
    metadata BYTEA,
    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS snapshot_created_idx ON snapshot(persistence_id, created);

-- ========================================
-- Pekko Persistence: Event Journal
-- ========================================
CREATE TABLE IF NOT EXISTS event_journal (
    ordering BIGSERIAL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    deleted BOOLEAN DEFAULT FALSE NOT NULL,
    writer VARCHAR(255) NOT NULL,
    write_timestamp BIGINT NOT NULL,
    adapter_manifest VARCHAR(255) NOT NULL,
    event_ser_id INTEGER NOT NULL,
    event_ser_manifest VARCHAR(255) NOT NULL,
    event_payload BYTEA NOT NULL,
    meta_ser_id INTEGER,
    meta_ser_manifest VARCHAR(255),
    meta_payload BYTEA,
    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS event_journal_ordering_idx ON event_journal(ordering);

-- ========================================
-- Entity Registry
-- ========================================
-- Maps campaign_id → advertiser_id
CREATE TABLE IF NOT EXISTS advertiser_campaigns (
    advertiser_id VARCHAR(100) NOT NULL,
    campaign_id   VARCHAR(100) NOT NULL PRIMARY KEY,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_advertiser_campaigns_adv ON advertiser_campaigns(advertiser_id);

-- Maps site_id → publisher_id. host = the site's real domain for display
-- (site_id is a lossy slug); '' for rows created before the column existed
-- and not yet healed by the report backfill or a site re-registration.
CREATE TABLE IF NOT EXISTS publisher_sites (
    publisher_id VARCHAR(100) NOT NULL,
    site_id      VARCHAR(100) NOT NULL PRIMARY KEY,
    host         VARCHAR(255) NOT NULL DEFAULT '',
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_publisher_sites_pub ON publisher_sites(publisher_id);

-- ========================================
-- Tracking Events Journal (TimescaleDB Hypertable)
-- ========================================
CREATE TABLE IF NOT EXISTS tracking_events (
    sequence_nr    BIGSERIAL,
    event_type     VARCHAR(20) NOT NULL, -- impression | click | cta_click | fold | unfold
    event_time     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    site_id        VARCHAR(100) NOT NULL,
    campaign_id    VARCHAR(100),
    advertiser_id  VARCHAR(100),
    creative_id    VARCHAR(100) NOT NULL,
    category       VARCHAR(100),
    -- Request-hygiene mark (fraud Layer 0/1): why this event is excluded
    -- from money and learning. NULL = clean; consumers filter
    -- `suspect_reason IS NULL`. Existing DBs:
    -- ALTER TABLE tracking_events ADD COLUMN suspect_reason VARCHAR(20);
    suspect_reason VARCHAR(20),
    cpm            DECIMAL(10, 4),
    dogeared       BOOLEAN NOT NULL DEFAULT FALSE, -- impression served because slot was pinned
    url            TEXT,
    slot           VARCHAR(50),
    request_id     VARCHAR(64),  -- UUID (36) or hashed token (16); previously ULID-sized (26) before batch path adopted UUIDs
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Convert to TimescaleDB hypertable (partitioned by event_time, 1 day chunks)
SELECT create_hypertable('tracking_events', 'event_time',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- Indexes (TimescaleDB auto-creates index on event_time)
CREATE INDEX IF NOT EXISTS idx_tracking_events_sequence ON tracking_events(sequence_nr);
CREATE INDEX IF NOT EXISTS idx_tracking_events_campaign ON tracking_events(campaign_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_tracking_events_advertiser ON tracking_events(advertiser_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_tracking_events_site_time ON tracking_events(site_id, event_time DESC);

-- Retention policy: auto-drop chunks older than 30 days
SELECT add_retention_policy('tracking_events', INTERVAL '30 days', if_not_exists => TRUE);

-- Compression policy: compress chunks older than 7 days
ALTER TABLE tracking_events SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'campaign_id',
    timescaledb.compress_orderby = 'event_time DESC'
);
SELECT add_compression_policy('tracking_events', INTERVAL '7 days', if_not_exists => TRUE);

-- ========================================
-- Floor Decisions (TimescaleDB Hypertable)
-- ========================================
-- One row per completed sweep cycle. Captures the argmax pick and the
-- per-candidate evidence the optimizer used. Sourced by the dashboard's
-- "Optimized floor over time" chart so history survives cluster restarts
-- and ring-buffer rollover. Inserted by FloorDecisionJournal on Phase.Init
-- (the moment a cycle completes and the next one starts).
CREATE TABLE IF NOT EXISTS floor_decisions (
    sequence_nr      BIGSERIAL,
    site_id          VARCHAR(100) NOT NULL,
    ts               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    argmax_floor     DECIMAL(10, 4) NOT NULL,
    prev_argmax      DECIMAL(10, 4),       -- NULL on the first completed cycle
    cycle_revenue    DECIMAL(12, 4),       -- summed across all measured candidates this cycle
    cycle_imps       BIGINT,
    -- Per-candidate snapshot — same shape as the API's sweep-evidence response.
    -- TEXT (not JSONB) because Slick sends the value as VARCHAR; can be
    -- changed to JSONB if/when we need to query inside the JSON. The
    -- value is well-formed JSON either way.
    candidates_json  TEXT,
    -- Demand category this decision is for (PER_CATEGORY_FLOOR_MODE).
    -- NULL = the site-wide sweep's decision (legacy single-floor row).
    -- Existing DBs: ALTER TABLE floor_decisions ADD COLUMN category VARCHAR(100);
    category         VARCHAR(100),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

SELECT create_hypertable('floor_decisions', 'ts',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists => TRUE
);

CREATE INDEX IF NOT EXISTS idx_floor_decisions_site_ts ON floor_decisions(site_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_floor_decisions_sequence ON floor_decisions(sequence_nr);

-- Retention: keep 90 days. Cycle picks are tiny rows, so we don't need
-- aggressive cleanup. 90d gives 3 months of trend data per site.
SELECT add_retention_policy('floor_decisions', INTERVAL '90 days', if_not_exists => TRUE);

-- Compression: chunks older than 30 days. Older decisions are static
-- historical record, fine to compress.
ALTER TABLE floor_decisions SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'site_id',
    timescaledb.compress_orderby = 'ts DESC'
);
SELECT add_compression_policy('floor_decisions', INTERVAL '30 days', if_not_exists => TRUE);

-- ========================================
-- Fraud flags (docs/design/FRAUD_PREVENTION.md, Layer 2)
-- ========================================
-- One row per (site, signal, day) the economics detector tripped. The
-- UNIQUE constraint makes re-running the detector idempotent — the same
-- day upserts rather than duplicating. status drives the Phase-3 review
-- queue (open → released | confirmed).
CREATE TABLE IF NOT EXISTS fraud_flags (
    id            BIGSERIAL PRIMARY KEY,
    site_id       VARCHAR(100) NOT NULL,
    signal        VARCHAR(40)  NOT NULL,   -- suspect_share | imp_per_pageview | ctr_spike
    severity      DOUBLE PRECISION NOT NULL,
    window_day    DATE NOT NULL,
    evidence      TEXT NOT NULL,           -- human-readable metric snapshot
    status        VARCHAR(20) NOT NULL DEFAULT 'open',  -- open | released | confirmed
    flagged_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    resolved_at   TIMESTAMP WITH TIME ZONE,
    resolved_by   VARCHAR(200),
    UNIQUE (site_id, signal, window_day)
);
CREATE INDEX IF NOT EXISTS idx_fraud_flags_status ON fraud_flags (status, flagged_at DESC);
CREATE INDEX IF NOT EXISTS idx_fraud_flags_site ON fraud_flags (site_id, window_day DESC);

-- ========================================
-- Dashboard Read Tables (Pekko Projection)
-- ========================================
-- Real-time campaign statistics
CREATE TABLE IF NOT EXISTS campaign_stats (
    campaign_id     VARCHAR(100) PRIMARY KEY,
    advertiser_id   VARCHAR(100) NOT NULL,
    impressions     BIGINT NOT NULL DEFAULT 0,
    clicks          BIGINT NOT NULL DEFAULT 0,
    cta_clicks      BIGINT NOT NULL DEFAULT 0,
    total_spend     DECIMAL(12, 4) NOT NULL DEFAULT 0,
    folds           BIGINT NOT NULL DEFAULT 0,
    unfolds         BIGINT NOT NULL DEFAULT 0,
    dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    dogeared_clicks      BIGINT NOT NULL DEFAULT 0,
    dogeared_cta_clicks  BIGINT NOT NULL DEFAULT 0,
    first_impression_at  TIMESTAMP WITH TIME ZONE,
    last_impression_at   TIMESTAMP WITH TIME ZONE,
    last_click_at        TIMESTAMP WITH TIME ZONE,
    last_fold_at         TIMESTAMP WITH TIME ZONE,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_campaign_stats_advertiser ON campaign_stats(advertiser_id);

-- Per-creative statistics
CREATE TABLE IF NOT EXISTS creative_stats (
    creative_id     VARCHAR(100) NOT NULL,
    campaign_id     VARCHAR(100) NOT NULL,
    advertiser_id   VARCHAR(100) NOT NULL,
    impressions     BIGINT NOT NULL DEFAULT 0,
    clicks          BIGINT NOT NULL DEFAULT 0,
    cta_clicks      BIGINT NOT NULL DEFAULT 0,
    total_spend     DECIMAL(12, 4) NOT NULL DEFAULT 0,
    folds           BIGINT NOT NULL DEFAULT 0,
    unfolds         BIGINT NOT NULL DEFAULT 0,
    dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    dogeared_clicks      BIGINT NOT NULL DEFAULT 0,
    dogeared_cta_clicks  BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (creative_id, campaign_id)
);

CREATE INDEX IF NOT EXISTS idx_creative_stats_campaign ON creative_stats(campaign_id);
CREATE INDEX IF NOT EXISTS idx_creative_stats_advertiser ON creative_stats(advertiser_id);

-- Hourly aggregations for time-series charts
CREATE TABLE IF NOT EXISTS campaign_hourly_stats (
    campaign_id     VARCHAR(100) NOT NULL,
    hour_bucket     TIMESTAMP WITH TIME ZONE NOT NULL,
    impressions     BIGINT NOT NULL DEFAULT 0,
    clicks          BIGINT NOT NULL DEFAULT 0,
    cta_clicks      BIGINT NOT NULL DEFAULT 0,
    spend           DECIMAL(12, 4) NOT NULL DEFAULT 0,
    folds           BIGINT NOT NULL DEFAULT 0,
    unfolds         BIGINT NOT NULL DEFAULT 0,
    dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    dogeared_clicks      BIGINT NOT NULL DEFAULT 0,
    dogeared_cta_clicks  BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (campaign_id, hour_bucket)
);

CREATE INDEX IF NOT EXISTS idx_campaign_hourly_stats_time ON campaign_hourly_stats(hour_bucket);

-- Daily aggregations for longer-term trends.
-- day_bucket is the ADVERTISER's local day (account timezone at
-- projection-write time; UTC for org-less entities) — the same boundary
-- the budget rollover and spend-today use, so the report's days match
-- the advertiser's wallet. A timezone change re-buckets only future
-- rows; history keeps the days it was written under.
CREATE TABLE IF NOT EXISTS campaign_daily_stats (
    campaign_id     VARCHAR(100) NOT NULL,
    day_bucket      DATE NOT NULL,
    impressions     BIGINT NOT NULL DEFAULT 0,
    clicks          BIGINT NOT NULL DEFAULT 0,
    cta_clicks      BIGINT NOT NULL DEFAULT 0,
    spend           DECIMAL(12, 4) NOT NULL DEFAULT 0,
    unique_sites    INTEGER NOT NULL DEFAULT 0,
    folds           BIGINT NOT NULL DEFAULT 0,
    unfolds         BIGINT NOT NULL DEFAULT 0,
    dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    dogeared_clicks      BIGINT NOT NULL DEFAULT 0,
    dogeared_cta_clicks  BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (campaign_id, day_bucket)
);

CREATE INDEX IF NOT EXISTS idx_campaign_daily_stats_day ON campaign_daily_stats(day_bucket);

-- Dimensional daily rollup: campaign x day x site x category.
-- Serves the advertiser report's by-site / by-category / by-publisher cuts
-- AND the publisher report (via publisher_sites join). Durable — outlives
-- the 30-day tracking_events retention. category '' = serve with no
-- matched category. Written by DashboardProjectionHandler with the same
-- spend derivation (cpm/1000, dogeared excluded) so splits reconcile with
-- campaign_daily_stats.
--
-- One event, two owners, two local days: day_bucket is the ADVERTISER's
-- local day, pub_day_bucket the PUBLISHER's. Advertiser report queries
-- group by day_bucket, publisher report queries by pub_day_bucket — each
-- side sees its own account-timezone days (matching its wallet/earnings
-- statements). Both in the PK: one event near a midnight can split keys
-- that share the other side's day.
CREATE TABLE IF NOT EXISTS campaign_dim_daily_stats (
    campaign_id  VARCHAR(100) NOT NULL,
    day_bucket   DATE NOT NULL,
    pub_day_bucket DATE NOT NULL,
    site_id      VARCHAR(100) NOT NULL,
    category     VARCHAR(100) NOT NULL DEFAULT '',
    impressions  BIGINT NOT NULL DEFAULT 0,
    clicks       BIGINT NOT NULL DEFAULT 0,
    cta_clicks   BIGINT NOT NULL DEFAULT 0,
    spend        DECIMAL(12, 4) NOT NULL DEFAULT 0,
    dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (campaign_id, day_bucket, pub_day_bucket, site_id, category)
);

CREATE INDEX IF NOT EXISTS idx_campaign_dim_daily_stats_day ON campaign_dim_daily_stats(day_bucket);
CREATE INDEX IF NOT EXISTS idx_campaign_dim_daily_stats_pub_day ON campaign_dim_daily_stats(pub_day_bucket);

-- Advertiser-level rollup
CREATE TABLE IF NOT EXISTS advertiser_summary (
    advertiser_id       VARCHAR(100) PRIMARY KEY,
    total_impressions   BIGINT NOT NULL DEFAULT 0,
    total_clicks        BIGINT NOT NULL DEFAULT 0,
    total_cta_clicks    BIGINT NOT NULL DEFAULT 0,
    total_spend         DECIMAL(14, 4) NOT NULL DEFAULT 0,
    total_folds         BIGINT NOT NULL DEFAULT 0,
    total_unfolds       BIGINT NOT NULL DEFAULT 0,
    total_dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    total_dogeared_clicks      BIGINT NOT NULL DEFAULT 0,
    total_dogeared_cta_clicks  BIGINT NOT NULL DEFAULT 0,
    active_campaigns    INTEGER NOT NULL DEFAULT 0,
    total_creatives     INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ========================================
-- Pekko Projection Offset Tracking
-- ========================================
CREATE TABLE IF NOT EXISTS projection_offset_store (
    projection_name VARCHAR(255) NOT NULL,
    projection_key  VARCHAR(255) NOT NULL,
    current_offset  VARCHAR(255) NOT NULL,
    manifest        VARCHAR(32) NOT NULL,
    mergeable       BOOLEAN NOT NULL,
    last_updated    BIGINT NOT NULL,
    PRIMARY KEY (projection_name, projection_key)
);

CREATE TABLE IF NOT EXISTS projection_management (
    projection_name VARCHAR(255) NOT NULL,
    projection_key  VARCHAR(255) NOT NULL,
    paused          BOOLEAN NOT NULL,
    last_updated    BIGINT NOT NULL,
    PRIMARY KEY (projection_name, projection_key)
);

-- ========================================
-- Advertiser Email Mapping (Login)
-- ========================================
-- Maps email addresses to advertisers (one advertiser can have multiple emails)
CREATE TABLE IF NOT EXISTS advertiser_email (
    email           VARCHAR(320) PRIMARY KEY,  -- Max email length per RFC 5321
    advertiser_id   VARCHAR(32) NOT NULL,      -- ULID reference to advertiser
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_advertiser_email_advertiser ON advertiser_email(advertiser_id);

-- ========================================
-- Image Asset Storage (deduped by hash)
-- ========================================
CREATE TABLE IF NOT EXISTS image_asset (
    hash            VARCHAR(64) PRIMARY KEY,   -- SHA-256 hash of image bytes
    s3_key          VARCHAR(512) NOT NULL,     -- Path in R2/S3: "assets/{hash}.{ext}"
    mime            VARCHAR(64) NOT NULL,      -- MIME type: "image/png", etc.
    width           INT NOT NULL,
    height          INT NOT NULL,
    uploaded_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ========================================
-- Creative Records (links image to campaign)
-- ========================================
-- Image fields (s3_key, mime) are denormalized from image_asset for
-- fast serving without joins. Render dimensions are not tracked here:
-- fluid creatives size from the publisher's slot at delivery time.
CREATE TABLE IF NOT EXISTS creative (
    creative_id                  VARCHAR(32) PRIMARY KEY,   -- ULID
    image_hash                   VARCHAR(64) NOT NULL REFERENCES image_asset(hash),
    advertiser_id                VARCHAR(32) NOT NULL,
    campaign_id                  VARCHAR(32) NOT NULL,
    name                         VARCHAR(256),
    landing_url                  TEXT,
    landing_domain               VARCHAR(256),
    created_at                   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- Denormalized from image_asset for fast serving
    s3_key                       VARCHAR(512) NOT NULL,
    mime                         VARCHAR(64) NOT NULL,
    width                        INT NOT NULL DEFAULT 0,
    height                       INT NOT NULL DEFAULT 0,
    -- Magazine creative pages (JSON for expandable-magazine-banner)
    pages_json                   TEXT,
    -- Banner-level config (animation, expandDurationMs, font, etc.)
    banner_config_json           TEXT,
    -- Category verification (from Gemini)
    match_confidence             DOUBLE PRECISION,          -- 0.0-1.0 match score
    verification_reason          TEXT,                      -- Gemini's explanation
    declared_category            VARCHAR(64),               -- Campaign's adProductCategory
    -- Safety flags
    adult_content                BOOLEAN NOT NULL DEFAULT FALSE,
    violence                     BOOLEAN NOT NULL DEFAULT FALSE,
    hate_speech                  BOOLEAN NOT NULL DEFAULT FALSE,
    safety_score                 DOUBLE PRECISION,
    -- Suggested content categories from Gemini (JSON array as text)
    suggested_content_categories TEXT,
    -- LP text snapshot (full extracted text, fed to Gemini verify)
    lp_text_snapshot             TEXT,
    -- Status
    status                       VARCHAR(16) NOT NULL DEFAULT 'Active',
    -- Images that failed to load at the last render (dead/IP-blocked src,
    -- logo included) and were hidden; surfaced in the dashboard.
    broken_images                INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_creative_image_hash ON creative(image_hash);
CREATE INDEX IF NOT EXISTS idx_creative_campaign ON creative(campaign_id);
CREATE INDEX IF NOT EXISTS idx_creative_advertiser ON creative(advertiser_id);