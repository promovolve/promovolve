// Horizontal size-matrix strip. Sits under the slim top bar, spans
// the full viewport width, and renders every banner size as a
// scrollable chip so the author can see the full fanout at a glance
// without giving up vertical canvas space.
//
// Layout:
//   ┌─────────────────────────────────────────────────────────────┐
//   │ [Fanout · 3/11]     │  [chip] [chip] | [chip] [chip] …      │
//   │ [progress bar]      │                                        │
//   │ [legend]            │  ← horizontal scroll, wheel + thumb    │
//   └─────────────────────────────────────────────────────────────┘
//
//   Left  — fanout summary (stays put while cells scroll).
//   Right — horizontally-scrolling row of size chips, with a 1px
//           divider between Master layouts (Expanded PC/Mobile) and
//           the IAB sizes group.
//
// Each chip shows a 60×32 thumbnail of the current page in that
// mode, the pixel dimensions + fanout dot, and the industry nickname.
// Clicking a chip switches mode. The active chip auto-scrolls into
// view on mode change.

import { currentPage, switchMode } from "../state";
import type { Store } from "../store";
import { MODES, isSized, type Mode } from "../modes";
import type { DesignerState } from "../types";
import { fanoutCounts, fanoutStatus, type FanoutStatus } from "../state/fanout";
import { renderThumbnail } from "./thumbnail";
import { tokens } from "./tokens";

export interface SizeMatrixHandle {
  update(state: DesignerState): void;
}

const CHIP_W = 78;
const CHIP_H = 64;
const THUMB_W = 60;
const THUMB_H = 32;

export function mountSizeMatrix(host: HTMLElement, store: Store): SizeMatrixHandle {
  const strip = document.createElement("div");
  strip.className = "cd-size-matrix";
  strip.style.cssText = [
    "flex: 1 1 auto",
    "display: flex",
    "flex-direction: row",
    "height: 89px", // +1px border = 90 on the pre-zoom grid
    `background: ${tokens.ink800}`,
    `border-bottom: 1px solid ${tokens.ink500}`,
    "padding: 0 14px",
    "gap: 14px",
    "align-items: stretch",
    "overflow: hidden",
    `color: ${tokens.ink200}`,
    `font-family: ${tokens.sans}`,
    "font-size: 12px",
  ].join(";");

  // Left — fanout summary. Stays visible while the chip row scrolls.
  const summary = document.createElement("div");
  summary.style.cssText = [
    "flex: 0 0 auto",
    "min-width: 160px",
    "display: flex",
    "flex-direction: column",
    "justify-content: center",
    "padding: 10px 14px 10px 0",
    `border-right: 1px solid ${tokens.ink500}`,
  ].join(";");

  const headerLabel = document.createElement("div");
  headerLabel.style.cssText = [
    `font-family: ${tokens.sans}`,
    "font-size: 11px",
    `color: ${tokens.ink300}`,
    "margin-bottom: 8px",
  ].join(";");
  summary.appendChild(headerLabel);

  const progress = document.createElement("div");
  progress.style.cssText = [
    "display: flex",
    "height: 4px",
    "border-radius: 2px",
    "overflow: hidden",
    `background: ${tokens.ink600}`,
  ].join(";");
  const progAuth = document.createElement("div");
  progAuth.style.background = tokens.ok;
  progAuth.style.transition = "flex-basis .15s";
  const progGen = document.createElement("div");
  progGen.style.background = tokens.warn;
  progGen.style.transition = "flex-basis .15s";
  const progEmpty = document.createElement("div");
  progEmpty.style.background = "transparent";
  progEmpty.style.transition = "flex-basis .15s";
  progress.append(progAuth, progGen, progEmpty);
  summary.appendChild(progress);

  const legend = document.createElement("div");
  legend.style.cssText = [
    "display: flex",
    "gap: 10px",
    "margin-top: 8px",
    "font-size: 10px",
    `color: ${tokens.ink300}`,
    `font-family: ${tokens.sans}`,
  ].join(";");
  const legAuth  = legendItem(tokens.ok,    "Authored");
  const legGen   = legendItem(tokens.warn,  "Generated");
  const legEmpty = legendItem("",           "Empty");
  legend.append(legAuth.el, legGen.el, legEmpty.el);
  summary.appendChild(legend);

  strip.appendChild(summary);

  // Right — horizontally-scrolling chip row. Scroll behaviour is
  // smooth so programmatic scrollIntoView on mode change glides; the
  // scrollbar is styled thin + ink500 thumb so it doesn't compete
  // with the cells.
  const scroller = document.createElement("div");
  scroller.className = "cd-size-matrix-scroller";
  scroller.style.cssText = [
    "flex: 1",
    "overflow-x: auto",
    "overflow-y: hidden",
    "display: flex",
    "align-items: center",
    "gap: 6px",
    "padding: 10px 0",
    "scroll-behavior: smooth",
    // No scrollbar — the edge chevrons below are the overflow
    // affordance (user decision 2026-08-18); wheel/trackpad/drag and
    // the arrows all still scroll.
    "scrollbar-width: none",
  ].join(";");
  // Wheel-to-scroll-horizontally: trackpad two-finger + mouse wheel
  // both translate vertical deltas into horizontal scroll. Without
  // this, a mouse wheel over the strip would scroll the page or do
  // nothing depending on the surrounding overflow.
  scroller.addEventListener("wheel", (e) => {
    if (e.deltaY === 0) return;
    scroller.scrollLeft += e.deltaY;
    e.preventDefault();
  }, { passive: false });
  installDragScroll(scroller);

  // WebKit scrollbar styling — inline styles can't target pseudo-
  // elements, so inject a one-off <style> keyed to this class name.
  const scrollbarStyle = document.createElement("style");
  scrollbarStyle.textContent = `
.cd-size-matrix-scroller::-webkit-scrollbar { display: none; }
`;
  scroller.appendChild(scrollbarStyle);

  // Chevron overflow affordance: ‹ › float over the row's edges, each
  // visible ONLY while there is content to scroll toward on its side —
  // both hidden when everything fits. Same 24px ghost-button language
  // as the footer pager arrows. A click glides most of a
  // viewport; wheel/trackpad/drag scrolling still works underneath.
  const scrollWrap = document.createElement("div");
  scrollWrap.style.cssText = [
    "flex: 1",
    "position: relative",
    "display: flex",
    "min-width: 0",
  ].join(";");
  const edgeArrow = (label: string, side: "left" | "right"): HTMLButtonElement => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    b.title = side === "left" ? "Scroll sizes left" : "Scroll sizes right";
    b.setAttribute("aria-label", b.title);
    b.style.cssText = [
      "position: absolute",
      `${side}: 0`,
      "top: 50%",
      "transform: translateY(-50%)",
      "z-index: 2",
      "width: 24px",
      "height: 24px",
      "display: none", // shown by updateArrows when scrollable that way
      "align-items: center",
      "justify-content: center",
      `background: ${tokens.ink800}`,
      `color: ${tokens.ink300}`,
      `border: 1px solid ${tokens.ink500}`,
      "border-radius: 2px",
      "font: inherit",
      "font-size: 14px",
      "cursor: pointer",
      "padding: 0",
      `box-shadow: 0 0 8px 6px ${tokens.ink800}`, // soft fade over the chips beneath
      "transition: color .12s, border-color .12s",
    ].join(";");
    b.addEventListener("mouseenter", () => { b.style.color = tokens.ink100; });
    b.addEventListener("mouseleave", () => { b.style.color = tokens.ink300; });
    b.addEventListener("click", () => {
      const dx = Math.max(120, Math.round(scroller.clientWidth * 0.7));
      scroller.scrollBy({ left: side === "left" ? -dx : dx, behavior: "smooth" });
    });
    return b;
  };
  const leftArrow = edgeArrow("\u2039", "left");
  const rightArrow = edgeArrow("\u203a", "right");
  const updateArrows = (): void => {
    const max = scroller.scrollWidth - scroller.clientWidth;
    leftArrow.style.display = scroller.scrollLeft > 1 ? "flex" : "none";
    rightArrow.style.display = scroller.scrollLeft < max - 1 ? "flex" : "none";
  };
  scroller.addEventListener("scroll", updateArrows, { passive: true });
  new ResizeObserver(updateArrows).observe(scroller);
  // Content lands after mount (cells append below) — settle once painted.
  requestAnimationFrame(updateArrows);

  scrollWrap.append(scroller, leftArrow, rightArrow);
  strip.appendChild(scrollWrap);

  interface CellHandle {
    root: HTMLButtonElement;
    thumbWrap: HTMLElement;
    setActive(active: boolean): void;
    setStatus(status: FanoutStatus): void;
    refreshThumb(state: DesignerState): void;
  }
  const cells = new Map<string, CellHandle>();

  // Master layouts first (Expanded PC, Expanded Mobile).
  for (const m of MODES) {
    if (!isSized(m) || m.key === "mobile") {
      cells.set(m.key, buildCell(m, store, scroller));
    }
  }

  // 1px vertical divider between the two groups.
  scroller.appendChild(groupDivider());

  // IAB sizes.
  for (const m of MODES) {
    if (isSized(m) && m.key !== "mobile") {
      cells.set(m.key, buildCell(m, store, scroller));
    }
  }

  host.appendChild(strip);

  let lastModeKey: string | null = null;

  const handle: SizeMatrixHandle = {
    update(state) {
      const page = currentPage(state);
      const counts = fanoutCounts(page ?? ({ layout: [], banners: {} } as never));
      headerLabel.textContent = `Fanout · ${counts.authored}/${counts.total}`;
      const pct = (n: number): string => `${(n / counts.total) * 100}%`;
      progAuth.style.flexBasis  = pct(counts.authored);
      progGen.style.flexBasis   = pct(counts.generated);
      progEmpty.style.flexBasis = pct(counts.empty);
      legAuth.setCount(counts.authored);
      legGen.setCount(counts.generated);
      legEmpty.setCount(counts.empty);

      for (const [key, cell] of cells) {
        const mode = MODES.find((m) => m.key === key);
        if (!mode) continue;
        const active = state.mode.key === key;
        cell.setActive(active);
        cell.setStatus(page ? fanoutStatus(page, mode) : "empty");
        cell.refreshThumb(state);
      }

      // Keep the active chip visible. Only scrolls when the mode
      // actually changed, so drag-in-progress commits that re-trigger
      // update() don't fight the user's manual scroll position.
      // Both `block` and `inline` are passed explicitly because
      // scrollIntoView() with defaults can yank the whole page.
      if (state.mode.key !== lastModeKey) {
        lastModeKey = state.mode.key;
        const active = cells.get(state.mode.key);
        active?.root.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
      }
    },
  };
  return handle;

  function buildCell(mode: Mode, store: Store, parent: HTMLElement): CellHandle {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.style.cssText = [
      "flex: 0 0 auto",
      "display: flex",
      "flex-direction: column",
      "align-items: center",
      "gap: 4px",
      "padding: 6px 8px",
      `width: ${CHIP_W}px`,
      `height: ${CHIP_H}px`,
      "border-radius: 2px",
      "background: transparent",
      "border: 1px solid transparent",
      `color: ${tokens.ink200}`,
      "cursor: pointer",
      "text-align: center",
      "font: inherit",
      "transition: background .12s, border-color .12s, color .12s",
    ].join(";");
    btn.addEventListener("mouseenter", () => {
      if (btn.dataset.active !== "1") btn.style.background = tokens.ink700;
    });
    btn.addEventListener("mouseleave", () => {
      if (btn.dataset.active !== "1") btn.style.background = "transparent";
    });
    btn.addEventListener("click", () => {
      if (store.state.mode.key !== mode.key) {
        store.replace(switchMode(store.state, mode.key));
      }
    });

    // Thumbnail — 60×32, rebuilt on each update so it reflects the
    // current page's layout.
    const thumbWrap = document.createElement("div");
    thumbWrap.style.cssText = [
      "flex: 0 0 auto",
      `width: ${THUMB_W}px`,
      `height: ${THUMB_H}px`,
      "display: flex",
      "align-items: center",
      "justify-content: center",
    ].join(";");
    btn.appendChild(thumbWrap);

    // Label row — dimensions + fanout dot. (The nickname line is gone:
    // the thumbnail's aspect plus the px dims identify the size, the
    // active mode's NAME shows next to Regenerate in the canvas header,
    // and the tooltip carries the full label for hover.)
    btn.title = `${mode.label} — ${mode.dims ?? `${mode.w}×${mode.h}`}`;
    const labelRow = document.createElement("div");
    labelRow.style.cssText = [
      "display: inline-flex",
      "align-items: center",
      "gap: 4px",
      "line-height: 1",
    ].join(";");
    const dims = document.createElement("span");
    dims.style.cssText = [
      `font-family: ${tokens.sans}`,
      "font-size: 10px",
      `color: ${tokens.ink200}`,
      "white-space: nowrap",
    ].join(";");
    dims.textContent = mode.dims ?? `${mode.w}×${mode.h}`;
    const dot = document.createElement("span");
    dot.style.cssText = [
      "width: 5px",
      "height: 5px",
      "border-radius: 50%",
      `box-shadow: inset 0 0 0 1px ${tokens.ink500}`,
    ].join(";");
    labelRow.append(dims, dot);
    btn.appendChild(labelRow);

    // "missing" line — shows only while the size has no layout (the
    // canvas no longer renders a placeholder box in edit mode; this
    // chip line is where that state lives now).
    const missing = document.createElement("span");
    missing.textContent = "missing";
    missing.style.cssText = [
      `font-family: ${tokens.sans}`,
      "font-size: 9px",
      `color: ${tokens.ink100}`,
      `background: ${tokens.warn}`,
      "padding: 0 3px",
      "line-height: 1",
      "display: none",
    ].join(";");
    btn.appendChild(missing);

    parent.appendChild(btn);

    return {
      root: btn,
      thumbWrap,
      setActive(active) {
        btn.dataset.active = active ? "1" : "0";
        btn.setAttribute("aria-current", active ? "true" : "false");
        btn.style.background = "transparent";
        btn.style.borderColor = "transparent";
        btn.style.borderBottom = `2px solid ${active ? tokens.ink100 : "transparent"}`;
        btn.style.color = active ? tokens.ink100 : tokens.ink200;
      },
      setStatus(status) {
        if (status === "authored") {
          dot.style.background = tokens.ok;
          dot.style.boxShadow = "none";
        } else if (status === "generated") {
          dot.style.background = tokens.warn;
          dot.style.boxShadow = "none";
        } else {
          dot.style.background = "transparent";
          dot.style.boxShadow = `inset 0 0 0 1px ${tokens.ink500}`;
        }
        missing.style.display = status === "empty" ? "block" : "none";
      },
      refreshThumb(state) {
        // Always render the cover page so the size matrix is a stable
        // reference frame — switching the editor to page 2/3 shouldn't
        // ripple through every IAB thumbnail. The canvas itself still
        // shows whichever page the author is editing.
        const coverIdx = state.bannerConfig.coverPageIdx ?? 0;
        const page = state.pages[coverIdx] ?? state.pages[0] ?? null;
        thumbWrap.innerHTML = "";
        thumbWrap.appendChild(renderThumbnail(page, mode, THUMB_W, THUMB_H, {
          active: state.mode.key === mode.key,
        }));
      },
    };
  }
}

function groupDivider(): HTMLElement {
  const el = document.createElement("div");
  el.style.cssText = [
    "flex: 0 0 auto",
    "width: 1px",
    "height: 60px",
    `background: ${tokens.ink500}`,
    "margin: 0 4px",
    "align-self: center",
  ].join(";");
  return el;
}

function legendItem(dotColor: string, label: string): { el: HTMLElement; setCount: (n: number) => void } {
  const el = document.createElement("span");
  el.style.cssText = "display: flex; align-items: center; gap: 4px;";
  const dot = document.createElement("span");
  dot.style.cssText = [
    "width: 6px",
    "height: 6px",
    "border-radius: 50%",
    dotColor ? `background: ${dotColor}` : `box-shadow: inset 0 0 0 1px ${tokens.ink500}`,
  ].join(";");
  const text = document.createElement("span");
  const count = document.createElement("span");
  count.style.cssText = `color: ${tokens.ink300};`;
  text.textContent = label;
  el.append(dot, text, count);
  return {
    el,
    setCount(n) { count.textContent = ` ${n}`; },
  };
}

// Press-and-drag on the strip scrolls horizontally, Figma-style.
// Ignores drags that start on a cell button — those still register
// as clicks.
function installDragScroll(scroller: HTMLElement): void {
  let startX = 0;
  let startScroll = 0;
  let dragging = false;
  scroller.addEventListener("pointerdown", (e) => {
    if ((e.target as HTMLElement).closest("button")) return;
    dragging = true;
    startX = e.clientX;
    startScroll = scroller.scrollLeft;
    scroller.setPointerCapture(e.pointerId);
    scroller.style.cursor = "grabbing";
  });
  scroller.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    scroller.scrollLeft = startScroll - (e.clientX - startX);
  });
  const end = (e: PointerEvent): void => {
    if (!dragging) return;
    dragging = false;
    scroller.releasePointerCapture(e.pointerId);
    scroller.style.cursor = "";
  };
  scroller.addEventListener("pointerup", end);
  scroller.addEventListener("pointercancel", end);
}
