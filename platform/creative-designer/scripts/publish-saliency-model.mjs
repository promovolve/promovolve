#!/usr/bin/env node
// One-shot: fetch the U-2-Net-Lite ONNX model from rembg's GitHub
// release, upload to R2 under a content-hashed filename, print the
// public URL. Run after model upgrades or initial setup. The hashed
// URL is what we paste into saliency.ts as MODEL_URL.
//
// Why a separate script (not part of the per-build flow): the model
// is ~4.5 MB and doesn't change between authoring sessions or
// banner-component releases. Treating it as a one-time-uploaded
// artifact (like a base image) avoids re-uploading on every save.
//
// R2 credentials come from scripts/.env, same as banner-component's
// publish:r2 — see node --env-file-if-exists below.

import { createHash } from "node:crypto";
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";

const SOURCE_URL =
  "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx";

const required = ["R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"];
const missing = required.filter((k) => !process.env[k]);
if (missing.length > 0) {
  console.error(`Missing env vars: ${missing.join(", ")}`);
  console.error("Run via the npm script so scripts/.env loads.");
  process.exit(1);
}

console.log(`Fetching ${SOURCE_URL}…`);
const res = await fetch(SOURCE_URL);
if (!res.ok) {
  console.error(`Source fetch failed: HTTP ${res.status} ${res.statusText}`);
  process.exit(1);
}
const buf = Buffer.from(await res.arrayBuffer());
console.log(`  ${(buf.length / 1024).toFixed(1)} KB downloaded`);

const hash = createHash("sha256").update(buf).digest("hex").slice(0, 10);
const key = `models/u2netp.${hash}.onnx`;

const s3 = new S3Client({
  region: "auto",
  endpoint: `https://${process.env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com`,
  credentials: {
    accessKeyId: process.env.R2_ACCESS_KEY_ID,
    secretAccessKey: process.env.R2_SECRET_ACCESS_KEY,
  },
});

console.log(`Uploading to s3://${process.env.R2_BUCKET}/${key}…`);
await s3.send(new PutObjectCommand({
  Bucket: process.env.R2_BUCKET,
  Key: key,
  Body: buf,
  ContentType: "application/octet-stream",
  // Content-hashed filename → safe to cache forever. Browsers and
  // the CDN can hold this indefinitely; new model = new hash = new
  // URL = no stale-cache window.
  CacheControl: "public, max-age=31536000, immutable",
}));

const publicUrl = `https://pub-7ab486148c8740dbb2cc31c5072eb91c.r2.dev/${key}`;
console.log(`\nPublished: ${publicUrl}`);
console.log("\nUpdate MODEL_URL in src/saliency.ts to the above URL,");
console.log("then run `npm run build && npm run fanout` and rebuild the Go server.");
