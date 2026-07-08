// Apply a layout template's items as the layout for a (page × mode)
// cell, with role-based brand-kit colour overrides.
//
// The new template-driven model: when the LP-to-Creative flow's first
// step has a templateId picked, the designer uses the template's
// items[] verbatim instead of calling Gemini for layout. Each item
// carries a `role` tag (`headline`, `cta`, `body`, …) and the
// brand-kit colours map by role: CTA fills with `Accent`, headlines
// take `Brand` (or fall back to the item's literal colour), body uses
// `Text`, and so on. Text items use `field:` references so the
// LLM-rewritten copy from rewrite-sections lands in the right slot.

import type { BrandKit } from "./brand-kit";
import { enforceContrast } from "./color-contrast";
import type { LayoutTemplate } from "./layout-templates";
import type { LayoutItem, Page } from "./types";

/** Map from item role to the brand-kit colour name we look up. The
  * lookup is by name (case-insensitive); missing colours leave the
  * item's literal colour intact so a sparse kit doesn't blow up
  * generated layouts. */
const ROLE_TO_KIT_COLOR: Record<string, string> = {
  headline:    "Brand",
  subheadline: "Text",
  body:        "Text",
  cta:         "Accent",   // applies to fill on rect/circle items
  "cta-text":  "Background", // text overlaid on the CTA shape
  badge:       "Accent",
  divider:     "Brand",
  // hero (image) and accent (image) don't take colour overrides.
};

function findKitColor(kit: BrandKit | null, name: string): string | null {
  if (!kit) return null;
  const lower = name.toLowerCase();
  const hit = kit.colors.find((c) => c.name.toLowerCase() === lower);
  return hit ? hit.value : null;
}

/** Which kit font (by index into kit.fonts) each text role uses.
  * Heading-like roles take the primary (heading) font; body-like roles
  * take the secondary, falling back to the primary when the kit has
  * only one font. Fonts in the kit are pre-snapped to renderable system
  * families (sans-serif / serif / Georgia / monospace) at extraction
  * time, so whatever lands here renders in the live ad without any
  * web-font loading — the banner ships no @font-face. */
const ROLE_TO_KIT_FONT_INDEX: Record<string, number> = {
  headline:    0,
  "cta-text":  0,
  badge:       0,
  subheadline: 1,
  body:        1,
};

function findKitFont(kit: BrandKit | null, index: number): string | null {
  if (!kit || !Array.isArray(kit.fonts) || kit.fonts.length === 0) return null;
  return kit.fonts[index] ?? kit.fonts[0] ?? null;
}

/** Override an item's colour and font fields based on its role and the
  * brand kit. Returns a new item; never mutates the input. Items
  * without a role, or roles with no kit mapping, pass through
  * unchanged. */
export function applyKitToItem(item: LayoutItem, kit: BrandKit | null): LayoutItem {
  const role = (item as LayoutItem & { role?: string }).role;
  if (!role) return item;

  let next: LayoutItem = item;

  // 1. Colour override by role: text-like roles override `color`;
  //    rect/circle roles override `fill`. The role tag implies which.
  const kitColorName = ROLE_TO_KIT_COLOR[role];
  if (kitColorName) {
    const kitColor = findKitColor(kit, kitColorName);
    if (kitColor) {
      if (next.type === "text") next = { ...next, color: kitColor };
      else if (next.type === "rect" || next.type === "circle") next = { ...next, fill: kitColor };
    }
  }

  // 2. Font override by role (text items only). Puts the kit's heading/
  //    body font onto fontFamily so it actually renders in the ad — the
  //    banner honours item.fontFamily verbatim (render-collapsed.ts).
  if (next.type === "text") {
    const idx = ROLE_TO_KIT_FONT_INDEX[role];
    if (idx !== undefined) {
      const font = findKitFont(kit, idx);
      if (font) next = { ...next, fontFamily: font };
    }
  }

  return next;
}

/** Produce the layout items for a template instance, applied against
  * a specific page. Four transforms in order:
  *   1. Filter image items whose `field` reference resolves to nothing
  *      (page has no img / etc.) — keeps text-only layouts from
  *      reserving empty space where an image would have gone.
  *   2. Materialize `field` references on image items into literal
  *      `src` values. The renderer accepts either form, but the
  *      crop-modal and auto-crop pipelines read item.src directly,
  *      so resolving here keeps both working with template-driven
  *      images. Text items keep their `field` references — text
  *      props panel happily edits via field, no need to materialize.
  *   3. Brand-kit role colour overrides.
  *   4. Contrast enforcement against page.bg — Gemini-rewritten pages
  *      typically have dark gradient backgrounds, while templates
  *      ship with hardcoded dark text colours that go invisible.
  *      Mirrors what presets.ts does via pickContrast. */
export function applyTemplate(
  template: LayoutTemplate,
  kit: BrandKit | null,
  page: Page,
  /** "mobile" picks template.mobileItems when present; any other value
    * (default) uses template.items. Landscape `items` crammed into
    * portrait produces the 50/50 antipattern; mobileItems gives each
    * template a designed portrait stack. */
  variant: "pc" | "mobile" = "pc",
): LayoutItem[] {
  const baseItems = variant === "mobile" && template.mobileItems
    ? template.mobileItems
    : template.items;
  const pageRec = page as Record<string, unknown>;
  const filtered = baseItems.filter((it) => {
    if (it.type !== "image") return true;
    const literalSrc = (it as LayoutItem & { src?: string }).src;
    if (literalSrc) return true;
    const field = (it as LayoutItem & { field?: string }).field;
    if (!field) return false;
    const v = pageRec[field];
    return typeof v === "string" && v.length > 0;
  });
  const resolved = filtered.map((it) => {
    if (it.type !== "image") return it;
    const literalSrc = (it as LayoutItem & { src?: string }).src;
    if (literalSrc) return it;
    const field = (it as LayoutItem & { field?: string }).field;
    if (!field) return it;
    const v = pageRec[field];
    if (typeof v !== "string" || v.length === 0) return it;
    // Drop `field` once materialized so subsequent edits to page.img
    // don't ghost-update the template image. The author can re-pick.
    const { field: _drop, ...rest } = it as LayoutItem & { field?: string };
    return { ...rest, src: v } as LayoutItem;
  });
  const branded = resolved.map((it) => applyKitToItem(it as LayoutItem, kit));
  // Auto-mark CTA-role items with ctaTarget. Delivery no longer wires
  // hotspots (the whole sheet navigates on a deliberate tap), so this
  // is purely a styling hint now — enforceContrast prefers the accent
  // color for ctaTarget items, which keeps CTA copy visually distinct.
  const ctaTargeted = branded.map((it) => {
    const r = (it as LayoutItem & { role?: string }).role;
    if (r === "cta" || r === "cta-text") {
      return { ...it, ctaTarget: true };
    }
    return it;
  });
  return enforceContrast(ctaTargeted, page.bg, page.accent) as LayoutItem[];
}
