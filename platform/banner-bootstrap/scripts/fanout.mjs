#!/usr/bin/env node
// Copy the built bootstrap bundle into the example publisher site so
// the existing `<script src="/promovolve-ad.js">` tags in those pages
// load the current bootstrap. The publisher site is a dev-env vehicle
// for E2E testing — every `npm run build` should refresh the local
// copy so the publisher pages always serve the latest dog-ear logic
// without each HTML file needing to chase content-hashed R2 URLs.
//
// Run AFTER `npm run build`.
import { copyFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, "../../..");

const SOURCE = resolve(__dirname, "../dist/promovolve-bootstrap.js");
// All three example publisher sites load /promovolve-ad.js from their
// own static dir (see <script src> in each index.html). Each needs the
// freshest bootstrap so dog-ear pin submission, off-page exclusion,
// and any other client-side serve-flow changes are exercised in dev.
// Without all three in the fanout, the "production-shape" sites
// (publisher-site, publisher-site-ja) silently drift behind on
// features that landed in the bootstrap — exactly how off-page pin
// exclusion appeared "broken" until this fix.
const TARGETS = [
  resolve(REPO_ROOT, "modules/examples/publisher-site-ja-localhost/promovolve-ad.js"),
  resolve(REPO_ROOT, "modules/examples/publisher-site-ja/promovolve-ad.js"),
  resolve(REPO_ROOT, "modules/examples/publisher-site/promovolve-ad.js"),
];

if (!existsSync(SOURCE)) {
  console.error(`Source not found: ${SOURCE}\nRun \`npm run build\` first.`);
  process.exit(1);
}

for (const target of TARGETS) {
  copyFileSync(SOURCE, target);
  console.log(`  ${target.replace(REPO_ROOT + "/", "")}`);
}
