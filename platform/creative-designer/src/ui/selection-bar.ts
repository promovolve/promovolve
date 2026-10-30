// Selection bar — "what can I do to this?". Sits directly above the
// canvas and shows the selected item's actions in one fixed place, all
// labelled, whatever is selected (selection-model.ts decides which).
// With nothing selected it says so in words instead of vanishing.
//
// Each button calls exactly what the control it replaced called (the
// Properties-panel image/formula buttons, the old align toolbar, the
// right-click arrange menu). The right-click menu still works.
//
// Always one line. When the centre column is too narrow, the actions
// that don't fit move into a More menu, least-used first (arrange
// before the type's own actions); Delete always stays on the bar.

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

// The order actions leave the bar for More when space runs out.
const OVERFLOW_ORDER: SelectionAction[] = [
  "sendToBack", "sendBackward", "bringForward", "bringToFront", "group",
  "fitToText", "alignRight", "alignCenter", "alignLeft",
  "removeBackground", "crop", "replace", "editFormula",
];

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
    "height: 40px", // border-box: includes the 1px rule, 40 on the pre-zoom grid
    "box-sizing: border-box",
    "padding: 0 12px",
    "display: flex",
    "align-items: center",
    "gap: 6px",
    // Above the canvas so the More menu drops over it.
    "position: relative",
    "z-index: 5",
    `background: ${tokens.ink900}`,
    `border-bottom: 1px solid ${tokens.ink600}`,
    "font-size: 11px",
  ].join(";");
  host.appendChild(bar);

  const plain = (label: string, border: string, color: string): HTMLButtonElement => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    b.style.cssText = [
      "padding: 3px 9px",
      "background: transparent",
      `color: ${color}`,
      `border: 1px solid ${border}`,
      "border-radius: 2px",
      "cursor: pointer",
      "font: inherit",
      "white-space: nowrap",
      "flex-shrink: 0",
    ].join(";");
    b.addEventListener("mouseenter", () => { b.style.background = tokens.ink700; });
    b.addEventListener("mouseleave", () => { b.style.background = "transparent"; });
    return b;
  };

  const btn = (action: SelectionAction, active: boolean): HTMLButtonElement => {
    const arrange = action.startsWith("bring") || action.startsWith("send") || action === "group";
    const idleBorder = arrange ? "transparent" : tokens.ink100;
    const b = plain(LABEL[action], active ? tokens.focus : idleBorder, active ? tokens.focus : tokens.ink100);
    b.dataset.action = action;
    if (active) b.setAttribute("aria-pressed", "true");
    b.addEventListener("click", () => run(store, action));
    return b;
  };

  const sep = (): HTMLElement => {
    const el = document.createElement("span");
    el.style.cssText = `width: 1px; height: 18px; background: ${tokens.ink400}; margin: 0 2px; flex-shrink: 0;`;
    return el;
  };

  // More: a button plus a menu that drops below it. Rebuilt by fit().
  const moreWrap = document.createElement("span");
  moreWrap.style.cssText = "position: relative; flex-shrink: 0;";
  const more = plain("More ▾", "transparent", tokens.ink100);
  more.setAttribute("aria-haspopup", "menu");
  const menu = document.createElement("div");
  menu.setAttribute("role", "menu");
  menu.style.cssText = [
    "position: absolute",
    "top: calc(100% + 4px)",
    "left: 0",
    "display: none",
    "flex-direction: column",
    "min-width: 140px",
    "padding: 4px 0",
    `background: ${tokens.ink900}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 2px",
    "box-shadow: 0 4px 12px rgba(0,0,0,0.18)",
  ].join(";");
  moreWrap.append(more, menu);

  const closeMenu = (): void => {
    menu.style.display = "none";
    more.setAttribute("aria-expanded", "false");
    document.removeEventListener("pointerdown", onOutside, true);
    document.removeEventListener("keydown", onEsc, true);
  };
  const onOutside = (e: Event): void => { if (!moreWrap.contains(e.target as Node)) closeMenu(); };
  const onEsc = (e: KeyboardEvent): void => { if (e.key === "Escape") closeMenu(); };
  more.addEventListener("click", () => {
    if (menu.style.display === "flex") { closeMenu(); return; }
    menu.style.display = "flex";
    more.setAttribute("aria-expanded", "true");
    document.addEventListener("pointerdown", onOutside, true);
    document.addEventListener("keydown", onEsc, true);
  });

  let shown: { action: SelectionAction; el: HTMLButtonElement }[] = [];
  let divider: HTMLElement | null = null;

  // Move actions into More, in OVERFLOW_ORDER, until the bar fits.
  const fit = (): void => {
    closeMenu();
    for (const { el } of shown) el.style.display = "";
    if (divider) divider.style.display = "";
    moreWrap.style.display = "none";
    const hidden = new Set<SelectionAction>();
    for (const a of OVERFLOW_ORDER) {
      if (bar.scrollWidth <= bar.clientWidth) break;
      const entry = shown.find((x) => x.action === a);
      if (!entry) continue;
      entry.el.style.display = "none";
      hidden.add(a);
      moreWrap.style.display = "";
    }
    const anyArrangeLeft = shown.some(({ action, el }) =>
      el.style.display !== "none" && (action.startsWith("bring") || action.startsWith("send") || action === "group"));
    if (divider && !anyArrangeLeft) divider.style.display = "none";
    menu.replaceChildren(...shown.filter(({ action }) => hidden.has(action)).map(({ action }) => {
      const item = document.createElement("button");
      item.type = "button";
      item.setAttribute("role", "menuitem");
      item.textContent = LABEL[action];
      item.style.cssText = [
        "padding: 5px 12px",
        "text-align: left",
        "background: transparent",
        "border: none",
        `color: ${tokens.ink100}`,
        "font: inherit",
        "white-space: nowrap",
        "cursor: pointer",
      ].join(";");
      item.addEventListener("mouseenter", () => { item.style.background = tokens.ink700; });
      item.addEventListener("mouseleave", () => { item.style.background = "transparent"; });
      item.addEventListener("click", () => { closeMenu(); run(store, action); });
      return item;
    }));
  };
  new ResizeObserver(fit).observe(bar);

  return {
    update(state) {
      closeMenu();
      bar.replaceChildren();
      shown = [];
      divider = null;
      const actions = selectionActions(state);
      if (actions.length === 0) {
        const empty = document.createElement("span");
        empty.textContent = "Nothing selected. Click an item on the banner, or add one from the left.";
        empty.style.cssText = `color: ${tokens.ink300}; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;`;
        bar.appendChild(empty);
        return;
      }
      const what = document.createElement("span");
      what.dataset.cdSelectionWhat = "";
      what.textContent = scopeLabel(state);
      what.title = what.textContent;
      what.style.cssText = [
        "font-weight: 700",
        `color: ${tokens.ink100}`,
        "margin-right: 4px",
        "white-space: nowrap",
        "overflow: hidden",
        "text-overflow: ellipsis",
        "max-width: 160px",
        "flex-shrink: 0",
      ].join(";");
      bar.appendChild(what);

      const [item] = selectedItems(state);
      const currentAlign = item?.type === "text" ? (item.textAlign ?? "left") : null;
      let prevArrange: boolean | null = null;
      for (const a of actions) {
        if (a === "delete") {
          const spacer = document.createElement("span");
          spacer.style.flex = "1";
          bar.append(moreWrap, spacer, btn(a, false));
          continue;
        }
        const arrange = a.startsWith("bring") || a.startsWith("send") || a === "group";
        if (prevArrange === false && arrange) {
          divider = sep();
          bar.appendChild(divider);
        }
        prevArrange = arrange;
        const el = btn(a, ALIGN[a] !== undefined && ALIGN[a] === currentAlign);
        shown.push({ action: a, el });
        bar.appendChild(el);
      }
      fit();
    },
  };
}
