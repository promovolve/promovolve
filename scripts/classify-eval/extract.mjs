// Fetch each eval page in a real browser and capture the text exactly the
// way the ad tag does (platform/banner-bootstrap/src/bootstrap.ts
// extractPageText: body text nodes in document order, no script/style/
// noscript, stop at 8000 chars), plus the data-section / data-place hints
// the page's own tag carries. Output: pages/<slug>.json, one per URL.
//
//   node scripts/classify-eval/extract.mjs [pages.tsv]
import fs from "node:fs";
import path from "node:path";
import { chromium } from "../../platform/dashboard-tests/node_modules/playwright/index.mjs";

const here = path.dirname(new URL(import.meta.url).pathname);
const tsv = process.argv[2] || path.join(here, "pages.tsv");
const outDir = path.join(here, "pages");
fs.mkdirSync(outDir, { recursive: true });

const urls = fs.readFileSync(tsv, "utf8").split("\n")
  .filter((l) => l.trim() && !l.startsWith("#"))
  .map((l) => l.split("\t")[0].trim());

const slug = (u) => u.replace(/^https?:\/\//, "").replace(/[^A-Za-z0-9]+/g, "_").replace(/^_|_$/g, "") || "root";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
for (const url of urls) {
  try {
    // DOM ready, then a short best-effort wait for client-rendered text: pages
    // with trackers or polling never reach "networkidle", and waiting for it
    // timed out 10 of 23 landing pages (#429).
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 45000 });
    await page.waitForLoadState("networkidle", { timeout: 5000 }).catch(() => {});
    const got = await page.evaluate(() => {
      const MAX = 8000;
      const parts = []; let len = 0;
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          const p = node.parentElement; if (!p) return NodeFilter.FILTER_REJECT;
          const t = p.tagName;
          if (t === "SCRIPT" || t === "STYLE" || t === "NOSCRIPT") return NodeFilter.FILTER_REJECT;
          return node.textContent && node.textContent.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
        },
      });
      while (len < MAX) { const n = walker.nextNode(); if (!n) break; const t = n.textContent.trim(); if (t) { parts.push(t); len += t.length + 1; } }
      const tag = document.querySelector("script[data-pub]");
      return {
        text: parts.join(" ").slice(0, MAX),
        section: tag?.getAttribute("data-section") || "",
        place: tag?.getAttribute("data-place") || "",
      };
    });
    const rec = { url, ...got, extractedAt: new Date().toISOString() };
    fs.writeFileSync(path.join(outDir, slug(url) + ".json"), JSON.stringify(rec, null, 2));
    console.log(`  ${url}  text=${got.text.length}  section="${got.section}"  place="${got.place}"`);
  } catch (e) {
    console.error(`  ${url}  FAILED: ${e.message}`);
  }
}
await browser.close();
