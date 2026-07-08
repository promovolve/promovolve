// sheetSizeFor — aspect-fit sizing for the floating reader sheet.
// The invariants: exactly ONE driven dimension is returned (aspect-
// ratio derives the other; setting both squashes), portrait drives
// height, landscape/square drives width, and the cq fallback terms
// keep the sheet inside 88% of the reader on BOTH axes.

import { describe, expect, it } from "vitest";
import { sheetFitPct, sheetSizeFor } from "../src/utils";

describe("sheetSizeFor", () => {
  it("portrait drives height: 88% of height OR the width-fit equivalent", () => {
    expect(sheetSizeFor("9/16")).toEqual({ height: "min(900px, 88cqh, 156.44cqw)" });
  });

  it("landscape drives width: 88% of width OR the height-fit equivalent", () => {
    expect(sheetSizeFor("16/9")).toEqual({ width: "min(1200px, 88cqw, 156.44cqh)" });
  });

  it("square counts as landscape (width-driven, symmetric fit)", () => {
    expect(sheetSizeFor("1/1")).toEqual({ width: "min(1200px, 88cqw, 88.00cqh)" });
  });

  it("falls back to 16:9 on an unparseable aspect", () => {
    expect(sheetSizeFor("banana")).toEqual({ width: "min(1200px, 88cqw, 156.44cqh)" });
  });

  it("never returns both dimensions", () => {
    for (const a of ["9/16", "16/9", "300/600", "970/250"]) {
      const size = sheetSizeFor(a) as Record<string, string>;
      expect(Object.keys(size)).toHaveLength(1);
    }
  });

  it("fit percentage scales both cq terms (phones run near full-bleed)", () => {
    expect(sheetSizeFor("9/16", 97)).toEqual({ height: "min(900px, 97cqh, 172.44cqw)" });
    expect(sheetFitPct(true)).toBe(97);
    expect(sheetFitPct(false)).toBe(88);
  });
});
