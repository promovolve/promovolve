#!/usr/bin/env bash
#
# k8s-gke/setup.sh — provision the GKE deployment from nothing (idempotent).
#
# THE deployment: Promovolve has no production tier above this (we don't
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
#   • images available at the digests pinned in k8s/kustomization.yaml — true
#     for the maintainer's staging cluster (CI pushes them). Running your own?
#     use --build-images below; you do not need access to those repos.
#
# USAGE
#   k8s-gke/setup.sh                 # full provision + deploy + verify
#   k8s-gke/setup.sh --deploy-only   # skip GCP provisioning, just (re)apply
#   PROJECT_ID=promovolve-xyz k8s-gke/setup.sh   # if `promovolve` ID is taken
#   BILLING_ACCOUNT=XXXXXX-... k8s-gke/setup.sh  # pick a billing account
#
# RUNNING YOUR OWN DEPLOYMENT (the open-source path)
#   The digests pinned in k8s/kustomization.yaml live in PRIVATE Docker Hub
#   repos under `hanishi` — you cannot pull them. Build and push your own:
#
#     REGISTRY=ghcr.io/you k8s-gke/setup.sh --build-images
#
#   --build-images builds Dockerfile.api + Dockerfile.platform for linux/arm64
#   (the c4a nodes are ARM), pushes them to $REGISTRY, and deploys exactly the
#   digests it just pushed — so a first install never depends on someone else's
#   registry or on the pins in this repo being fresh. Re-run it to redeploy
#   after code changes. `docker login <your registry>` first.
#
#   REGISTRY alone (no --build-images) deploys the repo's pinned digests from
#   your registry — only useful if you have already pushed those exact images.
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
# standard-2 (was standard-4): the 4-core node idled at 3% CPU and cost
# ~¥14k/mo alone; 2 cores / 8 GB fits the right-sized pod requests with
# headroom. See the GCP cost profile handoff (2026-08-23, executed 08-29).
MACHINE="${MACHINE:-c4a-standard-2}"
NS=promovolve
IP_NAME=promovolve-ingress
# Overridable so a caller that already knows its context (scripts/gke-factory-reset.sh
# takes one as a flag) can delegate the apply here without the two derivations
# drifting apart.
CTX="${CTX:-gke_${PROJECT_ID}_${ZONE}_${CLUSTER}}"

# Where the images live. The default is the maintainer's private Docker Hub —
# the STAGING deployment, whose digests CI pins in k8s/kustomization.yaml.
# Anyone else running Promovolve for their own business overrides this and
# builds their own (see --build-images in the header). Left at the default
# with no --build-images, this script behaves exactly as it always has.
REGISTRY="${REGISTRY:-docker.io/hanishi}"
BUILD_IMAGES=0

DEPLOY_ONLY=0
APPLY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --deploy-only)  DEPLOY_ONLY=1; shift ;;
    # Stop after the manifests are applied: no rollout waits, no epilogue.
    # For callers driving their own scale-up sequence — scripts/gke-factory-reset.sh
    # applies while Postgres is deliberately at zero, so waiting here would hang.
    --apply-only)   APPLY_ONLY=1; DEPLOY_ONLY=1; shift ;;
    --pin-images)   PIN_IMAGES=1; shift ;;
    --build-images) BUILD_IMAGES=1; shift ;;
    # Apply even though a CI pin-back pull request (branch ci/pins) is still
    # open, i.e. the repo's pins are known to lag the cluster. See the guard
    # below for what that costs.
    --allow-open-pins) ALLOW_OPEN_PINS=1; shift ;;
    -h|--help) sed -n '2,52p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1 (try --help)" >&2; exit 2 ;;
  esac
done

KDIR="$(cd "$(dirname "$0")" && pwd)"           # k8s-gke
BASEDIR="$(cd "$KDIR/../k8s" && pwd)"           # k8s (base + secrets.env)
# Explicit --context on every kubectl call: this script must NEVER land a
# deploy in the docker-desktop context (the inverse of up.sh's guard).
kc()  { kubectl --context "$CTX" -n "$NS" "$@"; }
kcg() { kubectl --context "$CTX" "$@"; }
REPO="$(cd "$KDIR/.." && pwd)"                  # repo root (docker build context)

# The image ref the BASE renders for one component, read out of
# k8s/kustomization.yaml rather than hardcoded a second time here — the whole
# point of this exercise is that the registry is stated in ONE place.
base_ref() {  # $1 = api|platform  ->  docker.io/hanishi/promovolve-api@sha256:...
  awk -v want="name: promovolve/$1" '
    index($0, want) { f = 1 }
    f && /newName:/         { n = $2 }
    f && /digest: sha256:/  { print n "@" $2; exit }
  ' "$BASEDIR/kustomization.yaml"
}

# Render the overlay, then point the two workload images at $REGISTRY (and, if
# we just built them, at the digests we pushed). Substituting the rendered
# output rather than nesting another kustomize overlay is deliberate: an
# overlay's `images:` transformer matches the name AFTER the base has already
# rewritten it, so a parent overlay would have to name `docker.io/hanishi/...`
# literally — re-hardcoding the registry it is supposed to remove.
#
# With REGISTRY unset and no --build-images, both sides are identical and this
# is a byte-for-byte no-op (verified against the pre-change render).
render() {
  local from_api to_api from_plt to_plt
  from_api="$(base_ref api)";      from_plt="$(base_ref platform)"
  to_api="${REGISTRY}/promovolve-api@${API_DIGEST:-${from_api##*@}}"
  to_plt="${REGISTRY}/promovolve-platform@${PLATFORM_DIGEST:-${from_plt##*@}}"
  kubectl kustomize --load-restrictor LoadRestrictionsNone "$KDIR" \
    | sed -e "s#${from_api}#${to_api}#g" -e "s#${from_plt}#${to_plt}#g"
}

# Build + push both images to $REGISTRY and remember the digests, so a first
# install deploys what it just built instead of depending on someone else's
# registry (or on this repo's pins being fresh). linux/arm64 ONLY — see the
# c4a note above; an amd64 image dies with `exec format error` on these nodes.
build_and_push() {
  command -v docker >/dev/null || die "docker not found (needed for --build-images)"
  local tag; tag="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo manual)"
  # file AND context differ per component — these mirror deploy.yml's two
  # build-push steps exactly, so a local build produces the same image CI does.
  local c file ctx
  for c in api platform; do
    case "$c" in
      api)      file="$REPO/Dockerfile.api";     ctx="$REPO" ;;
      platform) file="$REPO/platform/Dockerfile"; ctx="$REPO/platform" ;;
    esac
    echo "==> building $c (linux/arm64) -> ${REGISTRY}/promovolve-${c}:${tag}"
    docker buildx build \
      --platform linux/arm64 \
      --file "$file" \
      --tag "${REGISTRY}/promovolve-${c}:${tag}" \
      --push "$ctx" \
      || die "docker buildx build failed for $c — is 'docker login ${REGISTRY%%/*}' done?"
  done
  API_DIGEST="$(docker buildx imagetools inspect "${REGISTRY}/promovolve-api:${tag}" \
    --format '{{.Manifest.Digest}}' 2>/dev/null)" || die "could not read the pushed api digest"
  PLATFORM_DIGEST="$(docker buildx imagetools inspect "${REGISTRY}/promovolve-platform:${tag}" \
    --format '{{.Manifest.Digest}}' 2>/dev/null)" || die "could not read the pushed platform digest"
  echo "==> pushed  api=${API_DIGEST}"
  echo "==> pushed  platform=${PLATFORM_DIGEST}"
}
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
    gcloud projects create "$PROJECT_ID" --name="Promovolve"
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
      --disk-type hyperdisk-balanced --disk-size 30 \
      --boot-disk-provisioned-iops 3000 --boot-disk-provisioned-throughput 140 \
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

if [ "${REGISTRY}" != "docker.io/hanishi" ]; then
  # Your own registry: we cannot mint a pull secret from the maintainer's
  # Docker Hub keychain entry, and a PUBLIC registry needs none at all. Create
  # regcred yourself if your images are private:
  #   kubectl -n promovolve create secret docker-registry regcred \
  #     --docker-server=<host> --docker-username=<u> --docker-password=<token>
  # The workloads reference it via imagePullSecrets; an absent secret is fine
  # for public images (kubelet just pulls anonymously).
  if kc get secret regcred >/dev/null 2>&1; then
    echo "==> regcred present — using it for ${REGISTRY}"
  else
    echo "==> no regcred (REGISTRY=${REGISTRY}) — assuming public images"
    echo "    if they are private, create regcred and re-run (see comment in this script)"
  fi
elif kc get secret regcred >/dev/null 2>&1; then
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

# Preserve CI-deployed images across manifest applies. deploy.yml now writes
# each rolled digest back to k8s/kustomization.yaml (its pin-images job), so
# the pins should already match what is running and this step is belt-and-
# braces. It stays because it is the cheap guard against the case that bit
# twice while the pins were hand-maintained: the render carries whatever is
# pinned, so a naive apply ROLLS THE APP BACK (2026-07-12: an infra-only
# deploy reverted four shipped fixes; 2026-07-27: gke-factory-reset.sh took
# api + singleton back four days and every /v1/internal/* route with them).
# Default: capture the live images before the apply and restore them after,
# so manual deploys are infra/config-only. Pass --pin-images to deliberately
# deploy the pinned digests.
# --build-images just pushed the images we want live, so preserving whatever
# is running would throw them away — it takes the same path as --pin-images.
if [ "$BUILD_IMAGES" -eq 1 ]; then
  build_and_push
  PIN_IMAGES=1
fi

LIVE_API=""; LIVE_SINGLETON=""; LIVE_PLATFORM=""
if [ "${PIN_IMAGES:-0}" -ne 1 ]; then
  LIVE_API=$(kc get statefulset promovolve-api -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
  LIVE_SINGLETON=$(kc get statefulset promovolve-singleton -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
  LIVE_PLATFORM=$(kc get deployment promovolve-platform -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
fi

# publish-banner points the cluster at a new bundle with `kubectl set env`,
# which REPLACES the platform's `valueFrom: configMapKeyRef` with a literal
# `value`. Applying the manifest then strategic-merges the valueFrom back on
# top, and the API server rejects an env entry carrying both:
#
#   The Deployment "promovolve-platform" is invalid: …env[1].valueFrom:
#   Invalid value: "": may not be specified when `value` is not empty
#
# So every apply after a banner publish failed on the platform tier — and
# would have aborted a factory reset mid-wipe, since the reset delegates here.
# Drop the literal and let the configMap be authoritative again. Safe because
# CI now writes the published URL back into k8s/kustomization.yaml
# (scripts/pin-banner-url.sh), so the configMap already holds what the
# override was pinning. Harmless when there is no override to remove.
if kc get deployment promovolve-platform >/dev/null 2>&1; then
  kc set env deployment/promovolve-platform BANNER_SCRIPT_URL- >/dev/null 2>&1 || true
fi

# CI writes the live digests and banner URL back to k8s/kustomization.yaml
# THROUGH A PULL REQUEST (deploy.yml -> scripts/pin-back-pr.sh, branch
# ci/pins, auto-merged once CI is green) because the main Ruleset forbids
# direct pushes. While that PR is open the pins here are stale: the image
# restore above covers the digests, but the api-config render carries the
# OLD BANNER_SCRIPT_URL and the override-drop above makes it authoritative,
# so an apply now would point visitors back at the previous bundle. Refuse
# unless told otherwise; the check is advisory-only where gh is absent.
if [ "${ALLOW_OPEN_PINS:-0}" -ne 1 ] && command -v gh >/dev/null 2>&1; then
  open_pins=$(gh pr list --head ci/pins --state open --json url --jq '.[0].url // empty' 2>/dev/null || true)
  [ -z "$open_pins" ] || die "a CI pin-back PR is still open ($open_pins) — the repo's pins lag the cluster. Merge it (or wait for auto-merge), then re-run; --allow-open-pins overrides."
fi

echo "==> applying manifests (kustomize overlay k8s-gke, registry ${REGISTRY})"
render | kcg apply -f -

if [ "${PIN_IMAGES:-0}" -ne 1 ]; then
  echo "==> restoring live (CI-deployed) images over the manifest pins"
  [ -n "$LIVE_API" ]       && kc set image statefulset/promovolve-api       api="$LIVE_API"             >/dev/null
  [ -n "$LIVE_SINGLETON" ] && kc set image statefulset/promovolve-singleton singleton="$LIVE_SINGLETON" >/dev/null
  [ -n "$LIVE_PLATFORM" ]  && kc set image deployment/promovolve-platform   platform="$LIVE_PLATFORM"   >/dev/null
fi

if [ "$APPLY_ONLY" -eq 1 ]; then
  echo "==> --apply-only: manifests applied; the caller owns scale-up and verification"
  exit 0
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

Redeploys: CI builds and rolls by digest on every push to main, and writes the
digests back to k8s/kustomization.yaml — nothing to do by hand. For an
infra/config-only change, k8s-gke/setup.sh --deploy-only (it preserves the
running images). Running your own? REGISTRY=... k8s-gke/setup.sh --build-images
EOF
