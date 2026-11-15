// Guards the gaps the key-drift test can't see (GH #498 review): visible
// text assigned without t(), and selectors that match translated text.
import { readdirSync, readFileSync, statSync } from "fs";
import { join } from "path";
import { describe, expect, it } from "vitest";
import { JA } from "../src/i18n/ja";
import { TEMPLATES } from "../src/layout-templates";

const SRC = join(__dirname, "..", "src");
function files(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? files(p) : p.endsWith(".ts") ? [p] : [];
  });
}
const lines = files(SRC).flatMap((p) =>
  readFileSync(p, "utf8").split("\n").map((l, i) => ({ at: `${p.slice(SRC.length + 1)}:${i + 1}`, l })),
);

describe("i18n sweep", () => {
  it("no visible English assigned without t()", () => {
    // textContent / title / placeholder / pill text given a raw English literal.
    const raw = /(textContent|\.title|placeholder|showPill\(|setPill\()\s*=?\s*[`"'][A-Z][a-z]+[- ][a-z]/;
    expect(lines.filter(({ l }) => raw.test(l) && !/\bt\(/.test(l)).map((x) => x.at)).toEqual([]);
  });

  it("no user-facing Error message in English", () => {
    // math.ts throws only on a developer mistake (bad aspect literal).
    const raw = /new Error\(\s*[`"'][A-Z][a-z]+[- ][a-z]/;
    expect(lines.filter(({ at, l }) => raw.test(l) && !/\bt\(/.test(l) && !at.startsWith("math.ts")).map((x) => x.at)).toEqual([]);
  });

  it("no selector matches on placeholder text (it gets translated)", () => {
    expect(lines.filter(({ l }) => /querySelector[^(]*\([^)]*placeholder=/.test(l)).map((x) => x.at)).toEqual([]);
  });

  it("every built-in layout template name and description has a Japanese entry", () => {
    const keys = TEMPLATES.flatMap((t) => [t.name, t.description]);
    expect(keys.filter((k) => !(k in JA))).toEqual([]);
  });
});
