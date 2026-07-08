// pickExpandedLayout — the expanded reader's portrait-master selection.
// The invariants: the portrait master (banners["mobile-expanded"]) wins
// on every device, and legacy creatives without one fall back (callers
// render page.layout, the 16:9 master, when this returns undefined).

import { describe, expect, it } from "vitest";
import { pickExpandedLayout } from "../src/utils";
import type { LayoutItem, Page } from "../src/types";

const item = (): LayoutItem[] => [{ type: "text", text: "x" } as LayoutItem];

describe("pickExpandedLayout", () => {
  it("returns the portrait master when authored", () => {
    const portrait = item();
    const page = { layout: item(), banners: { "mobile-expanded": portrait } } as unknown as Page;
    expect(pickExpandedLayout(page)).toBe(portrait);
  });

  it("returns undefined for legacy creatives (no portrait master)", () => {
    expect(pickExpandedLayout({ layout: item() } as unknown as Page)).toBeUndefined();
    expect(
      pickExpandedLayout({ layout: item(), banners: { "300x250": item() } } as unknown as Page),
    ).toBeUndefined();
  });

  it("treats an EMPTY portrait layout as unauthored", () => {
    const page = { layout: item(), banners: { "mobile-expanded": [] } } as unknown as Page;
    expect(pickExpandedLayout(page)).toBeUndefined();
  });
});
