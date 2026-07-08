import { beforeEach, describe, expect, it } from "vitest";
import { _state, copy, duplicate, hasClipboard, paste } from "../src/interaction/clipboard";
import { addItem, initialState } from "../src/state";
import { Store } from "../src/store";
import type { TextItem } from "../src/types";

const txt = (text: string): TextItem => ({ type: "text", text, left: 10, top: 10, width: 20 });

function storeWith(items: TextItem[] = []): Store {
  let state = initialState([{ layout: [], banners: {} }]);
  for (const item of items) state = addItem(state, item);
  return new Store(state);
}

beforeEach(() => {
  _state.reset();
});

describe("copy", () => {
  it("stores the currently-selected item", () => {
    const store = storeWith([txt("a")]);
    expect(copy(store)).toBe(true);
    expect(hasClipboard()).toBe(true);
  });
  it("returns false with no selection", () => {
    const store = storeWith([]);
    expect(copy(store)).toBe(false);
  });
});

describe("paste", () => {
  it("appends a clone with a position offset", () => {
    const store = storeWith([txt("a")]);
    copy(store);
    paste(store);
    const items = store.state.pages[0]!.layout!;
    expect(items).toHaveLength(2);
    expect(items[1]!.left).toBe(13);
    expect(items[1]!.top).toBe(13);
  });
  it("returns false when clipboard is empty", () => {
    const store = storeWith([txt("a")]);
    expect(paste(store)).toBe(false);
  });
});

describe("duplicate", () => {
  it("copies the selected item in one shot", () => {
    const store = storeWith([txt("a")]);
    expect(duplicate(store)).toBe(true);
    const items = store.state.pages[0]!.layout!;
    expect(items).toHaveLength(2);
    expect((items[1] as TextItem).text).toBe("a");
    expect(items[1]!.left).toBe(13);
  });
  it("doesn't share references with the original", () => {
    const store = storeWith([txt("a")]);
    duplicate(store);
    const items = store.state.pages[0]!.layout!;
    expect(items[0]).not.toBe(items[1]);
  });
});
