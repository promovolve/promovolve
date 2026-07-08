import { IDBFactory } from "fake-indexeddb";
import { beforeEach, describe, expect, it, vi } from "vitest";

// Each test gets a fresh fake-indexeddb factory (so the `promovolve-dogear`
// database starts empty) and a fresh module instance (so the storage
// module's internal `dbPromise` cache is not reused across tests).
async function loadStorage(): Promise<typeof import("../src/dogear-storage")> {
  vi.resetModules();
  return await import("../src/dogear-storage");
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});

// All tests construct Pins with the same default-TTL semantics the
// bootstrap uses when the server didn't supply a campaign endAt: 7
// days from foldedAt. Local helper so tests don't have to import the
// production pinExpiresAt() (which is exercised separately).
const SEVEN_DAYS = 7 * 24 * 60 * 60 * 1000;
const defaultExpiry = (foldedAt: number): number => foldedAt + SEVEN_DAYS;

describe("dogear-storage", () => {

  it("setPin → getPin round-trips the same record", async () => {
    const { setPin, getPin } = await loadStorage();
    const foldedAt = Date.now();
    await setPin({
      slotId:     "leader-top",
      creativeId: "ad_7f3a9b",
      page:       2,
      foldedAt,
      expiresAt:  defaultExpiry(foldedAt),
    });
    const pin = await getPin("leader-top");
    expect(pin).not.toBeNull();
    expect(pin?.slotId).toBe("leader-top");
    expect(pin?.creativeId).toBe("ad_7f3a9b");
    expect(pin?.page).toBe(2);
  });

  it("getPin returns null for an unknown slot", async () => {
    const { getPin } = await loadStorage();
    expect(await getPin("never-folded")).toBeNull();
  });

  it("setPin overwrites an existing record on refold", async () => {
    const { setPin, getPin } = await loadStorage();
    const t0 = Date.now() - 5000;
    const t1 = Date.now() - 1000;
    await setPin({ slotId: "s1", creativeId: "c1", page: 0, foldedAt: t0, expiresAt: defaultExpiry(t0) });
    await setPin({ slotId: "s1", creativeId: "c1", page: 2, foldedAt: t1, expiresAt: defaultExpiry(t1) });
    const pin = await getPin("s1");
    expect(pin?.page).toBe(2);
    expect(pin?.foldedAt).toBe(t1);
  });

  it("getPin returns null and deletes a record older than 7 days (lazy expiry)", async () => {
    const longAgo = Date.now() - SEVEN_DAYS - 1000;
    const storage = await loadStorage();
    await storage.setPin({ slotId: "s1", creativeId: "c1", page: 0, foldedAt: longAgo, expiresAt: defaultExpiry(longAgo) });

    expect(await storage.getPin("s1")).toBeNull();
    // Read again — pin should already be gone (deleted lazily on the first read).
    expect(await storage.getPin("s1")).toBeNull();
  });

  it("getPin keeps a record under 7 days old (recently seen)", async () => {
    // Pre-existing test updated for the lastSeenAt idle sweep: a pin
    // folded 6 days ago survives the 7-day expiry ONLY if the reader
    // re-encountered it within the 24h idle window — an old fold with
    // no recent sighting is use-it-or-lose-it swept by design.
    const SIX_DAYS = 6 * 24 * 60 * 60 * 1000;
    const recent = Date.now() - SIX_DAYS;
    const storage = await loadStorage();
    await storage.setPin({
      slotId: "s1", creativeId: "c1", page: 0,
      foldedAt: recent, expiresAt: defaultExpiry(recent),
      lastSeenAt: Date.now() - 1000,
    });
    const pin = await storage.getPin("s1");
    expect(pin).not.toBeNull();
    expect(pin?.foldedAt).toBe(recent);
  });

  it("clearPin removes the record", async () => {
    const storage = await loadStorage();
    const foldedAt = Date.now();
    await storage.setPin({ slotId: "s1", creativeId: "c1", page: 0, foldedAt, expiresAt: defaultExpiry(foldedAt) });
    expect(await storage.getPin("s1")).not.toBeNull();
    await storage.clearPin("s1");
    expect(await storage.getPin("s1")).toBeNull();
  });

  it("clearPin is a no-op for an unknown slot", async () => {
    const { clearPin } = await loadStorage();
    await expect(clearPin("never-folded")).resolves.toBeUndefined();
  });

  it("sweepExpired deletes only records older than 7 days", async () => {
    const now = Date.now();
    const storage = await loadStorage();
    const old1Folded = now - SEVEN_DAYS - 1;
    const old2Folded = now - SEVEN_DAYS - 60_000;
    const freshFolded = now - 1000;
    await storage.setPin({ slotId: "old1",  creativeId: "c1", page: 0, foldedAt: old1Folded,  expiresAt: defaultExpiry(old1Folded) });
    await storage.setPin({ slotId: "old2",  creativeId: "c2", page: 1, foldedAt: old2Folded,  expiresAt: defaultExpiry(old2Folded) });
    await storage.setPin({ slotId: "fresh", creativeId: "c3", page: 2, foldedAt: freshFolded, expiresAt: defaultExpiry(freshFolded) });

    await storage.sweepExpired();

    expect(await storage.getPin("old1")).toBeNull();
    expect(await storage.getPin("old2")).toBeNull();
    expect(await storage.getPin("fresh")).not.toBeNull();
  });

  it("sweepExpired is a no-op when the store is empty", async () => {
    const { sweepExpired } = await loadStorage();
    await expect(sweepExpired()).resolves.toBeUndefined();
  });

  it("getPin / setPin / clearPin / sweepExpired no-op when indexedDB is unavailable", async () => {
    // Simulate the IndexedDB-unavailability fallback by stripping the
    // global. Per spec design constraints, the spread still works in
    // this case — folding is silently disabled.
    vi.stubGlobal("indexedDB", undefined);
    const storage = await loadStorage();

    await expect(storage.getPin("s1")).resolves.toBeNull();
    const foldedAt = Date.now();
    await expect(storage.setPin({ slotId: "s1", creativeId: "c1", page: 0, foldedAt, expiresAt: defaultExpiry(foldedAt) })).resolves.toBeUndefined();
    await expect(storage.clearPin("s1")).resolves.toBeUndefined();
    await expect(storage.sweepExpired()).resolves.toBeUndefined();

    vi.unstubAllGlobals();
  });

  it("clearPinsByCreativeIds deletes matching pins + counted records, leaves others", async () => {
    // The server's stalePins signal: pins whose creative is gone or
    // whose slot was renamed away. Matching is by creativeId (pins are
    // keyed by slotId), and non-stale pins must survive untouched.
    const storage = await loadStorage();
    const t = Date.now();
    await storage.setPin({ slotId: "s1", creativeId: "stale-1", page: 0, foldedAt: t, expiresAt: defaultExpiry(t) });
    await storage.setPin({ slotId: "s2", creativeId: "alive-1", page: 0, foldedAt: t, expiresAt: defaultExpiry(t) });
    await storage.markCounted("stale-1", defaultExpiry(t));
    await storage.markCounted("alive-1", defaultExpiry(t));

    await storage.clearPinsByCreativeIds(["stale-1", "never-pinned"]);

    expect(await storage.getPin("s1")).toBeNull();
    expect(await storage.wasCounted("stale-1")).toBe(false);
    const survivor = await storage.getPin("s2");
    expect(survivor?.creativeId).toBe("alive-1");
    expect(await storage.wasCounted("alive-1")).toBe(true);
  });

  it("clearPinsByCreativeIds with an empty list is a no-op", async () => {
    const storage = await loadStorage();
    const t = Date.now();
    await storage.setPin({ slotId: "s1", creativeId: "c1", page: 0, foldedAt: t, expiresAt: defaultExpiry(t) });
    await storage.clearPinsByCreativeIds([]);
    expect((await storage.getPin("s1"))?.creativeId).toBe("c1");
  });

  it("setPin then clearPin then setPin (refold flow) works end-to-end", async () => {
    // Spec scenario: reader folds → unfolds → folds again. Each fold is
    // an independent CPF event; the IDB store records the latest.
    const storage = await loadStorage();
    const t0 = Date.now() - 5000;
    const t1 = Date.now() - 1000;
    await storage.setPin({ slotId: "s1", creativeId: "c1", page: 0, foldedAt: t0, expiresAt: defaultExpiry(t0) });
    await storage.clearPin("s1");
    expect(await storage.getPin("s1")).toBeNull();
    await storage.setPin({ slotId: "s1", creativeId: "c1", page: 1, foldedAt: t1, expiresAt: defaultExpiry(t1) });
    const pin = await storage.getPin("s1");
    expect(pin?.page).toBe(1);
    expect(pin?.foldedAt).toBe(t1);
  });
});
