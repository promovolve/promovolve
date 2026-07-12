// "Sync color across all 3 pages" (user decision 2026-07-13): a checkbox
// in the Page Background accordion, operable only on page 1. While on
// (pages[0].syncBg), page-1 background edits write every page; pages 2/3
// keep their own colour when it's off. See state.ts setSyncPageBg/setPageBg.

import { describe, expect, it } from "vitest";
import { initialState, setPageBg, setSyncPageBg } from "../src/state";
import type { Page } from "../src/types";

const threePages = (): Page[] =>
  ([{ headline: "1", bg: "#111111" }, { headline: "2", bg: "#222222" }, { headline: "3" }] as Page[]);

describe("setSyncPageBg", () => {
  it("turning ON copies page 1's bg onto every page and sets the flag", () => {
    const out = setSyncPageBg(initialState(threePages(), "expanded"), true);
    expect(out.pages[0]!.syncBg).toBe(true);
    expect(out.pages.map((p) => p.bg)).toEqual(["#111111", "#111111", "#111111"]);
  });

  it("turning ON with no page-1 bg clears the others' bg too", () => {
    const pages = threePages();
    delete pages[0]!.bg;
    const out = setSyncPageBg(initialState(pages, "expanded"), true);
    expect(out.pages.map((p) => p.bg)).toEqual([undefined, undefined, undefined]);
  });

  it("turning OFF drops the flag and leaves colours as they are", () => {
    const on = setSyncPageBg(initialState(threePages(), "expanded"), true);
    const off = setSyncPageBg(on, false);
    expect(off.pages[0]!.syncBg).toBeUndefined();
    expect(off.pages.map((p) => p.bg)).toEqual(["#111111", "#111111", "#111111"]);
  });
});

describe("setPageBg under sync", () => {
  it("page-1 edit writes every page while synced", () => {
    const s = setSyncPageBg(initialState(threePages(), "expanded"), true);
    const out = setPageBg(s, "#abcdef");
    expect(out.pages.map((p) => p.bg)).toEqual(["#abcdef", "#abcdef", "#abcdef"]);
  });

  it("page-1 clear clears every page while synced", () => {
    const s = setSyncPageBg(initialState(threePages(), "expanded"), true);
    const out = setPageBg(s, null);
    expect(out.pages.map((p) => p.bg)).toEqual([undefined, undefined, undefined]);
  });

  it("page-1 edit stays per-page when sync is off", () => {
    const out = setPageBg(initialState(threePages(), "expanded"), "#abcdef");
    expect(out.pages.map((p) => p.bg)).toEqual(["#abcdef", "#222222", undefined]);
  });

  it("a pages-2/3 edit is per-page regardless of the flag", () => {
    const s = setSyncPageBg(initialState(threePages(), "expanded"), true);
    const out = setPageBg({ ...s, pageIdx: 1 }, "#333333");
    expect(out.pages.map((p) => p.bg)).toEqual(["#111111", "#333333", "#111111"]);
  });
});
