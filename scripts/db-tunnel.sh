#!/usr/bin/env bash
#
# scripts/db-tunnel.sh — port-forward the CLUSTER postgres to localhost so it
# can be browsed from an IDE / psql.
#
# The cluster DB (promovolve-db-0) is a headless in-cluster service with no
# host port; meanwhile the LOCAL docker dev container already owns
# localhost:5432. Mixing the two up looks like "I see no records in db" —
# hence the distinct default port here.
#
#   scripts/db-tunnel.sh              # cluster DB on localhost:15432
#   scripts/db-tunnel.sh --port 5433  # custom local port
#   scripts/db-tunnel.sh --ns myns    # custom namespace
#
# Connection: host=localhost port=15432 db/user/password = promovolve.
# Keeps re-establishing the forward if it drops (pod restart, laptop sleep);
# Ctrl-C to stop.
set -euo pipefail

NS=promovolve
PORT=15432
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --ns)   NS="$2"; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1 (try --help)" >&2; exit 2 ;;
  esac
done

echo "Cluster postgres → localhost:$PORT  (namespace '$NS', Ctrl-C to stop)"
echo "  psql -h localhost -p $PORT -U promovolve -d promovolve"

while true; do
  kubectl port-forward -n "$NS" svc/promovolve-db "$PORT":5432 || true
  echo "port-forward dropped — reconnecting in 2s…" >&2
  sleep 2
done
