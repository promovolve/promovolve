#!/usr/bin/env node
// Publish dist/expandable-magazine-banner.js to Cloudflare R2 under a
// content-hashed filename, then update scripts/.env so the backend
// picks up the new URL on next restart.
//
// Content-hashed URLs let us serve the file with
// `Cache-Control: public, max-age=31536000, immutable` — browsers and
// the CDN can cache forever, and every publish produces a brand new
// URL so there's never a stale-cache window.
//
// Uses the same R2 credentials the JVM-side R2ImageStorage reads from
// (R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET).
// Run AFTER `npm run build`.

import { readFileSync, existsSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const SOURCE = resolve(__dirname, "../dist/expandable-magazine-banner.js");
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
const key = `js/expandable-magazine-banner.${hash}.js`;
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
    // Safe because the filename changes with content.
    CacheControl: "public, max-age=31536000, immutable",
  }),
);

const url = CDN_BASE_URL ? `${CDN_BASE_URL}/${key}` : `<CDN_BASE_URL>/${key}`;
console.log(`Published: ${url}`);

// Update scripts/.env so `run-dev.sh` and `run-dashboard.sh` pick up
// the new URL on restart. Replaces any existing BANNER_SCRIPT_URL
// line; appends if absent.
if (CDN_BASE_URL && existsSync(ENV_FILE)) {
  const envText = readFileSync(ENV_FILE, "utf8");
  const line = `BANNER_SCRIPT_URL=${url}`;
  const updated = /^BANNER_SCRIPT_URL=.*$/m.test(envText)
    ? envText.replace(/^BANNER_SCRIPT_URL=.*$/m, line)
    : envText.trimEnd() + `\n${line}\n`;
  writeFileSync(ENV_FILE, updated);
  console.log(`Updated ${ENV_FILE} — restart API + dashboard to pick up the new URL.`);
} else {
  console.log(`Set BANNER_SCRIPT_URL=${url} on the API + dashboard to flip traffic.`);
}

// Also update k8s/kustomization.yaml so a cluster deploy points at the SAME
// bundle. This configMapGenerator literal is the source of truth the k8s api
// (envFrom) and platform (configMapKeyRef) both read — if it isn't bumped on
// every publish, the k8s dashboard/preview silently lags on an old banner
// (e.g. losing keep-frame / fit / the magazine preview). Always keep it in
// lockstep with scripts/.env.
const KUSTOMIZATION = resolve(REPO_ROOT, "k8s/kustomization.yaml");
if (CDN_BASE_URL && existsSync(KUSTOMIZATION)) {
  const kText = readFileSync(KUSTOMIZATION, "utf8");
  const re = /^(\s*-\s*BANNER_SCRIPT_URL=).*$/m;
  if (re.test(kText)) {
    writeFileSync(KUSTOMIZATION, kText.replace(re, `$1${url}`));
    console.log(`Updated ${KUSTOMIZATION} — re-apply kustomize (+ rollout) to deploy it to k8s.`);
  } else {
    console.log(`WARN: no BANNER_SCRIPT_URL line in ${KUSTOMIZATION} — k8s NOT updated.`);
  }
}
