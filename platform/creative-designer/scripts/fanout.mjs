#!/usr/bin/env node
// Copy the built designer bundle into platform/static/ so the Go
// dashboard can serve it via //go:embed. Unlike the banner component
// (which is publicly served and lives on the CDN), the designer is
// internal-only and rides along with the Go binary.
//
// Run AFTER `npm run build`. Requires a Go rebuild to pick up the
// new bytes — static files are embedded at compile time.

import { copyFileSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, "../../..");
const DIST = resolve(__dirname, "../dist");
const TARGET = resolve(REPO_ROOT, "platform/static");

if (!existsSync(DIST)) {
  console.error(`Source not found: ${DIST}\nRun \`npm run build\` first.`);
  process.exit(1);
}

mkdirSync(TARGET, { recursive: true });

// Copy all top-level files from dist/ (creative-designer.js, any .css,
// sourcemap). Skip subdirectories — Vite may emit a .vite/ metadata dir
// we don't want to fan out.
for (const name of readdirSync(DIST, { withFileTypes: true })) {
  if (!name.isFile()) continue;
  const src = resolve(DIST, name.name);
  const dst = resolve(TARGET, name.name);
  copyFileSync(src, dst);
  console.log(`  platform/static/${name.name}`);
}

console.log("\nRemember to rebuild the Go dashboard:");
console.log("  go build ./platform/cmd/server");
