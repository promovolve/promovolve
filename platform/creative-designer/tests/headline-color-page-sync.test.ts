// Per-field colour page-sync (user decisions 2026-07-13): headline AND
// body each carry a flag on pages[0] (syncHeadlineColor / syncBodyColor)
// — ABSENT means ON (always-synced is the historical typography-sync
// behavior). Explicitly false scopes that field's colour edits to their
// own page (reader + that page's buckets); every other typography
// property still spans all pages, and the two fields' flags are
// independent. Re-enabling re-broadcasts page 1's colour. See state.ts
// propagateTypography / setSyncFieldColor.

import { describe, expect, it } from "vitest";
import { initialState, isFieldColorSynced, propagateTypography, setSyncFieldColor } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const txt = (field: string, color: string, extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "text", field, color, ...extra }) as LayoutItem;

const threePages = (): Page[] => ([
  {
    layout: [txt("headline", "#aaaaaa"), txt("body", "#a1a1a1")],
    banners: { "300x250": [txt("headline", "#aaaaaa")] },
  },
  { layout: [txt("headline", "#bbbbbb"), txt("body", "#b1b1b1")] },
  { layout: [txt("headline", "#cccccc"), txt("body", "#c1c1c1")] },
] as Page[]);

const colorsOf = (s: { pages: Page[] }, field: string): (string | undefined)[] =>
  s.pages.map((p) => (p.layout?.find((it) => (it as { field?: string }).field === field) as { color?: string } | undefined)?.color);

const flagOff = (pages: Page[], key: string): Page[] =>
  pages.map((p, i) => (i === 0 ? ({ ...p, [key]: false } as Page) : p));

describe("field colour propagation vs the page-sync flags", () => {
  it("absent flags (default) → colour spans every page, both fields", () => {
    let s = initialState(threePages(), "expanded");
    s = propagateTypography(s, "headline", { color: "#ff0000" });
    s = propagateTypography(s, "body", { color: "#00ff00" });
    expect(colorsOf(s, "headline")).toEqual(["#ff0000", "#ff0000", "#ff0000"]);
    expect(colorsOf(s, "body")).toEqual(["#00ff00", "#00ff00", "#00ff00"]);
  });

  it("headline flag off → headline colour stays on the edited page (buckets included), body still global", () => {
    let s = initialState(flagOff(threePages(), "syncHeadlineColor"), "expanded");
    s = propagateTypography(s, "headline", { color: "#ff0000" });
    s = propagateTypography(s, "body", { color: "#00ff00" });
    expect(colorsOf(s, "headline")).toEqual(["#ff0000", "#bbbbbb", "#cccccc"]);
    expect((s.pages[0]!.banners!["300x250"]![0] as { color?: string }).color).toBe("#ff0000");
    expect(colorsOf(s, "body")).toEqual(["#00ff00", "#00ff00", "#00ff00"]);
  });

  it("body flag off → body colour per-page, headline still global", () => {
    let s = initialState(flagOff(threePages(), "syncBodyColor"), "expanded");
    s = { ...s, pageIdx: 1 };
    s = propagateTypography(s, "body", { color: "#00ff00" });
    s = propagateTypography(s, "headline", { color: "#ff0000" });
    expect(colorsOf(s, "body")).toEqual(["#a1a1a1", "#00ff00", "#c1c1c1"]);
    expect(colorsOf(s, "headline")).toEqual(["#ff0000", "#ff0000", "#ff0000"]);
  });

  it("flag off leaves NON-colour typography global", () => {
    let s = initialState(flagOff(threePages(), "syncBodyColor"), "expanded");
    s = propagateTypography(s, "body", { color: "#00ff00", fontFamily: "Georgia" });
    expect(colorsOf(s, "body")).toEqual(["#00ff00", "#b1b1b1", "#c1c1c1"]);
    expect(s.pages.map((p) => (p.layout?.find((it) => (it as { field?: string }).field === "body") as { fontFamily?: string }).fontFamily))
      .toEqual(["Georgia", "Georgia", "Georgia"]);
  });
});

describe("setSyncFieldColor", () => {
  it("turning ON re-broadcasts page 1's colour for that field everywhere", () => {
    const s = initialState(flagOff(threePages(), "syncHeadlineColor"), "expanded");
    const out = setSyncFieldColor(s, "headline", true);
    expect(out.pages[0]!.syncHeadlineColor).toBeUndefined(); // absent = on
    expect(colorsOf(out, "headline")).toEqual(["#aaaaaa", "#aaaaaa", "#aaaaaa"]);
    expect(colorsOf(out, "body")).toEqual(["#a1a1a1", "#b1b1b1", "#c1c1c1"]); // untouched
  });

  it("turning OFF sets the flag and leaves colours as they are", () => {
    const out = setSyncFieldColor(initialState(threePages(), "expanded"), "body", false);
    expect(out.pages[0]!.syncBodyColor).toBe(false);
    expect(isFieldColorSynced(out, "body")).toBe(false);
    expect(isFieldColorSynced(out, "headline")).toBe(true);
    expect(colorsOf(out, "body")).toEqual(["#a1a1a1", "#b1b1b1", "#c1c1c1"]);
  });

  it("fields without a toggle (sub) always report synced", () => {
    expect(isFieldColorSynced(initialState(threePages(), "expanded"), "sub")).toBe(true);
  });
});
