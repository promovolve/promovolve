# LP Brand-Kit Extraction → Designer Default

> **STATUS: IMPLEMENTED** (2026-05-31, commit `44b88ac6`). All changes below are
> live: `lp-analyzer.js` (extractPalette/extractFonts), `LPAnalyzer.scala`,
> `ApiModels.scala`/`ApiJsonFormats.scala`/`EndpointRoutes.scala`,
> `creative-editor.html` (_toHex/_snapFont/buildBrandKitFromLP),
> `template-apply.ts` (ROLE_TO_KIT_FONT_INDEX). Tests:
> `template-apply-fonts.test.ts`. The "system fonts only" limitation at the end
> of this doc was later lifted — the current font pipeline (per-creative CJK
> subsetting, weight-scoped R2 keys) is documented in the book:
> `book/src/format/brand-kit.md`.

**Goal:** When an advertiser analyzes a landing page (LP), extract a *brand kit*
from the page — a full color palette plus the page's font faces — and seed it as
the default brand kit that carries into the Designer view. Today only two colors
(dominant background + body text) are extracted, and they are used as preview
hints only; the Designer falls back to a generic hardcoded `EMPTY_KIT`.

## Decisions (from the user)
- **Palette depth:** *Full palette* — top ~6 distinct colors by prominence, plus
  the LP's font faces (heading + body).
- **Precedence:** *Always seed from LP* — after analysis, the LP-derived kit
  always becomes the editor's default (overriding the generic `EMPTY_KIT` and any
  previously loaded kit), but the advertiser can still edit it before handoff.

## What already exists (no change needed to the contract)
- `lp-analyzer.js` extracts `extractDominantColor()` (bg) + `extractTextColor()`.
- `LPAnalysisResult` (crawler) carries `dominantColor` / `textColor`.
- `AnalyzeLPResponse` (api) forwards them; editor reads `data.dominantColor` /
  `data.textColor` into `lpColor` / `lpTextColor`.
- A full `BrandKit { colors[], fonts[], logoUrl? }` type + storage + precedence
  already exist in `creative-designer/src/brand-kit.ts`. The Designer already
  accepts a handed-off kit via `window.__DESIGNER__.brandKitJson` (precedence #2,
  above localStorage). So the editor only has to *produce* a good kit JSON.

## Changes

### 1. `modules/browser/src/main/resources/lp-analyzer.js`
Append two `window.*` extractors (returning **JSON strings** so the existing
`page.evaluate(...).toString` pattern stays simple):
- `window.extractPalette()` → JSON array of up to 6 hex strings, ordered by
  prominence. Collect colors from element backgrounds (weight = bounding-box
  area) and from text/links/buttons (weight = area). Normalize rgb()/rgba() →
  hex, drop fully transparent, dedup near-duplicates (quantized key), sort by
  weight, take top 6.
- `window.extractFonts()` → JSON array of font-family names (first token of the
  computed `font-family`, quotes stripped) for the heading (h1/h2) and body/p,
  deduped, up to ~4.

### 2. `modules/browser/.../LPAnalyzer.scala`
- Add `palette: Vector[String] = Vector.empty` and `fonts: Vector[String] =
  Vector.empty` to `LPAnalysisResult` (after `textColor`).
- `jsonFormat4` → `jsonFormat6`.
- In `analyze()`, `page.evaluate("extractPalette()")` / `extractFonts()`, parse
  the JSON string to `Vector[String]` (Try-guarded, defaults to empty).
- Thread the two new values into the main `LPAnalysisResult(...)` construction.
  Other positional constructions get the defaults for free.

### 3. `modules/api/.../ApiModels.scala`
- Add `palette: Vector[String] = Vector.empty` and `fonts: Vector[String] =
  Vector.empty` to `AnalyzeLPResponse`.

### 4. `modules/api/.../ApiJsonFormats.scala`
- `jsonFormat4(AnalyzeLPResponse.apply)` → `jsonFormat6`.

### 5. `modules/api/.../EndpointRoutes.scala`
- `toAnalyzeLPResponse`: pass `palette = result.palette, fonts = result.fonts`.

### 6. `platform/templates/advertiser/creative-editor.html`
- Add `_toHex(css)` helper (hex passthrough; rgb()/rgba() → hex; drop alpha=0;
  named colors skipped — `<input type=color>` needs hex).
- Add `buildBrandKitFromLP(data)`: assemble an ordered, deduped hex list from
  `dominantColor`, `textColor`, then `palette`; name them
  `Background, Text, Accent, Brand, Color 5, Color 6`; set `brandKitColors`,
  set `brandKitFonts` from `data.fonts`, default `brandKitName` to "From landing
  page", and persist via `saveBrandKitToStorage()`.
- Call `buildBrandKitFromLP(data)` inside `analyzeLP()` right after the existing
  `lpColor` / `lpTextColor` assignment. This *always* seeds from the LP.
- Handoff is unchanged: `selectedBrandKitJson()` already serializes
  `brandKitColors` / `brandKitFonts`.

## Tests
- **Scala:** round-trip `AnalyzeLPResponse` (and `LPAnalysisResult`) JSON with the
  new `palette` / `fonts` fields (jsonFormat6). Add to the existing api/crawler
  json-format spec if present.
- **JS:** the analyzer runs in a real browser (Playwright) so it isn't unit
  tested here; verify behavior in the verification step instead.

## Verification
Analyze a colorful LP, confirm `extractPalette()` / `extractFonts()` return
sensible values, then confirm the Designer opens with the LP-derived palette +
fonts as the active brand kit (not the generic gray/amber default).

## Fonts in the rendered ad (follow-up — done)

Extraction alone did NOT put fonts in the live ad. Two gaps existed:
1. **Nothing applied the kit font to text items.** `template-apply.ts` mapped kit
   *colors* by role but had no font mapping; the props-panel font dropdown is a
   fixed 5-item list; Gemini auto-layout only gets colors. So `kit.fonts[]` sat
   unused in localStorage.
2. **The banner loads no web fonts.** `banner-component/src/fonts.ts` only
   resolves system families; there is no `@font-face`/Google-Fonts `<link>`.
   So an arbitrary face like "Montserrat" would silently fall back.

Fixes (decision: *snap to nearest allow-listed*):
- **`creative-editor.html` `_snapFont()`**: snaps each extracted family to a
  renderable bucket — `monospace` / `Georgia` (serif) / `sans-serif` — before
  seeding the kit. `buildBrandKitFromLP()` now stores snapped+deduped fonts, so
  every font in the kit actually renders.
- **`template-apply.ts` `applyKitToItem()`**: now also sets `fontFamily` on text
  items by role via `ROLE_TO_KIT_FONT_INDEX` (headline/cta-text/badge → fonts[0]
  heading; subheadline/body → fonts[1], falling back to fonts[0]). The banner
  already honors `item.fontFamily` (`render-collapsed.ts:61`), so this reaches
  the live ad. Test: `tests/template-apply-fonts.test.ts` (6 cases).

Result: LP-derived fonts now render in the ad — constrained to system families
so they never fall back.

**Superseded (2026-05-31):** the "system families only" constraint was lifted by
the self-hosted web fonts feature — `_snapFont` now *keeps* an allow-listed real
family as a stack (`"Montserrat, sans-serif"`), the family's woff2 is provisioned
to R2 at publish, and the banner's expanded view loads the exact face from our
CDN. The current pipeline is documented in `book/src/format/brand-kit.md`.

## Notes / risks
- Adding fields with defaults keeps all positional `LPAnalysisResult(...)` call
  sites compiling.
- `_toHex` deliberately skips CSS named colors (no reliable name→hex without a
  table); the palette extractor already emits hex so this only affects the
  rgb()-string `dominantColor`/`textColor`, which are always rgb() from
  `getComputedStyle`.
