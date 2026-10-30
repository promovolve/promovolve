import { describe, expect, it } from "vitest";
import { lowContrastRatio } from "../src/color-contrast";

describe("lowContrastRatio", () => {
  it("returns the ratio for a readable-colour pair below AA", () => {
    expect(lowContrastRatio("#ffffff", "#ffffff")).toBe(1);
    expect(lowContrastRatio("#777777", "#888888")).toBeLessThan(4.5);
  });

  it("is null when the pair passes AA", () => {
    expect(lowContrastRatio("#000000", "#ffffff")).toBeNull();
  });

  it("is null when either colour can't be read", () => {
    expect(lowContrastRatio("#ffffff", "linear-gradient(#000, #fff)")).toBeNull();
    expect(lowContrastRatio("red", "#ffffff")).toBeNull();
    expect(lowContrastRatio(undefined, "#ffffff")).toBeNull();
    expect(lowContrastRatio("#ffffff", undefined)).toBeNull();
  });
});
