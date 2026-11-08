import { describe, expect, it } from "vitest";
import { initialState } from "../src/state";
import type { BannerConfig } from "../src/types";
import { entranceVisible } from "../src/ui/banner-config-panel";
import { pagerVisible } from "../src/ui/canvas-foot";
import { foldGuideTops } from "../src/render/overlay";
import { videoAllowed } from "../src/ui/page-bg-panel";
import { regenAllowed } from "../src/ui/menu-bar";

const map = { format: "map" } as BannerConfig;
const mag = {} as BannerConfig;
const st = (mode: string, cfg: BannerConfig) => initialState([], mode, cfg);

describe("Map UI gates", () => {
  it("entrance row hidden for map, shown for magazine", () => {
    expect(entranceVisible(map)).toBe(false);
    expect(entranceVisible(mag)).toBe(true);
  });
  it("pager hidden for map", () => {
    expect(pagerVisible(st("mobile", map))).toBe(false);
    expect(pagerVisible(st("mobile", mag))).toBe(true);
    expect(pagerVisible(st("300x250", mag))).toBe(false);
  });
  it("fold guides drawn for map on the expanded mode only", () => {
    expect(foldGuideTops(st("mobile", map))).toEqual([100 / 3, 200 / 3]);
    expect(foldGuideTops(st("300x250", map))).toEqual([]);
    expect(foldGuideTops(st("mobile", mag))).toEqual([]);
  });
  it("video controls disabled for map", () => {
    expect(videoAllowed(map)).toBe(false);
    expect(videoAllowed(mag)).toBe(true);
  });
  it("regenerate disabled for map", () => {
    expect(regenAllowed(map)).toBe(false);
    expect(regenAllowed(mag)).toBe(true);
  });
});
