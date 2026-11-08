import { describe, expect, it } from "vitest";
import { initialState } from "../src/state";
import type { BannerConfig } from "../src/types";
import { entranceOptions, effectiveEntrance } from "../src/ui/banner-config-panel";
import { pagerVisible } from "../src/ui/canvas-foot";
import { foldGuideTops } from "../src/render/overlay";
import { videoAllowed } from "../src/ui/page-bg-panel";
import { regenAllowed } from "../src/ui/menu-bar";
import { templatesAllowed } from "../src/ui/tool-rail";

const foldout = { format: "foldout" } as BannerConfig;
const mag = {} as BannerConfig;
const st = (mode: string, cfg: BannerConfig) => initialState([], mode, cfg);

describe("Fold-out UI gates", () => {
  it("entrance: foldout offers dissolve | fade, magazine deal | fade", () => {
    expect(entranceOptions(foldout).map((o) => o.value)).toEqual(["dissolve", "fade"]);
    expect(entranceOptions(mag).map((o) => o.value)).toEqual(["deal", "fade"]);
    expect(effectiveEntrance(foldout)).toBe("dissolve");
    expect(effectiveEntrance({ ...foldout, entrance: "fade" })).toBe("fade");
    expect(effectiveEntrance({ ...mag, entrance: "dissolve" })).toBe("deal");
  });
  it("pager hidden for foldout", () => {
    expect(pagerVisible(st("mobile", foldout))).toBe(false);
    expect(pagerVisible(st("mobile", mag))).toBe(true);
    expect(pagerVisible(st("300x250", mag))).toBe(false);
  });
  it("fold guides drawn for foldout on the expanded mode only", () => {
    expect(foldGuideTops(st("mobile", foldout))).toEqual([100 / 3, 200 / 3]);
    expect(foldGuideTops(st("300x250", foldout))).toEqual([]);
    expect(foldGuideTops(st("mobile", mag))).toEqual([]);
  });
  it("video controls disabled for foldout", () => {
    expect(videoAllowed(foldout)).toBe(false);
    expect(videoAllowed(mag)).toBe(true);
  });
  it("regenerate disabled for foldout", () => {
    expect(regenAllowed(foldout)).toBe(false);
    expect(regenAllowed(mag)).toBe(true);
  });
  it("layout templates disabled on the foldout expanded tab only", () => {
    expect(templatesAllowed(st("mobile", foldout))).toBe(false);
    expect(templatesAllowed(st("300x250", foldout))).toBe(true);
    expect(templatesAllowed(st("mobile", mag))).toBe(true);
  });
});
