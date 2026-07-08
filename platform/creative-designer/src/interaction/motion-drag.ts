// Drag the motion-ghost of an item to edit its animationTo position.
// Mirrors interaction/drag.ts but writes into item.animationTo instead
// of item.left/top.

import { clientToPct, type Rect } from "../coords";
import { clamp } from "../math";
import { currentLayout, updateItem } from "../state";
import type { Store } from "../store";

interface MotionDragParams {
  e: PointerEvent;
  idx: number;
  store: Store;
  canvasRect: () => Rect;
}

export function startMotionDrag({ e, idx, store, canvasRect }: MotionDragParams): void {
  const item = currentLayout(store.state)[idx];
  if (!item) return;
  const to = item.animationTo;
  if (!to) return;
  e.preventDefault();
  e.stopPropagation();

  const start = clientToPct(canvasRect(), e.clientX, e.clientY);
  const origLeft = to.left ?? item.left ?? 0;
  const origTop = to.top ?? item.top ?? 0;
  const stateAtStart = store.state;

  const onMove = (ev: PointerEvent): void => {
    const p = clientToPct(canvasRect(), ev.clientX, ev.clientY);
    const nextLeft = clamp(round1(origLeft + (p.x - start.x)), -50, 150);
    const nextTop = clamp(round1(origTop + (p.y - start.y)), -50, 150);
    const next = updateItem(store.state, idx, (it) => ({
      ...it,
      animationTo: { ...(it.animationTo ?? {}), left: nextLeft, top: nextTop },
    }));
    store.replace(next);
  };

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    const before = currentLayout(stateAtStart)[idx]?.animationTo;
    const after = currentLayout(store.state)[idx]?.animationTo;
    if (before?.left !== after?.left || before?.top !== after?.top) {
      store.commit(store.state);
    }
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onEnd);
  window.addEventListener("pointercancel", onEnd);
  window.addEventListener("blur", onEnd);
}

function round1(v: number): number {
  return Math.round(v * 10) / 10;
}
