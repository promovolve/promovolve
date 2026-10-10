#!/usr/bin/env bash
# Roll image and runtime permissions together so the old launcher never runs as non-root.
set -euo pipefail
if [ "$#" -ne 2 ]; then
  echo "Usage: $0 KUBERNETES_CONTEXT IMAGE@sha256:DIGEST" >&2
  exit 2
fi
isolation_context="$1"
isolation_image="$2"
if [[ ! "$isolation_image" =~ ^[a-zA-Z0-9][a-zA-Z0-9._:/-]*@sha256:[0-9a-f]{64}$ ]]; then
  echo "An immutable image digest is required" >&2
  exit 2
fi
isolation_dir="$(cd "$(dirname "$0")" && pwd)"
profile_revision=$(shasum -a 256 "$isolation_dir/../docker/chromium-seccomp.json" | cut -d ' ' -f 1)
kc() { kubectl --context "$isolation_context" -n promovolve "$@"; }

kubectl kustomize --load-restrictor LoadRestrictionsNone "$isolation_dir/runtime-security" | kc apply -f -
kc rollout status daemonset/chromium-seccomp --timeout=180s

for tier in singleton api; do
  # One template update keeps the executable and its required UID in sync.
  kc patch statefulset "promovolve-$tier" --type strategic --patch "{
    \"spec\": {\"template\": {
      \"metadata\": {\"annotations\": {\"promovolve.io/seccomp-sha256\": \"$profile_revision\"}},
      \"spec\": {
      \"securityContext\": {\"fsGroup\": 1000, \"fsGroupChangePolicy\": \"Always\"},
      \"containers\": [{
        \"name\": \"$tier\",
        \"image\": \"$isolation_image\",
        \"env\": [{\"name\": \"CHROMIUM_NO_SANDBOX\", \"value\": \"false\"}],
        \"securityContext\": {
          \"runAsNonRoot\": true,
          \"runAsUser\": 1000,
          \"runAsGroup\": 1000,
          \"allowPrivilegeEscalation\": false,
          \"capabilities\": {\"drop\": [\"ALL\"]},
          \"seccompProfile\": {\"type\": \"Localhost\", \"localhostProfile\": \"promovolve/chromium-v1.json\"}
        }
      }]
    }}}
  }"
  kc rollout status "statefulset/promovolve-$tier" --timeout=600s
done
