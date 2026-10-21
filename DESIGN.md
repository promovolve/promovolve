---
name: Promovolve
description: The dashboard for an open-source contextual ad network, read like a Japanese weather forecast. White ground, ink, a blue-grey field, and colour only for intensity and alert levels.
colors:
  ink: "#1a1a1a"
  ink-black: "#0c0c0c"
  ground: "#ffffff"
  field: "#dfe7ee"
  page-tint: "#f6f8fa"
  fill: "#e8edf2"
  hairline: "#d6dee5"
  rule: "#bdc7d0"
  mark: "#8a96a1"
  ink-secondary: "#4a5560"
  ink-soft: "#3a434d"
  ink-label: "#2a3138"
  link-blue: "#0041ff"
  focus-blue: "#2185ff"
  jma-0: "#f2f2ff"
  jma-1: "#a0d2ff"
  jma-2: "#2185ff"
  jma-3: "#0041ff"
  jma-4: "#fff500"
  jma-5: "#ff9900"
  jma-6: "#ff2800"
  jma-7: "#b40068"
  advisory: "#fff500"
  warning: "#d70a00"
  notice-error-fill: "#fff1f0"
  notice-advisory-fill: "#fffbd1"
  notice-advisory-edge: "#b3a600"
  notice-ok-fill: "#e3f1e7"
  notice-ok-edge: "#146c2e"
  notice-info-fill: "#e6efff"
  night-page: "#111519"
  night-surface: "#1a1f24"
  night-field: "#222a33"
  night-ink: "#f2f5f8"
  night-action: "#e8edf2"
  night-secondary: "#a8b3bd"
  night-link: "#a0d2ff"
typography:
  figure:
    fontFamily: "'BIZ UDPGothic', 'BIZ UDPゴシック', system-ui, -apple-system, 'Hiragino Sans', 'Yu Gothic UI', sans-serif"
    fontSize: "3rem"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.025em"
    fontFeature: "'tnum'"
  headline:
    fontFamily: "'BIZ UDPGothic', 'BIZ UDPゴシック', system-ui, -apple-system, 'Hiragino Sans', 'Yu Gothic UI', sans-serif"
    fontSize: "1.875rem"
    fontWeight: 700
    lineHeight: "2.25rem"
    letterSpacing: "-0.025em"
  title:
    fontFamily: "'BIZ UDPGothic', 'BIZ UDPゴシック', system-ui, -apple-system, 'Hiragino Sans', 'Yu Gothic UI', sans-serif"
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

The ground is white and the text is ink (`ink`). A pale blue-grey field (`field`) marks two things only: the top bar and a selected row. Sections are separated by rules, not boxes. Corners are 2px, and nothing casts a shadow unless it floats. Colour has two jobs and no others: the JMA rainfall-intensity scale for intensity data, and the yellow/red alert levels. Actions are ink. Plain states are words.

The density suits operating work. Body text is 14px, labels 12px, and figures are tabular everywhere. BIZ UDPGothic, Morisawa's Universal Design gothic, sets English and 日本語 in one face, so the two languages carry the same weight and rhythm. Dark mode is a full theme: every neutral step keeps its contrast role when the theme flips.

**Key Characteristics:**
- White ground, ink text, a blue-grey field only for the top bar and selected rows
- Colour only for intensity data (JMA scale) and alert levels (advisory yellow, warning red)
- Ink action colour: filled primary, outlined secondary and destructive
- Ruled sections and flat tables with an ink top rule, never cards or boxes
- 2px corners everywhere; shadows only on floating layers
- One large figure per region, followed by a sentence
- BIZ UDPGothic for all text, tabular figures, EN/日本語 parity

## Colors

Ink on white with a cool blue-grey neutral scale. All hue in the system belongs to data and alerts.

### Primary
- **Ink** (`ink`): body text, headings, the filled primary button, the selected segment of a segmented control, the 2px rule over every table and figure row, the now line in a forecast strip, the current-tab underline. In dark mode the action fill becomes **Night Action** (`night-action`) with dark text, and text becomes **Night Ink** (`night-ink`).
- **Link Blue** (`link-blue`): link text only (inline links and help prose). In dark mode it becomes **Night Link** (`night-link`). It is the same hue as JMA step 3, so links read as "the blue" rather than as a second brand colour.

### Secondary
- **Field Blue-Grey** (`field`): the top bar, the nav panel on small screens, and the selected row in an inbox or list. It is never used to box content. In dark mode it is **Night Field** (`night-field`).

### Tertiary: the intensity scale and alert levels
- **JMA intensity scale** (`jma-0` … `jma-7`): pale violet-white, sky, azure, deep blue, yellow, orange, red, magenta. This is the Japan Meteorological Agency rainfall palette. It colours intensity data only: spend or delivery per hour on Campaigns, charge per day on Wallet. Step 0 is reserved for no data; a zero cell renders in Fill (`fill`).
- **Advisory Yellow** (`advisory`): the JMA "take note" level. Always with Ink text. Used for the advisory tag, the view-as banner, and pending-review states.
- **Warning Red** (`warning`): the JMA warning level. Always with white text (5.4:1). Used for the warning tag and error notices.
- **Notices** use pale fills with a full 1px edge in the level's colour: error (`notice-error-fill`, `warning` edge), advisory (`notice-advisory-fill`, `notice-advisory-edge`), ok (`notice-ok-fill`, `notice-ok-edge`), info (`notice-info-fill`, `focus-blue` edge). Dark mode uses translucent fills of the same hues with lighter edges.

### Neutral
- **Ground** (`ground`): the page and every surface. In dark mode it is **Night Surface** (`night-surface`) on **Night Page** (`night-page`).
- **Fill** (`fill`): plain tags, zero-intensity cells, hover on secondary buttons and menu items, inline code.
- **Hairline** (`hairline`): lines between table rows and figure rows.
- **Rule** (`rule`): section top rules, input borders, table header underline.
- **Mark** (`mark`): rules and icons only, never text (3:1).
- **Ink Secondary** (`ink-secondary`): secondary text, hints, placeholders, table headers, legend text (7.4:1). In dark mode **Night Secondary** (`night-secondary`).
- **Ink Soft** (`ink-soft`) and **Ink Label** (`ink-label`): idle nav tabs, form labels, plain-tag text.
- **Focus Blue** (`focus-blue`): the 2px focus outline (offset 2px) and the 40% input focus ring. It is JMA step 2.
- Text selection is Night Link on Ink.

### Named Rules
**The Colour Is Data Rule.** Hue appears only in intensity data (the JMA scale) and alert levels (advisory yellow, warning red). Buttons, tabs, plain statuses, headings and decoration are ink and grey. If a coloured thing is not a measurement or an alert, it is wrong.

**The Fixed Scale Rule.** Every intensity cell is placed on the same seven steps, as a ratio to an even pace: below ¼, ¼–0.6, 0.6–1.2 (around even), 1.2–2, 2–3, 3–4, 4× and above. Campaigns divide by an even spread of the daily budget (budget ÷ 24 per hour); Wallet divides by the average recent day. So every strip reads on one scale whatever its budget or currency. The thresholds live in `handler/forecast.go` `intensity()`, and the legend ticks must match them.

**The Contrast Role Rule.** Templates reference neutrals by step (`gray-50`…`gray-950`, `white`), never by hex. Each step keeps its role in both themes, so a light-authored template reads correctly in dark.

## Typography

**Font:** BIZ UDPGothic (self-hosted woff2, Latin and Japanese subsets, `font-display: swap`), falling back to system-ui, Hiragino Sans and Yu Gothic UI.
**Mono:** the system monospace, for IDs, tokens and code only.

**Character:** a Universal Design gothic built for public signage and forms. It is plain, very legible at 12px, and sets Latin and kana with the same stroke weight, so a 日本語 screen and an English screen look like the same document. Hierarchy comes from size and bold (700). There is no second display face.

### Hierarchy
- **Figure** (700, 48px, line-height 1, -0.025em, tabular): the one large number of a region: today's spend on Campaigns, earnings on publisher Stats. Followed on the same baseline by a sentence.
- **Headline** (700, 30px, -0.025em): the page title, one per page, with the "as of" line under it in Ink Secondary.
- **Title** (700, 20px): section headings. The Wallet balance headline is a 24px bold-plus-regular sentence ("Balance ¥X. After today's ¥Y is charged, about ¥Z.").
- **Sentence** (400, 18px): the outlook sentence beside a figure.
- **Body** (400, 14px/20px): tables, forms, buttons, notices.
- **Label** (700, 12px): table headers, tags, figure labels (400), legend and axis text (400).
- **Figure row value** (700, 24px, tabular): the values in a row of headline figures.

### Named Rules
**The Tabular Figures Rule.** Every table, money column and figure is tabular (`tabular-nums`), so columns line up digit for digit.

**The One Face Rule.** All text is BIZ UDPGothic in both languages. Weight is 400 or 700; nothing in between is reliable across the subsets.

## Layout

The signed-in shell is a column: a 56px top bar, then a scrolling main pane padded 16px (32px from `md`). There is no sidebar. Content columns are capped per page: `max-w-3xl` for a forecast block, `max-w-4xl` for Wallet, `max-w-5xl` for admin tables.

A page opens with the title on the left and the primary action on the right, aligned to the bottom. Under it comes the region's figure and sentence, the forecast strip with its axis and legend, then a segmented filter, then the table. Regions are separated by a 1px rule and 24px of vertical space, not by containers.

Rhythm is a 4px grid: 8–12px inside groups, 16–24px between groups, 32–40px between regions. Table cells pad 12px × 8px.

Below `lg` (1024px) the top-bar tabs collapse into a menu button that opens the same `<nav>` as a stacked Field panel under the bar; it is `hidden` when closed, so it leaves the tab order, and Escape closes it. The Campaigns list is one grid shared by header and rows from `xl` (1280px); below that each row stacks with the status beside the name and every other cell full width. Wide tables scroll inside an `overflow-x-auto` wrapper.

The read-only view-as session pins an advisory-yellow banner above the top bar and dims every mutating control to 45% opacity.

## Elevation & Depth

Flat. Depth comes from rules and the Field tint, never from shadow. One shadow exists, and only things that float above the page use it.

### Shadow Vocabulary
- **Float** (`box-shadow: 0 8px 24px -6px rgb(16 24 32 / 0.22)`): the account menu, date pickers and typeahead lists, always with a 1px Ink border.

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
- **Advisory** (`.badge-warning`): Advisory Yellow with Ink text.
- **Warning** (`.badge-danger`): Warning Red with white text.
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
- **Focus:** border turns Ink and a 2px Focus Blue ring at 40% appears.
- **Label / Hint:** `.form-label` (14px bold, Ink Label) above, wired with `for`/`id`; `.form-hint` (12px Ink Secondary) below.
- **Safety net:** raw inputs, selects and textareas take the white surface and Ink text from the base layer, so they theme correctly in dark.

### Notices
- **Style** (`.notice` + `-error`/`-advisory`/`-ok`/`-info`): 14px Ink text, 12px × 8px, a full 1px edge in the level's colour over a pale fill, 2px corners. Never a coloured side stripe.

### Navigation
- **Top bar:** Field, 56px. The wordmark "Promovolve" (18px bold) and the role (12px) on the left, the role's pages as tabs, then the side switcher, the account menu (email and chevron, opening a floating menu) and, below `lg`, the menu button.
- **Tabs** (`.nav-tab`): 14px Ink Soft; hover Ink. The current page has `aria-current="page"`, bold Ink text and a 3px Ink underline from `lg` (a white fill in the stacked panel).

### Forecast strip (signature)
The shared `forecast-strip` partial draws a run of cells on the JMA scale: a day's 24 hours on Campaigns, or 14 settled days, today and 14 projected days on Wallet.
- **Delivered cells:** 14px tall, 1px gaps, filled with the cell's JMA step (Fill for zero). Each cell has a `title` with its value.
- **Projection:** cells after now, at the current pace until the budget or balance would run out, drawn at 40% opacity with a 1px dashed Ink Secondary edge.
- **Now line:** a 2px Ink bar that overhangs the strip by 4px top and bottom.
- **Axis:** 12px Ink Secondary tick labels under the strip.
- **Accessibility:** the strip is `role="img"` with the outlook sentence as its label; the sentence is also printed as text, with an Advisory or Warning tag in front when the outlook is an alert.

### Forecast legend
The shared `forecast-legend` partial explains the scale under every strip: a sentence naming the ratio ("Spend per hour, as a multiple of an even spread of the daily budget"), seven 36px swatches with each step's lower bound centred on its left edge (¼, 0.6, 1.2, 2, 3, 4×), then a dashed swatch for "at this pace" and an ink bar for "now".

### Help prose (`.prose-doc`)
In-app help renders markdown at 14px with relaxed leading. H2s sit under a Fill rule, links are underlined Link Blue, inline code is on Fill, and code blocks are a dark slab in both themes.

## Do's and Don'ts

### Do:
- **Do** put intensity data on the JMA scale through `intensity()` and show the legend under it, with ticks that match the thresholds.
- **Do** use Advisory Yellow with Ink text and Warning Red with white text for alert levels, and nothing else in those colours.
- **Do** make the primary action Ink-filled, and guard destructive actions with an ink outline button plus a confirm dialog that names the object.
- **Do** write plain states as words in a plain grey tag.
- **Do** separate regions with a 1px Rule and spacing; put a 2px Ink rule over tables and figure rows.
- **Do** lead a money region with one 48px tabular figure and a sentence that says what it means.
- **Do** mark the current tab with `aria-current="page"`, make expandable rows real buttons with `aria-expanded`, and keep text at or above Ink Secondary (7.4:1).
- **Do** keep every string at EN/日本語 parity and leave room for 日本語 lengths in tabs and buttons.
- **Do** run `scripts/build-tailwind.sh` after template class changes and commit `platform/static/tailwind.css`.

### Don't:
- **Don't** use colour for actions, plain states, headings or decoration. No coloured buttons; no green "success", no blue "info" tags.
- **Don't** use the JMA colours outside intensity data, or put a projection on a paused campaign.
- **Don't** draw cards, boxed panels, KPI-card rows or a sidebar. The page is ruled, not boxed.
- **Don't** use the Field tint except for the top bar, the nav panel and selected rows.
- **Don't** add shadows to anything that doesn't float, or corners above 2px.
- **Don't** use Mark (`gray-400`) or lighter for text.
- **Don't** hard-code neutral hex values in templates; use the gray steps so dark mode follows.
- **Don't** use coloured side stripes on notices; use the full-edge notice.
