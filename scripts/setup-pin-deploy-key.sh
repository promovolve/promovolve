#!/usr/bin/env bash
#
# Create (or rotate) the deploy key deploy.yml's pin-back jobs push with.
#
# Use a repo-scoped write deploy key so pin-back PR updates run CI without
# the manual workflow approval required for GITHUB_TOKEN pushes.
# ci/pins is unprotected, so no Ruleset bypass is needed.
#
#   scripts/setup-pin-deploy-key.sh            # this repo (gh's current repo)
#   scripts/setup-pin-deploy-key.sh owner/repo # a fork
#
# Idempotent: re-running replaces both the deploy key and the secret (a
# rotation). The private key never touches disk outside a mktemp dir that
# is removed on exit.
set -euo pipefail

REPO="${1:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
TITLE="deploy.yml pin-back (ci/pins push)"
SECRET="PIN_DEPLOY_KEY"

command -v gh >/dev/null || { echo "gh is required" >&2; exit 1; }
command -v ssh-keygen >/dev/null || { echo "ssh-keygen is required" >&2; exit 1; }

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
ssh-keygen -q -t ed25519 -N "" -C "$TITLE" -f "$tmp/key"

# Replace an existing key of the same title so a rotation leaves one behind.
gh repo deploy-key list -R "$REPO" --json id,title --jq ".[] | select(.title==\"$TITLE\") | .id" \
  | while read -r id; do [ -n "$id" ] && gh repo deploy-key delete -R "$REPO" "$id"; done
if ! gh repo deploy-key add -R "$REPO" --allow-write --title "$TITLE" "$tmp/key.pub"; then
  cat >&2 <<EOF
could not register the deploy key. If GitHub said "Deploy keys are disabled
for this repository", that is the ORGANIZATION policy (it was off on
promovolve until 2026-09-04). An org owner turns it on with
  gh api -X PATCH orgs/${REPO%%/*} -F deploy_keys_enabled_for_repositories=true
or in the org's Settings > Deploy keys page, then re-run this script.
EOF
  exit 1
fi
gh secret set "$SECRET" -R "$REPO" < "$tmp/key"

echo "deploy key '$TITLE' registered with write access on $REPO"
echo "secret $SECRET set; the next deploy's pin-back will push ci/pins with it"
