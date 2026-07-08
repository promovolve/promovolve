#!/usr/bin/env bash
#
# Build the banner-component, publish to Cloudflare R2 under a content-
# hashed filename, and update scripts/.env with the new URL. The JVM
# reads BANNER_SCRIPT_URL at startup, so a restart is required for
# delivery to pick up the new bundle.
#
# Usage:
#   ./scripts/publish-banner.sh
#
# Skips the typecheck + lint pre-steps that `npm run release` runs.
# Add --release to run the full chain.

set -e

cd "$(dirname "$0")/.."

CMD="build && npm run publish:r2"
if [[ "${1:-}" == "--release" ]]; then
  CMD="release"
fi

echo "→ Building + publishing banner-component (cmd: npm run $CMD)..."
( cd platform/banner-component && eval "npm run $CMD" )

echo
NEW_URL=$(grep '^BANNER_SCRIPT_URL=' scripts/.env | head -1 | cut -d= -f2-)
echo "✓ Published. BANNER_SCRIPT_URL is now:"
echo "    $NEW_URL"

if pgrep -f "sbt-args\|promovolve.*Main" > /dev/null 2>&1; then
  echo
  echo "⚠️  JVM is running. Restart it so the new URL is loaded:"
  echo "    kill \$(lsof -ti :8080) && ./scripts/run-dev.sh"
else
  echo
  echo "(JVM not running — next ./scripts/run-dev.sh will pick up the new URL.)"
fi
