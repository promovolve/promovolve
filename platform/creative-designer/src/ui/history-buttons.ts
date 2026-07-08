// Undo / Redo buttons for the header. Click or Cmd+Z keyboard shortcut
// both work; these just make the capability visible. Disabled state
// (low opacity + no-events cursor) reflects store.canUndo / canRedo.

import type { Store } from "../store";
import { tokens } from "./tokens";

export function mountHistoryButtons(container: HTMLElement, store: Store): void {
  const undo = makeBtn("↶", "Undo (⌘Z)", () => store.undo());
  const redo = makeBtn("↷", "Redo (⌘⇧Z)", () => store.redo());

  const sync = (): void => {
    setEnabled(undo, store.canUndo());
    setEnabled(redo, store.canRedo());
  };

  container.append(undo, redo);
  sync();
  store.subscribe(sync);
}

function makeBtn(label: string, title: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.title = title;
  b.setAttribute("aria-label", title);
  // Ghost/Icon style: 28×28, transparent until hover. Matches the
  // direction-B prototype's IconBtn primitive.
  b.style.cssText = [
    "width: 28px",
    "height: 28px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    `color: ${tokens.ink300}`,
    "border: 1px solid transparent",
    "border-radius: 4px",
    "font: inherit",
    "font-size: 14px",
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
