// Pin / unpin the shared MAIN image. "Pinned" is the single value page.img
// (+ the one item with field:"img") — additional images are local and never
// sync. See state.ts pinImageAsMain / unpinMainImage / ensureMainImage.

import { describe, expect, it } from "vitest";
import { deleteItem, initialState, pinImageAsMain, unpinMainImage } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const mainImg = (extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "image", field: "img", ...extra }) as LayoutItem;
const localImg = (src: string, extra: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "image", src, ...extra }) as LayoutItem;
const txt = (field: string): LayoutItem => ({ type: "text", field }) as LayoutItem;

const images = (arr?: LayoutItem[]): LayoutItem[] => (arr ?? []).filter((it) => it.type === "image");
const isMain = (it: LayoutItem): boolean => it.type === "image" && (it as { field?: string }).field === "img";

describe("pinImageAsMain", () => {
  it("turns a local image into the shared main (field:img + page.img)", () => {
    const pages: Page[] = [{ layout: [localImg("a"), txt("headline")] }] as Page[];
    const out = pinImageAsMain(initialState(pages, "expanded"), 0);
    expect(out.pages[0]!.img).toBe("a");
    const im = images(out.pages[0]!.layout)[0]!;
    expect(isMain(im)).toBe(true);
    expect((im as { src?: string }).src).toBeUndefined(); // bake dropped → resolves page.img
  });

  it("preserves other local images in the same view", () => {
    const pages: Page[] = [{ layout: [localImg("a"), localImg("b"), txt("headline")] }] as Page[];
    const out = pinImageAsMain(initialState(pages, "expanded"), 0);
    const imgs = images(out.pages[0]!.layout);
    expect(imgs.filter(isMain).length).toBe(1);
    expect(imgs.find((it) => (it as { src?: string }).src === "b")).toBeTruthy(); // the other local kept
  });

  it("no-ops on a non-image, an already-main item, or an image without a src", () => {
    const s1 = initialState([{ layout: [txt("headline")] }] as Page[], "expanded");
    expect(pinImageAsMain(s1, 0)).toBe(s1);
    const s2 = initialState([{ layout: [mainImg()] }] as Page[], "expanded");
    expect(pinImageAsMain(s2, 0)).toBe(s2);
    const s3 = initialState([{ layout: [localImg("")] }] as Page[], "expanded");
    expect(pinImageAsMain(s3, 0)).toBe(s3); // empty src isn't pinnable
  });
});

describe("unpinMainImage", () => {
  it("demotes the main to a local image (keeps the source) and clears page.img", () => {
    const pages: Page[] = [{ img: "a", layout: [mainImg(), txt("headline")] }] as Page[];
    const out = unpinMainImage(initialState(pages, "expanded"), 0);
    expect(out.pages[0]!.img).toBeUndefined();
    const im = images(out.pages[0]!.layout)[0]!;
    expect(isMain(im)).toBe(false);              // no longer the shared main
    expect((im as { src?: string }).src).toBe("a"); // baked the source it was showing
  });

  it("strips the orphaned hero from every OTHER view (they lose the synced image)", () => {
    const pages: Page[] = [{
      img: "a",
      layout: [mainImg(), txt("headline")],
      banners: { "300x250": [mainImg(), localImg("z")] },
    }] as Page[];
    const out = unpinMainImage(initialState(pages, "expanded"), 0);
    const bucket = out.pages[0]!.banners!["300x250"]!;
    expect(bucket.filter(isMain).length).toBe(0);            // hero gone from the size bucket
    expect(bucket.find((it) => (it as { src?: string }).src === "z")).toBeTruthy(); // its local survives
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
