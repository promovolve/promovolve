import { describe, expect, it } from "vitest";
import { computeResize } from "../src/interaction/resize";

const orig = { left: 10, top: 20, width: 50, height: 30 };

describe("computeResize", () => {
  it("east handle extends width only", () => {
    const next = computeResize("e", orig, { dx: 10, dy: 0 });
    expect(next).toEqual({ left: 10, top: 20, width: 60, height: 30 });
  });

  it("south handle extends height only", () => {
    const next = computeResize("s", orig, { dx: 0, dy: 5 });
    expect(next).toEqual({ left: 10, top: 20, width: 50, height: 35 });
  });

  it("west handle shrinks width and shifts left", () => {
    // dx = +10 means pointer moved right → west edge narrows → width shrinks.
    const next = computeResize("w", orig, { dx: 10, dy: 0 });
    expect(next).toEqual({ left: 20, top: 20, width: 40, height: 30 });
  });

  it("north handle shrinks height and shifts top", () => {
    const next = computeResize("n", orig, { dx: 0, dy: 10 });
    expect(next).toEqual({ left: 10, top: 30, width: 50, height: 20 });
  });

  it("corner handles combine two axes", () => {
    const next = computeResize("nw", orig, { dx: -5, dy: -5 });
    // dx = -5 → west edge moves left → width grows; left decreases.
    // dy = -5 → north edge moves up  → height grows; top decreases.
    expect(next).toEqual({ left: 5, top: 15, width: 55, height: 35 });
  });

  it("enforces the collapse floor of 0.2%", () => {
    // The floor only prevents collapsing/inverting the dragged axis —
    // it must stay below the Line tool's 0.4% thickness, or grabbing a
    // corner handle with zero vertical movement fattens a line.
    const next = computeResize("e", orig, { dx: -200, dy: 0 });
    expect(next.width).toBe(0.2);
  });

  it("collapse floor applies before left shift on west handle", () => {
    const next = computeResize("w", orig, { dx: 200, dy: 0 });
    expect(next.width).toBe(0.2);
    expect(next.left).toBe(orig.left + orig.width - 0.2); // east edge stays put
  });

  it("never moves an axis the handle does not own (thin-line invariant)", () => {
    // SE-corner grab with no vertical travel: a 0.4%-tall line must
    // stay 0.4% — the old MIN_SIZE=2 floored it to 2% on contact.
    const line = { left: 25, top: 50, width: 50, height: 0.4 };
    const next = computeResize("se", line, { dx: 10, dy: 0 });
    expect(next.height).toBe(0.4);
    expect(next.width).toBe(60);
  });

  it("returns a fresh object (no mutation)", () => {
    const before = { ...orig };
    computeResize("se", orig, { dx: 1, dy: 1 });
    expect(orig).toEqual(before);
  });
});
