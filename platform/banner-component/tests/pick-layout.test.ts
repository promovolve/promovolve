// pickCollapsedLayout — nearest-aspect selection for collapsed banners.
// The invariants: exact sizes win, near-shapes beat the master, the
// master wins for 16:9-ish slots, mobile-expanded never leaks in, and
// legacy keys (whose designer tab was retired) still participate.

import { describe, expect, it } from "vitest";
import { pickCollapsedLayout } from "../src/utils";
import type { LayoutItem } from "../src/types";

const item = (): LayoutItem[] => [{ type: "text", text: "x" } as LayoutItem];

const BUCKETS = {
  "300x250": item(), // 1.20
  "970x250": item(), // 3.88
  "728x90":  item(), // 8.09
  "300x600": item(), // 0.50
  "mobile-expanded": item(),
};

describe("pickCollapsedLayout", () => {
  it("exact size wins with distance zero", () => {
    expect(pickCollapsedLayout(BUCKETS, 300, 250)?.key).toBe("300x250");
    expect(pickCollapsedLayout(BUCKETS, 728, 90)?.key).toBe("728x90");
  });

  it("a near-shape slot picks the nearest bucket, not the master", () => {
    expect(pickCollapsedLayout(BUCKETS, 336, 280)?.key).toBe("300x250"); // 1.20
    expect(pickCollapsedLayout(BUCKETS, 970, 90)?.key).toBe("728x90");   // 10.8 → strip
    expect(pickCollapsedLayout(BUCKETS, 320, 100)?.key).toBe("970x250"); // 3.2 → billboard
    expect(pickCollapsedLayout(BUCKETS, 160, 600)?.key).toBe("300x600"); // 0.27 → tall
  });

  it("returns null (master) when the slot is 16:9-ish", () => {
    expect(pickCollapsedLayout(BUCKETS, 800, 450)).toBeNull();
    expect(pickCollapsedLayout(BUCKETS, 640, 360)).toBeNull();
  });

  it("never picks mobile-expanded for a 9:16 slot", () => {
    // Tall slot: nearest WxH candidate is 300x600 — the mobile reader
    // layout doesn't match the WxH pattern and must not leak in.
    expect(pickCollapsedLayout(BUCKETS, 360, 640)?.key).toBe("300x600");
  });

  it("legacy keys still participate after their tab was retired", () => {
    const legacy = { "336x280": item() };
    expect(pickCollapsedLayout(legacy, 336, 280)?.key).toBe("336x280");
    // ...and beat the master for same-shape slots.
    expect(pickCollapsedLayout(legacy, 300, 250)?.key).toBe("336x280");
  });

  it("ignores empty layouts and handles missing banners", () => {
    expect(pickCollapsedLayout({ "300x250": [] }, 300, 250)).toBeNull();
    expect(pickCollapsedLayout(undefined, 300, 250)).toBeNull();
  });
});
