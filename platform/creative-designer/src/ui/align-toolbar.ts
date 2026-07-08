// Text-alignment toolbar. Sits in the canvas header. Three buttons
// (left / center / right) that set the selected text item's
// `textAlign` property — alignment of text WITHIN its own bounding
// box, the standard "Align" affordance authors recognise from word
// processors. Enabled only when exactly one text item is selected;
// non-text selections, multi-selection, and empty selection all
// fade the buttons out.
//
// The earlier broader version (alignSelection / distributeSelection
// for any item) lives in state.ts and is reachable from outside if
// future panels want it back.

import { currentItem, updateItem } from "../state";
import type { Store } from "../store";
import { tokens } from "./tokens";

type TextAlign = "left" | "center" | "right";

export function mountAlignToolbar(host: HTMLElement, store: Store): void {
  const bar = document.createElement("div");
  bar.className = "cd-tool-group cd-align-group";
  bar.style.cssText = [
    "flex: 0 0 auto",
    "display: flex",
    "flex-direction: row",
    "align-items: center",
    "gap: 2px",
  ].join(";");

  const buttons: Array<{ btn: HTMLButtonElement; align: TextAlign }> = [];

  const addAlign = (icon: string, label: string, align: TextAlign): void => {
    const b = iconBtn(icon, label, () => {
      const item = currentItem(store.state);
      if (!item || item.type !== "text") return;
      const idxs = store.state.selectedItemIdxs;
      if (idxs.length !== 1) return;
      store.commit(updateItem(store.state, idxs[0]!, (it) => ({ ...it, textAlign: align })));
    });
    bar.appendChild(b);
    buttons.push({ btn: b, align });
  };

  addAlign(ICON_ALIGN_LEFT,    "Align text left",   "left");
  addAlign(ICON_ALIGN_HCENTER, "Align text center", "center");
  addAlign(ICON_ALIGN_RIGHT,   "Align text right",  "right");

  const refresh = (): void => {
    const item = currentItem(store.state);
    const enabled = !!item && item.type === "text";
    const activeAlign = enabled ? (item.textAlign ?? "left") : null;
    for (const { btn, align } of buttons) {
      btn.disabled = !enabled;
      btn.style.opacity = enabled ? "1" : "0.35";
      btn.style.cursor = enabled ? "pointer" : "default";
      // Highlight whichever alignment the selected text currently uses.
      const active = enabled && align === activeAlign;
      btn.style.background = active ? tokens.ink700 : "transparent";
      btn.style.color = active ? tokens.ink100 : tokens.ink300;
    }
  };
  refresh();
  store.subscribe(refresh);

  host.appendChild(bar);
}

function iconBtn(iconSvg: string, title: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.title = title;
  b.setAttribute("aria-label", title);
  b.innerHTML = iconSvg;
  b.style.cssText = [
    "width: 30px",
    "height: 30px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    `color: ${tokens.ink300}`,
    "border: 1px solid transparent",
    `border-radius: ${tokens.r6}px`,
    "cursor: pointer",
    "padding: 0",
    "transition: background .12s, color .12s",
  ].join(";");
  b.addEventListener("mouseenter", () => {
    if (b.disabled) return;
    b.style.color = tokens.ink100;
    b.style.background = tokens.ink700;
  });
  b.addEventListener("mouseleave", () => {
    if (b.disabled) return;
    // Don't clobber the active-state styling set by refresh().
  });
  b.addEventListener("click", () => {
    if (b.disabled) return;
    onClick();
  });
  return b;
}

// ─── Icon SVGs (14×14, stroke currentColor) ─────────────────────────
// Lines representing left/centre/right text alignment.

const ICON_ALIGN_LEFT = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><path d="M2 4 L12 4"/><path d="M2 7 L8 7"/><path d="M2 10 L10 10"/></svg>`;
const ICON_ALIGN_HCENTER = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><path d="M2 4 L12 4"/><path d="M4 7 L10 7"/><path d="M3 10 L11 10"/></svg>`;
const ICON_ALIGN_RIGHT = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><path d="M2 4 L12 4"/><path d="M6 7 L12 7"/><path d="M4 10 L12 10"/></svg>`;
