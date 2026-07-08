#!/usr/bin/env node
// Copy the built bundle into the single consumer path:
//   - modules/crawler/src/main/resources/  (on the Scala classpath,
//     served by the API + read by the crawler for Playwright screenshots)
//
// The dashboard template references the API URL via {{.BannerScriptURL}},
// so there's no longer a Go-side copy to sync.
//
// Run AFTER `npm run build`. The consumer needs its own rebuild step
// (`sbt compile`) to pick up the new bytes because the path is embedded
// at build time.

import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, "../../..");

const SOURCE = resolve(__dirname, "../dist/expandable-magazine-banner.js");
const TARGETS = [
  resolve(REPO_ROOT, "modules/crawler/src/main/resources/expandable-magazine-banner.js"),
];

if (!existsSync(SOURCE)) {
  console.error(`Source not found: ${SOURCE}\nRun \`npm run build\` first.`);
  process.exit(1);
}

for (const target of TARGETS) {
  mkdirSync(dirname(target), { recursive: true });
  copyFileSync(SOURCE, target);
  console.log(`  ${target.replace(REPO_ROOT + "/", "")}`);
}

console.log("\nRemember to rebuild the consumer:");
console.log("  sbt compile");
