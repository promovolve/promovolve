#!/usr/bin/env bash
#
# Create (or rotate) the deploy key deploy.yml's pin-back jobs push with.
#
# WHY: the pin-back pull request (scripts/pin-back-pr.sh) needs the main
# Ruleset's required checks on its head commit, and only a real push can
# produce them — a push made with GITHUB_TOKEN fires no workflow, and check
# runs from a workflow_dispatch run are not counted (2026-09-04, PR #41 sat
# BLOCKED with six green check runs). A write deploy key is the smallest
# identity that pushes like a user: repo-scoped, git-only, no account, no
# expiry to babysit, and it needs no Ruleset bypass because ci/pins is not
# a protected branch.
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
gh repo deploy-key add -R "$REPO" --allow-write --title "$TITLE" "$tmp/key.pub"
gh secret set "$SECRET" -R "$REPO" < "$tmp/key"

echo "deploy key '$TITLE' registered with write access on $REPO"
echo "secret $SECRET set; the next deploy's pin-back will push ci/pins with it"
