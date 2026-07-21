// Canvas web-font loading. The editing canvas styles text with raw CSS
// font-family and historically registered NO web fonts — the author
// edited in whatever their OS happened to have installed while the
// published thumbnail and delivery rendered the true self-hosted face
// (the same "what you see isn't what ships" disease as the font-stack
// flattening, from the other direction). This module registers the
// draft's faces into document.fonts from the same derivation the banner
// runtime uses, so the canvas draws with the faces that will actually
// ship.
//
// Limits, by design:
// - A face that isn't in R2 yet (family never published anywhere, or a
//   CJK subset for text still being typed) fails its load silently and
//   the canvas keeps the CSS fallback — registration can improve the
//   canvas but never break it. Publish provisions the face; the next
//   editing session gets it.
// - For CJK creatives the subset URL tracks the CURRENT text, so we
//   also try each face's shared `latin` variant: mixed-language
//   creatives then at least render their Latin glyphs true while the
//   draft's exact CJK subset may not exist yet.
import { collectExpandedFonts } from "@banner/font-catalog";
import type { Page } from "./types";

const attempted = new Set<string>();

function register(family: string, weight: number, url: string): void {
  if (attempted.has(url)) return;
  attempted.add(url);
  try {
    const face = new FontFace(family, `url(${url}) format("woff2")`, {
      display: "swap",
      weight: String(weight),
    });
    document.fonts.add(face);
    // Not-yet-hosted faces 404 here; the canvas keeps its CSS fallback.
    face.load().catch(() => undefined);
  } catch {
    // FontFace unavailable (ancient browser) — canvas falls back wholesale.
  }
}

/**
 * Derive and register every self-hosted face the draft references.
 * Idempotent per URL; cheap enough to run on a debounced store
 * subscription. `bannerScriptUrl` supplies the CDN origin (the banner
 * bundle lives in the same bucket as the fonts), so this works even
 * before the draft has any CDN-hosted image for the banner's own
 * origin derivation to find.
 */
export function syncCanvasFonts(pages: Page[], bannerScriptUrl: string): void {
  let origin: string;
  try {
    origin = new URL(bannerScriptUrl).origin;
  } catch {
    return;
  }
  for (const f of collectExpandedFonts(pages, origin)) {
    register(f.family, f.weight, f.url);
    // Companion attempt on the shared latin variant when the derived
    // variant is a CJK subset key (8-hex suffix) — see module comment.
    const latinUrl = f.url.replace(/-[0-9a-f]{8}\.woff2$/, "-latin.woff2");
    if (latinUrl !== f.url) register(f.family, f.weight, latinUrl);
  }
}
