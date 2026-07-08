// Static pixel-based rulers for sized IAB banner modes. A thin strip
// across the top of the canvas and another down the left edge, with
// tick marks every 10px and labels every 50px. Lets the advertiser
// compare element sizes between different banner sizes (e.g., keep
// a logo at 80px wide across 300×250 and 728×90).
//
// Not shown on responsive modes (expanded PC, mobile) — pixel values
// are meaningless when the canvas scales.

import { type Mode } from "../modes";
import { tokens } from "../ui/tokens";

const STRIP_THICKNESS = 22; // px — thick enough for legible labels
const MINOR_EVERY = 10;     // px in banner coordinates
const MAJOR_EVERY = 50;

let topStrip: HTMLElement | null = null;
let leftStrip: HTMLElement | null = null;
let corner: HTMLElement | null = null;
let outerFrame: HTMLElement | null = null;
// Viewer-level toggle. When false, rulers stay hidden regardless of
// mode. Persisted in localStorage so the preference survives reloads.
let enabled: boolean = (() => {
  try { return localStorage.getItem("cd-rulers") !== "0"; } catch { return true; }
})();
let lastMode: Mode | null = null;
const listeners = new Set<(enabled: boolean) => void>();

export function mountRulers(canvasWrap: HTMLElement): void {
  if (topStrip && topStrip.isConnected) return;

  // Wrap canvasWrap in an outer frame so rulers can sit OUTSIDE the
  // canvas edges (otherwise overflow:hidden on canvasWrap would clip
  // any children with negative top/left). The frame uses CSS grid
  // with a ruler-sized row + column to reserve space for the strips.
  const parent = canvasWrap.parentElement;
  if (!parent) return;
  outerFrame = document.createElement("div");
  outerFrame.className = "cd-ruler-frame";
  Object.assign(outerFrame.style, {
    position: "relative",
    display: "grid",
    gridTemplateColumns: `${STRIP_THICKNESS}px auto`,
    gridTemplateRows: `${STRIP_THICKNESS}px auto`,
    width: "fit-content",
    margin: "0 auto",
  });
  parent.insertBefore(outerFrame, canvasWrap);
  // Move canvas into the bottom-right cell of the grid.
  outerFrame.appendChild(canvasWrap);
  // canvasWrap no longer owns its own margin — outer frame handles
  // centering. Clear it so we don't double-center.
  canvasWrap.style.margin = "0";

  corner = document.createElement("div");
  corner.className = "cd-ruler-corner";
  Object.assign(corner.style, {
    gridColumn: "1",
    gridRow: "1",
    background: tokens.ink900,
    borderRight: `1px solid ${tokens.ink500}`,
    borderBottom: `1px solid ${tokens.ink500}`,
    visibility: "hidden",
  });
  topStrip = document.createElement("div");
  topStrip.className = "cd-ruler-top";
  Object.assign(topStrip.style, {
    gridColumn: "2",
    gridRow: "1",
    position: "relative",
    background: tokens.ink800,
    borderBottom: `1px solid ${tokens.ink500}`,
    overflow: "hidden",
    fontSize: "9px",
    fontFamily: tokens.sans,
    color: tokens.ink300,
    visibility: "hidden",
  });
  leftStrip = document.createElement("div");
  leftStrip.className = "cd-ruler-left";
  Object.assign(leftStrip.style, {
    gridColumn: "1",
    gridRow: "2",
    position: "relative",
    background: tokens.ink800,
    borderRight: `1px solid ${tokens.ink500}`,
    overflow: "hidden",
    fontSize: "9px",
    fontFamily: tokens.sans,
    color: tokens.ink300,
    visibility: "hidden",
  });
  outerFrame.insertBefore(corner, canvasWrap);
  outerFrame.insertBefore(topStrip, canvasWrap);
  outerFrame.insertBefore(leftStrip, canvasWrap);
  // Put canvas in its grid cell (bottom-right).
  canvasWrap.style.gridColumn = "2";
  canvasWrap.style.gridRow = "2";
}

/** Show/update rulers for the current mode. Hides on non-sized modes
 *  and when the user has turned rulers off via setRulersEnabled. The
 *  grid template always reserves the ruler-sized row + column so the
 *  canvas position doesn't jump when the toggle flips or when the
 *  advertiser switches to/from a non-sized mode. Visibility is
 *  controlled by strip content only. */
export function updateRulers(mode: Mode): void {
  lastMode = mode;
  if (!topStrip || !leftStrip || !corner || !outerFrame) return;
  // Rulers on every mode — PC 16:9, Mobile 9:16, and all sized IAB
  // banners. The displayed values are the mode's reference pixel
  // dimensions (mode.w / mode.h), which are a stable ratio users can
  // compare element sizes against even on responsive expanded modes.
  const show = enabled;
  if (!show) {
    // Keep grid layout; just blank the strips so the canvas stays
    // in the same place whether the rulers are on or off.
    topStrip.style.visibility = "hidden";
    leftStrip.style.visibility = "hidden";
    corner.style.visibility = "hidden";
    return;
  }
  topStrip.style.visibility = "visible";
  leftStrip.style.visibility = "visible";
  corner.style.visibility = "visible";
  renderTicks(topStrip, mode.w, "h");
  renderTicks(leftStrip, mode.h, "v");
}

/** Toggle the user preference; persists to localStorage. */
export function setRulersEnabled(next: boolean): void {
  if (enabled === next) return;
  enabled = next;
  try { localStorage.setItem("cd-rulers", next ? "1" : "0"); } catch { /* ignore */ }
  if (lastMode) updateRulers(lastMode);
  listeners.forEach(fn => fn(next));
}

export function rulersEnabled(): boolean {
  return enabled;
}

export function onRulersToggle(fn: (enabled: boolean) => void): () => void {
  listeners.add(fn);
  return () => { listeners.delete(fn); };
}

function renderTicks(strip: HTMLElement, spanPx: number, axis: "h" | "v"): void {
  strip.innerHTML = "";
  // Ticks are positioned as a percent of the strip's length — the
  // strip spans exactly the banner's displayed extent (minus the
  // opposite strip's corner square), so % of strip = % of banner.
  // Percent per pixel: 100 / spanPx.
  for (let px = 0; px <= spanPx; px += MINOR_EVERY) {
    const pct = (px / spanPx) * 100;
    const isMajor = px % MAJOR_EVERY === 0;
    const tick = document.createElement("div");
    const tickLen = isMajor ? 10 : 5;
    if (axis === "h") {
      Object.assign(tick.style, {
        position: "absolute",
        left: `${pct}%`,
        bottom: "0",
        width: "1px",
        height: `${tickLen}px`,
        background: "rgba(148, 163, 184, 0.55)",
      });
    } else {
      Object.assign(tick.style, {
        position: "absolute",
        top: `${pct}%`,
        right: "0",
        width: `${tickLen}px`,
        height: "1px",
        background: "rgba(148, 163, 184, 0.55)",
      });
    }
    strip.appendChild(tick);
    if (isMajor && px > 0 && px < spanPx) {
      const label = document.createElement("div");
      label.textContent = String(px);
      if (axis === "h") {
        Object.assign(label.style, {
          position: "absolute",
          left: `${pct}%`,
          top: "2px",
          transform: "translateX(-50%)",
          whiteSpace: "nowrap",
        });
      } else {
        Object.assign(label.style, {
          position: "absolute",
          top: `${pct}%`,
          left: "2px",
          transform: "translateY(-50%)",
          whiteSpace: "nowrap",
        });
      }
      strip.appendChild(label);
    }
  }
}
