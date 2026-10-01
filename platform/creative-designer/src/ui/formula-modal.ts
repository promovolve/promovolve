// Formula modal. Type LaTeX, see it rendered live, pick the ink color,
// Insert. The formula is rendered to SVG here (formula.ts) and uploaded
// as an ordinary image; the item keeps `latex`/`latexColor` so this
// modal can re-open it ("Edit formula…" in the props panel).
//
// Esc = cancel, Cmd/Ctrl+Enter = insert.

import { uploadImage } from "../api/upload-asset";
import { pickContrast } from "../color-contrast";
import { loadMathJax, texToSvg, type FormulaSvg } from "../formula";
import { addLocalImage, currentPage, updateItem } from "../state";
import type { Store } from "../store";
import type { ImageItem } from "../types";
import { tokens } from "./tokens";

export function openFormulaModal(store: Store, edit?: { idx: number; item: ImageItem }): void {
  document.getElementById("cd-formula-modal")?.remove();

  const root = document.createElement("div");
  root.id = "cd-formula-modal";
  root.style.cssText = [
    "position: fixed", "inset: 0", "background: rgba(0,0,0,0.88)", "z-index: 200",
    "display: flex", "align-items: center", "justify-content: center",
    `font-family: ${tokens.sans}`,
  ].join(";");

  const card = document.createElement("div");
  card.style.cssText = [
    "width: min(560px, calc(100vw - 32px))", "display: flex", "flex-direction: column", "gap: 12px",
    "padding: 16px", `background: ${tokens.ink800}`, `border: 1px solid ${tokens.ink500}`,
    "border-radius: 6px", `color: ${tokens.ink100}`, "font-size: 12px",
  ].join(";");

  const title = document.createElement("div");
  title.textContent = edit ? "Edit formula" : "Insert formula";
  title.style.cssText = "font-weight: 600; font-size: 13px;";

  const input = document.createElement("textarea");
  input.value = edit?.item.latex ?? "E = mc^2";
  input.spellcheck = false;
  input.rows = 3;
  input.setAttribute("aria-label", "LaTeX");
  input.style.cssText = [
    "width: 100%", "box-sizing: border-box", "resize: vertical", "padding: 8px",
    "font-family: ui-monospace, Menlo, monospace", "font-size: 13px",
    `background: ${tokens.ink900}`, `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`, "border-radius: 4px",
  ].join(";");

  // Preview sits on the page's own background so the ink color reads as
  // it will in the ad.
  const page = currentPage(store.state);
  // An <img>, never innerHTML: it shows exactly what the ad will, and
  // keeps anything a stored formula smuggles in (\href{javascript:…})
  // inert inside the dashboard.
  const preview = document.createElement("div");
  preview.style.cssText = [
    "min-height: 96px", "display: flex", "align-items: center", "justify-content: center",
    "padding: 12px", "overflow: auto", `background: ${page?.bg ?? tokens.ink900}`,
    `border: 1px solid ${tokens.ink500}`, "border-radius: 4px",
  ].join(";");

  const previewImg = document.createElement("img");
  previewImg.alt = "Formula preview";
  previewImg.style.cssText = "display:block;max-width:100%;";
  preview.appendChild(previewImg);

  const colorLabel = document.createElement("label");
  colorLabel.style.cssText = `display:flex;align-items:center;gap:8px;color:${tokens.ink300};font-size:11px;`;
  const color = document.createElement("input");
  color.type = "color";
  color.value = edit?.item.latexColor ?? pickContrast(page?.bg).headline;
  colorLabel.append("color", color);

  const status = document.createElement("span");
  status.setAttribute("role", "status");
  status.style.cssText = `flex:1;font-size:11px;color:${tokens.ink300};`;
  status.textContent = "Loading formula renderer…";

  const cancelBtn = button("Cancel", false, () => root.remove());
  const doneBtn = button(edit ? "Update" : "Insert", true, () => void commit());
  const setDone = (on: boolean): void => {
    doneBtn.disabled = !on;
    doneBtn.style.opacity = on ? "1" : "0.4";
    doneBtn.style.cursor = on ? "pointer" : "default";
  };
  setDone(false);

  const footer = document.createElement("div");
  footer.style.cssText = "display:flex;align-items:center;gap:8px;";
  footer.append(colorLabel, status, cancelBtn, doneBtn);
  card.append(title, input, preview, footer);
  root.appendChild(card);
  document.body.appendChild(root);
  input.focus();

  let rendered: FormulaSvg | null = null;
  let mj: Awaited<ReturnType<typeof loadMathJax>> | null = null;

  const render = (): void => {
    if (!mj) return;
    try {
      rendered = texToSvg(mj, input.value.trim() || "\\,", color.value);
      previewImg.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(rendered.svg)}`;
      status.textContent = "";
      status.style.color = tokens.ink300;
      setDone(!!input.value.trim());
    } catch (e) {
      rendered = null;
      status.textContent = (e as Error).message;
      status.style.color = tokens.err;
      setDone(false);
    }
  };

  const commit = async (): Promise<void> => {
    if (!rendered || doneBtn.disabled) return;
    const { svg, width, height } = rendered;
    const latex = input.value.trim();
    const latexColor = color.value;
    setDone(false);
    status.style.color = tokens.ink300;
    status.textContent = "Uploading…";
    try {
      const file = new File([svg], "formula.svg", { type: "image/svg+xml" });
      const { src } = await uploadImage(file);
      if (edit) {
        // Keep the box's width and position; re-derive height so the
        // new formula keeps its aspect (same math as addLocalImage).
        const { w: cw, h: ch } = store.state.mode;
        store.commit(updateItem(store.state, edit.idx, (it) => ({
          ...it, src, latex, latexColor,
          height: Math.round(((it.width ?? 50) * (cw * height) / (ch * width)) * 10) / 10,
        })));
      } else {
        store.commit(addLocalImage(store.state, src, { w: width, h: height },
          { fillMode: "fit", latex, latexColor }));
      }
      root.remove();
    } catch (e) {
      status.style.color = tokens.err;
      status.textContent = `Upload failed: ${(e as Error).message}`;
      setDone(true);
    }
  };

  input.addEventListener("input", render);
  color.addEventListener("input", render);
  root.addEventListener("keydown", (e) => {
    if (e.key === "Escape") root.remove();
    else if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void commit();
    e.stopPropagation(); // keep designer shortcuts (⌫ delete, T, I…) out of the textarea
  });

  loadMathJax().then(
    (api) => { mj = api; render(); },
    (e: Error) => { status.textContent = e.message; status.style.color = tokens.err; },
  );
}

function button(label: string, primary: boolean, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.style.cssText = [
    "padding: 5px 12px", "border-radius: 4px", "font: inherit", "font-size: 12px", "cursor: pointer",
    primary
      ? `background: ${tokens.amber}; color: oklch(0.12 0.04 55); border: none; font-weight: 600`
      : `background: transparent; color: ${tokens.ink200}; border: 1px solid ${tokens.ink500}`,
  ].join(";");
  b.addEventListener("click", onClick);
  return b;
}
