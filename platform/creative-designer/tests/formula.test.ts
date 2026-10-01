import { describe, expect, it } from "vitest";
import { finishSvg } from "../src/formula";

describe("finishSvg", () => {
  const raw =
    '<svg xmlns="http://www.w3.org/2000/svg" width="8.1ex" height="2.3ex" style="vertical-align: -0.5ex;" viewBox="0 -800 3500 1000">' +
    '<g stroke="currentColor" fill="currentColor"><path d="M0 0"/></g></svg>';

  it("bakes the ink color in and sizes from the viewBox", () => {
    const out = finishSvg(raw, "#ff0000");
    expect(out.svg).not.toContain("currentColor");
    expect(out.svg).toContain('fill="#ff0000"');
    expect(out.svg).not.toContain("style=");
    // 3500 × 1000 MathJax units = 3.5em × 1em at 32px/em
    expect(out).toMatchObject({ width: 112, height: 32 });
    expect(out.svg).toContain('width="112" height="32"');
  });

  it("rejects output without a viewBox", () => {
    expect(() => finishSvg("<svg></svg>", "#000")).toThrow();
  });
});
