import { describe, expect, it } from "vitest";
import { composeMapPages, posterLayoutFor, POSTER_BAND, POSTER_CREASE_INSET } from "../src/presets";
import type { LayoutItem, Page } from "../src/types";

const beat = (n: number, extra: Partial<Page> = {}): Page =>
  ({ headline: `H${n}`, sub: `S${n}`, body: `B${n}`, img: "https://x/i.jpg", ...extra }) as Page;
const beats = (extra: Partial<Page> = {}): Page[] => [beat(0), beat(1), beat(2, extra)];
type T = { type: "text"; field?: string; text?: string; top: number; height: number; left: number; width: number };
const texts = (items: LayoutItem[]) => items.filter((i) => i.type === "text") as unknown as T[];
const bandOf = (t: T) => Math.floor((t.top + 0.001) / POSTER_BAND);

describe("posterLayoutFor", () => {
  it("each beat sits inside its band, clear of the creases", () => {
    const ts = texts(posterLayoutFor(beats(), null));
    expect(ts.length).toBeGreaterThanOrEqual(6);
    for (const t of ts) {
      const k = bandOf(t);
      expect(t.top).toBeGreaterThanOrEqual(k * POSTER_BAND + POSTER_CREASE_INSET - 1e-9);
      expect(t.top + t.height).toBeLessThanOrEqual((k + 1) * POSTER_BAND - POSTER_CREASE_INSET + 1e-9);
    }
  });

  it("beat 1 binds fields, beats 2-3 are literal", () => {
    const hs = texts(posterLayoutFor(beats(), null)).filter((t) => t.field === "headline" || t.text?.startsWith("H"));
    expect(hs[0].field).toBe("headline");
    expect(hs[1].text).toBe("H1");
    expect(hs[2].text).toBe("H2");
  });

  it("falls back to body when sub is empty", () => {
    const b = beats();
    b[0] = beat(0, { sub: "" });
    b[1] = beat(1, { sub: "" });
    const ts = texts(posterLayoutFor(b, null));
    expect(ts.some((t) => t.field === "body")).toBe(true);
    expect(ts.some((t) => t.text === "B1")).toBe(true);
  });

  it("image is full-bleed and first", () => {
    expect(posterLayoutFor(beats(), null)[0]).toMatchObject({ type: "image", left: 0, top: 0, width: 100, height: 100 });
  });

  it("every text item has a scrim behind it", () => {
    const items = posterLayoutFor(beats(), null);
    items.forEach((it, idx) => {
      if (it.type !== "text") return;
      const t = it as unknown as T;
      const ok = items.slice(0, idx).some((r) => {
        const x = r as unknown as { type: string; scrim?: unknown; top: number; height: number; left: number; width: number };
        return x.type === "rect" && x.scrim && x.top <= t.top && x.top + x.height >= t.top + t.height
          && x.left <= t.left && x.left + x.width >= t.left + t.width;
      });
      expect(ok).toBe(true);
    });
  });

  it("CTA only when ctaLabel", () => {
    expect(texts(posterLayoutFor(beats(), null)).some((t) => t.text === "Shop now")).toBe(false);
    const cta = texts(posterLayoutFor(beats({ ctaLabel: "Shop now" }), null)).find((t) => t.text === "Shop now")!;
    expect(cta).toBeDefined();
    expect(bandOf(cta)).toBe(2);
  });
});

describe("composeMapPages", () => {
  const wide = [{ type: "image", field: "img", left: 0, top: 0, width: 100, height: 100 }] as LayoutItem[];
  const other = [{ type: "image", field: "img", left: 1, top: 1, width: 9, height: 9 }] as LayoutItem[];

  it("writes the portrait master, leaves layout and other buckets, returns one page", () => {
    const b = beats();
    b[0] = beat(0, { layout: wide, banners: { "300x250": other } });
    const out = composeMapPages(b, false, null);
    expect(out).toHaveLength(1);
    expect(out[0].headline).toBe("H0");
    expect(out[0].banners!["mobile-expanded"].length).toBeGreaterThan(1);
    expect(out[0].layout).toBe(wide);
    expect(out[0].banners!["300x250"]).toBe(other);
  });

  it("does not recompose a resumed map with a portrait master", () => {
    const p = { ...beat(0), banners: { "mobile-expanded": other } } as Page;
    const out = composeMapPages([p], true, null);
    expect(out).toHaveLength(1);
    expect(out[0].banners!["mobile-expanded"]).toBe(other);
  });

  it("composes a page that has only a wide layout", () => {
    const p = { ...beat(0), layout: wide } as Page;
    const out = composeMapPages([p], false, null);
    expect(out[0].banners!["mobile-expanded"][0]).toMatchObject({ type: "image", width: 100 });
    expect(out[0].layout).toBe(wide);
  });
});
