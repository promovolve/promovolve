// Hostile-environment suite: loads the BUILT bundle into fixture pages
// that reproduce real-world publisher hostility (transformed ancestors,
// strict CSP, Trusted Types, quirks mode, CSS inheritance bombs, SPA
// remounts, z-index wars, vertical writing) and asserts the ad still
// mounts, expands, and covers the viewport.
//
// Prereq: `npm run build` (fixtures load /dist/expandable-magazine-banner.js).
// Run: node tests/hostile/run.mjs
//
// KNOWN[name] documents environments we have decided we do not survive
// yet — they must fail in the EXPECTED way (regressions in the message
// still fail the suite) and are reported as documented gaps, not passes.
import { createServer } from "http";
import { readFile } from "fs/promises";
import { extname, join } from "path";
import { chromium } from "playwright";

const ROOT = new URL("../..", import.meta.url).pathname;
const MIME = { ".html": "text/html", ".js": "text/javascript", ".map": "application/json" };

const server = createServer(async (req, res) => {
  try {
    const path = join(ROOT, decodeURIComponent(new URL(req.url, "http://x").pathname));
    const body = await readFile(path);
    res.writeHead(200, { "content-type": MIME[extname(path)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404).end("not found");
  }
});
await new Promise((r) => server.listen(0, r));
const PORT = server.address().port;

// Environments we KNOWINGLY do not survive yet. Each maps to a predicate
// over the probe result that must hold — the documented failure shape.
const KNOWN = {
  // Trusted Types (require-trusted-types-for 'script'): the component
  // builds its DOM with shadowRoot.innerHTML, which throws under
  // enforcement — the ad never mounts on such sites. Fix direction:
  // register a TT policy (trustedTypes.createPolicy) or build via
  // DOM APIs. Until then this is a documented no-serve environment.
  "trusted-types": (r) => !r.mounted && r.errors.some((e) => e.includes("TrustedHTML")),
};

async function probe(browser, name) {
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  const errors = [];
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text().slice(0, 200)); });
  page.on("pageerror", (e) => errors.push(String(e).slice(0, 200)));
  await page.goto(`http://127.0.0.1:${PORT}/tests/hostile/fixtures/${name}.html`, { waitUntil: "load" });
  await page.waitForTimeout(name === "spa-remount" ? 2600 : 1200);

  const mounted = await page.evaluate(() => {
    const el = document.querySelector("expandable-magazine-banner");
    return !!el?.shadowRoot?.querySelector(".design-box");
  });
  let expanded = false, coverage = 0, closeVisible = false;
  if (mounted) {
    await page.locator("expandable-magazine-banner").first().scrollIntoViewIfNeeded();
    await page.waitForTimeout(400);
    await page.locator("expandable-magazine-banner").first().click();
    await page.waitForTimeout(1500); // deal-in settles
    const st = await page.evaluate(() => {
      const el = document.querySelector("expandable-magazine-banner");
      const overlay = el?.shadowRoot?.querySelector(".overlay");
      if (!overlay) return { expanded: false, coverage: 0, closeVisible: false };
      const r = overlay.getBoundingClientRect();
      const vw = window.innerWidth, vh = window.innerHeight;
      const ix = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
      const iy = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
      const closeBtn = [...(overlay.querySelectorAll("button") ?? [])]
        .find((b) => /close|閉/i.test(b.textContent ?? ""));
      let closeOnTop = false;
      if (closeBtn) {
        const cb = closeBtn.getBoundingClientRect();
        const hit = document.elementFromPoint(cb.x + cb.width / 2, cb.y + cb.height / 2);
        closeOnTop = hit === el || el.contains(hit);
      }
      return {
        expanded: true,
        coverage: (ix * iy) / (vw * vh),
        closeVisible: closeOnTop,
      };
    });
    ({ expanded, coverage, closeVisible } = st);
  }
  await page.close();
  return { name, mounted, expanded, coverage: Math.round(coverage * 100), closeVisible, errors: errors.slice(0, 2) };
}

const FIXTURES = ["quirks-mode", "css-bomb", "csp-strict", "trusted-types",
  "transformed-ancestor", "spa-remount", "zindex-war", "vertical-writing"];

const browser = await chromium.launch();
const results = [];
for (const f of FIXTURES) results.push(await probe(browser, f));
await browser.close();
server.close();

let failed = 0;
for (const r of results) {
  const healthy = r.mounted && r.expanded && r.coverage >= 95 && r.closeVisible;
  const known = KNOWN[r.name];
  let verdict;
  if (healthy) verdict = known ? "FIXED (remove from KNOWN)" : "ok";
  else if (known && known(r)) verdict = "known-gap";
  else { verdict = "FAIL"; failed++; }
  console.log(
    `${verdict.padEnd(10)} ${r.name.padEnd(22)} mounted=${r.mounted} expanded=${r.expanded} ` +
    `coverage=${r.coverage}% closeOnTop=${r.closeVisible}` +
    (r.errors.length ? `  err: ${r.errors.join(" | ")}` : "")
  );
  if (verdict === "FIXED (remove from KNOWN)") failed++;
}
process.exit(failed ? 1 : 0);
