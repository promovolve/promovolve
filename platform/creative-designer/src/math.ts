// Tiny shared math helpers. Pure, deterministic, easy to test.

export function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

export function round1(v: number): number {
  return Math.round(v * 10) / 10;
}

// Normalize a rotation reading to (-180, 180]. `Math.atan2` already returns
// that range, but when callers accumulate deltas (origRot + delta) the result
// can drift outside — this snaps it back.
export function wrapDegrees(deg: number): number {
  let r = deg;
  while (r > 180) r -= 360;
  while (r <= -180) r += 360;
  return r;
}

// "16/9" → { w: 16, h: 9 }. Accepts any "<num>/<num>" form the banner
// component also accepts (e.g., "300/250").
export function parseAspect(aspect: string): { w: number; h: number } {
  const [w, h] = aspect.split("/").map((n) => Number(n));
  if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) {
    throw new Error(`Invalid aspect "${aspect}" — expected "<num>/<num>"`);
  }
  return { w, h };
}
