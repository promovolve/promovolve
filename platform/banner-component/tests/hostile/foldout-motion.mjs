// Fold-out motion check: the tap-open must be ONE smooth hump of motion (no
// mid-flight stall), and a drag must never navigate (no CTA popup).
//
// Default target: fixtures/foldout.html on the BUILT bundle (npm run build),
// served here — no dev server needed.
//   node tests/hostile/foldout-motion.mjs
// Against the dev harness (vite on :5199, Fold-out toggle) with screenshots:
//   node tests/hostile/foldout-motion.mjs --dev --shots <dir>
import { createServer } from "http";
import { readFile } from "fs/promises";
import { extname, join } from "path";
import { chromium } from "playwright";

const args = process.argv.slice(2);
const DEV = args.includes("--dev");
const SHOTS = args.includes("--shots") ? args[args.indexOf("--shots") + 1] : null;

const ROOT = new URL("../..", import.meta.url).pathname;
const MIME = { ".html": "text/html", ".js": "text/javascript", ".map": "application/json" };
const server = DEV ? null : createServer(async (req, res) => {
  try {
    const path = join(ROOT, decodeURIComponent(new URL(req.url, "http://x").pathname));
    const body = await readFile(path);
    res.writeHead(200, { "content-type": MIME[extname(path)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404).end("not found");
  }
});
if (server) await new Promise((r) => server.listen(0, r));
const URL_ = DEV ? "http://localhost:5199/dev/index.html"
  : `http://127.0.0.1:${server.address().port}/tests/hostile/fixtures/foldout.html`;

const browser = await chromium.launch();

async function openPage(vp) {
  const page = await browser.newPage({ viewport: vp, deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(URL_, { waitUntil: "load" });
  if (DEV) await page.getByRole("button", { name: "Fold-out", exact: true }).click();
  await page.waitForSelector("expandable-magazine-banner");
  await page.waitForTimeout(300);
  return { page, errors };
}

// Per-frame trace of the tap-open: hinge angles + the fold-out's translate.
async function trace(vp) {
  const { page, errors } = await openPage(vp);
  // record from the frame before the tap; a real (trusted) click opens it
  await page.evaluate(() => {
    const sr = document.querySelector("expandable-magazine-banner").shadowRoot;
    const out = (window.__trace = []);
    const t0 = performance.now();
    // stamp with the rAF time — the same clock the fold-out's spring steps on
    const tick = (now) => {
      const hs = [...sr.querySelectorAll(".foldout-panel")].slice(1)
        .map((h) => parseFloat((h.style.transform.match(/-?[\d.]+/) || [0])[0]));
      const m = sr.querySelector(".foldout");
      const ty = m ? parseFloat((m.style.transform.match(/translate\([^,]+,\s*(-?[\d.]+)/) || [0, 0])[1]) : 0;
      out.push({ t: now - t0, hs, ty });
      if (now - t0 < 1800) requestAnimationFrame(tick); else window.__traceDone = true;
    };
    requestAnimationFrame(tick);
  });
  await page.locator("expandable-magazine-banner").click();
  await page.waitForFunction(() => window.__traceDone);
  const frames = await page.evaluate(() => window.__trace);
  await page.close();
  return { ...analyze(frames), errors };
}

// Activity = degrees of crease motion + px of travel / 2, as a RATE (per
// 10 ms) so uneven frame spacing reads as nothing. Only frames with a fold-out
// count (its first frame jumps from nothing to the slot); a second rAF in
// the same vsync (< 4 ms on) folds into the next sample; a 3-frame moving
// average takes out one-frame sampling jitter (a stall is a run, not one).
//
// The middle = from the first frame at >= 30% of peak to the last one;
// before it is the ease-in, after it the spring's settle tail. Inside it no
// frame may fall below 20% of peak: that is a stall, and the motion would
// rise again after it as a second hump. (30% in, 20% floor: the gap keeps
// frame noise at the edges from reading as a dip.)
function analyze(frames) {
  const fs = frames.filter((f) => f.hs.length);
  const raw = [];
  for (let i = 1, prev = fs[0]; i < fs.length; i++) {
    const f = fs[i], dt = f.t - prev.t;
    if (dt < 4) continue;
    const d = f.hs.reduce((s, v, k) => s + Math.abs(v - prev.hs[k]), 0) + Math.abs(f.ty - prev.ty) / 2;
    raw.push({ t: f.t, a: (d / dt) * 10 });
    prev = f;
  }
  const act = raw.map((x, k) => ({ t: x.t, a: (raw[Math.max(0, k - 1)].a + x.a + raw[Math.min(raw.length - 1, k + 1)].a) / 3 }));
  const peak = Math.max(0, ...act.map((x) => x.a));
  const moving = act.filter((x) => x.a > 0.05);
  const hot = act.map((x) => x.a >= 0.3 * peak);
  const mid = act.slice(hot.indexOf(true), hot.lastIndexOf(true) + 1);
  const minMid = Math.min(...mid.map((x) => x.a));
  // coarse sparkline: mean rate per 50 ms bucket, from the tap
  const sum = [], n = [];
  for (const x of act) { const k = Math.floor(x.t / 50); sum[k] = (sum[k] || 0) + x.a; n[k] = (n[k] || 0) + 1; }
  const mean = Array.from(sum, (v, k) => (v ? v / n[k] : 0));
  const mx = Math.max(...mean);
  const spark = mean.map((v) => " ▁▂▃▄▅▆▇█"[Math.round((v / mx) * 8)]).join("").trimEnd();
  return {
    ok: peak > 0 && minMid >= 0.2 * peak, frames: act.length, peak, minMid, pct: (100 * minMid) / peak,
    first: moving[0]?.t ?? 0, last: moving.at(-1)?.t ?? 0,
    midSpan: `${mid[0]?.t.toFixed(0)}→${mid.at(-1)?.t.toFixed(0)}ms`, spark,
  };
}

// Self-check: the detector must catch a stall. A synthetic open that lifts
// and creases, holds still for 120 ms, then finishes, must fail.
{
  const fake = [];
  let h = -180;
  for (let t = 0; t <= 600; t += 8) {
    if (t < 200 || t > 320) h = Math.min(0, h + 6);
    fake.push({ t, hs: [h], ty: 0 });
  }
  if (analyze(fake).ok) { console.log("FAIL       foldout-motion self-check: a stalled trace passed"); process.exit(1); }
}

// Drag from mid-poster up by one panel, release: folds, never a CTA.
async function dragProbe(vp) {
  const { page, errors } = await openPage(vp);
  await page.locator("expandable-magazine-banner").click();
  await page.waitForTimeout(2200);
  const per = await page.evaluate(() =>
    document.querySelector("expandable-magazine-banner").shadowRoot.querySelector(".foldout-panel").getBoundingClientRect().height);
  let popup = false, navigated = false;
  page.context().on("page", () => { popup = true; });
  page.on("framenavigated", (f) => { if (f === page.mainFrame()) navigated = true; });
  const cx = vp.width / 2, cy = vp.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx, cy - per, { steps: 8 });
  await page.mouse.up();
  await page.waitForTimeout(1000);
  await page.close();
  // control: a TAP on the open poster does open the CTA — so "no popup"
  // above means the drag was told apart, not that popups go unseen
  const c = await openPage(vp);
  await c.page.locator("expandable-magazine-banner").click();
  await c.page.waitForTimeout(2200);
  const tapPopup = c.page.context().waitForEvent("page", { timeout: 1000 }).then(() => true, () => false);
  await c.page.mouse.click(cx, cy);
  const control = await tapPopup;
  await c.page.close();
  return { ok: !popup && !navigated && control && errors.length === 0, per, popup, navigated, control, errors };
}

// Held drag poses + open/closing/closed, for the visual review.
async function shots(name, vp) {
  const { page, errors } = await openPage(vp);
  await page.locator("expandable-magazine-banner").click();
  await page.waitForTimeout(2200);
  await page.screenshot({ path: `${SHOTS}/${name}-open.png` });
  const per = await page.evaluate(() =>
    document.querySelector("expandable-magazine-banner").shadowRoot.querySelector(".foldout-panel").getBoundingClientRect().height);
  const cx = vp.width / 2, cy = vp.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  for (const f of [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75]) {
    await page.mouse.move(cx, cy - f * per, { steps: 3 });
    await page.waitForTimeout(50);
    await page.screenshot({ path: `${SHOTS}/${name}-f${f}.png` });
  }
  await page.mouse.up();
  await page.waitForTimeout(260);
  await page.screenshot({ path: `${SHOTS}/${name}-closing.png` });
  await page.waitForTimeout(900);
  await page.screenshot({ path: `${SHOTS}/${name}-closed.png` });
  await page.close();
  return errors;
}

let failed = 0;
for (const [name, vp] of [["phone", { width: 390, height: 844 }], ["desk", { width: 1280, height: 800 }]]) {
  const t = await trace(vp);
  t.ok &&= t.errors.length === 0;
  if (!t.ok) failed++;
  console.log(`${(t.ok ? "ok" : "FAIL").padEnd(10)} foldout-motion-${name.padEnd(11)} frames=${t.frames} ` +
    `motion ${t.first.toFixed(0)}→${t.last.toFixed(0)}ms peak=${t.peak.toFixed(1)} minMid=${t.minMid.toFixed(2)} ` +
    `(${t.pct.toFixed(0)}% of peak, middle ${t.midSpan})` + (t.errors.length ? `  err: ${t.errors.join(" | ")}` : ""));
  console.log(`           ${t.spark}`);
  const d = await dragProbe(vp);
  if (!d.ok) failed++;
  console.log(`${(d.ok ? "ok" : "FAIL").padEnd(10)} foldout-drag-${name.padEnd(13)} dragUp=${d.per.toFixed(0)}px ` +
    `popup=${d.popup} navigated=${d.navigated} tapControlPopup=${d.control}` + (d.errors.length ? `  err: ${d.errors.join(" | ")}` : ""));
  if (SHOTS) {
    const e = await shots(name, vp);
    console.log(`shots      ${name} → ${SHOTS}` + (e.length ? `  err: ${e.join(" | ")}` : ""));
  }
}
await browser.close();
server?.close();
process.exit(failed ? 1 : 0);
