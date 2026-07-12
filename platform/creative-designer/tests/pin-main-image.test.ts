// THE main image (user decision 2026-07-13): one image per page, defined by
// the EXPANDED view. There is no pin/unpin — normalize.ts reconcileMainImage
// derives page.img from the expanded view's hero at load and binds every
// view's first image to field:"img"; setMainImage changes it from the
// expanded view. See state.ts "The shared MAIN image".

import { describe, expect, it } from "vitest";
import { deleteItem, initialState } from "../src/state";
import { normalizePages } from "../src/normalize";
import type { LayoutItem, Page } from "../src/types";

const mainImg = (extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "image", field: "img", ...extra }) as LayoutItem;
const localImg = (src: string, extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "image", src, ...extra }) as LayoutItem;
const txt = (field: string): LayoutItem => ({ type: "text", field }) as LayoutItem;

const images = (arr?: LayoutItem[]): LayoutItem[] => (arr ?? []).filter((it) => it.type === "image");
const isMain = (it: LayoutItem): boolean => it.type === "image" && (it as { field?: string }).field === "img";
const src = (it: LayoutItem): string | undefined => (it as { src?: string }).src;

describe("reconcileMainImage (normalize load-time)", () => {
  it("adopts the expanded view's baked hero src as page.img and binds it", () => {
    const [p] = normalizePages([{
      headline: "h", img: "stale",
      banners: { "mobile-expanded": [localImg("expanded-truth"), txt("headline")] },
    }]);
    expect(p!.img).toBe("expanded-truth");
    const hero = images(p!.banners!["mobile-expanded"])[0]!;
    expect(isMain(hero)).toBe(true);
    expect(src(hero)).toBeUndefined(); // resolves page.img live
  });

  it("snaps a drifted size's hero onto the shared image (bake dropped)", () => {
    const [p] = normalizePages([{
      headline: "h", img: "a",
      banners: {
        "mobile-expanded": [mainImg(), txt("headline")],
        "300x250": [localImg("old-variant"), txt("headline")],
      },
    }]);
    expect(p!.img).toBe("a"); // bound expanded hero → page.img untouched
    const hero = images(p!.banners!["300x250"])[0]!;
    expect(isMain(hero)).toBe(true);
    expect(src(hero)).toBeUndefined();
  });

  it("binds only the FIRST image per view — later locals stay untouched", () => {
    const [p] = normalizePages([{
      headline: "h", img: "a",
      banners: { "300x250": [localImg("a"), localImg("decoration"), txt("headline")] },
    }]);
    const imgs = images(p!.banners!["300x250"]);
    expect(imgs.filter(isMain).length).toBe(1);
    expect(src(imgs[1]!)).toBe("decoration");
  });

  it("no page.img and no expanded hero → views stay as stored", () => {
    const [p] = normalizePages([{
      headline: "h",
      banners: { "300x250": [localImg("only-here"), txt("headline")] },
    }]);
    expect(p!.img).toBeUndefined();
    expect(isMain(images(p!.banners!["300x250"])[0]!)).toBe(false);
  });

  it("falls back to page.layout as the definition when there is no expanded bucket", () => {
    const [p] = normalizePages([{
      headline: "h", img: "stale",
      layout: [localImg("wide-truth"), txt("headline")],
    }]);
    expect(p!.img).toBe("wide-truth");
    expect(isMain(images(p!.layout)[0]!)).toBe(true);
  });
});

describe("deleteItem on the main", () => {
  it("clears page.img so the hero doesn't resurrect", () => {
    const pages: Page[] = [{ img: "a", layout: [mainImg(), txt("headline")] }] as Page[];
    const out = deleteItem(initialState(pages, "expanded"), 0);
    expect(out.pages[0]!.img).toBeUndefined();
    expect(images(out.pages[0]!.layout).length).toBe(0);
  });
});
