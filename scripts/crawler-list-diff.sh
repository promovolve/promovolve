#!/usr/bin/env bash
#
# Check a freshly downloaded crawler-user-agents.json and say what it changes
# against the vendored copy BotUaMatcher reads (crawler-list-refresh.yml).
#
#   scripts/crawler-list-diff.sh <downloaded.json>
#
# Exits 1 on a download that is not the list (an error page, a truncated
# file, a schema change), so nothing half-broken reaches a PR. Prints the
# patterns added and removed, one per line, for the commit and PR body.
set -euo pipefail

NEW="${1:?usage: crawler-list-diff.sh <downloaded.json>}"
OLD="$(git rev-parse --show-toplevel)/modules/core/src/main/resources/fraud/crawler-user-agents.json"

jq -e 'type == "array" and length > 1000 and all(.[]; .pattern | type == "string")' "$NEW" >/dev/null \
  || { echo "$NEW is not the crawler-user-agents list" >&2; exit 1; }

added=$(jq -r --slurpfile old "$OLD" '[.[].pattern] - [$old[0][].pattern] | .[]' "$NEW")
removed=$(jq -r --slurpfile new "$NEW" '[.[].pattern] - [$new[0][].pattern] | .[]' "$OLD")

echo "Added $(grep -c . <<<"$added" || true), removed $(grep -c . <<<"$removed" || true) patterns."
[ -z "$added" ] || { echo; echo "Added:"; sed 's/^/    /' <<<"$added"; }
[ -z "$removed" ] || { echo; echo "Removed:"; sed 's/^/    /' <<<"$removed"; }
