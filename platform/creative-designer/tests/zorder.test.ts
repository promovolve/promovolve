import { describe, expect, it } from "vitest";
import {
  addItem,
  bringForward,
  bringToFront,
  currentLayout,
  initialState,
  setSelection,
  sendBackward,
  sendToBack,
} from "../src/state";
import type { TextItem } from "../src/types";

const t = (text: string): TextItem => ({ type: "text", text, left: 0, top: 0 });

function setupFour() {
  let s = initialState([{ layout: [], banners: {} }]);
  for (const name of ["a", "b", "c", "d"]) s = addItem(s, t(name));
  return s;
}

const names = (state: ReturnType<typeof setupFour>): string[] =>
  currentLayout(state).map((it) => (it as TextItem).text!);

describe("bringForward", () => {
  it("moves a single selection one step up", () => {
    const s = bringForward(setSelection(setupFour(), [1])); // select 'b'
    expect(names(s)).toEqual(["a", "c", "b", "d"]);
    expect(s.selectedItemIdxs).toEqual([2]);
  });
  it("no-op when already at the top", () => {
    const s = bringForward(setSelection(setupFour(), [3]));
    expect(names(s)).toEqual(["a", "b", "c", "d"]);
  });
  it("two non-adjacent selected each move one step", () => {
    const s = bringForward(setSelection(setupFour(), [0, 2])); // a, c
    // a: 0 → 1, c: 2 → 3 → order [b, a, d, c]
    expect(names(s)).toEqual(["b", "a", "d", "c"]);
    expect(new Set(s.selectedItemIdxs)).toEqual(new Set([1, 3]));
  });
  it("adjacent selected move as a block", () => {
    // a, b selected (0, 1). Forward: each slot tries to move up, but
    // 1 can't (blocked by 2), 0 can but 1 would move into its place.
    // Expected: [c, a, b, d] — the block shifts up by one.
    const s = bringForward(setSelection(setupFour(), [0, 1]));
    expect(names(s)).toEqual(["c", "a", "b", "d"]);
  });
});

describe("sendBackward", () => {
  it("moves a single selection one step down", () => {
    const s = sendBackward(setSelection(setupFour(), [2])); // select 'c'
    expect(names(s)).toEqual(["a", "c", "b", "d"]);
    expect(s.selectedItemIdxs).toEqual([1]);
  });
  it("no-op when already at the bottom", () => {
    const s = sendBackward(setSelection(setupFour(), [0]));
    expect(names(s)).toEqual(["a", "b", "c", "d"]);
  });
});

describe("bringToFront", () => {
  it("moves selected to the end, preserving relative order", () => {
    const s = bringToFront(setSelection(setupFour(), [0, 2])); // a, c
    expect(names(s)).toEqual(["b", "d", "a", "c"]);
    expect(new Set(s.selectedItemIdxs)).toEqual(new Set([2, 3]));
  });
  it("no-op with empty selection", () => {
    const s = bringToFront(setupFour());
    expect(names(s)).toEqual(["a", "b", "c", "d"]);
  });
});

describe("sendToBack", () => {
  it("moves selected to the start, preserving relative order", () => {
    const s = sendToBack(setSelection(setupFour(), [1, 3])); // b, d
    expect(names(s)).toEqual(["b", "d", "a", "c"]);
    expect(new Set(s.selectedItemIdxs)).toEqual(new Set([0, 1]));
  });
});
