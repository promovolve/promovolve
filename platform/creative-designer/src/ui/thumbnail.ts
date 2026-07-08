// Miniature of a page at a given mode's aspect. Renders into an
// outer frame sized by the caller; an inner canvas is fit inside
// (preserving the mode's aspect ratio) and each layout item is drawn
// as a percent-positioned block — rect as its fill, image as a
// neutral fill, text as a thin color bar, circle as a circle. No
// actual typography or image loading; the goal is that the author
// can recognise which size is which at a glance and see whether
// the composition is roughly right.

import { itemsForMode } from "../state/fanout";
import type { Mode } from "../modes";
import type { LayoutItem, Page } from "../types";
import { tokens } from "./tokens";

export interface ThumbnailOpts {
  active?: boolean;
}

/**
 * Render a thumbnail element for a given mode + page. Caller sets
 * the wrapper's display size via CSS (width/height). The inner
 * canvas sizes itself to match the mode's aspect ratio within the
 * wrapper, leaving padding on the short axis.
 */
export function renderThumbnail(
  page: Page | null,
  mode: Mode,
  width: number,
  height: number,
  opts: ThumbnailOpts = {},
): HTMLElement {
  const wrap = document.createElement("div");
  wrap.style.cssText = [
    "position: relative",
    `width: ${width}px`,
    `height: ${height}px`,
    "flex: 0 0 auto",
    `background: ${tokens.ink800}`,
    `border: 1px solid ${opts.active ? tokens.amberMuted : tokens.ink500}`,
    "border-radius: 4px",
    "overflow: hidden",
    "display: flex",
    "align-items: center",
    "justify-content: center",
  ].join(";");

  const ratio = mode.w / mode.h;
  const frameRatio = (width - 6) / (height - 6);
  const innerW = ratio >= frameRatio
    ? width - 6
    : Math.round((height - 6) * ratio);
  const innerH = ratio >= frameRatio
    ? Math.round((width - 6) / ratio)
    : height - 6;

  const inner = document.createElement("div");
  inner.style.cssText = [
    "position: relative",
    `width: ${innerW}px`,
    `height: ${innerH}px`,
    `background: ${tokens.canvas}`,
    "border-radius: 1px",
    "overflow: hidden",
  ].join(";");
  wrap.appendChild(inner);

  if (!page) return wrap;

  const items = itemsForMode(page, mode);
  for (const item of items) {
    if ((item as LayoutItem & { hidden?: boolean }).hidden) continue;
    const node = renderItem(item);
    if (node) inner.appendChild(node);
  }
  return wrap;
}

function renderItem(item: LayoutItem): HTMLElement | null {
  if (item.type === "rect") {
    const el = box(item.left ?? 0, item.top ?? 0, item.width ?? 20, item.height ?? 10);
    el.style.background = item.fill ?? tokens.ink600;
    return el;
  }
  if (item.type === "image") {
    // ink600 (not ink700): on the light-mode inner canvas, ink700 sits a
    // hair off the surface and vanishes — ink600 stays a visible block in
    // both themes.
    const el = box(item.left ?? 0, item.top ?? 0, item.width ?? 30, item.height ?? 20);
    el.style.background = tokens.ink600;
    return el;
  }
  if (item.type === "circle") {
    const el = document.createElement("div");
    const r = item.radius ?? 5;
    Object.assign(el.style, {
      position: "absolute",
      left: `${item.left ?? 0}%`,
      top: `${item.top ?? 0}%`,
      width: `${r * 2}%`,
      // Approximation — circles use min dim on the real canvas; at
      // thumbnail scale the visual weight matters more than geometric
      // accuracy. Using width-%-squared reads fine.
      height: `${r * 2}%`,
      background: item.fill ?? tokens.ink600,
      borderRadius: "50%",
    });
    return el;
  }
  if (item.type === "text") {
    // A thin horizontal bar at the text's position, tinted by color.
    const el = document.createElement("div");
    Object.assign(el.style, {
      position: "absolute",
      left: `${item.left ?? 0}%`,
      top: `${item.top ?? 0}%`,
      width: `${item.width ?? 30}%`,
      height: "2px",
      background: item.color ?? tokens.ink200,
      opacity: "0.85",
      borderRadius: "1px",
    });
    return el;
  }
  return null;
}

function box(left: number, top: number, width: number, height: number): HTMLElement {
  const el = document.createElement("div");
  Object.assign(el.style, {
    position: "absolute",
    left: `${left}%`,
    top: `${top}%`,
    width: `${width}%`,
    height: `${height}%`,
  });
  return el;
}
