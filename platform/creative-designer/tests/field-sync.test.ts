import { describe, expect, it } from "vitest";
import { initialState, relinkItem, setItemContent, setMainImage } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

// A field-bound headline in the portrait reader (the master edit
// surface), the tabless wide layout, and the 300x250.
const headline = (): LayoutItem =>
  ({ type: "text", field: "headline", left: 0, top: 0, width: 50, height: 10 } as LayoutItem);
const mk = (): Page[] => [
  {
    headline: "OLD",
    layout: [headline()],
    banners: { "mobile-expanded": [headline()], "300x250": [headline()] },
  } as Page,
];
const txt = (it: LayoutItem): string | undefined => (it as { text?: string }).text;
const fld = (it: LayoutItem): string | undefined => (it as { field?: string }).field;

describe("field content sync (master ↔ sizes)", () => {
  it("master edit writes the shared page field and leaves items field-bound", () => {
    // The portrait reader is the master edit surface (the wide 16:9
    // layout has no tab anymore).
    const r = setItemContent(initialState(mk(), "mobile"), 0, "NEW", "text");
    expect(r.pages[0].headline).toBe("NEW");
    // reader item stays field-bound (no local bake) → reads page.headline
    expect(txt(r.pages[0].banners!["mobile-expanded"][0])).toBeUndefined();
    // the tabless wide layout + 300x250 are untouched + still
    // field-bound → render NEW too
    expect(txt(r.pages[0].layout![0])).toBeUndefined();
    expect(txt(r.pages[0].banners!["300x250"][0])).toBeUndefined();
    expect(fld(r.pages[0].banners!["300x250"][0])).toBe("headline");
  });

  it("editing in the MOBILE expanded reader also writes the shared field (syncs)", () => {
    // mobile is an expanded reader (isMultiPage), not a collapsed size.
    const t = (): LayoutItem => ({ type: "text", field: "headline", left: 0, top: 0, width: 50, height: 10 } as LayoutItem);
    const pages: Page[] = [{ headline: "OLD", layout: [t()], banners: { "mobile-expanded": [t()] } } as Page];
    const r = setItemContent(initialState(pages, "mobile"), 0, "FROM MOBILE", "text");
    expect(r.pages[0].headline).toBe("FROM MOBILE");               // shared field written
    expect(txt(r.pages[0].banners!["mobile-expanded"][0])).toBeUndefined(); // mobile item stays field-bound
    expect(txt(r.pages[0].layout![0])).toBeUndefined();            // PC reader resolves the new value too
  });

  it("size edit bakes a local override and leaves the master field intact", () => {
    const r = setItemContent(initialState(mk(), "300x250"), 0, "SHORT", "text");
    expect(txt(r.pages[0].banners!["300x250"][0])).toBe("SHORT"); // local override
    expect(r.pages[0].headline).toBe("OLD"); // master unchanged → other sizes unaffected
  });

  it("master IMAGE edit writes the shared page.img (syncs sizes)", () => {
    const img = (): LayoutItem => ({ type: "image", field: "img", left: 0, top: 0, width: 40, height: 40 } as LayoutItem);
    const pages = (): Page[] => [
      { img: "old.png", layout: [img()], banners: { "mobile-expanded": [img()], "300x250": [img()] } } as Page,
    ];
    const r = setItemContent(initialState(pages(), "mobile"), 0, "new.png", "src");
    expect((r.pages[0] as { img?: string }).img).toBe("new.png");
    expect((r.pages[0].banners!["mobile-expanded"][0] as { src?: string }).src).toBeUndefined(); // field-bound
    expect(fld(r.pages[0].banners!["300x250"][0])).toBe("img"); // size still resolves page.img=new
    // a size image swap is a local override
    const r2 = setItemContent(initialState(pages(), "300x250"), 0, "size.png", "src");
    expect((r2.pages[0].banners!["300x250"][0] as { src?: string }).src).toBe("size.png");
    expect((r2.pages[0] as { img?: string }).img).toBe("old.png");
  });

  it("setMainImage adds a field:img slot to generated sizes that lack one", () => {
    const t = (): LayoutItem => ({ type: "text", field: "headline", left: 0, top: 0, width: 50, height: 10 } as LayoutItem);
    // Generated WITHOUT an image: text-only master + size, no image slots.
    const pages: Page[] = [{ headline: "H", layout: [t()], banners: { "300x250": [t()] } } as Page];
    const r = setMainImage(initialState(pages, "expanded"), "x.png");
    expect((r.pages[0] as { img?: string }).img).toBe("x.png");
    const hasImg = (items?: LayoutItem[]) => (items ?? []).some((it) => it.type === "image" && fld(it) === "img");
    expect(hasImg(r.pages[0].layout)).toBe(true);           // expanded got a slot
    expect(hasImg(r.pages[0].banners!["300x250"])).toBe(true); // and the 300x250
  });

  it("setMainImage converts a baked image in place (no duplicate layered on top)", () => {
    const baked = (s: string): LayoutItem =>
      ({ type: "image", src: s, left: 0, top: 0, width: 40, height: 40 } as LayoutItem);
    const fImg = (): LayoutItem =>
      ({ type: "image", field: "img", left: 0, top: 0, width: 40, height: 40 } as LayoutItem);
    // PC has a baked image; mobile already has a baked + a stray field:img (the old bug).
    const pages: Page[] = [
      {
        img: "old.png",
        layout: [baked("old.png")],
        banners: { "mobile-expanded": [baked("old.png"), fImg()] },
      } as Page,
    ];
    const r = setMainImage(initialState(pages, "expanded"), "new.png");
    const imgs = (items?: LayoutItem[]) => (items ?? []).filter((it) => it.type === "image");
    // exactly one image per view, field-bound, no baked src
    expect(imgs(r.pages[0].layout)).toHaveLength(1);
    expect(fld(r.pages[0].layout![0])).toBe("img");
    expect((r.pages[0].layout![0] as { src?: string }).src).toBeUndefined();
    expect(imgs(r.pages[0].banners!["mobile-expanded"])).toHaveLength(1); // duplicate removed
    expect(fld(r.pages[0].banners!["mobile-expanded"][0])).toBe("img");
    expect((r.pages[0] as { img?: string }).img).toBe("new.png");
  });

  it("setMainImage moves the image behind the text (index 0, back of stack)", () => {
    const t = (): LayoutItem =>
      ({ type: "text", field: "headline", left: 0, top: 0, width: 50, height: 10 } as LayoutItem);
    // Image appended in FRONT of the text (the old buggy ordering).
    const frontImg = (): LayoutItem =>
      ({ type: "image", field: "img", left: 0, top: 0, width: 40, height: 40 } as LayoutItem);
    const pages: Page[] = [
      { img: "old.png", layout: [t(), frontImg()], banners: { "300x250": [t(), frontImg()] } } as Page,
    ];
    const r = setMainImage(initialState(pages, "expanded"), "new.png");
    expect(r.pages[0].layout![0].type).toBe("image"); // image now at back
    expect(r.pages[0].layout![1].type).toBe("text");
    expect(r.pages[0].banners!["300x250"][0].type).toBe("image");
  });

  it("relink drops the size override so it follows the master again", () => {
    const overridden = setItemContent(initialState(mk(), "300x250"), 0, "SHORT", "text");
    const r = relinkItem(overridden, 0, "text");
    expect(txt(r.pages[0].banners!["300x250"][0])).toBeUndefined();
    expect(fld(r.pages[0].banners!["300x250"][0])).toBe("headline");
  });
});
