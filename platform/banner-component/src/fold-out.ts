// Fold-out (gatefold poster) format: the fold maths (pure), then the DOM.
import { PAPER_BACK_BLEND } from "./paper";
import { fontUI } from "./fonts";
import { buildCloseButton } from "./render-overlay";
export const FOLDOUT_MAX = 2; // 3 panels -> progress 0..2
export const FOLDOUT = {
  omega: 8, coverRest: 178, innerRest: 180,
  flickS: 0.15, restMs: 80, dtMax: 0.032, perspective: 1400, slop: 12,
} as const;

const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x));
const smooth = (t: number) => t * t * (3 - 2 * t);

export function isFoldout(cfg: { format?: unknown }): boolean {
  return cfg.format === "foldout";
}

// Hinge angles (degrees from open) for a drag or a close at this progress:
// both creases bend together, like pushing up a real zigzag fold. The third
// panel tips toward the viewer only as far as the second tips away (never
// past edge-on), so its unprinted back never faces the viewer.
export function foldAngles(progress: number): [number, number] {
  const a = FOLDOUT.coverRest * (1 - smooth(clamp(progress / FOLDOUT_MAX, 0, 1)));
  return [a, Math.min(FOLDOUT.innerRest, 2 * a)];
}


type Spring = { p: number; v: number };

// tempo > 1 slows every motion by that factor (the Designer's Tempo knob).
export function springStep(s: Spring, target: number, dt: number, tempo = 1): Spring {
  const d = Math.min(dt, FOLDOUT.dtMax);
  const w = FOLDOUT.omega / tempo;
  const v = s.v + (w * w * (target - s.p) - 2 * w * s.v) * d;
  const p = s.p + v * d;
  if (p <= 0) return { p: 0, v: 0 };
  if (p >= FOLDOUT_MAX) return { p: FOLDOUT_MAX, v: 0 };
  return { p, v };
}

export function springSettled(s: Spring, target: number): boolean {
  return Math.abs(s.p - target) < 1e-3 && Math.abs(s.v) < 0.01;
}

export function releaseTarget(p: number, v: number, msSinceLastMove: number): "open" | "closed" {
  const vv = msSinceLastMove > FOLDOUT.restMs ? 0 : v;
  return p + vv * FOLDOUT.flickS < FOLDOUT_MAX / 2 ? "closed" : "open";
}

// Opening is gravity, not a spring: each hinge is a damped pendulum (θ in
// degrees from open, 0 = hanging flat). It starts slow when let go, speeds
// up as it swings down, swings a few degrees past flat and settles — paper
// falling open. A spring does the opposite (fast start, slow creep in).
// An inner panel is released while the one above is still swinging and
// inherits part of its speed (it hangs off a moving hinge), so the second
// fall overlaps the first instead of starting from rest after it.
export const FALL = { grav: 450, zeta: 0.6, nudge: 60, releaseAt: 140, inherit: 0.6, hMax: 1 / 240 } as const;
const DEG = Math.PI / 180;
// tempo T slows the fall by T: gravity / T², damping / T (same swing shape).
// Gravity pulls on the panel's angle to the screen (gTheta), not on its hinge:
// an inner flap hangs down as the one above drops, instead of riding it over.
export function fallStep(theta: number, omega: number, h: number, tempo = 1, gTheta = theta): [number, number] {
  const grav = FALL.grav / (tempo * tempo);
  const damp = 2 * FALL.zeta * Math.sqrt(grav);
  const w = omega * DEG + (-grav * Math.sin(gTheta * DEG) - damp * omega * DEG) * h;
  return [theta + (w * h) / DEG, w / DEG];
}

export function isTap(dx: number, dy: number): boolean {
  return Math.hypot(dx, dy) <= FOLDOUT.slop;
}

// Mirrors sheetSizeFor("9/16", fit) in px.
export function posterSize(vw: number, vh: number, fitPct: number): { w: number; h: number } {
  const h = Math.min(900, (vh * fitPct) / 100, (((vw * fitPct) / 100) * 16) / 9);
  return { w: (h * 9) / 16, h };
}

// Pixel dissolve: the poster's bounds cut into square cells, revealed in a
// shuffled order as t goes 0 → 1. A clip-path (CSSOM, so strict-CSP safe)
// on each panel, in the panel's own plane: the squares belong to the paper
// and fold with it, rather than a screen-fixed grid the paper slides under.
export function shuffledCells(n: number, rand: () => number = Math.random): number[] {
  const a = Array.from({ length: n }, (_, i) => i);
  for (let i = n - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}
export function dissolveClip(order: readonly number[], n: number, cols: number, x: number, y: number, cell: number): string {
  if (n >= order.length) return "none";
  if (n <= 0) return "inset(50%)";
  const c1 = cell + 1; // 1px overlap: no hairlines between neighbours
  let d = "";
  for (let i = 0; i < n; i++) {
    const c = order[i];
    d += `M${(x + (c % cols) * cell).toFixed(1)} ${(y + Math.floor(c / cols) * cell).toFixed(1)}h${c1}v${c1}h${-c1}Z`;
  }
  return `path("${d}")`;
}


// ─── DOM ────────────────────────────────────────────────────────────
// The delivery animation, ported from the approved dev/map-check.html
// (vertical layout only: the poster unfolds downward everywhere). Three
// panels, each hinged on the bottom edge of the one above; every face
// holds the whole poster offset so only its third shows.

export interface FoldOutOptions {
  root: HTMLElement;            // empty element appended to the shadow root; fold-out styles it
  framed: boolean;              // true → root absolute inset:0 in the host frame; false → fixed inset:0
  face: () => HTMLElement;      // a fresh poster stage (called 3×); fold-out sizes + offsets it
  fitPct: number;
  backBackground: string;       // background for panel backs (paper stock)
  closeLabel: string;
  rtl: boolean;                 // close pill on the right, dog-ear corner mirrored
  bottomClose: boolean;
  fade: boolean;                // entrance "fade": the folded poster fades in/out instead of the pixel dissolve
  cols: number;                 // pixel dissolve: squares across the poster (DISSOLVE_COLS)
  tempo: number;                // dealTempo(cfg): 1 = stock speed, >1 slower (dissolve, fall, fold-up)         // desktop: the reader's pill straddling the open poster's bottom edge; else the mobile top-left pill
  reducedMotion: boolean;
  onLift(t: number): void;      // 0 hidden → 1 dissolved in; banner fades the collapsed frame to 1 - t
  onTap(): void;                // deliberate tap on the poster (isTap) → CTA
  onOpened(topPanelFace: HTMLElement): void; // fully open; paired with onFoldStart
  onFoldStart(): void;          // left fully-open (drag or close)
  onCloseCommit(): void;        // a close committed (pill, backdrop, drag, close()); once, before onClosed
  onClosed(): void;             // after fly-back; root emptied
}
export interface FoldOutHandle { close(): void; destroy(): void; relayout(): void; }

const PANELS = FOLDOUT_MAX + 1;
const FADE_MS = 200; // reduced motion: plain fade in and out
const DISSOLVE_MS = 300; // pixel dissolve in (before the unfold) and out (after the fold-up)
const CREASE_CLEAR_MS = 1800; // once open, the creases slowly dissolve away: the poster lies flat
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

const FOLDOUT_CSS = `
.foldout { position: absolute; left: 0; top: 0; transform-origin: 0 0; }
/* Sibling panels, each carrying its whole hinge chain (see render): no
   preserve-3d, so they paint in DOM order — cover, then each flap over the
   one above it — instead of by the engine's depth sort, which WebKit (every
   iOS browser) gets wrong for panels folded flat against each other. */
.foldout-panel { position: absolute; left: 0; top: 0; transform-origin: 0 0; will-change: transform; }
.foldout-face, .foldout-back { position: absolute; inset: 0; overflow: hidden;
  box-shadow: 0 1px 2px rgba(0,0,0,0.30), 0 4px 12px rgba(0,0,0,0.22); }
.foldout-face { container-type: size; }
/* drawn in the panel's plane: pre-flipped, so it reads upright when the panel turns over */
.foldout-back { transform: scaleY(-1); }
/* crease shade on the hinge edge, darkens as the fold closes */
.foldout-crease { position: absolute; inset: 0; z-index: 6; pointer-events: none; opacity: 0;
  background: linear-gradient(180deg, #0006, transparent 35%); }
/* the crease stays in the paper once opened: a faint dark/lit line */
.foldout-face.creased::before { content: ""; position: absolute; inset: 0 0 auto 0; height: 3px;
  z-index: 6; pointer-events: none;
  background: linear-gradient(180deg, rgba(0,0,0,.22), rgba(255,255,255,.16) 1px, transparent);
  transition: opacity 120ms; }
/* fully open: the creases dissolve slowly (and snap back when it folds).
   Each face's shadow falls up across the hinge onto the panel above, a dark
   band once the crease line is gone; the one sheet's shadow takes over. */
.foldout, .foldout-face { transition: box-shadow 120ms; }
.foldout.flat { box-shadow: 0 1px 2px rgba(0,0,0,0.30), 0 4px 12px rgba(0,0,0,0.22); }
.foldout.flat .foldout-face { box-shadow: none; }
.foldout.flat, .foldout.flat .foldout-face { transition: box-shadow var(--crease-clear) ease-in-out; }
.foldout.flat .foldout-face.creased::before { opacity: 0;
  transition: opacity var(--crease-clear) ease-in-out; }
`;

let foldoutSheet: CSSStyleSheet | undefined;

export function openFoldOut(o: FoldOutOptions): FoldOutHandle {
  const { root } = o;
  Object.assign(root.style, {
    position: o.framed ? "absolute" : "fixed",
    inset: "0",
    zIndex: "9999",
    overflow: "hidden",
    touchAction: "none",
    userSelect: "none",
    webkitUserSelect: "none",
    backgroundColor: "rgba(11, 11, 16, 0)",
  } as Partial<CSSStyleDeclaration>);

  // Constructed stylesheet, adopted by the shadow root: unlike a <style>
  // element it is not blocked by a strict `style-src 'self'` CSP (the
  // hostile suite's foldout-csp-strict fixture). <style> stays as fallback.
  const scope = root.getRootNode() as ShadowRoot;
  try {
    foldoutSheet ??= new CSSStyleSheet();
    foldoutSheet.replaceSync(FOLDOUT_CSS);
    if (!scope.adoptedStyleSheets.includes(foldoutSheet)) scope.adoptedStyleSheets = [...scope.adoptedStyleSheets, foldoutSheet];
  } catch {
    const style = document.createElement("style");
    style.textContent = FOLDOUT_CSS;
    root.append(style);
  }
  const foldout = document.createElement("div");
  foldout.className = "foldout";
  foldout.style.setProperty("--crease-clear", `${CREASE_CLEAR_MS * o.tempo}ms`);
  const panels: HTMLElement[] = [];
  const faces: HTMLElement[] = [];
  const backs: HTMLElement[] = [];
  const stages: HTMLElement[] = [];
  const creases: HTMLElement[] = [];
  for (let i = 0; i < PANELS; i++) {
    const panel = document.createElement("div");
    panel.className = "foldout-panel";
    const face = document.createElement("div");
    face.className = i > 0 ? "foldout-face creased" : "foldout-face";
    const stage = o.face(); // carries its own grain (buildStage)
    face.append(stage);
    if (i > 0) {
      const crease = document.createElement("div");
      crease.className = "foldout-crease";
      face.append(crease);
      creases.push(crease);
    }
    const back = document.createElement("div");
    back.className = "foldout-back";
    back.style.background = o.backBackground;
    back.style.backgroundBlendMode = PAPER_BACK_BLEND;
    panel.append(face, back);
    foldout.append(panel);
    panels.push(panel);
    faces.push(face);
    backs.push(back);
    stages.push(stage);
  }

  // Close control, same as the Magazine reader: desktop = its pill
  // straddling the sheet's bottom edge (shown only while fully open);
  // mobile = the top-left pill on the scrim.
  const chrome = document.createElement("div");
  if (o.bottomClose) {
    Object.assign(chrome.style, {
      position: "absolute", zIndex: "100", pointerEvents: "none",
      opacity: "0", visibility: "hidden", transition: "opacity 0.3s ease, visibility 0.3s",
    } as Partial<CSSStyleDeclaration>);
    const pill = buildCloseButton({ ui: fontUI(), onClick: () => close(), label: o.closeLabel });
    pill.classList.add("foldout-close");
    pill.setAttribute("type", "button");
    pill.setAttribute("aria-label", o.closeLabel);
    chrome.append(pill);
  } else {
    const pill = document.createElement("button");
    pill.type = "button";
    pill.className = "foldout-close";
    pill.textContent = o.closeLabel;
    pill.setAttribute("aria-label", o.closeLabel);
    Object.assign(pill.style, {
      position: "absolute",
      top: o.framed ? "12px" : "max(12px, env(safe-area-inset-top, 0px))",
      [o.rtl ? "right" : "left"]: "12px",
      zIndex: "100",
      height: "40px",
      padding: "0 20px",
      borderRadius: "20px",
      border: "1px solid rgba(255,255,255,0.3)",
      background: "rgba(0,0,0,0.7)",
      color: "rgba(255,255,255,0.95)",
      fontSize: "13px",
      letterSpacing: "2px",
      lineHeight: "1",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      cursor: "pointer",
      fontFamily: fontUI(),
      boxShadow: "0 4px 14px rgba(0,0,0,0.4)",
      backdropFilter: "blur(6px)",
      webkitBackdropFilter: "blur(6px)",
    } as Partial<CSSStyleDeclaration>);
    pill.addEventListener("click", () => close());
    chrome.append(pill);
  }
  // Mobile pill: the Magazine reader's rhythm — hidden while a thumb is on
  // the poster, fading back in after it lets go (and a beat after opening).
  const mobilePill = o.bottomClose ? null : chrome.querySelector<HTMLElement>(".foldout-close");
  let pillTimer = 0;
  const setPill = (v: boolean) => {
    if (!mobilePill) return;
    window.clearTimeout(pillTimer);
    mobilePill.style.opacity = v ? "1" : "0";
    mobilePill.style.pointerEvents = v ? "" : "none";
  };
  const schedulePill = (ms: number) => {
    window.clearTimeout(pillTimer);
    pillTimer = window.setTimeout(() => setPill(true), ms);
  };
  if (mobilePill) mobilePill.style.transition = "opacity 0.3s ease";
  setPill(false);
  schedulePill(1200);
  const showChrome = (v: boolean) => {
    if (!o.bottomClose) return;
    chrome.style.opacity = v ? "1" : "0";
    chrome.style.visibility = v ? "visible" : "hidden"; // hidden = unclickable mid-fold
  };
  // Flat wrapper that carries the entrance fade (o.fade).
  const veil = document.createElement("div");
  Object.assign(veil.style, { position: "absolute", inset: "0", pointerEvents: "none" } as Partial<CSSStyleDeclaration>);
  foldout.style.pointerEvents = "auto";
  veil.append(foldout);
  root.append(veil, chrome);

  // Geometry in root-local px. jsdom (and a not-yet-laid-out root) report
  // a zero rect; fall back to the window so sizes stay finite.
  let g = { vw: 0, vh: 0, w: 0, h: 0, ph: 0, cx: 0, cy: 0 };
  const rootRect = () => root.getBoundingClientRect();
  let cell = 0;
  let order: number[] = [];
  let shown = -1; // cells currently revealed (skip re-clipping when unchanged)
  const layout = () => {
    const r = rootRect();
    const vw = r.width || window.innerWidth;
    const vh = r.height || window.innerHeight;
    // Panel edges on whole device pixels: a fractional seam is antialiased
    // in each 3D layer and stays visible as a hairline after the creases clear.
    const dpr = window.devicePixelRatio || 1;
    const snap = (v: number) => Math.round(v * dpr) / dpr;
    const { w, h: fit } = posterSize(vw, vh, o.fitPct);
    const ph = snap(fit / PANELS);
    const h = ph * PANELS;
    g = { vw, vh, w, h, ph, cx: snap(r.left + (vw - w) / 2) - r.left, cy: snap(r.top + (vh - h) / 2) - r.top };
    panels.forEach((p) => { p.style.width = `${w}px`; p.style.height = `${g.ph}px`; });
    Object.assign(foldout.style, { width: `${w}px`, height: `${h}px` }); // the flat sheet's shadow
    stages.forEach((st, i) => Object.assign(st.style, {
      inset: "auto", left: "0", top: `${-i * g.ph}px`, width: `${w}px`, height: `${h}px`,
    }));
    cell = w / o.cols;
    const cells = o.cols * Math.ceil(h / cell || 0);
    if (order.length !== cells) order = shuffledCells(cells);
    shown = -1;
    if (o.bottomClose) Object.assign(chrome.style, { left: `${g.cx}px`, top: `${g.cy}px`, width: `${w}px`, height: `${h}px` });
  };

  let s = { p: 0, v: 0 };
  let goal = FOLDOUT_MAX;
  let raf = 0;
  let timer = 0;
  let isOpen = false;
  let done = false;
  let committed = false; // a close is under way: nothing reopens it

  // Hinge angles (degrees from open) are the rendered truth: the spring and
  // the finger set them from progress; the gravity fall integrates them.
  const restOf = (i: number) => (i === 0 ? FOLDOUT.coverRest : FOLDOUT.innerRest);
  const angFromP = (p: number): number[] => foldAngles(p);
  let ang = angFromP(0);
  let angV = ang.map(() => 0);
  const pFromAng = () => { // inverse of angFromP on the summed angle (monotone in p)
    const want = ang.reduce((a, x) => a + Math.max(0, x), 0);
    let lo = 0, hi = FOLDOUT_MAX;
    for (let n = 0; n < 24; n++) {
      const mid = (lo + hi) / 2;
      if (angFromP(mid).reduce((a, x) => a + x, 0) > want) lo = mid; else hi = mid;
    }
    return (lo + hi) / 2;
  };
  // Which side of each panel shows, and whether it is tucked behind the
  // panel above, decided here: panels paint in DOM order, never depth-sorted
  // (WebKit sorted them wrong: cream backs over the poster, flaps over the
  // cover). A panel folded past 90° away from the viewer lies behind its
  // parent, so it and everything below it hide; any other flap lies in front.
  const sideVis: string[] = [];
  const setVis = (el: HTMLElement, i: number, show: boolean) => {
    const v = show ? "" : "hidden";
    if (sideVis[i] !== v) { sideVis[i] = v; el.style.visibility = v; }
  };
  setVis(backs[0], 0, false); // the cover never turns
  const render = () => {
    // perspective() leads every panel's chain, vanishing point on the cover's centre
    const ox = g.w / 2, oy = g.ph / 2;
    let chain = `translate(${ox}px, ${oy}px) perspective(${FOLDOUT.perspective}px) translate(${-ox}px, ${-oy}px)`;
    panels[0].style.transform = chain;
    let theta = 0; // a panel's angle to the viewer: the sum of its hinges
    let tucked = false;
    for (let k = 1; k < PANELS; k++) {
      const sign = (k - 1) % 2 === 0 ? -1 : 1;
      const a = ang[k - 1];
      chain += ` translateY(${g.ph}px) rotateX(${sign * a}deg)`; // hinged on the bottom edge of panel k-1
      panels[k].style.transform = chain;
      creases[k - 1].style.opacity = String(clamp(a / restOf(k - 1), 0, 1) * 0.9);
      const parentFacing = Math.cos((theta * Math.PI) / 180) > 0;
      tucked ||= a > 90 && (sign < 0) === parentFacing;
      theta += sign * a;
      const front = Math.cos((theta * Math.PI) / 180) > 0;
      setVis(faces[k], 2 * k, !tucked && front);
      setVis(backs[k], 2 * k + 1, !tucked && !front);
    }
    // The poster sits centred; it pixel-dissolves in folded, then unfolds,
    // and dissolves out as it folds shut (trackVis).
    const t = vis;
    foldout.style.transform = `translate(${g.cx}px, ${g.cy}px)`;
    // opacity on the veil: on a panel each would fade separately, showing the ones behind
    if (o.fade) veil.style.opacity = String(t);
    else {
      const n = Math.round(t * order.length);
      if (n !== shown) {
        shown = n;
        // one grid over the whole poster; each panel takes its own rows
        panels.forEach((pn, i) => { pn.style.clipPath = dissolveClip(order, n, o.cols, 0, -i * g.ph, cell); });
      }
    }
    // backdrop fades via its background alpha, never opacity (that would fade the foldout too)
    root.style.backgroundColor = `rgba(11, 11, 16, ${0.75 * t})`;
    o.onLift(t);
  };

  const leaveOpen = () => {
    if (!isOpen) return;
    isOpen = false;
    foldout.classList.remove("flat");
    showChrome(false);
    o.onFoldStart();
  };
  // Folding shut dissolves along with the fold: from where the fold began
  // (progress and dissolve at that moment) down to nothing at fully folded,
  // so a finger or the close button carries the dissolve with it.
  let fadeBase: { p: number; vis: number } | null = null;
  const trackVis = (p: number) => {
    // still folded (closed mid dissolve-in): the timed dissolve reverses instead
    if (!fadeBase || fadeBase.p < 0.05 || o.reducedMotion) return;
    vis = clamp((fadeBase.vis * p) / fadeBase.p, 0, 1);
  };
  const arrive = () => {
    if (goal === FOLDOUT_MAX) {
      if (!isOpen) { isOpen = true; foldout.classList.add("flat"); showChrome(true); o.onOpened(faces[0]); }
    } else dissolveTo(0, finish);
  };

  // Timed pixel dissolve of the folded poster (vis 0 → 1 or back), own rAF
  // so a grab (which cancels the spring) never strands it half-shown.
  let vis = 0;
  let draf = 0;
  const dissolveTo = (target: number, then: () => void) => {
    cancelAnimationFrame(draf);
    let last = performance.now();
    const step = (now: number) => {
      const d = Math.max(0, now - last) / (DISSOLVE_MS * o.tempo);
      last = now;
      vis = target > vis ? Math.min(target, vis + d) : Math.max(target, vis - d);
      render();
      if (vis === target) { then(); return; }
      draf = requestAnimationFrame(step);
    };
    draf = requestAnimationFrame(step);
  };

  // Critically damped spring on progress, seeded with the current velocity:
  // a flick carries its speed into the settle, a grab mid-flight picks up
  // exactly where the foldout is.
  const springTo = (target: number) => {
    cancelAnimationFrame(raf);
    goal = target;
    if (o.reducedMotion) {
      if (target === 0) { fadeOut(); return; }
      s = { p: target, v: 0 };
      ang = angFromP(target);
      render();
      arrive();
      return;
    }
    if (target === FOLDOUT_MAX) { fallOpen(); return; }
    s = { p: pFromAng(), v: s.v }; // may be mid-fall: continue from where the paper is
    let last = performance.now();
    const step = (now: number) => {
      s = springStep(s, target, Math.max(0, now - last) / 1000, o.tempo);
      ang = angFromP(s.p);
      trackVis(s.p);
      last = now;
      // Closing: dissolve as soon as the panels read as shut, not after the
      // spring's long settle tail (a visible pause before the dissolve).
      if (springSettled(s, target) || (target === 0 && s.p < 0.02)) {
        s = { p: target, v: 0 };
        ang = angFromP(target);
        render();
        arrive();
        return;
      }
      render();
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
  };

  // Gravity fall to open. Hinge i+1 hangs folded until hinge i has swung
  // past FALL.releaseAt; a hinge let go from rest gets a small nudge. A
  // release after a drag carries the finger's speed into the swing.
  const fallOpen = () => {
    // The dissolve rides the unfold, the close played backwards: from where
    // the paper is now (folded on entry, part-way after a let-go) to whole at open.
    fadeBase = null;
    const rise = { p: pFromAng(), vis };
    if (s.v) {
      const a1 = angFromP(Math.min(FOLDOUT_MAX, s.p + 0.01));
      angV = ang.map((a, i) => ((a1[i] - a) / 0.01) * s.v);
    }
    let last = performance.now();
    const step = (now: number) => {
      let dt = Math.min(0.05, Math.max(0, (now - last) / 1000));
      last = now;
      while (dt > 1e-6) {
        const h = Math.min(dt, FALL.hMax);
        dt -= h;
        let screen = 0; // the hinge's panel angle to the screen, signed as its hinge opens
        for (let i = 0; i < ang.length; i++) {
          const sign = i % 2 === 0 ? -1 : 1; // as in render
          screen += sign * ang[i];
          const atRest = ang[i] >= restOf(i) - 0.5;
          if (i > 0 && atRest && ang[i - 1] > FALL.releaseAt) continue; // still folded under
          if (atRest && angV[i] >= 0) angV[i] = Math.min(-FALL.nudge / o.tempo, i > 0 ? FALL.inherit * angV[i - 1] : 0);
          [ang[i], angV[i]] = fallStep(ang[i], angV[i], h, o.tempo, sign * screen);
          ang[i] = Math.min(ang[i], restOf(i));
        }
      }
      if (ang.every((a, i) => Math.abs(a) < 0.3 && Math.abs(angV[i]) < 3)) {
        ang = ang.map(() => 0);
        angV = ang.map(() => 0);
        s = { p: FOLDOUT_MAX, v: 0 };
        vis = 1;
        render();
        arrive();
        return;
      }
      if (rise.p < FOLDOUT_MAX) {
        vis = Math.max(vis, clamp(rise.vis + ((1 - rise.vis) * (pFromAng() - rise.p)) / (FOLDOUT_MAX - rise.p), 0, 1));
      }
      render();
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
  };

  const fadeOut = () => {
    root.style.transition = `opacity ${FADE_MS}ms ease`;
    root.style.opacity = "0";
    window.clearTimeout(timer);
    timer = window.setTimeout(() => { s = { p: 0, v: 0 }; ang = angFromP(0); render(); finish(); }, FADE_MS);
  };

  const close = () => {
    if (done || committed) return;
    committed = true;
    leaveOpen();
    o.onCloseCommit();
    setPill(false);
    fadeBase ??= { p: pFromAng(), vis };
    springTo(0);
  };

  // ── pointer: drag folds/unfolds, a tap is a CTA (poster) or a close (backdrop)
  let drag: { x0: number; y0: number; a: number; p: number; t: number; last: number; onFoldout: boolean; moved: boolean } | null = null;
  const onDown = (e: PointerEvent) => {
    const tgt = e.target as Element | null;
    // the close pill and the dog-ear keep their own clicks (capture would steal them)
    if (committed || tgt?.closest?.(".dogear-corner, a, button")) return;
    // primary button/pointer only (isPrimary is undefined on synthetic events)
    if ((e.button ?? 0) !== 0 || e.isPrimary === false) return;
    cancelAnimationFrame(raf);
    cancelAnimationFrame(draf); // the finger owns the dissolve now (a release springs to goal)
    window.clearTimeout(timer);
    s = { p: pFromAng(), v: 0 }; // a grab mid-fall picks up where the paper is
    angV = angV.map(() => 0);
    fadeBase = { p: s.p, vis };
    setPill(false);
    drag = { x0: e.clientX, y0: e.clientY, a: e.clientY, p: s.p, t: e.timeStamp, last: s.p, onFoldout: foldout.contains(tgt), moved: false };
    try { root.setPointerCapture?.(e.pointerId); } catch { /* synthetic pointer */ }
  };
  const onMove = (e: PointerEvent) => {
    if (!drag || committed) return; // Escape mid-drag: the close owns the foldout
    if (!drag.moved) {
      if (isTap(e.clientX - drag.x0, e.clientY - drag.y0)) return;
      drag.moved = true;
      drag.a = e.clientY; // rebase at the slop edge: no jump when the drag engages
      return;
    }
    // pulling down opens, pushing up folds
    const p = Math.min(FOLDOUT_MAX, Math.max(0, drag.p + (e.clientY - drag.a) / g.ph));
    if (p < FOLDOUT_MAX) leaveOpen();
    // finger velocity in progress/s, smoothed so one noisy sample can't fling it
    const dt = (e.timeStamp - drag.t) / 1000;
    const v = dt > 0 ? lerp(s.v, (p - drag.last) / dt, 0.4) : s.v;
    s = { p, v };
    ang = angFromP(p);
    trackVis(p);
    drag.t = e.timeStamp;
    drag.last = p;
    render();
  };
  const onUp = (e: PointerEvent) => {
    if (!drag) return;
    const d = drag;
    drag = null;
    if (committed) return;
    schedulePill(900);
    if (!d.moved && isTap(e.clientX - d.x0, e.clientY - d.y0)) {
      s = { p: s.p, v: 0 };
      // the CTA is a tap on the OPEN poster, never on one still unfolding
      if (d.onFoldout) { if (isOpen) o.onTap(); springTo(goal); } else close();
      return;
    }
    if (releaseTarget(s.p, s.v, e.timeStamp - d.t) === "closed") close();
    else springTo(FOLDOUT_MAX);
  };
  const onCancel = () => {
    if (!drag) return;
    drag = null;
    if (!committed) { schedulePill(900); springTo(goal); }
  };
  root.addEventListener("pointerdown", onDown);
  root.addEventListener("pointermove", onMove);
  root.addEventListener("pointerup", onUp);
  root.addEventListener("pointercancel", onCancel);

  const relayout = () => { layout(); render(); };
  window.addEventListener("resize", relayout);

  const destroy = () => {
    done = true;
    cancelAnimationFrame(raf);
    cancelAnimationFrame(draf);
    window.clearTimeout(timer);
    window.clearTimeout(pillTimer);
    window.removeEventListener("resize", relayout);
    root.removeEventListener("pointerdown", onDown);
    root.removeEventListener("pointermove", onMove);
    root.removeEventListener("pointerup", onUp);
    root.removeEventListener("pointercancel", onCancel);
    root.replaceChildren();
  };
  function finish(): void {
    if (done) return;
    destroy();
    o.onClosed();
  }

  layout();
  if (o.reducedMotion) {
    vis = 1;
    s = { p: FOLDOUT_MAX, v: 0 };
    ang = angFromP(FOLDOUT_MAX);
    render();
    root.style.opacity = "0";
    void root.offsetWidth; // commit opacity 0 so the transition runs
    root.style.transition = `opacity ${FADE_MS}ms ease`;
    root.style.opacity = "1";
    timer = window.setTimeout(arrive, FADE_MS);
  } else {
    render();
    springTo(goal); // falls open, dissolving in as it unfolds
  }

  return { close, destroy, relayout };
}
