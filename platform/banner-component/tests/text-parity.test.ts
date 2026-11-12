// @vitest-environment jsdom
//
// Text must wrap the same in both render paths: render-collapsed (the
// designer canvas) and layout-item (Preview / the expanded reader).
// They drifted twice — authored newlines collapsed in the reader, and
// vertical columns balanced only in the reader (GH #479).

import { describe, expect, it } from "vitest";
import { layoutItemToNode } from "../src/layout-item";
import { renderCollapsedItemHtml } from "../src/render-collapsed";
import type { LayoutItem } from "../src/types";

const item = (extra: object): LayoutItem =>
  ({ type: "text", text: "全国の宿から\n理想の湯を", left: 10, top: 10, width: 40, ...extra }) as LayoutItem;

function both(it: LayoutItem): [CSSStyleDeclaration, CSSStyleDeclaration] {
  const host = document.createElement("div");
  host.innerHTML = renderCollapsedItemHtml(it, 0, {}, "sans-serif");
  const collapsed = (host.firstElementChild as HTMLElement).style;
  const expanded = (layoutItemToNode(it, {}, "sans-serif", 0) as HTMLElement).style;
  return [collapsed, expanded];
}

describe("text wrapping parity", () => {
  for (const writingMode of ["horizontal-tb", "vertical-rl"]) {
    it(`${writingMode}: both paths keep newlines and break words alike`, () => {
      const [c, e] = both(item({ writingMode }));
      for (const prop of ["white-space", "word-break", "writing-mode", "text-wrap"]) {
        expect(e.getPropertyValue(prop), prop).toBe(c.getPropertyValue(prop));
      }
      expect(e.getPropertyValue("white-space")).toBe("pre-wrap");
    });
  }
});
