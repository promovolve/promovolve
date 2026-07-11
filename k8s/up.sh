#!/usr/bin/env bash
#
# k8s/up.sh — bring the PromoVolve stack UP on a local cluster from nothing.
#
# WHY THIS EXISTS
#   Docker Desktop's built-in (multi-node) Kubernetes does not survive a VM
#   reset: a restart under disk pressure (or a DD update) recreates the cluster
#   from scratch, taking the `promovolve` namespace, every PVC/PV, and ALL data
#   (Postgres + DData) with it. When that happens there is nothing to "restart"
#   — the whole stack has to be re-deployed. reset.sh does NOT help: it scales
#   and wipes an ALREADY-deployed stack. This script is its complement — the
#   from-zero bring-up — so recovery is one command instead of a sequence.
#
#   It is the safe sibling of reset.sh: purely ADDITIVE. It never deletes a
#   namespace, PVC, or PV, so it can never cost you data. Re-running it on a
#   healthy cluster is a no-op (every step is idempotent — `apply` reconciles,
#   regcred is skipped if present, rollout waits return immediately when ready).
#
# WHAT IT DOES
#   0. preflight — refuse to run unless the kubectl context is the expected
#      LOCAL one (default: docker-desktop), so a deploy can't land in GKE/prod
#      by accident; check the cluster is reachable and secrets.env exists.
#   1. namespace — create `promovolve` if missing.
#   2. regcred — the Docker Hub image-pull secret (private repos). Minted from
#      your EXISTING `docker login` via the macOS keychain helper, so you never
#      handle an access token. Skipped if it already exists.
#   3. deploy — `kubectl kustomize --load-restrictor … | kubectl apply -f -`
#      (the --load-restrictor is required: db-initdb is single-sourced from
#      ../docker/init-db.sql, outside the kustomize root).
#   4. wait + verify — block until db → api → crawler → platform are ready
#      (~7–8 min on a cold cluster: image pulls → DB init → cluster formation),
#      then confirm the Pekko members are Up and the dashboard answers.
#
# WHAT IT DELIBERATELY DOES NOT DO
#   • Wipe/delete anything (that's reset.sh).
#   • Build or push images — it assumes hanishi/promovolve-{api,platform}:dev
#     are already on Docker Hub (see README §1 / `make`). A pull failure is
#     surfaced by the rollout wait rather than hidden.
#
#   After a FULL wipe the DB comes back EMPTY — register a fresh account at the
#   dashboard URL printed at the end. If the cluster only RESTARTED (PVs
#   survived), the pods come back on their own and this just reports them.
#
# USAGE
#   k8s/up.sh                  # bring the stack up (or reconcile if already up)
#   k8s/up.sh --no-wait        # apply and exit; don't block on readiness
#   k8s/up.sh --yes            # skip the context-safety guard
#   k8s/up.sh --context NAME   # allow a different kubectl context than docker-desktop
#   k8s/up.sh --ns myns        # override namespace (default: promovolve)
set -euo pipefail

NS=promovolve
EXPECTED_CTX=docker-desktop
ASSUME_YES=0
DO_WAIT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --ns)        NS="$2"; shift 2 ;;
    --context)   EXPECTED_CTX="$2"; shift 2 ;;
    --yes|-y)    ASSUME_YES=1; shift ;;
    --no-wait)   DO_WAIT=0; shift ;;
    -h|--help)   sed -n '2,52p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1 (try --help)" >&2; exit 2 ;;
  esac
done

# Resolve the k8s dir from the script's own location so it works from any CWD.
KDIR="$(cd "$(dirname "$0")" && pwd)"
kc() { kubectl -n "$NS" "$@"; }
die() { echo "ERROR: $*" >&2; exit 1; }

echo "PromoVolve k8s up — namespace '$NS'"

# --- 0. preflight ----------------------------------------------------------
CUR_CTX="$(kubectl config current-context 2>/dev/null || true)"
echo "  kubectl context: ${CUR_CTX:-<none>}"
if [ "$CUR_CTX" != "$EXPECTED_CTX" ] && [ "$ASSUME_YES" -ne 1 ]; then
  die "context is '${CUR_CTX:-<none>}', expected '$EXPECTED_CTX'. This deploys a
  full stack — refusing so it can't land in the wrong cluster (e.g. GKE/prod).
  Re-run with --context '$CUR_CTX' if intended, or --yes to skip this guard."
fi
kubectl cluster-info >/dev/null 2>&1 || die "cluster not reachable (is Docker Desktop Kubernetes running?)"
[ -f "$KDIR/secrets.env" ] || die "missing $KDIR/secrets.env — copy secrets.env.example and fill in GEMINI_API_KEY / R2_* / JDBC_PASSWORD."

# --- 1. namespace (idempotent) ---------------------------------------------
echo "==> ensuring namespace '$NS'"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# --- 2. regcred (create only if missing) -----------------------------------
if kc get secret regcred >/dev/null 2>&1; then
  echo "==> regcred already present — skipping"
else
  echo "==> creating regcred from your keychain Docker login"
  command -v docker-credential-desktop >/dev/null 2>&1 \
    || die "docker-credential-desktop not found. Either log in with 'docker login',
  or create regcred manually with a Docker Hub access token (see README §2)."
  command -v python3 >/dev/null 2>&1 || die "python3 required to parse the keychain credential."
  CRED="$(echo 'https://index.docker.io/v1/' | docker-credential-desktop get 2>/dev/null || true)"
  DUSER="$(printf '%s' "$CRED" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Username"])' 2>/dev/null || true)"
  DSECRET="$(printf '%s' "$CRED" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Secret"])' 2>/dev/null || true)"
  [ -n "$DUSER" ] && [ -n "$DSECRET" ] \
    || die "couldn't read Docker Hub creds from the keychain. Run 'docker login' first,
  or create regcred manually with an access token (README §2)."
  kc create secret docker-registry regcred \
    --docker-server=https://index.docker.io/v1/ \
    --docker-username="$DUSER" --docker-password="$DSECRET" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "    regcred created for docker user: $DUSER"
fi

# --- 3. deploy (idempotent; apply reconciles) ------------------------------
echo "==> applying manifests (kustomize)"
kubectl kustomize --load-restrictor LoadRestrictionsNone "$KDIR" | kubectl apply -f -

if [ "$DO_WAIT" -ne 1 ]; then
  echo "==> --no-wait: manifests applied. Watch with: kubectl -n $NS get pods -w"
  exit 0
fi

# --- 4. wait for readiness (db → api → crawler → platform) -----------------
# Order matters: the DB must init (init-db.sql on a fresh volume) before the
# api can persist; the api forms the Pekko cluster (startupProbe allows ~5 min)
# before the platform is useful. rollout status adapts to the manifest replica
# counts and returns instantly when a workload is already ready.
echo "==> waiting for rollout (cold start ~7-8 min: image pulls -> DB init -> cluster form)"
kc rollout status statefulset/promovolve-db       --timeout=240s
kc rollout status statefulset/promovolve-api      --timeout=420s
kc rollout status deployment/promovolve-platform  --timeout=180s

# --- verify -----------------------------------------------------------------
echo "==> verifying"
kc exec promovolve-api-0 -c api -- sh -c 'wget -qO- http://localhost:8558/cluster/members 2>/dev/null' 2>/dev/null \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); up=sum(1 for m in d["members"] if m["status"]=="Up"); print(f"    cluster: {up}/{len(d[\"members\"])} members Up, {len(d[\"unreachable\"])} unreachable, leader={\"set\" if d[\"leader\"] else \"NONE\"}")' 2>/dev/null \
  || echo "    (cluster members check skipped)"
# Docker Desktop maps the LoadBalancer services to localhost (no port-forward).
DASH="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/ 2>/dev/null || true)"
echo "    dashboard http://localhost:9090 -> HTTP ${DASH:-unreachable}"

cat <<EOF

Done. Open the dashboard: http://localhost:9090   (API/Swagger: http://localhost:8080/docs)
If this was a fresh/wiped cluster the DB is EMPTY — register a new account in the UI.
EOF
