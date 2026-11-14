import { afterEach, describe, expect, it } from "vitest";
import { lang, setLang, t } from "../src/i18n";
import { JA } from "../src/i18n/ja";

afterEach(() => setLang(null));

describe("t", () => {
  it("returns the key in English", () => { setLang("en"); expect(t("Undo")).toBe("Undo"); });
  it("translates in Japanese", () => { setLang("ja"); expect(t("Undo")).toBe(JA["Undo"]); });
  it("falls back to the key when Japanese has no entry", () => { setLang("ja"); expect(t("zz-missing")).toBe("zz-missing"); });
  it("fills placeholders in order", () => { setLang("en"); expect(t("Page %d / %d", 2, 3)).toBe("Page 2 / 3"); });
  it("fills placeholders in Japanese", () => { setLang("ja"); expect(t("Page %d / %d", 2, 3)).toBe("2 / 3 ページ"); });
  it("resolves ja-JP and JA to Japanese, anything else to English", () => {
    setLang("ja-JP"); expect(lang()).toBe("ja");
    setLang("JA"); expect(lang()).toBe("ja");
    setLang("fr"); expect(lang()).toBe("en");
    setLang(null); expect(lang()).toBe("en"); // node: no document
  });
});
