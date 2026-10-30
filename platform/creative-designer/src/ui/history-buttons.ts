// Undo / Redo buttons for the header. Click or Cmd+Z keyboard shortcut
// both work; these just make the capability visible. Disabled state
// (low opacity + no-events cursor) reflects store.canUndo / canRedo.

import type { Store } from "../store";
import { tokens } from "./tokens";

export function mountHistoryButtons(container: HTMLElement, store: Store): void {
  const undo = makeBtn("↶", "Undo", "Undo (⌘Z)", () => store.undo());
  const redo = makeBtn("↷", "Redo", "Redo (⌘⇧Z)", () => store.redo());

  const sync = (): void => {
    setEnabled(undo, store.canUndo());
    setEnabled(redo, store.canRedo());
  };

  container.append(undo, redo);
  sync();
  store.subscribe(sync);
}

function makeBtn(icon: string, word: string, title: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  // The word drops when the top bar runs short (see menu-bar.ts).
  const w = document.createElement("span");
  w.textContent = ` ${word}`;
  w.dataset.cdCompact = "2";
  w.style.whiteSpace = "pre";
  b.append(icon, w);
  b.title = title;
  b.setAttribute("aria-label", title);
  // Ghost/Icon style: 28×28, transparent until hover. Matches the
  // direction-B prototype's IconBtn primitive.
  b.style.cssText = [
    "height: 26px",
    "padding: 0 6px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    `color: ${tokens.ink300}`,
    "border: 1px solid transparent",
    "border-radius: 2px",
    "font: inherit",
    "font-size: 11px",
    "white-space: nowrap",
    "cursor: pointer",
    "transition: background .12s, color .12s, border-color .12s",
  ].join(";");
  b.addEventListener("mouseenter", () => {
    if (!b.disabled) {
      b.style.color = tokens.ink100;
      b.style.background = tokens.ink700;
    }
  });
  b.addEventListener("mouseleave", () => {
    if (!b.disabled) {
      b.style.color = tokens.ink300;
      b.style.background = "transparent";
    }
  });
  b.addEventListener("click", onClick);
  return b;
}

function setEnabled(btn: HTMLButtonElement, enabled: boolean): void {
  btn.disabled = !enabled;
  btn.style.opacity = enabled ? "1" : "0.35";
  btn.style.cursor = enabled ? "pointer" : "default";
}
