# RL floor-agent cluster test harness

Drives the **running** Promovolve cluster with synthetic traffic against a
real `SiteEntity` + `FloorCpmOptimizationAgent`, polls observations into a
JSONL file, and renders plots. This is the integration-level counterpart to
the pure simulator at `modules/core/src/main/scala/promovolve/rl/sim/` —
slower but exercises CBOR persistence, the live auction, pacing, and the
full Pekko serialization path.

## Prerequisites

- Cluster running (`scripts/run-dev.sh`) with a verified site and a publisher
  whose advertiser already has the campaigns referenced in the scenario.
- Publisher site reachable (the bootstrap site used by `simulate-traffic`).
- Python 3.10+ and `pip install -r requirements.txt`.

## Run

```bash
python3 runner.py scenarios/baseline.yaml \
  --publisher 01KRXB2CNNAMEA3K5QZ0CBKWA4 \
  --site localhost-8888 \
  --plot
```

Outputs go to `runs/<scenario-name>/<UTC-timestamp>/`:
- `observations.jsonl` — every floor observation polled from the API, one per line
- `summary.json` — final state + run metadata
- `plots/*.png` — generated if `--plot` is passed

## Scenario format

See `scenarios/baseline.yaml` for the minimal example. Key fields:

```yaml
name: baseline                # used for the run directory name
durationSeconds: 1200         # how long to drive traffic (wall clock)
pollIntervalSeconds: 5        # how often to scrape /floor-observations
site:
  initialFloorCpm: 5.0        # PATCH /sites/{id} on start
  minFloorCpm: 5.0
  bidWeight: 0.5
  behavior: {stability: balanced, aggressiveness: balanced, coldStart: normal}
  pacingDayDurationSeconds: 300
traffic:
  workers: 4
  intervalSeconds: 1
  ctr: 0.10
events: []                    # see below
```

### Events

Triggered relative to run start. Currently supports:

```yaml
events:
  - {atSeconds: 600, action: pauseCampaign,  campaignId: 01KRXBC1HR1FD696E453SX83MB}
  - {atSeconds: 900, action: resumeCampaign, campaignId: 01KRXBC1HR1FD696E453SX83MB}
  - {atSeconds: 1100, action: setCampaignMaxCpm, campaignId: 01KRXBC1HR1FD696E453SX83MB, maxCpm: 8.0}
```

## Sweep-optimizer scenarios

The floor is set by the `FloorSweepOptimizer` (the sole optimizer; the
former DQN/RL agent was removed). The harness just observes whatever the
in-cluster optimizer does. Start the cluster normally and run the
`*_sweep_*.yaml` scenarios:

- `compare_sweep_long.yaml` — 90-min convergence test, pair with the
  `compare_floor_5` fixed-floor baseline.
- `discover_sweep_floor.yaml` — 90-min discovery test from
  `minFloorCpm=$1` in a $2/$4/$6/$8/$10 market.
- `responsive_sweep_bidders.yaml` — 40-min non-stationarity test with
  bidders that raise on silence.

Compare across modes with `compare.py`; the strategy label in plot
legends shows `(rl)` vs `(sweep)` so you can eyeball convergence
behaviour for both.

## Plotting separately

```bash
python3 plot.py runs/baseline/2026-05-18T21-30/
```

Generates the standard set (floor / epsilon / loss / reward) in `plots/`.
Re-runs are idempotent — drop in a custom plot script if you want to mix
scenarios on the same axes.

## What this is *not*

- Not a pure simulator — needs the live cluster running.
- Not a CI gate (yet) — outputs are for eyeballing convergence behaviour.
- Not creating advertisers/campaigns — they must pre-exist.
