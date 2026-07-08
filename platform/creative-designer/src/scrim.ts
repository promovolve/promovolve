// Gradient-scrim composition. A scrim is a rect whose `fill` is a
// CSS gradient built from a small param set (edge / colour(s) / strength)
// so the author edits intent — "darken the bottom" — not raw gradient
// stops. The banner renderer is oblivious: it paints `fill` like any
// rect, so the gradient string produced here is the only thing that has
// to be correct. See RectItem.scrim in @banner/types.

import type { RectItem } from "./types";

export type ScrimSpec = NonNullable<RectItem["scrim"]>;
export type ScrimEdge = ScrimSpec["edge"];

export const SCRIM_EDGES: ScrimEdge[] = ["bottom", "top", "left", "right", "radial", "radial-edge"];

// Neutral default — dominantColor isn't plumbed to the designer yet, so a
// new scrim starts as a plain dark legibility wash; the author tints it
// via the picker / brand-kit chips.
export const SCRIM_DEFAULT_COLOR = "#000000";
export const SCRIM_DEFAULT_STRENGTH = 0.7;

function clamp01(v: number): number {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

// "#rgb" / "#rrggbb" → "rgba(r,g,b,a)". Non-hex input (already an rgb/
// named colour) falls back to black so a scrim never renders invisible.
function rgba(hex: string, alpha: number): string {
  const a = clamp01(alpha);
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return `rgba(0,0,0,${a})`;
  let h = m[1]!;
  if (h.length === 3) h = h.split("").map((c) => c + c).join("");
  const n = parseInt(h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

// The opaque end sits at `edge`; CSS gradients name the direction they
// run TOWARD, so a bottom-anchored scrim runs "to top".
const TOWARD: Record<Exclude<ScrimEdge, "radial" | "radial-edge">, string> = {
  bottom: "to top",
  top: "to bottom",
  left: "to right",
  right: "to left",
};

/** Compose the CSS gradient for a scrim. The transparent end always lets
 *  the image underneath show through; a second colour, when set, is the
 *  middle stop. "radial" is opaque at the centre; "radial-edge" reverses
 *  it (clear centre, opaque rim). */
export function scrimGradient(spec: ScrimSpec): string {
  const s = clamp01(spec.strength);
  const near = rgba(spec.color, s);
  const mid = spec.color2
    ? rgba(spec.color2, s * 0.6)
    : rgba(spec.color, s * 0.5);
  if (spec.edge === "radial") {
    return `radial-gradient(ellipse at center, ${near} 0%, ${mid} 55%, transparent 100%)`;
  }
  if (spec.edge === "radial-edge") {
    return `radial-gradient(ellipse at center, transparent 0%, ${mid} 55%, ${near} 100%)`;
  }
  return `linear-gradient(${TOWARD[spec.edge]}, ${near} 0%, ${mid} 55%, transparent 100%)`;
}
