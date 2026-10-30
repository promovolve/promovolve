// Printed-page suite (DESIGN.md: The Ink Only Rule, The Two Face Rule):
// the dashboard reads as a black-and-white printed page. The Go source
// scan (platform/print_rules_test.go) sees classes; this sees what the
// browser actually paints — computed colours, the heading face, sideways
// scroll, link underlines, the focus outline and the intensity scale.
//
// The pages are the REAL renders from the Go preview tests (PREVIEW_OUT),
// in light and dark. Prereq: Go toolchain + playwright's chromium.
// Run: npm run test:print [-- <screenshot dir>]
import { execFileSync } from "child_process";
import { createServer } from "http";
import { readFile } from "fs/promises";
import { mkdtempSync } from "fs";
import { tmpdir } from "os";
import { extname, join } from "path";
import { chromium } from "playwright";

const PLATFORM = new URL("..", import.meta.url).pathname;
const shots = process.argv[2];

// 1. Render the real pages through the Go preview tests.
const previewDir = mkdtempSync(join(tmpdir(), "pv-print-"));
execFileSync("go", ["test", "./internal/handler/", "-count=1", "-run",
  "TestCampaignsForecastRenders|TestWalletForecastRenders|TestSitesAndCreativesTemplatesRender|TestBillingTemplatesRender"], {
  cwd: PLATFORM,
  env: { ...process.env, PREVIEW_OUT: previewDir },
  stdio: "inherit",
});

// 2. Serve /preview/* from the render dir, static assets from platform/.
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".map": "application/json", ".woff2": "font/woff2" };
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, "http://x").pathname);
    const path = pathname.startsWith("/preview/")
      ? join(previewDir, pathname.slice("/preview/".length))
      : join(PLATFORM, pathname);
    const body = await readFile(path);
    res.writeHead(200, { "content-type": MIME[extname(path)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404).end("not found");
  }
});
await new Promise((r) => server.listen(0, r));
const PORT = server.address().port;

const failures = [];
const check = (name, ok, detail = "") => {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${ok ? "" : `  ${detail}`}`);
  if (!ok) failures.push(name);
};

const PAGES = ["advertiser-forecast", "advertiser-wallet", "publisher-stats", "publisher-sites",
  "admin-sites", "admin-billing", "advertiser-creatives"];

const browser = await chromium.launch();
for (const theme of ["light", "dark"]) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  if (theme === "dark") {
    await ctx.addInitScript(() => {
      try { localStorage.setItem("promovolve.dashboard.theme", "dark"); } catch {}
      document.documentElement.classList.add("dark");
    });
  }
  const page = await ctx.newPage();
  for (const name of PAGES) {
    for (const lang of ["en", "ja"]) {
      const id = `${name}-${lang} ${theme}`;
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.goto(`http://127.0.0.1:${PORT}/preview/${name}-${lang}.html`, { waitUntil: "load" });
      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(150);

      // no-hue: every painted colour outside the ad creatives is neutral.
      const hue = await page.evaluate(() => {
        const skip = "img, video, canvas, iframe, magazine-preview, [data-creative]";
        const props = ["color", "background-color", "border-top-color", "border-right-color",
          "border-bottom-color", "border-left-color", "outline-color", "fill", "stroke"];
        const bad = [];
        for (const el of document.querySelectorAll("body *")) {
          if (el.closest(skip)) continue;
          const cs = getComputedStyle(el);
          if (cs.display === "none") continue;
          for (const p of props) {
            const m = cs.getPropertyValue(p).match(/rgba?\(([\d.]+)[ ,]+([\d.]+)[ ,]+([\d.]+)(?:[ ,/]+([\d.]+))?/);
            if (!m || (m[4] !== undefined && +m[4] === 0)) continue;
            const [r, g, b] = [+m[1], +m[2], +m[3]];
            if (Math.max(r, g, b) - Math.min(r, g, b) > 2) {
              const cls = typeof el.className === "string" ? el.className.trim().split(/\s+/).slice(0, 4).join(".") : "";
              bad.push(`${el.tagName.toLowerCase()}${cls ? "." + cls : ""}: ${p} rgb(${r},${g},${b})`);
              break;
            }
          }
        }
        return bad;
      });
      check(`${id}: no-hue`, hue.length === 0, `${hue.length} elements, e.g. ${hue.slice(0, 5).join(" | ")}`);

      // mincho-headings: titles, section heads and the big figure.
      const type = await page.evaluate(() => {
        const els = [...document.querySelectorAll(".page-title, .section-title, .text-5xl, h1, h2")]
          // IDs keep the mono face (DESIGN.md: mono for IDs, tokens, code).
          .filter((e) => e.offsetParent !== null && !e.classList.contains("sr-only") && !e.classList.contains("font-mono"));
        const off = els.filter((e) => !/^"?BIZ UDPMincho/.test(getComputedStyle(e).fontFamily))
          .map((e) => `${e.tagName.toLowerCase()} "${e.textContent.trim().slice(0, 20)}"`);
        // fonts.check() is true when NO face matches, so look for a loaded one.
        const loaded = [...document.fonts].some((f) => f.family.replace(/"/g, "") === "BIZ UDPMincho" && f.status === "loaded");
        return { n: els.length, off, loaded };
      });
      check(`${id}: mincho-headings`, type.n > 0 && type.off.length === 0 && type.loaded,
        `${type.n} headings, not Mincho: ${type.off.slice(0, 3).join(", ")}; face loaded: ${type.loaded}`);

      // no-uppercase.
      const upper = await page.evaluate(() =>
        [...document.querySelectorAll("body *")].filter((e) => getComputedStyle(e).textTransform === "uppercase")
          .map((e) => e.textContent.trim().slice(0, 20)));
      check(`${id}: no-uppercase`, upper.length === 0, upper.slice(0, 3).join(", "));

      // links-underlined: with no link colour, the underline is the link.
      const links = await page.evaluate(() =>
        [...document.querySelectorAll("a.text-brand-ink")].filter((a) => a.offsetParent !== null)
          .filter((a) => !getComputedStyle(a).textDecorationLine.includes("underline"))
          .map((a) => a.textContent.trim().slice(0, 20)));
      check(`${id}: links-underlined`, links.length === 0, links.slice(0, 3).join(", "));

      // focus-ink: the keyboard focus outline on an ink primary button.
      const focus = await page.evaluate(() => {
        const b = [...document.querySelectorAll(".btn-primary")].find((e) => e.offsetParent !== null);
        if (!b) return null;
        b.focus({ focusVisible: true });
        return { has: b.matches(":focus-visible") };
      });
      if (focus) {
        // focus({focusVisible}) is not honoured everywhere: Tab to it instead.
        if (!focus.has) {
          for (let i = 0; i < 60; i++) {
            await page.keyboard.press("Tab");
            if (await page.evaluate(() => document.activeElement?.classList.contains("btn-primary"))) break;
          }
        }
        const f = await page.evaluate(() => {
          const b = document.activeElement;
          if (!b?.classList.contains("btn-primary")) return null;
          const cs = getComputedStyle(b);
          const m = cs.outlineColor.match(/(\d+)[ ,]+(\d+)[ ,]+(\d+)/);
          return { neutral: !!m && Math.max(+m[1], +m[2], +m[3]) - Math.min(+m[1], +m[2], +m[3]) <= 2,
            offset: parseFloat(cs.outlineOffset), width: parseFloat(cs.outlineWidth), color: cs.outlineColor };
        });
        check(`${id}: focus-ink`, !!f && f.neutral && f.offset >= 2 && f.width >= 2, JSON.stringify(f));
      }

      // scale + now-halo: the intensity legend and strip (forecast page).
      if (name === "advertiser-forecast") {
        const sc = await page.evaluate(() => {
          const lum = (el) => {
            const m = getComputedStyle(el).backgroundColor.match(/([\d.]+)[ ,]+([\d.]+)[ ,]+([\d.]+)/);
            const lin = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
            return 0.2126 * lin(+m[1]) + 0.7152 * lin(+m[2]) + 0.0722 * lin(+m[3]);
          };
          const steps = [1, 2, 3, 4, 5, 6, 7].map((n) => document.querySelector(`.bg-jma-${n}`)).filter(Boolean);
          const zero = document.createElement("span");
          zero.className = "bg-gray-100";
          document.body.append(zero);
          const z = lum(zero);
          zero.remove();
          const ls = steps.map(lum);
          const dark = document.documentElement.classList.contains("dark");
          const mono = ls.every((l, i) => i === 0 || (dark ? l > ls[i - 1] : l < ls[i - 1]));
          const [hi, lo] = [Math.max(z, ls[0]), Math.min(z, ls[0])];
          const now = document.querySelector('[role="img"] > span.absolute');
          const ncs = now && getComputedStyle(now);
          return { n: steps.length, mono, zeroVsOne: (hi + 0.05) / (lo + 0.05),
            halo: !!ncs && (parseFloat(ncs.outlineWidth) > 0 && ncs.outlineStyle !== "none" || ncs.boxShadow !== "none") };
        });
        check(`${id}: scale`, sc.n === 7 && sc.mono && sc.zeroVsOne >= 1.3, JSON.stringify(sc));
        check(`${id}: now-halo`, sc.halo, JSON.stringify(sc));
      }

      if (shots) await page.screenshot({ path: `${shots}/${name}-${lang}-${theme}.png`, fullPage: true });

      // no-hscroll: nothing scrolls sideways at 1440 or at 1280.
      for (const w of [1440, 1280]) {
        await page.setViewportSize({ width: w, height: 900 });
        await page.waitForTimeout(100);
        const hs = await page.evaluate(() => {
          const out = [];
          if (document.documentElement.scrollWidth > innerWidth) out.push(`page ${document.documentElement.scrollWidth}>${innerWidth}`);
          const main = document.querySelector("main");
          if (main) {
            for (const el of [main, ...main.querySelectorAll("*")]) {
              const ox = getComputedStyle(el).overflowX;
              if ((ox === "auto" || ox === "scroll") && el.scrollWidth > el.clientWidth + 1 && el.offsetParent !== null) {
                out.push(`${el.tagName.toLowerCase()}.${String(el.className).trim().split(/\s+/).slice(0, 3).join(".")} ${el.scrollWidth}>${el.clientWidth}`);
              }
            }
            const cs = getComputedStyle(main);
            const inner = main.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
            const first = [...main.children].find((c) => !c.matches("script, template, style") && getComputedStyle(c).display !== "none");
            if (first && first.getBoundingClientRect().width < inner - 1) out.push(`capped ${first.tagName.toLowerCase()}.${String(first.className).trim().split(/\s+/).slice(0, 3).join(".")} ${Math.round(first.getBoundingClientRect().width)}<${Math.round(inner)}`);
          }
          return out;
        });
        check(`${id}: no-hscroll @${w}`, hs.length === 0, hs.slice(0, 3).join(" | "));
      }
    }
  }
  // Phone width, for the review screenshots only (reflow is out of scope).
  if (shots) {
    await page.setViewportSize({ width: 390, height: 844 });
    for (const lang of ["en", "ja"]) {
      await page.goto(`http://127.0.0.1:${PORT}/preview/advertiser-forecast-${lang}.html`, { waitUntil: "load" });
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: `${shots}/advertiser-forecast-${lang}-${theme}-390.png`, fullPage: true });
    }
  }
  await ctx.close();
}
await browser.close();
server.close();

if (failures.length) {
  console.log(`\n${failures.length} printed-page check(s) failed`);
  process.exit(1);
}
console.log("\nall printed-page checks passed");
