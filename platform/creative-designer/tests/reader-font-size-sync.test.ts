// Font size syncs ABSOLUTELY across the multi-page reader from ANY page.
// Regression: the page-1-master subscriber used to revert size edits made
// on pages 2/3 (only page 1 was editable — "font size doesn't sync").
// Reader pages share one geometry so an absolute copy is correct; banner
// buckets keep their proportional fontScale fan-out.

import { describe, expect, it } from "vitest";
import { initialState, setReaderFieldFontSize, syncTypographyFromPage1 } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const txt = (field: string, fontSize: number): LayoutItem =>
  ({ type: "text", field, fontSize, fontFamily: "sans-serif" }) as LayoutItem;

const readerPages = (): Page[] => [
  { layout: [txt("body", 5)], banners: { "mobile-expanded": [txt("body", 4)], "300x250": [txt("body", 3)] } },
  { layout: [txt("body", 6)], banners: { "mobile-expanded": [txt("body", 4.5)] } },
  { layout: [txt("body", 7)], banners: { "mobile-expanded": [txt("body", 5)] } },
] as Page[];

const sizeOf = (p: Page, surface: "layout" | "mobile-expanded"): number => {
  const items = surface === "layout" ? p.layout! : p.banners!["mobile-expanded"]!;
  return (items[0] as unknown as { fontSize: number }).fontSize;
};

describe("setReaderFieldFontSize", () => {
  it("sets the same absolute size on every page's reader surfaces", () => {
    const out = setReaderFieldFontSize(initialState(readerPages(), "expanded"), "body", 9);
    for (const pi of [0, 1, 2]) {
      expect(sizeOf(out.pages[pi]!, "layout")).toBe(9);
      expect(sizeOf(out.pages[pi]!, "mobile-expanded")).toBe(9);
    }
  });

  it("does not touch banner buckets (they scale proportionally elsewhere)", () => {
    const out = setReaderFieldFontSize(initialState(readerPages(), "expanded"), "body", 9);
    const bucket = out.pages[0]!.banners!["300x250"]![0] as unknown as { fontSize: number };
    expect(bucket.fontSize).toBe(3);
  });

  it("survives the page-1-master subscriber (no revert): sync is a no-op after it", () => {
    // Simulates an edit made from page 3: absolute set, then the
    // subscriber runs — it must converge, not fight.
    const set = setReaderFieldFontSize(initialState(readerPages(), "expanded"), "body", 11);
    const afterSubscriber = syncTypographyFromPage1(set);
    expect(afterSubscriber).toBe(set); // fixpoint — nothing reverted
    for (const pi of [0, 1, 2]) {
      expect(sizeOf(afterSubscriber.pages[pi]!, "layout")).toBe(11);
    }
  });

  it("ignores non-matching fields and field-less text", () => {
    const p: Page[] = [
      { layout: [txt("headline", 8), { type: "text", fontSize: 6 } as LayoutItem] },
      { layout: [txt("headline", 5)] },
    ] as Page[];
    const out = setReaderFieldFontSize(initialState(p, "expanded"), "body", 9);
    expect(out).toBe(out); // no crash
    expect(sizeOf(out.pages[1]!, "layout")).toBe(5); // headline untouched
  });

  it("rejects nonsense sizes", () => {
    const s = initialState(readerPages(), "expanded");
    expect(setReaderFieldFontSize(s, "body", 0)).toBe(s);
    expect(setReaderFieldFontSize(s, "body", NaN)).toBe(s);
    expect(setReaderFieldFontSize(s, "", 9)).toBe(s);
  });
});

import { fitReaderFieldBoxes } from "../src/state";

describe("fitReaderFieldBoxes", () => {
  const boxed = (field: string, height: number, text?: string): LayoutItem =>
    ({ type: "text", field, fontSize: 5, width: 88, height, ...(text ? { text } : {}) }) as LayoutItem;

  it("fits each page's box to ITS OWN text via the measurer", () => {
    const p: Page[] = [
      { body: "short", layout: [boxed("body", 10)] },
      { body: "a much longer body that needs a taller box", layout: [boxed("body", 10)] },
    ] as Page[];
    // stub measurer: height proportional to text length
    const out = fitReaderFieldBoxes(initialState(p, "expanded"), "body",
      (text) => Math.ceil(text.length / 2));
    expect((out.pages[0]!.layout![0] as unknown as { height: number }).height).toBe(3);
    expect((out.pages[1]!.layout![0] as unknown as { height: number }).height).toBe(21);
  });

  it("prefers the item's baked text over the page field", () => {
    const p: Page[] = [
      { body: "shared body", layout: [boxed("body", 10, "override text that is longer")] },
    ] as Page[];
    const seen: string[] = [];
    fitReaderFieldBoxes(initialState(p, "expanded"), "body", (text) => { seen.push(text); return 5; });
    expect(seen).toEqual(["override text that is longer"]);
  });

  it("skips vertical items and null measurements; fixpoint when heights match", () => {
    const p: Page[] = [
      { body: "b", layout: [
        { type: "text", field: "body", fontSize: 5, height: 10, writingMode: "vertical-rl" } as LayoutItem,
      ] },
      { body: "b", layout: [boxed("body", 7)] },
    ] as Page[];
    const s1 = initialState(p, "expanded");
    // measurer returns 7 → page 2 already at 7, vertical skipped → same state
    expect(fitReaderFieldBoxes(s1, "body", () => 7)).toBe(s1);
    expect(fitReaderFieldBoxes(s1, "body", () => null)).toBe(s1);
  });
});
