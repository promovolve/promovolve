#!/usr/bin/env bash
#
# k8s/db-proxy.sh — port-forward the cluster's Postgres to localhost so psql
# or any GUI client can inspect it. The local docker dev postgres
# (promovolve-db) owns 5432, so the cluster surfaces on 5433 by default.
#
# USAGE
#   k8s/db-proxy.sh              # foreground; Ctrl-C stops it
#   k8s/db-proxy.sh --port 5544  # different local port
#   k8s/db-proxy.sh --ns myns    # override namespace (default: promovolve)
#
# Then, in another terminal:
#   psql "postgres://promovolve:promovolve@localhost:5433/promovolve?sslmode=disable"
set -euo pipefail

NS=promovolve
PORT=5433
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --ns)   NS="$2";   shift 2 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

kubectl -n "$NS" wait --for=condition=ready pod/promovolve-db-0 --timeout=60s >/dev/null

echo "Cluster Postgres → localhost:$PORT (Ctrl-C to stop)"
echo
echo "  psql \"postgres://promovolve:promovolve@localhost:$PORT/promovolve?sslmode=disable\""
echo
exec kubectl -n "$NS" port-forward pod/promovolve-db-0 "$PORT":5432
