// Remove-background modal. Opened from the props-panel's "Remove
// background…" button on an image item. Loads the source with CORS
// (pixel reads), runs the u2netp matte in the browser (remove-bg.ts),
// previews the cutout over a checkerboard, and on Apply uploads the
// result as a NEW asset and points the item at it. The original asset
// is untouched — undo is one history step back.
//
// Main image (field:"img") → setMainImageSrc: page.img swaps, every
// size keeps its placement + crop (pixel dimensions are unchanged).
// Baked local image (item.src) → that item's src only.
//
// Cancel leaves the item untouched. Esc = cancel, Enter = Apply.

import { uploadImage } from "../api/upload-asset";
import { HINT_DROP, HINT_KEEP, hasHints, paintDisc, paintStroke, type HintValue } from "../matte-hints";
import { compositeCutout, computeMatte, cutoutFilename, encodeCanvas, outputSize } from "../remove-bg";
import { rewriteForSaliency } from "../saliency";
import { currentPage, setMainImageSrc, updateItem } from "../state";
import type { Store } from "../store";
import type { ImageItem, LayoutItem } from "../types";
import { tokens } from "./tokens";

// Coverage sanity band. Outside it the model almost certainly didn't
// find a subject-on-background photo — warn, but still let the author
// apply (they can see the preview).
const COVERAGE_MIN = 0.02;
const COVERAGE_MAX = 0.98;

// Brush radius in DISPLAY pixels (slider range + default). Converted to
// output pixels per stroke from the canvas' current on-screen scale.
const BRUSH_MIN = 4;
const BRUSH_MAX = 80;
const BRUSH_DEFAULT = 18;
const UNDO_CAP = 20;

// Interactive preview resolution (long edge). Every stroke / toggle
// re-composites, and refineMatte is a per-pixel pass — at the full
// 2000px output cap that is ~3 MP and a second-plus of main-thread
// freeze per stroke on a laptop. 1000px is ~4× cheaper and still finer
// than the stage can display; the full-res composite runs exactly once,
// on Apply. Hints are painted at this resolution and resampled up.
const PREVIEW_EDGE = 1000;
const KEEP_TINT = "rgba(80,220,120,0.45)";
const DROP_TINT = "rgba(240,80,80,0.45)";

// Magnifier: fixed-corner loupe showing the region under the pointer at
// LOUPE_ZOOM × on-screen size (wheel over the preview adjusts it).
const LOUPE_PX = 200;
const LOUPE_ZOOM_DEFAULT = 3;
const LOUPE_ZOOM_MIN = 2;
const LOUPE_ZOOM_MAX = 8;

export function openRemoveBgModal(store: Store, idx: number, item: ImageItem): void {
  const page = currentPage(store.state) as Record<string, unknown> | null;
  const src = item.src ?? (item.field ? (page?.[item.field] as string | undefined) : undefined);
  if (!src) return;
  const existing = document.getElementById("cd-remove-bg-modal");
  if (existing) existing.remove();

  const root = document.createElement("div");
  root.id = "cd-remove-bg-modal";
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
    `font-family: ${tokens.sans}`,
  ].join(";");
  document.body.appendChild(root);

  // Stage — checkerboard so transparency reads as transparency.
  const stage = document.createElement("div");
  stage.style.cssText = [
    "position: relative",
    "max-width: calc(100vw - 160px)",
    "max-height: calc(100vh - 200px)",
    "overflow: hidden",
    "border-radius: 6px",
    `border: 1px solid ${tokens.ink500}`,
    "background-color: #808080",
    "background-image: linear-gradient(45deg,#666 25%,transparent 25%),linear-gradient(-45deg,#666 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#666 75%),linear-gradient(-45deg,transparent 75%,#666 75%)",
    "background-size: 16px 16px",
    "background-position: 0 0,0 8px,8px -8px,-8px 0",
  ].join(";");

  const mediaCss = [
    "display: block",
    "max-width: calc(100vw - 164px)",
    "max-height: calc(100vh - 204px)",
    "user-select: none",
    "-webkit-user-drag: none",
  ].join(";");

  // Source image. CORS-anonymous through the dashboard proxy so the
  // canvas can read pixels (same dance as crop-modal). If that load
  // fails, fall back to a plain load so the author at least sees the
  // image — but pixels are then unreadable and Apply stays disabled.
  const img = document.createElement("img");
  img.crossOrigin = "anonymous";
  img.alt = "";
  img.style.cssText = mediaCss;
  let pixelsReadable = true;
  let triedFallback = false;
  img.addEventListener("error", () => {
    if (triedFallback) return;
    triedFallback = true;
    pixelsReadable = false;
    img.removeAttribute("crossorigin");
    img.src = src;
  });
  stage.appendChild(img);

  // Stroke overlay (tint while painting) + brush cursor. Both sit on the
  // stage's padding box, which the displayed canvas fills exactly.
  const overlay = document.createElement("canvas");
  overlay.style.cssText = "position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;display:none;";
  const cursor = document.createElement("div");
  cursor.style.cssText = "position:absolute;pointer-events:none;border-radius:50%;border:1.5px solid #fff;box-shadow:0 0 0 1px rgba(0,0,0,0.6);display:none;transform:translate(-50%,-50%);";
  const loupe = document.createElement("canvas");
  const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
  loupe.width = LOUPE_PX * dpr;
  loupe.height = LOUPE_PX * dpr;
  loupe.style.cssText = [
    "position:absolute",
    "top:8px",
    "right:8px",
    `width:${LOUPE_PX}px`,
    `height:${LOUPE_PX}px`,
    "pointer-events:none",
    "display:none",
    "border-radius:6px",
    `border:1px solid ${tokens.ink300}`,
    "box-shadow:0 4px 18px rgba(0,0,0,0.6)",
    "background-color:#808080",
    "background-image:linear-gradient(45deg,#666 25%,transparent 25%),linear-gradient(-45deg,#666 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#666 75%),linear-gradient(-45deg,transparent 75%,#666 75%)",
    "background-size:12px 12px",
    "background-position:0 0,0 6px,6px -6px,-6px 0",
  ].join(";");
  stage.append(overlay, cursor, loupe);
  root.append(stage);

  // ── Brush tool row ──
  // Restore = paint "this is subject", Erase = "this is background".
  // Strokes are hints applied before edge refinement, so a rough blob
  // over a paw gets its real edge found by the guided filter.
  const tools = document.createElement("div");
  tools.style.cssText = `display:flex;gap:8px;align-items:center;color:${tokens.ink200};font-size:12px;`;
  let tool: HintValue | null = null;
  const restoreBtn = secondaryBtn("Restore", () => setTool(tool === HINT_KEEP ? null : HINT_KEEP));
  const eraseBtn = secondaryBtn("Erase", () => setTool(tool === HINT_DROP ? null : HINT_DROP));
  restoreBtn.title = "Paint over parts the cut wrongly removed (e.g. feet)";
  eraseBtn.title = "Paint over parts the cut wrongly kept";
  const sizeLabel = document.createElement("label");
  sizeLabel.style.cssText = "display:flex;align-items:center;gap:6px;";
  const sizeInput = document.createElement("input");
  sizeInput.type = "range";
  sizeInput.min = String(BRUSH_MIN);
  sizeInput.max = String(BRUSH_MAX);
  sizeInput.value = String(BRUSH_DEFAULT);
  sizeInput.style.cssText = "width:110px;";
  sizeLabel.append(document.createTextNode("Size"), sizeInput);
  const undoBtn = secondaryBtn("Undo stroke", () => undoStroke());
  const resetBtn = secondaryBtn("Reset", () => resetHints());
  const toolHint = document.createElement("span");
  toolHint.style.cssText = `color:${tokens.ink300};font-size:11px;margin-left:auto;`;
  tools.append(restoreBtn, eraseBtn, sizeLabel, undoBtn, resetBtn, toolHint);
  root.append(tools);
  for (const b of [restoreBtn, eraseBtn, undoBtn, resetBtn]) b.disabled = true;
  sizeInput.disabled = true;

  // Footer.
  const footer = document.createElement("div");
  footer.style.cssText = "display: flex; gap: 8px; align-items: center;";
  const hint = document.createElement("span");
  hint.id = "cd-remove-bg-status";
  hint.style.cssText = `color:${tokens.ink300};font-size:11px;margin-right:auto;font-family:${tokens.sans};max-width:60vw;`;
  hint.textContent = "Loading image…";
  // "Refine edges" — guided-filter pass against the full-res source
  // (matte-refine.ts). On by default; off shows the raw model matte so
  // the author can see what refinement is doing, or escape it when it
  // misfires (busy backgrounds with subject-coloured detail).
  const refineWrap = document.createElement("label");
  refineWrap.style.cssText = `display:flex;align-items:center;gap:6px;color:${tokens.ink200};font-size:12px;cursor:pointer;user-select:none;`;
  const refineBox = document.createElement("input");
  refineBox.type = "checkbox";
  refineBox.checked = true;
  refineBox.disabled = true;
  refineWrap.append(refineBox, document.createTextNode("Refine edges"));
  const compareBtn = secondaryBtn("Hold to compare", () => undefined);
  compareBtn.disabled = true;
  const cancelBtn = secondaryBtn("Cancel", () => void close(false));
  const applyBtn = primaryBtn("Apply", () => void close(true));
  applyBtn.disabled = true;
  footer.append(hint, refineWrap, compareBtn, cancelBtn, applyBtn);
  root.append(footer);

  let matte: Uint8ClampedArray | null = null;
  let result: { canvas: HTMLCanvasElement; coverage: number } | null = null;
  let busy = false;
  // Brush state — hint plane at output resolution, plus an undo stack of
  // pre-stroke snapshots.
  let hints: Uint8Array | null = null;
  let hintDims = { w: 0, h: 0 };
  const undoStack: Uint8Array[] = [];
  let painting = false;
  let last = { x: 0, y: 0 };

  const setTool = (t: HintValue | null): void => {
    tool = t;
    const on = (b: HTMLButtonElement, active: boolean): void => {
      b.style.background = active ? tokens.amber : "transparent";
      b.style.color = active ? "oklch(0.12 0.04 55)" : tokens.ink200;
      b.style.borderColor = active ? tokens.amber : tokens.ink500;
      b.dataset.active = active ? "1" : "";
    };
    on(restoreBtn, t === HINT_KEEP);
    on(eraseBtn, t === HINT_DROP);
    stage.style.cursor = t ? "none" : "";
    cursor.style.display = "none";
    cursor.style.borderColor = t === HINT_DROP ? "#f66" : "#fff";
    toolHint.textContent = t === HINT_KEEP
      ? "Paint over what to keep — the edge snaps inside your stroke."
      : t === HINT_DROP ? "Paint over what to remove." : "";
  };
  const updateUndo = (): void => {
    undoBtn.disabled = undoStack.length === 0;
    resetBtn.disabled = !hints || !hasHints(hints);
  };
  const undoStroke = (): void => {
    const prev = undoStack.pop();
    if (!prev || busy) return;
    hints = prev;
    updateUndo();
    safeRender();
  };
  const resetHints = (): void => {
    if (!hints || busy) return;
    undoStack.length = 0;
    hints.fill(0);
    updateUndo();
    safeRender();
  };

  // ── Loupe ──
  let loupeZoom = LOUPE_ZOOM_DEFAULT;
  let lastClient: { x: number; y: number } | null = null;
  const drawLoupe = (): void => {
    if (!result || !lastClient) { loupe.style.display = "none"; return; }
    const rect = result.canvas.getBoundingClientRect();
    const scale = result.canvas.width / rect.width;
    const px = (lastClient.x - rect.left) * scale;
    const py = (lastClient.y - rect.top) * scale;
    if (px < 0 || py < 0 || px > result.canvas.width || py > result.canvas.height) {
      loupe.style.display = "none";
      return;
    }
    // Source window in output pixels: LOUPE_PX on-screen px ÷ zoom.
    const side = (LOUPE_PX / loupeZoom) * scale;
    const sx = px - side / 2;
    const sy = py - side / 2;
    const ctx = loupe.getContext("2d");
    if (!ctx) return;
    ctx.clearRect(0, 0, loupe.width, loupe.height);
    ctx.imageSmoothingEnabled = loupeZoom < 4; // nearest-neighbour when pixel-peeping
    ctx.drawImage(result.canvas, sx, sy, side, side, 0, 0, loupe.width, loupe.height);
    if (painting) ctx.drawImage(overlay, sx, sy, side, side, 0, 0, loupe.width, loupe.height);
    // Brush ring (when a tool is active) + centre cross-hair.
    const c = loupe.width / 2;
    ctx.lineWidth = dpr;
    if (tool) {
      ctx.strokeStyle = tool === HINT_DROP ? "#f66" : "#fff";
      ctx.beginPath();
      ctx.arc(c, c, brushDisplayRadius() * loupeZoom * dpr, 0, Math.PI * 2);
      ctx.stroke();
    } else {
      ctx.strokeStyle = "rgba(255,255,255,0.8)";
      ctx.beginPath();
      ctx.moveTo(c - 6 * dpr, c); ctx.lineTo(c + 6 * dpr, c);
      ctx.moveTo(c, c - 6 * dpr); ctx.lineTo(c, c + 6 * dpr);
      ctx.stroke();
    }
    // Zoom badge.
    ctx.fillStyle = "rgba(0,0,0,0.55)";
    ctx.fillRect(0, loupe.height - 16 * dpr, 40 * dpr, 16 * dpr);
    ctx.fillStyle = "#fff";
    ctx.font = `${10 * dpr}px ${tokens.sans}`;
    ctx.textBaseline = "middle";
    ctx.fillText(`${loupeZoom}×`, 6 * dpr, loupe.height - 8 * dpr);
    // Keep the loupe out from under the pointer: flip corners when the
    // pointer is inside the loupe's current box.
    const srect = stage.getBoundingClientRect();
    const inRight = lastClient.x > srect.right - LOUPE_PX - 16 && lastClient.y < srect.top + LOUPE_PX + 16;
    loupe.style.right = inRight ? "auto" : "8px";
    loupe.style.left = inRight ? "8px" : "auto";
    loupe.style.display = "block";
  };
  stage.addEventListener("wheel", (e) => {
    if (!result) return;
    e.preventDefault();
    const dir = e.deltaY > 0 ? -1 : 1;
    loupeZoom = Math.max(LOUPE_ZOOM_MIN, Math.min(LOUPE_ZOOM_MAX, loupeZoom + dir));
    drawLoupe();
  }, { passive: false });

  // Pointer → output-pixel coordinates via the displayed canvas' rect.
  const toCanvas = (e: PointerEvent): { x: number; y: number; scale: number } | null => {
    if (!result) return null;
    const rect = result.canvas.getBoundingClientRect();
    if (rect.width === 0) return null;
    const scale = result.canvas.width / rect.width;
    return { x: (e.clientX - rect.left) * scale, y: (e.clientY - rect.top) * scale, scale };
  };
  const brushDisplayRadius = (): number => Number(sizeInput.value);
  const tintStroke = (x0: number, y0: number, x1: number, y1: number, r: number): void => {
    const octx = overlay.getContext("2d");
    if (!octx) return;
    octx.strokeStyle = tool === HINT_DROP ? DROP_TINT : KEEP_TINT;
    octx.lineWidth = r * 2;
    octx.lineCap = "round";
    octx.lineJoin = "round";
    octx.beginPath();
    octx.moveTo(x0, y0);
    octx.lineTo(x1, y1);
    octx.stroke();
  };
  stage.addEventListener("pointermove", (e) => {
    lastClient = { x: e.clientX, y: e.clientY };
    if (!result) return;
    if (!tool) { drawLoupe(); return; }
    const rect = stage.getBoundingClientRect();
    cursor.style.display = "block";
    cursor.style.left = `${e.clientX - rect.left - stage.clientLeft}px`;
    cursor.style.top = `${e.clientY - rect.top - stage.clientTop}px`;
    const d = brushDisplayRadius() * 2;
    cursor.style.width = `${d}px`;
    cursor.style.height = `${d}px`;
    if (!painting || !hints) return;
    const p = toCanvas(e);
    if (!p) return;
    const r = brushDisplayRadius() * p.scale;
    paintStroke(hints, hintDims.w, hintDims.h, last.x, last.y, p.x, p.y, r, tool);
    tintStroke(last.x, last.y, p.x, p.y, r);
    last = { x: p.x, y: p.y };
    drawLoupe();
  });
  // The hover branch above returns before painting; draw the loupe for
  // the tool-active hover case too.
  stage.addEventListener("pointermove", () => { if (tool && !painting) drawLoupe(); });
  stage.addEventListener("pointerleave", () => {
    cursor.style.display = "none";
    lastClient = null;
    loupe.style.display = "none";
  });
  stage.addEventListener("pointerdown", (e) => {
    if (!tool || !hints || !result || busy || e.button !== 0) return;
    const p = toCanvas(e);
    if (!p) return;
    e.preventDefault();
    stage.setPointerCapture(e.pointerId);
    painting = true;
    undoStack.push(Uint8Array.from(hints));
    if (undoStack.length > UNDO_CAP) undoStack.shift();
    const r = brushDisplayRadius() * p.scale;
    paintDisc(hints, hintDims.w, hintDims.h, p.x, p.y, r, tool);
    overlay.style.display = "block";
    tintStroke(p.x, p.y, p.x, p.y, r);
    last = { x: p.x, y: p.y };
  });
  const endStroke = (): void => {
    if (!painting) return;
    painting = false;
    overlay.getContext("2d")?.clearRect(0, 0, overlay.width, overlay.height);
    overlay.style.display = "none";
    updateUndo();
    safeRender();
  };
  stage.addEventListener("pointerup", endStroke);
  stage.addEventListener("pointercancel", endStroke);

  // Hold-to-compare: press shows the original, release shows the cutout.
  const showOriginal = (on: boolean): void => {
    if (!result) return;
    result.canvas.style.display = on ? "none" : "block";
    img.style.display = on ? "block" : "none";
    if (on) loupe.style.display = "none";
  };

  // Composite (or re-composite) from the cached matte and show it.
  const render = (): void => {
    if (!matte) return;
    const t0 = performance.now();
    const next = compositeCutout(img, matte, {
      refine: refineBox.checked,
      hints: hints ? { data: hints, w: hintDims.w, h: hintDims.h } : null,
      maxEdge: PREVIEW_EDGE,
    });
    result?.canvas.remove();
    result = next;
    next.canvas.style.cssText = mediaCss;
    img.style.display = "none";
    // Keep overlay + cursor + loupe above the canvas.
    stage.insertBefore(next.canvas, overlay);
    drawLoupe();
    console.info("[remove-bg] composite refine=%s %dms", refineBox.checked, Math.round(performance.now() - t0));
    const pct = Math.round(next.coverage * 100);
    if (next.coverage < COVERAGE_MIN) {
      hint.textContent = `Almost nothing detected as subject (${pct}% kept) — this image may not have a clear foreground.`;
    } else if (next.coverage > COVERAGE_MAX) {
      hint.textContent = `Nearly everything was kept (${pct}%) — no distinct background found.`;
    } else {
      hint.textContent = `Subject detected (${pct}% of the image kept). Apply uploads the cutout as a new asset; the original stays in your library.`;
    }
  };
  const safeRender = (): void => {
    if (busy) return;
    try { render(); } catch (err) {
      console.warn("[remove-bg] re-composite failed:", err);
    }
  };
  refineBox.addEventListener("change", safeRender);
  compareBtn.addEventListener("pointerdown", () => showOriginal(true));
  compareBtn.addEventListener("pointerup", () => showOriginal(false));
  compareBtn.addEventListener("pointerleave", () => showOriginal(false));

  img.addEventListener("load", () => {
    if (!pixelsReadable) {
      hint.textContent = "Can't read this image's pixels (third-party host) — upload it to the asset library first.";
      return;
    }
    void run();
  });
  img.src = rewriteForSaliency(src);

  async function run(): Promise<void> {
    busy = true;
    hint.textContent = "Detecting subject… (first run downloads the ~5 MB model)";
    try {
      matte = await computeMatte(img);
      hintDims = outputSize(img.naturalWidth, img.naturalHeight, PREVIEW_EDGE);
      hints = new Uint8Array(hintDims.w * hintDims.h);
      overlay.width = hintDims.w;
      overlay.height = hintDims.h;
      render();
      refineBox.disabled = false;
      compareBtn.disabled = false;
      applyBtn.disabled = false;
      restoreBtn.disabled = false;
      eraseBtn.disabled = false;
      sizeInput.disabled = false;
      updateUndo();
    } catch (err) {
      console.warn("[remove-bg] failed:", err);
      hint.textContent = "Background removal failed — see the console. The image is unchanged.";
    } finally {
      busy = false;
    }
  }

  const onKey = (e: KeyboardEvent): void => {
    if (painting) return;
    if (e.key === "Escape") {
      e.preventDefault();
      if (tool) setTool(null); else void close(false); // first Esc drops the brush
    }
    if (e.key === "Enter")  { e.preventDefault(); void close(true); }
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "z") { e.preventDefault(); undoStroke(); }
    if (e.key === "[") sizeInput.value = String(Math.max(BRUSH_MIN, Number(sizeInput.value) - 4));
    if (e.key === "]") sizeInput.value = String(Math.min(BRUSH_MAX, Number(sizeInput.value) + 4));
  };
  window.addEventListener("keydown", onKey);

  async function close(commit: boolean): Promise<void> {
    if (busy) return;
    if (!commit || !result) {
      teardown();
      return;
    }
    busy = true;
    applyBtn.disabled = true;
    cancelBtn.disabled = true;
    hint.textContent = "Rendering full resolution…";
    try {
      // The preview was composited at PREVIEW_EDGE; the asset gets the
      // real output size. Yield a frame first so the status line paints
      // before the synchronous full-res pass blocks the thread.
      await new Promise<void>((r) => requestAnimationFrame(() => r()));
      const t0 = performance.now();
      const full = compositeCutout(img, matte as Uint8ClampedArray, {
        refine: refineBox.checked,
        hints: hints ? { data: hints, w: hintDims.w, h: hintDims.h } : null,
      });
      console.info("[remove-bg] full-res composite %d×%d %dms", full.canvas.width, full.canvas.height, Math.round(performance.now() - t0));
      hint.textContent = "Uploading cutout…";
      const blob = await encodeCanvas(full.canvas);
      const file = new File([blob], cutoutFilename(src as string), { type: "image/webp" });
      const { src: newSrc } = await uploadImage(file);
      applyCutout(store, idx, item, newSrc);
      teardown();
    } catch (err) {
      console.error("[remove-bg] upload failed:", err);
      hint.textContent = "Upload failed — try again.";
      applyBtn.disabled = false;
      cancelBtn.disabled = false;
    } finally {
      busy = false;
    }
  }

  function teardown(): void {
    window.removeEventListener("keydown", onKey);
    root.remove();
  }
}

function applyCutout(store: Store, idx: number, item: ImageItem, src: string): void {
  const state = store.state;
  if (item.field) {
    store.commit(setMainImageSrc(state, src));
    return;
  }
  store.commit(updateItem(state, idx, (it): LayoutItem => (it.type === "image" ? { ...it, src } : it)));
}

// ─── Buttons (same look as crop-modal) ───────────────────────────

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
  // Hover styling skips a button flagged active (the brush tool toggles
  // paint themselves amber via data-active).
  b.addEventListener("mouseenter", () => {
    if (b.dataset.active === "1") return;
    b.style.background = tokens.ink700; b.style.color = tokens.ink100;
  });
  b.addEventListener("mouseleave", () => {
    if (b.dataset.active === "1") return;
    b.style.background = "transparent"; b.style.color = tokens.ink200;
  });
  b.addEventListener("click", onClick);
  return b;
}
