import { describe, expect, it } from "vitest";
import { addItem, groupSelection, initialState, setSelection } from "../src/state";
import { panelScope, scopeLabel, selectionActions } from "../src/selection-model";
import type { DesignerState, ImageItem, LayoutItem, RectItem, TextItem } from "../src/types";

const text: TextItem = { type: "text", text: "Autumn in Kyoto", left: 0, top: 0 };
const image: ImageItem = { type: "image", src: "https://cdn.example/a/hero.jpg", left: 0, top: 0 };
const formula: ImageItem = { type: "image", src: "", latex: "E=mc^2", left: 0, top: 0 };
const scrim = { type: "rect", left: 0, top: 0, scrim: {} } as unknown as RectItem;
const circle = { type: "circle", left: 0, top: 0, radius: 5 } as LayoutItem;

const ARRANGE = ["bringToFront", "bringForward", "sendBackward", "sendToBack"];

function withItems(...items: LayoutItem[]): DesignerState {
  let s = initialState([{ layout: [], banners: {} }]);
  for (const it of items) s = addItem(s, it);
  return setSelection(s, []);
}

describe("selectionActions", () => {
  it("is empty with nothing selected", () => {
    expect(selectionActions(withItems(text, image))).toEqual([]);
  });

  it("offers image actions for one image", () => {
    const s = setSelection(withItems(image), [0]);
    expect(selectionActions(s)).toEqual(["replace", "crop", "removeBackground", ...ARRANGE, "delete"]);
  });

  it("offers edit formula for one formula", () => {
    const s = setSelection(withItems(formula), [0]);
    expect(selectionActions(s)).toEqual(["editFormula", ...ARRANGE, "delete"]);
  });

  it("offers alignment for one text item", () => {
    const s = setSelection(withItems(text), [0]);
    expect(selectionActions(s)).toEqual(["alignLeft", "alignCenter", "alignRight", ...ARRANGE, "delete"]);
  });

  it("offers only arrange and delete for a scrim or a circle", () => {
    expect(selectionActions(setSelection(withItems(scrim), [0]))).toEqual([...ARRANGE, "delete"]);
    expect(selectionActions(setSelection(withItems(circle), [0]))).toEqual([...ARRANGE, "delete"]);
  });

  it("mixed image and text selection gets only shared actions", () => {
    const s = setSelection(withItems(image, text), [0, 1]);
    expect(selectionActions(s)).toEqual([...ARRANGE, "group", "delete"]);
  });

  it("an already-grouped selection is not offered group", () => {
    const s = groupSelection(setSelection(withItems(image, text), [0, 1]));
    expect(selectionActions(s)).toEqual([...ARRANGE, "delete"]);
  });

  it("ignores a stale selection index", () => {
    const s = setSelection(withItems(text, image), [99]);
    expect(selectionActions(s)).toEqual([]);
    expect(panelScope(s)).toBe("banner");
    expect(() => scopeLabel(s)).not.toThrow();
  });
});

describe("panelScope and scopeLabel", () => {
  it("shows the banner with nothing selected", () => {
    const s = withItems(text);
    expect(panelScope(s)).toBe("banner");
    expect(scopeLabel(s)).toBe("Banner and page 1");
  });

  it("scope label for nothing selected on page 2", () => {
    expect(scopeLabel({ ...withItems(text), pageIdx: 1 })).toBe("Banner and page 2");
  });

  it("names the one selected item", () => {
    expect(panelScope(setSelection(withItems(image), [0]))).toBe("item");
    expect(scopeLabel(setSelection(withItems(image), [0]))).toBe("Image · hero.jpg");
    expect(scopeLabel(setSelection(withItems(text), [0]))).toBe("Text · Autumn in Kyoto");
    expect(scopeLabel(setSelection(withItems(formula), [0]))).toBe("Formula · E=mc^2");
    expect(scopeLabel(setSelection(withItems(scrim), [0]))).toBe("Scrim · Scrim");
  });

  it("counts several selected items", () => {
    expect(scopeLabel(setSelection(withItems(image, text), [0, 1]))).toBe("2 items");
  });
});
