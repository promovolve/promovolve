// Normalize incoming pages from the backend. Ported from the inline
// editor's normalizeInitialPages / defaultLayoutForPage helpers.
//
// When a page comes in without a `layout` (or with one that clearly
// isn't in percent coordinates), we synthesize a default layout from
// the page's flat fields: tag, headline, sub, body, img. This is the
// "first time opening this creative in the designer" case — before
// this, the advertiser only typed text into the compose step.

import { resolveLayoutColors } from "./color-contrast";
import { kitFont, kitColor, type BrandKit } from "./brand-kit";
import type { LayoutItem, Page, TextItem } from "./types";

const DESIGN_ASPECT = "16/9";

export function normalizePages(
  raw: unknown,
  opts: { fillDefaultLayout?: boolean; kit?: BrandKit | null } = {},
): Page[] {
  const arr = toArray(raw);
  return arr.map((p) => normalizePage(p, opts.fillDefaultLayout ?? true, opts.kit ?? null));
}

function toArray(raw: unknown): Page[] {
  if (Array.isArray(raw)) return raw as Page[];
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as Page[]) : [];
    } catch {
      return [];
    }
  }
  return [];
}

function normalizePage(p: Page, fillDefaultLayout: boolean, kit: BrandKit | null = null): Page {
  const page: Page = { ...p, designAspect: DESIGN_ASPECT };
  // Legacy fields that aren't part of the current schema.
  delete (page as Record<string, unknown>).designWidth;
  delete (page as Record<string, unknown>).designHeight;

  const hasUsableLayout =
    Array.isArray(page.layout) && page.layout.length > 0 && looksLikePercentLayout(page.layout);
  if (!hasUsableLayout && fillDefaultLayout) {
    // Match the LP's background on the canvas when the kit carries one, so
    // the synthesized layout's text colours (resolved against this bg) and
    // the rendered background agree.
    const kitBg = kitColor(kit, "Background");
    if (kitBg) page.bg = kitBg;
    page.layout = defaultLayoutForPage(page, kit);
  }
  if (!page.banners || typeof page.banners !== "object") {
    page.banners = {};
  }
  // Images are backgrounds — always behind the text. Stacking is array order
  // (later = on top), so push every image to the FRONT of each array (back of
  // the z-stack). Applied to the expanded master AND every per-size bucket so
  // a creative opened from the backend (or synthesized here) never shows an
  // image on top of its text, regardless of how it was stored.
  if (Array.isArray(page.layout)) page.layout = imagesToBack(page.layout);
  const banners = page.banners as Record<string, LayoutItem[]>;
  for (const key of Object.keys(banners)) {
    if (Array.isArray(banners[key])) banners[key] = imagesToBack(banners[key]);
  }
  return page;
}

// Stable reorder: image items first (back of the z-stack), everything else
// after, each group keeping its relative order.
export function imagesToBack(items: LayoutItem[]): LayoutItem[] {
  const images = items.filter((it) => it.type === "image");
  if (images.length === 0) return items;
  const rest = items.filter((it) => it.type !== "image");
  return [...images, ...rest];
}

// Heuristic: a percentage-based layout has all left/top values in 0..100.
// Anything with pixel coords (e.g., fabric.js legacy) fails this and we
// regenerate from the page's flat fields.
export function looksLikePercentLayout(layout: LayoutItem[]): boolean {
  return layout.every((it) => {
    const l = it.left;
    const t = it.top;
    return (l == null || (l >= 0 && l <= 100)) && (t == null || (t >= 0 && t <= 100));
  });
}

// Matches the inline editor's defaults exactly — same coords, fonts,
// and animations — so switching between the old and new designer
// doesn't rebaseline the creative. Text colors are picked against the
// page background via pickContrast (mirrors presets.ts) instead of the
// old hardcoded white/gray: on the dark default background those land
// on the same #ffffff/#d1d5db/#9ca3af, so existing creatives are
// unchanged, but a light page.bg now gets legible dark text instead of
// an invisible white headline.
export function defaultLayoutForPage(page: Page, kit: BrandKit | null = null): LayoutItem[] {
  // LP brand-kit colours (background/text/accent), contrast-guarded; falls
  // back to pickContrast(page.bg) + page.accent when the kit has none.
  const col = resolveLayoutColors(page, kit);
  // Brand-kit heading (0) / body (1) font so a determined LP font reaches the
  // collapsed view too. The banner's collectExpandedFonts scans page.layout, so
  // the self-hosted woff2 loads document-wide; the system family after the comma
  // in the kit's stack is the always-available fallback. Falls back to the old
  // system families when the kit carries no font.
  const headFont = kitFont(kit, 0, "Georgia");
  const bodyFont = kitFont(kit, 1, "sans-serif");
  const items: LayoutItem[] = [];

  // Image first → it sits at the back of the z-stack, behind all the text
  // (matches presets.ts). The text items pushed below render on top.
  if (page.img) {
    items.push({ type: "image", field: "img", left: 60, top: 15, width: 35, height: 70 });
  }

  // Field-BOUND text items (no baked text) so the headline/sub/body/etc.
  // resolve page[field] live — one source of truth, and editing the
  // master field syncs every size that's also field-bound. (See
  // state.ts setItemContent.) The renderer reads `item.text ?? page[field]`.
  const txt = (
    field: string,
    top: number,
    fontSize: number,
    color: string,
    fontFamily: string,
    extra: Partial<TextItem> = {},
  ): TextItem => ({
    type: "text",
    field,
    left: 6,
    top,
    width: 50,
    fontSize,
    color,
    fontFamily,
    ...extra,
  });

  // Copy only: headline + body. No category/tag eyebrow, no CTA / "Read
  // More" — the whole sheet is the link, and `tag` is internal metadata.
  if (page.headline) items.push(txt("headline", 20, 9, col.headline, headFont, { fontWeight: "bold" }));
  if (page.body) items.push(txt("body", 56, 3, col.body, bodyFont));

  return items;
}
