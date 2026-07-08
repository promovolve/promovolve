// Image saliency for crop-modal auto-suggest, ONNX Runtime Web edition.
//
// Strategy: run U-2-Net-Lite (u2netp, ~4.6 MB ONNX) in the browser
// to produce a salience heatmap, then derive a bounding box from the
// thresholded mask. U-2-Net is purpose-built for salient-object
// segmentation (vs COCO-SSD's object bounding-box detection), so it
// handles abstract images, off-center compositions, and non-COCO
// subjects (typography, graphics, food close-ups) where object
// detection would just shrug.
//
// Why ONNX Runtime Web (not TF.js):
//  - HuggingFace and PyTorch ecosystems publish models as ONNX.
//    Future features (anti-fraud fingerprinting, text classifier)
//    will likely also be ONNX. One toolchain, three uses.
//  - WebGPU backend in onnxruntime-web is currently better-tuned
//    than TF.js's; falls back to WASM when unavailable.
//
// Lazy loading: ort.min.js + the model load on first call so the
// designer bundle stays small. CDN-hosted today; should move to a
// self-hosted R2 mirror before this is depended on at scale.

export interface SalientBox {
  x: number; // % of natural image width
  y: number; // % of natural image height
  w: number;
  h: number;
}

interface OrtTensor {
  data: Float32Array;
  dims: number[];
}

interface OrtSession {
  inputNames: string[];
  outputNames: string[];
  run(feeds: Record<string, OrtTensor>): Promise<Record<string, OrtTensor>>;
}

interface OrtNamespace {
  Tensor: new (
    type: "float32",
    data: Float32Array,
    dims: readonly number[]
  ) => OrtTensor;
  InferenceSession: {
    create(
      modelUrl: string,
      opts?: { executionProviders?: ("webgpu" | "wasm")[] }
    ): Promise<OrtSession>;
  };
  env: { wasm: { wasmPaths?: string; numThreads?: number } };
}

declare global {
  interface Window {
    ort?: OrtNamespace;
  }
}

// Pinned versions — bumping is deliberate. CDN paths hit content-
// hashed caches so they're effectively immutable.
const ORT_VERSION = "1.20.0";
const ORT_SCRIPT_URL = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/ort.min.js`;
const ORT_WASM_BASE = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/`;

// U-2-Net-Lite (u2netp), exported to ONNX. Mirrored to our own R2
// (content-hashed filename, immutable cache). The original artifact
// comes from the rembg project's GitHub release; GitHub's release
// CDN doesn't send CORS headers so the browser blocks cross-origin
// fetches — R2 with our bucket-level CORS rules is the workable
// host. ~4.5 MB. Bumping the version is a deliberate operation:
// re-run platform/creative-designer/scripts/publish-saliency-model.mjs
// (or the inline ad-hoc upload) to push a new hash, then update this
// constant.
const MODEL_URL =
  "https://pub-7ab486148c8740dbb2cc31c5072eb91c.r2.dev/models/u2netp.309c846925.onnx";

// U-2-Net input is 320×320 RGB, ImageNet-normalized.
const INPUT_SIZE = 320;
const NORM_MEAN = [0.485, 0.456, 0.406];
const NORM_STD = [0.229, 0.224, 0.225];

// Saliency-mask threshold for bbox derivation. Values above this are
// treated as "subject"; the bbox is the tightest rectangle around
// those pixels. 0.5 is the standard fg/bg cutoff for U-2-Net's
// sigmoid output. Tighter (0.7) yields stricter / smaller boxes;
// looser (0.3) yields more inclusive / larger boxes.
const MASK_THRESHOLD = 0.5;

// Minimum fraction of the image the mask must cover to count as a
// confident detection. Tiny mask = noisy output, not a real subject —
// fall back to the default full-image crop in that case.
const MIN_COVERAGE = 0.01;

let scriptLoading: Promise<void> | null = null;
let sessionLoading: Promise<OrtSession> | null = null;

function injectScript(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${url}"]`);
    if (existing) {
      if (existing.dataset.loaded === "1") {
        resolve();
        return;
      }
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", (e) => reject(e));
      return;
    }
    const s = document.createElement("script");
    s.src = url;
    s.async = true;
    s.addEventListener("load", () => {
      s.dataset.loaded = "1";
      resolve();
    });
    s.addEventListener("error", (e) => reject(e));
    document.head.appendChild(s);
  });
}

async function ensureScript(): Promise<OrtNamespace> {
  if (!scriptLoading) {
    console.info("[saliency] loading onnxruntime-web v%s from CDN", ORT_VERSION);
    const t0 = performance.now();
    scriptLoading = injectScript(ORT_SCRIPT_URL).then(() => {
      console.info("[saliency] ort.min.js ready (%dms)", Math.round(performance.now() - t0));
    });
  }
  await scriptLoading;
  const ort = window.ort;
  if (!ort) throw new Error("onnxruntime-web global not present after script load");
  // Point ort at the same CDN for its WASM artifacts (ort-wasm.wasm,
  // ort-wasm-simd.wasm, etc.) instead of trying to resolve them from
  // the page's origin. Without this the WASM loads fail in the
  // designer because we're served from /static/ and the WASM files
  // aren't there.
  ort.env.wasm.wasmPaths = ORT_WASM_BASE;
  return ort;
}

async function ensureSession(): Promise<OrtSession> {
  if (sessionLoading) return sessionLoading;
  sessionLoading = (async () => {
    const ort = await ensureScript();
    console.info("[saliency] loading u2netp ONNX model from R2 (~4.5 MB)");
    const t0 = performance.now();
    // Prefer WebGPU when available; fall back to WASM. Order matters —
    // ONNX Runtime tries them in sequence and uses the first that
    // initializes. Most desktops will pick WebGPU; older browsers and
    // some integrated GPUs land on WASM.
    const session = await ort.InferenceSession.create(MODEL_URL, {
      executionProviders: ["webgpu", "wasm"],
    });
    console.info("[saliency] model + session ready (%dms)", Math.round(performance.now() - t0));
    return session;
  })();
  return sessionLoading;
}

/** Kick off the ort.min.js + u2netp.onnx downloads and InferenceSession
  * setup before anything actually needs them. Safe to call multiple
  * times — subsequent calls return the same cached promise. Returns a
  * promise that resolves when the model is fully ready for inference.
  */
export function prewarmSaliency(): Promise<void> {
  return ensureSession().then(() => undefined);
}

/** Rewrite an asset URL so saliency-side image loads (which need to
  * read pixels via canvas, hence `<img crossOrigin="anonymous">`) go
  * through the dashboard's same-origin /proxy/asset/ route instead of
  * R2's pub-r2-dev URL directly. R2's edge cache can serve a CORS-less
  * response for a given URL once it's been fetched without an Origin
  * header — and bucket-level CORS rules can't override that cached
  * response. The proxy hop adds Access-Control-Allow-Origin server-
  * side, immune to edge-cache state.
  *
  * Plain <img> display paths (modal thumbnail, ad-unit) don't need
  * this rewrite — they don't read pixels, so CORS headers don't
  * matter. Keep them on the direct CDN URL.
  */
export function rewriteForSaliency(src: string): string {
  // Match `…/assets/<filename>` (typically `<hash>.<ext>`). If the
  // URL doesn't fit, leave it alone — third-party / non-R2 hosts
  // can't be proxied this way and saliency will skip them anyway.
  const m = src.match(/\/assets\/([^/?#]+)$/);
  if (!m) return src;
  return `/proxy/asset/${m[1]}`;
}

// Resize the image to INPUT_SIZE × INPUT_SIZE via a hidden canvas,
// pull the pixel data, and pack it into a Float32Array in NCHW order
// (the input shape U-2-Net expects). Normalizes per channel using
// ImageNet stats.
function imageToTensorData(img: HTMLImageElement): Float32Array {
  const canvas = document.createElement("canvas");
  canvas.width = INPUT_SIZE;
  canvas.height = INPUT_SIZE;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("2d canvas context unavailable");
  // Stretch-fit. A letterbox would preserve aspect but the bbox we
  // derive afterward is in 0..1 space relative to the *resized*
  // image, which then maps back to source coordinates exactly —
  // stretching is fine and avoids dealing with the letterbox
  // padding offset.
  ctx.drawImage(img, 0, 0, INPUT_SIZE, INPUT_SIZE);
  const { data } = ctx.getImageData(0, 0, INPUT_SIZE, INPUT_SIZE);

  const channelSize = INPUT_SIZE * INPUT_SIZE;
  const out = new Float32Array(3 * channelSize);
  for (let i = 0; i < channelSize; i++) {
    const r = data[i * 4 + 0] / 255;
    const g = data[i * 4 + 1] / 255;
    const b = data[i * 4 + 2] / 255;
    out[i] = (r - NORM_MEAN[0]) / NORM_STD[0];
    out[channelSize + i] = (g - NORM_MEAN[1]) / NORM_STD[1];
    out[2 * channelSize + i] = (b - NORM_MEAN[2]) / NORM_STD[2];
  }
  return out;
}

// Thresholded-bbox extraction from the saliency mask. Walks the
// mask once, tracking the min/max x and y of pixels above
// MASK_THRESHOLD. Returns the rect in 0..1 fractional coords plus
// the raw pixel coverage (used for the confidence gate).
function bboxFromMask(mask: Float32Array): { x: number; y: number; w: number; h: number; coverage: number } | null {
  let minX = INPUT_SIZE;
  let minY = INPUT_SIZE;
  let maxX = -1;
  let maxY = -1;
  let count = 0;
  for (let y = 0; y < INPUT_SIZE; y++) {
    for (let x = 0; x < INPUT_SIZE; x++) {
      const v = mask[y * INPUT_SIZE + x];
      if (v >= MASK_THRESHOLD) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
        count++;
      }
    }
  }
  if (maxX < 0 || maxY < 0) return null;
  return {
    x: minX / INPUT_SIZE,
    y: minY / INPUT_SIZE,
    w: (maxX - minX + 1) / INPUT_SIZE,
    h: (maxY - minY + 1) / INPUT_SIZE,
    coverage: count / (INPUT_SIZE * INPUT_SIZE),
  };
}

export async function findSalientBox(img: HTMLImageElement): Promise<SalientBox | null> {
  if (!img.naturalWidth || !img.naturalHeight) return null;
  try {
    const ort = await ensureScript();
    const session = await ensureSession();
    const tensorData = imageToTensorData(img);
    const input = new ort.Tensor("float32", tensorData, [1, 3, INPUT_SIZE, INPUT_SIZE]);
    const inputName = session.inputNames[0];
    const output = await session.run({ [inputName]: input });

    // U-2-Net publishes 7 sided outputs (d0..d6); d0 is the highest-
    // resolution combined output. Different ONNX exports name it
    // differently — fall back to the first output when the named
    // one isn't present.
    const outputName = session.outputNames.includes("d0_pred") ? "d0_pred"
      : session.outputNames.includes("output") ? "output"
      : session.outputNames[0];
    const mask = output[outputName].data;

    const bbox = bboxFromMask(mask);
    if (!bbox) return null;
    if (bbox.coverage < MIN_COVERAGE) return null;

    // Map 0..1 fractional rect back to %-of-natural-image. The
    // stretch-fit in imageToTensorData means this mapping is direct —
    // the tensor x=0..1 axis spans the whole source width.
    return {
      x: bbox.x * 100,
      y: bbox.y * 100,
      w: bbox.w * 100,
      h: bbox.h * 100,
    };
  } catch (err) {
    console.warn("[saliency] inference failed:", err);
    return null;
  }
}
