// Pointer-driven resize for multi-selection. Operates on the group
// bounding box: dragging a handle reshapes the bbox, and every
// selected item is rescaled + repositioned proportionally to its
// position inside the original bbox.
//
// This is the multi-select counterpart to resize-gesture.ts. Items
// that aren't in the selection are left alone. Circles (which store
// only a radius, not separate w/h) get an averaged scale so they
// stay circular even when the bbox stretches non-uniformly.

import { clientToPct, type Rect } from "../coords";
import { itemBoundsPct, type BoundsPct } from "../geometry";
import { currentLayout, transformSelection } from "../state";
import type { Store } from "../store";
import type { LayoutItem } from "../types";
import type { ResizeDir } from "./resize";

interface GroupResizeParams {
  e: PointerEvent;
  dir: ResizeDir;
  store: Store;
  canvasRect: () => Rect;
}

interface ItemSnapshot {
  idx: number;
  /** Original full item (for spread + non-bounds fields). */
  item: LayoutItem;
  /** Original bbox in % coords. */
  bounds: BoundsPct;
  /** Position of bbox top-left as a fraction of the group bbox. */
  relX: number;
  relY: number;
  /** Bbox dimensions as a fraction of the group bbox. */
  relW: number;
  relH: number;
}

/** Compute the group bbox (the smallest axis-aligned rectangle that
  * contains every selected item's bbox). Returns null if nothing's
  * selected. */
export function selectionBounds(items: LayoutItem[], idxs: number[]): BoundsPct | null {
  const valid = idxs.filter((i) => i >= 0 && i < items.length);
  if (valid.length === 0) return null;
  const bs = valid.map((i) => itemBoundsPct(items[i]!));
  const left = Math.min(...bs.map((b) => b.left));
  const top = Math.min(...bs.map((b) => b.top));
  const right = Math.max(...bs.map((b) => b.left + b.width));
  const bottom = Math.max(...bs.map((b) => b.top + b.height));
  return { left, top, width: right - left, height: bottom - top };
}

/** Apply the handle-direction delta to a bbox. Negative directions
  * (n, w) move the top-left edge; positive (s, e) push out the
  * opposite edge. Same handle convention as the solo resize gesture. */
export function applyHandleDelta(
  bounds: BoundsPct,
  dir: ResizeDir,
  dx: number,
  dy: number,
): BoundsPct {
  let { left, top, width, height } = bounds;
  if (dir.includes("e")) width += dx;
  if (dir.includes("w")) { left += dx; width -= dx; }
  if (dir.includes("s")) height += dy;
  if (dir.includes("n")) { top += dy; height -= dy; }
  return { left, top, width, height };
}

/** Rescale each item from the old group bbox to the new one. Pure
  * helper — exported so tests can drive the math without mounting
  * a DOM. */
export function rescaleItem(snap: ItemSnapshot, next: BoundsPct): LayoutItem {
  const newBoxLeft = next.left + snap.relX * next.width;
  const newBoxTop = next.top + snap.relY * next.height;
  const newBoxWidth = snap.relW * next.width;
  const newBoxHeight = snap.relH * next.height;
  if (snap.item.type === "circle") {
    // Average the two scales so a non-uniform group resize keeps the
    // circle circular instead of distorting into an ellipse the model
    // can't represent. Tradeoff: the circle's bounds may drift slightly
    // from the proportional bbox — visually preferable to a stretched
    // half-circle.
    const newRadius = (newBoxWidth + newBoxHeight) / 4;
    return { ...snap.item, left: newBoxLeft, top: newBoxTop, radius: newRadius };
  }
  return {
    ...snap.item,
    left: newBoxLeft,
    top: newBoxTop,
    width: newBoxWidth,
    height: newBoxHeight,
  };
}

export function startGroupResize({ e, dir, store, canvasRect }: GroupResizeParams): void {
  e.preventDefault();
  e.stopPropagation();

  const stateAtStart = store.state;
  const items = currentLayout(stateAtStart);
  const idxs = stateAtStart.selectedItemIdxs;
  const origBounds = selectionBounds(items, idxs);
  if (!origBounds || origBounds.width <= 0 || origBounds.height <= 0) return;

  const snapshots: ItemSnapshot[] = idxs
    .filter((i) => i >= 0 && i < items.length)
    .map((i) => {
      const item = items[i]!;
      const b = itemBoundsPct(item);
      return {
        idx: i,
        item,
        bounds: b,
        relX: (b.left - origBounds.left) / origBounds.width,
        relY: (b.top - origBounds.top) / origBounds.height,
        relW: b.width / origBounds.width,
        relH: b.height / origBounds.height,
      };
    });

  const start = clientToPct(canvasRect(), e.clientX, e.clientY);
  const snapshotByIdx = new Map(snapshots.map((s) => [s.idx, s] as const));

  const onMove = (ev: PointerEvent): void => {
    const p = clientToPct(canvasRect(), ev.clientX, ev.clientY);
    const next = applyHandleDelta(origBounds, dir, p.x - start.x, p.y - start.y);
    // Floor at 1% in either dimension so items don't collapse to zero —
    // mirrors the solo resize gesture.
    if (next.width < 1 || next.height < 1) return;
    const nextState = transformSelection(store.state, (item, idx) => {
      const snap = snapshotByIdx.get(idx);
      return snap ? rescaleItem(snap, next) : item;
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