import { hasTorn, imageEdgeCss, needsImageWrapper, nextTornMaskId, tornMaskDefs, vignetteOverlayCss } from "./image-effects";
import { trustedHTML } from "./trusted-html";
import type { LayoutItem, Page } from "./types";

// Resolve a layout item's text/src from either a literal value or a
// page-field reference. Page fields are arbitrary strings (see Page.[key]).
function fieldValue(page: Page, field: string | undefined): string {
  if (!field) return "";
  const v = page[field];
  return typeof v === "string" ? v : "";
}

// Render a single layout item to a DOM node for the expanded-overlay view.
// Positions/sizes use CSS % + cq units — the stage is a `container-type: size`
// parent so scaling is driven by the stage's computed box, not the viewport.
//
// The node renders at its base state and, if animationTo is set, the
// owning updatePages handler tweens it to those target values when the
// page becomes active.
export function layoutItemToNode(
  item: LayoutItem,
  page: Page,
  defaultFontFamily: string,
  index: number,
): HTMLElement | null {
  let node: HTMLElement;

  if (item.type === "text") {
    const div = document.createElement("div");
    div.textContent = item.text ?? fieldValue(page, item.field);
    const hasHeight = typeof item.height === "number";
    // Vertical alignment inside an explicit-height box: flex column
    // with the text as an anonymous flex item. Only when authored —
    // packed (auto-height) boxes have no headroom to distribute.
    const vAlign = hasHeight && item.verticalAlign && item.verticalAlign !== "top"
      ? { display: "flex", flexDirection: "column",
          justifyContent: item.verticalAlign === "middle" ? "center" : "flex-end" }
      : {};
    Object.assign(div.style, {
      width: `${item.width ?? 30}%`,
      // Stamp height when authored so textFit:"shrink" has a target
      // box to fit into; without an explicit height, autoFitText has
      // no clientHeight to compare scrollHeight against.
      ...(hasHeight ? { height: `${item.height}%`, overflow: "hidden" } : {}),
      ...vAlign,
      // cqmax (% of the LARGER container side), not cqh, so a single fontSize
      // is useful relative to the creative's dimension: on wide-short formats
      // (leaderboards) it's driven by the width instead of going tiny against
      // the short height; tall/square formats are unchanged (their max dim is
      // the height). autoFitText then shrinks to fit each box.
      fontSize: `${item.fontSize ?? 5}cqmax`,
      fontFamily: item.fontFamily ?? defaultFontFamily,
      color: item.color ?? "#ffffff",
      fontWeight: String(item.fontWeight ?? "normal"),
      textAlign: item.textAlign ?? "left",
      lineHeight: String(item.lineHeight ?? 1.2),
      writingMode: item.writingMode && item.writingMode !== "horizontal-tb" ? item.writingMode : "",
      textOrientation: item.writingMode === "vertical-rl" ? "mixed" : "",
      direction: item.direction === "rtl" ? "rtl" : "",
    });
    if (item.border) {
      Object.assign(div.style, {
        border: `0.2cqmin solid ${item.color ?? "#ffffff"}`,
        padding: "1cqmin 2cqmin",
        borderRadius: "0.3cqmin",
      });
    }
    // Mirror the collapsed-renderer's contract: items with both an
    // explicit height AND textFit="shrink" get tagged so the post-
    // render rAF pass calls autoFitText on them. Without this, the
    // expanded view silently ignored textFit and templates with long
    // copy overflowed their boxes.
    // Shrink-to-fit is the default: any height-bearing text autofits
    // unless it explicitly opts out with textFit:"clip".
    if (hasHeight && item.textFit !== "clip") {
      div.dataset.autofit = "1";
    }
    node = div;
  } else if (item.type === "image") {
    const src = item.src ?? fieldValue(page, item.field);
    const w = item.width ?? 20;
    const h = item.height ?? 20;
    if (!src) {
      // Field-bound image whose page field is empty (e.g. an auto-added
      // slot before an image is set) — render an invisible spacer rather
      // than an <img src=""> (which shows a broken-image icon). Mirrors
      // render-collapsed's `if (!src) return ""`.
      const spacer = document.createElement("div");
      Object.assign(spacer.style, { width: `${w}%`, height: `${h}%` });
      node = spacer;
    } else {
      // Edge effects (frame/feather/torn) ride on the same element that
      // carries box+radius; vignette is a sibling overlay. When a
      // wrapper exists (crop or vignette) the effects go on the wrapper.
      const objectFit = item.fillMode === "fit" ? "contain" : "cover";
      // Torn edge: a per-item inline <mask> def (unique id) injected into
      // the wrapper and referenced by the edge CSS. A data-URI SVG mask
      // renders transparent in Chromium — see image-effects.ts.
      const tornId = hasTorn(item) ? nextTornMaskId() : undefined;
      const tornDefs = tornId ? tornMaskDefs(item.tornEdge!, tornId) : "";
      const edge = imageEdgeCss(item, tornId);
      const vigCss = vignetteOverlayCss(item);
      const overlay = (parent: HTMLElement): void => {
        if (!vigCss) return;
        const ov = document.createElement("div");
        ov.style.cssText = vigCss;
        parent.appendChild(ov);
      };
      if (item.crop) {
        // Mirrors render-collapsed.ts: the wrapper clips a scaled-and-
        // offset inner <img> so only the chosen sub-rectangle of the
        // source shows, filling the bounding box. (Fit and crop are
        // mutually exclusive, so crop always covers.)
        const { x, y, w: cW, h: cH } = item.crop;
        const safeW = Math.max(cW, 1);
        const safeH = Math.max(cH, 1);
        const wrap = document.createElement("div");
        Object.assign(wrap.style, {
          width: `${w}%`,
          height: `${h}%`,
          overflow: "hidden",
          position: "relative",
        });
        if (item.borderRadius) wrap.style.borderRadius = `${item.borderRadius}cqmin`;
        if (edge) wrap.style.cssText += edge;
        if (tornDefs) wrap.insertAdjacentHTML("afterbegin", trustedHTML(tornDefs));
        const img = document.createElement("img");
        img.src = src;
        img.alt = "";
        Object.assign(img.style, {
          position: "absolute",
          left: `${-x / safeW * 100}%`,
          top:  `${-y / safeH * 100}%`,
          width:  `${100 / safeW * 100}%`,
          height: `${100 / safeH * 100}%`,
          maxWidth: "none",
          maxHeight: "none",
          objectFit: "cover",
          display: "block",
        });
        wrap.appendChild(img);
        overlay(wrap);
        node = wrap;
      } else if (needsImageWrapper(item)) {
        // No crop, but vignette needs a wrapper to host its overlay.
        const wrap = document.createElement("div");
        Object.assign(wrap.style, {
          width: `${w}%`,
          height: `${h}%`,
          overflow: "hidden",
          position: "relative",
        });
        if (item.borderRadius) wrap.style.borderRadius = `${item.borderRadius}cqmin`;
        if (edge) wrap.style.cssText += edge;
        if (tornDefs) wrap.insertAdjacentHTML("afterbegin", trustedHTML(tornDefs));
        const img = document.createElement("img");
        img.src = src;
        img.alt = "";
        Object.assign(img.style, {
          position: "absolute",
          inset: "0",
          width: "100%",
          height: "100%",
          objectFit,
          display: "block",
        });
        wrap.appendChild(img);
        overlay(wrap);
        node = wrap;
      } else {
        // Plain image: frame/feather apply straight to the <img> (torn
        // and vignette always take the wrapper branch above).
        const img = document.createElement("img");
        img.src = src;
        img.alt = "";
        Object.assign(img.style, {
          width: `${w}%`,
          height: `${h}%`,
          objectFit,
        });
        if (item.borderRadius) img.style.borderRadius = `${item.borderRadius}cqmin`;
        if (edge) img.style.cssText += edge;
        node = img;
      }
    }
  } else if (item.type === "rect") {
    const div = document.createElement("div");
    const rw = item.width ?? 20;
    const rh = item.height ?? 10;
    // Edge-anchored rects (scrims above all): top% and height% round to
    // device pixels INDEPENDENTLY, so a bottom-anchored rect
    // (top + height = 100) can come up a fraction short and leave a 1px
    // sliver of page background along the bottom — invisible on dark
    // pages, a glaring light line under a dark scrim on a white page.
    // Overdraw edge-anchored rects by 1px; the design box's
    // overflow:hidden clips the excess. Same for the right edge.
    const bottomAnchored = (item.top ?? 0) + rh >= 99.5;
    const rightAnchored = (item.left ?? 0) + rw >= 99.5;
    Object.assign(div.style, {
      width: rightAnchored ? `calc(${100 - (item.left ?? 0)}% + 1px)` : `${rw}%`,
      height: bottomAnchored ? `calc(${100 - (item.top ?? 0)}% + 1px)` : `${rh}%`,
      background: item.fill ?? "transparent",
    });
    if (item.stroke) div.style.border = `0.2cqmin solid ${item.stroke}`;
    if (item.borderRadius) div.style.borderRadius = `${item.borderRadius}cqmin`;
    node = div;
  } else if (item.type === "circle") {
    const div = document.createElement("div");
    const r = item.radius ?? 5;
    Object.assign(div.style, {
      width: `${r * 2}cqmin`,
      height: `${r * 2}cqmin`,
      borderRadius: "50%",
      background: item.fill ?? "transparent",
    });
    if (item.stroke) div.style.border = `0.2cqmin solid ${item.stroke}`;
    node = div;
  } else {
    return null;
  }

  const baseLeft = item.left ?? 0;
  const baseTop = item.top ?? 0;
  const baseRotation = item.rotation ?? 0;
  const baseOpacity = item.opacity ?? 1;

  // Drop shadow. Text uses text-shadow (box-shadow on a transparent
  // text element looks wrong); everything else uses box-shadow on the
  // outer node. cqmin units so shadow scales with the banner.
  if (item.shadow) {
    const sx = item.shadow.x ?? 0;
    const sy = item.shadow.y ?? 0.4;
    const sb = item.shadow.blur ?? 1.2;
    const sc = item.shadow.color ?? "rgba(0,0,0,0.5)";
    const shadowCss = `${sx}cqmin ${sy}cqmin ${sb}cqmin ${sc}`;
    if (item.type === "text") node.style.textShadow = shadowCss;
    else node.style.boxShadow = shadowCss;
  }

  Object.assign(node.style, {
    position: "absolute",
    left: `${baseLeft}%`,
    top: `${baseTop}%`,
    transformOrigin: "center",
    // Reset inherited typography at the item level. Shadow DOM isolates
    // SELECTOR matching but NOT inheritance — inherited props (text-transform,
    // font-style, letter-spacing, …) still cross the boundary from the host
    // page into the shadow tree. A publisher page with `text-transform:
    // uppercase` on body/a wrapper was bleeding through and rendering every
    // creative ALL CAPS (the text data + inline styles never set it). Declaring
    // them here beats inheritance. Per-item text-transform (e.g. the Close
    // pill) still wins via its own inline value.
    textTransform: "none",
    fontStyle: "normal",
    fontVariant: "normal",
    letterSpacing: "normal",
    wordSpacing: "normal",
    rotate: baseRotation ? `${baseRotation}deg` : "",
    opacity: baseOpacity !== 1 ? String(baseOpacity) : "",
    // Promote each animated item to its own GPU layer. Without this,
    // mobile Safari/Chrome leave pixel trails during opacity/transform
    // tweens because the software compositor doesn't fully repaint the
    // previous frame. will-change hints the engine + translate3d forces
    // the hardware layer on older WebKit that ignores will-change.
    willChange: item.animationTo ? "transform, opacity" : "auto",
    transform: "translateZ(0)",
    backfaceVisibility: "hidden",
  });
  node.dataset.animItem = "true";
  node.dataset.layoutItem = "true";
  node.dataset.baseLeft = String(baseLeft);
  node.dataset.baseTop = String(baseTop);
  node.dataset.baseRotation = String(baseRotation);
  node.dataset.baseOpacity = String(baseOpacity);
  node.dataset.layoutIdx = String(index);
  // Field tag so the cross-page autofit harmonizer (motion.ts) can group
  // the same role across the reader's pages and unify their fitted size.
  if (item.type === "text" && item.field) node.dataset.field = item.field;
  if (item.animationTo) node.dataset.animationTo = JSON.stringify(item.animationTo);
  // item.ctaTarget no longer wires anything here: the whole sheet is
  // the click target now (banner.ts wires it at page level), so the
  // field is only a designer styling hint.
  return node;
}
