import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { setLang } from "../src/i18n";
import { WRITING_LABELS, displayFileName, fontDisplayName, isHashName, optionEntries, uniqueFonts } from "../src/display-labels";
import { itemName, typeName } from "../src/selection-model";
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

describe("displayFileName", () => {
  it("hides hashed asset names", () => {
    expect(displayFileName("https://cdn.example/assets/c58aed219f1018c6222.mp4")).toBeNull();
    expect(displayFileName("/assets/c58aed219f1018c6222.webp?v=3")).toBeNull();
    expect(displayFileName("")).toBeNull();
  });
  it("keeps a real file name, without its query or fragment", () => {
    expect(displayFileName("/media/hero.jpg?v=3")).toBe("hero.jpg");
    expect(displayFileName("https://x.test/a/loop.mp4#t=2")).toBe("loop.mp4");
  });
});

describe("uniqueFonts", () => {
  it("keeps the first stack for each display name", () => {
    expect(uniqueFonts(["Inter", "Georgia", "Inter, sans-serif", "sans-serif", "Lato, sans-serif"]))
      .toEqual(["Inter", "Georgia", "sans-serif", "Lato, sans-serif"]);
  });
});

describe("item names", () => {
  it("drops the query string from a real image file name", () => {
    expect(itemName({ type: "image", src: "/media/hero.jpg?v=3" } as LayoutItem)).toBe("hero.jpg");
  });
  it("names a circle the same in the header and the layer row", () => {
    const circle = { type: "circle", left: 0, top: 0, radius: 5 } as LayoutItem;
    expect(typeName(circle)).toBe("円");
    expect(itemName(circle)).toBe("円");
  });
  it("calls a plain rectangle a shape, not a scrim", () => {
    const rect = { type: "rect", left: 0, top: 0, width: 10, height: 10 } as LayoutItem;
    expect(typeName(rect)).toBe("図形");
    expect(itemName(rect)).toBe("図形");
  });
});
