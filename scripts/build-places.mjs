// Build the place vocabulary Promovolve ships. Invoked by build-places.sh.
//
// Two authorities, deliberately:
//   ISO 3166-1 / 3166-2  (Debian iso-codes)  -> countries and subdivisions
//   GeoNames cities5000                      -> cities
//
// GeoNames is NOT used for subdivision codes. Its admin1 numbering is its
// own and does not match ISO - GeoNames JP.13 is Hyogo, ISO JP-13 is Tokyo.
// Shipping GeoNames codes under an ISO label would have resolved every
// LLM-emitted "JP-13" to the wrong prefecture, silently. Cities are linked
// to ISO subdivisions by exact normalised name within their country; an
// unmatched admin1 stores empty and the chain falls back to the country,
// which is a smaller error than a wrong subdivision.
//
// Code scheme:
//   country      "JP"            ISO 3166-1 alpha-2
//   subdivision  "JP-13"         ISO 3166-2
//   city         "GN1863440"     GeoNames id, prefixed
//
// Ids are opaque. The ancestor chain comes from the table's own
// country/admin1 columns, never from parsing the string.
//
// Localised names follow the existing taxonomy convention
// (LocalizedNames.loadAll): `<base>_<lang>.tsv` of `code<TAB>name`.
// `aliases_<lang>.tsv` (same shape, many rows per code) carries every
// OTHER localised name GeoNames knows for a place - the everyday アメリカ
// next to the catalogue's formal 米国 - and feeds search only, never display.
//
// `aliases_en.tsv` is NOT generated here and this script refuses to write
// it. English colloquial names ("South Korea" for the formal "Korea,
// Republic of") are exactly what neither source carries, so there is
// nothing to merge them out of; the file is hand-maintained and reviewed.

import fs from "node:fs";
import readline from "node:readline";

const [srcDir, outDir, langsArg] = process.argv.slice(2);
// "en" is refused rather than ignored: passing it would silently overwrite
// the hand-maintained aliases_en.tsv with an empty generated one.
const LANGS = (langsArg || "ja").split(",").filter(Boolean);
if (LANGS.includes("en")) {
  console.error("build-places: 'en' is not a generated language — aliases_en.tsv is hand-maintained.");
  process.exit(1);
}

const read = (f) => fs.readFileSync(`${srcDir}/${f}`, "utf8");
const lines = (f) => read(f).split("\n");
const write = (f, header, rows) =>
  fs.writeFileSync(`${outDir}/${f}`, [header, ...rows].join("\n") + "\n", "utf8");
const byCode = (a, b) => a[0].localeCompare(b[0]);

/** Diacritic- and punctuation-insensitive key for cross-source name matching. */
const norm = (s) =>
  (s || "").normalize("NFD").replace(/[̀-ͯ]/g, "")
    .toLowerCase().replace(/[^a-z0-9]/g, "");

/** gettext .po -> Map<msgid, msgstr>, skipping empty entries. */
function parsePo(text) {
  const out = new Map();
  const re = /msgid\s+"((?:[^"\\]|\\.)*)"\s*\nmsgstr\s+"((?:[^"\\]|\\.)*)"/g;
  for (const m of text.matchAll(re)) {
    const id = m[1].replace(/\\"/g, '"');
    const str = m[2].replace(/\\"/g, '"');
    if (id && str) out.set(id, str);
  }
  return out;
}

// -- countries (ISO 3166-1) -------------------------------------------
const iso1 = JSON.parse(read("iso_3166-1.json"))["3166-1"];
const countries = iso1
  .filter((c) => c.alpha_2 && c.name)
  .map((c) => [c.alpha_2, c.name]);
countries.sort(byCode);
const validCountries = new Set(countries.map((r) => r[0]));
const countryEnName = new Map(countries);
const countryOfficial = new Map(
  iso1.filter((c) => c.alpha_2).map((c) => [c.alpha_2, c.official_name || ""]));

// -- subdivisions (ISO 3166-2) ----------------------------------------
const iso2 = JSON.parse(read("iso_3166-2.json"))["3166-2"];
const subdivisions = [];
for (const s of iso2) {
  if (!s.code || !s.name) continue;
  const country = s.code.split("-")[0];
  if (!validCountries.has(country)) continue;
  subdivisions.push([s.code, country, s.name]);
}
subdivisions.sort(byCode);
const subEnName = new Map(subdivisions.map((r) => [r[0], r[2]]));

// -- name matching for the GeoNames link --------------------------------
// The sources disagree in SHAPE, not just spelling: GeoNames says "Tainan
// City" where ISO says "Tainan"; ISO says "Asturias, Principado de" where
// GeoNames says "Asturias"; brackets carry alternates ("Catalunya
// [Cataluna]"). Exact-equality matching left 39% of cities with no
// subdivision link — and an unlinked city silently kills subdivision-level
// place targeting for every page classified as that city (found live
// 2026-08-27: targeting TW-TNN could never match the Tainan page).
//
// Every variant match is UNIQUE-GATED: a key that could mean two different
// subdivisions of one country maps to nothing. A wrong link is worse than
// a blank — a blank degrades targeting to the country, a wrong link sends
// it to the wrong subdivision.
const ADMIN_WORDS = new Set([
  "city", "province", "prefecture", "state", "region", "district", "county",
  "municipality", "governorate", "oblast", "krai", "voivodeship", "department",
  "canton", "territory", "division", "zone", "emirate", "parish", "special",
  "metropolitan", "autonomous", "capital", "federal", "do", "si", "shi", "ken",
  "fu", "sheng",
]);
/** All the ways one subdivision/admin1 name may be written, normalized. */
function nameKeys(raw) {
  const out = new Set();
  const bare = (raw || "").replace(/\[[^\]]*\]/g, " ").trim();
  const inBrackets = [...(raw || "").matchAll(/\[([^\]]*)\]/g)].map((m) => m[1]);
  const bases = [bare, ...inBrackets];
  const cut = bare.indexOf(",");
  if (cut > 0) bases.push(bare.slice(0, cut), `${bare.slice(cut + 1)} ${bare.slice(0, cut)}`);
  for (const b of bases) {
    const k = norm(b);
    if (k) out.add(k);
    // Strip leading/trailing administrative words (possibly several:
    // "Special Municipality"), word by word.
    let words = b.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
    while (words.length > 1 && ADMIN_WORDS.has(words[words.length - 1])) words = words.slice(0, -1);
    while (words.length > 1 && ADMIN_WORDS.has(words[0])) words = words.slice(1);
    const stripped = words.join("");
    if (stripped) out.add(stripped);
  }
  return out;
}
// TIER 1 — the original exact map, PRESERVED VERBATIM (same insertion
// semantics as before the variant matching existed). Anything this map
// linked before must keep linking identically: the first cut of the
// variant matcher let derived keys collide with exact ones ("Madrid" vs
// "Madrid, Comunidad de"), which UNLINKED 375 previously-linked cities
// and, in one case, let the city-name fallback assign Madrid's Salamanca
// district to Salamanca province. Exact wins; variants only ever ADD.
const subExact = new Map();
for (const [code, country, name] of subdivisions) {
  subExact.set(`${country} ${norm(name)}`, code);
}
// TIER 2 — variant keys, unique-gated, consulted only when tier 1 missed.
const subKeyToIso = new Map();
for (const [code, country, name] of subdivisions) {
  for (const k of nameKeys(name)) {
    const mapKey = `${country} ${k}`;
    if (subKeyToIso.has(mapKey) && subKeyToIso.get(mapKey) !== code) subKeyToIso.set(mapKey, null);
    else subKeyToIso.set(mapKey, code);
  }
}
/** ISO subdivision for a name in a country: exact first, else unique variant. */
function isoSubFor(country, raw) {
  const exact = subExact.get(`${country} ${norm(raw)}`);
  if (exact) return exact;
  const hits = new Set();
  for (const k of nameKeys(raw)) {
    const c = subKeyToIso.get(`${country} ${k}`);
    if (c) hits.add(c);
  }
  return hits.size === 1 ? [...hits][0] : undefined;
}

// -- cities (GeoNames) ------------------------------------------------
// cities5000: 0 id, 1 name, 2 ascii, 8 country, 10 admin1, 14 population
const gnAdmin1Name = new Map(); // "JP.13" -> "Hyogo"
for (const line of lines("admin1CodesASCII.txt")) {
  if (!line.trim()) continue;
  const c = line.split("\t");
  if (c[0]) gnAdmin1Name.set(c[0], c[1] || c[2] || "");
}
/** GeoNames "JP.13" -> ISO "JP-28", or absent when no confident match. */
const gnToIso = new Map();
let gnAdmin1Total = 0, gnAdmin1Mapped = 0;
for (const [gnKey, name] of gnAdmin1Name) {
  const country = gnKey.split(".")[0];
  if (!validCountries.has(country)) continue;
  gnAdmin1Total++;
  const iso = isoSubFor(country, name);
  if (iso) { gnToIso.set(gnKey, iso); gnAdmin1Mapped++; }
}

// GeoNames ids of the countries and subdivisions we ship, so their
// alternate names (which the per-country dumps carry alongside the
// cities') can be attributed to the ISO code.
const countryGeoId = new Map(); // geonameid -> "JP"
// GeoNames continent code (AS, EU, ...) per country: what the classifier
// derives a Travel Locations category from, instead of asking the model.
const countryContinent = new Map(); // "JP" -> "AS"
for (const line of lines("countryInfo.txt")) {
  if (!line.trim() || line.startsWith("#")) continue;
  const c = line.split("\t");
  if (c[0] && c[16] && validCountries.has(c[0])) countryGeoId.set(c[16], c[0]);
  if (c[0] && c[8] && validCountries.has(c[0])) countryContinent.set(c[0], c[8]);
}
const admin1GeoId = new Map(); // geonameid -> "JP-28"
for (const line of lines("admin1CodesASCII.txt")) {
  if (!line.trim()) continue;
  const c = line.split("\t");
  const iso = c[0] && gnToIso.get(c[0]);
  if (iso && c[3]) admin1GeoId.set(c[3], iso);
}

const cities = [];
const cityGeoId = new Map(); // geonameid -> "GN123"
for (const line of lines("cities5000.txt")) {
  if (!line.trim()) continue;
  const c = line.split("\t");
  if (c.length < 15 || !c[0] || !c[8]) continue;
  if (!validCountries.has(c[8])) continue;
  const code = `GN${c[0]}`;
  let admin1 = c[10] ? (gnToIso.get(`${c[8]}.${c[10]}`) || "") : "";
  // Last resort for a city whose admin1 could not be mapped AT ALL (e.g.
  // GeoNames' Taiwan has only 4 admin1s — "Taiwan" covers Tainan, so no
  // admin1 mapping can ever exist): a large city whose OWN name uniquely
  // names a subdivision of its country IS that subdivision (Taiwan's
  // special municipalities, metropolitan cities). Guard rails, in order:
  // a mapped admin1 always wins (a Madrid district named Salamanca keeps
  // Madrid — it never reaches this line); the match is unique-gated; and
  // the population floor keeps a small township that merely shares a
  // distant subdivision's name from being teleported into it — for a
  // city that big, sharing the name IS the relationship.
  const pop = parseInt(c[14] || "0", 10);
  if (!admin1 && pop >= 100000) admin1 = isoSubFor(c[8], c[1]) || isoSubFor(c[8], c[2]) || "";
  cities.push([code, c[1], c[8], admin1, c[14] || "0"]);
  cityGeoId.set(c[0], code);
}
cities.sort(byCode);
const linkedCities = cities.filter((r) => r[3]).length;

write("countries.tsv", "code\tname_en\tcontinent",
  countries.map((r) => [...r, countryContinent.get(r[0]) || ""].join("\t")));
write("subdivisions.tsv", "code\tcountry\tname_en", subdivisions.map((r) => r.join("\t")));
write("cities.tsv", "code\tname_en\tcountry\tadmin1\tpopulation",
  cities.map((r) => r.join("\t")));

// -- localised names --------------------------------------------------
// Countries and subdivisions: iso-codes gettext catalogues, keyed by the
// English name. Cities: the GeoNames per-country alternate-name dumps,
// keyed by geonameid - the only source that covers 69k cities.
//
// Coverage note: a per-country alternate-name dump carries names for
// places IN that country, so JP.txt gives Japanese names for Japanese
// places - what a Japanese publisher declaring their own town needs. It
// does not give the Japanese name for Paris; that lives in FR.txt.
const counts = [];

/** Key for "is this the same name" across scripts - no case, no marks, no punctuation. */
const normKey = (s) =>
  (s || "").normalize("NFD").toLowerCase()
    .replace(/[^\p{L}\p{N}]/gu, "");

// One streaming pass over every alternate-name dump (US.txt alone is
// hundreds of MB; a whole-file split would not fit a JS string).
// Columns: 0 id, 1 geonameid, 2 lang, 3 name, 4 preferred, 5 short,
// 6 colloquial, 7 historic.
//   altPick  lang -> code -> {name, rank}   the one display name per CITY
//   altNames lang -> code -> [names]        every non-historic name, all levels
const altPick = new Map(LANGS.map((l) => [l, new Map()]));
const altNames = new Map(LANGS.map((l) => [l, new Map()]));
const wanted = new Set(LANGS);
const codeOf = (geoId) =>
  cityGeoId.get(geoId) || admin1GeoId.get(geoId) || countryGeoId.get(geoId);
for (const f of fs.readdirSync(srcDir).filter((f) => /^alt-[A-Z]{2}\.txt$/.test(f))) {
  const rl = readline.createInterface({ input: fs.createReadStream(`${srcDir}/${f}`, "utf8") });
  for await (const line of rl) {
    if (!line) continue;
    const c = line.split("\t");
    if (!wanted.has(c[2]) || !c[3]) continue;
    if (c[7] === "1") continue; // historic
    const code = codeOf(c[1]);
    if (!code) continue;
    const names = altNames.get(c[2]);
    const list = names.get(code);
    if (list) list.push(c[3]); else names.set(code, [c[3]]);
    if (c[6] === "1" || !code.startsWith("GN")) continue; // display pick: cities, non-colloquial
    const rank = c[4] === "1" ? 0 : c[5] === "1" ? 1 : 2;
    const pick = altPick.get(c[2]);
    const prev = pick.get(code);
    if (!prev || rank < prev.rank ||
        (rank === prev.rank && c[3].length < prev.name.length)) {
      pick.set(code, { name: c[3], rank });
    }
  }
}

for (const lang of LANGS) {
  const po1 = fs.existsSync(`${srcDir}/iso_3166-1_${lang}.po`)
    ? parsePo(read(`iso_3166-1_${lang}.po`)) : new Map();
  const po2 = fs.existsSync(`${srcDir}/iso_3166-2_${lang}.po`)
    ? parsePo(read(`iso_3166-2_${lang}.po`)) : new Map();

  const cRows = [];
  for (const [code, name] of countryEnName) {
    const t = po1.get(name) || po1.get(countryOfficial.get(code) || " ");
    if (t) cRows.push([code, t]);
  }
  const sRows = [];
  for (const [code, name] of subEnName) {
    const t = po2.get(name);
    if (t) sRows.push([code, t]);
  }

  const cityPick = altPick.get(lang) || new Map();
  const cityRows = [...cityPick].map(([code, v]) => [code, v.name]);

  // Aliases: every other name the dumps know, minus the one shown, plus
  // the curated scripts/places-aliases-<lang>.tsv for names neither
  // source carries (イギリス: iso-codes and GeoNames both say only 英国).
  const shown = new Map([...cRows, ...sRows, ...cityRows].map(([code, n]) => [code, normKey(n)]));
  const merged = new Map(altNames.get(lang) || new Map());
  const curatedPath = `${import.meta.dirname}/places-aliases-${lang}.tsv`;
  if (fs.existsSync(curatedPath)) {
    for (const line of fs.readFileSync(curatedPath, "utf8").split("\n")) {
      if (!line.trim() || line.startsWith("#")) continue;
      const [code, name] = line.split("\t");
      if (!code || !name) continue;
      const list = merged.get(code);
      if (list) list.push(name); else merged.set(code, [name]);
    }
  }
  const aliasRows = [];
  for (const [code, names] of merged) {
    const seen = new Set([shown.get(code)].filter(Boolean));
    for (const n of names) {
      const k = normKey(n);
      if (!k || seen.has(k)) continue;
      seen.add(k);
      aliasRows.push([code, n]);
    }
  }

  for (const [file, rows] of
       [[`countries_${lang}.tsv`, cRows], [`subdivisions_${lang}.tsv`, sRows],
        [`cities_${lang}.tsv`, cityRows], [`aliases_${lang}.tsv`, aliasRows]]) {
    rows.sort(byCode);
    write(file, "code\tname", rows.map((r) => r.join("\t")));
  }
  counts.push(`${lang}: ${cRows.length}c/${sRows.length}s/${cityRows.length}city/${aliasRows.length}alias`);
}

const pct = (n, d) => d ? `${(100 * n / d).toFixed(1)}%` : "n/a";
console.log(
  `   countries=${countries.length} subdivisions=${subdivisions.length} cities=${cities.length}\n` +
  `   GeoNames admin1 -> ISO 3166-2: ${gnAdmin1Mapped}/${gnAdmin1Total} (${pct(gnAdmin1Mapped, gnAdmin1Total)})\n` +
  `   cities linked to a subdivision: ${linkedCities}/${cities.length} (${pct(linkedCities, cities.length)}); ` +
  `the rest chain to their country\n` +
  `   localised - ${counts.join("  ")}`
);
