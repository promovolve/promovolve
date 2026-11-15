import { afterEach, describe, expect, it } from "vitest";
import { setLang } from "../src/i18n";
import { initialState, setSelection } from "../src/state";
import { scopeLabel } from "../src/selection-model";
import { statusWord, weightText } from "../src/ui/canvas-foot";

afterEach(() => setLang(null));

describe("chrome labels in Japanese", () => {
  it("names the fanout statuses in plain words", () => {
    setLang("ja");
    expect([statusWord("authored"), statusWord("generated"), statusWord("empty")]).toEqual(["手動", "自動", "未作成"]);
  });

  it("calls the weight the file size", () => {
    setLang("ja");
    expect(weightText(38, "ok")).toBe("容量 38 KB / 200 KB");
    expect(weightText(250, "err")).toBe("容量 250 KB / 200 KB・上限超過");
  });

  it("says what the panel shows with nothing selected", () => {
    setLang("ja");
    expect(scopeLabel(setSelection(initialState([{ layout: [], banners: {} }], "mobile"), []))).toBe("バナーと 1 ページ目");
  });

  it("keeps English in English", () => {
    setLang("en");
    expect(statusWord("authored")).toBe("Edited");
    expect(weightText(38, "ok")).toBe("File size 38 KB / 200 KB");
  });
});
