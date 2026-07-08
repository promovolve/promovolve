#!/usr/bin/env bash
#
# Run PromoVolve API server in development mode
#
# Options:
#   --clean        Clear ddata (remembered entities) before starting
#   --fresh        Clear ddata AND truncate database before starting
#   --log CLASS    Enable DEBUG logging for a specific class (e.g. promovolve.publisher.delivery.AdServer)
#

set -e

cd "$(dirname "$0")/.."

CLEAN_DDATA=false
FRESH_START=false
LOG_CLASS=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --clean)
      CLEAN_DDATA=true
      shift
      ;;
    --fresh)
      CLEAN_DDATA=true
      FRESH_START=true
      shift
      ;;
    --log)
      LOG_CLASS="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

if [ "$CLEAN_DDATA" = true ]; then
  echo "Clearing ddata (remembered entities + serve index)..."
  rm -rf target/ddata-promovolve-*/
  rm -rf target/ddata/
  rm -rf modules/api/target/ddata-promovolve-*/
  rm -rf modules/api/target/ddata/
fi

if [ "$FRESH_START" = true ]; then
  echo "Truncating database..."
  docker exec promovolve-db psql -U promovolve -c "
    -- Pekko persistence
    TRUNCATE durable_state, snapshot, event_journal CASCADE;
    -- Platform (Go)
    TRUNCATE platform_users CASCADE;
    DROP TABLE IF EXISTS api_keys;
    DROP TABLE IF EXISTS webauthn_credentials;
    DROP TABLE IF EXISTS platform_margin_history;
    DROP TABLE IF EXISTS recovery_tokens;
    -- Publisher approval queue
    DROP TABLE IF EXISTS pending_selection;
    DROP TABLE IF EXISTS pending_first_seen;
    DROP TABLE IF EXISTS approved_creative;
    DROP TABLE IF EXISTS flagged_creative;
    -- Creative & image storage
    DROP TABLE IF EXISTS creative;
    DROP TABLE IF EXISTS creative_meta;
    DROP TABLE IF EXISTS image_asset;
    -- Publisher/advertiser email mapping
    DROP TABLE IF EXISTS publisher_email;
    DROP TABLE IF EXISTS advertiser_email;
    -- Snapshots (Thompson Sampling stats, traffic shape)
    DROP TABLE IF EXISTS creative_stats_snapshot;
    DROP TABLE IF EXISTS traffic_shape_snapshot;
    -- Projection (dashboard analytics)
    DROP TABLE IF EXISTS tracking_events;
    DROP TABLE IF EXISTS campaign_stats;
    DROP TABLE IF EXISTS creative_stats;
    DROP TABLE IF EXISTS campaign_hourly_stats;
    DROP TABLE IF EXISTS campaign_daily_stats;
    DROP TABLE IF EXISTS campaign_dim_daily_stats;
    DROP TABLE IF EXISTS advertiser_summary;
  " 2>/dev/null || echo "Warning: Could not truncate database (is Docker running?)"
  # Recreate dropped tables (tracking_events, campaign_stats, etc.)
  echo "Recreating projection tables..."
  docker exec -i promovolve-db psql -U promovolve -d promovolve < "$(dirname "$0")/../docker/init-db.sql" 2>/dev/null || echo "Warning: Could not run init-db.sql"
  # Reset projection offset so dashboard reprocesses from start
  echo "Resetting projection offsets..."
  docker exec promovolve-db psql -U promovolve -d promovolve -c "UPDATE projection_offset_store SET current_offset = 0;" 2>/dev/null
fi

echo "Starting PromoVolve API server..."
export LOG_LEVEL=${LOG_LEVEL:-INFO}

# Load secrets from .env file
ENV_FILE="$(dirname "$0")/.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  source "$ENV_FILE"
  set +a
else
  echo "Error: $ENV_FILE not found. Copy scripts/.env.example to scripts/.env and fill in your values."
  exit 1
fi

export ENABLE_TEST_ROUTES=${ENABLE_TEST_ROUTES:-true}

# Show where the banner web component will be fetched from so it's
# obvious which build the API is pointing at. Empty value means the
# bootstrap can't load the banner — run `npm run publish:r2` in
# platform/banner-component to populate BANNER_SCRIPT_URL in scripts/.env.
echo "BANNER_SCRIPT_URL=${BANNER_SCRIPT_URL:-<UNSET — banner rendering disabled>}"

LOG_CLASS_ARGS=()
if [ -n "$LOG_CLASS" ]; then
  LOG_CLASS_ARGS+=("-J-DLOG_CLASS=$LOG_CLASS")
fi

exec sbt \
  -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -J-Dpromovolve.gemini.enabled=true \
  "${LOG_CLASS_ARGS[@]}" \
  "api/run"