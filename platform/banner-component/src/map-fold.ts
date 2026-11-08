// Map (gatefold poster) format: pure fold maths. No DOM.
export const MAP_MAX = 2; // 3 panels -> progress 0..2
export const MAP = {
  overlap: 0.45, omega: 8, coverRest: 178, innerRest: 180,
  lift: 0.7, flickS: 0.15, restMs: 80, dtMax: 0.032, perspective: 1400, slop: 12,
} as const;

const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x));
const smooth = (t: number) => t * t * (3 - 2 * t);

export function isMap(cfg: { format?: unknown }): boolean {
  return cfg.format === "map";
}

export function hingeOpen(progress: number, i: number, max = MAP_MAX, overlap = MAP.overlap): number {
  const L = Math.min(max, 1 + overlap);
  const gap = max > 1 ? (max - L) / (max - 1) : 0;
  return smooth(clamp((progress - i * gap) / L, 0, 1));
}

// rotateX degrees: negative folds away from the viewer for a top-edge hinge.
export function hingeAngle(progress: number, i: number): number {
  const rest = i === 0 ? MAP.coverRest : MAP.innerRest;
  const sign = i % 2 === 0 ? -1 : 1;
  return sign * rest * (1 - hingeOpen(progress, i));
}

type Spring = { p: number; v: number };

export function springStep(s: Spring, target: number, dt: number): Spring {
  const d = Math.min(dt, MAP.dtMax);
  const w = MAP.omega;
  const v = s.v + (w * w * (target - s.p) - 2 * w * s.v) * d;
  const p = s.p + v * d;
  if (p <= 0) return { p: 0, v: 0 };
  if (p >= MAP_MAX) return { p: MAP_MAX, v: 0 };
  return { p, v };
}

export function springSettled(s: Spring, target: number): boolean {
  return Math.abs(s.p - target) < 0.002 && Math.abs(s.v) < 0.01;
}

export function releaseTarget(p: number, v: number, msSinceLastMove: number): "open" | "closed" {
  const vv = msSinceLastMove > MAP.restMs ? 0 : v;
  return p + vv * MAP.flickS < MAP_MAX / 2 ? "closed" : "open";
}

export function isTap(dx: number, dy: number): boolean {
  return Math.hypot(dx, dy) <= MAP.slop;
}

// Mirrors sheetSizeFor("9/16", fit) in px.
export function posterSize(vw: number, vh: number, fitPct: number): { w: number; h: number } {
  const h = Math.min(900, (vh * fitPct) / 100, (((vw * fitPct) / 100) * 16) / 9);
  return { w: (h * 9) / 16, h };
}

export function liftT(progress: number): number {
  return smooth(clamp(progress / MAP.lift, 0, 1));
}
