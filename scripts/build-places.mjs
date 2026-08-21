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

import fs from "node:fs";

const [srcDir, outDir, langsArg] = process.argv.slice(2);
const LANGS = (langsArg || "ja").split(",").filter(Boolean);

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

// name -> ISO code, per country, for the GeoNames link
const subByCountryName = new Map();
for (const [code, country, name] of subdivisions) {
  subByCountryName.set(`${country} ${norm(name)}`, code);
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
  const iso = subByCountryName.get(`${country} ${norm(name)}`);
  if (iso) { gnToIso.set(gnKey, iso); gnAdmin1Mapped++; }
}

const cities = [];
const cityGeoId = new Map(); // geonameid -> "GN123"
for (const line of lines("cities5000.txt")) {
  if (!line.trim()) continue;
  const c = line.split("\t");
  if (c.length < 15 || !c[0] || !c[8]) continue;
  if (!validCountries.has(c[8])) continue;
  const code = `GN${c[0]}`;
  const admin1 = c[10] ? (gnToIso.get(`${c[8]}.${c[10]}`) || "") : "";
  cities.push([code, c[1], c[8], admin1, c[14] || "0"]);
  cityGeoId.set(c[0], code);
}
cities.sort(byCode);
const linkedCities = cities.filter((r) => r[3]).length;

write("countries.tsv", "code\tname_en", countries.map((r) => r.join("\t")));
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

  const cityPick = new Map();
  for (const f of fs.readdirSync(srcDir).filter((f) => /^alt-[A-Z]{2}\.txt$/.test(f))) {
    for (const line of read(f).split("\n")) {
      if (!line.trim()) continue;
      const c = line.split("\t");
      if (c[2] !== lang) continue;
      if (c[7] === "1" || c[6] === "1") continue; // historic / colloquial
      const code = cityGeoId.get(c[1]);
      if (!code || !c[3]) continue;
      const rank = c[4] === "1" ? 0 : c[5] === "1" ? 1 : 2;
      const prev = cityPick.get(code);
      if (!prev || rank < prev.rank ||
          (rank === prev.rank && c[3].length < prev.name.length)) {
        cityPick.set(code, { name: c[3], rank });
      }
    }
  }
  const cityRows = [...cityPick].map(([code, v]) => [code, v.name]);

  for (const [file, rows] of
       [[`countries_${lang}.tsv`, cRows], [`subdivisions_${lang}.tsv`, sRows],
        [`cities_${lang}.tsv`, cityRows]]) {
    rows.sort(byCode);
    write(file, "code\tname", rows.map((r) => r.join("\t")));
  }
  counts.push(`${lang}: ${cRows.length}c/${sRows.length}s/${cityRows.length}city`);
}

const pct = (n, d) => d ? `${(100 * n / d).toFixed(1)}%` : "n/a";
console.log(
  `   countries=${countries.length} subdivisions=${subdivisions.length} cities=${cities.length}\n` +
  `   GeoNames admin1 -> ISO 3166-2: ${gnAdmin1Mapped}/${gnAdmin1Total} (${pct(gnAdmin1Mapped, gnAdmin1Total)})\n` +
  `   cities linked to a subdivision: ${linkedCities}/${cities.length} (${pct(linkedCities, cities.length)}); ` +
  `the rest chain to their country\n` +
  `   localised - ${counts.join("  ")}`
);
