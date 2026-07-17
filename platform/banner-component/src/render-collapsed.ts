// Collapsed-state per-item HTML renderer. Produces the inline-styled
// markup for a single LayoutItem inside the collapsed banner's
// design box. Pure: takes the item plus its containing page (for
// `field` indirection) and the default font, returns HTML string.
//
// Lives in its own module so the type-by-type style assembly doesn't
// inflate banner.ts — the function is mostly per-shape inline-style
// concatenation, repeated across text/image/rect/circle. Output is
// concatenated and assigned via shadowRoot.innerHTML by
// renderFromLayout in banner.ts.

import { hasTorn, imageEdgeCss, needsImageWrapper, nextTornMaskId, tornMaskDefs, vignetteOverlayCss } from "./image-effects";
import type { LayoutItem, Page } from "./types";

export function renderCollapsedItemHtml(
  item: LayoutItem,
  i: number,
  page: Page,
  defaultFont: string,
): string {
  const rotation = item.rotation ?? 0;
  const opacity = item.opacity ?? 1;
  // Shadow goes on the outer element for box-types, on the text
  // element for text. boxShadowCss/textShadowCss are kept separate so
  // each per-type branch can decide which to apply.
  const sh = item.shadow;
  const shadowCss = sh
    ? `${sh.x ?? 0}cqmin ${sh.y ?? 0.4}cqmin ${sh.blur ?? 1.2}cqmin ${sh.color ?? "rgba(0,0,0,0.5)"}`
    : "";
  const boxShadowCss = sh && item.type !== "text" ? `box-shadow:${shadowCss};` : "";
  const textShadowCss = sh && item.type === "text" ? `text-shadow:${shadowCss};` : "";
  const baseStyle =
    `position:absolute;` +
    `left:${item.left ?? 0}%;top:${item.top ?? 0}%;` +
    `transform-origin:center;` +
    // Reset inherited typography (see layout-item.ts): Shadow DOM doesn't
    // block CSS inheritance, so a publisher page's `text-transform:uppercase`
    // (and friends) leaks into the collapsed ad and renders it ALL CAPS.
    // Declaring them here beats the inherited value.
    `text-transform:none;font-style:normal;font-variant:normal;letter-spacing:normal;word-spacing:normal;` +
    (rotation ? `rotate:${rotation}deg;` : "") +
    (opacity !== 1 ? `opacity:${opacity};` : "") +
    boxShadowCss;
  const idxAttr = `data-layout-idx="${i}"`;

  if (item.type === "text") {
    const text =
      item.text ?? (item.field ? ((page[item.field] as string) ?? "") : "");
    const fs = item.fontSize ?? 5;
    const ff = item.fontFamily ?? defaultFont;
    const color = item.color ?? "#ffffff";
    const fw = String(item.fontWeight ?? "normal");
    const ta = item.textAlign ?? "left";
    const border = item.border
      ? `border:0.2cqmin solid ${color};padding:1cqmin 2cqmin;border-radius:0.3cqmin;`
      : "";
    // When a text item has an explicit height, we lock to that box
    // and either clip overflow (default) or shrink the font to fit
    // (textFit="shrink", applied by the post-render auto-fit pass).
    const hasHeight = item.height != null;
    const heightStyle = hasHeight ? `height:${item.height}%;overflow:hidden;` : "";
    // Vertical alignment of the headroom in a worst-case-sized box.
    // Mirrors layout-item.ts (expanded renderer).
    const vAlignStyle = hasHeight && item.verticalAlign && item.verticalAlign !== "top"
      ? `display:flex;flex-direction:column;justify-content:${item.verticalAlign === "middle" ? "center" : "flex-end"};`
      : "";
    // Shrink-to-fit is the default: any height-bearing text autofits
    // unless it explicitly opts out with textFit:"clip".
    const fitAttr = hasHeight && item.textFit !== "clip" ? ' data-autofit="1"' : "";
    const writingMode = item.writingMode && item.writingMode !== "horizontal-tb"
      ? `writing-mode:${item.writingMode};text-orientation:mixed;` : "";
    const direction = item.direction === "rtl" ? `direction:rtl;` : "";
    const lh = item.lineHeight ?? 1.2;
    // font-synthesis:none — when a self-hosted family ships a single weight
    // (e.g. Montserrat Thin = 100), don't let the browser fake a heavier/
    // italic face if the item's font-weight differs; render the true face.
    // System families (sans-serif/Georgia) carry real bold, so unaffected.
    return `<div ${idxAttr}${fitAttr} style="${baseStyle}width:${item.width ?? 30}%;${heightStyle}${vAlignStyle}font-size:${fs}cqmax;font-family:${ff};color:${color};font-weight:${fw};font-synthesis:none;text-align:${ta};line-height:${lh};white-space:pre-wrap;word-break:break-word;${writingMode}${direction}${border}${textShadowCss}">${text}</div>`;
  }

  if (item.type === "image") {
    const src =
      item.src ?? (item.field ? ((page[item.field] as string) ?? "") : "");
    if (!src) return "";
    const w = item.width ?? 20;
    const h = item.height ?? 20;
    const br = item.borderRadius ? `border-radius:${item.borderRadius}cqmin;` : "";
    // Edge effects (frame/feather/torn) ride on the same element that
    // carries the box+radius; vignette is a sibling overlay. When a
    // wrapper exists (crop or vignette) the effects go on the wrapper.
    // Torn edge: a per-item inline <mask> def (unique id) referenced by
    // the edge CSS. Emitted inside the wrapper (torn forces one). A
    // data-URI SVG mask renders transparent in Chromium — see
    // image-effects.ts.
    const tornId = hasTorn(item) ? nextTornMaskId() : undefined;
    const tornDefs = tornId ? tornMaskDefs(item.tornEdge!, tornId) : "";
    const edge = imageEdgeCss(item, tornId);
    const vig = vignetteOverlayCss(item);
    const vigHtml = vig ? `<div style="${vig}"></div>` : "";
    const objectFit = item.fillMode === "fit" ? "contain" : "cover";

    // Explicit crop: wrapper div clips a scaled+offset inner <img>
    // so only the chosen sub-rectangle of the source shows, filling
    // the bounding box. Inner img is sized so cropW × containerW
    // equals its rendered cropW; offset by -cropX/cropW * 100%.
    // object-fit:cover on the inner img handles any residual
    // aspect mismatch between crop and bounding box. (Fit and crop are
    // mutually exclusive, so crop always covers.)
    if (item.crop) {
      const { x, y, w: cW, h: cH } = item.crop;
      const safeW = Math.max(cW, 1);
      const safeH = Math.max(cH, 1);
      const innerW = 100 / safeW * 100;
      const innerH = 100 / safeH * 100;
      const innerLeft = -x / safeW * 100;
      const innerTop = -y / safeH * 100;
      const wrapStyle = `${baseStyle}width:${w}%;height:${h}%;overflow:hidden;${br}${edge}`;
      const innerStyle =
        `position:absolute;left:${innerLeft}%;top:${innerTop}%;` +
        `width:${innerW}%;height:${innerH}%;display:block;` +
        `max-width:none;max-height:none;object-fit:cover;`;
      return `<div ${idxAttr} style="${wrapStyle}">${tornDefs}<img src="${src}" alt="" style="${innerStyle}">${vigHtml}</div>`;
    }
    if (needsImageWrapper(item)) {
      // No crop, but vignette/torn need a wrapper (overlay host / mask
      // def). The <img> fills the wrapper; effects + radius live on it.
      const wrapStyle = `${baseStyle}width:${w}%;height:${h}%;overflow:hidden;${br}${edge}`;
      const innerStyle = `position:absolute;inset:0;width:100%;height:100%;object-fit:${objectFit};display:block;`;
      return `<div ${idxAttr} style="${wrapStyle}">${tornDefs}<img src="${src}" alt="" style="${innerStyle}">${vigHtml}</div>`;
    }
    // Plain image: frame/feather apply straight to the <img> (torn and
    // vignette always take the wrapper branch above).
    return `<img ${idxAttr} style="${baseStyle}width:${w}%;height:${h}%;object-fit:${objectFit};${br}${edge}" src="${src}" alt="">`;
  }

  if (item.type === "rect") {
    const fill = item.fill ?? "transparent";
    const stroke = item.stroke ? `border:0.2cqmin solid ${item.stroke};` : "";
    const br = item.borderRadius ? `border-radius:${item.borderRadius}cqmin;` : "";
    // Edge-anchored rects (scrims): %-positioning rounds to device
    // pixels independently, and a hand-dragged scrim can sit at
    // top+height = 99.8 — either way a sub-pixel sliver of the page
    // background shows along the bottom (glaring for a dark scrim on a
    // white creative). A rect meant to touch an edge (within 0.5%) gets
    // its size SNAPPED to overshoot that edge by 1px; the design box's
    // overflow:hidden clips the excess.
    const rw = item.width ?? 20;
    const rh = item.height ?? 10;
    const wCss = (item.left ?? 0) + rw >= 99.5 ? `calc(${100 - (item.left ?? 0)}% + 1px)` : `${rw}%`;
    const hCss = (item.top ?? 0) + rh >= 99.5 ? `calc(${100 - (item.top ?? 0)}% + 1px)` : `${rh}%`;
    return `<div ${idxAttr} style="${baseStyle}width:${wCss};height:${hCss};background:${fill};${stroke}${br}"></div>`;
  }

  if (item.type === "circle") {
    const r = item.radius ?? 5;
    const fill = item.fill ?? "transparent";
    const stroke = item.stroke ? `border:0.2cqmin solid ${item.stroke};` : "";
    return `<div ${idxAttr} style="${baseStyle}width:${r * 2}cqmin;height:${r * 2}cqmin;border-radius:50%;background:${fill};${stroke}"></div>`;
  }


  return "";
}
