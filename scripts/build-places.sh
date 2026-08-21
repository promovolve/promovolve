#!/usr/bin/env bash
# Build the geographic vocabulary tables shipped in
# modules/core/src/main/resources/places/.
#
#   ./scripts/build-places.sh
#
# Downloads into a temp dir, trims to the columns Promovolve needs, and
# rewrites the TSVs in place. Re-run to refresh; the diff should be small.
#
# Sources:
#   ISO 3166-1 / 3166-2 codes, names, translations
#     Debian iso-codes (LGPL-2.1) https://salsa.debian.org/iso-codes-team/iso-codes
#   Cities and their localised names
#     GeoNames (CC BY 4.0)        https://download.geonames.org/export/dump/
# Both require attribution - see NOTICE.
#
# Why node rather than curl: node is already a build dependency
# (platform/, banner-bootstrap/), and some managed environments deny
# curl/wget outright.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/modules/core/src/main/resources/places"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Languages with a catalogue in this build (see LocalizedNames.langs), and
# the countries whose GeoNames alternate-name dumps supply localised CITY
# names. Country and subdivision names come from the iso-codes catalogues
# and need no per-country dump.
LANGS="${PLACES_LANGS:-ja}"
ALT_COUNTRIES="${PLACES_ALT_COUNTRIES:-JP}"

echo "-> downloading into $TMP"
node -e '
const fs = require("fs");
const [langs, alt, dir] = process.argv.slice(1);
const iso = "https://salsa.debian.org/iso-codes-team/iso-codes/-/raw/main";
const gn  = "https://download.geonames.org/export/dump";
const files = [
  [`${iso}/data/iso_3166-1.json`, "iso_3166-1.json"],
  [`${iso}/data/iso_3166-2.json`, "iso_3166-2.json"],
  [`${gn}/admin1CodesASCII.txt`, "admin1CodesASCII.txt"],
  [`${gn}/cities5000.zip`, "cities5000.zip"],
  ...langs.split(",").filter(Boolean).flatMap((l) => [
    [`${iso}/iso_3166-1/${l}.po`, `iso_3166-1_${l}.po`],
    [`${iso}/iso_3166-2/${l}.po`, `iso_3166-2_${l}.po`],
  ]),
  ...alt.split(",").filter(Boolean).map((cc) => [
    [`${gn}/alternatenames/${cc}.zip`], `alt-${cc}.zip`,
  ]).map(([u, n]) => [u[0], n]),
];
(async () => {
  for (const [url, name] of files) {
    const r = await fetch(url);
    if (!r.ok) { console.error("FAIL", url, r.status); process.exit(1); }
    fs.writeFileSync(`${dir}/${name}`, Buffer.from(await r.arrayBuffer()));
    console.log("  ", name);
  }
})();
' "$LANGS" "$ALT_COUNTRIES" "$TMP"

for z in "$TMP"/*.zip; do unzip -o -q "$z" -d "$TMP"; done
# The per-country alternate-name zips unpack to <CC>.txt; the transform
# expects the alt- prefix so it can tell them from anything else.
for cc in ${ALT_COUNTRIES//,/ }; do
  [ -f "$TMP/$cc.txt" ] && mv "$TMP/$cc.txt" "$TMP/alt-$cc.txt"
done

echo "-> trimming into $OUT"
mkdir -p "$OUT"
node "$ROOT/scripts/build-places.mjs" "$TMP" "$OUT" "$LANGS"

echo "-> done"
wc -l "$OUT"/*.tsv
