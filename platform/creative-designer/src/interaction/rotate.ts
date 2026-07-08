// Pure rotation math. The drag starts with the pointer at some angle to
// the item's center; subsequent pointer positions yield a delta angle
// that we add to the original rotation. Normalize to (-180, 180] so UI
// inputs can bind directly to a number without drift.

import { wrapDegrees } from "../math";

export interface Point {
  x: number;
  y: number;
}

function angleTo(center: Point, p: Point): number {
  return (Math.atan2(p.y - center.y, p.x - center.x) * 180) / Math.PI;
}

// `snap` (Shift held) rounds to 15° increments — the standard
// constrained rotate of every drawing tool; otherwise round to 1°.
export function computeRotation(
  origRotation: number,
  center: Point,
  start: Point,
  current: Point,
  snap = false,
): number {
  const delta = angleTo(center, current) - angleTo(center, start);
  const step = snap ? 15 : 1;
  return wrapDegrees(Math.round((origRotation + delta) / step) * step);
}
