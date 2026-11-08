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
import { currentLayout, masterColor } from "./state";
import { enforceContrast } from "./color-contrast";
import { TEMPLATES } from "./layout-templates";
import { markGenerated, presetLayoutFor } from "./presets";
import { packSizedFieldBoxes } from "./render/canvas";
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

  // Self-healing buckets: pack MACHINE-GENERATED boxes when their tab
  // opens. Buckets have no edit gesture of their own (field-bound items
  // can't be inline-edited in sized tabs), so a bucket whose boxes were
  // generated oversized — before generation-time packing existed, or by
  // any older pipeline — could never heal: no edit means no re-fit, and
  // the author packed by hand, box by box (reported 2026-08-17/18).
  //
  // Scope is exactly `_generated: true`: the flag marks "machine filled
  // this in, the author never touched it" (updateItem strips it on the
  // first touch, and the banners map round-trips opaquely through save,
  // so it survives load). There is no author intent on such a box to
  // preserve — this is generation-time packing completing late, NOT the
  // banned load-time normalization of authored work. Hand-sized boxes
  // (wrap-width choices included) are never touched; packTextItemHeight
  // no-ops when a box already hugs, so revisits are idempotent, and the
  // commit it makes is undoable like any gesture.
  let lastTabKey = "";
  const packGeneratedOnTabOpen = (): void => {
    const state = store.state;
    const key = `${state.pageIdx}|${state.mode.key}`;
    if (key === lastTabKey) return;
    lastTabKey = key;
    if (state.mode.key === "expanded") return;
    // The reader keeps its template geometry — except the Map poster, whose
    // 9:16 master is machine-composed (posterLayoutFor) and packs here like
    // any bucket, band-capped (packSizedFieldBoxes).
    const poster = state.mode.key === "mobile" && state.bannerConfig.format === "map";
    if (state.mode.key === "mobile" && !poster) return;
    const sizeKey = state.mode.sizeKey;
    if (!sizeKey) return;
    whenStageReady(() => {
      // The author may have moved on while the stage settled.
      if (`${store.state.pageIdx}|${store.state.mode.key}` !== key) return;
      // Via the FIELD packer, not packTextItemHeight: it preserves
      // `_generated` (packTextItemHeight commits through updateItem,
      // which strips the flag — the heal would mislabel the bucket
      // "authored" in the fanout status), it fits BOTH axes for
      // vertical-rl, and it walks a machine-bloated font down to fit
      // (see packSizedFieldBoxes). onlyGenerated + this sizeKey keeps
      // the blast radius at exactly the machine boxes of this tab.
      const fields = new Set<string | null>();
      currentLayout(store.state).forEach((it) => {
        if (it.type !== "text") return;
        if (!(it as { _generated?: boolean })._generated) return;
        const f = (it as { field?: string }).field;
        if (f) fields.add(f);
        else if (poster) fields.add(null); // literal beats 2-3
      });
      fields.forEach((f) => packSizedFieldBoxes(store, f, sizeKey, true));
    });
  };

  // Run on boot for every page × mode cell. Subscribe to catch future
  // page adds. Existing state changes short-circuit via the `generated`
  // set so they don't fan out N×M requests on every drag.
  fanOutAllCells();
  packGeneratedOnTabOpen();
  // After the synchronous preset pass, lock the populated state in
  // as the baseline so the user's first real commit doesn't push a
  // blank creative into the undo stack. Async Gemini responses for
  // cells without presets arrive later and still use replace — they
  // land into transient and, if the user doesn't commit, fold into
  // the next seed-equivalent moment (their first real edit).
  store.seed();
  const unsubscribe = store.subscribe(fanOutAllCells);
  const unsubscribePack = store.subscribe(packGeneratedOnTabOpen);

  return {
    isGenerating: (state) => inFlight.has(keyFor(state)),
    onChange: (fn) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    destroy: () => {
      unsubscribe();
      unsubscribePack();
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

// markGenerated lives in presets.ts (the Map poster uses it too); re-exported
// here for callers and tests that know it as part of applyLayout.
export { markGenerated };

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

  // Pack generated boxes at the source. Template/preset geometry is sized
  // for generic copy; real copy — especially vertical-rl Japanese, where
  // the packing axis is WIDTH — can leave the text hugging one edge of a
  // huge stale rectangle that the author then packs by hand, box by box
  // (reported 2026-08-17, 300x600). Generation is authoring-by-machine, so
  // machine output arrives packed; nothing here ever touches an authored
  // box: the pack is scoped to THIS mode's sizeKey, and the items it fits
  // were created two lines up. Collapsed buckets only — the strip above
  // guarantees every generated text item is field-bound, so the per-field
  // bucket packer (hidden container-type:size probes) measures this bucket
  // whether or not its tab is on screen; the expanded/mobile readers keep
  // template geometry designed for their aspect. Stage-ready retry covers
  // the boot fan-out, which fires before the canvas has mounted.
  if (isCollapsedBucket && mode.sizeKey) {
    const fields = new Set<string>();
    for (const it of tagged) {
      if (it.type !== "text") continue;
      const f = (it as { field?: string }).field;
      if (f) fields.add(f);
    }
    if (fields.size > 0) {
      const sizeKey = mode.sizeKey;
      whenStageReady(() => {
        fields.forEach((f) => packSizedFieldBoxes(store, f, sizeKey));
      });
    }
  }
}

/** Run `fn` once the live canvas stage is mounted and measurable,
  * retrying across frames (the boot fan-out generates every bucket before
  * first paint). ALWAYS defers at least one frame, even when the stage is
  * already up: the packer clones the current tab's field element as its
  * style template, and running synchronously after store.replace would
  * read the PREVIOUS render's DOM — where the new layout's indices can
  * point at a different item entirely (an image where the headline now
  * is). One frame is the same settle the edit paths give their packs.
  * Gives up quietly after ~20 frames: packing is an enhancement, an
  * unmeasurable canvas must not queue work forever. */
function whenStageReady(fn: () => void, tries = 20): void {
  requestAnimationFrame(() => {
    const stage = document.querySelector<HTMLElement>("#canvas-host expandable-magazine-banner")
      ?.shadowRoot?.querySelector<HTMLElement>(".design-box");
    if (stage && stage.clientWidth > 0) { fn(); return; }
    if (tries > 0) whenStageReady(fn, tries - 1);
  });
}
