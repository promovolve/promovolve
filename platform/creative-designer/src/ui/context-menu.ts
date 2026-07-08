// Right-click context menu. Floating panel anchored to the cursor,
// dismissed on click-outside / Esc / another context-menu invocation.
//
// Menu items are data-driven — pass an array of MenuItem to
// openContextMenu and we render + dispatch. Separators are represented
// by a literal `"---"` string in the item list.

import { tokens } from "./tokens";

export interface MenuItem {
  label: string;
  shortcut?: string;
  disabled?: boolean;
  onSelect: () => void;
}

export type MenuEntry = MenuItem | "---";

let current: HTMLElement | null = null;

export function openContextMenu(x: number, y: number, items: MenuEntry[]): void {
  dismiss();
  if (items.length === 0) return;

  const menu = document.createElement("div");
  menu.className = "cd-context-menu";
  menu.style.cssText = [
    "position: fixed",
    `left: ${x}px`,
    `top: ${y}px`,
    "min-width: 200px",
    "padding: 4px",
    `background: ${tokens.ink700}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 6px",
    "box-shadow: 0 10px 30px rgba(0,0,0,0.5)",
    `color: ${tokens.ink100}`,
    "font-size: 12px",
    // Body-mounted (outside #designer-root): set the UI font so the menu
    // doesn't inherit the document default in the prod shell.
    `font-family: ${tokens.sans}`,
    "z-index: 1000",
    "user-select: none",
  ].join(";");

  for (const entry of items) {
    if (entry === "---") {
      const sep = document.createElement("div");
      sep.style.cssText = `height:1px;background:${tokens.ink500};margin:4px 6px;`;
      menu.appendChild(sep);
      continue;
    }
    menu.appendChild(renderItem(entry));
  }

  document.body.appendChild(menu);
  current = menu;
  clampToViewport(menu);

  // Install dismissers. Delay one tick so the pointerup that opened
  // the menu doesn't immediately close it.
  setTimeout(() => {
    window.addEventListener("pointerdown", onOutsidePointer, true);
    window.addEventListener("keydown", onKey);
  }, 0);
}

export function dismiss(): void {
  if (!current) return;
  current.remove();
  current = null;
  window.removeEventListener("pointerdown", onOutsidePointer, true);
  window.removeEventListener("keydown", onKey);
}

function renderItem(item: MenuItem): HTMLElement {
  const row = document.createElement("div");
  row.style.cssText = [
    "display: flex",
    "align-items: center",
    "gap: 12px",
    "padding: 6px 10px",
    "border-radius: 4px",
    `cursor: ${item.disabled ? "default" : "pointer"}`,
    `color: ${item.disabled ? tokens.ink400 : tokens.ink100}`,
  ].join(";");
  const label = document.createElement("span");
  label.textContent = item.label;
  label.style.flex = "1";
  row.appendChild(label);
  if (item.shortcut) {
    const hint = document.createElement("span");
    hint.textContent = item.shortcut;
    hint.style.cssText = `color:${tokens.ink400};font-size:11px;`;
    row.appendChild(hint);
  }
  if (!item.disabled) {
    row.addEventListener("mouseenter", () => { row.style.background = tokens.ink600; });
    row.addEventListener("mouseleave", () => { row.style.background = "transparent"; });
    row.addEventListener("click", (e) => {
      e.stopPropagation();
      item.onSelect();
      dismiss();
    });
  }
  return row;
}

function onOutsidePointer(e: PointerEvent): void {
  if (!current) return;
  if (current.contains(e.target as Node)) return;
  dismiss();
}

function onKey(e: KeyboardEvent): void {
  if (e.key === "Escape") {
    e.preventDefault();
    dismiss();
  }
}

function clampToViewport(menu: HTMLElement): void {
  const rect = menu.getBoundingClientRect();
  if (rect.right > window.innerWidth) {
    menu.style.left = `${window.innerWidth - rect.width - 8}px`;
  }
  if (rect.bottom > window.innerHeight) {
    menu.style.top = `${window.innerHeight - rect.height - 8}px`;
  }
}
