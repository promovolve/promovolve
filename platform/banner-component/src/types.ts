// Schema for the `pages` attribute — what the editor/AI produces and the
// banner consumes. Kept permissive (extra fields allowed) because layout items
// may reference arbitrary page-level fields by name via `field:`.

// Target end state for a tween. Item starts at its base state
// (left/top/rotation/1/1) and animates to these values. Any subset
// of fields may be provided — only the ones present animate.
//
// left/top in %; rotation in degrees; scale as a multiplier
// (1 = unchanged); opacity 0..1.
export interface MotionTarget {
  left?: number;
  top?: number;
  rotation?: number;
  scale?: number;
  opacity?: number;
  duration?: number;
  delay?: number;
  // CSS timing-function. Any valid value accepted — common names
  // ("linear", "ease", "ease-in", "ease-out", "ease-in-out") or a
  // cubic-bezier() expression. Defaults to a smooth ease-out curve.
  easing?: string;
}

interface LayoutItemBase {
  left?: number;
  top?: number;
  width?: number;
  height?: number;
  rotation?: number;
  // Initial opacity (0..1). Combined with animationTo.opacity this
  // gives fade-in entrances: base opacity 0 → animationTo opacity 1.
  opacity?: number;
  // Drop shadow. Renders as CSS box-shadow for rect/image, applied to
  // the wrapper for cropped image, and as a text-shadow when set on a
  // text item. Offsets and blur use cqmin so shadows scale with the
  // banner size; color is any CSS color (defaults to #00000080 when
  // shadow object exists but color omitted).
  shadow?: { x?: number; y?: number; blur?: number; color?: string };
  // If set, the item tweens from its base state to these values
  // after `delay` seconds.
  animationTo?: MotionTarget;
  // Historical: marked this item as a CTA click hotspot. Navigation is
  // now page-wide (a deliberate tap anywhere on the sheet opens the
  // landing page — banner.ts wires it at page level, with scroll/drag
  // gestures excluded), so the renderer ignores this. It survives as a
  // designer styling hint (accent-color preference in color-contrast)
  // and in existing creatives' JSON.
  ctaTarget?: boolean;
  // Designer-side flags used by the Layers panel. Not read by the
  // banner renderer — they only affect what the advertiser can edit
  // and see in the authoring UI. `locked` prevents selection /
  // transform; `hidden` hides the item in the canvas. Both default
  // to undefined (treated as false).
  locked?: boolean;
  hidden?: boolean;
  // Designer-side flag: true if this item was emitted by the auto-
  // layout generator and the author hasn't touched it yet. Used by
  // the size-matrix fanout status pill ("authored" vs "auto-layout")
  // so authors can tell which sizes still need manual review. Stripped
  // by state.ts::updateItem so any user edit flips the whole size
  // from generated → authored.
  _generated?: boolean;
  // Designer-side group membership. Items that share a groupId move,
  // copy, and select together — picking any one of them expands to
  // the whole group. One group per item (no nesting). Renderer
  // ignores this field.
  groupId?: string;
  // Semantic role this item plays in its layout template. Used by
  // the role-based brand-kit applier to swap colours (CTA → kit.Accent,
  // headline → kit.Brand, body → kit.Text, etc.) at template-apply
  // time, and by the auto-layout to know which items are content vs
  // chrome. Renderer ignores this field; it's purely metadata.
  role?: "headline" | "subheadline" | "body" | "cta" | "cta-text"
       | "hero" | "accent" | "badge" | "divider";
}

export interface TextItem extends LayoutItemBase {
  type: "text";
  text?: string;
  field?: string;
  fontSize?: number;
  fontFamily?: string;
  color?: string;
  fontWeight?: string | number;
  textAlign?: "left" | "center" | "right" | "justify";
  border?: boolean;
  // How the text behaves when it's taller than the item's height:
  //   "clip"   — strict containment, overflow is hidden (default)
  //   "shrink" — auto-fit by reducing font-size until it fits
  // Only meaningful when the item has an explicit height.
  textFit?: "clip" | "shrink";
  // Where the text sits inside an explicit-height box. Boxes are
  // worst-case regions (templates size them for the longest copy a
  // slot might get), so short copy leaves headroom — this controls
  // where that headroom goes. Default "top" matches the historical
  // render. Only meaningful when the item has an explicit height.
  verticalAlign?: "top" | "middle" | "bottom";
  // CSS writing-mode. Defaults to horizontal-tb (Latin/CJK horizontal).
  // Use vertical-rl for Japanese tategaki (top-to-bottom, columns
  // run right-to-left).
  writingMode?: "horizontal-tb" | "vertical-rl";
  // CSS direction. Defaults to ltr. Set to rtl for Arabic/Hebrew.
  direction?: "ltr" | "rtl";
  // Multiplier on font-size (CSS line-height number). Defaults to 1.2
  // when omitted, matching the prior hardcoded behaviour.
  lineHeight?: number;
}

export interface ImageItem extends LayoutItemBase {
  type: "image";
  src?: string;
  field?: string;
  borderRadius?: number;
  /**
   * Crop rectangle in percent of the natural image (0-100). When set,
   * the item's bounding box shows only this sub-rectangle of the
   * source, scaled to fill. Omit for no crop (full image shown with
   * object-fit:cover).
   *
   * Example: { x: 10, y: 0, w: 80, h: 100 } trims 10% off each side.
   */
  crop?: { x: number; y: number; w: number; h: number };
  /**
   * How the image fills its box. "fill" (default) — object-fit:cover, may
   * crop to the box aspect (honours `crop`). "fit" — object-fit:contain,
   * the whole image shows at its natural proportion, letterboxed inside
   * the box (ignores `crop`). Lets the author keep the original aspect.
   */
  fillMode?: "fill" | "fit";
  // ---- Edge effects (all optional, all default to off) ----
  // The four below are applied by the shared image-effects helper so the
  // collapsed and expanded renderers stay byte-identical.
  /**
   * Feather: soft fade to transparent on all four edges. Amount in cqmin
   * (the inset depth of the fade). 0/undefined = off. Composited with
   * `tornEdge` via the CSS mask channel when both are set.
   */
  feather?: number;
  /**
   * Vignette: darkened (or tinted) edges via a radial overlay. `strength`
   * 0..1 controls how far in and how dark the falloff reaches. 0 = off.
   * `color` overrides the default black falloff (its own alpha wins).
   */
  vignette?: { strength: number; color?: string };
  /**
   * Torn / deckle rough edge, rendered as an SVG-mask displacement.
   * Roughly the displacement intensity (1..10 sensible). 0/undefined = off.
   */
  tornEdge?: number;
}

export interface RectItem extends LayoutItemBase {
  type: "rect";
  fill?: string;
  stroke?: string;
  // Corner radius (cqmin). Omit/zero for square corners.
  borderRadius?: number;
  /**
   * Gradient scrim params. When present, the designer treats this rect
   * as a legibility scrim: `fill` holds a CSS gradient COMPOSED from
   * these fields (see creative-designer/scrim.ts::scrimGradient), and
   * the props panel shows scrim controls instead of a flat-color picker.
   * The renderer ignores this object — it only ever paints `fill` — so
   * scrims need no render-side support; they're rects with a gradient.
   * Survives save→reload because layout is stored as opaque JSON.
   */
  scrim?: {
    // Which edge holds the OPAQUE end; the gradient fades to transparent
    // toward the opposite side so the image underneath shows through.
    // "radial" is opaque at the centre fading outward (a spotlight darken);
    // "radial-edge" is the reverse — clear centre, opaque rim (a vignette
    // that keeps a centred subject/headline legible).
    edge: "top" | "bottom" | "left" | "right" | "radial" | "radial-edge";
    // Near (opaque-end) colour, #rrggbb.
    color: string;
    // Optional second colour stop. When set the gradient runs
    // color → color2 → transparent (a two-colour scrim); both ends fade.
    color2?: string;
    // Alpha of the opaque end, 0..1. Mid stops scale down from this.
    strength: number;
  };
}

export interface CircleItem extends LayoutItemBase {
  type: "circle";
  radius?: number;
  fill?: string;
  stroke?: string;
}

export type LayoutItem = TextItem | ImageItem | RectItem | CircleItem;

/** Full-bleed video that plays underneath the layout in every mode.
  * Applies to Expanded PC, Expanded Mobile, and all IAB banner sizes —
  * one video per Page, positioned behind every layout item in every
  * size fanout. Auto-layout regeneration preserves it (lives on Page,
  * not on layout items). */
export interface VideoBg {
  src: string;
  fit?: "cover" | "contain";
  loop?: boolean;
  muted?: boolean;
  autoplay?: boolean;
  poster?: string;
  opacity?: number;
  /** Trim in/out in seconds. When both set, the player seeks to `inSec`
    * on load and loops back to `inSec` when `currentTime >= outSec`. */
  inSec?: number;
  outSec?: number;
  /** Size in bytes — optional, populated at upload time so the designer
    * can surface "Video 2.1 MB" in the canvas foot without a HEAD fetch. */
  sizeBytes?: number;
}

/** Full-bleed image texture that fills the page behind the layout in
  * every mode. Like VideoBg it lives on Page (not on a layout item), so
  * auto-layout regeneration preserves it and every size fanout renders
  * the same texture. Layers just above the solid bg color / video and
  * below every layout item. */
export interface TextureBg {
  src: string;
  /** "tile" repeats the image (at its natural size, or `scale` px) to
    * fill the slot; "cover" scales a single copy to cover the slot. */
  mode: "tile" | "cover";
  /** 0..1, default 1. */
  opacity?: number;
  /** Tile width in CSS px — "tile" mode only. Unset = natural pixel
    * size. Keeping it a fixed px keeps the pattern's physical scale
    * constant as the fluid creative grows (it just tiles more). */
  scale?: number;
  /** Focal point for "cover" mode as percentages 0..100 — which part of
    * the image stays visible when it's cropped to fill the slot. Maps to
    * background-position. Default 50/50 (center). Ignored in "tile". */
  focusX?: number;
  focusY?: number;
  /** Optional blend of the texture over the page's bg color / video,
    * so a texture can tint a colored card. Default "normal". */
  blend?: "normal" | "multiply" | "overlay" | "screen" | "soft-light";
  /** Size in bytes — optional, populated at upload time for the panel
    * readout (mirrors VideoBg.sizeBytes). */
  sizeBytes?: number;
}

export interface Page {
  headline?: string;
  sub?: string;
  body?: string;
  caption?: string;
  tag?: string;
  img?: string;
  imgEmoji?: string;
  bg?: string;
  accent?: string;
  ctaLabel?: string;
  layout?: LayoutItem[];
  banners?: Record<string, LayoutItem[]>;
  designAspect?: string;
  videoBg?: VideoBg;
  textureBg?: TextureBg;
  /** Designer-only flag, meaningful on page 0: while true, the page-1
    * background color is kept copied onto every page (the designer
    * enforces it on edit; bg values stay materialized per page, so the
    * renderer never reads this). */
  syncBg?: boolean;
  /** Designer-only flag, meaningful on page 0: the headline text colour
    * syncs across all pages while true — and ABSENT means true (that has
    * always been the typography-sync behavior, unlike syncBg). Explicit
    * false scopes headline colour edits to their own page. Renderer
    * never reads this; colours stay materialized per item. */
  syncHeadlineColor?: boolean;
  [key: string]: unknown;
}

// Expand-time transitions the wrapper has CSS for. Not a user choice
// anymore: the reader always deals its sheets in ("stack" — the
// kawaraban lifecycle). "fade" survives only as the prefers-reduced-
// motion fallback and as the wrapper's post-flight close envelope.
export const EXPAND_EFFECTS = ["fade", "stack"] as const;
export type ExpandAnimation = typeof EXPAND_EFFECTS[number];

// Paper weight for the interactive page-peel — the "stock" the magazine
// is printed on. Purely a FEEL preset: it tunes how the peel responds to
// the thumb (how far you pull per fold, how easily it commits, how hard a
// flick throws it) and how the released page settles (spring stiffness /
// damping). Listed as a const tuple so the designer can enumerate the
// choices with no drift from what the engine accepts.
export const PAPER_WEIGHTS = ["light", "medium", "heavy"] as const;
export type PaperWeight = typeof PAPER_WEIGHTS[number];

export interface PaperFeel {
  travel: number;   // thumb-distance per fold: t = dx / (width · travel)
  commitAt: number; // fold fraction at release that commits the turn
  flickVel: number; // release velocity (progress/sec) that throws it over
  springK: number;  // settle-spring stiffness
  springC: number;  // settle-spring damping (lower = more flop / overshoot)
  tempo: number;    // deal-in/out speed multiplier: heavy paper enters and
                    // leaves the pile SLOWER (more mass), light snaps
}

// light  = onionskin: pulls easily, commits early, flicks readily, and the
//          released page flops over with a lively low-damped spring.
// medium = the default hand-feel.
// heavy  = card stock: stiff to pull, needs a firmer commit, and settles
//          with more damping (a weighty, deliberate turn).
export const PAPER_FEEL: Record<PaperWeight, PaperFeel> = {
  light:  { travel: 1.4, commitAt: 0.33, flickVel: 1.85, springK: 158, springC: 15, tempo: 0.8 },
  medium: { travel: 1.5, commitAt: 0.35, flickVel: 2.0, springK: 150, springC: 17, tempo: 1.0 },
  heavy:  { travel: 1.9, commitAt: 0.42, flickVel: 2.6, springK: 130, springC: 24, tempo: 1.3 },
};

export interface BannerConfig {
  layout: "auto";
  font: "sans" | "serif";
  showTag: boolean;
  showSub: boolean;
  // Legacy field: persisted creatives may still carry "fade" or
  // "crt-power-on" from when the designer offered a choice. The engine
  // ignores it — it MUST NOT be honored, because every creative saved
  // before 2026-07-17 has "fade" pinned from the old default and would
  // silently lose the deal. The advertiser's real choice is `entrance`.
  expandAnimation?: string;
  // The reader's entrance/exit. "deal" (default) = the kawaraban
  // lifecycle: sheets deal in on open, fly away on finish, scatter on
  // close. "fade" = a plain fade in and out for advertisers who don't
  // want the theatre; page turns inside the reader are unaffected.
  entrance?: "deal" | "fade";
  // Paper stock for the interactive page-peel (see PAPER_FEEL). Defaults
  // to "medium" when unset. Purely a hand-feel preset — no visual change.
  paperWeight?: PaperWeight;
  // Base tone of the paper BACK revealed as a page peels (the flap, the
  // dog-ear tease, the folded corner — all one stock). A CSS color; the
  // fiber + mottle texture and lighting gradient still ride on top. Unset
  // → the default warm paper tone (#f0e9d9).
  paperBackColor?: string;
  /**
   * Brand logo overlay, rendered on EVERY page + size at the same
   * position (a single creative-wide logo). Position/size in percent of
   * the design box; always object-fit:contain (logos never crop).
   */
  logo?: { src: string; left: number; top: number; width: number; height: number };
  // Legacy override of the expand-effect duration in milliseconds.
  // No designer UI sets it anymore (tempo comes from paperWeight); a
  // stored value still lands as `--expand-duration` on the wrapper.
  expandDurationMs?: number;
  designAspect?: string;
  // Reading direction for the page-turn / dog-ear / nav. "auto"
  // (default) resolves to RTL when the content is Arabic or uses
  // vertical-rl writing; "ltr"/"rtl" force it. RTL mirrors the peel
  // (bottom-left corner), the dog-ear (top-left), and the nav arrows.
  readingDirection?: "auto" | "ltr" | "rtl";
  // Which page is the "cover" — the static frame shown in the
  // publisher's slot before the reader expands. Defaults to 0
  // (first page) when undefined. Author-controlled via the designer's
  // page selector so the cover can be a later page if that frame is
  // the strongest hook.
  coverPageIdx?: number;
}
