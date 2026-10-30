// Top bar — the whole banner. Field grey like the dashboard's top
// bar, so moving between the two reads as one product.
//
// Left: breadcrumb back to the dashboard — "Campaigns" (a link to the
// campaigns page) / campaign name (when ctx.campaignName is set) /
// creative name — and a plain Draft tag while the creative has never
// been published (!ctx.creativeId). Names truncate; the full text is in
// the title.
//
// Right, in order: Undo / Redo, Regenerate this size, Preview, Save
// draft, Publish (the one ink-filled primary), Theme. Save draft sits
// apart from Publish, with Preview between them, so a mis-tap can't
// publish.
//
// When the window narrows, the buttons give up words before the
// breadcrumb disappears: each [data-cd-compact="N"] span hides in step
// order (Theme: prefix, Undo/Redo words, " this size", Preview word)
// until the breadcrumb has room again. Save draft and Publish keep
// their words.

import { regenerateCurrentMode } from "../auto-layout";
import { isMultiPage } from "../modes";
import type { Store } from "../store";
import type { DesignerContext, DesignerState } from "../types";
import { mountHistoryButtons } from "./history-buttons";
import { mountPreviewButton } from "./preview-button";
import { mountDraftButton, mountPublishButton } from "./save";
import { tokens, cycleMode, getMode, type ThemeMode } from "./tokens";

export interface MenuBarHandle {
  update(state: DesignerState): void;
}

export function mountMenuBar(
  container: HTMLElement,
  store: Store,
  ctx: DesignerContext,
): MenuBarHandle {
  const bar = document.createElement("header");
  bar.className = "cd-menu-bar";
  bar.style.cssText = [
    "height: 39px", // +1px border = 40 on the pre-zoom grid
    "padding: 0 12px",
    "display: flex",
    "align-items: center",
    "gap: 10px",
    `background: ${tokens.field}`,
    `border-bottom: 1px solid ${tokens.ink500}`,
    "flex: 0 0 auto",
    "min-width: 0",
  ].join(";");

  // Breadcrumb. min-width:0 + flex:1 lets the names shrink first, so
  // the actions on the right never get pushed off the bar.
  const crumb = document.createElement("nav");
  crumb.setAttribute("aria-label", "Breadcrumb");
  crumb.style.cssText = [
    "display: flex",
    "align-items: center",
    "gap: 6px",
    "flex: 1",
    "min-width: 0",
    "white-space: nowrap",
    "overflow: hidden",
    "font-size: 12px",
    `color: ${tokens.ink300}`,
  ].join(";");
  const campaigns = document.createElement("a");
  campaigns.href = "/advertiser/campaigns";
  campaigns.textContent = "Campaigns";
  campaigns.style.cssText = `color: ${tokens.ink300}; text-decoration: none; flex: 0 0 auto;`;
  crumb.appendChild(campaigns);
  if (ctx.campaignName) crumb.append(slash(), crumbName(ctx.campaignName, tokens.ink300, "0 1 auto"));
  const creative = crumbName(ctx.creativeName, tokens.ink100, "0 1 auto");
  creative.dataset.cdCrumbCreative = "";
  creative.style.fontWeight = "700";
  crumb.append(slash(), creative);
  if (!ctx.creativeId) {
    const tag = document.createElement("span");
    tag.textContent = "Draft";
    tag.setAttribute("role", "status");
    tag.style.cssText = [
      "flex: 0 0 auto",
      "padding: 1px 6px",
      `background: ${tokens.ink700}`,
      "border-radius: 2px",
      "font-size: 11px",
      `color: ${tokens.ink300}`,
    ].join(";");
    crumb.appendChild(tag);
  }
  bar.appendChild(crumb);

  const actions = document.createElement("div");
  actions.style.cssText = "display: flex; align-items: center; gap: 6px; flex: 0 0 auto;";
  bar.appendChild(actions);

  mountHistoryButtons(actions, store);
  actions.appendChild(divider());

  // Regenerate is an EXPANDED-mode action (the two-step Gemini copy
  // rewrite + layout). For IAB banner sizes it only ever re-applied the
  // deterministic preset — a reset in regenerate's clothing that wiped
  // any author layout in the bucket — so it's disabled on sized tabs
  // (user decision 2026-08-18).
  const regenBtn = textBtn("Regenerate", () => {
    void regenerateCurrentMode(store).catch((e) => console.error("[menu-bar] regenerate failed", e));
  });
  regenBtn.append(compactSpan(" this size", 3));
  actions.append(regenBtn, divider());

  mountPreviewButton(actions, store);
  mountDraftButton(actions, store, ctx);
  mountPublishButton(actions, store, ctx);
  actions.appendChild(themeToggle());

  container.appendChild(bar);

  // Hide compact steps until the breadcrumb gets its room: its natural
  // width, capped so a very long name truncates instead of stripping
  // every button.
  const fit = (): void => {
    const steps = [...actions.querySelectorAll<HTMLElement>("[data-cd-compact]")];
    for (const el of steps) el.style.display = "";
    const kids = [...crumb.children] as HTMLElement[];
    const natural = kids.reduce((sum, k) => sum + k.scrollWidth, 0) + 6 * (kids.length - 1);
    const need = Math.min(natural, 220);
    for (let step = 1; step <= 4 && crumb.clientWidth < need; step++) {
      for (const el of steps) if (el.dataset.cdCompact === String(step)) el.style.display = "none";
    }
  };
  const ro = new ResizeObserver(fit);
  ro.observe(bar);
  ro.observe(actions);

  return {
    update(state: DesignerState) {
      const modeName = state.mode.label.replace(/\s*\(.*\)$/, "");
      const regenEnabled = isMultiPage(state.mode);
      regenBtn.disabled = !regenEnabled;
      regenBtn.style.opacity = regenEnabled ? "1" : "0.35";
      regenBtn.style.cursor = regenEnabled ? "pointer" : "default";
      regenBtn.title = regenEnabled
        ? `Regenerate ${modeName}`
        : "Regenerate works on the expanded pages — banner sizes follow them automatically";
    },
  };
}

function crumbName(text: string, color: string, flex: string): HTMLElement {
  const el = document.createElement("span");
  el.textContent = text;
  el.title = text;
  el.style.cssText = `color: ${color}; flex: ${flex}; min-width: 0; overflow: hidden; text-overflow: ellipsis;`;
  return el;
}

function compactSpan(text: string, step: number): HTMLElement {
  const el = document.createElement("span");
  el.textContent = text;
  el.dataset.cdCompact = String(step);
  el.style.whiteSpace = "pre";
  return el;
}

function slash(): HTMLElement {
  const el = document.createElement("span");
  el.textContent = "/";
  el.setAttribute("aria-hidden", "true");
  el.style.cssText = `color: ${tokens.ink400}; flex: 0 0 auto;`;
  return el;
}

function divider(): HTMLElement {
  const el = document.createElement("div");
  el.style.cssText = `width: 1px; height: 20px; background: ${tokens.ink400}; margin: 0 2px;`;
  return el;
}

function textBtn(label: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.style.cssText = [
    "padding: 4px 9px",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink100}`,
    "border-radius: 2px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
    "white-space: nowrap",
  ].join(";");
  b.addEventListener("mouseenter", () => { if (!b.disabled) b.style.background = tokens.ink700; });
  b.addEventListener("mouseleave", () => { b.style.background = tokens.ink900; });
  b.addEventListener("click", onClick);
  return b;
}

// Theme cycle: Auto (follows OS) → Light → Dark → Auto, shared with the
// dashboard. Labelled like the dashboard's account-menu item; cycling
// flips the data-cd-theme attribute, re-resolving every --cd-* variable
// instantly (no re-mount).
const MODE_LABEL: Record<ThemeMode, string> = { auto: "Auto", light: "Light", dark: "Dark" };
function themeToggle(): HTMLElement {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.style.cssText = [
    "flex: 0 0 auto",
    "padding: 4px 6px",
    "border: 1px solid transparent",
    "border-radius: 2px",
    "background: transparent",
    `color: ${tokens.ink300}`,
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
    "white-space: nowrap",
  ].join(";");
  const render = (): void => {
    const m = getMode();
    btn.replaceChildren(compactSpan("Theme: ", 1), MODE_LABEL[m]);
    btn.setAttribute("aria-label", `Theme: ${MODE_LABEL[m]}`);
    btn.title = m === "auto" ? "Follows your system setting — click to change" : "Click to change";
  };
  render();
  btn.addEventListener("click", () => {
    cycleMode();
    render();
  });
  return btn;
}
