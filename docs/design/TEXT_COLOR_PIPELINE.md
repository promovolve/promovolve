# Text Color Pipeline (How Creative Text Color Is Picked)

> **Status:** Documentation of current behavior (as built). Code references are
> to `platform/creative-designer/` (TypeScript designer) and `modules/`
> (Scala server) as of 2026-06-30.
>
> **Related:** [`book/src/format/brand-kit.md`](../../book/src/format/brand-kit.md),
> [`DESIGNER_DEV.md`](../../dev_docs/DESIGNER_DEV.md).

## One-line mental model

> Use the landing page's own text color **if it's legible on the LP's
> background**, otherwise auto-pick light-or-dark by background luminance — with
> a WCAG backstop over anything AI-generated, plus a manual picker and a live
> contrast warning on top.

Color selection is fundamentally a **client/designer concern**. The Scala server
never computes a color; it only forwards LP-extracted colors and nudges Gemini
via prompt text (see [§5](#5-server-side-scala)).

## Precedence (first match wins, per text item)

1. **Manual edit** — a color the author set in the props panel (or via a
   brand-kit color chip). Survives regen if it already passes contrast.
2. **Brand-kit `Text` color** (LP-extracted) — **only if** it clears WCAG AA
   (≥ 4.5:1) against the resolved background.
3. **Auto contrast pick** — a luminance-based palette off the background:
   dark bg → light text, light bg → dark text.
4. **`enforceContrast` backstop** — any generated item still failing AA is
   snapped to a legible palette pick (or the accent, for CTAs).

## 1. The baseline: contrast against the background

`pickContrast(bg)` chooses a 3-tier palette purely from the **background's
perceptual luminance** (Rec.709, threshold 0.5) —
`platform/creative-designer/src/color-contrast.ts:40-44`:

| Background | headline | sub | body |
|---|---|---|---|
| **Dark** (`lum < 0.5`) | `#ffffff` | `#d1d5db` | `#9ca3af` |
| **Light** (`lum ≥ 0.5`) | `#0a0a0b` | `#374151` | `#6b7280` |

- Tier is assigned by role/size — headline = strongest contrast, body = lowest.
- **Unparseable** colors (gradients, named colors) fall through to the **dark**
  palette (matching Gemini's "dark elegant gradient" intent).
- The hex parser handles `#rgb`, `#rrggbb`, **and `rgb()/rgba()`**
  (`color-contrast.ts:142-169`). The `rgb()` support is load-bearing: LP-extracted
  backgrounds often arrive as `rgb(17,17,17)`, and without it `pickContrast` was
  wrongly defaulting to the dark palette on light pages.

## 2. Brand-kit override (creatives made from a landing page)

`resolveLayoutColors(page, kit)` is the real entry point for auto-layout —
`color-contrast.ts:72-83`:

```ts
const kitBg   = kitColor(kit, "Background");
const bg      = kitBg ?? page.bg;
const auto    = pickContrast(bg);
const kitText = kitColor(kit, "Text");
const text    = kitText && contrastRatio(kitText, bg) >= WCAG_AA_NORMAL ? kitText : null;
const accent  = kitColor(kit, "Accent") ?? (typeof page.accent === "string" ? page.accent : "#c4a35a");
return { bg, headline: text ?? auto.headline, sub: text ?? auto.sub, body: text ?? auto.body, accent };
```

- **bg** ← kit `Background` (LP dominant) else `page.bg`.
- **text** ← kit `Text` (LP text color) **only if it clears WCAG AA 4.5:1**;
  otherwise discarded.
- **accent** ← kit `Accent` → `page.accent` → house gold `#c4a35a`.

> **Nuance:** when the LP `Text` color survives the gate, it overrides **all
> three** tiers — `headline`/`sub`/`body` collapse to that single color. You only
> get the distinct per-role tiers when the kit text fails the gate and the
> palette pick takes over.

`normalizePage` also forces the canvas bg to the kit background first, so the
contrast math and the rendered bg agree (`normalize.ts:48-51`); the default
collapsed layout then ships **copy only** (headline + body) via
`defaultLayoutForPage` (`normalize.ts:97-144`).

### Where the brand kit comes from

The kit is **DOM-extracted from the landing page by the Playwright LP
analyzer** (`LPWorker`, running on crawler-role nodes) — not from an LLM
(`modules/browser/src/main/resources/lp-analyzer.js`):

- `extractDominantColor()` — first usable element background (pure white skipped).
- `extractTextColor()` — `color` of the first `p`/main/article/body.
- `extractPalette()` — up to 6 hex swatches ordered by on-screen painted area.

`LPAnalyzer.scala` runs these via `page.evaluate(...)` into `LPAnalysisResult`,
forwarded through `AnalyzeLPResponse`. The editor's `buildBrandKitFromLP(data)`
(`platform/templates/advertiser/creative-editor.html`) orders them
`dominantColor, textColor, palette`, runs `_toHex()`, and names the roles
**`Background, Text, Accent, Brand, Color 5, Color 6`**. After analysis the
LP-derived kit **always** becomes the editor default
([`book/src/format/brand-kit.md`](../../book/src/format/brand-kit.md)).

Brand-kit load precedence (`brand-kit.ts:82-125`): server-injected
`window.__DESIGNER__.brandKit` → handoff `brandKitJson` → `localStorage` →
`EMPTY_KIT` (which ships **no colors** — "the palette comes from the LP").
`kitColor()` looks roles up case-insensitively by name (`brand-kit.ts:75-80`).

## 3. Contrast enforcement on generated layouts

`enforceContrast(items, bg, accent)` is a **post-filter** over any applied layout
(`color-contrast.ts:99-117`):

- Items already passing WCAG AA are **left untouched** (author-approved choices
  survive a regen).
- A failing item is replaced: **CTA** (`ctaTarget`) items prefer `accent` *if
  accent itself passes*, otherwise fall through; non-CTA items pick by
  `fontSize` — `≥ 8 → headline`, `≥ 4 → sub`, else `body`.

Invoked as `legibilizeItems()` on presets/templates/Gemini output
(`auto-layout.ts:245-247`; `template-apply.ts:159`).

## 4. Manual editing

The props-panel color field and the brand-kit color chips set a user-chosen
color. A **non-destructive** "low contrast" warning shows when the ratio drops
below 4.5:1 — `contrastWarningRow()` (`props-panel.ts:773-804`, wired at `:224`).
It never changes the color.

> **Per-format color model (2026-06-30).** Color follows a *master-group* rule
> (`propagateColor`, `state.ts`): the 16:9 wide master (`page.layout`, a
> tabless delivery artifact) plus **every
> collapsed banner bucket** (300×250, 728×90, …) form one master group that
> always shares the headline color. Banner buckets never hold an independent
> color — they inherit the expanded master both **on edit** (`propagateColor`)
> and **at generation/load** (`applyLayout` in `auto-layout.ts` mirrors the
> master onto a freshly auto-generated bucket, so it doesn't load with the
> preset's bg-derived black/white next to a brand-colored master). The expanded
> **Mobile** reader is **independent** — its color is set freely by the user and
> is never touched by a master-group edit (and vice-versa); there is no link
> toggle. (Template-apply `harmonizeTypography` still unifies color across *all*
> formats for a cohesive restyle — color remains in `TYPO_SYNC_KEYS` for that
> path only.)

## 5. Server-side (Scala)

The server **never picks a text color**. Two roles only:

1. **Forwarding LP colors** — `LPAnalyzer.scala` / `ApiModels.scala` /
   `EndpointRoutes.scala` pass `dominantColor`/`textColor`/`palette` verbatim.
2. **Nudging Gemini via prompt** — `LPExtractor.generateLayout`
   (`modules/core/src/main/scala/promovolve/creative/LPExtractor.scala:469-494,
   638-644`) injects the brand palette and states the contrast policy *in
   English*, hard-coding the **same hex values** as the TS palettes (dark bg →
   `#ffffff/#d1d5db/#9ca3af`, light bg → `#0a0a0b/#374151/#6b7280`), and notes
   "the post-processor will override it." That post-processor is the **client**
   `enforceContrast`. The endpoint returns Gemini's layout with **no server-side
   contrast post-processing** (`EndpointRoutes.scala:651-656`).

> **Reachability:** the Gemini layout path is a fallback that's currently
> effectively unreachable — "every mode has a preset" (`auto-layout.ts:57-89`).
> In practice colors come from **presets/templates + `resolveLayoutColors`**, not
> the LLM. The server path stays wired for future template gaps.

## Not involved in color selection

- **Saliency** (`saliency.ts`, U-2-Net) drives **image crop auto-suggest only** —
  no color, no text placement.
- **Scrim** (`scrim.ts`) is a manual gradient rect (`#000` @ 0.7 default) an
  author drops over an image to darken the region *behind* text. It improves
  effective contrast but is orthogonal to automatic color selection, and the LP
  dominant color isn't plumbed into it yet (`scrim.ts:15-17`).

## Key files

| Concern | File |
|---|---|
| Palette pick, WCAG ratio, enforce, resolve | `platform/creative-designer/src/color-contrast.ts` |
| Brand-kit type, roles, load precedence | `platform/creative-designer/src/brand-kit.ts` |
| Default layout + bg sync | `platform/creative-designer/src/normalize.ts` |
| Preset layouts | `platform/creative-designer/src/presets.ts` |
| Template role→kit mapping + enforce | `platform/creative-designer/src/template-apply.ts` |
| Auto-layout / legibilize wrapper | `platform/creative-designer/src/auto-layout.ts` |
| Contrast warning UI | `platform/creative-designer/src/ui/props-panel.ts` |
| LP DOM color extraction | `modules/browser/src/main/resources/lp-analyzer.js` |
| LP analysis → kit forwarding + Gemini prompt | `modules/core/src/main/scala/promovolve/creative/LPExtractor.scala` |
