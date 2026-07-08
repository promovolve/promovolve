import { describe, expect, it } from "vitest";
import {
  addItem,
  alignSelection,
  currentLayout,
  distributeSelection,
  initialState,
  setSelection,
} from "../src/state";
import type { CircleItem, RectItem, TextItem } from "../src/types";

const rect = (left: number, top: number, w = 10, h = 10): RectItem => ({
  type: "rect", left, top, width: w, height: h, fill: "#000",
});

const text = (left: number, top: number, w = 10): TextItem => ({
  type: "text", text: "x", left, top, width: w,
});

const circle = (left: number, top: number, r = 5): CircleItem => ({
  type: "circle", left, top, radius: r, fill: "#f00",
});

function withItems(items: ReturnType<typeof rect>[] | (RectItem | TextItem | CircleItem)[]) {
  let s = initialState([{ layout: [], banners: {} }]);
  for (const it of items) s = addItem(s, it);
  return s;
}

describe("alignSelection", () => {
  it("single selection aligns to the canvas (left edge)", () => {
    // Item at (10, 10), aligning left → snaps to canvas left (0).
    const s = setSelection(withItems([rect(10, 10), rect(50, 30)]), [0]);
    const out = alignSelection(s, "left");
    expect(currentLayout(out)[0]!.left).toBe(0);
    // Other (unselected) items unaffected.
    expect(currentLayout(out)[1]!.left).toBe(50);
  });

  it("single selection horiz-center aligns to canvas mid (50)", () => {
    // Item at left=10, width=10 → centered means left = 45 (50 - 10/2).
    const s = setSelection(withItems([rect(10, 10)]), [0]);
    const out = alignSelection(s, "hcenter");
    expect(currentLayout(out)[0]!.left).toBe(45);
  });

  it("no-op with no selection (empty selectedItemIdxs)", () => {
    // withItems leaves the last-added item selected; clear it so the
    // function takes the fast no-op path.
    const s = setSelection(withItems([rect(10, 10)]), []);
    const out = alignSelection(s, "left");
    expect(out).toBe(s);
  });

  it("single item alignment moves the box against the canvas (any item type)", () => {
    // Toolbar align buttons are always positional — text alignment
    // within a text box is the separate textAlign dropdown in the
    // Typography panel. Verify text + non-text both move the box.
    const sRect = setSelection(withItems([rect(10, 10)]), [0]);
    expect(currentLayout(alignSelection(sRect, "left"))[0]!.left).toBe(0);

    const sText = setSelection(withItems([text(10, 10, 30)]), [0]);
    const out = alignSelection(sText, "left");
    expect(currentLayout(out)[0]!.left).toBe(0);
    expect((currentLayout(out)[0] as TextItem).textAlign).toBeUndefined();
  });

  it("aligns to the leftmost edge of the selection bbox", () => {
    const s = setSelection(withItems([rect(10, 10), rect(50, 30), rect(80, 50)]), [0, 1, 2]);
    const out = alignSelection(s, "left");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([10, 10, 10]);
  });

  it("aligns to the rightmost edge (left adjusts so right edges match)", () => {
    // widths all 10; rightmost right-edge = 80+10 = 90 → all left = 80
    const s = setSelection(withItems([rect(10, 10), rect(50, 30), rect(80, 50)]), [0, 1, 2]);
    const out = alignSelection(s, "right");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([80, 80, 80]);
  });

  it("hcenter aligns to horizontal center of group bbox", () => {
    // group span: [10, 90]; center = 50; each item width 10 → left = 45
    const s = setSelection(withItems([rect(10, 10), rect(50, 30), rect(80, 50)]), [0, 1, 2]);
    const out = alignSelection(s, "hcenter");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([45, 45, 45]);
  });

  it("vertical alignment (top/vcenter/bottom)", () => {
    // tops/heights: (10,8)→bottom 18; (30,12)→bottom 42; (50,4)→bottom 54
    // Group bbox: minTop=10, maxBottom=54, vcenter=32
    const s = setSelection(withItems([rect(10, 10, 10, 8), rect(20, 30, 10, 12), rect(30, 50, 10, 4)]), [0, 1, 2]);
    expect(currentLayout(alignSelection(s, "top")).map((it) => it.top)).toEqual([10, 10, 10]);
    // bottom: top = 54 - h
    expect(currentLayout(alignSelection(s, "bottom")).map((it) => it.top)).toEqual([46, 42, 50]);
    // vcenter: top = 32 - h/2
    expect(currentLayout(alignSelection(s, "vcenter")).map((it) => it.top)).toEqual([28, 26, 30]);
  });

  it("treats circles uniformly via itemBoundsPct (top-left + 2r)", () => {
    // circle at left=20, top=20, r=5 has bbox 20..30 / 20..30
    // rect at left=50, top=10, 10x10 has bbox 50..60 / 10..20
    // Aligning left → both left = 20
    const s = setSelection(withItems([circle(20, 20), rect(50, 10)]), [0, 1]);
    const out = alignSelection(s, "left");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([20, 20]);
  });

  it("ignores non-selected items", () => {
    const s = setSelection(
      withItems([rect(10, 10), rect(50, 30), rect(80, 50)]),
      [0, 2], // skip middle
    );
    const out = alignSelection(s, "left");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([10, 50, 10]); // middle untouched
  });
});

describe("distributeSelection", () => {
  it("two items distribute against the canvas (pin to edges)", () => {
    // Two width=10 rects starting at left=10/50. Distribute-h should
    // pin first to left edge (0) and second to right edge (90), since
    // the selection's own bbox is degenerate with only 2 items.
    const s = setSelection(withItems([rect(10, 10), rect(50, 30)]), [0, 1]);
    const out = distributeSelection(s, "horizontal");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([0, 90]);
  });

  it("one item distribute centers it on the canvas", () => {
    // Single width=10 item → centered means left = 45.
    const s = setSelection(withItems([rect(10, 10)]), [0]);
    const out = distributeSelection(s, "horizontal");
    expect(currentLayout(out)[0]!.left).toBe(45);
  });

  it("no-op with empty selection", () => {
    const s = setSelection(withItems([rect(10, 10)]), []);
    const out = distributeSelection(s, "horizontal");
    expect(out).toBe(s);
  });

  it("distributes equal gaps horizontally between 3 items", () => {
    // widths all 10; lefts 0, 50, 100. Span 0..110 = 110.
    // Sum widths = 30. Total gap = 80. Per-gap = 40.
    // Targets: 0, 0+10+40=50, 50+10+40=100 → already correct.
    const s = setSelection(withItems([rect(0, 10), rect(50, 10), rect(100, 10)]), [0, 1, 2]);
    const out = distributeSelection(s, "horizontal");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([0, 50, 100]);
  });

  it("redistributes when middle items are off-grid", () => {
    // Same anchors (0, 100+10) but middle at 30 instead of 50.
    const s = setSelection(withItems([rect(0, 10), rect(30, 10), rect(100, 10)]), [0, 1, 2]);
    const out = distributeSelection(s, "horizontal");
    const lefts = currentLayout(out).map((it) => it.left);
    expect(lefts).toEqual([0, 50, 100]);
  });

  it("distributes vertically", () => {
    const s = setSelection(withItems([rect(10, 0, 10, 10), rect(10, 30, 10, 10), rect(10, 100, 10, 10)]), [0, 1, 2]);
    const out = distributeSelection(s, "vertical");
    const tops = currentLayout(out).map((it) => it.top);
    // Span 0..110 = 110. Sum heights 30. Gap 80 / 2 = 40.
    // Targets: 0, 0+10+40=50, 50+10+40=100
    expect(tops).toEqual([0, 50, 100]);
  });

  it("anchors leftmost and rightmost after sorting by position", () => {
    // Items added in arbitrary order; distribution sorts before placing.
    const s = setSelection(
      withItems([rect(100, 10), rect(0, 10), rect(30, 10)]),
      [0, 1, 2],
    );
    const out = distributeSelection(s, "horizontal");
    const lefts = currentLayout(out).map((it) => it.left);
    // Sorted: idx 1 (left=0), idx 2 (left=30), idx 0 (left=100)
    // After distribute: lefts become [0, 50, 100] in sorted order
    // Original idx 1 → 0; idx 2 → 50; idx 0 → 100
    expect(lefts[1]).toBe(0);
    expect(lefts[2]).toBe(50);
    expect(lefts[0]).toBe(100);
  });

  it("text items distribute the same way (uniform bbox handling)", () => {
    const s = setSelection(
      withItems([text(0, 10, 10), text(20, 10, 10), text(60, 10, 10)]),
      [0, 1, 2],
    );
    const out = distributeSelection(s, "horizontal");
    const lefts = currentLayout(out).map((it) => it.left);
    // Span 0..70 = 70. Sum 30. Gap 40 / 2 = 20.
    // Targets: 0, 0+10+20=30, 30+10+20=60
    expect(lefts).toEqual([0, 30, 60]);
  });
});