import { describe, expect, it } from "vitest";
import { composeFoldoutPages, posterLayoutFor, POSTER_BAND, POSTER_CREASE_INSET } from "../src/presets";
import type { LayoutItem, Page } from "../src/types";

const beat = (n: number, extra: Partial<Page> = {}): Page =>
  ({ headline: `H${n}`, sub: `S${n}`, body: `B${n}`, img: "https://x/i.jpg", ...extra }) as Page;
type T = { type: "text"; field?: string; text?: string; top: number; height: number; left: number; width: number };
const texts = (items: LayoutItem[]) => items.filter((i) => i.type === "text") as unknown as T[];
const bandOf = (t: T) => Math.floor((t.top + 0.001) / POSTER_BAND);

describe("posterLayoutFor", () => {
  const ad = (extra: Partial<Page> = {}) => beat(0, extra);

  it("one ad: headline + sub on the cover fold, body on the middle fold, all bound to fields", () => {
    const ts = texts(posterLayoutFor(ad(), null));
    const by = (f: string) => ts.find((t) => t.field === f)!;
    expect(bandOf(by("headline"))).toBe(0);
    expect(bandOf(by("sub"))).toBe(0);
    expect(bandOf(by("body"))).toBe(1);
    expect(ts.every((t) => t.field)).toBe(true); // no literal copy: one page, its own fields
  });

  it("every box sits inside its band, clear of the creases", () => {
    for (const t of texts(posterLayoutFor(ad({ ctaLabel: "Shop now" }), null))) {
      const k = bandOf(t);
      expect(t.top).toBeGreaterThanOrEqual(k * POSTER_BAND + POSTER_CREASE_INSET - 1e-9);
      expect(t.top + t.height).toBeLessThanOrEqual((k + 1) * POSTER_BAND - POSTER_CREASE_INSET + 1e-9);
    }
  });

  it("skips empty sub / body", () => {
    const ts = texts(posterLayoutFor(ad({ sub: "", body: "" }), null));
    expect(ts.map((t) => t.field)).toEqual(["headline"]);
  });

  it("is machine-placed: every item _generated, every text shrink-to-fit (the pack's walk-down may size it)", () => {
    for (const it of posterLayoutFor(ad({ ctaLabel: "Shop now" }), null)) {
      expect((it as { _generated?: boolean })._generated).toBe(true);
      if (it.type === "text") expect((it as { textFit?: string }).textFit).toBe("shrink");
    }
  });

  it("the copy boxes (pack caps) stay inside the 6% side gutters and never overlap", () => {
    const ts = texts(posterLayoutFor(ad({ ctaLabel: "Shop now" }), null)).sort((a, b) => a.top - b.top);
    for (const t of ts) {
      expect(t.left).toBeGreaterThanOrEqual(6);
      expect(t.left + t.width).toBeLessThanOrEqual(94);
    }
    for (let i = 1; i < ts.length; i++) expect(ts[i].top).toBeGreaterThanOrEqual(ts[i - 1].top + ts[i - 1].height);
  });

  it("image is full-bleed and first", () => {
    expect(posterLayoutFor(ad(), null)[0]).toMatchObject({ type: "image", left: 0, top: 0, width: 100, height: 100 });
  });

  it("adds no scrims: the poster is unshaded", () => {
    expect(posterLayoutFor(ad(), null).some((i) => i.type === "rect")).toBe(false);
  });

  it("CTA only when ctaLabel, on the bottom fold", () => {
    expect(texts(posterLayoutFor(ad(), null)).some((t) => t.text === "Shop now")).toBe(false);
    const cta = texts(posterLayoutFor(ad({ ctaLabel: "Shop now" }), null)).find((t) => t.text === "Shop now")!;
    expect(bandOf(cta)).toBe(2);
  });
});

describe("composeFoldoutPages", () => {
  const wide = [{ type: "image", field: "img", left: 0, top: 0, width: 100, height: 100 }] as LayoutItem[];
  const other = [{ type: "image", field: "img", left: 1, top: 1, width: 9, height: 9 }] as LayoutItem[];

  it("writes the portrait master, leaves layout and other buckets, returns one page", () => {
    const out = composeFoldoutPages([beat(0, { layout: wide, banners: { "300x250": other } })], false, null);
    expect(out).toHaveLength(1);
    expect(out[0].headline).toBe("H0");
    expect(out[0].banners!["mobile-expanded"].length).toBeGreaterThan(1);
    expect(out[0].layout).toBe(wide);
    expect(out[0].banners!["300x250"]).toBe(other);
  });

  it("does not recompose a resumed fold-out with a portrait master", () => {
    const p = { ...beat(0), banners: { "mobile-expanded": other } } as Page;
    const out = composeFoldoutPages([p], true, null);
    expect(out).toHaveLength(1);
    expect(out[0].banners!["mobile-expanded"]).toBe(other);
  });

  it("composes a page that has only a wide layout", () => {
    const p = { ...beat(0), layout: wide } as Page;
    const out = composeFoldoutPages([p], false, null);
    expect(out[0].banners!["mobile-expanded"][0]).toMatchObject({ type: "image", width: 100 });
    expect(out[0].layout).toBe(wide);
  });
});
