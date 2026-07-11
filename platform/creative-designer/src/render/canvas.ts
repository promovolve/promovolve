// Mounts an <expandable-magazine-banner> into a container, sized to
// the current mode, with the current page's data as attributes. The
// banner component renders the creative — we're just hosting it and
// keeping its attributes in sync with state changes. Interaction
// (drag/resize/rotate/marquee/motion) lives in render/overlay.ts.

import type { DesignerState, LayoutItem, Page } from "../types";
import { currentLayout, fitReaderFieldBoxes, currentPage, setReaderFieldFontSize, updateItem } from "../state";
import { isSized, isMultiPage, type Mode } from "../modes";
import type { Store } from "../store";
import { tokens } from "../ui/tokens";

export interface CanvasHandle {
  root: HTMLElement;
  banner: HTMLElement;
  update(state: DesignerState): void;
}

export function mountCanvas(container: HTMLElement, state: DesignerState, store?: Store): CanvasHandle {
  // Do not clear the container — other chrome (navbar, page tabs) is
  // mounted into the same host before us and we'd wipe them out.
  const wrap = document.createElement("div");
  wrap.className = "cd-canvas-wrap";
  wrap.style.cssText = [
    "position: relative",
    "margin: 0 auto",
    // width + aspect-ratio set per-mode + zoom in update()
    `background: ${tokens.ink900}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 6px",
    "overflow: hidden",
  ].join(";");

  const banner = document.createElement("expandable-magazine-banner");
  banner.setAttribute("mode", "edit");
  banner.id = "cd-banner";
  wrap.appendChild(banner);
  container.appendChild(wrap);

  // Track the previous state handed to update() so we can detect
  // which text items had their text content (or font-relevant
  // attributes) changed between renders and only re-measure those.
  // Avoids re-measuring every text item on every unrelated tick
  // (drag/rotate of another item, zoom change, selection, etc).
  let prevState: DesignerState = state;

  const handle: CanvasHandle = {
    root: wrap,
    banner,
    update(next) {
      // A mode/page switch swaps in a different layout, so the index-based
      // diff below is meaningless (textRemeasure/fontSync bail on it). But the
      // newly shown format still needs its autofit-fitted sizes captured into
      // the store — exactly like first paint — otherwise a collapsed bucket
      // keeps its (too-big) authored preset fontSize in the store while the DOM
      // only LOOKS shrunk, and the next selection re-render snaps the text back
      // to that authored size ("text jumps huge on click").
      const viewSwitched = prevState.mode.key !== next.mode.key || prevState.pageIdx !== next.pageIdx;
      const needsRemeasure = textRemeasureIndices(prevState, next);
      const needsFontSync = fontSyncIndices(prevState, next);
      applyState(wrap, banner, next);
      prevState = next;
      if (store && viewSwitched) {
        requestAnimationFrame(() => {
          measureTextHeights(banner, store!, null);
          requestAnimationFrame(() => syncAutoFitFontSizes(banner, store!, null));
        });
      } else if (store && (needsRemeasure.length > 0 || needsFontSync.length > 0)) {
        requestAnimationFrame(() => {
          // Font sync FIRST: it reads the auto-fitted el.style.fontSize off
          // the DOM the banner just settled this frame. measureTextHeights
          // can store.replace() → re-render synchronously, so reading before
          // it guarantees a clean post-autofit DOM.
          if (needsFontSync.length > 0) syncAutoFitFontSizes(banner, store!, needsFontSync);
          if (needsRemeasure.length > 0) measureTextHeights(banner, store!, needsRemeasure);
        });
      }
    },
  };

  applyState(wrap, banner, state);

  // First paint: measure every unstamped text item so the bounding
  // box hugs the text, then capture any auto-fit shrink so the props
  // panel reflects the effective size. Subsequent edits go through
  // textRemeasureIndices / fontSyncIndices above.
  if (store) {
    requestAnimationFrame(() => {
      measureTextHeights(banner, store, null);
      // Second rAF: read font sizes after measureTextHeights' re-render
      // (and its scheduled autofit) has settled.
      requestAnimationFrame(() => syncAutoFitFontSizes(banner, store, null));
    });
  }
  return handle;
}

// Compare prev vs next layouts for text items whose text/font/width
// changed — those affect the natural rendered height and need a
// remeasure. Returns the indices to remeasure; empty array means no
// remeasure needed.
function textRemeasureIndices(prev: DesignerState, next: DesignerState): number[] {
  if (prev === next) return [];
  if (prev.pageIdx !== next.pageIdx || prev.mode.key !== next.mode.key) return [];
  const prevItems = currentLayout(prev);
  const nextItems = currentLayout(next);
  const out: number[] = [];
  for (let i = 0; i < nextItems.length; i++) {
    const a = prevItems[i];
    const b = nextItems[i];
    if (!b || b.type !== "text") continue;
    if (!a || a.type !== "text") { out.push(i); continue; }
    if (a.text !== b.text
      || a.fontSize !== b.fontSize
      || a.fontFamily !== b.fontFamily
      || a.width !== b.width
      || a.writingMode !== b.writingMode
    ) {
      out.push(i);
    }
  }
  return out;
}

// Text items whose auto-fit RESULT may have changed since the last
// render — i.e. anything that affects whether the copy fits its box:
// the copy itself, the box geometry (width AND height — height is what
// edge-handle resizes change, and textRemeasureIndices ignores it),
// the font family/size, or the writing mode. syncAutoFitFontSizes reads
// the post-autofit size back for these so the props-panel font-size
// field tracks the rendered size. Returns indices; empty = no sync.
function fontSyncIndices(prev: DesignerState, next: DesignerState): number[] {
  if (prev === next) return [];
  if (prev.pageIdx !== next.pageIdx || prev.mode.key !== next.mode.key) return [];
  const prevItems = currentLayout(prev);
  const nextItems = currentLayout(next);
  const out: number[] = [];
  for (let i = 0; i < nextItems.length; i++) {
    const a = prevItems[i];
    const b = nextItems[i];
    if (!b || b.type !== "text") continue;
    if (!a || a.type !== "text") { out.push(i); continue; }
    if (a.text !== b.text
      || a.fontSize !== b.fontSize
      || a.fontFamily !== b.fontFamily
      || a.width !== b.width
      || a.height !== b.height
      || a.writingMode !== b.writingMode
    ) {
      out.push(i);
    }
  }
  return out;
}

/** "Fit box to text": shrink a text item's authored height down to its
  * content. measureTextHeights can't do this — scrollHeight floors at
  * clientHeight, so an oversized box always measures as exactly its
  * own height. Instead, measure a height-neutralized CLONE inside the
  * same stage (same container-query context, so cqh font sizing and
  * %-width wrap points resolve identically), then stamp the result.
  * Invoked from the props panel's Fit button. */
export function packTextItemHeight(store: Store, idx: number, fitWidth = false): void {
  const banner = document.querySelector<HTMLElement>("#canvas-host expandable-magazine-banner");
  const shadow = banner?.shadowRoot;
  const stage = shadow?.querySelector<HTMLElement>(".design-box");
  if (!shadow || !stage) return;
  const item = currentLayout(store.state)[idx];
  if (!item || item.type !== "text") return;
  const el = shadow.querySelector<HTMLElement>(`[data-layout-idx="${idx}"]`);
  if (!el) return;
  // Vertical-rl text flows top→bottom and adds COLUMNS to the left, so it
  // grows along the horizontal axis — pack WIDTH. Horizontal text grows
  // vertically — pack HEIGHT (the original behaviour).
  const vertical = item.writingMode === "vertical-rl";

  // Measure at the AUTHORED font size, not whatever autoFitText (motion.ts)
  // shrank the live element to. Without this, the box-hug clones inherit the
  // shrunken inline font-size and hug the shrunken text — so a long edit
  // ends up with a smaller font in a same-width box. Measuring at the
  // authored size instead grows the box (width to the canvas edge, then
  // height) to fit the text at full size; autoFitText then finds it fits and
  // leaves the font alone. The shrink stays a delivery-only overflow net.
  const authoredFont = `${item.fontSize ?? 5}cqmax`;

  // WIDTH hug (horizontal text): set the box width to the WIDEST RENDERED LINE
  // — grow OR shrink — so the box hugs the text in every case:
  //   • short text  → grows/shrinks to the single line (no gap, no needless wrap)
  //   • long text   → wraps at the canvas edge, then hugs the widest wrapped
  //                   line (no excessive trailing space beside a 2-line block)
  // Measured by laying the text out at the max available width (canvas minus
  // the left edge, which stays fixed) and taking the widest line rect.
  let widthPct = item.width;
  if (fitWidth && !vertical && item.width != null) {
    const avail = 100 - (item.left ?? 0);
    const wc = el.cloneNode(true) as HTMLElement;
    wc.style.visibility = "hidden";
    wc.style.position = "absolute";
    wc.removeAttribute("data-layout-idx");
    wc.style.width = `${avail}%`; // widest the box is allowed to grow
    wc.style.height = "auto";
    wc.style.fontSize = authoredFont; // measure at full size, not shrunken
    stage.appendChild(wc);
    let maxLineW = 0;
    try {
      const range = document.createRange();
      range.selectNodeContents(wc);
      for (const r of range.getClientRects()) maxLineW = Math.max(maxLineW, r.width);
    } catch { /* fall back below */ }
    if (maxLineW <= 0) maxLineW = wc.scrollWidth;
    wc.remove();
    const stageW = stage.clientWidth;
    if (stageW > 0 && maxLineW > 0) {
      const w = Math.ceil((maxLineW / stageW) * 100 * 10) / 10;
      widthPct = Math.min(w, avail); // hug widest line, never past the canvas
    }
  }

  const clone = el.cloneNode(true) as HTMLElement;
  clone.style.visibility = "hidden";
  clone.style.position = "absolute";
  clone.style.fontSize = authoredFont; // measure height at full size too
  clone.removeAttribute("data-layout-idx");
  if (vertical) {
    clone.style.width = "auto";
  } else {
    clone.style.height = "auto";
    if (widthPct != null && widthPct !== item.width) clone.style.width = `${widthPct}%`;
  }
  stage.appendChild(clone);
  const content = vertical ? clone.scrollWidth : clone.scrollHeight;
  const extent = vertical ? stage.clientWidth : stage.clientHeight;
  clone.remove();
  if (extent <= 0) return;
  const pct = (content / extent) * 100;
  if (!Number.isFinite(pct) || pct <= 0) return;
  const packed = Math.ceil(pct * 10) / 10;
  if (vertical) {
    const curW = item.width ?? 0;
    if (Math.abs(curW - packed) < 0.1) return; // already hugs its columns
    // vertical-rl starts at the right, so the box is right-anchored: keep
    // the right edge fixed and shrink leftward.
    const newLeft = (item.left ?? 0) + (curW - packed);
    store.commit(updateItem(store.state, idx, (it): LayoutItem =>
      it.type === "text" ? { ...it, width: packed, left: newLeft } : it,
    ));
  } else {
    const widthChanged = item.width != null && widthPct != null && Math.abs(item.width - widthPct) >= 0.1;
    const heightSame = item.height != null && Math.abs(item.height - packed) < 0.1;
    if (!widthChanged && heightSame) return; // already hugs
    store.commit(updateItem(store.state, idx, (it): LayoutItem =>
      it.type === "text" ? { ...it, width: widthPct ?? it.width, height: packed } : it,
    ));
  }
}

/** Per-page box fit after a font-size edit: measure EVERY reader page's
 * own text for `field` at the authored size and stamp the heights
 * (state.fitReaderFieldBoxes). The clone is measured in the CURRENT
 * page's stage — reader pages share one geometry, so the container-query
 * context is valid for all of them; only the text differs, which is
 * exactly what's cloned in. One store.replace, no undo entry. */
export function packReaderFieldBoxes(store: Store, field: string): void {
  if (!field) return;
  const banner = document.querySelector<HTMLElement>("#canvas-host expandable-magazine-banner");
  const shadow = banner?.shadowRoot;
  const stage = shadow?.querySelector<HTMLElement>(".design-box");
  if (!shadow || !stage || stage.clientHeight <= 0) return;
  // Style template: the current page's element for this field.
  const idx = currentLayout(store.state).findIndex(
    (it) => it.type === "text" && (it as { field?: string }).field === field,
  );
  const el = idx >= 0 ? shadow.querySelector<HTMLElement>(`[data-layout-idx="${idx}"]`) : null;
  if (!el) return;

  const next = fitReaderFieldBoxes(store.state, field, (text, item) => {
    const clone = el.cloneNode(true) as HTMLElement;
    clone.textContent = text;
    clone.style.visibility = "hidden";
    clone.style.position = "absolute";
    clone.style.height = "auto";
    clone.removeAttribute("data-layout-idx");
    const it = item as unknown as { fontSize?: number; width?: number };
    clone.style.fontSize = `${it.fontSize ?? 5}cqmax`;
    if (it.width != null) clone.style.width = `${it.width}%`;
    stage.appendChild(clone);
    const h = clone.scrollHeight;
    clone.remove();
    if (h <= 0) return null;
    return Math.ceil((h / stage.clientHeight) * 100 * 10) / 10;
  });
  if (next !== store.state) store.replace(next);
}

// Measure text item natural heights against the banner's design
// stage and stamp each result into state.item.height. Pass null
// for `filter` to measure all text items lacking a height (boot
// path); pass an array of indices to force-remeasure regardless
// of existing height (edit path). NOTE: this path can only GROW a
// stamped box (scrollHeight ≥ clientHeight) — true packing is the
// explicit packTextItemHeight above.
function measureTextHeights(
  banner: HTMLElement, store: Store, filter: number[] | null,
): void {
  const shadow = banner.shadowRoot;
  if (!shadow) return;
  const stage = shadow.querySelector<HTMLElement>(".design-box");
  if (!stage) return;
  const stageHeight = stage.clientHeight;
  if (stageHeight <= 0) return;

  const items = currentLayout(store.state);
  let next = store.state;
  let changed = false;
  const indices = filter ?? items.map((_, i) => i);
  for (const idx of indices) {
    const item = items[idx];
    if (!item || item.type !== "text") continue;
    // Boot-path guard: only measure items without a stamped height.
    // Edit-path always remeasures (filter is the explicit list).
    if (filter === null && item.height != null) continue;
    const el = shadow.querySelector<HTMLElement>(`[data-layout-idx="${idx}"]`);
    if (!el) continue;
    const pct = (el.scrollHeight / stageHeight) * 100;
    if (!Number.isFinite(pct) || pct <= 0) continue;
    const newHeight = Math.ceil(pct * 10) / 10;
    // Skip no-op updates so we don't thrash the store + trigger a
    // re-render + re-measure → another no-op → stable.
    if (item.height != null && Math.abs(item.height - newHeight) < 0.1) continue;
    next = updateItem(next, idx, (it): LayoutItem =>
      it.type === "text" ? { ...it, height: newHeight } : it,
    );
    changed = true;
  }
  if (changed) store.replace(next);
}

// Read the auto-fitted font-size the banner stamped onto each text
// element and write it back into the store, so the props-panel
// "font size" field shows the EFFECTIVE rendered size rather than the
// authored value. autoFitText (motion.ts) shrinks an overflowing text
// item to fit its box and writes the result to el.style.fontSize in
// cqmax — the same unit item.fontSize uses (layout-item.ts) — so the
// two are directly comparable. It only ever SHRINKS, so this can only
// reduce fontSize; a box that's later grown keeps the smaller size (no
// separate authored ceiling is retained — the chosen "match what's
// rendered" model). Mirrors measureTextHeights: post-render, replace()
// not commit() (a derived value, not its own undo step), 0.1 tolerance
// to avoid re-render thrash. Only data-autofit items are touched —
// textFit:"clip" and height-less items render at the authored size.
export function syncAutoFitFontSizes(
  banner: HTMLElement, store: Store, filter: number[] | null, retry = true,
): void {
  const shadow = banner.shadowRoot;
  if (!shadow) return;
  const items = currentLayout(store.state);
  let next = store.state;
  let changed = false;
  // Autofit-tagged elements whose inline font-size hasn't been stamped
  // yet: we read the DOM BEFORE the banner's own autofit rAF ran (the
  // desktop expanded overlay schedules its pass in a separate frame).
  // Reading now would silently keep the stale authored size in the
  // store — the source of the "text jumps huge on click" mismatch — so
  // retry those indices once on the next frame instead.
  const unstamped: number[] = [];
  const indices = filter ?? items.map((_, i) => i);
  for (const idx of indices) {
    const item = items[idx];
    if (!item || item.type !== "text") continue;
    const el = shadow.querySelector<HTMLElement>(`[data-layout-idx="${idx}"]`);
    if (!el || el.dataset.autofit !== "1") continue; // only autofitted items
    const fitted = parseFloat(el.style.fontSize); // "<n>cqmax" → n
    if (!Number.isFinite(fitted) || fitted <= 0) {
      if (retry) unstamped.push(idx);
      continue;
    }
    const rounded = Math.round(fitted * 10) / 10;
    const current = item.fontSize ?? 5;
    if (Math.abs(current - rounded) < 0.1) continue; // no-op within tolerance
    // Field-bound reader text: write the fitted size to the SAME field on
    // EVERY page, not just this one. The page-1-master subscriber
    // (syncTypographyFromPage1) instantly reverts a lone page-2/3 write
    // back to the master's value — which is exactly the "text enlarges
    // when clicked on page 2/3" bug: the DOM shows the fitted size while
    // the store keeps snapping back to page 1's bigger one. Converging
    // all pages (setReaderFieldFontSize) makes the subscriber a no-op,
    // and matches delivery, where harmonizeAutofit pins the field group
    // to the smallest fitted size across pages anyway.
    const field = (item as { field?: string }).field;
    if (field && isMultiPage(store.state.mode)) {
      next = setReaderFieldFontSize(next, field, rounded);
    } else {
      next = updateItem(next, idx, (it): LayoutItem =>
        it.type === "text" ? { ...it, fontSize: rounded } : it,
      );
    }
    changed = true;
  }
  if (changed) store.replace(next);
  if (unstamped.length > 0)
    requestAnimationFrame(() => syncAutoFitFontSizes(banner, store, unstamped, /*retry=*/ false));
}

// Zoom that fits a format into the canvas viewport, capped at 1× so small
// banners render true-size (not magnified) and large formats shrink to fit.
// Clamped to the slider's range. Reads the live #canvas-host box (minus its
// 48px padding each side); falls back to sane defaults before first layout.
export function fitZoomForMode(mode: Mode): number {
  const host = document.getElementById("canvas-host");
  const availW = (host ? host.clientWidth : 1100) - 96;
  const availH = (host ? host.clientHeight : 700) - 96;
  if (availW <= 0 || availH <= 0 || !mode.w || !mode.h) return 1;
  const fit = Math.min(availW / mode.w, availH / mode.h);
  return Math.max(0.25, Math.min(1, fit));
}

function applyState(wrap: HTMLElement, banner: HTMLElement, state: DesignerState): void {
  const { mode, zoom } = state;
  wrap.style.aspectRatio = mode.aspect;
  // 1× == the banner's TRUE delivered size: the design-box is drawn at
  // mode.w × mode.h px at zoom 1, so the zoom readout means "× of real size"
  // (a 300×250 reads 1× at 300px; a 1600×900 reads 1× at 1600px). The view
  // fits-to-window on each format switch (fitZoomForMode), capped at 1× so
  // small banners show true-size rather than magnified; the slider/pan take it
  // from there. CSS aspect-ratio derives the height from the width.
  const z = zoom ?? 1;
  wrap.style.width = `${mode.w * z}px`;
  wrap.style.maxWidth = "none";

  // In IAB-size modes, the canvas authors the cover frame — that's the
  // only thing publishers see at that size in production. Page nav is
  // conceptually irrelevant; force the cover page so 1/3 ↔ 2/3 ↔ 3/3
  // navigation in expanded mode doesn't ripple into IAB editing.
  // Multi-page modes (Expanded PC + Expanded Mobile) honor the active
  // pageIdx so the pager widget actually navigates.
  const coverIdx = state.bannerConfig.coverPageIdx ?? 0;
  const page = isMultiPage(state.mode)
    ? currentPage(state)
    : (state.pages[coverIdx] ?? state.pages[0] ?? null);
  if (!page) {
    banner.removeAttribute("pages");
    return;
  }

  // Give the banner a single-page array so the component renders just
  // the currently-edited page at the active aspect. The sized-mode
  // pagesData copy routes banners[sizeKey] into .layout to match the
  // non-expanded authoring model.
  const pagesForBanner = [prepareRenderPage(page, state)];
  banner.setAttribute("pages", JSON.stringify(pagesForBanner));
  banner.setAttribute("width", String(mode.w));
  banner.setAttribute("height", String(mode.h));
  banner.setAttribute("collapsed-page-index", "0");
  // Forward banner-level config (animation, font, etc.) so changes
  // in banner-config-panel show up in the preview without a reload.
  // Logo is expanded-view only — show it on the expanded master (where the
  // author positions it), not the per-size collapsed banners.
  const cfgForBanner = isMultiPage(state.mode)
    ? state.bannerConfig
    : { ...state.bannerConfig, logo: undefined };
  banner.setAttribute("config", JSON.stringify(cfgForBanner));
}

function prepareRenderPage(page: Page, state: DesignerState): Page {
  const source = isSized(state.mode)
    ? (state.mode.sizeKey ? page.banners?.[state.mode.sizeKey] ?? [] : [])
    : (page.layout ?? []);
  // Hidden items keep their array slot so data-layout-idx and
  // overlay/layers indices stay aligned; the banner renders them
  // with opacity 0 so they're invisible on canvas. Overlay hit
  // testing and marquee both skip them, so a hidden item is
  // effectively gone until the Layers panel un-hides it.
  const layout = source.map((it) => {
    const anyIt = it as LayoutItem & { hidden?: boolean };
    return anyIt.hidden ? { ...it, opacity: 0 } : it;
  });
  // Clear `banners` on the render-copy so the banner component's
  // render() falls through to our prepared `layout` instead of
  // re-reading the raw page.banners[sizeKey] (which would bypass
  // the hidden→opacity:0 mapping for every IAB sized mode — that
  // was the "eye icon only clears the rectangle" symptom).
  // Authoring state in store.state.pages is untouched.
  return {
    ...page,
    layout,
    banners: {},
    designAspect: state.mode.aspect,
  };
}
