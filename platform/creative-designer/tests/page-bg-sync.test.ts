// "Sync color across all 3 pages" (user decisions 2026-07-13): a checkbox
// in the Page Background accordion, operable only on page 1, DEFAULT ON
// (absent flag = synced — flipped from opt-in on user request). While on,
// page-1 background edits write every page; explicitly off, each page
// owns its colour. See state.ts setSyncPageBg/setPageBg.

import { describe, expect, it } from "vitest";
import { initialState, setPageBg, setSyncPageBg } from "../src/state";
import type { Page } from "../src/types";

const threePages = (): Page[] =>
  ([{ headline: "1", bg: "#111111" }, { headline: "2", bg: "#222222" }, { headline: "3" }] as Page[]);

const withSyncOff = (pages: Page[]): Page[] =>
  pages.map((p, i) => (i === 0 ? ({ ...p, syncBg: false } as Page) : p));

describe("setSyncPageBg", () => {
  it("turning ON copies page 1's bg onto every page and clears the flag (absent = on)", () => {
    const out = setSyncPageBg(initialState(withSyncOff(threePages()), "expanded"), true);
    expect(out.pages[0]!.syncBg).toBeUndefined();
    expect(out.pages.map((p) => p.bg)).toEqual(["#111111", "#111111", "#111111"]);
  });

  it("turning ON with no page-1 bg clears the others' bg too", () => {
    const pages = withSyncOff(threePages());
    delete pages[0]!.bg;
    const out = setSyncPageBg(initialState(pages, "expanded"), true);
    expect(out.pages.map((p) => p.bg)).toEqual([undefined, undefined, undefined]);
  });

  it("turning OFF sets the flag and leaves colours as they are", () => {
    const out = setSyncPageBg(initialState(threePages(), "expanded"), false);
    expect(out.pages[0]!.syncBg).toBe(false);
    expect(out.pages.map((p) => p.bg)).toEqual(["#111111", "#222222", undefined]);
  });
});

describe("setPageBg under sync", () => {
  it("page-1 edit writes every page by DEFAULT (absent flag = synced)", () => {
    const out = setPageBg(initialState(threePages(), "expanded"), "#abcdef");
    expect(out.pages.map((p) => p.bg)).toEqual(["#abcdef", "#abcdef", "#abcdef"]);
  });

  it("page-1 clear clears every page while synced", () => {
    const out = setPageBg(initialState(threePages(), "expanded"), null);
    expect(out.pages.map((p) => p.bg)).toEqual([undefined, undefined, undefined]);
  });

  it("page-1 edit stays per-page when sync is explicitly off", () => {
    const out = setPageBg(initialState(withSyncOff(threePages()), "expanded"), "#abcdef");
    expect(out.pages.map((p) => p.bg)).toEqual(["#abcdef", "#222222", undefined]);
  });

  it("a pages-2/3 edit is per-page regardless of the flag", () => {
    const out = setPageBg({ ...initialState(threePages(), "expanded"), pageIdx: 1 }, "#333333");
    expect(out.pages.map((p) => p.bg)).toEqual(["#111111", "#333333", undefined]);
  });
});
