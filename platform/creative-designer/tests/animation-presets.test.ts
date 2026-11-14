import { describe, expect, it } from "vitest";
import { ANIMATION_PRESETS, EXIT_PRESETS, easingIdFor, exitPresetById, presetById, presetDistance, withDistance } from "../src/animation-presets";

describe("animation presets", () => {
  it("every preset materializes a non-empty entrance with a duration", () => {
    for (const p of ANIMATION_PRESETS) {
      expect(Object.keys(p.from).length).toBeGreaterThan(0);
      expect(p.from.duration).toBeGreaterThan(0);
      expect(presetById(p.id)).toBe(p);
    }
  });

  it("withDistance rescales offsets but preserves each axis direction", () => {
    const up = presetById("fade-up")!.from;    // dy: +5
    const left = presetById("fade-left")!.from; // dx: -5
    expect(withDistance(up, 12).dy).toBe(12);
    expect(withDistance(left, 12).dx).toBe(-12);
    // Distance reads back the dominant magnitude.
    expect(presetDistance(withDistance(up, 12))).toBe(12);
  });

  it("withDistance leaves untouched axes and non-positional fields alone", () => {
    const rise = presetById("rise")!.from;
    const scaled = withDistance(rise, 8);
    expect(scaled.scale).toBe(rise.scale);
    expect(scaled.opacity).toBe(rise.opacity);
    expect(scaled.dx).toBe(rise.dx); // undefined stays undefined
  });

  it("exit presets never target an ABSOLUTE position — the drag-detach trap that killed the raw editor (dx/dy offsets are fine)", () => {
    for (const p of EXIT_PRESETS) {
      expect(p.to.left).toBeUndefined();
      expect(p.to.top).toBeUndefined();
      expect(p.to.rotation).toBeUndefined();
      expect(p.to.duration).toBeGreaterThan(0);
      expect(p.to.delay).toBeGreaterThanOrEqual(0);
      expect(exitPresetById(p.id)).toBe(p);
    }
  });

  it("easing menu round-trips: css → id, unknown css → smooth", () => {
    expect(easingIdFor(undefined)).toBe("smooth");
    expect(easingIdFor("linear")).toBe("linear");
    expect(easingIdFor("cubic-bezier(0.5,0,0.2,1)")).toBe("snappy");
    expect(easingIdFor("steps(4)")).toBe("smooth");
  });
});

describe("preset labels are translated", () => {
  it("every entrance, exit and easing label has a Japanese entry", async () => {
    const { JA } = await import("../src/i18n/ja");
    const { EASING_MENU } = await import("../src/animation-presets");
    const labels = [...ANIMATION_PRESETS, ...EXIT_PRESETS, ...EASING_MENU].map((p) => p.label);
    expect(labels.filter((l) => !(l in JA))).toEqual([]);
    expect(JA["Fade in"]).toBe("フェードイン");
    expect(JA["From the left"]).toBe("左から");
  });
});
