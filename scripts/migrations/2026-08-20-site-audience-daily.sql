-- Tier 3 of docs/design/GEOGRAPHIC_CONTEXT.md: the aggregate that makes a
-- publisher's declared reader audience checkable.
--
-- What this table deliberately does NOT hold: any row describing one reader.
-- The beacon path resolves an IP to a country, adds 1 to an in-memory
-- counter, and drops the IP — the same IP it already drops after fraud
-- hygiene. Only these daily sums reach disk. A per-event country column
-- would put a country next to a timestamp, which is a far more revealing
-- artefact than a daily total, and would falsify the claim in api/GPC.md
-- that no per-viewer record exists.
--
-- Counts are ADDITIVE: every API pod flushes its own partial tally on its
-- own timer, so writes use ON CONFLICT ... count = count + EXCLUDED.count.
-- A last-writer-wins upsert would keep one pod's view and silently
-- undercount a multi-pod cluster.
--
-- Country granularity is a property of the source (the iptoasn dump carries
-- no subdivision), which is why a city-level declaration can only ever be
-- verified as far as its country.
--
-- Days are UTC. Settlement is local-day per entity, but this is an audit
-- statistic rather than money, and one global boundary keeps counts from
-- several pods addable without agreeing on a timezone.
CREATE TABLE IF NOT EXISTS site_audience_daily (
    site_id  VARCHAR(128) NOT NULL,
    country  CHAR(2)      NOT NULL,
    day      DATE         NOT NULL,
    count    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (site_id, country, day)
);

CREATE INDEX IF NOT EXISTS idx_site_audience_daily_site_day
    ON site_audience_daily (site_id, day DESC);
