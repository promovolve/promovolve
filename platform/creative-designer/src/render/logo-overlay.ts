// Logo overlay — a self-contained drag/resize affordance for the single
// creative-wide brand logo (BannerConfig.logo). The logo isn't a layout
// item (it lives on the config and renders on every page/size), so it sits
// OUTSIDE the per-item overlay system: this module draws its own proxy box
// over where the logo renders and writes position/size back to the config.
// Because it's one config item, every page/size stays in sync for free.

import { clientToPct, type Rect } from "../coords";
import { isMultiPage } from "../modes";
import type { Store } from "../store";
import type { DesignerState } from "../types";

export interface LogoOverlayHandle {
  update(state: DesignerState): void;
}

const MIN = 3; // min logo box size in %

export function mountLogoOverlay(canvasWrap: HTMLElement, store: Store): LogoOverlayHandle {
  const box = document.createElement("div");
  box.style.cssText = [
    "position: absolute",
    "box-sizing: border-box",
    "border: 1px dashed rgba(99,102,241,0.9)",
    "cursor: move",
    "display: none",
    "z-index: 60",
    "touch-action: none",
  ].join(";");

  // Bottom-right resize handle.
  const handle = document.createElement("div");
  handle.style.cssText = [
    "position: absolute",
    "right: -5px",
    "bottom: -5px",
    "width: 11px",
    "height: 11px",
    "background: #6366f1",
    "border: 1px solid #fff",
    "border-radius: 2px",
    "cursor: nwse-resize",
    "touch-action: none",
  ].join(";");
  box.appendChild(handle);
  canvasWrap.appendChild(box);

  const rectOf = (): Rect => {
    const r = canvasWrap.getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  };

  const patchLogo = (patch: Partial<{ left: number; top: number; width: number; height: number }>): void => {
    const bc = store.state.bannerConfig;
    if (!bc.logo) return;
    store.replace({ ...store.state, bannerConfig: { ...bc, logo: { ...bc.logo, ...patch } } });
  };

  // Drag to move.
  box.addEventListener("pointerdown", (e) => {
    if (e.target === handle) return;
    const logo = store.state.bannerConfig.logo;
    if (!logo) return;
    e.preventDefault();
    e.stopPropagation();
    const start = clientToPct(rectOf(), e.clientX, e.clientY);
    const orig = { left: logo.left, top: logo.top };
    const onMove = (ev: PointerEvent): void => {
      const p = clientToPct(rectOf(), ev.clientX, ev.clientY);
      const cur = store.state.bannerConfig.logo;
      if (!cur) return;
      patchLogo({
        left: clamp(orig.left + (p.x - start.x), 0, 100 - cur.width),
        top:  clamp(orig.top + (p.y - start.y), 0, 100 - cur.height),
      });
    };
    const onUp = (): void => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });

  // Corner resize (grows right/down from the top-left anchor).
  handle.addEventListener("pointerdown", (e) => {
    const logo = store.state.bannerConfig.logo;
    if (!logo) return;
    e.preventDefault();
    e.stopPropagation();
    const start = clientToPct(rectOf(), e.clientX, e.clientY);
    const orig = { width: logo.width, height: logo.height, left: logo.left, top: logo.top };
    const onMove = (ev: PointerEvent): void => {
      const p = clientToPct(rectOf(), ev.clientX, ev.clientY);
      patchLogo({
        width:  clamp(orig.width + (p.x - start.x), MIN, 100 - orig.left),
        height: clamp(orig.height + (p.y - start.y), MIN, 100 - orig.top),
      });
    };
    const onUp = (): void => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });

  return {
    update(state) {
      const logo = state.bannerConfig.logo;
      // Logo lives on the expanded view only — its proxy shows on the
      // expanded master, not the per-size collapsed banners.
      if (!logo || !isMultiPage(state.mode)) {
        box.style.display = "none";
        return;
      }
      box.style.display = "block";
      box.style.left = `${logo.left}%`;
      box.style.top = `${logo.top}%`;
      box.style.width = `${logo.width}%`;
      box.style.height = `${logo.height}%`;
    },
  };
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}
