// Pointer-driven rotation for multi-selection. Pivots every selected
// item around the group's center. Each item's center is rotated (so
// they orbit the group center) AND its own rotation field gets the
// same delta added (so the item itself spins to match the orbit) —
// the combined effect looks like rotating a rigid group of stickers
// stuck to a board.

import { pctToClient, type Rect } from "../coords";
import { itemBoundsPct, type BoundsPct } from "../geometry";
import { currentLayout, transformSelection } from "../state";
import type { Store } from "../store";
import type { LayoutItem } from "../types";
import { selectionBounds } from "./group-resize-gesture";

interface GroupRotateParams {
  e: PointerEvent;
  store: Store;
  canvasRect: () => Rect;
}

interface ItemSnapshot {
  idx: number;
  item: LayoutItem;
  bounds: BoundsPct;
  /** Original center in % coords. */
  cx: number;
  cy: number;
  /** Original rotation in degrees. */
  rotation: number;
}

/** Rotate a point (px, py) around (gcx, gcy) by `radians`. Returns
  * the new (x, y). Pure helper — exported for tests. */
export function rotatePoint(
  px: number,
  py: number,
  gcx: number,
  gcy: number,
  radians: number,
): { x: number; y: number } {
  const dx = px - gcx;
  const dy = py - gcy;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  return {
    x: gcx + dx * cos - dy * sin,
    y: gcy + dx * sin + dy * cos,
  };
}

/** Compute the new item state given the group center, the rotation
  * delta in degrees, and the item's pre-gesture snapshot. */
export function rotateItem(
  snap: ItemSnapshot,
  gcx: number,
  gcy: number,
  deltaDeg: number,
): LayoutItem {
  const rad = (deltaDeg * Math.PI) / 180;
  const newCenter = rotatePoint(snap.cx, snap.cy, gcx, gcy, rad);
  const newLeft = newCenter.x - snap.bounds.width / 2;
  const newTop = newCenter.y - snap.bounds.height / 2;
  const newRotation = snap.rotation + deltaDeg;
  return { ...snap.item, left: newLeft, top: newTop, rotation: newRotation };
}

export function startGroupRotate({ e, store, canvasRect }: GroupRotateParams): void {
  e.preventDefault();
  e.stopPropagation();

  const stateAtStart = store.state;
  const items = currentLayout(stateAtStart);
  const idxs = stateAtStart.selectedItemIdxs;
  const origBounds = selectionBounds(items, idxs);
  if (!origBounds) return;

  const gcx = origBounds.left + origBounds.width / 2;
  const gcy = origBounds.top + origBounds.height / 2;

  const snapshots: ItemSnapshot[] = idxs
    .filter((i) => i >= 0 && i < items.length)
    .map((i) => {
      const item = items[i]!;
      const b = itemBoundsPct(item);
      return {
        idx: i,
        item,
        bounds: b,
        cx: b.left + b.width / 2,
        cy: b.top + b.height / 2,
        rotation: item.rotation ?? 0,
      };
    });
  const snapByIdx = new Map(snapshots.map((s) => [s.idx, s] as const));

  // Compute the initial pointer angle relative to the group center
  // (in client coords — pctToClient maps the group center to the
  // viewport so the angle math works regardless of canvas size).
  const rect = canvasRect();
  const groupCenterClient = pctToClient(rect, { x: gcx, y: gcy });
  const angle = (clientX: number, clientY: number): number =>
    Math.atan2(clientY - groupCenterClient.y, clientX - groupCenterClient.x);
  const startAngle = angle(e.clientX, e.clientY);

  const onMove = (ev: PointerEvent): void => {
    const cur = angle(ev.clientX, ev.clientY);
    const deltaDeg = ((cur - startAngle) * 180) / Math.PI;
    const nextState = transformSelection(store.state, (item, idx) => {
      const snap = snapByIdx.get(idx);
      return snap ? rotateItem(snap, gcx, gcy, deltaDeg) : item;
    });
    store.replace(nextState);
  };

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    if (store.state !== stateAtStart) store.commit(store.state);
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onEnd);
  window.addEventListener("pointercancel", onEnd);
  window.addEventListener("blur", onEnd);
}