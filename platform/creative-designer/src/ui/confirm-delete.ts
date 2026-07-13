// Shared guard for every UI delete path (keyboard, toolbar, context
// menu). Deleting a field-bound text item in an EXPANDED reader removes
// that field from every size of the page (state.ts stripDeletedFields —
// one-source model, user decision 2026-07-13), so it warns first.
// Collapsed-size deletions are per-view and commit silently.

import { isMultiPage } from "../modes";
import { currentLayout, deleteSelection, hasSelection } from "../state";
import type { Store } from "../store";

export function commitDeleteSelection(store: Store): void {
  const s = store.state;
  if (!hasSelection(s)) return;
  if (isMultiPage(s.mode)) {
    const items = currentLayout(s);
    const fields = [...new Set(
      s.selectedItemIdxs
        .map((i) => items[i])
        .filter((it) => it?.type === "text")
        .map((it) => (it as { field?: string }).field)
        .filter((f): f is string => !!f),
    )];
    if (fields.length > 0) {
      const what = fields.join(" and ");
      if (!window.confirm(`Deleting the ${what} here also removes it from every size. Continue?`)) return;
    }
  }
  store.commit(deleteSelection(s));
}
