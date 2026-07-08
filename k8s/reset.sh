#!/usr/bin/env bash
#
# k8s/reset.sh — reset the PromoVolve runtime state on a Kubernetes cluster to
# a clean, known-good baseline.
#
# WHY THIS EXISTS
#   The runtime state lives in two places that must be wiped *together*, in the
#   right order:
#     • Postgres PVC (data-promovolve-db-0): campaigns, creatives, sites,
#       LOGINS, the entity DurableState table, event journals, projections.
#     • DData PVCs (ddata-promovolve-api-0..N, one per api replica), mounted at
#       /data — two LMDB stores per pod:
#         ddata-promovolve-ddataReplicator-*  → CRDT DData: ServeIndex, page
#                                                classifications, approvals, floor
#         ddata-promovolve-entityReplicator-* → the REMEMBER-ENTITIES list
#   The entityReplicator store is the gotcha: if you wipe Postgres but leave it
#   (or leave the api running so it re-fills), cluster sharding re-activates the
#   old campaign/advertiser/site entities and they re-persist themselves — so
#   wiped campaigns "come back" as empty ghosts.
#
#   Therefore the only reliable order is:
#     1. scale the writers (api, singleton, db) to 0  ← nothing can re-seed
#     2. delete the PVC(s)
#     3. bring db, then singleton, then api back up    ← fresh, empty volumes
#
#   It also CANNOT undo a stale browser session: an old JWT or an open
#   ?campaignId=... tab will re-mint entities the instant the api is back. After
#   running this, log out / close tabs / use a fresh private window before
#   touching the UI. The script prints that reminder at the end.
#
# WHAT SURVIVES: committed code, k8s manifests/config, R2 assets/fonts, the
# GH-Pages publisher demo. Only the cluster's runtime data is wiped.
#
# USAGE
#   k8s/reset.sh                # full reset (DB + DData); interactive confirm
#   k8s/reset.sh --ddata-only   # clear DData only (keep campaigns/creatives/login)
#   k8s/reset.sh --yes          # skip the confirmation prompt
#   k8s/reset.sh --ns myns      # override namespace (default: promovolve)
set -euo pipefail

NS=promovolve
ASSUME_YES=0
DDATA_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --ns)         NS="$2"; shift 2 ;;
    --yes|-y)     ASSUME_YES=1; shift ;;
    --ddata-only) DDATA_ONLY=1; shift ;;
    -h|--help)    sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1 (try --help)" >&2; exit 2 ;;
  esac
done

DB_PVC=data-promovolve-db-0
kc() { kubectl -n "$NS" "$@"; }

# The api StatefulSet has ONE DData (LMDB) PVC per replica (ddata-promovolve-api-0,
# -1, …). Enumerate the live ones so we wipe ALL of them — leaving any behind
# re-seeds remember-entities → ghost entities. Space-separated (no arrays, to
# stay safe under `set -u` on the bash 3.2 that ships with macOS).
DDATA_PVCS="$(kc get pvc -o name 2>/dev/null | sed 's#.*/##' | grep -E '^ddata-promovolve-(api|singleton)-' | tr '\n' ' ' || true)"

# Remember the DESIRED replica counts before we scale to 0, so we restore the
# real topology (entity tier = odd count ≥3) instead of hardcoding. Fall back
# to the manifest defaults if the live read is empty. (The crawler tier was
# DELETED 2026-07-02 — its role is folded into the api pods, see
# application-app.conf.)
API_REPLICAS="$(kc get statefulset promovolve-api -o jsonpath='{.spec.replicas}' 2>/dev/null || true)"
SINGLETON_REPLICAS="$(kc get statefulset promovolve-singleton -o jsonpath='{.spec.replicas}' 2>/dev/null || true)"
{ [ -n "${API_REPLICAS:-}" ] && [ "$API_REPLICAS" -ge 1 ] 2>/dev/null; } || API_REPLICAS=2
# Singleton tier is parked at 0 (role consolidated onto the api pods,
# 2026-07-02) — 0 is a valid steady state; only restore what was running.
{ [ -n "${SINGLETON_REPLICAS:-}" ] && [ "$SINGLETON_REPLICAS" -ge 0 ] 2>/dev/null; } || SINGLETON_REPLICAS=0

echo "PromoVolve k8s reset — namespace '$NS'"
if [ "$DDATA_ONLY" -eq 1 ]; then
  echo "  MODE: DData-only — wipes ServeIndex / classifications / approvals / floor /"
  echo "        remember-entities. KEEPS campaigns, creatives, sites, logins (Postgres)."
  PVCS_TO_DELETE="$DDATA_PVCS"
else
  echo "  MODE: FULL — wipes Postgres ($DB_PVC: campaigns, creatives, sites, LOGINS,"
  echo "        durable_state, journals) AND DData (${DDATA_PVCS:-<none found>})."
  PVCS_TO_DELETE="$DDATA_PVCS $DB_PVC"
fi
echo "  Restores api→${API_REPLICAS} / singleton→${SINGLETON_REPLICAS} replicas."
echo "  Committed code/config + R2 assets are NOT touched."

if [ "$ASSUME_YES" -ne 1 ]; then
  printf "Type 'wipe' to proceed: "
  read -r ans
  [ "$ans" = "wipe" ] || { echo "aborted."; exit 1; }
fi

echo "==> scaling api + singleton to 0 (stop entity activation so nothing re-seeds)"
kc scale statefulset promovolve-api --replicas=0
kc scale statefulset promovolve-singleton --replicas=0 2>/dev/null || true
if [ "$DDATA_ONLY" -ne 1 ]; then
  kc scale statefulset promovolve-db --replicas=0
fi
# Wait for ALL entity pods (app tier every replica + the singleton tier), not just ordinal 0.
kc wait --for=delete pod -l app=promovolve-api,tier=app --timeout=120s || true
kc wait --for=delete pod -l app=promovolve-api,tier=singleton --timeout=120s || true
[ "$DDATA_ONLY" -ne 1 ] && kc wait --for=delete pod/promovolve-db-0 --timeout=120s || true

echo "==> deleting PVC(s): ${PVCS_TO_DELETE:-<none>}"
# Intentional word-split of the space-separated PVC list (shellcheck SC2086).
# shellcheck disable=SC2086
[ -n "${PVCS_TO_DELETE// /}" ] && kc delete pvc $PVCS_TO_DELETE --ignore-not-found --timeout=90s \
  || echo "  (no matching PVCs found — nothing to delete)"

if [ "$DDATA_ONLY" -ne 1 ]; then
  echo "==> bringing DB up (fresh volume re-runs init-db.sql)"
  kc scale statefulset promovolve-db --replicas=1
  kc rollout status statefulset/promovolve-db --timeout=180s
fi

echo "==> bringing singleton + api up (fresh DData) — singleton→${SINGLETON_REPLICAS}, api→${API_REPLICAS}"
kc scale statefulset promovolve-singleton --replicas="$SINGLETON_REPLICAS" 2>/dev/null || true
kc scale statefulset promovolve-api --replicas="$API_REPLICAS"
kc rollout status statefulset/promovolve-singleton --timeout=240s 2>/dev/null || true
kc rollout status statefulset/promovolve-api --timeout=240s

echo "==> restarting platform (refresh its DB connection)"
kc rollout restart deployment/promovolve-platform >/dev/null
kc rollout status deployment/promovolve-platform --timeout=150s

echo "==> verifying baseline"
if [ "$DDATA_ONLY" -ne 1 ]; then
  kc exec promovolve-db-0 -- psql -U promovolve -d promovolve -t -A -c \
    "select 'creatives='||count(*) from creative; \
     select 'users='||count(*) from platform_users; \
     select 'durable_state='||count(*) from durable_state;" 2>/dev/null | grep -v '^$' || true
fi
kc exec promovolve-api-0 -- sh -c 'echo "ddata LMDB files:"; find /data -name data.mdb -printf "  %s bytes  %p\n" 2>/dev/null || true' 2>/dev/null || true

cat <<'EOF'

Done. ┃ IMPORTANT — clear your browser before reopening the UI:
  • log out, close all promovolve tabs (especially any ?campaignId=… URL),
  • use a fresh / private window, then register a new account.
A stale JWT or open tab re-activates old entities and they will reappear as
empty "ghost" campaigns (the remember-entities + durable_state re-seed).
EOF
