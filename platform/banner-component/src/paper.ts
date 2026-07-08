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
// mottle settles it back to roughly the same perceived tone.
const PAPER_BASE = "#f0e9d9";

/** Background list for a paper-back surface: caller's lighting/sheen
  * gradient on top, then the two texture tiles, then the base tone.
  * Pair with PAPER_BACK_BLEND on background-blend-mode. */
export function paperBackBackground(sheen: string): string {
  return `${sheen}, url("${FIBER_TILE}"), url("${MOTTLE_TILE}"), ${PAPER_BASE}`;
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

  return {
    keptClip: toClip(kept) ?? "polygon(0px 0px, 0px 0px, 0px 0px)",
    cutClip: toClip(cut),
    flapTransform: `matrix(${a},${b},${c},${d},${e},${f})`,
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

// ── Turn driver ──────────────────────────────────────────────────────

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
    // block the see-through to the page beneath.
    if (sheet) sheet.style.background = "transparent";
  }

  let flap: HTMLElement | null = null;
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
      if (sheet) sheet.style.background = "";
    }
    turning.classList.remove("page-peeling");
  };
  const settle = (): void => {
    if (settled) return;
    settled = true;
    clearTimeout(failsafe);
    if (rafId !== null) cancelAnimationFrame(rafId);
    flap?.remove();
    if (sheet) {
      sheet.style.clipPath = "";
      sheet.style.opacity = "";
    }
    restoreFoldState();
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
    turning.classList.add("page-peeling");

    const start = performance.now();
    const frame = (now: number): void => {
      if (settled) return;
      const u = Math.min(1, (now - start) / durationMs);
      const t = direction === "next" ? easeInOutCubic(u) : 1 - easeInOutCubic(u);
      // Un-peel landing: hand the fold back while still in motion so
      // the flap drop + notch switch are masked (see restoreFoldState).
      if (direction === "prev" && t <= 0.12) restoreFoldState();
      const ff = foldFrame(t, w, h, notch, rtl);
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
      if (u < 1) rafId = requestAnimationFrame(frame);
      else settle();
    };
    rafId = requestAnimationFrame(frame);
  }
  return settle;
}
