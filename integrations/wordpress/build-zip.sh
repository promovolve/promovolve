#!/usr/bin/env bash
#
# integrations/wordpress/build-zip.sh — package the publisher plugin as the zip
# WordPress accepts under Plugins → Add New → Upload Plugin.
#
# WHY A SCRIPT AND NOT JUST `zip -r`
#   The version lives in THREE places that must agree — the plugin header
#   `Version:`, the PHP constant PROMOVOLVE_VERSION (which cache-busts the
#   block's editor script), and readme.txt `Stable tag:`. Nothing at runtime
#   forces them to match, and a stale PROMOVOLVE_VERSION is the nastiest of the
#   three: editors keep serving the OLD cached editor.js, so a shipped block fix
#   silently does not apply. This script refuses to build on a mismatch, which
#   is the only reliable moment to catch it.
#
#   It also guards the two packaging mistakes that produce a zip WordPress
#   rejects or a publisher sneers at: contents at the archive root instead of
#   inside a single `promovolve/` folder, and macOS resource forks showing up as
#   a __MACOSX/ directory.
#
# USAGE
#   ./build-zip.sh            # -> dist/promovolve-<version>.zip
set -euo pipefail

cd "$(dirname "$0")"

PLUGIN_DIR="promovolve"
MAIN_FILE="$PLUGIN_DIR/promovolve.php"
README="$PLUGIN_DIR/readme.txt"
OUT_DIR="dist"

[ -f "$MAIN_FILE" ] || { echo "error: $MAIN_FILE not found" >&2; exit 1; }

# ── the three versions must agree ─────────────────────────────────────────────
header_version=$(sed -n 's/^ \* Version:[[:space:]]*\([0-9][^[:space:]]*\).*/\1/p' "$MAIN_FILE" | head -1)
const_version=$(sed -n "s/^const PROMOVOLVE_VERSION[[:space:]]*=[[:space:]]*'\([^']*\)'.*/\1/p" "$MAIN_FILE" | head -1)
stable_tag=$(sed -n 's/^Stable tag:[[:space:]]*\([0-9][^[:space:]]*\).*/\1/p' "$README" | head -1)

[ -n "$header_version" ] || { echo "error: could not read 'Version:' from $MAIN_FILE" >&2; exit 1; }
[ -n "$const_version" ]  || { echo "error: could not read PROMOVOLVE_VERSION from $MAIN_FILE" >&2; exit 1; }
[ -n "$stable_tag" ]     || { echo "error: could not read 'Stable tag:' from $README" >&2; exit 1; }

if [ "$header_version" != "$const_version" ] || [ "$header_version" != "$stable_tag" ]; then
	echo "error: version mismatch — all three must agree before packaging:" >&2
	echo "  $MAIN_FILE  Version:            $header_version" >&2
	echo "  $MAIN_FILE  PROMOVOLVE_VERSION: $const_version" >&2
	echo "  $README  Stable tag:         $stable_tag" >&2
	exit 1
fi

VERSION="$header_version"

# ── syntax gate: a zip that fatals on activation is worse than no zip ─────────
if command -v php >/dev/null 2>&1; then
	find "$PLUGIN_DIR" -name '*.php' -print0 | xargs -0 -n1 php -l >/dev/null
	echo "php -l: clean"
else
	echo "note: php not installed — skipping the syntax check" >&2
fi
# ── behaviour gate: the topic-hint selection rules ───────────────────────────
# php -l only proves the file parses. These pin WHICH taxonomies are read, in
# what order, and which terms survive the cap — all of which fail silently
# (a hint that is merely thinner than it should be still looks fine).
if command -v php >/dev/null 2>&1; then
	php tests/topic-test.php
fi

if command -v node >/dev/null 2>&1; then
	find "$PLUGIN_DIR" -name '*.js' -print0 | xargs -0 -n1 node --check
	node -e 'JSON.parse(require("fs").readFileSync("promovolve/blocks/slot/block.json","utf8"))'
	echo "node --check + block.json: clean"
fi

# ── build ─────────────────────────────────────────────────────────────────────
mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/promovolve-$VERSION.zip"
rm -f "$ZIP"

# -X drops the macOS extended attributes that otherwise land as __MACOSX/.
# Zipping the DIRECTORY (not its contents) is what puts everything under a
# single top-level promovolve/ folder, which is what the uploader requires.
zip -r -q -X "$ZIP" "$PLUGIN_DIR" \
	-x '*.DS_Store' -x '*/.git/*' -x '*.orig' -x '*~'

# ── verify what we actually produced ─────────────────────────────────────────
if unzip -l "$ZIP" | grep -q '__MACOSX'; then
	echo "error: $ZIP contains __MACOSX entries" >&2
	exit 1
fi
if ! unzip -l "$ZIP" | grep -q " $PLUGIN_DIR/promovolve\.php$"; then
	echo "error: $ZIP is missing $PLUGIN_DIR/promovolve.php — wrong archive layout" >&2
	exit 1
fi

echo
echo "built $ZIP ($(du -h "$ZIP" | cut -f1)) — version $VERSION"
unzip -l "$ZIP" | awk 'NR>3 && $4 != "" {print "  " $4}' | grep -v '/$' || true
