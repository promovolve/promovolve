// Pointer-driven resize. Wraps the pure computeResize helper.
// Transient updates via Store.replace; one commit on pointerup.

import { refitItemCropToBox } from "../auto-crop";
import { clientToPct, type Rect } from "../coords";
import { parseAspect } from "../math";
import { currentLayout, updateItem } from "../state";
import type { Store } from "../store";
import { computeCircleRadius, computeResize, type ResizeDir } from "./resize";

interface ResizeParams {
  e: PointerEvent;
  dir: ResizeDir;
  idx: number;
  store: Store;
  canvasRect: () => Rect;
}

export function startResize({ e, dir, idx, store, canvasRect }: ResizeParams): void {
  const item = currentLayout(store.state)[idx];
  if (!item) return;
  e.preventDefault();
  e.stopPropagation();

  const start = clientToPct(canvasRect(), e.clientX, e.clientY);
  const orig = {
    left: item.left ?? 0,
    top: item.top ?? 0,
    width: item.width ?? 20,
    height: item.height ?? 20,
  };
  const stateAtStart = store.state;
  const aspect = parseAspect(store.state.mode.aspect);
  const aspectRatio = aspect.h / aspect.w;
  const isCircle = item.type === "circle";
  const isImage = item.type === "image";
  // Shift = constrain to the original proportions (any rectangular
  // item). Images used to be UNCONDITIONALLY locked "so the picture
  // doesn't stretch" — but stretching can't happen anymore: uncropped
  // images render object-fit:cover (the box crops, never distorts),
  // and cropped images get their crop window re-fitted to the new box
  // aspect at commit (see refitItemCropToBox). So the box shapes
  // freely by default, like every mainstream editor.
  const origRatio = orig.width / orig.height; // width / height, in % coords
  // Text on a CORNER handle scales like Canva/Keynote: the whole box —
  // including the font — grows/shrinks proportionally, so the type
  // tracks the box instead of reflowing at a fixed size. Edge handles
  // (n/s/e/w) keep the width-change-then-reflow behavior. Non-text
  // items are unaffected.
  const isCorner =
    (dir.includes("e") || dir.includes("w")) &&
    (dir.includes("n") || dir.includes("s"));
  const scaleFont = item.type === "text" && isCorner;
  const origFontSize = item.type === "text" ? item.fontSize ?? 5 : 0;

  const onMove = (ev: PointerEvent): void => {
    const p = clientToPct(canvasRect(), ev.clientX, ev.clientY);
    const raw = computeResize(dir, orig, { dx: p.x - start.x, dy: p.y - start.y });
    // Proportional when Shift is held OR we're font-scaling a text
    // corner (which is inherently a uniform scale).
    const bounds = ev.shiftKey || scaleFont ? lockAspect(raw, dir, orig, origRatio) : raw;
    // Snap-to-grid: whole percent by default, Alt for fine 0.1%.
    const step = ev.altKey ? 0.1 : 1;
    // Axes the gesture didn't move keep their EXACT original value.
    // The old unconditional clamp(1)+integer-snap also processed
    // untouched axes, so merely LENGTHENING a 0.4%-thin line floored
    // its height to 1% — and the floor made it impossible to ever thin
    // it back with handles. Snap before clamping so the grid can't
    // round a thin dimension to zero (0.2 floor wins) or push the box
    // off-canvas.
    const sizeOf = (v: number, origV: number): number =>
      v === origV ? origV : Math.min(100, Math.max(0.2, snap(v, step)));
    const w = sizeOf(bounds.width, orig.width);
    const h = sizeOf(bounds.height, orig.height);
    const posOf = (v: number, origV: number, max: number): number =>
      Math.max(0, Math.min(max, v === origV ? origV : snap(v, step)));
    const left = posOf(bounds.left, orig.left, 100 - w);
    const top = posOf(bounds.top, orig.top, 100 - h);
    const next = updateItem(store.state, idx, (it) => {
      if (it.type === "circle") {
        const rBounds = { left, top, width: w, height: h };
        return {
          ...it,
          left,
          top,
          radius: computeCircleRadius(orig, rBounds, aspectRatio),
        };
      }
      if (scaleFont && it.type === "text") {
        // Uniform scale: font tracks the box (w/h scaled together by
        // lockAspect). Base the ratio on width; round to 0.1 so the
        // props-panel font-size field reads cleanly as it syncs.
        const fontScale = w / orig.width;
        const fontSize = Math.max(0.1, Math.round(origFontSize * fontScale * 10) / 10);
        return { ...it, left, top, width: w, height: h, fontSize };
      }
      return { ...it, left, top, width: w, height: h };
    });
    store.replace(next);
  };

  const onEnd = (): void => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onEnd);
    window.removeEventListener("pointercancel", onEnd);
    window.removeEventListener("blur", onEnd);
    const nowItem = currentLayout(store.state)[idx];
    const wasItem = currentLayout(stateAtStart)[idx];
    if (
      nowItem && wasItem &&
      !shallowEqual(nowItem as unknown as Record<string, unknown>, wasItem as unknown as Record<string, unknown>)
    ) {
      store.commit(store.state);
      // Cropped image whose box changed shape: re-fit the crop window
      // to the new aspect so the renderer stays pixel-exact (async —
      // needs the image's natural dims; folds into the same undo step
      // when it lands, see refitItemCropToBox).
      if (isImage) void refitItemCropToBox(store, idx);
    }
    void isCircle;
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

// Lock the bounds to the original width/height ratio (Shift-drag). For
// corner handles, the axis with the larger relative change drives the
// other. For edge handles, the perpendicular axis is recomputed from
// the dragged axis. West and north directions require re-anchoring the
// top-left since the opposite edge should stay fixed.
function lockAspect(
  bounds: { left: number; top: number; width: number; height: number },
  dir: ResizeDir,
  orig: { left: number; top: number; width: number; height: number },
  ratio: number,
): { left: number; top: number; width: number; height: number } {
  const horiz = dir.includes("e") || dir.includes("w");
  const vert = dir.includes("n") || dir.includes("s");
  let { left, top, width, height } = bounds;

  if (horiz && vert) {
    // Corner drag: pick whichever axis moved more in proportion to
    // its original length, and match the other to preserve ratio.
    const widthChange = Math.abs(width - orig.width) / Math.max(1, orig.width);
    const heightChange = Math.abs(height - orig.height) / Math.max(1, orig.height);
    if (widthChange >= heightChange) {
      height = width / ratio;
    } else {
      width = height * ratio;
    }
  } else if (horiz) {
    height = width / ratio;
  } else if (vert) {
    width = height * ratio;
  }

  // West/north anchored their opposite edges in computeResize by
  // adjusting left/top. When we recompute width/height for aspect
  // lock, re-anchor those edges against the original opposite edge.
  if (dir.includes("w")) left = orig.left + (orig.width - width);
  if (dir.includes("n")) top = orig.top + (orig.height - height);

  return { left, top, width, height };
}

function shallowEqual(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  for (const k of Object.keys(a)) if (a[k] !== b[k]) return false;
  for (const k of Object.keys(b)) if (a[k] !== b[k]) return false;
  return true;
}
