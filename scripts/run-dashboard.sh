#!/usr/bin/env bash
#
# Run the Go dashboard server locally.
#
# Sources scripts/.env for shared secrets (BANNER_SCRIPT_URL, etc.),
# applies dashboard-specific defaults, and runs `go run ./cmd/server`.
# Any env var already set in the shell wins over the defaults here.

set -e

cd "$(dirname "$0")/.."

# Shared secrets / cross-process config
ENV_FILE="scripts/.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  source "$ENV_FILE"
  set +a
else
  echo "Warning: $ENV_FILE not found — running with defaults only."
fi

# Dashboard-specific defaults (Go side only)
export DATABASE_URL="${DATABASE_URL:-postgres://promovolve:promovolve@localhost:5432/promovolve?sslmode=disable}"
export CORE_API_URL="${CORE_API_URL:-http://localhost:8080}"
export LISTEN_ADDR="${LISTEN_ADDR:-:9091}"
export JWT_SECRET="${JWT_SECRET:-dev-secret}"
export BANNER_SCRIPT_URL="${BANNER_SCRIPT_URL:-}"

# Auth (dev): keep password login/register working so --fresh DB wipes don't
# require a browser passkey ceremony. Never set DEV_AUTH in deployments.
export DEV_AUTH="${DEV_AUTH:-true}"
export RP_ID="${RP_ID:-localhost}"
export RP_ORIGINS="${RP_ORIGINS:-http://localhost:9091}"

# Serve /static/ from the live directory instead of the compile-time
# embed — designer edits land on the next browser reload. Pair with
# `npm run dev` in platform/creative-designer (vite build --watch
# writes into platform/static) for a no-rebuild designer loop.
# Absolute path: the exec below runs from platform/, so a relative
# value would silently point at platform/platform/static and 404.
export DEV_STATIC_DIR="${DEV_STATIC_DIR:-$PWD/platform/static}"

# Build the creative-designer bundle and fan it out to platform/static
# so the served (or embedded) copy reflects source changes. Skipped
# when DASHBOARD_SKIP_DESIGNER_BUILD=1 is set (faster restarts when
# only touching Go code, or when `npm run dev` is watching instead).
if [ "${DASHBOARD_SKIP_DESIGNER_BUILD:-0}" != "1" ]; then
  echo "Building creative-designer bundle…"
  (
    cd platform/creative-designer
    npm run build --silent
    npm run fanout --silent
  )
fi

echo "Starting dashboard on $LISTEN_ADDR"
echo "  CORE_API_URL=$CORE_API_URL"
echo "  BANNER_SCRIPT_URL=$BANNER_SCRIPT_URL"

cd platform
exec go run ./cmd/server
