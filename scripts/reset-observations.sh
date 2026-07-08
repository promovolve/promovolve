#!/bin/bash
# Reset observation/revenue data without touching campaigns, creatives,
# advertiser/publisher entities, site state, or approvals.
#
# What gets wiped:
#   - tracking_events       (impression/click history)
#   - floor_decisions       (sweep cycle history / chart data)
#   - campaign_stats        (lifetime per-campaign aggregates)
#   - campaign_hourly_stats (hourly per-campaign aggregates)
#   - campaign_daily_stats  (daily per-campaign aggregates)
#   - creative_stats        (per-creative aggregates)
#   - creative_stats_snapshot
#   - projection_offset_store (forces projections to re-process from offset 0)
#   - floor optimizer snapshot per site (sweep state, argmax history in entity)
#   - per-campaign pacing day-start counters
#
# What is preserved:
#   - publishers, advertisers, campaigns, creatives, sites
#   - approvals already done
#   - publisher_email / advertiser_email / platform_users (login mappings)
#   - ServeIndex DData (no re-crawl needed)
#
# Prerequisites: cluster running on localhost:8080, postgres on docker.

set -e
API=http://localhost:8080
echo "=== check cluster is alive ==="
curl -sS -o /dev/null -m 3 -w "HTTP %{http_code}\n" "$API/v1/" || { echo "Cluster not responding — start it first"; exit 1; }

echo ""
echo "=== TRUNCATE projection tables ==="
docker exec promovolve-db psql -U promovolve -d promovolve -c "
TRUNCATE TABLE
  tracking_events,
  floor_decisions,
  campaign_stats,
  campaign_hourly_stats,
  campaign_daily_stats,
  creative_stats,
  creative_stats_snapshot,
  projection_offset_store;
" 2>&1 | tail -3

echo ""
echo "=== reset floor optimizer for each publisher's sites ==="
# Read all sites from DB (avoids needing auth tokens)
SITES=$(docker exec promovolve-db psql -U promovolve -d promovolve -t -c "
SELECT regexp_replace(persistence_id, '^site-', '') AS site_id
FROM durable_state
WHERE persistence_id LIKE 'site-%';
" 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$')

PUBS=$(docker exec promovolve-db psql -U promovolve -d promovolve -t -c "
SELECT regexp_replace(persistence_id, '^publisher-', '') AS pub_id
FROM durable_state
WHERE persistence_id LIKE 'publisher-%';
" 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$')

for PUB in $PUBS; do
  for SITE in $SITES; do
    code=$(curl -sS -o /dev/null -m 5 -w "%{http_code}" -X POST "$API/v1/publishers/$PUB/sites/$SITE/reset-floor-agent" 2>/dev/null)
    [ "$code" = "200" ] && echo "  reset $SITE (publisher $PUB) → HTTP $code"
  done
done

echo ""
echo "=== reset campaign day-starts (zero spendToday across all sites) ==="
ADVS=$(docker exec promovolve-db psql -U promovolve -d promovolve -t -c "
SELECT regexp_replace(persistence_id, '^advertiser-', '')
FROM durable_state WHERE persistence_id LIKE 'advertiser-%';
" 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$')

for ADV in $ADVS; do
  CAMPS_JSON=$(curl -sS "$API/v1/advertisers/$ADV/campaigns?limit=100" 2>/dev/null \
    | python3 -c "
import sys, json
d = json.load(sys.stdin)
out = [{'advertiserId': '$ADV', 'campaignId': c['id']} for c in d.get('data', [])]
print(json.dumps({'campaigns': out}))
" 2>/dev/null)
  for SITE in $SITES; do
    curl -sS -X POST "$API/v1/publishers/me/sites/$SITE/pacing/reset" \
      -H "Content-Type: application/json" -d "$CAMPS_JSON" -o /dev/null -w "  pacing reset for advertiser $ADV on site $SITE → HTTP %{http_code}\n" 2>/dev/null
  done
done

echo ""
echo "=== done ==="
echo "Observation data wiped. Campaigns/creatives/sites preserved."
echo "Refresh /publisher/stats and /publisher/sites/<id>/observations — should show zeros."
