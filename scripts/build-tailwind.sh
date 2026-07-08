#!/usr/bin/env bash
#
# scripts/build-tailwind.sh — compile the dashboard's Tailwind CSS.
#
# The Docker image build compiles this CSS itself (platform/Dockerfile css
# stage, same pinned tailwindcss version) — deploys can never ship stale
# styles. This script exists for LOCAL `go run` / tests, which embed the
# committed platform/static/tailwind.css: run it after class changes in
# platform/templates/ (or static/passkey.js) and commit the output.
set -euo pipefail
cd "$(dirname "$0")/../platform"

npx --yes tailwindcss@3.4.17 \
  -c tailwind.config.js \
  -i tailwind.input.css \
  -o static/tailwind.css \
  --minify

ls -la static/tailwind.css
