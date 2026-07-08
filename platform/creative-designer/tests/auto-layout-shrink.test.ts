// Generated copy (preset / template / Gemini) must auto-fit its slot:
// applyLayout funnels every generated layout through markGenerated,
// which stamps textFit:"shrink" on text so long copy scales DOWN
// instead of overflowing/clipping. These pin that contract.

import { describe, expect, it } from "vitest";
import { markGenerated } from "../src/auto-layout";
import type { LayoutItem, Page } from "../src/types";

const layout = (): Page["layout"] => [
  // fixed-height headline slot (the overflow-prone case)
  { type: "text", field: "headline", left: 8, top: 20, width: 84, height: 22, fontSize: 10 },
  // heightless body — auto-grows; shrink is inert but still stamped
  { type: "text", field: "body", left: 8, top: 50, width: 84, fontSize: 4 },
  { type: "image", field: "img", left: 0, top: 0, width: 100, height: 100 },
  { type: "rect", left: 0, top: 0, width: 100, height: 6, fill: "#000" },
] as LayoutItem[];

const textFitOf = (it: LayoutItem): unknown => (it as { textFit?: unknown }).textFit;
const generatedFlag = (it: LayoutItem): unknown => (it as { _generated?: unknown })._generated;

describe("markGenerated", () => {
  it("forces textFit:shrink on every text item", () => {
    const texts = markGenerated(layout()).filter((it) => it.type === "text");
    expect(texts.length).toBe(2);
    for (const t of texts) expect(textFitOf(t)).toBe("shrink");
  });

  it("tags every item _generated", () => {
    for (const it of markGenerated(layout())) expect(generatedFlag(it)).toBe(true);
  });

  it("does not put textFit on non-text items", () => {
    const out = markGenerated(layout());
    expect(textFitOf(out.find((it) => it.type === "image")!)).toBeUndefined();
    expect(textFitOf(out.find((it) => it.type === "rect")!)).toBeUndefined();
  });

  it("overrides an incoming textFit:clip (Gemini may have said clip)", () => {
    const clipped = [{ type: "text", field: "headline", height: 20, textFit: "clip" }] as LayoutItem[];
    expect(textFitOf(markGenerated(clipped)[0]!)).toBe("shrink");
  });

  it("is null/empty safe", () => {
    expect(markGenerated(undefined as unknown as Page["layout"])).toEqual([]);
    expect(markGenerated([])).toEqual([]);
  });
});
