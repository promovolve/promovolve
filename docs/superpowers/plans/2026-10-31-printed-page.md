# Printed Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the dashboard read as a black-and-white printed page. That means four things: neutral greyscale everywhere except ad creatives, Mincho headings, no leftover hue, glyphs or uppercase, and no width caps that push tables into a horizontal scrollbar.

**Architecture:** Most of the change is at the token level, in `tailwind.input.css` and `tailwind.config.js`, so templates that use tokens follow without edits. Two guards come first and stay. A Go test scans template and Go sources for hue, glyphs and uppercase. A browser check renders the real pages through the Go preview tests and reads computed styles: colour, font, width, link underline and focus. The template sweep then makes both guards pass.

**Tech Stack:** Go `html/template` and Tailwind 3.4.17 (compiled CSS committed at `platform/static/tailwind.css`), Alpine, Playwright (in `platform/dashboard-tests`), and the creative designer (TypeScript/Vite, vitest).

**Spec:** `docs/superpowers/specs/2026-10-31-printed-page-design.md`

## Global Constraints

- Work on branch `feat/printed-page`. No push and no PR until the user says so (issue first, then a PR with `Closes #N`).
- Never type `npx <tool>`. Use `node_modules/.bin/<tool>`, `npm run`/`npm test`, or `scripts/build-tailwind.sh`. The script calls a pinned npx inside itself, which is fine.
- After any template class or `tailwind.input.css` change, run `scripts/build-tailwind.sh` from the repo root and commit `platform/static/tailwind.css` along with it.
- Every new or changed UI string needs an EN key plus a 日本語 entry in `platform/internal/i18n/ja.go`, and `go test ./...` in `platform` must stay green. That includes `i18n_drift_test.go`.
- Colour values come from the spec tables only. Neutral steps are r=g=b.
- Ad creatives stay in full colour: `<magazine-preview>`, banner previews, `<img>`, `<video>`, `<canvas>` of creatives, and the designer canvas content. No `filter: grayscale` anywhere.
- Layout stays as it is, apart from removing the page-level width caps. Nothing scrolls sideways at 1280px or 1440px.
- The user reviews screenshots before the final commit of visual work (Task 7).

## Review Focus

1. **The now line on dark cells.** The now line is ink, and light step 7 is `#1b1b1b`, so the line disappears against high-intensity cells. It needs a 1px ground-colour halo. Task 2 adds this and its check.
2. **The zero cell against step 1.** Light zero (gray-100 `#ececec`) against step 1 (`#cccccc`) is 1.36:1, and dark is 1.57:1. Both are the minimum that reads. Task 2 checks that the scale is monotonic and that zero vs step 1 is ≥ 1.3 in both themes.
3. **Hue arriving through places a class grep misses:** inline `style` hex, Alpine `:class` strings, Go-returned classes (`WinRateClass`, `StatusColor`) and Chart.js colours. Task 1's Go guard also scans template hex and named Go files. The browser check catches whatever renders.
4. **Links that are told apart only by colour.** Once link blue is gone, an `a.text-brand-ink` that isn't underlined looks like plain text. Task 2 makes underline the default and checks it.
5. **The focus outline on ink-filled controls.** An ink outline on an ink primary button must stay visible. A 2px offset leaves a ground-coloured gap. Task 2 checks outline colour and offset on `.btn-primary`.

---

### Task 1: Guards (both fail at first)

**Files:**
- Create: `platform/print_rules_test.go` (package `platform`, next to `i18n_drift_test.go`)
- Create: `platform/dashboard-tests/printed-page.run.mjs`
- Modify: `platform/dashboard-tests/package.json` (add `"test:print": "node printed-page.run.mjs"`)
- Modify: `platform/internal/handler/billing_render_test.go` (write `PREVIEW_OUT` renders, using the same pattern as `sites_render_test.go:145-151`)

**Interfaces:**
- Produces: `TestTemplatesArePrintedPage` (Go) and `npm run test:print` (browser check, which takes an optional screenshot dir as argv[2]). Every later task runs these two.

- [ ] **Step 1: Write `TestTemplatesArePrintedPage`.** It walks `templates/**/*.html` plus `internal/handler/pages.go`, and fails with `file:line: match` for any of the following:
  - the regex `\b(text|bg|border|ring|outline|fill|stroke|from|to|accent|divide|placeholder)-(red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b`
  - the glyphs `✓ ✔ ✗ ✕`
  - the word `uppercase` in a class attribute
  - any `#rrggbb` or `rgb(a)(` literal in a template whose channels are not all equal

  It also fails if `tailwind.input.css` contains `grayscale(`. Name each check in a `t.Run` subtest: `hue-utilities`, `glyphs`, `uppercase`, `hex`, `no-grayscale-filter`.

- [ ] **Step 2: Run it.** `cd platform && go test -run TestTemplatesArePrintedPage .` Expected: FAIL in `hue-utilities` (about 100 lines; creative-editor, approval and campaigns are the biggest), `glyphs` (9), `uppercase` (25) and `hex` (the report pages' `rgba(33,133,255,0.10)` and others). `no-grayscale-filter` PASSes.

- [ ] **Step 3: Write `printed-page.run.mjs`.** Use the same skeleton as `datepicker.run.mjs`: render through Go, serve `/preview/*` plus `platform/`, Chromium.
  - It renders through `go test ./internal/handler/ -run 'TestCampaignsForecastRenders|TestWalletForecastRenders|TestSitesAndCreativesTemplatesRender|TestBillingTemplatesRender' -count=1`.
  - The pages are: `advertiser-forecast`, `advertiser-wallet`, `publisher-stats`, `publisher-sites`, `admin-sites`, `admin-billing` and `advertiser-creatives`, each `-en` and `-ja`. That's 1440×900, light, then dark (add `dark` to `<html>` before load through `addInitScript`).

  For each page and theme it calls `check(name, ok, detail)`:
  - **`no-hue`:** for every element outside `img, video, canvas, iframe, magazine-preview, [data-creative]`, every computed `color`, `background-color`, `border-{top,right,bottom,left}-color`, `outline-color`, `fill` and `stroke` with alpha > 0 has `max(r,g,b) − min(r,g,b) ≤ 2`. On failure, list the first 5 as `tag.class: prop rgb(...)`.
  - **`mincho-headings`:** every `.page-title` and `.section-title`, plus the `text-5xl` figure, has a computed `font-family` that starts with `"BIZ UDPMincho"`, and `document.fonts.check('700 30px "BIZ UDPMincho"')` is true after `document.fonts.ready`.
  - **`no-hscroll`:** at 1440×900 and again at 1280×900, `document.documentElement.scrollWidth ≤ innerWidth`. No element inside `<main>` with computed `overflow-x` of `auto` or `scroll` has `scrollWidth > clientWidth + 1`. No page-level wrapper (the first child of `<main>`) is narrower than `<main>`'s content box minus 1px.
  - **`no-uppercase`:** no element has computed `text-transform: uppercase`.
  - **`links-underlined`:** every visible `a.text-brand-ink` has a computed `text-decoration-line` that includes `underline`.
  - **`focus-ink`:** focus the first `.btn-primary` with the keyboard (Tab until active). Its computed `outline-color` is neutral and `outline-offset` is ≥ 2px.
  - **`scale` (forecast page only):** the legend spans `.bg-jma-1` … `.bg-jma-7` have luminance strictly decreasing in light and strictly increasing in dark. Zero cell (`bg-gray-100`) vs `.bg-jma-1` contrast is ≥ 1.3.
  - **`now-halo` (forecast page only):** the now line (`[role=img] > span.absolute`) has a non-zero `outline-width` or `box-shadow`.

  With argv[2] set, it saves `<dir>/<page>-<theme>.png` (full page). It exits 1 if anything failed, and prints `all printed-page checks passed` otherwise.

- [ ] **Step 4: Run it.** `cd platform/dashboard-tests && npm run test:print`. Expected: exit 1, with FAILs in `no-hue` (link blue, JMA cells, field blue-grey, alert badges), `mincho-headings`, `no-hscroll` (wallet, admin pages, and any capped page whose table overflows), `links-underlined`, `focus-ink` and `scale`. Read the output. A crash or a 404 is not the expected failure: fix the harness until only check FAILs remain.

- [ ] **Step 5: Commit.**
```bash
git add platform/print_rules_test.go platform/dashboard-tests/printed-page.run.mjs platform/dashboard-tests/package.json platform/internal/handler/billing_render_test.go
git commit -m "test(platform): printed-page guards (source scan + computed-style check), failing"
```

### Task 2: Greyscale tokens

**Files:**
- Modify: `platform/tailwind.input.css` (`:root` / `.dark` variables at lines 13–47, the base rules at lines 52–56, `.badge-warning`/`.badge-danger` at 92–93, `.form-input` at 99, notices at 105–115, `.dark .badge-warning` at 209)
- Modify: `platform/tailwind.config.js` (`jma`, `advisory`, `warning` colours)
- Modify: `platform/templates/layout.html` (the `forecast-strip` now line, at line 446)
- Modify: `platform/static/tailwind.css` (rebuilt)

**Interfaces:**
- Produces: `--c-jma-1` … `--c-jma-7` CSS variables (RGB triples), and `jma` Tailwind colours routed through them, so later tasks keep using `bg-jma-N`.

- [ ] **Step 1: Neutral greys.** Replace every `--c-*` triple with the spec's table (light and dark), so r=g=b. `--c-brand-ink` is ink in each theme: `26 26 26` in light, `245 245 245` in dark. The dark `--c-brand` becomes `236 236 236` and the dark `--c-on-brand` becomes `21 21 21` (same luminance, no tint).
- [ ] **Step 2: Intensity variables.**
  - Light: `--c-jma-1..7` = `204`, `171`, `139`, `109`, `80`, `53`, `27`.
  - Dark: `71`, `95`, `121`, `147`, `175`, `203`, `232`.
  - Each triple repeats the value three times.
  - In `tailwind.config.js`, `jma: {0: 'rgb(var(--c-gray-100) / <alpha-value>)', 1: 'rgb(var(--c-jma-1) / <alpha-value>)', …}`.
  - `advisory` is `rgb(var(--c-gray-300) / <alpha-value>)`.
  - `warning` is `rgb(var(--c-gray-900) / <alpha-value>)`.
  - Update the config comments to say greyscale.
- [ ] **Step 3: Base rules.**
  - `::selection { background: rgb(var(--c-gray-300)); color: rgb(var(--c-gray-900)); }`
  - `:focus-visible { outline: 2px solid rgb(var(--c-gray-900)); outline-offset: 2px; }`
  - `.form-input` focus ring is `focus:ring-gray-900/40`.
  - Add `a.text-brand-ink, button.text-brand-ink { text-decoration-line: underline; text-underline-offset: 2px; }`.
- [ ] **Step 4: Alerts and notices.**
  - `.badge-danger`: `bg-gray-900 text-white`. Both tokens flip in dark, so the badge reads light-on-dark there too.
  - `.badge-warning`: `bg-gray-300 text-gray-900`.
  - Delete `.dark .badge-warning`.
  - Notices:
    - `.notice-error`: `border-2 border-gray-900 bg-white`
    - `.notice-advisory`: `border-gray-400 bg-gray-100`
    - `.notice-ok` and `.notice-info`: `border-gray-300 bg-gray-50`
  - Delete the `.dark .notice-*` overrides; the tokens flip on their own.
  - Update the comment above them.
- [ ] **Step 5: Now line halo.** In `forecast-strip`, add `outline outline-1 outline-white` to the now-line span, so it shows on step-7 cells (`white` is the surface token in both themes).
- [ ] **Step 6: Rebuild and run.** Run `scripts/build-tailwind.sh`, then `cd platform/dashboard-tests && npm run test:print`. Expected:
  - `scale`, `now-halo`, `links-underlined` and `focus-ink` PASS.
  - `no-hue` fails only on elements that carry a hue utility class or a hard-coded colour from a template (the detail shows `text-red-…`, `bg-amber-…` and so on). Task 4 removes those.
  - `mincho-headings` and `no-hscroll` still FAIL.
- [ ] **Step 7: Go tests.** `cd platform && go test ./...`. Expected: PASS except `TestTemplatesArePrintedPage` (still failing until Task 4).
- [ ] **Step 8: Commit.**
```bash
git add platform/tailwind.input.css platform/tailwind.config.js platform/templates/layout.html platform/static/tailwind.css
git commit -m "feat(platform): greyscale tokens: neutral greys, grey intensity scale, typographic alerts"
```

### Task 3: Mincho headings

**Files:**
- Create: `platform/static/fonts/biz-udpmincho/` (woff2 files + `OFL.txt`)
- Create: `platform/static/fonts/biz-udpmincho.css`
- Modify: `platform/templates/layout.html:26` (add the stylesheet link before `tailwind.css`)
- Modify: the `<link>` font lines in standalone pages that don't use `layout` (find them with `grep -rln 'biz-udpgothic.css' platform/templates`)
- Modify: `platform/tailwind.config.js` (`fontFamily.serif`)
- Modify: `platform/tailwind.input.css` (`.page-title`, `.section-title`, new `.figure-lead`)
- Modify: `platform/templates/advertiser/campaigns.html:25`, `platform/templates/publisher/stats.html:30` (the figure gets `figure-lead`)

- [ ] **Step 1: Fetch the font.** Download `@fontsource/biz-udpmincho@5.3.0` without npm install. Use node: fetch `https://registry.npmjs.org/@fontsource/biz-udpmincho/-/biz-udpmincho-5.3.0.tgz` into the scratchpad, then `tar -xzf` it there. Copy every `files/biz-udpmincho-*-{400,700}-normal.woff2` (the numbered Japanese subsets plus latin), and `LICENSE` as `OFL.txt`.
- [ ] **Step 2: Write the CSS.** Concatenate the package's `400.css` and `700.css` into `biz-udpmincho.css`. Rewrite `url(./files/` to `url(biz-udpmincho/`, keep only woff2 sources, and keep the header comment format of `biz-udpgothic.css` (name, licence, version, subsets).
- [ ] **Step 3: Tailwind and classes.**
  - `fontFamily.serif`: `['"BIZ UDPMincho"', '"BIZ UDP明朝"', '"Hiragino Mincho ProN"', '"Yu Mincho"', 'serif']`.
  - `.page-title` and `.section-title` add `font-serif`.
  - Add `.figure-lead { @apply font-serif; }` and apply it to the two `text-5xl` figures.
- [ ] **Step 4: Rebuild and run.** Run `scripts/build-tailwind.sh`, then `npm run test:print`. Expected: `mincho-headings` PASSes on every page and theme. Spot-check `publisher-stats-ja` for the 明朝 glyphs in the screenshot (`npm run test:print -- <scratch dir>`).
- [ ] **Step 5: Commit.**
```bash
git add platform/static/fonts/biz-udpmincho platform/static/fonts/biz-udpmincho.css platform/templates platform/tailwind.config.js platform/tailwind.input.css platform/static/tailwind.css
git commit -m "feat(platform): Mincho headings (BIZ UDPMincho, self-hosted)"
```

### Task 4: Leftovers sweep

**Files:**
- Modify (hue utilities, from Task 1's failure list):
  - `advertiser/creative-editor.html` (25)
  - `publisher/approval.html` (20)
  - `advertiser/campaigns.html` (14)
  - `publisher/sites.html` (8)
  - `advertiser/creatives.html` (7)
  - `admin/billing.html` (6)
  - `admin/users.html` (5)
  - `advertiser/report.html` (4)
  - `advertiser/account.html` (4)
  - `publisher/trusted.html`, `publisher/site-observations.html`, `org/members.html` (2 each)
  - `setup.html`, `recover.html`, `admin/billing-topups.html`, `admin/billing-journal.html` (1 each)
- Modify: `platform/internal/handler/pages.go:1420-1424` (`WinRateClass`) and `:5316-5332` (`StatusColor`)
- Modify (glyph strings): `layout.html:355`, `publisher/sites.html:250,259,270,279,321,485`, `advertiser/creative-editor.html:126`, `advertiser/campaigns.html:457`, and `platform/internal/i18n/ja.go:143,624,890`
- Modify (uppercase): the 10 templates from Task 1's `uppercase` failures
- Modify: the error and advisory notices (`grep -rn 'notice-error\|notice-advisory' platform/templates`)

- [ ] **Step 1: Hue utilities.** Map by meaning, not by shade:
  - An alert or error state uses `.badge-danger`, `.badge-warning` or the matching `.notice-*`.
  - Emphasis text uses `text-gray-900 font-bold`.
  - Hint or secondary text uses `text-gray-500`.
  - Success or "ok" text uses plain `text-gray-900` (the word carries it).
  - Fills and borders use `bg-gray-100` and `border-gray-300`/`border-gray-900`.

  Apply the same mapping inside Alpine `:class` and `x-bind` strings. `WinRateClass`: low is `text-gray-900 font-bold`, mid is `text-gray-900`, high is `text-gray-500`, and update its doc comment. `StatusColor`: "Highly variable" is `badge-warning`, and the other three are `badge-neutral` (already in `tailwind.input.css:94`). `.badge-warning`'s `text-[#1a1a1a]` becomes `text-gray-900` in Task 2.
- [ ] **Step 2: Chart colours.** In `advertiser/report.html` and `publisher/report.html`, every Chart.js colour literal becomes a neutral grey: series lines ink `rgb(26,26,26)`, fills `rgba(26,26,26,0.08)`, and the second series `rgb(120,120,120)` dashed `[4,3]`. Charts on other templates follow the same rule.
- [ ] **Step 3: Glyphs.** Change the keys to `"Copied"`, `"Saved"` and `"Recommended:"`. In `ja.go`, rename the three entries to `"コピーしました"`, `"保存しました"` and `"おすすめ:"`. Line 457 in campaigns: replace the glyph with the word its neighbour strings use, an existing key where one fits.
- [ ] **Step 4: Uppercase.** Delete `uppercase` and any `tracking-wide`/`tracking-wider` that only served it.
- [ ] **Step 5: Notice lead words.** Every `.notice-error` that doesn't already start with a bold label gets `<b>{{t "Error:"}}</b> ` first. Every `.notice-advisory` gets `<b>{{t "Note:"}}</b> `. Add `"Error:"` → `"エラー："` and `"Note:"` → `"注意："` to `ja.go` if they aren't there.
- [ ] **Step 6: Run.** Run `cd platform && go test ./...`, then `scripts/build-tailwind.sh`, then `npm run test:print`. Expected:
  - `TestTemplatesArePrintedPage` PASSes, and so does every other Go test.
  - `no-hue` and `no-uppercase` PASS on every page and theme.
  - Only `no-hscroll` still fails.
- [ ] **Step 7: Commit.**
```bash
git add platform/templates platform/internal platform/static/tailwind.css
git commit -m "fix(platform): printed-page leftovers: hue utilities, glyphs, uppercase, notice words"
```

### Task 5: No width caps, no sideways scroll

**Files:**
- Modify: the page-level wrappers, which are the first `<div>` of the page with `max-w-*xl`:
  - `advertiser/wallet.html:2`
  - `publisher/earnings.html:2`
  - `publisher/ledger.html`
  - `publisher/trusted.html`
  - `publisher/site-observations.html`
  - `org/members.html:2`
  - `admin/sites.html:2`, `admin/users.html`, `admin/requests.html`, `admin/fraud.html`
  - `admin/billing*.html`
  - `help.html:2`
- Modify: any table wrapper that still overflows at 1280px after that (the `no-hscroll` detail names it)

- [ ] **Step 1: Remove page-level caps.** On the listed wrappers, remove `max-w-*xl` and keep the other classes. Where removing it leaves an empty `class`, drop the attribute. `<main>` gets no new cap.

  Keep these caps:
  - inner blocks: `max-w-3xl` on `.page-sub` and campaign sentences, `max-w-2xl` on forms and notices
  - `help.html`'s prose column, if it is the inner one (not the page wrapper)
  - the auth pages (`login`, `setup`, `recover`, `request-account`, `guard-error`)
- [ ] **Step 2: Fix any remaining overflow.** If `no-hscroll` still names a table at 1280px, make its columns fit: let long text cells wrap (`whitespace-normal`, `break-words`), and drop fixed `w-*` or `min-w-*` on cells. Don't hide columns.
- [ ] **Step 3: Run.** Run `scripts/build-tailwind.sh`, then `npm run test:print`. Expected: `all printed-page checks passed`. Then run `cd platform && go test ./...` (PASS).
- [ ] **Step 4: Commit.**
```bash
git add platform/templates platform/static/tailwind.css
git commit -m "fix(platform): drop page width caps so tables never scroll sideways"
```

### Task 6: Creative designer chrome

**Files:**
- Modify: `platform/creative-designer/src/ui/tokens.ts:32-36` (`focus`, `focusMuted`, `focusBg`) and the `--cd-*` greys, which become r=g=b with the same luminance
- Modify: the canvas selection and handle drawing (find it with `grep -rn "tokens.focus\|focus\b" platform/creative-designer/src --include=*.ts`)
- Test: `platform/creative-designer/tests/` (vitest), `platform/creative-designer/scripts/layout-check.mjs`
- Modify: `platform/static/creative-designer.js(.map)` (rebuilt)

- [ ] **Step 1: Write the failing test.** `tests/tokens.test.ts` → `"designer chrome is neutral"`: for every entry in `tokens.ts`'s exported palette (both `dark` and `light`), parse the hex or rgb and assert `max−min ≤ 2`.
- [ ] **Step 2: Run it.** `cd platform/creative-designer && node_modules/.bin/vitest run tests/tokens.test.ts`. Expected: FAIL on `focus` `#2185ff` and the blue-grey steps.
- [ ] **Step 3: Implement.**
  - `focus` is ink: `#f5f5f5` in dark, `#1a1a1a` in light.
  - `focusMuted` is the same at 0.5 alpha.
  - `focusBg` is the field (`#292929` / `#e6e6e6`).
  - Other blue-grey steps become neutral, with luminance kept.
  - Canvas selection and handles draw a 1px white outer line, then the focus line, so they show on any image.
- [ ] **Step 4: Run.** Run `node_modules/.bin/vitest run` (all PASS), then `node_modules/.bin/tsc --noEmit`, then `npm run build && npm run fanout`. Then, with `npm run serve` running, `node scripts/layout-check.mjs <scratch>`: expected `all checks passed`.
- [ ] **Step 5: Commit.**
```bash
git add platform/creative-designer/src platform/creative-designer/tests platform/static/creative-designer.js platform/static/creative-designer.js.map
git commit -m "feat(designer): greyscale chrome; selection with a white halo"
```

### Task 7: Docs, screenshots, the user's review

**Files:**
- Modify: `DESIGN.md` (front-matter colour tokens, Overview, Colors, Typography, the forecast-strip section, Do/Don't)
- Modify: `.impeccable/design.json` (the same tokens and rules)

- [ ] **Step 1: DESIGN.md.**
  - Rewrite Colors as greyscale. "The Colour Is Data Rule" becomes "**The Ink Only Rule.** No hue in the dashboard; ad creatives are the only colour on the page."
  - Replace the JMA scale entry with the seven grey steps (both themes) and say that the dark scale runs light-for-more.
  - Typography: the "One Face Rule" becomes "**The Two Face Rule.** Mincho (BIZ UDPMincho 700) for page titles, section headings and the big figure; Gothic (BIZ UDPGothic) for everything you read or operate."
  - Strip section: grey cells, the halo on the now line.
  - Do/Don't: "Don't use `filter: grayscale` or colour utilities; use the tokens."
  - Mirror the same changes in `design.json`.
- [ ] **Step 2: Screenshots.** Run `npm run test:print -- /private/tmp/claude-501/-Users-hanishi-promovolve/<session>/scratchpad/print-shots`, plus the designer's `layout-light.png`/`layout-dark.png` from Task 6. Also take Campaigns at 390px: add a one-off `--mobile` flag to the script that sets 390×844 for `advertiser-forecast`.
- [ ] **Step 3: The user's review.** Share the screenshot paths. Stop until the user approves the look. If the projection's faded cells read like solid lighter steps, switch the projection to a diagonal hatch, as the spec allows: `background-image: repeating-linear-gradient(45deg, currentColor 0 1px, transparent 1px 4px)` in the step's grey, replacing `opacity-40`. Then re-run Task 1's checks.
- [ ] **Step 4: Final run and commit.** Run `cd platform && go test ./...`, `npm run test:print` and `npm test` (the date-picker suite). All must pass.
```bash
git add DESIGN.md .impeccable/design.json
git commit -m "docs: DESIGN.md for the printed page (Ink Only, Two Face)"
```
