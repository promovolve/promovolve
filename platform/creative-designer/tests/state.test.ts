import { describe, expect, it } from "vitest";
import type { LayoutItem, Page, TextItem } from "../src/types";
import {
  addItem,
  currentItem,
  currentLayout,
  currentPage,
  deleteItem,
  initialState,
  reorderItem,
  selectItem,
  setTextureBg,
  setTextureSrc,
  switchMode,
  switchPage,
  updateItem,
} from "../src/state";

const txt = (text: string): TextItem => ({ type: "text", text, left: 0, top: 0 });

function pageWith(layout: LayoutItem[] = [], banners: Record<string, LayoutItem[]> = {}): Page {
  return { layout, banners };
}

describe("initialState + selectors", () => {
  it("starts in expanded mode with no selection", () => {
    const s = initialState([pageWith()]);
    expect(s.mode.key).toBe("expanded");
    expect(s.pageIdx).toBe(0);
    expect(s.selectedItemIdxs).toEqual([]);
  });

  it("currentLayout reads page.layout in expanded mode", () => {
    const s = initialState([pageWith([txt("a")])]);
    expect(currentLayout(s)).toEqual([txt("a")]);
  });

  it("currentLayout reads page.banners[sizeKey] in sized mode", () => {
    const s = switchMode(
      initialState([pageWith([], { "300x250": [txt("in banner")] })]),
      "300x250",
    );
    expect(currentLayout(s)).toEqual([txt("in banner")]);
  });

  it("currentPage returns null when out of range", () => {
    const s = initialState([]);
    expect(currentPage(s)).toBeNull();
  });
});

describe("setTextureBg", () => {
  it("sets the current page's textureBg without touching layout", () => {
    const s = setTextureBg(initialState([pageWith([txt("a")])]), { src: "https://cdn/t.webp", mode: "tile" });
    expect(currentPage(s)?.textureBg).toEqual({ src: "https://cdn/t.webp", mode: "tile" });
    expect(currentLayout(s)).toEqual([txt("a")]);
  });

  it("clears the textureBg key entirely when passed null", () => {
    let s = setTextureBg(initialState([pageWith()]), { src: "https://cdn/t.webp", mode: "cover" });
    s = setTextureBg(s, null);
    expect(currentPage(s)?.textureBg).toBeUndefined();
    expect("textureBg" in (currentPage(s) as Page)).toBe(false);
  });

  it("only writes the active page", () => {
    let s = initialState([pageWith(), pageWith()]);
    s = setTextureBg(s, { src: "https://cdn/t.webp", mode: "tile" });
    expect(s.pages[0]?.textureBg).toBeDefined();
    expect(s.pages[1]?.textureBg).toBeUndefined();
  });

  it("is a no-op when there is no current page", () => {
    const s = initialState([]);
    expect(setTextureBg(s, { src: "x", mode: "tile" })).toBe(s);
  });
});

describe("setTextureSrc", () => {
  it("sets a fresh texture with tile defaults and the given sizeBytes", () => {
    const s = setTextureSrc(initialState([pageWith()]), "https://cdn/t.webp", 1234);
    expect(currentPage(s)?.textureBg).toEqual({
      src: "https://cdn/t.webp", mode: "tile", opacity: 1, scale: undefined, blend: undefined, sizeBytes: 1234,
    });
  });

  it("preserves the author's mode/opacity/scale/blend when only the source changes", () => {
    let s = setTextureBg(initialState([pageWith()]), {
      src: "https://cdn/old.webp", mode: "cover", opacity: 0.5, scale: 48, blend: "multiply",
    });
    s = setTextureSrc(s, "https://cdn/new.webp");
    expect(currentPage(s)?.textureBg).toEqual({
      src: "https://cdn/new.webp", mode: "cover", opacity: 0.5, scale: 48, blend: "multiply", sizeBytes: undefined,
    });
  });
});

describe("addItem", () => {
  it("appends and selects the new item (expanded)", () => {
    const s = addItem(initialState([pageWith()]), txt("a"));
    expect(currentLayout(s)).toEqual([txt("a")]);
    expect(s.selectedItemIdxs).toEqual([0]);
  });

  it("appends to the sized bucket without disturbing expanded", () => {
    const s = addItem(
      switchMode(initialState([pageWith([txt("master")])]), "300x250"),
      txt("sized"),
    );
    expect(currentLayout(s)).toEqual([txt("sized")]);
    expect(s.pages[0]!.layout).toEqual([txt("master")]);
    expect(s.pages[0]!.banners?.["300x250"]).toEqual([txt("sized")]);
  });

  it("is immutable — doesn't mutate the input state", () => {
    const before = initialState([pageWith()]);
    addItem(before, txt("x"));
    expect(before.pages[0]!.layout).toEqual([]);
  });
});

describe("updateItem", () => {
  it("replaces the item with the updater's result", () => {
    let s = addItem(initialState([pageWith()]), txt("hello"));
    s = updateItem(s, 0, (it) => ({ ...it, text: "world" } as LayoutItem));
    expect(currentLayout(s)[0]).toMatchObject({ text: "world" });
  });

  it("is a no-op when idx is out of range", () => {
    const s = addItem(initialState([pageWith()]), txt("x"));
    expect(updateItem(s, 99, () => txt("y"))).toBe(s);
  });
});

describe("deleteItem", () => {
  const threeItems = () => {
    let s = initialState([pageWith()]);
    s = addItem(s, txt("a"));
    s = addItem(s, txt("b"));
    s = addItem(s, txt("c"));
    return s;
  };

  it("removes the item", () => {
    const s = deleteItem(threeItems(), 1);
    expect(currentLayout(s).map((it) => (it as TextItem).text)).toEqual(["a", "c"]);
  });

  it("clears selection when deleting the selected item", () => {
    const before = selectItem(threeItems(), 1);
    const s = deleteItem(before, 1);
    expect(s.selectedItemIdxs).toEqual([]);
  });

  it("shifts selection down when deleting an earlier item", () => {
    const before = selectItem(threeItems(), 2);
    const s = deleteItem(before, 0);
    expect(s.selectedItemIdxs).toEqual([1]);
  });
});

describe("reorderItem", () => {
  const s0 = () => {
    let s = initialState([pageWith()]);
    ["a", "b", "c", "d"].forEach((t) => { s = addItem(s, txt(t)); });
    return s;
  };

  it("moves an item forward", () => {
    const s = reorderItem(s0(), 0, 2);
    expect(currentLayout(s).map((it) => (it as TextItem).text)).toEqual(["b", "c", "a", "d"]);
  });

  it("moves an item backward", () => {
    const s = reorderItem(s0(), 3, 1);
    expect(currentLayout(s).map((it) => (it as TextItem).text)).toEqual(["a", "d", "b", "c"]);
  });

  it("keeps selection pinned to the moved item", () => {
    const before = selectItem(s0(), 0);
    const s = reorderItem(before, 0, 2);
    expect(s.selectedItemIdxs).toEqual([2]);
  });
});

describe("switchMode / switchPage", () => {
  it("switchMode drops selection and changes mode", () => {
    const before = selectItem(addItem(initialState([pageWith()]), txt("x")), 0);
    const s = switchMode(before, "300x250");
    expect(s.mode.key).toBe("300x250");
    expect(s.selectedItemIdxs).toEqual([]);
  });

  it("switchPage is a no-op when out of range", () => {
    const s = initialState([pageWith()]);
    expect(switchPage(s, 5)).toBe(s);
  });
});

describe("currentItem", () => {
  it("returns null when nothing is selected", () => {
    expect(currentItem(initialState([pageWith()]))).toBeNull();
  });

  it("returns the selected item", () => {
    const s = selectItem(addItem(initialState([pageWith()]), txt("x")), 0);
    expect(currentItem(s)).toMatchObject({ text: "x" });
  });
});
