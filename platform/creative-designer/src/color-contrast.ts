// Pick text colors that read against a background, and check contrast
// of arbitrary color pairs.
//
// Two callers:
//   - presets.ts (auto-layout) and toolbar.ts (new-text default) use
//     pickContrast() to choose readable headline/sub/body colors at
//     creation time, based on the page's bg luminance.
//   - props-panel.ts uses contrastRatio() to render a "low contrast"
//     warning next to the color picker when the user has wandered into
//     a hard-to-read combination.
//
// Hex parsing only. Gradients, named colors, hsl() etc. fall back to
// the dark-bg palette — that matches Gemini's "dark elegant gradient"
// extraction intent and avoids over-engineering CSS color parsing.

import { kitColor, type BrandKit } from "./brand-kit";

export interface ContrastingColors {
  /** Strongest contrast — for headlines. */
  headline: string;
  /** Medium contrast — for sub-heads. */
  sub: string;
  /** Lowest of the three — for body paragraphs. */
  body: string;
}

const DARK_BG_PALETTE: ContrastingColors = {
  headline: "#ffffff",
  sub:      "#d1d5db",
  body:     "#9ca3af",
};

const LIGHT_BG_PALETTE: ContrastingColors = {
  headline: "#0a0a0b",
  sub:      "#374151",
  body:     "#6b7280",
};

/** Pick text colors that contrast against the page background. */
export function pickContrast(bg: string | undefined | null): ContrastingColors {
  const lum = parseHexLuminance(bg);
  if (lum === null) return DARK_BG_PALETTE; // unparseable → assume dark
  return lum < 0.5 ? DARK_BG_PALETTE : LIGHT_BG_PALETTE;
}

/** Compute the WCAG contrast ratio between two hex colors. Returns 1
  * when either color isn't a parseable hex (so the caller can treat
  * unknown combinations as "OK" rather than always-warning). */
export function contrastRatio(a: string | undefined | null, b: string | undefined | null): number {
  const la = parseHexRelativeLuminance(a);
  const lb = parseHexRelativeLuminance(b);
  if (la === null || lb === null) return 1;
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

/** WCAG AA contrast threshold for normal-size text. */
export const WCAG_AA_NORMAL: number = 4.5;

/** Resolve the colours an auto-generated layout should use, preferring the
  * LP's brand-kit colours so the creative matches the landing page (the way
  * kitFont matches its type):
  *   - bg     ← kit "Background" (LP dominant) when present, else page.bg
  *   - text   ← kit "Text" (LP text colour) when it meets WCAG AA on that bg;
  *              otherwise the auto contrast pick (so we never ship illegible
  *              text even if extraction paired poorly)
  *   - accent ← kit "Accent", else page.accent, else the house gold
  * Kit colours are hex (editor _toHex), so the contrast maths are reliable
  * even when page.bg is a non-hex rgb()/gradient string. With no kit, this is
  * exactly today's behaviour (pickContrast(page.bg) + page.accent). */
export function resolveLayoutColors(
  page: { bg?: string; accent?: string },
  kit: BrandKit | null,
): { bg: string | undefined; headline: string; sub: string; body: string; accent: string } {
  const kitBg = kitColor(kit, "Background");
  const bg = kitBg ?? page.bg;
  const auto = pickContrast(bg);
  const kitText = kitColor(kit, "Text");
  const text = kitText && contrastRatio(kitText, bg) >= WCAG_AA_NORMAL ? kitText : null;
  const accent = kitColor(kit, "Accent") ?? (typeof page.accent === "string" ? page.accent : "#c4a35a");
  return { bg, headline: text ?? auto.headline, sub: text ?? auto.sub, body: text ?? auto.body, accent };
}

/** Snap any text item whose `color` fails WCAG AA against `bg` to a
  * legible palette pick. Used as a post-filter on Gemini-generated
  * layouts: the prompt asks for contrasting colors but Gemini sometimes
  * picks near-bg-colored text, leaving the words invisible.
  *
  * Selection logic:
  *   - ctaTarget items prefer `accent` when accent itself contrasts; if
  *     accent is also too low-contrast, fall through to palette.headline
  *     so the CTA still reads.
  *   - Non-CTA items pick by fontSize: large = headline color, mid =
  *     sub, small = body.
  * Items with already-passing contrast are returned unchanged so
  * authored choices the editor explicitly approved survive a regen.
  */
export function enforceContrast<T extends { type?: string; color?: string; fontSize?: number; ctaTarget?: boolean }>(
  items: T[],
  bg: string | undefined | null,
  accent: string | undefined | null,
): T[] {
  const palette = pickContrast(bg);
  return items.map((item) => {
    if (item.type !== "text" || typeof item.color !== "string") return item;
    if (contrastRatio(item.color, bg) >= WCAG_AA_NORMAL) return item;
    const fontSize = typeof item.fontSize === "number" ? item.fontSize : 4;
    const replacement =
      item.ctaTarget && typeof accent === "string" && contrastRatio(accent, bg) >= WCAG_AA_NORMAL
        ? accent
        : fontSize >= 8 ? palette.headline
        : fontSize >= 4 ? palette.sub
        : palette.body;
    return { ...item, color: replacement };
  });
}

// ─── Internals ──────────────────────────────────────────────────────

/** Rec.709 perceptual luminance — used for the light-vs-dark bg pick.
  * Faster + simpler than the WCAG relative luminance, and the only
  * decision we need is "which palette". */
function parseHexLuminance(input: string | undefined | null): number | null {
  const rgb = parseHex(input);
  if (!rgb) return null;
  const [r, g, b] = rgb;
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
}

/** WCAG relative luminance — used for the contrast-ratio formula. */
function parseHexRelativeLuminance(input: string | undefined | null): number | null {
  const rgb = parseHex(input);
  if (!rgb) return null;
  const [r, g, b] = rgb.map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

// Parses #rgb / #rrggbb / rgb(r,g,b) / rgba(r,g,b,a). Other CSS color
// forms (named colors, hsl, gradients) return null — callers either
// fall through to a palette default or skip the contrast check.
// rgb() coverage matters because LP-extracted backgrounds frequently
// arrive as `rgb(17, 17, 17)` rather than `#111111`; without it,
// pickContrast was always falling through to the dark-bg default and
// silently picking white text on light backgrounds.
function parseHex(input: string | undefined | null): [number, number, number] | null {
  if (!input) return null;
  const trimmed = input.trim();
  const hexMatch = trimmed.match(/^#([0-9a-f]{6}|[0-9a-f]{3})$/i);
  if (hexMatch) {
    const hex = hexMatch[1]!.length === 3
      ? hexMatch[1]!.split("").map((c) => c + c).join("")
      : hexMatch[1]!;
    return [
      parseInt(hex.slice(0, 2), 16),
      parseInt(hex.slice(2, 4), 16),
      parseInt(hex.slice(4, 6), 16),
    ];
  }
  const rgbMatch = trimmed.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*[\d.]+\s*)?\)$/i);
  if (rgbMatch) {
    const clamp = (n: number): number => Math.max(0, Math.min(255, n));
    return [clamp(parseInt(rgbMatch[1]!, 10)), clamp(parseInt(rgbMatch[2]!, 10)), clamp(parseInt(rgbMatch[3]!, 10))];
  }
  return null;
}
