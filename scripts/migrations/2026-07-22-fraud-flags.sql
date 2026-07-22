-- Fraud prevention Layer 2 (docs/design/FRAUD_PREVENTION.md): the
-- economics-detector output table. One row per (site, signal, day);
-- the UNIQUE constraint keeps re-runs idempotent.
CREATE TABLE IF NOT EXISTS fraud_flags (
    id            BIGSERIAL PRIMARY KEY,
    site_id       VARCHAR(100) NOT NULL,
    signal        VARCHAR(40)  NOT NULL,
    severity      DOUBLE PRECISION NOT NULL,
    window_day    DATE NOT NULL,
    evidence      TEXT NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'open',
    flagged_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    resolved_at   TIMESTAMP WITH TIME ZONE,
    resolved_by   VARCHAR(200),
    UNIQUE (site_id, signal, window_day)
);
CREATE INDEX IF NOT EXISTS idx_fraud_flags_status ON fraud_flags (status, flagged_at DESC);
CREATE INDEX IF NOT EXISTS idx_fraud_flags_site ON fraud_flags (site_id, window_day DESC);
