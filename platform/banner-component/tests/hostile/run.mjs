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
const MIME = {
  ".html": "text/html", ".js": "text/javascript", ".map": "application/json",
  // The video fixture's clip + poster. A wrong type here means no decode
  // and a silently pointless test.
  ".mp4": "video/mp4", ".jpg": "image/jpeg",
};

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://x");
    const path = join(ROOT, decodeURIComponent(url.pathname));
    const body = await readFile(path);
    // ?stall=<ms> holds the response back. The video fixture uses it to
    // recreate the window this suite exists to police: a clip that has
    // not decoded yet, with the ad already on the page.
    const stall = Number(url.searchParams.get("stall") ?? 0);
    if (stall > 0) await new Promise((r) => setTimeout(r, stall));
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
// (Trusted Types graduated 2026-07-19: every HTML sink goes through the
// "promovolve" TT policy — see src/trusted-html.ts — and the fixture now
// also ALLOWLISTS that policy name, so it must fully pass like any other
// environment. Publishers restricting policy names must include
// "promovolve" in their trusted-types directive.)
const KNOWN = {};

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
  let expanded = false, coverage = 0, closeVisible = false, pathOk = false, transformStyle = "";
  if (mounted) {
    await page.locator("expandable-magazine-banner").first().scrollIntoViewIfNeeded();
    await page.waitForTimeout(400);
    await page.locator("expandable-magazine-banner").first().click();
    await page.waitForTimeout(1500); // deal-in settles
    const st = await page.evaluate((expectMap) => {
      const el = document.querySelector("expandable-magazine-banner");
      const sr = el?.shadowRoot;
      const overlay = sr?.querySelector(".overlay");
      const mapRoot = sr?.querySelector(".map-root");
      const panels = sr?.querySelectorAll(".map-panel").length ?? 0;
      const coverEl = sr?.querySelector(".map-panel");
      // Path pin: map fixtures open ONLY the map (3 panels, no reader);
      // every other fixture opens ONLY the reader (no map root/panels).
      const pathOk = expectMap
        ? !!mapRoot && panels === 3 && !overlay
        : !!overlay && !mapRoot && panels === 0;
      const host = expectMap ? mapRoot : overlay;
      if (!host) return { expanded: false, coverage: 0, closeVisible: false, pathOk, panels, transformStyle: "" };
      const r = host.getBoundingClientRect();
      const vw = window.innerWidth, vh = window.innerHeight;
      const ix = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
      const iy = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
      const closeBtn = [...(host.querySelectorAll("button") ?? [])]
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
        pathOk, panels,
        transformStyle: coverEl ? getComputedStyle(coverEl).transformStyle : "",
      };
    }, name.startsWith("map"));
    pathOk = st.pathOk; transformStyle = st.transformStyle;
    ({ expanded, coverage, closeVisible } = st);
  }
  await page.close();
  return { name, mounted, expanded, pathOk, transformStyle, coverage: Math.round(coverage * 100), closeVisible, errors: errors.slice(0, 2) };
}

const FIXTURES = ["quirks-mode", "css-bomb", "csp-strict", "trusted-types",
  "transformed-ancestor", "spa-remount", "zindex-war", "vertical-writing",
  "map", "map-csp-strict"];

// Short-viewport delivery behaviors — probed with phone emulation, not
// the shared 900×700 probe, so it gets its own function and verdict:
//  1. DIRECT MODE: on a landscape phone (innerHeight < MIN_EXPAND_VH)
//     a tap must open the LP in a new tab and fire the CTA pixel — and
//     must NOT open the reader.
//  2. ROTATE-OUT: a reader opened in portrait must auto-close (normal
//     flight path) when the phone rotates to landscape.
async function probeShortViewport(browser) {
  const fixture = `http://127.0.0.1:${PORT}/tests/hostile/fixtures/short-viewport.html`;
  const overlayPresent = (page) => page.evaluate(() => {
    const el = document.querySelector("expandable-magazine-banner");
    return !!el?.shadowRoot?.querySelector(".overlay");
  });

  // 1. Landscape phone: tap → LP popup + CTA pixel, no overlay.
  const land = await browser.newPage({
    viewport: { width: 844, height: 390 }, hasTouch: true, isMobile: true,
  });
  let ctaPixel = false;
  land.on("request", (req) => { if (req.url().includes("_ctapixel.gif")) ctaPixel = true; });
  await land.goto(fixture, { waitUntil: "load" });
  await land.waitForTimeout(600);
  const popupP = land.waitForEvent("popup", { timeout: 3000 }).catch(() => null);
  await land.locator("expandable-magazine-banner").click();
  const popup = await popupP;
  await land.waitForTimeout(400);
  const directOk = !!popup && popup.url().includes("_lp.html") && ctaPixel && !(await overlayPresent(land));
  await popup?.close().catch(() => {});
  await land.close();

  // 2. Portrait phone: tap → reader opens; rotate → reader closes.
  const port = await browser.newPage({
    viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true,
  });
  await port.goto(fixture, { waitUntil: "load" });
  await port.waitForTimeout(600);
  await port.locator("expandable-magazine-banner").click();
  await port.waitForTimeout(1500); // deal-in settles
  const openedPortrait = await overlayPresent(port);
  // Rotation-letterbox guard: while the reader is open the document
  // root background must be latched to the scrim tone (the browser
  // paints orientation-change letterbox bands with THAT color — no
  // overlay geometry can reach them).
  const rootBg = () => port.evaluate(() => document.documentElement.style.backgroundColor);
  const latchedWhileOpen = (await rootBg()) !== "";
  await port.setViewportSize({ width: 844, height: 390 });
  await port.waitForTimeout(1800); // close flight settles
  const closedOnRotate = !(await overlayPresent(port));
  const restoredAfterClose = (await rootBg()) === "";
  await port.close();

  // 3. Map: open in portrait, rotate to landscape → no .map-panel remains.
  const mapFixture = `http://127.0.0.1:${PORT}/tests/hostile/fixtures/map.html`;
  const mapPanels = (page) => page.evaluate(() =>
    document.querySelector("expandable-magazine-banner")?.shadowRoot?.querySelectorAll(".map-panel").length ?? 0);
  const mp = await browser.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  await mp.goto(mapFixture, { waitUntil: "load" });
  await mp.waitForTimeout(600);
  await mp.locator("expandable-magazine-banner").click();
  await mp.waitForTimeout(1500);
  const mapOpened = (await mapPanels(mp)) === 3;
  await mp.setViewportSize({ width: 844, height: 390 });
  await mp.waitForTimeout(1800);
  const mapClosedOnRotate = (await mapPanels(mp)) === 0;
  await mp.close();

  return { directOk, openedPortrait, closedOnRotate, latchedWhileOpen, restoredAfterClose, mapOpened, mapClosedOnRotate };
}

// Disconnect while a map is open: the app swaps its subtree under the live
// map. The old element's disconnectedCallback must tear the map down — no
// map root left anywhere (the old shadow root, any live banner, or the
// top layer), a fresh banner mounted, no page errors.
async function probeMapRemount(browser) {
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e).slice(0, 200)));
  await page.goto(`http://127.0.0.1:${PORT}/tests/hostile/fixtures/map-spa-remount.html`, { waitUntil: "load" });
  await page.waitForTimeout(1200);
  await page.locator("expandable-magazine-banner").first().click();
  await page.waitForTimeout(1500);
  const openedBefore = await page.evaluate(() => {
    const el = document.querySelector("expandable-magazine-banner");
    window.__oldSr = el.shadowRoot;
    return el.shadowRoot.querySelectorAll(".map-panel").length === 3;
  });
  await page.evaluate(() => window.__swap());
  await page.waitForTimeout(1500);
  const after = await page.evaluate(() => {
    const els = [...document.querySelectorAll("expandable-magazine-banner")];
    return {
      oldGone: !window.__oldSr.host.isConnected,
      oldMapLeft: window.__oldSr.querySelectorAll(".map-root,.map-panel").length,
      liveMapLeft: els.reduce((n, e) => n + (e.shadowRoot?.querySelectorAll(".map-root,.map-panel").length ?? 0), 0),
      topLayerLeft: document.querySelectorAll(":popover-open,dialog[open]").length,
      freshMounted: els.length === 1 && !!els[0].shadowRoot?.querySelector(".design-box"),
    };
  });
  await page.close();
  const ok = openedBefore && after.oldGone && after.oldMapLeft === 0 && after.liveMapLeft === 0
    && after.freshMounted && errors.length === 0;
  return { ok, openedBefore, ...after, errors: errors.slice(0, 2) };
}

// A video background must never let the page's own colour reach the
// screen — not in the ad box while the clip is still arriving, and not on
// the reader's cover. Both used to happen, in two separate render paths
// (fixed 86d37c3 + 61cef0b): the box painted `bg` and revealed as soon as
// IMAGES were ready, and the reader's page painted `bg` under the video.
// Neither is visible to the eye for more than a moment, which is exactly
// why it needs a machine with a stopwatch.
//
// The fixture's cover carries bg #ff00ff so the reader check can name the
// exact colour that must never reach the sheet. The BOX is checked
// differently: a posterless clip legitimately keeps its colour there and
// paints the film on top, so what matters is not whether the colour is
// SET but whether it was ever left VISIBLE — the ad on screen with no
// poster painted and no frame decoded. That is `blankMs`, and it is the
// blink itself, in milliseconds.
//
// Sampling starts at "commit", NOT "load": the load event waits on the
// media element, so by then the very window under test is over.
const FORBIDDEN_BG = "rgb(255, 0, 255)";
async function probeVideoBg(browser, { poster }) {
  const name = poster ? "video-bg-poster" : "video-bg-noposter";
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e).slice(0, 200)));
  await page.goto(
    `http://127.0.0.1:${PORT}/tests/hostile/fixtures/video-bg.html${poster ? "?poster=1" : ""}`,
    { waitUntil: "commit" },
  );

  const t0 = Date.now();
  const samples = [];
  for (let i = 0; i < 70; i++) { // ~3.5s at 50ms — covers the 1.2s stall + the 2s cap
    const s = await page.evaluate(() => {
      const root = document.querySelector("expandable-magazine-banner")?.shadowRoot;
      const banner = root?.querySelector(".banner");
      const box = root?.querySelector(".design-box");
      if (!box || !banner) return null;
      const cs = getComputedStyle(box);
      const v = root.querySelector("video");
      return {
        visible: getComputedStyle(banner).opacity === "1",
        bgImage: cs.backgroundImage !== "none",
        bgColor: cs.backgroundColor,
        videoReady: v ? v.readyState : 0,
      };
    }).catch(() => null);
    if (s) samples.push({ t: Date.now() - t0, ...s });
    if (samples.length && samples[samples.length - 1].videoReady >= 2 && samples.some((x) => x.visible)) break;
    await page.waitForTimeout(50);
  }

  const shown = samples.filter((s) => s.visible);
  const revealAt = shown.length ? shown[0].t : null;
  // The ad is on screen with nothing of the film up: no poster painted
  // AND the clip has no frame yet. This is the blink, in milliseconds.
  const blank = shown.filter((s) => !s.bgImage && s.videoReady < 2);
  const blankMs = blank.length ? blank[blank.length - 1].t - blank[0].t + 50 : 0;
  // What the ad was painting the moment it appeared — the poster, or a
  // decoded frame. Reported so a pass says WHY it passed.
  const atReveal = !shown.length ? "never-revealed"
    : shown[0].bgImage ? "poster" : shown[0].videoReady >= 2 ? "frame" : "nothing";

  // …and the reader's cover, which is the other render path.
  let readerColourSeen = null;
  await page.locator("expandable-magazine-banner").first().click();
  await page.waitForTimeout(1500); // deal-in settles
  readerColourSeen = await page.evaluate((forbidden) => {
    const stage = document.querySelector("expandable-magazine-banner")
      ?.shadowRoot?.querySelector(".page-0 .paper-stage");
    if (!stage) return null; // reader never opened — reported as a failure below
    return getComputedStyle(stage).backgroundColor === forbidden;
  }, FORBIDDEN_BG);

  await page.close();
  return { name, poster, revealAt, blankMs, atReveal, readerColourSeen, errors: errors.slice(0, 2) };
}

const browser = await chromium.launch();
const results = [];
for (const f of FIXTURES) results.push(await probe(browser, f));
const sv = await probeShortViewport(browser);
const mr = await probeMapRemount(browser);
const vids = [
  await probeVideoBg(browser, { poster: true }),
  await probeVideoBg(browser, { poster: false }),
];
await browser.close();
server.close();

let failed = 0;
for (const r of results) {
  // map-csp-strict additionally needs the map's stylesheet to have survived
  // the CSP: without it the cover loses preserve-3d and the fold flattens.
  const styled = r.name !== "map-csp-strict" || r.transformStyle === "preserve-3d";
  const healthy = r.mounted && r.expanded && r.pathOk && styled && r.coverage >= 95 && r.closeVisible;
  const known = KNOWN[r.name];
  let verdict;
  if (healthy) verdict = known ? "FIXED (remove from KNOWN)" : "ok";
  else if (known && known(r)) verdict = "known-gap";
  else { verdict = "FAIL"; failed++; }
  console.log(
    `${verdict.padEnd(10)} ${r.name.padEnd(22)} mounted=${r.mounted} expanded=${r.expanded} ` +
    `path=${r.pathOk} coverage=${r.coverage}% closeOnTop=${r.closeVisible}` +
    (r.name === "map-csp-strict" ? ` transformStyle=${r.transformStyle}` : "") +
    (r.errors.length ? `  err: ${r.errors.join(" | ")}` : "")
  );
  if (verdict === "FIXED (remove from KNOWN)") failed++;
}
{
  const ok = sv.directOk && sv.openedPortrait && sv.closedOnRotate
    && sv.latchedWhileOpen && sv.restoredAfterClose && sv.mapOpened && sv.mapClosedOnRotate;
  if (!ok) failed++;
  console.log(
    `${(ok ? "ok" : "FAIL").padEnd(10)} ${"short-viewport".padEnd(22)} direct=${sv.directOk} ` +
    `openedPortrait=${sv.openedPortrait} closedOnRotate=${sv.closedOnRotate} ` +
    `rootBgLatched=${sv.latchedWhileOpen} rootBgRestored=${sv.restoredAfterClose} ` +
    `mapOpened=${sv.mapOpened} mapClosedOnRotate=${sv.mapClosedOnRotate}`
  );
}
{
  if (!mr.ok) failed++;
  console.log(
    `${(mr.ok ? "ok" : "FAIL").padEnd(10)} ${"map-spa-remount".padEnd(22)} openedBefore=${mr.openedBefore} ` +
    `oldDetached=${mr.oldGone} oldMapLeft=${mr.oldMapLeft} liveMapLeft=${mr.liveMapLeft} ` +
    `topLayerLeft=${mr.topLayerLeft} freshMounted=${mr.freshMounted}` +
    (mr.errors.length ? `  err: ${mr.errors.join(" | ")}` : "")
  );
}
for (const v of vids) {
  // Same bar for both paths — the film or nothing, never the page colour.
  // The posterless run additionally proves the reveal WAITED for the clip:
  // with no still to show, appearing early can only mean appearing blank.
  const waited = v.poster || (v.revealAt ?? 0) >= 900; // stall is 1200ms
  const ok = v.revealAt !== null && v.blankMs === 0
    && v.readerColourSeen === false && waited;
  if (!ok) failed++;
  console.log(
    `${(ok ? "ok" : "FAIL").padEnd(10)} ${v.name.padEnd(22)} revealAt=${v.revealAt}ms ` +
    `blank=${v.blankMs}ms atReveal=${v.atReveal} pageColourInReader=${v.readerColourSeen}` +
    (v.poster ? "" : ` waitedForClip=${waited}`) +
    (v.errors.length ? `  err: ${v.errors.join(" | ")}` : "")
  );
}
process.exit(failed ? 1 : 0);
