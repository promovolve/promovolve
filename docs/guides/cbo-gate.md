# Gating Campaign Budget Optimization

How to measure whether the CBO allocator (GH #38) earns more tap-throughs
per unit of spend than fixed campaign walls, and what a result does and
does not mean. The driver is `scripts/cbo-gate.sh`, the analyzer
`scripts/cbo_gate_analyze.py`; the scenarios live in `scripts/scenarios/`.

## The scenarios

| pair | scenario | account / walls | true tap-through rates | what it tests |
|---|---|---|---|---|
| scarce | `cbo-two-campaign-scarce` vs `-scarce-fixed` | 10 / 5 + 5 | 0.30 vs 0.10 | walls bind; moving budget is the only lever |
| ample | `cbo-two-campaign` vs `-fixed` | 20 / 10 + 10 | 0.30 vs 0.10 | slack exists; a wrong wall costs spend |
| symmetric | `cbo-two-campaign-symmetric` | 10 / 5 + 5 | 0.20 vs 0.20 | the allocator must not chase noise |

Three simulated days of 300 s each. The server must run with
`SIM_DAY_DURATION_SECONDS=300 CBO_TICK_INTERVAL=3s REAUCTION_INTERVAL=30s`
on both the api and singleton tiers.

## Running it

```
kubectl --context docker-desktop -n promovolve scale deploy/promovolve-platform --replicas=0
scripts/cbo-gate.sh --out /tmp/gate-$(date +%m%d) --seeds 4 scarce symmetric ample
```

The driver refuses to start on a loaded host (1-minute load ≥ 5), with the
platform scaled up, or without the sim env vars; `--force` overrides. It
builds `scripts/runscenario.jar` on first use, runs both arms of each pair
back to back per seed, retires each run's advertiser, and prints the
summary table at the end. Budget 16 minutes per run.

## The rules, and why

**Both arms per seed, back to back.** The fixed control has no allocator
and still swings about ±12% per 1,000 spent from one run to the next
(1802 → 2302 across one afternoon). An optimized run compared against a
control from a different hour once produced a "failed on every criterion"
that the paired control an hour later turned into +54%.

**Judge the median over at least four valid pairs.** One 3-day run
measures roughly 65 tap-throughs per arm; the same build produced −1.5%
and +93% on the scarce pair under identical conditions. A single row is
inside the noise of a ±30% criterion.

**Validity is judged on the control.** The analyzer marks a pair invalid
when the control spent under 90% of the budget-implied maximum or ran
under 85% of the expected impressions per second. The ample scenario
needs 20.00/day of serves to fill its budget; on a loaded host both arms
lose a third of their impressions, the account budget is no longer below
demand, and the scenario stops testing what it was built for — while the
run still exits 0 with a plausible number. The optimized arm's own spend
is informational: it under-spends by design when the strong campaign is
capped by what it can win.

**Wall direction is the primary diagnostic.** For each optimized run the
analyzer reports whether the campaign with the higher true rate ended the
last day holding ≥ 55% of the walls (`correct`), ≤ 45% (`INVERTED`), or
neither. Every inverted scarce run so far returned about 0% gain and
every correct one +21% to +93%; the direction count is far lower-variance
than the efficiency ratio and is what actually gates the win.

**Symmetric passes on day-end drift.** Its criterion is the day-3 split
within 15% of even (drift ≤ 0.15). An every-report bar is unreachable at
~50 tap-throughs per day for any count-driven allocator.

## Reading the summary

```
seed     pair      opt/1k   ctl/1k    gain opt CTAs ctl CTAs direction    ctl spend ctl imps/s valid
seed1    scarce      3544     1834  +93.2%      106       55 correct           100%        7.3 yes
seed2    scarce      2676     1880  +42.4%       80       55 correct            99%        7.3 yes
seed3    ample       1939     2518  -23.0%       78      113 unseparated        75%        9.7 NO

scarce: 2 valid pair(s); gain median +67.8%, range +42.4% .. +93.2%; direction correct 2, inverted 0, unseparated 0
ample: no valid pairs
```

Pass for a build: scarce direction correct in every valid seed, symmetric
drift ≤ 0.15 at day end, and the scarce gain median ≥ +30%. Ample only
counts on a quiet host.

## Reading one log

`scripts/cbo_gate_analyze.py DIR/seed1/cbo-two-campaign-scarce.log` prints
the per-tick table (server-side wall, impressions, tap-throughs, spend per
campaign), the day-by-day tap-throughs per 1,000 with day-end walls, and
the validity and direction lines. The allocator's own reasoning is in the
singleton pod's log when `LOG_CLASS=promovolve.advertiser.AdvertiserEntity`
is set — read it *during* the run, pod logs rotate within minutes.
