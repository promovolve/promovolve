// Spec metrics — derived from the current page + mode + layout. Used
// by the CanvasFoot to surface quick-feedback numbers the author
// cares about while composing:
//
//   weightKb           — approximate KB the creative will ship at,
//                        so authors spot image-heavy creatives
//                        before publish. Accurate asset-weight
//                        measurement requires server-side inspection
//                        of stored blobs; until that pipeline exists
//                        we estimate from image pixel area (rendered
//                        width × height at the current mode's
//                        reference resolution) times a JPEG-ish
//                        byte-per-pixel constant.
//   clickZone          — width/height of the CTA target in percent.
//                        The auction's "reasonable click target"
//                        threshold is 20×8% of the creative surface.
//   legibilityWarnings — count of text items whose fontSize (% of
//                        container height) is below 3, which renders
//                        as sub-pixel smudges on mobile viewports.
//
// Thresholds live in WEIGHT_OK / WEIGHT_MAX / CLICK_MIN_W /
// CLICK_MIN_H / LEGIBILITY_MIN_FONT so the CanvasFoot's traffic-
// light colouring stays consistent with whatever ad-ops signs off
// on. Numbers currently reflect the README's placeholders.

import type { DesignerState, LayoutItem } from "../types";

// KB thresholds for weight pill: <160 ok, <200 warn, ≥200 err.
export const WEIGHT_OK  = 160;
export const WEIGHT_MAX = 200;

// Click zone thresholds: a CTA target smaller than 20×8% of the
// creative makes it hard to hit on touch. 20% wide / 8% tall is the
// tap-friendly minimum for banner sizes in the typical IAB range.
export const CLICK_MIN_W = 20;
export const CLICK_MIN_H = 8;

// Any text at fontSize < 3 (% of the page's shorter dim) fails
// mobile legibility checks.
export const LEGIBILITY_MIN_FONT = 3;

// JPEG-ish estimate: ~0.15 bytes per rendered pixel at quality 85.
// Good enough to distinguish "tiny" creatives from "photo-heavy"
// without touching R2 or the asset blob store. Replace with the
// real asset-weight pipeline when it lands.
const BYTES_PER_PX = 0.15;

export type Threshold = "ok" | "warn" | "err";

export interface SpecMetrics {
  weightKb: number;
  weightThreshold: Threshold;
  /** Bytes of the current page's video background, when known.
    * Billed separately from static weight by most ad servers —
    * canvas-foot renders it as its own pill when > 0. */
  videoBytes: number;
  clickZone: { w: number; h: number } | null;
  clickZoneOk: boolean;
  legibilityWarnings: number;
  legibilityOk: boolean;
}

export function specMetrics(state: DesignerState): SpecMetrics {
  const layout = currentLayout(state);
  const { w: modeW, h: modeH } = state.mode;

  let imagePixels = 0;
  let clickZone: { w: number; h: number } | null = null;
  let legibilityWarnings = 0;

  for (const item of layout) {
    if (item.type === "image") {
      // Image dimensions in pixels at the current mode's reference
      // resolution. Circles use radius * 2; other shapes don't
      // contribute weight (they're CSS fills).
      const iw = (item.width  ?? 0) / 100 * modeW;
      const ih = (item.height ?? 0) / 100 * modeH;
      imagePixels += Math.max(0, iw) * Math.max(0, ih);
    }
    if ((item as LayoutItem & { ctaTarget?: boolean }).ctaTarget) {
      const w = (item as LayoutItem & { width?: number }).width;
      const h = (item as LayoutItem & { height?: number }).height;
      if (w != null && h != null && clickZone == null) clickZone = { w, h };
    }
    if (
      item.type === "text" &&
      typeof item.fontSize === "number" &&
      item.fontSize < LEGIBILITY_MIN_FONT
    ) {
      legibilityWarnings++;
    }
  }

  // A texture bg ships with every impression like any image asset, so
  // its actual bytes count toward the transfer-weight budget. (Video is
  // kept separate as its own pill — it's an order of magnitude larger.)
  const textureBytes = state.pages[state.pageIdx]?.textureBg?.sizeBytes ?? 0;
  const weightKb = Math.round((imagePixels * BYTES_PER_PX + textureBytes) / 1024);
  const weightThreshold: Threshold =
    weightKb < WEIGHT_OK  ? "ok"
    : weightKb < WEIGHT_MAX ? "warn"
    : "err";

  const clickZoneOk = !!clickZone &&
    clickZone.w >= CLICK_MIN_W &&
    clickZone.h >= CLICK_MIN_H;

  const page = state.pages[state.pageIdx];
  const videoBytes = page?.videoBg?.sizeBytes ?? 0;

  return {
    weightKb,
    weightThreshold,
    videoBytes,
    clickZone,
    clickZoneOk,
    legibilityWarnings,
    legibilityOk: legibilityWarnings === 0,
  };
}

function currentLayout(state: DesignerState): LayoutItem[] {
  const page = state.pages[state.pageIdx];
  if (!page) return [];
  const mode = state.mode;
  if (mode.key === "expanded") return page.layout ?? [];
  if (!mode.sizeKey) return [];
  return page.banners?.[mode.sizeKey] ?? [];
}
