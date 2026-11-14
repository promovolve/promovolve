import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLang } from "../src/i18n";
import { WRITING_LABELS, fontDisplayName, isHashName, optionEntries } from "../src/display-labels";
import { itemName } from "../src/selection-model";
import type { LayoutItem } from "../src/types";

beforeEach(() => setLang("ja"));
afterEach(() => setLang(null));

describe("optionEntries", () => {
  it("keeps English values and shows translated labels", () => {
    expect(optionEntries(["horizontal-tb", "vertical-rl"], WRITING_LABELS)).toEqual([
      { value: "horizontal-tb", label: "横書き" },
      { value: "vertical-rl", label: "縦書き" },
    ]);
  });
  it("shows the value when there is no label", () => {
    expect(optionEntries(["100"], undefined)).toEqual([{ value: "100", label: "100" }]);
  });
});

describe("fontDisplayName", () => {
  it("shows the first family without quotes", () => {
    expect(fontDisplayName('"Noto Sans JP", sans-serif')).toBe("Noto Sans JP");
    expect(fontDisplayName("Montserrat, sans-serif")).toBe("Montserrat");
  });
  it("names the system stacks", () => {
    expect(fontDisplayName("-apple-system, sans-serif")).toBe("システム ゴシック");
    expect(fontDisplayName("serif")).toBe("システム 明朝");
  });
});

describe("isHashName", () => {
  it("spots hash file names, with or without a query", () => {
    expect(isHashName("c58aed219f1018c6222.jpg")).toBe(true);
    expect(isHashName("c58aed219f1018c6222.jpg?v=3")).toBe(true);
    expect(isHashName("hero.jpg")).toBe(false);
  });
});

describe("itemName (layer names)", () => {
  it("shows Image for a hash-named image", () => {
    expect(itemName({ type: "image", src: "https://cdn.example/a/c58aed219f1018c6222.jpg?v=3", left: 0, top: 0 } as LayoutItem)).toBe("画像");
  });
  it("shows the field label for a field-bound text with no text", () => {
    expect(itemName({ type: "text", field: "headline", left: 0, top: 0 } as LayoutItem)).toBe("見出し");
  });
  it("shows the text itself when there is text", () => {
    expect(itemName({ type: "text", text: "次の発見", left: 0, top: 0 } as LayoutItem)).toBe("次の発見");
  });
});
