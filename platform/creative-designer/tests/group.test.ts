import { describe, expect, it } from "vitest";
import {
  addItem,
  currentLayout,
  groupSelection,
  initialState,
  selectItem,
  setSelection,
  toggleSelect,
  ungroupSelection,
} from "../src/state";
import type { LayoutItem, RectItem } from "../src/types";

const r = (left: number): RectItem => ({
  type: "rect", left, top: 10, width: 10, height: 10, fill: "#000",
});

function withFour() {
  let s = initialState([{ layout: [], banners: {} }]);
  for (let i = 0; i < 4; i++) s = addItem(s, r(i * 20));
  return s;
}

const groupOf = (it: LayoutItem): string | undefined =>
  (it as LayoutItem & { groupId?: string }).groupId;

describe("groupSelection", () => {
  it("no-op with fewer than 2 selected", () => {
    const s = groupSelection(setSelection(withFour(), [0]));
    expect(currentLayout(s).every((it) => groupOf(it) === undefined)).toBe(true);
  });

  it("assigns one shared groupId to every selected item", () => {
    const s = groupSelection(setSelection(withFour(), [0, 2]));
    const items = currentLayout(s);
    expect(groupOf(items[0]!)).toBeDefined();
    expect(groupOf(items[0]!)).toBe(groupOf(items[2]!));
    expect(groupOf(items[1]!)).toBeUndefined();
    expect(groupOf(items[3]!)).toBeUndefined();
  });

  it("re-grouping reassigns the whole expanded selection", () => {
    // Group {0, 1}, then try to group {1, 2}. Selection expansion
    // pulls 0 into the second group call (since 0 shares group with
    // 1), so all three end up under the new id. Authors who want to
    // peel an item out of a group must ungroup first.
    const s1 = groupSelection(setSelection(withFour(), [0, 1]));
    const id1 = groupOf(currentLayout(s1)[0]!);
    const s2 = groupSelection(setSelection(s1, [1, 2]));
    const items = currentLayout(s2);
    const id2 = groupOf(items[1]!);
    expect(id2).toBeDefined();
    expect(id2).not.toBe(id1);
    expect(groupOf(items[0]!)).toBe(id2);
    expect(groupOf(items[2]!)).toBe(id2);
    expect(groupOf(items[3]!)).toBeUndefined();
  });
});

describe("ungroupSelection", () => {
  it("strips groupId from selected items only", () => {
    let s = groupSelection(setSelection(withFour(), [0, 1, 2]));
    expect(groupOf(currentLayout(s)[0]!)).toBeDefined();
    s = ungroupSelection(setSelection(s, [0])); // ungroup only item 0
    const items = currentLayout(s);
    expect(groupOf(items[0]!)).toBeUndefined();
    // Items 1 and 2 still share the old groupId — but selecting 0
    // pulled them in via group expansion, so they get ungrouped too.
    // (Documented behaviour: group ops act on the expanded selection.)
    expect(groupOf(items[1]!)).toBeUndefined();
    expect(groupOf(items[2]!)).toBeUndefined();
  });

  it("no-op when no selected item is grouped", () => {
    const before = setSelection(withFour(), [0, 1]);
    const after = ungroupSelection(before);
    expect(currentLayout(after)).toEqual(currentLayout(before));
    // Should be the exact same state object (no allocation).
    expect(after).toBe(before);
  });
});

describe("selection expansion", () => {
  it("selecting one group member pulls in siblings", () => {
    const grouped = groupSelection(setSelection(withFour(), [0, 2]));
    const s = selectItem(grouped, 0);
    expect(new Set(s.selectedItemIdxs)).toEqual(new Set([0, 2]));
  });

  it("setSelection on a non-group idx leaves selection alone", () => {
    const grouped = groupSelection(setSelection(withFour(), [0, 2]));
    const s = selectItem(grouped, 1); // ungrouped
    expect(s.selectedItemIdxs).toEqual([1]);
  });

  it("toggleSelect on a group member toggles the whole group", () => {
    const grouped = groupSelection(setSelection(withFour(), [0, 2]));
    // Start with item 1 selected; shift-click item 0 → expects 1, 0, 2
    const sel = setSelection(grouped, [1]);
    const t1 = toggleSelect(sel, 0);
    expect(new Set(t1.selectedItemIdxs)).toEqual(new Set([1, 0, 2]));
    // Shift-click item 2 again → removes the whole group
    const t2 = toggleSelect(t1, 2);
    expect(t2.selectedItemIdxs).toEqual([1]);
  });

  it("marquee selection (multiple idxs at once) expands all groups represented", () => {
    let s = withFour();
    // Group items 0 and 3
    s = groupSelection(setSelection(s, [0, 3]));
    // Then group items 1 and 2 separately
    s = groupSelection(setSelection(s, [1, 2]));
    // Marquee picks items 0 and 1 — expansion pulls in 3 and 2.
    const sel = setSelection(s, [0, 1]);
    expect(new Set(sel.selectedItemIdxs)).toEqual(new Set([0, 1, 2, 3]));
  });
});