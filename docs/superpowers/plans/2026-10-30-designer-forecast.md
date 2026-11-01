# Creative designer in the Forecast world: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the creative designer in the Forecast visual system, share the dashboard's theme setting, and regroup its tools into a labelled left rail, a selection bar and a values-only right panel.

**Architecture:** The designer keeps its structure: a frame of host elements built by `renderShell` in `src/index.ts`, panels that `mountX(host, store)` and `update(state)`, and colours as `--cd-*` CSS variables from `src/ui/tokens.ts`. Decisions that need tests (which actions a selection gets, what the panel shows, theme migration) go into pure functions in a new `src/selection-model.ts` and into `tokens.ts`, because vitest runs in plain Node with no DOM. Everything visual is checked with one Playwright script against the Vite harness, then by the user.

**Tech Stack:** TypeScript, Vite, vitest (Node env, no jsdom), Playwright (already a devDependency), Go `html/template` for the page shell.

**Spec:** `docs/superpowers/specs/2026-10-30-designer-forecast-design.md`

## Global Constraints

- Work on branch `feat/forecast-designer` (stacked on `feat/forecast-pub-admin`). Issue #322.
- Run all `npm` commands in `platform/creative-designer`.
- Every px size in the spec is a pre-zoom CSS value. Chrome hosts get `zoom: 1.2` (`UI_SCALE` in `src/index.ts`); keep that, and add the new rail and selection-bar hosts to the zoomed list. Keep chrome heights on multiples of 5 (the comment above `UI_SCALE` explains why).
- Hue only for data and alerts. The only accent is Focus Blue `#2185ff`. No `amber` identifier may remain in `src/`.
- `ink400` (Mark) is never used for text.
- Never change anything rendered inside the advertiser's banner, the saved data format, keyboard shortcuts or the right-click menu.
- The editor stays English-only. New strings are plain English, sentence case.
- Do not commit any visual change until the user has reviewed it on the Vite harness (Task 6). Tasks 1–5 commit on the branch locally and are not pushed.

### Spec corrections found while planning (already applied to the spec)

- There is no "Regenerate all" function; the canvas-header comment that mentions it is stale. The top bar gets only **Regenerate this size**.
- Saving is two buttons today: **Save draft** (`mountDraftButton`) and **Publish** (`mountPublishButton`). The top bar keeps both; Publish is the one ink-filled primary button.
- Rail width is **90px**, not 92, so it renders at an integer 108px under the 1.2 zoom.
- Tools other than Select are one-shot actions, not modes. Select is the only tool with an active state, and it shows that state permanently.
- `MIN_W` is **850**, not 840: the canvas is about 376px wide at today's minimum, and the new rail plus panel take (90 + 300) × 1.2 = 468px.

## Review Focus

1. **Theme storage that throws** (private browsing, blocked storage): the designer still loads, on Auto, and nothing throws. Test in Task 1.
2. **Mixed multi-selection** (an image and a text item together): only the shared actions appear (arrange, Group, Delete), never Crop or Align. Test in Task 3.
3. **A selection that is already one group:** Group is not offered again. Test in Task 3.
4. **A stale selection index** (an index past the end of the current layout, for example right after a delete or a page switch): the bar and the scope line ignore it instead of crashing. Test in Task 3.
5. **A very long creative or campaign name:** the top bar stays one line at its height and truncates with an ellipsis, and the full name is in the `title`. Check in Task 6's script.

---

### Task 1: Forecast tokens, font, corners and the shared theme setting

**Files:**
- Modify: `src/ui/tokens.ts`
- Modify: every `src/**/*.ts` that uses `tokens.amber`, `tokens.amberMuted` or `tokens.amberBg` (rename only)
- Modify: `platform/templates/advertiser/creative-design.html`
- Modify: `index.html` (Vite harness)
- Test: `tests/theme-mode.test.ts`

**Interfaces:**
- Produces, from `src/ui/tokens.ts`:
  - `THEME_KEY = "promovolve.dashboard.theme"` and `LEGACY_THEME_KEY = "promovolve.designer.theme"` (exported consts)
  - `type ThemeStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">`
  - `migrateThemeKey(storage: ThemeStorage): void`
  - token keys `field`, `focus`, `focusMuted`, `focusBg` (replacing `amber`, `amberMuted`, `amberBg`); all other keys unchanged
  - `getMode`, `applyMode`, `cycleMode`, `initTheme`: same signatures as today, now on `THEME_KEY`; `initTheme()` calls `migrateThemeKey(localStorage)` first, inside a try/catch

- [ ] **Step 1: Write the failing tests** in `tests/theme-mode.test.ts`, using a `Map`-backed fake `ThemeStorage`:
  - `copies a legacy choice when the dashboard key is absent`: legacy `"dark"` → dashboard key `"dark"`, legacy key removed
  - `keeps the dashboard choice when both exist`: dashboard `"light"`, legacy `"dark"` → dashboard stays `"light"`, legacy removed
  - `ignores an invalid legacy value`: legacy `"purple"` → dashboard key absent, legacy removed
  - `does nothing when neither key exists`: storage still empty
  - `survives storage that throws`: every method throws → `migrateThemeKey` does not throw
  - `exposes focus tokens and no amber`: `tokens.focus === "var(--cd-focus)"`, `tokens.field === "var(--cd-field)"`, `Object.keys(tokens).some(k => k.startsWith("amber")) === false`

- [ ] **Step 2: Run them and see them fail**

Run: `npx vitest run tests/theme-mode.test.ts`
Expected: FAIL (`migrateThemeKey` is not exported)

- [ ] **Step 3: Implement in `src/ui/tokens.ts`**
  - Replace the `COLORS` values with the spec's colour table (Visual system → Colour tokens), rename the amber keys, and add `field`.
  - `sans: '"BIZ UDPGothic", system-ui, sans-serif'`; `mono: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'`; `r4`, `r6`, `r8` all `2`.
  - Point the theme functions at `THEME_KEY`. Add `migrateThemeKey`, wrapped in its own try/catch.
  - Rename every `tokens.amber*` call site to the matching `tokens.focus*`. Run `npx tsc --noEmit` to catch any you missed.

- [ ] **Step 4: Run the tests and see them pass**

Run: `npx vitest run`
Expected: all PASS, including the existing suites

- [ ] **Step 5: Update the page shell and the harness**
  - In `creative-design.html` `<head>`: add `<link rel="stylesheet" href="{{asset "fonts/biz-udpgothic.css"}}">`. Add an inline script that reads `promovolve.dashboard.theme` (falling back to `promovolve.designer.theme`, then Auto) and sets `document.documentElement.dataset.cdTheme` plus the `<html>` background (`#ffffff` light, `#111519` dark) before the bundle loads. Replace the `#0a0a0b` body background with `background: var(--cd-ink900)`.
  - In `index.html` (harness): link `../static/fonts/biz-udpgothic.css`. If Vite refuses to serve it from outside its root, add `server: { fs: { allow: [".."] } }` to `vite.config.ts`.

- [ ] **Step 6: Verify the build and the Go side**

Run: `npm run build && npm run fanout && (cd .. && go test ./...)`
Expected: build succeeds, Go tests PASS

Run: `grep -rn amber src | wc -l`
Expected: `0`

- [ ] **Step 7: Commit**

```bash
git add src tests/theme-mode.test.ts index.html vite.config.ts ../templates/advertiser/creative-design.html ../static
git commit -m "feat(designer): Forecast tokens, BIZ UDPGothic and the shared theme setting (#322)"
```

---

### Task 2: Move hard-coded chrome colours onto tokens; restyle the size strip

**Files:**
- Modify: the 18 files outside `tokens.ts` with literal colours (`grep -rlE '#[0-9a-fA-F]{6}\b|rgba?\(|oklch\(' src/ui src/render`)
- Modify: `src/ui/size-matrix.ts`
- Modify: every text use of `tokens.ink400` and every use of `tokens.warn`

**Interfaces:**
- Consumes: the Task 1 token keys.
- Produces: nothing new.

- [ ] **Step 1: Sort every literal colour** into the spec's three groups (Visual system → Hard-coded colours). Chrome moves to the nearest token by role. Shadows and dim layers stay as neutral black at low opacity. Banner content, swatches and new-item defaults stay as they are.

- [ ] **Step 2: Fix the Mark and Advisory uses.** Any `color: ${tokens.ink400}` on text becomes `tokens.ink300`. Each `tokens.warn` used as a tag becomes a `tokens.warn` fill with `tokens.ink100` text (Ink on Advisory Yellow). Used as plain text, it becomes `tokens.ink100` with a 3px `tokens.warn` marker beside it.

- [ ] **Step 3: Restyle the size strip.** Chips get square corners and black-and-grey fills. The active chip gets a 2px `tokens.ink100` bottom border instead of the amber fill. The fanout summary, progress bar, thumbnails, status dots, nicknames, divider and scrolling stay as they are.

- [ ] **Step 4: Set the type scale in the chrome.** Body text is 13px and labels are 12px. Numbers (sizes, coordinates, percentages, zoom) get `font-variant-numeric: tabular-nums`. Text inside the banner is not touched.

- [ ] **Step 5: Verify**

Run: `npx vitest run && npx tsc --noEmit && npm run build`
Expected: all PASS

Run: `grep -rnE 'oklch\(' src/ui src/render | grep -v tokens.ts`
Expected: no output. Any remaining `#hex` or `rgba(` hits are only shadows, dim layers or banner content.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "feat(designer): chrome colours on Forecast tokens, size strip restyled (#322)"
```

---

### Task 3: The selection model (pure, tested)

**Files:**
- Create: `src/selection-model.ts` (no DOM access)
- Modify: `src/ui/layers.ts` (import `itemName` instead of defining it)
- Test: `tests/selection-model.test.ts`

**Interfaces:**
- Consumes: `DesignerState`, `currentLayout(state)` from `src/state.ts`, `isFormula(item)` from `src/formula.ts`.
- Produces, from `src/selection-model.ts`:
  - `type SelectionAction = "replace" | "crop" | "removeBackground" | "editFormula" | "alignLeft" | "alignCenter" | "alignRight" | "bringToFront" | "bringForward" | "sendBackward" | "sendToBack" | "group" | "delete"`
  - `selectedItems(state: DesignerState): LayoutItem[]`: the items of `currentLayout(state)` at `state.selectedItemIdxs`, skipping indexes out of range
  - `selectionActions(state: DesignerState): SelectionAction[]`, always in the order the type lists them
  - `panelScope(state: DesignerState): "banner" | "item"`: `"item"` when `selectedItems(state)` is not empty
  - `scopeLabel(state: DesignerState): string`
  - `itemName(item: LayoutItem): string`: moved verbatim from `src/ui/layers.ts:420`
  - `typeName(item: LayoutItem): string`: `"Formula"` when `isFormula(item)`, `"Scrim"` for a rect with `scrim`, otherwise `"Text"`, `"Image"`, `"Shape"`

Rules for `selectionActions`:
- none selected → `[]`
- one image that is not a formula → `replace, crop, removeBackground`, then the arrange four, then `delete`
- one formula → `editFormula`, the arrange four, `delete`
- one text item → `alignLeft, alignCenter, alignRight`, the arrange four, `delete`
- one rect, scrim or circle → the arrange four, `delete`
- two or more → the arrange four, `group` (left out when every selected item has the same non-empty `groupId`), `delete`

Rules for `scopeLabel`:
- banner scope → `` `Banner and page ${state.pageIdx + 1}` ``
- one item → `` `${typeName(item)} · ${itemName(item)}` ``
- several → `` `${n} items` ``

- [ ] **Step 1: Write the failing tests** in `tests/selection-model.test.ts`. Build states with `initialState`, `addItem` and `setSelection` as `tests/zorder.test.ts` does. Write one test per rule above, plus:
  - `mixed image and text selection gets only shared actions`: equals `["bringToFront","bringForward","sendBackward","sendToBack","group","delete"]`
  - `an already-grouped selection is not offered group`
  - `ignores a stale selection index`: `setSelection(state, [99])` on a two-item layout → `selectionActions` is `[]`, `panelScope` is `"banner"`, and `scopeLabel` does not throw
  - `scope label for nothing selected on page 2` equals `"Banner and page 2"`

- [ ] **Step 2: Run them and see them fail**

Run: `npx vitest run tests/selection-model.test.ts`
Expected: FAIL (module not found)

- [ ] **Step 3: Implement `src/selection-model.ts`**, and change `src/ui/layers.ts` to import `itemName` from it.

- [ ] **Step 4: Run all tests and see them pass**

Run: `npx vitest run`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add src/selection-model.ts src/ui/layers.ts tests/selection-model.test.ts
git commit -m "feat(designer): selection model for the selection bar and panel scope (#322)"
```

---

### Task 4: The new frame: top bar, left rail, selection bar and footer

**Files:**
- Modify: `src/index.ts` (`renderShell`, `Shell`, mounting, `UI_SCALE` host list, the subscriber)
- Modify: `src/ui/menu-bar.ts` (becomes the top bar)
- Create: `src/ui/tool-rail.ts` (from `src/ui/toolbar.ts`, which is deleted)
- Create: `src/ui/selection-bar.ts`
- Modify: `src/ui/canvas-foot.ts`
- Delete: `src/ui/canvas-header.ts`, `src/ui/align-toolbar.ts`, `src/ui/toolbar.ts`
- Modify: `tests/infer-writing-mode.test.ts` (import from `../src/ui/tool-rail`)

**Interfaces:**
- Consumes: `selectionActions`, `selectedItems`, `typeName`, `itemName` (Task 3); the theme functions (Task 1).
- Produces:
  - `Shell` = `{ topBarHost, railHost, matrixHost, selectionBarHost, canvasHost, canvasFootHost, sidebarHost }`
  - `mountMenuBar(host: HTMLElement, store: Store, ctx: DesignerContext): MenuBarHandle`: same signature as today
  - `mountToolRail(host: HTMLElement, store: Store): void`, and `inferWritingModeFromLayout` moved here unchanged
  - `mountSelectionBar(host: HTMLElement, store: Store): { update(state: DesignerState): void }`
  - `mountCanvasFoot(host: HTMLElement, store: Store): CanvasFootHandle`: same signature

- [ ] **Step 1: Rebuild `renderShell`** as a CSS grid matching the spec's Layout diagram:
  - top bar: 40px, full width
  - rail: 90px, below the top bar on the left
  - centre column: size strip, selection bar (40px), canvas, footer (30px)
  - right panel: 300px

  The canvas host keeps its current styles except for colours: the background becomes `tokens.ink800` (page colour) with `tokens.ink600` dots. Add `railHost` and `selectionBarHost` to the `UI_SCALE` list.

- [ ] **Step 2: Turn `menu-bar.ts` into the top bar.**
  - Background `tokens.field`.
  - Left: the breadcrumb. "Campaigns" is a link to `/advertiser/campaigns`, then `ctx.campaignName` when it is set, then `ctx.creativeName`. Mark the creative name with `data-cd-crumb-creative`. Names truncate with an ellipsis and carry the full text in `title`. The Draft pill becomes a plain tag with `tokens.ink700` fill, `tokens.ink300` text and the text "Draft". The "pv" gradient badge is removed.
  - Right: undo and redo (`mountHistoryButtons`), then Regenerate this size (moved from `canvas-header.ts` with its enable/disable logic and tooltip), then `mountPreviewButton`, `mountDraftButton`, and `mountPublishButton` styled as the primary, then the theme button.
  - The theme button is a text button: "Theme: Auto", "Theme: Light" or "Theme: Dark".

- [ ] **Step 3: Create `tool-rail.ts`** from `toolbar.ts`.
  - Vertical, with the group heading "Add": Select (V), Text (T), Image (I), Formula, Scrim. Then a divider and "Start from": Layout templates, Brand kit.
  - Each button is an icon above a text label, with the shortcut in small mono text. `aria-label` is the label.
  - Delete is removed. Select carries the active style: `tokens.ink700` background, a 3px `tokens.focus` left bar, a `tokens.focus` icon.
  - The click handlers are the existing ones, unchanged.

- [ ] **Step 4: Create `selection-bar.ts`.**
  - With nothing selected it shows the text "Nothing selected. Click an item on the banner, or add one from the left." in `tokens.ink300`.
  - Otherwise it shows `` `${typeName} · ${itemName}` `` in bold for one item, or `` `${n} items` `` for several. Then one labelled button per `selectionActions(state)` entry: Replace, Crop, Remove background, Edit formula, Align left, Align centre, Align right, Bring to front, Bring forward, Send backward, Send to back, Group, Delete. Delete is pushed to the right end.
  - Each button calls exactly what the old control called:
    - Replace → `openAssetModal(store)`
    - Crop → `openCropModal(store, idx, item)`
    - Remove background → `openRemoveBgModal(store, idx, item)`
    - Edit formula → `openFormulaModal(store, { idx, item })`
    - Align → the `updateItem` commit from `align-toolbar.ts:39`
    - Arrange → `store.commit(bringToFront(store.state))` and the other three, as in `buildMenu` in `render/overlay.ts`
    - Group → `store.commit(groupSelection(store.state))`
    - Delete → `commitDeleteSelection(store)`
  - The active text alignment uses the `tokens.focus` text colour.

- [ ] **Step 5: Extend `canvas-foot.ts`.**
  - Left: the page pager, moved from `canvas-header.ts` with its `switchPage` calls and its multi-page-only visibility. Then the current size as `` `${w}×${h} · ${label} · ${status}` ``, using the same `Mode` fields and status words as the active chip in `size-matrix.ts`.
  - The existing metrics, rulers and zoom stay.

- [ ] **Step 6: Wire `index.ts`.** Mount the top bar, rail, selection bar and footer. Remove the canvas header. Call `selectionBar.update(state)` in the subscriber and once at boot. Delete the three old files and update the test import.

- [ ] **Step 7: Verify**

Run: `npx tsc --noEmit && npx vitest run && npm run build`
Expected: all PASS

Run: `npm run serve`, open http://localhost:5173 and click through once: every rail button opens or adds what it did before, selecting an item fills the bar, and undo and redo work.
Expected: no console errors

- [ ] **Step 8: Commit**

```bash
git add -A src tests/infer-writing-mode.test.ts
git commit -m "feat(designer): top bar, labelled tool rail, selection bar and footer pager (#322)"
```

---

### Task 5: The right panel shows the selection's scope

**Files:**
- Modify: `src/ui/sidebar.ts`
- Modify: `src/ui/props-panel.ts`
- Modify: `src/ui/size-gate.ts`
- Modify: `src/index.ts` (subscriber, sidebar width)

**Interfaces:**
- Consumes: `panelScope`, `scopeLabel` (Task 3).
- Produces: `SidebarHandle` gains `update(state: DesignerState): void`.

- [ ] **Step 1: Add the scope to `sidebar.ts`.**
  - Add a scope line under the tabs: "Showing: " followed by `scopeLabel(state)` in bold.
  - `update(state)` hides `propsSection` when `panelScope(state) === "banner"`, and hides `bannerConfigSection` and `pageBgSection` when it is `"item"`. Layers stays visible in both.
  - Tabs get the ink underline on the active tab. Sections get a 2px `tokens.ink100` top rule with their heading instead of a box.
  - Width becomes 300px in `renderShell`.

- [ ] **Step 2: Remove the action buttons from `props-panel.ts`:** "Edit formula…" (around line 377), "Replace image…" (around 399), "Remove background…" (around 414), and the Crop/Edit button in the crop row (around 626). Keep the crop row's status text; it is a value. Remove any imports this leaves unused.

- [ ] **Step 3: Set `MIN_W = 850` in `size-gate.ts`**, and update its comment with the reasoning from Global Constraints.

- [ ] **Step 4: Wire `sidebar.update(state)`** into the subscriber and the boot sequence in `index.ts`.

- [ ] **Step 5: Verify**

Run: `npx tsc --noEmit && npx vitest run && npm run build`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "feat(designer): right panel shows banner or item settings by selection (#322)"
```

---

### Task 6: Mechanics check, the user's review, then the PR

**Files:**
- Create: `scripts/layout-check.mjs`

**Interfaces:**
- Consumes: the Vite harness on http://localhost:5173 (`npm run serve`), the `data-cd-idx` hitboxes, `data-cd-crumb-creative`, the button labels from Tasks 4–5.

- [ ] **Step 1: Write `scripts/layout-check.mjs`.** It uses Playwright Chromium at 1440×900 and exits non-zero on the first failed check. It checks:
  - **Rail:** buttons named Select, Text, Image, Formula, Scrim, Layout templates and Brand kit are visible without hovering.
  - **Empty state:** the selection bar shows "Nothing selected. Click an item on the banner, or add one from the left." The panel shows "Showing: Banner and page 1".
  - **Selecting:** click `[data-cd-idx]` hitboxes until the bar's bold text starts with "Image ·". Expect Replace, Crop and Remove background, and the panel switched to "Showing: Image · …". Do the same for "Text ·" and expect the three Align buttons.
  - **Two items:** shift-click a second hitbox. Expect "2 items" and Group.
  - **Theme carry-over:** before load, set only `promovolve.designer.theme = "dark"`. After load, `promovolve.dashboard.theme` is `"dark"`, the legacy key is gone, and `<html data-cd-theme="dark">`.
  - **Long name:** set the `[data-cd-crumb-creative]` text to 200 characters. The top bar is still 48px tall (40 × 1.2) and the page has no horizontal scroll.
  - **Size gate:** at 840px wide the size gate is visible; at 860px it is hidden.
  - **Screenshots:** light and dark, written to the directory given as the first argument.

- [ ] **Step 2: Run it**

Run: `npm run serve` in one terminal, then `node scripts/layout-check.mjs <scratchpad dir>`
Expected: `all checks passed`, plus two screenshots

- [ ] **Step 3: Commit the script**

```bash
git add scripts/layout-check.mjs
git commit -m "test(designer): Playwright layout check for the tool regroup (#322)"
```

- [ ] **Step 4: The user's review.** Tell the user the harness is ready on http://localhost:5173 in light and dark, and share the screenshot paths. **Stop until they approve the look.** Make any changes they ask for, re-run Steps 2 and 3, and ask again.

- [ ] **Step 5: Final checks after approval**

Run: `npx vitest run && npm run build && npm run fanout && make -C .. check`
Expected: all PASS

- [ ] **Step 6: Push and open the PR** (ask the user first). Base `feat/forecast-pub-admin`, title "Dashboard redesign, stage 5: the creative designer". Write the body in plain English, in short sentences, and end it with `Closes #322`.
