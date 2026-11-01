import { describe, expect, it } from "vitest";
import { LEGACY_THEME_KEY, THEME_KEY, migrateThemeKey, tokens, type ThemeStorage } from "../src/ui/tokens";

function fakeStorage(init: Record<string, string> = {}): ThemeStorage & { map: Map<string, string> } {
  const map = new Map(Object.entries(init));
  return {
    map,
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
  };
}

describe("migrateThemeKey", () => {
  it("copies a legacy choice when the dashboard key is absent", () => {
    const s = fakeStorage({ [LEGACY_THEME_KEY]: "dark" });
    migrateThemeKey(s);
    expect(s.map.get(THEME_KEY)).toBe("dark");
    expect(s.map.has(LEGACY_THEME_KEY)).toBe(false);
  });

  it("keeps the dashboard choice when both exist", () => {
    const s = fakeStorage({ [THEME_KEY]: "light", [LEGACY_THEME_KEY]: "dark" });
    migrateThemeKey(s);
    expect(s.map.get(THEME_KEY)).toBe("light");
    expect(s.map.has(LEGACY_THEME_KEY)).toBe(false);
  });

  it("ignores an invalid legacy value", () => {
    const s = fakeStorage({ [LEGACY_THEME_KEY]: "purple" });
    migrateThemeKey(s);
    expect(s.map.has(THEME_KEY)).toBe(false);
    expect(s.map.has(LEGACY_THEME_KEY)).toBe(false);
  });

  it("does nothing when neither key exists", () => {
    const s = fakeStorage();
    migrateThemeKey(s);
    expect(s.map.size).toBe(0);
  });

  it("survives storage that throws", () => {
    const boom = (): never => {
      throw new Error("blocked");
    };
    expect(() => migrateThemeKey({ getItem: boom, setItem: boom, removeItem: boom })).not.toThrow();
  });
});

describe("tokens", () => {
  it("exposes focus tokens and no amber", () => {
    expect(tokens.focus).toBe("var(--cd-focus)");
    expect(tokens.field).toBe("var(--cd-field)");
    expect(Object.keys(tokens).some((k) => k.startsWith("amber"))).toBe(false);
    expect(THEME_KEY).toBe("promovolve.dashboard.theme");
  });
});
