import { describe, expect, it } from "vitest";
import { scrimGradient, type ScrimSpec } from "../src/scrim";

describe("scrimGradient", () => {
  it("anchors the opaque end at the named edge (runs toward the opposite side)", () => {
    expect(scrimGradient({ edge: "bottom", color: "#000000", strength: 1 })).toContain("to top");
    expect(scrimGradient({ edge: "top", color: "#000000", strength: 1 })).toContain("to bottom");
    expect(scrimGradient({ edge: "left", color: "#000000", strength: 1 })).toContain("to right");
    expect(scrimGradient({ edge: "right", color: "#000000", strength: 1 })).toContain("to left");
  });

  it("always fades to transparent so the image shows through", () => {
    for (const edge of ["bottom", "top", "left", "right", "radial"] as const) {
      expect(scrimGradient({ edge, color: "#123456", strength: 0.8 })).toContain("transparent 100%");
    }
  });

  it("converts hex + strength into the opaque-end rgba alpha", () => {
    // #ff8800 → rgb(255,136,0); strength 0.5 is the near-stop alpha.
    expect(scrimGradient({ edge: "bottom", color: "#ff8800", strength: 0.5 }))
      .toContain("rgba(255,136,0,0.5) 0%");
  });

  it("uses the second colour as the mid stop when set, else the primary", () => {
    const one: ScrimSpec = { edge: "bottom", color: "#ffffff", strength: 1 };
    // single colour: mid stop is the primary at a reduced alpha
    expect(scrimGradient(one)).toContain("rgba(255,255,255,0.5) 55%");
    const two: ScrimSpec = { ...one, color2: "#000000" };
    expect(scrimGradient(two)).toContain("rgba(0,0,0,0.6) 55%");
  });

  it("emits a radial gradient for the radial edge (opaque centre)", () => {
    const g = scrimGradient({ edge: "radial", color: "#000000", strength: 0.7 });
    expect(g.startsWith("radial-gradient(")).toBe(true);
    expect(g).toContain("ellipse at center");
    expect(g).toContain("rgba(0,0,0,0.7) 0%"); // opaque at the centre
    expect(g).toContain("transparent 100%");   // clear at the rim
  });

  it("reverses the stops for radial-edge (clear centre, opaque rim)", () => {
    const g = scrimGradient({ edge: "radial-edge", color: "#000000", strength: 0.7 });
    expect(g.startsWith("radial-gradient(")).toBe(true);
    expect(g).toContain("transparent 0%");      // clear at the centre
    expect(g).toContain("rgba(0,0,0,0.7) 100%"); // opaque at the rim
  });

  it("clamps strength to 0..1 and tolerates short / non-hex colours", () => {
    expect(scrimGradient({ edge: "bottom", color: "#000", strength: 5 })).toContain("rgba(0,0,0,1) 0%");
    expect(scrimGradient({ edge: "bottom", color: "rebeccapurple", strength: -1 })).toContain("rgba(0,0,0,0) 0%");
  });
});
