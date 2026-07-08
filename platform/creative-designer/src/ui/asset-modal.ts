// Asset-library modal. Fetches the advertiser's uploaded images from
// /advertiser/assets and lets the user pick one (→ becomes an image
// layout item in the current mode's layout) or upload new ones.
//
// Replaces the toolbar's window.prompt fallback from Phase 6a.
// Endpoints are identical to the inline editor's — GET/POST
// /advertiser/assets and DELETE /advertiser/assets/{id}.

import { imageDimensions, prepareForUpload, sha256Hex, uploadImageDirect } from "../api/upload-asset";
import { setMainImage } from "../state";
import type { Store } from "../store";
import { tokens } from "./tokens";

interface Asset {
  id: string;
  cdnUrl: string;
  filename?: string;
  width?: number;
  height?: number;
}

interface AssetsResponse {
  assets?: Asset[];
}

// Open the image library. Picking an asset sets the creative's single
// MAIN image (page.img) via setMainImage — so the image shows in the
// expanded view AND every size (slots are auto-added to sizes that lack
// one). There's one image per creative; this is how you set/replace it.
export function openAssetModal(store: Store | null, onPick?: (asset: Asset) => void): void {
  const existing = document.getElementById("cd-asset-modal");
  if (existing) existing.remove();

  const modal = buildModal();
  document.body.appendChild(modal.root);
  modal.load();

  // Default: set the creative's MAIN image. A caller can pass `onPick`
  // to route the chosen asset elsewhere (e.g. the brand-kit logo).
  modal.onPick = (asset: Asset) => {
    if (onPick) onPick(asset);
    else if (store) store.commit(setMainImage(store.state, asset.cdnUrl));
    modal.close();
  };
}

interface Modal {
  root: HTMLElement;
  load: () => Promise<void>;
  close: () => void;
  onPick: (asset: Asset) => void;
}

function buildModal(): Modal {
  const root = document.createElement("div");
  root.id = "cd-asset-modal";
  root.style.cssText = [
    "position: fixed",
    "inset: 0",
    "background: rgba(0,0,0,0.85)",
    // Above the brand-kit modal (z 999) so "Upload logo…" opens on top,
    // not behind it.
    "z-index: 1000",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    "padding: 40px",
    // Body-mounted (outside #designer-root): set the UI font so the dialog
    // doesn't inherit the document default in the prod shell.
    `font-family: ${tokens.sans}`,
  ].join(";");

  const panel = document.createElement("div");
  panel.style.cssText = [
    "width: min(960px, 100%)",
    "max-height: 100%",
    `background: ${tokens.ink800}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 8px",
    "display: flex",
    "flex-direction: column",
    "overflow: hidden",
  ].join(";");

  // Header
  const header = document.createElement("div");
  header.style.cssText = `display:flex;align-items:center;gap:8px;padding:12px 16px;border-bottom:1px solid ${tokens.ink500};`;
  const title = document.createElement("h2");
  title.textContent = "Images";
  title.style.cssText = `margin:0;font-size:15px;color:${tokens.ink100};font-weight:500;`;
  const count = document.createElement("span");
  count.style.cssText = `color:${tokens.ink400};font-size:12px;`;
  const spacer = document.createElement("div");
  spacer.style.flex = "1";
  const uploadLabel = document.createElement("label");
  uploadLabel.htmlFor = "cd-asset-upload";
  uploadLabel.textContent = "⬆ Upload";
  uploadLabel.style.cssText = `padding:4px 10px;background:${tokens.ink700};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:4px;cursor:pointer;font-size:12px;`;
  const fileInput = document.createElement("input");
  fileInput.id = "cd-asset-upload";
  fileInput.type = "file";
  fileInput.accept = "image/*";
  fileInput.multiple = true;
  fileInput.style.display = "none";
  const closeBtn = document.createElement("button");
  closeBtn.type = "button";
  closeBtn.textContent = "Close";
  closeBtn.style.cssText = `padding:4px 10px;background:${tokens.ink700};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:4px;cursor:pointer;font-size:12px;`;
  header.append(title, count, spacer, uploadLabel, fileInput, closeBtn);

  // Body
  const body = document.createElement("div");
  body.style.cssText = "padding:16px;overflow:auto;";
  const zone = document.createElement("div");
  zone.style.cssText = `border:2px dashed ${tokens.ink500};border-radius:6px;padding:18px;text-align:center;color:${tokens.ink400};font-size:13px;cursor:pointer;margin-bottom:16px;`;
  zone.textContent = "Drop images here, or click Upload above";
  const grid = document.createElement("div");
  // Responsive grid: thumbnails keep their natural aspect ratio (no square
  // crop) and flow left-to-right, wrapping into new rows that grow downward
  // so the body scrolls VERTICALLY. (A CSS `columns` masonry layout spills
  // into new columns to the right inside this height-bounded scroll panel,
  // which produced an unwanted horizontal scrollbar.)
  grid.style.cssText =
    "display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;align-items:start;";
  body.append(zone, grid);

  panel.append(header, body);
  root.appendChild(panel);

  // Behavior wiring
  let assets: Asset[] = [];

  const close = (): void => {
    root.remove();
  };

  const render = (): void => {
    count.textContent = assets.length ? `(${assets.length})` : "";
    grid.innerHTML = "";
    if (assets.length === 0) {
      const empty = document.createElement("div");
      empty.textContent = "No images yet — drop or upload some above.";
      empty.style.cssText = `grid-column:1/-1; text-align:center; color:${tokens.ink400}; padding:32px;`;
      grid.appendChild(empty);
      return;
    }
    for (const asset of assets) {
      grid.appendChild(card(asset));
    }
  };

  const card = (asset: Asset): HTMLElement => {
    const c = document.createElement("div");
    c.style.cssText = `position:relative;background:${tokens.ink900};border:1px solid ${tokens.ink500};border-radius:4px;overflow:hidden;cursor:pointer;`;
    const img = document.createElement("img");
    img.src = asset.cdnUrl;
    img.alt = asset.filename ?? "";
    // Natural aspect ratio — fill the column width, height follows the image.
    img.style.cssText = "width:100%;height:auto;display:block;";
    const fn = document.createElement("div");
    fn.textContent = asset.filename ?? "(unnamed)";
    fn.style.cssText = `position:absolute;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);color:${tokens.ink100};padding:4px 8px;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;`;
    const del = document.createElement("div");
    del.textContent = "×";
    del.title = "Remove from library";
    del.style.cssText = `position:absolute;top:4px;right:4px;width:20px;height:20px;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,0.7);color:${tokens.ink100};border-radius:50%;font-size:14px;cursor:pointer;`;
    del.addEventListener("click", async (e) => {
      e.stopPropagation();
      await deleteAsset(asset.id);
    });
    c.addEventListener("mouseenter", () => { del.style.display = "flex"; });
    c.addEventListener("mouseleave", () => { del.style.display = "none"; });
    c.addEventListener("click", () => { modal.onPick(asset); });
    c.append(img, fn, del);
    return c;
  };

  const load = async (): Promise<void> => {
    try {
      const resp = await fetch("/advertiser/assets");
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const data = (await resp.json()) as AssetsResponse;
      assets = Array.isArray(data.assets) ? data.assets : [];
    } catch (e) {
      console.error("[asset-modal] fetch failed:", e);
      assets = [];
    }
    render();
  };

  // Browser-direct upload flow:
  //   1. Compute SHA-256 hex of the file bytes (SubtleCrypto).
  //   2. POST /advertiser/assets/presigned-upload → server returns
  //      a short-lived R2 PUT URL (or alreadyExists=true if the
  //      hash is already in image_asset, in which case we skip the PUT).
  //   3. PUT bytes directly to R2 — they never touch the dashboard
  //      or the core API. Saves bandwidth + base64 overhead, and
  //      makes large uploads (video bg up to 200 MB) tractable.
  //   4. POST /advertiser/assets/register → server inserts the
  //      advertiser_asset row (and image_asset if the hash is new).
  //
  // Falls back to the byte-shipping POST /advertiser/assets if any
  // step fails — old path stays as a safety net for environments
  // without R2 (in-memory ImageStorage).
  const uploadFiles = async (files: FileList | File[]): Promise<void> => {
    for (const raw of Array.from(files)) {
      if (!raw.type.startsWith("image/")) continue;
      const file = await prepareForUpload(raw);
      const compressedPct = Math.round((1 - file.size / raw.size) * 100);
      console.info(
        "[asset-modal] upload %s: %s → %s (%s)",
        raw.name,
        formatBytes(raw.size),
        formatBytes(file.size),
        raw.size === file.size ? "unchanged" : `-${compressedPct}% client-side`,
      );
      try {
        const asset = await uploadImageDirect(file);
        if (asset) {
          console.info(
            "[asset-modal] stored %s → %s (%d×%d, %s)",
            raw.name,
            asset.cdnUrl,
            asset.width ?? 0,
            asset.height ?? 0,
            file.type,
          );
          assets = [asset, ...assets.filter((a) => a.id !== asset.id)];
        }
      } catch (e) {
        // Dev harness (vite serve, no /advertiser backend on :9091):
        // fall back to a local data-URL so the image still lands on the
        // canvas and effects/layout can be exercised offline. Dead code
        // in production builds (import.meta.env.DEV === false).
        if (import.meta.env.DEV) {
          try {
            const asset = await localAsset(file);
            assets = [asset, ...assets.filter((a) => a.id !== asset.id)];
            console.info("[asset-modal] dev fallback: local data-URL for %s (no backend)", raw.name);
            continue;
          } catch (e2) {
            console.error("[asset-modal] dev fallback failed:", e2);
          }
        }
        console.error("[asset-modal] upload failed:", e);
      }
    }
    render();
  };

  /** Dev-only: build an in-memory asset from a File via a data-URL, so
    * upload works in the server-less harness. Stable id from the content
    * hash so re-uploading the same file de-dupes. */
  async function localAsset(file: File): Promise<Asset> {
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
    const dims = await imageDimensions(file).catch(() => null);
    const hash = await sha256Hex(file).catch(() => String(file.size));
    return {
      id: `dev-${hash.slice(0, 12)}`,
      cdnUrl: dataUrl,
      filename: file.name,
      width: dims?.w,
      height: dims?.h,
    };
  }

  function formatBytes(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / 1024 / 1024).toFixed(2)} MB`;
  }

  const deleteAsset = async (id: string): Promise<void> => {
    try {
      const resp = await fetch("/advertiser/assets/" + encodeURIComponent(id), { method: "DELETE" });
      if (resp.status === 204 || resp.ok) {
        assets = assets.filter((a) => a.id !== id);
        render();
      }
    } catch (e) {
      console.error("[asset-modal] delete failed:", e);
    }
  };

  // Events
  fileInput.addEventListener("change", () => {
    if (fileInput.files) void uploadFiles(fileInput.files);
  });
  zone.addEventListener("click", () => fileInput.click());
  zone.addEventListener("dragover", (e) => { e.preventDefault(); zone.style.borderColor = tokens.amber; });
  zone.addEventListener("dragleave", () => { zone.style.borderColor = tokens.ink500; });
  zone.addEventListener("drop", (e) => {
    e.preventDefault();
    zone.style.borderColor = tokens.ink500;
    if (e.dataTransfer?.files) void uploadFiles(e.dataTransfer.files);
  });
  closeBtn.addEventListener("click", close);
  root.addEventListener("click", (e) => { if (e.target === root) close(); });

  const modal: Modal = {
    root,
    load,
    close,
    onPick: () => {
      // replaced by caller via openAssetModal
    },
  };
  return modal;
}
