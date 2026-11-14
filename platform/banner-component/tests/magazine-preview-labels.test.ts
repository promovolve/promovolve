import { describe, expect, it } from "vitest";
import { previewLabels } from "../src/preview-labels";

describe("previewLabels", () => {
  it("is Japanese for ja and ja-JP", () => {
    expect(previewLabels("ja-JP")).toEqual({ ad: "広告", pc: "PC", mobile: "モバイル" });
    expect(previewLabels("ja")).toEqual({ ad: "広告", pc: "PC", mobile: "モバイル" });
  });
  it("is English otherwise", () => {
    expect(previewLabels("")).toEqual({ ad: "Advertisement", pc: "PC", mobile: "Mobile" });
    expect(previewLabels("en")).toEqual({ ad: "Advertisement", pc: "PC", mobile: "Mobile" });
    expect(previewLabels("jav")).toEqual({ ad: "Advertisement", pc: "PC", mobile: "Mobile" });
  });
});
