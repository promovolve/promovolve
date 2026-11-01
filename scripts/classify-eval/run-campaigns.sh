#!/usr/bin/env bash
# Run the campaign-side classifier (CategoryVerificationClient) over
# campaigns.tsv and score it (#422). The page eval's sibling — see README.
#
#   [GEMINI_CAMPAIGN_MODEL=…] scripts/classify-eval/run-campaigns.sh [--no-extract] [--label NAME] [--baseline out/campaigns-NAME.json]
#
# A/B a model: run once as-is (2.5-flash, the production default), then with
# GEMINI_CAMPAIGN_MODEL=<candidate> --no-extract --baseline out/campaigns-gemini-2.5-flash.json
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/scripts/classify-eval"
EXTRACT=1; ARGS=()
for a in "$@"; do
  case "$a" in
    --no-extract) EXTRACT=0 ;;
    *) ARGS+=("$a") ;;
  esac
done
if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "campaign-eval: set GEMINI_API_KEY — the eval calls the real classifier" >&2
  exit 2
fi
if [ "$EXTRACT" = 1 ]; then
  echo "-> extracting landing-page text"
  node "$HERE/extract.mjs" "$HERE/campaigns.tsv"
fi
cd "$ROOT"
sbt -batch "core/Test/runMain promovolve.tools.CampaignClassifyEval $HERE ${ARGS[*]:-}" 2>&1 \
  | grep -v '^\[info\] \(welcome\|loading\|compiling\|set current\|running\)' \
  | sed 's/^\[info\] //'
