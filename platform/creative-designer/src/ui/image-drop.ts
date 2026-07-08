// Canvas image drag-and-drop. Drop an image file anywhere on the work
// surface and a small chooser asks how to use it: as the page's
// full-bleed Background texture, or as a positioned Image element you
// can move/resize. The chosen route uploads the file (browser-direct R2,
// with a dev-harness data-URL fallback) and commits the result.
//
// Only OS file drags are handled (dataTransfer carries Files). Internal
// HTML5 drags of existing items go through the interaction layer and are
// ignored here.

import { uploadImage } from "../api/upload-asset";
import { addLocalImage, setTextureSrc } from "../state";
import type { Store } from "../store";
import { tokens } from "./tokens";

export function mountImageDrop(target: HTMLElement, store: Store): void {
  // Visual affordance shown while an image file is dragged over the
  // surface. pointer-events:none so the drag/drop events still reach the
  // target underneath; fixed + sized to the target rect on show so it
  // doesn't depend on the target's positioning context.
  const overlay = document.createElement("div");
  overlay.style.cssText = [
    "position: fixed",
    "z-index: 900",
    "display: none",
    "align-items: center",
    "justify-content: center",
    "pointer-events: none",
    `border: 2px dashed ${tokens.amber}`,
    "border-radius: 6px",
    "background: rgba(0,0,0,0.35)",
    `color: ${tokens.ink100}`,
    `font-family: ${tokens.sans}`,
    "font-size: 13px",
  ].join(";");
  overlay.textContent = "Drop image — choose Background texture or Image element";
  document.body.appendChild(overlay);

  const showOverlay = (): void => {
    const r = target.getBoundingClientRect();
    overlay.style.left = `${r.left}px`;
    overlay.style.top = `${r.top}px`;
    overlay.style.width = `${r.width}px`;
    overlay.style.height = `${r.height}px`;
    overlay.style.display = "flex";
  };
  const hideOverlay = (): void => { overlay.style.display = "none"; };

  // dragenter/dragleave fire per child — count depth so the overlay
  // doesn't flicker as the cursor crosses item boundaries.
  let depth = 0;
  const hasFiles = (e: DragEvent): boolean =>
    !!e.dataTransfer && Array.from(e.dataTransfer.types).includes("Files");

  target.addEventListener("dragenter", (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    depth++;
    showOverlay();
  });
  target.addEventListener("dragover", (e) => {
    if (!hasFiles(e)) return;
    // Required so the browser fires a drop event on this element.
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = "copy";
  });
  target.addEventListener("dragleave", (e) => {
    if (!hasFiles(e)) return;
    depth = Math.max(0, depth - 1);
    if (depth === 0) hideOverlay();
  });
  target.addEventListener("drop", (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    depth = 0;
    hideOverlay();
    const file = firstImage(e.dataTransfer);
    if (file) openChooser(file, e.clientX, e.clientY, store);
  });
}

function firstImage(dt: DataTransfer | null): File | null {
  if (!dt) return null;
  for (const f of Array.from(dt.files)) {
    if (f.type.startsWith("image/")) return f;
  }
  return null;
}

// ─── Role chooser ──────────────────────────────────────────────────

function openChooser(file: File, x: number, y: number, store: Store): void {
  document.getElementById("cd-image-drop-chooser")?.remove();

  const objectUrl = URL.createObjectURL(file);
  let settled = false;

  const card = document.createElement("div");
  card.id = "cd-image-drop-chooser";
  card.style.cssText = [
    "position: fixed",
    "z-index: 1000",
    "width: 240px",
    `background: ${tokens.ink800}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 8px",
    "box-shadow: 0 8px 28px rgba(0,0,0,0.5)",
    "padding: 12px",
    `font-family: ${tokens.sans}`,
    `color: ${tokens.ink100}`,
  ].join(";");
  // Clamp to viewport so it never opens off-screen at the canvas edges.
  card.style.left = `${Math.min(x, window.innerWidth - 256)}px`;
  card.style.top = `${Math.min(y, window.innerHeight - 220)}px`;

  const thumb = document.createElement("img");
  thumb.src = objectUrl;
  thumb.style.cssText = `width:100%;height:96px;object-fit:cover;border-radius:4px;background:${tokens.ink900};display:block;margin-bottom:10px;`;
  card.appendChild(thumb);

  const label = document.createElement("div");
  label.textContent = "Use this image as:";
  label.style.cssText = `font-size:11px;color:${tokens.ink300};margin-bottom:8px;`;
  card.appendChild(label);

  const status = document.createElement("div");
  status.style.cssText = `font-size:11px;color:${tokens.ink400};min-height:14px;margin-top:8px;`;

  const cleanup = (): void => {
    URL.revokeObjectURL(objectUrl);
    card.remove();
    document.removeEventListener("keydown", onKey);
    document.removeEventListener("mousedown", onOutside, true);
  };
  const onKey = (e: KeyboardEvent): void => { if (e.key === "Escape" && !settled) cleanup(); };
  const onOutside = (e: MouseEvent): void => {
    if (!settled && !card.contains(e.target as Node)) cleanup();
  };

  const choose = async (route: "texture" | "element"): Promise<void> => {
    if (settled) return;
    settled = true;
    texBtn.disabled = true;
    elBtn.disabled = true;
    status.textContent = "Uploading…";
    try {
      const { src, sizeBytes } = await uploadImage(file);
      if (route === "texture") {
        store.commit(setTextureSrc(store.state, src, sizeBytes));
      } else {
        store.commit(addLocalImage(store.state, src));
      }
      cleanup();
    } catch (err) {
      console.error("[image-drop] upload failed:", err);
      status.textContent = "Upload failed — try again.";
      settled = false;
      texBtn.disabled = false;
      elBtn.disabled = false;
    }
  };

  const texBtn = chooserButton("Background texture", () => void choose("texture"));
  const elBtn = chooserButton("Image element", () => void choose("element"));
  const btns = document.createElement("div");
  btns.style.cssText = "display:flex;flex-direction:column;gap:6px;";
  btns.append(texBtn, elBtn);
  card.appendChild(btns);
  card.appendChild(status);

  document.body.appendChild(card);
  document.addEventListener("keydown", onKey);
  // Capture-phase so a click that dismisses doesn't also hit the canvas.
  document.addEventListener("mousedown", onOutside, true);
}

function chooserButton(text: string, onClick: () => void): HTMLButtonElement {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = text;
  btn.style.cssText = [
    "width: 100%",
    "padding: 8px 10px",
    `background: ${tokens.ink700}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 12px",
    "text-align: left",
  ].join(";");
  btn.addEventListener("mouseenter", () => { btn.style.borderColor = tokens.amber; });
  btn.addEventListener("mouseleave", () => { btn.style.borderColor = tokens.ink500; });
  btn.addEventListener("click", onClick);
  return btn;
}
