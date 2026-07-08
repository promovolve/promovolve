// Lazy layout generator. On boot, fans out Gemini calls for every
// (page × mode) cell that has no authored layout yet so the creative
// is ready to serve into any publisher slot. Subsequent state changes
// re-evaluate only when the set of empty cells grows (e.g., the user
// adds a page). An in-flight set keyed by `${pageIdx}|${modeKey}`
// prevents duplicate fetches.
//
// User-initiated regeneration still goes through the Regenerate button
// — it writes a layout, so this module sees it as "already has one"
// and stays out of the way.

import { generateLayout } from "./api/generate-layout";
import { rewriteCopy } from "./api/rewrite-copy";
import { loadBrandKit } from "./brand-kit";
import { masterColor } from "./state";
import { enforceContrast } from "./color-contrast";
import { TEMPLATES } from "./layout-templates";
import { presetLayoutFor } from "./presets";
import type { Store } from "./store";
import { applyTemplate as applyTemplateItems } from "./template-apply";
import type { DesignerState, LayoutItem, Page } from "./types";
import { MODES, WIDE_MASTER, isMultiPage, type Mode } from "./modes";

export interface AutoLayoutHandle {
  isGenerating(state: DesignerState): boolean;
  onChange(fn: () => void): () => void;
  destroy(): void;
}

export function installAutoLayoutGenerator(
  store: Store,
  opts: { autoModes?: ReadonlyArray<string> } = {},
): AutoLayoutHandle {
  const inFlight = new Set<string>();
  const listeners = new Set<() => void>();
  const generated = new Set<string>(); // cells we've already fired for
  const autoSet = opts.autoModes ? new Set(opts.autoModes) : null;

  const keyFor = (state: DesignerState): string => `${state.pageIdx}|${state.mode.key}`;

  const layoutOf = (page: Page, mode: Mode): unknown[] => {
    if (mode.key === "expanded") return page.layout ?? [];
    const key = mode.sizeKey;
    return (key && page.banners?.[key]) || [];
  };

  const fireFor = (pageIdx: number, mode: Mode): void => {
    const key = `${pageIdx}|${mode.key}`;
    if (generated.has(key) || inFlight.has(key)) return;
    const page = store.state.pages[pageIdx];
    if (!page) return;
    if (layoutOf(page, mode).length > 0) {
      generated.add(key); // already authored, don't revisit
      return;
    }
    generated.add(key);

    // Three layout sources, picked in this order for each (page × mode):
    //
    //   1. Template (LP-to-Creative first step set window.__DESIGNER__
    //      .templateId). Applies only to the two expanded variants
    //      (PC 16:9, Mobile 9:16) — the templates are designed for
    //      magazine-spread aspects, not 728x90 banners. Brand kit
    //      colours map by item role. Deterministic, no Gemini call.
    //   2. Hand-crafted preset for the mode (presets.ts). Drives every
    //      IAB sized banner regardless of templateId, and the two
    //      expanded variants when no template was picked.
    //   3. Gemini fallback. Only fires for expanded variants without
    //      a template AND without a preset — currently unreachable
    //      (every mode has a preset) but kept for future templates
    //      that exclude themselves from a particular mode.
    const isExpanded = mode.key === "expanded" || mode.key === "mobile";
    const templateId = window.__DESIGNER__?.templateId;
    if (isExpanded && templateId) {
      const template = TEMPLATES.find((t) => t.id === templateId);
      if (template) {
        const kit = loadBrandKit(window.__DESIGNER__?.campaignId);
        const variant = mode.key === "mobile" ? "mobile" : "pc";
        applyLayout(store, pageIdx, mode, applyTemplateItems(template, kit, page, variant));
        return;
      }
    }
    // Pass the brand kit so EVERY preset — expanded and IAB sizes alike —
    // picks up the determined LP font (the banner self-hosts the faces
    // across all banner buckets, so collapsed/IAB units render it too).
    const preset = presetLayoutFor(mode.key, page, loadBrandKit(window.__DESIGNER__?.campaignId));
    if (preset) {
      applyLayout(store, pageIdx, mode, preset);
      return;
    }

    inFlight.add(key);
    listeners.forEach((fn) => fn());

    void generateLayout(page, mode)
      .then((items) => {
        if (items.length === 0) return;
        applyLayout(store, pageIdx, mode, legibilizeItems(items, page));
      })
      .catch((e) => {
        console.error("[auto-layout] generate failed", e);
        generated.delete(key); // allow retry
      })
      .finally(() => {
        inFlight.delete(key);
        listeners.forEach((fn) => fn());
      });
  };

  const fanOutAllCells = (): void => {
    const state = store.state;
    const modes = autoSet
      ? MODES.filter((m) => autoSet.has(m.key))
      : MODES;
    state.pages.forEach((_, pageIdx) => {
      // The tabless wide master fires FIRST: page.layout must hold its
      // resolved fonts before the bucket presets inherit them (see
      // presets.ts expandedFont), and it's a delivery artifact (wide
      // collapsed slots, legacy readers) even though it has no tab.
      if (!autoSet || autoSet.has(WIDE_MASTER.key)) fireFor(pageIdx, WIDE_MASTER);
      modes.forEach((mode) => fireFor(pageIdx, mode));
    });
  };

  // Run on boot for every page × mode cell. Subscribe to catch future
  // page adds. Existing state changes short-circuit via the `generated`
  // set so they don't fan out N×M requests on every drag.
  fanOutAllCells();
  // After the synchronous preset pass, lock the populated state in
  // as the baseline so the user's first real commit doesn't push a
  // blank creative into the undo stack. Async Gemini responses for
  // cells without presets arrive later and still use replace — they
  // land into transient and, if the user doesn't commit, fold into
  // the next seed-equivalent moment (their first real edit).
  store.seed();
  const unsubscribe = store.subscribe(fanOutAllCells);

  return {
    isGenerating: (state) => inFlight.has(keyFor(state)),
    onChange: (fn) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    destroy: () => {
      unsubscribe();
      listeners.clear();
    },
  };
}

// User-initiated re-run of the generator for the current (page, mode).
// Unlike the background fan-out (which short-circuits when the cell
// already has items), this force-overwrites — it's the explicit
// "regenerate this size" / "reset to auto-layout" action.
//
// For the two expanded variants (PC 16:9, Mobile 9:16) Regenerate is a
// two-step Gemini call:
//   1. rewrite-copy: produce a fresh phrasing of the page's
//      tag/headline/sub/body anchored on lpTextSnapshot.
//   2. generate-layout: lay out the new copy in the current aspect.
// The rewritten page replaces store.state's page so all aspects
// (other multi-page renders + sized banners) pick up the new copy too
// — the wording is a creative-level decision, not per-aspect.
//
// IAB sized banners (300x250, 728x90, etc.) keep the deterministic
// preset path because LLMs don't compose well at tiny/odd aspects, and
// they re-read the page's copy at render time so a copy rewrite
// triggered from an expanded mode flows into them automatically.
//
// Returns a Promise so UI callers (the top-bar button, per-canvas
// button) can drive their own pending/error state. Resolves once both
// Gemini calls have completed and applied; rejects on either failure.
export function regenerateCurrentMode(store: Store): Promise<void> {
  const state = store.state;
  const pageIdx = state.pageIdx;
  const page = state.pages[pageIdx];
  if (!page) return Promise.resolve();
  const mode = state.mode;

  if (!isMultiPage(mode)) {
    const kit = loadBrandKit(window.__DESIGNER__?.campaignId);
    const preset = presetLayoutFor(mode.key, page, kit);
    if (preset) applyLayout(store, pageIdx, mode, preset);
    return Promise.resolve();
  }

  // Layout is never AI-generated — it comes from the template (or preset
  // when no template was picked). Gemini's only job here is rewriting
  // the page's copy; the layout container stays deterministic so authors
  // can predict where text/images land. This kills the 50/50-on-mobile
  // antipattern: Gemini was free-styling layouts when asked, and portrait
  // canvases coaxed it into landscape compositions even with hard hints.
  const lpContext = window.__DESIGNER__?.lpTextSnapshot ?? "";
  return rewriteCopy(page, lpContext)
    .then((rewritten) => {
      // Empty fields fall back to the existing copy — Gemini occasionally
      // omits a field and we don't want to wipe authored copy on retry.
      const merged: Page = {
        ...page,
        tag:      rewritten.tag      || page.tag      || "",
        headline: rewritten.headline || page.headline || "",
        sub:      rewritten.sub      || page.sub      || "",
        body:     rewritten.body     || page.body     || "",
      };
      applyPage(store, pageIdx, merged);

      // Re-apply the layout container against the rewritten copy.
      // Templates carry `field:` references on text items, so the new
      // copy lands in the right slots automatically.
      const items = layoutForMode(merged, mode);
      if (items && items.length > 0) {
        applyLayout(store, pageIdx, mode, legibilizeItems(items, merged));
      }
    });
}

/** The initial EXPANDED-master layout for a freshly-created page, honoring
  * the picked template (window.__DESIGNER__.templateId) — or the preset
  * when none — contrast-guarded. The boot path uses this so a picked
  * template is reflected on FIRST render, not only after a Regenerate:
  * the editor's generateCopy rewrites copy only; the layout is generated
  * in the designer, and normalizePages' generic default ignored templateId
  * (the "picked template not honored" bug). Returns null if none resolve. */
export function initialLayoutForPage(page: Page): LayoutItem[] | null {
  const items = layoutForMode(page, WIDE_MASTER);
  return items && items.length > 0 ? legibilizeItems(items, page) : null;
}

/** Resolve the deterministic layout items for a (page × mode): template
  * (with PC/mobile variant) if the LP-to-Creative flow picked one,
  * otherwise the hand-crafted preset. Never calls Gemini. */
function layoutForMode(page: Page, mode: Mode): LayoutItem[] | null {
  const kit = loadBrandKit(window.__DESIGNER__?.campaignId);
  const templateId = window.__DESIGNER__?.templateId;
  if (templateId) {
    const template = TEMPLATES.find((t) => t.id === templateId);
    if (template) {
      const variant = mode.key === "mobile" ? "mobile" : "pc";
      return applyTemplateItems(template, kit, page, variant);
    }
  }
  return presetLayoutFor(mode.key, page, kit) ?? null;
}

// Snap any text item whose Gemini-picked color fails WCAG AA against
// the page background to a legible palette pick. Wraps enforceContrast
// so callers don't have to thread through page.bg + page.accent every
// time they apply a Gemini layout.
function legibilizeItems(items: LayoutItem[], page: Page): LayoutItem[] {
  return enforceContrast(items, page.bg, page.accent) as LayoutItem[];
}

// Replace one page's flat fields without touching its layout/banners.
// Used by the regenerate flow's copy-rewrite step so the layout pass
// (which fires next) sees the new copy in the page it lays out.
function applyPage(store: Store, pageIdx: number, nextPage: Page): void {
  const state = store.state;
  store.replace({
    ...state,
    pages: state.pages.map((p, i) => (i === pageIdx ? nextPage : p)),
  });
}

// Stamp generation metadata onto a layout that's about to be applied.
//
// 1. `_generated: true` on every item, so the size-matrix fanout status
//    pill can tell "author designed this" from "Gemini / preset filled
//    this in". The flag is stripped by state.ts's updateItem the moment
//    the author touches a field.
// 2. `textFit: "shrink"` on every TEXT item. Generated copy is
//    unpredictably long (especially Gemini-returned layouts, whose
//    textFit is dropped on the Scala wire), so it must scale DOWN into
//    its slot rather than overflow/clip. applyLayout is the one funnel
//    every source (preset / template / Gemini) passes through, so this
//    covers them all. Inert on heightless items — the renderer gates
//    autofit on an explicit height — so free-growing fallback text and
//    hand-drawn boxes are untouched.
//
// Exported for unit testing; callers use it via applyLayout.
export function markGenerated(items: Page["layout"]): LayoutItem[] {
  return (items ?? []).map((it) =>
    it.type === "text"
      ? { ...it, _generated: true, textFit: "shrink" as const }
      : { ...it, _generated: true },
  );
}

// Writes items into the correct slot on the identified page. Unlike
// the old version this doesn't require the user to still be on the
// originating (page, mode) — it targets the specific cell so parallel
// Gemini responses land where they belong even if the user has moved on.
function applyLayout(store: Store, pageIdx: number, mode: Mode, items: Page["layout"]): void {
  const state = store.state;
  const page = state.pages[pageIdx];
  if (!page) return;
  // Generated creatives carry copy only: headline + body (+ image). Every
  // source funnels through here (presets.ts, a template, or Gemini), so this
  // is the one place to enforce it. Keep non-text items (image/rect/circle)
  // and text bound to `headline`/`body`; drop everything else preset —
  // the category eyebrow (`field:"tag"` → FEATURE / EXPERIENCE / STORY /
  // PLAN), sub, caption, the CTA label, and any hardcoded literal text
  // ("Read More"). These are internal metadata or preset labels, not copy.
  const stripped = (items ?? []).filter((it) => {
    if (it.type !== "text") return true;
    const field = (it as { field?: string }).field;
    return field === "headline" || field === "body";
  });
  // Banner sizes (collapsed buckets — NOT the expanded PC/Mobile readers)
  // always inherit the 1st-page expanded headline colour, even at generation.
  // Presets size colour off the page background (pickContrast), which diverges
  // from the brand/LP colour the expanded master carries — so a freshly
  // auto-generated bucket would otherwise load with a black/white headline next
  // to a brand-coloured master. Mirror the master here so they match on load,
  // the same way the typography sync keeps them in sync on edit.
  const isCollapsedBucket = mode.key !== "expanded" && mode.key !== "mobile";
  const recoloured = isCollapsedBucket
    ? stripped.map((it) => {
        if (it.type !== "text") return it;
        const field = (it as { field?: string }).field;
        const mc = field ? masterColor(state, field) : null;
        return mc && mc !== (it as { color?: string }).color
          ? ({ ...it, color: mc } as LayoutItem)
          : it;
      })
    : stripped;
  const tagged = markGenerated(recoloured);
  const nextPage: Page = mode.key === "expanded"
    ? { ...page, layout: tagged }
    : { ...page, banners: { ...(page.banners ?? {}), [mode.sizeKey!]: tagged } };
  store.replace({
    ...state,
    pages: state.pages.map((p, i) => (i === pageIdx ? nextPage : p)),
  });
}
