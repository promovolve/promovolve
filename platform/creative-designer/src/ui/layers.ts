// Layers panel — docked beneath the sidebar's tabbed section. Lists
// the current page's layout items top-of-stack first (reversed from
// state array order, because later-added items render on top in the
// canvas but read more naturally at the top of a Figma-style list).
//
// Click a row → single-select. Shift-click → toggle selection member-
// ship. Drag-to-reorder is deferred to a later PR — the existing
// `reorderItem` state action is the hookup point, this PR just lays
// out the rows and wires click selection.
//
// Each row shows:
//   - A 12px type icon (text / image / rect / circle) — amber when
//     selected, ink300 otherwise.
//   - The item's name, ellipsised. Derived from content (text item
//     uses its first line, image uses filename from src, rect/circle
//     fall back to the type name).
//   - Lock + visibility toggle icons (ink400). These read the new
//     `locked` / `hidden` fields on LayoutItem and toggle them via
//     `updateItem`. Functional in this PR.

import { currentLayout, currentPage, isSelected, reorderItem, selectItem, setVideoBg, toggleSelect, updateItem } from "../state";
import type { Store } from "../store";
import type { DesignerState, LayoutItem } from "../types";
import { tokens } from "./tokens";

export interface LayersHandle {
  update(state: DesignerState): void;
}

export function mountLayers(host: HTMLElement, store: Store): LayersHandle {
  // The sidebar owns the collapsible "Layers" section header + caret
  // (makeSection in ui/sidebar.ts), so this renders flat into the
  // group's body. The item count lives in that header's .cd-group-count
  // span — found by walking up to the enclosing .cd-group.
  host.style.cssText = "padding-bottom:10px;display:flex;flex-direction:column;max-height:50vh;min-height:0;";
  const count = host.closest(".cd-group")?.querySelector<HTMLElement>(".cd-group-count") ?? null;

  // Scrollable list of rows.
  const list = document.createElement("div");
  list.style.cssText = "flex: 1 1 auto; overflow: auto; padding: 4px;";
  host.appendChild(list);

  const empty = document.createElement("div");
  empty.textContent = "No layers on this page.";
  empty.style.cssText = [
    "padding: 16px 12px",
    "text-align: center",
    `color: ${tokens.ink400}`,
    "font-size: 11px",
    "display: none",
  ].join(";");
  host.appendChild(empty);

  // Fixed video-bg footer — always below the scroll list, non-
  // draggable, not part of currentLayout. Stays in the DOM even when
  // no video is set (display:none). Pinned below `list` so the scroll
  // region doesn't push it off-screen.
  const videoBgRow = buildVideoBgRow(store);
  host.appendChild(videoBgRow.el);

  const handle: LayersHandle = {
    update(state) {
      const items = currentLayout(state);
      const page = currentPage(state);
      const hasVideoBg = !!page?.videoBg;
      // No badge at zero — the body's "No layers on this page." carries
      // the empty state; a bare "0" next to the title read as a bug.
      if (count) count.textContent = items.length > 0 ? String(items.length) : "";
      list.innerHTML = "";
      if (items.length === 0 && !hasVideoBg) {
        empty.style.display = "";
      } else {
        empty.style.display = "none";
        // Render in reverse — last item in array is drawn on top of
        // canvas, so it's the top-most "layer" and should appear first
        // in the list.
        for (let i = items.length - 1; i >= 0; i--) {
          list.appendChild(renderRow(items[i]!, i, state, store));
        }
      }
      videoBgRow.update(state);
    },
  };
  return handle;
}

// ─── Video background footer row ──────────────────────────────────

function buildVideoBgRow(store: Store): { el: HTMLElement; update: (state: DesignerState) => void } {
  const el = document.createElement("div");
  el.style.cssText = [
    "display: none",
    "align-items: center",
    "gap: 8px",
    "padding: 6px 12px",
    `border-top: 1px solid ${tokens.ink600}`,
    `background: ${tokens.ink800}`,
    "flex: 0 0 auto",
    "user-select: none",
  ].join(";");

  const iconBox = document.createElement("span");
  iconBox.style.cssText = [
    "flex: 0 0 auto",
    "width: 12px",
    "height: 12px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    `color: ${tokens.ink400}`,
  ].join(";");
  iconBox.innerHTML = ICON_VIDEO;
  el.appendChild(iconBox);

  const name = document.createElement("span");
  name.style.cssText = [
    "flex: 1 1 auto",
    "min-width: 0",
    "overflow: hidden",
    "text-overflow: ellipsis",
    "white-space: nowrap",
    "font-size: 11px",
    `color: ${tokens.ink400}`,
    "font-style: italic",
  ].join(";");
  el.appendChild(name);

  // Remember the last non-zero opacity so the eye toggle can restore
  // the user's previous value instead of flipping back to full opacity.
  let lastOpacity = 1;

  const eye = document.createElement("button");
  eye.type = "button";
  eye.style.cssText = [
    "flex: 0 0 auto",
    "width: 18px",
    "height: 18px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    "border: none",
    "padding: 0",
    "cursor: pointer",
    `color: ${tokens.ink400}`,
    "transition: color .12s",
  ].join(";");
  eye.addEventListener("click", (e) => {
    e.stopPropagation();
    const page = currentPage(store.state);
    const bg = page?.videoBg;
    if (!bg) return;
    const current = bg.opacity ?? 1;
    if (current > 0) {
      lastOpacity = current;
      store.commit(setVideoBg(store.state, { ...bg, opacity: 0 }));
    } else {
      store.commit(setVideoBg(store.state, { ...bg, opacity: lastOpacity || 1 }));
    }
  });
  el.appendChild(eye);

  function update(state: DesignerState): void {
    const page = currentPage(state);
    const bg = page?.videoBg;
    if (!bg) {
      el.style.display = "none";
      return;
    }
    el.style.display = "flex";
    name.textContent = `Video background · ${fileNameFromUrl(bg.src)}`;
    const visible = (bg.opacity ?? 1) > 0;
    eye.innerHTML = visible ? ICON_EYE : ICON_EYE_OFF;
    eye.title = visible ? "Hide background video" : "Show background video";
    if (visible && (bg.opacity ?? 1) > 0) lastOpacity = bg.opacity ?? 1;
  }

  return { el, update };
}

function fileNameFromUrl(url: string): string {
  try {
    const parts = new URL(url, window.location.origin).pathname.split("/").filter(Boolean);
    return parts[parts.length - 1] || url;
  } catch {
    return url;
  }
}

function renderRow(item: LayoutItem, idx: number, state: DesignerState, store: Store): HTMLElement {
  const selected = isSelected(state, idx);
  const row = document.createElement("div");
  row.dataset.layerIdx = String(idx);
  row.draggable = true;
  row.setAttribute("role", "button");
  row.style.cssText = [
    "display: flex",
    "align-items: center",
    "gap: 8px",
    "padding: 5px 8px",
    "border-radius: 4px",
    "cursor: pointer",
    "border: 1px solid transparent",
    `background: ${selected ? tokens.amberBg : "transparent"}`,
    `color: ${selected ? tokens.ink100 : tokens.ink200}`,
    `border-color: ${selected ? tokens.amberMuted : "transparent"}`,
    "transition: background .12s, color .12s, border-color .12s",
    "user-select: none",
  ].join(";");
  row.addEventListener("mouseenter", () => {
    if (!isSelected(store.state, idx)) {
      row.style.background = tokens.ink700;
    }
  });
  row.addEventListener("mouseleave", () => {
    if (!isSelected(store.state, idx)) {
      row.style.background = "transparent";
    }
  });
  wireRowDrag(row, idx, store);

  // Type icon — 12px SVG. Colored by selection state. Using a small
  // inline SVG lets the icon match text color via currentColor.
  const iconBox = document.createElement("span");
  iconBox.style.cssText = [
    "flex: 0 0 auto",
    "width: 12px",
    "height: 12px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    `color: ${selected ? tokens.amber : tokens.ink300}`,
  ].join(";");
  iconBox.innerHTML = typeIcon(item.type);
  row.appendChild(iconBox);

  // Name
  const name = document.createElement("span");
  name.textContent = itemName(item);
  name.style.cssText = [
    "flex: 1 1 auto",
    "min-width: 0",
    "overflow: hidden",
    "text-overflow: ellipsis",
    "white-space: nowrap",
    "font-size: 12px",
  ].join(";");
  row.appendChild(name);

  // Lock toggle
  const lock = iconBtn("lock", !!item.locked, (e) => {
    e.stopPropagation();
    store.commit(updateItem(store.state, idx, (it) => ({ ...it, locked: !it.locked })));
  });
  row.appendChild(lock);

  // Visibility toggle
  const eye = iconBtn("eye", !item.hidden, (e) => {
    e.stopPropagation();
    store.commit(updateItem(store.state, idx, (it) => ({ ...it, hidden: !it.hidden })));
  });
  row.appendChild(eye);

  row.addEventListener("click", (e) => {
    if (e.shiftKey) {
      store.commit(toggleSelect(store.state, idx));
    } else {
      store.replace(selectItem(store.state, idx));
    }
  });

  return row;
}

// ─── Drag-to-reorder ────────────────────────────────────────────────
//
// Module-scoped because HTML5 drag-and-drop is single-source-at-a-time
// (one drag in flight per browser tab). The source-idx survives across
// the dragstart → dragover → drop → dragend lifecycle on different
// elements without needing per-row closures, and it auto-clears on
// dragend so an aborted drag (Esc / dropped outside) leaves no
// dangling indicator.
let dragSourceIdx: number | null = null;
let dragSourceRow: HTMLElement | null = null;
let dragHoverRow: HTMLElement | null = null;

function wireRowDrag(row: HTMLElement, idx: number, store: Store): void {
  // Don't initiate a drag from the lock/eye icon buttons — those are
  // their own click targets and dragging from them would be surprising.
  // We attach dragstart on the row but cancel it if the underlying
  // mousedown target was a child button.
  row.addEventListener("mousedown", (e) => {
    const t = e.target as HTMLElement | null;
    if (t?.closest("button") && t.closest("button") !== row) {
      row.draggable = false;
      // Restore for the next interaction.
      setTimeout(() => { row.draggable = true; }, 0);
    }
  });

  row.addEventListener("dragstart", (e) => {
    dragSourceIdx = idx;
    dragSourceRow = row;
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = "move";
      // Firefox requires a non-empty payload to actually start the drag.
      e.dataTransfer.setData("text/plain", String(idx));
    }
    row.style.opacity = "0.4";
  });

  row.addEventListener("dragend", () => {
    if (dragSourceRow) dragSourceRow.style.opacity = "";
    clearIndicator(dragHoverRow);
    dragSourceIdx = null;
    dragSourceRow = null;
    dragHoverRow  = null;
  });

  row.addEventListener("dragover", (e) => {
    if (dragSourceIdx === null || dragSourceIdx === idx) return;
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = "move";
    const rect = row.getBoundingClientRect();
    const topHalf = (e.clientY - rect.top) < rect.height / 2;
    setIndicator(row, topHalf);
    if (dragHoverRow && dragHoverRow !== row) clearIndicator(dragHoverRow);
    dragHoverRow = row;
  });

  row.addEventListener("dragleave", (e) => {
    // dragleave fires when crossing over children too. Only clear when
    // the cursor actually leaves the row element.
    const related = (e as DragEvent).relatedTarget as Node | null;
    if (related && row.contains(related)) return;
    if (dragHoverRow === row) {
      clearIndicator(row);
      dragHoverRow = null;
    }
  });

  row.addEventListener("drop", (e) => {
    e.preventDefault();
    if (dragSourceIdx === null || dragSourceIdx === idx) return;
    const rect = row.getBoundingClientRect();
    const topHalf = (e.clientY - rect.top) < rect.height / 2;
    const to = computeDropTarget(dragSourceIdx, idx, topHalf);
    clearIndicator(row);
    if (to !== null) {
      store.commit(reorderItem(store.state, dragSourceIdx, to));
    }
  });
}

// Map (from, hoverIdx, topHalf) → final array index for the moved item.
// The visual list renders rows in reverse — top of the list is the
// highest array index. Cases derived from "moved item appears
// immediately above/below the hover row visually after the drop":
//   from <  h, top:    to = h
//   from <  h, bottom: to = h - 1
//   from >  h, top:    to = h + 1
//   from >  h, bottom: to = h
//   from === h:        no-op
function computeDropTarget(from: number, hoverIdx: number, topHalf: boolean): number | null {
  if (from === hoverIdx) return null;
  if (from < hoverIdx)  return topHalf ? hoverIdx     : hoverIdx - 1;
  /* from > hoverIdx */ return topHalf ? hoverIdx + 1 : hoverIdx;
}

function setIndicator(row: HTMLElement, topHalf: boolean): void {
  row.style.boxShadow = topHalf
    ? `inset 0 2px 0 0 ${tokens.amber}`
    : `inset 0 -2px 0 0 ${tokens.amber}`;
}

function clearIndicator(row: HTMLElement | null): void {
  if (row) row.style.boxShadow = "";
}

function iconBtn(
  kind: "lock" | "eye",
  active: boolean,
  onClick: (e: MouseEvent) => void,
): HTMLElement {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.title = kind === "lock"
    ? (active ? "Unlock" : "Lock")
    : (active ? "Hide" : "Show");
  btn.style.cssText = [
    "flex: 0 0 auto",
    "width: 18px",
    "height: 18px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    "border: none",
    "padding: 0",
    "cursor: pointer",
    // Active = feature is "on" (item is locked, or item is visible).
    // Show at ink200 when active; muted when inactive so the
    // "default" visibility/unlocked state isn't visually loud.
    `color: ${active ? tokens.ink200 : tokens.ink500}`,
    "transition: color .12s",
  ].join(";");
  btn.innerHTML = kind === "lock" ? (active ? ICON_LOCK : ICON_UNLOCK)
                                  : (active ? ICON_EYE  : ICON_EYE_OFF);
  btn.addEventListener("click", onClick);
  btn.addEventListener("mouseenter", () => { btn.style.color = tokens.ink100; });
  btn.addEventListener("mouseleave", () => {
    btn.style.color = active ? tokens.ink200 : tokens.ink500;
  });
  return btn;
}

function itemName(item: LayoutItem): string {
  if (item.type === "text") {
    const t = (item.text ?? item.field ?? "").trim();
    if (!t) return "Text";
    const first = t.split(/\r?\n/, 1)[0]!;
    return first.length > 40 ? first.slice(0, 40) + "…" : first;
  }
  if (item.type === "image") {
    const src = (item.src ?? item.field ?? "").trim();
    if (!src) return "Image";
    const parts = src.split("/");
    return parts[parts.length - 1] || "Image";
  }
  if (item.type === "rect") return "Rect";
  if (item.type === "circle") return "Circle";
  return "Item";
}

function typeIcon(type: string): string {
  switch (type) {
    case "text":   return ICON_TEXT;
    case "image":  return ICON_IMAGE;
    case "rect":   return ICON_RECT;
    case "circle": return ICON_CIRCLE;
    default:       return ICON_RECT;
  }
}

// ─── Icon SVGs ──────────────────────────────────────────────────
// All 12×12, stroked in currentColor so row color flows through.

const ICON_TEXT = `<svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"><path d="M2 3h8"/><path d="M6 3v6"/></svg>`;
const ICON_IMAGE = `<svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="1.5" y="2" width="9" height="8" rx="1"/><circle cx="4.3" cy="4.8" r=".9"/><path d="M2 9l2.5-2.5 2 2L8 6l2 2"/></svg>`;
const ICON_RECT = `<svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="2" y="3" width="8" height="6" rx=".5"/></svg>`;
const ICON_CIRCLE = `<svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.2"><circle cx="6" cy="6" r="3.8"/></svg>`;

const ICON_LOCK = `<svg viewBox="0 0 12 12" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="2.5" y="5.5" width="7" height="5" rx=".8"/><path d="M4 5.5V4a2 2 0 0 1 4 0v1.5"/></svg>`;
const ICON_UNLOCK = `<svg viewBox="0 0 12 12" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="2.5" y="5.5" width="7" height="5" rx=".8"/><path d="M4 5.5V4a2 2 0 0 1 3.8-.7"/></svg>`;
const ICON_EYE = `<svg viewBox="0 0 12 12" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.2"><path d="M1.5 6c1.3-2 3-3 4.5-3s3.2 1 4.5 3c-1.3 2-3 3-4.5 3s-3.2-1-4.5-3z"/><circle cx="6" cy="6" r="1.3"/></svg>`;
const ICON_EYE_OFF = `<svg viewBox="0 0 12 12" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"><path d="M1.5 6c1.3-2 3-3 4.5-3 .8 0 1.5.2 2.2.6"/><path d="M10.5 6c-1.3 2-3 3-4.5 3-.8 0-1.5-.2-2.2-.6"/><path d="M2 2l8 8"/></svg>`;
const ICON_VIDEO = `<svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.2"><rect x="1.5" y="3" width="7" height="6" rx=".8"/><path d="M8.5 5l2-1v4l-2-1z" fill="currentColor" stroke="none"/></svg>`;
