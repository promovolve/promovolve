import { describe, expect, it } from "vitest";
import { computeRotation } from "../src/interaction/rotate";

const center = { x: 100, y: 100 };

describe("computeRotation", () => {
  it("no movement returns original rotation", () => {
    expect(computeRotation(30, center, { x: 200, y: 100 }, { x: 200, y: 100 })).toBe(30);
  });

  it("rotating 90° clockwise from 0 gives 90", () => {
    // start at 0°  (east of center) = (200, 100)
    // end at 90°   (south of center, pointing down in screen coords) = (100, 200)
    expect(computeRotation(0, center, { x: 200, y: 100 }, { x: 100, y: 200 })).toBe(90);
  });

  it("rotating counter-clockwise wraps into negatives", () => {
    // start east, end north (screen y grows down, so north is y decreasing)
    expect(computeRotation(0, center, { x: 200, y: 100 }, { x: 100, y: 0 })).toBe(-90);
  });

  it("normalizes to (-180, 180]", () => {
    // Large accumulator still lands in range.
    const result = computeRotation(170, center, { x: 200, y: 100 }, { x: 100, y: 200 });
    // 170 + 90 = 260 → wraps to -100
    expect(result).toBe(-100);
  });

  it("snap (Shift) rounds to the nearest 15°", () => {
    const at = (deg: number) => ({ x: 100 + 100 * Math.cos((deg * Math.PI) / 180), y: 100 + 100 * Math.sin((deg * Math.PI) / 180) });
    // ~88° around → snaps up to 90; un-snapped stays at the whole degree.
    expect(computeRotation(0, center, { x: 200, y: 100 }, at(88), true)).toBe(90);
    expect(computeRotation(0, center, { x: 200, y: 100 }, at(88), false)).toBe(88);
    // ~80° → nearest 15° multiple is 75 (not 90).
    expect(computeRotation(0, center, { x: 200, y: 100 }, at(80), true)).toBe(75);
  });
});
