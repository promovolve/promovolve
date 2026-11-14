# Designer Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Japanese advertiser sees the Creative Designer, its option values and the shared preview chrome in Japanese, with English as the fallback.

**Architecture:** A tiny `t()` helper in the Designer bundle reads `<html lang>` and looks English keys up in a `JA` map. Constant tables mark their keys with an identity `tk()` so a vitest drift test can find every key. Option values stay English; only displayed labels go through `t()`.

**Tech Stack:** TypeScript, Vite, Vitest (node environment, no jsdom) in `platform/creative-designer`; Vitest + jsdom in `platform/banner-component`.

**Spec:** `docs/superpowers/specs/2026-11-14-designer-localization-design.md`

## Global Constraints

- English text is the key; a missing key renders the key (English).
- Language comes from `document.documentElement.lang`: `ja` or `ja-*` (any case) → Japanese; anything else, or no `document` → English.
- Placeholders are `%s` / `%d`, filled in order. A Japanese value has the same count and order as its key.
- Translated text never becomes data: never pass `t(...)` output to state, a stored field, an option `value`, a CSS value, a preset id or a brand-kit role name.
- Japanese tone: です・ます for sentences; short noun labels for buttons. Terms match the dashboard (`platform/internal/i18n/ja.go`): クリエイティブ, プレビュー, 公開, 下書き, レイヤー.
- No new dependencies. Never `npx`; use `node_modules/.bin/<tool>` or `npm run`.
- After any `src` change: `npm run build && npm run fanout` in `platform/creative-designer`; `node scripts/fanout.mjs --check` must exit 0.
- Before starting: rebase `i18n/designer-ja` onto `origin/main` once PR #496 has merged (it adds the selection bar's Horizontal / Vertical labels).

## Review Focus

1. A label leaking into a stored value — picking 縦書き must store `vertical-rl`. Pinned in Task 2 (`optionEntries` test).
2. `<html lang="ja-JP">` or `"JA"` showing English. Pinned in Task 1.
3. A font family with quotes or a fallback list, e.g. `"Noto Sans JP", sans-serif` → `Noto Sans JP`. Pinned in Task 2.
4. An image `src` with a query string (`…/c58aed219f1018c6222.jpg?v=3`) still showing a hash. Pinned in Task 2.
5. A key built with a template literal (`` t(`Page ${n}`) ``) escaping the drift test. Pinned in Task 1 (drift test fails on it).

---

### Task 1: `t()`, `tk()` and the drift test

**Files:**
- Create: `platform/creative-designer/src/i18n.ts`
- Create: `platform/creative-designer/src/i18n/ja.ts`
- Test: `platform/creative-designer/tests/i18n.test.ts`
- Test: `platform/creative-designer/tests/i18n-drift.test.ts`

**Interfaces:**
- Produces:
  - `t(key: string, ...args: (string | number)[]): string`
  - `tk<T extends string>(key: T): T` — identity; marks a key stored in a table for later `t()`.
  - `setLang(lang: string | null): void` — test hook; `null` re-reads `<html lang>`.
  - `lang(): "en" | "ja"`
  - `JA: Record<string, string>` in `src/i18n/ja.ts`

- [ ] **Step 1: Write the failing tests** in `tests/i18n.test.ts`:

```ts
import { afterEach, describe, expect, it } from "vitest";
import { lang, setLang, t } from "../src/i18n";
import { JA } from "../src/i18n/ja";

afterEach(() => setLang(null));

describe("t", () => {
  it("returns the key in English", () => { setLang("en"); expect(t("Undo")).toBe("Undo"); });
  it("translates in Japanese", () => { setLang("ja"); expect(t("Undo")).toBe(JA["Undo"]); });
  it("falls back to the key when Japanese has no entry", () => { setLang("ja"); expect(t("zz-missing")).toBe("zz-missing"); });
  it("fills placeholders in order", () => { setLang("en"); expect(t("Page %d / %d", 2, 3)).toBe("Page 2 / 3"); });
  it("fills placeholders in Japanese", () => { setLang("ja"); expect(t("Page %d / %d", 2, 3)).toBe("2 / 3 ページ"); });
  it("resolves ja-JP and JA to Japanese, anything else to English", () => {
    setLang("ja-JP"); expect(lang()).toBe("ja");
    setLang("JA"); expect(lang()).toBe("ja");
    setLang("fr"); expect(lang()).toBe("en");
    setLang(null); expect(lang()).toBe("en"); // node: no document
  });
});
```

`JA` must contain `"Undo": "元に戻す"` and `"Page %d / %d": "%d / %d ページ"` for these to pass.

- [ ] **Step 2: Write the drift test** `tests/i18n-drift.test.ts`. It reads every `src/**/*.ts` except `src/i18n/ja.ts` and asserts:
  - `extractKeys(source)` finds keys in `t("…")`, `t('…')`, `tk("…")`, `tk('…')` (key may be followed by `,` or `)`).
  - **Every key is in `JA`** (message lists the missing keys).
  - **Every `JA` key is used** (message lists unused keys).
  - **`%s`/`%d` sequence is identical** between each key and its value.
  - **No template-literal keys:** the source has no `` t(` `` or `` tk(` ``.
  - A unit case on `extractKeys` itself: `extractKeys('t("A") + tk(\'B\', 1) + st("C")')` → `["A", "B"]` (must not match `st(`).

- [ ] **Step 3: Run tests, verify they fail**

Run: `cd platform/creative-designer && node_modules/.bin/vitest run tests/i18n.test.ts tests/i18n-drift.test.ts`
Expected: FAIL — `../src/i18n` not found.

- [ ] **Step 4: Implement `src/i18n.ts` and `src/i18n/ja.ts`** with the interfaces above. `lang()` caches the resolved language; `setLang` overrides or clears the cache. Placeholder fill: replace each `%s`/`%d` with the next arg via one regex pass. Seed `JA` with the two entries Step 1 needs.

- [ ] **Step 5: Use the two seed keys in `src`.** The drift test scans `src` only, so an entry used only by tests counts as unused. Wrap the Undo label in `src/ui/history-buttons.ts` as `t("Undo")` and the footer's page label in `src/ui/canvas-foot.ts` as `t("Page %d / %d", page, total)`.

- [ ] **Step 6: Run tests, verify they pass** (same command as Step 3), then `npm test`. Expected: PASS, full suite green.

- [ ] **Step 7: Commit**

```bash
git add src/i18n.ts src/i18n/ja.ts src/ui/history-buttons.ts src/ui/canvas-foot.ts tests/i18n.test.ts tests/i18n-drift.test.ts
git commit -m "Designer i18n: t(), tk() and a drift test (#498)"
```

### Task 2: Friendly labels for values

**Files:**
- Create: `platform/creative-designer/src/display-labels.ts`
- Modify: `platform/creative-designer/src/ui/props-panel.ts` (`selectField` at ~805; the writing, direction, v-align, fill mode, scrim edge and font fields)
- Modify: `platform/creative-designer/src/selection-model.ts` (`itemName`, ~84)
- Test: `platform/creative-designer/tests/display-labels.test.ts`

**Interfaces:**
- Consumes: `t`, `tk` from Task 1.
- Produces (all in `src/display-labels.ts`):
  - `optionEntries(options: string[], labels?: Record<string, string>): { value: string; label: string }[]` — label is `t(labels[value])` when present, else the value.
  - `WRITING_LABELS`, `DIRECTION_LABELS`, `VALIGN_LABELS`, `FILL_MODE_LABELS`, `SCRIM_EDGE_LABELS`: `Record<string, string>` of English keys wrapped in `tk()`.
  - `fontDisplayName(stack: string): string`
  - `FIELD_LABELS: Record<string, string>` (tk-wrapped).
  - `isHashName(name: string): boolean`
- `selectField(parent, label, value, options, onChange, wide?, read?, labels?)` — new last parameter `labels?: Record<string, string>`; builds options from `optionEntries`.

Exact labels (English key → Japanese value; add each pair to `JA`):

| Table | value → English | Japanese |
|---|---|---|
| WRITING | `horizontal-tb` → Horizontal, `vertical-rl` → Vertical | 横書き, 縦書き |
| DIRECTION | `ltr` → Left to right, `rtl` → Right to left | 左から右, 右から左 |
| VALIGN | `top` → Top, `middle` → Middle, `bottom` → Bottom | 上, 中央, 下 |
| FILL_MODE | `fill` → Fill (crop to box), `fit` → Fit (whole image) | 枠いっぱい（切り抜き）, 全体を表示 |
| SCRIM_EDGE | `bottom` → From bottom, `top` → From top, `left` → From left, `right` → From right, `radial` → Centre, `radial-edge` → Edges | 下から, 上から, 左から, 右から, 中央, 周囲 |
| FIELD | `tag` → Tag, `headline` → Headline, `sub` → Subhead, `body` → Body, `accent` → Accent, `caption` → Caption, `img` → Image | タグ, 見出し, 小見出し, 本文, アクセント, キャプション, 画像 |
| Fonts | `-apple-system, sans-serif` and `sans-serif` → System sans-serif, `serif` → System serif, `monospace` → System monospace | システム ゴシック, システム 明朝, システム 等幅 |

- [ ] **Step 1: Write the failing tests** in `tests/display-labels.test.ts` (use `setLang("ja")` / `setLang(null)` around them):
  - `optionEntries(["horizontal-tb","vertical-rl"], WRITING_LABELS)` in ja → `[{value:"horizontal-tb",label:"横書き"},{value:"vertical-rl",label:"縦書き"}]`.
  - `optionEntries(["100"], undefined)` → `[{value:"100",label:"100"}]`.
  - `fontDisplayName('"Noto Sans JP", sans-serif')` → `"Noto Sans JP"`; `fontDisplayName("Montserrat, sans-serif")` → `"Montserrat"`; in ja `fontDisplayName("-apple-system, sans-serif")` → `"システム ゴシック"`.
  - `isHashName("c58aed219f1018c6222.jpg")` → true; `isHashName("c58aed219f1018c6222.jpg?v=3")` → true; `isHashName("hero.jpg")` → false.
  - In ja, `itemName` of `{type:"image", src:"https://cdn.example/a/c58aed219f1018c6222.jpg?v=3"}` → `"画像"`; of `{type:"text", field:"headline"}` (no text) → `"見出し"`; of `{type:"text", text:"次の発見"}` → `"次の発見"`.

- [ ] **Step 2: Run, verify fail.** Run: `node_modules/.bin/vitest run tests/display-labels.test.ts`. Expected: FAIL — module not found.

- [ ] **Step 3: Implement** `src/display-labels.ts`. `isHashName`: strip a `?…` suffix, then match `/^[0-9a-f]{12,}(\.[a-z0-9]+)?$/i`. `fontDisplayName`: exact-match the known stacks first (via `t`), else first comma-separated family with surrounding quotes removed. In `itemName`: image with a hash name → `t("Image")`; text with no text but a field → `t(FIELD_LABELS[field] ?? field)`; the existing "Text" / "Image" / "Scrim" / "Circle" fallbacks go through `t()`.

- [ ] **Step 4: Wire the panel.** Add the `labels` parameter to `selectField`. Pass `WRITING_LABELS` to writing, `DIRECTION_LABELS` to direction, `VALIGN_LABELS` to v-align, `FILL_MODE_LABELS` to fill mode, `SCRIM_EDGE_LABELS` to edge. For the font field, pass a labels map built from `fontOptions(...)` with `fontDisplayName` (build it with a plain loop; no `tk` needed since values aren't keys). `onChange` still receives `select.value`.

- [ ] **Step 5: Run tests.** `node_modules/.bin/vitest run` — all green, including the drift test (add every new key to `JA`).

- [ ] **Step 6: Commit**

```bash
git add src/display-labels.ts src/ui/props-panel.ts src/selection-model.ts src/i18n/ja.ts tests/display-labels.test.ts
git commit -m "Designer i18n: friendly labels for option values, fonts and layers (#498)"
```

### Task 3: Translate the chrome (top bar, tool rail, selection bar, sizes, footer, layers, menus)

**Files (modify):** `src/ui/menu-bar.ts`, `src/ui/history-buttons.ts`, `src/ui/preview-button.ts`, `src/ui/save.ts`, `src/ui/tool-rail.ts`, `src/ui/selection-bar.ts`, `src/selection-model.ts` (`scopeLabel`, `typeName`), `src/ui/size-matrix.ts`, `src/ui/size-gate.ts`, `src/ui/canvas-foot.ts`, `src/ui/sidebar.ts`, `src/ui/layers.ts`, `src/ui/context-menu.ts`, `src/ui/confirm-delete.ts`, `src/ui/tokens.ts` (theme toggle label only, if it has one), `src/i18n/ja.ts`.
**Test:** `tests/i18n-drift.test.ts` (existing), `tests/selection-model.test.ts`, new `tests/chrome-labels.test.ts`.

**Interfaces:**
- Consumes: `t`, `tk`, `setLang` (Task 1); `itemName` labels (Task 2).
- Produces: `fanoutLegend(): { authored: string; generated: string; empty: string }` and `weightLabel(kb: number, max: number, over: boolean): string` exported from `src/ui/canvas-foot.ts` (pure, so they're testable).

Rules for this task:
- Constant label tables (`LABEL` in selection-bar, the canvas-foot status map, the tool rail's button names) wrap their values in `tk()` and are rendered with `t(...)`.
- Wording changes (spec §3): "Fanout · %d/%d" → "Sizes · %d/%d" (サイズ展開 · %d/%d); Authored → Edited (手動); Generated → Auto (自動); Empty → Empty (未作成); "Weight %s KB / %s KB" → "File size %s KB / %s KB" (容量 %s KB / %s KB); " · over budget" → " · over the limit" (・上限超過).
- Keyboard-shortcut hints inside labels (e.g. "Undo (⌘Z)") keep the shortcut outside the key: `` `${t("Undo")} (⌘Z)` ``.

- [ ] **Step 1: Write failing tests** in `tests/chrome-labels.test.ts`: in ja, `fanoutLegend()` → `{ authored: "手動", generated: "自動", empty: "未作成" }`; `weightLabel(38, 200, false)` → `"容量 38 KB / 200 KB"`; `weightLabel(250, 200, true)` → `"容量 250 KB / 200 KB・上限超過"`; in en, `weightLabel(38, 200, false)` → `"File size 38 KB / 200 KB"`. In `tests/selection-model.test.ts`, update the existing `scopeLabel` expectations to the new English (unchanged unless the wording changed) and add one ja case: nothing selected → `"バナーと %d ページ目"` filled as `"バナーと 1 ページ目"`.

- [ ] **Step 2: Run, verify fail.** `node_modules/.bin/vitest run tests/chrome-labels.test.ts tests/selection-model.test.ts` → FAIL.

- [ ] **Step 3: Wrap every visible string** in the files above and add each key with its Japanese value to `JA`. Verify nothing user-visible was missed with: `grep -nE "(textContent|title|placeholder|setAttribute\(\"aria-label\")\s*[=,(]\s*\"[A-Z]" src/ui/<file>.ts` per file — each hit must be inside `t(`.

- [ ] **Step 4: Run all tests.** `node_modules/.bin/vitest run` → PASS (drift test green).

- [ ] **Step 5: Commit**

```bash
git add src/ui src/selection-model.ts src/i18n/ja.ts tests
git commit -m "Designer i18n: translate the editor chrome (#498)"
```

### Task 4: Translate the panels and the animation presets

**Files (modify):** `src/ui/props-panel.ts`, `src/ui/animation-panel.ts`, `src/ui/banner-config-panel.ts` (incl. `WEIGHT_LABELS`), `src/ui/page-bg-panel.ts`, `src/ui/brand-kit-chips.ts`, `src/animation-presets.ts` (`label`s → `tk()`), `src/i18n/ja.ts`.
**Test:** `tests/animation-presets.test.ts` (existing), drift test.

**Interfaces:** Consumes `t`, `tk`. Preset `label` fields stay English keys (rendered with `t(preset.label)`); preset `id`s unchanged.

- [ ] **Step 1: Add a failing test** to `tests/animation-presets.test.ts`: every `ANIMATION_PRESETS` and `EXIT_PRESETS` `label` has a `JA` entry (e.g. `"Fade in"` → `"フェードイン"`, `"Rise"` → `"浮き上がり"`, `"Pop"` → `"ポップ"`, `"Stamp"` → `"スタンプ"`, `"From the left"` → `"左から"`).
- [ ] **Step 2: Run, verify fail.** `node_modules/.bin/vitest run tests/animation-presets.test.ts` → FAIL.
- [ ] **Step 3: Wrap every visible string** in the files above (section headers like Properties / Transform / Text / Typography / Layout / Image / Edge Effects / Scrim / Layers / Animation, field labels like content / color / weight / font size / line height / align / drop shadow / corner radius / strength, hints and warnings), with `JA` entries. Section labels that are also used as keys for open/closed state must keep the English string for state and pass `t()` only to the displayed header.
- [ ] **Step 4: Run all tests.** `node_modules/.bin/vitest run` → PASS.
- [ ] **Step 5: Commit**

```bash
git add src/ui src/animation-presets.ts src/i18n/ja.ts tests
git commit -m "Designer i18n: translate the panels and animation presets (#498)"
```

### Task 5: Translate the modals and remaining messages

**Files (modify):** `src/ui/asset-modal.ts`, `src/ui/crop-modal.ts`, `src/ui/formula-modal.ts`, `src/ui/remove-bg-modal.ts`, `src/ui/template-modal.ts`, `src/ui/brand-kit-modal.ts`, `src/ui/image-drop.ts`, `src/ui/thumbnail.ts`, plus any toast / confirm / error text found by `grep -rnE "toast|confirm\(|alert\(|new Error\(\"[A-Z]" src --include=*.ts` that reaches the user, `src/i18n/ja.ts`.
**Test:** drift test.

- [ ] **Step 1: Wrap every visible string** in those files; server-provided layout template names/descriptions in `template-modal.ts` are NOT wrapped (out of scope). Add `JA` entries.
- [ ] **Step 2: Sweep check.** `grep -rnE "\"[A-Z][a-z]+( [a-z]+)*[.…!?]?\"" src/ui | grep -v "t(\"\|tk(\""` — every remaining hit is a non-displayed value (CSS, ids, event names) or a server-origin name; note any intentional leftovers in the PR description.
- [ ] **Step 3: Run all tests.** `node_modules/.bin/vitest run` → PASS.
- [ ] **Step 4: Commit**

```bash
git add src tests
git commit -m "Designer i18n: translate the modals and messages (#498)"
```

### Task 6: Preview chrome in the banner component

**Files:**
- Modify: `platform/banner-component/src/magazine-preview.ts` (label at ~148; toggles at ~437)
- Test: `platform/banner-component/tests/magazine-preview-labels.test.ts`

**Interfaces:** Produces `previewLabels(lang: string): { ad: string; pc: string; mobile: string }` exported from `magazine-preview.ts`; `ja*` → `{ ad: "広告", pc: "PC", mobile: "モバイル" }`, else `{ ad: "Advertisement", pc: "PC", mobile: "Mobile" }`. The component calls it with `document.documentElement.lang`.

- [ ] **Step 1: Write the failing test** (`// @vitest-environment jsdom` not needed — pure): `previewLabels("ja-JP")` → ja object; `previewLabels("")` → en object.
- [ ] **Step 2: Run, verify fail.** `cd platform/banner-component && node_modules/.bin/vitest run tests/magazine-preview-labels.test.ts` → FAIL.
- [ ] **Step 3: Implement** `previewLabels` and use it for the "Advertisement" label and the PC / Mobile toggles.
- [ ] **Step 4: Run all banner tests.** `node_modules/.bin/vitest run` → PASS; `node_modules/.bin/tsc --noEmit -p .` → clean.
- [ ] **Step 5: Commit**

```bash
git add src/magazine-preview.ts tests/magazine-preview-labels.test.ts
git commit -m "Preview: Advertisement / PC / Mobile follow the page language (#498)"
```

### Task 7: Harness switch, bundle, visual review, PR

**Files:**
- Modify: `platform/creative-designer/index.html` (dev harness)
- Modify: `platform/static/creative-designer.js`, `platform/static/creative-designer.js.map` (rebuilt)

- [ ] **Step 1: Harness `?lang=` switch.** In `index.html`, before the bundle loads: `document.documentElement.lang = new URLSearchParams(location.search).get("lang") || "en";`.
- [ ] **Step 2: Build and verify the bundle.** `cd platform/creative-designer && node_modules/.bin/tsc --noEmit -p . && npm test && npm run build && npm run fanout && node scripts/fanout.mjs --check` → all exit 0.
- [ ] **Step 3: Visual pass.** `npm run serve`, open `http://localhost:5173/?lang=ja` in Chrome. Walk: top bar, tool rail, size strip and legend, footer, select text (selection bar incl. 横書き/縦書き, Properties sections, Typography font names, Layout options), select an image (fill mode), add a scrim (edge options), Layers names, Animation tab presets, each modal (Replace, Crop, Remove background, Formula, Layout templates, Brand kit), Preview (広告 / モバイル). Screenshot each; list any English left. Then `?lang=en` once to confirm English is intact. Hand the screenshots to the user; wait for approval before pushing.
- [ ] **Step 4: Commit, push, PR.**

```bash
git add index.html ../static/creative-designer.js ../static/creative-designer.js.map
git commit -m "Designer i18n: harness lang switch and rebuilt bundle (#498)"
git push -u origin i18n/designer-ja
gh pr create --base main --title "Designer: Japanese localization" --body "<plain English summary>  Closes #498"
```
