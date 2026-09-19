// Remove-background: turn the u2netp saliency mask (saliency.ts) into an
// alpha matte and bake it into the image as a WebP-with-alpha cutout.
//
// This is the same model the crop modal's auto-suggest already runs —
// there we throw the mask away after deriving a bounding box; here the
// mask IS the product. Everything runs in the browser: no server, no
// per-image cost. Quality is "u2netp at 320×320" — crisp on product
// shots against plain backgrounds, soft on hair/fur/glass. A better
// matting model (ISNet, BiRefNet-lite) plugs in by swapping saliency's
// MODEL_URL; nothing here assumes u2netp beyond the mask resolution.
//
// Pipeline:
//   runSaliencyMask → maskToAlpha (normalize + edge curve, pure)
//   → upsample to output size via canvas bilinear draw
//   → refineMatte (guided filter + decontamination, matte-refine.ts)
//   → copy into the alpha channel of the drawn source → WebP blob
//
// Output keeps the source's pixel dimensions (capped like
// prepareForUpload), so any existing crop / box geometry on the item
// stays valid after the src swap.

import { applyHints, resampleHints, type HintPlane } from "./matte-hints";
import { refineMatte } from "./matte-refine";
import { runSaliencyMask, SALIENCY_MASK_SIZE } from "./saliency";

// Edge curve: u2netp's sigmoid output is soft — a wide band of 0.3..0.7
// around every edge. Mapping that band linearly to alpha leaves a
// visible halo of half-transparent background around the subject.
// A smoothstep between these two points firms the edge while keeping
// ~1–2px of anti-aliasing at output scale. Lower EDGE_LO keeps more of
// the soft halo; raise EDGE_HI to bite further into the subject.
export const EDGE_LO = 0.3;
export const EDGE_HI = 0.7;

// Output long-edge cap. Mirrors prepareForUpload's MAX_EDGE so a cutout
// of an already-uploaded (≤2000px) asset is never upscaled, and one from
// a data-URL dev source never explodes.
const MAX_EDGE = 2000;

// WebP quality for the cutout. Higher than prepareForUpload's 0.82: lossy
// WebP quantizes the alpha plane too, and alpha ringing on a cutout edge
// is far more visible than chroma ringing in a photo.
const WEBP_QUALITY = 0.9;

/** Pure: raw saliency mask → 8-bit alpha plane of the same size.
  *
  * 1. Min-max normalize (what rembg does for u2net) so a low-contrast
  *    mask still spans the full range instead of coming out uniformly
  *    grey → uniformly half-transparent.
  * 2. Smoothstep the edge band (see EDGE_LO/EDGE_HI).
  *
  * A flat mask (max ≈ min — the model saw nothing) yields fully opaque
  * alpha so a failed detection degrades to "no change", never to a
  * blank image. */
export function maskToAlpha(mask: Float32Array, lo = EDGE_LO, hi = EDGE_HI): Uint8ClampedArray {
  const n = mask.length;
  const out = new Uint8ClampedArray(n);
  let min = Infinity;
  let max = -Infinity;
  for (let i = 0; i < n; i++) {
    const v = mask[i];
    if (v < min) min = v;
    if (v > max) max = v;
  }
  const range = max - min;
  if (!(range > 1e-6)) {
    out.fill(255);
    return out;
  }
  const span = hi - lo;
  for (let i = 0; i < n; i++) {
    const norm = (mask[i] - min) / range;
    let t = (norm - lo) / span;
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    const a = t * t * (3 - 2 * t); // smoothstep
    out[i] = Math.round(a * 255);
  }
  return out;
}

/** Fraction of the alpha plane that is (mostly) opaque. Used by the
  * modal to warn when the model kept almost nothing or almost
  * everything — both mean "this image isn't a subject-on-background
  * photo" and the result will look wrong. */
export function opaqueFraction(alpha: Uint8ClampedArray): number {
  let n = 0;
  for (let i = 0; i < alpha.length; i++) if (alpha[i] >= 128) n++;
  return alpha.length ? n / alpha.length : 0;
}

/** Output pixel size for a source, mirroring prepareForUpload's cap. */
export function outputSize(naturalW: number, naturalH: number, maxEdge = MAX_EDGE): { w: number; h: number } {
  const scale = Math.min(1, maxEdge / Math.max(naturalW, naturalH));
  return {
    w: Math.max(1, Math.round(naturalW * scale)),
    h: Math.max(1, Math.round(naturalH * scale)),
  };
}

/** Upsample an 8-bit alpha plane of size×size to w×h with the canvas'
  * bilinear filter, returning the w×h alpha plane. A greyscale draw
  * through a 2D context is the cheapest bilinear resampler in the
  * browser and matches how the mask was produced (stretch-fit). */
function upsampleAlpha(alpha: Uint8ClampedArray, size: number, w: number, h: number): Uint8ClampedArray {
  const small = document.createElement("canvas");
  small.width = size;
  small.height = size;
  const sctx = small.getContext("2d");
  if (!sctx) throw new Error("2d canvas context unavailable");
  const img = sctx.createImageData(size, size);
  for (let i = 0; i < alpha.length; i++) {
    const a = alpha[i];
    img.data[i * 4] = a;
    img.data[i * 4 + 1] = a;
    img.data[i * 4 + 2] = a;
    img.data[i * 4 + 3] = 255;
  }
  sctx.putImageData(img, 0, 0);

  const big = document.createElement("canvas");
  big.width = w;
  big.height = h;
  const bctx = big.getContext("2d", { willReadFrequently: true });
  if (!bctx) throw new Error("2d canvas context unavailable");
  bctx.imageSmoothingEnabled = true;
  bctx.imageSmoothingQuality = "high";
  bctx.drawImage(small, 0, 0, w, h);
  const { data } = bctx.getImageData(0, 0, w, h);
  const out = new Uint8ClampedArray(w * h);
  for (let i = 0; i < out.length; i++) out[i] = data[i * 4];
  return out;
}

export interface CompositeOptions {
  /** Run the guided-filter edge refinement + decontamination pass
    * (matte-refine.ts) against the full-res source. Default on. */
  refine?: boolean;
  /** Restore/erase brush hints (matte-hints.ts), at whatever resolution
    * they were painted — resampled to the composite size if they differ.
    * Applied to the coarse alpha before refinement so strokes still get
    * their edges snapped. */
  hints?: HintPlane | null;
  /** Long-edge cap for THIS composite. The modal previews at a small
    * cap (fast, interactive) and renders the real thing at the default
    * MAX_EDGE once, on Apply. */
  maxEdge?: number;
}

/** Draw the source with the alpha plane baked in and return the canvas
  * (the modal shows it and encodes it on Apply). Throws if the canvas
  * is tainted (CORS-less source) — the caller reports that. */
export function compositeCutout(
  img: HTMLImageElement,
  alphaSmall: Uint8ClampedArray,
  opts: CompositeOptions = {},
): { canvas: HTMLCanvasElement; coverage: number } {
  const { w, h } = outputSize(img.naturalWidth, img.naturalHeight, opts.maxEdge);
  let alpha = upsampleAlpha(alphaSmall, SALIENCY_MASK_SIZE, w, h);

  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("2d canvas context unavailable");
  ctx.drawImage(img, 0, 0, w, h);
  const pixels = ctx.getImageData(0, 0, w, h); // throws SecurityError on taint
  const d = pixels.data;
  if (opts.hints) applyHints(alpha, resampleHints(opts.hints, w, h));
  if (opts.refine ?? true) alpha = refineMatte(d, w, h, alpha);
  for (let i = 0; i < alpha.length; i++) d[i * 4 + 3] = alpha[i];
  ctx.putImageData(pixels, 0, 0);
  return { canvas, coverage: opaqueFraction(alpha) };
}

/** Model → coarse matte (SALIENCY_MASK_SIZE² alpha plane). Split from
  * compositeCutout so the modal runs the model once and re-composites
  * when the author toggles refinement. */
export async function computeMatte(img: HTMLImageElement): Promise<Uint8ClampedArray> {
  return maskToAlpha(await runSaliencyMask(img));
}

export function encodeCanvas(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("canvas.toBlob returned null"))), "image/webp", WEBP_QUALITY);
  });
}

/** Filename for the uploaded cutout, derived from the source URL so the
  * asset library reads "<original>-cutout.webp". Data-URL / opaque
  * sources fall back to a generic name. */
export function cutoutFilename(src: string): string {
  const m = src.match(/\/([^/?#]+?)(?:\.[a-z0-9]+)?(?:[?#].*)?$/i);
  const base = m && !src.startsWith("data:") ? m[1] : "image";
  return `${base}-cutout.webp`;
}
