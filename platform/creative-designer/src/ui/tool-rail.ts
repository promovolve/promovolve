// Left tool rail — "what can I add?". Always visible and the same in
// every state: each tool is an icon above a text label, with its
// keyboard shortcut. Two groups: Add (Select, Text, Image, Formula,
// Scrim) and Start from (Layout templates, Brand kit). Delete lives in
// the selection bar, with everything else that acts on a selection.
//
// The tools other than Select are one-shot actions, not modes, so
// Select is the only one with an active state and shows it
// permanently. Icons are inline SVGs in currentColor.

import { addItem, addLocalImage, currentItem, currentLayout, currentPage, selectItem } from "../state";
import { openFormulaModal } from "./formula-modal";
import type { Store } from "../store";
import type { DesignerState, LayoutItem, RectItem, TextItem } from "../types";
import { pickContrast } from "../color-contrast";
import { loadBrandKit } from "../brand-kit";
import { headlineFontOf } from "../presets";
import { scrimGradient, SCRIM_DEFAULT_COLOR, SCRIM_DEFAULT_STRENGTH } from "../scrim";
import { openAssetModal } from "./asset-modal";
import { openBrandKitModal } from "./brand-kit-modal";
import { openTemplateModal } from "./template-modal";
import { tokens } from "./tokens";

export function mountToolRail(host: HTMLElement, store: Store): void {
  const bar = document.createElement("div");
  bar.className = "cd-tool-rail";
  bar.setAttribute("aria-label", "Tools");
  bar.style.cssText = [
    "width: 90px",
    "height: 100%",
    "box-sizing: border-box",
    "padding: 8px 6px",
    "display: flex",
    "flex-direction: column",
    "gap: 2px",
    "overflow-y: auto",
    `background: ${tokens.ink900}`,
    `border-right: 1px solid ${tokens.ink600}`,
  ].join(";");

  // Track the most recently single-selected text item's fontSize so
  // the next "add text" inherits it. Sticky across selection changes —
  // once the user has chosen a size by clicking into a text item,
  // that's their working size until they solo-select a different one.
  // Not a per-mode ref: fontSize is already in cqh (% of container
  // height), so the number carries across modes semantically.
  let lastTextFontSize: number | null = null;
  store.subscribe((state) => {
    const item = currentItem(state);
    if (item?.type === "text" && typeof item.fontSize === "number") {
      lastTextFontSize = item.fontSize;
    }
  });

  const addText = (): void => {
    const fontSize = lastTextFontSize ?? inferFontSizeFromLayout(store.state) ?? 8;
    const page = currentPage(store.state);
    const color = pickContrast(page?.bg).headline;
    const fontFamily = inferFontFamilyFromLayout(store.state);
    const writingMode = inferWritingModeFromLayout(store.state);
    store.commit(addItem(store.state, {
      ...defaultText(), fontSize, color, fontFamily,
      ...(writingMode ? { writingMode } : {}),
    }));
  };

  bar.append(
    groupHeading("Add"),
    toolBtn(ICON_SELECT, "Select", "V", () => store.commit(selectItem(store.state, null)), { active: true }),
    toolBtn(ICON_TEXT, "Text", "T", addText),
    toolBtn(ICON_IMAGE, "Image", "I", () => openAssetModal(store, (asset) => store.commit(addLocalImage(store.state, asset.cdnUrl, asset.width && asset.height ? { w: asset.width, h: asset.height } : undefined)))),
    toolBtn(ICON_FORMULA, "Formula", "", () => openFormulaModal(store)),
    toolBtn(ICON_SCRIM, "Scrim", "", () => store.commit(addItem(store.state, defaultScrim()))),
    divider(),
    groupHeading("Start from"),
    toolBtn(ICON_TEMPLATE, "Layout templates", "", () => openTemplateModal(store)),
    toolBtn(ICON_BRAND, "Brand kit", "", () => openBrandKitModal(window.__DESIGNER__?.campaignId ?? "")),
  );

  host.appendChild(bar);
}

// Read a fontSize hint off the current-mode layout when the user
// hasn't selected any text yet. Preference order:
//   1. Auto-layout text (_generated:true) — whatever the template /
//      Gemini chose as the reference size for this mode.
//   2. Any text item on the canvas — the author's own work.
// Returns null if no text item is present at all; caller falls back
// to the hardcoded default (8).
function inferFontSizeFromLayout(state: DesignerState): number | null {
  const items = currentLayout(state);
  let fallback: number | null = null;
  for (const it of items) {
    if (it.type !== "text" || typeof it.fontSize !== "number") continue;
    if ((it as LayoutItem & { _generated?: boolean })._generated) return it.fontSize;
    if (fallback === null) fallback = it.fontSize;
  }
  return fallback;
}

// The face a new text box opens in. A box the author adds is copy, so
// it joins the creative's voice instead of arriving in the browser's
// default sans (see presets.ts BODY FOLLOWS HEADLINE). Preference:
//   1. The headline ON THIS CANVAS — what the author is looking at.
//   2. The wide master's headline / the kit's heading font, which is
//      what every generated layout resolved from anyway.
// Unlike fontSize this is NOT sticky across selections: fontSize is a
// per-box decision the author re-makes constantly, while the face is a
// creative-level constant they'd have to fight on every add.
function inferFontFamilyFromLayout(state: DesignerState): string {
  const here = currentLayout(state).find(
    (it) => it.type === "text" && (it as { field?: string }).field === "headline",
  ) as { fontFamily?: string } | undefined;
  if (here?.fontFamily) return here.fontFamily;
  const page = currentPage(state);
  return page
    ? headlineFontOf(page, loadBrandKit(window.__DESIGNER__?.campaignId))
    : "sans-serif";
}

/**
 * The reading direction a NEW text box should open in: whatever the text
 * around it already uses.
 *
 * A box that arrives horizontal in a vertical-rl layout is wrong on sight,
 * and fixing it means toggling the writing mode — which drops the text into
 * a box shaped for the other axis (packTextItemHeight hugs HEIGHT for
 * horizontal, WIDTH for vertical), forcing a large autofit shrink. So the
 * default isn't just untidy, it steers authors straight into the most
 * destructive gesture in the editor.
 *
 * Prefers the headline (the field that sets a page's voice, and the same
 * anchor inferFontFamilyFromLayout uses), then any text on the page, then
 * horizontal. Returns undefined for horizontal so the item stays free of a
 * redundant property — absent already means horizontal-tb everywhere.
 */
export function inferWritingModeFromLayout(state: DesignerState): "vertical-rl" | undefined {
  const texts = currentLayout(state).filter((it) => it.type === "text") as Array<{
    field?: string; writingMode?: string;
  }>;
  const anchor = texts.find((it) => it.field === "headline") ?? texts[0];
  return anchor?.writingMode === "vertical-rl" ? "vertical-rl" : undefined;
}

interface ToolBtnOpts {
  active?: boolean;
}

// Icon above its label, shortcut underneath in mono. The label is the
// accessible name; the shortcut rides in the title.
function toolBtn(iconSvg: string, label: string, shortcut: string, onClick: () => void, opts: ToolBtnOpts = {}): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.setAttribute("aria-label", label);
  b.title = shortcut ? `${label} (${shortcut})` : label;
  if (opts.active) b.setAttribute("aria-pressed", "true");
  b.innerHTML = `<span data-icon>${iconSvg}</span><span>${label}</span>` +
    (shortcut ? `<kbd style="font: 10px ${tokens.mono}; color: ${tokens.ink300};">${shortcut}</kbd>` : "");
  const idleBg = opts.active ? tokens.ink700 : "transparent";
  b.style.cssText = [
    "position: relative",
    "display: flex",
    "flex-direction: column",
    "align-items: center",
    "gap: 2px",
    "padding: 6px 2px",
    `background: ${idleBg}`,
    `color: ${tokens.ink100}`,
    "border: none",
    `border-radius: ${tokens.r4}px`,
    // Active tool: a 3px ink (focus) bar on the left edge.
    opts.active ? `box-shadow: inset 3px 0 0 ${tokens.focus}` : "box-shadow: none",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
    "line-height: 1.25",
    "text-align: center",
  ].join(";");
  const icon = b.querySelector<HTMLElement>("[data-icon]")!;
  icon.style.cssText = `display:flex;color:${opts.active ? tokens.focus : tokens.ink100};`;
  b.addEventListener("mouseenter", () => { b.style.background = tokens.ink700; });
  b.addEventListener("mouseleave", () => { b.style.background = idleBg; });
  b.addEventListener("click", onClick);
  return b;
}

function groupHeading(text: string): HTMLElement {
  const el = document.createElement("div");
  el.textContent = text;
  el.style.cssText = `font-size: 10px; color: ${tokens.ink300}; padding: 4px 4px 2px;`;
  return el;
}

function divider(): HTMLElement {
  const el = document.createElement("div");
  el.style.cssText = `height: 1px; background: ${tokens.ink600}; margin: 6px 4px;`;
  return el;
}

// ─── Icon SVGs (14×14, stroke currentColor) ───────────────────────

const ICON_SELECT = `<svg viewBox="0 0 14 14" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" stroke-linecap="round"><path d="M3 2.5l7 4.2-3 .9-1 3z"/></svg>`;
const ICON_TEXT = `<svg viewBox="0 0 14 14" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><path d="M3 3h8"/><path d="M7 3v8"/></svg>`;
const ICON_IMAGE = `<svg viewBox="0 0 14 14" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2" y="3" width="10" height="8" rx="1"/><circle cx="5" cy="6" r="1"/><path d="M2.5 10l3-3 2.5 2.5L10 8l2 2"/></svg>`;
const ICON_TEMPLATE = `<svg viewBox="0 0 14 14" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="2" y="2" width="10" height="10" rx="1"/><path d="M2 5 L12 5"/><path d="M5 5 L5 12"/></svg>`;
const ICON_BRAND = `<svg viewBox="0 0 14 14" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><circle cx="4" cy="5" r="2.2"/><circle cx="9" cy="4" r="1.6"/><circle cx="10" cy="9" r="2.2"/><circle cx="4.5" cy="10" r="1.6"/></svg>`;
// Scrim: a rect half-filled with a fade — a box whose lower band is solid
// and grades up to nothing.
const ICON_FORMULA = `<svg viewBox="0 0 14 14" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" stroke-linecap="round"><path d="M11 3H3.5l4 4-4 4H11"/></svg>`;
const ICON_SCRIM = `<svg viewBox="0 0 14 14" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="2" y="2" width="10" height="10" rx="1"/><path d="M2 8h10" opacity="0.5"/><path d="M2 10.5h10"/></svg>`;

// Defaults for new items — recognisable initial colors so authors
// immediately see the shape they just added and can pick their own
// colors via props-panel.

function defaultText(): TextItem {
  return {
    type: "text",
    text: "New text",
    left: 30, top: 30, width: 30,
    // Last-resort fontSize when no text has been selected in this
    // session and the layout has no text to infer from. 8cqh sits
    // between the preset headline (10) and the legibility warning
    // floor (3 via LEGIBILITY_MIN_FONT). The usual path — inherit
    // from the last-selected text, or from an auto-layout text item
    // — overrides this in mountToolRail's addText.
    fontSize: 8,
    color: "#ffffff",
    // Last-resort face for the same reason as fontSize above — the
    // usual path is addText's inferFontFamilyFromLayout, which opens
    // the box in the creative's headline face.
    fontFamily: "sans-serif",
  };
}

// A new scrim defaults to a bottom legibility wash over the lower half —
// the most common case (headline/body sit at the bottom over a hero
// image). `fill` is pre-composed so it renders the instant it's added;
// the props panel recomposes it when the author tweaks edge/colour/strength.
function defaultScrim(): RectItem {
  const scrim = {
    edge: "bottom" as const,
    color: SCRIM_DEFAULT_COLOR,
    strength: SCRIM_DEFAULT_STRENGTH,
  };
  return {
    type: "rect",
    left: 0, top: 50, width: 100, height: 50,
    scrim,
    fill: scrimGradient(scrim),
  };
}



export type _LayoutItemShape = LayoutItem;
