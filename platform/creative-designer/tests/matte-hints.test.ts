// Restore/erase brush rasterizer + hint application.

import { describe, expect, it } from "vitest";
import { applyHints, hasHints, HINT_DROP, HINT_KEEP, HINT_NONE, paintDisc, paintStroke } from "../src/matte-hints";

describe("paintDisc", () => {
  it("fills a disc and clips at the edges", () => {
    const w = 10, h = 10;
    const hints = new Uint8Array(w * h);
    paintDisc(hints, w, h, 0, 0, 2, HINT_KEEP); // corner-centred → quarter disc
    expect(hints[0]).toBe(HINT_KEEP);
    expect(hints[2]).toBe(HINT_KEEP);        // (2,0) on the rim
    expect(hints[3]).toBe(HINT_NONE);        // (3,0) outside
    expect(hints[2 * w + 2]).toBe(HINT_NONE); // (2,2) is √8 > 2 away
  });
});

describe("paintStroke", () => {
  it("leaves no gaps along a fast drag", () => {
    const w = 60, h = 10;
    const hints = new Uint8Array(w * h);
    paintStroke(hints, w, h, 5, 5, 55, 5, 2, HINT_DROP);
    for (let x = 5; x <= 55; x++) expect(hints[5 * w + x]).toBe(HINT_DROP);
    expect(hints[5 * w + 2]).toBe(HINT_NONE);
    expect(hints[5 * w + 58]).toBe(HINT_NONE);
  });
});

describe("applyHints", () => {
  it("forces KEEP→255 and DROP→0, leaves NONE alone", () => {
    const alpha = new Uint8ClampedArray([10, 20, 30]);
    applyHints(alpha, new Uint8Array([HINT_KEEP, HINT_NONE, HINT_DROP]));
    expect(Array.from(alpha)).toEqual([255, 20, 0]);
  });
});

describe("hasHints", () => {
  it("detects any painted pixel", () => {
    const hints = new Uint8Array(4);
    expect(hasHints(hints)).toBe(false);
    hints[3] = HINT_KEEP;
    expect(hasHints(hints)).toBe(true);
  });
});
