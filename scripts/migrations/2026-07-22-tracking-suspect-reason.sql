-- Fraud prevention Layer 0 (docs/design/FRAUD_PREVENTION.md):
-- request-hygiene mark on tracking events. NULL = clean; billing,
-- metering, learning, and projections filter `suspect_reason IS NULL`.
-- Nullable ADD COLUMN is metadata-only on TimescaleDB hypertables.
ALTER TABLE tracking_events ADD COLUMN IF NOT EXISTS suspect_reason VARCHAR(20);
