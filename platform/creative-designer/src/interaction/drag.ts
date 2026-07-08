// Drag-to-move the entire current selection. Every selected item
// shifts by the same delta so relative positions are preserved
// through the gesture. One undo step per drag regardless of how many
// items moved. Dynamic alignment guides appear while dragging when
// the moving item's edges/center line up with other items or the
// canvas edges/center.

import { clientToPct, type Rect } from "../coords";
import { clamp } from "../math";
import type { Store } from "../store";
import { currentLayout, updateItem } from "../state";
import type { DesignerState, LayoutItem } from "../types";
import { computeSnap, hideGuides, showGuides, type Bounds } from "./guides";

interface DragParams {
  e: PointerEvent;
  store: Store;
  canvasRect: () => Rect;
}

export function startDrag({ e, store, canvasRect }: DragParams): void {
  const selected = store.state.selectedItemIdxs;
  if (selected.length === 0) return;
  e.preventDefault();

  const start = clientToPct(canvasRect(), e.clientX, e.clientY);
  const stateAtStart = store.state;
  const items = currentLayout(stateAtStart);

  // Snapshot origins for every selected item so intermediate
  // updateItem calls can't drift after repeated clamping.
  const origs = selected.map((idx) => {
    const it = items[idx];
    return { idx, left: it?.left ?? 0, top: it?.top ?? 0 };
  });

  // Snap-target candidates: every item on the current layout that's
  // NOT being dragged. Computed once at drag-start so the reference
  // set stays stable through the gesture.
  const draggedSet = new Set(selected);
  const othersBounds: Bounds[] = items
    .map((it, i) => ({ it, i }))
    .filter(({ i }) => !draggedSet.has(i))
    .map(({ it }) => itemBounds(it));

  const onMove = (ev: PointerEvent): void => {
    const p = clientToPct(canvasRect(), ev.clientX, ev.clientY);
    let dx = p.x - start.x;
    let dy = p.y - start.y;
    // Snap-to-grid: round positions to whole-percent by default; hold
    // Alt (or Option on Mac) for fine-grained 0.1% placement.
    const step = ev.altKey ? 0.1 : 1;

    // Shift = constrain the move to a single axis (the one the pointer has
    // travelled further along), locking the perpendicular coordinate so the
    // item stays aligned — the standard shift-drag every drawing tool has.
    if (ev.shiftKey) {
      if (Math.abs(dx) >= Math.abs(dy)) dy = 0;
      else dx = 0;
    }

    // Compute alignment snap from the PRIMARY dragged item (first
    // selected). Multi-selection shifts as a group by the same
    // adjustment; we don't run snap per-item. Skipped while axis-locking
    // (shift) — the lock IS the constraint and smart-snap would fight it.
    const primaryIdx = origs[0]?.idx;
    const primaryOrig = origs[0];
    let snapDx = dx;
    let snapDy = dy;
    if (!ev.shiftKey && primaryIdx != null && primaryOrig) {
      const primaryItem = items[primaryIdx];
      if (primaryItem) {
        const candidateBounds = {
          left: primaryOrig.left + dx,
          top: primaryOrig.top + dy,
          width: primaryItem.type === "circle"
            ? (primaryItem.radius ?? 5) * 2
            : (primaryItem.width ?? 30),
          height: primaryItem.type === "circle"
            ? (primaryItem.radius ?? 5) * 2
            : (primaryItem.height ?? 20),
        };
        const snapRes = computeSnap(candidateBounds, othersBounds);
        snapDx = snapRes.left - primaryOrig.left;
        snapDy = snapRes.top - primaryOrig.top;
        showGuides(snapRes.guides);
      }
    } else if (ev.shiftKey) {
      hideGuides();
    }

    let next: DesignerState = store.state;
    for (const { idx, left, top } of origs) {
      next = updateItem(next, idx, (it) => {
        // Keep the item fully inside the canvas by clamping against
        // its own bounding box. Circles use 2×radius; other types use
        // width/height. Defaults match renderer fallbacks.
        const w = it.type === "circle"
          ? (it.radius ?? 5) * 2
          : (it.width ?? 30);
        const h = it.type === "circle"
          ? (it.radius ?? 5) * 2
          : (it.height ?? 20);
        return {
          ...it,
          left: clamp(snap(left + snapDx, step), 0, Math.max(0, 100 - w)),
          top:  clamp(snap(top  + snapDy, step), 0, Math.max(0, 100 - h)),
        };
      });
    }
    store.replace(next);
  };

  function itemBounds(it: LayoutItem): Bounds {
    if (it.type === "circle") {
      const r = it.radius ?? 5;
      return {
        left: it.left ?? 0,
        top: it.top ?? 0,
        width: r * 2,
        height: r * 2,
      };
    }
    return {
      left: it.left ?? 0,
      top: it.top ?? 0,
      width: it.width ?? 30,
      height: it.height ?? 20,
    };
  }

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    hideGuides();
    const afterItems = currentLayout(store.state);
    const moved = origs.some(({ idx, left, top }) => {
      const it = afterItems[idx];
      return it && (it.left !== left || it.top !== top);
    });
    if (moved) store.commit(store.state);
  };

  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onEnd);
  window.addEventListener("pointercancel", onEnd);
  window.addEventListener("blur", onEnd);
}

function snap(v: number, step: number): number {
  const s = step > 0 ? step : 0.1;
  return Math.round(v / s) * s;
}
