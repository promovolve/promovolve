#!/usr/bin/env bash
#
# Build the banner-bootstrap, publish to Cloudflare R2 under a content-
# hashed filename, and update scripts/.env with the new URL. The Go
# dashboard reads BOOTSTRAP_SCRIPT_URL at startup, so a restart is
# required for publisher demo pages to load the new bundle.
#
# Usage:
#   ./scripts/publish-bootstrap.sh
#
# Skips the typecheck + lint + test pre-steps that `npm run release` runs.
# Add --release to run the full chain.

set -e

cd "$(dirname "$0")/.."

CMD="build && npm run fanout && npm run publish:r2"
if [[ "${1:-}" == "--release" ]]; then
  CMD="release"
fi

echo "→ Building + publishing banner-bootstrap (cmd: npm run $CMD)..."
( cd platform/banner-bootstrap && eval "npm run $CMD" )

echo
NEW_URL=$(grep '^BOOTSTRAP_SCRIPT_URL=' scripts/.env | head -1 | cut -d= -f2-)
echo "✓ Published. BOOTSTRAP_SCRIPT_URL is now:"
echo "    $NEW_URL"

if pgrep -f 'cmd/server\|dashboard.*9091' > /dev/null 2>&1; then
  echo
  echo "⚠️  Dashboard is running. Restart it so the new URL flows to demo pages:"
  echo "    kill \$(lsof -ti :9091) && ./scripts/run-dashboard.sh"
else
  echo
  echo "(Dashboard not running — next ./scripts/run-dashboard.sh will pick up the new URL.)"
fi
