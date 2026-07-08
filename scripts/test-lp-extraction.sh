#!/usr/bin/env bash
# Test LP-to-Creative extraction via Gemini
# Usage: ./scripts/test-lp-extraction.sh [URL]

set -e
cd "$(dirname "$0")/.."

# Load API key
source scripts/.env

URL="${1:-https://www.allbirds.com/products/mens-tree-runners}"

echo "Fetching LP: $URL"
LP_HTML=$(curl -sL --max-time 10 -A "Mozilla/5.0" "$URL" | head -c 50000)

echo "Sending to Gemini for extraction ($(echo "$LP_HTML" | wc -c | tr -d ' ') chars)..."

PROMPT='You are an award-winning creative director crafting a premium magazine-style expandable banner ad from a landing page.

Given the HTML below, generate a JSON array of exactly 3 "pages" for the ad.

Page 1 (FEATURE): The hook — lead with the single most compelling thing about this product/service. Make the reader curious.
Page 2 (EXPERIENCE): The differentiator — what makes this worth their time. Speak to the experience, not features.
Page 3 (PLAN): The offer — pricing if available, or a clear call to action. Make it feel exclusive.

Rules for the copy:
- Write like a magazine editor, not a marketer. No buzzwords, no "unlock", no "transform".
- Headlines: 3-5 words, evocative, specific to this brand.
- Body: 2-3 sentences. Concrete details from the LP, not generic praise.

Rules for the design tokens:
- accent: extract from the brand colors on the LP (look at logo, buttons, links). Each page can use a DIFFERENT accent.
- bg: dark elegant gradient, VARY the hue per page (warm/cool/neutral). Use the format: linear-gradient(165deg, #1a1a1a 0%, #2d2518 40%, #1a1612 100%)
- imgEmoji: one emoji that captures the page mood. Be creative, not literal.

Schema per page:
{"tag": "FEATURE|EXPERIENCE|PLAN", "headline": "...", "sub": "...", "body": "...", "accent": "#hex", "bg": "linear-gradient(...)", "imgEmoji": "emoji", "caption": "..."}

Return ONLY a raw JSON array. No markdown fences, no explanation.

HTML:
'

RESPONSE=$(curl -s "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$GEMINI_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg prompt "$PROMPT" --arg html "$LP_HTML" '{
    contents: [{parts: [{text: ($prompt + $html)}]}],
    generationConfig: {temperature: 0.7, maxOutputTokens: 2000}
  }')")

echo ""
echo "=== Extracted Creative ==="
echo "$RESPONSE" | jq -r '.candidates[0].content.parts[0].text'
