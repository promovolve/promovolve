// Self-hosted web-font catalog for the EXPANDED view.
//
// The ad runtime never calls Google Fonts at the visitor's runtime
// (privacy + the German Google-Fonts ruling + our OSS transparency
// stance). Instead, allow-listed Google fonts are fetched ONCE at
// creative-publish time, stored in our own R2 bucket, and served from
// the same CDN origin as the creative's images. This module is the
// client half: it derives, from the creative's own layout, which
// allow-listed font faces to load and from where, then the banner
// registers them via the FontFace API during the expanded-view preload.
//
// Why an allow-list (not "fetch any family"): only OFL/Apache Google
// fonts are free to embed. Licensed faces (Helvetica Neue, proprietary
// brand fonts) can't be legally self-hosted, so a family that isn't on
// this list simply falls back to the snapped system font baked into the
// CSS font stack (e.g. "Montserrat, sans-serif" → sans-serif when the
// face never loads). The Scala side (GoogleFontCatalog.scala) mirrors
// this list — keep the two in sync.

import type { Page } from "./types";

// No allow-list: Google Fonts only serves free-to-embed families, so the
// banner optimistically derives the R2 URL for whatever family the layout
// references. If the server self-hosted it the woff2 loads; if not (licensed /
// non-Google), the FontFace load 404s and the CSS stack's system family takes
// over. Only generic / system families are skipped. Mirror of
// GoogleFontCatalog.scala (genericFamilies + slugify).
const GENERIC_FAMILIES = new Set([
  "sans-serif", "serif", "monospace", "cursive", "fantasy",
  "system-ui", "ui-sans-serif", "ui-serif", "ui-monospace", "ui-rounded",
  "georgia", "times", "times new roman", "arial", "helvetica", "helvetica neue",
  "courier", "courier new", "verdana", "tahoma", "trebuchet ms", "segoe ui",
]);
const isGeneric = (key: string): boolean => GENERIC_FAMILIES.has(key);
/** R2 slug stem from a family lookup-key (lowercased, spaces→hyphens) —
  * derived, no list. Matches GoogleFontCatalog.slugify. */
const slugify = (key: string): string => key.replace(/ /g, "-");

/** One resolved face: the base CSS family name (what the layout's
  * `fontFamily` stack references — base family, suffix stripped), the
  * numeric weight to register the FontFace at, and the self-hosted woff2
  * URL (per-weight). */
export interface FontFaceRef {
  family: string;
  weight: number;
  url: string;
}

// Weight/style descriptors peeled off a family name to recover the BASE
// family + numeric weight (mirror of GoogleFontCatalog.scala). LPs often
// author the named instance ("Montserrat Thin") as the family.
const WEIGHT_WORD: Record<string, number> = {
  thin: 100, hairline: 100, extralight: 200, ultralight: 200, light: 300,
  regular: 400, normal: 400, book: 400, medium: 500, semibold: 600, demibold: 600,
  bold: 700, extrabold: 800, ultrabold: 800, black: 900, heavy: 900,
};
const WEIGHT_MOD = new Set(["extra", "ultra", "semi", "demi"]);
// "variable"/"vf" = variable-font builds (e.g. "Montserrat Variable") LPs
// reference directly; strip so the base family still matches the catalog.
const STYLE_WORD = new Set(["italic", "oblique", "variable", "vf"]);
const peelable = (t: string): boolean => t in WEIGHT_WORD || WEIGHT_MOD.has(t) || STYLE_WORD.has(t);
const validW = (w: number): boolean => Number.isInteger(w) && w >= 100 && w <= 900 && w % 100 === 0;

/** First family from a stack, quotes stripped (may carry a weight suffix). */
function firstFamilyLiteral(stack: string | undefined): string {
  if (!stack) return "";
  return String(stack).split(",")[0].trim().replace(/^["']|["']$/g, "");
}

/** Every family literal in a stack, in order, quotes stripped. Mirrors
  * GoogleFontCatalog.familyLiterals — the pipeline used to consume only
  * the first family, which silently dropped the CJK companion a stack
  * like `Inter, Noto Sans JP, sans-serif` carries. */
function familyLiterals(stack: string | undefined): string[] {
  if (!stack) return [];
  return String(stack)
    .split(",")
    .map((s) => s.trim().replace(/^["']|["']$/g, "").trim())
    .filter(Boolean);
}

/** Split the first family into base lookup-key, base display name, and an
  * optional weight parsed from a trailing descriptor. Mirrors
  * GoogleFontCatalog.normalize. */
function normalizeFamily(stack: string | undefined): { key: string; display: string; weight: number | null } {
  const display = firstFamilyLiteral(stack).split(/\s+/).filter(Boolean);
  const lower = display.map((s) => s.toLowerCase());
  let n = display.length;
  while (n > 1 && peelable(lower[n - 1])) n--;
  const peeled = lower.slice(n);
  const phrase = peeled.filter((t) => !STYLE_WORD.has(t)).join("");
  let weight: number | null = phrase in WEIGHT_WORD ? WEIGHT_WORD[phrase] : null;
  if (weight == null) for (const t of peeled) if (t in WEIGHT_WORD) weight = WEIGHT_WORD[t];
  return { key: lower.slice(0, n).join(" "), display: display.slice(0, n).join(" "), weight };
}

/** Coerce a CSS font-weight value (number, "100", "bold", "normal") to a
  * valid numeric weight, or null. */
function cssWeight(w: unknown): number | null {
  if (typeof w === "number") return validW(w) ? w : null;
  if (typeof w === "string") {
    const s = w.trim().toLowerCase();
    if (s === "bold") return 700;
    if (s === "normal") return 400;
    const n = parseInt(s, 10);
    return validW(n) ? n : null;
  }
  return null;
}

/** Content key for a CJK text subset — identical to
  * GoogleFontCatalog.subsetKey (server). Canonicalizes to the sorted set of
  * unique code points, then FNV-1a (32-bit) over the UTF-8 bytes → 8 lowercase
  * hex. The server stores the `text=` woff2 under this key; we derive the same
  * URL. A mismatch merely 404s → the system (Mincho/serif) fallback. */
export function subsetKey(text: string): string {
  const cps = Array.from(new Set(Array.from(text, (ch) => ch.codePointAt(0) as number))).sort((a, b) => a - b);
  const canonical = String.fromCodePoint(...cps);
  const bytes = new TextEncoder().encode(canonical);
  let h = 0x811c9dc5;
  for (const b of bytes) { h ^= b; h = Math.imul(h, 0x01000193); }
  return (h >>> 0).toString(16).padStart(8, "0");
}

/** The creative's rendered content text — headline/sub/body/tag/caption across
  * all pages, PLUS every text item's baked `text` override in page.layout and
  * every banner bucket (a size-specific text edit renders characters the page
  * fields don't contain; without them the CJK subset misses those glyphs and
  * the browser falls back per-glyph — mixed typefaces mid-sentence). Used as
  * the CJK `text=` subset. MUST mirror the server's
  * CreativeProcessor.subsetTextFromPagesJson — same page fields AND item
  * texts (order is irrelevant — subsetKey canonicalizes), or the woff2 URL
  * won't match. */
export function collectSubsetText(pages: Page[]): string {
  const fields = ["headline", "sub", "body", "tag", "caption"] as const;
  let out = "";
  const itemTexts = (items: unknown): void => {
    if (!Array.isArray(items)) return;
    for (const it of items) {
      const o = it as Record<string, unknown> | null;
      if (o && o.type === "text" && typeof o.text === "string") out += o.text;
    }
  };
  for (const page of pages) {
    const p = page as Record<string, unknown>;
    for (const k of fields) {
      const v = p[k];
      if (typeof v === "string") out += v;
    }
    itemTexts(p["layout"]);
    const banners = p["banners"];
    if (banners && typeof banners === "object") {
      for (const items of Object.values(banners as Record<string, unknown>)) itemTexts(items);
    }
  }
  return out;
}

/** Whether text contains CJK (Japanese/Korean/Chinese) code points — the
  * signal that fonts must be `text=`-subset rather than served whole. A
  * property of the TEXT, so the banner and server agree without a font list.
  * Mirror of GoogleFontCatalog.hasCjk. */
export function hasCjk(text: string): boolean {
  for (const ch of text) {
    const cp = ch.codePointAt(0) as number;
    if (
      (cp >= 0x3040 && cp <= 0x30ff) || // hiragana + katakana
      (cp >= 0x3400 && cp <= 0x9fff) || // CJK ideographs (kanji / hanzi, incl. ext A)
      (cp >= 0xf900 && cp <= 0xfaff) || // CJK compatibility ideographs
      (cp >= 0xff66 && cp <= 0xff9f) || // halfwidth katakana
      (cp >= 0xac00 && cp <= 0xd7a3) || // hangul syllables (Korean)
      (cp >= 0x1100 && cp <= 0x11ff)    // hangul jamo
    ) return true;
  }
  return false;
}

/** Derive the CDN origin from the creative's own assets — the first
  * expanded image URL's origin. Fonts live on the same R2/CDN origin as
  * images (`/assets/...` and `/fonts/...` share a bucket), so no serve
  * change or extra config is needed. Returns null when no absolute image
  * URL is available (then font loading is skipped → system fallback). */
export function deriveCdnOrigin(pages: Page[]): string | null {
  for (const page of pages) {
    // Page-level image FIRST: designer-authored layouts store image items
    // as field REFERENCES ({field:"img"}, no src — the URL lives on
    // page.img), so an item-src-only scan finds nothing and silently
    // disables font self-hosting for every designer creative. Baked
    // LP-to-creative layouts may still carry item srcs — checked next.
    const pageImg = (page as { img?: unknown }).img;
    if (typeof pageImg === "string" && pageImg) {
      try {
        return new URL(pageImg).origin;
      } catch {
        // relative / malformed — keep looking
      }
    }
    const groups: Array<unknown[] | undefined> = [page.layout, ...Object.values(page.banners ?? {})];
    for (const items of groups) {
      if (!items) continue;
      for (const raw of items) {
        const it = raw as { type?: string; src?: string };
        if (it?.type === "image" && typeof it.src === "string" && it.src) {
          try {
            return new URL(it.src).origin;
          } catch {
            // relative / malformed — keep looking
          }
        }
      }
    }
  }
  return null;
}

/** Walk page.layout plus every per-size banner bucket and return the
  * deduped font faces referenced by text items, with self-hosted URLs
  * built from `origin`. Generic/system families are dropped (they ride
  * the CSS-stack fallback). Scanning the IAB buckets — not just the
  * expanded masters — means fonts used only by a collapsed/IAB preset
  * (e.g. a bold tag/CTA weight) get self-hosted too, so the collapsed
  * ad unit renders the brand font instead of falling back. */
export function collectExpandedFonts(pages: Page[], origin: string): FontFaceRef[] {
  const out: FontFaceRef[] = [];
  const seen = new Set<string>();
  // Variant is a creative-global decision (same rule as the server): a CJK
  // creative uses a per-text subset key for every font; a latin creative uses
  // the shared latin block. Derived once from the creative's text.
  const subset = collectSubsetText(pages);
  const variant = hasCjk(subset) ? subsetKey(subset) : "latin";
  const add = (items: unknown[] | undefined): void => {
    if (!items) return;
    for (const raw of items) {
      const it = raw as { type?: string; fontFamily?: string; fontWeight?: string | number };
      if (it?.type !== "text") continue;
      // EVERY family in the stack gets a face, not just the first — the
      // browser resolves `font-family: Inter, Noto Sans JP, sans-serif`
      // per glyph, so the CJK companion only works if its face is
      // registered too. Consuming only the head silently dropped it.
      for (const literal of familyLiterals(it.fontFamily)) {
        const norm = normalizeFamily(literal);
        if (!norm.key || isGeneric(norm.key)) continue; // generic/system → system fallback
        // weight precedence: descriptor in the name > CSS font-weight > 400
        const weight = norm.weight ?? cssWeight(it.fontWeight) ?? 400;
        const dedup = `${norm.key}:${weight}`;
        if (seen.has(dedup)) continue;
        seen.add(dedup);
        const slug = slugify(norm.key); // derived, no list — server self-hosts it if Google serves it
        out.push({
          // FontFace family = the LITERAL family as the layout's stack
          // references it (e.g. "Montserrat Thin", "Noto Sans JP"), so the
          // renderer's `font-family:<stack>` resolves to this face. The R2
          // slug/weight come from the BASE family normalization
          // (montserrat-100), since Google serves the weight via the
          // `wght@` axis of the base family.
          family: literal,
          weight,
          url: `${origin}/fonts/${slug}-${weight}-${variant}.woff2`,
        });
      }
    }
  };
  for (const page of pages) {
    add(page.layout);
    // Every per-size bucket (mobile-expanded AND the IAB sizes) so an
    // IAB-only weight (e.g. the bold tag/CTA) is self-hosted too.
    for (const bucket of Object.values(page.banners ?? {})) add(bucket);
  }
  return out;
}

/** Convenience: derive origin + collect faces in one call. Empty when
  * no origin is derivable or no allow-listed family is used. */
export function resolveExpandedFonts(pages: Page[]): FontFaceRef[] {
  const origin = deriveCdnOrigin(pages);
  if (!origin) return [];
  return collectExpandedFonts(pages, origin);
}
