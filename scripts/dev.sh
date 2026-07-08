#!/usr/bin/env bash
#
# Fast backend dev loop — hot-reload the API on every source change.
#
# Same setup as run-dev.sh (sources scripts/.env, needs the Docker `promovolve-db`
# postgres running) but uses sbt-revolver's `~reStart`: save a .scala file and
# sbt auto-recompiles the delta + restarts the app on :8080 in ~10-20s — instead
# of a manual stop/run cycle, and WAY faster than a Docker image + k8s roll.
#
# Run-dev.sh is still the way to start a CLEAN session (it truncates the DB /
# clears ddata with --fresh). Use that once, then `dev.sh` for the hot loop.
#
# Stop with Ctrl-C. (Under the hood: keeps one warm sbt session and reForks the
# app on change. A Pekko app reboots its JVM per change — no true hot-swap — so
# it's "fast", not "instant", but it removes the build + deploy from the loop.)
set -e

cd "$(dirname "$0")/.."

ENV_FILE="$(dirname "$0")/.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  source "$ENV_FILE"
  set +a
else
  echo "Error: $ENV_FILE not found. Copy scripts/.env.example to scripts/.env and fill in your values."
  exit 1
fi

export LOG_LEVEL=${LOG_LEVEL:-INFO}
export ENABLE_TEST_ROUTES=${ENABLE_TEST_ROUTES:-true}

echo "Hot-reload dev loop (sbt ~reStart) — save a .scala file → auto recompile + restart on http://localhost:8080"
echo "BANNER_SCRIPT_URL=${BANNER_SCRIPT_URL:-<UNSET — banner rendering disabled>}"

exec sbt \
  -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  "api / ~reStart"
