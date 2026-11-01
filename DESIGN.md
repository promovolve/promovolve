---
name: Promovolve
description: The dashboard for an open-source contextual ad network, read like a Japanese weather forecast printed in a newspaper. Black and white: ink on a white ground, a grey intensity scale, typographic alerts, Mincho headings. Ad creatives are the only colour.
colors:
  ink: "#1a1a1a"
  ink-black: "#0c0c0c"
  ground: "#ffffff"
  field: "#e6e6e6"
  page-tint: "#f8f8f8"
  fill: "#ececec"
  hairline: "#dddddd"
  rule: "#c6c6c6"
  mark: "#949494"
  ink-secondary: "#545454"
  ink-soft: "#424242"
  ink-label: "#303030"
  jma-1: "#cccccc"
  jma-2: "#ababab"
  jma-3: "#8b8b8b"
  jma-4: "#6d6d6d"
  jma-5: "#505050"
  jma-6: "#353535"
  jma-7: "#1b1b1b"
  advisory: "#c6c6c6"
  warning: "#1a1a1a"
  notice-advisory-fill: "#ececec"
  notice-advisory-edge: "#949494"
  notice-ok-fill: "#f8f8f8"
  notice-ok-edge: "#c6c6c6"
  night-page: "#151515"
  night-surface: "#1e1e1e"
  night-field: "#292929"
  night-ink: "#f5f5f5"
  night-action: "#ececec"
  night-secondary: "#b1b1b1"
  night-jma-1: "#474747"
  night-jma-2: "#5f5f5f"
  night-jma-3: "#797979"
  night-jma-4: "#939393"
  night-jma-5: "#afafaf"
  night-jma-6: "#cbcbcb"
  night-jma-7: "#e8e8e8"
typography:
  figure:
    fontFamily: "'BIZ UDPMincho', 'BIZ UDP明朝', 'Hiragino Mincho ProN', 'Yu Mincho', serif"
    fontSize: "3rem"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.025em"
    fontFeature: "'tnum'"
  headline:
    fontFamily: "'BIZ UDPMincho', 'BIZ UDP明朝', 'Hiragino Mincho ProN', 'Yu Mincho', serif"
    fontSize: "1.875rem"
    fontWeight: 700
    lineHeight: "2.25rem"
    letterSpacing: "-0.025em"
  title:
    fontFamily: "'BIZ UDPMincho', 'BIZ UDP明朝', 'Hiragino Mincho ProN', 'Yu Mincho', serif"
    fontSize: "1.25rem"
    fontWeight: 700
    lineHeight: "1.75rem"
  sentence:
    fontFamily: "'BIZ UDPGothic', 'BIZ UDPゴシック', system-ui, -apple-system, 'Hiragino Sans', 'Yu Gothic UI', sans-serif"
    fontSize: "1.125rem"
    fontWeight: 400
    lineHeight: "1.75rem"
  body:
    fontFamily: "'BIZ UDPGothic', 'BIZ UDPゴシック', system-ui, -apple-system, 'Hiragino Sans', 'Yu Gothic UI', sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: "1.25rem"
  label:
    fontFamily: "'BIZ UDPGothic', 'BIZ UDPゴシック', system-ui, -apple-system, 'Hiragino Sans', 'Yu Gothic UI', sans-serif"
    fontSize: "0.75rem"
    fontWeight: 700
    lineHeight: "1rem"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: "1rem"
rounded:
  none: "0"
  sm: "2px"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  2xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    rounded: "{rounded.sm}"
    padding: "8px 16px"
    height: "36px"
    typography: "{typography.body}"
  button-secondary:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "8px 16px"
    height: "36px"
  button-secondary-hover:
    backgroundColor: "{colors.fill}"
  button-sm:
    padding: "4px 12px"
    height: "24px"
    typography: "{typography.label}"
  tag-plain:
    backgroundColor: "{colors.fill}"
    textColor: "{colors.ink-label}"
    rounded: "{rounded.sm}"
    padding: "2px 8px"
    typography: "{typography.label}"
  tag-advisory:
    backgroundColor: "{colors.advisory}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "2px 8px"
    typography: "{typography.label}"
  tag-warning:
    backgroundColor: "{colors.warning}"
    textColor: "{colors.ground}"
    rounded: "{rounded.sm}"
    padding: "2px 8px"
    typography: "{typography.label}"
  input:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "8px 12px"
    typography: "{typography.body}"
  top-bar:
    backgroundColor: "{colors.field}"
    textColor: "{colors.ink}"
    height: "56px"
    padding: "0 32px"
  nav-tab:
    textColor: "{colors.ink-soft}"
    padding: "0 12px"
    typography: "{typography.body}"
  nav-tab-current:
    textColor: "{colors.ink}"
  section:
    backgroundColor: "{colors.ground}"
    padding: "24px 0"
  data-table-header:
    textColor: "{colors.ink-secondary}"
    padding: "8px 12px"
    typography: "{typography.label}"
  selected-row:
    backgroundColor: "{colors.field}"
    textColor: "{colors.ink}"
  forecast-cell:
    rounded: "{rounded.none}"
    height: "14px"
---

# Design System: Promovolve

## Overview

**Creative North Star: "The Delivery Forecast"**

The dashboard reads like a Japanese weather forecast. Delivery and money are shown as what happened hour by hour, what is coming at this pace, and official-style advisory and warning levels. An advertiser opens Campaigns and sees today's spend as one large number, a sentence saying where the day is heading, and each campaign's day as a strip of intensity cells. A publisher sees earnings the same way. An operator sees work queues as plain ruled tables.

It is a printed page: black and white, like a newspaper's weather page. The ground is white and the text is ink (`ink`). A pale grey field (`field`) marks two things only: the top bar and a selected row. Sections are separated by rules, not boxes. Corners are 2px, and nothing casts a shadow unless it floats. There is no hue anywhere in the dashboard: intensity is a grey scale, light to near-black, and the two alert levels are typographic, ink on mid grey and white reversed out of ink. Actions are ink, links are ink with an underline, and plain states are words. The ad creatives (banners, magazine previews, uploaded images) are the only colour on the page, like the photographs in a newspaper.

The density suits operating work. Body text is 14px, labels 12px, and figures are tabular everywhere. Headings and the big figure are set in BIZ UDPMincho, everything read or operated in BIZ UDPGothic: Morisawa's Universal Design pair, which sets English and 日本語 alike, so the two languages carry the same weight and rhythm. Dark mode is a full theme: every neutral step keeps its contrast role when the theme flips.

**Key Characteristics:**
- Black and white: white ground, ink text, a grey field only for the top bar and selected rows
- No hue: intensity is a seven-step grey scale, alerts are typographic, links are underlined; ad creatives are the only colour
- Ink action colour: filled primary, outlined secondary and destructive
- Ruled sections and flat tables with an ink top rule, never cards or boxes
- 2px corners everywhere; shadows only on floating layers
- One large figure per region, followed by a sentence
- Mincho headings and big figure, Gothic for everything else, tabular figures, EN/日本語 parity

## Colors

Ink on white with a neutral grey scale (every step r = g = b). There is no hue in the dashboard; the creatives bring their own.

### Primary
- **Ink** (`ink`): body text, headings, links, the filled primary button, the selected segment of a segmented control, the 2px rule over every table and figure row, the now line in a forecast strip, the current-tab underline, the focus outline. In dark mode the action fill becomes **Night Action** (`night-action`) with dark text, and text becomes **Night Ink** (`night-ink`).
- **Links** are ink text with an underline (offset 2px). Nothing else marks a link.

### Secondary
- **Field Grey** (`field`): the top bar, the nav panel on small screens, and the selected row in an inbox or list. It is never used to box content. In dark mode it is **Night Field** (`night-field`).

### Tertiary: the intensity scale and alert levels
- **Intensity scale** (`jma-1` … `jma-7`): seven grey tints, evenly spaced in lightness from light grey to near-black (L\* 82, 70, 58, 46, 34, 22, 10). It shades intensity data only: spend or delivery per hour on Campaigns, charge per day on Wallet. A zero cell renders in Fill (`fill`). The steps are CSS variables, so in dark mode the scale runs the other way (`night-jma-1` … `night-jma-7`, L\* 30 up to 92): more intensity is lighter.
- **Advisory** (`advisory`, 注意報): ink text on a mid-grey fill. Used for the advisory tag, the view-as banner, and pending-review states.
- **Warning** (`warning`, 警報): white text reversed out of ink, like a newspaper's 白抜き. In dark mode it inverts through the action tokens.
- **Notices** have neutral fills and are told apart by edge weight and by the lead word (Error: / Note:, エラー： / 注意：) the template puts in front, so meaning never rests on the edge: error is a 2px Ink edge on Ground; advisory a Mark edge on Fill (`notice-advisory-fill`, `notice-advisory-edge`); ok and info a Rule edge on Page Tint (`notice-ok-fill`, `notice-ok-edge`).
- **Charts** draw in Ink and the intensity greys; the forecast `chartTheme` in `layout.html` reads them from the tokens.

### Neutral
- **Ground** (`ground`): the page and every surface. In dark mode it is **Night Surface** (`night-surface`) on **Night Page** (`night-page`).
- **Fill** (`fill`): plain tags, zero-intensity cells, hover on secondary buttons and menu items, inline code.
- **Hairline** (`hairline`): lines between table rows and figure rows.
- **Rule** (`rule`): section top rules, input borders, table header underline.
- **Mark** (`mark`): rules and icons only, never text (3:1).
- **Ink Secondary** (`ink-secondary`): secondary text, hints, placeholders, table headers, legend text (7.4:1). In dark mode **Night Secondary** (`night-secondary`).
- **Ink Soft** (`ink-soft`) and **Ink Label** (`ink-label`): idle nav tabs, form labels, plain-tag text.
- **Focus:** a 2px Ink outline, offset 2px; inputs add a 2px Ink ring at 40%.
- Text selection is Ink on Rule.

### Named Rules
**The Ink Only Rule.** No hue in the dashboard; ad creatives are the only colour on the page. Intensity is grey, alerts are typographic, links are underlined. Greyscale comes from the tokens, never from `filter: grayscale` (it would grey the creatives too). `platform/print_rules_test.go` and `dashboard-tests/printed-page.run.mjs` enforce it.

**The Fixed Scale Rule.** Every intensity cell is placed on the same seven steps, as a ratio to an even pace: below ¼, ¼–0.6, 0.6–1.2 (around even), 1.2–2, 2–3, 3–4, 4× and above. Campaigns divide by an even spread of the daily budget (budget ÷ 24 per hour); Wallet divides by the average recent day. So every strip reads on one scale whatever its budget or currency. The thresholds live in `handler/forecast.go` `intensity()`, and the legend ticks must match them.

**The Contrast Role Rule.** Templates reference neutrals by step (`gray-50`…`gray-950`, `white`), never by hex. Each step keeps its role in both themes, so a light-authored template reads correctly in dark.

## Typography

**Headings:** BIZ UDPMincho 700 (self-hosted woff2 from `@fontsource/biz-udpmincho` 5.3.0, Latin and Japanese subsets, `font-display: swap`; Tailwind `font-serif`), falling back to Hiragino Mincho ProN and Yu Mincho.
**Text:** BIZ UDPGothic (self-hosted the same way), falling back to system-ui, Hiragino Sans and Yu Gothic UI.
**Mono:** the system monospace, for IDs, tokens and code only.

**Character:** a Universal Design gothic built for public signage and forms. It is plain, very legible at 12px, and sets Latin and kana with the same stroke weight, so a 日本語 screen and an English screen look like the same document. Hierarchy comes from size, bold (700) and the face: the Mincho of a newspaper headline over the Gothic of the text.

### Hierarchy
- **Figure** (Mincho 700, 48px, line-height 1, -0.025em, tabular): the one large number of a region: today's spend on Campaigns, earnings on publisher Stats. Followed on the same baseline by a sentence.
- **Headline** (Mincho 700, 30px, -0.025em): the page title, one per page, with the "as of" line under it in Ink Secondary.
- **Title** (Mincho 700, 20px): section headings; `h1`/`h2` take Mincho by default. IDs set in mono stay mono. The Wallet balance headline is a 24px bold-plus-regular sentence ("Balance ¥X. After today's ¥Y is charged, about ¥Z.").
- **Sentence** (400, 18px): the outlook sentence beside a figure.
- **Body** (400, 14px/20px): tables, forms, buttons, notices.
- **Label** (700, 12px): table headers, tags, figure labels (400), legend and axis text (400).
- **Figure row value** (700, 24px, tabular): the values in a row of headline figures.

### Named Rules
**The Tabular Figures Rule.** Every table, money column and figure is tabular (`tabular-nums`), so columns line up digit for digit.

**The Two Face Rule.** Mincho (BIZ UDPMincho 700) for page titles, section headings and the big figure; Gothic (BIZ UDPGothic) for everything you read or operate. The creative designer follows it too: Mincho for the creative's name, panel section headings and modal titles (`platform/creative-designer/DESIGN.md`). Weight is 400 or 700; nothing in between is reliable across the subsets.

## Layout

The signed-in shell is a column: a 56px top bar, then a scrolling main pane padded 16px (32px from `md`). There is no sidebar. Pages have no width cap: content, tables above all, uses the full width of the main pane, and at 1280px and 1440px nothing scrolls sideways. Narrow caps stay only on forms, prose (help), notices and the auth pages.

A page opens with the title on the left and the primary action on the right, aligned to the bottom. Under it comes the region's figure and sentence, the forecast strip with its axis and legend, then a segmented filter, then the table. Regions are separated by a 1px rule and 24px of vertical space, not by containers.

Rhythm is a 4px grid: 8–12px inside groups, 16–24px between groups, 32–40px between regions. Table cells pad 12px × 8px.

Below `lg` (1024px) the top-bar tabs collapse into a menu button that opens the same `<nav>` as a stacked Field panel under the bar; it is `hidden` when closed, so it leaves the tab order, and Escape closes it. The Campaigns list is one grid shared by header and rows from `xl` (1280px); below that each row stacks with the status beside the name and every other cell full width. At phone widths wide tables scroll inside an `overflow-x-auto` wrapper.

The read-only view-as session pins an advisory (mid-grey) banner above the top bar and dims every mutating control to 45% opacity.

## Elevation & Depth

Flat. Depth comes from rules and the Field tint, never from shadow. One shadow exists, and only things that float above the page use it.

### Shadow Vocabulary
- **Float** (`box-shadow: 0 8px 24px -6px rgb(20 20 20 / 0.22)`): the account menu, date pickers and typeahead lists, always with a 1px Ink border.

### Named Rules
**The Only-Floaters-Cast-Shadows Rule.** The theme maps `shadow`, `shadow-sm` and `shadow-md` to none, so a resting surface cannot get a shadow by accident. Only `shadow-lg`/`shadow-xl` resolve, and only floating layers use them.

## Shapes

Every corner is 2px: the theme maps `rounded`, `rounded-md`, `rounded-lg` and up to 2px, so legacy classes land on the system. Only status dots and the × inside a tag stay round. Forecast cells are square-cornered and sit 1px apart. Lines are 1px hairlines, with three heavier marks: the 2px ink rule over tables and figure rows, the 3px current-tab underline, and the 2px now line.

## Components

Defined once in `platform/tailwind.input.css` (`@layer components`) and in shared partials in `platform/templates/layout.html`.

### Buttons
- **Shape:** 2px corners, at least 36px tall, 8px × 16px padding, 14px bold, 6px gap for an icon.
- **Primary** (`.btn-primary`): Ink fill, white text; hover at 85%. One per view. Approving is also a primary (`.btn-success` is the same ink fill).
- **Secondary** (`.btn-secondary`): white with a 1px Ink border and Ink text; hover fills with Fill.
- **Destructive** (`.btn-danger`): the same ink outline as secondary. The guard is a confirm dialog naming the object and the consequence, not a red button.
- **Compact** (`.btn-sm`): 24px tall, 4px × 12px, 12px text.
- **Disabled:** 50% opacity, not-allowed cursor.
- **Segmented control:** a row of buttons inside one 1px Ink border, divided by Ink hairlines; the selected one is Ink-filled with white bold text and carries `aria-pressed="true"`. Used for list filters and the advertiser/publisher side switcher.

### Tags
- **Style** (`.badge`): 2px corners, 2px × 8px, 12px bold.
- **Plain** (`-info`, `-success`, `-neutral`): Fill with Ink Label or Ink text. Active, paused, approved and similar states are words in a plain tag.
- **Advisory** (`.badge-warning`, 注意報): Ink text on the Advisory grey.
- **Warning** (`.badge-danger`, 警報): white text reversed out of Ink.
- **Remove ×** (`.chip-x`): inherits the tag's text colour at 70%, 100% on hover, 24px hit area pulled back with negative margins, always with an `aria-label` ("Remove %s").

### Sections and figures
- **Section** (`.card`): a 1px Rule across the top and 24px of vertical padding (16px with `.card-tight`). No fill, no border box, no corner.
- **Figure row** (`.figures` / `.figure`): a grid under a 2px Ink rule; each figure has a 12px Ink Secondary label and a 24px bold tabular value, with a hairline under it.

### Data tables
- **Style** (`.data-table`): full width, 14px, a 2px Ink rule on top, 12px bold Ink Secondary headers over a Rule line, hairlines between rows, no outer box. Numeric cells are right-aligned (`.num`).
- **Selected row:** Field fill and bold text.
- **Expandable rows:** the row's name is a `<button>` with `aria-expanded`/`aria-controls`; the detail opens in place under it.

### Inputs / Fields
- **Style** (`.form-input`): full width, 8px × 12px, 1px Rule border, 2px corners, white fill, Ink text, Ink Secondary placeholder.
- **Focus:** border turns Ink and a 2px Ink ring at 40% appears.
- **Label / Hint:** `.form-label` (14px bold, Ink Label) above, wired with `for`/`id`; `.form-hint` (12px Ink Secondary) below.
- **Safety net:** raw inputs, selects and textareas take the white surface and Ink text from the base layer, so they theme correctly in dark.

### Notices
- **Style** (`.notice` + `-error`/`-advisory`/`-ok`/`-info`): 14px Ink text, 12px × 8px, 2px corners, a full edge over a neutral fill: 2px Ink on Ground for error, 1px Mark on Fill for advisory, 1px Rule on Page Tint for ok and info. Never a side stripe.
- **Lead word:** `data-lead="{{t "Error:"}}"` or `"Note:"` on the notice prints the word in bold before the text (`.notice[data-lead]::before`), for server- and script-filled notices alike.

### Navigation
- **Top bar:** Field, 56px. The wordmark "Promovolve" (18px bold) and the role (12px) on the left, the role's pages as tabs, then the side switcher, the account menu (email and chevron, opening a floating menu) and, below `lg`, the menu button.
- **Tabs** (`.nav-tab`): 14px Ink Soft; hover Ink. The current page has `aria-current="page"`, bold Ink text and a 3px Ink underline from `lg` (a white fill in the stacked panel).

### Forecast strip (signature)
The shared `forecast-strip` partial draws a run of cells on the grey intensity scale: a day's 24 hours on Campaigns, or 14 settled days, today and 14 projected days on Wallet.
- **Delivered cells:** 14px tall, 1px gaps, filled with the cell's grey step (Fill for zero). Each cell has a `title` with its value.
- **Projection:** cells after now, at the current pace until the budget or balance would run out, drawn at 40% opacity with a 1px dashed Ink Secondary edge.
- **Now line:** a 2px Ink bar with a 1px Ground outline (so it stays visible over the darkest step), overhanging the strip by 4px top and bottom.
- **Axis:** 12px Ink Secondary tick labels under the strip.
- **Accessibility:** the strip is `role="img"` with the outlook sentence as its label; the sentence is also printed as text, with an Advisory (注意報) or Warning (警報) tag in front when the outlook is an alert.

### Forecast legend
The shared `forecast-legend` partial explains the scale under every strip: a sentence naming the ratio ("Spend per hour, as a multiple of an even spread of the daily budget"), seven 36px grey swatches with each step's lower bound centred on its left edge (¼, 0.6, 1.2, 2, 3, 4×), then a dashed swatch for "at this pace" and an ink bar for "now".

### Help prose (`.prose-doc`)
In-app help renders markdown at 14px with relaxed leading. H2s sit under a Fill rule, links are underlined Ink, inline code is on Fill, and code blocks are a dark slab in both themes.

## Do's and Don'ts

### Do:
- **Do** put intensity data on the grey scale through `intensity()` and show the legend under it, with ticks that match the thresholds.
- **Do** mark alert levels typographically: Advisory is Ink on mid grey, Warning is white reversed out of Ink, and a notice opens with its lead word.
- **Do** make the primary action Ink-filled, and guard destructive actions with an ink outline button plus a confirm dialog that names the object.
- **Do** write plain states as words in a plain grey tag.
- **Do** separate regions with a 1px Rule and spacing; put a 2px Ink rule over tables and figure rows.
- **Do** lead a money region with one 48px tabular figure and a sentence that says what it means.
- **Do** mark the current tab with `aria-current="page"`, make expandable rows real buttons with `aria-expanded`, and keep text at or above Ink Secondary (7.4:1).
- **Do** keep every string at EN/日本語 parity and leave room for 日本語 lengths in tabs and buttons.
- **Do** run `scripts/build-tailwind.sh` after template class changes and commit `platform/static/tailwind.css`.

### Don't:
- **Don't** use hue anywhere in the dashboard: no colour utilities (`text-red-*`, `bg-amber-*`, …), no coloured hex in templates, no ✓/✗ glyphs standing in for words. Use the tokens.
- **Don't** use `filter: grayscale` to get greys; it would grey the ad creatives too.
- **Don't** use the intensity greys outside intensity data, or put a projection on a paused campaign.
- **Don't** draw cards, boxed panels, KPI-card rows or a sidebar. The page is ruled, not boxed.
- **Don't** use the Field tint except for the top bar, the nav panel and selected rows.
- **Don't** add shadows to anything that doesn't float, or corners above 2px.
- **Don't** use Mark (`gray-400`) or lighter for text.
- **Don't** hard-code neutral hex values in templates; use the gray steps so dark mode follows.
- **Don't** use side stripes on notices; use the full-edge notice.
- **Don't** set text in uppercase; neither language reads it as a heading.
- **Don't** cap a page's width; only forms, prose, notices and auth pages are narrow.
