import { readFileSync, readdirSync } from "fs";
import { join } from "path";
import { describe, expect, it } from "vitest";
import { COLORS } from "../src/ui/tokens";
import { weightText } from "../src/ui/canvas-foot";

// Chrome drawn over the canvas (selection, guides, marquee) is ink and
// grey like the rest of the designer (DESIGN.md, The Ink Only Rule).
const files = (dir: string): string[] => readdirSync(dir).map((f) => join(dir, f)).filter((f) => f.endsWith(".ts"));

describe("printed-page designer chrome", () => {
  it("canvas chrome has no hue", () => {
    const bad: string[] = [];
    for (const f of [...files("src/interaction"), ...files("src/render")]) {
      for (const m of readFileSync(f, "utf8").matchAll(/#([0-9a-f]{6})\b/gi)) {
        const h = m[1].toLowerCase();
        if (h.slice(0, 2) !== h.slice(2, 4) || h.slice(2, 4) !== h.slice(4, 6)) bad.push(`${f}: ${m[0]}`);
      }
    }
    expect(bad).toEqual([]);
  });

  // With ok/err as ink, a hover that fills with one token and writes in
  // another of the same value turns the label into a solid block.
  it("no hover writes text in its own fill colour", () => {
    const bad: string[] = [];
    for (const f of files("src/ui")) {
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(/style\.background = tokens\.(\w+);\s*\w+\.style\.color = tokens\.(\w+);/g)) {
        const [bg, fg] = [m[1], m[2]] as (keyof typeof COLORS)[];
        if (COLORS[bg] && COLORS[fg] && (COLORS[bg].dark === COLORS[fg].dark || COLORS[bg].light === COLORS[fg].light)) bad.push(`${f}: ${bg}/${fg}`);
      }
    }
    expect(bad).toEqual([]);
  });

  // ok and err are both ink, so over budget says so in words.
  it("over-budget weight says so", () => {
    expect(weightText(260, "err")).toBe("Weight 260 KB / 200 KB · over budget");
    expect(weightText(120, "ok")).toBe("Weight 120 KB / 200 KB");
  });
});
