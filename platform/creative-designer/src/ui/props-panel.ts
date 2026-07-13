// Properties panel for the selected item. Shown to the right of the
// canvas when something is selected; hidden otherwise.
//
// Two update paths:
//   - Selection change → rebuild the panel DOM (different item may
//     have different fields).
//   - Property change on the same selected item → patch field values
//     in place without rebuilding, to avoid yanking focus off a
//     currently-edited input.
//
// Edit semantics:
//   - `input` events → store.replace (live preview, no undo step)
//   - `change` events (blur, Enter, picker commit) → store.commit
//     (one undo step per completed edit)

import { refitItemCropToBox } from "../auto-crop";
import { packReaderFieldBoxes, packTextItemHeight } from "../render/canvas";
import { isMultiPage } from "../modes";
import { adoptTextOverride, currentItem, currentLayout, currentPage, fieldColorSyncKey, hasLocalTextOverride, isFieldColorSynced, propagateTypography, setItemContent, setReaderFieldFontSize, setSyncFieldColor, TYPO_SYNC_KEYS, updateItem } from "../state";
import type { Store } from "../store";
import type { DesignerState, ImageItem, LayoutItem, RectItem } from "../types";
import { scrimGradient, SCRIM_EDGES, type ScrimEdge, type ScrimSpec } from "../scrim";
import { contrastRatio, WCAG_AA_NORMAL } from "../color-contrast";
import { mountKitColorChips } from "./brand-kit-chips";
import { openAssetModal } from "./asset-modal";
import { openCropModal } from "./crop-modal";
import { tokens } from "./tokens";

export interface PropsPanelHandle {
  update(state: DesignerState): void;
}

// Typography font menu. System buckets (always render) + the allow-listed
// Google families we self-host in the expanded view (value = the full CSS
// stack so it both renders a sensible fallback and triggers self-hosting at
// publish; mirror of GoogleFontCatalog.scala / banner font-catalog.ts). The
// trailing bucket is the fallback when the woff2 isn't loaded.
const SYSTEM_FONTS = ["sans-serif", "serif", "monospace", "Georgia", "Helvetica Neue"];
const GOOGLE_FONT_STACKS: string[] = ([
  ["Montserrat", "sans-serif"], ["Poppins", "sans-serif"], ["Roboto", "sans-serif"], ["Open Sans", "sans-serif"],
  ["Lato", "sans-serif"], ["Inter", "sans-serif"], ["Raleway", "sans-serif"], ["Nunito", "sans-serif"],
  ["Nunito Sans", "sans-serif"], ["Work Sans", "sans-serif"], ["Rubik", "sans-serif"], ["Mulish", "sans-serif"],
  ["Manrope", "sans-serif"], ["DM Sans", "sans-serif"], ["Be Vietnam Pro", "sans-serif"], ["Oswald", "sans-serif"],
  ["Bebas Neue", "sans-serif"], ["Archivo", "sans-serif"], ["Barlow", "sans-serif"], ["Kanit", "sans-serif"],
  ["Josefin Sans", "sans-serif"], ["Quicksand", "sans-serif"], ["Karla", "sans-serif"], ["Figtree", "sans-serif"],
  ["Plus Jakarta Sans", "sans-serif"], ["Space Grotesk", "sans-serif"], ["Outfit", "sans-serif"],
  ["Playfair Display", "serif"], ["Merriweather", "serif"], ["Lora", "serif"], ["PT Serif", "serif"],
  ["Noto Serif", "serif"], ["Source Serif 4", "serif"], ["Cormorant Garamond", "serif"], ["EB Garamond", "serif"],
  ["Libre Baskerville", "serif"], ["Crimson Text", "serif"], ["Bitter", "serif"], ["DM Serif Display", "serif"],
  ["Roboto Slab", "serif"],
] as Array<[string, string]>).map(([fam, bucket]) => `${fam}, ${bucket}`);

/** Font dropdown options for the current value. Determined value first (so a
  * named instance like "Montserrat Thin, sans-serif" is always visible/kept),
  * then system buckets, then the allow-listed families. */
function fontOptions(current: string | undefined): string[] {
  const opts = [...SYSTEM_FONTS, ...GOOGLE_FONT_STACKS];
  const cur = (current ?? "").trim();
  if (cur && !opts.includes(cur)) opts.unshift(cur);
  return opts;
}

interface RenderedState {
  key: string; // "<idx>|<type>[|ov]"
  setters: Record<string, (item: LayoutItem) => void>;
}

// Structural suffix for the rebuild key — everything that changes the
// PANEL SHAPE for the same idx|type must be in here, since setters only
// patch values. "|ov": field-bound text with a baked per-size override
// (hint ↔ editable content swap). Fields with a colour page-sync toggle
// (headline/body) additionally encode the page index and that field's
// flag (colour picker ↔ "synced from page 1" hint swap).
function structuralMarker(state: DesignerState, item: LayoutItem): string {
  let m = hasLocalTextOverride(item) ? "|ov" : "";
  const field = item.type === "text" ? (item as { field?: string }).field : undefined;
  if (field && fieldColorSyncKey(field) != null) {
    m += `|pg${state.pageIdx}|cs${isFieldColorSynced(state, field) ? 1 : 0}`;
  }
  return m;
}

export function mountPropsPanel(container: HTMLElement, store: Store): PropsPanelHandle {
  // Rendered into a section of the shared sidebar (see ui/sidebar.ts).
  // The panel itself provides no positioning — it inherits layout from
  // its parent section and toggles display:none when no single item
  // is selected.
  const panel = document.createElement("div");
  panel.className = "cd-props";
  panel.style.cssText = [
    "padding: 14px",
    "display: none",
  ].join(";");
  container.appendChild(panel);

  let rendered: RenderedState | null = null;

  // The item-properties panel hides when no single item is selected.
  // The sidebar itself stays visible — the Page Background section
  // above now gives the sidebar purpose even when nothing is selected,
  // and the Layers section at the bottom should always be reachable.

  return {
    update(state) {
      const item = currentItem(state);
      // Props panel only makes sense for a single selection. With 0 or
      // 2+ selected, hide just the item-props panel (not the sidebar).
      // common-field editing for multi-select would need a different
      // UI surface.
      if (!item || state.selectedItemIdxs.length !== 1) {
        panel.style.display = "none";
        rendered = null;
        return;
      }
      const idx = state.selectedItemIdxs[0]!;
      panel.style.display = "block";
      // Key must match the one build() returns. Any mismatch here
      // triggers a full rebuild, which destroys input focus mid-edit
      // and lets global Delete/Backspace hit the canvas.
      // The override marker (field-bound text with a baked local copy) is
      // part of the key: ticking/unticking the sync checkbox swaps panel
      // STRUCTURE (read-only hint ↔ editable content field), which setters
      // can't patch — it needs the rebuild. Baked-text keystrokes don't
      // move the marker (text stays non-empty), so focus survives editing.
      const key = `${idx}|${item.type}${structuralMarker(state, item)}`;
      if (!rendered || rendered.key !== key) {
        rendered = build(panel, idx, item, store);
      } else {
        for (const fn of Object.values(rendered.setters)) fn(item);
      }
    },
  };
}

function build(panel: HTMLElement, idx: number, item: LayoutItem, store: Store): RenderedState {
  panel.innerHTML = "";
  const setters: Record<string, (item: LayoutItem) => void> = {};

  const header = document.createElement("div");
  header.style.cssText = `font-size:11px;letter-spacing:2px;color:${tokens.ink300};margin-bottom:12px;text-transform:uppercase;`;
  header.textContent = item.type === "rect" && item.scrim ? "scrim" : item.type;
  panel.appendChild(header);

  // Two related sections: geometry (position/size/rotation/opacity)
  // and content (type-specific). Animation moved to its own sidebar
  // tab — see mountAnimationPanel. Accordion opens one at a time.
  const sections: HTMLElement[] = [];

  // Drop-shadow controls. Appended into whichever group fits the item:
  // for TEXT it lives under Typography (a text-shadow is a typographic
  // property), for box-types it gets its own "Shadow" group (box-shadow).
  // Toggle drives whether the shadow object exists; offsets/blur/color
  // edit the sub-fields when present. Defaults match the renderer's so a
  // freshly-enabled shadow looks right with no further tweaks.
  const addShadowFields = (target: HTMLElement): void => {
    // Drop shadow as a NUMERIC control (0 = off), consistent with the other
    // Edge Effects (feather / vignette / torn edge) — not a boolean toggle.
    // The primary value is the shadow's blur; offset x / y and color are the
    // additional shadow properties that apply once it's on. All fields are
    // always present, so toggling needs no panel rebuild; x/y/color edits are
    // no-ops while it's off (blur 0 → shadow cleared).
    const sh = item.shadow;
    setters.shadowBlur = numberField(target, "drop shadow", sh?.blur ?? 0,
      (v, c) => mutate(store, idx, (it) => ({
        ...it,
        shadow: v > 0
          ? { x: it.shadow?.x ?? 0, y: it.shadow?.y ?? 0.4, blur: v, color: it.shadow?.color ?? "rgba(0,0,0,0.5)" }
          : undefined,
      }), c), 0, 20);
    setters.shadowX = numberField(target, "shadow x", sh?.x ?? 0,
      (v, c) => mutate(store, idx, (it) => (it.shadow ? { ...it, shadow: { ...it.shadow, x: v } } : it), c), -10, 10);
    setters.shadowY = numberField(target, "shadow y", sh?.y ?? 0.4,
      (v, c) => mutate(store, idx, (it) => (it.shadow ? { ...it, shadow: { ...it.shadow, y: v } } : it), c), -10, 10);
    setters.shadowColor = colorField(target, "shadow color", normalizeShadowColor(sh?.color),
      (v, c) => mutate(store, idx, (it) => (it.shadow ? { ...it, shadow: { ...it.shadow, color: v } } : it), c));
  };

  const transform = group("Transform");
  setters.left = numberField(transform, "left (%)", item.left ?? 0, (v, c) => mutate(store, idx, (it) => ({ ...it, left: v }), c));
  setters.top  = numberField(transform, "top (%)",  item.top  ?? 0, (v, c) => mutate(store, idx, (it) => ({ ...it, top:  v }), c));
  if (item.type !== "circle") {
    // For cropped images, a committed width/height edit re-fits the
    // crop window to the new box aspect — these fields used to be the
    // one path that could silently desync crop and box (the renderer
    // then re-crops around center, drifting the subject).
    const refit = (c: boolean): void => {
      if (c && item.type === "image") void refitItemCropToBox(store, idx);
    };
    setters.width  = numberField(transform, "width (%)",  item.width  ?? 30, (v, c) => { mutate(store, idx, (it) => ({ ...it, width:  v }), c); refit(c); });
    setters.height = numberField(transform, "height (%)", item.height ?? 20, (v, c) => { mutate(store, idx, (it) => ({ ...it, height: v }), c); refit(c); });
  } else {
    setters.radius = numberField(transform, "radius (%)", item.radius ?? 5, (v, c) => mutate(store, idx, (it) => ({ ...it, radius: v }), c));
  }
  setters.rotation = numberField(transform, "rotation", item.rotation ?? 0, (v, c) => mutate(store, idx, (it) => ({ ...it, rotation: v }), c), -180, 180);
  setters.opacity = numberField(transform, "opacity", item.opacity ?? 1, (v, c) => mutate(store, idx, (it) => ({ ...it, opacity: v }), c), 0, 1);
  // (The "CTA click target" checkbox is gone: delivery navigates on a
  // deliberate tap anywhere on the sheet, so there's nothing for the
  // author to wire. item.ctaTarget lives on only as a styling hint —
  // see color-contrast.ts.)
  panel.appendChild(transform);
  sections.push(transform);

  // Type-specific groups. Text is split into Text / Typography /
  // Layout so no single open accordion section overflows the panel
  // viewport. Image and rect/circle stay as one group each — their
  // field counts are already small.
  // Content (the text itself / the image) is edited only in an EXPANDED
  // reader — PC or mobile (isMultiPage) — the single source every banner
  // size renders. In a collapsed size you adjust layout + style (position
  // / font / colour), not WHAT is shown; the content field is replaced by
  // a read-only resolved hint there.
  const isExpanded = isMultiPage(store.state.mode);
  if (item.type === "text") {
    const textGroup = group("Text");
    const boundField = (item as { field?: string }).field;
    const bakedText = (item as { text?: string }).text;
    const overridden = !!boundField && bakedText != null && bakedText !== "";
    // Field-bound text in a SIZE: synced from the expanded view by default
    // (one source of truth), but overridable — unticking bakes the current
    // resolved value locally so this size edits its own copy; re-ticking
    // drops the bake and falls back to the shared field. Mirrors the
    // image "Main image · syncs across all sizes" pin row.
    if (!isExpanded && boundField) {
      // The toggle changes panel STRUCTURE (hint ↔ editable field, plus the
      // resync hint below), so the row renders its state statically — the
      // override marker in the rebuild key guarantees a rebuild on toggle.
      const syncRow = document.createElement("label");
      syncRow.style.cssText = `display:flex;align-items:center;gap:8px;margin-bottom:${overridden ? "4px" : "10px"};font-size:11px;color:${overridden ? tokens.amber : tokens.ink200};cursor:pointer;`;
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = !overridden;
      cb.style.cssText = "margin:0;cursor:inherit;";
      cb.addEventListener("change", () => {
        if (cb.checked) {
          // Re-tick = ADOPT: this size's text + colour become the shared
          // version everywhere, then the item re-links.
          store.commit(adoptTextOverride(store.state, idx));
        } else {
          mutateContent(store, idx, effectiveContent(store, item, "text"), "text", true);
        }
      });
      syncRow.append(cb, document.createTextNode(
        overridden
          ? `Overridden for this size (${boundField})`
          : `Synced across all sizes (${boundField})`,
      ));
      appendToGroup(textGroup, syncRow);
      if (overridden) {
        const resync = document.createElement("div");
        resync.style.cssText = `margin:0 0 10px 24px;font-size:11px;color:${tokens.ink300};font-style:italic;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;`;
        resync.textContent = "Re-tick to apply this text & color to all sizes";
        resync.title = "Text and color edits stay in this size while unticked. Ticking the box makes them the shared version every size shows.";
        appendToGroup(textGroup, resync);
      }
    }
    if (isExpanded || !boundField || overridden) {
      // Expanded view, LOCAL text (no field — e.g. dropped straight onto a
      // size; it exists nowhere else, so it must be editable here), or a
      // field item whose sync was unticked above: editable.
      setters.text = textField(textGroup, "content", effectiveContent(store, item, "text"), (v, c) => mutateContent(store, idx, v, "text", c));
    } else {
      contentHint(textGroup, effectiveContent(store, item, "text"));
    }
    // Per-field colour page-sync (headline + body): while pages[0]'s
    // flag is on (absent = on), page 1 is the only place that field's
    // colour is edited — pages 2/3 show a hint instead of the picker.
    // Mirrors the Page Background "Sync color across all 3 pages" row.
    const hasColorPageSync = boundField != null && fieldColorSyncKey(boundField) != null;
    const fieldSynced = boundField != null && isFieldColorSynced(store.state, boundField);
    const colorLocked = hasColorPageSync && fieldSynced && store.state.pageIdx > 0;
    if (colorLocked) {
      const lockHint = document.createElement("div");
      lockHint.style.cssText = `margin:0 0 10px;font-size:11px;color:${tokens.ink300};font-style:italic;`;
      lockHint.textContent = "Color synced from page 1";
      appendToGroup(textGroup, lockHint);
    } else {
      setters.color = colorField(textGroup, "color", item.color ?? "#ffffff", (v, c) => mutate(store, idx, (it) => ({ ...it, color: v }), c));
      mountKitColorChips(textGroup, {
        campaignId: window.__DESIGNER__?.campaignId ?? "",
        onPick: (color) => mutate(store, idx, (it) => ({ ...it, color }), true),
      });
    }
    if (hasColorPageSync && boundField) {
      // Always rendered for the field (stable group height when
      // paging); operable only while page 1 is selected.
      const firstPage = store.state.pageIdx === 0;
      const syncColorRow = document.createElement("label");
      syncColorRow.style.cssText = `display:flex;align-items:center;gap:8px;margin-bottom:10px;font-size:11px;color:${tokens.ink200};${firstPage ? "cursor:pointer;" : "opacity:0.45;cursor:not-allowed;"}`;
      if (!firstPage) syncColorRow.title = "Set from page 1";
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = fieldSynced;
      cb.disabled = !firstPage;
      cb.style.cssText = "margin:0;cursor:inherit;";
      cb.addEventListener("change", () => {
        store.commit(setSyncFieldColor(store.state, boundField, cb.checked));
      });
      syncColorRow.append(cb, document.createTextNode("Sync color across all 3 pages"));
      appendToGroup(textGroup, syncColorRow);
    }
    setters.contrastWarning = contrastWarningRow(textGroup, store);
    panel.appendChild(textGroup);
    sections.push(textGroup);

    const typo = group("Typography");
    setters.fontFamily = selectField(typo, "font", item.fontFamily ?? "sans-serif",
      fontOptions(item.fontFamily),
      (v, c) => mutate(store, idx, (it) => ({ ...it, fontFamily: v }), c),
      true, // full-width: font stacks are too long for the inline layout
    );
    setters.fontWeight = selectField(typo, "weight", String(item.fontWeight ?? "normal"),
      ["normal", "bold", "100", "200", "300", "400", "500", "600", "700", "800", "900"],
      (v, c) => mutate(store, idx, (it) => ({ ...it, fontWeight: v }), c),
    );
    // Capture the size at the START of an edit interaction so the commit-time
    // proportional fan-out scales the other formats by the true ratio (not
    // an incremental per-keystroke one). Resets on commit and on panel rebuild
    // (i.e. when a different item is selected).
    let fontSizeBaseline: number | null = null;
    setters.fontSize = numberField(typo, "font size", item.fontSize ?? 5, (v, c) => {
      if (fontSizeBaseline == null) {
        const cur = currentLayout(store.state)[idx];
        fontSizeBaseline = cur && cur.type === "text" ? (cur.fontSize ?? 5) : v;
      }
      mutateFontSize(store, idx, v, fontSizeBaseline, c);
      if (c) fontSizeBaseline = null;
    });
    setters.lineHeight = numberField(typo, "line height", item.lineHeight ?? 1.2,
      (v, c) => mutate(store, idx, (it) => ({ ...it, lineHeight: v }), c), 0.5, 3);
    setters.textAlign = selectField(typo, "align", item.textAlign ?? "left",
      ["left", "center", "right", "justify"],
      (v, c) => mutate(store, idx, (it) => ({ ...it, textAlign: v as "left" | "center" | "right" | "justify" }), c),
    );
    // Drop shadow is a typographic property for text (renders as
    // text-shadow), so it lives here rather than in a separate group.
    addShadowFields(typo);
    panel.appendChild(typo);
    sections.push(typo);

    const layoutGroup = group("Layout");
    // No "fit" control: text with an explicit height shrinks-to-fit by
    // default now (the renderer treats anything but textFit:"clip" as
    // shrink), so it never clips unexpectedly and stays consistent across
    // pages via the reader's harmonizer. Legacy items with textFit:"clip"
    // still clip; nothing in the UI sets it anymore.
    // Where short copy sits inside a worst-case-sized box. Boxes are
    // regions (stable composition contract); this distributes the
    // headroom instead of pooling it all at the bottom.
    setters.verticalAlign = selectField(layoutGroup, "v-align", item.verticalAlign ?? "top",
      ["top", "middle", "bottom"],
      (v, c) => mutate(store, idx, (it) => ({ ...it, verticalAlign: v as "top" | "middle" | "bottom" }), c),
    );
    setters.writingMode = selectField(layoutGroup, "writing", item.writingMode ?? "horizontal-tb",
      ["horizontal-tb", "vertical-rl"],
      (v, c) => mutate(store, idx, (it) => ({ ...it, writingMode: v as "horizontal-tb" | "vertical-rl" }), c),
    );
    setters.direction = selectField(layoutGroup, "direction", item.direction ?? "ltr",
      ["ltr", "rtl"],
      (v, c) => mutate(store, idx, (it) => ({ ...it, direction: v as "ltr" | "rtl" }), c),
    );
    panel.appendChild(layoutGroup);
    sections.push(layoutGroup);
  } else if (item.type === "image") {
    const content = group("Image");
    // No pin/sync toggle here (removed 2026-07-13, user decision): THE
    // image is defined by page 1 of the expanded view — normalize.ts
    // reconciles page.img from it at load, and "Replace image…" fans it
    // out. Sizes only place/crop it; a per-view checkbox was confusing.
    // "Replace image…" sets/replaces the shared main from the asset library;
    // in a size you adjust placement + crop.
    if (isExpanded) {
      const replaceBtn = document.createElement("button");
      replaceBtn.type = "button";
      replaceBtn.textContent = "Replace image…";
      replaceBtn.style.cssText = `background:${tokens.ink700};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:4px;padding:4px 8px;font:inherit;font-size:11px;cursor:pointer;align-self:flex-start;`;
      replaceBtn.addEventListener("click", () => openAssetModal(store));
      // Into the accordion BODY (not the section), so it collapses with
      // the "Image" section instead of dangling below the header.
      appendToGroup(content, replaceBtn);
    } else {
      contentHint(content, "Image set in the Expanded view");
    }
    // Fill = cover (may crop to the box, honours the crop window).
    // Fit  = contain (whole image at its natural proportion, letterboxed).
    selectField(content, "fill mode", item.fillMode ?? "fill", ["fill", "fit"],
      (v, c) => mutate(store, idx, (it) => {
        if (it.type !== "image") return it;
        return v === "fit"
          ? { ...it, fillMode: "fit" as const, crop: undefined }
          : { ...it, fillMode: "fill" as const };
      }, c),
    );
    // Crop only applies in Fill mode. The panel does NOT rebuild on a
    // fillMode toggle (it updates fields via setters), so build the crop
    // row once in a wrapper and toggle the wrapper reactively via a setter
    // — otherwise the crop button stays clickable in Fit.
    const cropWrap = document.createElement("div");
    appendToGroup(content, cropWrap);
    cropRow(cropWrap, idx, item, store);
    setters.cropVisible = (it) => {
      cropWrap.style.display = (it.type === "image" && it.fillMode === "fit") ? "none" : "";
    };
    setters.cropVisible(item);
    // Corner radius — previously only rects exposed this control. Wrapped
    // so it can be hidden in Fit mode (see fitEffectsVisible below).
    const radiusWrap = document.createElement("div");
    appendToGroup(content, radiusWrap);
    setters.borderRadius = numberField(radiusWrap, "corner radius", item.borderRadius ?? 0,
      (v, c) => mutate(store, idx, (it) => ({ ...it, borderRadius: v || undefined }), c), 0, 50);
    panel.appendChild(content);
    sections.push(content);

    // Edge effects. Each numeric control treats 0 as "off" (stored as
    // undefined, mirroring borderRadius), so the controls are always
    // present and toggling one needs no panel rebuild.
    const fx = group("Edge Effects");
    setters.feather = numberField(fx, "feather", item.feather ?? 0,
      (v, c) => mutate(store, idx, (it) => ({ ...it, feather: v || undefined }), c), 0, 30);
    setters.vignette = numberField(fx, "vignette", item.vignette?.strength ?? 0,
      (v, c) => mutate(store, idx, (it) => {
        if (it.type !== "image") return it;
        return { ...it, vignette: v > 0 ? { strength: v, color: it.vignette?.color } : undefined };
      }, c), 0, 1);
    setters.tornEdge = numberField(fx, "torn edge", item.tornEdge ?? 0,
      (v, c) => mutate(store, idx, (it) => ({ ...it, tornEdge: v || undefined }), c), 0, 10);
    // A drop shadow is the same family of box/edge effect, so it lives here
    // under Edge Effects rather than a separate "Shadow" group. (rect/circle
    // have no Edge Effects group, so they keep a standalone Shadow group
    // below.)
    addShadowFields(fx);
    panel.appendChild(fx);
    sections.push(fx);
    // Corner radius + edge effects act on the image's BOX. In Fit mode
    // the image is letterboxed inside that box (object-fit:contain), so
    // these only touch the transparent margin — visually inert. Hide
    // them in Fit, reactively (mirrors cropVisible; the panel doesn't
    // rebuild on a fillMode toggle).
    setters.fitEffectsVisible = (it) => {
      const fit = it.type === "image" && it.fillMode === "fit";
      radiusWrap.style.display = fit ? "none" : "";
      fx.style.display = fit ? "none" : "";
    };
    setters.fitEffectsVisible(item);
  } else if (item.type === "rect" && item.scrim) {
    buildScrimGroup(panel, sections, idx, item, store, setters);
  } else {
    const content = group("Fill");
    setters.fill = colorField(content, "color", item.fill ?? "#1f2937", (v, c) => mutate(store, idx, (it) => ({ ...it, fill: v }), c));
    mountKitColorChips(content, {
      campaignId: window.__DESIGNER__?.campaignId ?? "",
      onPick: (color) => mutate(store, idx, (it) => ({ ...it, fill: color }), true),
    });
    if (item.type === "rect") {
      setters.borderRadius = numberField(content, "corner radius", item.borderRadius ?? 0,
        (v, c) => mutate(store, idx, (it) => ({ ...it, borderRadius: v || undefined }), c), 0, 50);
    }
    panel.appendChild(content);
    sections.push(content);
  }

  // rect/circle get their own "Shadow" group (renders as box-shadow) — they
  // have no Edge Effects group to fold it into. Images put shadow under Edge
  // Effects (above); text shadow lives in Typography (it's a text-shadow, a
  // typographic property).
  if (item.type !== "text" && item.type !== "image" && !(item.type === "rect" && item.scrim)) {
    const shadowGroup = group("Shadow");
    addShadowFields(shadowGroup);
    panel.appendChild(shadowGroup);
    sections.push(shadowGroup);
  }

  wireAccordion(sections);

  return { key: `${idx}|${item.type}${structuralMarker(store.state, item)}`, setters };
}

// ─── Scrim group ─────────────────────────────────────────────────────

// Controls for a gradient scrim (a rect with item.scrim). Every edit
// patches the scrim params and re-derives `fill` in the same mutation, so
// the rendered gradient always matches the knobs. The opaque/native color
// picker only handles solid colours — transparency comes from the
// `strength` slider (alpha) and the fade is baked into the gradient — so a
// scrim is fully editable without a gradient-stop UI.
function buildScrimGroup(
  panel: HTMLElement, sections: HTMLElement[], idx: number,
  item: RectItem, store: Store, setters: Record<string, (item: LayoutItem) => void>,
): void {
  const g = group("Scrim");
  const scrim = item.scrim!;

  const apply = (patch: Partial<ScrimSpec>, commit: boolean): void => {
    mutate(store, idx, (it) => {
      if (it.type !== "rect" || !it.scrim) return it;
      const next = { ...it.scrim, ...patch };
      return { ...it, scrim: next, fill: scrimGradient(next) };
    }, commit);
  };
  const readScrim = (it: LayoutItem): ScrimSpec | undefined =>
    it.type === "rect" ? it.scrim : undefined;

  setters.scrimEdge = selectField(g, "edge", scrim.edge, [...SCRIM_EDGES],
    (v) => apply({ edge: v as ScrimEdge }, true), false,
    (it) => readScrim(it)?.edge);

  setters.scrimColor = colorField(g, "color", scrim.color,
    (v, c) => apply({ color: v }, c), (it) => readScrim(it)?.color);
  mountKitColorChips(g, {
    campaignId: window.__DESIGNER__?.campaignId ?? "",
    onPick: (color) => apply({ color }, true),
  });

  // Optional second colour stop. The checkbox toggles it; on enable it
  // defaults to the primary colour (no surprise hue) so the author then
  // picks the second colour. Both stops always fade to transparent.
  const cb = document.createElement("input");
  cb.type = "checkbox";
  cb.checked = scrim.color2 != null;
  cb.style.cssText = "margin:0;cursor:pointer;";
  const cbRow = document.createElement("label");
  cbRow.style.cssText = `display:flex;align-items:center;gap:8px;margin:8px 0 6px;font-size:11px;color:${tokens.ink200};cursor:pointer;`;
  cbRow.append(cb, document.createTextNode("Two-colour gradient"));
  appendToGroup(g, cbRow);
  cb.addEventListener("change", () => {
    const cur = currentItem(store.state);
    const base = cur && cur.type === "rect" ? cur.scrim : undefined;
    apply({ color2: cb.checked ? (base?.color2 ?? base?.color ?? scrim.color) : undefined }, true);
  });
  setters.scrimC2Toggle = (it) => { cb.checked = readScrim(it)?.color2 != null; };

  const c2Wrap = document.createElement("div");
  appendToGroup(g, c2Wrap);
  setters.scrimColor2 = colorField(c2Wrap, "color 2", scrim.color2 ?? scrim.color,
    (v, c) => apply({ color2: v }, c),
    (it) => readScrim(it)?.color2 ?? readScrim(it)?.color);
  setters.scrimColor2Visible = (it) => {
    c2Wrap.style.display = readScrim(it)?.color2 != null ? "" : "none";
  };
  setters.scrimColor2Visible(item);

  setters.scrimStrength = numberField(g, "strength", scrim.strength,
    (v, c) => apply({ strength: v }, c), 0, 1, (it) => readScrim(it)?.strength);

  setters.borderRadius = numberField(g, "corner radius", item.borderRadius ?? 0,
    (v, c) => mutate(store, idx, (it) => ({ ...it, borderRadius: v || undefined }), c), 0, 50);

  panel.appendChild(g);
  sections.push(g);
}

// <input type="color"> only accepts opaque #rrggbb. Strip the alpha
// channel from rgba()/hex8 values so the picker still shows something
// when the renderer's default semi-transparent shadow is active.
function normalizeShadowColor(c: string | undefined): string {
  if (!c) return "#000000";
  if (c.startsWith("#") && c.length === 9) return c.slice(0, 7);
  if (c.startsWith("rgba")) return "#000000";
  if (c.startsWith("rgb")) return "#000000";
  return c;
}

// Note: the old "Fit to text" button was removed — packing the box to its
// text is now triggered by clicking the text item on the canvas (see
// fitTextItem in render/overlay.ts), which calls packTextItemHeight.

// ─── Image crop row ──────────────────────────────────────────────────

// Renders the current crop readout (or "no crop") plus two buttons:
// "Crop image…" opens the modal, "Clear" strips the crop field so
// the banner reverts to the fast <img>+object-fit:cover path.
function cropRow(parent: HTMLElement, idx: number, item: ImageItem, store: Store): void {
  const wrap = document.createElement("div");
  wrap.style.cssText = "display:flex;align-items:center;gap:8px;margin:10px 0 4px;";

  const label = document.createElement("span");
  label.style.cssText = `flex:0 0 80px;color:${tokens.ink300};font-size:11px;`;
  label.textContent = "crop";

  const status = document.createElement("span");
  status.style.cssText = `flex:1;font-family:${tokens.sans};font-size:10px;color:${tokens.ink400};`;
  const c = item.crop;
  status.textContent = c
    ? `${c.w.toFixed(0)}×${c.h.toFixed(0)}% @ ${c.x.toFixed(0)},${c.y.toFixed(0)}`
    : "none";

  const editBtn = document.createElement("button");
  editBtn.type = "button";
  editBtn.textContent = c ? "Edit…" : "Crop…";
  editBtn.style.cssText = [
    "padding: 3px 8px",
    "background: transparent",
    `color: ${tokens.ink200}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 3px",
    "font: inherit",
    "font-size: 10px",
    "cursor: pointer",
  ].join(";");
  editBtn.addEventListener("click", () => openCropModal(store, idx, item));

  wrap.append(label, status, editBtn);

  if (c) {
    const clearBtn = document.createElement("button");
    clearBtn.type = "button";
    clearBtn.textContent = "×";
    clearBtn.title = "Clear crop";
    clearBtn.style.cssText = [
      "width: 18px",
      "height: 18px",
      "padding: 0",
      "background: transparent",
      `color: ${tokens.ink400}`,
      "border: none",
      "cursor: pointer",
      "font-size: 14px",
      "line-height: 1",
    ].join(";");
    clearBtn.addEventListener("click", () => {
      store.commit(updateItem(store.state, idx, (it) => {
        if (it.type !== "image") return it;
        const { crop: _drop, ...rest } = it as ImageItem & { crop?: unknown };
        return rest as ImageItem;
      }));
    });
    wrap.appendChild(clearBtn);
  }

  appendToGroup(parent, wrap);
}

// ─── Field builders ──────────────────────────────────────────────────

// Accordion section. Returns the outer element callers append to the
// panel; field builders route through row() which targets the inner
// .cd-group-body so collapsed sections hide their fields. The click
// handler is wired by wireAccordion() so sibling sections can close
// when this one opens.
function group(label: string): HTMLElement {
  const section = document.createElement("div");
  section.className = "cd-group";
  section.style.cssText = `border-bottom:1px solid ${tokens.ink500};`;

  const header = document.createElement("button");
  header.type = "button";
  header.className = "cd-group-header";
  header.style.cssText = `width:100%;display:flex;align-items:center;justify-content:space-between;gap:6px;background:none;border:none;color:${tokens.ink300};font:inherit;font-size:10px;letter-spacing:2px;padding:10px 0 8px;cursor:pointer;text-transform:uppercase;`;
  const labelSpan = document.createElement("span");
  labelSpan.textContent = label;
  const caret = document.createElement("span");
  caret.textContent = "▾";
  caret.className = "cd-group-caret";
  caret.style.cssText = `font-size:11px;color:${tokens.ink400};transition:transform 0.15s;`;
  header.append(labelSpan, caret);

  const body = document.createElement("div");
  body.className = "cd-group-body";
  body.style.cssText = "padding-bottom:10px;";

  section.append(header, body);
  return section;
}

// Set the initial open/closed state for the per-item props sections.
// Click handling lives at the sidebar level (single delegated listener,
// scoped to sibling sections within the same parent <section>).
//
// Single-open invariant is scoped to the per-item props group only —
// the supplied `sections` array. Peer sections in other sidebar
// containers (notably Layers, which lives in its own <section> and
// rebuilds on every selection change) keep whatever state the user
// left them in; collapsing Layers when the author clicks a layer row
// is the opposite of helpful.
function wireAccordion(sections: HTMLElement[]): void {
  const setOpen = (s: HTMLElement, open: boolean): void => {
    const body = s.querySelector<HTMLElement>(":scope > .cd-group-body");
    const caret = s.querySelector<HTMLElement>(":scope > .cd-group-header > .cd-group-caret");
    if (body) body.style.display = open ? "" : "none";
    if (caret) caret.style.transform = open ? "" : "rotate(-90deg)";
  };
  // Default-open the CORRESPONDING type-specific group (Text / Image /
  // Fill), which is always the section right after the generic Transform
  // group — so switching to a component lands on that component's own
  // properties, not the geometry box. Falls back to the only section when
  // there's no type-specific group.
  const openIdx = sections.length > 1 ? 1 : 0;
  sections.forEach((s, i) => setOpen(s, i === openIdx));
}

// Append into the section's body if it's an accordion group, else the
// element itself — lets field builders work outside group() too.
function appendToGroup(section: HTMLElement, el: HTMLElement): void {
  const body = section.querySelector<HTMLElement>(":scope > .cd-group-body");
  (body ?? section).appendChild(el);
}

function row(parent: HTMLElement, label: string, input: HTMLElement): void {
  const row = document.createElement("label");
  row.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:6px;";
  const lbl = document.createElement("span");
  lbl.textContent = label;
  lbl.style.cssText = `flex:0 0 80px;color:${tokens.ink300};font-size:11px;`;
  (input.style as CSSStyleDeclaration).cssText += ";flex:1;min-width:0;";
  row.append(lbl, input);
  appendToGroup(parent, row);
}

// Stacked variant — label on its own line, input spanning the FULL panel
// width below it. Used for fields whose value is too long to read in the
// ~170px the inline 80px-label layout leaves (e.g. the font stack
// "Plus Jakarta Sans, sans-serif").
function rowStacked(parent: HTMLElement, label: string, input: HTMLElement): void {
  const wrap = document.createElement("label");
  wrap.style.cssText = "display:flex;flex-direction:column;align-items:stretch;gap:4px;margin-bottom:6px;";
  const lbl = document.createElement("span");
  lbl.textContent = label;
  lbl.style.cssText = `color:${tokens.ink300};font-size:11px;`;
  (input.style as CSSStyleDeclaration).cssText += ";width:100%;box-sizing:border-box;";
  wrap.append(lbl, input);
  appendToGroup(parent, wrap);
}

const INPUT_STYLE = `background:${tokens.ink900};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:4px;padding:4px 6px;font:inherit;`;

function numberField(
  parent: HTMLElement, label: string, value: number,
  onChange: (v: number, commit: boolean) => void,
  min?: number, max?: number,
  read?: (item: LayoutItem) => unknown,
): (item: LayoutItem) => void {
  const input = document.createElement("input");
  input.type = "number";
  input.step = "0.1";
  if (min !== undefined) input.min = String(min);
  if (max !== undefined) input.max = String(max);
  input.value = String(value);
  input.style.cssText = INPUT_STYLE;
  input.addEventListener("input", () => onChange(Number(input.value), false));
  input.addEventListener("change", () => onChange(Number(input.value), true));
  row(parent, label, input);
  return (item) => {
    if (document.activeElement === input) return; // don't clobber user edits
    const v = read ? read(item) : readFieldValue(item, label);
    if (v !== undefined && input.value !== String(v)) input.value = String(v);
  };
}

function textField(
  parent: HTMLElement, label: string, value: string,
  onChange: (v: string, commit: boolean) => void,
): (item: LayoutItem) => void {
  const input = document.createElement("input");
  input.type = "text";
  input.value = value;
  input.style.cssText = INPUT_STYLE;
  input.addEventListener("input", () => onChange(input.value, false));
  input.addEventListener("change", () => onChange(input.value, true));
  row(parent, label, input);
  return (item) => {
    if (document.activeElement === input) return;
    const v = readFieldValue(item, label);
    if (typeof v === "string" && input.value !== v) input.value = v;
  };
}


function colorField(
  parent: HTMLElement, label: string, value: string,
  onChange: (v: string, commit: boolean) => void,
  read?: (item: LayoutItem) => unknown,
): (item: LayoutItem) => void {
  const input = document.createElement("input");
  input.type = "color";
  input.value = normalizeColorForPicker(value);
  input.style.cssText = INPUT_STYLE + "height:28px;padding:2px;";
  input.addEventListener("input", () => onChange(input.value, false));
  input.addEventListener("change", () => onChange(input.value, true));
  row(parent, label, input);
  return (item) => {
    if (document.activeElement === input) return;
    const v = read ? read(item) : readFieldValue(item, label);
    if (typeof v === "string") input.value = normalizeColorForPicker(v);
  };
}

function selectField(
  parent: HTMLElement, label: string, value: string, options: string[],
  onChange: (v: string, commit: boolean) => void,
  wide = false,
  read?: (item: LayoutItem) => unknown,
): (item: LayoutItem) => void {
  const input = document.createElement("select");
  input.style.cssText = INPUT_STYLE;
  for (const o of options) {
    const opt = document.createElement("option");
    opt.value = o;
    opt.textContent = o;
    if (o === value) opt.selected = true;
    input.appendChild(opt);
  }
  input.addEventListener("change", () => onChange(input.value, true));
  (wide ? rowStacked : row)(parent, label, input);
  return (item) => {
    if (document.activeElement === input) return;
    const v = read ? read(item) : readFieldValue(item, label);
    if (typeof v === "string" && input.value !== v) input.value = v;
  };
}

// Map the field's display label back to the item property. Keeps the
// build() section declarative without per-field update boilerplate.
function readFieldValue(item: LayoutItem, label: string): unknown {
  const record = item as unknown as Record<string, unknown>;
  const labelMap: Record<string, string> = {
    "left (%)": "left", "top (%)": "top", "width (%)": "width", "height (%)": "height",
    "radius (%)": "radius", "degrees": "rotation", "start": "opacity", "font size": "fontSize",
    "content": "text", "src": "src", "color": item.type === "text" ? "color" : "fill",
    "font": "fontFamily",
    "v-align": "verticalAlign",
  };
  const key = labelMap[label];
  if (!key) return undefined;
  return record[key];
}

// A passive contrast warning beneath the color picker. Renders a
// "⚠ Low contrast" hint when the WCAG contrast ratio between the
// item's color and the page background drops below AA-normal (4.5).
// Doesn't auto-correct; just alerts the author. Background colors
// that aren't a parseable hex (gradients, named colors) make the
// helper return ratio=1, so the warning never spuriously triggers.
function contrastWarningRow(parent: HTMLElement, store: Store): (item: LayoutItem) => void {
  const wrap = document.createElement("div");
  wrap.style.cssText = [
    "display: flex",
    "align-items: center",
    "gap: 6px",
    "margin: 2px 0 8px",
    "padding-left: 88px", // matches the `flex: 0 0 80px` label gutter in `row()`
    `color: ${tokens.amber}`,
    "font-size: 10.5px",
    "letter-spacing: 0.3px",
    "min-height: 14px",
  ].join(";");
  wrap.textContent = "";
  appendToGroup(parent, wrap);

  return (item: LayoutItem) => {
    if (item.type !== "text") {
      wrap.style.display = "none";
      return;
    }
    const page = currentPage(store.state);
    const ratio = contrastRatio(item.color, page?.bg);
    if (ratio < WCAG_AA_NORMAL) {
      wrap.style.display = "";
      wrap.textContent = `⚠ Low contrast (${ratio.toFixed(1)}:1) — readers may not see this text`;
    } else {
      wrap.style.display = "none";
      wrap.textContent = "";
    }
  };
}

function normalizeColorForPicker(v: string): string {
  // <input type="color"> only accepts #rrggbb. Strip alpha, expand #rgb.
  if (/^#[0-9a-f]{6}$/i.test(v)) return v.toLowerCase();
  if (/^#[0-9a-f]{3}$/i.test(v)) {
    return "#" + v.slice(1).split("").map((c) => c + c).join("").toLowerCase();
  }
  return "#ffffff";
}

// ─── Committing edits ────────────────────────────────────────────────

// Each field handler calls onChange(v, commit). `commit=false` is an
// `input` event (live preview during typing/dragging) and goes through
// store.replace — no history entry, no cursor jitter. `commit=true` is
// a `change` event (blur, Enter, picker close) and records one undo
// step. Without this split, every keystroke pushes a history entry and
// triggers a full re-render that can steal caret focus.
function mutate(store: Store, idx: number, fn: (it: LayoutItem) => LayoutItem, commit = true): void {
  const before = currentLayout(store.state)[idx];
  let next = updateItem(store.state, idx, fn);
  // Model-A typography sync: when a typography property changes on a
  // field-bound text item (headline/body/sub), fan that property out to the
  // same role across every creative. Non-typography edits (position, size,
  // crop, …) and image items (no `field`) fall through untouched.
  const after = currentLayout(next)[idx];
  const field = after && after.type === "text" ? (after as { field?: string }).field : undefined;
  if (before && after && field) {
    const b = before as unknown as Record<string, unknown>;
    const a = after as unknown as Record<string, unknown>;
    const typo: Record<string, unknown> = {};
    // Colour syncs like every other typography prop — one typographic
    // identity per role, from whichever surface the edit happens on —
    // EXCEPT on a detached override (sync checkbox unticked): there the
    // colour is a per-size choice and stays local, both outbound (here)
    // and inbound (propagateTypography skips detached items). Re-ticking
    // adopts the local text + colour everywhere (adoptTextOverride).
    for (const k of TYPO_SYNC_KEYS) if (a[k] !== b[k]) typo[k] = a[k];
    if (hasLocalTextOverride(after)) delete typo.color;
    if (Object.keys(typo).length > 0) next = propagateTypography(next, field, typo);
  }
  if (commit) store.commit(next);
  else store.replace(next);
}

// Font-size edit. Size is NOT copied verbatim across formats (a value tuned
// on the wide PC reader renders oversized on the tall mobile reader — see
// TYPO_SYNC_KEYS in state.ts). Instead it syncs PROPORTIONALLY: the fan-out
// runs only on commit and scales every OTHER format's size from its own base
// by newSize/baseline, so each format keeps its proportion. During live input
// we resize only the edited item (the other formats aren't on screen anyway),
// which also means the commit-time fan-out scales clean originals — no
// per-keystroke compounding drift. `baseline` is the edited item's size at the
// START of the interaction (captured by the caller before the first input).
function mutateFontSize(
  store: Store, idx: number, newSize: number, baseline: number, commit: boolean,
): void {
  let next = updateItem(store.state, idx, (it) =>
    it.type === "text" ? { ...it, fontSize: newSize } : it);
  const edited = currentLayout(next)[idx];
  const field = edited && edited.type === "text" ? (edited as { field?: string }).field : undefined;
  // Reader pages sync ABSOLUTELY from any page — including during live
  // typing, otherwise the page-1-master subscriber reverts a page-2/3
  // edit on every keystroke (the input visibly snaps back).
  if (field) next = setReaderFieldFontSize(next, field, newSize);
  if (commit) {
    if (field && baseline > 0 && newSize > 0 && newSize !== baseline) {
      // Banner buckets scale proportionally; the reader items are already
      // at the absolute size, so propagate skips... they match the target
      // ratio only if bases were uniform — re-assert absolute after.
      next = propagateTypography(next, field, {}, newSize / baseline, edited);
      next = setReaderFieldFontSize(next, field, newSize);
    }
    store.commit(next);
    // Re-pack the edited item's box at the new size (same pattern as
    // mutateContent), then copy the packed box to the synced reader pages.
    // Without this the old (smaller) boxes survive: pages 2/3 render the
    // synced font auto-shrunk back into them until each item is clicked —
    // and worse, those stale boxes get SAVED and the delivered expanded
    // view shows the body "relatively small".
    requestAnimationFrame(() => {
      packTextItemHeight(store, idx, true);
      if (field) packReaderFieldBoxes(store, field);
    });
  } else {
    store.replace(next);
  }
}

// Content edit (text / image src) routed through setItemContent so a
// field-bound master edit syncs every size and a size edit overrides.
function mutateContent(store: Store, idx: number, value: string, kind: "text" | "src", commit = true): void {
  // On commit (blur / Enter), trim trailing whitespace from text so the box
  // auto-fits the actual content — stray trailing spaces/newlines otherwise
  // inflate the measured box. Not on live input (you must be able to type a
  // space), and not for src (it's a URL).
  const v = commit && kind === "text" ? value.replace(/\s+$/, "") : value;
  const next = setItemContent(store.state, idx, v, kind);
  if (commit) store.commit(next);
  else store.replace(next);
  // After committing a text change, re-fit the box height to the (trimmed)
  // text — the inline-edit path does this via fitTextItem; the props field
  // didn't, so editing text here used to leave the box at its old size.
  if (commit && kind === "text") requestAnimationFrame(() => packTextItemHeight(store, idx, true));
}

// The value an item actually shows: its own baked text/src, or the shared
// page field it's bound to. Keeps the props input honest for field-bound
// items (which carry no local text/src).
function effectiveContent(store: Store, item: LayoutItem, kind: "text" | "src"): string {
  const local = (item as { text?: string; src?: string })[kind];
  if (local != null && local !== "") return local;
  const field = (item as { field?: string }).field;
  if (!field) return local ?? "";
  const page = currentPage(store.state) as Record<string, unknown> | null;
  return String(page?.[field] ?? "");
}

// Read-only echo of the resolved content, shown in a SIZE where content
// isn't editable — so the author can see what will render without being
// able to change it (content is edited in the Expanded view).
function contentHint(parent: HTMLElement, value: string): void {
  const hint = document.createElement("div");
  hint.textContent = value || "(empty)";
  hint.title = "Edit content in the Expanded view";
  hint.style.cssText = `color:${tokens.ink300};font-size:11px;background:${tokens.ink900};border:1px dashed ${tokens.ink500};border-radius:4px;padding:4px 6px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;`;
  parent.appendChild(hint);
}
