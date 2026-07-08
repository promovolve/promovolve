#!/usr/bin/env bash
#
# scripts/sync-help.sh — refresh the dashboards' embedded Help pages from the
# canonical user guides in docs/guides/. The platform embeds copies (the
# Docker build context is platform/ only); platform/help_sync_test.go fails
# `go test` whenever they drift, so editing a guide means running this and
# committing both.
set -euo pipefail
cd "$(dirname "$0")/.."

cp docs/guides/advertiser-quickstart.md platform/help/advertiser.md
cp docs/guides/publisher-integration.md platform/help/publisher.md
echo "synced: platform/help/{advertiser,publisher}.md"
