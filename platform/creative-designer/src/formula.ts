// LaTeX → SVG for formula items. Rendering happens HERE, at authoring
// time: the result is uploaded as an ordinary SVG image, so the banner
// (and every publisher page) never loads a math engine.
//
// MathJax is vendored at platform/static/vendor/mathjax (the -full build:
// every TeX extension bundled, so it never fetches more at runtime) and
// loaded on first use — the designer bundle itself doesn't grow.

const MATHJAX_URL = "/static/vendor/mathjax/tex-svg-full.js?v=3.2.2";

// Natural pixel size of 1em in the exported SVG. Vector output, so this
// only sets the intrinsic size/aspect the image item starts from.
const EM_PX = 32;

interface MathJaxApi {
  tex2svg(tex: string, opts: { display: boolean }): HTMLElement;
  startup: { promise: Promise<void> };
}

let loading: Promise<MathJaxApi> | null = null;

export function loadMathJax(): Promise<MathJaxApi> {
  if (loading) return loading;
  loading = new Promise<MathJaxApi>((resolve, reject) => {
    const w = window as unknown as { MathJax?: unknown };
    // Config must be on window before the script runs. fontCache "local"
    // keeps glyph <defs> inside each SVG so the exported file stands alone.
    // noundefined/noerrors would draw a typo in red and let it ship —
    // without them a bad macro surfaces as data-mjx-error, caught below.
    w.MathJax = {
      startup: { typeset: false },
      svg: { fontCache: "local" },
      tex: { packages: { "[-]": ["noundefined", "noerrors"] } },
    };
    const s = document.createElement("script");
    s.src = MATHJAX_URL;
    s.async = true;
    s.onload = () => {
      const mj = w.MathJax as MathJaxApi;
      mj.startup.promise.then(() => resolve(mj), reject);
    };
    s.onerror = () => {
      loading = null; // allow a retry on the next open
      reject(new Error("Couldn't load the formula renderer"));
    };
    document.head.appendChild(s);
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
// for pixels derived from the viewBox (MathJax units = 1/1000 em).
export function finishSvg(raw: string, color: string): FormulaSvg {
  const vb = /viewBox="([-\d.]+) ([-\d.]+) ([\d.]+) ([\d.]+)"/.exec(raw);
  if (!vb) throw new Error("Invalid formula");
  const width = Math.max(1, Math.round((Number(vb[3]) / 1000) * EM_PX));
  const height = Math.max(1, Math.round((Number(vb[4]) / 1000) * EM_PX));
  const svg = raw
    .replace(/currentColor/g, color)
    .replace(/ style="[^"]*"/, "")
    .replace(/ width="[^"]*"/, ` width="${width}"`)
    .replace(/ height="[^"]*"/, ` height="${height}"`);
  return { svg, width, height };
}
