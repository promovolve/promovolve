import { describe, it, expect } from "vitest";
import {
  isFoldout, hingeOpen, hingeAngle, springStep, springSettled,
  releaseTarget, isTap, posterSize, liftT,
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
    expect(hingeAngle(0, 0)).toBe(-178); expect(hingeAngle(0, 1)).toBe(180); expect(hingeAngle(2, 0)).toBeCloseTo(0);
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
  it("poster size / lift", () => {
    const a = posterSize(390, 844, 97);
    expect(a.w).toBeCloseTo(378.3, 1); expect(a.h).toBeCloseTo(672.5, 1);
    expect(posterSize(1280, 800, 88).h).toBeCloseTo(704); expect(posterSize(4000, 4000, 88).h).toBe(900);
    expect(liftT(0)).toBe(0); expect(liftT(0.7)).toBe(1);
  });
});
