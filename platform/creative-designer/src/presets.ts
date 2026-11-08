// Hand-crafted layout templates per IAB banner size. LLMs don't pick
// good compositions for tiny/odd-aspect ad units, so we fill those
// deterministically from the page's fields. The two expanded variants
// (PC 16:9, Mobile 9:16) still go through Gemini — that's where design
// freedom actually matters.
//
// Each template receives the page's flat fields and returns layout
// items in percent coordinates (font-size is % of container height).
// Image items are included when page.img is present.
//
// A preset emits ONLY what a creative can hold: an image, a headline,
// and — on the expanded surfaces — body copy. auto-layout.ts applyLayout
// drops every other text item on its way into state, so anything else a
// preset composes is discarded before it renders. The tag eyebrow, the
// `sub` line and the "Read More" CTA these presets used to build were
// exactly that, and were deleted 2026-08-08 rather than left as work the
// pipeline throws away. Positions of the surviving items are unchanged,
// so nothing about the rendered output moved.

import type { LayoutItem, Page } from "./types";
import { kitFont, type BrandKit } from "./brand-kit";
import { pickContrast, resolveLayoutColors } from "./color-contrast";
import { scrimGradient } from "./scrim";

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

/** The face a field is ALREADY set in on the wide master, or "" when it
  * isn't laid out yet. The expanded view is the font source of truth: it
  * is laid out FIRST (see the MODES order + the synchronous preset /
  * template fan-out), so by the time a collapsed preset runs, page.layout
  * already holds the resolved fontFamily. Reading it here is what keeps a
  * collapsed unit on the SAME font as the preview instead of pulling
  * kit.fonts[…] — which can be an all-caps LP display face the expanded
  * view never chose, the cause of "preview is mixed-case but the delivered
  * banner is ALL CAPS". */
function authoredFont(page: Page, field: "headline" | "body"): string {
  const hit = (page.layout ?? []).find(
    (it) => it.type === "text" && (it as { field?: string }).field === field,
  ) as { fontFamily?: string } | undefined;
  return hit?.fontFamily || "";
}

/** The face the creative's headline is set in — and, per BODY FOLLOWS
  * HEADLINE below, what body copy and author-added text boxes default to.
  * Falls back to the kit's heading font while the expanded view is still
  * being generated and page.layout is empty. */
export function headlineFontOf(page: Page, kit: BrandKit | null): string {
  return authoredFont(page, "headline") || kitFont(kit, 0, "Georgia");
}

// ── BODY FOLLOWS HEADLINE (user decision 2026-08-08) ──────────────────
// Body copy is set in the HEADLINE's face, not the kit's second font. One
// face per creative reads as composed rather than assembled, and
// kit.fonts[1] is frequently an LP *interface* face (the nav/button font a
// site uses for chrome) that never belonged in running copy. With the tag
// and CTA items gone, no preset reads kit.fonts[1] at all any more.
//
// A font the author set by hand still wins: this reads the authored body
// face first and only falls back to the headline's.
function readerBodyFont(page: Page, headFont: string): string {
  return authoredFont(page, "body") || headFont;
}

// Expanded PC (16:9). Magazine hero: image on right 40%, headline +
// body stacked on the left. No tag / CTA — the expanded surface is
// where the user is already engaged so the label and button add clutter
// rather than function.
function expandedPc(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = resolveLayoutColors(page, kit);
  const headFont = headlineFontOf(page, kit);
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
    type: "text", field: "body", left: col.left, top: 62, width: col.width,
    fontSize: 3, color: c.body, fontFamily: readerBodyFont(page, headFont), textAlign: "left",
    height: 24, textFit: "shrink",
  });
  return items;
}

// Expanded Mobile (9:16). Portrait stack: image top 35%, headline +
// body centered below. No tag / CTA for the same reason as PC.
function expandedMobile(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = resolveLayoutColors(page, kit);
  const headFont = headlineFontOf(page, kit);
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
    type: "text", field: "body", left: 6, top: textTop + 34, width: 88,
    fontSize: 2.8, color: c.body, fontFamily: readerBodyFont(page, headFont), textAlign: "center",
    height: 14, textFit: "shrink",
  });
  return items;
}

const img = (page: Page): string | undefined =>
  typeof page.img === "string" && page.img.length > 0 ? page.img : undefined;

// Medium rectangle (300×250, 336×280). Image left, headline right.
// Text wraps in a narrow column.
function mediumRectangle(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 4, top: 8, width: 42, height: 84, borderRadius: 2 });
  }
  const textLeft = src ? 50 : 6;
  const textWidth = src ? 46 : 88;
  items.push({
    type: "text", field: "headline", left: textLeft, top: 28, width: textWidth,
    fontSize: 14, color: c.headline, fontFamily: headlineFontOf(page, kit),
    fontWeight: "bold", textAlign: "left", textFit: "shrink", height: 44,
  });
  return items;
}

// Leaderboard (728×90, 970×90, 320×50). Horizontal strip: small image
// on far left, headline running to its right. The headline column stops
// at 66% — the right end of the strip stays clear rather than being
// filled, which is what gave the shape its rhythm when a CTA sat there.
function leaderboard(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 2, top: 10, width: 10, height: 80, borderRadius: 2 });
  }
  const textLeft = src ? 14 : 4;
  const textWidth = 66;
  items.push({
    type: "text", field: "headline", left: textLeft, top: 20, width: textWidth,
    fontSize: 42, color: c.headline, fontFamily: headlineFontOf(page, kit),
    fontWeight: "bold", textAlign: "left", height: 60, textFit: "shrink",
  });
  return items;
}

// 320×100 large mobile banner. Wider than a leaderboard so we can
// give the headline two lines of breathing room.
function wideMobile(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 3, top: 10, width: 22, height: 80, borderRadius: 2 });
  }
  const textLeft = src ? 28 : 5;
  const textWidth = 55;
  items.push({
    type: "text", field: "headline", left: textLeft, top: 18, width: textWidth,
    fontSize: 22, color: c.headline, fontFamily: headlineFontOf(page, kit),
    fontWeight: "bold", textAlign: "left", height: 58, textFit: "shrink",
  });
  return items;
}

// Billboard (970×250). Hero composition: image on left 40%, headline
// on the right.
function billboard(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 3, top: 8, width: 36, height: 84, borderRadius: 2 });
  }
  const textLeft = src ? 44 : 6;
  const textWidth = src ? 52 : 90;
  items.push({
    type: "text", field: "headline", left: textLeft, top: 26, width: textWidth,
    fontSize: 18, color: c.headline, fontFamily: headlineFontOf(page, kit),
    fontWeight: "bold", textAlign: "left", height: 50, textFit: "shrink",
  });
  return items;
}

// Skyscraper (160×600, 300×600). Stacked vertically: image on top,
// headline below it.
function skyscraper(page: Page, kit: BrandKit | null): LayoutItem[] {
  const items: LayoutItem[] = [];
  const c = pickContrast(page.bg);
  const src = img(page);
  if (src) {
    items.push({ type: "image", field: "img", left: 6, top: 4, width: 88, height: 34, borderRadius: 2 });
  }
  const textTop = src ? 42 : 8;
  items.push({
    type: "text", field: "headline", left: 6, top: textTop + 6, width: 88,
    fontSize: 6, color: c.headline, fontFamily: headlineFontOf(page, kit),
    fontWeight: "bold", textAlign: "center", height: 34, textFit: "shrink",
  });
  return items;
}

// ── Map poster (gatefold) ─────────────────────────────────────────────
// One 9:16 page, three beats stacked in thirds; the banner folds it along
// the two band lines. Text stays POSTER_CREASE_INSET clear of each fold.
// Contrast comes from per-band scrims, not legibilizeItems: that measures
// against page.bg, which the full-bleed image hides.
export const POSTER_BAND = 100 / 3;
export const POSTER_CREASE_INSET = 4;

export function posterLayoutFor(beats: Page[], kit: BrandKit | null): LayoutItem[] {
  const font = headlineFontOf(beats[0], kit);
  const items: LayoutItem[] = [{ type: "image", field: "img", left: 0, top: 0, width: 100, height: 100 }];
  for (let k = 0; k < 3; k++) {
    const spec = { edge: k === 0 ? "top" : "bottom", color: "#000000", strength: 0.55 } as const;
    items.push({ type: "rect", left: 0, top: k * POSTER_BAND, width: 100, height: POSTER_BAND, fill: scrimGradient(spec), scrim: spec } as LayoutItem);
  }
  const text = (o: Record<string, unknown>) =>
    ({ type: "text", left: 6, width: 88, color: "#ffffff", fontFamily: font, textAlign: "left", textFit: "shrink", ...o }) as LayoutItem;
  beats.slice(0, 3).forEach((b, k) => {
    const y = k * POSTER_BAND + POSTER_CREASE_INSET;
    const subField = b.sub ? "sub" : "body";
    const subText = b.sub || b.body;
    const bind = (field: string, literal: string | undefined) => (k === 0 ? { field } : { text: literal ?? "" });
    items.push(text({ ...bind("headline", b.headline), top: y, height: 10, fontSize: 8, fontWeight: "bold" }));
    if (subText) items.push(text({ ...bind(subField, subText), top: y + 11, height: 6, fontSize: 3.5 }));
    if (k === 2 && b.ctaLabel) {
      items.push(text({ text: b.ctaLabel, top: y + 19, height: 5, fontSize: 3.5, fontWeight: "bold" }));
    }
  });
  return items;
}

/** First open of a Map design: fold the three hand-off beats into one
  * poster page. The poster is the PORTRAIT master (banners["mobile-expanded"])
  * — what the 9:16 tab edits and delivery renders; `layout` stays the hidden
  * wide master. A resumed creative (portrait master already present) is left
  * alone. Caller invokes this only when bannerConfig.format === "map". */
export function composeMapPages(pages: Page[], hadPortrait: boolean, kit: BrandKit | null): Page[] {
  if (hadPortrait || pages.length === 0) return pages.slice(0, 1);
  const p0 = pages[0];
  return [{ ...p0, banners: { ...p0.banners, "mobile-expanded": posterLayoutFor(pages, kit) } }];
}
