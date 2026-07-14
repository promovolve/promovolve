import { describe, expect, it } from "vitest";
import { deriveCdnOrigin, collectExpandedFonts, collectSubsetText, resolveExpandedFonts, subsetKey } from "../src/font-catalog";
import type { Page } from "../src/types";

// Self-hosted fonts reach the EXPANDED view only when (a) the layout
// references an allow-listed family and (b) we can derive the CDN origin
// from the creative's own image assets. These tests guard both, plus the
// "system fallback" path for non-catalog families.

function page(layout: unknown[]): Page {
  return { layout } as unknown as Page;
}

describe("deriveCdnOrigin", () => {
  it("takes the origin of the first absolute expanded image URL", () => {
    const pages = [page([
      { type: "text", fontFamily: "Montserrat, sans-serif" },
      { type: "image", src: "https://cdn.example.com/assets/abc.webp" },
    ])];
    expect(deriveCdnOrigin(pages)).toBe("https://cdn.example.com");
  });

  it("returns null when there's no absolute image URL", () => {
    expect(deriveCdnOrigin([page([{ type: "text", fontFamily: "Montserrat" }])])).toBeNull();
    expect(deriveCdnOrigin([page([{ type: "image", src: "/relative/x.webp" }])])).toBeNull();
  });

  it("derives from page.img when image items are field references (designer layouts)", () => {
    // Designer-authored layouts store {type:"image", field:"img"} with NO
    // src — the URL lives on page.img. This exact shape silently disabled
    // font self-hosting for every designer creative once.
    const p = {
      img: "https://cdn.example.com/assets/cover.webp",
      layout: [
        { type: "image", field: "img" },
        { type: "text", fontFamily: "Shippori Mincho B1 Medium, Georgia, serif" },
      ],
    } as unknown as Page;
    expect(deriveCdnOrigin([p])).toBe("https://cdn.example.com");
  });
});

describe("collectExpandedFonts", () => {
  const origin = "https://cdn.example.com";

  it("resolves an allow-listed family to its per-weight woff2 URL (default 400)", () => {
    const faces = collectExpandedFonts([page([{ type: "text", fontFamily: "Montserrat, sans-serif" }])], origin);
    expect(faces).toEqual([
      { family: "Montserrat", weight: 400, url: "https://cdn.example.com/fonts/montserrat-400-latin.woff2" },
    ]);
  });

  it("parses a named-instance weight (Montserrat Thin → 100), keeps the literal family, base slug", () => {
    const faces = collectExpandedFonts([page([{ type: "text", fontFamily: "Montserrat Thin, sans-serif" }])], origin);
    expect(faces).toEqual([
      { family: "Montserrat Thin", weight: 100, url: "https://cdn.example.com/fonts/montserrat-100-latin.woff2" },
    ]);
  });

  it("matches a variable-font family (Montserrat Variable) to the base slug", () => {
    const faces = collectExpandedFonts([page([{ type: "text", fontFamily: "Montserrat Variable, ui-sans-serif, sans-serif", fontWeight: 100 }])], origin);
    expect(faces).toEqual([
      { family: "Montserrat Variable", weight: 100, url: "https://cdn.example.com/fonts/montserrat-100-latin.woff2" },
    ]);
  });

  it("uses the CSS font-weight when the name carries none", () => {
    const faces = collectExpandedFonts([page([{ type: "text", fontFamily: "Poppins, sans-serif", fontWeight: 600 }])], origin);
    expect(faces[0]).toEqual({ family: "Poppins", weight: 600, url: "https://cdn.example.com/fonts/poppins-600-latin.woff2" });
  });

  it("uses the catalog slug for multi-word families", () => {
    const faces = collectExpandedFonts([page([{ type: "text", fontFamily: "Playfair Display, serif" }])], origin);
    expect(faces[0].url).toBe("https://cdn.example.com/fonts/playfair-display-400-latin.woff2");
  });

  it("skips generic / system families (system fallback)", () => {
    expect(collectExpandedFonts([page([{ type: "text", fontFamily: "Helvetica Neue, sans-serif" }])], origin)).toEqual([]);
    expect(collectExpandedFonts([page([{ type: "text", fontFamily: "sans-serif" }])], origin)).toEqual([]);
    expect(collectExpandedFonts([page([{ type: "text", fontFamily: "Georgia, serif" }])], origin)).toEqual([]);
  });

  it("derives a URL for ANY non-generic family (no allow-list)", () => {
    // A family that was never curated still resolves — the server self-hosts it
    // if Google serves it; a licensed/non-Google one 404s at load → fallback.
    const faces = collectExpandedFonts([page([{ type: "text", fontFamily: "Brandon Grotesque, sans-serif" }])], origin);
    expect(faces[0].url).toBe("https://cdn.example.com/fonts/brandon-grotesque-400-latin.woff2");
  });

  it("keeps distinct weights of the same family, dedupes identical ones", () => {
    const pages = [
      page([
        { type: "text", fontFamily: "Inter, sans-serif", fontWeight: 100 },
        { type: "text", fontFamily: "Inter, sans-serif", fontWeight: 100 },
        { type: "text", fontFamily: "Inter, sans-serif", fontWeight: 700 },
      ]),
    ];
    const faces = collectExpandedFonts(pages, origin);
    expect(faces.map((f) => f.weight).sort()).toEqual([100, 700]);
  });

  it("also walks the mobile-expanded master", () => {
    const p = { banners: { "mobile-expanded": [{ type: "text", fontFamily: "Poppins, sans-serif" }] } } as unknown as Page;
    const faces = collectExpandedFonts([p], origin);
    expect(faces[0].url).toBe("https://cdn.example.com/fonts/poppins-400-latin.woff2");
  });

  it("ignores non-text items", () => {
    expect(collectExpandedFonts([page([{ type: "image", src: "x", fontFamily: "Montserrat" }])], origin)).toEqual([]);
  });

  it("uses a per-text subset key for CJK families (Noto Sans JP)", () => {
    const p = {
      headline: "日本語ABC",
      layout: [{ type: "text", fontFamily: "Noto Sans JP, sans-serif", fontWeight: 700 }],
    } as unknown as Page;
    const faces = collectExpandedFonts([p], origin);
    // subsetKey("日本語ABC") === "a42feb55" — locked against the server.
    expect(faces[0]).toEqual({
      family: "Noto Sans JP",
      weight: 700,
      url: "https://cdn.example.com/fonts/noto-sans-jp-700-a42feb55.woff2",
    });
  });

  it("self-hosts a non-Noto JP family too (Zen Old Mincho) — no allow-list", () => {
    const p = {
      headline: "日本語ABC",
      layout: [{ type: "text", fontFamily: "Zen Old Mincho, serif", fontWeight: 400 }],
    } as unknown as Page;
    const faces = collectExpandedFonts([p], origin);
    expect(faces[0].url).toBe("https://cdn.example.com/fonts/zen-old-mincho-400-a42feb55.woff2");
  });
});

// These literals MUST equal GoogleFontCatalog.subsetKey (Scala) — the server
// stores the CJK woff2 under this key and the banner derives the URL from it.
// FontProvisionSpec asserts the same values; if either drifts, CJK serving
// silently falls back to the system font.
// Mirror contract with the server's subsetTextFromPagesJson — the same
// fixture lives in FontProvisionSpec.scala. Baked per-item `text` overrides
// (layout + banner buckets) render characters the page fields don't have;
// both sides must include them or the CJK subset misses those glyphs and
// the browser falls back per-glyph (mixed typefaces mid-sentence).
describe("collectSubsetText (server contract)", () => {
  it("includes page fields AND baked text-item overrides from layout + buckets", () => {
    const pages = [{
      headline: "日本語",
      layout: [{ type: "text", text: "上書き" }, { type: "image", src: "x" }],
      banners: { "300x250": [{ type: "text", text: "別枠" }] },
    }] as unknown as Page[];
    const text = collectSubsetText(pages);
    for (const t of ["日本語", "上書き", "別枠"]) expect(text).toContain(t);
    expect(text).not.toContain("x");
  });
});

describe("subsetKey (server contract)", () => {
  it("matches the Scala fixtures, order/dup-insensitive", () => {
    expect(subsetKey("日本語ABC")).toBe("a42feb55");
    expect(subsetKey("ABC日本語")).toBe("a42feb55");
    expect(subsetKey("こんにちは世界")).toBe("c78ba161");
    expect(subsetKey("")).toBe("811c9dc5");
    expect(subsetKey("A")).toBe("c40bf6cc");
  });
});

describe("resolveExpandedFonts", () => {
  it("derives origin + collects faces in one call", () => {
    const pages = [page([
      { type: "text", fontFamily: "Lato, sans-serif" },
      { type: "image", src: "https://cdn.example.com/assets/x.webp" },
    ])];
    expect(resolveExpandedFonts(pages)).toEqual([
      { family: "Lato", weight: 400, url: "https://cdn.example.com/fonts/lato-400-latin.woff2" },
    ]);
  });

  it("returns [] when no CDN origin is derivable", () => {
    expect(resolveExpandedFonts([page([{ type: "text", fontFamily: "Lato, sans-serif" }])])).toEqual([]);
  });
});
