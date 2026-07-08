import { describe, expect, it } from "vitest";
import { collectEmptySizes } from "../src/ui/save";
import type { Store } from "../src/store";
import type { LayoutItem, Page } from "../src/types";

// A trivial non-empty item; fanoutStatus only cares whether the bucket
// has length, so the shape is irrelevant.
const item = (): LayoutItem => ({ type: "rect", left: 0, top: 0, width: 10, height: 10, fill: "#000" } as LayoutItem);

// Every size bucket the fanout walks (MODES with a sizeKey) plus the
// expanded layout. A fully-authored page fills all of them.
const fullPage = (): Page => ({
  layout: [item()],
  banners: {
    "mobile-expanded": [item()],
    "300x250": [item()],
    "970x250": [item()],
    "728x90": [item()],
    "320x100": [item()],
    "300x600": [item()],
  },
});

const store = (pages: Page[]): Store => ({ state: { pages } } as unknown as Store);

describe("collectEmptySizes (publish gate)", () => {
  it("passes a fully-authored single page", () => {
    expect(collectEmptySizes(store([fullPage()]))).toEqual([]);
  });

  it("flags a missing banner size", () => {
    const p = fullPage();
    delete p.banners!["300x250"];
    const empties = collectEmptySizes(store([p]));
    expect(empties).toHaveLength(1);
    expect(empties[0]).toMatchObject({ page: 1 });
    expect(empties[0].label).toMatch(/Rectangle/);
  });

  it("flags an empty (zero-item) bucket, not just a missing key", () => {
    const p = fullPage();
    p.banners!["728x90"] = [];
    const empties = collectEmptySizes(store([p]));
    expect(empties.map((e) => e.label).some((l) => /Strip/.test(l))).toBe(true);
  });

  it("does NOT gate on the tabless wide layout (page.layout)", () => {
    // The 16:9 wide layout has no tab — it's a fanout-maintained
    // delivery artifact the author can't fill by hand, so an empty one
    // must never block publish.
    const p = fullPage();
    p.layout = [];
    expect(collectEmptySizes(store([p]))).toEqual([]);
  });

  it("reports per-page so a later blank page is caught with its 1-based index", () => {
    const empties = collectEmptySizes(store([fullPage(), { layout: [], banners: {} }]));
    // Page 1 is clean; page 2 is blank across every editable size
    // (the portrait reader + 5 buckets — the tabless wide layout
    // doesn't gate).
    expect(empties.every((e) => e.page === 2)).toBe(true);
    expect(empties.length).toBe(6);
  });
});
