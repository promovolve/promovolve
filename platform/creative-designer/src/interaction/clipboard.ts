// Module-local clipboard for layout items. Not the OS clipboard —
// layout items are rich objects, and bridging to the system clipboard
// well (serialize to text? paste from external?) is its own project.
// Keeping it scoped to the designer means copy/paste works inside one
// tab but not across tabs; that's fine for now and matches most
// design tools' default behavior.

import type { LayoutItem } from "../types";
import { addItem, currentItem, currentLayout } from "../state";
import type { Store } from "../store";

let clipboard: LayoutItem | null = null;

const PASTE_OFFSET = 3; // percent — shift paste so it's visible

export function copy(store: Store): boolean {
  const item = currentItem(store.state);
  if (!item) return false;
  clipboard = deepClone(item);
  return true;
}

export function paste(store: Store): boolean {
  if (!clipboard) return false;
  const offsetClone = withOffset(deepClone(clipboard));
  store.commit(addItem(store.state, offsetClone));
  return true;
}

export function duplicate(store: Store): boolean {
  const item = currentItem(store.state);
  if (!item) return false;
  const offsetClone = withOffset(deepClone(item));
  store.commit(addItem(store.state, offsetClone));
  return true;
}

export function hasClipboard(): boolean {
  return clipboard !== null;
}

function withOffset(item: LayoutItem): LayoutItem {
  return {
    ...item,
    left: (item.left ?? 0) + PASTE_OFFSET,
    top: (item.top ?? 0) + PASTE_OFFSET,
  };
}

function deepClone<T>(v: T): T {
  return typeof structuredClone === "function"
    ? structuredClone(v)
    : (JSON.parse(JSON.stringify(v)) as T);
}

// Exported for tests.
export const _state = {
  get clipboard(): LayoutItem | null {
    return clipboard;
  },
  reset(): void {
    clipboard = null;
  },
};

// Keep currentLayout import alive for tree-shaking guarantees in some
// build modes; used only by tests.
void currentLayout;
