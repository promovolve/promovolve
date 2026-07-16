// Building blocks for the expanded-overlay DOM. Each function
// returns one focused element; renderOverlay() in banner.ts wires
// them together. Pure: no `this`, no shadow-root references — the
// caller appends the result wherever it makes sense.
//
// Why split: the original renderOverlay was a 270-line procedural
// blob mixing styling, animation hookup, and child wiring. Pulling
// the DOM-construction primitives out lets the coordinator stay at
// the orchestration level instead of getting buried in inline
// Object.assign blocks.

import type { BannerConfig, ExpandAnimation } from "./types";
import { PAPER_FEEL } from "./types";
import { EXPAND_EFFECT_CSS } from "./expand-effects";
import { PAPER_CSS } from "./paper";

// Outer dark backdrop. Click-outside / Escape doesn't dismiss — the
// caller wires the explicit close button + key handler.
//
// The 400ms opacity transition is layered with the per-effect
// animation on the wrapper inside; effects compose visually because
// the wrapper either drives its own opacity (CRT) or the overlay's
// fade is the effect (fade) — see EXPAND_EFFECT_CSS for details.
export function buildOverlay(opts: {
  font: string;
  framed: boolean;
  /** Optional background — defaults to #1a1a1a. Pass the cover page's
    * `bg` so the expanded reader matches the creative's authored color
    * instead of always sitting on a dark default. */
  background?: string;
}): HTMLElement {
  const { font, framed, background } = opts;
  const overlay = document.createElement("div");
  overlay.className = "overlay";
  Object.assign(overlay.style, {
    position: framed ? "absolute" : "fixed",
    inset: "0",
    zIndex: "9999",
    background: background ?? "#1a1a1a",
    opacity: "0",
    transition: "opacity 0.4s ease",
    fontFamily: font,
    // One reader everywhere: the floating sheet with page turns. The
    // mobile scroll-stack (overflow:auto + sticky sheets) is gone —
    // swipe drives next/prev instead.
    overflowY: "hidden",
    // The reader is a modal takeover: no gesture on it may scroll the
    // publisher page behind it. The old scroll-stack was itself the
    // scroller so it consumed touch gestures; this overlay is not, and
    // without touch-action:none the browser forwards touchmove to the
    // page — "swiping the ad scrolls the article behind it". The
    // touchmove preventDefault in renderOverlay covers engines that
    // ignore touch-action.
    touchAction: "none",
    // Size container so the sheet + chrome can aspect-fit against the
    // reader's actual box with cq units (delivery viewport and framed
    // preview bezels alike) — see sheetSizeFor in utils.
    containerType: "size",
  } as Partial<CSSStyleDeclaration>);

  // Effect-specific keyframes for the expand transition. The wrapper
  // inside carries `expand-effect-NAME`; CSS picks the matching
  // animation. New effects are CSS-only — drop in keyframes + a
  // selector and add the name to EXPAND_EFFECTS in types.ts.
  const effectStyle = document.createElement("style");
  effectStyle.textContent = EXPAND_EFFECT_CSS;
  overlay.appendChild(effectStyle);

  // Paper identity: sheet shadow/leaves, grain, page-turn keyframes.
  const paperStyle = document.createElement("style");
  paperStyle.textContent = PAPER_CSS;
  overlay.appendChild(paperStyle);

  return overlay;
}

// The element that all expanded content (close button, page counter,
// pages, nav arrows) gets appended to. Carrying `expand-effect-NAME`
// lets the chosen CSS animation transform/filter every child at once.
//
// Sets the per-effect duration override (via --expand-duration custom
// property) when the user picked one in the designer. CRT
// pseudoelements pick up the same value via the var() fallbacks in
// EXPAND_EFFECT_CSS, so all linked animations stay in sync.
//
// Drops `will-change` on animationend — leaving it set permanently is
// worse than not having it. Pseudoelement animations also fire
// animationend on the wrapper; the prefix filter keeps the cleanup
// from running too early.
export function buildExpandWrapper(opts: {
  cfg: BannerConfig;
  reducedMotion: boolean;
}): HTMLElement {
  const { cfg, reducedMotion } = opts;
  const wrapper = document.createElement("div");
  wrapper.className = "expand-wrapper";
  const effectName: ExpandAnimation = cfg.expandAnimation ?? "fade";
  // prefers-reduced-motion overrides the chosen effect with a flat
  // fade so visually-sensitive users don't get the CRT flash.
  const appliedEffect = reducedMotion ? "fade" : effectName;
  wrapper.classList.add(`expand-effect-${appliedEffect}`);
  wrapper.style.cssText = [
    "position: absolute",
    "inset: 0",
    "transform-origin: center center",
    "will-change: transform, filter, opacity",
  ].join(";");
  if (typeof cfg.expandDurationMs === "number" && cfg.expandDurationMs > 0) {
    wrapper.style.setProperty("--expand-duration", `${cfg.expandDurationMs}ms`);
  }
  // Paper mass drives the deal tempo: heavy stock enters (and leaves)
  // the pile slower; light snaps. Same multiplier the close flight uses.
  const tempo = PAPER_FEEL[cfg.paperWeight ?? "medium"].tempo;
  // Last sheet lands at 360·tempo + 2·90·tempo; the wrapper animation
  // (which owns the completion event) must outlast it: 540·tempo + 80.
  const stackTotalMs = Math.round(540 * tempo) + 80;
  wrapper.style.setProperty("--deal-ms", `${Math.round(360 * tempo)}ms`);
  wrapper.style.setProperty("--deal-stagger", `${Math.round(90 * tempo)}ms`);
  if (appliedEffect === "stack" && !(typeof cfg.expandDurationMs === "number" && cfg.expandDurationMs > 0)) {
    wrapper.style.setProperty("--expand-duration", `${stackTotalMs}ms`);
  }
  let settled = false;
  const settle = (): void => {
    if (settled) return;
    settled = true;
    wrapper.style.willChange = "";
    wrapper.removeEventListener("animationend", onEffectEnd);
  };
  const onEffectEnd = (e: AnimationEvent): void => {
    if (!e.animationName.startsWith("expand-effect-")) return;
    settle();
  };
  wrapper.addEventListener("animationend", onEffectEnd);
  // Safety net — mirrors the close path (banner.ts _collapse). Mobile
  // Safari sometimes never fires animationend for the open effect; with
  // animation-fill-mode:both the wrapper then sticks on the `from`
  // keyframe instead of resting at the end state. For CRT that `from`
  // is scaleY(0.005) — the wrapper collapses to a line ("hang") and the
  // stuck transform + will-change layer kills scrolling in the
  // overflow:auto overlay. If animationend hasn't fired by the time the
  // effect should be done, force the resting visual state so neither a
  // half-applied transform nor a lingering composite layer can break
  // scroll. No-ops when animationend already settled it.
  const cssDefaultMs =
    appliedEffect === "crt-power-on" ? 650
    : appliedEffect === "stack" ? stackTotalMs
    : 400;
  const durMs =
    typeof cfg.expandDurationMs === "number" && cfg.expandDurationMs > 0
      ? cfg.expandDurationMs
      : cssDefaultMs;
  setTimeout(() => {
    if (settled) return;
    wrapper.style.animation = "none";
    wrapper.style.transform = "none";
    wrapper.style.filter = "none";
    wrapper.style.opacity = "1";
    settle();
  }, durMs + 150);
  return wrapper;
}

// Bottom-center close pill. Caller decides whether to omit (preview-
// frame mode shows the designer's own X instead).
export function buildCloseButton(opts: {
  ui: string;
  onClick: () => void;
  // Localized button text (e.g. "Close" / "閉じる"). Defaults to "Close".
  label?: string;
  // True when the reader background is light → switch to dark ink so the
  // button doesn't vanish into a light creative. Default = light-on-dark.
  onLight?: boolean;
}): HTMLElement {
  const { ui, onClick, label, onLight } = opts;
  const ink = onLight ? "0,0,0" : "255,255,255";
  const close = document.createElement("button");
  close.className = "close-pill";
  Object.assign(close.style, {
    position: "absolute",
    // Straddle the creative's bottom edge — ~1/3 of the 36px pill on the
    // sheet, ~2/3 below it (anchored to the chrome box). -24px ≈ -2/3 × 36.
    bottom: "-24px",
    left: "50%",
    transform: "translateX(-50%)",
    zIndex: "100",
    // Clickable even when parented to a pointer-events:none chrome box.
    pointerEvents: "auto",
    // On dark bg keep the pill translucent (frosted glass): a light fill + a
    // backdrop blur so it stays findable over ANY creative — solid colors AND
    // busy images — without becoming a heavy opaque chip. The ring + ink carry
    // the legibility. Light bg keeps the minimal background-free treatment.
    background: onLight ? "none" : `rgba(${ink},0.08)`,
    backdropFilter: onLight ? "" : "blur(6px)",
    WebkitBackdropFilter: onLight ? "" : "blur(6px)",
    border: `1px solid rgba(${ink},${onLight ? "0.3" : "0.45"})`,
    color: `rgba(${ink},${onLight ? "0.7" : "0.95"})`,
    height: "36px",
    padding: "0 14px",
    borderRadius: "18px",
    cursor: "pointer",
    fontSize: "12px",
    letterSpacing: "1px",
    textTransform: "uppercase",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    fontFamily: ui,
  });
  close.textContent = label ?? "Close";
  close.addEventListener("click", onClick);
  return close;
}

// (The bottom-center "閉じる" pill for mobile was removed — it sat
// exactly where creatives put their CTA. Mobile close is now a small
// fixed ✕ at the viewport's top-left + tap-the-scrim-to-dismiss,
// both built in banner.ts's mobile branch.)

// Tiny "01 / 03"-style indicator pinned top-left. The actual text
// content is updated by updatePages() (in banner.ts) so it doesn't
// need to know the current page at construction time.
export function buildPageCounter(opts: { ui: string; onLight?: boolean; rtl?: boolean }): HTMLElement {
  const counter = document.createElement("div");
  counter.className = "page-counter";
  const ink = opts.onLight ? "0,0,0" : "255,255,255";
  Object.assign(counter.style, {
    position: "absolute",
    top: "22px",
    // Opposite the dog-ear: flap is top-right (LTR) / top-left (RTL), so
    // the counter takes the other top corner.
    [opts.rtl ? "right" : "left"]: "24px",
    zIndex: "100",
    fontSize: "10px",
    letterSpacing: "3px",
    color: `rgba(${ink},${opts.onLight ? "0.45" : "0.55"})`,
    fontFamily: opts.ui,
  });
  return counter;
}

// Prev / next nav arrow. Single shared style — the only difference
// is the side and glyph.
export function buildNavButton(opts: {
  direction: "prev" | "next";
  onClick: () => void;
  // True when the reader background is light → dark ink so the arrow
  // doesn't disappear into a light creative. Default = light-on-dark.
  onLight?: boolean;
  // RTL reading (Arabic / vertical): mirror each arrow's side + glyph so
  // "next" sits on the left. The class stays semantic (nav-prev/nav-next)
  // so enable/disable lookups are unchanged.
  rtl?: boolean;
}): HTMLElement {
  const { direction, onClick, onLight, rtl = false } = opts;
  const ink = onLight ? "0,0,0" : "255,255,255";
  const btn = document.createElement("button");
  btn.className = direction === "prev" ? "nav-prev" : "nav-next";
  // LTR: prev=left, next=right. RTL flips both.
  const onLeft = (direction === "prev") !== rtl;
  const sideKey = onLeft ? "left" : "right";
  Object.assign(btn.style, {
    position: "absolute",
    top: "50%",
    // Straddle the creative's edge — ~1/3 of the 40px button overlaps the
    // sheet, ~2/3 hangs into the margin. (Anchored to the chrome box,
    // which overlays the sheet; -27px ≈ -2/3 × 40.)
    [sideKey]: "-27px",
    transform: "translateY(-50%)",
    zIndex: "100",
    // Clickable even when parented to a pointer-events:none chrome box.
    pointerEvents: "auto",
    // Dark bg (white ink) needs higher alpha than light bg (black ink) to
    // read — black-on-light carries further at low opacity than white-on-dark.
    background: `rgba(${ink},${onLight ? "0.06" : "0.14"})`,
    border: `1px solid rgba(${ink},${onLight ? "0.18" : "0.32"})`,
    color: `rgba(${ink},${onLight ? "0.55" : "0.85"})`,
    width: "40px",
    height: "40px",
    borderRadius: "50%",
    cursor: "pointer",
    fontSize: "18px",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
  });
  btn.textContent = onLeft ? "‹" : "›";
  btn.addEventListener("click", onClick);
  return btn;
}

// (Bottom dots indicator + swipe hint removed — the top-left page
// counter carries position; the bottom row keeps only the close pill.)
