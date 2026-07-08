// Pure helpers shared across the banner internals. No DOM
// dependencies, no shared state — anything that needs `this` or
// the shadow root stays in banner.ts.

import type { LayoutItem, Page } from "./types";

// The expanded master's aspect — the implicit candidate every sized
// layout competes against in pickCollapsedLayout.
const MASTER_ASPECT = 16 / 9;

/** Pick the authored collapsed layout whose aspect is NEAREST the
  * slot's, or null when the 16:9 master is the closest shape (the
  * caller then renders the master fluidly, as before).
  *
  * Replaces the old exact `"WxH"` string match: creatives are fluid,
  * publisher slots are arbitrary boxes, and exact matching meant a
  * 301×250 slot ignored a perfect 300×250 layout while a 320×100
  * strip got the squeezed 16:9 master. Distance is measured on
  * log-aspect so wide and tall mismatches compare symmetrically
  * (2× too wide == 2× too tall). Keys are the canonical pixel names
  * ("300x250"); any `WxH` key in the creative's JSON participates,
  * including sizes whose designer tab was since retired. The
  * "mobile-expanded" key never matches the pattern, so the mobile
  * reader layout can't leak into collapsed rendering. Exact-size
  * matches win naturally (distance 0). Pure — exported for tests. */
export function pickCollapsedLayout(
  banners: Record<string, LayoutItem[]> | undefined,
  slotW: number,
  slotH: number,
): { key: string; layout: LayoutItem[] } | null {
  if (!banners || slotW <= 0 || slotH <= 0) return null;
  const slot = Math.log(slotW / slotH);
  let best: { key: string; layout: LayoutItem[] } | null = null;
  let bestDist = Math.abs(slot - Math.log(MASTER_ASPECT)); // master is a candidate
  for (const [key, layout] of Object.entries(banners)) {
    if (!layout || layout.length === 0) continue;
    const m = key.match(/^(\d+)x(\d+)$/);
    if (!m) continue;
    const w = Number(m[1]);
    const h = Number(m[2]);
    if (w <= 0 || h <= 0) continue;
    const dist = Math.abs(slot - Math.log(w / h));
    if (dist < bestDist) {
      bestDist = dist;
      best = { key, layout };
    }
  }
  return best;
}

/** The expanded reader's layout, for EVERY device: the portrait-authored
  * master (`banners["mobile-expanded"]`), or undefined when the creative
  * predates it. The expanded sheet is portrait on desktop too — a
  * floating 9:16 magazine page over the scrim — because LP-derived
  * creatives are portrait material (an LP is a vertical document);
  * composing them into the retired 16:9 desktop master never worked.
  * Callers fall back to `page.layout` (the 16:9 master) when this
  * returns undefined, so legacy creatives keep rendering. Pure —
  * exported for tests. */
export function pickExpandedLayout(page: Page): LayoutItem[] | undefined {
  const layout = page.banners?.["mobile-expanded"];
  return layout && layout.length > 0 ? layout : undefined;
}

/** Aspect-fit sizing for the floating reader sheet (and its chrome
  * box): 88% of whichever overlay dimension binds, plus an absolute
  * px ceiling so it doesn't balloon on huge monitors. Returns the ONE
  * driven dimension — `aspect-ratio` derives the other. Setting both
  * dimensions (or a driven dimension plus a cross-axis max-*) breaks
  * the ratio and squashes the sheet, which is exactly how the old
  * width-driven sizing failed for portrait and the height-driven fix
  * failed on narrow (phone) viewports. cq units resolve against the
  * overlay (container-type: size), so one formula covers delivery
  * viewports and framed preview bezels alike. Pure — exported for
  * tests. */
export function sheetSizeFor(aspectCss: string, fitPct = 88): { height: string } | { width: string } {
  const m = aspectCss.match(/^\s*([\d.]+)\s*\/\s*([\d.]+)\s*$/);
  const w = m ? parseFloat(m[1]!) : 16;
  const h = m ? parseFloat(m[2]!) : 9;
  return h > w
    ? { height: `min(900px, ${fitPct}cqh, ${((fitPct * h) / w).toFixed(2)}cqw)` }
    : { width: `min(1200px, ${fitPct}cqw, ${((fitPct * w) / h).toFixed(2)}cqh)` };
}

/** The sheet's fit percentage per device class. Desktop earns real
  * margins (the dimmed article around the sheet is the design, and
  * arrows/close straddle the sheet edges); on a phone those margins
  * just shrink the creative — 97% is effectively full-bleed with a
  * sliver of scrim. Mobile has no straddling chrome to make room for:
  * the close pill pins to the viewport corner and there are no nav
  * arrows (swipe + the corner peel are the gestures). */
export function sheetFitPct(isMobile: boolean): number {
  return isMobile ? 97 : 88;
}

// Lenient JSON parse: returns null instead of throwing. Used for
// data attributes (`data-animation-to`, `pages`, `config`) where the
// authoring layer is responsible for shape but a corrupt blob
// shouldn't crash the whole banner.
export function parseJSON<T>(raw: string | undefined): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

// Walk every expanded-master layout across the creative's pages and
// return the deduped list of image URLs. Used by the preload pass
// to warm the browser cache for everything that appears on expand.
//
// Only the expanded masters are included:
//   - page.layout                       — Expanded PC (16:9)
//   - page.banners["mobile-expanded"]   — Expanded Mobile (9:16)
//
// IAB sized variants are skipped — they'll be served by whichever
// collapsed banner already chose, and the collapsed render has
// already loaded those.
//
// `item.field` images (resolved against page fields by the renderer)
// aren't chased here — preload is a best-effort warm. If a field
// reference isn't expressible as a URL without the page context,
// skipping it is fine; the real render will fetch it.
export function collectExpandedImageUrls(pages: Page[]): string[] {
  const urls = new Set<string>();
  const add = (items: unknown[] | undefined): void => {
    if (!items) return;
    for (const raw of items) {
      const it = raw as { type?: string; src?: string };
      if (it?.type === "image" && typeof it.src === "string" && it.src) {
        urls.add(it.src);
      }
    }
  };
  for (const page of pages) {
    add(page.layout);
    add(page.banners?.["mobile-expanded"]);
  }
  return Array.from(urls);
}
