import { describe, expect, it } from "vitest";
import { COLORS, themeCss } from "../src/ui/tokens";

// The designer chrome is a printed page too (DESIGN.md, The Ink Only
// Rule): every token, in both themes, is a neutral grey. The creative on
// the canvas keeps its colour; it never comes from these tokens.
function channels(c: string): number[] {
  const hex = c.match(/^#([0-9a-f]{6})$/i);
  if (hex) return [0, 2, 4].map((i) => parseInt(hex[1].slice(i, i + 2), 16));
  const rgb = c.match(/rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/);
  if (rgb) return [rgb[1], rgb[2], rgb[3]].map(Number);
  throw new Error(`unparsed colour ${c}`);
}

describe("designer chrome is neutral", () => {
  for (const [name, pair] of Object.entries(COLORS)) {
    for (const theme of ["dark", "light"] as const) {
      it(`${name} (${theme})`, () => {
        const [r, g, b] = channels(pair[theme]);
        expect(Math.max(r, g, b) - Math.min(r, g, b), pair[theme]).toBeLessThanOrEqual(2);
      });
    }
  }
});

// Native range/checkbox/progress controls default to the browser's blue
// accent; the theme style sets them to ink.
it("native controls take the ink accent", () => {
  expect(themeCss()).toContain("accent-color:var(--cd-ink100)");
});
