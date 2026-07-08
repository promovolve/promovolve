// 40px strip above the canvas. Acts as the per-banner menu bar:
//
//   Left:  mode label · pixel dims · status pill · divider ·
//          Regenerate this size
//   Right: the page pager (‹ Page n / total ›) — moved here from
//          menu-bar.ts, it's the only pager in the shell now.
//
// Per-size regenerate lives here (not in the top slim bar) because it
// applies to the banner currently on screen. "Regenerate all" stays in
// the top bar. (A cross-size "Duplicate to…"/"Copy from…" action lived
// here too; removed — after the aspect-bucket collapse every remaining
// mode pair differs in shape, so a verbatim percent-coordinate copy
// always produced a distorted composition, and fanout + Regenerate
// already cover "fill this cell".)
//
// The status pill is the "at a glance" cue that complements the
// size-matrix dot on the left rail: both colors come from the same
// fanoutStatus selector so authors see a consistent signal.

import { switchPage } from "../state";
import type { Store } from "../store";
import type { DesignerState } from "../types";
import { regenerateCurrentMode } from "../auto-layout";
import { isMultiPage } from "../modes";
import { mountAlignToolbar } from "./align-toolbar";
import { mountHistoryButtons } from "./history-buttons";
import { mountToolbar } from "./toolbar";
import { tokens } from "./tokens";

export interface CanvasHeaderHandle {
  update(state: DesignerState): void;
}

export function mountCanvasHeader(host: HTMLElement, store: Store): CanvasHeaderHandle {
  const bar = document.createElement("div");
  bar.className = "cd-canvas-header";
  bar.style.cssText = [
    // Wraps instead of overflowing: the full strip needs ~1030px, and
    // anything past the centre column's width used to render UNDER
    // the sidebar (the pager/cover/CTA cluster ghosting through the
    // Properties panel). When tight, the right cluster drops to a
    // second row and the canvas yields the ~40px — never an overlap.
    "min-height: 40px",
    // wrap kept as dormant insurance — with the cover/CTA cluster gone
    // and 6px gaps, the strip fits one row at the 1024 viewport gate
    // (single-row need ~760px vs 764 available), so the fold can't
    // trigger at any supported width unless someone adds chrome.
    "flex-wrap: wrap",
    "row-gap: 0",
    "padding: 0 12px",
    "display: flex",
    "align-items: center",
    "gap: 6px",
    `background: ${tokens.ink800}`,
    `border-bottom: 1px solid ${tokens.ink500}`,
    "flex: 0 0 auto",
    "position: relative",
  ].join(";");

  // Tool group (select / text / image / rect / circle / trash) —
  // lives at the far left of the header, followed by a divider
  // separating it from the banner identity cluster.
  mountToolbar(bar, store);
  bar.appendChild(verticalDivider());

  // Undo / redo — editing actions live with the tools (moved here
  // from the menu bar, which keeps identity + preview/save/publish).
  mountHistoryButtons(bar, store);
  bar.appendChild(verticalDivider());

  // Alignment + distribution group. Always visible; buttons disable
  // themselves when the selection isn't large enough to act on.
  mountAlignToolbar(bar, store);
  bar.appendChild(verticalDivider());

  // (No mode label or status pill here: the active size-matrix chip
  // already names the mode and carries the SAME fanoutStatus dot —
  // the header duplicates were dropped to keep the strip one row.)

  // Per-banner regenerate. Fresh Gemini call (rewrite-copy +
  // generate-layout) for expanded/mobile, deterministic preset for
  // IAB sized modes. Overwrites whatever's currently in the cell — the
  // user can Cmd+Z to restore the previous draft.
  //
  // The mode name rides as a muted label on the RIGHT of the button —
  // it answers "regenerate WHAT?" right where the question arises
  // (e.g. a sized tab showing the canvas's 'Creative missing layout'
  // placeholder). This is the mode identity's only home in the header;
  // the matrix chips carry the dims + status dot.
  const modeTag = document.createElement("span");
  modeTag.style.cssText = [
    `color: ${tokens.ink300}`,
    "font-size: 11px",
    "white-space: nowrap",
  ].join(";");
  const regenBtn = ghostBtn(ICON_SPARKLE, "Regenerate", "Regenerate this size", () => {
    void regenerateCurrentMode(store).catch((e) => console.error("[canvas-header] regenerate failed", e));
  });
  bar.append(regenBtn, modeTag);

  // Spacer pushes pager to the right.
  const spacer = document.createElement("div");
  spacer.style.flex = "1";
  bar.appendChild(spacer);

  // Right cluster — pager
  const pager = document.createElement("div");
  pager.style.cssText = "display:flex;align-items:center;gap:6px;min-height:40px;";
  const prev = arrowBtn("‹", "Previous page");
  const pageLabel = document.createElement("span");
  pageLabel.style.cssText = [
    `color: ${tokens.ink200}`,
    `font-family: ${tokens.sans}`,
    "font-size: 11px",
    "min-width: 40px",
    "text-align: center",
    "font-variant-numeric: tabular-nums",
  ].join(";");
  const next = arrowBtn("›", "Next page");
  prev.addEventListener("click", () => {
    if (store.state.pageIdx > 0) store.replace(switchPage(store.state, store.state.pageIdx - 1));
  });
  next.addEventListener("click", () => {
    if (store.state.pageIdx < store.state.pages.length - 1) {
      store.replace(switchPage(store.state, store.state.pageIdx + 1));
    }
  });
  // (Cover picker removed with the CTA toggle: page 1 is the cover —
  // the editorial default the compose flow builds for. bannerConfig
  // .coverPageIdx stays in the schema, so creatives that chose another
  // cover before this keep rendering it.)
  pager.append(prev, pageLabel, next);
  bar.appendChild(pager);

  host.appendChild(bar);

  return {
    update(state) {
      // Short mode name (aspect parenthetical lives on the chips).
      const modeName = state.mode.label.replace(/\s*\(.*\)$/, "");
      modeTag.textContent = modeName;
      regenBtn.title = `Regenerate ${modeName}`;

      const total = state.pages.length;
      const current = state.pageIdx + 1;
      // Page nav applies in multi-page modes (Expanded PC + Expanded
      // Mobile) — IAB-sized modes are single-frame (cover only) so
      // pages 2/3 don't exist there.
      const pagerVisible = isMultiPage(state.mode);
      pager.style.display = pagerVisible ? "flex" : "none";
      pageLabel.textContent = total > 0 ? `${current} / ${total}` : "—"; // arrows make "page" self-evident
      setEnabled(prev, state.pageIdx > 0);
      setEnabled(next, state.pageIdx < total - 1);

    },
  };
}


// ─── Button builders ──────────────────────────────────────────────

function ghostBtn(iconSvg: string, label: string, title: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.title = title;
  b.setAttribute("aria-label", title);
  b.innerHTML = `${iconSvg}<span>${label}</span>`;
  b.style.cssText = [
    "display: inline-flex",
    "align-items: center",
    "gap: 5px",
    "padding: 4px 9px",
    "background: transparent",
    `color: ${tokens.ink200}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
    "white-space: nowrap",
    "transition: background .12s, color .12s, border-color .12s",
  ].join(";");
  b.addEventListener("mouseenter", () => {
    if (!b.disabled) {
      b.style.background = tokens.ink700;
      b.style.color = tokens.ink100;
    }
  });
  b.addEventListener("mouseleave", () => {
    if (!b.disabled) {
      b.style.background = "transparent";
      b.style.color = tokens.ink200;
    }
  });
  b.addEventListener("click", onClick);
  return b;
}

function verticalDivider(): HTMLElement {
  const el = document.createElement("div");
  el.style.cssText = `width: 1px; height: 20px; background: ${tokens.ink500}; margin: 0 2px;`;
  return el;
}

function arrowBtn(label: string, title: string): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.title = title;
  b.setAttribute("aria-label", title);
  b.style.cssText = [
    "width: 24px",
    "height: 24px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    `color: ${tokens.ink300}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "font: inherit",
    "font-size: 14px",
    "cursor: pointer",
    "padding: 0",
    "transition: color .12s, border-color .12s",
  ].join(";");
  b.addEventListener("mouseenter", () => { if (!b.disabled) b.style.color = tokens.ink100; });
  b.addEventListener("mouseleave", () => { if (!b.disabled) b.style.color = tokens.ink300; });
  return b;
}

function setEnabled(btn: HTMLButtonElement, enabled: boolean): void {
  btn.disabled = !enabled;
  btn.style.opacity = enabled ? "1" : "0.35";
  btn.style.cursor = enabled ? "pointer" : "default";
}

// ─── Icon SVGs ────────────────────────────────────────────────────

const ICON_SPARKLE = `<svg viewBox="0 0 14 14" width="12" height="12" fill="currentColor" aria-hidden="true"><path d="M7 1l1.2 3.3L11.5 5.5 8.2 6.7 7 10 5.8 6.7 2.5 5.5l3.3-1.2L7 1z"/><path d="M11 9l.5 1.4L13 11l-1.5.5L11 13l-.5-1.5L9 11l1.5-.5L11 9z"/></svg>`;
