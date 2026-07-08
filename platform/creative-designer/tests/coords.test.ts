import { describe, expect, it } from "vitest";
import { clientToPct, pctToClient } from "../src/coords";

const rect = { left: 100, top: 50, width: 500, height: 250 };

describe("clientToPct", () => {
  it("returns (0,0) at top-left", () => {
    expect(clientToPct(rect, 100, 50)).toEqual({ x: 0, y: 0 });
  });
  it("returns (100,100) at bottom-right", () => {
    expect(clientToPct(rect, 600, 300)).toEqual({ x: 100, y: 100 });
  });
  it("returns (50,50) at center", () => {
    expect(clientToPct(rect, 350, 175)).toEqual({ x: 50, y: 50 });
  });
  it("can return negatives when pointer is outside the rect", () => {
    expect(clientToPct(rect, 50, 25)).toEqual({ x: -10, y: -10 });
  });
});

describe("pctToClient", () => {
  it("is the inverse of clientToPct", () => {
    const px = { x: 250, y: 150 };
    const pct = clientToPct(rect, px.x, px.y);
    const back = pctToClient(rect, pct);
    expect(back).toEqual(px);
  });
});
