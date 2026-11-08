# Map (gatefold poster) format — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second creative format. A Map is one 9:16 poster, folded in thirds, that lifts out of the slot and unfolds downward. The advertiser picks it in the Creative Editor.

**Architecture:**
- `bannerConfig.format = "map"` flows through the existing opaque `bannerConfigJson` transport.
- **Delivery:** a new `src/map-fold.ts` in the banner component. Pure maths plus one DOM builder; `banner.ts` routes `_expand` and `_collapse` to it.
- **Designer:** a deterministic `posterLayoutFor` composes the three hand-off beats into one page on first open. Small UI gates handle Entrance, the pager, fold guides and video.
- **Editor:** gains a Format step and a one-image Poster step.
- **Go:** seeds the config from a new form field.

**Tech Stack:**
- TypeScript, vitest and vite (banner-component, creative-designer);
- Go `html/template` and Alpine (dashboard editor);
- Playwright (visual and hostile checks).

**Spec:** `docs/superpowers/specs/2026-11-08-map-gatefold-format-design.md`

## Global Constraints

- **Type:** `BannerConfig.format?: "map"`. Absent means Magazine. Anything other than `"map"` is treated as Magazine everywhere.
- **Map creative:** exactly one page, `pages[0]`. If more than one page arrives, delivery uses `pages[0]`.
- **No backend changes beyond the Go field:** no Scala change, no DB migration, no LPExtractor prompt change, and no Gemini call in the poster composer.
- **Motion constants,** copied from the approved prototype (`dev/map-check.html`):
  - overlap `0.45`;
  - spring ω `8` (critically damped, semi-implicit Euler, dt ≤ `0.032` s);
  - cover crease rest `178°`, inner creases `180°`;
  - lift phase `0.7` of panel 1's progress;
  - flick projection `0.15` s;
  - finger rest `80` ms zeroes the velocity;
  - `perspective(1400px)` inside the map transform;
  - EMA velocity factor `0.4`;
  - backdrop `rgb(11 11 16 / 0.75·t)`;
  - settle when |Δ| < `1e-3` and |v| < `1e-2`.
- **Tap slop:** `12` px, the same as the page CTA gate in `banner.ts`.
- **Unfold direction:** downward on every device (vertical Z-fold). Panel 1 is the top third.
- **Copy:** editor strings go through `{{t}}`, with Japanese entries in `platform/internal/i18n/ja.go`. Banner close label via `pickCloseLabel` ("Close" / 「閉じる」).
- **Builds:** don't commit built bundles (`platform/static/*.js`). Deploy builds them.
- **Shipping:** issue first. Branch `feat/map-gatefold-format`, then a PR with `Closes #N`. Never push to main. The user reviews the screenshots before the visual commits (Task 8).

## Review Focus

1. **Tap or drag on the open poster.** A press that moves under 12 px opens the LP once. A drag must never open it.
   *Test:* Task 1's `isTap`, plus the Task 8 Playwright drag probe asserting no popup.
2. **Close while still unfolding.** Escape or the close pill pressed mid-spring folds back from the current pose. It must not jump, and `onClosed` fires exactly once.
   *Test:* Task 3's `close-mid-open`.
3. **Resumed Map draft.** It must not be recomposed: the author's edits survive a reload.
   *Test:* Task 6's `does not recompose a resumed one-page map`.
4. **Old Magazine creatives untouched.** No `format` means the same `_expand` path, the Entrance row, the pager, and three pages.
   *Test:* Task 3's `magazine path unchanged` and Task 7's panel test.
5. **Rotation to landscape while open.** The map is removed instantly, with no flight against stale geometry, and `magazine-collapse` still fires.
   *Test:* Task 4's hostile short-viewport probe extended to the Map fixture.

---

### Task 0: Issue and branch

- [ ] **Step 1:** Confirm with the user, then run `gh issue create --title "Map (gatefold poster) creative format" --body "<plain-English summary of the spec + link>"`. Note `#N`.
- [ ] **Step 2:** From `main`, run `git checkout -b feat/map-gatefold-format`, then `git cherry-pick` the spec and plan commits from `docs/map-gatefold-spec` so they ride with the PR.

---

### Task 1: The `format` type and the map-fold maths

**Files:**
- Modify: `platform/banner-component/src/types.ts:446` (add the field after `coverPageIdx`)
- Create: `platform/banner-component/src/map-fold.ts` (maths section only in this task)
- Test: `platform/banner-component/tests/map-fold.test.ts`

**Interfaces (produces):**
```ts
// types.ts — BannerConfig
format?: "map";

// map-fold.ts
export const MAP_MAX = 2;                       // 3 panels → progress 0..2
export const MAP = { overlap: 0.45, omega: 8, coverRest: 178, innerRest: 180,
  lift: 0.7, flickS: 0.15, restMs: 80, dtMax: 0.032, perspective: 1400, slop: 12 } as const;
export function isMap(cfg: { format?: unknown }): boolean;             // cfg.format === "map"
export function hingeOpen(progress: number, i: number, max?: number, overlap?: number): number; // 0..1
export function hingeAngle(progress: number, i: number): number;      // degrees for hinge i
export function springStep(s: { p: number; v: number }, target: number, dt: number): { p: number; v: number };
export function springSettled(s: { p: number; v: number }, target: number): boolean;
export function releaseTarget(p: number, v: number, msSinceLastMove: number): "open" | "closed";
export function isTap(dx: number, dy: number): boolean;               // hypot ≤ MAP.slop
export function posterSize(vw: number, vh: number, fitPct: number): { w: number; h: number };
export function liftT(progress: number): number;                      // smooth(clamp(p / MAP.lift))
```

The algorithms are pinned by the prototype:
- `smooth(t) = t*t*(3-2t)`.
- `hingeOpen`: `L = min(max, 1+overlap)`, `gap = max>1 ? (max-L)/(max-1) : 0`, returns `smooth(clamp((p - i*gap)/L, 0, 1))`.
- `hingeAngle(p,i)`: `sign * rest * (1 - hingeOpen(p,i))`, where `rest = i===0 ? 178 : 180` and `sign = i%2===0 ? -1 : 1`. That's `rotateX`: negative folds away from the viewer for a top-edge hinge.
- `springStep`: `dt = min(dt, MAP.dtMax)`; `v += (ω²(target-p) - 2ωv)·dt`; `p += v·dt`. Clamp `p` to `[0, MAP_MAX]` and zero `v` at a bound.
- `releaseTarget`: `v' = ms > 80 ? 0 : v`; returns `p + v'·0.15 < MAP_MAX/2 ? "closed" : "open"`.
- `posterSize`: `h = min(900, vh·fit/100, vw·fit/100·16/9)`, `w = h·9/16`. This mirrors `sheetSizeFor("9/16", fit)` in px.

- [ ] **Step 1: Write the failing tests** in `tests/map-fold.test.ts`:
```ts
expect(isMap({ format: "map" })).toBe(true);
expect(isMap({})).toBe(false);
expect(isMap({ format: "Map" })).toBe(false);
expect(hingeOpen(0, 0)).toBe(0); expect(hingeOpen(2, 1)).toBe(1);
expect(hingeOpen(0.5, 1)).toBe(0);            // hinge 1 waits for its gap (0.55)
expect(hingeOpen(1, 0)).toBeGreaterThan(hingeOpen(1, 1)); // order
expect(hingeOpen(1, 1)).toBeGreaterThan(0);   // overlap: hinge 1 already moving at p=1
expect(hingeAngle(0, 0)).toBe(-178); expect(hingeAngle(0, 1)).toBe(180); expect(hingeAngle(2, 0)).toBeCloseTo(0);
// spring: from 0 to 2 settles inside 1.6 s of 16 ms steps, never exceeds 2
let s = { p: 0, v: 0 }; let max = 0;
for (let k = 0; k < 100; k++) { s = springStep(s, 2, 0.016); max = Math.max(max, s.p); }
expect(springSettled(s, 2)).toBe(true); expect(max).toBeLessThanOrEqual(2);
expect(springStep({ p: 0, v: -5 }, 0, 0.016)).toEqual({ p: 0, v: 0 }); // bound zeroes v
expect(springStep({ p: 1, v: 0 }, 2, 1).p).toBeCloseTo(springStep({ p: 1, v: 0 }, 2, 0.032).p); // dt clamp
expect(releaseTarget(0.9, 0, 10)).toBe("closed");
expect(releaseTarget(1.1, 0, 10)).toBe("open");
expect(releaseTarget(1.5, -6, 10)).toBe("closed");   // flick up folds it
expect(releaseTarget(0.9, -6, 120)).toBe("closed"); expect(releaseTarget(1.5, -6, 120)).toBe("open"); // rested finger: no flick
expect(isTap(8, 8)).toBe(true); expect(isTap(10, 10)).toBe(false);
expect(posterSize(390, 844, 97)).toEqual({ w: expect.closeTo(378.3, 1), h: expect.closeTo(672.5, 1) }); // width-bound
expect(posterSize(1280, 800, 88).h).toBeCloseTo(704); expect(posterSize(4000, 4000, 88).h).toBe(900);
expect(liftT(0)).toBe(0); expect(liftT(0.7)).toBe(1);
```
- [ ] **Step 2:** Run `cd platform/banner-component && node_modules/.bin/vitest run tests/map-fold.test.ts`. Expected: FAIL (module not found).
- [ ] **Step 3:** Implement the exports above in `src/map-fold.ts` and add `format?: "map"` to `BannerConfig`, with a doc comment pointing to the spec.
- [ ] **Step 4:** Run the same command. Expected: PASS. Also `npm run typecheck`: no errors.
- [ ] **Step 5: Commit:** `git add src/types.ts src/map-fold.ts tests/map-fold.test.ts && git commit -m "feat(banner): Map format type and fold maths"`

---

### Task 2: Extract the page stage builder (refactor, no behaviour change)

**Files:**
- Modify: `platform/banner-component/src/banner.ts:2531-2580` (the stage block inside `createPageFromLayout`)

**Interfaces (produces):**
```ts
// banner.ts, private method on the element class
private buildStage(page: Page, cfg: BannerConfig, opts: { video: boolean }): HTMLElement;
// returns the .paper-stage div: surface.stage background, (video + underlayCollapsedFrame when opts.video),
// texture, logo, layout items via layoutItemToNode, grain overlay. Cursor/containerType exactly as today.
```

- [ ] **Step 1:** Move the stage construction from `createPageFromLayout` into `buildStage`, unchanged. `createPageFromLayout` calls `this.buildStage(page, cfg, { video: true })`.
- [ ] **Step 2:** Run `node_modules/.bin/vitest run && npm run typecheck && npm run test:hostile`. Expected: everything passes, same as on `main`.
- [ ] **Step 3: Commit:** `git commit -am "refactor(banner): extract buildStage from createPageFromLayout"`

---

### Task 3: The map-fold DOM and the `banner.ts` integration

**Files:**
- Modify: `platform/banner-component/src/map-fold.ts` (add the DOM section)
- Modify: `platform/banner-component/src/banner.ts`:
  - `_expand` (677): branch at the top;
  - `_collapse` (765): branch at the top;
  - `onKeyDown` (663): Escape already calls `_collapse`, so nothing there.
- Test: `platform/banner-component/tests/map-fold-dom.test.ts` (vitest, happy-dom or jsdom per the existing vitest config; use fake timers and a stubbed `requestAnimationFrame`)

**Interfaces:**
- Consumes: Task 1 maths, Task 2 `buildStage`, plus `buildDogEar`, `paperBackStock` / `paperBackBackground` / `PAPER_BACK_BLEND` / `buildGrainOverlay` (`paper.ts`), `sheetFitPct`, and `pickCloseLabel`.
- Produces:
```ts
export interface MapFoldOptions {
  root: HTMLElement;            // empty element appended to the shadow root; map-fold styles it
  framed: boolean;              // true → root absolute inset:0 in the host frame; false → fixed inset:0
  face: () => HTMLElement;      // a fresh poster stage (called 3×); map-fold sizes + offsets it
  slotRect: () => DOMRect;      // collapsed slot, client coords; read once per open and once per close
  fitPct: number;
  backBackground: string;       // background for panel backs (paper stock)
  closeLabel: string;
  rtl: boolean;                 // close pill on the right, dog-ear corner mirrored
  reducedMotion: boolean;
  onLift(t: number): void;      // 0 at slot → 1 lifted; banner fades the collapsed frame to 1 - t
  onTap(): void;                // deliberate tap on the poster (isTap) → CTA
  onOpened(topPanelFace: HTMLElement): void; // fully open, once per open
  onFoldStart(): void;          // left fully-open (drag or close)
  onClosed(): void;             // after fly-back; root emptied
}
export interface MapFoldHandle { close(): void; destroy(): void; relayout(): void; }
export function openMapFold(o: MapFoldOptions): MapFoldHandle;
```

**DOM, as in the prototype:**
- **Structure:** nested `.map-panel` elements (preserve-3d, `transform-origin: top`), each with a `.map-face` and a `.map-back`. Backs use `rotateX(180deg)`, `backface-visibility: hidden`, the `backBackground` with `PAPER_BACK_BLEND`, and the contact `box-shadow: 0 1px 2px rgba(0,0,0,.3), 0 4px 12px rgba(0,0,0,.22)`.
- **Faces:** each face is `w × h/3` with overflow hidden. It holds `face()` sized `w × h`, positioned `top: -i·h/3`, plus `buildGrainOverlay()`. Faces with i > 0 get a crease shade whose opacity is `(1 - hingeOpen)·0.9`.
- **Map transform:** `translate(lerp(slot.x, cx), lerp(slot.y, cy)) + ox/oy scale(lerp(slot.w/w, 1)) perspective(1400px) translate(-ox,-oy)`, with `t = liftT(p)`, `(ox, oy)` at the cover centre, and coordinates relative to `root.getBoundingClientRect()`.
- **Backdrop:** `background-color` only, never opacity.
- **Pointer handling** on the root, with pointer capture:
  - Drag moves `p = start + dy / (h/3)`, with EMA velocity.
  - Release calls `releaseTarget`.
  - A tap (`isTap`) on the map calls `onTap`; a tap on the backdrop calls `close()`.
- **Close pill:** a `button` labelled `closeLabel`, top-left (right when RTL), styled like `.mobile-close-x`. It calls `close()`.
- **Reduced motion:** opacity fades, 200 ms in and out, with `p` jumping to the target.
- **Resize:** `resize` calls `relayout()`, which recomputes `posterSize` and re-renders at the current `p`.

**What `banner.ts` does**
- **`_expand`:** when `isMap(cfg)`, it does the bookkeeping as today (`_expanded`, the key listener, the CTA URL, the perf mark) and then:
  - creates `root`, and calls `openMapFold` with `face: () => this.buildStage(this.pagesData[0], cfg, { video: false })`;
  - `onLift`: sets `.banner` `style.opacity = String(1 - t)`;
  - `onTap`: the same CTA work as the page handler at `banner.ts:2652-2697` (pressed feedback is skipped, then `cta-click`, the pixel and `window.open`). Extract that work into `private fireCta(): void` and use it from both places;
  - `onOpened`:
    - if `this.canFold`, `buildDogEar(face, dogEarSize, this, 0, this.pinnedPageIdx >= 0, page.bg, rtl, true, this.consumeOverlayTease)` goes on the top face, pushed into `_dogEars`;
    - then `playItemAnimation` runs on every `[data-animation-from],[data-animation-to]` node in `root`, once per open;
  - `onFoldStart`: removes the dog-ear wrap and resets `_dogEars`;
  - `onClosed`: removes `root`, restores `.banner` opacity, and, when framed, dispatches `magazine-collapse`.
  - It dispatches `magazine-expand` with `detail: { ctaUrl, pageIndex: 0, totalPages: 1, format: "map" }`, and does **not** call `dispatchPageChanged`.
  - It skips `renderOverlay` and the framed hide timer.
- **`_collapse(instant)`:** when a map handle is live:
  - `instant` calls `destroy()` and then `onClosed` synchronously;
  - otherwise it calls `close()`;
  - when not framed, it dispatches `magazine-collapse` at once, as today.

  The rest of `_collapse` is skipped.

- [ ] **Step 1: Write the failing DOM tests** in `tests/map-fold-dom.test.ts`:
  - `builds 3 panels`: after `openMapFold`, `root.querySelectorAll(".map-panel")` has length 3, `.map-face` has length 3, and `face` was called 3 times.
  - `tap on poster calls onTap, drag does not`: pointerdown/up at the same point on `.map-face` gives `onTap` × 1. Down at y=100, then move to y=40, then up gives `onTap` × 0.
  - `close-mid-open`: open, advance 5 frames, `close()`, run frames until the spring settles; `onClosed` was called exactly once and `root.childElementCount === 0`.
  - `reduced motion`: with `reducedMotion: true`, open then run timers 250 ms; `onOpened` called; no hinge transform other than `rotateX(0deg)`.
- [ ] **Step 2:** Run `node_modules/.bin/vitest run tests/map-fold-dom.test.ts`. Expected: FAIL (`openMapFold` not exported).
- [ ] **Step 3:** Implement the DOM section of `map-fold.ts`, then the `banner.ts` branches and `fireCta`.
- [ ] **Step 4: Add the regression test `magazine path unchanged`** to the same file. Mount `<expandable-magazine-banner>` with three pages and no `format`, click `.banner`; `.overlay` exists and `.map-panel` doesn't. Then the same with `{format:"map"}`: `.map-panel` × 3 and no `.overlay`.
- [ ] **Step 5:** Run `node_modules/.bin/vitest run && npm run typecheck`. Expected: PASS.
- [ ] **Step 6: Commit:** `git commit -am "feat(banner): Map format delivery — gatefold unfold from the slot"`

---

### Task 4: Hostile suite, dev harness, prototype retired

**Files:**
- Create: `platform/banner-component/tests/hostile/fixtures/_seed-map.js` (a copy of `_seed.js` with one 9:16 page, three bands of literal text, `config` `{"format":"map"}`, and `landing-url` `_lp.html`)
- Create: `platform/banner-component/tests/hostile/fixtures/map.html` (the `quirks-mode.html` shape, loading `_seed-map.js`)
- Modify: `platform/banner-component/tests/hostile/run.mjs`:
  - add `"map"` to `FIXTURES`;
  - the probe's `expanded` check accepts `.map-panel` as well as `.overlay`;
  - extend `probeShortViewport` with a Map pass: open in portrait, rotate to 844×390, and assert no `.map-panel` remains.
- Modify: `platform/banner-component/dev/index.html` (a "Map" toggle next to the size switcher that mounts `PAGES.slice(0,1)` with `{...CONFIG, format: "map"}`)
- Delete: `platform/banner-component/dev/map-check.html`; remove its three ignores from `.impeccable/config.json`.

- [ ] **Step 1:** Add the fixture and probe changes. Run `npm run test:hostile`. Expected: `map` reports mounted, expanded, close visible, and no errors. The short-viewport Map pass reports closed.
- [ ] **Step 2:** Add the Map toggle to the dev harness, delete the prototype, and revert the impeccable ignores.
- [ ] **Step 3: Commit:** `git add -A tests/hostile dev .impeccable/config.json && git commit -m "test(banner): Map hostile fixture + dev harness; retire prototype"`

---

### Task 5: Go hand-off seeds the format

**Files:**
- Modify: `platform/internal/handler/pages.go`:
  - around 4914, read `format`;
  - at 4966, `creativeDesignData{…}` gains `BannerConfigJSON: formatBannerConfig(r.FormValue("format"))`.
- Test: `platform/internal/handler/creative_design_render_test.go`

**Interfaces (produces):**
```go
// formatBannerConfig returns the initial banner config for a fresh design:
// `{"format":"map"}` for format "map", "" for anything else.
func formatBannerConfig(format string) string
```

- [ ] **Step 1: Write the failing tests:**
  - `TestFormatBannerConfig`: `"map"` gives `{"format":"map"}`; `""`, `"magazine"`, `"MAP"` and `"map<script>"` give `""`.
  - `TestCreativeDesignCarriesMapFormat`: render with `BannerConfigJSON: formatBannerConfig("map")`; the output contains `format` and `map`.
- [ ] **Step 2:** Run `cd platform && go test ./internal/handler -run 'FormatBannerConfig|CarriesMapFormat'`. Expected: FAIL (undefined).
- [ ] **Step 3:** Implement `formatBannerConfig` and wire it into the fresh-design `creativeDesignData`. The resume and save-error paths keep their saved `bannerConfigJson`, so they don't change.
- [ ] **Step 4:** Run `go test ./internal/handler`. Expected: PASS.
- [ ] **Step 5: Commit:** `git commit -am "feat(dashboard): designer hand-off seeds Map format"`

---

### Task 6: Designer composes the poster on first open

**Files:**
- Modify: `platform/creative-designer/src/presets.ts` (add `posterLayoutFor` beside `presetLayoutFor`. This is where the presets live; the spec's "auto-layout.ts" meant "next to presetLayoutFor")
- Modify: `platform/creative-designer/src/index.ts:177-188` (the Map branch before the `templateId` re-layout)
- Test: `platform/creative-designer/tests/poster-layout.test.ts`

**Interfaces (produces):**
```ts
// presets.ts
export const POSTER_BAND = 100 / 3;   // fold lines at 33.333 / 66.667 (% of page height)
export const POSTER_CREASE_INSET = 4; // % kept clear on each side of a fold line
export function posterLayoutFor(beats: Page[], kit: BrandKit | null): LayoutItem[];
// index.ts (exported for the test)
export function composeMapPages(pages: Page[], hadLayout: boolean[], kit: BrandKit | null): Page[];
// → one page when bannerConfig is map AND !hadLayout[0]: { ...pages[0], layout: posterLayoutFor(pages, kit) };
//   otherwise pages unchanged (resume). Never more than pages[0] for map.
```

**Composition values:**
- **Image:** `{type:"image", field:"img", left:0, top:0, width:100, height:100}` first.
- **Scrims:** one scrim rect per band `k` (0..2), covering the band, with `scrim: { edge: k===0 ? "top" : "bottom", color: "#000000", strength: 0.55 }` and `fill: scrimGradient(that spec)`.
- **Text:** in band `k`, between `k·BAND + INSET` and `(k+1)·BAND − INSET`, at `left: 6, width: 88`:
  - a headline (`fontSize: 8`, bold, `#ffffff`, `textFit: "shrink"`);
  - a sub (`fontSize: 3.5`, `#ffffff`);
  - both in the font from `headlineFontOf(beats[0], kit)`.
- **Binding:** beat 0 uses `field: "headline"` / `field: "sub"`, so collapsed fanout and field sync keep working. Beats 1 and 2 use literal `text`.
- **CTA:** if `beats[2].ctaLabel` is set, a bold `#ffffff` text item at the bottom of band 2.
- **Contrast:** comes from the scrims, not `legibilizeItems`. `enforceContrast` measures against `page.bg`, which a full-bleed image hides. This is a deliberate deviation from the spec's wording; record it in the PR.

- [ ] **Step 1: Write the failing tests:**
  - `each beat sits inside its band, clear of the creases`: for every text item, take its index `k` and check `top ≥ k·BAND + INSET` and `top + height ≤ (k+1)·BAND − INSET`.
  - `beat 1 binds fields, beats 2–3 are literal`: the band-0 headline has `field: "headline"`; the band-1 and band-2 headlines have `text` equal to `beats[1].headline` and `beats[2].headline`.
  - `image is full-bleed and first`: `items[0]` equals `{left:0, top:0, width:100, height:100}`.
  - `every text item has a scrim behind it`: for each text item, a rect with `scrim` appears earlier and covers its box.
  - `CTA only when ctaLabel`: absent means no CTA item; `"Shop now"` means an item with `text: "Shop now"` in band 2.
  - `composeMapPages collapses three fresh pages to one`: 3 pages with no layout give length 1, a layout, and `pages[0].headline` preserved.
  - `does not recompose a resumed one-page map`: `[pageWithLayout]` with `hadLayout [true]` returns the same layout reference.
- [ ] **Step 2:** Run `cd platform/creative-designer && node_modules/.bin/vitest run tests/poster-layout.test.ts`. Expected: FAIL.
- [ ] **Step 3:** Implement `posterLayoutFor` and `composeMapPages`. In `index.ts`, parse `savedBannerConfig` *before* the template re-layout (move the block at 199-206 up), then `if (savedBannerConfig?.format === "map") pages = composeMapPages(pages, hadLayout, kit)`. Skip the `templateId` re-layout for Map.
- [ ] **Step 4:** Run `node_modules/.bin/vitest run && npm run typecheck`. Expected: PASS.
- [ ] **Step 5: Commit:** `git commit -am "feat(designer): compose the Map poster from the three beats"`

---

### Task 7: Designer UI for Map

**Files:**
- Modify: `platform/creative-designer/src/ui/banner-config-panel.ts:241` (`update`: `entRow.style.display = state.bannerConfig.format === "map" ? "none" : "flex"`)
- Modify: `platform/creative-designer/src/ui/canvas-foot.ts:150` (the pager is hidden when `state.bannerConfig.format === "map"`)
- Modify: `platform/creative-designer/src/render/overlay.ts:296` (after the background catcher, when the format is Map and `isMultiPage(state.mode)`, append two `div[data-cd-fold]` at `top: 33.333%` and `66.667%`: `position:absolute; left:0; right:0; border-top:1px dashed rgba(255,255,255,.55); pointer-events:none`, with a small "fold" label)
- Modify: `platform/creative-designer/src/ui/page-bg-panel.ts` (when Map: the video controls are disabled, with the note "Video backgrounds aren't available in Map format"; if `page.videoBg` is set, the warning "This video won't play in Map format — the page colour shows instead")
- Test: `platform/creative-designer/tests/map-ui.test.ts`

- [ ] **Step 1: Write the failing tests** (mount each panel with a minimal store, following existing UI tests such as `tests/printed-chrome.test.ts`):
  - `entrance row hidden for map, shown for magazine`;
  - `pager hidden for map`;
  - `fold guides drawn for map on the expanded mode only`: two `[data-cd-fold]` for Map plus mobile; none for Map at `300x250`; none for Magazine;
  - `video controls disabled for map`.
- [ ] **Step 2:** Run `node_modules/.bin/vitest run tests/map-ui.test.ts`. Expected: FAIL.
- [ ] **Step 3:** Implement the four gates.
- [ ] **Step 4:** Run `node_modules/.bin/vitest run && npm run typecheck`. Expected: PASS.
- [ ] **Step 5: Commit:** `git commit -am "feat(designer): Map UI — no Entrance, no pager, fold guides, no video"`

---

### Task 8: Creative Editor Format step and Poster step

**Files:**
- Modify: `platform/templates/advertiser/creative-editor.html`:
  - a hidden `format` input in `#designer-handoff` (line 4);
  - a new section 2 "Format" after line 105, renumbering 2→3 … 5→6;
  - section "Pages" (179) gets a Map variant;
  - Alpine state `format: 'magazine'`;
  - `pagesJsonStr()` (1116): in Map, each page's `img` comes from the single poster image.
- Modify: `platform/internal/i18n/ja.go` (Japanese for every new `{{t}}` string)

**Copy (EN, with JA):**
- "Format" → 「形式」
- "Magazine" → 「マガジン」
- "3 pages" → 「3ページ」
- "Map" → 「マップ」
- "One poster, folds in thirds" → 「1枚のポスターを三つ折りに」
- "— how the 3 panels persuade" → 「— 3つの面で伝える流れ」
- "Poster" → 「ポスター」
- "Top fold" → 「上の面」
- "Middle" → 「中の面」
- "Bottom" → 「下の面」
- "Click an image below to put it on the poster" → 「下の画像をクリックしてポスターに配置」

**Behaviour**
- **Format cards:** the same button pattern as the Story arc cards (`aria-pressed`, the selected border). Each carries a small glyph: three stacked rects for Magazine; one tall rect with two dashed rules for Map.
- **Poster step (Map):**
  - one `aspect-[9/16]` image slot, using `pageSlots[0].imageIdx` for the poster;
  - `assignImageToActive` always targets slot 0;
  - the three beats' headline and sub from `synthesizedPages[0..2]` beside it, under the three labels, with dashed rules between.
- **Hand-off:** `pagesJsonStr()` maps all three pages to `img = allImages[pageSlots[0].imageIdx].src` when `format === 'map'`.
- **Magazine:** the markup is unchanged.

- [ ] **Step 1:** Implement it, then run `cd platform && go test ./internal/... && go vet ./internal/handler`. Expected: PASS. The template tests parse every template.
- [ ] **Step 2: Visual check.** Run the dashboard locally (see `project_designer_dev_loops` / the `run` skill), open the Creative Editor with a test LP, pick Map, pick an image, and continue to the designer. Screenshot:
  - the Format step;
  - the Poster step;
  - the designer's expanded canvas with the fold guides;
  - the preview playing the map.

  Then run the Playwright captures: open, held drag poses at 0.25…1.75, closing and closed, at 390×844 and 1280×800, against `dev/index.html` in Map mode, plus the per-frame motion trace (adapt `map-motion.mjs` from the session scratchpad into `tests/hostile/map-motion.mjs`). Expected: a single smooth hump; no frame in the middle below 20% of peak.
- [ ] **Step 3: Drag-never-navigates probe.** Inside the motion script, drag from mid-poster up by one panel and release; assert no popup within 1 s.
- [ ] **Step 4: Stop for the user's visual review.** Send the screenshots and wait for approval.
- [ ] **Step 5: Commit:** `git commit -am "feat(dashboard): Creative Editor Format + Poster steps"`

---

### Task 9: Final verification and PR

- [ ] **Step 1:** Run `cd platform/banner-component && node_modules/.bin/vitest run && npm run typecheck && npm run test:hostile`, then `cd ../creative-designer && node_modules/.bin/vitest run && npm run typecheck && npm run build && npm run fanout -- --check`, then `cd .. && go test ./internal/...`. All must be green.
- [ ] **Step 2:** One real-iPhone check: `node_modules/.bin/vite --host` in banner-component, open `dev/index.html` in Map mode on the phone, and do tap, unfold, drag back, flick and dog-ear. Ask the user to do it if no device is available.
- [ ] **Step 3:** Push the branch, then `gh pr create`. Body: plain English, short sentences, `Closes #N`, and the poster-contrast deviation from Task 6.
- [ ] **Step 4:** Update memory:
  - `project_three_page_narrative_format.md` gets the Map one-page exception, decided by the user 2026-11-08;
  - a new `project_map_gatefold_format.md`.
