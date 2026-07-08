// Model-A typography sync: editing a typography property of a field-bound
// text item propagates it to the SAME field across EVERY creative — each
// page's reader layout AND every banner/IAB size bucket. Image items never
// sync (they aren't text), even author-added extras.
//
// fontSize is the exception: it syncs PROPORTIONALLY (a scale ratio), not as
// a verbatim copy, because the `cqmax` size unit measures a different axis on
// each format's aspect — so a shared number isn't a shared proportion.

import { describe, expect, it } from "vitest";
import { harmonizeTypography, inheritBannerColors, initialState, masterColor, propagateTypography } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const txt = (field: string, extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "text", field, ...extra }) as LayoutItem;
const img = (field: string, extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "image", field, ...extra }) as LayoutItem;

const pages = (): Page[] => [
  {
    layout: [txt("headline", { color: "#000" }), txt("body", { color: "#111" }), img("img")],
    banners: {
      "300x250": [txt("headline", { color: "#222" }), img("img")],
      "mobile-expanded": [txt("headline", { color: "#333" })],
    },
  },
  { layout: [txt("headline", { color: "#444" })] },
] as Page[];

const headlinesOf = (state: { pages: Page[] }): Record<string, unknown>[] => {
  const out: Record<string, unknown>[] = [];
  for (const p of state.pages) {
    for (const items of [p.layout ?? [], ...Object.values(p.banners ?? {})]) {
      for (const it of items) {
        if (it.type === "text" && (it as { field?: string }).field === "headline") {
          out.push(it as unknown as Record<string, unknown>);
        }
      }
    }
  }
  return out;
};

describe("propagateTypography", () => {
  it("fans a typography change out to the same field across pages AND every size bucket", () => {
    const out = propagateTypography(initialState(pages(), "expanded"), "headline", {
      color: "#f00",
      fontWeight: "700",
    });
    const heads = headlinesOf(out);
    expect(heads.length).toBe(4); // 2 layout + 300x250 + mobile-expanded
    for (const h of heads) {
      expect(h.color).toBe("#f00");
      expect(h.fontWeight).toBe("700");
    }
  });

  it("leaves other fields (body) untouched", () => {
    const out = propagateTypography(initialState(pages(), "expanded"), "headline", { color: "#f00" });
    const body = out.pages[0]!.layout!.find((it) => (it as { field?: string }).field === "body") as unknown as Record<string, unknown>;
    expect(body.color).toBe("#111");
    expect(body.fontWeight).toBeUndefined();
  });

  it("never syncs image items — even one whose field name matches", () => {
    const p: Page[] = [
      { layout: [txt("headline", { color: "#000" }), img("headline", { left: 5 })] },
    ] as Page[];
    const out = propagateTypography(initialState(p, "expanded"), "headline", { color: "#f00" });
    const image = out.pages[0]!.layout!.find((it) => it.type === "image") as unknown as Record<string, unknown>;
    expect(image.color).toBeUndefined(); // image guard held
    expect(image.left).toBe(5);
  });

  it("returns the same state object when nothing changes (fixpoint)", () => {
    const once = propagateTypography(initialState(pages(), "expanded"), "headline", { color: "#f00" });
    expect(propagateTypography(once, "headline", { color: "#f00" })).toBe(once);
  });

  it("no-ops for an empty typo set or unmatched field", () => {
    const s = initialState(pages(), "expanded");
    expect(propagateTypography(s, "headline", {})).toBe(s);
    expect(propagateTypography(s, "nonesuch", { color: "#f00" })).toBe(s);
  });

  it("scales fontSize PROPORTIONALLY across formats (not a verbatim copy)", () => {
    const ps: Page[] = [{
      layout: [txt("headline", { fontSize: 9 })],            // PC reader
      banners: {
        "mobile-expanded": [txt("headline", { fontSize: 6 })], // tall reader, own base
        "300x250": [txt("headline", { fontSize: 5 })],
      },
    }] as Page[];
    const s = initialState(ps, "expanded");
    const edited = s.pages[0]!.layout!.find((it) => (it as { field?: string }).field === "headline")!;
    // PC headline 9 → 12 is a 1.333× bump; every other format scales from its
    // OWN base by the same ratio. The edited source item is left untouched.
    const out = propagateTypography(s, "headline", {}, 12 / 9, edited);
    const mob = out.pages[0]!.banners!["mobile-expanded"]!.find((it) => (it as { field?: string }).field === "headline") as unknown as Record<string, unknown>;
    const rect = out.pages[0]!.banners!["300x250"]!.find((it) => (it as { field?: string }).field === "headline") as unknown as Record<string, unknown>;
    expect(mob.fontSize).toBe(8);    // 6 × 1.333 = 8
    expect(rect.fontSize).toBe(6.7); // 5 × 1.333 = 6.67 → 6.7
    // source untouched (caller already set it)
    expect((edited as unknown as Record<string, unknown>).fontSize).toBe(9);
  });

  it("a fontScale of 1 (or no scale) is a no-op", () => {
    const s = initialState(pages(), "expanded");
    expect(propagateTypography(s, "headline", {}, 1)).toBe(s);
    expect(propagateTypography(s, "headline", {})).toBe(s);
  });

  it("does NOT sync writingMode for the headline (per-dimension), but does for body", () => {
    const ps: Page[] = [{
      layout: [txt("headline"), txt("body")],
      banners: { "300x250": [txt("headline", { writingMode: "horizontal-tb" }), txt("body", { writingMode: "horizontal-tb" })] },
    }] as Page[];
    const s = initialState(ps, "expanded");
    // headline: color syncs, writingMode does NOT (stays per-dimension)
    const h = propagateTypography(s, "headline", { writingMode: "vertical-rl", color: "#f00" });
    const bh = h.pages[0]!.banners!["300x250"]!.find((it) => (it as { field?: string }).field === "headline") as unknown as Record<string, unknown>;
    expect(bh.color).toBe("#f00");
    expect(bh.writingMode).toBe("horizontal-tb"); // unchanged — not synced for headline
    // body: writingMode DOES sync
    const b = propagateTypography(s, "body", { writingMode: "vertical-rl" });
    const bb = b.pages[0]!.banners!["300x250"]!.find((it) => (it as { field?: string }).field === "body") as unknown as Record<string, unknown>;
    expect(bb.writingMode).toBe("vertical-rl");
  });
});

describe("colour sync (unified — the master-group partition is retired)", () => {
  const colorPages = (): Page[] => [{
    layout: [txt("headline", { color: "#111" })],                 // retired 16:9 artifact
    banners: {
      "300x250": [txt("headline", { color: "#111" })],            // collapsed bucket
      "728x90":  [txt("headline", { color: "#111" })],            // collapsed bucket
      "mobile-expanded": [txt("headline", { color: "#111" })],    // portrait reader
    },
  }] as Page[];
  const colorOf = (s: { pages: Page[] }, where: "layout" | "300x250" | "728x90" | "mobile-expanded") => {
    const arr = where === "layout" ? s.pages[0]!.layout! : s.pages[0]!.banners![where]!;
    return (arr.find((it) => (it as { field?: string }).field === "headline") as unknown as Record<string, unknown>).color;
  };

  it("a colour edit syncs to EVERY surface — buckets, the hidden 16:9 artifact, AND the portrait reader", () => {
    // With the 16:9 tab retired, the portrait reader is the only multi-page
    // edit surface; colour partitioning read as "my edit didn't take".
    const out = propagateTypography(initialState(colorPages(), "mobile"), "headline", { color: "#00f" });
    expect(colorOf(out, "mobile-expanded")).toBe("#00f");
    expect(colorOf(out, "layout")).toBe("#00f");
    expect(colorOf(out, "300x250")).toBe("#00f");
    expect(colorOf(out, "728x90")).toBe("#00f");
  });

  it("masterColor reads the expanded PC layout's colour for the role", () => {
    expect(masterColor(initialState(colorPages(), "mobile"), "headline")).toBe("#111");
  });

  it("inheritBannerColors forces persisted bucket colours onto the 1st-page expanded colour", () => {
    // Persisted creative: expanded headline maroon, but buckets saved black,
    // and the mobile reader deliberately different.
    const pages: Page[] = [{
      layout: [txt("headline", { color: "#691c32" })],
      banners: {
        "300x250": [txt("headline", { color: "#0a0a0b" })],
        "728x90":  [txt("headline", { color: "#0a0a0b" })],
        "mobile-expanded": [txt("headline", { color: "#003300" })],
      },
    }] as Page[];
    const out = inheritBannerColors(pages);
    const hc = (k: string) => (out[0]!.banners![k]!.find((it) => (it as { field?: string }).field === "headline") as unknown as Record<string, unknown>).color;
    expect(hc("300x250")).toBe("#691c32"); // forced onto master
    expect(hc("728x90")).toBe("#691c32");
    expect(hc("mobile-expanded")).toBe("#003300"); // mobile reader untouched
  });
});

describe("harmonizeTypography", () => {
  it("unifies style (color/weight) from the current view but leaves each format's fontSize alone", () => {
    const pages: Page[] = [{
      layout: [txt("headline", { color: "#f00", fontWeight: "700", fontSize: 10 })], // master (current = expanded)
      banners: { "300x250": [txt("headline", { color: "#000", fontWeight: "400", fontSize: 4 })] },
    }] as Page[];
    const out = harmonizeTypography(initialState(pages, "expanded"));
    const bannerHead = out.pages[0]!.banners!["300x250"]!.find(
      (it) => (it as { field?: string }).field === "headline",
    ) as unknown as Record<string, unknown>;
    expect(bannerHead.color).toBe("#f00");     // style synced
    expect(bannerHead.fontWeight).toBe("700"); // style synced
    expect(bannerHead.fontSize).toBe(4);       // size is per-format — NOT unified
  });
});
