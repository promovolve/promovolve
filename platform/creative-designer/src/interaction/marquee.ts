// Marquee (rubber-band) selection. User click-drags on empty canvas
// space; items whose bounding boxes intersect the dragged rectangle
// get selected on pointerup. Shift/Cmd held → add to existing
// selection; otherwise → replace.
//
// The marquee rectangle is a visible element added to the overlay
// while the gesture is active, styled to match Figma's dashed outline
// with a tinted fill.

import { clientToPct, type Rect } from "../coords";
import { itemBoundsPct, rectsIntersect, type BoundsPct } from "../geometry";
import { currentLayout, setSelection, toggleSelect } from "../state";
import type { Store } from "../store";
import type { LayoutItem } from "../types";
import { tokens } from "../ui/tokens";

const STROKE = tokens.amber;
// Marquee fill: amber at low opacity. Kept as rgba until we decide
// whether to expose alpha-overlay tokens.
const FILL = "rgba(245,158,11,0.12)";

interface MarqueeParams {
  e: PointerEvent;
  overlayRoot: HTMLElement;
  store: Store;
  canvasRect: () => Rect;
  additive: boolean;
}

export function startMarquee({ e, overlayRoot, store, canvasRect, additive }: MarqueeParams): void {
  e.preventDefault();
  const start = clientToPct(canvasRect(), e.clientX, e.clientY);
  const baseSelection = additive ? [...store.state.selectedItemIdxs] : [];

  // A plain click on empty canvas must ONLY deselect (done by the caller
  // before this runs) — it must NOT marquee-select. Real clicks carry a
  // pixel or two of jitter, and without a drag threshold that jitter fires
  // collectHits at the click point and re-selects whatever layer sits
  // under the cursor (the "clicking empty chooses a layer" bug). So the
  // marquee stays dormant — no rect, no selection — until the pointer
  // travels past DRAG_THRESHOLD_PX.
  const DRAG_THRESHOLD_PX = 4;
  const startCX = e.clientX;
  const startCY = e.clientY;
  let active = false;

  const rect = document.createElement("div");
  rect.className = "cd-marquee";
  rect.style.cssText = [
    "position: absolute",
    `left: ${start.x}%`,
    `top: ${start.y}%`,
    "width: 0",
    "height: 0",
    `border: 1px dashed ${STROKE}`,
    `background: ${FILL}`,
    "pointer-events: none",
  ].join(";");

  const onMove = (ev: PointerEvent): void => {
    if (!active) {
      if (Math.hypot(ev.clientX - startCX, ev.clientY - startCY) < DRAG_THRESHOLD_PX) return;
      active = true;
      overlayRoot.appendChild(rect);
    }
    const p = clientToPct(canvasRect(), ev.clientX, ev.clientY);
    const marquee = normalize(start, p);
    rect.style.left = `${marquee.left}%`;
    rect.style.top = `${marquee.top}%`;
    rect.style.width = `${marquee.width}%`;
    rect.style.height = `${marquee.height}%`;

    // Live-preview which items fall inside the marquee — saves a
    // surprise on release.
    const hits = collectHits(store, marquee);
    const next = combine(baseSelection, hits, additive);
    store.replace(setSelection(store.state, next));
  };

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    if (active) rect.remove();
    // Selection is already set via replace; no commit (navigation).
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onEnd);
  window.addEventListener("pointercancel", onEnd);
  window.addEventListener("blur", onEnd);
}

function normalize(a: { x: number; y: number }, b: { x: number; y: number }): BoundsPct {
  const left = Math.min(a.x, b.x);
  const top = Math.min(a.y, b.y);
  const width = Math.abs(b.x - a.x);
  const height = Math.abs(b.y - a.y);
  return { left, top, width, height };
}

function collectHits(store: Store, marquee: BoundsPct): number[] {
  const items = currentLayout(store.state);
  const hits: number[] = [];
  for (let i = 0; i < items.length; i++) {
    const it = items[i]! as LayoutItem & { hidden?: boolean; locked?: boolean };
    // Marquee skips hidden items (they're not on the canvas) and
    // locked items (can't be transformed, so sweeping them into a
    // selection would be misleading).
    if (it.hidden || it.locked) continue;
    if (rectsIntersect(marquee, itemBoundsPct(items[i]!))) hits.push(i);
  }
  return hits;
}

// Merge strategies:
//   additive: base ∪ hits (dedup preserving order)
//   replace:  hits
function combine(base: number[], hits: number[], additive: boolean): number[] {
  if (!additive) return hits;
  const set = new Set(base);
  const out = [...base];
  for (const i of hits) {
    if (!set.has(i)) {
      out.push(i);
      set.add(i);
    }
  }
  return out;
}

// toggleSelect is referenced from overlay but not here — keep import
// alive for future symmetric marquee behavior (Alt+drag to subtract).
void toggleSelect;
