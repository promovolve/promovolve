import { describe, expect, it } from "vitest";
import { clamp, parseAspect, round1, wrapDegrees } from "../src/math";

describe("clamp", () => {
  it("passes through values in range", () => {
    expect(clamp(5, 0, 10)).toBe(5);
  });
  it("clamps low", () => {
    expect(clamp(-1, 0, 10)).toBe(0);
  });
  it("clamps high", () => {
    expect(clamp(11, 0, 10)).toBe(10);
  });
});

describe("round1", () => {
  it("rounds to 1 decimal", () => {
    expect(round1(1.234)).toBe(1.2);
    expect(round1(1.25)).toBe(1.3);
  });
  it("handles negatives", () => {
    expect(round1(-1.78)).toBe(-1.8);
  });
});

describe("wrapDegrees", () => {
  it("leaves values in range alone", () => {
    expect(wrapDegrees(45)).toBe(45);
    expect(wrapDegrees(-45)).toBe(-45);
    expect(wrapDegrees(180)).toBe(180);
  });
  it("wraps > 180 down", () => {
    expect(wrapDegrees(200)).toBe(-160);
    expect(wrapDegrees(360)).toBe(0);
    expect(wrapDegrees(540)).toBe(180);
  });
  it("wraps ≤ -180 up", () => {
    expect(wrapDegrees(-200)).toBe(160);
    expect(wrapDegrees(-360)).toBe(0);
  });
});

describe("parseAspect", () => {
  it("parses ratios", () => {
    expect(parseAspect("16/9")).toEqual({ w: 16, h: 9 });
    expect(parseAspect("300/250")).toEqual({ w: 300, h: 250 });
  });
  it("rejects malformed", () => {
    expect(() => parseAspect("16")).toThrow();
    expect(() => parseAspect("0/1")).toThrow();
    expect(() => parseAspect("abc/def")).toThrow();
  });
});
