# Printed page: greyscale, Mincho headings, leftovers

Approved in conversation 2026-10-31. Branch `feat/printed-page`.

## Goal

The dashboard should read as a printed black-and-white page, like a newspaper. Most of the Forecast redesign already does: one headline figure and a sentence, rules instead of boxes, ink on white. This pass finishes the job. The layout stays as it is.

## Decisions

1. **Fully greyscale.** No hue anywhere in the dashboard chrome, the data or the alerts. Newspaper readers read black-and-white charts and tables without trouble.
2. **Mincho headings.** BIZ UDPMincho (Morisawa, OFL, weights 400 and 700) for page titles, section headings and the big figure. Body, tables, forms and labels stay BIZ UDPGothic.
3. **Layout unchanged**, except that every page shares one width.
4. **Ad creatives keep their colour.** Banners, magazine previews and uploaded images are content, like photos in a newspaper.

## 1. Colour

All of this changes at the token level: `platform/tailwind.input.css` (`:root` and `.dark`) and `platform/tailwind.config.js`. Templates that use tokens need no change.

**Neutral greys.** Each `--c-gray-*`, `--c-white` and `--c-field` step becomes the neutral grey with the same relative luminance, so every contrast ratio stays the same.

| step | light | dark |
|---|---|---|
| white | #ffffff | #1e1e1e |
| 50 | #f8f8f8 | #151515 |
| 100 | #ececec | #292929 |
| 200 | #dddddd | #393939 |
| 300 | #c6c6c6 | #4b4b4b |
| 400 | #949494 | #8c8c8c |
| 500 | #545454 | #b1b1b1 |
| 600 | #424242 | #cbcbcb |
| 700 | #303030 | #dedede |
| 800 | #222222 | #ebebeb |
| 900 | #1a1a1a | #f5f5f5 |
| 950 | #0c0c0c | #fbfbfb |
| field | #e6e6e6 | #292929 |

**Links.** `--c-brand-ink` becomes ink (gray-900 in each theme), and links are underlined. Nothing else signals a link.

**Focus.** `:focus-visible` uses a 2px ink outline with a 2px offset (gray-900 in each theme). The input focus ring uses ink at 40%. Text selection is gray-300 behind ink.

**Intensity scale.** The `jma-1` … `jma-7` tokens become seven grey tints, evenly spaced in lightness, from light to near-black: L\* 82, 70, 58, 46, 34, 22, 10. They are routed through CSS variables, so in dark mode the scale runs the other way: L\* 30 up to 92, and more intensity is lighter. Zero cells stay gray-100. The thresholds (`handler/forecast.go` `intensity()`) and the legend ticks don't change. The projection treatment (faded, with a dashed edge) stays. If the screenshots show a faded dark step reading like a solid light one, the projection switches to a diagonal hatch.

**Alerts.**
- 警報 (`.badge-danger`): white on ink, reversed out like a newspaper's 白抜き. In dark mode it inverts through the brand tokens.
- 注意報 (`.badge-warning`): ink on gray-300.
- `advisory` and `warning` stop being colours. The view-as banner and pending-review states use the 注意報 treatment.

**Notices.** These have neutral fills and are told apart by edge and word:
- error: a 2px ink edge on white
- advisory: a gray-400 edge on gray-100
- ok and info: a gray-300 edge on gray-50

Each error and advisory notice must open with its word (エラー / Error, 注意 / Note) where it doesn't already, so meaning never depends on the edge alone.

**Charts.** Hard-coded chart colours in report and stats pages, such as `rgba(33,133,255,…)`, become greys.

**Creative designer.** In `creative-designer/src/ui/tokens.ts`, `focus`, `focusMuted` and `focusBg` become ink and grey. On the canvas, selection and handles use an ink line with a white outer line, so they show on any image. The banner component (`<magazine-preview>`) and the creatives themselves are left alone.

## 2. Type

- Self-host BIZ UDPMincho 400 and 700 from `@fontsource/biz-udpmincho`, laid out like `static/fonts/biz-udpgothic` (woff2, Latin and Japanese subsets, `font-display: swap`, OFL.txt kept).
- Add a `serif` family to Tailwind: `'BIZ UDPMincho', 'BIZ UDP明朝', 'Hiragino Mincho ProN', 'Yu Mincho', serif`.
- `.page-title`, `.section-title` and the big figure use `font-serif` at 700. Sizes stay as DESIGN.md gives them.
- DESIGN.md's One Face Rule becomes a **Two Face Rule**: Mincho for headings and the big figure, Gothic for everything you read or operate.

## 3. Leftovers

- **Hue utilities** (about 100, in 16 templates; the biggest are creative-editor, approval and campaigns): replace them with greys, the alert classes or notices. A grep for `(text|bg|border)-(amber|red|green|emerald|yellow|orange|blue|indigo|rose)-[0-9]` over `platform/templates` must come back empty.
- **✓/✗ glyphs** (9, in sites, layout, creative-editor and campaigns): replace each with the word it stands for.
- **Uppercase** (25, in 10 templates): remove it.
- **Page width:** `<main>` in `layout.html` gets one `max-w-7xl` wrapper. Page-level `max-w-*xl` wrappers are removed. Narrow caps stay only on forms, prose, notices and the auth pages.

## 4. Docs and verification

- DESIGN.md: rewrite the Colors section as greyscale ("The Colour Is Data Rule" becomes "The Ink Only Rule"), add the Two Face Rule, and update the strip and legend description. `.impeccable/design.json` follows.
- Run the Go tests (`platform`: `go test ./...`, including the forecast tests) and the designer unit tests (`npm test` in `creative-designer`).
- Screenshots for the user's review before commit: Campaigns, Wallet, publisher Stats, Approval, an admin billing page and the creative designer, each light and dark, EN and 日本語, at 1440px, plus Campaigns at 390px.

## Out of scope

- Any layout change beyond the shared width.
- The banner component and ad creatives.
- New pages or content (for example, filling out publisher Stats).
