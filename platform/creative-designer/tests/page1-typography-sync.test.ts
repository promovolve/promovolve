// Page 1 is the typographic master: its font size / face / writing mode
// propagate to the same field on every other page, so a multi-page
// creative reads consistently (esp. writing-mode: vertical-rl).

import { describe, expect, it } from "vitest";
import { initialState, syncTypographyFromPage1 } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const txt = (field: string, extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "text", field, fontSize: 5, fontFamily: "sans-serif", ...extra }) as LayoutItem;

const pages = (): Page[] => [
  { layout: [
    txt("headline", { fontSize: 10, fontFamily: "Montserrat", writingMode: "vertical-rl" }),
    txt("body", { fontSize: 4 }),
  ] },
  { layout: [txt("headline", { fontSize: 6, fontFamily: "Georgia" }), txt("body", { fontSize: 3 })] },
  { layout: [txt("headline", { fontSize: 8 }), txt("body", { fontSize: 2 })] },
] as Page[];

const find = (p: Page, field: string): Record<string, unknown> =>
  (p.layout!.find((it) => (it as { field?: string }).field === field)) as unknown as Record<string, unknown>;

describe("syncTypographyFromPage1", () => {
  it("propagates page-1 size/face/writing to every other page's same field", () => {
    const out = syncTypographyFromPage1(initialState(pages(), "expanded"));
    for (const pi of [1, 2]) {
      const h = find(out.pages[pi]!, "headline");
      expect(h.fontSize).toBe(10);
      expect(h.fontFamily).toBe("Montserrat");
      expect(h.writingMode).toBe("vertical-rl");
      expect(find(out.pages[pi]!, "body").fontSize).toBe(4);
    }
  });

  it("leaves page 1 (the master) untouched", () => {
    const inp = initialState(pages(), "expanded");
    expect(syncTypographyFromPage1(inp).pages[0]).toBe(inp.pages[0]);
  });

  it("is an idempotent fixpoint once synced", () => {
    const once = syncTypographyFromPage1(initialState(pages(), "expanded"));
    expect(syncTypographyFromPage1(once)).toBe(once);
  });

  it("no-ops with fewer than 2 pages", () => {
    const s = initialState([pages()[0]!], "expanded");
    expect(syncTypographyFromPage1(s)).toBe(s);
  });

  it("also harmonizes the mobile-expanded reader surface (its own master)", () => {
    const p: Page[] = [
      { banners: { "mobile-expanded": [txt("headline", { fontSize: 6, writingMode: "vertical-rl" })] } },
      { banners: { "mobile-expanded": [txt("headline", { fontSize: 4 })] } },
    ] as Page[];
    const out = syncTypographyFromPage1(initialState(p, "expanded"));
    const h = out.pages[1]!.banners!["mobile-expanded"]!
      .find((it) => (it as { field?: string }).field === "headline") as unknown as Record<string, unknown>;
    expect(h.fontSize).toBe(6);
    expect(h.writingMode).toBe("vertical-rl");
  });

  it("only matches by field — an unmatched field is left alone", () => {
    const p: Page[] = [
      { layout: [txt("headline", { fontSize: 10 })] },
      { layout: [txt("headline", { fontSize: 6 }), txt("caption", { fontSize: 9 })] },
    ] as Page[];
    const out = syncTypographyFromPage1(initialState(p, "expanded"));
    expect(find(out.pages[1]!, "headline").fontSize).toBe(10);
    expect(find(out.pages[1]!, "caption").fontSize).toBe(9); // untouched
  });
});
