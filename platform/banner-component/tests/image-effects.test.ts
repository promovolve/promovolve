// @vitest-environment jsdom
//
// The three image edge effects (feather, vignette, torn) must
// render byte-identically through both render paths — render-collapsed
// (HTML strings) and layout-item (DOM nodes) — because the designer
// preview and the published banner share them. These tests pin the CSS
// each effect produces and assert both paths agree, including the
// crop-wrapper and vignette-overlay structural cases.

import { describe, expect, it } from "vitest";
import {
  hasVignette,
  imageEdgeCss,
  needsImageWrapper,
  vignetteOverlayCss,
} from "../src/image-effects";
import { layoutItemToNode } from "../src/layout-item";
import { renderCollapsedItemHtml } from "../src/render-collapsed";
import type { ImageItem, Page } from "../src/types";

const PAGE: Page = {};
const baseImg = (extra: Partial<ImageItem>): ImageItem => ({
  type: "image",
  src: "https://cdn.example/x.png",
  left: 10,
  top: 10,
  width: 40,
  height: 30,
  ...extra,
});

describe("imageEdgeCss", () => {
  it("is empty when no effect is set", () => {
    expect(imageEdgeCss(baseImg({}))).toBe("");
  });

  it("emits two intersected mask layers for feather (corners feather too)", () => {
    const css = imageEdgeCss(baseImg({ feather: 4 }));
    expect(css).toContain("mask-image:");
    expect(css).toContain("to right");
    expect(css).toContain("to bottom");
    expect(css).toContain("4cqmin");
    // two feather axes → must intersect, not stack
    expect(css).toContain("mask-composite:intersect");
    expect(css).toContain("-webkit-mask-composite:source-in");
  });

  it("references the inline <mask> by fragment for tornEdge (not a data URI)", () => {
    const css = imageEdgeCss(baseImg({ tornEdge: 3 }), "pv-torn-9");
    expect(css).toContain("mask-image:url(#pv-torn-9)");
    // A data-URI SVG mask renders transparent in Chromium — must NOT use one.
    expect(css).not.toContain("data:image/svg+xml,");
  });

  it("emits no torn layer when no mask id is supplied", () => {
    expect(imageEdgeCss(baseImg({ tornEdge: 3 }))).toBe("");
  });

  it("composites feather AND torn together", () => {
    const css = imageEdgeCss(baseImg({ feather: 3, tornEdge: 2 }), "pv-torn-7");
    // 3 layers (2 feather + 1 torn) → intersect. The two feather
    // gradients appear in both -webkit-mask-image and mask-image → 4.
    expect(css).toContain("mask-composite:intersect");
    expect((css.match(/linear-gradient/g) ?? []).length).toBe(4);
    expect(css).toContain("url(#pv-torn-7)");
  });
});

describe("vignetteOverlayCss", () => {
  it("returns null when off", () => {
    expect(vignetteOverlayCss(baseImg({}))).toBeNull();
    expect(vignetteOverlayCss(baseImg({ vignette: { strength: 0 } }))).toBeNull();
  });

  it("builds a radial-gradient overlay that inherits the radius", () => {
    const css = vignetteOverlayCss(baseImg({ vignette: { strength: 0.5 } }))!;
    expect(css).toContain("radial-gradient");
    expect(css).toContain("border-radius:inherit");
    expect(css).toContain("pointer-events:none");
    expect(css).toContain("rgba(0,0,0,0.350)"); // 0.7 * 0.5
  });

  it("honours a custom vignette color", () => {
    const css = vignetteOverlayCss(baseImg({ vignette: { strength: 0.4, color: "rgba(0,0,80,0.6)" } }))!;
    expect(css).toContain("rgba(0,0,80,0.6)");
  });
});

describe("needsImageWrapper", () => {
  it("is true for crop or vignette, false otherwise", () => {
    expect(needsImageWrapper(baseImg({}))).toBe(false);
    expect(needsImageWrapper(baseImg({ feather: 5 }))).toBe(false);
    // torn now needs a wrapper to host its inline <mask> def.
    expect(needsImageWrapper(baseImg({ tornEdge: 3 }))).toBe(true);
    expect(needsImageWrapper(baseImg({ vignette: { strength: 0.3 } }))).toBe(true);
    expect(needsImageWrapper(baseImg({ crop: { x: 0, y: 0, w: 50, h: 50 } }))).toBe(true);
    expect(hasVignette(baseImg({ vignette: { strength: 0.3 } }))).toBe(true);
  });
});

describe("collapsed render path", () => {
  it("applies feather mask straight to the <img> (no wrapper)", () => {
    const html = renderCollapsedItemHtml(baseImg({ feather: 4 }), 0, PAGE, "sans-serif");
    expect(html.startsWith("<img")).toBe(true);
    expect(html).toContain("mask-image:");
  });

  it("wraps for vignette and appends the overlay div", () => {
    const html = renderCollapsedItemHtml(baseImg({ vignette: { strength: 0.5 } }), 0, PAGE, "sans-serif");
    expect(html.startsWith("<div")).toBe(true);
    expect(html).toContain("<img");
    expect(html).toContain("radial-gradient"); // the overlay
    // overlay comes after the img
    expect(html.indexOf("radial-gradient")).toBeGreaterThan(html.indexOf("<img"));
  });

  it("keeps the crop wrapper and carries edge css on it", () => {
    const html = renderCollapsedItemHtml(
      baseImg({ crop: { x: 10, y: 0, w: 80, h: 100 }, feather: 3 }),
      0, PAGE, "sans-serif",
    );
    expect(html.startsWith("<div")).toBe(true);
    expect(html).toContain("overflow:hidden");
    expect(html).toContain("mask-image:");
  });
});

// Structure-only: jsdom's CSS parser drops cqmin/mask-* declarations, so
// these assert the DOM SHAPE each effect produces. The exact CSS values
// are pinned by the pure-helper and collapsed-string suites above.
describe("expanded (DOM) render path", () => {
  it("keeps a plain <img> for feather only (no wrapper)", () => {
    const node = layoutItemToNode(baseImg({ feather: 3 }), PAGE, "sans-serif", 0)!;
    expect(node.tagName).toBe("IMG");
  });

  it("wraps torn and injects an inline <mask> the wrapper references", () => {
    const node = layoutItemToNode(baseImg({ tornEdge: 2 }), PAGE, "sans-serif", 0)!;
    expect(node.tagName).toBe("DIV"); // torn forces a wrapper
    const mask = node.querySelector("mask");
    expect(mask).not.toBeNull();
    expect(node.querySelector("feDisplacementMap")).not.toBeNull();
    // the wrapper's mask CSS references the SAME id as the emitted <mask>
    const id = mask!.getAttribute("id")!;
    expect(id).toMatch(/^pv-torn-\d+$/);
    expect(node.getAttribute("style")).toContain(`url(#${id})`);
    expect(node.querySelector("img")).not.toBeNull();
  });

  it("wraps and appends an overlay div for vignette", () => {
    const node = layoutItemToNode(baseImg({ vignette: { strength: 0.6 } }), PAGE, "sans-serif", 0)!;
    expect(node.tagName).toBe("DIV");
    const kids = node.children;
    expect(kids.length).toBe(2); // img + overlay
    expect(kids[0]!.tagName).toBe("IMG");
    expect(kids[1]!.tagName).toBe("DIV"); // the overlay (not an img)
  });

  it("keeps the crop wrapper structure", () => {
    const node = layoutItemToNode(baseImg({ crop: { x: 10, y: 0, w: 80, h: 100 } }), PAGE, "sans-serif", 0)!;
    expect(node.tagName).toBe("DIV");
    expect(node.querySelector("img")).not.toBeNull();
  });
});
