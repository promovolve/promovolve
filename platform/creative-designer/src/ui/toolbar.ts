// Horizontal tool group sitting at the left of the canvas header.
// Icon-only buttons with keyboard-shortcut tooltips for authoring
// new items and removing the current selection. Mounted by
// canvas-header.ts as the leftmost cluster in the per-banner menu
// bar — no longer a standalone rail.
//
// Icons are inline SVGs in currentColor so hover/active states flow
// through font-color. The vertical divider before Delete separates
// the pointer + create cluster from the destructive action.

import { addItem, addLocalImage, currentItem, currentLayout, currentPage, selectItem } from "../state";
import { commitDeleteSelection } from "./confirm-delete";
import type { Store } from "../store";
import type { DesignerState, LayoutItem, RectItem, TextItem } from "../types";
import { pickContrast } from "../color-contrast";
import { scrimGradient, SCRIM_DEFAULT_COLOR, SCRIM_DEFAULT_STRENGTH } from "../scrim";
import { openAssetModal } from "./asset-modal";
import { openBrandKitModal } from "./brand-kit-modal";
import { openTemplateModal } from "./template-modal";
import { tokens } from "./tokens";

export function mountToolbar(host: HTMLElement, store: Store): void {
  const bar = document.createElement("div");
  bar.className = "cd-tool-group";
  bar.style.cssText = [
    "flex: 0 0 auto",
    "display: flex",
    "flex-direction: row",
    "align-items: center",
    "gap: 2px",
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
    store.commit(addItem(store.state, { ...defaultText(), fontSize, color }));
  };

  bar.append(
    iconBtn(ICON_SELECT, "Select · V",  () => store.commit(selectItem(store.state, null))),
    iconBtn(ICON_TEXT,   "Text · T",    addText),
    iconBtn(ICON_IMAGE,  "Image · I",   () => openAssetModal(store, (asset) => store.commit(addLocalImage(store.state, asset.cdnUrl)))),
    iconBtn(ICON_SCRIM,  "Scrim",       () => store.commit(addItem(store.state, defaultScrim()))),
    iconBtn(ICON_TEMPLATE, "Layout templates", () => openTemplateModal(store)),
    iconBtn(ICON_BRAND, "Brand kit", () => openBrandKitModal(window.__DESIGNER__?.campaignId ?? "")),
    divider(),
    iconBtn(ICON_TRASH,  "Delete · ⌫",  () => {
      commitDeleteSelection(store);
    }, { danger: true }),
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

interface IconBtnOpts {
  danger?: boolean;
}

function iconBtn(iconSvg: string, title: string, onClick: () => void, opts: IconBtnOpts = {}): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.title = title;
  // aria-label is the part before the bullet — "Text · T" → "Text"
  b.setAttribute("aria-label", title.split("·")[0]!.trim());
  b.innerHTML = iconSvg;
  const idleColor = opts.danger ? tokens.err : tokens.ink300;
  const hoverColor = opts.danger ? tokens.err : tokens.ink100;
  const hoverBg = opts.danger ? "transparent" : tokens.ink700;
  b.style.cssText = [
    "width: 30px",
    "height: 30px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    `color: ${idleColor}`,
    "border: 1px solid transparent",
    `border-radius: ${tokens.r6}px`,
    "cursor: pointer",
    "padding: 0",
    "transition: background .12s, color .12s, border-color .12s",
  ].join(";");
  b.addEventListener("mouseenter", () => {
    b.style.color = hoverColor;
    b.style.background = hoverBg;
    if (opts.danger) b.style.borderColor = tokens.err;
  });
  b.addEventListener("mouseleave", () => {
    b.style.color = idleColor;
    b.style.background = "transparent";
    b.style.borderColor = "transparent";
  });
  b.addEventListener("click", onClick);
  return b;
}

function divider(): HTMLElement {
  const el = document.createElement("div");
  el.style.cssText = [
    "width: 1px",
    "height: 22px",
    `background: ${tokens.ink500}`,
    "margin: 0 4px",
    "align-self: center",
  ].join(";");
  return el;
}

// ─── Icon SVGs (14×14, stroke currentColor) ───────────────────────

const ICON_SELECT = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" stroke-linecap="round"><path d="M3 2.5l7 4.2-3 .9-1 3z"/></svg>`;
const ICON_TEXT = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><path d="M3 3h8"/><path d="M7 3v8"/></svg>`;
const ICON_IMAGE = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2" y="3" width="10" height="8" rx="1"/><circle cx="5" cy="6" r="1"/><path d="M2.5 10l3-3 2.5 2.5L10 8l2 2"/></svg>`;
const ICON_TEMPLATE = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="2" y="2" width="10" height="10" rx="1"/><path d="M2 5 L12 5"/><path d="M5 5 L5 12"/></svg>`;
const ICON_BRAND = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><circle cx="4" cy="5" r="2.2"/><circle cx="9" cy="4" r="1.6"/><circle cx="10" cy="9" r="2.2"/><circle cx="4.5" cy="10" r="1.6"/></svg>`;
// Scrim: a rect half-filled with a fade — a box whose lower band is solid
// and grades up to nothing.
const ICON_SCRIM = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"><rect x="2" y="2" width="10" height="10" rx="1"/><path d="M2 8h10" opacity="0.5"/><path d="M2 10.5h10"/></svg>`;
const ICON_TRASH = `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><path d="M3 4h8"/><path d="M4 4l.5 7a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1l.5-7"/><path d="M5.5 4V3a1 1 0 0 1 1-1h1a1 1 0 0 1 1 1v1"/></svg>`;

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
    // — overrides this in mountToolbar's addText.
    fontSize: 8,
    color: "#ffffff",
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
