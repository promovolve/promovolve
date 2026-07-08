import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  EMPTY_KIT,
  clearBrandKit,
  loadBrandKit,
  notifyBrandKitChanged,
  saveBrandKit,
  subscribeBrandKit,
  type BrandKit,
} from "../src/brand-kit";

// Minimal in-memory localStorage stand-in. The real one is undefined
// in vitest's default node environment; we attach this to globalThis
// for the lifetime of each test so the brand-kit module's typeof
// guards see a usable storage.
function installMemoryStorage(): void {
  const store = new Map<string, string>();
  (globalThis as unknown as { localStorage: Storage }).localStorage = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => { store.set(k, v); },
    removeItem: (k: string) => { store.delete(k); },
    clear: () => { store.clear(); },
    get length() { return store.size; },
    key: (i: number) => Array.from(store.keys())[i] ?? null,
  } as Storage;
}

function uninstallStorage(): void {
  delete (globalThis as unknown as { localStorage?: Storage }).localStorage;
}

describe("brand kit storage", () => {
  beforeEach(() => installMemoryStorage());
  afterEach(() => uninstallStorage());

  it("returns the empty kit when nothing's stored", () => {
    expect(loadBrandKit("camp-1")).toEqual(EMPTY_KIT);
  });

  it("roundtrips a saved kit", () => {
    const kit: BrandKit = {
      name: "Acme",
      colors: [{ name: "Brand", value: "#ff0000" }],
      fonts: ["Helvetica"],
    };
    saveBrandKit(kit, "camp-1");
    expect(loadBrandKit("camp-1")).toEqual(kit);
  });

  it("scopes by campaign id (separate kits don't collide)", () => {
    saveBrandKit({ ...EMPTY_KIT, name: "A" }, "camp-A");
    saveBrandKit({ ...EMPTY_KIT, name: "B" }, "camp-B");
    expect(loadBrandKit("camp-A").name).toBe("A");
    expect(loadBrandKit("camp-B").name).toBe("B");
  });

  it("clearBrandKit removes the entry", () => {
    saveBrandKit({ ...EMPTY_KIT, name: "X" }, "camp-1");
    clearBrandKit("camp-1");
    expect(loadBrandKit("camp-1")).toEqual(EMPTY_KIT);
  });

  it("returns empty kit when stored value is malformed", () => {
    localStorage.setItem("promovolve.designer.brandKit.camp-1", "not-json");
    expect(loadBrandKit("camp-1")).toEqual(EMPTY_KIT);
  });

  it("returns empty kit when the parsed shape is wrong", () => {
    localStorage.setItem(
      "promovolve.designer.brandKit.camp-1",
      JSON.stringify({ colors: "not-an-array", fonts: [] }),
    );
    expect(loadBrandKit("camp-1")).toEqual(EMPTY_KIT);
  });

  it("survives missing localStorage entirely", () => {
    uninstallStorage();
    expect(loadBrandKit("camp-1")).toEqual(EMPTY_KIT);
    // saveBrandKit must not throw either.
    expect(() => saveBrandKit({ ...EMPTY_KIT, name: "x" }, "camp-1")).not.toThrow();
  });
});

describe("brand kit subscriptions", () => {
  it("notifies subscribers on changed kits", () => {
    const fn = vi.fn();
    const unsub = subscribeBrandKit(fn);
    const kit: BrandKit = { ...EMPTY_KIT, name: "live" };
    notifyBrandKitChanged(kit);
    expect(fn).toHaveBeenCalledWith(kit);
    unsub();
    notifyBrandKitChanged({ ...EMPTY_KIT, name: "after" });
    expect(fn).toHaveBeenCalledTimes(1);
  });
});
