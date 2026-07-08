// Shared CSS builders for the image "edge effects" (feather, frame,
// vignette, torn edge). Both render paths — render-collapsed.ts (HTML
// strings) and layout-item.ts (DOM nodes) — call these so the editor
// preview and the published banner can't drift on what an effect looks
// like. Pure string-builders: no DOM access.
//
// frame + feather + torn live ON the image element (or its wrapper) and
// come from imageEdgeCss(). vignette is an OVERLAY element and comes
// from vignetteOverlayCss(). needsImageWrapper() decides whether a
// positioned wrapper is required (crop already makes one; vignette also
// needs one to host the overlay).

import type { ImageItem } from "./types";

export function hasFeather(item: ImageItem): boolean {
  return (item.feather ?? 0) > 0;
}

export function hasTorn(item: ImageItem): boolean {
  return (item.tornEdge ?? 0) > 0;
}

export function hasVignette(item: ImageItem): boolean {
  return !!item.vignette && item.vignette.strength > 0;
}

// True when the <img> must sit inside a positioned wrapper: crop already
// uses one to clip, vignette needs one to layer its overlay over the
// image, and torn needs one to host its inline <mask> def (see
// tornMaskDefs). When a wrapper exists, the edge CSS (frame/mask/radius)
// goes on the WRAPPER and the inner <img> just fills it.
export function needsImageWrapper(item: ImageItem): boolean {
  return !!item.crop || hasVignette(item) || hasTorn(item);
}

let tornSeq = 0;
// A fresh fragment id for one torn image's <filter>/<mask> pair. Must be
// unique within the shadow root: the expanded reader renders the same
// item index on every page, and the collapsed + expanded views coexist
// during the open transition, so the item index alone would collide.
// Call ONCE per torn item and pass the result to both imageEdgeCss (the
// `url(#id)` reference) and tornMaskDefs (the <mask id> it points at).
export function nextTornMaskId(): string {
  tornSeq += 1;
  return `pv-torn-${tornSeq}`;
}

// mask (feather ∩ torn) as a kebab-case CSS string. Applied to the
// <img> when there's no wrapper, or to the wrapper when there is one.
// tornMaskId is the id of the inline <mask> built by tornMaskDefs; pass
// it whenever the item has a torn edge (torn always uses a wrapper, so
// the def can live beside the masked element).
export function imageEdgeCss(item: ImageItem, tornMaskId?: string): string {
  let css = "";
  const layers: string[] = [];
  if (hasFeather(item)) layers.push(...featherLayers(item.feather!));
  // Torn is a reference to an in-document <mask> element, NOT a data-URI
  // SVG mask: Chromium renders a data-URI SVG used as `mask-image` as a
  // fully transparent mask, which wipes the entire image (it reads as
  // black over the banner's dark stage). A `<mask>` referenced by
  // fragment masks correctly.
  if (hasTorn(item) && tornMaskId) layers.push(`url(#${tornMaskId})`);
  if (layers.length > 0) {
    const img = layers.join(",");
    // Longhands (not the `mask` shorthand, which would reset
    // mask-composite). source-in / intersect ANDs the layers so the
    // two feather axes — and the torn layer — combine instead of stack.
    css +=
      `-webkit-mask-image:${img};mask-image:${img};` +
      `-webkit-mask-repeat:no-repeat;mask-repeat:no-repeat;` +
      `-webkit-mask-size:100% 100%;mask-size:100% 100%;`;
    if (layers.length > 1) {
      css += `-webkit-mask-composite:source-in;mask-composite:intersect;`;
    }
  }
  return css;
}

// Inset linear fade on each axis; intersected (by the caller's
// mask-composite) so all four edges — and the corners — feather.
function featherLayers(amount: number): string[] {
  const a = `${amount}cqmin`;
  return [
    `linear-gradient(to right, transparent, #000 ${a}, #000 calc(100% - ${a}), transparent)`,
    `linear-gradient(to bottom, transparent, #000 ${a}, #000 calc(100% - ${a}), transparent)`,
  ];
}

// Torn/deckle edge: an inline SVG <mask> whose opaque rect is pushed
// around by fractal-noise displacement, used as a luminance mask via
// `mask: url(#id)`. Returned as zero-size, position:absolute markup to be
// dropped beside the masked element (inside its wrapper) — it adds no
// layout. Self-contained (its own filter+mask ids from `uid`), so the
// same string works injected into an HTML string (render-collapsed) or a
// DOM node (layout-item). intensity scales the displacement.
//
// Why a <mask> element and not a data-URI mask-image: see imageEdgeCss —
// Chromium renders a data-URI SVG mask as fully transparent (wipes the
// image), so the mask must be in the document and referenced by fragment.
export function tornMaskDefs(intensity: number, uid: string): string {
  // The mask content AND the filter both work in objectBoundingBox units
  // (the box is 0..1) so the deckle looks the same at any rendered size.
  // tornEdge runs 0..10 (the props-panel slider); map it to a displacement
  // amplitude that's a gentle nibble at 1 and a strong tear at 10, capped
  // so it never eats into the image body. baseFrequency 8 = paper-deckle
  // wobble (lower = broad waves, higher = fine grain).
  const scale = Math.min(0.15, 0.02 + Math.max(0, intensity) * 0.013);
  const fid = `${uid}-f`;
  return (
    `<svg xmlns='http://www.w3.org/2000/svg' width='0' height='0' aria-hidden='true' ` +
    `style='position:absolute;width:0;height:0;pointer-events:none'>` +
    `<defs>` +
    `<filter id='${fid}' filterUnits='objectBoundingBox' primitiveUnits='objectBoundingBox' ` +
    `x='-0.2' y='-0.2' width='1.4' height='1.4'>` +
    `<feTurbulence type='fractalNoise' baseFrequency='8' numOctaves='3' seed='4' result='n'/>` +
    `<feDisplacementMap in='SourceGraphic' in2='n' scale='${scale}' xChannelSelector='R' yChannelSelector='G'/>` +
    `</filter>` +
    `<mask id='${uid}' maskContentUnits='objectBoundingBox'>` +
    `<rect x='0.02' y='0.02' width='0.96' height='0.96' fill='#fff' filter='url(#${fid})'/>` +
    `</mask>` +
    `</defs>` +
    `</svg>`
  );
}

// Overlay element style (position:absolute inset:0). Returns null when
// vignette is off. Caller appends a child div carrying this and inherits
// the wrapper's border-radius so the darkening follows rounded corners.
export function vignetteOverlayCss(item: ImageItem): string | null {
  if (!hasVignette(item)) return null;
  const s = Math.min(1, Math.max(0, item.vignette!.strength));
  const color = item.vignette!.color ?? `rgba(0,0,0,${(0.7 * s).toFixed(3)})`;
  const inner = `${Math.round(60 - s * 40)}%`;
  return (
    `position:absolute;inset:0;pointer-events:none;border-radius:inherit;` +
    `background:radial-gradient(ellipse at center, transparent ${inner}, ${color} 100%);`
  );
}
