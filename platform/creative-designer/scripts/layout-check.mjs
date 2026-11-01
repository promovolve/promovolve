// Layout check for the designer's Forecast frame (#322): labelled tools,
// the selection bar, the panel scope, theme carry-over, a long name in
// the top bar, and the size gate. Runs against the Vite harness:
//
//   npm run serve                       # one terminal
//   node scripts/layout-check.mjs <dir> # screenshots land in <dir>
//
// Exits non-zero on the first failed check.
import { chromium } from "playwright";

const URL = "http://localhost:5173/";
const out = process.argv[2] ?? ".";

function check(ok, what) {
  if (!ok) {
    console.error(`FAIL: ${what}`);
    process.exit(1);
  }
  console.log(`ok   ${what}`);
}

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(URL, { waitUntil: "networkidle" });
  await page.waitForSelector(".cd-tool-rail");

  // Rail: every tool is labelled, no hover needed.
  for (const name of ["Select", "Text", "Image", "Formula", "Scrim", "Layout templates", "Brand kit"]) {
    check(await page.locator(".cd-tool-rail").getByText(name, { exact: true }).isVisible(), `rail shows "${name}"`);
  }

  const barText = () => page.textContent(".cd-selection-bar");
  const what = () => page.textContent("[data-cd-selection-what]").catch(() => "");
  const showing = () => page.evaluate(() => document.querySelector(".cd-sidebar b")?.textContent ?? "");
  const hasAction = (name) => page.locator(".cd-selection-bar button", { hasText: name }).first().isVisible();

  check((await barText()).includes("Nothing selected. Click an item on the banner, or add one from the left."), "empty selection bar text");
  check((await showing()) === "Banner and page 1", "panel shows Banner and page 1");

  // Two Face Rule: headings in Mincho (and the face really loaded).
  await page.evaluate(() => document.fonts.ready);
  const faces = await page.evaluate(() => {
    const ff = (sel) => { const el = document.querySelector(sel); return el ? getComputedStyle(el).fontFamily : ""; };
    return {
      crumb: ff("[data-cd-crumb-creative]"),
      group: ff(".cd-group-header > span:first-child"),
      tab: ff(".cd-tool-rail"),
      loaded: [...document.fonts].some((f) => f.family.replace(/"/g, "") === "BIZ UDPMincho" && f.status === "loaded"),
    };
  });
  check(/^"?BIZ UDPMincho/.test(faces.crumb), `creative name in Mincho (${faces.crumb})`);
  check(/^"?BIZ UDPMincho/.test(faces.group), `section headings in Mincho (${faces.group})`);
  check(/^"?BIZ UDPGothic/.test(faces.tab), `tools stay Gothic (${faces.tab})`);
  check(faces.loaded, "Mincho face loaded");
  check(/^"?BIZ UDPGothic/.test(await page.evaluate(() => getComputedStyle(document.body).fontFamily)), "body-level layers (modals, menus) in Gothic");
  await page.screenshot({ path: `${out}/layout-light.png` });

  // The harness fixture has no image item, so add one through the
  // dev-only store hook.
  const multiPage = await page.evaluate(() => {
    const store = window.__STORE__;
    const s = store.state;
    const img = { type: "image", src: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='30' height='20'%3E%3Crect width='30' height='20' fill='%2399aabb'/%3E%3C/svg%3E", left: 10, top: 10, width: 30, height: 20 };
    const pages = s.pages.map((p, i) => {
      if (i !== s.pageIdx) return p;
      if (s.mode.key === "expanded") return { ...p, layout: [...(p.layout ?? []), img] };
      const k = s.mode.sizeKey;
      return { ...p, banners: { ...p.banners, [k]: [...(p.banners?.[k] ?? []), img] } };
    });
    store.replace({ ...s, pages, selectedItemIdxs: [] });
    return s.mode.key === "mobile";
  });

  // Click hitboxes until the bar names the wanted type. The overlay
  // re-renders on click, so re-query by index each time.
  async function selectFirst(prefix) {
    const n = await page.locator("[data-cd-idx]").count();
    for (let i = 0; i < n; i++) {
      await page.locator("[data-cd-idx]").nth(i).click({ force: true });
      if ((await what()).startsWith(prefix)) return i;
    }
    return -1;
  }

  check((await selectFirst("Image ·")) >= 0, "an image can be selected");
  for (const a of ["Crop", "Remove background", ...(multiPage ? ["Replace"] : [])]) {
    check(await hasAction(a), `image offers "${a}"`);
  }
  check((await showing()).startsWith("Image ·"), "panel switched to the image");

  const textIdx = await selectFirst("Text ·");
  check(textIdx >= 0, "a text item can be selected");
  for (const a of ["Align left", "Align centre", "Align right"]) check(await hasAction(a), `text offers "${a}"`);

  const other = textIdx === 0 ? 1 : 0;
  await page.locator("[data-cd-idx]").nth(other).click({ force: true, modifiers: ["Shift"] });
  check((await what()) === "2 items", "shift-click selects 2 items");
  check(await hasAction("Group"), "two items offer Group");

  // Narrow window: the selection bar stays one line and the actions
  // that don't fit move into More.
  await page.setViewportSize({ width: 900, height: 900 });
  await selectFirst("Text ·");
  const selH = await page.evaluate(() => document.querySelector(".cd-selection-bar").getBoundingClientRect().height);
  check(Math.abs(selH - 48) < 1, `selection bar stays one line at 900px (got ${selH})`);
  check(await hasAction("Delete"), "Delete stays visible at 900px");
  await page.locator(".cd-selection-bar button", { hasText: "More" }).click();
  check(await page.getByRole("menuitem", { name: "Send to back" }).isVisible(), "More lists Send to back");
  await page.keyboard.press("Escape");

  // Narrow window: the breadcrumb never runs under the top bar's
  // buttons, and some of the creative name still shows.
  for (const w of [1000, 900]) {
    await page.setViewportSize({ width: w, height: 900 });
    await page.waitForTimeout(100);
    const top = await page.evaluate(() => {
      const crumb = document.querySelector(".cd-menu-bar nav").getBoundingClientRect();
      const actions = document.querySelector(".cd-menu-bar nav").nextElementSibling.getBoundingClientRect();
      const name = document.querySelector("[data-cd-crumb-creative]").getBoundingClientRect();
      return { gap: actions.left - crumb.right, name: name.width };
    });
    check(top.gap >= 0, `breadcrumb clear of the buttons at ${w}px (gap ${top.gap})`);
    check(top.name >= 48, `creative name still shows at ${w}px (${top.name}px)`);
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  // Long name: one line, same height, no horizontal scroll.
  await page.evaluate(() => {
    document.querySelector("[data-cd-crumb-creative]").textContent = "Autumn in Kyoto ".repeat(13).slice(0, 200);
  });
  const barH = await page.evaluate(() => document.querySelector(".cd-menu-bar").getBoundingClientRect().height);
  check(Math.abs(barH - 48) < 1, `top bar stays 48px with a long name (got ${barH})`);
  check(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), "no horizontal scroll");

  // Size gate.
  // The gate updates on resize, so wait for it rather than read once.
  const gateIs = (shown) => page.waitForFunction((want) => {
    const g = document.getElementById("cd-size-gate");
    return !!g && (getComputedStyle(g).display !== "none") === want;
  }, shown, { timeout: 3000 }).then(() => true, () => false);
  await page.setViewportSize({ width: 840, height: 900 });
  check(await gateIs(true), "size gate shows at 840px");
  await page.setViewportSize({ width: 860, height: 900 });
  check(await gateIs(false), "size gate hidden at 860px");

  // Theme carry-over from the designer's old key, in a fresh context.
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    if (!sessionStorage.getItem("seeded")) {
      sessionStorage.setItem("seeded", "1");
      localStorage.clear();
      localStorage.setItem("promovolve.designer.theme", "dark");
    }
  });
  const dark = await ctx.newPage();
  await dark.goto(URL, { waitUntil: "networkidle" });
  await dark.waitForSelector(".cd-tool-rail");
  const theme = await dark.evaluate(() => ({
    dash: localStorage.getItem("promovolve.dashboard.theme"),
    legacy: localStorage.getItem("promovolve.designer.theme"),
    attr: document.documentElement.dataset.cdTheme,
  }));
  check(theme.dash === "dark", "dashboard theme key carries the old choice");
  check(theme.legacy === null, "old designer theme key removed");
  check(theme.attr === "dark", "page renders dark");
  await dark.screenshot({ path: `${out}/layout-dark.png` });

  console.log("all checks passed");
} finally {
  await browser.close();
}
