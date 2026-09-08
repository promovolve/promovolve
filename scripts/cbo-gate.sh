#!/usr/bin/env bash
#
# CBO gate: paired-seed runs of the cbo-two-campaign scenarios against the
# docker-desktop dev cluster (GH #38, #95).
#
# Usage:
#   scripts/cbo-gate.sh --out DIR [--seeds N] [--force] PAIR...
#
#   PAIR is one of: scarce   (cbo-two-campaign-scarce + -scarce-fixed)
#                   ample    (cbo-two-campaign + -fixed)
#                   symmetric (cbo-two-campaign-symmetric; no control)
#
#   scripts/cbo-gate.sh --out /tmp/gate --seeds 4 scarce symmetric
#
# Every seed runs BOTH arms of a pair back to back: the fixed control swings
# about +-12% run to run, so an optimized number is only meaningful against a
# control drawn from the same conditions. One run is inside the noise of the
# +-30% criterion; judge the summary's median over >= 4 valid pairs.
#
# Refuses to start (override with --force) when:
#   - host 1-minute load >= 5: the ample scenario is throughput-bound and a
#     loaded host silently starves BOTH arms (exit 0, plausible numbers);
#   - the platform deployment is scaled above 0: its wallet sweep (:09/:39)
#     suspends harness advertisers mid-run;
#   - the api statefulset lacks SIM_DAY_DURATION_SECONDS / CBO_TICK_INTERVAL.
#
# Server-side env for a 300 s day (set on api AND singleton):
#   SIM_DAY_DURATION_SECONDS=300 CBO_TICK_INTERVAL=3s REAUCTION_INTERVAL=30s
#   LOG_CLASS=promovolve.advertiser.AdvertiserEntity   (allocator trace)
#
# Harness: RunScenario packaged as a jar (scala-cli's resident compiler got
# a 12-run chain OOM-killed; the jar with -Xmx512m did not). Built on first
# use into scripts/runscenario.jar (gitignored):
#   scala-cli --power package scripts/RunScenario.scala -o scripts/runscenario.jar
#
# Each run's advertiser is retired at the end (daily budget 0, manual mode):
# harness advertisers share additionalCategories 653/654/655 and a live one
# from an earlier run takes impressions from the next.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CTX="${KUBE_CONTEXT:-docker-desktop}"
NS="${KUBE_NAMESPACE:-promovolve}"
API="${API:-http://localhost:8080}"
JAR="${JAR:-$HERE/runscenario.jar}"
OUT=""
SEEDS=1
FORCE=0
PAIRS=()

while [[ $# -gt 0 ]]; do
  case $1 in
    --out) OUT="$2"; shift 2 ;;
    --seeds) SEEDS="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --context) CTX="$2"; shift 2 ;;
    --namespace) NS="$2"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    scarce|ample|symmetric) PAIRS+=("$1"); shift ;;
    *) echo "Unknown: $1" >&2; exit 1 ;;
  esac
done
[[ -n "$OUT" && ${#PAIRS[@]} -gt 0 ]] || { echo "need --out DIR and at least one PAIR (scarce|ample|symmetric)" >&2; exit 1; }

kc() { kubectl --context "$CTX" -n "$NS" "$@"; }
refuse() { echo "REFUSING: $*" >&2; [[ $FORCE -eq 1 ]] && { echo "  (--force given, continuing)" >&2; return 0; }; exit 2; }

# ---- preflight ---------------------------------------------------------------
load1=$(uptime | sed -E 's/.*load average[s]?: *//' | tr -d ',' | awk '{print $1}')
if awk -v l="$load1" 'BEGIN{exit !(l >= 5)}'; then
  refuse "host load $load1 >= 5. Close IDEs and wait; the ample scenario starves silently on a loaded host."
fi

replicas=$(kc get deploy promovolve-platform -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "?")
if [[ "$replicas" != "0" ]]; then
  refuse "platform deployment has $replicas replica(s); its wallet sweep suspends harness advertisers. Run: kubectl --context $CTX -n $NS scale deploy/promovolve-platform --replicas=0"
fi

envs=$(kc get statefulset promovolve-api -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value} {end}' 2>/dev/null || true)
for v in SIM_DAY_DURATION_SECONDS CBO_TICK_INTERVAL; do
  echo "$envs" | grep -q "$v=" || refuse "api statefulset lacks $v (set it on api AND singleton; see the header)"
done

if [[ ! -f "$JAR" ]]; then
  command -v scala-cli >/dev/null || { echo "no $JAR and no scala-cli to build it" >&2; exit 1; }
  echo "building $JAR ..."
  scala-cli --power package "$HERE/RunScenario.scala" -o "$JAR" -f
fi
command -v java >/dev/null || { echo "java not found" >&2; exit 1; }
command -v node >/dev/null || { echo "node not found (used to retire advertisers)" >&2; exit 1; }
mkdir -p "$OUT"

echo "gate: pairs=${PAIRS[*]} seeds=$SEEDS out=$OUT load=$load1 api digest=$(kc get statefulset promovolve-api -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's/.*@sha256://' | cut -c1-12)"

# ---- helpers -----------------------------------------------------------------
settle() {
  # Wait until the api pod is ready and has been quiet for a moment.
  local i n ready
  for i in $(seq 1 16); do
    n=$(kc logs promovolve-api-0 --since=45s 2>/dev/null | grep -c "Connection is not available\|Exception during recovery" || true)
    ready=$(kc get pod promovolve-api-0 -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null || echo false)
    if [[ "$n" == "0" && $i -gt 2 && "$ready" == "true" ]]; then break; fi
    sleep 30
  done
  echo "  settled after ~$((i*30))s"
}

retire() {
  # Zero the run's advertiser so it cannot bid against the next run.
  local log="$1" adv
  adv=$(grep -o "adv-1-[0-9-]*" "$log" | head -1 || true)
  [[ -n "$adv" ]] || { echo "  (no advertiser id in $log)"; return 0; }
  node -e "
    const a='$adv', api='$API';
    fetch(api+'/v1/advertisers/'+a).then(r=>r.json()).then(j=>console.log('  advertiser '+a+' status at end:', j.status)).catch(()=>{});
    fetch(api+'/v1/advertisers/'+a+'/budget',{method:'PUT',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({dailyBudget:'0',budgetMode:'manual'})}).then(r=>console.log('  retired '+a+' ('+r.status+')')).catch(e=>console.log('  retire failed', e.message));
  "
}

run_one() {
  local dir="$1" sc="$2" log="$dir/$sc.log"
  echo "=== $sc start $(date +%H:%M:%S) ==="
  settle
  java -Xmx512m -jar "$JAR" --scenario "$ROOT/scripts/scenarios/$sc.json" > "$log" 2>&1 || echo "  exit=$? (see $log)"
  echo "=== $sc exit $(date +%H:%M:%S) ==="
  grep -n "STOPPING\|Exception in\|ERROR" "$log" | tail -2 || true
  retire "$log"
  python3 "$HERE/cbo_gate_analyze.py" "$log" | tail -8
}

# ---- run ---------------------------------------------------------------------
for s in $(seq 1 "$SEEDS"); do
  dir="$OUT/seed$s"
  mkdir -p "$dir"
  for p in "${PAIRS[@]}"; do
    case $p in
      scarce)    run_one "$dir" cbo-two-campaign-scarce; run_one "$dir" cbo-two-campaign-scarce-fixed ;;
      ample)     run_one "$dir" cbo-two-campaign;        run_one "$dir" cbo-two-campaign-fixed ;;
      symmetric) run_one "$dir" cbo-two-campaign-symmetric ;;
    esac
  done
  echo "--- seed $s complete $(date +%H:%M:%S)"
done

echo
echo "=== SUMMARY ($OUT) ==="
python3 "$HERE/cbo_gate_analyze.py" --summary "$OUT"
