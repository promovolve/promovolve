// A formula is an image item that is NOT a photo. The one-main-image rules
// (load-time hero binding, images-to-back, Replace image) must leave it be.

import { describe, expect, it } from "vitest";
import { normalizePages } from "../src/normalize";
import { initialState, setMainImage } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const formula = (): LayoutItem =>
  ({ type: "image", src: "f.svg", latex: "E=mc^2", latexColor: "#fff", fillMode: "fit" }) as LayoutItem;
const photo = (src: string): LayoutItem => ({ type: "image", src }) as LayoutItem;
const rect = (): LayoutItem => ({ type: "rect", fill: "#000" }) as LayoutItem;
const txt = (field: string): LayoutItem => ({ type: "text", field }) as LayoutItem;

describe("formulas are not photos", () => {
  it("never becomes the page's main image on load", () => {
    const [p] = normalizePages([{
      headline: "h",
      banners: { "mobile-expanded": [txt("headline"), formula()] },
    }]);
    expect(p!.img).toBeUndefined();
    const f = p!.banners!["mobile-expanded"]!.find((it) => (it as { latex?: string }).latex)!;
    expect((f as { src?: string }).src).toBe("f.svg");
    expect((f as { field?: string }).field).toBeUndefined();
  });

  it("keeps its z-order on load (stays above a card it was placed on)", () => {
    const [p] = normalizePages([{
      headline: "h",
      banners: { "mobile-expanded": [photo("bg.jpg"), rect(), formula(), txt("headline")] },
    }]);
    const types = p!.banners!["mobile-expanded"]!.map((it) => ((it as { latex?: string }).latex ? "formula" : it.type));
    expect(types).toEqual(["image", "rect", "formula", "text"]);
  });

  it("survives Replace image", () => {
    const base = initialState([{ headline: "h", img: "old.jpg", layout: [photo("old.jpg"), formula(), txt("headline")] } as Page]);
    const next = setMainImage(base, "new.jpg");
    const layout = next.pages[next.pageIdx]!.layout!;
    expect(layout.filter((it) => (it as { latex?: string }).latex)).toHaveLength(1);
  });
});
