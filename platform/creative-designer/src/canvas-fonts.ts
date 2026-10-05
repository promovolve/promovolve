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
// - A face that isn't in R2 yet fails its load silently and the canvas
//   keeps the CSS fallback — registration can improve the canvas but never
//   break it. Publish provisions the face; the next editing session gets it.
// - CJK subsets are the common case of that: the subset URL tracks the
//   CURRENT text and is only provisioned on save, so a fresh draft or any
//   text edit points at a file that doesn't exist yet (GH #235). For those
//   the canvas falls back to Google's own per-text subset — the exact
//   source the server copies from (GoogleFontProvisioner), so the preview
//   matches what ships. Designer only: delivered ads still load only the
//   self-hosted copy, so publishers' readers never contact Google.
// - We also try each face's shared `latin` variant, so mixed-language
//   creatives render their Latin glyphs true in the meantime.
import { collectExpandedFonts, collectSubsetText, hasCjk, type FontFaceRef } from "@banner/font-catalog";
import type { Page } from "./types";

const attempted = new Set<string>();

function register(family: string, weight: number, url: string, onMissing?: () => void): FontFace | null {
  if (attempted.has(url)) return null;
  attempted.add(url);
  try {
    const face = new FontFace(family, `url(${url}) format("woff2")`, {
      display: "swap",
      weight: String(weight),
    });
    document.fonts.add(face);
    // Not-yet-hosted faces 404 here. Drop the dead face so it can't shadow
    // a fallback registered under the same family/weight.
    face.load().catch(() => {
      document.fonts.delete(face);
      onMissing?.();
    });
    return face;
  } catch {
    // FontFace unavailable (ancient browser) — canvas falls back wholesale.
    return null;
  }
}

/** Google's css2 URL for a per-text subset — byte-for-byte the request
  * GoogleFontProvisioner.cssUrlFor makes, so the preview gets the face
  * the server will later self-host. `pinned: false` drops the weight for
  * single-weight families, which css2 rejects with HTTP 400. */
export function googleSubsetCssUrl(cssFamily: string, weight: number, text: string, pinned = true): string {
  const fam = encodeURIComponent(cssFamily);
  const base = pinned
    ? `https://fonts.googleapis.com/css2?family=${fam}:wght@${weight}&display=swap`
    : `https://fonts.googleapis.com/css2?family=${fam}&display=swap`;
  return `${base}&text=${encodeURIComponent(text)}`;
}

/** First font url(...) in a css2 response — a text= request returns a
  * single @font-face. Mirror of GoogleFontProvisioner.firstFontUrl. */
export function firstFontUrl(css: string): string | null {
  const m = /url\(\s*['"]?([^'")\s]+)['"]?\s*\)/.exec(css);
  return m ? m[1]! : null;
}

// One live Google fallback per family/weight: each text edit yields a new
// subset, so replace the previous face instead of piling them up.
const googleFaces = new Map<string, FontFace>();

async function registerGoogleSubset(f: FontFaceRef, text: string): Promise<void> {
  try {
    let res = await fetch(googleSubsetCssUrl(f.cssFamily, f.weight, text));
    if (res.status === 400) res = await fetch(googleSubsetCssUrl(f.cssFamily, f.weight, text, false));
    if (!res.ok) return; // not a Google family — canvas keeps its CSS fallback
    const url = firstFontUrl(await res.text());
    if (!url) return;
    const face = register(f.family, f.weight, url);
    if (!face) return;
    const key = `${f.family}|${f.weight}`;
    const prev = googleFaces.get(key);
    if (prev) document.fonts.delete(prev);
    googleFaces.set(key, face);
  } catch {
    // Offline / blocked — canvas keeps its CSS fallback.
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
  const text = collectSubsetText(pages);
  const cjk = hasCjk(text);
  for (const f of collectExpandedFonts(pages, origin)) {
    // A CJK subset that isn't provisioned yet previews from Google instead.
    register(f.family, f.weight, f.url, cjk ? () => void registerGoogleSubset(f, text) : undefined);
    // Companion attempt on the shared latin variant when the derived
    // variant is a CJK subset key (8-hex suffix) — see module comment.
    const latinUrl = f.url.replace(/-[0-9a-f]{8}\.woff2$/, "-latin.woff2");
    if (latinUrl !== f.url) register(f.family, f.weight, latinUrl);
  }
}
