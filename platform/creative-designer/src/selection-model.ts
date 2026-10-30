// What the selection means for the chrome: which actions the selection
// bar offers, and whether the right panel shows banner-wide settings or
// the selected item's values. Pure (no DOM) so it is unit-tested; the
// selection bar, the sidebar and the Layers panel all read from here.

import { isFormula } from "./formula";
import { isMultiPage } from "./modes";
import { currentLayout } from "./state";
import type { DesignerState, LayoutItem } from "./types";

export type SelectionAction =
  | "replace"
  | "crop"
  | "removeBackground"
  | "editFormula"
  | "alignLeft"
  | "alignCenter"
  | "alignRight"
  | "fitToText"
  | "bringToFront"
  | "bringForward"
  | "sendBackward"
  | "sendToBack"
  | "group"
  | "delete";

const ARRANGE: SelectionAction[] = ["bringToFront", "bringForward", "sendBackward", "sendToBack"];

// The selected items of the current layout. Indexes past the end (a
// selection that outlived a delete or a page switch) are skipped.
export function selectedItems(state: DesignerState): LayoutItem[] {
  const layout = currentLayout(state);
  return state.selectedItemIdxs.flatMap((i) => (layout[i] ? [layout[i]!] : []));
}

export function selectionActions(state: DesignerState): SelectionAction[] {
  const items = selectedItems(state);
  if (items.length === 0) return [];
  if (items.length > 1) {
    const gid = items[0]!.groupId;
    const oneGroup = !!gid && items.every((it) => it.groupId === gid);
    return [...ARRANGE, ...(oneGroup ? [] : (["group"] as SelectionAction[])), "delete"];
  }
  const item = items[0]!;
  const own: SelectionAction[] =
    item.type === "text" ? ["alignLeft", "alignCenter", "alignRight", "fitToText"]
    : isFormula(item) ? ["editFormula"]
    : item.type === "image" ? imageActions(state, item)
    : [];
  return [...own, ...ARRANGE, "delete"];
}

// Same rules as the Properties panel had: THE image is set from the
// expanded view (sizes only place and crop it), crop applies only in
// Fill mode, and a baked local image (own src) can be cut out anywhere.
function imageActions(state: DesignerState, item: Extract<LayoutItem, { type: "image" }>): SelectionAction[] {
  const expanded = isMultiPage(state.mode);
  const out: SelectionAction[] = [];
  if (expanded) out.push("replace");
  if (item.fillMode !== "fit") out.push("crop");
  if (expanded || item.src) out.push("removeBackground");
  return out;
}

export function panelScope(state: DesignerState): "banner" | "item" {
  return selectedItems(state).length > 0 ? "item" : "banner";
}

export function scopeLabel(state: DesignerState): string {
  const items = selectedItems(state);
  if (items.length === 0) return `Banner and page ${state.pageIdx + 1}`;
  if (items.length > 1) return `${items.length} items`;
  return `${typeName(items[0]!)} · ${itemName(items[0]!)}`;
}

export function typeName(item: LayoutItem): string {
  if (isFormula(item)) return "Formula";
  if (item.type === "text") return "Text";
  if (item.type === "image") return "Image";
  if (item.type === "rect" && item.scrim) return "Scrim";
  return "Shape";
}

export function itemName(item: LayoutItem): string {
  if (item.type === "text") {
    const t = (item.text ?? item.field ?? "").trim();
    if (!t) return "Text";
    const first = t.split(/\r?\n/, 1)[0]!;
    return first.length > 40 ? first.slice(0, 40) + "…" : first;
  }
  if (item.type === "image") {
    if (item.latex) return item.latex.length > 40 ? item.latex.slice(0, 40) + "…" : item.latex;
    const src = (item.src ?? item.field ?? "").trim();
    if (!src) return "Image";
    const parts = src.split("/");
    return parts[parts.length - 1] || "Image";
  }
  if (item.type === "rect") return "Scrim";
  if (item.type === "circle") return "Circle";
  return "Item";
}
