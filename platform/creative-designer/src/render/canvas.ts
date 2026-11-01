// Mounts an <expandable-magazine-banner> into a container, sized to
// the current mode, with the current page's data as attributes. The
// banner component renders the creative — we're just hosting it and
// keeping its attributes in sync with state changes. Interaction
// (drag/resize/rotate/marquee/motion) lives in render/overlay.ts.
//
// AUTHORED SIZE IS NEVER OVERWRITTEN. autoFitText (banner-component
// motion.ts) shrinks copy that overflows its box and stamps the result on
// el.style.fontSize — a RENDER-time clamp, so a too-long headline can never
// blow out a fixed IAB slot at delivery. Nothing reads that result back into
// item.fontSize. An earlier version did (syncAutoFitFontSizes), so the store
// chased the DOM: the fitted size became the authored size, autofit only ever
// shrinks, and the number ratcheted down every edit — across all three reader
// pages, since field-bound sizes sync. It also ate deliberate edits (type 9,
// get 6 back). The props panel now shows what the author chose and reports
// the drawn size separately when the two differ.

import type { DesignerState, LayoutItem, Page } from "../types";
import { currentLayout, fitReaderFieldBoxes, fitSizedFieldBoxes, currentPage, updateItem } from "../state";
import { MODES, isSized, isMultiPage, type Mode } from "../modes";
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
    "border-radius: 2px",
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
      // diff below is meaningless (textRemeasure bails on it) — the newly
      // shown format just gets the boot-path measure for its unstamped boxes.
      const viewSwitched = prevState.mode.key !== next.mode.key || prevState.pageIdx !== next.pageIdx;
      const needsRemeasure = textRemeasureIndices(prevState, next);
      applyState(wrap, banner, next);
      prevState = next;
      if (store && viewSwitched) {
        requestAnimationFrame(() => measureTextHeights(banner, store!, null));
      } else if (store && needsRemeasure.length > 0) {
        requestAnimationFrame(() => measureTextHeights(banner, store!, needsRemeasure));
      }
    },
  };

  applyState(wrap, banner, state);

  // First paint: measure every unstamped text item so the bounding box
  // hugs the text. Subsequent edits go through textRemeasureIndices above.
  // Nothing reads the auto-fitted size back — see the note on autofit below.
  if (store) {
    requestAnimationFrame(() => measureTextHeights(banner, store, null));
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
 * own text for `field` at the authored size and stamp the block-axis
 * extents (state.fitReaderFieldBoxes) — height for horizontal items,
 * width for vertical-rl. The clone is measured in the CURRENT page's
 * stage — reader pages share one geometry, so the container-query
 * context is valid for all of them. The measured ITEM's writing mode is
 * stamped on the clone explicitly: the headline's orientation is a
 * per-dimension choice, so the template element's surface can disagree
 * with the surface being measured. One store.replace, no undo entry. */
export function packReaderFieldBoxes(store: Store, field: string): void {
  if (!field) return;
  const banner = document.querySelector<HTMLElement>("#canvas-host expandable-magazine-banner");
  const shadow = banner?.shadowRoot;
  const stage = shadow?.querySelector<HTMLElement>(".design-box");
  if (!shadow || !stage || stage.clientHeight <= 0 || stage.clientWidth <= 0) return;
  // Style template: the current page's element for this field.
  const idx = currentLayout(store.state).findIndex(
    (it) => it.type === "text" && (it as { field?: string }).field === field,
  );
  const el = idx >= 0 ? shadow.querySelector<HTMLElement>(`[data-layout-idx="${idx}"]`) : null;
  if (!el) return;

  const next = fitReaderFieldBoxes(store.state, field, (text, item) => {
    const it = item as unknown as {
      fontSize?: number; width?: number; height?: number; writingMode?: string;
    };
    const vertical = it.writingMode === "vertical-rl";
    const clone = el.cloneNode(true) as HTMLElement;
    clone.textContent = text;
    clone.style.visibility = "hidden";
    clone.style.position = "absolute";
    clone.removeAttribute("data-layout-idx");
    clone.style.fontSize = `${it.fontSize ?? 5}cqmax`;
    clone.style.writingMode = vertical ? "vertical-rl" : "horizontal-tb";
    clone.style.textOrientation = vertical ? "mixed" : "";
    if (vertical) {
      // Columns grow leftward; the authored height is the column length.
      clone.style.width = "auto";
      clone.style.height = it.height != null ? `${it.height}%` : "auto";
    } else {
      clone.style.height = "auto";
      if (it.width != null) clone.style.width = `${it.width}%`;
    }
    stage.appendChild(clone);
    const block = vertical ? clone.scrollWidth : clone.scrollHeight;
    clone.remove();
    if (block <= 0) return null;
    const extent = vertical ? stage.clientWidth : stage.clientHeight;
    return Math.ceil((block / extent) * 100 * 10) / 10;
  });
  if (next !== store.state) store.replace(next);
}

/** The banner-bucket counterpart of packReaderFieldBoxes: after a synced
 * field edit (content or font size), fit the same-field box in EVERY
 * IAB bucket to the text it now renders. Reader pages share the live
 * stage's geometry; buckets don't — each is measured inside a hidden
 * `container-type: size` div at its own canonical pixel size, so cqmax
 * fonts and %-extents resolve exactly as that bucket renders them. The
 * live element for the field is the style template (typography is one
 * identity per role); per-bucket properties that legitimately differ
 * (fontSize, writingMode, geometry) are overridden per item. */
export function packSizedFieldBoxes(
  store: Store, field: string, onlySizeKey?: string, onlyGenerated?: boolean,
): void {
  if (!field) return;
  const banner = document.querySelector<HTMLElement>("#canvas-host expandable-magazine-banner");
  const shadow = banner?.shadowRoot;
  const stage = shadow?.querySelector<HTMLElement>(".design-box");
  if (!shadow || !stage) return;
  const idx = currentLayout(store.state).findIndex(
    (it) => it.type === "text" && (it as { field?: string }).field === field,
  );
  const el = idx >= 0 ? shadow.querySelector<HTMLElement>(`[data-layout-idx="${idx}"]`) : null;
  if (!el) return;

  // One measuring container per bucket, created on first use. Parented in
  // the stage so the clones inherit the same font/inheritance context.
  const containers = new Map<string, HTMLElement>();
  const containerFor = (sizeKey: string): HTMLElement | null => {
    const cached = containers.get(sizeKey);
    if (cached) return cached;
    const mode = MODES.find((m) => m.sizeKey === sizeKey);
    if (!mode) return null; // retired key (336x280 …) — still served, not editable
    const c = document.createElement("div");
    c.style.cssText =
      `position:absolute;left:0;top:0;visibility:hidden;overflow:hidden;` +
      `width:${mode.w}px;height:${mode.h}px;container-type:size;`;
    stage.appendChild(c);
    containers.set(sizeKey, c);
    return c;
  };

  const next = fitSizedFieldBoxes(store.state, field, (text, item, sizeKey) => {
    const box = containerFor(sizeKey);
    if (!box || box.clientWidth <= 0 || box.clientHeight <= 0) return null;
    const t = item as unknown as {
      fontSize?: number; lineHeight?: number; left?: number; top?: number;
      width?: number; height?: number; writingMode?: string; direction?: string;
      _generated?: boolean;
    };
    const vertical = t.writingMode === "vertical-rl";
    const mkClone = (fontSize: number): HTMLElement => {
      const clone = el.cloneNode(true) as HTMLElement;
      clone.removeAttribute("data-layout-idx");
      clone.textContent = text;
      clone.style.visibility = "hidden";
      clone.style.position = "absolute";
      clone.style.left = "0";
      clone.style.top = "0";
      // Measure at the requested size, not whatever autoFitText left on
      // the live template — same rule as packTextItemHeight.
      clone.style.fontSize = `${fontSize}cqmax`;
      clone.style.lineHeight = String(t.lineHeight ?? 1.2);
      clone.style.writingMode = vertical ? "vertical-rl" : "horizontal-tb";
      clone.style.textOrientation = vertical ? "mixed" : "";
      // Match the renderer (layout-item): vertical text balances its
      // columns, so the fit must measure balanced breaks too.
      clone.style.textWrap = vertical ? "balance" : "";
      clone.style.direction = t.direction === "rtl" ? "rtl" : "ltr";
      return clone;
    };
    // Inline extent first — the longest rendered line (column, for
    // vertical), laid out at the full space available from the box's
    // block-start anchor — then the block extent at that inline size.
    // Mirrors packTextItemHeight's fitWidth+height sequence with the
    // axes swapped for vertical-rl.
    const availInline = Math.max(1, 100 - ((vertical ? t.top : t.left) ?? 0));
    // Available BLOCK space: horizontal text stacks lines below `top`;
    // vertical-rl adds columns leftward from the box's fixed RIGHT edge.
    const availBlock = vertical
      ? Math.max(1, Math.min(100, (t.left ?? 0) + (t.width ?? 0)))
      : Math.max(1, 100 - (t.top ?? 0));
    const measureAt = (fontSize: number): { inlinePct: number; blockPct: number } | null => {
      const ic = mkClone(fontSize);
      if (vertical) { ic.style.height = `${availInline}%`; ic.style.width = "auto"; }
      else { ic.style.width = `${availInline}%`; ic.style.height = "auto"; }
      box.appendChild(ic);
      let maxLine = 0;
      try {
        const range = document.createRange();
        range.selectNodeContents(ic);
        for (const r of range.getClientRects()) {
          maxLine = Math.max(maxLine, vertical ? r.height : r.width);
        }
      } catch { /* fall back below */ }
      if (maxLine <= 0) maxLine = vertical ? ic.scrollHeight : ic.scrollWidth;
      ic.remove();
      if (maxLine <= 0) return null;
      const inlineExtent = vertical ? box.clientHeight : box.clientWidth;
      const inlinePct = Math.min(
        Math.ceil((maxLine / inlineExtent) * 100 * 10) / 10, availInline);
      const bc = mkClone(fontSize);
      if (vertical) { bc.style.height = `${inlinePct}%`; bc.style.width = "auto"; }
      else { bc.style.width = `${inlinePct}%`; bc.style.height = "auto"; }
      box.appendChild(bc);
      const block = vertical ? bc.scrollWidth : bc.scrollHeight;
      bc.remove();
      if (block <= 0) return null;
      const blockExtent = vertical ? box.clientWidth : box.clientHeight;
      const blockPct = Math.ceil((block / blockExtent) * 100 * 10) / 10;
      return { inlinePct, blockPct };
    };

    const authored = t.fontSize ?? 5;
    let size = authored;
    let m = measureAt(size);
    if (!m) return null;
    // Font correction — MACHINE items only (fitSizedFieldBoxes enforces
    // the stamp guard; skipping the walk for authored items here keeps
    // their measure identical to before). A machine-set size that cannot
    // fit the bucket even at the full available extent walks down until
    // the copy fits: a headline fanned into 970x250 at a master-tuned
    // cqmax renders glyphs taller than the banner, and hugging a box
    // around it displays the bloat instead of containing it. Floor at
    // 1.5cqmax, ~12 steps of ×0.85 — never a loop, never invisible text.
    if (t._generated === true) {
      const MIN_FONT = 1.5;
      let steps = 12;
      while (m && m.blockPct > availBlock && size > MIN_FONT && steps > 0) {
        size = Math.max(MIN_FONT, Math.round(size * 0.85 * 10) / 10);
        m = measureAt(size);
        steps -= 1;
      }
      if (!m) return null;
    }
    const fit = vertical
      ? { width: m.blockPct, height: m.inlinePct }
      : { width: m.inlinePct, height: m.blockPct };
    return size !== authored ? { ...fit, fontSize: size } : fit;
  }, onlySizeKey, onlyGenerated);
  for (const c of containers.values()) c.remove();
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
  // setAttribute fires attributeChangedCallback (= a FULL banner
  // re-render) even when the value is byte-identical, and applyState
  // runs on every store tick — zoom, selection, drags that touch
  // nothing in the page. Skipping unchanged values is what keeps a
  // video-background page editable: each redundant re-render used to
  // mint a fresh <video> decoder for the raw upload, and a few seconds
  // of dragging crashed the tab.
  const setAttrIfChanged = (name: string, value: string): void => {
    if (banner.getAttribute(name) !== value) banner.setAttribute(name, value);
  };
  setAttrIfChanged("pages", JSON.stringify(pagesForBanner));
  setAttrIfChanged("width", String(mode.w));
  setAttrIfChanged("height", String(mode.h));
  setAttrIfChanged("collapsed-page-index", "0");
  // Forward banner-level config (animation, font, etc.) so changes
  // in banner-config-panel show up in the preview without a reload.
  // Logo is expanded-view only — show it on the expanded master (where the
  // author positions it), not the per-size collapsed banners.
  const cfgForBanner = isMultiPage(state.mode)
    ? state.bannerConfig
    : { ...state.bannerConfig, logo: undefined };
  setAttrIfChanged("config", JSON.stringify(cfgForBanner));
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
