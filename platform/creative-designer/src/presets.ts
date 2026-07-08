// Hand-crafted layout templates per IAB banner size. LLMs don't pick
// good compositions for tiny/odd-aspect ad units, so we fill those
// deterministically from the page's fields. The two expanded variants
// (PC 16:9, Mobile 9:16) still go through Gemini — that's where design
// freedom actually matters.
//
// Each template receives the page's flat fields and returns layout
// items in percent coordinates (font-size is % of container height).
// Image items are included when page.img is present; a CTA ("Read
// More") uses page.accent for color.

import type { LayoutItem, Page } from "./types";
import { kitFont, type BrandKit } from "./brand-kit";
import { pickContrast, resolveLayoutColors } from "./color-contrast";

export function presetLayoutFor(mode: string, page: Page, kit?: BrandKit | null): LayoutItem[] | null {
  const fn = PRESETS[mode];
  return fn ? fn(page, kit ?? null) : null;
}

type Preset = (page: Page, kit: BrandKit | null) => LayoutItem[];

// Font roles come from the shared kitFont (brand-kit.ts). EVERY preset —
// the expanded variants, the default collapsed layout (normalize.ts), and
// the explicit IAB-size presets below — consults the kit so the determined
// LP font reaches every surface, expanded and collapsed alike. The banner
// self-hosts the faces (collectExpandedFonts now scans page.layout AND all
// page.banners buckets, so IAB-only weights like the bold tag/CTA load too);
// the system family after the comma in the kit's stack is the always-present
// fallback while the woff2 streams in (display:swap).

const PRESETS: Record<string, Preset> = {
  "expanded": expandedPc,
  "mobile":   expandedMobile,
  "300x250":  mediumRectangle,
  "336x280":  mediumRectangle,
  "970x250":  billboard,
  "728x90":   leaderboard,
  "970x90":   leaderboard,
  "320x50":   leaderboard,
  "320x100":  wideMobile,
  "160x600":  skyscraper,
  "300x600":  skyscraper,
};

// Font source of truth = the expanded view. It's laid out FIRST (see the
// MODES order + the synchronous preset/template fan-out), so by the time
// an IAB-size preset runs, page.layout already holds the expanded
// headline/body text items with their resolved fontFamily. IAB presets
// inherit that here, so collapsed units render the SAME font as the
// preview instead of pulling kit.fonts[role] — which can be an all-caps
// LP display face the expanded view (template/Gemini) never chose, the
// cause of "preview is mixed-case but the delivered banner is ALL CAPS".
// Falls back to the kit role font when page.layout is still empty — i.e.
// while generating the expanded view itself — so the expanded presets
// keep their prior behaviour unchanged.
function expandedFont(
  page: Page,
  field: "headline" | "body",
  kit: BrandKit | null,
  role: number,
  fallback: string,
): string {
  const hit = (page.layout ?? []).find(
    (it) => it.type === "text" && (it as { field?: string }).field === field,
  ) as { fontFamily?: string } | undefined;
  return hit?.fontFamily || kitFont(kit, role, fallback);
}

// Expanded PC (16:9). Magazine hero: image on right 40%, headline +
// sub + body stacked on the left. No tag / CTA — the expanded surface
// is where the user is already engaged so the label and button add
// clutter rather than function.
function expandedPc(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = resolveLayoutColors(page, kit);
  const headFont = expandedFont(page, "headline", kit, 0, "Georgia");
  const bodyFont = expandedFont(page, "body", kit, 1, "sans-serif");
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 54, top: 8, width: 40, height: 84, borderRadius: 1 });
  }
  const col = { left: 6, width: src ? 44 : 88 };
  items.push({
    type: "text", field: "headline", left: col.left, top: 20, width: col.width,
    fontSize: 10, color: c.headline, fontFamily: headFont,
    fontWeight: "bold", textAlign: "left", height: 24, textFit: "shrink",
  });
  items.push({
    type: "text", field: "sub", left: col.left, top: 48, width: col.width,
    fontSize: 4, color: c.sub, fontFamily: bodyFont, textAlign: "left",
    height: 10, textFit: "shrink",
  });
  items.push({
    type: "text", field: "body", left: col.left, top: 62, width: col.width,
    fontSize: 3, color: c.body, fontFamily: bodyFont, textAlign: "left",
    height: 24, textFit: "shrink",
  });
  return items;
}

// Expanded Mobile (9:16). Portrait stack: image top 35%, headline +
// sub + body centered below. No tag / CTA for the same reason as PC.
function expandedMobile(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = resolveLayoutColors(page, kit);
  const headFont = expandedFont(page, "headline", kit, 0, "Georgia");
  const bodyFont = expandedFont(page, "body", kit, 1, "sans-serif");
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 6, top: 6, width: 88, height: 34, borderRadius: 1 });
  }
  const textTop = src ? 46 : 12;
  items.push({
    type: "text", field: "headline", left: 6, top: textTop, width: 88,
    fontSize: 6, color: c.headline, fontFamily: headFont,
    fontWeight: "bold", textAlign: "center", height: 16, textFit: "shrink",
  });
  items.push({
    type: "text", field: "sub", left: 6, top: textTop + 20, width: 88,
    fontSize: 3.2, color: c.sub, fontFamily: bodyFont, textAlign: "center",
    height: 10, textFit: "shrink",
  });
  items.push({
    type: "text", field: "body", left: 6, top: textTop + 34, width: 88,
    fontSize: 2.8, color: c.body, fontFamily: bodyFont, textAlign: "center",
    height: 14, textFit: "shrink",
  });
  return items;
}

const accentOr = (page: Page): string => (typeof page.accent === "string" ? page.accent : "#c4a35a");
const tag = (page: Page): string => typeof page.tag === "string" ? page.tag : "";
const img = (page: Page): string | undefined =>
  typeof page.img === "string" && page.img.length > 0 ? page.img : undefined;

// Medium rectangle (300×250, 336×280). Image left, stacked text right,
// CTA under text. Text wraps in a narrow column.
function mediumRectangle(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const headFont = expandedFont(page, "headline", kit, 0, "Georgia");
  const bodyFont = expandedFont(page, "body", kit, 1, "sans-serif");
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 4, top: 8, width: 42, height: 84, borderRadius: 2 });
  }
  const textLeft = src ? 50 : 6;
  const textWidth = src ? 46 : 88;
  if (tag(page)) {
    items.push({
      type: "text", field: "tag", left: textLeft, top: 14, width: textWidth,
      fontSize: 8, color: accentOr(page), fontFamily: bodyFont,
      fontWeight: "bold", textAlign: "left",
    });
  }
  items.push({
    type: "text", field: "headline", left: textLeft, top: 28, width: textWidth,
    fontSize: 14, color: c.headline, fontFamily: headFont,
    fontWeight: "bold", textAlign: "left", textFit: "shrink", height: 44,
  });
  items.push({
    type: "text", text: "Read More", left: textLeft, top: 78, width: textWidth,
    fontSize: 9, color: accentOr(page), fontFamily: bodyFont,
    fontWeight: "bold", textAlign: "left", ctaTarget: true,
  });
  return items;
}

// Leaderboard (728×90, 970×90, 320×50). Horizontal strip: small image
// on far left, headline centered, CTA on far right.
function leaderboard(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const headFont = expandedFont(page, "headline", kit, 0, "Georgia");
  const bodyFont = expandedFont(page, "body", kit, 1, "sans-serif");
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 2, top: 10, width: 10, height: 80, borderRadius: 2 });
  }
  const textLeft = src ? 14 : 4;
  const textWidth = 66;
  items.push({
    type: "text", field: "headline", left: textLeft, top: 20, width: textWidth,
    fontSize: 42, color: c.headline, fontFamily: headFont,
    fontWeight: "bold", textAlign: "left", height: 60, textFit: "shrink",
  });
  items.push({
    type: "text", text: "Read More", left: 82, top: 32, width: 16,
    fontSize: 28, color: accentOr(page), fontFamily: bodyFont,
    fontWeight: "bold", textAlign: "center", ctaTarget: true,
  });
  return items;
}

// 320×100 large mobile banner. Wider than a leaderboard so we can
// give the headline two lines of breathing room.
function wideMobile(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const headFont = expandedFont(page, "headline", kit, 0, "Georgia");
  const bodyFont = expandedFont(page, "body", kit, 1, "sans-serif");
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 3, top: 10, width: 22, height: 80, borderRadius: 2 });
  }
  const textLeft = src ? 28 : 5;
  const textWidth = 55;
  items.push({
    type: "text", field: "headline", left: textLeft, top: 18, width: textWidth,
    fontSize: 22, color: c.headline, fontFamily: headFont,
    fontWeight: "bold", textAlign: "left", height: 58, textFit: "shrink",
  });
  items.push({
    type: "text", text: "Read More", left: 82, top: 36, width: 16,
    fontSize: 14, color: accentOr(page), fontFamily: bodyFont,
    fontWeight: "bold", textAlign: "center", ctaTarget: true,
  });
  return items;
}

// Billboard (970×250). Hero composition: image on left 40%, text
// stacked on right with tag + headline + CTA.
function billboard(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const headFont = expandedFont(page, "headline", kit, 0, "Georgia");
  const bodyFont = expandedFont(page, "body", kit, 1, "sans-serif");
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 3, top: 8, width: 36, height: 84, borderRadius: 2 });
  }
  const textLeft = src ? 44 : 6;
  const textWidth = src ? 52 : 90;
  if (tag(page)) {
    items.push({
      type: "text", field: "tag", left: textLeft, top: 14, width: textWidth,
      fontSize: 7, color: accentOr(page), fontFamily: bodyFont,
      fontWeight: "bold", textAlign: "left",
    });
  }
  items.push({
    type: "text", field: "headline", left: textLeft, top: 26, width: textWidth,
    fontSize: 18, color: c.headline, fontFamily: headFont,
    fontWeight: "bold", textAlign: "left", height: 50, textFit: "shrink",
  });
  items.push({
    type: "text", text: "Read More", left: textLeft, top: 80, width: textWidth,
    fontSize: 8, color: accentOr(page), fontFamily: bodyFont,
    fontWeight: "bold", textAlign: "left", ctaTarget: true,
  });
  return items;
}

// Skyscraper (160×600, 300×600). Stacked vertically: image on top,
// headline middle, CTA at bottom.
function skyscraper(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const headFont = expandedFont(page, "headline", kit, 0, "Georgia");
  const bodyFont = expandedFont(page, "body", kit, 1, "sans-serif");
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 6, top: 4, width: 88, height: 34, borderRadius: 2 });
  }
  const textTop = src ? 42 : 8;
  if (tag(page)) {
    items.push({
      type: "text", field: "tag", left: 6, top: textTop, width: 88,
      fontSize: 3.2, color: accentOr(page), fontFamily: bodyFont,
      fontWeight: "bold", textAlign: "center",
    });
  }
  items.push({
    type: "text", field: "headline", left: 6, top: textTop + 6, width: 88,
    fontSize: 6, color: c.headline, fontFamily: headFont,
    fontWeight: "bold", textAlign: "center", height: 34, textFit: "shrink",
  });
  items.push({
    type: "text", text: "Read More", left: 6, top: 90, width: 88,
    fontSize: 3.5, color: accentOr(page), fontFamily: bodyFont,
    fontWeight: "bold", textAlign: "center", ctaTarget: true,
  });
  return items;
}
