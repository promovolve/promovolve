// Per-item motion helpers used by the layout-item animation system.
// Items declare an `animationTo` MotionTarget (and optionally
// `animationFrom`); these helpers translate that into CSS at the
// right moment in the lifecycle:
//
//   - transitionFor: build the CSS `transition` string for whichever
//     fields the target moves.
//   - applyTargetState: write the target's end values to the element
//     so the transition has somewhere to ride to.
//   - autoFitText: shrink font-size to fit a clip box. Lives here
//     because it's the only way text items reconcile content size
//     with the canvas-percent layout system, and it's invoked from
//     the same render code path that applies motion.

import type { MotionFrom, MotionTarget } from "./types";

export const DEFAULT_EASING = "cubic-bezier(0.16,1,0.3,1)";

// Opacity gets its own default curve. DEFAULT_EASING is an aggressive
// ease-out — right for motion settling into place, but a fade under it
// is ~90% done at a third of its duration, so "Duration 5s" LOOKS like
// a 1.5s fade. easeInOutSine spends the whole configured window
// perceptibly fading. An author's explicit easing overrides both.
export const FADE_EASING = "cubic-bezier(0.37,0,0.63,1)";

/** Reduced-motion probe. Entrances are skipped entirely (the resting
  * layout IS the meaningful pose); animationTo end-poses are applied
  * instantly instead of tweened. */
export function prefersReducedMotion(): boolean {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
}

/** The authored resting pose an entrance tweens home to. */
export interface EntranceBase {
  left: number;
  top: number;
  rotation: number;
  opacity: number;
}

/** Snap an element to its entrance start pose — synchronously, with
  * transitions off, so the very first paint is the off-state (never a
  * flash of the resting pose before the tween). */
export function applyEntranceStart(el: HTMLElement, from: MotionFrom, base: EntranceBase): void {
  el.style.transition = "none";
  if (from.dx !== undefined) el.style.left = `${base.left + from.dx}%`;
  if (from.dy !== undefined) el.style.top = `${base.top + from.dy}%`;
  if (from.rotate !== undefined) el.style.rotate = `${base.rotation + from.rotate}deg`;
  if (from.scale !== undefined) el.style.scale = String(from.scale);
  if (from.opacity !== undefined) el.style.opacity = String(from.opacity);
}

/** Tween the element home from its entrance pose: transition only the
  * fields the entrance moved, then write the resting values. Call after
  * applyEntranceStart has painted (double-rAF at the call site). */
export function playEntranceHome(el: HTMLElement, from: MotionFrom, base: EntranceBase): void {
  const duration = from.duration ?? 0.6;
  const timing = `${duration}s ${from.easing ?? DEFAULT_EASING} 0s`;
  const fadeTiming = `${duration}s ${from.easing ?? FADE_EASING} 0s`;
  const parts: string[] = [];
  if (from.dx !== undefined) parts.push(`left ${timing}`);
  if (from.dy !== undefined) parts.push(`top ${timing}`);
  if (from.rotate !== undefined) parts.push(`rotate ${timing}`);
  if (from.scale !== undefined) parts.push(`scale ${timing}`);
  if (from.opacity !== undefined) parts.push(`opacity ${fadeTiming}`);
  el.style.transition = parts.join(",");
  if (from.dx !== undefined) el.style.left = `${base.left}%`;
  if (from.dy !== undefined) el.style.top = `${base.top}%`;
  if (from.rotate !== undefined) el.style.rotate = base.rotation ? `${base.rotation}deg` : "0deg";
  if (from.scale !== undefined) el.style.scale = "1";
  if (from.opacity !== undefined) el.style.opacity = String(base.opacity);
}

// Build a CSS transition string for whichever fields of the
// MotionTarget are present. Drives the base → animationTo tween.
export function transitionFor(target: MotionTarget, fallbackDuration: number): string {
  const duration = target.duration ?? fallbackDuration;
  const delay = target.delay ?? 0;
  const timing = `${duration}s ${target.easing ?? DEFAULT_EASING} ${delay}s`;
  const fadeTiming = `${duration}s ${target.easing ?? FADE_EASING} ${delay}s`;
  const parts: string[] = [];
  if (target.left !== undefined || target.dx !== undefined) parts.push(`left ${timing}`);
  if (target.top !== undefined || target.dy !== undefined) parts.push(`top ${timing}`);
  if (target.rotation !== undefined) parts.push(`rotate ${timing}`);
  if (target.scale !== undefined) parts.push(`scale ${timing}`);
  if (target.opacity !== undefined) parts.push(`opacity ${fadeTiming}`);
  return parts.join(",");
}

/** Seconds from page activation until a MotionTarget starts playing.
  * Plain targets: their own delay. afterEntrance targets: the
  * entrance's full runtime is added, so the target's delay reads as
  * "show for N seconds" in the Designer. */
export function targetStartSeconds(target: MotionTarget, from: MotionFrom | null | undefined): number {
  const own = target.delay ?? 0;
  if (!target.afterEntrance || !from) return own;
  return (from.delay ?? 0) + (from.duration ?? 0.6) + own;
}

/** Resolve a MotionTarget's effective end values against the item's
  * authored base. Absolute left/top win; otherwise dx/dy ride on the
  * base (that's what makes offset-based exits drag-safe — the base is
  * read at play time, so it's always the CURRENT authored position). */
export function resolveTargetValues(
  target: MotionTarget,
  base: { left: number; top: number; rotation: number },
): { left: number; top: number; rotation: number; scale: number; opacity: number } {
  return {
    left: target.left ?? base.left + (target.dx ?? 0),
    top: target.top ?? base.top + (target.dy ?? 0),
    rotation: target.rotation ?? base.rotation,
    scale: target.scale ?? 1,
    opacity: target.opacity ?? 1,
  };
}

// Apply the end values of a motion target to an element. Only the
// fields explicitly set on the target are written — others remain at
// whatever the base layout produced.
export function applyTargetState(
  el: HTMLElement,
  target: MotionTarget,
  values: { left: number; top: number; rotation: number; scale: number; opacity: number },
): void {
  if (target.left !== undefined || target.dx !== undefined) el.style.left = `${values.left}%`;
  if (target.top !== undefined || target.dy !== undefined) el.style.top = `${values.top}%`;
  if (target.rotation !== undefined) el.style.rotate = `${values.rotation}deg`;
  if (target.scale !== undefined) el.style.scale = String(values.scale);
  if (target.opacity !== undefined) el.style.opacity = String(values.opacity);
}

/** Snap an element to the pose its choreography ENDS in: the authored
  * resting base (where an entrance tweens home to) with the animationTo
  * end state written on top, transitions off. `to` = null settles an
  * item that only has an entrance — its end pose IS the base.
  *
  * This is what a page that has ALREADY played looks like. The expanded
  * reader plays a page's motion once, at the moment the page opens;
  * flipping back to a read page must show it the way the reader left
  * it, not replay copy they have already read. */
export function applySettledState(
  el: HTMLElement,
  to: MotionTarget | null,
  base: EntranceBase,
): void {
  el.style.transition = "none";
  el.style.left = `${base.left}%`;
  el.style.top = `${base.top}%`;
  el.style.rotate = base.rotation ? `${base.rotation}deg` : "";
  el.style.scale = "";
  el.style.opacity = base.opacity !== 1 ? String(base.opacity) : "";
  if (to) applyTargetState(el, to, resolveTargetValues(to, base));
}

// Binary-search the font-size until the wrapped text fits inside the
// item's clip box. The CSS starts as `${fs}cqh`; we measure in px for
// precision, then write the result back in cqh so it scales
// proportionally with whatever container size the banner renders at.
// Without the cqh write-back, the designer canvas (rendered at a
// larger pixel size) and the preview/delivery (rendered at the target
// device size) shrink to different absolute px values — text ends up
// proportionally different in the two views even though the source
// fontSize is identical.
export function autoFitText(el: HTMLElement): void {
  const maxH = el.clientHeight;
  const maxW = el.clientWidth;
  if (maxH <= 0 || maxW <= 0) return;
  // Max container dimension for cqmax conversion (cqmax = % of the LARGER
  // side). Walk up to the nearest ancestor that establishes a size container
  // (the .design-box). When the lookup fails (shadow-root edge cases), fall
  // back to el itself so we still produce a finite ratio.
  const containerMax =
    findSizeContainerMaxDim(el) ?? el.parentElement?.clientHeight ?? maxH;
  if (containerMax <= 0) return;

  const current = parseFloat(getComputedStyle(el).fontSize);
  if (!Number.isFinite(current) || current <= 0) return;

  const writeCqmax = (px: number): void => {
    const cqmax = (px / containerMax) * 100;
    el.style.fontSize = `${cqmax.toFixed(3)}cqmax`;
  };

  // Fit BOTH axes: height (copy taller than its box) AND width (a line wider
  // than its box — e.g. a long CJK headline in a narrow banner column that
  // can't wrap small enough). +0.5px tolerance absorbs sub-pixel rounding so
  // a perfectly-fitting line isn't shrunk needlessly.
  const fits = (): boolean => el.scrollHeight <= maxH && el.scrollWidth <= maxW + 0.5;

  // Already fits → keep the AUTHORED size. Re-deriving it from px rounds
  // (2.8 → 2.801cqmax), and in a box sized exactly to its text that pushes
  // a glyph to the next line/column and clips the last one — Preview then
  // breaks differently from the designer canvas (GH #482).
  if (fits()) return;
  let lo = 4;
  let hi = current;
  for (let i = 0; i < 12 && hi - lo > 0.5; i++) {
    const mid = (lo + hi) / 2;
    el.style.fontSize = `${mid}px`;
    if (fits()) lo = mid;
    else hi = mid;
  }
  writeCqmax(lo);
}

// After the per-element autoFitText sweep, make each field CONSISTENT
// across the reader's pages: group autofitted text by its data-field and
// pin every member to the group's SMALLEST fitted size. autoFitText
// shrinks each box independently to fit its own copy, so a wordier page
// ends up smaller; this re-unifies them (page 1's headline == page 2's
// headline) while guaranteeing nothing overflows — the min is exactly the
// size that fit the tightest page. cqh units are comparable because every
// reader sheet shares the same size container. Call right after the sweep.
export function harmonizeAutofit(root: HTMLElement): void {
  const groups = new Map<string, HTMLElement[]>();
  for (const el of root.querySelectorAll<HTMLElement>('[data-autofit="1"][data-field]')) {
    const f = el.dataset.field;
    if (!f) continue;
    const g = groups.get(f);
    if (g) g.push(el);
    else groups.set(f, [el]);
  }
  for (const group of groups.values()) {
    if (group.length < 2) continue;
    let min = Infinity;
    for (const el of group) {
      const fs = parseFloat(el.style.fontSize); // "<n>cqmax" → n
      if (Number.isFinite(fs) && fs > 0) min = Math.min(min, fs);
    }
    if (!Number.isFinite(min)) continue;
    const css = `${min}cqmax`;
    for (const el of group) {
      if (el.style.fontSize !== css) el.style.fontSize = css;
    }
  }
}

/** Find the nearest ancestor whose CSS `container-type` includes size — that's
  * the element cqmax is measured against. Returns the MAX of its client
  * width/height (cqmax = % of the larger side), or null when no such ancestor
  * exists. */
function findSizeContainerMaxDim(el: HTMLElement): number | null {
  let cur: HTMLElement | null = el.parentElement;
  while (cur) {
    const ct = getComputedStyle(cur).containerType;
    if (ct && (ct.includes("size") || ct === "size")) {
      return Math.max(cur.clientWidth, cur.clientHeight);
    }
    cur = cur.parentElement;
  }
  return null;
}
