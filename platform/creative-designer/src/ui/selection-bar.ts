// Selection bar — "what can I do to this?". Sits directly above the
// canvas and shows the selected item's actions in one fixed place, all
// labelled, whatever is selected (selection-model.ts decides which).
// With nothing selected it says so in words instead of vanishing.
//
// Each button calls exactly what the control it replaced called (the
// Properties-panel image/formula buttons, the old align toolbar, the
// right-click arrange menu). The right-click menu still works.
//
// Wraps to a second row when the centre column is narrow: the canvas
// below is flex:1 and yields the height, so no action ever hides.

import { packTextItemHeight } from "../render/canvas";
import { bringForward, bringToFront, groupSelection, sendBackward, sendToBack, updateItem } from "../state";
import { selectedItems, selectionActions, scopeLabel, type SelectionAction } from "../selection-model";
import type { Store } from "../store";
import type { DesignerState, ImageItem } from "../types";
import { openAssetModal } from "./asset-modal";
import { commitDeleteSelection } from "./confirm-delete";
import { openCropModal } from "./crop-modal";
import { openFormulaModal } from "./formula-modal";
import { openRemoveBgModal } from "./remove-bg-modal";
import { tokens } from "./tokens";

const LABEL: Record<SelectionAction, string> = {
  replace: "Replace",
  crop: "Crop",
  removeBackground: "Remove background",
  editFormula: "Edit formula",
  alignLeft: "Align left",
  alignCenter: "Align centre",
  alignRight: "Align right",
  fitToText: "Fit box to text",
  bringToFront: "Bring to front",
  bringForward: "Bring forward",
  sendBackward: "Send backward",
  sendToBack: "Send to back",
  group: "Group",
  delete: "Delete",
};

const ALIGN: Partial<Record<SelectionAction, "left" | "center" | "right">> = {
  alignLeft: "left",
  alignCenter: "center",
  alignRight: "right",
};

// The actions that open an editor for one image need its index and item.
function soleImage(store: Store): { idx: number; item: ImageItem } | null {
  const [item] = selectedItems(store.state);
  const idx = store.state.selectedItemIdxs[0];
  return item?.type === "image" && idx !== undefined ? { idx, item } : null;
}

function run(store: Store, action: SelectionAction): void {
  const s = store.state;
  const align = ALIGN[action];
  if (align) {
    store.commit(updateItem(s, s.selectedItemIdxs[0]!, (it) => ({ ...it, textAlign: align })));
    return;
  }
  switch (action) {
    case "replace": openAssetModal(store); return;
    case "crop": { const img = soleImage(store); if (img) openCropModal(store, img.idx, img.item); return; }
    case "removeBackground": { const img = soleImage(store); if (img) openRemoveBgModal(store, img.idx, img.item); return; }
    case "editFormula": { const img = soleImage(store); if (img) openFormulaModal(store, img); return; }
    case "fitToText": packTextItemHeight(store, s.selectedItemIdxs[0]!, true); return;
    case "bringToFront": store.commit(bringToFront(s)); return;
    case "bringForward": store.commit(bringForward(s)); return;
    case "sendBackward": store.commit(sendBackward(s)); return;
    case "sendToBack": store.commit(sendToBack(s)); return;
    case "group": store.commit(groupSelection(s)); return;
    case "delete": commitDeleteSelection(store); return;
  }
}

export function mountSelectionBar(host: HTMLElement, store: Store): { update(state: DesignerState): void } {
  const bar = document.createElement("div");
  bar.className = "cd-selection-bar";
  bar.setAttribute("role", "toolbar");
  bar.setAttribute("aria-label", "Selection");
  bar.style.cssText = [
    "min-height: 39px", // +1px border = 40 on the pre-zoom grid
    "box-sizing: border-box",
    "padding: 5px 12px",
    "display: flex",
    "flex-wrap: wrap",
    "align-items: center",
    "gap: 6px",
    `background: ${tokens.ink900}`,
    `border-bottom: 1px solid ${tokens.ink600}`,
    "font-size: 11px",
  ].join(";");
  host.appendChild(bar);

  const btn = (action: SelectionAction, active: boolean): HTMLButtonElement => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = LABEL[action];
    b.dataset.action = action;
    const arrange = action.startsWith("bring") || action.startsWith("send") || action === "group";
    const idleBorder = arrange ? "transparent" : tokens.ink100;
    b.style.cssText = [
      "padding: 3px 9px",
      "background: transparent",
      `color: ${active ? tokens.focus : tokens.ink100}`,
      `border: 1px solid ${active ? tokens.focus : idleBorder}`,
      "border-radius: 2px",
      "cursor: pointer",
      "font: inherit",
      "white-space: nowrap",
    ].join(";");
    if (active) b.setAttribute("aria-pressed", "true");
    b.addEventListener("mouseenter", () => { b.style.background = tokens.ink700; });
    b.addEventListener("mouseleave", () => { b.style.background = "transparent"; });
    b.addEventListener("click", () => run(store, action));
    return b;
  };

  const sep = (): HTMLElement => {
    const el = document.createElement("span");
    el.style.cssText = `width: 1px; height: 18px; background: ${tokens.ink400}; margin: 0 2px;`;
    return el;
  };

  return {
    update(state) {
      bar.replaceChildren();
      const actions = selectionActions(state);
      if (actions.length === 0) {
        const empty = document.createElement("span");
        empty.textContent = "Nothing selected. Click an item on the banner, or add one from the left.";
        empty.style.color = tokens.ink300;
        bar.appendChild(empty);
        return;
      }
      const what = document.createElement("span");
      what.dataset.cdSelectionWhat = "";
      what.textContent = scopeLabel(state);
      what.style.cssText = `font-weight: 700; color: ${tokens.ink100}; margin-right: 4px; white-space: nowrap;`;
      bar.appendChild(what);

      const [item] = selectedItems(state);
      const currentAlign = item?.type === "text" ? (item.textAlign ?? "left") : null;
      let prevArrange: boolean | null = null;
      for (const a of actions) {
        if (a === "delete") {
          const spacer = document.createElement("span");
          spacer.style.flex = "1";
          bar.append(spacer, btn(a, false));
          continue;
        }
        const arrange = a.startsWith("bring") || a.startsWith("send") || a === "group";
        if (prevArrange === false && arrange) bar.appendChild(sep());
        prevArrange = arrange;
        bar.appendChild(btn(a, ALIGN[a] !== undefined && ALIGN[a] === currentAlign));
      }
    },
  };
}
