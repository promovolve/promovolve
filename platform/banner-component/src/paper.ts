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
const PAPER_BASE = "#f0e9d9";

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

  /* The cone curl's ROLL strip (the tube hugging the fold), drawn as
   * its own element beneath the sail (.paper-flap) so the horn's union
   * silhouette can never self-intersect. Same paper stock; its crown
   * gradient comes from foldShading per frame. */
  .paper-tube {
    position: absolute;
    inset: 0;
    z-index: 6;
    pointer-events: none;
    display: none;
    background: ${paperBackBackground("linear-gradient(0deg, rgba(0,0,0,0.06), rgba(0,0,0,0.06))")};
    background-blend-mode: ${PAPER_BACK_BLEND};
    will-change: clip-path, opacity;
  }

  /* Per-frame crease shading on the un-peeled sheet: a fold-following
   * gradient (set from foldShading) that darkens the page toward the
   * lift line — the lifted paper shading the page it's still attached
   * to. Without it the fold reads as a printed line, not a bend.
   * Lives inside .paper-sheet so the kept clip crops it for free. */
  .paper-crease-shade {
    position: absolute;
    inset: 0;
    z-index: 6;
    pointer-events: none;
    display: none;
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
  // Lift rides the SHORT side: a height-proportional arc over-rotated
  // the fold on tall sheets (a 9:16 portrait got a too-acute diagonal
  // crease — the fold should sit more obtuse there, per the classic
  // curl reference).
  let p: Pt = { x, y: h - Math.sin(Math.PI * t) * Math.min(w, h) * 0.28 };
  // INEXTENSIBILITY (turn.js's folding secret — its compute() recurses
  // with a corrected drag point whenever the fold would stretch the
  // sheet): the mirrored corner can never sit farther from any anchored
  // page corner than the paper between them. The unconstrained line
  // violated this past mid-turn (22% stretch at t=0.75 on a 9:16 sheet
  // — the "wrong diagonal"); clamping makes the corner RIDE the reach
  // circles, which is the elegant terminal sweep of a real page turn.
  const anchors = rtl
    ? [
        { kx: 0, ky: 0, r: h },
        { kx: w, ky: h, r: w },
        { kx: w, ky: 0, r: Math.hypot(w, h) },
      ]
    : [
        { kx: w, ky: 0, r: h },
        { kx: 0, ky: h, r: w },
        { kx: 0, ky: 0, r: Math.hypot(w, h) },
      ];
  for (let pass = 0; pass < 2; pass++) {
    for (const a of anchors) {
      const dx = p.x - a.kx, dy = p.y - a.ky;
      const d = Math.hypot(dx, dy);
      if (d > a.r) p = { x: a.kx + (dx * a.r) / d, y: a.ky + (dy * a.r) / d };
    }
  }
  return p;
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

/** Cone-curl tube radius (px) at the base junction for a w×h sheet at
  * progress t. Scales with the sheet (the reference look — the classic
  * iOS page-curl — runs a tube of roughly a tenth of the short side),
  * and breathes with the lift so the roll relaxes as the page settles.
  * Shared by foldFrame (silhouette) and foldShading (the roll's light
  * band and shadow must be sized to the same radius). */
export function curlRadius(t: number, w: number, h: number): number {
  const base = Math.max(22, Math.min(72, Math.min(w, h) * 0.16));
  // CONSTANT through the turn (the shader-breakdown approach): breathing
  // with lift modulated the sail rotation and tube fatness mid-sweep,
  // which read as the sheet changing SIZE in motion. Only the first few
  // percent ramp in, so the curl doesn't pop into existence.
  return base * Math.min(1, t * 12);
}


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
  /** Cone-curl only: clip for the roll strip hugging the fold (the
    * tube), rendered as its own element under the sail so the horn's
    * union can't self-intersect into holes. Absent on the mirror path. */
  tubeClip?: string | null;
}


interface PeelBasis { P: Pt; mid: Pt; n: Pt }

/** Fold basis: perpendicular bisector of (home corner -> corner). The
  * earlier apex-compensated variant (pre-rotate the corner, rotate the
  * sail back) caused two real bugs for one subtle nicety: it bent the
  * sail's straight edges once, and at deep turn it swung the whole sail
  * across the fold where the fold-clip erased it mid-drag. A pure
  * mirror sail is stable at every t, keeps edges straight by
  * construction, and lands the corner exactly on the thumb; the cone
  * TUBE carries the curl. (The mirror shows ~π·r of sheet the roll has
  * physically consumed — invisible to the eye at these radii.) */
function peelBasis(t: number, w: number, h: number, rtl: boolean, curl: number, corner?: Pt): PeelBasis {
  void curl;
  const C0: Pt = { x: rtl ? 0 : w, y: h };
  const P = corner ?? cornerAt(t, w, h, rtl);
  const mid: Pt = { x: (C0.x + P.x) / 2, y: (C0.y + P.y) / 2 };
  let nx = P.x - C0.x, ny = P.y - C0.y;
  const len = Math.hypot(nx, ny) || 1;
  nx /= len; ny /= len;
  return { P, mid, n: { x: nx, y: ny } };
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
export function foldFrame(
  t: number, w: number, h: number, notch?: number, rtl = false, curl = 0,
  corner?: Pt,
): FoldFrame {
  const fade = t < 0.8 ? 1 : Math.max(0, (1 - t) / 0.2);
  if (t <= 0.0001) {
    return { keptClip: null, cutClip: null, flapTransform: "", fade: 1 };
  }
  // The corner override is the live THUMB position (interactive peel):
  // the fold is the perpendicular bisector of C0->corner, so tracking
  // the thumb in 2D lets the reader set the fold ANGLE, not just its
  // progress — the canned arc only drives auto-turns and the release.
  // With curl the basis is apex-compensated (see peelBasis).
  const basis = peelBasis(t, w, h, rtl, curl, corner);
  const { mid, n } = basis;
  const nx = n.x, ny = n.y;

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
  if (curl > 0 && cut.length >= 3) {
    // CONE CURL (the iOS page-curl look, from the user's reference):
    // instead of mirroring the cut region, wrap it around a cone lying
    // along the fold — radius near zero at the apex junction, growing
    // toward the base junction. Every boundary point at perpendicular
    // distance d from the fold projects to
    //     proj(d, r) = r·sin(d/r)          (on the roll, overhang ≤ r)
    //     proj(d, r) = −(d − π·r)          (past the roll, flat on top)
    // toward the cut side. That single mapping produces everything the
    // mirror couldn't: the fat rounded tube at the bottom, the concave
    // horn edges (the roll consumes sheet — foreshortening), and the
    // tight tip at the apex. The flap is blank paper, so the shape is
    // drawn DIRECTLY in visual space: cutClip below is final geometry
    // and flapTransform is identity.
    const e: Pt = { x: -nx, y: -ny }; // unit toward the cut side
    const fold: Pt = { x: -ny, y: nx }; // unit along the fold line
    // Fold-lying vertices of the cut region = the two junctions.
    const sideOf = (p: Pt): number => (p.x - mid.x) * nx + (p.y - mid.y) * ny;
    const onFold = cut.map(p => Math.abs(sideOf(p)) < 0.01);
    const jIdx: number[] = [];
    for (let i = 0; i < cut.length; i++) if (onFold[i]) jIdx.push(i);
    if (jIdx.length >= 2) {
      const uOf = (p: Pt): number => (p.x - mid.x) * fold.x + (p.y - mid.y) * fold.y;
      // Extreme fold-vertices along the fold axis: at deep turn the
      // reach constraint parks the fold exactly THROUGH page corners,
      // adding a third on-fold vertex — picking first/last in ring
      // order then collapses the walk to a sliver (the sheet blinked
      // out at the end of a slow drag). Apex = the higher extreme.
      let ja = jIdx[0], jb = jIdx[0];
      for (const i of jIdx) {
        if (uOf(cut[i]) < uOf(cut[ja])) ja = i;
        if (uOf(cut[i]) > uOf(cut[jb])) jb = i;
      }
      if (cut[ja].y > cut[jb].y) { const tmp = ja; ja = jb; jb = tmp; }
      const uApex = uOf(cut[ja]), uBase = uOf(cut[jb]);
      const span = Math.abs(uBase - uApex) || 1;
      const rBase = curl; // tube radius at the base, px
      // Nonlinear taper (the reference look): the horn stays needle-thin
      // for the top half and fattens late into the tube — a linear cone
      // reads as a wide flat sash.
      const rOf = (u: number): number =>
        Math.max(0.5, rBase * Math.pow(Math.min(1, Math.abs(u - uApex) / span), 1.6));
      // Two projections, TWO shapes: drawing the whole warped boundary
      // as one polygon lets the roll's return path loop under the sail
      // and the winding cancels — a visible hole where tube meets sail.
      // So the curl renders as its two physical parts, each a simple
      // single-valued outline: the TUBE (the roll strip hugging the
      // fold, overhang 0→r→0) and the SAIL (the flat folded-over part,
      // pulled in by the π·r the roll consumed). Union = the horn.
      const at = (u: number, proj: number): Pt => ({
        x: mid.x + fold.x * u + e.x * proj,
        y: mid.y + fold.y * u + e.y * proj,
      });
      const warpTube = (p: Pt): Pt => {
        const d = -sideOf(p), u = uOf(p), r = rOf(u);
        return at(u, d < Math.PI * r ? r * Math.sin(Math.min(d / r, Math.PI)) : 0);
      };
      // The sail is FLAT sheet: a pure mirror (an isometry — straight
      // edges stay straight, the corner lands exactly on the thumb, and
      // the image lies on the kept side at every t).
      const warpSail = (p: Pt): Pt => {
        const s = sideOf(p);
        return { x: p.x - 2 * s * nx, y: p.y - 2 * s * ny };
      };
      // Walk the ring from apex to base junction the LONG way (through
      // the page edges + corner). Direction chosen by path length — the
      // old on-fold-vertex test broke when a page corner sat exactly on
      // the fold (guaranteed at deep turn by the reach constraint).
      const ringDir = (step: number): Pt[] => {
        const r: Pt[] = [];
        for (let i = ja; ; i = (i + step + cut.length) % cut.length) {
          r.push(cut[i]);
          if (i === jb && r.length > 1) break;
          if (r.length > cut.length + 1) break; // safety
        }
        return r;
      };
      const pathLen = (r: Pt[]): number => {
        let s = 0;
        for (let i = 1; i < r.length; i++) s += Math.hypot(r[i].x - r[i - 1].x, r[i].y - r[i - 1].y);
        return s;
      };
      const fwd = ringDir(1), bwd = ringDir(-1);
      const walk = pathLen(fwd) >= pathLen(bwd) ? fwd : bwd;
      const sampled: Pt[] = [walk[0]];
      for (let i = 1; i < walk.length; i++) {
        const a = walk[i - 1], b = walk[i];
        const len = Math.hypot(b.x - a.x, b.y - a.y);
        const steps = Math.max(1, Math.min(18, Math.ceil(len / 14)));
        for (let k = 1; k <= steps; k++) {
          sampled.push({ x: a.x + ((b.x - a.x) * k) / steps, y: a.y + ((b.y - a.y) * k) / steps });
        }
      }
      // ENDCAP: the roll runs off the page edge at full diameter — it
      // does not pinch to a point at the base junction (that gap showed
      // a sliver of the front page between tube and sail). Continue the
      // walk past the junction along its page edge; the warp sweeps
      // these into the roll's rounded end, closing the junction.
      {
        const last = walk[walk.length - 1];
        const prev = walk[walk.length - 2] ?? last;
        const el = Math.hypot(last.x - prev.x, last.y - prev.y) || 1;
        const dirX = (last.x - prev.x) / el, dirY = (last.y - prev.y) / el;
        const capLen = rBase * 1.3;
        const CAP = 8;
        for (let k2 = 1; k2 <= CAP; k2++) {
          sampled.push({
            x: last.x + dirX * (capLen * k2) / CAP,
            y: last.y + dirY * (capLen * k2) / CAP,
          });
        }
      }
      return {
        keptClip: toClip(kept) ?? "polygon(0px 0px, 0px 0px, 0px 0px)",
        // Flat material physically ENDS at the fold (the tangent line
        // where the sheet leaves the roll) — the sail's isometry image
        // is clipped there, or its rotated closure juts past the fold
        // at the fat end and the visible crease kinks between sail edge
        // and tube crown. Beyond the fold only the roll (tube) shows.
        cutClip: toClip(clipPoly(sampled.map(warpSail), mid, n, +1)),
        tubeClip: toClip(sampled.map(warpTube)),
        flapTransform: "", // geometry is already in visual space
        fade,
      };
    }
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

// ── Curl shading ─────────────────────────────────────────────────────
//
// The mirror fold is geometrically right but shades like cloth: the
// flap's lighting gradient was a fixed 315° and its drop-shadow a
// constant halo, and the front sheet got nothing at all. Real paper
// bends — the light follows the BEND, not the screen. These are the
// per-frame, fold-anchored replacements: a cylinder profile across the
// crease on the flap's back, an attached-shadow ramp on the front
// sheet fading away from the lift line, and a shadow that grows and
// falls away from the crease as the page lifts.

export interface FoldShading {
  /** Full background stack for the flap (fold-following sheen). */
  flapBackground: string;
  /** drop-shadow filter for the flap, scaled/directed by lift. */
  flapFilter: string;
  /** Gradient for the .paper-crease-shade overlay on the front sheet. */
  creaseShade: string;
  /** Background stack for the roll strip (.paper-tube): lit crown just
    * past the fold turning under into the dark outer silhouette. */
  tubeBackground: string;
  /** drop-shadow for the tube — the roll overhang shading the page it
    * is about to lift, tight and dark. */
  tubeFilter: string;
}

/** Compute the fold-anchored shading for a w×h sheet at progress t.
  * Pure — same inputs as [[foldFrame]]; returns null when flat. */
export function foldShading(
  t: number, w: number, h: number, rtl = false, corner?: Pt,
): FoldShading | null {
  if (t <= 0.0001) return null;
  const C0: Pt = { x: rtl ? 0 : w, y: h };
  // Same apex-compensated basis as foldFrame, so every gradient stays
  // anchored on the fold that is actually drawn.
  const basis = peelBasis(t, w, h, rtl, curlRadius(t, w, h), corner);
  const P = basis.P;
  const mid = basis.mid;
  const nx = basis.n.x, ny = basis.n.y;
  const lift = Math.sin(Math.PI * t); // page height off the stack, 0→1→0

  // A linear-gradient along unit direction u, with stops positioned in
  // px from the gradient's own start edge: CSS angle 0deg points up and
  // the axis runs through the box center, so a point's stop position is
  // its projection onto u plus half the axis length.
  const grad = (u: Pt): { deg: number; L: number; sMid: number } => {
    const th = Math.atan2(u.x, -u.y);
    const L = Math.abs(w * Math.sin(th)) + Math.abs(h * Math.cos(th));
    const sMid = (mid.x - w / 2) * u.x + (mid.y - h / 2) * u.y + L / 2;
    return { deg: (th * 180) / Math.PI, L, sMid };
  };

  // Shading intensity rides the CORNER'S TRAVEL (turn.js's far/width
  // ramp), not the lift arc: the lift fades in the back half of the
  // sweep and took the shadows with it — a page mid-flight went
  // weightless. Travel keeps them strong until the melt-out.
  const travel = Math.hypot(P.x - C0.x, P.y - C0.y);
  const k = 0.30 + 0.70 * Math.min(1, travel / w);

  // The shadow band is GEOMETRIC (turn.js: gradientSize = side·sin α):
  // it spans a fraction of the distance from the fold to the farthest
  // still-flat corner, so mid-turn the penumbra sweeps hundreds of px
  // across the page — the single biggest "real paper" cue in both
  // turn.js and the iOS reference. A fixed-width band reads as a line.
  const dKept = Math.max(
    0,
    -mid.x * nx + -mid.y * ny,
    (w - mid.x) * nx + -mid.y * ny,
    (w - mid.x) * nx + (h - mid.y) * ny,
    -mid.x * nx + (h - mid.y) * ny,
  );
  const band = Math.min(340, Math.max(48, dKept * 0.45));

  // The curl body is drawn in VISUAL space (see the cone path in
  // foldFrame — flapTransform is identity), so this gradient shades the
  // roll where it actually is: axis from the fold toward the cut side.
  // Reading along it: the flat folded-over part (kept side, plain paper
  // with a soft curvature shade approaching the fold), then the roll's
  // lit crown just past the fold (the tube catching light), turning
  // under into the dark outer silhouette at ~r.
  const r = curlRadius(t, w, h);
  const gF = grad({ x: -nx, y: -ny });
  // The SAIL (flat folded-over part, kept side of the fold). A sheet
  // mid-air is never OPTICALLY flat even when geometrically flat — a
  // uniform fill reads as a sticker (user: "the flatness of the fold
  // doesn't look right"). Full-surface profile, far edge → fold: gentle
  // falloff at the free edge, a broad soft sheen across the middle
  // (the sheet bowing toward the light), neutral, then turn.js's crease
  // treatment — the roll's self-shadow hugging the fold, snapping to
  // the lit fold line. Extent is the sail's actual reach (fold→corner).
  const ext = Math.max(48, travel / 2);
  const flapSheen =
    `linear-gradient(${gF.deg.toFixed(2)}deg,` +
    ` rgba(0,0,0,${(0.15 * k).toFixed(3)}) ${(gF.sMid - ext).toFixed(1)}px,` +
    ` rgba(255,255,255,${(0.12 * k).toFixed(3)}) ${(gF.sMid - ext * 0.55).toFixed(1)}px,` +
    ` rgba(0,0,0,0.02) ${(gF.sMid - ext * 0.25).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.20 * k).toFixed(3)}) ${(gF.sMid - Math.max(6, r * 0.4)).toFixed(1)}px,` +
    ` rgba(255,255,255,${(0.20 * k).toFixed(3)}) ${gF.sMid.toFixed(1)}px)`;
  // The TUBE (roll strip past the fold): lit crown turning under into
  // the dark outer silhouette at ~r.
  const tubeSheen =
    `linear-gradient(${gF.deg.toFixed(2)}deg,` +
    ` rgba(255,255,255,${(0.20 * k).toFixed(3)}) ${gF.sMid.toFixed(1)}px,` +
    ` rgba(255,255,255,${(0.32 * k).toFixed(3)}) ${(gF.sMid + r * 0.35).toFixed(1)}px,` +
    ` rgba(255,255,255,0.02) ${(gF.sMid + r * 0.7).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.40 * k).toFixed(3)}) ${(gF.sMid + r).toFixed(1)}px)`;

  // Front sheet: the classic two-part crease treatment (turn.js's
  // signature) — a LIT HAIRLINE right at the fold (the bend catching
  // light, seen from the front) snapping into the attached shadow,
  // which fades into the kept side. Width is GEOMETRIC: it follows the
  // fold's depth (∝ corner travel), not the lift arc, so the penumbra
  // breathes with the fold shape like a real cast shadow.
  const gS = grad({ x: nx, y: ny });
  const creaseShade =
    `linear-gradient(${gS.deg.toFixed(2)}deg,` +
    ` rgba(255,255,255,${(0.20 * k).toFixed(3)}) ${(gS.sMid - 1).toFixed(1)}px,` +
    ` rgba(255,255,255,${(0.20 * k).toFixed(3)}) ${(gS.sMid + 1).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.30 * k).toFixed(3)}) ${(gS.sMid + 6).toFixed(1)}px,` +
    ` rgba(0,0,0,${(0.10 * k).toFixed(3)}) ${(gS.sMid + band * 0.45).toFixed(1)}px,` +
    ` rgba(0,0,0,0) ${(gS.sMid + band).toFixed(1)}px)`;

  // Cast shadow, LAYERED (the reference's broad penumbra hugging the
  // horn): a tight contact shadow plus a wide soft halo, both falling
  // toward the kept side and sized by the roll radius — the sheet rides
  // the roll, so the bigger the tube, the higher it floats. Without the
  // halo the sail reads as appliqued onto the page, not above it.
  const off = 3 + 10 * lift;
  const off2 = r * 0.7;
  const flapFilter =
    `drop-shadow(${(nx * off).toFixed(1)}px ${(ny * off).toFixed(1)}px ` +
    `${(6 + 12 * lift).toFixed(0)}px rgba(0,0,0,${(0.22 + 0.16 * lift).toFixed(2)}))` +
    ` drop-shadow(${(nx * off2).toFixed(1)}px ${(Math.abs(ny * off2) + r * 0.3).toFixed(1)}px ` +
    `${(r * 1.5).toFixed(0)}px rgba(0,0,0,${(0.42 * k).toFixed(2)}))`;
  // The roll's own shadow onto the page it is about to lift (the
  // reveal side) — tight, hugging the tube's outer silhouette.
  const tubeFilter =
    `drop-shadow(${(-nx * r * 0.25).toFixed(1)}px ${(Math.abs(ny) * r * 0.25 + 2).toFixed(1)}px ` +
    `${(r * 0.7).toFixed(0)}px rgba(0,0,0,${(0.32 * k).toFixed(2)}))`;

  return {
    flapBackground: paperBackBackground(flapSheen),
    flapFilter,
    creaseShade,
    tubeBackground: paperBackBackground(tubeSheen),
    tubeFilter,
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
    tube?.remove();
    shade?.remove();
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
    // The cone curl's roll strip — rendered beneath the sail.
    tube = document.createElement("div");
    tube.className = "paper-tube";
    tube.appendChild(buildGrainOverlay());
    stack.appendChild(tube);
    // Fold-anchored crease shading on the front sheet (see foldShading).
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
      const ff = foldFrame(t, w, h, notch, rtl, curlRadius(t, w, h));
      sheet.style.clipPath = ff.keptClip ?? "";
      sheet.style.opacity = String(ff.fade);
      const sh = foldShading(t, w, h, rtl);
      // Independent per-layer gating — see the same block in
      // createInteractivePeel.
      if (flap) {
        if (sh && ff.cutClip) {
          flap.style.display = "block";
          flap.style.clipPath = ff.cutClip;
          flap.style.transform = ff.flapTransform;
          flap.style.opacity = String(ff.fade);
          flap.style.background = sh.flapBackground;
          flap.style.filter = sh.flapFilter;
        } else {
          flap.style.display = "none";
        }
      }
      if (tube) {
        if (sh && ff.tubeClip) {
          tube.style.display = "block";
          tube.style.clipPath = ff.tubeClip;
          tube.style.opacity = String(ff.fade);
          tube.style.background = sh.tubeBackground;
          tube.style.filter = sh.tubeFilter;
        } else {
          tube.style.display = "none";
        }
      }
      if (shade) {
        if (sh && (ff.cutClip || ff.tubeClip)) {
          shade.style.display = "block";
          shade.style.background = sh.creaseShade;
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
  under: HTMLElement;   // the next page, already resting beneath
  rtl?: boolean;
  springK?: number;     // settle-spring stiffness (paper weight)
  springC?: number;     // settle-spring damping (paper weight)
  onCommit: () => void; // reached t=1: swap in the next page (instant)
  onCancel: () => void; // returned to t=0: nothing turned, restore resting
}): { scrubTo: (t: number, corner?: { x: number; y: number }) => void; release: (commit: boolean, v0?: number) => void } | null {
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

  const priorTransition = { turning: turning.style.transition, under: under.style.transition };
  for (const el of [turning, under]) {
    el.style.transition = "none";
    el.style.opacity = "1";
    el.style.pointerEvents = "none";
  }
  turning.style.zIndex = "30";
  under.style.zIndex = "20";

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
    sheet.style.background = "transparent";
  }
  const notch = punchClip
    ? sheet.querySelector<HTMLElement>(".dogear-corner")?.getBoundingClientRect().width ?? 0
    : 0;

  const flap = document.createElement("div");
  flap.className = "paper-flap";
  flap.appendChild(buildGrainOverlay());
  stack.appendChild(flap);
  // The cone curl's roll strip — rendered beneath the sail.
  const tube = document.createElement("div");
  tube.className = "paper-tube";
  tube.appendChild(buildGrainOverlay());
  stack.appendChild(tube);
  // Fold-anchored crease shading on the front sheet (see foldShading) —
  // clipped by the sheet's kept clip for free.
  const shade = document.createElement("div");
  shade.className = "paper-crease-shade";
  sheet.appendChild(shade);
  turning.classList.add("page-peeling");

  let lastT = 0;
  let lastCorner: { x: number; y: number } | undefined;
  let rafId: number | null = null;
  let done = false;

  const paint = (t: number, corner?: { x: number; y: number }, melt = false): void => {
    const ff = foldFrame(t, w, h, notch, rtl, curlRadius(t, w, h), corner);
    // The melt-out (fade past t=0.8) exists for the CLOCK-driven turn,
    // where speed masks it. A held thumb must never watch the sheet
    // evaporate mid-air — while scrubbing, the paper stays fully there;
    // only the release flight melts.
    const fade = melt ? ff.fade : 1;
    sheet.style.clipPath = ff.keptClip ?? "";
    sheet.style.opacity = String(fade);
    const sh = foldShading(t, w, h, rtl, corner);
    // Each layer renders on its own clip: gating everything on the
    // sail's clip once hid the tube+shade whenever the sail vanished —
    // the folded sheet blinked out mid-drag.
    if (sh && ff.cutClip) {
      flap.style.display = "block";
      flap.style.clipPath = ff.cutClip;
      flap.style.transform = ff.flapTransform;
      flap.style.opacity = String(fade);
      flap.style.background = sh.flapBackground;
      flap.style.filter = sh.flapFilter;
    } else {
      flap.style.display = "none";
    }
    if (sh && ff.tubeClip) {
      tube.style.display = "block";
      tube.style.clipPath = ff.tubeClip;
      tube.style.opacity = String(fade);
      tube.style.background = sh.tubeBackground;
      tube.style.filter = sh.tubeFilter;
    } else {
      tube.style.display = "none";
    }
    if (sh && (ff.cutClip || ff.tubeClip)) {
      shade.style.display = "block";
      shade.style.background = sh.creaseShade;
      shade.style.opacity = String(fade);
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
    }
    turning.classList.remove("page-peeling");
    turning.style.zIndex = "";
    under.style.zIndex = "";
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
      under.style.transition = priorTransition.under;
    }));
  };

  return {
    // corner = the live thumb position in sheet coords: the fold is the
    // perpendicular bisector of (home corner -> thumb), so tracking the
    // thumb in 2D lets the reader set the fold's ANGLE with their hand
    // (a 9:16 portrait sheet especially wants a more obtuse fold than
    // the canned arc gives).
    scrubTo: (t: number, corner?: { x: number; y: number }): void => {
      if (done) return;
      lastT = clamp01(t);
      lastCorner = corner;
      paint(lastT, corner);
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
      let cornerOv = lastCorner;
      const step = (now: number): void => {
        if (done) return;
        const dt = Math.min(0.032, Math.max(0, (now - prev) / 1000));
        prev = now;
        const accel = (target - t) * springK - v * springC;
        v += accel * dt;
        t += v * dt;
        // The thumb's fold-angle override decays back onto the canned
        // arc as the spring carries the page, so the release lands on
        // the same path the auto-turn uses.
        if (cornerOv) {
          const cAt = cornerAt(clamp01(t), w, h, rtl);
          cornerOv = {
            x: cAt.x + (cornerOv.x - cAt.x) * 0.85,
            y: cAt.y + (cornerOv.y - cAt.y) * 0.85,
          };
          if (Math.hypot(cornerOv.x - cAt.x, cornerOv.y - cAt.y) < 2) cornerOv = undefined;
        }
        // Reaching the far edge (commit) or flat (cancel) ends it — past
        // t=1 the flap has already faded out, so there's nothing to settle.
        if (commit && t >= 1) { finish(true); return; }
        if (!commit && t <= 0) { paint(0, undefined, true); finish(false); return; }
        // Asymptotic approach with no crossing (heavily damped): stop once
        // it's effectively there and barely moving.
        if (Math.abs(target - t) < 0.004 && Math.abs(v) < 0.06) { finish(commit); return; }
        paint(clamp01(t), cornerOv, true);
        rafId = requestAnimationFrame(step);
      };
      rafId = requestAnimationFrame(step);
    },
  };
}
