// Guided-filter matte refinement + foreground decontamination, on tiny
// synthetic images. Pins: (1) constant alpha is a fixed point, (2) a
// blurred alpha edge snaps to the guide's sharp colour edge, (3) an edge
// pixel contaminated by the background colour is pulled toward the
// foreground colour, (4) opaque pixels are never recoloured.

import { describe, expect, it } from "vitest";
import { boxBlur, refineMatte } from "../src/matte-refine";

// Vertical colour edge at x = W/2: red foreground (left) on green
// background (right). Alpha is a WIDE linear ramp across the edge.
function scene(W: number, H: number, ramp: number): { rgba: Uint8ClampedArray; alpha: Uint8ClampedArray } {
  const rgba = new Uint8ClampedArray(W * H * 4);
  const alpha = new Uint8ClampedArray(W * H);
  const edge = W / 2;
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const i = y * W + x;
      const fg = x < edge;
      rgba[i * 4] = fg ? 220 : 30;
      rgba[i * 4 + 1] = fg ? 40 : 200;
      rgba[i * 4 + 2] = 40;
      rgba[i * 4 + 3] = 255;
      const t = (edge - x) / ramp + 0.5; // 1 deep inside fg, 0 deep in bg
      alpha[i] = Math.round(255 * Math.max(0, Math.min(1, t)));
    }
  }
  return { rgba, alpha };
}

const transitionWidth = (alpha: Uint8ClampedArray, W: number, row: number): number => {
  let lo = -1, hi = -1;
  for (let x = 0; x < W; x++) {
    const a = alpha[row * W + x];
    if (a < 230 && lo < 0) lo = x;
    if (a < 25) { hi = x; break; }
  }
  return hi - lo;
};

describe("boxBlur", () => {
  it("constant in → constant out (edge windows are normalized)", () => {
    const w = 7, h = 5;
    const src = new Float32Array(w * h).fill(0.4);
    const dst = new Float32Array(w * h), tmp = new Float32Array(w * h);
    boxBlur(src, w, h, 2, dst, tmp);
    for (const v of dst) expect(v).toBeCloseTo(0.4, 6);
  });
  it("averages a single impulse over the window", () => {
    const w = 5, h = 1;
    const src = new Float32Array([0, 0, 1, 0, 0]);
    const dst = new Float32Array(5), tmp = new Float32Array(5);
    boxBlur(src, w, h, 1, dst, tmp);
    expect(Array.from(dst).map((v) => +v.toFixed(3))).toEqual([0, 0.333, 0.333, 0.333, 0]);
  });
});

describe("refineMatte", () => {
  it("leaves a fully opaque matte and its colours untouched", () => {
    const W = 32, H = 16;
    const { rgba } = scene(W, H, 1);
    const before = Uint8ClampedArray.from(rgba);
    const alpha = new Uint8ClampedArray(W * H).fill(255);
    const out = refineMatte(rgba, W, H, alpha, { workEdge: 32 });
    expect(Array.from(out).every((a) => a === 255)).toBe(true);
    expect(Array.from(rgba)).toEqual(Array.from(before));
  });

  it("snaps a wide alpha ramp to the guide's sharp edge", () => {
    const W = 64, H = 16;
    const { rgba, alpha } = scene(W, H, 24); // 24px ramp across a 1px colour edge
    const before = transitionWidth(alpha, W, 8);
    const out = refineMatte(rgba, W, H, alpha, { workEdge: 64, radius: 6 });
    const after = transitionWidth(out, W, 8);
    expect(before).toBeGreaterThan(15);
    expect(after).toBeLessThan(before / 2);
    // Deep inside / outside stay put.
    expect(out[8 * W + 2]).toBe(255);
    expect(out[8 * W + W - 3]).toBe(0);
  });

  it("decontaminates edge pixels toward the foreground colour", () => {
    const W = 64, H = 16;
    const { rgba, alpha } = scene(W, H, 8);
    // Paint the semi-transparent band with the observed mix: half red, half green.
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        const i = y * W + x;
        const a = alpha[i] / 255;
        if (a > 0 && a < 1) {
          rgba[i * 4] = Math.round(220 * a + 30 * (1 - a));
          rgba[i * 4 + 1] = Math.round(40 * a + 200 * (1 - a));
        }
      }
    }
    const probe = 8 * W + (W / 2 - 2); // inside the band, fg side
    const greenBefore = rgba[probe * 4 + 1];
    const out = refineMatte(rgba, W, H, alpha, { workEdge: 64, radius: 4 });
    const a = out[probe] / 255;
    if (a > 0 && a < 0.995) {
      expect(rgba[probe * 4 + 1]).toBeLessThan(greenBefore); // less green
      expect(rgba[probe * 4]).toBeGreaterThan(150);           // more red
    }
    // Opaque interior pixel is never recoloured.
    const deep = 8 * W + 2;
    expect(rgba[deep * 4]).toBe(220);
    expect(rgba[deep * 4 + 1]).toBe(40);
  });
});