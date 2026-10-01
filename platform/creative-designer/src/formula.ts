// LaTeX → SVG for formula items. Rendering happens HERE, at authoring
// time: the result is uploaded as an ordinary SVG image, so the banner
// (and every publisher page) never loads a math engine.
//
// MathJax is vendored at platform/static/vendor/mathjax (the -full build:
// every TeX extension bundled, so it never fetches more at runtime) and
// loaded on first use — the designer bundle itself doesn't grow.

import type { LayoutItem } from "./types";

// Natural pixel size of 1em in the exported SVG. Vector output, so this
// only sets the intrinsic size/aspect the image item starts from.
const EM_PX = 32;

// A formula is an image item that carries its LaTeX source. It is NOT a
// photo: the one-main-image rules (hero binding, images-to-back, Replace
// image, auto-crop) must skip it.
export function isFormula(item: LayoutItem): boolean {
  return item.type === "image" && item.latex !== undefined;
}

interface MathJaxApi {
  tex2svg(tex: string, opts: { display: boolean }): HTMLElement;
  startup: { promise: Promise<void> };
}

let loading: Promise<MathJaxApi> | null = null;

export function loadMathJax(): Promise<MathJaxApi> {
  if (loading) return loading;
  loading = new Promise<MathJaxApi>((resolve, reject) => {
    const w = window as unknown as { MathJax?: unknown };
    // Config must be on window before the script runs.
    //  - fontCache "local" keeps glyph <defs> inside each SVG so the
    //    exported file stands alone.
    //  - noundefined/noerrors would draw a typo in red and let it ship;
    //    without them a bad macro surfaces as data-mjx-error (caught below).
    //  - html (\href \class \style \cssId) and require are dropped: an ad
    //    formula needs neither, and the SVG goes to the CDN unsanitized.
    w.MathJax = {
      startup: { typeset: false },
      svg: { fontCache: "local" },
      tex: { packages: { "[-]": ["noundefined", "noerrors", "html", "require"] } },
    };
    const s = document.createElement("script");
    s.src = window.__DESIGNER__?.mathjaxUrl ?? "/static/vendor/mathjax/tex-svg-full.js";
    s.async = true;
    s.onload = () => {
      const mj = w.MathJax as MathJaxApi;
      mj.startup.promise.then(() => resolve(mj), reject);
    };
    s.onerror = () => reject(new Error("Couldn't load the formula renderer"));
    document.head.appendChild(s);
  }).catch((e: unknown) => {
    loading = null; // any failure: let the next open retry
    throw e;
  });
  return loading;
}

export interface FormulaSvg {
  svg: string;
  width: number;
  height: number;
}

// Render LaTeX to a standalone SVG string. Throws with MathJax's message
// on a TeX error (unknown macro, unbalanced braces…).
export function texToSvg(mj: MathJaxApi, latex: string, color: string): FormulaSvg {
  const node = mj.tex2svg(latex, { display: true });
  const err = node.querySelector("[data-mjx-error]");
  if (err) throw new Error(err.getAttribute("data-mjx-error") ?? "Invalid formula");
  const svg = node.querySelector("svg");
  if (!svg) throw new Error("Invalid formula");
  return finishSvg(new XMLSerializer().serializeToString(svg), color);
}

// Make MathJax's SVG fit for an <img>: bake the ink color in (an <img>
// can't inherit currentColor from the page) and swap the ex-based size
// for pixels derived from the viewBox (MathJax units = 1/1000 em). Also
// drops every link that isn't an in-file glyph reference ("#…") — belt
// and braces with the dropped html package, since this file is served
// from the CDN as-is.
export function finishSvg(raw: string, color: string): FormulaSvg {
  const vb = /viewBox="([-\d.]+) ([-\d.]+) ([\d.]+) ([\d.]+)"/.exec(raw);
  if (!vb) throw new Error("Invalid formula");
  const width = Math.max(1, Math.round((Number(vb[3]) / 1000) * EM_PX));
  const height = Math.max(1, Math.round((Number(vb[4]) / 1000) * EM_PX));
  const svg = raw
    .replace(/\s(?:xlink:)?href="(?!#)[^"]*"/g, "")
    .replace(/currentColor/g, color)
    .replace(/ style="[^"]*"/, "")
    .replace(/ width="[^"]*"/, ` width="${width}"`)
    .replace(/ height="[^"]*"/, ` height="${height}"`);
  return { svg, width, height };
}
