#!/usr/bin/env node
// Publish dist/promovolve-bootstrap.js to Cloudflare R2 under a
// content-hashed filename, then update scripts/.env so the backend
// picks up the new URL on next restart.
//
// Same pattern as banner-component's publish script — immutable
// caching via content-hashed filename, no stale-cache window.
//
// Run AFTER `npm run build`.

import { readFileSync, existsSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const SOURCE = resolve(__dirname, "../dist/promovolve-bootstrap.js");
const REPO_ROOT = resolve(__dirname, "../../..");
const ENV_FILE = resolve(REPO_ROOT, "scripts/.env");

const required = ["R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"];
const missing = required.filter((k) => !process.env[k]);
if (missing.length > 0) {
  console.error(`Missing env vars: ${missing.join(", ")}`);
  console.error("Set them in the same place you set them for the API (same R2 bucket).");
  process.exit(1);
}

if (!existsSync(SOURCE)) {
  console.error(`Source not found: ${SOURCE}\nRun \`npm run build\` first.`);
  process.exit(1);
}

const { R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET, CDN_BASE_URL } = process.env;

const body = readFileSync(SOURCE);
const hash = createHash("sha256").update(body).digest("hex").slice(0, 10);
const key = `js/promovolve-bootstrap.${hash}.js`;
const sizeKB = (body.length / 1024).toFixed(2);

const client = new S3Client({
  region: "auto",
  endpoint: `https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com`,
  credentials: {
    accessKeyId: R2_ACCESS_KEY_ID,
    secretAccessKey: R2_SECRET_ACCESS_KEY,
  },
});

console.log(`Uploading ${sizeKB} KB to s3://${R2_BUCKET}/${key}`);

await client.send(
  new PutObjectCommand({
    Bucket: R2_BUCKET,
    Key: key,
    Body: body,
    ContentType: "application/javascript; charset=utf-8",
    CacheControl: "public, max-age=31536000, immutable",
  }),
);

// Also upload to the stable, unhashed path that publisher HTML references.
// Short cache so publishers pick up new builds within 5 minutes without any
// HTML changes. The hashed copy above remains for backend code that needs
// immutable referencing.
const STABLE_KEY = "promovolve-ad.js";
console.log(`Uploading ${sizeKB} KB to s3://${R2_BUCKET}/${STABLE_KEY} (stable alias, max-age=300)`);
await client.send(
  new PutObjectCommand({
    Bucket: R2_BUCKET,
    Key: STABLE_KEY,
    Body: body,
    ContentType: "application/javascript; charset=utf-8",
    CacheControl: "public, max-age=300",
  }),
);

const url = CDN_BASE_URL ? `${CDN_BASE_URL}/${key}` : `<CDN_BASE_URL>/${key}`;
const stableUrl = CDN_BASE_URL ? `${CDN_BASE_URL}/${STABLE_KEY}` : `<CDN_BASE_URL>/${STABLE_KEY}`;
console.log(`Published: ${url}`);
console.log(`Stable:    ${stableUrl}`);

if (CDN_BASE_URL && existsSync(ENV_FILE)) {
  const envText = readFileSync(ENV_FILE, "utf8");
  const line = `BOOTSTRAP_SCRIPT_URL=${url}`;
  const updated = /^BOOTSTRAP_SCRIPT_URL=.*$/m.test(envText)
    ? envText.replace(/^BOOTSTRAP_SCRIPT_URL=.*$/m, line)
    : envText.trimEnd() + `\n${line}\n`;
  writeFileSync(ENV_FILE, updated);
  console.log(`Updated ${ENV_FILE} — backend uses BOOTSTRAP_SCRIPT_URL (hashed); publisher HTML uses ${stableUrl}.`);
} else {
  console.log(`Set BOOTSTRAP_SCRIPT_URL=${url} on any consumer that needs an immutable bootstrap URL.`);
  console.log(`Publisher pages should reference ${stableUrl}.`);
}
