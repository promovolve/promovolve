// Browser-direct image upload, shared by the asset library modal and
// the page-background texture picker.
//
// Flow (see the inline comments in uploadImageDirect):
//   prepareForUpload → sha256Hex → POST presigned-upload → PUT to R2
//   → POST register. Bytes go straight from the browser to Cloudflare
//   R2 — they never touch the dashboard or the core API — which keeps
//   large uploads tractable and avoids base64 overhead.
//
// These four functions were lifted verbatim out of asset-modal.ts so
// both callers share one implementation. asset-modal keeps its own
// dev-only data-URL fallback on top of this.

export interface UploadedAsset {
  id: string;
  cdnUrl: string;
  filename?: string;
  width?: number;
  height?: number;
  mime?: string;
  thumbUrl?: string;
}

interface RegisterResponse {
  asset?: UploadedAsset;
}

/** Browser-direct upload: hash → presign → PUT → register. Returns the
  * registered AdvertiserAssetView. Throws on any step failure so the
  * caller can fall back / log. */
export async function uploadImageDirect(file: File): Promise<UploadedAsset> {
  const hash = await sha256Hex(file);
  const dimensions = await imageDimensions(file).catch(() => null);

  const presignReq = {
    filename: file.name,
    mimeType: file.type || "application/octet-stream",
    hash,
    sizeBytes: file.size,
  };
  const presignResp = await fetch("/advertiser/assets/presigned-upload", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(presignReq),
  });
  if (!presignResp.ok) throw new Error(`presign HTTP ${presignResp.status}`);
  const { uploadUrl, alreadyExists } = (await presignResp.json()) as {
    uploadUrl: string;
    s3Key: string;
    alreadyExists: boolean;
  };

  if (!alreadyExists) {
    // PUT bytes directly to R2 — bytes never touch the dashboard.
    const putResp = await fetch(uploadUrl, {
      method: "PUT",
      body: file,
      headers: { "Content-Type": file.type || "application/octet-stream" },
    });
    if (!putResp.ok) throw new Error(`R2 PUT HTTP ${putResp.status}`);
  }

  const registerReq = {
    filename: file.name,
    mimeType: file.type || "application/octet-stream",
    hash,
    width: dimensions?.w,
    height: dimensions?.h,
  };
  const registerResp = await fetch("/advertiser/assets/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(registerReq),
  });
  if (!registerResp.ok) throw new Error(`register HTTP ${registerResp.status}`);
  const data = (await registerResp.json()) as RegisterResponse;
  if (!data.asset) throw new Error("register response missing asset");

  // Best-effort gallery thumbnail: upload a small WebP next to the original
  // at assets/{hash}_thumb.webp (the server derives that URL by convention).
  // Awaited so the returned asset's thumbUrl resolves immediately; any
  // failure is swallowed — the gallery falls back to the full image.
  await uploadThumbnail(file, hash).catch((e) =>
    console.warn("[upload-asset] thumbnail upload failed (non-fatal):", e),
  );
  return data.asset;
}

/** Downscale to a gallery thumbnail (long edge ≤ maxEdge) as WebP. Returns
  * null if the browser can't decode — caller skips the thumb upload. */
async function makeThumbnail(file: File, maxEdge = 400): Promise<Blob | null> {
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
    const w = Math.max(1, Math.round(bitmap.width * scale));
    const h = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.drawImage(bitmap, 0, 0, w, h);
    return await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/webp", 0.7));
  } catch {
    return null;
  }
}

/** Upload a thumbnail to the convention key assets/{parentHash}_thumb.webp
  * via the normal presign→PUT flow (a bare R2 object — no register row, so
  * no image_asset/advertiser_asset entry). Images only. */
async function uploadThumbnail(file: File, parentHash: string): Promise<void> {
  if (!file.type.startsWith("image/")) return;
  const blob = await makeThumbnail(file);
  if (!blob) return;
  const presignResp = await fetch("/advertiser/assets/presigned-upload", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    // hash = "{parentHash}_thumb" → server key assets/{parentHash}_thumb.webp.
    body: JSON.stringify({
      filename: "thumb.webp",
      mimeType: "image/webp",
      hash: `${parentHash}_thumb`,
      sizeBytes: blob.size,
    }),
  });
  if (!presignResp.ok) return;
  const { uploadUrl, alreadyExists } = (await presignResp.json()) as {
    uploadUrl: string;
    alreadyExists: boolean;
  };
  if (alreadyExists || !uploadUrl) return;
  await fetch(uploadUrl, {
    method: "PUT",
    body: blob,
    headers: { "Content-Type": "image/webp" },
  });
}

/** Compress then upload an image, returning its CDN URL + the stored
  * byte size (post-compression). In the dev harness (vite serve, no
  * /advertiser backend) the upload throws — fall back to a local
  * data-URL so the image still lands on the canvas and the render can be
  * exercised offline. Dead code in production (import.meta.env.DEV ===
  * false). */
export async function uploadImage(file: File): Promise<{ src: string; sizeBytes: number }> {
  const prepared = await prepareForUpload(file);
  try {
    const asset = await uploadImageDirect(prepared);
    return { src: asset.cdnUrl, sizeBytes: prepared.size };
  } catch (e) {
    if (import.meta.env.DEV) {
      console.info("[upload-asset] dev fallback: local data-URL (no backend)");
      return { src: await fileToDataUrl(prepared), sizeBytes: prepared.size };
    }
    throw e;
  }
}

/** Read a File/Blob as a base64 data-URL. */
function fileToDataUrl(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

/** SubtleCrypto SHA-256 of a Blob/File, returned as lowercase hex. */
export async function sha256Hex(file: Blob): Promise<string> {
  const buf = await file.arrayBuffer();
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Read natural dimensions from the file. Rejects if the browser can't
  * decode (rare for image/* files). */
export function imageDimensions(file: File): Promise<{ w: number; h: number }> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve({ w: img.naturalWidth, h: img.naturalHeight });
    };
    img.onerror = (e) => {
      URL.revokeObjectURL(url);
      reject(e);
    };
    img.src = url;
  });
}

// Downsample + WebP-encode before upload. Runs entirely in the browser
// via <canvas>, so the server stores already-optimised bytes and every
// impression ships them. GIF/WebP/SVG are left alone (animation/vector
// would be lost). Non-image MIMEs are left alone by the caller.
//
// Trade-offs baked in:
//   - Long-edge cap of 2000px. Expanded PC caps at 1600px; 2000 leaves
//     headroom for retina without storing 4000px phone-camera originals
//     for a 300x250 ad.
//   - Quality 0.82 — visually indistinguishable from the source on
//     photos, ~30-50% smaller than equivalent JPEG.
//   - Keep original if the downsample didn't kick in AND WebP came out
//     larger. Rare (already-optimised JPEGs of noise-heavy photos) but
//     worth the safety net.
export async function prepareForUpload(file: File): Promise<File> {
  if (
    file.type === "image/gif" ||
    file.type === "image/webp" ||
    file.type === "image/svg+xml"
  ) return file;
  try {
    const bitmap = await createImageBitmap(file);
    const { width: srcW, height: srcH } = bitmap;
    const MAX_EDGE = 2000;
    const scale = Math.min(1, MAX_EDGE / Math.max(srcW, srcH));
    const w = Math.max(1, Math.round(srcW * scale));
    const h = Math.max(1, Math.round(srcH * scale));

    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) return file;
    ctx.drawImage(bitmap, 0, 0, w, h);

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/webp", 0.82),
    );
    if (!blob) return file;
    if (scale === 1 && blob.size >= file.size) return file;

    const base = file.name.replace(/\.[^.]+$/, "") || "image";
    return new File([blob], `${base}.webp`, { type: "image/webp" });
  } catch (e) {
    console.warn("[upload-asset] webp encode failed, uploading original", e);
    return file;
  }
}
