// Layout-template picker. Modal grid showing each template as a
// miniature wireframe — picking one replaces the current mode's
// layout wholesale. Sorts by orientation match against the current
// canvas aspect so the most-compatible templates surface first.

import { TEMPLATES, orientationScore, type LayoutTemplate } from "../layout-templates";
import { applyTemplate } from "../state";
import type { Store } from "../store";
import { tokens } from "./tokens";

const MODAL_ID = "cd-template-modal";

export function openTemplateModal(store: Store): void {
  const existing = document.getElementById(MODAL_ID);
  if (existing) existing.remove();

  const overlay = document.createElement("div");
  overlay.id = MODAL_ID;
  overlay.style.cssText = [
    "position: fixed",
    "inset: 0",
    "background: rgba(0,0,0,0.5)",
    "z-index: 999",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    // Mounted on <body>, outside #designer-root — set the UI font here so
    // the dialog matches the rest of the chrome instead of inheriting the
    // document default (the prod shell sets no body font).
    `font-family: ${tokens.sans}`,
  ].join(";");

  const modal = document.createElement("div");
  modal.style.cssText = [
    `background: ${tokens.ink800}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 8px",
    "width: 640px",
    "max-height: 75vh",
    "padding: 16px 18px 18px",
    "display: flex",
    "flex-direction: column",
    "gap: 10px",
    `color: ${tokens.ink100}`,
  ].join(";");

  const header = document.createElement("div");
  header.style.cssText = "display:flex;align-items:center;justify-content:space-between;";
  const title = document.createElement("h3");
  title.textContent = "Apply layout template";
  title.style.cssText = "margin:0;font-size:14px;font-weight:600;";
  const note = document.createElement("span");
  note.textContent = "Replaces this size's layout — Cmd+Z to undo";
  note.style.cssText = `font-size:11px;color:${tokens.ink400};`;
  const close = document.createElement("button");
  close.textContent = "×";
  close.style.cssText = `background:none;border:none;color:${tokens.ink300};font-size:22px;line-height:1;cursor:pointer;padding:0 4px;`;
  close.addEventListener("click", () => overlay.remove());
  header.append(title, note, close);

  const body = document.createElement("div");
  body.style.cssText = "overflow-y:auto;padding-right:4px;";
  const grid = document.createElement("div");
  grid.style.cssText = "display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:10px;";

  // Sort templates by orientation match so the most-compatible
  // templates appear first. Less-compatible ones still render — author
  // can pick anything; they're not hidden.
  const mode = store.state.mode;
  const canvasAspect = mode.h > 0 ? mode.w / mode.h : 1;
  const sorted = TEMPLATES.slice().sort((a, b) =>
    orientationScore(b, canvasAspect) - orientationScore(a, canvasAspect),
  );
  for (const tpl of sorted) {
    grid.appendChild(buildTemplateCard(tpl, canvasAspect, store, () => overlay.remove()));
  }
  body.appendChild(grid);

  modal.append(header, body);
  overlay.appendChild(modal);
  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) overlay.remove();
  });
  document.body.appendChild(overlay);
}

function buildTemplateCard(
  tpl: LayoutTemplate,
  canvasAspect: number,
  store: Store,
  onPick: () => void,
): HTMLElement {
  const card = document.createElement("button");
  card.type = "button";
  card.style.cssText = [
    `background: ${tokens.ink700}`,
    `border: 1px solid ${tokens.ink600}`,
    "border-radius: 6px",
    "padding: 8px",
    "display: flex",
    "flex-direction: column",
    "gap: 6px",
    "cursor: pointer",
    "text-align: left",
    `color: ${tokens.ink100}`,
    "transition: border-color .12s, background .12s",
  ].join(";");
  card.addEventListener("mouseenter", () => {
    card.style.background = tokens.ink600;
    card.style.borderColor = tokens.amber;
  });
  card.addEventListener("mouseleave", () => {
    card.style.background = tokens.ink700;
    card.style.borderColor = tokens.ink600;
  });
  card.addEventListener("click", () => {
    store.commit(applyTemplate(store.state, tpl.items));
    onPick();
  });

  card.append(buildPreview(tpl, canvasAspect), buildLabel(tpl));
  return card;
}

function buildLabel(tpl: LayoutTemplate): HTMLElement {
  const wrap = document.createElement("div");
  const name = document.createElement("div");
  name.textContent = tpl.name;
  name.style.cssText = "font-size:12px;font-weight:600;margin-bottom:2px;";
  const desc = document.createElement("div");
  desc.textContent = tpl.description;
  desc.style.cssText = `font-size:10px;color:${tokens.ink300};line-height:1.3;`;
  wrap.append(name, desc);
  return wrap;
}

// Render the template as an SVG wireframe sized to the current
// canvas's aspect. Each item becomes a grey rect; text shows a tiny
// label so the role of each block is recognisable. Crude but truthful
// — what the author sees here is what they'll get on the canvas.
function buildPreview(tpl: LayoutTemplate, canvasAspect: number): SVGElement {
  // Cap dimensions so a 320×50 banner doesn't make the card 12px tall.
  // Keep aspect honest within reason; clamp extremes.
  const targetW = 160;
  const aspectClamped = Math.min(4, Math.max(0.4, canvasAspect));
  const targetH = Math.max(36, Math.min(120, targetW / aspectClamped));

  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", `0 0 100 100`);
  svg.setAttribute("preserveAspectRatio", "none");
  svg.style.width = `${targetW}px`;
  svg.style.height = `${targetH}px`;
  svg.style.background = "#f5f5f4";
  svg.style.borderRadius = "3px";
  svg.style.display = "block";

  for (const item of tpl.items) {
    const left = item.left ?? 0;
    const top = item.top ?? 0;
    if (item.type === "rect") {
      const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      rect.setAttribute("x", String(left));
      rect.setAttribute("y", String(top));
      rect.setAttribute("width", String(item.width ?? 20));
      rect.setAttribute("height", String(item.height ?? 10));
      rect.setAttribute("fill", item.fill ?? "#9ca3af");
      if (item.borderRadius) rect.setAttribute("rx", String(item.borderRadius / 2));
      if (item.rotation) rect.setAttribute(
        "transform",
        `rotate(${item.rotation} ${left + (item.width ?? 0) / 2} ${top + (item.height ?? 0) / 2})`,
      );
      svg.appendChild(rect);
    } else if (item.type === "text") {
      // Represent text as a thin stroke under the position. Catches
      // scale and placement without trying to render real text at
      // 160px wide (which would be unreadable).
      const r = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      r.setAttribute("x", String(left));
      r.setAttribute("y", String(top));
      const w = item.width ?? 30;
      const h = Math.max(2, (item.fontSize ?? 5) * 0.7);
      r.setAttribute("width", String(w));
      r.setAttribute("height", String(h));
      r.setAttribute("fill", item.color ?? "#374151");
      r.setAttribute("opacity", "0.65");
      svg.appendChild(r);
    } else if (item.type === "circle") {
      const c = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      const r = item.radius ?? 5;
      c.setAttribute("cx", String(left + r));
      c.setAttribute("cy", String(top + r));
      c.setAttribute("r", String(r));
      c.setAttribute("fill", item.fill ?? "#9ca3af");
      svg.appendChild(c);
    }
  }
  return svg;
}
