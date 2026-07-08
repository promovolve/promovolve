// Window-level keyboard shortcuts for the designer.
//
// - Cmd/Ctrl+Z            → undo
// - Cmd/Ctrl+Shift+Z      → redo (also Ctrl+Y on non-Mac)
// - Delete / Backspace    → delete selected item
// - Escape                → deselect
// - Arrow keys            → nudge selected item by 1%
// - Shift + Arrow         → nudge by 5%
//
// Shortcuts suspend when the user is typing in an editable control
// (input/textarea/contenteditable) so typing names or text values
// doesn't eat arrow keys.

import { clamp } from "../math";
import {
  bringForward,
  bringToFront,
  currentLayout,
  deleteSelection,
  groupSelection,
  hasSelection,
  selectItem,
  sendBackward,
  sendToBack,
  ungroupSelection,
  updateItem,
} from "../state";
import type { Store } from "../store";
import type { DesignerState } from "../types";
import { copy, duplicate, paste } from "./clipboard";

const NUDGE = 1;        // percent per arrow key
const NUDGE_LARGE = 5;  // percent per Shift+arrow

export function installKeyboard(store: Store): () => void {
  const onKey = (e: KeyboardEvent): void => {
    if (isTypingTarget(e.target)) return;

    const mod = e.metaKey || e.ctrlKey;

    // Undo / redo
    if (mod && (e.key === "z" || e.key === "Z")) {
      e.preventDefault();
      if (e.shiftKey) store.redo();
      else store.undo();
      return;
    }
    if (mod && (e.key === "y" || e.key === "Y")) {
      e.preventDefault();
      store.redo();
      return;
    }

    // Copy / paste / duplicate
    if (mod && (e.key === "c" || e.key === "C")) {
      if (copy(store)) e.preventDefault();
      return;
    }
    if (mod && (e.key === "v" || e.key === "V")) {
      if (paste(store)) e.preventDefault();
      return;
    }
    if (mod && (e.key === "d" || e.key === "D")) {
      if (duplicate(store)) e.preventDefault();
      return;
    }

    // Group / ungroup — Figma convention.
    //   Cmd/Ctrl+G        → group selection
    //   Cmd/Ctrl+Shift+G  → ungroup selection
    if (mod && (e.key === "g" || e.key === "G")) {
      e.preventDefault();
      if (e.shiftKey) store.commit(ungroupSelection(store.state));
      else store.commit(groupSelection(store.state));
      return;
    }

    // Z-order: Figma convention.
    //   `]` / `[`               → bring forward / send backward (one step)
    //   `}` / `{` (Shift+]/[)   → bring to front / send to back
    if (e.key === "]" && hasSelection(store.state)) {
      e.preventDefault();
      store.commit(bringForward(store.state));
      return;
    }
    if (e.key === "[" && hasSelection(store.state)) {
      e.preventDefault();
      store.commit(sendBackward(store.state));
      return;
    }
    if (e.key === "}" && hasSelection(store.state)) {
      e.preventDefault();
      store.commit(bringToFront(store.state));
      return;
    }
    if (e.key === "{" && hasSelection(store.state)) {
      e.preventDefault();
      store.commit(sendToBack(store.state));
      return;
    }

    if (e.key === "Escape") {
      if (hasSelection(store.state)) store.replace(selectItem(store.state, null));
      return;
    }

    if ((e.key === "Delete" || e.key === "Backspace") && hasSelection(store.state)) {
      e.preventDefault();
      store.commit(deleteSelection(store.state));
      return;
    }

    // Arrow-key nudge — applies to every selected item so multi-
    // selections stay aligned after the keystroke.
    if (!hasSelection(store.state)) return;
    const d = arrowDelta(e);
    if (!d) return;
    e.preventDefault();
    const step = e.shiftKey ? NUDGE_LARGE : NUDGE;
    let next: DesignerState = store.state;
    let moved = false;
    for (const idx of store.state.selectedItemIdxs) {
      next = updateItem(next, idx, (it) => ({
        ...it,
        left: clamp(round1((it.left ?? 0) + d.dx * step), -50, 150),
        top: clamp(round1((it.top ?? 0) + d.dy * step), -50, 150),
      }));
      moved = true;
    }
    if (moved && next !== store.state) store.commit(next);
  };

  window.addEventListener("keydown", onKey);
  return () => window.removeEventListener("keydown", onKey);
}

function arrowDelta(e: KeyboardEvent): { dx: number; dy: number } | null {
  switch (e.key) {
    case "ArrowLeft":  return { dx: -1, dy:  0 };
    case "ArrowRight": return { dx:  1, dy:  0 };
    case "ArrowUp":    return { dx:  0, dy: -1 };
    case "ArrowDown":  return { dx:  0, dy:  1 };
    default:           return null;
  }
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
  return target.isContentEditable;
}

function round1(v: number): number {
  return Math.round(v * 10) / 10;
}

// Referenced by typecheck so currentLayout import stays useful if the
// keyboard handler grows to use it later.
void currentLayout;
