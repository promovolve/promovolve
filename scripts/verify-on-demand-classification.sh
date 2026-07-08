#!/usr/bin/env bash
# Live verification for on-demand (crawl-free) classification (Phase 1).
# Full path incl. a creative + approval so the auction yields a real winner.
# Proves: cold serve -> needText=true ; classify-page -> 202 ; after classify
# + approval -> needText=false + a winner (the cold->warm transition).
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
PUB="${PUB:-verify-site}"
PUBLISHER="${PUBLISHER:-verify-publisher}"
ADV="adv-verify-$$"
URL="https://publisher.com/verify-cold-$(date +%s)"
CAT="${CAT:-483}"   # IAB Content 3.0 "Sports"
SLOT='[{"id":"slot-1","w":300,"h":250}]'
TEXT='Sports championship: the home football team won in overtime. The quarterback threw three touchdowns and the coach praised the defense. Fans celebrated the playoff berth; league standings now favor a deep postseason run. Basketball, soccer and baseball scores follow.'

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
jget() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"//"; }
post() { curl -s -w "\n__HTTP=%{http_code}" -X POST "$1" -H 'Content-Type: application/json' -d "$2" --max-time 15; }
urlenc() { python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
serve() { curl -s -X POST "$BASE/v1/serve/batch" -H 'Content-Type: application/json' \
  -d "{\"pub\":\"$PUB\",\"url\":\"$URL\",\"imp\":$SLOT}" --max-time 8; }

say "[setup] create the SITE with a config — REQUIRED so the IABTaxonomy"
say "         assistant initializes; without it ClassifyUrl returns not_ready."
post "$BASE/v1/publishers/$PUBLISHER/sites" "{
  \"id\":\"$PUB\",\"domain\":\"publisher.com\",
  \"crawlConfig\":{\"seedUrl\":\"https://publisher.com/\",\"cronSchedule\":\"0 0 2 * * ?\",\"maxDepth\":2,\"concurrency\":2,\"hostRegex\":\".*\",\"targetElements\":[]},
  \"slots\":[{\"slotId\":\"slot-1\",\"width\":300,\"height\":250}],
  \"taxonomyIds\":[\"$CAT\"],\"minFloorCpm\":\"0.5\"}" | sed 's/__HTTP=/ HTTP=/' | tail -1

say "[setup] advertiser + campaign(targetCategories) + creative + activate + category bidders"
curl -s -X PUT "$BASE/v1/advertisers/$ADV/budget" -H 'Content-Type: application/json' -d '{"dailyBudget":"500.0"}' >/dev/null
CAMP=$(curl -s -X POST "$BASE/v1/advertisers/$ADV/campaigns" -H 'Content-Type: application/json' -d "{
  \"name\":\"verify camp\",\"budget\":{\"daily\":\"200.0\"},
  \"schedule\":{\"startAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"},
  \"adProductCategory\":\"$CAT\",\"targetCategories\":[\"$CAT\"],
  \"bidding\":{\"strategy\":\"fixed\",\"maxCpm\":\"5.0\"},
  \"landingUrl\":\"https://example.com/landing\"}" | jget id)
echo "campaignId=$CAMP"
CRT=$(curl -s -X POST "$BASE/v1/advertisers/$ADV/campaigns/$CAMP/creatives" -H 'Content-Type: application/json' -d "{
  \"name\":\"verify creative\",\"landingUrl\":\"https://example.com/landing\",\"skipVerify\":true,
  \"pages\":[{\"tag\":\"FEATURE\",\"headline\":\"Sports\",\"sub\":\"\",\"body\":\"\",\"accent\":\"#1a5276\",\"bg\":\"#ffffff\",\"imgEmoji\":\"\",\"caption\":\"\",\"banners\":{}}]}" | jget id)
echo "creativeId=$CRT"
curl -s -X PUT "$BASE/v1/advertisers/$ADV/campaigns/$CAMP/status" -H 'Content-Type: application/json' -d '{"status":"active"}' >/dev/null
curl -s -X POST "$BASE/v1/auction/categories/$CAT/campaigns" -H 'Content-Type: application/json' -d "{\"campaigns\":{\"$CAMP\":\"$ADV\"}}" >/dev/null

say "[setup] force-verify site host"
post "$BASE/v1/publishers/$PUBLISHER/sites/$PUB/force-verify?host=publisher.com" '{}' | sed 's/__HTTP=/ HTTP=/'

say "[wait] 12s for demand categories -> SiteEntity"
sleep 12

say "TIER 1 — cold serve fresh url (EXPECT needText=true)"
serve; echo

say "TIER 2 — classify-page with sports text (EXPECT HTTP=202)"
post "$BASE/v1/classify-page" "{\"pub\":\"$PUB\",\"url\":\"$URL\",\"text\":\"$TEXT\",\"imp\":$SLOT}" | sed 's/__HTTP=/ HTTP=/'

say "[wait] 18s for Gemini classify -> auction"
sleep 18

say "[setup] approve the creative for this url"
post "$BASE/v1/publishers/$PUBLISHER/sites/$PUB/approval/approve" "{\"url\":\"$URL\",\"slot\":\"slot-1\",\"creativeId\":\"$CRT\"}" | sed 's/__HTTP=/ HTTP=/'
sleep 5

say "serve-index for url:"
curl -s "$BASE/v1/publishers/$PUBLISHER/sites/$PUB/serve-index?url=$(urlenc "$URL")&slotId=slot-1" --max-time 10; echo

say "TIER 3 — serve same url (EXPECT needText absent/false + a winner)"
for i in 1 2 3 4; do printf "t+%2ds: " $((i*5)); serve; echo; sleep 5; done
