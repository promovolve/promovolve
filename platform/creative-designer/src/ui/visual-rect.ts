// Zoom-aware element rect for pointer math inside the scaled UI chrome.
//
// index.ts applies CSS `zoom` (UI_SCALE) to the chrome hosts, and the
// browsers disagree on what that does to getBoundingClientRect: Chrome
// and Firefox (standardized, 2024) return zoom-ADJUSTED coordinates that
// match pointer clientX/Y; WebKit still returns unzoomed layout
// coordinates while its pointer events are zoomed. Any
// `(e.clientX - rect.left) / rect.width` inside a zoomed host is
// therefore off by the zoom factor in Safari only — the focal-point
// marker landing far from the cursor was this.
//
// The rect's own baked-in zoom is detectable without UA sniffing:
// offsetWidth is always unzoomed layout px, so rect.width / offsetWidth
// equals whatever zoom the browser already applied to the rect. Scale by
// the remainder against the real cumulative zoom and every browser lands
// in visual (pointer) coordinates.
export interface VisualRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export function visualRect(el: HTMLElement): VisualRect {
  const r = el.getBoundingClientRect();
  const zoom = cumulativeZoom(el);
  if (zoom === 1) return r;
  const baked = el.offsetWidth > 0 && r.width > 0 ? r.width / el.offsetWidth : zoom;
  const missing = zoom / baked;
  return { left: r.left * missing, top: r.top * missing, width: r.width * missing, height: r.height * missing };
}

function cumulativeZoom(el: Element): number {
  // Standardized fast path where present (Chrome 128+ / recent WebKit).
  const c = (el as Element & { currentCSSZoom?: number }).currentCSSZoom;
  if (typeof c === "number" && c > 0) return c;
  let z = 1;
  for (let n: Element | null = el; n; n = n.parentElement) {
    const v = parseFloat(getComputedStyle(n).zoom || "1");
    if (v > 0 && v !== 1) z *= v;
  }
  return z;
}
