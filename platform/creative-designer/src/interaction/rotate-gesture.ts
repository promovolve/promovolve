// Pointer-driven rotation. Wraps the pure computeRotation helper.
// The rotation handle sits above the selected item; dragging it pivots
// the item around its center.

import { pctToClient, type Rect } from "../coords";
import { currentLayout, updateItem } from "../state";
import type { Store } from "../store";
import { computeRotation } from "./rotate";

interface RotateParams {
  e: PointerEvent;
  idx: number;
  store: Store;
  canvasRect: () => Rect;
}

export function startRotate({ e, idx, store, canvasRect }: RotateParams): void {
  const item = currentLayout(store.state)[idx];
  if (!item) return;
  e.preventDefault();
  e.stopPropagation();

  const rect = canvasRect();
  const bounds = itemBoundsPct(item);
  const center = pctToClient(rect, {
    x: bounds.left + bounds.width / 2,
    y: bounds.top + bounds.height / 2,
  });
  const origRotation = item.rotation ?? 0;
  const stateAtStart = store.state;
  const start = { x: e.clientX, y: e.clientY };

  const onMove = (ev: PointerEvent): void => {
    const rotation = computeRotation(origRotation, center, start, {
      x: ev.clientX,
      y: ev.clientY,
    }, ev.shiftKey);
    const next = updateItem(store.state, idx, (it) => ({ ...it, rotation }));
    store.replace(next);
  };

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    const was = currentLayout(stateAtStart)[idx]?.rotation ?? 0;
    const now = currentLayout(store.state)[idx]?.rotation ?? 0;
    if (was !== now) store.commit(store.state);
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onEnd);
  window.addEventListener("pointercancel", onEnd);
  window.addEventListener("blur", onEnd);
}

function itemBoundsPct(item: ReturnType<typeof currentLayout>[number]): {
  left: number;
  top: number;
  width: number;
  height: number;
} {
  const left = item.left ?? 0;
  const top = item.top ?? 0;
  if (item.type === "circle") {
    const r = item.radius ?? 5;
    return { left, top, width: r * 2, height: r * 2 };
  }
  return { left, top, width: item.width ?? 30, height: item.height ?? 20 };
}
