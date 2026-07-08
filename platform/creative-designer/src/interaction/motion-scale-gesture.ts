// Pointer-driven scale of the motion ghost. Dragging the SE corner
// handle outward grows the ghost; inward shrinks. Scale is stored as
// a multiplier (1 = unchanged) on item.animationTo.scale.

import { pctToClient, type Rect } from "../coords";
import { itemBoundsPct } from "../geometry";
import { currentLayout, updateItem } from "../state";
import type { Store } from "../store";

interface MotionScaleParams {
  e: PointerEvent;
  idx: number;
  store: Store;
  canvasRect: () => Rect;
}

const MIN_SCALE = 0.1;
const MAX_SCALE = 10;

export function startMotionScale({ e, idx, store, canvasRect }: MotionScaleParams): void {
  const item = currentLayout(store.state)[idx];
  if (!item || !item.animationTo) return;
  e.preventDefault();
  e.stopPropagation();

  const rect = canvasRect();
  const itemBounds = itemBoundsPct(item);
  const endLeft = item.animationTo.left ?? itemBounds.left;
  const endTop = item.animationTo.top ?? itemBounds.top;
  const center = pctToClient(rect, {
    x: endLeft + itemBounds.width / 2,
    y: endTop + itemBounds.height / 2,
  });
  const origScale = item.animationTo.scale ?? 1;
  const origDist = distance({ x: e.clientX, y: e.clientY }, center);
  if (origDist < 1) return; // degenerate: pointer started at center
  const stateAtStart = store.state;

  const onMove = (ev: PointerEvent): void => {
    const curDist = distance({ x: ev.clientX, y: ev.clientY }, center);
    const scale = clamp(origScale * (curDist / origDist), MIN_SCALE, MAX_SCALE);
    const next = updateItem(store.state, idx, (it) => ({
      ...it,
      animationTo: { ...(it.animationTo ?? {}), scale: round2(scale) },
    }));
    store.replace(next);
  };

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    const was = currentLayout(stateAtStart)[idx]?.animationTo?.scale ?? 1;
    const now = currentLayout(store.state)[idx]?.animationTo?.scale ?? 1;
    if (was !== now) store.commit(store.state);
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onEnd);
  window.addEventListener("pointercancel", onEnd);
  window.addEventListener("blur", onEnd);
}

function distance(a: { x: number; y: number }, b: { x: number; y: number }): number {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return Math.sqrt(dx * dx + dy * dy);
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function round2(v: number): number {
  return Math.round(v * 100) / 100;
}
