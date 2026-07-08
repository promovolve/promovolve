// Changing the brand kit re-applies its fonts to existing text: headline
// fields take the heading font (fonts[0]), everything else the body font
// (fonts[1]), across the layout and every size bucket.

import { describe, expect, it } from "vitest";
import { applyBrandKitFontsToText, initialState } from "../src/state";
import type { BrandKit } from "../src/brand-kit";
import type { LayoutItem, Page } from "../src/types";

const txt = (field: string, font: string): LayoutItem =>
  ({ type: "text", field, fontFamily: font }) as LayoutItem;
const fontOf = (items: LayoutItem[], field: string): unknown =>
  (items.find((i) => (i as { field?: string }).field === field) as unknown as Record<string, unknown>).fontFamily;
const kit: BrandKit = { name: "", colors: [], fonts: ["BrandHead", "BrandBody"] };

describe("applyBrandKitFontsToText", () => {
  it("sets headline → fonts[0] and other text → fonts[1] across layout + banners", () => {
    const pages: Page[] = [{
      layout: [txt("headline", "Old"), txt("body", "Old"), txt("sub", "Old")],
      banners: { "300x250": [txt("headline", "Old"), txt("body", "Old")] },
    }] as Page[];
    const out = applyBrandKitFontsToText(initialState(pages, "expanded"), kit);
    const L = out.pages[0]!.layout!;
    expect(fontOf(L, "headline")).toBe("BrandHead");
    expect(fontOf(L, "body")).toBe("BrandBody");
    expect(fontOf(L, "sub")).toBe("BrandBody");
    expect(fontOf(out.pages[0]!.banners!["300x250"]!, "headline")).toBe("BrandHead");
  });

  it("is an idempotent fixpoint once applied", () => {
    const pages: Page[] = [{ layout: [txt("headline", "BrandHead"), txt("body", "BrandBody")] }] as Page[];
    const s = initialState(pages, "expanded");
    expect(applyBrandKitFontsToText(s, kit)).toBe(s);
  });

  it("falls back to Georgia / sans-serif with an empty kit", () => {
    const pages: Page[] = [{ layout: [txt("headline", "X"), txt("body", "Y")] }] as Page[];
    const out = applyBrandKitFontsToText(initialState(pages, "expanded"), { name: "", colors: [], fonts: [] });
    expect(fontOf(out.pages[0]!.layout!, "headline")).toBe("Georgia");
    expect(fontOf(out.pages[0]!.layout!, "body")).toBe("sans-serif");
  });
});
