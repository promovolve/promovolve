#!/usr/bin/env bash
#
# scripts/reset-billing.sh — "clean the books, keep the world."
#
# Scoped billing/stats reset for the local-day settlement rollout and for
# demo resets: wipes ALL money records and observation/stat history while
# preserving identity, orgs, passkeys, sites, campaigns, creatives, and
# approvals. One Postgres serves both tiers, so this is a single psql run.
#
# WIPED (when the table exists — guards let this run before or after the
# local-day platform migration):
#   money:  ledger_entries, ledger_transactions, payouts, billing_accounts,
#           daily_settlements + billing_settlement_days   (legacy UTC-day)
#           settlement_cursors + settlement_windows +
#           advertiser_settlements + publisher_settlements (local-day)
#   stats:  tracking_events, campaign_stats, creative_stats,
#           campaign_hourly_stats, campaign_daily_stats,
#           campaign_dim_daily_stats, advertiser_summary
#
# PRESERVED (deliberately untouched):
#   platform_users, webauthn_credentials (passkeys are RP_ID-bound and
#   unrecoverable), recovery_tokens, orgs (incl. timezone), org_members,
#   org_side_requests, audit_log, site_requests, publisher_sites,
#   advertiser_campaigns, creative*, image_asset, platform_margin_history,
#   platform_settings, payout_methods, approval-queue tables,
#   floor_decisions + traffic_shape_snapshot (learning, not money),
#   Pekko event_journal/snapshot/durable_state (entity state lives on),
#   projection_offset_store / projection_management.
#
# Projection offsets are NOT reset on purpose: the Pekko event journal is
# kept, so zeroing offsets would replay retained events straight back into
# the freshly truncated stat tables and resurrect the old numbers.
# Projections simply continue forward from their current offsets.
#
# Core-side entity state (advertiser/campaign spendToday) is NOT touched:
# it self-heals at the entity's next local-midnight budget roll. Expect up
# to one day where "today's spend" is nonzero while the wallet shows a
# clean slate.
#
# USAGE
#   scripts/reset-billing.sh --yes                       # cluster via k8s/db-proxy.sh (localhost:5433)
#   scripts/reset-billing.sh --dsn "$PGURL" --yes        # explicit DSN
#   scripts/reset-billing.sh --docker --yes              # local docker postgres (promovolve-db)
#
# Recommended rollout order (see docs/design/BILLING.md):
#   1. scale the platform deployment to 0 (stop the old settler)
#   2. run this script
#   3. deploy the local-day platform (Migrate creates the new tables)
set -euo pipefail

DSN="postgres://promovolve:promovolve@localhost:5433/promovolve?sslmode=disable"
DOCKER=0
CONFIRM=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dsn)    DSN="$2"; shift 2 ;;
    --docker) DOCKER=1; shift ;;
    --yes)    CONFIRM=1; shift ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

if [ "$CONFIRM" -ne 1 ]; then
  echo "This wipes ALL money records (ledger, wallets, settlements, payouts)"
  echo "and stat history, keeping users/orgs/sites/campaigns/creatives."
  echo "Re-run with --yes to proceed."
  exit 2
fi

run_psql() {
  if [ "$DOCKER" -eq 1 ]; then
    docker exec -i promovolve-db psql -U promovolve -d promovolve -v ON_ERROR_STOP=1
  else
    psql "$DSN" -v ON_ERROR_STOP=1
  fi
}

echo "=== reset-billing: truncating money + stat tables ==="
run_psql <<'SQL'
DO $$
DECLARE
  -- Order matters only for readability; TRUNCATE ... CASCADE handles FKs.
  tbl text;
  present text[] := '{}';
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    -- money (legacy UTC-day + local-day; whichever generation exists)
    'ledger_entries', 'ledger_transactions',
    'daily_settlements', 'billing_settlement_days',
    'settlement_cursors', 'settlement_windows',
    'advertiser_settlements', 'publisher_settlements',
    'payouts', 'billing_accounts',
    -- observation / stat history
    'tracking_events', 'campaign_stats', 'creative_stats',
    'campaign_hourly_stats', 'campaign_daily_stats',
    'campaign_dim_daily_stats', 'advertiser_summary'
  ] LOOP
    IF to_regclass(tbl) IS NOT NULL THEN
      present := present || tbl;
    END IF;
  END LOOP;
  IF array_length(present, 1) IS NULL THEN
    RAISE NOTICE 'nothing to truncate';
  ELSE
    RAISE NOTICE 'truncating: %', array_to_string(present, ', ');
    EXECUTE 'TRUNCATE TABLE ' || array_to_string(present, ', ') || ' CASCADE';
  END IF;
END
$$;
SQL

echo ""
echo "Books are clean. Reminders:"
echo "  - every wallet is now \$0: advertisers with live traffic suspend within"
echo "    one settler tick until the operator records a top-up"
echo "  - projection offsets were kept on purpose (no replay of old events)"
echo "  - core spendToday self-heals at the next local-midnight budget roll"
