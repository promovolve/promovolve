#!/usr/bin/env bash
#
# k8s-local/up.sh — run the PromoVolve stack on Docker Desktop Kubernetes from
# LOCALLY-BUILT images, with no Docker Hub push/pull in the loop.
#
# WHY A SEPARATE SCRIPT FROM k8s/up.sh
#   k8s/up.sh deploys the Docker Hub `:dev` images (pull + regcred). This one
#   runs `:local` images you build on your machine — the fast inner loop for
#   dashboard/handler/creative work. The catch: Docker Desktop's Kubernetes is
#   kind-based (the node is the container `desktop-control-plane` with its OWN
#   containerd), so a `docker build` on the host is NOT visible to the cluster.
#   Enabling the "containerd image store" in Docker Desktop settings does NOT
#   bridge them either — the node's containerd is still separate. The reliable
#   fix, and what this script does, is to SIDE-LOAD the image straight into the
#   node's containerd (the `kind load docker-image` mechanism):
#
#     docker save <img> | docker exec -i <node> ctr -n k8s.io images import -
#
#   With the images present in the node and imagePullPolicy=IfNotPresent (set by
#   the k8s-local overlay), kubelet uses them directly — no registry involved.
#
# WHAT IT DOES
#   0. preflight — refuse unless the kubectl context is docker-desktop (so a
#      local deploy can't land in GKE/prod); cluster reachable; secrets present.
#   1. build   — (only with --build) docker build the :local images.
#   2. sideload— import each :local image into the node's containerd. Skipped
#      per-image if already present, UNLESS --build (rebuilt content must reload).
#   3. deploy  — kustomize the k8s-local overlay and apply.
#   4. restart — (only with --build) roll the workloads so new same-tag image
#      content is actually picked up (IfNotPresent won't restart on its own).
#   5. wait+verify — block until db → api → platform are ready; probe localhost.
#
# USAGE
#   k8s-local/up.sh              # side-load (if missing) + apply + wait
#   k8s-local/up.sh --build      # rebuild :local images, reload, apply, restart
#   k8s-local/up.sh --no-wait    # apply and exit; don't block on readiness
#   k8s-local/up.sh --yes        # skip the context-safety guard
#   k8s-local/up.sh --ns myns    # override namespace (default: promovolve)
#
# Full teardown (destroys the local DB + DData):
#   kubectl --context docker-desktop delete ns promovolve
set -euo pipefail

NS=promovolve
EXPECTED_CTX=docker-desktop
ASSUME_YES=0
DO_WAIT=1
DO_BUILD=0
# Neutral LOCAL-ONLY image names — no Docker Hub namespace (side-load means
# nothing is pushed/pulled). Must match the overlay's images: newName.
IMAGES=(localhost/promovolve-api:local localhost/promovolve-platform:local)

while [ $# -gt 0 ]; do
  case "$1" in
    --ns)      NS="$2"; shift 2 ;;
    --context) EXPECTED_CTX="$2"; shift 2 ;;
    --yes|-y)  ASSUME_YES=1; shift ;;
    --no-wait) DO_WAIT=0; shift ;;
    --build)   DO_BUILD=1; shift ;;
    -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1 (try --help)" >&2; exit 2 ;;
  esac
done

LDIR="$(cd "$(dirname "$0")" && pwd)"           # k8s-local/
ROOT="$(cd "$LDIR/.." && pwd)"                   # repo root
kc() { kubectl --context "$EXPECTED_CTX" -n "$NS" "$@"; }
die() { echo "ERROR: $*" >&2; exit 1; }

echo "PromoVolve k8s-local up — context '$EXPECTED_CTX', namespace '$NS'"

# --- 0. preflight ----------------------------------------------------------
CUR_CTX="$(kubectl config current-context 2>/dev/null || true)"
if [ "$CUR_CTX" != "$EXPECTED_CTX" ] && [ "$ASSUME_YES" -ne 1 ]; then
  die "current context is '${CUR_CTX:-<none>}', expected '$EXPECTED_CTX'.
  This runs a full local stack — refusing so it can't hit the wrong cluster.
  Switch context, or pass --context '$CUR_CTX', or --yes to override."
fi
kubectl --context "$EXPECTED_CTX" cluster-info >/dev/null 2>&1 \
  || die "context '$EXPECTED_CTX' not reachable (is Docker Desktop Kubernetes enabled?)"
[ -f "$ROOT/k8s/secrets.env" ] \
  || die "missing k8s/secrets.env — copy k8s/secrets.env.example and fill it in (the overlay reuses the base secrets)."

# Discover the single kind node that backs Docker Desktop k8s. The side-load
# imports into THIS container's containerd (namespace k8s.io = what kubelet uses).
NODE="$(kubectl --context "$EXPECTED_CTX" get nodes -o jsonpath='{.items[0].metadata.name}')"
docker exec "$NODE" ctr --version >/dev/null 2>&1 \
  || die "cannot 'docker exec $NODE ctr' — this side-load path needs the kind-based
  Docker Desktop Kubernetes (node runs as a docker container). Node: '$NODE'."
echo "  node: $NODE"

# --- 1. build (optional) ---------------------------------------------------
if [ "$DO_BUILD" -eq 1 ]; then
  echo "==> building :local images (this is the slow part)"
  docker build -f "$ROOT/Dockerfile.api"      -t localhost/promovolve-api:local      "$ROOT"
  docker build -f "$ROOT/platform/Dockerfile" -t localhost/promovolve-platform:local "$ROOT/platform"
fi

# --- 2. side-load into the node's containerd -------------------------------
loaded_in_node() { docker exec "$NODE" ctr -n k8s.io images ls 2>/dev/null | grep -qF -- "$1"; }
for img in "${IMAGES[@]}"; do
  if [ "$DO_BUILD" -ne 1 ] && loaded_in_node "$img"; then
    echo "==> $img already in node — skipping side-load (use --build to reload)"
    continue
  fi
  echo "==> side-loading $img into $NODE (large images take a minute)"
  docker image inspect "$img" >/dev/null 2>&1 \
    || die "image '$img' not built. Run with --build, or build it first (see README)."
  docker save "$img" | docker exec -i "$NODE" ctr -n k8s.io images import - >/dev/null
done

# --- 3. deploy (idempotent) ------------------------------------------------
echo "==> ensuring namespace '$NS'"
kubectl --context "$EXPECTED_CTX" create namespace "$NS" --dry-run=client -o yaml \
  | kubectl --context "$EXPECTED_CTX" apply -f - >/dev/null
echo "==> applying k8s-local overlay"
# --load-restrictor: db-initdb is single-sourced from ../docker/init-db.sql,
# outside the kustomize root (same as k8s/up.sh).
kubectl kustomize --load-restrictor LoadRestrictionsNone "$LDIR" \
  | kubectl --context "$EXPECTED_CTX" apply -f -

# --- 4. restart to pick up rebuilt same-tag images -------------------------
# IfNotPresent + an unchanged :local tag means kubelet won't restart pods on its
# own when the image CONTENT changed. After a rebuild, force it.
if [ "$DO_BUILD" -eq 1 ]; then
  echo "==> rolling workloads onto the rebuilt images"
  kc rollout restart statefulset/promovolve-api deployment/promovolve-platform >/dev/null || true
fi

if [ "$DO_WAIT" -ne 1 ]; then
  echo "==> --no-wait: applied. Watch: kubectl --context $EXPECTED_CTX -n $NS get pods -w"
  exit 0
fi

# --- 5. wait + verify ------------------------------------------------------
echo "==> waiting for rollout (cold start ~5-8 min: DB init -> Pekko cluster form)"
kc rollout status statefulset/promovolve-db      --timeout=240s
kc rollout status statefulset/promovolve-api     --timeout=480s
kc rollout status deployment/promovolve-platform --timeout=180s

echo "==> verifying (Docker Desktop maps LoadBalancers to localhost)"
DASH="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:9090/ 2>/dev/null || true)"
API="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/v1/layout-templates 2>/dev/null || true)"
echo "    dashboard http://localhost:9090        -> HTTP ${DASH:-unreachable}"
echo "    api       http://localhost:8080/docs   -> HTTP ${API:-unreachable} (layout-templates probe)"

cat <<EOF

Done. Dashboard: http://localhost:9090   API/Swagger: http://localhost:8080/docs
Fresh/empty DB -> the dashboard redirects to /setup to create the first account.
Iterate after a code change:  k8s-local/up.sh --build
Full teardown (wipes local data):  kubectl --context $EXPECTED_CTX delete ns $NS
EOF
