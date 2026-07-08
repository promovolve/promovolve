#!/usr/bin/env bash
#
# Creative-designer dev loop with TRUE HMR, wired to a REAL backend —
# no build / fanout / image-push / rollout per edit.
#
# This is the k8s-backend variant of the designer dev loop. Instead of
# running a local Go dashboard (scripts/run-dashboard.sh, which needs a
# local api + postgres), it serves the designer straight from source via
# Vite and points the designer's OWN built-in proxy (see
# platform/creative-designer/vite.config.ts) at an already-running
# backend — by default the live k8s platform that Docker Desktop exposes
# on :9090.
#
# What you get:
#   - http://localhost:5173/         the designer, hot-reloading from source
#   - /login, /advertiser/*, /api/*  proxied to $DASHBOARD_URL, so real
#                                    auth, asset upload, save, and
#                                    generate-layout all hit the cluster
#
# First-time auth (needed for save/upload/generate, not for pure UI work):
#   open http://localhost:5173/login and sign in THERE — the proxy
#   forwards Set-Cookie so the auth cookie lands on the :5173 origin and
#   later /advertiser/* calls carry it through.
#
# Usage:
#   ./scripts/run-designer-dev.sh                                  # → k8s platform on :9090
#   DASHBOARD_URL=http://localhost:9091 ./scripts/run-designer-dev.sh   # → local run-dashboard.sh
#   PORT=5180 ./scripts/run-designer-dev.sh
#
# The backend ($DASHBOARD_URL) must already be running. For the k8s
# default that's the platform Service surfaced by Docker Desktop on :9090.
#
# See dev_docs/DESIGNER_DEV.md for the full picture (Loop A vs Loop B) and how
# to ship changes to the cluster when you're done iterating.

set -e
cd "$(dirname "$0")/.."

DASHBOARD_URL="${DASHBOARD_URL:-http://localhost:9090}"
PORT="${PORT:-5173}"

# Fail loud (but don't abort) if the backend isn't reachable — otherwise
# the proxy silently 502s on every /login and the cause is non-obvious.
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$DASHBOARD_URL/login" || true)
if [ "$code" != "200" ] && [ "$code" != "302" ]; then
  echo "⚠️  Backend at $DASHBOARD_URL not reachable (/login → ${code:-no response})."
  echo "    • k8s default: is the platform Service exposed on :9090? (Docker Desktop LB)"
  echo "    • local stack: ./scripts/run-dashboard.sh, then DASHBOARD_URL=http://localhost:9091 $0"
  echo "    Continuing — the proxy will error until the backend is up."
  echo
fi

echo "→ Designer dev (HMR)  http://localhost:$PORT"
echo "  backend proxy →     $DASHBOARD_URL"
echo "  sign in first at    http://localhost:$PORT/login"
echo

cd platform/creative-designer
export DASHBOARD_URL
exec npx vite --port "$PORT" --host
