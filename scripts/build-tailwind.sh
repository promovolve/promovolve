#!/usr/bin/env bash
#
# scripts/build-tailwind.sh — compile the dashboard's Tailwind CSS.
#
# The Docker image build compiles this CSS itself (platform/Dockerfile css
# stage, using the same Tailwind lockfile) — deploys can never ship stale
# styles. This script exists for LOCAL `go run` / tests, which embed the
# committed platform/static/tailwind.css: run it after class changes in
# platform/templates/ (or static/passkey.js) and commit the output.
set -euo pipefail
cd "$(dirname "$0")/../platform"

npm --prefix tailwind ci --include=dev --ignore-scripts
./tailwind/node_modules/.bin/tailwindcss \
  -c tailwind.config.js \
  -i tailwind.input.css \
  -o static/tailwind.css \
  --minify

ls -la static/tailwind.css
