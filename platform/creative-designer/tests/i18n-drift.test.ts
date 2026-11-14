// Every t("…") / tk("…") key in src has a Japanese entry, every entry is
// used, and placeholders line up — so a new Designer string can't ship
// untranslated (GH #498). Mirrors platform/i18n_drift_test.go.
import { readdirSync, readFileSync, statSync } from "fs";
import { join } from "path";
import { describe, expect, it } from "vitest";
import { JA } from "../src/i18n/ja";

const SRC = join(__dirname, "..", "src");
const CATALOG = join(SRC, "i18n", "ja.ts");

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? files(p) : p.endsWith(".ts") && p !== CATALOG ? [p] : [];
  });
}

export function extractKeys(source: string): string[] {
  const re = /(?<![\w.$])tk?\(\s*(["'])((?:\\.|(?!\1).)*)\1\s*[,)]/g;
  return [...source.matchAll(re)].map((m) => m[2]!.replace(/\\(.)/g, "$1"));
}

const placeholders = (s: string): string => (s.match(/%[sd]/g) ?? []).join("");

const sources = files(SRC).map((p) => ({ p, s: readFileSync(p, "utf8") }));
const used = new Set(sources.flatMap(({ s }) => extractKeys(s)));

describe("i18n drift", () => {
  it("extracts t / tk literal keys only", () => {
    expect(extractKeys(`t("A") + tk('B', 1) + st("C") + x.t("D")`)).toEqual(["A", "B"]);
  });

  it("every key has a Japanese entry", () => {
    expect([...used].filter((k) => !(k in JA))).toEqual([]);
  });

  it("every Japanese entry is used", () => {
    expect(Object.keys(JA).filter((k) => !used.has(k))).toEqual([]);
  });

  it("placeholders match between key and value", () => {
    expect(Object.entries(JA).filter(([k, v]) => placeholders(k) !== placeholders(v)).map(([k]) => k)).toEqual([]);
  });

  it("no template-literal keys", () => {
    expect(sources.filter(({ s }) => /(?<![\w.$])tk?\(\s*`/.test(s)).map(({ p }) => p)).toEqual([]);
  });
});
