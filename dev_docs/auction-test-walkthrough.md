# Auction Test Walkthrough

How to drive end-to-end auction traffic through a running PromoVolve cluster for debugging or development.

The recommended testing path is the scala-cli script `scripts/RunScenario.scala`, which orchestrates the production `/v1/...` flow in one shot, plus the Go simulator at `platform/cmd/simulate-traffic` (`go build ./cmd/simulate-traffic`) for steady-state load. (A few low-level `/test/*` debug endpoints still exist — e.g. `/test/bid`, `/test/direct-bid`, `/test/spend` in `AuctionRoutes.scala` — but `/test/register` and `/test/serve-index` are gone; campaign registration goes through `POST /v1/auction/categories/{category}/campaigns` and the serve index is inspected via `GET /v1/publishers/{id}/sites/{siteId}/serve-index`.)

## Prerequisites

Dev runs on the local Kubernetes cluster (Docker Desktop) — **never**
start the API with `sbt`/`run-dev.sh`: a forked `api/run` JVM squats
`:8080` and shadows the k8s LoadBalancer, producing ghost 403s that look
like auth bugs.

```bash
kubectl config use-context docker-desktop   # ALWAYS pin the context — a GKE prod context exists
k8s/up.sh                                   # bring the dev cluster up (2 all-roles api pods + postgres)
k8s/reset.sh --yes                          # optional factory reset — verify PVC ages after, one run has silently no-opped
```

The API listens on `http://localhost:8080` via the k8s service. The dashboard (Go) is a separate process — run with `scripts/run-dashboard.sh`. The example publisher sites under `modules/examples/publisher-site*/` are static — serve them with any static file server (e.g., `python3 -m http.server 8888`) from inside the chosen site directory.

## End-to-end flow with `RunScenario.scala`

`scripts/RunScenario.scala` is a scala-cli script that creates an advertiser + campaign + fluid creative, registers the campaign with categories, and (optionally) waits for the campaign to bid. It uses only production `/v1/...` endpoints.

```bash
# Run a built-in scenario:
scala-cli scripts/RunScenario.scala -- --scenario scenarios/single-advertiser.json

# Or pass flags directly (see RunScenario.scala for the full set):
scala-cli scripts/RunScenario.scala -- \
  --advertiser adv-1 \
  --budget 100 \
  --max-cpm 5 \
  --category 227 \
  --site-id site-001 \
  --slot-id slot-1 \
  --page-url "http://localhost:8888/health/index.html"
```

Behind the scenes the script calls these production endpoints (all under `http://localhost:8080`):

| Step | Endpoint | Purpose |
|---|---|---|
| 1 | `POST /v1/advertisers/{id}` | Create advertiser |
| 2 | `PUT /v1/advertisers/{id}/budget` | Set daily budget |
| 3 | `POST /v1/advertisers/{id}/campaigns` | Create campaign (returns campaign ID) |
| 4 | `PATCH /v1/advertisers/{id}/campaigns/{cid}` | Configure (categories, maxCpm, etc.) |
| 5 | `POST /v1/advertisers/{id}/campaigns/{cid}/creatives` | Publish creative (`skipVerify: true` for synthetic load) |
| 6 | `PUT /v1/advertisers/{id}/campaigns/{cid}/status` | Activate |
| 7 | `POST /v1/publishers/{id}/sites/{sid}/classify` | Trigger auction (page → categories → fan-out → bids → ServeIndex) |

To exercise the **serve path** (impressions / clicks / Thompson Sampling feedback) on top of a populated `ServeIndex`, use the Go simulator:

```bash
cd platform && go build -o simulate-traffic ./cmd/simulate-traffic
./simulate-traffic -workers 4 -interval 500ms -ctr 0.10
```

It auto-discovers `[data-promovolve-slot]` placements on the example publisher pages, sends one `POST /v1/serve/batch` per page-load, fires the returned `impUrl` for every winner, and (per the configured CTR) fires `clickUrl` too.

## Reading the server logs

Each stage of the auction emits a marker emoji. Tail the API output:

```bash
tail -f server.log | grep -E "🏁|🔄|📊|📨|📥|🎯|📦|💾|🟢|🔴|📢|📣|📋|🔍|⚠️|📬|💰|❌|🔁"
```

| Emoji | Component | Meaning |
|-------|-----------|---------|
| 🟢 | `CampaignEntity` | Campaign became active |
| 🔴 | `CampaignEntity` | Campaign became inactive |
| 🔄 | `CampaignEntity` | Config changed, triggering re-auction |
| 📢 | `CampaignDirectory` | Publishing campaigns to `CategoryBidderEntity` |
| 📣 | `CampaignDirectory` | `CampaignChanged` published to topic |
| ✅ | `CampaignDirectory` | Category became viable (≥1 active campaign) |
| ❌ | `CampaignDirectory` | Category became non-viable |
| 🏁 | `AuctioneerEntity` | Auction START (`PageCategoriesClassified` arrived) |
| 📊 | `AuctioneerEntity` | Categories ranked by `TaxonomyRankerEntity` |
| 📨 | `AuctioneerEntity` | Sending bid requests to `CategoryBidderEntity` per slot |
| 📥 | `AuctioneerEntity` | Bids collected for a slot |
| 🎯 | `AuctioneerEntity` | `🎯 Auction COMPLETE` for a slot |
| 🔁 | `AuctioneerEntity` | Re-auction triggered (campaign status / budget / config change) |
| 📬 | `AuctioneerEntity` | Received `CampaignChanged` event |
| 💰 | `AuctioneerEntity` | Received `BudgetEvent` |
| 📋 | `CategoryBidderEntity` | Received `ActiveCampaigns` list |
| 🔍 | `CategoryBidderEntity` | Processing `CategoryBidRequest` |
| ⚠️ | `CategoryBidderEntity` | No active campaigns for category |
| 📦 | `AdServer` | Received candidates from auctioneer |
| 💾 | `AdServer` | Cached candidates to `ServeIndex` (DData) |

## Inspecting state without curling endpoints

Pekko cluster state lives in DData and the Postgres `durable_state` / `event_journal` tables. For a quick read:

- **Active candidates per slot**: query `ServeIndex` via the dashboard's site/auction view (`/publisher/sites/{id}/auction-state`) — the `/test/serve-index` endpoint that used to expose this is gone.
- **Active campaigns**: `psql ... -c "select id, status, daily_budget from advertiser_campaigns where status='Active'"`.
- **Approved creatives**: `psql ... -c "select creative_id, advertiser_id, campaign_id, mime, status from creative where status='Active'"`.
- **Per-creative impressions**: dashboard `/dashboard/campaigns/{cid}/creatives` route, or the `creative_stats` table.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `🏁 Auction START` but no `🎯 Auction COMPLETE` | A `CategoryBidderEntity` ask timed out. Check `📋` emoji — the bidder needs an `ActiveCampaigns` list before it can respond. |
| `🎯 Auction COMPLETE: winners=0` | Campaign(s) bid but every creative was rejected. Check the `Creative … rejected` log line for the reason (`isActive=false` or `hasRejections=true`). |
| `ServeIndex Get: key=…\|… BUCKET NOT FOUND` | Slot's auction never ran — the URL hasn't been classified, OR the auctioneer hasn't received `PageCategoriesClassified` yet. Trigger via `/v1/publishers/{id}/sites/{sid}/classify`. |
| `BATCH SERVED: N filled, M unfilled` with M > 0 on a page where every slot has approved creatives | Per-page-per-campaign hard dedup is hitting. Same campaign won an earlier slot in the batch; later slots have no eligible bidders. Add a second campaign or reduce slot count to verify. |
| Entity rehydration fails on startup | Persistence shape changed since the last run. Wipe with `scripts/run-dev.sh --fresh`. |

## Related scripts

- `scripts/RunScenario.scala` — end-to-end advertiser/campaign/creative setup + classify call
- `scripts/RotateCreative.scala` — replaces a campaign's creative without touching anything else
- `platform/simulate-traffic` — Go binary that sends `POST /v1/serve/batch` traffic against the example publisher sites
- `scripts/run-dev.sh` — start the cluster (`--fresh` wipes DB + DData)
- `scripts/publish-bootstrap.sh` — rebuild and fan out the publisher ad-tag bundle to the example sites
