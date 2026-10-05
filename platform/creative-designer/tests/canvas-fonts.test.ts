import { describe, expect, it } from "vitest";
import { firstFontUrl, googleSubsetCssUrl } from "../src/canvas-fonts";

describe("Google per-text subset fallback (designer preview, GH #235)", () => {
  it("builds the same css2 request the server provisions from", () => {
    expect(googleSubsetCssUrl("Noto Sans JP", 900, "当日でもお得に")).toBe(
      "https://fonts.googleapis.com/css2?family=Noto%20Sans%20JP:wght@900&display=swap&text=" +
        encodeURIComponent("当日でもお得に"),
    );
  });

  it("drops the weight when retrying a single-weight family", () => {
    expect(googleSubsetCssUrl("Prata", 700, "あ", false)).toBe(
      "https://fonts.googleapis.com/css2?family=Prata&display=swap&text=" + encodeURIComponent("あ"),
    );
  });

  it("takes the font url out of a css2 response", () => {
    const css = `@font-face {
  font-family: 'Noto Sans JP';
  font-weight: 900;
  src: url(https://fonts.gstatic.com/l/font?kit=abc123&skey=x&v=v55) format('woff2');
}`;
    expect(firstFontUrl(css)).toBe("https://fonts.gstatic.com/l/font?kit=abc123&skey=x&v=v55");
    expect(firstFontUrl("/* nothing */")).toBeNull();
  });
});
