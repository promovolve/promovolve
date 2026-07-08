// Auto-apply saliency-suggested crops to image items in the expanded
// layout when the designer first opens. Runs in the background after
// boot — non-blocking, picks up uncropped images and stamps a crop
// based on the U-2-Net salient-region mask.
//
// The crop-modal already does this per-image when the user opens it
// for an uncropped image (see crop-modal.ts:208 hadAuthoredCrop). This
// module batches the same call eagerly so the post-LP-extract designer
// load comes up with sensible crops already applied — fewer clicks
// before publish.
//
// Skipped:
// - Items that already have a crop (resumed drafts stay untouched)
// - Items without a src (nothing to look at)
// - Banner-size layouts (page.banners[size]) — only the expanded layout
//   is auto-cropped, since that's the master the author primarily
//   shapes. Banner sizes are auto-generated and re-cropped from the
//   master if needed.

import type { LayoutItem } from "@banner/types";
import { findMode } from "./modes";
import { findSalientBox, prewarmSaliency, rewriteForSaliency } from "./saliency";
import { currentLayout, currentPage, updateItem } from "./state";
import type { Store } from "./store";
import type { DesignerState } from "./types";

type CropBox = { x: number; y: number; w: number; h: number };

interface ItemRef {
  pageIdx: number;
  itemIdx: number;
  src: string;
  // Item box in canvas percents — needed to aspect-fit the crop (the
  // renderer's crop math is only exact when crop pixel aspect == item
  // box pixel aspect). Defaults mirror the renderer (layout-item.ts).
  width: number;
  height: number;
}

function findUncroppedImages(state: DesignerState): ItemRef[] {
  const refs: ItemRef[] = [];
  state.pages.forEach((page, pageIdx) => {
    (page.layout ?? []).forEach((item, itemIdx) => {
      if (item.type !== "image") return;
      if (item.crop) return;
      const src = item.src;
      if (!src) return;
      refs.push({
        pageIdx,
        itemIdx,
        src,
        width: item.width ?? 20,
        height: item.height ?? 20,
      });
    });
  });
  return refs;
}

type Outcome =
  | { status: "ok"; ref: ItemRef; box: CropBox }
  | { status: "cors"; ref: ItemRef }       // image load blocked (CORS / 404 / network)
  | { status: "no-subject"; ref: ItemRef } // saliency returned no usable box
  | { status: "error"; ref: ItemRef };     // ONNX / inference threw

// ─── Aspect-fitting ────────────────────────────────────────────────
//
// The raw saliency box is a TIGHT bounding rect of the subject with an
// arbitrary aspect. Committing it as-is is what made boot crops look
// wrong: the banner renderer scales the crop window to fill the item
// box exactly, and when the window's pixel aspect differs from the
// box's, object-fit:cover silently re-crops around center — the
// authored window isn't what renders, so subjects drifted out of
// frame or over-zoomed. (The crop-modal avoids this by adjusting the
// ITEM's height at commit — fine for a deliberate user action, but the
// boot pass must not reshape the generated layout.)
//
// So fit the window to the item instead: pad the subject, then expand
// to exactly the item box's pixel aspect, never shrinking below the
// subject, with a minimum-size floor so a tiny subject doesn't become
// an extreme zoom. The result renders pixel-exact with the subject
// centered and breathing room around it.

export interface FitOptions {
  /** Padding around the subject before aspect-fitting, as a fraction
    * of the subject's own size per side. */
  padFrac?: number;
  /** Minimum crop-window size as a fraction of the image dimension —
    * the floor that stops a tiny salient region from producing a
    * pixelated extreme close-up. */
  minFrac?: number;
}

/** Expand a salient-subject rect (%-of-image coords) into a crop
  * window with exactly `targetAspect` (item box pixel aspect, w/h),
  * padded, floored, clamped inside the image, centered on the subject.
  * Pure — exported for tests. */
export function fitCropToItemAspect(
  subject: CropBox,
  imgW: number,
  imgH: number,
  targetAspect: number,
  opts: FitOptions = {},
): CropBox {
  const padFrac = opts.padFrac ?? 0.08;
  const minFrac = opts.minFrac ?? 0.3;
  // Degenerate item boxes (zero/negative height) would blow the math
  // up through Infinity — treat them as square.
  if (!Number.isFinite(targetAspect) || targetAspect <= 0) targetAspect = 1;

  // Percent → px, then pad each side relative to the subject's size.
  let sx = (subject.x / 100) * imgW;
  let sy = (subject.y / 100) * imgH;
  let sw = (subject.w / 100) * imgW;
  let sh = (subject.h / 100) * imgH;
  const padX = sw * padFrac;
  const padY = sh * padFrac;
  sx -= padX; sy -= padY; sw += 2 * padX; sh += 2 * padY;

  // Expand (never shrink) to the target aspect so the window contains
  // the padded subject in both dimensions.
  let w = Math.max(sw, sh * targetAspect);
  let h = w / targetAspect;

  // Minimum-window floor: scale up (preserving aspect) until one
  // dimension reaches minFrac of its image dimension.
  if (w < imgW * minFrac && h < imgH * minFrac) {
    const scale = Math.min((imgW * minFrac) / w, (imgH * minFrac) / h);
    w *= scale;
    h *= scale;
  }

  // Clamp to the image, preserving aspect. The second clamp can't
  // re-violate the first: w = h×A ≤ imgH×A, and being in that branch
  // means imgW/A > imgH ⇒ imgH×A < imgW.
  if (w > imgW) { w = imgW; h = w / targetAspect; }
  if (h > imgH) { h = imgH; w = h * targetAspect; }

  // Center on the subject, slide into bounds.
  const cx = sx + sw / 2;
  const cy = sy + sh / 2;
  const x = Math.min(Math.max(cx - w / 2, 0), imgW - w);
  const y = Math.min(Math.max(cy - h / 2, 0), imgH - h);

  // Px → percent, rounded to 0.1 like the crop-modal's commit.
  const r = (v: number): number => Math.round(v * 10) / 10;
  return {
    x: r((x / imgW) * 100),
    y: r((y / imgH) * 100),
    w: r((w / imgW) * 100),
    h: r((h / imgH) * 100),
  };
}

/** The largest sub-rect of `crop` with exactly `targetAspect` (pixel
  * aspect), centered inside it. This is what object-fit:cover already
  * SHOWS while a cropped image's box is being reshaped — committing it
  * makes the stored crop match the pixels on screen, with no visual
  * jump at pointer-up. Always a subset of the old window, so no image-
  * bounds clamping is needed. Pure — exported for tests. */
export function shrinkCropToAspect(
  crop: CropBox,
  imgW: number,
  imgH: number,
  targetAspect: number,
): CropBox {
  if (!Number.isFinite(targetAspect) || targetAspect <= 0) targetAspect = 1;
  const wPx = (crop.w / 100) * imgW;
  const hPx = (crop.h / 100) * imgH;
  let w = Math.min(wPx, hPx * targetAspect);
  let h = w / targetAspect;
  if (h > hPx) { h = hPx; w = h * targetAspect; }
  const x = (crop.x / 100) * imgW + (wPx - w) / 2;
  const y = (crop.y / 100) * imgH + (hPx - h) / 2;
  const r = (v: number): number => Math.round(v * 10) / 10;
  return {
    x: r((x / imgW) * 100),
    y: r((y / imgH) * 100),
    w: r((w / imgW) * 100),
    h: r((h / imgH) * 100),
  };
}

/** After an image item's box changes shape, re-fit its crop window to
  * the new box aspect (WYSIWYG: commits the sub-window cover was
  * already showing on screen, so nothing visibly moves). No-ops for
  * uncropped items, missing src, items that vanished (undo raced the
  * async image load), or when the crop already matches. Async because
  * it needs the image's natural pixel size; on a rendered canvas the
  * image is browser-cached so it resolves near-instantly, landing as a
  * small follow-up commit right after the resize commit. */
export async function refitItemCropToBox(store: Store, itemIdx: number): Promise<void> {
  const item = currentLayout(store.state)[itemIdx];
  if (!item || item.type !== "image" || !item.crop) return;
  // Resolve like the renderer — a field-bound (shared) image has no
  // item.src; it reads page[field]. Without this, resizing a cropped
  // shared image never re-fit the crop.
  const page = currentPage(store.state) as Record<string, unknown> | null;
  const resolveSrc = (it: LayoutItem): string | undefined =>
    (it as { src?: string }).src ??
    ((it as { field?: string }).field ? (page?.[(it as { field?: string }).field!] as string | undefined) : undefined);
  const src = resolveSrc(item);
  if (!src) return;
  const img = await new Promise<HTMLImageElement | null>((resolve) => {
    const el = new Image();
    el.crossOrigin = "anonymous";
    el.onload = () => resolve(el.naturalWidth > 0 ? el : null);
    el.onerror = () => resolve(null);
    el.src = rewriteForSaliency(src);
  });
  if (!img) return;
  const after = currentLayout(store.state)[itemIdx];
  if (!after || after.type !== "image" || !after.crop || resolveSrc(after) !== src) return; // changed under us
  const mode = store.state.mode;
  const boxAspect =
    ((after.width ?? 20) * mode.w) / (Math.max(0.001, after.height ?? 20) * mode.h);
  const next = shrinkCropToAspect(after.crop, img.naturalWidth, img.naturalHeight, boxAspect);
  const c = after.crop;
  if (next.x === c.x && next.y === c.y && next.w === c.w && next.h === c.h) return;
  store.commit(updateItem(store.state, itemIdx, (it) =>
    it.type === "image" ? { ...it, crop: next } : it));
}

function loadImage(src: string): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img.naturalWidth > 0 ? img : null);
    img.onerror = () => resolve(null);
    img.src = rewriteForSaliency(src);
  });
}

async function suggestFor(ref: ItemRef, itemAspect: number): Promise<Outcome> {
  const img = await loadImage(ref.src);
  if (!img) return { status: "cors", ref };
  try {
    const box = await findSalientBox(img);
    if (!box) return { status: "no-subject", ref };
    // Fit the tight subject box to the item's pixel aspect — the raw
    // box renders distorted/re-cropped (see fitCropToItemAspect).
    const fitted = fitCropToItemAspect(box, img.naturalWidth, img.naturalHeight, itemAspect);
    return { status: "ok", ref, box: fitted };
  } catch {
    return { status: "error", ref };
  }
}

async function runWithLimit<T>(tasks: Array<() => Promise<T>>, limit: number): Promise<T[]> {
  const results: T[] = new Array(tasks.length);
  let cursor = 0;
  const worker = async (): Promise<void> => {
    while (cursor < tasks.length) {
      const my = cursor++;
      results[my] = await tasks[my]!();
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, () => worker()));
  return results;
}

function applyCrops(
  state: DesignerState,
  results: Array<{ ref: ItemRef; box: CropBox }>,
): DesignerState {
  if (results.length === 0) return state;
  const byPage = new Map<number, Map<number, CropBox>>();
  for (const r of results) {
    let inner = byPage.get(r.ref.pageIdx);
    if (!inner) {
      inner = new Map();
      byPage.set(r.ref.pageIdx, inner);
    }
    inner.set(r.ref.itemIdx, r.box);
  }

  const pages = state.pages.map((page, pageIdx) => {
    const inner = byPage.get(pageIdx);
    if (!inner || !page.layout) return page;
    const layout: LayoutItem[] = page.layout.map((it, itemIdx) => {
      const box = inner.get(itemIdx);
      if (!box) return it;
      // The wider designer convention: when the user touches an auto-
      // generated item, it loses _generated. Adding a crop is a
      // touch — strip the flag so fanout doesn't treat the item as
      // pristine and overwrite it on regenerate.
      const { _generated: _g, ...rest } = it as LayoutItem & { _generated?: boolean };
      return { ...rest, crop: box } as LayoutItem;
    });
    return { ...page, layout };
  });
  return { ...state, pages };
}

// ─── Status pill ───────────────────────────────────────────────────
//
// A small floating element that surfaces the auto-crop pass to the
// user — model download is a few seconds on a cold cache, and silent
// network activity at boot makes the designer feel slow without a
// signal. Pill is fixed bottom-right, low-contrast, and removes itself
// when the pass finishes (after a short "done" beat).

function showPill(text: string): HTMLElement {
  let el = document.getElementById("cd-auto-crop-status");
  if (el) {
    el.textContent = text;
    el.style.opacity = "1";
    return el;
  }
  el = document.createElement("div");
  el.id = "cd-auto-crop-status";
  el.style.cssText = [
    "position: fixed",
    "right: 16px",
    "bottom: 16px",
    "padding: 8px 14px",
    "background: rgba(20,20,20,0.85)",
    "color: rgba(255,255,255,0.9)",
    "border: 1px solid rgba(255,255,255,0.15)",
    "border-radius: 999px",
    "font: 12px/1 system-ui, -apple-system, sans-serif",
    "z-index: 9999",
    "pointer-events: none",
    "transition: opacity 200ms ease-out",
    "opacity: 1",
  ].join(";");
  el.textContent = text;
  document.body.appendChild(el);
  return el;
}

function setPill(text: string): void {
  const el = document.getElementById("cd-auto-crop-status");
  if (el) el.textContent = text;
  else showPill(text);
}

function hidePill(afterMs = 1500): void {
  const el = document.getElementById("cd-auto-crop-status");
  if (!el) return;
  setTimeout(() => {
    el.style.opacity = "0";
    setTimeout(() => el.remove(), 250);
  }, afterMs);
}

// Minimum visible time per pill phase. Without this, fast paths
// (warm cache, 3-concurrent inference) finish so quickly the
// browser only renders one frame and the user sees Loading→Done
// with no intermediate "Auto-cropping…" state. 600-800ms is enough
// for the eye to register the transition.
const MIN_PHASE_MS = 700;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Run a saliency pass over all uncropped image items in the expanded
  * layout and commit the suggested crops. One undo step covers the
  * entire pass. Safe to call repeatedly — items with an existing crop
  * are skipped, so re-runs are no-ops once everything is cropped.
  */
export async function autoCropOnBoot(store: Store): Promise<void> {
  const refs = findUncroppedImages(store.state);
  if (refs.length === 0) {
    console.info("[auto-crop] no uncropped images — skipping pass");
    return;
  }
  console.info("[auto-crop] processing %d uncropped image item(s)", refs.length);

  // Pre-warm the model in parallel with image fetches. Both operate
  // independently until inference, where they need each other —
  // starting them together shaves a round-trip off the cold path.
  // Hold the loading pill for MIN_PHASE_MS even on a cache hit so
  // the user can read it before it changes.
  showPill("Loading saliency model…");
  const loadStart = performance.now();
  await prewarmSaliency();
  const loadElapsed = performance.now() - loadStart;
  if (loadElapsed < MIN_PHASE_MS) await sleep(MIN_PHASE_MS - loadElapsed);

  const total = refs.length;
  let done = 0;
  setPill(`Auto-cropping (0/${total})…`);
  const inferStart = performance.now();

  // The boot pass only touches the expanded master layout, so the
  // item's rendered pixel aspect always derives from the expanded
  // canvas — NOT state.mode, which may be a banner size at boot.
  const canvas = findMode("expanded");
  const tasks = refs.map((ref) => async () => {
    const itemAspect = (ref.width * canvas.w) / (ref.height * canvas.h);
    const r = await suggestFor(ref, itemAspect);
    done++;
    setPill(`Auto-cropping (${done}/${total})…`);
    return r;
  });

  // Concurrency cap: 3 keeps the ONNX session from thrashing while
  // still amortising the initial model load across requests.
  const outcomes = await runWithLimit(tasks, 3);
  // Same trick on the inference phase so a fast (cached / few items)
  // pass still flashes the "Auto-cropping (k/N)…" state long enough
  // to read instead of jumping straight to the summary.
  const inferElapsed = performance.now() - inferStart;
  if (inferElapsed < MIN_PHASE_MS) await sleep(MIN_PHASE_MS - inferElapsed);
  const ok = outcomes.filter((o): o is Outcome & { status: "ok" } => o.status === "ok");
  const cors = outcomes.filter((o) => o.status === "cors").length;
  const noSubject = outcomes.filter((o) => o.status === "no-subject").length;
  const errored = outcomes.filter((o) => o.status === "error").length;

  // Persistent log line: helps a publisher report back what happened
  // without needing to expand the console for individual phase logs.
  console.info(
    "[auto-crop] pass complete: %d cropped, %d skipped (CORS), %d skipped (no subject), %d errored",
    ok.length, cors, noSubject, errored,
  );

  if (ok.length > 0) {
    store.commit(applyCrops(store.state, ok));
  }

  // Build a user-readable summary that surfaces partial failures so
  // they're not invisible in the UI. Partials linger ~5s; clean
  // success fades after 1.5s so the pill doesn't overstay.
  const skipped = cors + noSubject + errored;
  let summary: string;
  let lingerMs: number;
  if (ok.length === total) {
    summary = `Auto-crop done (${ok.length}/${total})`;
    lingerMs = 1500;
  } else if (ok.length === 0 && cors === total) {
    summary = `Auto-crop blocked: ${cors} image${cors === 1 ? "" : "s"} not CORS-readable`;
    lingerMs = 6000;
  } else if (ok.length === 0) {
    summary = `Auto-crop: no usable subject in ${total} image${total === 1 ? "" : "s"}`;
    lingerMs = 5000;
  } else {
    const parts: string[] = [`${ok.length}/${total} cropped`];
    if (cors > 0) parts.push(`${cors} CORS-blocked`);
    if (noSubject > 0) parts.push(`${noSubject} no subject`);
    if (errored > 0) parts.push(`${errored} errored`);
    summary = `Auto-crop: ${parts.join(", ")}`;
    lingerMs = 5000;
  }
  setPill(summary);
  hidePill(lingerMs);

  // Surface a one-liner per skipped item so the publisher can find
  // which one to crop manually. CORS blocks the most common — log
  // those at warn so they catch the eye in DevTools.
  for (const o of outcomes) {
    if (o.status === "cors") {
      console.warn("[auto-crop] image load blocked (CORS / 404 / network):", o.ref.src);
    } else if (o.status === "no-subject") {
      console.info("[auto-crop] no salient subject for image:", o.ref.src);
    } else if (o.status === "error") {
      console.warn("[auto-crop] inference error for image:", o.ref.src);
    }
  }

  void skipped; // referenced via parts construction above; quiet unused warning
}
