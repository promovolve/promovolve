// @vitest-environment jsdom
//
// applyTextureBg paints a full-bleed image layer behind the layout in
// every mode. These guard the CSS it emits per mode/opacity/blend and
// the stacking order relative to the video and the layout items — the
// invariant that a texture reads above the bg color / video but below
// every layout item.

import { beforeEach, describe, expect, it } from "vitest";
import { applyTextureBg } from "../src/banner";

let container: HTMLElement;

beforeEach(() => {
  container = document.createElement("div");
});

describe("applyTextureBg", () => {
  it("returns null and paints nothing when absent or src is empty", () => {
    expect(applyTextureBg(container, undefined, null)).toBeNull();
    expect(applyTextureBg(container, { src: "", mode: "tile" }, null)).toBeNull();
    expect(container.children.length).toBe(0);
  });

  it("tile mode repeats at natural size by default", () => {
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "tile" }, null)!;
    expect(el.style.backgroundRepeat).toBe("repeat");
    expect(el.style.backgroundSize).toBe("auto");
    expect(el.style.backgroundImage).toBe('url("https://cdn/x.webp")');
    expect(el.style.position).toBe("absolute");
    expect(el.style.pointerEvents).toBe("none");
  });

  it("tile mode uses a fixed px tile size when scale is set", () => {
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "tile", scale: 64 }, null)!;
    expect(el.style.backgroundRepeat).toBe("repeat");
    expect(el.style.backgroundSize).toBe("64px");
  });

  it("ignores a non-positive scale (falls back to natural)", () => {
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "tile", scale: 0 }, null)!;
    expect(el.style.backgroundSize).toBe("auto");
  });

  it("cover mode scales one copy to cover, centered by default, no repeat", () => {
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "cover" }, null)!;
    expect(el.style.backgroundSize).toBe("cover");
    expect(el.style.backgroundRepeat).toBe("no-repeat");
    expect(el.style.backgroundPosition).toBe("50% 50%");
  });

  it("cover mode honors the focal point", () => {
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "cover", focusX: 20, focusY: 80 }, null)!;
    expect(el.style.backgroundPosition).toBe("20% 80%");
  });

  it("clamps an out-of-range focal point to 0..100", () => {
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "cover", focusX: -10, focusY: 150 }, null)!;
    expect(el.style.backgroundPosition).toBe("0% 100%");
  });

  it("applies opacity and a non-normal blend, omitting blend when normal", () => {
    const withBlend = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "tile", opacity: 0.4, blend: "multiply" }, null)!;
    expect(withBlend.style.opacity).toBe("0.4");
    expect(withBlend.style.mixBlendMode).toBe("multiply");

    const fresh = document.createElement("div");
    const normal = applyTextureBg(fresh, { src: "https://cdn/x.webp", mode: "tile", blend: "normal" }, null)!;
    expect(normal.style.opacity).toBe("1");
    expect(normal.style.mixBlendMode).toBe("");
  });

  it("quotes the url() so paths with parens/commas can't break the token", () => {
    const el = applyTextureBg(container, { src: "https://cdn/a(b),c.webp", mode: "tile" }, null)!;
    expect(el.style.backgroundImage).toBe('url("https://cdn/a(b),c.webp")');
  });

  it("with no video, inserts as the first child (above bg color, below items)", () => {
    const item = container.appendChild(document.createElement("span"));
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "tile" }, null)!;
    expect(container.firstChild).toBe(el);
    // The pre-existing layout item stays after the texture in DOM order,
    // so it paints on top.
    expect(el.nextSibling).toBe(item);
  });

  it("with a video, stacks just above the video and below the items", () => {
    // Mirror the real call: video is first child, items follow.
    const video = container.appendChild(document.createElement("video"));
    const item = container.appendChild(document.createElement("span"));
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "tile" }, video)!;
    // DOM order must be: video, texture, item → video below, item on top.
    expect(Array.from(container.children)).toEqual([video, el, item]);
  });

  it("falls back to first-child when afterNode isn't in the container", () => {
    const orphan = document.createElement("video");
    const item = container.appendChild(document.createElement("span"));
    const el = applyTextureBg(container, { src: "https://cdn/x.webp", mode: "tile" }, orphan)!;
    expect(container.firstChild).toBe(el);
    expect(el.nextSibling).toBe(item);
  });
});
