// One-source deletion (user decision 2026-07-13, "option A"): deleting a
// field-bound TEXT item in an EXPANDED reader removes that field from
// the page EVERYWHERE — wide layout artifact + every size bucket — while
// keeping the page[field] VALUE (undo/Regenerate can restore wording).
// Deleting in a collapsed SIZE stays per-view. Deleting the main hero
// clears page.img on every delete path. See state.ts stripDeletedFields.

import { describe, expect, it } from "vitest";
import { deleteSelection, initialState, switchMode } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

const txt = (field: string): LayoutItem => ({ type: "text", field, left: 5, top: 10, width: 50 }) as LayoutItem;
const img = (): LayoutItem => ({ type: "image", field: "img", left: 0, top: 0, width: 50, height: 50 }) as LayoutItem;

const onePage = (): Page[] => ([
  {
    headline: "H", body: "B", img: "hero.png",
    layout: [img(), txt("headline"), txt("body")],
    banners: {
      "mobile-expanded": [img(), txt("headline"), txt("body")],
      "300x250": [img(), txt("headline"), txt("body")],
      "970x250": [img(), txt("headline")],
    },
  },
] as Page[]);

const fieldsIn = (arr?: LayoutItem[]): string[] =>
  (arr ?? []).filter((it) => it.type === "text").map((it) => (it as { field?: string }).field ?? "?");

describe("deleteSelection in the expanded reader", () => {
  it("removes the field from the wide artifact and every size bucket; keeps page[field]", () => {
    // mode "mobile" edits banners["mobile-expanded"]: headline at idx 1.
    let s = initialState(onePage(), "mobile");
    s = { ...s, selectedItemIdxs: [1] };
    const out = deleteSelection(s);
    const p = out.pages[0]!;
    expect(fieldsIn(p.banners!["mobile-expanded"])).toEqual(["body"]);
    expect(fieldsIn(p.layout)).toEqual(["body"]);
    expect(fieldsIn(p.banners!["300x250"])).toEqual(["body"]);
    expect(fieldsIn(p.banners!["970x250"])).toEqual([]);
    expect(p.headline).toBe("H"); // value survives for undo/Regenerate
  });

  it("multi-select removes every selected field everywhere", () => {
    let s = initialState(onePage(), "mobile");
    s = { ...s, selectedItemIdxs: [1, 2] };
    const out = deleteSelection(s);
    expect(fieldsIn(out.pages[0]!.banners!["300x250"])).toEqual([]);
    expect(fieldsIn(out.pages[0]!.layout)).toEqual([]);
  });

  it("deleting the main hero clears page.img (deleteSelection path)", () => {
    let s = initialState(onePage(), "mobile");
    s = { ...s, selectedItemIdxs: [0] };
    const out = deleteSelection(s);
    expect(out.pages[0]!.img).toBeUndefined();
    // text fields untouched everywhere
    expect(fieldsIn(out.pages[0]!.banners!["300x250"])).toEqual(["headline", "body"]);
  });
});

describe("deleteSelection in a collapsed size", () => {
  it("stays per-view — other sizes and the reader keep the field", () => {
    let s = initialState(onePage(), "mobile");
    s = switchMode(s, "300x250");
    s = { ...s, selectedItemIdxs: [1] }; // headline in the 300x250 bucket
    const out = deleteSelection(s);
    const p = out.pages[0]!;
    expect(fieldsIn(p.banners!["300x250"])).toEqual(["body"]);
    expect(fieldsIn(p.banners!["mobile-expanded"])).toEqual(["headline", "body"]);
    expect(fieldsIn(p.banners!["970x250"])).toEqual(["headline"]);
  });
});
