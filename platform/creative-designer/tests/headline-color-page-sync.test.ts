// Headline colour page-sync (user decision 2026-07-13): pages[0].
// syncHeadlineColor — ABSENT means ON (always-synced is the historical
// typography-sync behavior). Explicitly false scopes headline colour
// edits to their own page (reader + that page's buckets); every other
// typography property still spans all pages. Re-enabling re-broadcasts
// page 1's headline colour. See state.ts propagateTypography /
// setSyncHeadlineColor.

import { describe, expect, it } from "vitest";
import { initialState, propagateTypography, setSyncHeadlineColor } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const head = (color: string, extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "text", field: "headline", color, ...extra }) as LayoutItem;

const threePages = (): Page[] => ([
  { layout: [head("#aaaaaa")], banners: { "300x250": [head("#aaaaaa")] } },
  { layout: [head("#bbbbbb")] },
  { layout: [head("#cccccc")] },
] as Page[]);

const colors = (s: { pages: Page[] }): (string | undefined)[] =>
  s.pages.map((p) => (p.layout?.[0] as { color?: string } | undefined)?.color);

describe("headline colour propagation vs the page-sync flag", () => {
  it("absent flag (default) → colour spans every page", () => {
    const out = propagateTypography(initialState(threePages(), "expanded"), "headline", { color: "#ff0000" });
    expect(colors(out)).toEqual(["#ff0000", "#ff0000", "#ff0000"]);
  });

  it("flag off → colour stays on the edited page (its buckets included)", () => {
    const s = { ...initialState(threePages(), "expanded") };
    s.pages = s.pages.map((p, i) => (i === 0 ? ({ ...p, syncHeadlineColor: false } as Page) : p));
    const out = propagateTypography(s, "headline", { color: "#ff0000" });
    expect(colors(out)).toEqual(["#ff0000", "#bbbbbb", "#cccccc"]);
    expect((out.pages[0]!.banners!["300x250"]![0] as { color?: string }).color).toBe("#ff0000");
  });

  it("flag off on page 2 → page 2 only", () => {
    const s = { ...initialState(threePages(), "expanded"), pageIdx: 1 };
    s.pages = s.pages.map((p, i) => (i === 0 ? ({ ...p, syncHeadlineColor: false } as Page) : p));
    const out = propagateTypography(s, "headline", { color: "#ff0000" });
    expect(colors(out)).toEqual(["#aaaaaa", "#ff0000", "#cccccc"]);
  });

  it("flag off leaves NON-colour typography global", () => {
    const s = { ...initialState(threePages(), "expanded") };
    s.pages = s.pages.map((p, i) => (i === 0 ? ({ ...p, syncHeadlineColor: false } as Page) : p));
    const out = propagateTypography(s, "headline", { color: "#ff0000", fontFamily: "Georgia" });
    expect(colors(out)).toEqual(["#ff0000", "#bbbbbb", "#cccccc"]);
    expect(out.pages.map((p) => (p.layout?.[0] as { fontFamily?: string }).fontFamily))
      .toEqual(["Georgia", "Georgia", "Georgia"]);
  });
});

describe("setSyncHeadlineColor", () => {
  it("turning ON re-broadcasts page 1's headline colour everywhere", () => {
    let s = initialState(threePages(), "expanded");
    s = { ...s, pages: s.pages.map((p, i) => (i === 0 ? ({ ...p, syncHeadlineColor: false } as Page) : p)) };
    const out = setSyncHeadlineColor(s, true);
    expect(out.pages[0]!.syncHeadlineColor).toBeUndefined(); // absent = on
    expect(colors(out)).toEqual(["#aaaaaa", "#aaaaaa", "#aaaaaa"]);
  });

  it("turning OFF sets the flag and leaves colours as they are", () => {
    const out = setSyncHeadlineColor(initialState(threePages(), "expanded"), false);
    expect(out.pages[0]!.syncHeadlineColor).toBe(false);
    expect(colors(out)).toEqual(["#aaaaaa", "#bbbbbb", "#cccccc"]);
  });
});
