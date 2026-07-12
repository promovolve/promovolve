#!/usr/bin/env bash
#
# k8s-gke/setup.sh — provision the GKE deployment from nothing (idempotent).
#
# THE deployment: PromoVolve has no production tier above this (we don't
# operate it as a business), so this cluster is deliberately staging-grade:
#   • zonal (asia-northeast1-a) — the $74.40/mo free-tier credit covers the
#     cluster management fee for ONE zonal cluster per billing account
#   • ONE spot c4a-standard-4 ARM node (~60-70% off; preemption = a few minutes
#     of Pekko self-heal, acceptable here)
#   • in-cluster Postgres, images stay on private Docker Hub via regcred
#
# WHAT IT DOES (every step skips if already done — safe to re-run):
#   1. project   — create $PROJECT_ID, link billing, enable the GKE API
#   2. network   — reserve the global static IP `promovolve-ingress`
#   3. cluster   — 1-node spot zonal cluster; workload log ingestion to
#                  Cloud Logging is OFF (--logging=SYSTEM) to keep the two
#                  chatty JVMs from eating the 50GB/mo free logging tier;
#                  `kubectl logs` is unaffected (reads kubelet directly)
#   4. deploy    — namespace, regcred (from your keychain docker login),
#                  kustomize apply of k8s-gke (base + GCLB overlay)
#   5. verify    — rollout waits + Pekko member check, then print the DNS
#                  records to create at Cloudflare (DNS-ONLY / grey cloud)
#
# PREREQS
#   • gcloud auth login done (interactive), and a billing account you can link
#   • docker login done (regcred is minted from the keychain, like up.sh)
#   • k8s/secrets.env + k8s/platform-secrets.env filled in
#   • images pushed to Docker Hub at the digests pinned in k8s/kustomization.yaml
#
# USAGE
#   k8s-gke/setup.sh                 # full provision + deploy + verify
#   k8s-gke/setup.sh --deploy-only   # skip GCP provisioning, just (re)apply
#   PROJECT_ID=promovolve-xyz k8s-gke/setup.sh   # if `promovolve` ID is taken
#   BILLING_ACCOUNT=XXXXXX-... k8s-gke/setup.sh  # pick a billing account
set -euo pipefail

# ARM (Axion) NODES, NOT x86: images are built on the Apple-silicon dev Mac
# and pushed single-arch arm64 — on e2/x86 nodes every binary dies with
# `exec format error` (learned the hard way 2026-07-11). c4a keeps the
# laptop push workflow unchanged. asia-northeast1-b is the only Tokyo zone
# with c4a-standard-*. C4A attaches HYPERDISK ONLY (no pd-balanced) — hence
# the boot --disk-type below and the hyperdisk-balanced StorageClass +
# volumeClaimTemplate patches in this overlay.
PROJECT_ID="${PROJECT_ID:-promovolve}"
ZONE="${ZONE:-asia-northeast1-b}"
CLUSTER="${CLUSTER:-promovolve}"
MACHINE="${MACHINE:-c4a-standard-4}"
NS=promovolve
IP_NAME=promovolve-ingress
CTX="gke_${PROJECT_ID}_${ZONE}_${CLUSTER}"

DEPLOY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --deploy-only) DEPLOY_ONLY=1; shift ;;
    --pin-images)  PIN_IMAGES=1; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1 (try --help)" >&2; exit 2 ;;
  esac
done

KDIR="$(cd "$(dirname "$0")" && pwd)"           # k8s-gke
BASEDIR="$(cd "$KDIR/../k8s" && pwd)"           # k8s (base + secrets.env)
# Explicit --context on every kubectl call: this script must NEVER land a
# deploy in the docker-desktop context (the inverse of up.sh's guard).
kc()  { kubectl --context "$CTX" -n "$NS" "$@"; }
kcg() { kubectl --context "$CTX" "$@"; }
die() { echo "ERROR: $*" >&2; exit 1; }

command -v gcloud  >/dev/null || die "gcloud not found"
command -v kubectl >/dev/null || die "kubectl not found"
[ -f "$BASEDIR/secrets.env" ]          || die "missing k8s/secrets.env"
[ -f "$BASEDIR/platform-secrets.env" ] || die "missing k8s/platform-secrets.env"

if [ "$DEPLOY_ONLY" -ne 1 ]; then
  # --- 1. project ------------------------------------------------------------
  echo "==> project '$PROJECT_ID'"
  if ! gcloud projects describe "$PROJECT_ID" >/dev/null 2>&1; then
    echo "    creating (if this fails with 'already exists', the global ID is"
    echo "    taken by someone else — re-run with PROJECT_ID=promovolve-<suffix>)"
    gcloud projects create "$PROJECT_ID" --name="PromoVolve"
  fi
  BILLING="${BILLING_ACCOUNT:-$(gcloud billing accounts list --filter='open=true' --format='value(name)' | head -1)}"
  [ -n "$BILLING" ] || die "no open billing account visible; pass BILLING_ACCOUNT=..."
  if [ "$(gcloud billing projects describe "$PROJECT_ID" --format='value(billingEnabled)' 2>/dev/null)" != "True" ]; then
    echo "    linking billing account $BILLING"
    gcloud billing projects link "$PROJECT_ID" --billing-account="$BILLING"
  fi
  gcloud services enable container.googleapis.com --project "$PROJECT_ID"

  # --- 2. static IP for the Ingress -------------------------------------------
  echo "==> global static IP '$IP_NAME'"
  gcloud compute addresses describe "$IP_NAME" --global --project "$PROJECT_ID" >/dev/null 2>&1 \
    || gcloud compute addresses create "$IP_NAME" --global --project "$PROJECT_ID"

  # --- 3. cluster --------------------------------------------------------------
  echo "==> cluster '$CLUSTER' ($ZONE, 1x $MACHINE spot)"
  if ! gcloud container clusters describe "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" >/dev/null 2>&1; then
    gcloud container clusters create "$CLUSTER" \
      --project "$PROJECT_ID" --zone "$ZONE" \
      --machine-type "$MACHINE" --spot --num-nodes 1 \
      --disk-type hyperdisk-balanced --disk-size 50 \
      --release-channel regular \
      --logging=SYSTEM --monitoring=SYSTEM
  fi
  gcloud container clusters get-credentials "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID"
  # get-credentials makes GKE the CURRENT kubectl context — which silently
  # redirects any bare `kubectl` in OTHER terminals/sessions (one already
  # applied the dev base onto GKE this way, 2026-07-11). This script pins
  # --context on every call, so hand the default back to docker-desktop.
  kubectl config use-context docker-desktop >/dev/null 2>&1 || true
fi

kcg cluster-info >/dev/null 2>&1 || die "cluster not reachable via context $CTX (run without --deploy-only first?)"

# --- 4. deploy ----------------------------------------------------------------
echo "==> namespace '$NS'"
kcg create namespace "$NS" --dry-run=client -o yaml | kcg apply -f - >/dev/null

if kc get secret regcred >/dev/null 2>&1; then
  echo "==> regcred already present — skipping"
else
  echo "==> creating regcred from your keychain Docker login (private Docker Hub repos)"
  command -v docker-credential-desktop >/dev/null 2>&1 \
    || die "docker-credential-desktop not found — 'docker login' first, or create regcred manually (k8s/README.md §2)"
  CRED="$(echo 'https://index.docker.io/v1/' | docker-credential-desktop get 2>/dev/null || true)"
  DUSER="$(printf '%s' "$CRED" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Username"])' 2>/dev/null || true)"
  DSECRET="$(printf '%s' "$CRED" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Secret"])' 2>/dev/null || true)"
  [ -n "$DUSER" ] && [ -n "$DSECRET" ] || die "couldn't read Docker Hub creds from the keychain — 'docker login' first"
  kc create secret docker-registry regcred \
    --docker-server=https://index.docker.io/v1/ \
    --docker-username="$DUSER" --docker-password="$DSECRET" \
    --dry-run=client -o yaml | kcg apply -n "$NS" -f - >/dev/null
  echo "    regcred created for docker user: $DUSER"
fi

# Preserve CI-deployed images across manifest applies. The kustomize render
# carries the digest pins from k8s/kustomization.yaml, which go stale the
# moment CI deploys (CI rolls images by digest, never the pins) — a naive
# apply therefore ROLLS THE APP BACK (happened live 2026-07-12: an
# infra-only deploy reverted four shipped fixes). Default: capture the live
# images before the apply and restore them after, so manual deploys are
# infra/config-only. Pass --pin-images to deliberately deploy the pinned
# digests (the pre-CI manual flow).
LIVE_API=""; LIVE_SINGLETON=""; LIVE_PLATFORM=""
if [ "${PIN_IMAGES:-0}" -ne 1 ]; then
  LIVE_API=$(kc get statefulset promovolve-api -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
  LIVE_SINGLETON=$(kc get statefulset promovolve-singleton -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
  LIVE_PLATFORM=$(kc get deployment promovolve-platform -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
fi

echo "==> applying manifests (kustomize overlay k8s-gke)"
kubectl kustomize --load-restrictor LoadRestrictionsNone "$KDIR" | kcg apply -f -

if [ "${PIN_IMAGES:-0}" -ne 1 ]; then
  echo "==> restoring live (CI-deployed) images over the manifest pins"
  [ -n "$LIVE_API" ]       && kc set image statefulset/promovolve-api       api="$LIVE_API"             >/dev/null
  [ -n "$LIVE_SINGLETON" ] && kc set image statefulset/promovolve-singleton singleton="$LIVE_SINGLETON" >/dev/null
  [ -n "$LIVE_PLATFORM" ]  && kc set image deployment/promovolve-platform   platform="$LIVE_PLATFORM"   >/dev/null
fi

# --- 5. wait + verify -----------------------------------------------------------
echo "==> waiting for rollout (cold start: image pulls -> DB init -> cluster form)"
kc rollout status statefulset/promovolve-db       --timeout=300s
kc rollout status statefulset/promovolve-api      --timeout=600s
kc rollout status deployment/promovolve-platform  --timeout=300s

kc exec promovolve-api-0 -c api -- sh -c 'wget -qO- http://localhost:8558/cluster/members 2>/dev/null' 2>/dev/null \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); up=sum(1 for m in d["members"] if m["status"]=="Up"); print(f"    cluster: {up}/{len(d[\"members\"])} members Up, {len(d[\"unreachable\"])} unreachable")' 2>/dev/null \
  || echo "    (cluster members check skipped)"

INGRESS_IP="$(gcloud compute addresses describe "$IP_NAME" --global --project "$PROJECT_ID" --format='value(address)' 2>/dev/null || true)"
cat <<EOF

Done. NEXT STEPS (manual, one-time):

  1. Cloudflare DNS for programmer.llc — create/repoint as DNS-ONLY (grey
     cloud; orange-cloud proxying breaks Google managed-cert issuance):
        A  promovolve   ->  ${INGRESS_IP:-<gcloud compute addresses describe $IP_NAME --global>}
        A  ads          ->  ${INGRESS_IP:-<same IP>}
     NOTE: this CUTS OVER the public hostnames from the dev-box Cloudflare
     tunnel to GKE. The local DD cluster keeps working at localhost.

  2. Wait for the managed cert (15-60 min AFTER DNS resolves):
        kubectl --context $CTX -n $NS get managedcertificate promovolve
     Status Active = done. Ingress 502s during provisioning are normal.

  3. Fresh DB — register the first account at https://promovolve.programmer.llc/setup

Redeploys after image pushes: update the digests in k8s/kustomization.yaml,
then  k8s-gke/setup.sh --deploy-only
EOF
