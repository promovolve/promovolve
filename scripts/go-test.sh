#!/usr/bin/env bash
#
# Build, vet, and race-test every FIRST-PARTY Go package in platform/.
#
# WHY THIS EXISTS: the Go CI job ran `go test ./cmd/... .`, and the cmd
# packages have no tests, so only the root package's tests ever gated a
# deploy — everything under internal/ (handlers, billing, currency,
# settings, models, site requests) was invisible to CI (GH #21). The scope
# had been narrowed because a bare `./...` also picks up Go snippets that
# npm vendors below node_modules (flatted ships a golang/ port). This is
# the one place the selection lives: CI and contributors both run it.
#
#   scripts/go-test.sh            # build + vet + test -race
#   scripts/go-test.sh list       # just print the package list
#
# Database-backed billing tests skip themselves unless
# BILLING_TEST_DATABASE_URL is set; making them mandatory is GH #22.
set -euo pipefail

cd "$(dirname "$0")/../platform"

# Everything the module knows about, minus vendored-by-npm snippets. Kept as
# a deny-list rather than an allow-list so a new top-level Go directory is
# tested by default instead of silently skipped.
pkgs=$(go list ./... | grep -v -E '/(node_modules|\.vite)/')

if [ "${1:-}" = "list" ]; then
  printf '%s\n' "$pkgs"
  exit 0
fi

# shellcheck disable=SC2086  # intentional word-splitting of the package list
{
  echo "==> go build ($(printf '%s\n' "$pkgs" | wc -l | tr -d ' ') packages)"
  go build $pkgs
  echo "==> go vet"
  go vet $pkgs
  echo "==> go test -race"
  go test -race $pkgs
}
