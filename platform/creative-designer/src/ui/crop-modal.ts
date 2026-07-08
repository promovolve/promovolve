// Crop-image modal. Shown by the props-panel's "Crop image…" button
// or by double-clicking an image item in the future. Displays the
// source image at contain-size, overlays a draggable crop rectangle,
// and on Done writes { x, y, w, h } (percent of natural image) back
// onto the ImageItem.
//
// On commit, the bounding box's height is auto-adjusted so the
// rendered crop keeps the source aspect — otherwise a 4:3 crop
// shoved into a 16:9 box would be stretched by the wrapper-div
// renderer, defeating the point of the crop.
//
// Cancel leaves the item untouched. Esc = cancel, Enter = Done.

import { findSalientBox, rewriteForSaliency } from "../saliency";
import { currentPage, updateItem } from "../state";
import type { Store } from "../store";
import type { ImageItem, LayoutItem } from "../types";
import { tokens } from "./tokens";

interface CropRect {
  x: number; y: number; w: number; h: number; // all in %-of-natural-image
}

export function openCropModal(store: Store, idx: number, item: ImageItem): void {
  // Resolve the source the same way the renderer does — a field-bound
  // image (the shared main image) carries no item.src; it reads page.img.
  // Without this the crop modal saw an empty src and bailed.
  const page = currentPage(store.state) as Record<string, unknown> | null;
  const src = item.src ?? (item.field ? (page?.[item.field] as string | undefined) : undefined);
  if (!src) return;
  const existing = document.getElementById("cd-crop-modal");
  if (existing) existing.remove();

  const root = document.createElement("div");
  root.id = "cd-crop-modal";
  root.style.cssText = [
    "position: fixed",
    "inset: 0",
    "background: rgba(0,0,0,0.88)",
    "z-index: 200",
    "display: flex",
    "flex-direction: column",
    "align-items: center",
    "justify-content: center",
    "padding: 40px",
    "gap: 16px",
    // Body-mounted (outside #designer-root): set the UI font so the dialog
    // doesn't inherit the document default in the prod shell.
    `font-family: ${tokens.sans}`,
  ].join(";");
  document.body.appendChild(root);

  // Stage — contains the image + crop overlay. Sized to fit the
  // viewport with some margin.
  const stage = document.createElement("div");
  stage.style.cssText = [
    "position: relative",
    "max-width: calc(100vw - 160px)",
    "max-height: calc(100vh - 200px)",
    "overflow: hidden",
    `background: ${tokens.ink900}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 6px",
  ].join(";");

  const img = document.createElement("img");
  // Try CORS-anonymous first so saliency.ts can read pixels via
  // getImageData (canvas would otherwise be tainted and throw
  // SecurityError). Works for our own R2-hosted assets.
  //
  // For third-party hosts (LP-extracted images on customer CDNs)
  // that don't send Access-Control-Allow-Origin, the anonymous-
  // mode load *fails* outright. Fall back to a plain load — the
  // image at least displays in the modal; saliency silently
  // skips (canvas-taint catch in saliency.ts returns null →
  // crop-modal stays at the default full-image rect).
  img.crossOrigin = "anonymous";
  img.alt = "";
  let triedFallback = false;
  img.addEventListener("error", () => {
    if (triedFallback) return;
    triedFallback = true;
    img.removeAttribute("crossorigin");
    img.src = src;
  });
  // Route saliency-side loads through the dashboard proxy so reads
  // always get CORS headers. R2's edge cache can serve a CORS-less
  // response for a given URL once it's been fetched without an Origin
  // header, and bucket rules can't override that cached response.
  // Plain <img> display (modal thumbnail, ad-unit) doesn't need this
  // and stays on the direct CDN URL via the fallback path above.
  img.src = rewriteForSaliency(src);
  img.style.cssText = [
    "display: block",
    "max-width: calc(100vw - 164px)",
    "max-height: calc(100vh - 204px)",
    "user-select: none",
    "-webkit-user-drag: none",
  ].join(";");
  stage.appendChild(img);

  // Dim overlay — four rectangles around the crop rect so the
  // outside-the-crop area reads as muted. Simpler than clip-path
  // and works in any browser.
  const dimTop    = dim();
  const dimRight  = dim();
  const dimBottom = dim();
  const dimLeft   = dim();
  stage.append(dimTop, dimRight, dimBottom, dimLeft);

  // Crop rectangle — the visible framing overlay.
  const cropBox = document.createElement("div");
  cropBox.style.cssText = [
    "position: absolute",
    `border: 1px solid ${tokens.amber}`,
    "box-shadow: 0 0 0 1px rgba(0,0,0,0.5) inset",
    "cursor: move",
  ].join(";");
  stage.appendChild(cropBox);

  // Corner + edge handles — 8 total.
  type HandleKey = "nw" | "n" | "ne" | "e" | "se" | "s" | "sw" | "w";
  const handles = new Map<HandleKey, HTMLElement>();
  const HANDLE_KEYS: HandleKey[] = ["nw", "n", "ne", "e", "se", "s", "sw", "w"];
  for (const k of HANDLE_KEYS) {
    const h = document.createElement("div");
    h.dataset.handle = k;
    h.style.cssText = [
      "position: absolute",
      "width: 10px",
      "height: 10px",
      `background: ${tokens.amber}`,
      `border: 1px solid ${tokens.ink900}`,
      `cursor: ${cursorFor(k)}`,
    ].join(";");
    cropBox.appendChild(h);
    handles.set(k, h);
  }

  // Footer — action buttons.
  const footer = document.createElement("div");
  footer.style.cssText = "display: flex; gap: 8px; align-items: center;";
  const hint = document.createElement("span");
  hint.style.cssText = `color:${tokens.ink300};font-size:11px;margin-right:auto;font-family:${tokens.sans};`;
  hint.textContent = "Loading image…";
  const resetBtn = secondaryBtn("Reset", () => {
    autoSuggested = false; // explicit reset → drop the "auto-detected" hint
    setCrop({ x: 0, y: 0, w: 100, h: 100 });
  });
  const cancelBtn = secondaryBtn("Cancel", () => close(false));
  const doneBtn = primaryBtn("Done", () => close(true));
  footer.append(hint, resetBtn, cancelBtn, doneBtn);
  root.append(stage, footer);

  // State — current crop rect in natural-image percent. Defaults
  // to the item's existing crop, or full-image if none.
  let crop: CropRect = item.crop
    ? { ...item.crop }
    : { x: 0, y: 0, w: 100, h: 100 };
  let natural = { w: 0, h: 0 };

  const setCrop = (next: CropRect): void => {
    crop = clampCrop(next);
    paint();
  };

  const paint = (): void => {
    // cropBox in stage coords — image fills the stage, so percent
    // of natural image maps directly to percent of the image
    // element's rendered box.
    cropBox.style.left   = `${crop.x}%`;
    cropBox.style.top    = `${crop.y}%`;
    cropBox.style.width  = `${crop.w}%`;
    cropBox.style.height = `${crop.h}%`;

    // Dim rectangles — layout around the crop box in stage coords.
    const L = `${crop.x}%`;
    const T = `${crop.y}%`;
    const R = `${crop.x + crop.w}%`;
    const B = `${crop.y + crop.h}%`;
    Object.assign(dimTop.style,    { left: "0",  top: "0", width: "100%",                  height: T });
    Object.assign(dimBottom.style, { left: "0",  top: B,   width: "100%",                  bottom: "0" });
    Object.assign(dimLeft.style,   { left: "0",  top: T,   width: L,                       height: `${crop.h}%` });
    Object.assign(dimRight.style,  { left: R,    top: T,   right: "0",                     height: `${crop.h}%` });

    // Position the 8 handles at cropBox corners/midpoints. They're
    // children of cropBox, so % values are relative to it.
    const place = (k: HandleKey, left: string, top: string): void => {
      const h = handles.get(k);
      if (!h) return;
      h.style.left = left;
      h.style.top = top;
      h.style.marginLeft = "-5px";
      h.style.marginTop  = "-5px";
    };
    place("nw", "0%",   "0%");   place("n",  "50%",  "0%");   place("ne", "100%", "0%");
    place("w",  "0%",   "50%");  place("e",  "100%", "50%");
    place("sw", "0%",   "100%"); place("s",  "50%",  "100%"); place("se", "100%", "100%");

    if (!natural.w || !natural.h) {
      hint.textContent = "Loading image…";
    } else {
      const dim = `${crop.w.toFixed(0)}% × ${crop.h.toFixed(0)}% · origin ${crop.x.toFixed(0)}% ${crop.y.toFixed(0)}% · source ${natural.w}×${natural.h}`;
      hint.textContent = autoSuggested ? `${dim} · auto-detected subject` : dim;
    }
  };

  // True only when this modal opened with no authored crop yet AND
  // we successfully placed a saliency-suggested rect. Used to render
  // a hint and to back off if the user explicitly clicks Reset (so
  // we don't immediately re-suggest).
  let autoSuggested = false;
  const hadAuthoredCrop = item.crop != null;

  const onLoad = (): void => {
    natural = { w: img.naturalWidth, h: img.naturalHeight };
    paint();
    // Only auto-suggest when the author hasn't already cropped this
    // image. Re-opening an existing crop is "review what I authored,"
    // not "ask the model again."
    if (hadAuthoredCrop) return;
    findSalientBox(img).then((box) => {
      if (!box) return;
      // Skip if the user has already moved the box manually since
      // the modal opened (rare, since inference takes ~200ms after
      // load — but a fast author could).
      if (crop.x !== 0 || crop.y !== 0 || crop.w !== 100 || crop.h !== 100) return;
      autoSuggested = true;
      setCrop(box);
    }).catch(() => { /* saliency.ts already logged */ });
  };
  if (img.complete && img.naturalWidth > 0) {
    onLoad();
  } else {
    img.addEventListener("load", onLoad);
  }

  // ─── Interaction ─────────────────────────────────────────────

  const stageRect = (): DOMRect => stage.getBoundingClientRect();

  const pxToCropPercent = (clientX: number, clientY: number): { x: number; y: number } => {
    const r = stageRect();
    return {
      x: clamp(((clientX - r.left) / r.width) * 100, 0, 100),
      y: clamp(((clientY - r.top) / r.height) * 100, 0, 100),
    };
  };

  // Drag inside cropBox → pan the crop rect.
  cropBox.addEventListener("pointerdown", (e) => {
    const handle = (e.target as HTMLElement).dataset.handle as HandleKey | undefined;
    e.preventDefault();
    e.stopPropagation();
    const start = pxToCropPercent(e.clientX, e.clientY);
    const origin = { ...crop };
    const onMove = (ev: PointerEvent): void => {
      const p = pxToCropPercent(ev.clientX, ev.clientY);
      const dx = p.x - start.x;
      const dy = p.y - start.y;
      setCrop(handle ? resizeByHandle(origin, handle, dx, dy) : panBy(origin, dx, dy));
    };
    const onUp = (): void => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
  });

  // Click-drag on empty stage → redraw crop from scratch.
  stage.addEventListener("pointerdown", (e) => {
    if (e.target !== stage && e.target !== img) return; // ignore cropBox / handles
    e.preventDefault();
    const start = pxToCropPercent(e.clientX, e.clientY);
    setCrop({ x: start.x, y: start.y, w: 0.1, h: 0.1 });
    const onMove = (ev: PointerEvent): void => {
      const p = pxToCropPercent(ev.clientX, ev.clientY);
      const x = Math.min(start.x, p.x);
      const y = Math.min(start.y, p.y);
      setCrop({ x, y, w: Math.abs(p.x - start.x), h: Math.abs(p.y - start.y) });
    };
    const onUp = (): void => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
      // Tiny rect = treat as a cancel (user clicked without dragging).
      if (crop.w < 2 || crop.h < 2) setCrop({ x: 0, y: 0, w: 100, h: 100 });
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
  });

  // Keyboard.
  const onKey = (e: KeyboardEvent): void => {
    if (e.key === "Escape") { e.preventDefault(); close(false); }
    if (e.key === "Enter")  { e.preventDefault(); close(true); }
  };
  window.addEventListener("keydown", onKey);

  function close(commit: boolean): void {
    window.removeEventListener("keydown", onKey);
    if (commit && natural.w > 0 && natural.h > 0) {
      applyCrop(store, idx, item, crop, natural);
    }
    root.remove();
  }
}

// ─── Crop geometry ─────────────────────────────────────────────

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function clampCrop(c: CropRect): CropRect {
  // Keep crop fully inside [0,100]; enforce a 1% minimum so the user
  // can't make it invisible.
  const w = clamp(c.w, 1, 100);
  const h = clamp(c.h, 1, 100);
  const x = clamp(c.x, 0, 100 - w);
  const y = clamp(c.y, 0, 100 - h);
  return { x, y, w, h };
}

function panBy(origin: CropRect, dx: number, dy: number): CropRect {
  return { x: origin.x + dx, y: origin.y + dy, w: origin.w, h: origin.h };
}

function resizeByHandle(
  origin: CropRect,
  handle: "nw" | "n" | "ne" | "e" | "se" | "s" | "sw" | "w",
  dx: number, dy: number,
): CropRect {
  let { x, y, w, h } = origin;
  // Horizontal part.
  if (handle.includes("w")) { x += dx; w -= dx; }
  if (handle.includes("e")) { w += dx; }
  // Vertical part.
  if (handle.includes("n")) { y += dy; h -= dy; }
  if (handle.includes("s")) { h += dy; }
  // Flip handling: if a drag inverts the rect, clamp size to minimum.
  if (w < 1) { x += w - 1; w = 1; }
  if (h < 1) { y += h - 1; h = 1; }
  return { x, y, w, h };
}

function cursorFor(k: string): string {
  return {
    nw: "nwse-resize", se: "nwse-resize",
    ne: "nesw-resize", sw: "nesw-resize",
    n: "ns-resize", s: "ns-resize",
    e: "ew-resize", w: "ew-resize",
  }[k] ?? "move";
}

function dim(): HTMLElement {
  const el = document.createElement("div");
  el.style.cssText = [
    "position: absolute",
    "background: rgba(0,0,0,0.55)",
    "pointer-events: none",
  ].join(";");
  return el;
}

// ─── Commit ─────────────────────────────────────────────────────

function applyCrop(
  store: Store, idx: number, _item: ImageItem, crop: CropRect, natural: { w: number; h: number },
): void {
  // If the user reset the crop to the whole image, strip the field
  // entirely — keeps the banner on the fast `<img>` path and the
  // item comparable to a never-cropped item.
  const isFull = crop.x <= 0.5 && crop.y <= 0.5 && crop.w >= 99.5 && crop.h >= 99.5;

  // Auto-adjust the bounding-box height so the cropped image keeps
  // the source aspect once the renderer scales it. Formula:
  //   (w × canvasW) / (h × canvasH) = (cropW × imgW) / (cropH × imgH)
  //   ⇒ h = w × (cropH × imgH × canvasW) / (cropW × imgW × canvasH)
  // Keeps width unchanged; user can still resize freely afterwards.
  const state = store.state;
  const { w: canvasW, h: canvasH } = state.mode;
  store.commit(updateItem(state, idx, (it): LayoutItem => {
    if (it.type !== "image") return it;
    const width = it.width ?? 30;
    const heightFromCrop = isFull
      ? it.height
      : width * (crop.h * natural.h * canvasW) / (crop.w * natural.w * canvasH);
    const rounded = heightFromCrop != null ? Math.round(heightFromCrop * 10) / 10 : undefined;
    if (isFull) {
      const { crop: _drop, ...rest } = it as ImageItem & { crop?: unknown };
      return rest as ImageItem;
    }
    return {
      ...it,
      crop: {
        x: Math.round(crop.x * 10) / 10,
        y: Math.round(crop.y * 10) / 10,
        w: Math.round(crop.w * 10) / 10,
        h: Math.round(crop.h * 10) / 10,
      },
      ...(rounded != null ? { height: rounded } : {}),
    };
  }));
}

// ─── Buttons ─────────────────────────────────────────────────────

function primaryBtn(label: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.style.cssText = [
    "padding: 6px 14px",
    `background: ${tokens.amber}`,
    "color: oklch(0.12 0.04 55)",
    "border: none",
    "border-radius: 4px",
    "font: inherit",
    "font-size: 12px",
    "font-weight: 600",
    "cursor: pointer",
  ].join(";");
  b.addEventListener("click", onClick);
  return b;
}

function secondaryBtn(label: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.style.cssText = [
    "padding: 5px 12px",
    "background: transparent",
    `color: ${tokens.ink200}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "font: inherit",
    "font-size: 12px",
    "cursor: pointer",
    "transition: background .12s, color .12s",
  ].join(";");
  b.addEventListener("mouseenter", () => { b.style.background = tokens.ink700; b.style.color = tokens.ink100; });
  b.addEventListener("mouseleave", () => { b.style.background = "transparent"; b.style.color = tokens.ink200; });
  b.addEventListener("click", onClick);
  return b;
}
