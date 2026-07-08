// 30px strip below the canvas. Two clusters:
//
//   Left:  spec metrics from state/spec-metrics (PR 6 will wire real
//          numbers — for now weight stays 0, click-zone reads the
//          ctaTarget rect, legibility counts sub-3%-height text).
//   Right: rulers toggle + zoom slider + readout. These moved down
//          from the menu bar because they're canvas-view concerns
//          rather than identity chrome.

import { setZoom } from "../state";
import type { Store } from "../store";
import type { DesignerState } from "../types";
import { onRulersToggle, rulersEnabled, setRulersEnabled } from "../render/rulers";
import { specMetrics, WEIGHT_MAX } from "../state/spec-metrics";
import { tokens } from "./tokens";

export interface CanvasFootHandle {
  update(state: DesignerState): void;
}

export function mountCanvasFoot(host: HTMLElement, store: Store): CanvasFootHandle {
  const bar = document.createElement("div");
  bar.className = "cd-canvas-foot";
  bar.style.cssText = [
    "height: 30px",
    "padding: 0 12px",
    "display: flex",
    "align-items: center",
    "gap: 10px",
    `background: ${tokens.ink800}`,
    `border-top: 1px solid ${tokens.ink500}`,
    `color: ${tokens.ink300}`,
    `font-family: ${tokens.sans}`,
    "font-size: 11px",
    "flex: 0 0 auto",
  ].join(";");

  // Left: spec metrics
  const metrics = document.createElement("div");
  metrics.style.cssText = "display:flex;align-items:center;gap:10px;min-width:0;flex:1;overflow:hidden;";

  const weight = metric();
  const sepVideo = separator();
  const video = metric();
  // Video metric sits between weight and click-zone. Hidden (with its
  // leading separator) when no video background is set so the row
  // doesn't grow a meaningless "· Video 0 B" pill on every creative.
  sepVideo.style.display = "none";
  video.el.style.display = "none";
  metrics.append(weight.el, sepVideo, video.el);
  bar.appendChild(metrics);

  // Right: rulers + zoom
  const right = document.createElement("div");
  right.style.cssText = "display:flex;align-items:center;gap:10px;flex:0 0 auto;";

  const rulersBtn = document.createElement("button");
  rulersBtn.type = "button";
  rulersBtn.title = "Toggle rulers";
  rulersBtn.setAttribute("aria-label", "Toggle rulers");
  rulersBtn.textContent = "\u2195\u2194";
  rulersBtn.style.cssText = [
    "width: 22px",
    "height: 22px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "background: transparent",
    `color: ${tokens.ink400}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font-size: 11px",
    "padding: 0",
    "transition: color .12s, background .12s, border-color .12s",
  ].join(";");
  const applyRulerBtnState = (on: boolean): void => {
    rulersBtn.style.color = on ? tokens.amber : tokens.ink400;
    rulersBtn.style.borderColor = on ? tokens.amberMuted : tokens.ink500;
    rulersBtn.style.background = on ? tokens.amberBg : "transparent";
  };
  applyRulerBtnState(rulersEnabled());
  rulersBtn.addEventListener("click", () => setRulersEnabled(!rulersEnabled()));
  onRulersToggle(applyRulerBtnState);
  right.appendChild(rulersBtn);

  const zoomInput = document.createElement("input");
  zoomInput.type = "range";
  zoomInput.min = "0.25";
  zoomInput.max = "3";
  zoomInput.step = "0.05";
  zoomInput.title = "Canvas zoom";
  zoomInput.style.cssText = `width: 110px; accent-color: ${tokens.amber}; cursor: pointer;`;
  zoomInput.addEventListener("input", () => {
    const z = Number(zoomInput.value);
    if (Number.isFinite(z) && z !== store.state.zoom) store.replace(setZoom(store.state, z));
  });
  right.appendChild(zoomInput);

  const zoomReadout = document.createElement("button");
  zoomReadout.type = "button";
  zoomReadout.title = "Reset to 1×";
  zoomReadout.setAttribute("aria-label", "Reset zoom to 1×");
  zoomReadout.style.cssText = [
    "min-width: 42px",
    "text-align: right",
    `color: ${tokens.ink200}`,
    `font-family: ${tokens.sans}`,
    "font-size: 11px",
    "background: transparent",
    "border: none",
    "padding: 0 2px",
    "cursor: pointer",
  ].join(";");
  zoomReadout.addEventListener("click", () => store.replace(setZoom(store.state, 1)));
  right.appendChild(zoomReadout);

  bar.appendChild(right);
  host.appendChild(bar);

  return {
    update(state) {
      const metrics = specMetrics(state);

      weight.setValue(
        `Weight ${metrics.weightKb} KB / ${WEIGHT_MAX} KB`,
        thresholdColor(metrics.weightThreshold),
      );

      // Video is billed separately by most ad servers — show its size
      // as its own pill when present so the static weight threshold
      // stays meaningful. 0 bytes = no video or size unknown → hide.
      if (metrics.videoBytes > 0) {
        sepVideo.style.display = "";
        video.el.style.display = "";
        video.setValue(`Video ${formatBytes(metrics.videoBytes)}`, tokens.ink200);
      } else {
        sepVideo.style.display = "none";
        video.el.style.display = "none";
      }

      // Zoom slider sync (only when user isn't currently dragging it).
      if (document.activeElement !== zoomInput) zoomInput.value = String(state.zoom);
      zoomReadout.textContent = `${state.zoom.toFixed(2).replace(/\.?0+$/, "")}×`;
    },
  };
}

function metric(): { el: HTMLElement; setValue: (text: string, color: string) => void } {
  const el = document.createElement("span");
  el.style.cssText = "white-space: nowrap; overflow: hidden; text-overflow: ellipsis;";
  return {
    el,
    setValue(text, color) {
      el.textContent = text;
      el.style.color = color;
    },
  };
}

function separator(): HTMLElement {
  const el = document.createElement("span");
  el.textContent = "·";
  el.style.color = tokens.ink500;
  return el;
}

function thresholdColor(t: "ok" | "warn" | "err"): string {
  if (t === "ok")   return tokens.ok;
  if (t === "warn") return tokens.warn;
  return tokens.err;
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}
