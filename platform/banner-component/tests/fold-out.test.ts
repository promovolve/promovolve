import { describe, it, expect } from "vitest";
import {
  isFoldout, hingeOpen, springStep, fallStep, springSettled,
  releaseTarget, isTap, posterSize, shuffledCells, dissolveClip,
} from "../src/fold-out";

describe("fold-out maths", () => {
  it("isFoldout", () => {
    expect(isFoldout({ format: "foldout" })).toBe(true);
    expect(isFoldout({})).toBe(false);
    expect(isFoldout({ format: "Fold-out" })).toBe(false);
  });
  it("hinges", () => {
    expect(hingeOpen(0, 0)).toBe(0); expect(hingeOpen(2, 1)).toBe(1);
    expect(hingeOpen(0.5, 1)).toBe(0);
    expect(hingeOpen(1, 0)).toBeGreaterThan(hingeOpen(1, 1));
    expect(hingeOpen(1, 1)).toBeGreaterThan(0);
  });
  it("spring", () => {
    let s = { p: 0, v: 0 }; let max = 0;
    for (let k = 0; k < 100; k++) { s = springStep(s, 2, 0.016); max = Math.max(max, s.p); }
    expect(springSettled(s, 2)).toBe(true); expect(max).toBeLessThanOrEqual(2);
    expect(springStep({ p: 0, v: -5 }, 0, 0.016)).toEqual({ p: 0, v: 0 });
    expect(springStep({ p: 1, v: 0 }, 2, 1).p).toBeCloseTo(springStep({ p: 1, v: 0 }, 2, 0.032).p);
  });
  it("settle thresholds", () => {
    expect(springSettled({ p: 1.9995, v: 0 }, 2)).toBe(true);
    expect(springSettled({ p: 1.998, v: 0 }, 2)).toBe(false);
    expect(springSettled({ p: 2, v: 0.02 }, 2)).toBe(false);
  });
  it("release / tap", () => {
    expect(releaseTarget(0.9, 0, 10)).toBe("closed");
    expect(releaseTarget(1.1, 0, 10)).toBe("open");
    expect(releaseTarget(1.5, -6, 10)).toBe("closed");
    expect(releaseTarget(0.9, -6, 120)).toBe("closed"); expect(releaseTarget(1.5, -6, 120)).toBe("open");
    expect(isTap(8, 8)).toBe(true); expect(isTap(10, 10)).toBe(false);
  });
  it("poster size", () => {
    const a = posterSize(390, 844, 97);
    expect(a.w).toBeCloseTo(378.3, 1); expect(a.h).toBeCloseTo(672.5, 1);
    expect(posterSize(1280, 800, 88).h).toBeCloseTo(704); expect(posterSize(4000, 4000, 88).h).toBe(900);
  });
});

describe("pixel dissolve", () => {
  it("shuffledCells is a permutation", () => {
    const a = shuffledCells(50);
    expect([...a].sort((x, y) => x - y)).toEqual(Array.from({ length: 50 }, (_, i) => i));
  });
  it("hidden at 0, unclipped when all cells are in, one square per revealed cell", () => {
    const order = shuffledCells(24);
    expect(dissolveClip(order, 0, 4, 0, 0, 10)).toBe("inset(50%)");
    expect(dissolveClip(order, 24, 4, 0, 0, 10)).toBe("none");
    const d = dissolveClip([5, 0], 1, 4, 100, 200, 10); // cell 5 = col 1, row 1
    expect(d).toBe('path("M110.0 210.0h11v11h-11Z")');
    expect(dissolveClip(order, 7, 4, 0, 0, 10).match(/Z/g)).toHaveLength(7);
  });
});

describe("gravity fall", () => {
  // paper falling open: slow start, fastest at the bottom, a small swing past flat, then rest
  it("falls open in ~0.3-0.45 s, swings a few degrees past flat, settles under 0.9 s", () => {
    let th = 178, w = -60, t = 0, firstZero = 0, minTh = 0, settled = 0, peakAt = 0, peakW = 0;
    const h = 1 / 240;
    while (t < 2) {
      [th, w] = fallStep(th, w, h);
      t += h;
      if (!firstZero && th <= 0) firstZero = t;
      if (!firstZero && Math.abs(w) > peakW) { peakW = Math.abs(w); peakAt = th; }
      if (firstZero) minTh = Math.min(minTh, th);
      if (firstZero && !settled && Math.abs(th) < 0.3 && Math.abs(w) < 3) settled = t;
    }
    expect(firstZero).toBeGreaterThan(0.3); expect(firstZero).toBeLessThan(0.45);
    expect(peakAt).toBeLessThan(90); // fastest in the lower half of the swing; a spring peaks at the start
    expect(-minTh).toBeGreaterThan(2); expect(-minTh).toBeLessThan(12);
    expect(settled).toBeLessThan(0.9);
  });
});
