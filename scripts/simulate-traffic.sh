#!/usr/bin/env bash
#
# Simulate ad traffic — acts like browsers loading publisher pages.
# Calls /v1/serve, fires impression pixels, and randomly clicks.
#
# Usage:
#   scripts/simulate-traffic.sh                          # defaults
#   scripts/simulate-traffic.sh --interval 2 --ctr 0.15  # faster, more clicks
#   scripts/simulate-traffic.sh --pub localhost-8888      # specific publisher
#

set -e

API="${API:-http://localhost:8080/v1}"
PUB="localhost-8888"
INTERVAL=3
CTR=0.10

while [[ $# -gt 0 ]]; do
  case $1 in
    --pub) PUB="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --ctr) CTR="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--pub ID] [--interval SECS] [--ctr RATE] [--api URL]"
      exit 0 ;;
    *) echo "Unknown: $1"; exit 1 ;;
  esac
done

# Discover pages and slots from the serve-index
echo "Discovering slots for publisher $PUB..."
SLOTS_JSON=$(curl -s "$API/publishers/$PUB/sites/$PUB/serve-index" 2>/dev/null)
if [ -z "$SLOTS_JSON" ] || echo "$SLOTS_JSON" | grep -q "error"; then
  echo "Could not fetch serve-index. Using fallback slots."
  # Fallback: try known pages
  PAGES_AND_SLOTS=""
else
  # Extract unique (url, slotId) pairs from serve-index candidates
  PAGES_AND_SLOTS=$(echo "$SLOTS_JSON" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    seen = set()
    for c in d.get('candidates', []):
        key = c.get('key', '')
        parts = key.split('|', 1)
        if len(parts) == 2:
            slot = parts[1]
            # We don't have URL in serve-index, will use slot only
            if slot not in seen:
                print(slot)
                seen.add(slot)
except: pass
" 2>/dev/null)
fi

# If no slots found, try to discover by hitting known pages
if [ -z "$PAGES_AND_SLOTS" ]; then
  echo "No serve-index data. Trying known page URLs..."
  PAGES_AND_SLOTS=""
  for page in \
    "http://localhost:8888/health/index.html" \
    "http://localhost:8888/basketball/index.html" \
    "http://localhost:8888/soccer/index.html" \
    "http://localhost:8888/yakyu/index.html" \
    "http://localhost:8888/esports/index.html" \
    "http://localhost:8888/keiba/index.html" \
    "http://localhost:8888/index.html"; do
    # Fetch page and extract slot IDs
    SLOTS=$(curl -s "$page" 2>/dev/null | grep -o 'data-ad-slot="[^"]*"' | sed 's/data-ad-slot="//;s/"//' || true)
    for slot in $SLOTS; do
      PAGES_AND_SLOTS="$PAGES_AND_SLOTS
$page $slot"
    done
  done
fi

# Parse into arrays
ENTRIES=()
while IFS= read -r line; do
  [ -n "$line" ] && ENTRIES+=("$line")
done <<< "$PAGES_AND_SLOTS"

if [ ${#ENTRIES[@]} -eq 0 ]; then
  echo "No slots found. Make sure the publisher site is running and pages have been crawled."
  exit 1
fi

echo ""
echo "=== Ad Traffic Simulator ==="
echo "  Publisher: $PUB"
echo "  Slots:    ${#ENTRIES[@]} page/slot combinations"
echo "  Interval: ${INTERVAL}s between requests"
echo "  CTR:      ${CTR} (click probability)"
echo "  Ctrl+C to stop"
echo ""

IMPS=0
CLICKS=0
SERVES=0

cleanup() {
  echo ""
  echo "=== Summary ==="
  echo "  Serve requests: $SERVES"
  echo "  Impressions:    $IMPS"
  echo "  Clicks:         $CLICKS"
  if [ $IMPS -gt 0 ]; then
    echo "  Actual CTR:     $(python3 -c "print(f'{$CLICKS/$IMPS*100:.1f}%')")"
  fi
  exit 0
}
trap cleanup INT

while true; do
  # Pick random entry
  ENTRY="${ENTRIES[$((RANDOM % ${#ENTRIES[@]}))]}"

  # Parse page URL and slot
  if [[ "$ENTRY" == *" "* ]]; then
    PAGE_URL="${ENTRY%% *}"
    SLOT="${ENTRY##* }"
  else
    # Slot only (from serve-index), use a default page URL
    SLOT="$ENTRY"
    PAGE_URL="http://localhost:8888/health/index.html"
  fi

  # Random user ID (simulate different users)
  UID="sim-$(( RANDOM % 100 ))"

  # Serve request
  ENCODED_URL=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$PAGE_URL'))")
  RESPONSE=$(curl -s "$API/serve?pub=$PUB&url=$ENCODED_URL&slot=$SLOT&uid=$UID" 2>/dev/null)
  SERVES=$((SERVES + 1))

  # Skip if no ad returned (204)
  if [ -z "$RESPONSE" ] || [ ${#RESPONSE} -lt 10 ]; then
    sleep "$INTERVAL"
    continue
  fi

  # Parse URLs from response
  IMP_URL=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('impUrl',''))" 2>/dev/null || true)
  CLICK_URL=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('clickUrl',''))" 2>/dev/null || true)
  CID=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('creativeId','')[:12])" 2>/dev/null || true)

  if [ -z "$IMP_URL" ]; then
    sleep "$INTERVAL"
    continue
  fi

  # Fire impression
  curl -s "$IMP_URL" > /dev/null 2>&1
  IMPS=$((IMPS + 1))

  # Random click
  DO_CLICK=$(python3 -c "import random; print(1 if random.random() < $CTR else 0)")
  if [ "$DO_CLICK" = "1" ] && [ -n "$CLICK_URL" ]; then
    curl -s "$CLICK_URL" > /dev/null 2>&1
    CLICKS=$((CLICKS + 1))
    printf "  [%s] CLICK  %s slot=%s uid=%s  (%d imps, %d clicks)\n" "$(date +%H:%M:%S)" "$CID" "$SLOT" "$UID" "$IMPS" "$CLICKS"
  else
    printf "  [%s] IMP    %s slot=%s uid=%s  (%d imps)\n" "$(date +%H:%M:%S)" "$CID" "$SLOT" "$UID" "$IMPS"
  fi

  sleep "$INTERVAL"
done
