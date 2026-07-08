// Pointer-driven rotation of the motion ghost. Mirror of
// rotate-gesture.ts but writes to item.animationTo.rotation instead
// of item.rotation.

import { pctToClient, type Rect } from "../coords";
import { currentLayout, updateItem } from "../state";
import type { Store } from "../store";
import { computeRotation } from "./rotate";
import { itemBoundsPct } from "../geometry";

interface MotionRotateParams {
  e: PointerEvent;
  idx: number;
  store: Store;
  canvasRect: () => Rect;
}

export function startMotionRotate({ e, idx, store, canvasRect }: MotionRotateParams): void {
  const item = currentLayout(store.state)[idx];
  if (!item || !item.animationTo) return;
  e.preventDefault();
  e.stopPropagation();

  const rect = canvasRect();
  // Ghost sits at the motion target position with the same size as
  // the item. Use its bounds to compute the rotation pivot.
  const itemBounds = itemBoundsPct(item);
  const endLeft = item.animationTo.left ?? itemBounds.left;
  const endTop = item.animationTo.top ?? itemBounds.top;
  const center = pctToClient(rect, {
    x: endLeft + itemBounds.width / 2,
    y: endTop + itemBounds.height / 2,
  });
  const origRotation = item.animationTo.rotation ?? item.rotation ?? 0;
  const stateAtStart = store.state;
  const start = { x: e.clientX, y: e.clientY };

  const onMove = (ev: PointerEvent): void => {
    const rotation = computeRotation(origRotation, center, start, {
      x: ev.clientX,
      y: ev.clientY,
    });
    const next = updateItem(store.state, idx, (it) => ({
      ...it,
      animationTo: { ...(it.animationTo ?? {}), rotation },
    }));
    store.replace(next);
  };

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    const was = currentLayout(stateAtStart)[idx]?.animationTo?.rotation ?? null;
    const now = currentLayout(store.state)[idx]?.animationTo?.rotation ?? null;
    if (was !== now) store.commit(store.state);
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onEnd);
  window.addEventListener("pointercancel", onEnd);
  window.addEventListener("blur", onEnd);
}
