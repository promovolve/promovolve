// Page Background panel. Always visible in the Properties tab of the
// sidebar — surfaces the current page's full-bleed video background
// with upload, fit, opacity, playback toggles, trim, and remove.
//
// The videoBg lives on Page (not on layout items), so auto-layout
// regeneration ignores it and it renders in every size fanout.
//
// Update pattern mirrors props-panel.ts: text/number `input` events
// go through store.replace for live preview; `change` events (blur,
// slider release, select close) commit a single undo step.

import { isSized } from "../modes";
import { currentPage, setPageBg, setSyncPageBg, setTextureBg, setTextureSrc, setVideoBg } from "../state";
import type { Store } from "../store";
import type { DesignerState, TextureBg, VideoBg } from "../types";
import { openAssetModal } from "./asset-modal";
import { tokens } from "./tokens";
import { visualRect } from "./visual-rect";

// Delivered video loops are cut to this many seconds at publish
// (VideoTranscoder.LoopSeconds — keep in sync).
const MAX_LOOP_SEC = 15;

export interface PageBgPanelHandle {
  update(state: DesignerState): void;
}

export function mountPageBgPanel(container: HTMLElement, store: Store): PageBgPanelHandle {
  const panel = document.createElement("div");
  panel.className = "cd-page-bg";
  panel.style.cssText = "padding: 14px;";
  container.appendChild(panel);

  // (Section title "Page Background" is provided by the sidebar's
  // collapsible section header — see makeSection in ui/sidebar.ts.)

  // Color picker — applies to the page's solid bg fallback. Independent
  // of any video bg above (the video sits on top when present).
  const colorRow = mountColorRow(store);
  panel.appendChild(colorRow.el);

  // Subhead between sections so the two surfaces read as related but
  // distinct ("Color" vs "Video").
  const videoHeader = document.createElement("div");
  videoHeader.style.cssText = `font-size:11px;letter-spacing:2px;color:${tokens.ink300};margin:18px 0 10px;text-transform:uppercase;`;
  videoHeader.textContent = "Video";
  panel.appendChild(videoHeader);

  // Body — structure differs based on whether a video is set.
  const body = document.createElement("div");
  panel.appendChild(body);

  // Subhead + body for the texture surface. A texture is a full-bleed
  // image that fills the page behind the layout — tiled or covering.
  const textureHeader = document.createElement("div");
  textureHeader.style.cssText = `font-size:11px;letter-spacing:2px;color:${tokens.ink300};margin:18px 0 10px;text-transform:uppercase;`;
  textureHeader.textContent = "Texture";
  panel.appendChild(textureHeader);

  const textureBody = document.createElement("div");
  panel.appendChild(textureBody);

  let renderedSrc: string | null | undefined = undefined;
  // Texture rebuilds on src OR mode change (mode toggles the tile-size
  // row in/out); opacity ticks live via patchTextureValues.
  let renderedTextureKey: string | null | undefined = undefined;

  return {
    update(state) {
      const page = currentPage(state);
      colorRow.update(page?.bg ?? "", state.pageIdx === 0, state.pages[0]?.syncBg !== false, isSized(state.mode));
      const videoBg = page?.videoBg;
      const src = videoBg?.src ?? null;
      // Only rebuild when present/absent state flips — otherwise
      // patching existing inputs keeps focus and avoids jitter.
      if (renderedSrc !== src) {
        body.innerHTML = "";
        if (videoBg) {
          renderWithVideo(body, videoBg, store);
        } else {
          renderEmpty(body, store);
        }
        renderedSrc = src;
      } else if (videoBg) {
        patchValues(body, videoBg);
      }

      const textureBg = page?.textureBg;
      const texKey = textureBg ? `${textureBg.src}|${textureBg.mode ?? "tile"}` : null;
      if (renderedTextureKey !== texKey) {
        textureBody.innerHTML = "";
        if (textureBg) {
          renderWithTexture(textureBody, textureBg, store);
        } else {
          renderEmptyTexture(textureBody, store);
        }
        renderedTextureKey = texKey;
      } else if (textureBg) {
        patchTextureValues(textureBody, textureBg);
      }
    },
  };
}

// ─── Background color row ──────────────────────────────────────────

interface ColorRowHandle {
  el: HTMLElement;
  update(currentBg: string, firstPage: boolean, synced: boolean, sized: boolean): void;
}

function mountColorRow(store: Store): ColorRowHandle {
  const wrap = document.createElement("div");
  const row = document.createElement("div");
  row.style.cssText = "display:flex;align-items:center;gap:8px;";
  wrap.appendChild(row);

  const label = document.createElement("label");
  label.style.cssText = `flex:0 0 80px;color:${tokens.ink300};font-size:11px;`;
  label.textContent = "Color";

  const swatch = document.createElement("input");
  swatch.type = "color";
  swatch.style.cssText = "width:32px;height:24px;padding:0;border:none;background:transparent;cursor:pointer;";

  const text = document.createElement("input");
  text.type = "text";
  text.placeholder = "#1a1a1a or transparent";
  text.style.cssText = [
    "flex:1",
    "min-width:0",
    `background:${tokens.ink900}`,
    `color:${tokens.ink100}`,
    `border:1px solid ${tokens.ink700}`,
    "border-radius:3px",
    "padding:4px 6px",
    `font-family:${tokens.sans}`,
    "font-size:11px",
  ].join(";");

  // ── Opacity: the native color input has no alpha channel, but the
  // engine renders any CSS color — a translucent page background lets
  // the HOST ARTICLE show through the collapsed ad (glassy/native
  // look). The slider composes an 8-digit hex (#RRGGBBAA) from the
  // swatch RGB + its own alpha; 100% writes plain 6-digit hex so
  // opaque creatives keep their familiar values.
  const alphaRow = document.createElement("div");
  alphaRow.style.cssText = "display:flex;align-items:center;gap:8px;margin-top:6px;";
  const alphaLabel = document.createElement("label");
  alphaLabel.style.cssText = `flex:0 0 80px;color:${tokens.ink300};font-size:11px;`;
  alphaLabel.textContent = "Opacity";
  const alpha = document.createElement("input");
  alpha.type = "range";
  alpha.min = "0";
  alpha.max = "100";
  alpha.value = "100";
  alpha.style.cssText = "flex:1;min-width:0;cursor:pointer;";
  const alphaVal = document.createElement("span");
  alphaVal.style.cssText = `flex:0 0 34px;text-align:right;color:${tokens.ink300};font-size:11px;`;
  alphaVal.textContent = "100%";
  alphaRow.append(alphaLabel, alpha, alphaVal);

  const hexRgb = (v: string): string | null => {
    const m = /^#([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?$/.exec(v.trim());
    return m ? "#" + m[1] : null;
  };
  const hexAlpha = (v: string): number => {
    const m = /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})$/.exec(v.trim());
    return m ? Math.round((parseInt(m[1], 16) / 255) * 100) : 100;
  };
  // rgb: the base colour to compose with the slider's alpha. The swatch
  // handlers pass their OWN fresh value — deriving it from the text
  // field here made a swatch pick recompose from the stale old colour
  // (a base-colour change silently no-oped until Reset).
  const composeBg = (rgb: string): string => {
    const a = Number(alpha.value);
    if (a >= 100) return rgb;
    const aa = Math.round((a / 100) * 255).toString(16).padStart(2, "0");
    return rgb + aa;
  };
  const currentRgb = (): string => hexRgb(text.value) ?? swatch.value;

  const clear = document.createElement("button");
  clear.type = "button";
  clear.textContent = "Reset";
  clear.style.cssText = [
    "background:transparent",
    `color:${tokens.ink300}`,
    `border:1px solid ${tokens.ink700}`,
    "border-radius:3px",
    "padding:4px 8px",
    "font-size:11px",
    "cursor:pointer",
  ].join(";");

  // Order: hex field first, swatch after it — the swatch doubles as a
  // preview of what the field says, so it reads value → color.
  row.appendChild(label);
  row.appendChild(text);
  row.appendChild(swatch);
  row.appendChild(clear);
  wrap.appendChild(alphaRow);

  // Keep the swatch and text input in sync. Picker fires "input" on
  // every drag tick — replace state for live preview, commit on close.
  // Text input commits on blur via "change".
  swatch.addEventListener("input", () => {
    const v = composeBg(swatch.value);
    text.value = v;
    store.replace(setPageBg(store.state, v));
  });
  swatch.addEventListener("change", () => {
    store.commit(setPageBg(store.state, composeBg(swatch.value)));
  });
  alpha.addEventListener("input", () => {
    alphaVal.textContent = alpha.value + "%";
    const v = composeBg(currentRgb());
    text.value = v;
    store.replace(setPageBg(store.state, v));
  });
  alpha.addEventListener("change", () => {
    store.commit(setPageBg(store.state, composeBg(currentRgb())));
  });
  text.addEventListener("change", () => {
    const v = text.value.trim();
    store.commit(setPageBg(store.state, v === "" ? null : v));
  });
  clear.addEventListener("click", () => {
    text.value = "";
    store.commit(setPageBg(store.state, null));
  });

  // "Sync color across all 3 pages" — governs whether the page-1 colour
  // is copied onto pages 2/3 (see state.ts setSyncPageBg / setPageBg).
  // ALWAYS rendered so the accordion height never jumps when paging;
  // operable only while page 1 is selected, greyed on pages 2/3.
  const syncRow = document.createElement("label");
  syncRow.style.cssText = `display:flex;align-items:center;gap:8px;margin-top:8px;font-size:11px;color:${tokens.ink200};`;
  const syncCb = document.createElement("input");
  syncCb.type = "checkbox";
  syncCb.style.cssText = "margin:0;cursor:inherit;";
  syncCb.addEventListener("change", () => {
    store.commit(setSyncPageBg(store.state, syncCb.checked));
  });
  // Label is CONTEXT-SENSITIVE (see update below): in the full-screen
  // tabs the magazine's pages are visible, so "all 3 pages" reads
  // naturally; on a sized tab (300×250, 728×90, …) that structure is
  // invisible and "which 3 pages?" is the first question — there the
  // label says what the tick means from that vantage point instead.
  const syncLabel = document.createTextNode("Sync color across all 3 pages");
  syncRow.append(syncCb, syncLabel);
  wrap.appendChild(syncRow);

  const setRowEnabled = (input: HTMLInputElement | HTMLButtonElement, on: boolean): void => {
    input.disabled = !on;
    input.style.opacity = on ? "" : "0.45";
    input.style.cursor = on ? "" : "not-allowed";
  };

  return {
    el: wrap,
    update(currentBg, firstPage, synced, sized) {
      syncLabel.data = sized
        ? "Use one color for the whole creative"
        : "Sync color across all 3 pages";
      // Only repopulate when the value differs from the input — avoids
      // clobbering user input mid-edit and keeps focus/caret intact.
      if (text.value !== currentBg) {
        text.value = currentBg;
      }
      // Color picker only accepts 6-digit hex; for 8-digit values show
      // the RGB part and reflect the alpha on the slider. Non-hex values
      // ("transparent", gradients) leave both untouched so we don't lie.
      const rgbPart = hexRgb(currentBg);
      if (rgbPart && swatch.value.toLowerCase() !== rgbPart.toLowerCase()) {
        swatch.value = rgbPart;
      }
      if (rgbPart) {
        const a = hexAlpha(currentBg);
        if (Number(alpha.value) !== a) {
          alpha.value = String(a);
          alphaVal.textContent = a + "%";
        }
      }
      // Toggle operable only on page 1 (state readable everywhere).
      syncCb.checked = synced;
      setRowEnabled(syncCb, firstPage);
      syncRow.style.opacity = firstPage ? "" : "0.45";
      syncRow.style.cursor = firstPage ? "pointer" : "not-allowed";
      syncRow.title = firstPage ? "" : "Set from page 1";
      // While synced, pages 2/3 show the colour but can't edit it —
      // page 1 is the single edit surface.
      const editable = firstPage || !synced;
      setRowEnabled(swatch, editable);
      setRowEnabled(text, editable);
      setRowEnabled(clear, editable);
      row.title = editable ? "" : "Color synced from page 1";
    },
  };
}

// ─── Empty state ───────────────────────────────────────────────────

function renderEmpty(body: HTMLElement, store: Store): void {
  const hint = document.createElement("p");
  hint.style.cssText = `color:${tokens.ink400};font-size:11px;margin:0 0 10px;line-height:1.5;`;
  hint.textContent = "Full-bleed video plays behind every size. MP4 or WebM.";
  body.appendChild(hint);

  const btn = uploadButton(store, "Upload video…");
  body.appendChild(btn);
}

// ─── With-video state ──────────────────────────────────────────────

function renderWithVideo(body: HTMLElement, videoBg: VideoBg, store: Store): void {
  // Inline preview — plays the current clip so the author can see
  // exactly what's going behind the layout. Muted + playsinline so it
  // autoplays in the panel without user interaction.
  const preview = document.createElement("video");
  preview.src = videoBg.src;
  preview.muted = true;
  preview.autoplay = true;
  preview.loop = true;
  preview.playsInline = true;
  preview.controls = true;
  preview.setAttribute("playsinline", "");
  preview.setAttribute("muted", "");
  preview.style.cssText = [
    "width: 100%",
    "aspect-ratio: 16 / 9",
    "object-fit: cover",
    `background: ${tokens.ink900}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "margin-bottom: 10px",
    "display: block",
  ].join(";");
  // Swallow autoplay-policy rejections — the poster/first-frame still
  // shows and the controls let the author press play.
  preview.play().catch(() => { /* autoplay may be denied in the panel */ });
  body.appendChild(preview);

  // Current-file readout + replace button.
  const head = document.createElement("div");
  head.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:12px;";
  const filename = document.createElement("span");
  filename.style.cssText = `flex:1;min-width:0;font-size:11px;color:${tokens.ink200};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;`;
  filename.textContent = fileNameFromUrl(videoBg.src);
  filename.title = videoBg.src;
  head.appendChild(filename);
  const replace = uploadButton(store, "Replace");
  (replace.style as CSSStyleDeclaration).cssText += ";flex:0 0 auto;";
  head.appendChild(replace);
  body.appendChild(head);

  // Fit — cover | contain.
  row(body, "Fit", segmented(["cover", "contain"], videoBg.fit ?? "cover",
    (v) => commit(store, (bg) => ({ ...bg, fit: v as "cover" | "contain" })),
  ), "data-fit");

  // Opacity — 0..100% slider.
  row(body, "Opacity", opacitySlider(videoBg.opacity ?? 1,
    (v, commitNow) => mutate(store, (bg) => ({ ...bg, opacity: v }), commitNow),
  ), "data-opacity");

  // Loop / Muted / Autoplay toggles.
  // No Loop / Muted / Autoplay toggles: living paper is ALWAYS a silent
  // autoplaying loop — the publish transcode strips the audio track at
  // the file level and the loop is the format. Choices that don't exist
  // don't get controls.

  // Trim in/out — optional seconds. Empty = clear.
  const trim = document.createElement("div");
  trim.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:6px;";
  const trimLabel = document.createElement("span");
  trimLabel.textContent = "Trim";
  trimLabel.style.cssText = `flex:0 0 80px;color:${tokens.ink300};font-size:11px;`;
  trim.appendChild(trimLabel);
  // The delivered loop is capped at MAX_LOOP_SEC (the publish transcode
  // cuts the window — VideoTranscoder.LoopSeconds, keep in sync). Clamp
  // Out to In+cap here so the draft preview never loops footage the
  // published ad won't contain.
  trim.appendChild(trimInput("in", videoBg.inSec,
    (v) => commit(store, (bg) => {
      const next = { ...bg, inSec: v };
      const start = v ?? 0;
      if (next.outSec != null) {
        // Keep the window valid AND within the cap: an Out at or before
        // the new In would be an inverted window (glitchy preview,
        // ignored by the transcode) — drop it and let the cap apply.
        if (next.outSec <= start) next.outSec = undefined;
        else if (next.outSec > start + MAX_LOOP_SEC) next.outSec = start + MAX_LOOP_SEC;
      }
      return next;
    }),
  ));
  trim.appendChild(trimInput("out", videoBg.outSec,
    (v) => commit(store, (bg) => {
      const start = bg.inSec ?? 0;
      const clamped = v == null ? undefined : Math.min(Math.max(v, start), start + MAX_LOOP_SEC);
      return { ...bg, outSec: clamped };
    }),
  ));
  trim.dataset.trim = "1";
  body.appendChild(trim);

  // Remove — destructive, bottom.
  const remove = document.createElement("button");
  remove.type = "button";
  remove.textContent = "Remove";
  remove.style.cssText = [
    "margin-top: 14px",
    "width: 100%",
    "padding: 6px 10px",
    "background: transparent",
    `color: ${tokens.err}`,
    `border: 1px solid ${tokens.err}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  remove.addEventListener("click", () => {
    store.commit(setVideoBg(store.state, null));
  });
  body.appendChild(remove);
}

// ─── Patch-in-place (no rebuild) ───────────────────────────────────

// When the videoBg identity stays the same but values tick during a
// live drag (opacity), sync inputs that weren't being edited without
// destroying focus on the one that is.
function patchValues(body: HTMLElement, videoBg: VideoBg): void {
  const opacity = body.querySelector<HTMLInputElement>('[data-opacity] input[type="range"]');
  if (opacity && document.activeElement !== opacity) {
    opacity.value = String(Math.round((videoBg.opacity ?? 1) * 100));
  }
  const opacityReadout = body.querySelector<HTMLElement>('[data-opacity] .cd-readout');
  if (opacityReadout) opacityReadout.textContent = `${Math.round((videoBg.opacity ?? 1) * 100)}%`;
}

// ─── Texture: empty state ──────────────────────────────────────────

function renderEmptyTexture(body: HTMLElement, store: Store): void {
  // Primary path: drop an image on the canvas (mountImageDrop →
  // "Background texture"). Secondary: pick an existing library image —
  // which is also where landing-page images imported during compose
  // live, and the library modal has its own upload.
  const hint = document.createElement("p");
  hint.style.cssText = `color:${tokens.ink400};font-size:11px;margin:0 0 10px;line-height:1.5;`;
  hint.innerHTML = "Full-bleed image behind every size — tiled pattern or single cover image.<br><br>"
    + `<span style="color:${tokens.ink300}">Drag an image onto the canvas and choose <b>Background texture</b>, or:</span>`;
  body.appendChild(hint);

  body.appendChild(textureLibraryButton(store, "Choose from library…"));
}

/** Open the Images library (uploads + landing-page images, with its own
  * upload) and route the chosen image to the texture bg instead of the
  * main image. The full-screen modal covers the canvas, so this is the
  * click path that complements canvas drag-drop. */
function textureLibraryButton(store: Store, label: string): HTMLButtonElement {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = label;
  btn.style.cssText = [
    "padding: 6px 10px",
    `background: ${tokens.ink700}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  btn.addEventListener("click", () => {
    openAssetModal(store, (asset) => store.commit(setTextureSrc(store.state, asset.cdnUrl)));
  });
  return btn;
}

// ─── Texture: with-texture state ───────────────────────────────────

const FILLS = "fills (no crop)";
// How a cover image of `imageAspect` is cropped by a slot of `slotAspect`,
// which tells the author which focal axis actually moves it there:
//   image wider than the slot  → overflows horizontally → left/right pans
//   image taller than the slot → overflows vertically   → up/down pans
//   ~equal aspect              → fills exactly, no slack → focal no-op
// The ±2% band keeps a near-match from reading as a meaningful pan.
function focalCropLabel(imageAspect: number, slotAspect: number): string {
  const r = imageAspect / slotAspect;
  if (r > 1.02) return "left/right";
  if (r < 0.98) return "up/down";
  return FILLS;
}

function renderWithTexture(body: HTMLElement, textureBg: TextureBg, store: Store): void {
  const mode: "tile" | "cover" = textureBg.mode ?? "tile";

  // Preview — a swatch that shows the texture exactly as it renders. In
  // cover mode it doubles as a focal-point picker: drag to choose which
  // part of the image stays visible under the crop.
  body.appendChild(texturePreview(textureBg, store));
  if (mode === "cover") {
    const tip = document.createElement("p");
    tip.style.cssText = `color:${tokens.ink400};font-size:10px;margin:-4px 0 10px;`;
    tip.textContent = "Drag on the preview to set the focal point.";
    body.appendChild(tip);
    // A cover focal point only pans on the axis the crop OVERFLOWS, and PC
    // (16:9) and Mobile (9:16) have opposite aspects — so the same image
    // usually pans on one device and "fills" (no slack → focal does
    // nothing) on the other. Once the image's real aspect is known, spell
    // out which device each axis affects, so a "fills" device doesn't look
    // broken when dragging the marker has no visible effect there.
    if (textureBg.src) {
      const probe = new Image();
      probe.onload = () => {
        if (!probe.naturalWidth || !probe.naturalHeight) return;
        const a = probe.naturalWidth / probe.naturalHeight;
        const pc = focalCropLabel(a, 16 / 9);
        const mb = focalCropLabel(a, 9 / 16);
        if (pc === FILLS && mb === FILLS) {
          // Image matches both slots' aspects (rare — they differ): no axis
          // to pan anywhere, so the focal is a genuine no-op. Dim the marker.
          tip.textContent = "This image fills every slot — the focal point has no effect here.";
          const marker = body.querySelector<HTMLElement>("[data-tex-focus]");
          if (marker) marker.style.opacity = "0.35";
        } else {
          tip.textContent = `Drag to set the focal point · PC: ${pc} · Mobile: ${mb}`;
        }
      };
      probe.src = textureBg.src;
    }
  }

  // Current-file readout + library re-pick. Replace by dropping a new
  // image on the canvas, or picking another from the library.
  const head = document.createElement("div");
  head.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:12px;";
  const filename = document.createElement("span");
  filename.style.cssText = `flex:1;min-width:0;font-size:11px;color:${tokens.ink200};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;`;
  filename.textContent = fileNameFromUrl(textureBg.src);
  filename.title = textureBg.src;
  head.appendChild(filename);
  const library = textureLibraryButton(store, "Library");
  (library.style as CSSStyleDeclaration).cssText += ";flex:0 0 auto;";
  head.appendChild(library);
  body.appendChild(head);

  // Mode — tile | cover.
  row(body, "Mode", segmented(["tile", "cover"], mode,
    (v) => commitTex(store, (bg) => ({ ...bg, mode: v as "tile" | "cover" })),
  ), "data-tex-mode");

  // Tile size — px, tile mode only. Empty = natural pixel size.
  if (mode === "tile") {
    row(body, "Tile px", scaleInput(textureBg.scale,
      (v) => commitTex(store, (bg) => ({ ...bg, scale: v })),
    ), "data-tex-scale");
  }

  // Opacity — 0..100% slider.
  row(body, "Opacity", opacitySlider(textureBg.opacity ?? 1,
    (v, commitNow) => mutateTex(store, (bg) => ({ ...bg, opacity: v }), commitNow),
  ), "data-tex-opacity");

  // Blend — how the texture mixes over the page color / video.
  row(body, "Blend", select(
    ["normal", "multiply", "overlay", "screen", "soft-light"],
    textureBg.blend ?? "normal",
    (v) => commitTex(store, (bg) => ({ ...bg, blend: v as TextureBg["blend"] })),
  ), "data-tex-blend");

  // Remove — destructive, bottom.
  const remove = document.createElement("button");
  remove.type = "button";
  remove.textContent = "Remove";
  remove.style.cssText = [
    "margin-top: 14px",
    "width: 100%",
    "padding: 6px 10px",
    "background: transparent",
    `color: ${tokens.err}`,
    `border: 1px solid ${tokens.err}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  remove.addEventListener("click", () => {
    store.commit(setTextureBg(store.state, null));
  });
  body.appendChild(remove);
}

// Live opacity + focal-point ticks without rebuilding (keeps focus/drag).
function patchTextureValues(body: HTMLElement, textureBg: TextureBg): void {
  const opacity = body.querySelector<HTMLInputElement>('[data-tex-opacity] input[type="range"]');
  if (opacity && document.activeElement !== opacity) {
    opacity.value = String(Math.round((textureBg.opacity ?? 1) * 100));
  }
  const readout = body.querySelector<HTMLElement>('[data-tex-opacity] .cd-readout');
  if (readout) readout.textContent = `${Math.round((textureBg.opacity ?? 1) * 100)}%`;

  // Cover focal point — reposition the swatch background + marker as the
  // author drags (mutateTex replaces state, which re-runs this patch).
  const preview = body.querySelector<HTMLElement>("[data-tex-preview]");
  if (preview && (textureBg.mode ?? "tile") === "cover") {
    const fx = textureBg.focusX ?? 50;
    const fy = textureBg.focusY ?? 50;
    preview.style.backgroundPosition = `${fx}% ${fy}%`;
    const marker = preview.querySelector<HTMLElement>("[data-tex-focus]");
    if (marker) { marker.style.left = `${fx}%`; marker.style.top = `${fy}%`; }
  }
}

// Texture preview swatch. Cover mode is an interactive focal-point
// picker (drag a marker → focusX/focusY %); tile mode is a static
// repeating swatch at the chosen tile size.
function texturePreview(textureBg: TextureBg, store: Store): HTMLElement {
  const mode: "tile" | "cover" = textureBg.mode ?? "tile";
  const preview = document.createElement("div");
  preview.dataset.texPreview = "1";
  const base = [
    "position: relative",
    "width: 100%",
    "height: 96px",
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "margin-bottom: 10px",
    "overflow: hidden",
  ];

  if (mode === "cover") {
    const fx = textureBg.focusX ?? 50;
    const fy = textureBg.focusY ?? 50;
    preview.style.cssText = [
      ...base,
      `background: ${tokens.ink900} no-repeat url("${textureBg.src}")`,
      "background-size: cover",
      `background-position: ${fx}% ${fy}%`,
      "cursor: crosshair",
      "touch-action: none",
      // Safari: without these the drag starts a text selection / native
      // image drag and WebKit steals the gesture (pointer capture alone
      // doesn't stop it there, unlike Chrome).
      "user-select: none",
      "-webkit-user-select: none",
      "-webkit-touch-callout: none",
    ].join(";");

    const marker = document.createElement("div");
    marker.dataset.texFocus = "1";
    marker.style.cssText = [
      "position: absolute",
      `left: ${fx}%`,
      `top: ${fy}%`,
      "width: 18px",
      "height: 18px",
      "margin: -9px 0 0 -9px",
      "border-radius: 50%",
      "border: 2px solid #fff",
      "box-shadow: 0 0 0 1px rgba(0,0,0,0.6), 0 1px 4px rgba(0,0,0,0.6)",
      "pointer-events: none",
    ].join(";");
    preview.appendChild(marker);

    const setFromEvent = (e: PointerEvent, commitNow: boolean): void => {
      // visualRect, not getBoundingClientRect: the sidebar is zoomed
      // (UI_SCALE) and WebKit's rects don't include the zoom while its
      // pointer coordinates do — the marker drifted from the cursor in
      // Safari, growing with distance from the viewport origin.
      const r = visualRect(preview);
      const x = Math.round(Math.max(0, Math.min(100, ((e.clientX - r.left) / r.width) * 100)));
      const y = Math.round(Math.max(0, Math.min(100, ((e.clientY - r.top) / r.height) * 100)));
      mutateTex(store, (bg) => ({ ...bg, focusX: x, focusY: y }), commitNow);
    };
    // Window-scoped drag: older Safari's mouse setPointerCapture is broken
    // (moves stop being retargeted to the element), so element-scoped
    // pointermove dies the moment the cursor leaves this 96px swatch.
    // Listening on window needs no capture at all; capture is still taken
    // opportunistically so browsers that honor it suppress hover effects
    // elsewhere during the drag.
    let dragging = false;
    const onMove = (e: PointerEvent): void => {
      if (dragging) setFromEvent(e, false);
    };
    const endDrag = (e: PointerEvent): void => {
      if (!dragging) return;
      dragging = false;
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", endDrag);
      window.removeEventListener("pointercancel", endDrag);
      try { preview.releasePointerCapture(e.pointerId); } catch { /* capture may already be released */ }
      setFromEvent(e, true);  // single undo step for the whole drag
    };
    preview.addEventListener("pointerdown", (e) => {
      dragging = true;
      // Stop Safari from starting a text selection / native drag instead.
      e.preventDefault();
      try { preview.setPointerCapture(e.pointerId); } catch { /* optional */ }
      window.addEventListener("pointermove", onMove);
      window.addEventListener("pointerup", endDrag);
      window.addEventListener("pointercancel", endDrag);
      setFromEvent(e, false);
    });
  } else {
    preview.style.cssText = [
      ...base,
      `background: ${tokens.ink900} repeat url("${textureBg.src}")`,
      `background-size: ${typeof textureBg.scale === "number" && textureBg.scale > 0 ? `${textureBg.scale}px` : "auto"}`,
    ].join(";");
  }
  return preview;
}

// Tile-size px input. Empty = natural size (undefined).
function scaleInput(value: number | undefined, onChange: (v: number | undefined) => void): HTMLInputElement {
  const input = document.createElement("input");
  input.type = "number";
  input.min = "1";
  input.step = "1";
  input.placeholder = "natural";
  input.value = value == null ? "" : String(value);
  input.style.cssText = [
    "flex: 1",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  input.addEventListener("change", () => {
    const raw = input.value.trim();
    if (raw === "") onChange(undefined);
    else {
      const n = Number(raw);
      onChange(Number.isFinite(n) && n > 0 ? n : undefined);
    }
  });
  return input;
}

function select(options: string[], selected: string, onChange: (v: string) => void): HTMLSelectElement {
  const sel = document.createElement("select");
  sel.style.cssText = [
    "flex: 1",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
    "cursor: pointer",
  ].join(";");
  for (const opt of options) {
    const o = document.createElement("option");
    o.value = opt;
    o.textContent = opt;
    if (opt === selected) o.selected = true;
    sel.appendChild(o);
  }
  sel.addEventListener("change", () => onChange(sel.value));
  return sel;
}

function commitTex(store: Store, fn: (bg: TextureBg) => TextureBg): void {
  const prev = currentPage(store.state)?.textureBg;
  if (!prev) return;
  store.commit(setTextureBg(store.state, fn(prev)));
}

function mutateTex(store: Store, fn: (bg: TextureBg) => TextureBg, commitNow: boolean): void {
  const prev = currentPage(store.state)?.textureBg;
  if (!prev) return;
  const next = setTextureBg(store.state, fn(prev));
  if (commitNow) store.commit(next);
  else store.replace(next);
}

// ─── Controls ──────────────────────────────────────────────────────

function uploadButton(store: Store, label: string): HTMLButtonElement {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = label;
  btn.style.cssText = [
    "padding: 6px 10px",
    `background: ${tokens.ink700}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
  ].join(";");

  const input = document.createElement("input");
  input.type = "file";
  // Include both MIME types and extensions — some OS/browser combos
  // grey out MP4 when only MIME is specified. `video/*` as a last
  // resort covers codecs we didn't enumerate.
  input.accept = ".mp4,.webm,video/mp4,video/webm,video/*";
  input.style.display = "none";
  input.addEventListener("change", async () => {
    const file = input.files?.[0];
    if (!file) return;
    btn.disabled = true;
    const orig = btn.textContent;
    btn.textContent = "Uploading…";
    try {
      const url = await uploadVideo(file);
      if (url) {
        // Preserve existing flags when replacing — authors don't want
        // their loop/muted/opacity reset by picking a new source.
        const prev = currentPage(store.state)?.videoBg;
        store.commit(setVideoBg(store.state, {
          src: url,
          fit: prev?.fit ?? "cover",
          loop: prev?.loop ?? true,
          muted: prev?.muted ?? true,
          autoplay: prev?.autoplay ?? true,
          opacity: prev?.opacity ?? 1,
          inSec: prev?.inSec,
          outSec: prev?.outSec,
          poster: prev?.poster,
          sizeBytes: file.size,
        }));
      }
    } catch (e) {
      console.error("[page-bg] upload failed:", e);
    } finally {
      btn.disabled = false;
      btn.textContent = orig;
      input.value = "";
    }
  });

  btn.addEventListener("click", () => input.click());
  btn.appendChild(input);
  return btn;
}

interface AssetUploadResponse {
  asset?: { cdnUrl?: string };
}

async function uploadVideo(file: File): Promise<string | null> {
  const fd = new FormData();
  fd.append("file", file, file.name);
  const resp = await fetch("/advertiser/assets", { method: "POST", body: fd });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const data = (await resp.json()) as AssetUploadResponse;
  return data.asset?.cdnUrl ?? null;
}

function segmented(options: string[], selected: string, onChange: (v: string) => void): HTMLElement {
  const wrap = document.createElement("div");
  wrap.style.cssText = [
    "display:flex",
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "overflow: hidden",
    "flex: 1",
  ].join(";");
  for (const opt of options) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = opt;
    const isSel = opt === selected;
    btn.style.cssText = [
      "flex: 1",
      "padding: 4px 6px",
      `background: ${isSel ? tokens.amberBg : "transparent"}`,
      `color: ${isSel ? tokens.ink100 : tokens.ink300}`,
      "border: none",
      "font: inherit",
      "font-size: 11px",
      "cursor: pointer",
    ].join(";");
    btn.addEventListener("click", () => onChange(opt));
    wrap.appendChild(btn);
  }
  return wrap;
}

function opacitySlider(value: number, onChange: (v: number, commit: boolean) => void): HTMLElement {
  const wrap = document.createElement("div");
  wrap.style.cssText = "display:flex;align-items:center;gap:8px;flex:1;";
  const input = document.createElement("input");
  input.type = "range";
  input.min = "0";
  input.max = "100";
  input.step = "1";
  input.value = String(Math.round(value * 100));
  input.style.cssText = `flex:1;accent-color:${tokens.amber};cursor:pointer;`;
  const readout = document.createElement("span");
  readout.className = "cd-readout";
  readout.style.cssText = `flex:0 0 36px;text-align:right;color:${tokens.ink300};font-family:${tokens.sans};font-size:11px;`;
  readout.textContent = `${Math.round(value * 100)}%`;
  input.addEventListener("input", () => {
    const v = Number(input.value) / 100;
    readout.textContent = `${input.value}%`;
    onChange(v, false);
  });
  input.addEventListener("change", () => {
    const v = Number(input.value) / 100;
    onChange(v, true);
  });
  wrap.append(input, readout);
  return wrap;
}


function trimInput(placeholder: string, value: number | undefined, onChange: (v: number | undefined) => void): HTMLInputElement {
  const input = document.createElement("input");
  input.type = "number";
  input.min = "0";
  input.step = "0.1";
  input.placeholder = placeholder;
  input.value = value == null ? "" : String(value);
  input.style.cssText = [
    "flex: 1",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  input.addEventListener("change", () => {
    const raw = input.value.trim();
    if (raw === "") onChange(undefined);
    else {
      const n = Number(raw);
      onChange(Number.isFinite(n) ? n : undefined);
    }
  });
  return input;
}

function row(body: HTMLElement, label: string, control: HTMLElement, marker?: string): void {
  const r = document.createElement("label");
  r.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:6px;";
  if (marker) r.setAttribute(marker, "1");
  const lbl = document.createElement("span");
  lbl.textContent = label;
  lbl.style.cssText = `flex:0 0 80px;color:${tokens.ink300};font-size:11px;`;
  r.append(lbl, control);
  body.appendChild(r);
}

// ─── Commit helpers ────────────────────────────────────────────────

function commit(store: Store, fn: (bg: VideoBg) => VideoBg): void {
  const page = currentPage(store.state);
  const prev = page?.videoBg;
  if (!prev) return;
  store.commit(setVideoBg(store.state, fn(prev)));
}

function mutate(store: Store, fn: (bg: VideoBg) => VideoBg, commitNow: boolean): void {
  const page = currentPage(store.state);
  const prev = page?.videoBg;
  if (!prev) return;
  const next = setVideoBg(store.state, fn(prev));
  if (commitNow) store.commit(next);
  else store.replace(next);
}

// ─── Utils ─────────────────────────────────────────────────────────

function fileNameFromUrl(url: string): string {
  try {
    const u = new URL(url, window.location.origin);
    const parts = u.pathname.split("/").filter(Boolean);
    return parts[parts.length - 1] || url;
  } catch {
    return url;
  }
}
