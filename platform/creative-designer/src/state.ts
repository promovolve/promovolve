// Pure state mutations. Every function returns a new state — no in-place
// edits — so the history wrapper can snapshot without deep-cloning.
//
// Selection is an insertion-ordered, deduped list (selectedItemIdxs).
// Figma conventions: plain click replaces selection with [idx];
// Shift+click toggles idx in the list; marquee builds a list from a
// rectangle. Empty list = nothing selected.

import type { BannerConfig, DesignerState, LayoutItem, Page, TextureBg, VideoBg } from "./types";
import { findMode, isMultiPage, MODES } from "./modes";
import { presetLayoutFor } from "./presets";
import { kitFont, type BrandKit } from "./brand-kit";
import { itemBoundsPct } from "./geometry";

// ─── Constructors ───────────────────────────────────────────────────

const DEFAULT_BANNER_CONFIG: BannerConfig = {
  layout: "auto",
  font: "sans",
  showTag: true,
  showSub: true,
  expandAnimation: "fade",
};

export function initialState(
  pages: Page[],
  initialModeKey = "expanded",
  bannerConfig?: BannerConfig
): DesignerState {
  return {
    pages,
    pageIdx: 0,
    mode: findMode(initialModeKey),
    selectedItemIdxs: [],
    zoom: 1,
    // Drafts may carry a saved bannerConfig (loaded by the shell from
    // the creative record). First-time authoring → defaults.
    bannerConfig: bannerConfig ?? { ...DEFAULT_BANNER_CONFIG },
  };
}

export function setZoom(state: DesignerState, zoom: number): DesignerState {
  return { ...state, zoom };
}

// ─── Selectors ──────────────────────────────────────────────────────

export function currentPage(state: DesignerState): Page | null {
  return state.pages[state.pageIdx] ?? null;
}

// Returns the layout array for the active mode. In "expanded" mode this
// is page.layout; in a sized mode, page.banners[sizeKey]. Returns [] if
// no page or the bucket doesn't exist yet.
export function currentLayout(state: DesignerState): LayoutItem[] {
  const page = currentPage(state);
  if (!page) return [];
  if (state.mode.key === "expanded") {
    return page.layout ?? [];
  }
  const sizeKey = state.mode.sizeKey;
  if (!sizeKey) return [];
  return page.banners?.[sizeKey] ?? [];
}

// Returns the single selected item, but only when exactly one item is
// selected. Callers that want "the primary of a multi-selection" should
// read selectedItemIdxs[0] themselves — this is for UI that only makes
// sense with one focus target (props panel, rotate handle, etc.).
export function currentItem(state: DesignerState): LayoutItem | null {
  if (state.selectedItemIdxs.length !== 1) return null;
  return currentLayout(state)[state.selectedItemIdxs[0]!] ?? null;
}

export function hasSelection(state: DesignerState): boolean {
  return state.selectedItemIdxs.length > 0;
}

export function isSelected(state: DesignerState, idx: number): boolean {
  return state.selectedItemIdxs.includes(idx);
}

// ─── Internal: write-through the current layout ─────────────────────

function updateCurrentLayout(
  state: DesignerState,
  fn: (items: LayoutItem[]) => LayoutItem[],
): DesignerState {
  const page = currentPage(state);
  if (!page) return state;

  const isExpanded = state.mode.key === "expanded";
  const sizeKey = state.mode.sizeKey;
  const items = isExpanded
    ? (page.layout ?? [])
    : sizeKey
      ? (page.banners?.[sizeKey] ?? [])
      : [];
  const next = fn(items);

  const nextPage: Page = isExpanded
    ? { ...page, layout: next }
    : sizeKey
      ? { ...page, banners: { ...(page.banners ?? {}), [sizeKey]: next } }
      : page;

  return {
    ...state,
    pages: state.pages.map((p, i) => (i === state.pageIdx ? nextPage : p)),
  };
}

// Page 1 is the typographic master of a multi-page creative. Its
// headline/body/sub (any field-bound text) font SIZE, font FACE, and
// WRITING mode/direction propagate to the same field on every other
// page, so the magazine reads consistently — e.g. choosing vertical-rl
// on page 1 turns every page vertical, and a font-size set on page 1 is
// the size everywhere. Runs over BOTH multi-page reader surfaces — the
// PC expanded layout (page.layout) AND the mobile expanded bucket
// (page.banners["mobile-expanded"]) — each using page 1's own copy of
// that surface as the master (mobile uses its own sizes, not the PC's).
// Per-size collapsed/IAB buckets are single-page ads, so they're left
// out (they already inherit the expanded font via presets.ts). Returns
// the SAME state object when nothing changes, so the store subscriber
// that calls it converges to a fixpoint instead of looping.
const PAGE1_SYNCED_KEYS = ["fontSize", "fontFamily", "writingMode", "direction"] as const;

interface ReaderSurface {
  get: (p: Page) => LayoutItem[] | undefined;
  set: (p: Page, layout: LayoutItem[]) => Page;
}
// The two isMultiPage() surfaces (see modes.ts). Keep in sync with it.
const READER_SURFACES: ReaderSurface[] = [
  { get: (p) => p.layout, set: (p, layout) => ({ ...p, layout }) },
  {
    get: (p) => p.banners?.["mobile-expanded"],
    set: (p, layout) => ({ ...p, banners: { ...(p.banners ?? {}), "mobile-expanded": layout } }),
  },
];

export function syncTypographyFromPage1(state: DesignerState): DesignerState {
  if (state.pages.length < 2) return state;
  let pages = state.pages;
  let changed = false;

  for (const surf of READER_SURFACES) {
    // page 0 is never rewritten, so it stays the master across surfaces.
    const master = surf.get(pages[0]!) ?? [];
    const byField = new Map<string, Record<string, unknown>>();
    for (const it of master) {
      if (it.type !== "text" || !it.field) continue;
      const t = it as unknown as Record<string, unknown>;
      byField.set(it.field, {
        fontSize: t.fontSize, fontFamily: t.fontFamily,
        writingMode: t.writingMode, direction: t.direction,
      });
    }
    if (byField.size === 0) continue;

    let surfChanged = false;
    const next = pages.map((page, pi) => {
      const layout = surf.get(page);
      if (pi === 0 || !layout || layout.length === 0) return page;
      let pageChanged = false;
      const nextLayout = layout.map((it) => {
        if (it.type !== "text" || !it.field) return it;
        const typo = byField.get(it.field);
        if (!typo) return it;
        const cur = it as unknown as Record<string, unknown>;
        if (PAGE1_SYNCED_KEYS.every((k) => cur[k] === typo[k])) return it;
        pageChanged = true;
        return { ...it, ...typo } as LayoutItem;
      });
      if (!pageChanged) return page;
      surfChanged = true;
      return surf.set(page, nextLayout);
    });
    if (surfChanged) { pages = next; changed = true; }
  }

  return changed ? { ...state, pages } : state;
}

// setReaderFieldFontSize: absolute font-size sync across the multi-page
// reader surfaces, from ANY page. The page-1-master subscriber
// (syncTypographyFromPage1) makes page 1 the only editable page for size —
// an edit on page 2/3 is instantly reverted to the master's value, which
// reads as "font size doesn't sync". Writing the new size to the same
// field on EVERY page first (this function) makes the subscriber a no-op
// converger instead of a reverter. Reader pages share one geometry, so an
// absolute copy is correct here; the differently-shaped banner buckets
// keep their proportional fontScale fan-out (see TYPO_SYNC_KEYS note).
export function setReaderFieldFontSize(
  state: DesignerState,
  field: string,
  size: number,
): DesignerState {
  if (!field || !Number.isFinite(size) || size <= 0) return state;
  let pages = state.pages;
  let changed = false;
  for (const surf of READER_SURFACES) {
    let surfChanged = false;
    const next = pages.map((page) => {
      const layout = surf.get(page);
      if (!layout || layout.length === 0) return page;
      let pageChanged = false;
      const nextLayout = layout.map((it) => {
        if (it.type !== "text" || (it as { field?: string }).field !== field) return it;
        if ((it as unknown as { fontSize?: number }).fontSize === size) return it;
        pageChanged = true;
        return { ...it, fontSize: size } as LayoutItem;
      });
      if (!pageChanged) return page;
      surfChanged = true;
      return surf.set(page, nextLayout);
    });
    if (surfChanged) { pages = next; changed = true; }
  }
  return changed ? { ...state, pages } : state;
}

// fitReaderFieldBoxes: after a font-size edit, fit the SAME-FIELD text box
// on EVERY reader page to that page's OWN content at the authored size.
// Copying the edited page's box is not enough — pages share geometry but
// not text, so a longer page still auto-shrinks into the copied box
// ("body stays small until clicked"). The DOM-measuring callback lives in
// render/canvas (clone in the live stage = same container-query context,
// valid for every page since reader pages share one geometry); this walks
// the surfaces and stamps the returned heights. Vertical-rl items are
// left alone (their packing moves left too — the per-item Fit handles it).
export function fitReaderFieldBoxes(
  state: DesignerState,
  field: string,
  measureHeightPct: (text: string, item: LayoutItem) => number | null,
): DesignerState {
  if (!field) return state;
  let pages = state.pages;
  let changed = false;
  for (const surf of READER_SURFACES) {
    let surfChanged = false;
    const next = pages.map((page) => {
      const layout = surf.get(page);
      if (!layout || layout.length === 0) return page;
      let pageChanged = false;
      const nextLayout = layout.map((it) => {
        if (it.type !== "text" || (it as { field?: string }).field !== field) return it;
        if ((it as { writingMode?: string }).writingMode === "vertical-rl") return it;
        const text = ((it as { text?: string }).text
          ?? (page as unknown as Record<string, unknown>)[field]) as string | undefined;
        if (!text) return it;
        const h = measureHeightPct(text, it);
        if (h == null || !Number.isFinite(h) || h <= 0) return it;
        const cur = (it as unknown as { height?: number }).height;
        if (cur != null && Math.abs(cur - h) < 0.1) return it;
        pageChanged = true;
        return { ...it, height: h } as LayoutItem;
      });
      if (!pageChanged) return page;
      surfChanged = true;
      return surf.set(page, nextLayout);
    });
    if (surfChanged) { pages = next; changed = true; }
  }
  return changed ? { ...state, pages } : state;
}

// ── Model-A typography sync ──────────────────────────────────────────
// One typographic identity per role: editing any typography property of a
// field-bound text item (headline/body/sub) propagates that property to the
// SAME field across EVERY creative — each page's reader layout AND every
// banner/IAB size bucket. Image items (incl. author-added extras) are never
// touched: they aren't text and carry no `field`, so the role match below
// skips them.
//
// fontSize is DELIBERATELY NOT in this set: it syncs PROPORTIONALLY, not
// absolutely. The size unit is `cqmax` (% of the LARGER box side), and that
// side flips axis between formats — width on the 16:9 PC reader, height on
// the 9:16 mobile reader — so a single shared NUMBER does not mean the same
// relative size across formats (a value tuned on PC renders oversized on
// mobile). Instead, a size edit fans out as a SCALE factor (see
// propagateTypography's fontScale): each format keeps its own base size and
// grows/shrinks by the same ratio, preserving each format's proportion.
export const TYPO_SYNC_KEYS = [
  "color", "fontFamily", "fontWeight",
  "lineHeight", "textAlign", "shadow", "writingMode", "direction",
] as const;

// A field-bound text item carrying a baked local copy — the sync checkbox
// was unticked for this size. Detached items keep their own TEXT and
// COLOR: colour edits on them don't fan out, and colour fan-outs from
// synced sizes skip them (face/weight/size-ratio still sync — the
// checkbox governs text + colour only). Re-ticking ADOPTS the local
// version: see adoptTextOverride.
export function hasLocalTextOverride(it: LayoutItem): boolean {
  if (it.type !== "text") return false;
  const t = it as { field?: string; text?: string };
  return !!t.field && t.text != null && t.text !== "";
}

export function propagateTypography(
  state: DesignerState,
  field: string,
  typo: Record<string, unknown>,
  // Proportional fontSize sync: when set (≠1), every matching field item's
  // fontSize is multiplied by this ratio — see the TYPO_SYNC_KEYS note for
  // why size scales rather than copies. `skip` is the just-edited source
  // item (already at its new size); it's left untouched so it isn't
  // double-scaled.
  fontScale?: number,
  skip?: LayoutItem,
): DesignerState {
  // The HEADLINE text is shared across every creative, but its writing mode
  // (horizontal/vertical) is a PER-DIMENSION choice — so each creative can
  // orient the shared headline to fit its own shape (vertical on a tall format,
  // horizontal on a wide one). So don't sync writingMode for the headline
  // field; body (and other fields) still sync it.
  const effTypo: Record<string, unknown> =
    field === "headline" && "writingMode" in typo
      ? Object.fromEntries(Object.entries(typo).filter(([k]) => k !== "writingMode"))
      : typo;
  const keys = Object.keys(effTypo);
  const hasScale = fontScale != null && Number.isFinite(fontScale) && fontScale > 0 && fontScale !== 1;
  if ((keys.length === 0 && !hasScale) || !field) return state;
  const apply = (items: LayoutItem[]): LayoutItem[] => {
    let any = false;
    const next = items.map((it) => {
      if (it.type !== "text" || (it as { field?: string }).field !== field) return it;
      if (it === skip) return it; // source item — already at its new size
      // Detached override (sync unticked): its colour is local — apply
      // everything else (face/weight/scale still one identity per role).
      const itemTypo = hasLocalTextOverride(it) && "color" in effTypo
        ? Object.fromEntries(Object.entries(effTypo).filter(([k]) => k !== "color"))
        : effTypo;
      const itemKeys = Object.keys(itemTypo);
      const cur = it as unknown as Record<string, unknown>;
      const styleSame = itemKeys.length === 0 || itemKeys.every((k) => cur[k] === itemTypo[k]);
      let scaled: number | undefined;
      if (hasScale) {
        const base = (cur.fontSize as number | undefined) ?? 5;
        const next = Math.round(base * fontScale! * 10) / 10;
        if (next > 0 && next !== base) scaled = next;
      }
      if (styleSame && scaled === undefined) return it;
      any = true;
      return {
        ...it,
        ...itemTypo,
        ...(scaled !== undefined ? { fontSize: scaled } : {}),
      } as LayoutItem;
    });
    return any ? next : items;
  };
  let changed = false;
  const pages = state.pages.map((page) => {
    const layout = page.layout ? apply(page.layout) : page.layout;
    let banners = page.banners;
    if (banners) {
      let bChanged = false;
      const nb: Record<string, LayoutItem[]> = {};
      for (const [k, items] of Object.entries(banners)) {
        const r = apply(items);
        nb[k] = r;
        if (r !== items) bChanged = true;
      }
      if (bChanged) banners = nb;
    }
    if (layout === page.layout && banners === page.banners) return page;
    changed = true;
    return { ...page, layout, banners };
  });
  return changed ? { ...state, pages } : state;
}

// ── Colour ────────────────────────────────────────────────────────────
// Colour EDITS obey the generic all-formats typography sync above (it's in
// TYPO_SYNC_KEYS): one colour per role, editable from any surface. It used
// to be partitioned — portrait reader independent from the (16:9 master +
// collapsed buckets) group — but with the 16:9 tab retired the portrait
// reader is the only multi-page edit surface, and the partition just read
// as "my colour edit didn't take" on the banner sizes.
//
// EXCEPTION — detached overrides (2026-07-13, user decision): a size whose
// sync checkbox is unticked (hasLocalTextOverride) owns its text AND
// colour. Its colour edits don't fan out, incoming colour fan-outs skip
// it, and the load-time anchor below leaves it alone. Re-ticking ADOPTS
// the local text + colour as the shared version (adoptTextOverride).
//
// GENERATION and LOAD keep the master anchor: freshly fanned-out buckets
// inherit page.layout's colour (masterColor, used by applyLayout), and
// persisted creatives from the partitioned era are reconciled on load
// (inheritBannerColors) — those are about sane defaults, not edits.
export const MOBILE_EXPANDED_KEY = "mobile-expanded";

// The master-group colour for a role — the expanded PC reader (page 0's
// layout) is the authority. Falls back to any collapsed bucket, then null.
export function masterColor(state: DesignerState, field: string): string | null {
  const p0 = state.pages[0];
  if (!p0) return null;
  const fromLayout = (p0.layout ?? []).find(
    (it) => it.type === "text" && (it as { field?: string }).field === field,
  ) as { color?: string } | undefined;
  if (fromLayout?.color) return fromLayout.color;
  for (const [k, items] of Object.entries(p0.banners ?? {})) {
    if (k === MOBILE_EXPANDED_KEY) continue;
    const hit = items.find((it) => it.type === "text" && (it as { field?: string }).field === field) as { color?: string } | undefined;
    if (hit?.color) return hit.color;
  }
  return null;
}

// Force every collapsed banner bucket's text colour onto the 1st page's
// expanded (page.layout) colour, by field. Banner sizes always show the
// 1st-page headline colour "no matter what" — but a PERSISTED creative can
// carry a stale per-bucket colour (saved before this rule, or generated off
// the page bg via pickContrast), and those loaded layouts are never
// regenerated. So reconcile on LOAD here, the same way applyLayout reconciles
// freshly generated buckets and the typography sync reconciles edits. The mobile
// reader is independent and left untouched. Idempotent — returns the same
// array when nothing changes.
export function inheritBannerColors(pages: Page[]): Page[] {
  const p0 = pages[0];
  if (!p0) return pages;
  const masterByField = new Map<string, string>();
  for (const it of p0.layout ?? []) {
    const f = (it as { field?: string }).field;
    const c = (it as { color?: string }).color;
    if (it.type === "text" && f && typeof c === "string") masterByField.set(f, c);
  }
  if (masterByField.size === 0) return pages;
  let changed = false;
  const out = pages.map((page) => {
    if (!page.banners) return page;
    let bChanged = false;
    const banners: Record<string, LayoutItem[]> = {};
    for (const [k, items] of Object.entries(page.banners)) {
      if (k === MOBILE_EXPANDED_KEY) { banners[k] = items; continue; }
      let any = false;
      const next = items.map((it) => {
        if (it.type !== "text") return it;
        // Detached override (sync unticked) — its colour is a deliberate
        // per-size choice; the load-time anchor must not wipe it.
        if (hasLocalTextOverride(it)) return it;
        const f = (it as { field?: string }).field;
        const mc = f ? masterByField.get(f) : undefined;
        if (mc && mc !== (it as { color?: string }).color) { any = true; return { ...it, color: mc } as LayoutItem; }
        return it;
      });
      banners[k] = any ? next : items;
      if (any) bChanged = true;
    }
    if (bChanged) { changed = true; return { ...page, banners }; }
    return page;
  });
  return changed ? out : pages;
}

// Harmonize headline/body typography across every creative, mastered from the
// CURRENT view's field-bound text — the TYPO_SYNC_KEYS set (color, face,
// weight, …). Used right after a template is applied so the just-picked
// typography unifies across sizes immediately, not only after a manual edit.
// fontSize is intentionally EXCLUDED (it's not in TYPO_SYNC_KEYS): each
// format keeps its own size so the template's per-format proportions survive
// — see the TYPO_SYNC_KEYS note on proportional sizing. A manual size edit
// still fans out proportionally via propagateTypography's fontScale.
export function harmonizeTypography(state: DesignerState): DesignerState {
  let next = state;
  for (const it of currentLayout(state)) {
    if (it.type !== "text") continue;
    const field = (it as { field?: string }).field;
    if (!field) continue;
    const src = it as unknown as Record<string, unknown>;
    const typo: Record<string, unknown> = {};
    for (const k of TYPO_SYNC_KEYS) if (src[k] !== undefined) typo[k] = src[k];
    if (Object.keys(typo).length > 0) next = propagateTypography(next, field, typo);
  }
  return next;
}

// Re-apply the brand kit's fonts to every text item by role — headline
// fields take the heading font (kit.fonts[0]), every other text item takes
// the body font (kit.fonts[1]) — across every page and size bucket. Wired
// to fire when the brand kit changes (brand-kit-modal save) so editing the
// kit propagates to EXISTING text, not just freshly-generated layouts.
// Returns the same state object when nothing changes.
export function applyBrandKitFontsToText(state: DesignerState, kit: BrandKit | null): DesignerState {
  const head = kitFont(kit, 0, "Georgia");
  const body = kitFont(kit, 1, "sans-serif");
  const remap = (items: LayoutItem[]): LayoutItem[] => {
    let any = false;
    const next = items.map((it) => {
      if (it.type !== "text") return it;
      const want = it.field === "headline" ? head : body;
      if (it.fontFamily === want) return it;
      any = true;
      return { ...it, fontFamily: want };
    });
    return any ? next : items;
  };
  let changed = false;
  const pages = state.pages.map((page) => {
    const layout = page.layout ? remap(page.layout) : page.layout;
    let banners = page.banners;
    if (banners) {
      let bChanged = false;
      const nb: Record<string, LayoutItem[]> = {};
      for (const [k, items] of Object.entries(banners)) {
        const r = remap(items);
        nb[k] = r;
        if (r !== items) bChanged = true;
      }
      if (bChanged) banners = nb;
    }
    if (layout === page.layout && banners === page.banners) return page;
    changed = true;
    return { ...page, layout, banners };
  });
  return changed ? { ...state, pages } : state;
}

// ─── Content-editing mutations (undoable) ───────────────────────────

// Adds an item and selects it (replacing any multi-selection).
export function addItem(state: DesignerState, item: LayoutItem): DesignerState {
  const next = updateCurrentLayout(state, (items) => [...items, item]);
  return { ...next, selectedItemIdxs: [currentLayout(next).length - 1] };
}

// Add an image as a LOCAL (unsynced) item to the current view — centered, a
// modest size, carrying its own baked src. It stays in this view only; THE
// shared image is changed via setMainImage from the expanded view. Selected
// after add so the props panel is ready.
export function addLocalImage(state: DesignerState, src: string): DesignerState {
  const item = { type: "image", src, left: 25, top: 25, width: 50, height: 50, fillMode: "fill" } as LayoutItem;
  return addItem(state, item);
}

export function updateItem(
  state: DesignerState,
  idx: number,
  fn: (item: LayoutItem) => LayoutItem,
): DesignerState {
  const items = currentLayout(state);
  if (idx < 0 || idx >= items.length) return state;
  return updateCurrentLayout(state, (arr) =>
    arr.map((it, i) => {
      if (i !== idx) return it;
      // Any user edit clears the _generated flag — the moment the
      // author touches an auto-filled item, the whole size flips
      // from "generated" to "authored" in the fanout status.
      const { _generated: _g, ...rest } = fn(it) as LayoutItem & { _generated?: boolean };
      return rest as LayoutItem;
    }),
  );
}

// Edit an item's CONTENT (text or image src). A field-bound item edited
// in an EXPANDED reader (PC *or* mobile — see isMultiPage) writes the
// shared page[field], so every view bound to that field re-renders the
// new value (one source of truth for headline / sub / body / image).
// Content isn't editable in the collapsed banner sizes (the UI gates that
// off); a non-field item just bakes a literal.
export function setItemContent(
  state: DesignerState,
  idx: number,
  value: string,
  kind: "text" | "src",
): DesignerState {
  const items = currentLayout(state);
  const item = items[idx] as (LayoutItem & { field?: string }) | undefined;
  if (!item) return state;

  if (isMultiPage(state.mode) && item.field) {
    const field = item.field;
    // Drop the edited item's own bake (in the reader's layout,
    // banners[mobile-expanded] — the portrait reader is the only
    // multi-page edit surface now) so it reads page[field], then write
    // the shared field. Every other field-bound view (the tabless wide
    // page.layout, the collapsed buckets) resolves the new value live.
    const cleared = updateCurrentLayout(state, (arr) =>
      arr.map((it, i) => {
        if (i !== idx) return it;
        const { [kind]: _baked, _generated: _g, ...rest } =
          it as LayoutItem & { text?: string; src?: string; _generated?: boolean };
        return rest as LayoutItem;
      }),
    );
    const page = currentPage(cleared);
    if (!page) return cleared;
    const nextPage = { ...page, [field]: value } as Page;
    return { ...cleared, pages: cleared.pages.map((p, i) => (i === cleared.pageIdx ? nextPage : p)) };
  }

  // Non-field item (or a locked size that somehow reaches here): bake.
  return updateItem(state, idx, (it) => ({ ...it, [kind]: value }));
}

// Undo a per-size override: drop the item's baked text/src so it falls
// back to the shared page[field] again. No-op for non-field items.
export function relinkItem(state: DesignerState, idx: number, kind: "text" | "src"): DesignerState {
  return updateItem(state, idx, (it) => {
    const { [kind]: _baked, ...rest } = it as LayoutItem & { text?: string; src?: string };
    return rest as LayoutItem;
  });
}

// Re-tick after local edits = ADOPT, not discard: push this size's baked
// text into the shared page[field] (every synced view resolves it) and
// broadcast this size's colour to the role everywhere, then drop the bake
// so the item re-links. Other sizes that are THEMSELVES overridden keep
// their own colour (propagateTypography skips detached items).
export function adoptTextOverride(state: DesignerState, idx: number): DesignerState {
  const it = currentLayout(state)[idx] as
    | (LayoutItem & { field?: string; text?: string; color?: string })
    | undefined;
  if (!it || it.type !== "text" || !it.field) return state;
  const field = it.field;
  let next = state;
  if (it.text != null && it.text !== "") {
    const page = currentPage(next);
    if (page) {
      const nextPage = { ...page, [field]: it.text } as Page;
      next = { ...next, pages: next.pages.map((p, i) => (i === next.pageIdx ? nextPage : p)) };
    }
  }
  next = relinkItem(next, idx, "text");
  if (it.color) next = propagateTypography(next, field, { color: it.color });
  return next;
}

// Set the single MAIN image (page.img) — the source of truth shared by
// every size via field:"img". Any generated size that lacks an image slot
// gets one added at that size's preset position, so adding/changing the
// image in the expanded view shows in ALL sizes (not just ones generated
// with an image). Sizes that already have a field:"img" item just resolve
// the new page.img. Empty/ungenerated sizes are left alone (the fanout
// builds them later). Only meaningful from the expanded view.
export function setMainImage(state: DesignerState, src: string): DesignerState {
  const page = currentPage(state);
  if (!page) return state;
  const withImg = { ...page, img: src } as Page;
  // Pull the image slot's position from the size's own preset.
  const slotFor = (modeKey: string): LayoutItem | null => {
    const preset = presetLayoutFor(modeKey, withImg);
    const img = preset?.find((it) => it.type === "image") as LayoutItem | undefined;
    return img ? ({ ...img, field: "img", src: undefined } as LayoutItem) : null;
  };
  // Ensure a view has EXACTLY ONE main image bound to page.img, sitting at the
  // BACK of the z-stack (index 0 — text always renders on top). If it already
  // has an image (baked from an older generation, or field-bound), convert the
  // first one — drop its baked src/crop so it resolves the new page.img live —
  // drop any extra image items (the "layered on top" duplicate), and move it
  // behind the text. Only a view with no image at all gets a fresh preset slot.
  // This is the "set THE single main" op (asset-replace).
  const ensureMainImage = (items: LayoutItem[], modeKey: string): LayoutItem[] => {
    const keep = items.find((it) => it.type === "image");
    const rest = items.filter((it) => it.type !== "image");
    if (!keep) {
      const s = slotFor(modeKey);
      return s ? [s, ...rest] : rest;
    }
    const { src: _src, crop: _crop, ...base } = keep as LayoutItem & { src?: string; crop?: unknown };
    const main = { ...base, field: "img" } as LayoutItem;
    return [main, ...rest];
  };

  let layout = page.layout ?? [];
  if (layout.length > 0) layout = ensureMainImage(layout, "expanded");
  let banners = { ...(page.banners ?? {}) };
  for (const m of MODES) {
    if (m.key === "expanded" || !m.sizeKey) continue;
    const items = banners[m.sizeKey];
    if (items && items.length > 0) {
      banners = { ...banners, [m.sizeKey]: ensureMainImage(items, m.key) };
    }
  }
  const nextPage = { ...page, img: src, layout, banners } as Page;
  // The image moves to index 0, shifting every other item — selection by index
  // would now point at the wrong item, so drop it.
  return {
    ...state,
    selectedItemIdxs: [],
    pages: state.pages.map((p, i) => (i === state.pageIdx ? nextPage : p)),
  };
}

// ── The shared MAIN image ────────────────────────────────────────────
// One image per page, everywhere (user decision 2026-07-13): the image on
// the EXPANDED view defines it. It's the single value `page.img` plus the
// one item per view carrying field:"img" (which resolves page.img live).
//   isMain(X)        = X.field === "img"
//   syncsAcrossSizes = always — sizes place/crop the image, never choose it
// There is no pin/unpin: page.img is reconciled FROM the expanded view at
// load (normalize.ts reconcileMainImage) and changed via setMainImage
// ("Replace image…" / canvas drop in the expanded view), which rebinds
// every view. Author-added extra images are baked locals and never sync.

export function deleteItem(state: DesignerState, idx: number): DesignerState {
  const items = currentLayout(state);
  if (idx < 0 || idx >= items.length) return state;
  const removed = items[idx];
  const next = updateCurrentLayout(state, (arr) => arr.filter((_, i) => i !== idx));
  // Deleting the shared main clears page.img so it doesn't resurrect on the
  // next ensureMainImage; the other views keep their own hero slots, which now
  // resolve an empty source until a new image is set from the expanded view.
  const clearedMain = removed && removed.type === "image" && (removed as { field?: string }).field === "img";
  const page = clearedMain ? currentPage(next) : null;
  const withImg = page ? { ...next, pages: next.pages.map((p, i) => (i === next.pageIdx ? ({ ...page, img: undefined } as Page) : p)) } : next;
  // Drop the deleted idx from the selection; shift higher indexes down.
  const nextSelected = shiftSelectionAfterDelete(state.selectedItemIdxs, idx);
  return { ...withImg, selectedItemIdxs: nextSelected };
}

// Deletes every currently-selected item. Cleared selection afterwards.
export function deleteSelection(state: DesignerState): DesignerState {
  if (state.selectedItemIdxs.length === 0) return state;
  const drop = new Set(state.selectedItemIdxs);
  const next = updateCurrentLayout(state, (arr) => arr.filter((_, i) => !drop.has(i)));
  return { ...next, selectedItemIdxs: [] };
}

// ─── Z-order (built on top of reorderItem) ─────────────────────────
//
// "Forward" = later in the layout array = rendered on top.
// "Back"    = earlier in the array      = rendered behind.
//
// Group semantics: all four functions operate on the current
// selection. When multiple items are selected, they keep their
// relative order among themselves (Figma-style).

// Move every selected item to the top of the z-stack (end of array).
export function bringToFront(state: DesignerState): DesignerState {
  if (state.selectedItemIdxs.length === 0) return state;
  const items = currentLayout(state);
  const selectedSet = new Set(state.selectedItemIdxs);
  const rest: number[] = [];
  const moved: number[] = [];
  for (let i = 0; i < items.length; i++) {
    (selectedSet.has(i) ? moved : rest).push(i);
  }
  const permutation = [...rest, ...moved];
  return applyPermutation(state, permutation);
}

// Mirror of bringToFront.
export function sendToBack(state: DesignerState): DesignerState {
  if (state.selectedItemIdxs.length === 0) return state;
  const items = currentLayout(state);
  const selectedSet = new Set(state.selectedItemIdxs);
  const rest: number[] = [];
  const moved: number[] = [];
  for (let i = 0; i < items.length; i++) {
    (selectedSet.has(i) ? moved : rest).push(i);
  }
  const permutation = [...moved, ...rest];
  return applyPermutation(state, permutation);
}

// Walk selected items from highest index down, each moves one step
// toward the end. Items already at the top (or blocked by another
// selected item) stay.
export function bringForward(state: DesignerState): DesignerState {
  const items = currentLayout(state);
  const selectedSet = new Set(state.selectedItemIdxs);
  const order = items.map((_, i) => i);
  for (let i = items.length - 1; i >= 0; i--) {
    const src = order[i]!;
    if (!selectedSet.has(src)) continue;
    const nextIdx = i + 1;
    if (nextIdx >= order.length) continue;
    if (selectedSet.has(order[nextIdx]!)) continue;
    [order[i], order[nextIdx]] = [order[nextIdx]!, order[i]!];
  }
  return applyPermutation(state, order);
}

// Mirror of bringForward.
export function sendBackward(state: DesignerState): DesignerState {
  const items = currentLayout(state);
  const selectedSet = new Set(state.selectedItemIdxs);
  const order = items.map((_, i) => i);
  for (let i = 0; i < items.length; i++) {
    const src = order[i]!;
    if (!selectedSet.has(src)) continue;
    const prevIdx = i - 1;
    if (prevIdx < 0) continue;
    if (selectedSet.has(order[prevIdx]!)) continue;
    [order[i], order[prevIdx]] = [order[prevIdx]!, order[i]!];
  }
  return applyPermutation(state, order);
}

// Reorders the current layout according to the given index permutation
// (permutation[i] = "the item that should end up at index i came from
// this old index"). Selection is mapped to new positions so it stays
// pinned to the items that actually moved.
function applyPermutation(state: DesignerState, permutation: number[]): DesignerState {
  const items = currentLayout(state);
  if (permutation.length !== items.length) return state;
  // No-op if permutation is identity.
  let changed = false;
  for (let i = 0; i < permutation.length; i++) {
    if (permutation[i] !== i) { changed = true; break; }
  }
  if (!changed) return state;

  const next = updateCurrentLayout(state, () => permutation.map((oldIdx) => items[oldIdx]!));
  const oldToNew = new Map<number, number>();
  permutation.forEach((oldIdx, newIdx) => oldToNew.set(oldIdx, newIdx));
  const nextSelection = state.selectedItemIdxs.map((i) => oldToNew.get(i) ?? i);
  return { ...next, selectedItemIdxs: nextSelection };
}

export function reorderItem(state: DesignerState, from: number, to: number): DesignerState {
  const items = currentLayout(state);
  if (from < 0 || from >= items.length || to < 0 || to >= items.length) return state;
  if (from === to) return state;
  const next = updateCurrentLayout(state, (arr) => {
    const copy = arr.slice();
    const [moved] = copy.splice(from, 1);
    copy.splice(to, 0, moved!);
    return copy;
  });
  const nextSelected = state.selectedItemIdxs.map((i) => remapReorder(i, from, to));
  return { ...next, selectedItemIdxs: nextSelected };
}

// ─── Alignment & distribution ───────────────────────────────────────
//
// Group ops on the current selection. Targets the selection's own
// bounding box (Figma convention) — never the canvas. With one item
// selected there is nothing to align against, so all operations are
// no-ops below the threshold (2 for align, 3 for distribute).
//
// Items carry left/top in percent coords; circles store left/top as
// the bbox top-left (matching itemBoundsPct), so the same delta math
// works uniformly across all item types.

export type AlignSide = "left" | "hcenter" | "right" | "top" | "vcenter" | "bottom";

export function alignSelection(state: DesignerState, side: AlignSide): DesignerState {
  if (state.selectedItemIdxs.length < 1) return state;
  const items = currentLayout(state);
  const picks = state.selectedItemIdxs
    .filter((i) => i >= 0 && i < items.length)
    .map((i) => ({ idx: i, item: items[i]!, b: itemBoundsPct(items[i]!) }));
  if (picks.length < 1) return state;

  // Single selection aligns against the canvas (0..100); multi-select
  // aligns against the selection's own bounding box (Figma convention).
  // Distribute still requires 3+ — there's nothing to distribute when
  // only the canvas anchors exist.
  const useCanvas = picks.length === 1;
  const minLeft   = useCanvas ? 0   : Math.min(...picks.map((p) => p.b.left));
  const maxRight  = useCanvas ? 100 : Math.max(...picks.map((p) => p.b.left + p.b.width));
  const minTop    = useCanvas ? 0   : Math.min(...picks.map((p) => p.b.top));
  const maxBottom = useCanvas ? 100 : Math.max(...picks.map((p) => p.b.top + p.b.height));
  const groupCx = (minLeft + maxRight) / 2;
  const groupCy = (minTop + maxBottom) / 2;

  return updateCurrentLayout(state, (layout) => {
    const next = layout.slice();
    for (const p of picks) {
      let dl = 0;
      let dt = 0;
      switch (side) {
        case "left":    dl = minLeft - p.b.left; break;
        case "right":   dl = maxRight - (p.b.left + p.b.width); break;
        case "hcenter": dl = groupCx - (p.b.left + p.b.width / 2); break;
        case "top":     dt = minTop - p.b.top; break;
        case "bottom":  dt = maxBottom - (p.b.top + p.b.height); break;
        case "vcenter": dt = groupCy - (p.b.top + p.b.height / 2); break;
      }
      if (dl === 0 && dt === 0) continue;
      const item = p.item;
      next[p.idx] = {
        ...item,
        left: (item.left ?? 0) + dl,
        top: (item.top ?? 0) + dt,
      };
    }
    return next;
  });
}

export type DistributeAxis = "horizontal" | "vertical";

// Equal-gap distribution. With 3+ items, anchors the two outermost
// and redistributes the rest so whitespace between adjacent items is
// uniform. With 1 or 2 items the selection's own bbox is degenerate,
// so the canvas (0..100) takes over as the span — a single item
// centers; two items pin to the canvas edges with even gap between.
export function distributeSelection(state: DesignerState, axis: DistributeAxis): DesignerState {
  if (state.selectedItemIdxs.length < 1) return state;
  const items = currentLayout(state);
  const picks = state.selectedItemIdxs
    .filter((i) => i >= 0 && i < items.length)
    .map((i) => ({ idx: i, item: items[i]!, b: itemBoundsPct(items[i]!) }));
  if (picks.length < 1) return state;

  const horizontal = axis === "horizontal";
  const sorted = picks.slice().sort((a, b) =>
    horizontal ? a.b.left - b.b.left : a.b.top - b.b.top,
  );
  // Span: selection bbox for 3+; canvas for 1-2.
  const useCanvas = sorted.length < 3;
  const first = sorted[0]!;
  const last = sorted[sorted.length - 1]!;
  const start = useCanvas ? 0 : (horizontal ? first.b.left : first.b.top);
  const end = useCanvas
    ? 100
    : (horizontal ? last.b.left + last.b.width : last.b.top + last.b.height);
  const span = end - start;
  const sumSizes = sorted.reduce(
    (acc, p) => acc + (horizontal ? p.b.width : p.b.height),
    0,
  );
  const totalGap = span - sumSizes;
  if (sorted.length === 1) {
    // Single item: center on canvas. Equivalent to align-hcenter /
    // align-vcenter; we keep it here so distribute is never a no-op.
    const p = sorted[0]!;
    const size = horizontal ? p.b.width : p.b.height;
    const targetEdge = (span - size) / 2;
    const currentEdge = horizontal ? p.b.left : p.b.top;
    const delta = targetEdge - currentEdge;
    if (delta === 0) return state;
    return updateCurrentLayout(state, (layout) =>
      layout.map((it, i) => i === p.idx
        ? (horizontal
            ? { ...it, left: (it.left ?? 0) + delta }
            : { ...it, top:  (it.top  ?? 0) + delta })
        : it,
      ),
    );
  }
  const gap = totalGap / (sorted.length - 1);

  return updateCurrentLayout(state, (layout) => {
    const next = layout.slice();
    let cursor = start;
    for (const p of sorted) {
      const size = horizontal ? p.b.width : p.b.height;
      const targetEdge = cursor;
      const currentEdge = horizontal ? p.b.left : p.b.top;
      const delta = targetEdge - currentEdge;
      if (delta !== 0) {
        const item = p.item;
        next[p.idx] = horizontal
          ? { ...item, left: (item.left ?? 0) + delta }
          : { ...item, top: (item.top ?? 0) + delta };
      }
      cursor += size + gap;
    }
    return next;
  });
}

// ─── Selection ──────────────────────────────────────────────────────

// Expand a list of indexes to include every item sharing a groupId
// with any of them. Items without a groupId are returned unchanged.
// Used by setSelection / toggleSelect so picking one group member
// always selects the whole group (Figma convention for grouped
// objects). Idempotent and order-preserving.
function expandToGroups(items: LayoutItem[], idxs: number[]): number[] {
  const seen = new Set<number>();
  const groupIds = new Set<string>();
  for (const i of idxs) {
    const it = items[i];
    if (it && (it as LayoutItem & { groupId?: string }).groupId) {
      groupIds.add((it as LayoutItem & { groupId?: string }).groupId!);
    }
  }
  const result: number[] = [];
  for (const i of idxs) {
    if (!seen.has(i)) {
      seen.add(i);
      result.push(i);
    }
  }
  if (groupIds.size > 0) {
    items.forEach((it, i) => {
      const g = (it as LayoutItem & { groupId?: string }).groupId;
      if (g && groupIds.has(g) && !seen.has(i)) {
        seen.add(i);
        result.push(i);
      }
    });
  }
  return result;
}

// Replaces the entire selection. Pass [] to deselect; pass [idx] for
// single-select; pass [a, b, ...] for multi-select (e.g., marquee).
// Out-of-range indexes are filtered; duplicates are removed; group
// members of any included idx are pulled in too.
export function setSelection(state: DesignerState, idxs: number[]): DesignerState {
  const items = currentLayout(state);
  const seen = new Set<number>();
  const clean: number[] = [];
  for (const i of idxs) {
    if (i < 0 || i >= items.length) continue;
    if (seen.has(i)) continue;
    seen.add(i);
    clean.push(i);
  }
  return { ...state, selectedItemIdxs: expandToGroups(items, clean) };
}

// Convenience: replace selection with [idx], or [] if idx is null.
export function selectItem(state: DesignerState, idx: number | null): DesignerState {
  if (idx === null) return { ...state, selectedItemIdxs: [] };
  return setSelection(state, [idx]);
}

// Figma-style Shift+click: toggle idx's membership in the current
// selection. If it was selected, remove it (and its group siblings);
// if not, append (and pull its group siblings in).
export function toggleSelect(state: DesignerState, idx: number): DesignerState {
  const items = currentLayout(state);
  if (idx < 0 || idx >= items.length) return state;
  const item = items[idx]!;
  const groupId = (item as LayoutItem & { groupId?: string }).groupId;
  const groupMembers: Set<number> = groupId
    ? new Set(items.flatMap((it, i) => (it as LayoutItem & { groupId?: string }).groupId === groupId ? [i] : []))
    : new Set([idx]);
  const has = state.selectedItemIdxs.includes(idx);
  const next = has
    ? state.selectedItemIdxs.filter((i) => !groupMembers.has(i))
    : [...state.selectedItemIdxs.filter((i) => !groupMembers.has(i)), ...groupMembers];
  return { ...state, selectedItemIdxs: next };
}

// ─── Group / ungroup ────────────────────────────────────────────────
//
// Soft groups: membership lives on the item via `groupId`, not as a
// separate hierarchy node. Items in a group select, drag, copy, and
// delete together because the existing selection ops already act on
// every selected idx, and selection auto-expands group siblings.
//
// One group per item — re-grouping reassigns. Ungrouping strips the
// id. Renderer ignores the field.

let groupCounter = 0;
function nextGroupId(): string {
  groupCounter += 1;
  // 36-base millisecond + counter → short, sortable, collision-free
  // for any plausible session. Not cryptographic — these are local-
  // only ids that never leave the browser.
  return `g${Date.now().toString(36)}${groupCounter.toString(36)}`;
}

export function groupSelection(state: DesignerState): DesignerState {
  if (state.selectedItemIdxs.length < 2) return state;
  const id = nextGroupId();
  const sel = new Set(state.selectedItemIdxs);
  return updateCurrentLayout(state, (layout) =>
    layout.map((item, i) => sel.has(i) ? { ...item, groupId: id } : item),
  );
}

// Replace the current mode's layout wholesale. Used by the layout-
// templates picker — templates land as one history step (Cmd+Z
// brings back whatever was on the canvas before). Selection is
// cleared because the prior indexes no longer point at the same
// items.
export function applyTemplate(state: DesignerState, items: LayoutItem[]): DesignerState {
  const applied = updateCurrentLayout(state, () => items.slice());
  // Harmonize the just-applied typographic identity across every size so it's
  // consistent immediately (not only after the user edits a property). Per-size
  // fontSize is preserved.
  return { ...harmonizeTypography(applied), selectedItemIdxs: [] };
}

// Apply a per-item transformer to every selected item in a single
// mutation — group resize / rotate gestures use this to land their
// per-frame updates as one history step on commit. The transformer
// receives the live item plus its index in the layout (so it can
// look up cached bbox info if it precomputed any).
export function transformSelection(
  state: DesignerState,
  transform: (item: LayoutItem, idx: number) => LayoutItem,
): DesignerState {
  if (state.selectedItemIdxs.length === 0) return state;
  const sel = new Set(state.selectedItemIdxs);
  return updateCurrentLayout(state, (layout) =>
    layout.map((item, i) => sel.has(i) ? transform(item, i) : item),
  );
}

export function ungroupSelection(state: DesignerState): DesignerState {
  if (state.selectedItemIdxs.length === 0) return state;
  const sel = new Set(state.selectedItemIdxs);
  let changed = false;
  const next = updateCurrentLayout(state, (layout) =>
    layout.map((item, i) => {
      if (!sel.has(i)) return item;
      const it = item as LayoutItem & { groupId?: string };
      if (!it.groupId) return item;
      changed = true;
      const { groupId: _drop, ...rest } = it;
      return rest as LayoutItem;
    }),
  );
  return changed ? next : state;
}

// ─── Page background video (undoable) ──────────────────────────────

/** Set or clear the current page's video background. Pass `null` to
  * remove. Writes to the page directly — not a layout item — so
  * auto-layout regeneration ignores it and every size fanout renders
  * the same video behind its own items. */
export function setVideoBg(state: DesignerState, bg: VideoBg | null): DesignerState {
  const page = currentPage(state);
  if (!page) return state;
  const nextPage: Page = bg === null
    ? (() => {
        const { videoBg: _drop, ...rest } = page;
        return rest as Page;
      })()
    : { ...page, videoBg: bg };
  return {
    ...state,
    pages: state.pages.map((p, i) => (i === state.pageIdx ? nextPage : p)),
  };
}

/** Set or clear the current page's texture background. Pass `null` to
  * remove. Writes to the page directly (not a layout item), mirroring
  * setVideoBg, so auto-layout regeneration ignores it and every size
  * fanout renders the same texture behind its own items. */
export function setTextureBg(state: DesignerState, bg: TextureBg | null): DesignerState {
  const page = currentPage(state);
  if (!page) return state;
  const nextPage: Page = bg === null
    ? (() => {
        const { textureBg: _drop, ...rest } = page;
        return rest as Page;
      })()
    : { ...page, textureBg: bg };
  return {
    ...state,
    pages: state.pages.map((p, i) => (i === state.pageIdx ? nextPage : p)),
  };
}

/** Set the texture bg from a ready image URL (a drop or a re-pick),
  * preserving the author's existing mode/opacity/scale/blend knobs — only
  * the source changes. `sizeBytes` is known for fresh uploads and feeds
  * the pre-publish weight estimate. */
export function setTextureSrc(state: DesignerState, src: string, sizeBytes?: number): DesignerState {
  const prev = currentPage(state)?.textureBg;
  return setTextureBg(state, {
    src,
    mode: prev?.mode ?? "tile",
    opacity: prev?.opacity ?? 1,
    scale: prev?.scale,
    blend: prev?.blend,
    focusX: prev?.focusX,
    focusY: prev?.focusY,
    sizeBytes,
  });
}

/** Set or clear the current page's solid background color (CSS color
  * string). Pass `null` to remove and revert to the renderer's default
  * dark gradient. Lives on the page, mirrors setVideoBg. While page-1
  * colour sync is on (pages[0].syncBg), a page-1 edit writes EVERY page —
  * the UI disables the colour row on pages 2/3, so page 1 is the only
  * edit surface; a direct pages-2/3 call stays per-page. */
export function setPageBg(state: DesignerState, bg: string | null): DesignerState {
  const page = currentPage(state);
  if (!page) return state;
  const apply = (p: Page): Page => {
    if (bg === null || bg === "") {
      const { bg: _drop, ...rest } = p;
      return rest as Page;
    }
    return { ...p, bg };
  };
  if (state.pageIdx === 0 && state.pages[0]?.syncBg) {
    return { ...state, pages: state.pages.map(apply) };
  }
  return {
    ...state,
    pages: state.pages.map((p, i) => (i === state.pageIdx ? apply(p) : p)),
  };
}

/** Toggle "sync color across all 3 pages" (stored on page 0, persisted
  * with pagesJSON). Turning it ON immediately copies page 1's background
  * colour — or its absence — onto every page; turning it OFF leaves the
  * pages as they are, free to diverge again. */
export function setSyncPageBg(state: DesignerState, on: boolean): DesignerState {
  const first = state.pages[0];
  if (!first) return state;
  const bg = first.bg;
  const pages = state.pages.map((p, i) => {
    let next = p;
    if (on && i > 0) {
      if (bg == null) {
        const { bg: _drop, ...rest } = p;
        next = rest as Page;
      } else {
        next = { ...p, bg };
      }
    }
    if (i === 0) next = { ...next, syncBg: on || undefined } as Page;
    return next;
  });
  return { ...state, pages };
}

// ─── Navigation (not undoable) ──────────────────────────────────────

export function switchMode(state: DesignerState, modeKey: string): DesignerState {
  const mode = findMode(modeKey);
  // IAB-sized modes only ever render the cover (see render/canvas.ts);
  // snap pageIdx so edits land on the page that's actually shown
  // instead of a hidden pages[1]/[2] left over from Expanded nav.
  const coverIdx = state.bannerConfig.coverPageIdx ?? 0;
  const pageIdx = isMultiPage(mode) ? state.pageIdx : coverIdx;
  return { ...state, mode, pageIdx, selectedItemIdxs: [] };
}

export function switchPage(state: DesignerState, pageIdx: number): DesignerState {
  if (pageIdx < 0 || pageIdx >= state.pages.length) return state;
  return { ...state, pageIdx, selectedItemIdxs: [] };
}

// ─── Helpers ────────────────────────────────────────────────────────

function shiftSelectionAfterDelete(sel: number[], deletedIdx: number): number[] {
  const out: number[] = [];
  for (const i of sel) {
    if (i === deletedIdx) continue;
    out.push(i > deletedIdx ? i - 1 : i);
  }
  return out;
}

function remapReorder(i: number, from: number, to: number): number {
  if (i === from) return to;
  if (from < i && i <= to) return i - 1;
  if (to <= i && i < from) return i + 1;
  return i;
}
