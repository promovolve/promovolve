// Paper identity for the expanded reader: grain texture, physical
// sheet styling (shadow + stacked leaves), and the turn.js-style
// corner-peel page turn used by desktop navigation. No image assets
// and no dependencies — the grain is an inline SVG feTurbulence tile
// and the peel is ~80 lines of fold geometry, so the delivered
// bundle stays self-contained (turn.js itself is jQuery-based and
// non-commercial-licensed, both non-starters here).
//
// Same injection pattern as EXPAND_EFFECT_CSS: renderOverlay() drops
// PAPER_CSS into a <style> inside the overlay; banner.ts calls
// animatePageTurn() on navigation.

// 160px tile of fractal noise. URL-encoded inline so it can sit in a
// CSS url() without escaping issues ('#' must be %23).
const GRAIN_TILE =
  "data:image/svg+xml," +
  "%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E" +
  "%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E" +
  "%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E";

// ── The magazine's paper stock (the BACK of every page) ─────────────
// Two texture tiles layered over a warm base make the flat tone read
// as uncoated paper: a fine desaturated fiber "tooth" (soft-light — a
// neutral-gray noise leaves the tone alone and only modulates it) and
// a much larger, softer pulp mottling (multiply, faint). Shared by the
// page-turn flap, the dog-ear tease flap, and the folded corner
// triangle, so the whole magazine is one paper.
const FIBER_TILE =
  "data:image/svg+xml," +
  "%3Csvg xmlns='http://www.w3.org/2000/svg' width='200' height='200'%3E" +
  "%3Cfilter id='f'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.7' numOctaves='2' seed='11' stitchTiles='stitch'/%3E" +
  "%3CfeColorMatrix type='saturate' values='0'/%3E%3C/filter%3E" +
  "%3Crect width='100%25' height='100%25' filter='url(%23f)' opacity='0.55'/%3E%3C/svg%3E";
const MOTTLE_TILE =
  "data:image/svg+xml," +
  "%3Csvg xmlns='http://www.w3.org/2000/svg' width='320' height='320'%3E" +
  "%3Cfilter id='m'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.035' numOctaves='3' seed='4' stitchTiles='stitch'/%3E" +
  "%3CfeColorMatrix type='saturate' values='0'/%3E%3C/filter%3E" +
  "%3Crect width='100%25' height='100%25' filter='url(%23m)' opacity='0.2'/%3E%3C/svg%3E";
// Base is a touch lighter than the old flat #ece4d3 — the multiply
// mottle settles it back to roughly the same perceived tone. It's the
// FALLBACK for --paper-back-color, which a creative can override to print
// on a different stock; the fiber/mottle/sheen layers ride on top either
// way (soft-light + multiply modulate whatever base tone shows through).
export const PAPER_BASE = "#f0e9d9";

/**
 * Resolve the paper-back stock for a banner's peeled surfaces (tease,
 * thumb-peel, pinned flap, page-turn back). A translucent page
 * background carries its alpha onto the back of the sheet — see-through
 * paper is see-through from BOTH sides; an opaque cream flap over a
 * translucent ad reads as a sticker on the article. A configured
 * back color that already carries its own alpha wins as-is.
 * Returns undefined when the default opaque stock should apply.
 */
export function paperBackStock(
  configured: string | undefined,
  pageBg: string | undefined,
): string | undefined {
  if (configured && !/^#[0-9a-fA-F]{6}$/.test(configured)) return configured;
  const alpha = pageBg && /^#[0-9a-fA-F]{8}$/.test(pageBg) ? pageBg.slice(7, 9) : undefined;
  if (!alpha) return configured;
  return `${configured ?? PAPER_BASE}${alpha}`;
}

/** Background list for a paper-back surface: caller's lighting/sheen
  * gradient on top, then the two texture tiles, then the base tone. The
  * base is a CSS var so a creative can recolor the stock (banner.ts sets
  * --paper-back-color from config); the texture layers are unchanged.
  * Pair with PAPER_BACK_BLEND on background-blend-mode. */
export function paperBackBackground(sheen: string): string {
  return `${sheen}, url("${FIBER_TILE}"), url("${MOTTLE_TILE}"), var(--paper-back-color, ${PAPER_BASE})`;
}
export const PAPER_BACK_BLEND = "normal, soft-light, multiply, normal";

export const PAPER_CSS = `
  /* The page's outer box. No stacked-leaf pseudos and no ambient
   * pile shadow — an earlier iteration had both (an offset "stack"
   * peeking out behind the sheet), but pages reveal from directly
   * BENEATH during a peel, never from that pile, so it read as
   * incoherent set dressing. The dim scrim alone separates the
   * floating sheet from the article. */
  .paper-stack {
    background: var(--leaf-bg, #e8e4da);
    /* The pile-shift: when a page is turned, the remaining sheets
     * slide forward one stack slot (banner.ts drives the translate);
     * this transition is that slide. Inert during peels — the peel
     * animates the sheet inside, never the box. */
    transition: transform 280ms ease;
  }
  /* While THIS page is peeling, its box gets out of the way so the
   * lifting sheet reveals the real page beneath — the incoming page
   * element sits directly underneath at the same centered position. */
  .page-peeling .paper-stack {
    background: transparent;
  }

  /* The sheet: the thin leaf carrying the creative (and the dog-ear
   * flap — a fold belongs to its page and peels with it). During a
   * turn its clip-path is driven per frame by animatePageTurn().
   * A light contact shadow is the only shadow on the page. */
  .paper-sheet {
    box-shadow:
      0 1px 2px rgba(0,0,0,0.30),
      0 4px 12px rgba(0,0,0,0.22);
    /* Only ever visible through transient gaps (the dog-ear punch
     * clips the WHOLE pageBox — a folded corner is a hole punched
     * through the magazine to the article behind, see buildDogEar). */
    background: var(--leaf-bg, #e8e4da);
  }

  /* The BACK of the peeling page: a full-page blank-paper layer,
   * clipped (in local coords) to the region folded past the crease
   * and mirrored across the fold line via a reflection matrix.
   * transform-origin 0 0 because the matrix is derived for a
   * top-left origin (CSS defaults to center). drop-shadow runs after
   * clipping, so the shadow traces the curled shape onto the page
   * beneath. The lighting gradient is the curl's specular cue. */
  .paper-flap {
    position: absolute;
    inset: 0;
    z-index: 7;
    pointer-events: none;
    transform-origin: 0 0;
    display: none;
    /* Every page's back is the SAME paper stock (one magazine, one
     * paper) — a fixed warm tone rather than the page's own bg, so a
     * dark creative still peels with a paper-colored back. Fiber +
     * mottle tiles under the lighting gradient make it read as
     * uncoated paper instead of a flat fill (paperBackBackground). */
    background: ${paperBackBackground(
      `linear-gradient(315deg,
        rgba(0,0,0,0.20) 0%,
        rgba(0,0,0,0.04) 22%,
        rgba(255,255,255,0.28) 52%,
        rgba(0,0,0,0.10) 100%)`,
    )};
    background-blend-mode: ${PAPER_BACK_BLEND};
    filter: drop-shadow(0 0 22px rgba(0,0,0,0.45));
    will-change: transform, clip-path, opacity;
  }

  /* The roll strip just past the fold — the curl's ROUNDNESS. The
   * production flap is a flat mirror ending at the fold; this band is
   * the bending paper between the two, painted with the roll's lit
   * crown turning under into its dark silhouette (foldRoundness).
   * Same paper stock as the flap; sits beneath it. */
  .paper-tube {
    position: absolute;
    inset: 0;
    z-index: 6;
    pointer-events: none;
    display: none;
    background: ${paperBackBackground("linear-gradient(0deg, rgba(0,0,0,0.05), rgba(0,0,0,0.05))")};
    background-blend-mode: ${PAPER_BACK_BLEND};
    will-change: clip-path, opacity;
  }

  /* Per-frame fold shadow on the un-peeled sheet: a fold-anchored
   * gradient (foldShadow) — the lit line at the crease snapping into
   * the attached shadow that fades into the page. The one addition on
   * top of the production fold: without it the fold reads as a printed
   * line, not a bend. Inside .paper-sheet so the kept clip crops it. */
  .paper-crease-shade {
    position: absolute;
    inset: 0;
    z-index: 6;
    pointer-events: none;
    display: none;
  }

  /* While the LAST sheet is lifted / in flight, the chrome gets out
   * of the way — the reader is ending; buttons must not hover over a
   * departing sheet. Applied by banner.ts (.last-flight on .overlay)
   * the moment the 3rd sheet is grabbed or the close pill is clicked;
   * removed if the drag cancels and the sheet lands back. */
  .overlay.last-flight .close-pill,
  .overlay.last-flight .nav-prev,
  .overlay.last-flight .nav-next,
  .overlay.last-flight .page-counter {
    opacity: 0 !important;
    pointer-events: none !important;
    transition: opacity 160ms ease;
  }

  /* Fine grain over the whole composition. mix-blend-mode:overlay
   * reads as texture on both light and dark authored backgrounds;
   * opacity is low enough to never fight the creative. */
  .paper-grain {
    position: absolute;
    inset: 0;
    z-index: 5;
    pointer-events: none;
    opacity: 0.05;
    mix-blend-mode: overlay;
    background-image: url("${GRAIN_TILE}");
  }
`;

/** Grain overlay for a page stage. Appended last so it sits above the
  * layout items (which never set z-index); pointer-events:none keeps
  * CTA hit-testing intact. */
export function buildGrainOverlay(): HTMLElement {
  const grain = document.createElement("div");
  grain.className = "paper-grain";
  return grain;
}

// ── Fold geometry ────────────────────────────────────────────────────
//
// The peel grabs the bottom-right corner. At progress t the corner
// has traveled along an arc toward (and past) the left edge; the
// fold line is the perpendicular bisector between the corner's home
// position and its current position. Everything on the corner's side
// of that line is "folded over": the front sheet is clipped to the
// other side, and the flap renders the folded region mirrored across
// the line (clip-path applies in local coords before transform, so
// clipping the cut region and reflecting the whole layer lands it
// exactly on the visible fold).

interface Pt { x: number; y: number }

/** Where the grabbed corner is at progress t — linear travel across to
  * the mirrored position with a sine lift so the page arcs up off the
  * stack instead of dragging flat. LTR grabs the bottom-RIGHT corner
  * (home x=w, travels left); RTL grabs the bottom-LEFT (home x=0,
  * travels right) for Arabic / Japanese-vertical reading. */
export function cornerAt(t: number, w: number, h: number, rtl = false): Pt {
  const x = rtl ? 2 * w * t : w - 2 * w * t;
  return { x, y: h - Math.sin(Math.PI * t) * h * 0.28 };
}

// Sutherland–Hodgman: clip `poly` to the half-plane where
// sign((p - M)·n) * keep >= 0.
function clipPoly(poly: Pt[], M: Pt, n: Pt, keep: 1 | -1): Pt[] {
  const side = (p: Pt): number => ((p.x - M.x) * n.x + (p.y - M.y) * n.y) * keep;
  const out: Pt[] = [];
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i], b = poly[(i + 1) % poly.length];
    const sa = side(a), sb = side(b);
    if (sa >= 0) out.push(a);
    if ((sa >= 0) !== (sb >= 0)) {
      const u = sa / (sa - sb);
      out.push({ x: a.x + (b.x - a.x) * u, y: a.y + (b.y - a.y) * u });
    }
  }
  return out;
}

const toClip = (poly: Pt[]): string | null =>
  poly.length < 3
    ? null
    : "polygon(" + poly.map(p => `${p.x.toFixed(2)}px ${p.y.toFixed(2)}px`).join(",") + ")";

export interface FoldFrame {
  /** clip-path for the front sheet (null = leave unclipped). */
  keptClip: string | null;
  /** clip-path for the flap's local cut region (null = no flap yet). */
  cutClip: string | null;
  /** Reflection matrix() mapping the cut region onto the fold. */
  flapTransform: string;
  /** Opacity for sheet+flap: 1 through most of the turn, melting to
    * 0 over the last 20% — there is no left page to receive the
    * turned sheet (single-sheet magazine, not a book spread), so it
    * dissolves as it sweeps past the spine. */
  fade: number;
}

/** Compute one frame of the peel for a w×h sheet at progress t∈[0,1].
  *
  * `notch` (px) is the dog-ear's corner size when the page is folded:
  * the corner triangle is folded away from the leaf, so the leaf's
  * BACK — the curling layer — must show the same notched silhouette
  * along the crease (from (w-notch, 0) to (w, notch)). Without it the
  * curl sweeps past the corner as a pristine rectangle while the
  * front's fold is consumed in place — geometrically wrong, and it
  * reads as such. One extra half-plane clip on the cut region (in
  * local coords, pre-mirror) fixes it. */
export function foldFrame(t: number, w: number, h: number, notch?: number, rtl = false): FoldFrame {
  const fade = t < 0.8 ? 1 : Math.max(0, (1 - t) / 0.2);
  if (t <= 0.0001) {
    return { keptClip: null, cutClip: null, flapTransform: "", fade: 1 };
  }
  const C0: Pt = { x: rtl ? 0 : w, y: h };
  const P = cornerAt(t, w, h, rtl);
  const mid: Pt = { x: (C0.x + P.x) / 2, y: (C0.y + P.y) / 2 };
  let nx = P.x - C0.x, ny = P.y - C0.y;
  const len = Math.hypot(nx, ny) || 1;
  nx /= len; ny /= len;
  const n: Pt = { x: nx, y: ny };

  // The fold-over region is the side CONTAINING the original corner:
  // side(C0) = (C0-mid)·n = -|C0P|/2 < 0 → cut = negative side.
  const rect: Pt[] = [{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: h }, { x: 0, y: h }];
  const kept = clipPoly(rect, mid, n, +1);
  let cut = clipPoly(rect, mid, n, -1);
  if (notch && notch > 0 && cut.length >= 3) {
    // Dog-eared leaf: the folded-away top corner triangle notches the
    // curling back along the crease. LTR's corner is top-right (notch
    // from (w-notch,0); inward page-side normal (-1,1)); RTL mirrors to
    // the top-left (notch from (notch,0); normal (1,1)). Normalization
    // is irrelevant for the sign test.
    cut = rtl
      ? clipPoly(cut, { x: notch, y: 0 }, { x: 1, y: 1 }, +1)
      : clipPoly(cut, { x: w - notch, y: 0 }, { x: -1, y: 1 }, +1);
  }

  // Reflection across the fold line: A = I - 2nnᵀ, b = 2(M·n)n.
  const Mn = mid.x * nx + mid.y * ny;
  const a = 1 - 2 * nx * nx, b = -2 * nx * ny,
        c = -2 * nx * ny,    d = 1 - 2 * ny * ny,
        e = 2 * Mn * nx,     f = 2 * Mn * ny;

  // The exit: this is a STACK OF SHEETS, not a book — there is no left
  // page for the turned sheet to land on. So over the same window the
  // melt-fade runs (t > 0.8), the sheet also FLIES off the pile: the
  // flap picks up an accelerating translation up-and-away while it
  // dissolves. (The fly-away was discovered by accident in the slide-
  // curl experiment — its geometry carried the page off-screen — and
  // kept on purpose: paper leaves, it doesn't just evaporate in place.)
  let flapTransform = `matrix(${a},${b},${c},${d},${e},${f})`;
  if (t > 0.8) {
    const exit = (t - 0.8) / 0.2;
    const ease = exit * exit; // accelerating, like a tossed sheet
    // ~45° up-and-away (equal screen-space x/y) — a flatter exit read
    // as sliding, not being tossed off the pile.
    const fx = (rtl ? 1 : -1) * w * 1.0 * ease;
    const fy = -w * 1.0 * ease;
    flapTransform = `translate(${fx.toFixed(1)}px, ${fy.toFixed(1)}px) ` + flapTransform;
  }

  return {
    keptClip: toClip(kept) ?? "polygon(0px 0px, 0px 0px, 0px 0px)",
    cutClip: toClip(cut),
    flapTransform,
    fade,
  };
}

/** One frame of a small DOG-EAR corner peel for the hover hint — same
  * machinery as [[foldFrame]] but the grabbed corner is the TOP one
  * (top-right LTR / top-left RTL, where the dog-ear lives) and it travels
  * a short way diagonally inward, so it reads as the corner lifting like
  * a page turn rather than a flat fold. Keep `t` small (≈0.1–0.2): this
  * is a teaser, not a full fold. */
/** How far (× the short side) the grabbed corner travels per unit of peel
  * progress. Exported so the resting dog-ear can size its crease to match
  * the peel exactly: at peak `t`, the fold leg = min(w,h) · t · this. */
export const DOGEAR_PEEL_TRAVEL = 1.7;

export function dogEarPeelFrame(t: number, w: number, h: number, rtl = false): FoldFrame {
  if (t <= 0.0001) return { keptClip: null, cutClip: null, flapTransform: "", fade: 1 };
  const C0: Pt = { x: rtl ? 0 : w, y: 0 }; // the dog-ear's top corner
  // Travel diagonally inward (down-left for an LTR top-right corner),
  // scaled by the short side so depth is consistent across aspects.
  const d = Math.min(w, h) * t * DOGEAR_PEEL_TRAVEL;
  const P: Pt = { x: C0.x + (rtl ? d : -d), y: C0.y + d };
  const mid: Pt = { x: (C0.x + P.x) / 2, y: (C0.y + P.y) / 2 };
  let nx = P.x - C0.x, ny = P.y - C0.y;
  const len = Math.hypot(nx, ny) || 1;
  nx /= len; ny /= len;
  const n: Pt = { x: nx, y: ny };
  const rect: Pt[] = [{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: h }, { x: 0, y: h }];
  const kept = clipPoly(rect, mid, n, +1);   // sheet keeps the far side
  const cut = clipPoly(rect, mid, n, -1);     // folded triangle (holds C0)
  const Mn = mid.x * nx + mid.y * ny;
  const a = 1 - 2 * nx * nx, b = -2 * nx * ny,
        c = -2 * nx * ny,    dd = 1 - 2 * ny * ny,
        e = 2 * Mn * nx,     f = 2 * Mn * ny;
  return {
    keptClip: toClip(kept) ?? "polygon(0px 0px, 0px 0px, 0px 0px)",
    cutClip: toClip(cut),
    flapTransform: `matrix(${a},${b},${c},${dd},${e},${f})`,
    fade: 1,
  };
}

/** The fold's shadow on the still-flat sheet — the one lighting cue
  * layered onto the production fold (geometry untouched). Anatomy from
  * studying turn.js: a lit hairline exactly AT the crease (the bend
  * catching light) snapping into an attached shadow that ramps out
  * into the page. Two geometric properties matter: the band's WIDTH
  * follows the fold's depth (a fixed-width band reads as a drawn
  * line), and its INTENSITY rides the corner's travel (riding the lift
  * arc made the shadow fade mid-flight, a weightless page). Returns a
  * background-image for .paper-crease-shade, or null when flat. */
export function foldShadow(t: number, w: number, h: number, rtl = false): string | null {
  if (t <= 0.0001) return null;
  const C0: Pt = { x: rtl ? 0 : w, y: h };
  const P = cornerAt(t, w, h, rtl);
  const mid: Pt = { x: (C0.x + P.x) / 2, y: (C0.y + P.y) / 2 };
  let nx = P.x - C0.x, ny = P.y - C0.y;
  const len = Math.hypot(nx, ny) || 1;
  nx /= len; ny /= len;
  const travel = Math.hypot(P.x - C0.x, P.y - C0.y);
  const k = 0.30 + 0.70 * Math.min(1, travel / w);
  // Band width: a fraction of the distance from the fold to the
  // farthest still-flat corner (turn.js: gradientSize = side·sin α).
  const dKept = Math.max(
    0,
    (0 - mid.x) * nx + (0 - mid.y) * ny,
    (w - mid.x) * nx + (0 - mid.y) * ny,
    (w - mid.x) * nx + (h - mid.y) * ny,
    (0 - mid.x) * nx + (h - mid.y) * ny,
  );
  const band = Math.min(320, Math.max(42, dKept * 0.45));
  // CSS gradient along +n (from the fold into the kept side): angle for
  // direction u is atan2(u.x, -u.y); the axis runs through the box
  // center, so a point's stop position is its projection plus half the
  // axis length.
  const th = Math.atan2(nx, -ny);
  const L = Math.abs(w * Math.sin(th)) + Math.abs(h * Math.cos(th));
  const sMid = (mid.x - w / 2) * nx + (mid.y - h / 2) * ny + L / 2;
  return (
    `linear-gradient(${((th * 180) / Math.PI).toFixed(2)}deg,` +
    ` rgba(255,255,255,${(0.18 * k).toFixed(3)}) ${(sMid - 1).toFixed(1)}px,` +
    ` rgba(255,255,255,${(0.18 * k).toFixed(3)}) ${(sMid + 1).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.28 * k).toFixed(3)}) ${(sMid + 6).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.10 * k).toFixed(3)}) ${(sMid + band * 0.45).toFixed(1)}px,` +
    ` rgba(0,0,0,0) ${(sMid + band).toFixed(1)}px)`
  );
}

/** RETIRED — returns null. Every layer-based roll (cone or strip, on
  * either fold model) read as a band, never as bending paper: the
  * roll's signature is nonlinear foreshortening, which CSS transforms
  * cannot express. The one sound future route is a small <canvas>
  * drawing the band strip-by-strip (classic JS page-flip technique).
  * Kept as the call-site seam for that experiment. */
export function foldRoundness(
  t: number, w: number, h: number, rtl = false,
): { clip: string; background: string } | null {
  void t; void w; void h; void rtl;
  return null;
}

// ── Slide-curl model (gl_SimplePageCurl, scriptituk/xfade-easing) ────
//
// The classic transition's structural secret: the crease's ANGLE is
// CONSTANT — progress only SLIDES the axis across the page. Everything
// derives from one signed distance to that axis, so every clip is a
// straight line at a fixed angle, the flap's reflection has a constant
// linear part, and the roll is a constant-width strip. All of the hard
// geometry of the rotating-crease model (junction walks, cone tapers,
// reach constraints) simply doesn't exist here.

/** Which curl the reader uses. "corner" = the production rotating
  * mirror fold; "slide" = the fixed-angle sweeping curl. */
export const CURL_MODEL: "corner" | "slide" = "corner";

/** Crease tilt from vertical (radians). The xfade default (angle 80°)
  * is a 10° tilt; the classic iOS look sits nearer 30°. */
const SLIDE_TILT = (30 * Math.PI) / 180;

interface SlideBasis { e: Pt; A: Pt; r: number; s: number; smax: number }

function slideBasis(t: number, w: number, h: number, rtl: boolean): SlideBasis {
  // e: unit normal of the crease, pointing toward the grabbed corner.
  const e: Pt = { x: Math.cos(SLIDE_TILT) * (rtl ? -1 : 1), y: Math.sin(SLIDE_TILT) };
  const C0: Pt = { x: rtl ? 0 : w, y: h };
  // r = 0: the tube is retired. Rendering a believable roll from
  // silhouette layers was attempted exhaustively (two geometric models,
  // five variants) and always read as a band, never as bending paper —
  // the roll needs per-pixel foreshortening a DOM layer can't do.
  // turn.js's answer, adopted here: a FLAT fold whose roundness lives
  // entirely in the crease lighting (slideShadow). With r=0 the whole
  // model collapses gracefully: back meets front exactly at the crease.
  const r = 0;
  let far = 0;
  for (const K of [{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: h }, { x: 0, y: h }]) {
    far = Math.max(far, (C0.x - K.x) * e.x + (C0.y - K.y) * e.y);
  }
  // Sweep until the whole sheet AND its roll are past the far corner —
  // the turn completes geometrically, no melt-fade needed.
  const smax = far + Math.PI * r + 24;
  const s = t * smax;
  return { e, A: { x: C0.x - e.x * s, y: C0.y - e.y * s }, r, s, smax };
}

/** One frame of the slide curl, in the same shape foldFrame returns:
  * keptClip clips the front sheet to the un-swept side of the axis;
  * cutClip/flapTransform render the folded-over back — a reflection
  * about the line HALF the roll's arc behind the axis (the sheet the
  * roll consumed), clipped to source points at least π·r from the
  * axis (nearer ones are ON the roll, drawn by slideRoundness). */
export function slideFrame(t: number, w: number, h: number, notch?: number, rtl = false): FoldFrame {
  if (t <= 0.0001) {
    return { keptClip: null, cutClip: null, flapTransform: "", fade: 1 };
  }
  const { e, A, r } = slideBasis(t, w, h, rtl);
  const rect: Pt[] = [{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: h }, { x: 0, y: h }];
  const kept = clipPoly(rect, A, e, -1);
  const srcLine: Pt = { x: A.x + e.x * Math.PI * r, y: A.y + e.y * Math.PI * r };
  let cut = clipPoly(rect, srcLine, e, +1);
  if (notch && notch > 0 && cut.length >= 3) {
    cut = rtl
      ? clipPoly(cut, { x: notch, y: 0 }, { x: 1, y: 1 }, +1)
      : clipPoly(cut, { x: w - notch, y: 0 }, { x: -1, y: 1 }, +1);
  }
  // Reflection about the line through M = A + e·(π·r/2), normal e.
  const M: Pt = { x: A.x + (e.x * Math.PI * r) / 2, y: A.y + (e.y * Math.PI * r) / 2 };
  const nx = e.x, ny = e.y;
  const Mn = M.x * nx + M.y * ny;
  const a = 1 - 2 * nx * nx, b = -2 * nx * ny,
        c = -2 * nx * ny,    d = 1 - 2 * ny * ny,
        e0 = 2 * Mn * nx,    f0 = 2 * Mn * ny;
  return {
    keptClip: toClip(kept) ?? "polygon(0px 0px, 0px 0px, 0px 0px)",
    cutClip: toClip(cut),
    flapTransform: `matrix(${a},${b},${c},${d},${e0},${f0})`,
    fade: 1, // the sweep completes geometrically — nothing to melt
  };
}

/** The roll: a constant-width strip along the axis (d ∈ [0, r]) with
  * the crown lighting. The strip meeting the page edges obliquely IS
  * the classic bottom wave — no endcap machinery needed. */
export function slideRoundness(
  t: number, w: number, h: number, rtl = false,
): { clip: string; background: string } | null {
  if (t <= 0.0001) return null;
  const { e, A, r, s } = slideBasis(t, w, h, rtl);
  if (r < 1 || s <= 0) return null;
  const rect: Pt[] = [{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: h }, { x: 0, y: h }];
  let strip = clipPoly(rect, A, e, +1);
  strip = clipPoly(strip, { x: A.x + e.x * r, y: A.y + e.y * r }, e, -1);
  const clip = toClip(strip);
  if (!clip) return null;
  const k = 0.35 + 0.65 * Math.min(1, t * 4);
  const th = Math.atan2(e.x, -e.y);
  const L = Math.abs(w * Math.sin(th)) + Math.abs(h * Math.cos(th));
  const sA = (A.x - w / 2) * e.x + (A.y - h / 2) * e.y + L / 2;
  const sheen =
    `linear-gradient(${((th * 180) / Math.PI).toFixed(2)}deg,` +
    ` rgba(0,0,0,${(0.10 * k).toFixed(3)}) ${sA.toFixed(1)}px,` +
    ` rgba(255,255,255,${(0.30 * k).toFixed(3)}) ${(sA + r * 0.45).toFixed(1)}px,` +
    ` rgba(255,255,255,0.02) ${(sA + r * 0.75).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.38 * k).toFixed(3)}) ${(sA + r).toFixed(1)}px)`;
  return { clip, background: paperBackBackground(sheen) };
}

/** The fold's shadow on the still-flat sheet: lit contact line at the
  * axis snapping into an attached shadow that ramps backward over the
  * un-swept page (geometric width, travel-scaled intensity). */
export function slideShadow(t: number, w: number, h: number, rtl = false): string | null {
  if (t <= 0.0001) return null;
  const { e, A } = slideBasis(t, w, h, rtl);
  const k = 0.30 + 0.70 * Math.min(1, t * 4);
  // Axis direction REVERSED so gradient stops increase into the
  // un-swept side (CSS stops must ascend).
  const u: Pt = { x: -e.x, y: -e.y };
  const th = Math.atan2(u.x, -u.y);
  const L = Math.abs(w * Math.sin(th)) + Math.abs(h * Math.cos(th));
  const sA = (A.x - w / 2) * u.x + (A.y - h / 2) * u.y + L / 2;
  // Band: fraction of how much un-swept page remains.
  let dKept = 0;
  for (const K of [{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: h }, { x: 0, y: h }]) {
    dKept = Math.max(dKept, (K.x - A.x) * u.x + (K.y - A.y) * u.y);
  }
  const band = Math.min(320, Math.max(42, dKept * 0.45));
  return (
    `linear-gradient(${((th * 180) / Math.PI).toFixed(2)}deg,` +
    ` rgba(255,255,255,${(0.18 * k).toFixed(3)}) ${(sA - 1).toFixed(1)}px,` +
    ` rgba(255,255,255,${(0.18 * k).toFixed(3)}) ${(sA + 1).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.28 * k).toFixed(3)}) ${(sA + 6).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.10 * k).toFixed(3)}) ${(sA + band * 0.45).toFixed(1)}px,` +
    ` rgba(0,0,0,0) ${(sA + band).toFixed(1)}px)`
  );
}

// ── Turn driver ──────────────────────────────────────────────────────

/** The page turn does not PEEL. This reader is a stack of loose
  * sheets (a kawaraban pile, not a bound book): the natural gesture is
  * to slide the top sheet off — it follows the thumb from the first
  * pixel, picks up a little rotation, and sails away. Folding belongs
  * to the DOG-EAR (a deliberate crease you make to keep something).
  * The grip cue is a LIFT, not a fold: at first touch the sheet is
  * picked up off the pile — a slight scale-up and a blooming cast
  * shadow — and then it slides. No crease, no paper back, ever. */
export const SHEET_RELEASE_T = 0;

const easeInOutCubic = (t: number): number =>
  t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;

/** Run the corner-peel page turn between two page containers (the
  * absolute `.page-N` elements, each holding a `.paper-stack` with a
  * `.paper-sheet` inside).
  *
  * - next: the outgoing page (on top) peels away from its bottom-
  *   right corner; the incoming page is already flat beneath it.
  * - prev: the incoming page (on top) un-peels back into place over
  *   the still-visible outgoing page — the same drive run backwards,
  *   so the back-turn is inherently the forward turn in reverse.
  *
  * Returns a finish-now function: calling it settles the turn
  * immediately (used when the reader spams next/prev). Settling is
  * idempotent; frame completion, the failsafe timeout, and finish-now
  * all funnel into the same cleanup. A page with no .paper-sheet
  * (missing-layout placeholder) simply rides the failsafe — the
  * pages swap at the end without a peel. */
export function animatePageTurn(opts: {
  outgoing: HTMLElement;
  incoming: HTMLElement;
  direction: "next" | "prev";
  durationMs?: number;
  // RTL reading (Arabic / Japanese-vertical): peel the bottom-LEFT
  // corner instead of the bottom-right.
  rtl?: boolean;
  onDone: () => void;
}): () => void {
  const { outgoing, incoming, direction, onDone } = opts;
  const rtl = opts.rtl ?? false;
  const durationMs = opts.durationMs ?? 850;
  const turning = direction === "next" ? outgoing : incoming;
  const under = direction === "next" ? incoming : outgoing;
  const stack = turning.querySelector<HTMLElement>(".paper-stack");
  const sheet = turning.querySelector<HTMLElement>(".paper-sheet");

  const priorTransition = {
    outgoing: outgoing.style.transition,
    incoming: incoming.style.transition,
  };
  // Both pages fully visible for the duration, without the containers'
  // inline opacity transition fading anything mid-turn.
  for (const el of [outgoing, incoming]) {
    el.style.transition = "none";
    el.style.opacity = "1";
    el.style.pointerEvents = "none";
  }
  // Well above the resting page-stack z-band (banner.ts lays upcoming
  // sheets at single-digit z) so a turn always rides on top of the pile.
  turning.style.zIndex = "30";
  under.style.zIndex = "20";

  // A dog-eared page carries a punch clip on its (static) pageBox.
  // For the duration of the peel, hand that clip to the stage inside
  // the sheet so the notch TRAVELS with the peeling page (nested
  // clips intersect with the per-frame peel clip) instead of staying
  // glued to the page frame and biting the curl at a fixed position.
  const stage = sheet?.querySelector<HTMLElement>(".paper-stage") ?? null;
  const punchClip = stack?.style.clipPath ?? "";
  if (stack && stage && punchClip) {
    stack.style.clipPath = "";
    stack.style.removeProperty("-webkit-clip-path");
    stage.style.clipPath = punchClip;
    stage.style.setProperty("-webkit-clip-path", punchClip);
    // The sheet's own background would fill the traveling notch and
    // block the see-through to the page beneath — and its rectangular
    // contact shadow, no longer clipped by the stack, would trace a
    // dark contour around the transparent notch. Both off in flight.
    if (sheet) { sheet.style.background = "transparent"; sheet.style.boxShadow = "none"; }
  }

  let flap: HTMLElement | null = null;
  let tube: HTMLElement | null = null;
  let shade: HTMLElement | null = null;
  let rafId: number | null = null;
  let settled = false;
  // Fold-state restore is separate from settle so a REVERSE turn can
  // run it early: on an un-peel the dog-eared corner arrives near the
  // end, and restoring the punch (and dropping the lifted flap) only
  // at settle popped visibly on an already-resting page. Running it
  // while the page is still landing lets motion mask the switch — the
  // forward turn gets the same masking for free at turn start.
  let foldRestored = false;
  const restoreFoldState = (): void => {
    if (foldRestored) return;
    foldRestored = true;
    if (stack && stage && punchClip) {
      stage.style.clipPath = "";
      stage.style.removeProperty("-webkit-clip-path");
      stack.style.clipPath = punchClip;
      stack.style.setProperty("-webkit-clip-path", punchClip);
      if (sheet) { sheet.style.background = ""; sheet.style.boxShadow = ""; }
    }
    turning.classList.remove("page-peeling");
  };
  const settle = (): void => {
    if (settled) return;
    settled = true;
    clearTimeout(failsafe);
    if (rafId !== null) cancelAnimationFrame(rafId);
    flap?.remove();
    tube?.remove();
    shade?.remove();
    if (sheet) {
      sheet.style.clipPath = "";
      sheet.style.opacity = "";
    }
    restoreFoldState();
    turning.style.transform = "";
    turning.style.filter = "";
    turning.style.zIndex = "";
    under.style.zIndex = "";
    // Resting state first (onDone sets the final opacities while the
    // transitions are still 'none'), THEN restore the inline opacity
    // transitions — double rAF so the restored 0.5s transition can
    // never animate the hide onDone just committed.
    onDone();
    requestAnimationFrame(() => requestAnimationFrame(() => {
      outgoing.style.transition = priorTransition.outgoing;
      incoming.style.transition = priorTransition.incoming;
    }));
  };
  // Failsafe mirrors the old animationend safety net: a hidden tab
  // (throttled rAF), a missing sheet, or anything else that stalls
  // the frame loop still lands the pages in their resting state.
  const failsafe = setTimeout(settle, durationMs + 200);

  if (sheet && stack) {
    const rect = sheet.getBoundingClientRect();
    const w = rect.width || 1;
    const h = rect.height || 1;
    // Dog-eared page: the folded-away corner notches the curling back
    // (see foldFrame). The dog-ear button IS the corner box — its
    // rendered size is the notch size in px.
    const notch = punchClip
      ? sheet.querySelector<HTMLElement>(".dogear-corner")?.getBoundingClientRect().width ?? 0
      : 0;
    flap = document.createElement("div");
    flap.className = "paper-flap";
    // Texture on the paper back — inherits the flap's clip+transform.
    flap.appendChild(buildGrainOverlay());
    stack.appendChild(flap);
    // The roll strip past the fold — the curl's roundness.
    tube = document.createElement("div");
    tube.className = "paper-tube";
    tube.appendChild(buildGrainOverlay());
    stack.appendChild(tube);
    // The fold's shadow on the still-flat sheet (see foldShadow).
    shade = document.createElement("div");
    shade.className = "paper-crease-shade";
    sheet.appendChild(shade);
    turning.classList.add("page-peeling");

    const start = performance.now();
    const frame = (now: number): void => {
      if (settled) return;
      const u = Math.min(1, (now - start) / durationMs);
      const t = direction === "next" ? easeInOutCubic(u) : 1 - easeInOutCubic(u);
      // Un-peel landing: hand the fold back while still in motion so
      // the flap drop + notch switch are masked (see restoreFoldState).
      if (direction === "prev" && t <= 0.12) restoreFoldState();
      // Grip until SHEET_RELEASE_T, then the bend freezes and the whole
      // sheet departs (release-and-slide — a loose sheet lifts off the
      // pile; it never folds over a crease like a bound page).
      // The corner lift RELAXES once the sheet comes free — slightly
      // bent paper springs back flat; it doesn't stay creased.
      const relRaw = t <= SHEET_RELEASE_T ? 0 : (t - SHEET_RELEASE_T) / (1 - SHEET_RELEASE_T);
      const tBend = t <= SHEET_RELEASE_T ? t : SHEET_RELEASE_T * Math.max(0, 1 - relRaw * 2.5);
      const ff = CURL_MODEL === "slide" ? slideFrame(t, w, h, notch, rtl) : foldFrame(tBend, w, h, notch, rtl);
      const rel = CURL_MODEL === "slide" || t <= SHEET_RELEASE_T
        ? 0 : (t - SHEET_RELEASE_T) / (1 - SHEET_RELEASE_T);
      if (rel > 0) {
        // LIFT: picked up off the pile in the first sliver of drag —
        // slight scale, blooming cast shadow. Then the SLIDE: x follows
        // the drag linearly (the sheet moves WITH the thumb); the
        // upward arc and spin build later.
        const lift = Math.min(1, rel / 0.08);
        const fx = -w * 1.05 * rel * (rtl ? -1 : 1);
        const fy = -w * 0.55 * rel * rel;
        const rot = -6 * rel * (rtl ? -1 : 1);
        turning.style.transform =
          `translate(${fx.toFixed(1)}px, ${fy.toFixed(1)}px) rotate(${rot.toFixed(2)}deg) scale(${(1 + 0.018 * lift).toFixed(4)})`;
        turning.style.filter =
          `drop-shadow(0 ${(12 * lift).toFixed(1)}px ${(22 * lift).toFixed(1)}px rgba(0,0,0,${(0.32 * lift).toFixed(2)}))`;
        turning.style.opacity = String(rel < 0.75 ? 1 : Math.max(0, 1 - (rel - 0.75) / 0.25));
      } else {
        turning.style.transform = "";
        turning.style.filter = "";
        turning.style.opacity = "1";
      }
      sheet.style.clipPath = ff.keptClip ?? "";
      sheet.style.opacity = String(ff.fade);
      if (flap) {
        if (ff.cutClip) {
          flap.style.display = "block";
          flap.style.clipPath = ff.cutClip;
          flap.style.transform = ff.flapTransform;
          flap.style.opacity = String(ff.fade);
        } else {
          flap.style.display = "none";
        }
      }
      if (tube) {
        const ro = CURL_MODEL === "slide" ? slideRoundness(t, w, h, rtl) : foldRoundness(t, w, h, rtl);
        if (ro) {
          tube.style.display = "block";
          tube.style.clipPath = ro.clip;
          tube.style.background = ro.background;
          tube.style.opacity = String(ff.fade);
        } else {
          tube.style.display = "none";
        }
      }
      if (shade) {
        const sh = CURL_MODEL === "slide" ? slideShadow(t, w, h, rtl) : foldShadow(tBend, w, h, rtl);
        if (sh) {
          shade.style.display = "block";
          shade.style.backgroundImage = sh;
          shade.style.opacity = String(ff.fade);
        } else {
          shade.style.display = "none";
        }
      }
      if (u < 1) rafId = requestAnimationFrame(frame);
      else settle();
    };
    rafId = requestAnimationFrame(frame);
  }
  return settle;
}

const clamp01 = (t: number): number => (t < 0 ? 0 : t > 1 ? 1 : t);

// Spring that carries the released peel to its resting end. Lightly
// underdamped (ζ≈0.7) so a committed page flops over with a touch of
// follow-through instead of easing to a dead stop — the "weight" of real
// paper. Units: progress per second; K stiffness, C damping.
const PEEL_SPRING_K = 150;
const PEEL_SPRING_C = 17;

/** Interactive corner-peel driver for a FORWARD (next) page turn.
  *
  * Unlike [[animatePageTurn]] — which is clock-driven and fire-and-forget —
  * this hands the fold `progress` to the CALLER: `scrubTo(t)` paints one
  * live frame (drive it from the thumb, linearly, so the corner sticks to
  * the finger), and `release(commit)` hands the rest to a short clock that
  * either finishes the turn (commit) or springs the page back to flat
  * (cancel — the reader let go before the commit threshold).
  *
  * Deliberately NOT a refactor of animatePageTurn: the auto turn (arrows,
  * keyboard) is load-bearing and proven, so the manual driver shares only
  * the pure fold geometry ([[foldFrame]]) and sets its own stage up. The
  * two can be unified once the interactive feel is settled.
  *
  * Setup mirrors animatePageTurn's forward branch: the turning sheet lifts
  * above the next (already-resting) sheet, a paper-backed flap carries the
  * curl, and a dog-eared page's punch clip is handed to the traveling
  * stage so the notch folds WITH the curl. Returns null for a missing-
  * layout placeholder (no sheet to fold) — the caller should just swap. */
export function createInteractivePeel(opts: {
  turning: HTMLElement; // current top page — peels away from its bottom corner
  under: HTMLElement | null; // the next page beneath — null on the LAST
                             // sheet, which flies off over the backdrop
  rtl?: boolean;
  springK?: number;     // settle-spring stiffness (paper weight)
  springC?: number;     // settle-spring damping (paper weight)
  onCommit: () => void; // reached t=1: swap in the next page (instant)
  onCancel: () => void; // returned to t=0: nothing turned, restore resting
}): { scrubTo: (t: number) => void; release: (commit: boolean, v0?: number) => void } | null {
  const { turning, under, onCommit, onCancel } = opts;
  const rtl = opts.rtl ?? false;
  const springK = opts.springK ?? PEEL_SPRING_K;
  const springC = opts.springC ?? PEEL_SPRING_C;
  const stack = turning.querySelector<HTMLElement>(".paper-stack");
  const sheet = turning.querySelector<HTMLElement>(".paper-sheet");
  if (!sheet || !stack) return null;

  const rect = sheet.getBoundingClientRect();
  const w = rect.width || 1;
  const h = rect.height || 1;

  const priorTransition = { turning: turning.style.transition, under: under?.style.transition ?? "" };
  for (const el of [turning, under]) {
    if (!el) continue;
    el.style.transition = "none";
    el.style.opacity = "1";
    el.style.pointerEvents = "none";
  }
  turning.style.zIndex = "30";
  if (under) under.style.zIndex = "20";

  // Punch-clip handoff (dog-eared page): move the notch clip onto the
  // traveling stage so it folds WITH the curl instead of biting it at a
  // fixed frame position — see the same dance in animatePageTurn.
  const stage = sheet.querySelector<HTMLElement>(".paper-stage") ?? null;
  const punchClip = stack.style.clipPath ?? "";
  if (stage && punchClip) {
    stack.style.clipPath = "";
    stack.style.removeProperty("-webkit-clip-path");
    stage.style.clipPath = punchClip;
    stage.style.setProperty("-webkit-clip-path", punchClip);
    // Background AND contact shadow off — the unclipped box-shadow
    // otherwise traces a dark contour around the traveling notch.
    sheet.style.background = "transparent";
    sheet.style.boxShadow = "none";
  }
  const notch = punchClip
    ? sheet.querySelector<HTMLElement>(".dogear-corner")?.getBoundingClientRect().width ?? 0
    : 0;

  const flap = document.createElement("div");
  flap.className = "paper-flap";
  flap.appendChild(buildGrainOverlay());
  stack.appendChild(flap);
  // The roll strip past the fold — the curl's roundness.
  const tube = document.createElement("div");
  tube.className = "paper-tube";
  tube.appendChild(buildGrainOverlay());
  stack.appendChild(tube);
  // The fold's shadow on the still-flat sheet (see foldShadow).
  const shade = document.createElement("div");
  shade.className = "paper-crease-shade";
  sheet.appendChild(shade);
  turning.classList.add("page-peeling");

  let lastT = 0;
  let rafId: number | null = null;
  let done = false;

  const paint = (t: number): void => {
    // Grip until SHEET_RELEASE_T, then the bend freezes and the whole
    // sheet departs (release-and-slide — a loose sheet lifts off the
    // pile; it never folds over a crease like a bound page).
    // The corner lift RELAXES once the sheet comes free — slightly
    // bent paper springs back flat; it doesn't stay creased.
    const relRaw = t <= SHEET_RELEASE_T ? 0 : (t - SHEET_RELEASE_T) / (1 - SHEET_RELEASE_T);
    const tBend = t <= SHEET_RELEASE_T ? t : SHEET_RELEASE_T * Math.max(0, 1 - relRaw * 2.5);
    const ff = CURL_MODEL === "slide" ? slideFrame(t, w, h, notch, rtl) : foldFrame(tBend, w, h, notch, rtl);
    const rel = CURL_MODEL === "slide" || t <= SHEET_RELEASE_T
      ? 0 : (t - SHEET_RELEASE_T) / (1 - SHEET_RELEASE_T);
    if (rel > 0) {
      // LIFT: picked up off the pile in the first sliver of drag —
      // slight scale, blooming cast shadow. Then the SLIDE: x follows
      // the drag linearly (the sheet moves WITH the thumb); the
      // upward arc and spin build later.
      const lift = Math.min(1, rel / 0.08);
      const fx = -w * 1.05 * rel * (rtl ? -1 : 1);
      const fy = -w * 0.55 * rel * rel;
      const rot = -6 * rel * (rtl ? -1 : 1);
      turning.style.transform =
        `translate(${fx.toFixed(1)}px, ${fy.toFixed(1)}px) rotate(${rot.toFixed(2)}deg) scale(${(1 + 0.018 * lift).toFixed(4)})`;
      turning.style.filter =
        `drop-shadow(0 ${(12 * lift).toFixed(1)}px ${(22 * lift).toFixed(1)}px rgba(0,0,0,${(0.32 * lift).toFixed(2)}))`;
      turning.style.opacity = String(rel < 0.75 ? 1 : Math.max(0, 1 - (rel - 0.75) / 0.25));
    } else {
      turning.style.transform = "";
      turning.style.filter = "";
      turning.style.opacity = "1";
    }
    sheet.style.clipPath = ff.keptClip ?? "";
    sheet.style.opacity = String(ff.fade);
    if (ff.cutClip) {
      flap.style.display = "block";
      flap.style.clipPath = ff.cutClip;
      flap.style.transform = ff.flapTransform;
      flap.style.opacity = String(ff.fade);
    } else {
      flap.style.display = "none";
    }
    const ro = CURL_MODEL === "slide" ? slideRoundness(t, w, h, rtl) : foldRoundness(t, w, h, rtl);
    if (ro) {
      tube.style.display = "block";
      tube.style.clipPath = ro.clip;
      tube.style.background = ro.background;
      tube.style.opacity = String(ff.fade);
    } else {
      tube.style.display = "none";
    }
    const sh = CURL_MODEL === "slide" ? slideShadow(t, w, h, rtl) : foldShadow(tBend, w, h, rtl);
    if (sh) {
      shade.style.display = "block";
      shade.style.backgroundImage = sh;
      shade.style.opacity = String(ff.fade);
    } else {
      shade.style.display = "none";
    }
  };

  const teardown = (): void => {
    flap.remove();
    tube.remove();
    shade.remove();
    sheet.style.clipPath = "";
    sheet.style.opacity = "";
    if (stage && punchClip) {
      stage.style.clipPath = "";
      stage.style.removeProperty("-webkit-clip-path");
      stack.style.clipPath = punchClip;
      stack.style.setProperty("-webkit-clip-path", punchClip);
      sheet.style.background = "";
      sheet.style.boxShadow = "";
    }
    turning.classList.remove("page-peeling");
    turning.style.transform = "";
    turning.style.filter = "";
    turning.style.zIndex = "";
    if (under) under.style.zIndex = "";
  };

  const finish = (commit: boolean): void => {
    if (done) return;
    done = true;
    if (rafId !== null) cancelAnimationFrame(rafId);
    teardown();
    // Commit/cancel re-lays the resting state with transitions still off
    // (onCommit swaps the page, onCancel restores the stack z/offsets we
    // overrode); restore the inline transitions a frame later so that
    // relayout can't animate — mirrors animatePageTurn.settle.
    (commit ? onCommit : onCancel)();
    requestAnimationFrame(() => requestAnimationFrame(() => {
      turning.style.transition = priorTransition.turning;
      if (under) under.style.transition = priorTransition.under;
    }));
  };

  return {
    scrubTo: (t: number): void => {
      if (done) return;
      lastT = clamp01(t);
      paint(lastT);
    },
    // Hand the remaining travel to a velocity-seeded spring: the page
    // leaves the thumb at the speed it was moving (v0, progress/sec) and
    // the spring carries it to its end — forward to t=1 (commit) or back
    // to t=0 (cancel) — with a little follow-through so it reads as a
    // sheet with weight rather than a canned tween.
    release: (commit: boolean, v0 = 0): void => {
      if (done) return;
      const target = commit ? 1 : 0;
      let t = lastT;
      let v = v0;
      let prev = performance.now();
      const step = (now: number): void => {
        if (done) return;
        const dt = Math.min(0.032, Math.max(0, (now - prev) / 1000));
        prev = now;
        const accel = (target - t) * springK - v * springC;
        v += accel * dt;
        t += v * dt;
        // Reaching the far edge (commit) or flat (cancel) ends it — past
        // t=1 the flap has already faded out, so there's nothing to settle.
        if (commit && t >= 1) { finish(true); return; }
        if (!commit && t <= 0) { paint(0); finish(false); return; }
        // Asymptotic approach with no crossing (heavily damped): stop once
        // it's effectively there and barely moving.
        if (Math.abs(target - t) < 0.004 && Math.abs(v) < 0.06) { finish(commit); return; }
        paint(clamp01(t));
        rafId = requestAnimationFrame(step);
      };
      rafId = requestAnimationFrame(step);
    },
  };
}
