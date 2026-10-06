import { IDBFactory } from "fake-indexeddb";
import { beforeEach, describe, expect, it, vi } from "vitest";

// Reach reporting (GH #238): at most one report per campaign per day, the
// stored day sent back unchanged, freshness rotated per Chrome updater v4.

type Storage = typeof import("../src/dogear-storage");
let storage: Storage;

beforeEach(async () => {
  globalThis.indexedDB = new IDBFactory();
  vi.resetModules();
  storage = await import("../src/dogear-storage");
});

const HEX32 = /^[0-9a-f]{32}$/;

describe("takeReachReport", () => {
  it("reports 'never' first, then nothing more that day", async () => {
    const first = await storage.takeReachReport("c1", 20_000);
    expect(first?.prev).toBeNull();
    expect(first?.fresh).toMatch(HEX32);
    expect(await storage.takeReachReport("c1", 20_000)).toBeNull();
  });

  it("sends back the stored day, and the freshness value stored with it", async () => {
    const d1 = await storage.takeReachReport("c1", 20_000);
    const d3 = await storage.takeReachReport("c1", 20_002);
    const d4 = await storage.takeReachReport("c1", 20_003);
    expect(d3?.prev).toBe(20_000);
    expect(d4?.prev).toBe(20_002);
    // Each value is sent once: rotated on every new day.
    const sent = [d1?.fresh, d3?.fresh, d4?.fresh];
    expect(new Set(sent).size).toBe(3);
  });

  it("lets only one of two same-campaign slots report", async () => {
    const [a, b] = await Promise.all([
      storage.takeReachReport("c1", 20_000),
      storage.takeReachReport("c1", 20_000),
    ]);
    expect([a, b].filter(Boolean)).toHaveLength(1);
  });

  it("keeps campaigns apart", async () => {
    expect(await storage.takeReachReport("c1", 20_000)).not.toBeNull();
    expect(await storage.takeReachReport("c2", 20_000)).not.toBeNull();
  });

  it("says nothing when the day goes backwards (timezone change)", async () => {
    await storage.takeReachReport("c1", 20_005);
    expect(await storage.takeReachReport("c1", 20_004)).toBeNull();
  });
});

describe("reportReach", () => {
  it("appends prev and fresh to the signed URL, once per day", async () => {
    const urls: string[] = [];
    vi.stubGlobal("Image", class { set src(v: string) { urls.push(v); } });
    const { reportReach } = await import("../src/reach");
    const r = { campaignId: "c1", day: 20_000, url: "https://ads.example/v1/reach?tok=x" };
    await reportReach(r);
    await reportReach(r);
    await reportReach({ ...r, day: 20_001 });
    expect(urls).toHaveLength(2);
    expect(urls[0]).toMatch(/^https:\/\/ads\.example\/v1\/reach\?tok=x&prev=never&fresh=[0-9a-f]{32}$/);
    expect(urls[1]).toMatch(/&prev=20000&fresh=[0-9a-f]{32}$/);
    vi.unstubAllGlobals();
  });
});
