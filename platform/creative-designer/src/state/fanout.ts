// Fanout status — how "done" a given size is for a given page. Read
// by the Size Matrix and the Canvas Header to tell the author which
// sizes still need attention.
//
//   empty     — no items in that size's bucket. Author hasn't
//               designed it and auto-layout hasn't run yet.
//   generated — items exist but they were emitted by the auto-layout
//               generator (they carry the _generated flag). Looks
//               OK but nobody has reviewed it.
//   authored  — at least one item in the size lacks the _generated
//               flag, meaning the author has touched it. Treat the
//               whole size as approved even if some sibling items
//               are still auto-filled — one deliberate edit anywhere
//               in the size implies the author signed off.

import { MODES, type Mode } from "../modes";
import type { LayoutItem, Page } from "../types";

export type FanoutStatus = "authored" | "generated" | "empty";

export function itemsForMode(page: Page, mode: Mode): LayoutItem[] {
  if (mode.key === "expanded") return page.layout ?? [];
  const key = mode.sizeKey;
  return (key ? page.banners?.[key] : null) ?? [];
}

export function fanoutStatus(page: Page, mode: Mode): FanoutStatus {
  const items = itemsForMode(page, mode);
  if (items.length === 0) return "empty";
  return items.some((it) => !(it as LayoutItem & { _generated?: boolean })._generated)
    ? "authored"
    : "generated";
}

export interface FanoutCounts {
  authored: number;
  generated: number;
  empty: number;
  total: number;
}

export function fanoutCounts(page: Page): FanoutCounts {
  const out: FanoutCounts = { authored: 0, generated: 0, empty: 0, total: MODES.length };
  for (const m of MODES) out[fanoutStatus(page, m)]++;
  return out;
}
