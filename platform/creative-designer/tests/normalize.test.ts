import { describe, expect, it } from "vitest";
import { defaultLayoutForPage, looksLikePercentLayout, normalizePages } from "../src/normalize";
import type { LayoutItem } from "../src/types";

describe("looksLikePercentLayout", () => {
  it("accepts layouts with left/top in 0..100", () => {
    const ok: LayoutItem[] = [
      { type: "text", text: "a", left: 10, top: 20 },
      { type: "text", text: "b", left: 50, top: 50 },
    ];
    expect(looksLikePercentLayout(ok)).toBe(true);
  });
  it("accepts missing left/top", () => {
    expect(looksLikePercentLayout([{ type: "text", text: "a" }])).toBe(true);
  });
  it("rejects pixel-scale values", () => {
    const px: LayoutItem[] = [{ type: "text", text: "a", left: 300, top: 200 }];
    expect(looksLikePercentLayout(px)).toBe(false);
  });
});

describe("defaultLayoutForPage", () => {
  it("generates items from flat page fields", () => {
    const items = defaultLayoutForPage({
      tag: "FEATURE",
      headline: "Big News",
      sub: "subtitle here",
      body: "body text",
      img: "https://example.com/x.jpg",
    });
    // Copy only: image, headline, body = 3. No tag eyebrow / sub /
    // "Read More" — the whole sheet is the link, tag is internal.
    expect(items).toHaveLength(3);
    expect(items.some((it) => it.type === "image" && (it as { field?: string }).field === "img")).toBe(true);
  });
  it("skips fields that aren't present", () => {
    const items = defaultLayoutForPage({ headline: "only this" });
    expect(items).toHaveLength(1);
  });
  it("keeps the legacy white headline on the dark default background", () => {
    // No page.bg → banner renders its dark default gradient, so the
    // contrast pick must stay white to match pre-fix creatives.
    const items = defaultLayoutForPage({ headline: "H", sub: "s", body: "b" });
    const headline = items.find((it) => it.type === "text" && (it as { field?: string }).field === "headline");
    expect((headline as { color?: string }).color).toBe("#ffffff");
  });
  it("picks dark headline text on a light page background", () => {
    const items = defaultLayoutForPage({ headline: "H", sub: "s", body: "b", bg: "#ffffff" });
    const headline = items.find((it) => it.type === "text" && (it as { field?: string }).field === "headline");
    const color = (headline as { color?: string }).color ?? "";
    // Dark text — not the old hardcoded white that vanished on light bg.
    expect(color).not.toBe("#ffffff");
    expect(color.toLowerCase()).toBe("#0a0a0b");
  });
  it("falls back to system fonts when no kit is given", () => {
    const items = defaultLayoutForPage({ headline: "H", body: "b" });
    const headline = items.find((it) => it.type === "text" && (it as { field?: string }).field === "headline");
    const body = items.find((it) => it.type === "text" && (it as { field?: string }).field === "body");
    expect((headline as { fontFamily?: string }).fontFamily).toBe("Georgia");
    expect((body as { fontFamily?: string }).fontFamily).toBe("sans-serif");
  });
  it("uses the LP brand-kit colours (background-derived text)", () => {
    // Dark LP bg + light LP text (legible pair) → text items take the LP
    // text colour. (tag/CTA are no longer emitted — headline + body only.)
    const kit = {
      name: "From landing page",
      colors: [
        { name: "Background", value: "#101820" },
        { name: "Text", value: "#f0f0f0" },
        { name: "Accent", value: "#e0a030" },
      ],
      fonts: [],
    };
    const items = defaultLayoutForPage({ tag: "T", headline: "H", sub: "s", body: "b" }, kit);
    const ff = (t: string) =>
      (items.find((it) => it.type === "text" && (it as { field?: string }).field === t) as { color?: string }).color;
    expect(ff("headline")).toBe("#f0f0f0"); // LP text colour
    expect(ff("body")).toBe("#f0f0f0");
  });

  it("falls back to a legible contrast pick when the LP text colour is illegible", () => {
    // Near-white text on a white bg fails WCAG AA → must NOT be used; the
    // dark contrast pick is substituted so the headline stays readable.
    const kit = {
      name: "LP",
      colors: [
        { name: "Background", value: "#ffffff" },
        { name: "Text", value: "#fafafa" },
      ],
      fonts: [],
    };
    const items = defaultLayoutForPage({ headline: "H" }, kit);
    const head = items.find((it) => it.type === "text" && (it as { field?: string }).field === "headline") as { color?: string };
    expect(head.color).not.toBe("#fafafa"); // illegible LP text rejected
    expect(head.color).toBe("#0a0a0b");      // legible pick on white bg
  });

  it("uses the brand-kit LP font for the collapsed layout (no Georgia cliff)", () => {
    // Regression: the served collapsed layout must carry the determined LP
    // font (heading=fonts[0], body=fonts[1]) so it doesn't show the hardcoded
    // Georgia. Kit fonts are pre-snapped "<Family>, <bucket>" stacks.
    const kit = {
      name: "From landing page",
      colors: [],
      fonts: ["Montserrat Variable ExtraBold, sans-serif", "Montserrat Variable, sans-serif"],
    };
    const items = defaultLayoutForPage({ tag: "T", headline: "H", sub: "s", body: "b" }, kit);
    const ff = (t: string) =>
      (items.find((it) => it.type === "text" && (it as { field?: string }).field === t) as { fontFamily?: string }).fontFamily;
    expect(ff("headline")).toBe("Montserrat Variable ExtraBold, sans-serif"); // heading → fonts[0]
    expect(ff("body")).toBe("Montserrat Variable, sans-serif");          // body → fonts[1]
    // No item should be left on the hardcoded serif default.
    expect(items.every((it) => (it as { fontFamily?: string }).fontFamily !== "Georgia")).toBe(true);
  });
});

describe("normalizePages", () => {
  it("parses from a JSON string", () => {
    const raw = JSON.stringify([{ headline: "Hello" }]);
    const result = normalizePages(raw);
    expect(result).toHaveLength(1);
    expect((result[0]?.layout ?? []).length).toBeGreaterThan(0);
  });
  it("synthesizes a layout when none present", () => {
    const result = normalizePages([{ headline: "Hi" }]);
    expect((result[0]?.layout ?? []).length).toBeGreaterThan(0);
  });
  it("preserves existing percent-based layouts", () => {
    const layout: LayoutItem[] = [{ type: "text", text: "authored", left: 10, top: 10 }];
    const result = normalizePages([{ headline: "H", layout }]);
    expect(result[0]?.layout).toEqual(layout);
  });
  it("regenerates when the existing layout is pixel-scale", () => {
    const layout: LayoutItem[] = [{ type: "text", text: "px", left: 500, top: 400 }];
    const result = normalizePages([{ headline: "H", layout }]);
    expect(result[0]?.layout?.[0]).not.toEqual(layout[0]);
  });
  it("ensures banners is an object", () => {
    const result = normalizePages([{ headline: "H" }]);
    expect(typeof result[0]?.banners).toBe("object");
  });
  it("returns [] for garbage input", () => {
    expect(normalizePages(null)).toEqual([]);
    expect(normalizePages("{not json")).toEqual([]);
    expect(normalizePages(42)).toEqual([]);
  });
});

describe("images sit behind text (load-time z-order)", () => {
  it("moves images to the back of layout and every banner bucket on normalize", () => {
    const raw = [
      {
        headline: "H",
        // image stored AT THE END (front) — the legacy bad ordering
        layout: [
          { type: "text", field: "headline", left: 6, top: 20 },
          { type: "image", field: "img", left: 60, top: 15 },
        ],
        banners: {
          "300x250": [
            { type: "text", field: "headline", left: 6, top: 20 },
            { type: "image", field: "img", left: 4, top: 8 },
          ],
        },
      },
    ];
    const [page] = normalizePages(raw, { fillDefaultLayout: false });
    expect(page.layout![0].type).toBe("image"); // image now at the back
    expect(page.layout![1].type).toBe("text");
    expect(page.banners!["300x250"][0].type).toBe("image");
    expect(page.banners!["300x250"][1].type).toBe("text");
  });

  it("defaultLayoutForPage puts the image first (behind the text)", () => {
    const items = defaultLayoutForPage({ headline: "Big", img: "https://x/y.jpg" });
    expect(items[0].type).toBe("image");
    expect(items.slice(1).every((it) => it.type === "text")).toBe(true);
  });
});
