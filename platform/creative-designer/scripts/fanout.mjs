#!/usr/bin/env node
// Copy the built designer bundle into platform/static/ so the Go
// dashboard can serve it via //go:embed. Unlike the banner component
// (which is publicly served and lives on the CDN), the designer is
// internal-only and rides along with the Go binary.
//
// Run AFTER `npm run build`. Requires a Go rebuild to pick up the
// new bytes — static files are embedded at compile time.

import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, "../../..");
const DIST = resolve(__dirname, "../dist");
const TARGET = resolve(REPO_ROOT, "platform/static");
const check = process.argv.includes("--check");

if (!existsSync(DIST)) {
  console.error(`Source not found: ${DIST}\nRun \`npm run build\` first.`);
  process.exit(1);
}

if (!check) mkdirSync(TARGET, { recursive: true });

const files = readdirSync(DIST, { withFileTypes: true }).filter((entry) => entry.isFile());
if (!files.some((entry) => entry.name === "creative-designer.js")) {
  throw new Error("Designer bundle missing from dist; run npm run build first.");
}

let stale = false;

// Copy all top-level files from dist/ (creative-designer.js, any .css,
// sourcemap). Skip subdirectories — Vite may emit a .vite/ metadata dir
// we don't want to fan out.
for (const name of files) {
  const src = resolve(DIST, name.name);
  const dst = resolve(TARGET, name.name);
  if (check) {
    if (!existsSync(dst) || !readFileSync(src).equals(readFileSync(dst))) {
      console.error(`Stale or missing platform/static/${name.name}`);
      stale = true;
    }
  } else {
    copyFileSync(src, dst);
    console.log(`  platform/static/${name.name}`);
  }
}

if (check) {
  // Other dashboard assets share this directory; only this prefix is Designer-owned.
  for (const name of readdirSync(TARGET)) {
    if (name.startsWith("creative-designer.") && !files.some((entry) => entry.name === name)) {
      console.error(`Obsolete Designer asset: platform/static/${name}`);
      stale = true;
    }
  }
  if (stale) console.error("Run npm run build && npm run fanout and commit the generated assets.");
  process.exit(stale ? 1 : 0);
}

console.log("\nFrom platform/creative-designer, rebuild the Go dashboard:");
console.log("  make -C .. build-server");
