import { describe, expect, it } from "vitest";
import { applyKitToItem } from "../src/template-apply";
import type { BrandKit } from "../src/brand-kit";
import type { LayoutItem } from "../src/types";

// Guards that brand-kit fonts actually reach text items — without this
// wiring the kit's fonts[] sat unused in localStorage and the live ad
// always rendered the system default. fonts[0] is the heading face,
// fonts[1] the body face; both are pre-snapped to renderable families.
const KIT: BrandKit = {
  name: "From landing page",
  colors: [
    { name: "Brand", value: "#102030" },
    { name: "Text", value: "#111111" },
    { name: "Accent", value: "#ff6600" },
    { name: "Background", value: "#ffffff" },
  ],
  fonts: ["Georgia", "sans-serif"],
};

function textItem(role: string): LayoutItem {
  return { type: "text", role, text: "x" } as unknown as LayoutItem;
}

describe("applyKitToItem font wiring", () => {
  it("puts the heading font on headline items", () => {
    const out = applyKitToItem(textItem("headline"), KIT) as LayoutItem & { fontFamily?: string };
    expect(out.fontFamily).toBe("Georgia");
  });

  it("puts the body font on body/subheadline items", () => {
    expect((applyKitToItem(textItem("body"), KIT) as { fontFamily?: string }).fontFamily).toBe("sans-serif");
    expect((applyKitToItem(textItem("subheadline"), KIT) as { fontFamily?: string }).fontFamily).toBe("sans-serif");
  });

  it("falls back to the heading font when the kit has only one font", () => {
    const oneFont: BrandKit = { ...KIT, fonts: ["Georgia"] };
    expect((applyKitToItem(textItem("body"), oneFont) as { fontFamily?: string }).fontFamily).toBe("Georgia");
  });

  it("applies both the role colour and the role font together", () => {
    const out = applyKitToItem(textItem("headline"), KIT) as LayoutItem & { fontFamily?: string; color?: string };
    expect(out.color).toBe("#102030");   // Brand
    expect(out.fontFamily).toBe("Georgia");
  });

  it("leaves fontFamily untouched when the kit has no fonts", () => {
    const noFonts: BrandKit = { ...KIT, fonts: [] };
    expect((applyKitToItem(textItem("headline"), noFonts) as { fontFamily?: string }).fontFamily).toBeUndefined();
  });

  it("does not set a font on roles without a font mapping (cta shape)", () => {
    const cta = { type: "rect", role: "cta" } as unknown as LayoutItem;
    const out = applyKitToItem(cta, KIT) as LayoutItem & { fontFamily?: string; fill?: string };
    expect(out.fontFamily).toBeUndefined();
    expect(out.fill).toBe("#ff6600"); // Accent still applied
  });
});
