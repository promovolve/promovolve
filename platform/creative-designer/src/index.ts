// Entry point. Boots the designer from window.__DESIGNER__ and
// mounts a #designer-root div into <body>.

import type {BannerConfig, DesignerContext, LayoutItem, Page } from "./types";
import { applyBrandKitFontsToText, currentLayout, inheritBannerColors, initialState, readerFieldStart, setReaderFieldStart, setZoom, syncTypographyFromPage1 } from "./state";
import { isMultiPage } from "./modes";
import { looksLikePercentLayout, normalizePages } from "./normalize";
import { loadBrandKit, subscribeBrandKit } from "./brand-kit";
import { syncCanvasFonts } from "./canvas-fonts";
import { Store } from "./store";
import { mountCanvas, fitZoomForMode } from "./render/canvas";
import { mountOverlay } from "./render/overlay";
import { mountLogoOverlay } from "./render/logo-overlay";
import { installKeyboard } from "./interaction/keyboard";
import { installPan } from "./interaction/pan";
import { mountBannerConfigPanel } from "./ui/banner-config-panel";
import { mountPageBgPanel } from "./ui/page-bg-panel";
import { mountImageDrop } from "./ui/image-drop";
import { mountPropsPanel } from "./ui/props-panel";
import { mountAnimationPanel } from "./ui/animation-panel";
import { mountSizeMatrix } from "./ui/size-matrix";
import { mountMenuBar } from "./ui/menu-bar";
import { mountSidebar } from "./ui/sidebar";
import { mountToolRail } from "./ui/tool-rail";
import { mountSelectionBar } from "./ui/selection-bar";
import { mountLayers } from "./ui/layers";
import { mountCanvasFoot } from "./ui/canvas-foot";
import { installAutoLayoutGenerator, initialLayoutForPage } from "./auto-layout";
import { autoCropOnBoot } from "./auto-crop";
import { prewarmSaliency } from "./saliency";
import { mountGuides } from "./interaction/guides";
import { mountRulers, updateRulers } from "./render/rulers";
import { mountSizeGate } from "./ui/size-gate";
import { initTheme, tokens } from "./ui/tokens";

function mountRoot(): HTMLElement {
  // Full-viewport flex column. Padding is intentionally absent — each
  // component cell (matrix, sidebar, canvas wrap) carries its own
  // borders/spacing.
  const root = document.createElement("div");
  root.id = "designer-root";
  root.style.cssText = [
    "display: flex",
    "flex-direction: column",
    "height: 100vh",
    "width: 100vw",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink200}`,
    `font-family: ${tokens.sans}`,
    "font-size: 12px",
    "overflow: hidden",
    "-webkit-font-smoothing: antialiased",
  ].join(";");
  document.body.appendChild(root);
  return root;
}

interface Shell {
  topBarHost: HTMLElement;
  railHost: HTMLElement;
  matrixHost: HTMLElement;
  selectionBarHost: HTMLElement;
  canvasHost: HTMLElement;
  canvasFootHost: HTMLElement;
  sidebarHost: HTMLElement;
}

/**
 * Create the shell layout. Returns the hosts that callers mount
 * content into (pre-zoom px; chrome hosts render at UI_SCALE):
 *
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  topBarHost — the whole banner                   (40px)  │
 *   ├──────┬───────────────────────────────────────┬───────────┤
 *   │ rail │  matrixHost — sizes                    │ sidebar   │
 *   │ Host ├───────────────────────────────────────┤ Host      │
 *   │ 90px │  selectionBarHost — this item (40px+)  │ 300px     │
 *   │      ├───────────────────────────────────────┤ values    │
 *   │ what │  canvasHost              (pan/zoom)   │ only      │
 *   │ can I├───────────────────────────────────────┤           │
 *   │ add? │  canvasFootHost — page, size, metrics │           │
 *   └──────┴───────────────────────────────────────┴───────────┘
 */
function renderShell(root: HTMLElement): Shell {
  const topBarHost = div(root, { flex: "0 0 auto" });

  const body = div(root, { display: "flex", flexDirection: "row", flex: "1", minWidth: "0", minHeight: "0" });

  const railHost = div(body, { flex: "0 0 auto", width: "90px", minHeight: "0", display: "flex" });

  const centre = div(body, {
    display: "flex",
    flexDirection: "column",
    flex: "1",
    minWidth: "0",
    minHeight: "0",
  });

  // The size strip scrolls horizontally, so the centre column's span
  // costs nothing; the rail and sidebar run flush up to the top bar.
  const matrixHost = div(centre, { flex: "0 0 auto", minWidth: "0", display: "flex" });

  const selectionBarHost = div(centre, { flex: "0 0 auto" });

  const canvasHost = document.createElement("div");
  canvasHost.id = "canvas-host";
  // The canvas region: the page colour with hairline dots, so the
  // drawable area reads as "work surface" against the chrome.
  // minHeight 0 lets it shrink inside the flex column — and yield to
  // the selection bar when that wraps to a second row.
  canvasHost.style.cssText = [
    "flex: 1",
    // overflow:hidden — pan is a CSS-transform translate on the
    // canvas wrap (handled by interaction/pan.ts), not native scroll.
    // Native scroll fought us: the browser would clamp scrollLeft/Top
    // back to a "valid" range on layout recalc, which felt like the
    // canvas snapping back after release.
    "overflow: hidden",
    "min-height: 0",
    `background: radial-gradient(circle at 1px 1px, ${tokens.ink600} 1px, transparent 0) ${tokens.ink800}`,
    "background-size: 24px 24px",
    "display: flex",
    "align-items: safe center",
    "justify-content: safe center",
    "padding: 48px",
    "position: relative",
  ].join(";");
  centre.appendChild(canvasHost);

  const canvasFootHost = div(centre, { flex: "0 0 auto" });

  const sidebarHost = div(body, { flex: "0 0 auto", width: "300px", minHeight: "0", display: "flex" });

  return { topBarHost, railHost, matrixHost, selectionBarHost, canvasHost, canvasFootHost, sidebarHost };
}

function div(parent: HTMLElement, style: Partial<CSSStyleDeclaration>): HTMLDivElement {
  const el = document.createElement("div");
  Object.assign(el.style, style);
  parent.appendChild(el);
  return el;
}

function boot(ctx: DesignerContext): void {
  // Inject the theme CSS variables and apply the stored theme (default
  // dark) before anything renders, so the first paint is already themed.
  initTheme();

  // Kick the saliency model download off as early as possible — ort.js
  // is ~700 KB from CDN and the u2netp ONNX is ~4.5 MB from R2. Doing
  // it now means the auto-crop pass below can start inferencing the
  // moment the UI is up, instead of waiting on a cold network round-
  // trip when the first uncropped image is found.
  void prewarmSaliency().catch(() => { /* logged inside saliency.ts */ });

  // Layouts normally arrive pre-generated from the editor, so this
  // flag is a no-op in production. We pass `true` so the dev harness
  // (and any other path that hands us flat-field-only pages) gets a
  // sensible auto-generated layout instead of a blank canvas.
  // normalize.ts only synthesises a default when no usable layout
  // already exists — pre-generated pages are untouched.
  // Which incoming pages already had a real (percent-coords) layout —
  // captured BEFORE normalizePages, which MUTATES page.layout to fill a
  // generic default. Saved/edited creatives have one; fresh ones (straight
  // from the editor, which rewrites copy only) don't.
  const rawPages: unknown[] = Array.isArray(ctx.pages) ? ctx.pages : [];
  const hadLayout = rawPages.map((p) => {
    const l = (p as Page).layout;
    return Array.isArray(l) && l.length > 0 && looksLikePercentLayout(l as LayoutItem[]);
  });
  const pages = normalizePages(ctx.pages, {
    fillDefaultLayout: true,
    // Same kit the expanded presets use, so a synthesized collapsed layout
    // gets the LP font (heading/body) instead of the hardcoded Georgia/sans.
    kit: loadBrandKit(window.__DESIGNER__?.campaignId),
  });
  // Honor the picked layout template on FIRST render. The editor's
  // generateCopy rewrites copy only — the layout is generated here — and
  // normalizePages' generic default ignores templateId, so without this a
  // picked template only appeared after a Regenerate ("picked template not
  // honored"). Re-lay-out only FRESH pages; saved creatives keep the layout
  // the author already edited.
  if (window.__DESIGNER__?.templateId) {
    pages.forEach((page, i) => {
      if (hadLayout[i]) return;
      const items = initialLayoutForPage(page);
      if (items) page.layout = items;
    });
  }
  // Banner sizes always show the 1st-page expanded headline colour. Persisted
  // creatives can carry a stale per-bucket colour (older saves / bg-derived
  // preset colour), so reconcile on load before the store is seeded.
  const colourReconciled = inheritBannerColors(pages);
  // Boot into the portrait reader — the surface delivery renders on
  // every device; the 16:9 tab is now the wide-collapsed/legacy layout
  // (see modes.ts).
  // A resumed creative carries its saved banner-level config (logo,
  // paper stock, reading direction, entrance); seed the store with it
  // so Save/Publish round-trips the blob instead of resetting it to
  // defaults — the silent reset is what kept eating the brand logo.
  let savedBannerConfig: BannerConfig | undefined;
  if (ctx.bannerConfigJson) {
    try {
      const parsed = JSON.parse(ctx.bannerConfigJson) as BannerConfig;
      if (parsed && typeof parsed === "object") savedBannerConfig = parsed;
    } catch { /* malformed blob → fresh defaults */ }
  }
  const store = new Store(initialState(colourReconciled, "mobile", savedBannerConfig));
  // A failed save re-renders the designer with the submitted state and an
  // explanation — surface it. Dismissible; auto-fades after 12s.
  if (ctx.errorMsg) {
    const toast = document.createElement("div");
    toast.style.cssText = [
      "position: fixed",
      "top: 14px",
      "left: 50%",
      "transform: translateX(-50%)",
      "z-index: 10000",
      "max-width: 560px",
      `background: ${tokens.ink900}`,
      `color: ${tokens.ink100}`,
      `border: 1px solid ${tokens.err}`,
      "border-radius: 2px",
      "padding: 10px 36px 10px 14px",
      "font-size: 13px",
      "line-height: 1.45",
      "box-shadow: 0 8px 24px rgba(0,0,0,0.45)",
    ].join(";");
    toast.textContent = ctx.errorMsg;
    const x = document.createElement("button");
    x.textContent = "×";
    x.style.cssText = `position:absolute;top:6px;right:8px;background:none;border:none;color:${tokens.ink300};font-size:16px;cursor:pointer;`;
    x.addEventListener("click", () => toast.remove());
    toast.appendChild(x);
    document.body.appendChild(toast);
    setTimeout(() => { toast.style.transition = "opacity 0.6s"; toast.style.opacity = "0"; setTimeout(() => toast.remove(), 700); }, 12000);
  }
  // Dev-server-only hook so the vite fixture can be driven by Playwright
  // (state injection + assertions). import.meta.hot exists only under
  // `vite serve` — the committed static bundle is a --mode development
  // BUILD, where env.DEV would leak this into production.
  if (import.meta.hot) (window as unknown as { __STORE__?: Store }).__STORE__ = store;
  // Brand logo (Phase 2a): if the campaign's brand kit carries a logo and
  // this creative doesn't already have one, drop it onto the banner config
  // so it renders on every page + size. Default top-left, small; saved
  // creatives keep their own logo/position (the `!logo` guard).
  {
    const kit = loadBrandKit(window.__DESIGNER__?.campaignId);
    if (kit.logoUrl && !store.state.bannerConfig.logo) {
      store.replace({
        ...store.state,
        bannerConfig: {
          ...store.state.bannerConfig,
          logo: { src: kit.logoUrl, left: 4, top: 4, width: 18, height: 12 },
        },
      });
    }
  }

  const root = mountRoot();
  // Viewport gate: covers the shell while the window is below the
  // minimum the layout needs; designer stays mounted underneath.
  mountSizeGate();
  const shell = renderShell(root);
  // Scale the UI chrome up a notch. The panels/toolbars use hardcoded px
  // sizes (no central font token), so zoom is the proportional knob —
  // text AND spacing grow together. Applied to every chrome host EXCEPT
  // the canvas, which keeps its own scale (the user zooms it separately).
  // 1.2, not 1.15: chrome heights sit on a multiple-of-5 pre-zoom grid
  // (top bar 40, strip 90, selection bar 40, tab-bar 35, collapsed rows 35)
  // so every zoomed position lands on an exact integer CSS pixel — at
  // 1.15 nothing is integral, each zoom-root rounded independently, and
  // structural lines that must MEET across the canvas/sidebar divider
  // missed by a pixel (2026-08-18).
  const UI_SCALE = "1.2";
  for (const h of [shell.topBarHost, shell.railHost, shell.matrixHost, shell.selectionBarHost, shell.canvasFootHost, shell.sidebarHost]) {
    h.style.setProperty("zoom", UI_SCALE);
    // Figures line up in every readout (sizes, coordinates, zoom). Chrome
    // hosts only: the canvas renders the advertiser's own type.
    h.style.setProperty("font-variant-numeric", "tabular-nums");
  }

  const menuBar = mountMenuBar(shell.topBarHost, store, ctx);
  mountToolRail(shell.railHost, store);
  const matrix = mountSizeMatrix(shell.matrixHost, store);
  const selectionBar = mountSelectionBar(shell.selectionBarHost, store);
  const canvas = mountCanvas(shell.canvasHost, store.state, store);
  const overlay = mountOverlay(canvas.root, store);
  const logoOverlay = mountLogoOverlay(canvas.root, store);
  // Drop an image file onto the work surface → choose Background texture
  // or positioned Image element.
  mountImageDrop(canvas.root, store);
  // Alignment guides: an overlay layer that draws thin lines during
  // drag/resize when items line up with each other or canvas edges.
  mountGuides(canvas.root);
  // Pixel rulers on sized banner modes. Hidden on expanded PC/mobile
  // (responsive) and when the user has toggled them off.
  mountRulers(canvas.root);
  updateRulers(store.state.mode);
  const canvasFt = mountCanvasFoot(shell.canvasFootHost, store);
  const sidebar = mountSidebar(shell.sidebarHost);
  const bannerConfig = mountBannerConfigPanel(sidebar.bannerConfigSection, store);
  const pageBg = mountPageBgPanel(sidebar.pageBgSection, store);
  const props = mountPropsPanel(sidebar.propsSection, store);
  const animation = mountAnimationPanel(sidebar.animationSection, store);
  const layers = mountLayers(sidebar.layersSection, store);

  // Page 1 is the typographic master: its font size / face / writing
  // mode propagate to every other page. Idempotent — registered BEFORE
  // the UI subscriber so a propagating change re-fires with the synced
  // state and the panels render the followers' updated values. Run once
  // up front to cover a loaded draft whose pages are already populated.
  const syncPage1Typography = (): void => {
    const synced = syncTypographyFromPage1(store.state);
    if (synced !== store.state) store.replace(synced);
  };
  syncPage1Typography();
  store.subscribe(syncPage1Typography);

  // Start-corner sync: moving a field-bound reader text item on ANY page
  // moves it on all three, so the headline (and the body) begins at the same
  // point throughout the magazine. Only the corner — each page keeps its own
  // box extent, since the pages carry different copy and fitReaderFieldBoxes
  // packs each box to its own text.
  //
  // Driven by DIFFING state rather than hooked into the drag, so every way of
  // moving a box is covered by one rule: dragging, the left/top fields,
  // nudging with the arrow keys, the alignment tools. Mirrors the font-size
  // rule — whichever page you edit is the one that wins.
  //
  // Deliberately NOT run on load: a saved creative whose pages were placed
  // independently would otherwise be silently rewritten the moment it opens.
  // Existing drafts converge when the author next moves the item.
  let startSyncPrev = store.state;
  const syncFieldStarts = (): void => {
    const prev = startSyncPrev;
    const next = store.state;
    startSyncPrev = next;
    if (prev === next || !isMultiPage(next.mode)) return;
    // A page/format switch swaps in a different layout, so the index-based
    // diff below would compare unrelated items.
    if (prev.pageIdx !== next.pageIdx || prev.mode.key !== next.mode.key) return;
    const before = currentLayout(prev);
    const after = currentLayout(next);
    for (let i = 0; i < after.length; i++) {
      const b = after[i];
      const a = before[i];
      if (!b || b.type !== "text" || !b.field) continue;
      if (!a || a.type !== "text" || a.field !== b.field) continue;
      if (a.left === b.left && a.top === b.top) continue;
      const start = readerFieldStart(b);
      if (!start) continue;
      const synced = setReaderFieldStart(store.state, b.field, start);
      if (synced !== store.state) store.replace(synced);
    }
  };
  store.subscribe(syncFieldStarts);

  // Register the draft's self-hosted web fonts so the canvas draws with
  // the faces that actually ship (see canvas-fonts.ts). Once up front,
  // then debounced on state changes — font choices change rarely, and
  // for CJK drafts the derived subset URL tracks the text, so per-
  // keystroke recomputation would spray pointless fetch attempts.
  syncCanvasFonts(store.state.pages, ctx.bannerScriptUrl);
  let fontSyncTimer: number | undefined;
  store.subscribe((state) => {
    if (fontSyncTimer !== undefined) window.clearTimeout(fontSyncTimer);
    fontSyncTimer = window.setTimeout(
      () => syncCanvasFonts(state.pages, ctx.bannerScriptUrl),
      800
    );
  });

  // Fit-to-window on format switch. 1× is the banner's true size, so a fresh
  // format would otherwise either overflow (large) or sit tiny (small); fit it
  // to the canvas (capped at 1×) whenever the mode changes. Idempotent via the
  // lastFitModeKey guard, so the re-render from setZoom doesn't loop.
  let lastFitModeKey: string | null = null;
  const fitOnModeChange = (state: typeof store.state): void => {
    if (state.mode.key === lastFitModeKey) return;
    lastFitModeKey = state.mode.key;
    const fit = fitZoomForMode(state.mode);
    if (Math.abs(fit - state.zoom) > 0.01) store.replace(setZoom(store.state, fit));
  };
  store.subscribe(fitOnModeChange);

  store.subscribe((state) => {
    menuBar.update(state);
    matrix.update(state);
    selectionBar.update(state);
    canvas.update(state);
    overlay.update(state);
    logoOverlay.update(state);
    canvasFt.update(state);
    bannerConfig.update(state);
    pageBg.update(state);
    props.update(state);
    animation.update(state);
    layers.update(state);
    updateRulers(state.mode);
  });
  menuBar.update(store.state);
  matrix.update(store.state);
  selectionBar.update(store.state);
  canvasFt.update(store.state);
  bannerConfig.update(store.state);
  pageBg.update(store.state);
  props.update(store.state);
  animation.update(store.state);
  layers.update(store.state);
  logoOverlay.update(store.state);
  // Initial fit: boot-time store changes may fire the subscriber before the
  // canvas-host is laid out (fitZoomForMode then uses fallback dims), so clear
  // the guard and re-fit once on the next frame with the real box.
  requestAnimationFrame(() => { lastFitModeKey = null; fitOnModeChange(store.state); });
  // Keep the on-creative logo in sync with the brand kit: setting/clearing
  // the brand logo updates the logo's src (preserving the author's position),
  // or removes it. Makes the brand-kit modal's logo show live.
  // Track the kit's last-known logo so a removal can be told apart from a
  // cold/shadowed save. Seed from the kit that was current at load.
  let lastKitLogoUrl = loadBrandKit(window.__DESIGNER__?.campaignId)?.logoUrl;
  subscribeBrandKit((kit) => {
    // Re-apply the kit's heading font to existing text (fonts[0], one face
    // throughout) so editing the brand kit propagates to the canvas, not
    // just future layouts.
    let next = applyBrandKitFontsToText(store.state, kit);
    // Brand logo, folded into the same update.
    const bc = next.bannerConfig;
    if (kit.logoUrl) {
      if (bc.logo?.src !== kit.logoUrl) {
        const logo = bc.logo
          ? { ...bc.logo, src: kit.logoUrl }
          : { src: kit.logoUrl, left: 4, top: 4, width: 18, height: 12 };
        next = { ...next, bannerConfig: { ...bc, logo } };
      }
    } else if (lastKitLogoUrl && bc.logo) {
      // Only mirror a removal when the kit ACTUALLY HAD a logo and it was
      // just cleared (the modal's Remove). A save where the kit never had a
      // logo — a cold browser, or localStorage shadowing a server kit —
      // must NOT wipe a logo the author already placed on the creative.
      next = { ...next, bannerConfig: { ...bc, logo: undefined } };
    }
    lastKitLogoUrl = kit.logoUrl;
    if (next !== store.state) store.replace(next);
  });

  installKeyboard(store);
  // Figma-style canvas panning: space+drag, H toggles hand tool, and
  // middle-mouse drag. Trackpad two-finger pan already works via
  // canvas-host's overflow:auto.
  installPan(shell.canvasHost);
  // Auto-generate every mode that has no layout yet — Expanded PC,
  // Expanded Mobile, and all IAB sizes. The auction needs every slot
  // size authored to serve anywhere, and lazy on-tab-visit fanout
  // keeps users from clicking through each size manually.
  installAutoLayoutGenerator(store);

  // Run a background saliency pass over uncropped image items. Resumed
  // drafts skip everything that already has a crop, so this is a no-op
  // on revisits. The first ONNX inference takes ~1s for the model
  // download + warm-up; subsequent items are ~200ms each, capped at 3
  // concurrent. Fire-and-forget — the UI is fully usable while it
  // runs and the commit at the end is one undo step.
  void autoCropOnBoot(store).catch((err) => {
    console.warn("[creative-designer] auto-crop on boot failed", err);
  });

  console.info(
    "[creative-designer] booted",
    {
      campaignId: ctx.campaignId,
      creativeName: ctx.creativeName,
      bannerSize: ctx.bannerSize,
      pageCount: pages.length,
    },
  );
}

const ctx = window.__DESIGNER__;
if (ctx) {
  boot(ctx);
} else {
  console.warn("[creative-designer] window.__DESIGNER__ not set — did the template inject context?");
}

export {};
