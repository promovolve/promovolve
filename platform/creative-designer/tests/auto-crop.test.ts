// fitCropToItemAspect — the boot auto-crop's geometry. The renderer
// (banner layout-item.ts) shows the authored crop window pixel-exact
// ONLY when the window's pixel aspect equals the item box's pixel
// aspect; these tests pin that invariant plus padding, the minimum-
// window floor, and edge clamping.

import { describe, expect, it } from "vitest";
import { fitCropToItemAspect } from "../src/auto-crop";

// Pixel aspect of a %-of-image crop box on a given image.
const pxAspect = (box: { w: number; h: number }, imgW: number, imgH: number): number =>
  ((box.w / 100) * imgW) / ((box.h / 100) * imgH);

describe("fitCropToItemAspect", () => {
  it("produces a window whose pixel aspect matches the item box", () => {
    // Tall subject in a wide 1000×800 image, fitted for a 2:1 item box.
    const out = fitCropToItemAspect({ x: 40, y: 20, w: 10, h: 50 }, 1000, 800, 2);
    expect(pxAspect(out, 1000, 800)).toBeCloseTo(2, 1);
  });

  it("contains the padded subject when nothing clamps", () => {
    const subject = { x: 40, y: 40, w: 20, h: 20 };
    const out = fitCropToItemAspect(subject, 1000, 1000, 1, { padFrac: 0.1, minFrac: 0 });
    // Window must cover the subject plus its 10%-per-side padding.
    expect(out.x).toBeLessThanOrEqual(38);
    expect(out.y).toBeLessThanOrEqual(38);
    expect(out.x + out.w).toBeGreaterThanOrEqual(62);
    expect(out.y + out.h).toBeGreaterThanOrEqual(62);
  });

  it("stays inside the image when the subject hugs a corner", () => {
    const out = fitCropToItemAspect({ x: 0, y: 0, w: 10, h: 10 }, 1000, 1000, 16 / 9);
    expect(out.x).toBeGreaterThanOrEqual(0);
    expect(out.y).toBeGreaterThanOrEqual(0);
    expect(out.x + out.w).toBeLessThanOrEqual(100.05); // 0.1-rounding slack
    expect(out.y + out.h).toBeLessThanOrEqual(100.05);
  });

  it("enforces the minimum-window floor on tiny subjects", () => {
    // 2%×2% subject would be a 50× zoom without the floor.
    const out = fitCropToItemAspect({ x: 49, y: 49, w: 2, h: 2 }, 1000, 1000, 1, { minFrac: 0.3 });
    expect(out.w).toBeGreaterThanOrEqual(30 - 0.1);
    expect(out.h).toBeGreaterThanOrEqual(30 - 0.1);
    // Still centered on the subject.
    expect(out.x + out.w / 2).toBeCloseTo(50, 0);
    expect(out.y + out.h / 2).toBeCloseTo(50, 0);
  });

  it("falls back to the largest in-bounds window when the aspect can't be reached around the subject", () => {
    // Full-height subject in a square image, 16:9 target: matching the
    // aspect would need w > imgW, so the window clamps to full width
    // and keeps the aspect by shrinking height.
    const out = fitCropToItemAspect({ x: 45, y: 0, w: 10, h: 100 }, 1000, 1000, 16 / 9);
    expect(out.w).toBeCloseTo(100, 0);
    expect(pxAspect(out, 1000, 1000)).toBeCloseTo(16 / 9, 1);
    expect(out.y).toBeGreaterThanOrEqual(0);
    expect(out.y + out.h).toBeLessThanOrEqual(100.05);
  });

  it("treats a degenerate target aspect as square instead of blowing up", () => {
    const out = fitCropToItemAspect({ x: 40, y: 40, w: 20, h: 20 }, 1000, 1000, Infinity);
    expect(pxAspect(out, 1000, 1000)).toBeCloseTo(1, 1);
    expect(out.w).toBeGreaterThan(0);
  });
});

import { shrinkCropToAspect } from "../src/auto-crop";

describe("shrinkCropToAspect", () => {
  it("returns the largest centered sub-window at the target aspect", () => {
    // 50%×50% window on a 1000×1000 image (500×500 px), reshaped 2:1.
    const out = shrinkCropToAspect({ x: 25, y: 25, w: 50, h: 50 }, 1000, 1000, 2);
    expect(out.w).toBeCloseTo(50, 1);   // full width kept
    expect(out.h).toBeCloseTo(25, 1);   // height halved
    expect(out.x).toBeCloseTo(25, 1);   // centered: x unchanged
    expect(out.y).toBeCloseTo(37.5, 1); // centered vertically
  });

  it("stays inside the original window (no clamping ever needed)", () => {
    const orig = { x: 10, y: 20, w: 30, h: 40 };
    for (const a of [0.2, 0.8, 1, 3, 10]) {
      const out = shrinkCropToAspect(orig, 800, 600, a);
      expect(out.x).toBeGreaterThanOrEqual(orig.x - 0.05);
      expect(out.y).toBeGreaterThanOrEqual(orig.y - 0.05);
      expect(out.x + out.w).toBeLessThanOrEqual(orig.x + orig.w + 0.05);
      expect(out.y + out.h).toBeLessThanOrEqual(orig.y + orig.h + 0.05);
    }
  });

  it("is a no-op when the window already has the target aspect", () => {
    // 400×200 px window on a 1000×1000 image, target 2:1.
    const out = shrinkCropToAspect({ x: 10, y: 10, w: 40, h: 20 }, 1000, 1000, 2);
    expect(out).toEqual({ x: 10, y: 10, w: 40, h: 20 });
  });

  it("guards degenerate aspect", () => {
    const out = shrinkCropToAspect({ x: 0, y: 0, w: 50, h: 50 }, 1000, 1000, Infinity);
    expect(out.w).toBeGreaterThan(0);
    expect(out.h).toBeGreaterThan(0);
  });
});
