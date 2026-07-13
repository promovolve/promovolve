import { IDBFactory } from "fake-indexeddb";
import { beforeEach, describe, expect, it, vi } from "vitest";

// The client half of the pin-cleanup-on-revoke contract (the e2e owed
// since the feature shipped): a serve response carrying
// dogear={honored:false, reason:"creative_removed"} — which the server
// only emits for true revocations — must clear the slot's IndexedDB pin
// AND its fold-count dedup record; every other signal retains the pin.
// Honored pins flag every beacon URL dogeared=1 ($0 re-encounter).

async function load(): Promise<{
  resp: typeof import("../src/dogear-response");
  storage: typeof import("../src/dogear-storage");
}> {
  vi.resetModules();
  return {
    resp: await import("../src/dogear-response"),
    storage: await import("../src/dogear-storage"),
  };
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});

const SEVEN_DAYS = 7 * 24 * 60 * 60 * 1000;

async function seedPin(storage: typeof import("../src/dogear-storage")): Promise<void> {
  const foldedAt = Date.now();
  await storage.setPin({
    slotId: "slot-1",
    creativeId: "cr-1",
    page: 2,
    foldedAt,
    lastSeenAt: foldedAt,
    expiresAt: foldedAt + SEVEN_DAYS,
  });
  await storage.markCounted("cr-1");
}

const winner = (dogear?: { honored: boolean; reason?: string }) => ({
  impUrl: "https://ads.example/v1/imp?tok=abc",
  clickUrl: "https://ads.example/v1/click?tok=abc",
  ctaUrl: "https://ads.example/v1/cta?tok=abc",
  dogear,
});

describe("processDogearResponse", () => {
  it("creative_removed clears the pin and the fold-count dedup record", async () => {
    const { resp, storage } = await load();
    await seedPin(storage);
    const pin = (await storage.getPin("slot-1"))!;

    const out = await resp.processDogearResponse(
      "slot-1", winner({ honored: false, reason: "creative_removed" }), pin);

    expect(out.pinnedPage).toBeNull();
    expect(await storage.getPin("slot-1")).toBeNull();
    expect(await storage.wasCounted("cr-1")).toBe(false); // resurrection refolds count fresh
    expect(out.impUrl).not.toContain("dogeared=1"); // fresh serve, billable
  });

  it("campaign_inactive / budget_exhausted / dogear_disabled RETAIN the pin", async () => {
    const { resp, storage } = await load();
    await seedPin(storage);
    const pin = (await storage.getPin("slot-1"))!;

    for (const reason of ["campaign_inactive", "budget_exhausted", "dogear_disabled"]) {
      const out = await resp.processDogearResponse("slot-1", winner({ honored: false, reason }), pin);
      expect(out.pinnedPage).toBeNull();
      expect(await storage.getPin("slot-1")).not.toBeNull();
      expect(await storage.wasCounted("cr-1")).toBe(true);
    }
  });

  it("honored pin flags all three beacon URLs and forwards the pinned page", async () => {
    const { resp, storage } = await load();
    await seedPin(storage);
    const pin = (await storage.getPin("slot-1"))!;

    const out = await resp.processDogearResponse("slot-1", winner({ honored: true }), pin);

    expect(out.impUrl).toContain("&dogeared=1");
    expect(out.clickUrl).toContain("&dogeared=1");
    expect(out.ctaUrl).toContain("&dogeared=1");
    expect(out.pinnedPage).toBe(2);
    expect(await storage.getPin("slot-1")).not.toBeNull();
  });

  it("no dogear field → passthrough, pin untouched", async () => {
    const { resp, storage } = await load();
    await seedPin(storage);
    const out = await resp.processDogearResponse("slot-1", winner(undefined), undefined);
    expect(out.impUrl).toBe("https://ads.example/v1/imp?tok=abc");
    expect(out.pinnedPage).toBeNull();
    expect(await storage.getPin("slot-1")).not.toBeNull();
  });
});

describe("clearRemovedPin (the no-winner slot path)", () => {
  it("clears on creative_removed even with winner=None", async () => {
    const { resp, storage } = await load();
    await seedPin(storage);
    const pin = (await storage.getPin("slot-1"))!;

    const cleared = await resp.clearRemovedPin(
      "slot-1", { honored: false, reason: "creative_removed" }, pin);

    expect(cleared).toBe(true);
    expect(await storage.getPin("slot-1")).toBeNull();
    expect(await storage.wasCounted("cr-1")).toBe(false);
  });

  it("missing dogear (transient pool miss, Spray omits None) is a no-op", async () => {
    const { resp, storage } = await load();
    await seedPin(storage);
    expect(await resp.clearRemovedPin("slot-1", undefined, undefined)).toBe(false);
    expect(await storage.getPin("slot-1")).not.toBeNull();
  });
});

describe("isCreativeRemoved", () => {
  it("matches only the exact revocation signal", async () => {
    const { resp } = await load();
    expect(resp.isCreativeRemoved({ honored: false, reason: "creative_removed" })).toBe(true);
    expect(resp.isCreativeRemoved({ honored: true, reason: "creative_removed" })).toBe(false);
    expect(resp.isCreativeRemoved({ honored: false, reason: "campaign_inactive" })).toBe(false);
    expect(resp.isCreativeRemoved(undefined)).toBe(false);
    expect(resp.isCreativeRemoved(null)).toBe(false);
  });
});
