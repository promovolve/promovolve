// Curated layout templates. Picking a template replaces the current
// mode's layout with the template's items — a deterministic alternative
// to AI regeneration that gives authors a known starting point for
// common ad compositions.
//
// Items use percent coordinates so a single template renders into any
// canvas aspect, but extreme aspect ratios (a 320×50 leaderboard with
// a square-leaning template) look bad. Each template carries an
// `orientation` hint so the picker can sort/filter by what fits the
// current mode.
//
// Placeholder text + neutral colours are intentional: the author
// customises after applying. Field bindings (text via `field:`) are
// avoided — templates aren't tied to any particular page schema.
//
// No CTA button component: delivery navigates on a tap anywhere on the
// sheet (whole-sheet CTA), so templates carry no "Read More" / button
// element — the copy and image ARE the ad.

import type { LayoutItem } from "./types";
import { scrimGradient, type ScrimSpec } from "./scrim";

export type TemplateOrientation = "any" | "landscape" | "portrait" | "square";

// The full-bleed overlay's bottom scrim. Authored as scrim params (not a
// raw gradient string) so selecting it after applying the template opens
// the Scrim controls — edge/colour/strength — instead of a flat-fill
// picker that could only flatten the gradient. `fill` is derived from the
// same spec so the rendered gradient and the editable params never drift.
const FULLBLEED_SCRIM: ScrimSpec = { edge: "bottom", color: "#000000", strength: 0.85 };

/** Structural role of a slot in a template. The Gemini auto-layout
  * prompt uses these to reason about composition intent independent
  * of the visual item array (which is per-aspect and rigid). */
export type SlotRole =
  | "headline"
  | "subheadline"
  | "body"
  | "hero"        // Primary image
  | "accent"      // Secondary image / icon
  | "badge"       // Small promotional shape (sale tag, ribbon)
  | "divider";    // Decorative rule

/** Coarse positional region. Picker sorting + Gemini prompt phrasing
  * use these — not exact coordinates. */
export type SlotRegion =
  | "top-left"   | "top"    | "top-right"
  | "left"       | "center" | "right"
  | "bottom-left"| "bottom" | "bottom-right";

export interface TemplateSlot {
  role: SlotRole;
  region: SlotRegion;
  /** Optional weight hint. "primary" = the visual focal point of its
    * region (large headline, anchor image); "secondary" = supporting. */
  prominence?: "primary" | "secondary";
}

export interface LayoutTemplate {
  id: string;
  name: string;
  description: string;
  /** Hint for the picker — tightly-shaped templates flag the aspect
    * ranges they were designed for. "any" works in every mode. */
  orientation: TemplateOrientation;
  /** Item array for the PC (16:9 landscape) expanded layout. The
    * coordinates are authored against this aspect. */
  items: LayoutItem[];
  /** Optional dedicated item array for the Mobile (9:16 portrait)
    * expanded layout. When present, applyTemplate uses these for the
    * mobile variant instead of squeezing `items` into portrait
    * (which produces the 50/50 antipattern). Falls back to `items`
    * when absent. */
  mobileItems?: LayoutItem[];
  /** Abstract slot spec used by the AI generator (Gemini auto-layout).
    * Aspect-agnostic; describes composition intent. The two views
    * deliberately diverge — the item array is one concrete example
    * of the slot spec realized for the template's typical aspect. */
  slots: TemplateSlot[];
}

// Color tokens used inside templates. Inline (not the UI token set)
// because templates render inside the user's banner — the banner's
// own background palette is what matters, not the designer chrome.
const COLOR_INK = "#1f2937";
const COLOR_MUTED = "#6b7280";
const COLOR_LIGHT = "#ffffff";

export const TEMPLATES: LayoutTemplate[] = [

  {
    id: "promo",
    name: "Promo / Sale",
    description: "Hero image left, sale badge + headline + body right. Mobile: image top, badge floats over.",
    orientation: "any",
    slots: [
      { role: "hero",     region: "left",         prominence: "primary"   },
      { role: "headline", region: "right",        prominence: "primary"   },
      { role: "body",     region: "right",        prominence: "secondary" },
    ],
    items: [
      { type: "image", role: "hero",     field: "img",      left: 4,  top: 8,  width: 42, height: 84, borderRadius: 1 },
      { type: "text",  role: "headline", field: "headline", left: 50, top: 32, width: 46, height: 22, fontSize: 9,  color: COLOR_INK,    fontWeight: "700", lineHeight: 1.1, textFit: "shrink" },
      { type: "text",  role: "body",     field: "body",     left: 50, top: 58, width: 46, height: 14, fontSize: 5,  color: COLOR_MUTED, textFit: "shrink" },
    ],
    mobileItems: [
      { type: "image", role: "hero",     field: "img",      left: 0,  top: 0,  width: 100, height: 44 },
      { type: "text",  role: "headline", field: "headline", left: 6,  top: 50, width: 88,  height: 18, fontSize: 9,   color: COLOR_INK,    fontWeight: "700", lineHeight: 1.1,  textAlign: "center", textFit: "shrink" },
      { type: "text",  role: "body",     field: "body",     left: 6,  top: 70, width: 88,  height: 14, fontSize: 4,   color: COLOR_MUTED,  textAlign: "center", textFit: "shrink" },
    ],
  },

  // ─── Portrait (mobile-expanded) templates ─────────────────────────
  // 9:16 viewport (540×960). Composed top-down with image as anchor,
  // text stacked below or overlaid. Landscape templates squeezed into
  // portrait crop awkwardly — these are mobile-native.

  {
    id: "mobile-hero-top",
    name: "Mobile · Hero Top",
    description: "Image fills the top half. Tag, headline, body stacked beneath. Editorial mobile layout.",
    orientation: "portrait",
    slots: [
      { role: "hero",        region: "top",    prominence: "primary"   },
      { role: "headline",    region: "center", prominence: "primary"   },
      { role: "body",        region: "bottom", prominence: "secondary" },
    ],
    // `items` doubles as a PC fallback when the picker surfaces this
    // template in a landscape mode; the portrait `mobileItems` is the
    // primary use.
    items: [
      { type: "image", role: "hero",        field: "img",      left: 0,  top: 0,  width: 100, height: 50 },
      { type: "text",  role: "headline",    field: "headline", left: 6,  top: 59, width: 88,  height: 14, fontSize: 9,   color: COLOR_INK,    fontWeight: "700", lineHeight: 1.12, textFit: "shrink" },
      { type: "text",  role: "body",        field: "body",     left: 6,  top: 74, width: 88,  height: 12, fontSize: 4,   color: COLOR_MUTED,  lineHeight: 1.4,  textFit: "shrink" },
    ],
    mobileItems: [
      { type: "image", role: "hero",        field: "img",      left: 0,  top: 0,  width: 100, height: 50 },
      { type: "text",  role: "headline",    field: "headline", left: 6,  top: 59, width: 88,  height: 14, fontSize: 9,   color: COLOR_INK,    fontWeight: "700", lineHeight: 1.12, textFit: "shrink" },
      { type: "text",  role: "body",        field: "body",     left: 6,  top: 74, width: 88,  height: 12, fontSize: 4,   color: COLOR_MUTED,  lineHeight: 1.4,  textFit: "shrink" },
    ],
  },

  {
    id: "mobile-fullbleed-overlay",
    name: "Mobile · Full-Bleed Overlay",
    description: "Image fills the page. Headline + body sit on a dark gradient scrim at the bottom. Cinematic.",
    orientation: "portrait",
    slots: [
      { role: "hero",     region: "center", prominence: "primary"   },
      { role: "headline", region: "bottom", prominence: "primary"   },
      { role: "body",     region: "bottom", prominence: "secondary" },
    ],
    items: [
      { type: "image", role: "hero",     field: "img",      left: 0,  top: 0,  width: 100, height: 100 },
      { type: "rect",  role: "accent",   left: 0,  top: 50, width: 100, height: 50, scrim: FULLBLEED_SCRIM, fill: scrimGradient(FULLBLEED_SCRIM) },
      { type: "text",  role: "headline", field: "headline", left: 6,  top: 72, width: 88,  height: 10, fontSize: 9,   color: COLOR_LIGHT, fontWeight: "700", lineHeight: 1.12, textFit: "shrink" },
      { type: "text",  role: "body",     field: "body",     left: 6,  top: 84, width: 88,  height: 8,  fontSize: 4,   color: "rgba(255,255,255,0.85)", lineHeight: 1.35, textFit: "shrink" },
    ],
    mobileItems: [
      { type: "image", role: "hero",     field: "img",      left: 0,  top: 0,  width: 100, height: 100 },
      { type: "rect",  role: "accent",   left: 0,  top: 50, width: 100, height: 50, scrim: FULLBLEED_SCRIM, fill: scrimGradient(FULLBLEED_SCRIM) },
      { type: "text",  role: "headline", field: "headline", left: 6,  top: 72, width: 88,  height: 10, fontSize: 9,   color: COLOR_LIGHT, fontWeight: "700", lineHeight: 1.12, textFit: "shrink" },
      { type: "text",  role: "body",     field: "body",     left: 6,  top: 84, width: 88,  height: 8,  fontSize: 4,   color: "rgba(255,255,255,0.85)", lineHeight: 1.35, textFit: "shrink" },
    ],
  },
];

/** Render a template's slot spec as a single line of plain English
  * suitable for inclusion in an LLM prompt. The Gemini auto-layout
  * call uses this to nudge composition; the abstract regions keep
  * the prompt aspect-agnostic so a single template generalises across
  * mobile, expanded, and IAB sizes. */
export function slotsAsPromptLine(template: LayoutTemplate): string {
  if (template.slots.length === 0) return "";
  const phrases = template.slots.map((s) => {
    const prom = s.prominence ? ` (${s.prominence})` : "";
    return `${s.role}${prom} in ${s.region}`;
  });
  return `Layout intent: ${phrases.join("; ")}.`;
}

/** Decide a template's orientation match score for the given canvas
  * aspect ratio (w/h). Returns 1 for an exact match, 0.5 for "any",
  * 0 for a mismatch. Picker uses this to sort templates so the most
  * compatible ones surface first without hiding incompatible ones. */
export function orientationScore(template: LayoutTemplate, canvasAspect: number): number {
  if (template.orientation === "any") return 0.5;
  if (template.orientation === "landscape") return canvasAspect > 1.2 ? 1 : 0;
  if (template.orientation === "portrait") return canvasAspect < 0.85 ? 1 : 0;
  if (template.orientation === "square") return canvasAspect >= 0.85 && canvasAspect <= 1.2 ? 1 : 0;
  return 0;
}
