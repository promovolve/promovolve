// Restore / erase brush hints for remove-background. A hint plane at
// the cutout's output resolution: 0 = no opinion, KEEP = author says
// "this is subject", DROP = "this is background". Applied to the coarse
// upsampled alpha BEFORE the guided-filter refinement (remove-bg.ts), so
// a rough stroke over a paw forces the region in and the filter then
// snaps the actual edge to the fur/ground boundary inside the stroke.
//
// Pure typed-array code — no DOM — so tests can pin the rasterizer.

export const HINT_NONE = 0;
export const HINT_KEEP = 1;
export const HINT_DROP = 2;

export type HintValue = typeof HINT_KEEP | typeof HINT_DROP;

/** Rasterize a filled disc of radius `r` (pixels) centred at (cx, cy). */
export function paintDisc(hints: Uint8Array, w: number, h: number, cx: number, cy: number, r: number, value: HintValue): void {
  const r2 = r * r;
  const y0 = Math.max(0, Math.floor(cy - r));
  const y1 = Math.min(h - 1, Math.ceil(cy + r));
  const x0 = Math.max(0, Math.floor(cx - r));
  const x1 = Math.min(w - 1, Math.ceil(cx + r));
  for (let y = y0; y <= y1; y++) {
    const dy = y - cy;
    for (let x = x0; x <= x1; x++) {
      const dx = x - cx;
      if (dx * dx + dy * dy <= r2) hints[y * w + x] = value;
    }
  }
}

/** Rasterize a stroke segment from (x0,y0) to (x1,y1) as a chain of
  * discs spaced ≤ r/2 apart so fast drags leave no gaps. */
export function paintStroke(
  hints: Uint8Array, w: number, h: number,
  x0: number, y0: number, x1: number, y1: number, r: number, value: HintValue,
): void {
  const dx = x1 - x0;
  const dy = y1 - y0;
  const len = Math.hypot(dx, dy);
  const steps = Math.max(1, Math.ceil(len / Math.max(1, r / 2)));
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    paintDisc(hints, w, h, x0 + dx * t, y0 + dy * t, r, value);
  }
}

/** Force alpha per hint: KEEP → 255, DROP → 0, NONE untouched. In place. */
export function applyHints(alpha: Uint8ClampedArray, hints: Uint8Array): void {
  const n = Math.min(alpha.length, hints.length);
  for (let i = 0; i < n; i++) {
    const v = hints[i];
    if (v === HINT_KEEP) alpha[i] = 255;
    else if (v === HINT_DROP) alpha[i] = 0;
  }
}

/** Hint plane with its dimensions — strokes are painted at the modal's
  * PREVIEW resolution and resampled to the output size on Apply. */
export interface HintPlane {
  data: Uint8Array;
  w: number;
  h: number;
}

/** Nearest-neighbour resample of a hint plane to dw×dh. Hints are
  * categorical (KEEP/DROP/NONE) so nearest is the only sensible filter;
  * the blocky edge it leaves is exactly what the guided filter refines
  * away afterwards. Returns the same array when dims already match. */
export function resampleHints(src: HintPlane, dw: number, dh: number): Uint8Array {
  if (src.w === dw && src.h === dh) return src.data;
  const out = new Uint8Array(dw * dh);
  const sx = src.w / dw;
  const sy = src.h / dh;
  for (let y = 0; y < dh; y++) {
    const syi = Math.min(src.h - 1, Math.floor((y + 0.5) * sy));
    const srow = syi * src.w;
    const drow = y * dw;
    for (let x = 0; x < dw; x++) {
      out[drow + x] = src.data[srow + Math.min(src.w - 1, Math.floor((x + 0.5) * sx))];
    }
  }
  return out;
}

/** True when any pixel carries a hint. */
export function hasHints(hints: Uint8Array): boolean {
  for (let i = 0; i < hints.length; i++) if (hints[i] !== HINT_NONE) return true;
  return false;
}
