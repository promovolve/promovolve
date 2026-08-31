// maskToAlpha — the pure half of remove-background. Pins the two
// behaviours the modal relies on: a flat (no-subject) mask degrades to
// fully opaque (never a blank image), and the edge curve maps the
// normalized mask through a firm smoothstep rather than a linear ramp.

import { describe, expect, it } from "vitest";
import { cutoutFilename, EDGE_HI, EDGE_LO, maskToAlpha, opaqueFraction, outputSize } from "../src/remove-bg";

describe("maskToAlpha", () => {
  it("flat mask → fully opaque (failed detection is a no-op, not a blank)", () => {
    const a = maskToAlpha(new Float32Array([0.42, 0.42, 0.42, 0.42]));
    expect(Array.from(a)).toEqual([255, 255, 255, 255]);
  });

  it("min-max normalizes: the extremes always land on 0 and 255", () => {
    // Low-contrast raw mask (0.2..0.4) still spans the full range.
    const a = maskToAlpha(new Float32Array([0.2, 0.3, 0.4]));
    expect(a[0]).toBe(0);
    expect(a[2]).toBe(255);
  });

  it("clamps the edge band: below EDGE_LO → 0, above EDGE_HI → 255", () => {
    const a = maskToAlpha(new Float32Array([0, EDGE_LO - 0.05, EDGE_HI + 0.05, 1]));
    expect(a[1]).toBe(0);
    expect(a[2]).toBe(255);
  });

  it("is a smoothstep across the band (steeper than linear mid-band)", () => {
    const mid = (EDGE_LO + EDGE_HI) / 2;
    const q = EDGE_LO + (EDGE_HI - EDGE_LO) * 0.25;
    const a = maskToAlpha(new Float32Array([0, q, mid, 1]));
    expect(a[2]).toBe(128); // midpoint is exactly half
    // Quarter point: linear would be 64; smoothstep(0.25) = 0.15625 → 40.
    expect(a[1]).toBeLessThan(50);
  });
});

describe("opaqueFraction", () => {
  it("counts ≥128 as opaque", () => {
    expect(opaqueFraction(new Uint8ClampedArray([0, 127, 128, 255]))).toBe(0.5);
  });
});

describe("outputSize", () => {
  it("never upscales and caps the long edge", () => {
    expect(outputSize(800, 600)).toEqual({ w: 800, h: 600 });
    expect(outputSize(4000, 1000)).toEqual({ w: 2000, h: 500 });
  });
});

describe("cutoutFilename", () => {
  it("derives from the R2 basename", () => {
    expect(cutoutFilename("https://cdn.example/assets/abc123.webp")).toBe("abc123-cutout.webp");
    expect(cutoutFilename("https://cdn.example/assets/abc123.webp?x=1")).toBe("abc123-cutout.webp");
  });
  it("falls back for data URLs", () => {
    expect(cutoutFilename("data:image/png;base64,AAAA")).toBe("image-cutout.webp");
  });
});
