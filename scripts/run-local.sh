#!/usr/bin/env bash
# Run the complete local stack with Wrangler's persistent R2 simulation.

set -euo pipefail

cd "$(dirname "$0")/.."

for tool in docker node npm sbt go curl pgrep id; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Error: $tool is required."
    exit 1
  fi
done

if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
else
  echo "Error: Docker Compose is required (docker compose or docker-compose)."
  exit 1
fi

if [ ! -f scripts/.env ]; then
  cp scripts/.env.example scripts/.env
  echo "Created scripts/.env. Add one LLM API key, then run this command again."
  exit 1
fi

set -a
source scripts/.env
set +a

if [ -z "${GEMINI_API_KEY:-}" ] && [ -z "${OPENAI_API_KEY:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo "Error: set GEMINI_API_KEY, OPENAI_API_KEY, or ANTHROPIC_API_KEY in scripts/.env."
  exit 1
fi

export R2_LOCAL_URL="${R2_LOCAL_URL:-http://127.0.0.1:8787}"
export CDN_BASE_URL="$R2_LOCAL_URL"
export BANNER_SCRIPT_URL="${R2_LOCAL_URL%/}/js/expandable-magazine-banner.js"
npm_cache_dir="${TMPDIR:-/tmp}/promovolve-npm-cache-$(id -u)"

r2_address=$(node -e '
  const url = new URL(process.argv[1]);
  const validHost = url.hostname === "127.0.0.1" || url.hostname === "localhost";
  const validPath = url.pathname === "/" && !url.search && !url.hash;
  if (url.protocol !== "http:" || !validHost || !validPath || url.username || url.password) process.exit(1);
  process.stdout.write(`${url.hostname} ${url.port || "80"}`);
' "$R2_LOCAL_URL") || {
  echo "Error: R2_LOCAL_URL must be an HTTP loopback URL without a path, query, or credentials."
  exit 1
}
read -r r2_host r2_port <<< "$r2_address"

if ! node -e '
  const net = require("node:net");
  const server = net.createServer();
  server.once("error", () => process.exit(1));
  server.listen({ host: process.argv[1], port: Number(process.argv[2]), exclusive: true }, () => {
    server.close(() => process.exit(0));
  });
' "$r2_host" "$r2_port"; then
  echo "Error: $R2_LOCAL_URL is already in use. Stop the existing process before starting Promovolve."
  exit 1
fi

worker_pid=""
api_pid=""
dashboard_pid=""

signal_tree() {
  local signal="$1"
  local pid="$2"
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    signal_tree "$signal" "$child"
  done
  kill -"$signal" "$pid" 2>/dev/null || true
}

cleanup() {
  trap - EXIT INT TERM
  for pid in "$dashboard_pid" "$api_pid" "$worker_pid"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      signal_tree TERM "$pid"
    fi
  done
  for _ in {1..50}; do
    processes_running=false
    for pid in "$dashboard_pid" "$api_pid" "$worker_pid"; do
      if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        processes_running=true
      fi
    done
    if [ "$processes_running" = false ]; then
      break
    fi
    sleep 0.1
  done
  for pid in "$dashboard_pid" "$api_pid" "$worker_pid"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      signal_tree KILL "$pid"
    fi
    if [ -n "$pid" ]; then
      wait "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT INT TERM

echo "Starting PostgreSQL..."
"${compose[@]}" up -d postgres
for _ in {1..60}; do
  db_status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' promovolve-db 2>/dev/null || true)
  if [ "$db_status" = "healthy" ] || [ "$db_status" = "running" ]; then
    break
  fi
  if [ "$db_status" = "exited" ] || [ "$db_status" = "dead" ]; then
    echo "Error: PostgreSQL container stopped during startup."
    exit 1
  fi
  sleep 1
done
if [ "$db_status" != "healthy" ] && [ "$db_status" != "running" ]; then
  echo "Error: PostgreSQL did not become ready."
  exit 1
fi

echo "Installing Wrangler dependencies..."
npm ci --include=dev --prefix dev/r2-local --cache "$npm_cache_dir"

echo "Starting local R2 simulation on $R2_LOCAL_URL..."
export XDG_CONFIG_HOME="$PWD/dev/r2-local/.wrangler/config"
export XDG_CACHE_HOME="$PWD/dev/r2-local/.wrangler/cache"
export WRANGLER_LOG_PATH="$PWD/dev/r2-local/.wrangler/logs"
export WRANGLER_SEND_METRICS=false
local_r2_instance_id="promovolve-$$-$RANDOM"
npm --prefix dev/r2-local run dev -- \
  --ip "$r2_host" \
  --port "$r2_port" \
  --var "LOCAL_INSTANCE_ID:$local_r2_instance_id" &
worker_pid=$!

for _ in {1..60}; do
  if ! kill -0 "$worker_pid" 2>/dev/null; then
    echo "Error: local R2 process exited during startup."
    exit 1
  fi
  local_r2_health=$(curl --silent --fail "$R2_LOCAL_URL/health" 2>/dev/null || true)
  if [ "$local_r2_health" = "$local_r2_instance_id" ]; then
    break
  fi
  sleep 1
done
if [ "${local_r2_health:-}" != "$local_r2_instance_id" ]; then
  echo "Error: local R2 did not become ready."
  exit 1
fi

echo "Installing banner component dependencies..."
npm ci --include=dev --prefix platform/banner-component --cache "$npm_cache_dir"
echo "Installing creative designer dependencies..."
npm ci --include=dev --prefix platform/creative-designer --cache "$npm_cache_dir"

echo "Building the local banner component..."
npm --prefix platform/banner-component run build --silent
curl --silent --show-error --fail \
  -X PUT \
  -H "Content-Type: application/javascript; charset=utf-8" \
  --data-binary @platform/banner-component/dist/expandable-magazine-banner.js \
  "$BANNER_SCRIPT_URL"
curl --silent --show-error --fail \
  -X PUT \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @platform/banner-component/dist/expandable-magazine-banner.js.map \
  "${R2_LOCAL_URL%/}/js/expandable-magazine-banner.js.map"

echo "Starting core API on http://127.0.0.1:8080..."
scripts/run-dev.sh &
api_pid=$!

echo "Starting dashboard on http://127.0.0.1:9091..."
scripts/run-dashboard.sh &
dashboard_pid=$!

echo
echo "Promovolve is starting:"
echo "  Dashboard: http://127.0.0.1:9091"
echo "  Core API: http://127.0.0.1:8080"
echo "  Local R2: $R2_LOCAL_URL"
echo "Press Ctrl-C to stop the application processes. PostgreSQL remains running."

while true; do
  for pid in "$worker_pid" "$api_pid" "$dashboard_pid"; do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid"
      exit $?
    fi
  done
  sleep 1
done
