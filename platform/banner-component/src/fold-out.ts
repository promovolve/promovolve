// Fold-out (gatefold poster) format: the fold maths (pure), then the DOM.
import { PAPER_BACK_BLEND } from "./paper";
import { fontUI } from "./fonts";
import { buildCloseButton } from "./render-overlay";
export const FOLDOUT_MAX = 2; // 3 panels -> progress 0..2
export const FOLDOUT = {
  overlap: 0.45, omega: 8, coverRest: 178, innerRest: 180,
  flickS: 0.15, restMs: 80, dtMax: 0.032, perspective: 1400, slop: 12,
} as const;

const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x));
const smooth = (t: number) => t * t * (3 - 2 * t);

export function isFoldout(cfg: { format?: unknown }): boolean {
  return cfg.format === "foldout";
}

export function hingeOpen(progress: number, i: number, max = FOLDOUT_MAX, overlap = FOLDOUT.overlap): number {
  const L = Math.min(max, 1 + overlap);
  const gap = max > 1 ? (max - L) / (max - 1) : 0;
  return smooth(clamp((progress - i * gap) / L, 0, 1));
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
export function fallStep(theta: number, omega: number, h: number, tempo = 1): [number, number] {
  const grav = FALL.grav / (tempo * tempo);
  const damp = 2 * FALL.zeta * Math.sqrt(grav);
  const w = omega * DEG + (-grav * Math.sin(theta * DEG) - damp * omega * DEG) * h;
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
// on a flat wrapper: on the foldout itself it would flatten preserve-3d.
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
// nested panels, each hinged on its parent's bottom edge; every face
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
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

const FOLDOUT_CSS = `
.foldout { position: absolute; left: 0; top: 0; transform-style: preserve-3d; transform-origin: 0 0; }
.foldout-panel { position: absolute; left: 0; top: 0; transform-style: preserve-3d;
  transform-origin: center top; will-change: transform; }
.foldout-panel .foldout-panel { top: 100%; }
/* box-shadow, not a drop-shadow filter: a filter would flatten preserve-3d */
.foldout-face, .foldout-back { position: absolute; inset: 0; overflow: hidden;
  backface-visibility: hidden; -webkit-backface-visibility: hidden;
  box-shadow: 0 1px 2px rgba(0,0,0,0.30), 0 4px 12px rgba(0,0,0,0.22); }
.foldout-face { container-type: size; }
.foldout-back { transform: rotateX(180deg); }
/* crease shade on the hinge edge, darkens as the fold closes */
.foldout-crease { position: absolute; inset: 0; z-index: 6; pointer-events: none; opacity: 0;
  background: linear-gradient(180deg, #0006, transparent 35%); }
/* the crease stays in the paper once opened: a faint dark/lit line */
.foldout-face.creased::before { content: ""; position: absolute; inset: 0 0 auto 0; height: 3px;
  z-index: 6; pointer-events: none;
  background: linear-gradient(180deg, rgba(0,0,0,.22), rgba(255,255,255,.16) 1px, transparent); }
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
  const panels: HTMLElement[] = [];
  const faces: HTMLElement[] = [];
  const stages: HTMLElement[] = [];
  const creases: HTMLElement[] = [];
  let parent: HTMLElement = foldout;
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
    parent.append(panel);
    parent = panel;
    panels.push(panel);
    faces.push(face);
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
  const showChrome = (v: boolean) => {
    if (!o.bottomClose) return;
    chrome.style.opacity = v ? "1" : "0";
    chrome.style.visibility = v ? "visible" : "hidden"; // hidden = unclickable mid-fold
  };
  // Flat wrapper that carries the pixel-dissolve clip (see dissolveClip).
  const veil = document.createElement("div");
  Object.assign(veil.style, { position: "absolute", inset: "0", pointerEvents: "none" } as Partial<CSSStyleDeclaration>);
  foldout.style.pointerEvents = "auto";
  veil.append(foldout);
  root.append(veil, chrome);

  // Geometry in root-local px. jsdom (and a not-yet-laid-out root) report
  // a zero rect; fall back to the window so sizes stay finite.
  let g = { vw: 0, vh: 0, w: 0, h: 0, ph: 0, cx: 0, cy: 0 };
  const rootRect = () => root.getBoundingClientRect();
  const COLS = 12;
  let cell = 0;
  let order: number[] = [];
  let shown = -1; // cells currently revealed (skip re-clipping when unchanged)
  const layout = () => {
    const r = rootRect();
    const vw = r.width || window.innerWidth;
    const vh = r.height || window.innerHeight;
    const { w, h } = posterSize(vw, vh, o.fitPct);
    g = { vw, vh, w, h, ph: h / PANELS, cx: (vw - w) / 2, cy: (vh - h) / 2 };
    panels.forEach((p) => { p.style.width = `${w}px`; p.style.height = `${g.ph}px`; });
    stages.forEach((st, i) => Object.assign(st.style, {
      inset: "auto", left: "0", top: `${-i * g.ph}px`, width: `${w}px`, height: `${h}px`,
    }));
    cell = w / COLS;
    const cells = COLS * Math.ceil(h / cell || 0);
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
  const angFromP = (p: number) => Array.from({ length: PANELS - 1 }, (_, i) => restOf(i) * (1 - hingeOpen(p, i)));
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
  const render = () => {
    for (let k = 1; k < PANELS; k++) {
      const sign = (k - 1) % 2 === 0 ? -1 : 1;
      panels[k].style.transform = `rotateX(${sign * ang[k - 1]}deg)`;
      creases[k - 1].style.opacity = String(clamp(ang[k - 1] / restOf(k - 1), 0, 1) * 0.9);
    }
    // The poster sits centred; it pixel-dissolves in folded, then unfolds,
    // and folds up before it dissolves out. perspective() rides the foldout
    // with its vanishing point on the cover's centre.
    const t = vis;
    const ox = g.w / 2, oy = g.ph / 2;
    foldout.style.transform =
      `translate(${g.cx + ox}px, ${g.cy + oy}px) ` +
      `perspective(${FOLDOUT.perspective}px) translate(${-ox}px, ${-oy}px)`;
    // opacity on the flat veil, like the clip: on the foldout it would flatten preserve-3d
    if (o.fade) veil.style.opacity = String(t);
    else {
      const n = Math.round(t * order.length);
      if (n !== shown) {
        shown = n;
        veil.style.clipPath = dissolveClip(order, n, COLS, g.cx, g.cy, cell);
      }
    }
    // backdrop fades via its background alpha, never opacity (that would fade the foldout too)
    root.style.backgroundColor = `rgba(11, 11, 16, ${0.75 * t})`;
    o.onLift(t);
  };

  const leaveOpen = () => {
    if (!isOpen) return;
    isOpen = false;
    showChrome(false);
    o.onFoldStart();
  };
  const arrive = () => {
    if (goal === FOLDOUT_MAX) {
      if (!isOpen) { isOpen = true; showChrome(true); o.onOpened(faces[0]); }
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
        for (let i = 0; i < ang.length; i++) {
          const atRest = ang[i] >= restOf(i) - 0.5;
          if (i > 0 && atRest && ang[i - 1] > FALL.releaseAt) continue; // still folded under
          if (atRest && angV[i] >= 0) angV[i] = Math.min(-FALL.nudge / o.tempo, i > 0 ? FALL.inherit * angV[i - 1] : 0);
          [ang[i], angV[i]] = fallStep(ang[i], angV[i], h, o.tempo);
          ang[i] = Math.min(ang[i], restOf(i));
        }
      }
      if (ang.every((a, i) => Math.abs(a) < 0.3 && Math.abs(angV[i]) < 3)) {
        ang = ang.map(() => 0);
        angV = ang.map(() => 0);
        s = { p: FOLDOUT_MAX, v: 0 };
        render();
        arrive();
        return;
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
    window.clearTimeout(timer);
    s = { p: pFromAng(), v: 0 }; // a grab mid-fall picks up where the paper is
    angV = angV.map(() => 0);
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
    drag.t = e.timeStamp;
    drag.last = p;
    render();
  };
  const onUp = (e: PointerEvent) => {
    if (!drag) return;
    const d = drag;
    drag = null;
    if (committed) return;
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
    if (!committed) springTo(goal);
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
    // Dissolve in folded, then unfold — unless a finger already holds it
    // (its release springs to goal). A close cancels this via dissolveTo(0).
    dissolveTo(1, () => { if (!drag) springTo(goal); });
  }

  return { close, destroy, relayout };
}
