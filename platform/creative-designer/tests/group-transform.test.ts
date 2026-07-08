import { describe, expect, it } from "vitest";
import {
  applyHandleDelta,
  rescaleItem,
  selectionBounds,
} from "../src/interaction/group-resize-gesture";
import { rotateItem, rotatePoint } from "../src/interaction/group-rotate-gesture";
import type { CircleItem, LayoutItem, RectItem, TextItem } from "../src/types";

const r = (left: number, top: number, w = 10, h = 10): RectItem => ({
  type: "rect", left, top, width: w, height: h, fill: "#000",
});

const c = (left: number, top: number, radius = 5): CircleItem => ({
  type: "circle", left, top, radius, fill: "#f00",
});

describe("selectionBounds", () => {
  it("returns the union bbox of multiple items", () => {
    const items = [r(10, 10), r(50, 30, 20, 15), r(70, 5, 5, 5)];
    expect(selectionBounds(items, [0, 1, 2])).toEqual({
      left: 10, top: 5, width: 65, height: 40,
    });
  });

  it("handles circles via itemBoundsPct (top-left + 2r)", () => {
    // circle at (20, 20) r=5 → bbox 20..30 / 20..30
    // rect at (50, 10) 10×10 → bbox 50..60 / 10..20
    const items = [c(20, 20), r(50, 10)];
    expect(selectionBounds(items, [0, 1])).toEqual({
      left: 20, top: 10, width: 40, height: 20,
    });
  });

  it("ignores out-of-range indexes", () => {
    const items = [r(10, 10)];
    expect(selectionBounds(items, [0, 5])).toEqual({
      left: 10, top: 10, width: 10, height: 10,
    });
  });

  it("returns null when no valid indexes", () => {
    expect(selectionBounds([r(10, 10)], [99])).toBeNull();
  });
});

describe("applyHandleDelta", () => {
  const b = { left: 10, top: 10, width: 20, height: 20 };

  it("east handle extends width", () => {
    expect(applyHandleDelta(b, "e", 5, 0)).toEqual({ left: 10, top: 10, width: 25, height: 20 });
  });

  it("south handle extends height", () => {
    expect(applyHandleDelta(b, "s", 0, 7)).toEqual({ left: 10, top: 10, width: 20, height: 27 });
  });

  it("west handle moves left and shrinks width", () => {
    expect(applyHandleDelta(b, "w", 4, 0)).toEqual({ left: 14, top: 10, width: 16, height: 20 });
  });

  it("north handle moves top and shrinks height", () => {
    expect(applyHandleDelta(b, "n", 0, 3)).toEqual({ left: 10, top: 13, width: 20, height: 17 });
  });

  it("nw corner moves both axes inward", () => {
    expect(applyHandleDelta(b, "nw", 2, 3)).toEqual({ left: 12, top: 13, width: 18, height: 17 });
  });

  it("se corner extends both axes outward", () => {
    expect(applyHandleDelta(b, "se", 5, 4)).toEqual({ left: 10, top: 10, width: 25, height: 24 });
  });
});

describe("rescaleItem", () => {
  // Items at the corners of a 0..40 / 0..20 bbox; double the bbox in
  // both axes and check each item ends up where it should.
  const orig = { left: 0, top: 0, width: 40, height: 20 };
  const next = { left: 0, top: 0, width: 80, height: 40 };

  function snapFor(item: LayoutItem) {
    const b = item.type === "circle"
      ? { left: item.left ?? 0, top: item.top ?? 0, width: (item.radius ?? 0) * 2, height: (item.radius ?? 0) * 2 }
      : { left: item.left ?? 0, top: item.top ?? 0, width: (item as RectItem).width ?? 0, height: (item as RectItem).height ?? 0 };
    return {
      idx: 0, item, bounds: b,
      relX: (b.left - orig.left) / orig.width,
      relY: (b.top - orig.top) / orig.height,
      relW: b.width / orig.width,
      relH: b.height / orig.height,
    };
  }

  it("scales position and size proportionally for rects", () => {
    const item = r(10, 5, 8, 4); // rel 0.25, 0.25, 0.2, 0.2
    const out = rescaleItem(snapFor(item), next) as RectItem;
    expect(out.left).toBeCloseTo(20);    // 0.25 * 80
    expect(out.top).toBeCloseTo(10);     // 0.25 * 40
    expect(out.width).toBeCloseTo(16);   // 0.2 * 80
    expect(out.height).toBeCloseTo(8);   // 0.2 * 40
  });

  it("preserves circles via averaged scale (no stretching)", () => {
    // Circle at (10, 5) r=4 → bbox 10..18 / 5..13. relW=8/40=0.2, relH=8/20=0.4.
    // After 2x both axes: newW=16, newH=16. avg radius = (16+16)/4 = 8.
    const item = c(10, 5, 4);
    const out = rescaleItem(snapFor(item), next) as CircleItem;
    expect(out.radius).toBeCloseTo(8);
  });

  it("preserves non-bounds fields (fill, type) through scaling", () => {
    const item: TextItem = { type: "text", text: "hi", left: 10, top: 5, width: 8 };
    const out = rescaleItem(snapFor(item), next) as TextItem;
    expect(out.text).toBe("hi");
    expect(out.type).toBe("text");
  });
});

describe("rotatePoint", () => {
  it("rotates 90° around origin: (1, 0) → (0, 1)", () => {
    const p = rotatePoint(1, 0, 0, 0, Math.PI / 2);
    expect(p.x).toBeCloseTo(0);
    expect(p.y).toBeCloseTo(1);
  });

  it("180° around (5, 5): (10, 5) → (0, 5)", () => {
    const p = rotatePoint(10, 5, 5, 5, Math.PI);
    expect(p.x).toBeCloseTo(0);
    expect(p.y).toBeCloseTo(5);
  });

  it("a point at the center is unchanged by any rotation", () => {
    const p = rotatePoint(5, 5, 5, 5, Math.PI / 3);
    expect(p.x).toBeCloseTo(5);
    expect(p.y).toBeCloseTo(5);
  });
});

describe("rotateItem", () => {
  it("orbits center around group center AND adds delta to item rotation", () => {
    // Item at (10, 0), 10×10 → center (15, 5). Group center (5, 5).
    // Rotate +90° around (5, 5): center → (5, 15). Top-left → (0, 10).
    // Item starts with rotation=20 → ends at 110.
    const item = { ...r(10, 0), rotation: 20 };
    const snap = {
      idx: 0,
      item,
      bounds: { left: 10, top: 0, width: 10, height: 10 },
      cx: 15,
      cy: 5,
      rotation: 20,
    };
    const out = rotateItem(snap, 5, 5, 90) as RectItem;
    expect(out.left).toBeCloseTo(0);
    expect(out.top).toBeCloseTo(10);
    expect(out.rotation).toBeCloseTo(110);
  });

  it("zero delta is a no-op (same position, same rotation)", () => {
    const item = { ...r(10, 10), rotation: 45 };
    const snap = {
      idx: 0, item,
      bounds: { left: 10, top: 10, width: 10, height: 10 },
      cx: 15, cy: 15, rotation: 45,
    };
    const out = rotateItem(snap, 50, 50, 0) as RectItem;
    expect(out.left).toBeCloseTo(10);
    expect(out.top).toBeCloseTo(10);
    expect(out.rotation).toBeCloseTo(45);
  });
});
