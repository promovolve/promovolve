// Shared guard for every UI delete path (keyboard, toolbar, context
// menu). Deleting a field-bound text item in an EXPANDED reader removes
// that field from every size of the page (state.ts stripDeletedFields —
// one-source model, user decision 2026-07-13), so it warns first — but
// only when the field actually EXISTS in at least one size: a field
// that lives solely in the reader (e.g. body copy on many creatives)
// deletes locally in effect, and prompting "removes it from every size"
// there was just wrong (user caught it 2026-07-13). Collapsed-size
// deletions are per-view and always commit silently.

import { isMultiPage } from "../modes";
import { currentLayout, currentPage, deleteSelection, hasSelection, MOBILE_EXPANDED_KEY } from "../state";
import type { DesignerState, LayoutItem } from "../types";
import type { Store } from "../store";

// How many SIZE buckets (the reader itself doesn't count) of the current
// page contain a text item bound to `field`.
function sizesContainingField(s: DesignerState, field: string): number {
  const page = currentPage(s);
  let n = 0;
  for (const [key, arr] of Object.entries(page?.banners ?? {})) {
    if (key === MOBILE_EXPANDED_KEY) continue;
    if ((arr as LayoutItem[] | undefined)?.some(
      (it) => it.type === "text" && (it as { field?: string }).field === field,
    )) n++;
  }
  return n;
}

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
    const parts = fields.flatMap((f) => {
      const n = sizesContainingField(s, f);
      return n > 0 ? [`the ${f} from ${n} size${n === 1 ? "" : "s"}`] : [];
    });
    if (parts.length > 0) {
      if (!window.confirm(`Deleting here also removes ${parts.join(" and ")}. Continue?`)) return;
    }
  }
  store.commit(deleteSelection(s));
}
