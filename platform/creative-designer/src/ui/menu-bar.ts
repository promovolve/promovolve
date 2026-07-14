// Slim top bar — identity on the left, action buttons on the right.
// After PRs 4+5 the bar is 38px tall and carries only identity
// information + global actions. The pager moved to canvas-header;
// zoom + rulers moved to canvas-foot; the menu bar is no longer the
// catch-all it used to be.
//
// Identity cluster, left to right:
//   - Logo badge:  20×20 amber→red gradient square, "pv" in mono.
//   - Campaign name (optional, only when ctx.campaignName is set).
//   - Chevron separator.
//   - Creative name.
//   - DRAFT pill (only when !ctx.creativeId — i.e. the creative has
//     never been published, so it doesn't have a stable id yet).
//
// Actions cluster, right-aligned:
//   - Preview (GhostBtn). (Undo/redo moved to the canvas-header tool
//     cluster; a "Regenerate all" button was once listed here but
//     never shipped — per-size Regenerate in canvas-header covers it.)
//   - Save Draft (GhostBtn) + Publish (PrimaryBtn). Existing
//     mountSaveButton returns both — relies on its internal styling
//     which was updated in PR 1 to use tokens.

import type { Store } from "../store";
import type { DesignerContext, DesignerState } from "../types";
import { mountPreviewButton } from "./preview-button";
import { mountPublishButton } from "./save";
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
    "height: 38px",
    "padding: 0 12px",
    "display: flex",
    "align-items: center",
    "gap: 10px",
    `background: ${tokens.ink800}`,
    `border-bottom: 1px solid ${tokens.ink500}`,
    "flex: 0 0 auto",
  ].join(";");

  bar.appendChild(logoBadge());

  // Campaign name (optional)
  if (ctx.campaignName) {
    const campaign = document.createElement("span");
    campaign.textContent = ctx.campaignName;
    campaign.style.cssText = [
      `color: ${tokens.ink300}`,
      "font-size: 11px",
      "white-space: nowrap",
      "max-width: 160px",
      "overflow: hidden",
      "text-overflow: ellipsis",
    ].join(";");
    const chevron = document.createElement("span");
    chevron.textContent = "\u203a"; // ›
    chevron.style.cssText = `color: ${tokens.ink400}; font-size: 10px;`;
    bar.append(campaign, chevron);
  }

  const creative = document.createElement("span");
  creative.textContent = ctx.creativeName;
  creative.style.cssText = [
    `color: ${tokens.ink100}`,
    "font-size: 12px",
    "font-weight: 500",
    "white-space: nowrap",
    "overflow: hidden",
    "text-overflow: ellipsis",
    "max-width: 240px",
  ].join(";");
  bar.appendChild(creative);

  // Draft pill — shows when the creative hasn't been published yet.
  // Published creatives arrive with ctx.creativeId populated.
  if (!ctx.creativeId) {
    const pill = document.createElement("span");
    pill.textContent = "DRAFT";
    pill.setAttribute("role", "status");
    pill.style.cssText = [
      "display: inline-block",
      "padding: 1px 5px",
      `border: 1px solid ${tokens.ink500}`,
      "border-radius: 3px",
      `font-family: ${tokens.sans}`,
      "font-size: 10px",
      `color: ${tokens.ink400}`,
      "letter-spacing: 0.5px",
    ].join(";");
    bar.appendChild(pill);
  }

  // Flex spacer
  const spacer = document.createElement("div");
  spacer.style.flex = "1";
  bar.appendChild(spacer);

  // Actions cluster
  const actions = document.createElement("div");
  actions.style.cssText = "display: flex; align-items: center; gap: 6px;";
  bar.appendChild(actions);

  // (Undo/redo moved to the canvas-header tool cluster — editing
  // actions belong next to the tools, not the lifecycle buttons.)
  actions.appendChild(themeToggle());
  mountPreviewButton(actions, store);
  // Publish only — Save Draft moved to the canvas header next to the
  // editing tools, so a mis-tap near Publish can't silently publish.
  mountPublishButton(actions, store, ctx);

  container.appendChild(bar);

  return {
    update(_state: DesignerState) {
      // History buttons subscribe to the store directly. Other
      // buttons are static — nothing to update here.
    },
  };
}

// Theme cycle: Auto (follows OS) → Light → Dark → Auto. Shows the current
// mode's icon; cycling flips the data-cd-theme attribute, re-resolving
// every --cd-* variable instantly (no re-mount).
const MODE_ICON: Record<ThemeMode, string> = { auto: "◐", light: "☀", dark: "☾" };
function themeToggle(): HTMLElement {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.setAttribute("aria-label", "Cycle theme: auto, light, dark");
  btn.style.cssText = [
    "flex: 0 0 auto",
    "width: 26px",
    "height: 26px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "border-radius: 4px",
    `border: 1px solid ${tokens.ink500}`,
    "background: transparent",
    `color: ${tokens.ink300}`,
    "cursor: pointer",
    "font-size: 14px",
    "line-height: 1",
  ].join(";");
  const render = (): void => {
    const m = getMode();
    btn.textContent = MODE_ICON[m];
    btn.title = `Theme: ${m}${m === "auto" ? " (follows your OS)" : ""} — click to cycle`;
  };
  render();
  btn.addEventListener("click", () => {
    cycleMode();
    render();
  });
  return btn;
}

// Logo badge — small gradient square with "pv" in mono. Matches the
// direction-B prototype visual; gradient colours come from the amber
// ramp on the light side and a redder hue at the dark end for a
// two-tone warm-neutral signature.
function logoBadge(): HTMLElement {
  const el = document.createElement("div");
  el.textContent = "pv";
  el.setAttribute("aria-label", "Promovolve");
  el.style.cssText = [
    "flex: 0 0 auto",
    "width: 20px",
    "height: 20px",
    "border-radius: 4px",
    `background: linear-gradient(135deg, ${tokens.amber}, oklch(0.58 0.15 35))`,
    "color: oklch(0.12 0.04 55)",
    `font-family: ${tokens.sans}`,
    "font-size: 9px",
    "font-weight: 700",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "letter-spacing: -0.5px",
  ].join(";");
  return el;
}
