import { describe, expect, it } from "vitest";
import { TEMPLATES, orientationScore, slotsAsPromptLine } from "../src/layout-templates";
import { addItem, applyTemplate, currentLayout, initialState, setSelection } from "../src/state";
import type { RectItem } from "../src/types";

const r = (left: number): RectItem => ({
  type: "rect", left, top: 10, width: 10, height: 10, fill: "#000",
});

describe("applyTemplate", () => {
  it("replaces the current mode's layout wholesale", () => {
    let s = initialState([{ layout: [], banners: {} }]);
    s = addItem(s, r(0));
    s = addItem(s, r(20));
    expect(currentLayout(s)).toHaveLength(2);

    const tplItems = TEMPLATES[0]!.items;
    const after = applyTemplate(s, tplItems);
    expect(currentLayout(after)).toHaveLength(tplItems.length);
    expect(currentLayout(after)[0]).toEqual(tplItems[0]);
  });

  it("clears selection (prior indexes no longer point at the same items)", () => {
    let s = initialState([{ layout: [], banners: {} }]);
    s = addItem(s, r(0));
    s = setSelection(s, [0]);
    expect(s.selectedItemIdxs).toEqual([0]);

    const after = applyTemplate(s, TEMPLATES[0]!.items);
    expect(after.selectedItemIdxs).toEqual([]);
  });

  it("returns an independent layout (mutation guard)", () => {
    const s = initialState([{ layout: [], banners: {} }]);
    const tplItems = TEMPLATES[0]!.items;
    const after = applyTemplate(s, tplItems);
    // Mutating the returned layout shouldn't affect the template source.
    const layout = currentLayout(after);
    layout.push(r(99));
    expect(TEMPLATES[0]!.items).toHaveLength(tplItems.length);
  });
});

describe("orientationScore", () => {
  it("any-orientation templates score 0.5 in every aspect", () => {
    const tpl = { ...TEMPLATES[0]!, orientation: "any" as const };
    expect(tpl.orientation).toBe("any");
    expect(orientationScore(tpl, 16 / 9)).toBe(0.5);
    expect(orientationScore(tpl, 1)).toBe(0.5);
    expect(orientationScore(tpl, 9 / 16)).toBe(0.5);
  });

  it("landscape templates win when canvas is wide", () => {
    const tpl = { ...TEMPLATES[0]!, orientation: "landscape" as const };
    expect(orientationScore(tpl, 16 / 9)).toBe(1);
    expect(orientationScore(tpl, 9 / 16)).toBe(0);
    expect(orientationScore(tpl, 1)).toBe(0); // 1 < 1.2 threshold
  });

  it("portrait threshold (canvasAspect < 0.85)", () => {
    const portraitTpl = { ...TEMPLATES[0]!, orientation: "portrait" as const };
    expect(orientationScore(portraitTpl, 0.5)).toBe(1);
    expect(orientationScore(portraitTpl, 0.84)).toBe(1);
    expect(orientationScore(portraitTpl, 1)).toBe(0);
  });

  it("square match window (0.85..1.2)", () => {
    const squareTpl = { ...TEMPLATES[0]!, orientation: "square" as const };
    expect(orientationScore(squareTpl, 1)).toBe(1);
    expect(orientationScore(squareTpl, 0.85)).toBe(1);
    expect(orientationScore(squareTpl, 1.2)).toBe(1);
    expect(orientationScore(squareTpl, 0.7)).toBe(0);
    expect(orientationScore(squareTpl, 1.5)).toBe(0);
  });
});

describe("template slot specs", () => {
  it("every template has at least one slot", () => {
    for (const tpl of TEMPLATES) {
      expect(tpl.slots.length, `template ${tpl.id}`).toBeGreaterThan(0);
    }
  });

  it("every slot has a recognised role and region", () => {
    const validRoles = new Set(["headline", "subheadline", "body", "hero", "accent", "badge", "divider"]);
    const validRegions = new Set([
      "top-left", "top", "top-right",
      "left", "center", "right",
      "bottom-left", "bottom", "bottom-right",
    ]);
    for (const tpl of TEMPLATES) {
      for (const slot of tpl.slots) {
        expect(validRoles.has(slot.role), `${tpl.id}: bad role ${slot.role}`).toBe(true);
        expect(validRegions.has(slot.region), `${tpl.id}: bad region ${slot.region}`).toBe(true);
      }
    }
  });

});

describe("slotsAsPromptLine", () => {
  it("emits a single sentence describing every slot", () => {
    // Synthetic slot spec (independent of the catalog) so the format
    // assertions hold regardless of which templates ship.
    const tpl = { ...TEMPLATES[0]!, slots: [
      { role: "hero",     region: "top",    prominence: "primary"   },
      { role: "headline", region: "center", prominence: "primary"   },
      { role: "body",     region: "bottom", prominence: "secondary" },
    ] } as typeof TEMPLATES[0];
    const line = slotsAsPromptLine(tpl);
    expect(line).toContain("Layout intent:");
    expect(line).toContain("hero (primary) in top");
    expect(line).toContain("headline (primary) in center");
    expect(line).toContain("body (secondary) in bottom");
  });

  it("returns empty string for a slot-less template (defensive)", () => {
    const empty = { ...TEMPLATES[0]!, slots: [] };
    expect(slotsAsPromptLine(empty)).toBe("");
  });

  it("omits the prominence parens when not set", () => {
    const tpl = { ...TEMPLATES[0]!, slots: [{ role: "headline" as const, region: "center" as const }] };
    expect(slotsAsPromptLine(tpl)).toBe("Layout intent: headline in center.");
  });
});
