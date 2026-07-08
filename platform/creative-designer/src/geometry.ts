// Shared geometry helpers — bounds, intersection. Extracted from
// overlay and rotate-gesture so marquee and future features don't
// re-derive the same math.

import type { LayoutItem } from "./types";

export interface BoundsPct {
  left: number;
  top: number;
  width: number;
  height: number;
}

// Approximate the layout item's bounding box in percent coordinates.
// Circles use 2×radius; rect/image/text use width+height. Defaults
// match the banner component's fallbacks.
export function itemBoundsPct(item: LayoutItem): BoundsPct {
  const left = item.left ?? 0;
  const top = item.top ?? 0;
  if (item.type === "circle") {
    const r = item.radius ?? 5;
    return { left, top, width: r * 2, height: r * 2 };
  }
  return {
    left,
    top,
    width: item.width ?? 30,
    height: item.height ?? 20,
  };
}

// Axis-aligned rectangle intersection. Treats touching edges as
// intersecting — matches "marquee brushes over the edge" feel.
export function rectsIntersect(a: BoundsPct, b: BoundsPct): boolean {
  return (
    a.left < b.left + b.width &&
    a.left + a.width > b.left &&
    a.top < b.top + b.height &&
    a.top + a.height > b.top
  );
}

// Compute centred insertion bounds (in percent coords) that preserve
// an image's native aspect against the current canvas. Without this,
// hard-coded percentages like `width: 20, height: 20` render as a
// non-square pixel rectangle on a 16:9 canvas, stretching square
// images (icons, square photos) into ovals.
//
// `targetPct` is the cap along the item's dominant dimension as a
// percentage of the canvas. Returns the dimensions, plus left/top to
// centre the item in the canvas.
export function fitInsertion(opts: {
  imgWidth: number;
  imgHeight: number;
  canvasWidth: number;
  canvasHeight: number;
  targetPct?: number;
}): BoundsPct {
  const target = opts.targetPct ?? 40;
  if (!opts.imgWidth || !opts.imgHeight || opts.canvasWidth <= 0 || opts.canvasHeight <= 0) {
    return { width: 30, height: 40, left: 35, top: 30 };
  }
  // Width% × canvasW / (Height% × canvasH) must equal imgW / imgH
  // ⇒ Width%/Height% = (imgW/imgH) × (canvasH/canvasW).
  // If that ratio ≥ 1 the image is wider relative to the canvas, so
  // cap width at `target` and derive height; otherwise mirror.
  const ratio = (opts.imgWidth / opts.imgHeight) * (opts.canvasHeight / opts.canvasWidth);
  const widthPct = ratio >= 1 ? target : target * ratio;
  const heightPct = ratio >= 1 ? target / ratio : target;
  return {
    width: round1(widthPct),
    height: round1(heightPct),
    left: round1((100 - widthPct) / 2),
    top: round1((100 - heightPct) / 2),
  };
}

function round1(v: number): number {
  return Math.round(v * 10) / 10;
}
