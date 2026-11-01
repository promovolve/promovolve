// A new text box must open in the reading direction the surrounding text
// already uses. Arriving horizontal in a vertical-rl layout is not just
// untidy: correcting it means toggling the writing mode, which drops the
// text into a box shaped for the other axis (packTextItemHeight hugs HEIGHT
// for horizontal, WIDTH for vertical) and forces a large autofit shrink. The
// default was steering authors into the editor's most destructive gesture.

import { describe, expect, it } from "vitest";
import { addItem, initialState } from "../src/state";
import { inferWritingModeFromLayout } from "../src/ui/tool-rail";
import type { RectItem, TextItem } from "../src/types";

const text = (over: Partial<TextItem> = {}): TextItem => ({
  type: "text", text: "x", left: 10, top: 10, width: 20, ...over,
} as TextItem);

const rect = (): RectItem => ({
  type: "rect", left: 0, top: 0, width: 10, height: 10, fill: "#000",
});

const withItems = (items: Array<TextItem | RectItem>) => {
  let s = initialState([{ layout: [], banners: {} }]);
  for (const it of items) s = addItem(s, it);
  return s;
};

describe("inferWritingModeFromLayout", () => {

  it("returns undefined on an empty canvas", () => {
    expect(inferWritingModeFromLayout(withItems([]))).toBeUndefined();
  });

  it("inherits vertical-rl from the headline", () => {
    const s = withItems([text({ field: "headline", writingMode: "vertical-rl" })]);
    expect(inferWritingModeFromLayout(s)).toBe("vertical-rl");
  });

  it("stays horizontal when the headline is horizontal", () => {
    const s = withItems([text({ field: "headline" })]);
    expect(inferWritingModeFromLayout(s)).toBeUndefined();
  });

  it("prefers the headline over other text, like the font-family rule does", () => {
    // Mixed layouts exist; the headline sets the page's voice, so a new box
    // joins THAT rather than whatever happens to be first in the array.
    const s = withItems([
      text({ field: "body" }),
      text({ field: "headline", writingMode: "vertical-rl" }),
    ]);
    expect(inferWritingModeFromLayout(s)).toBe("vertical-rl");
  });

  it("falls back to any text when there is no headline", () => {
    const s = withItems([text({ field: "body", writingMode: "vertical-rl" })]);
    expect(inferWritingModeFromLayout(s)).toBe("vertical-rl");
  });

  it("ignores non-text items", () => {
    const s = withItems([rect(), text({ field: "headline", writingMode: "vertical-rl" })]);
    expect(inferWritingModeFromLayout(s)).toBe("vertical-rl");
  });

  it("returns undefined rather than 'horizontal-tb', so the item carries no redundant property", () => {
    // Absent already means horizontal everywhere in the renderer; writing it
    // out would make every new box differ from every generated one.
    const s = withItems([text({ field: "headline", writingMode: "horizontal-tb" })]);
    expect(inferWritingModeFromLayout(s)).toBeUndefined();
  });
});
