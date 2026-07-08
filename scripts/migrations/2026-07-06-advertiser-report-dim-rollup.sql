-- Advertiser report dimensional rollup — cluster migration + backfill.
-- See docs/design/ADVERTISER_REPORTING.md.
--
-- RUN ORDER (matters):
--   1. Deploy the api image that writes campaign_dim_daily_stats
--      (DashboardProjectionHandler) FIRST — live accrual starts.
--   2. Then run this script once against the projection DB, at a quiet
--      moment (projection caught up). Past complete UTC days are rebuilt
--      authoritatively from tracking_events; today is rebuilt too, so the
--      only seam is events inside the projection lag at run time — run
--      when lag is ~0 and it's exact.
--   3. Re-running is safe: the rebuild is DELETE + re-aggregate.
--
-- Reach: tracking_events has a 30-day retention policy, so the backfill
-- recovers at most ~30 days of dimensional history; full depth accrues
-- live from the deploy in step 1.

-- ---- Schema (no-ops where init-db.sql already created these) ----------

CREATE TABLE IF NOT EXISTS campaign_dim_daily_stats (
    campaign_id  VARCHAR(100) NOT NULL,
    day_bucket   DATE NOT NULL,
    site_id      VARCHAR(100) NOT NULL,
    category     VARCHAR(100) NOT NULL DEFAULT '',
    impressions  BIGINT NOT NULL DEFAULT 0,
    clicks       BIGINT NOT NULL DEFAULT 0,
    cta_clicks   BIGINT NOT NULL DEFAULT 0,
    spend        DECIMAL(12, 4) NOT NULL DEFAULT 0,
    dogeared_impressions BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (campaign_id, day_bucket, site_id, category)
);

CREATE INDEX IF NOT EXISTS idx_campaign_dim_daily_stats_day ON campaign_dim_daily_stats(day_bucket);

ALTER TABLE publisher_sites ADD COLUMN IF NOT EXISTS host VARCHAR(255) NOT NULL DEFAULT '';

-- ---- Heal publisher_sites.host for pre-existing sites -----------------
-- New/re-registered sites get host from createSite; existing rows are
-- healed from each site's most recent tracked page URL. Sites with no
-- surviving events keep host = '' (report falls back to the site_id slug).

UPDATE publisher_sites ps
SET host = sub.host
FROM (
    SELECT DISTINCT ON (site_id)
           site_id,
           substring(url FROM '^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)') AS host
    FROM tracking_events
    WHERE url IS NOT NULL AND url <> ''
    ORDER BY site_id, event_time DESC
) sub
WHERE ps.site_id = sub.site_id
  AND ps.host = ''
  AND sub.host IS NOT NULL AND sub.host <> '';

-- ---- Backfill the rollup from the surviving raw-event window ----------
-- Same semantics as the live handler: spend = cpm/1000 on non-dogeared
-- impressions only; dogeared serves count only dogeared_impressions;
-- category NULL/blank normalizes to ''. Full rebuild (DELETE + aggregate)
-- keeps the script idempotent and seam-free for complete days.

BEGIN;

DELETE FROM campaign_dim_daily_stats;

INSERT INTO campaign_dim_daily_stats
    (campaign_id, day_bucket, site_id, category,
     impressions, clicks, cta_clicks, spend, dogeared_impressions, updated_at)
SELECT
    campaign_id,
    (event_time AT TIME ZONE 'UTC')::date AS day_bucket,
    site_id,
    COALESCE(NULLIF(TRIM(category), ''), '') AS category,
    COUNT(*) FILTER (WHERE event_type = 'impression' AND NOT dogeared),
    COUNT(*) FILTER (WHERE event_type = 'click' AND NOT dogeared),
    COUNT(*) FILTER (WHERE event_type = 'cta_click' AND NOT dogeared),
    COALESCE(SUM(cpm / 1000.0) FILTER (WHERE event_type = 'impression' AND NOT dogeared), 0),
    COUNT(*) FILTER (WHERE event_type = 'impression' AND dogeared),
    NOW()
FROM tracking_events
WHERE campaign_id IS NOT NULL AND campaign_id <> ''
  AND event_type IN ('impression', 'click', 'cta_click')
GROUP BY campaign_id, (event_time AT TIME ZONE 'UTC')::date, site_id,
         COALESCE(NULLIF(TRIM(category), ''), '');

COMMIT;
