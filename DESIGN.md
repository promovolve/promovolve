---
name: Promovolve
description: The dashboard for an open-source contextual ad network: warm paper-and-ink neutrals, one amber accent.
colors:
  brand-amber: "#d35f00"
  brand-amber-hover: "#c35800"
  brand-amber-soft: "#fce4c4"
  brand-ink: "#b05000"
  brand-ink-night: "#ec8c45"
  surface: "#ffffff"
  paper: "#fcfaf7"
  paper-shade: "#f5f3f0"
  rule: "#eae7e4"
  rule-strong: "#d3d0cd"
  ink-faint: "#a8a49f"
  ink-muted: "#6b6762"
  ink-soft: "#5b5753"
  ink-body: "#403c38"
  ink-deep: "#272320"
  ink: "#14110e"
  ink-black: "#0c0806"
  night-void: "#0f0d0b"
  night-surface: "#181614"
  night-rule: "#2d2925"
  success: "#16a34a"
  danger: "#dc2626"
  warning-fill: "#fffbeb"
  warning-ink: "#b45309"
typography:
  headline:
    fontFamily: "ui-sans-serif, system-ui, sans-serif, 'Apple Color Emoji', 'Segoe UI Emoji'"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: "2rem"
  title:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 600
    lineHeight: "1.75rem"
  body:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: "1.25rem"
  label:
    fontFamily: "ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: "1rem"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: "1rem"
rounded:
  sm: "4px"
  md: "6px"
  lg: "8px"
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
    backgroundColor: "{colors.brand-amber}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
    typography: "{typography.body}"
  button-primary-hover:
    backgroundColor: "{colors.brand-amber-hover}"
    textColor: "{colors.ink}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-body}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
  button-secondary-hover:
    backgroundColor: "{colors.paper}"
  button-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
  button-sm:
    padding: "4px 12px"
    typography: "{typography.label}"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.lg}"
    padding: "24px"
  card-tight:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.lg}"
    padding: "16px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
    typography: "{typography.body}"
  badge-neutral:
    backgroundColor: "{colors.paper-shade}"
    textColor: "{colors.ink-soft}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
    typography: "{typography.label}"
  sidebar:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper-shade}"
    width: "240px"
    padding: "16px"
  nav-item-active:
    backgroundColor: "{colors.ink-body}"
    textColor: "{colors.surface}"
    rounded: "{rounded.sm}"
    padding: "8px 12px"
---

# Design System: Promovolve

## Overview

**Creative North Star: "The Editor's Desk"**

The dashboard is the desk beside the magazine. It is a calm, orderly workspace where advertisers set budgets, publishers approve ads and operators settle the books. The expressive work happens in the page-turning creative itself. The desk stays out of its way: warm paper surfaces, ink-coloured text, one amber accent for the action that matters, and numbers set plainly enough to audit.

The density suits work, not marketing. Body text is 14px, metadata is 12px, and tables and forms carry most of the content. Surfaces are flat, separated by hairline borders rather than shadows. A permanently dark sidebar frames the paper-toned work area in both themes, like the dark edge of a desk around a sheet of paper.

Dark mode is a first-class theme, not a filter. Every neutral is a CSS variable that keeps its *contrast role* when the theme flips, so a template written for light mode reads correctly in dark without per-page edits.

**Key Characteristics:**
- Warm neutrals (paper and ink), never cool blue-greys
- One accent: amber (`brand-amber`), with dark ink on top, never white
- Flat surfaces; shadows only on things that float (menus, popovers)
- An always-dark sidebar that is identical in both themes
- System fonts, so English and 日本語 both render in the reader's native face

## Colors

A warm paper-and-ink neutral scale carries almost everything. One amber accent marks primary actions and the active state, and semantic red, green and amber appear only where something is wrong, healthy or pending.

### Primary
- **Press Amber** (`brand-amber`): primary buttons, the active side-switcher pill, focus rings (at 20% alpha), and the accent bar on a selected inbox row. It is the same in both themes on purpose.
- **Press Amber, Pressed** (`brand-amber-hover`): hover state for primary buttons.
- **Amber Wash** (`brand-amber-soft`): selected-row and selected-option fill in light mode. In dark mode it becomes translucent amber at 22% alpha.
- **Amber Ink** (`brand-ink`, `text-brand-ink`): amber for *text*, meaning links and inline emphasis. It is theme-aware: `brand-ink` in light mode (5.0:1 on Paper) and `brand-ink-night` in dark mode (7.2:1). The fill amber is never used as text.

### Neutral
- **Surface** (`surface`): cards, inputs and table bodies. In dark mode it becomes **Night Surface** (`night-surface`).
- **Paper** (`paper`): the page background behind cards. In dark mode it becomes **Night Void** (`night-void`).
- **Paper Shade** (`paper-shade`): subtle fills, neutral badges and faint dividers.
- **Rule** (`rule`) and **Strong Rule** (`rule-strong`): the standard card border and the input/secondary-button border. In dark mode the standard border is **Night Rule** (`night-rule`).
- **Ink scale**, faintest to darkest: Faint (`ink-faint`) for decorative marks and disabled controls only, Muted (`ink-muted`) for secondary text, hints and placeholders (5.0:1 or better on every light surface), Soft (`ink-soft`), Body (`ink-body`) for button and label text, Deep (`ink-deep`), and **Ink** (`ink`) for headings, body text and the sidebar background.

### Semantic
- **Danger** (`danger`): destructive buttons and error text. Error callouts use a red-50 fill with a red-200 border.
- **Success** (`success`): confirm buttons and healthy status.
- **Warning** (`warning-fill` / `warning-ink`): pending or degraded callouts and badges.
- Badges in every family use a pale fill with dark text in light mode, and a translucent fill (15% alpha) with light text in dark mode.

### Named Rules
**The Dark Ink on Amber Rule.** Text on Press Amber is always Ink (`ink`), which reaches 4.8:1. White on amber is 3.9:1 and fails AA. The dark-mode override pins primary-button text to Ink so the theme flip can't invert it.

**The Contrast Role Rule.** Neutrals are referenced by their Tailwind step (`gray-50`…`gray-950`, `white`), and each step keeps its role across themes: 50 is the page background, white is a raised surface, 900 is the strongest text. Never hard-code a neutral hex in a template. Use the step, and the theme follows.

**The One Accent Rule.** Amber is the only brand colour. Indigo and blue are not secondary primaries (the indigo second primary in older creative-editor pages is deprecated). Blue appears only inside the info badge and info callout.

## Typography

**Body Font:** the system UI sans (`ui-sans-serif, system-ui, sans-serif`)
**Mono Font:** the system monospace, used for IDs, tokens, digests and code

**Character:** no brand typeface on purpose. The OS face renders Latin and CJK together at native quality, which matters for EN and 日本語 parity, and keeps the desk quiet next to the creative.

### Hierarchy
- **Headline** (600, 24px/32px): `.page-title`, one per page.
- **Title** (600, 18px/28px): section headings within a page.
- **Body** (400, 14px/20px): almost everything, including tables, forms, buttons and prose.
- **Label** (500, 12px/16px): form labels at 14px weight 500 (`.form-label`), plus badges, hints, timestamps and table metadata at 12px.
- **Mono** (400, 12px): machine identifiers only.
- The auth pages (login, setup, recover, request account) set the wordmark "Promovolve" at 30px weight 600, centred.

### Named Rules
**The Two-Size Rule.** Working text is 14px or 12px. A larger size has to be a page or section heading. Don't create emphasis by size inside tables or forms; use weight 500–600 or Ink colour.

## Layout

The signed-in shell is a full-height row. On the left is a fixed 240px sidebar that never shrinks and scrolls on its own. On the right is the main pane (`min-w-0`, scrolls horizontally if it must), so wide tables and long URLs scroll inside the pane instead of squeezing the nav. Signed-out pages centre one narrow card (`max-w-md`) on Paper.

Content columns are capped per page type: `max-w-2xl` for forms and settings, `max-w-4xl`/`max-w-5xl` for tables and reports. Rhythm is a 4px grid. Gaps of 8–12px sit inside groups, 16–24px between them, and cards pad 24px (16px when tight). Table cells pad 24px horizontally and 8px vertically.

Below `md` (768px) the sidebar becomes an off-canvas drawer behind a 48px always-dark top bar holding the wordmark, the role and a menu button. A closed drawer is `visibility: hidden`, so its links leave the tab order. A backdrop or Escape closes it. Main padding drops to 16px. Stat-card rows go to two columns (`grid-cols-2 sm:grid-cols-4`), and two-column forms stack to one column below `sm`. Tab strips scroll sideways rather than wrap.

The read-only admin view-as session pins an amber banner (`amber-400`, Ink text) to the top and dims every mutating control to 45% opacity, so read-only is visible before anyone tries to act.

## Elevation & Depth

The system is flat. Surfaces sit on Paper with a 1px Rule border, and layering is tonal (Paper → Surface) rather than shadowed. Shadows are a sign that something floats above the page.

### Shadow Vocabulary
- **Float** (Tailwind `shadow-lg`): dropdown menus, typeahead lists and popovers (the date picker), always paired with a border.
- **Lift** (Tailwind `shadow-sm`): rare small raised controls.

### Named Rules
**The Only-Floaters-Cast-Shadows Rule.** A card at rest never gets a shadow. If something needs to stand out without floating, give it a border or a fill.

## Shapes

Gentle, consistent corners. Controls (buttons, inputs, menus) use 6px (`rounded-md`), surfaces (cards, panels, code blocks, popovers) use 8px (`rounded-lg`), badges are full pills, and sidebar nav items use 4px. Bare `rounded` is deprecated. Borders are 1px hairlines. The only thicker line is the 4px left rule on blockquotes and the 3px inset amber bar on a selected inbox row.

## Components

Flat and restrained. Components are defined once as `@layer components` classes in `platform/tailwind.input.css` and used by name in templates.

### Buttons
- **Shape:** 6px radius, 8px × 16px padding, 14px weight 500, with a 6px gap for an icon.
- **Primary** (`.btn .btn-primary`): Press Amber with Ink text. It marks the one main action in a view.
- **Secondary** (`.btn .btn-secondary`): Surface with a Strong Rule border and Body ink. Hover fills it with Paper.
- **Success / Danger** (`.btn-success`, `.btn-danger`): solid green or red with white text, pinned white in dark mode.
- **Compact** (`.btn-sm`): 4px × 12px padding, 12px text.
- **Disabled:** 50% opacity with a not-allowed cursor.

### Badges
- **Style** (`.badge` + `-info`/`-success`/`-warning`/`-danger`/`-neutral`): a 12px weight-500 pill, padded 2px × 8px, with a pale fill and dark text, or a translucent fill and light text in dark mode.
- **Remove × button** (`.chip-x`): inherits the badge's text colour at 70% opacity and reaches 100% on hover. It has a 24px hit area, pulled back with negative margins so the pill stays 20px tall. It always carries an `aria-label` from `Remove %s` or `Unblock %s`.

### Cards / Containers
- **Corner Style:** 8px.
- **Background:** Surface on Paper.
- **Shadow Strategy:** none (see Elevation & Depth).
- **Border:** 1px Rule.
- **Internal Padding:** 24px (`.card`), or 16px (`.card-tight`).

### Inputs / Fields
- **Style** (`.form-input`): full width, 8px × 12px padding, 1px Strong Rule border, 6px radius, Surface fill and Ink text, with Faint placeholders.
- **Labels:** every `.form-label` points at its field with `for`/`id`. Ids inside a `{{range}}` carry the row's `.ID`. A radio group is a `<fieldset>` with a `<legend class="form-label">`, and a date picker takes `"Id"`, which its label also targets.
- **Focus:** the border turns amber and a 2px amber ring at 20% alpha appears. The default outline is removed in favour of that ring.
- **Label / Hint:** `.form-label` above the field, `.form-hint` 12px below it.
- **Safety net:** any raw `<input>`, `<select>` or `<textarea>` gets themed Surface and Ink from the base layer, so it can never render white-on-dark.

### Navigation
- **Sidebar:** Ink background and light text, frozen to the light scale (`.theme-light-scope`) so it looks identical in both themes. The wordmark sits at 18px weight 600 with the role ("Advertiser Dashboard") under it at 12px.
- **Phones:** below `md` the sidebar is the drawer described in Layout.
- **Items:** 14px text, 8px × 12px padding, 4px radius. The active item gets an `ink-body` fill with white text. Idle items hover to `ink-deep`.
- **Side switcher:** when one org holds both accounts, a two-button segmented control shows the active side as an amber pill with Ink text.
- **Account footer:** the email, an icon-only theme toggle (auto/light/dark), then account links, set off by a top rule.

### Charts
Charts call `chartTheme()` (defined in `layout.html`) when they are drawn. It returns axis ink (`ink-muted`), grid (`rule`), the surface colour (for the 2px rings on points) and a categorical palette with separate light and dark steps of the same five hues: blue, aqua, yellow, green and violet, plus amber as a sixth. Both sets were checked with the dataviz validator. Every chart with two or more series has a legend and a table, so colour is never the only way to tell series apart. Inline SVG charts use theme classes (`stroke-gray-*`, `fill-brand`) rather than fixed hex values.

### Help prose (`.prose-doc`)
In-app help renders markdown at 14px with relaxed leading. H2s are separated by a top Rule, links are underlined `brand-ink`, inline code is set on Paper Shade, and code blocks stay a dark Ink slab in both themes.

## Do's and Don'ts

### Do:
- **Do** use the canonical classes (`.btn-*`, `.card`, `.badge-*`, `.form-*`, `.page-title`/`.page-sub`) for new markup. Swap ad-hoc utilities for them whenever you touch a page.
- **Do** run `scripts/build-tailwind.sh` after any template class change and commit `platform/static/tailwind.css`, or the new class ships unstyled.
- **Do** put Ink text on amber. Pin it with a `.dark` override if the element sits on a literal colour.
- **Do** add a `.dark` override in `tailwind.input.css` when you introduce a literal (non-grey) Tailwind colour step that isn't covered yet.
- **Do** keep English and 日本語 strings at parity, and leave room for 日本語 line lengths in buttons and nav.

### Don't:
- **Don't** add a second accent colour. Indigo and blue as primaries are deprecated.
- **Don't** put shadows on resting cards or panels.
- **Don't** use `ink-faint` (`gray-400`, 2.5:1) or lighter for text that carries meaning on a light surface. The floor for secondary text is `ink-muted` (`gray-500`). The always-dark sidebar is the exception: there `gray-400` is 7.6:1.
- **Don't** use `text-brand` for text. The fill amber is 3.9:1 on white. Use `text-brand-ink`.
- **Don't** use 500/600 steps of amber, orange or green for status text. They are about 3.2–3.6:1. Use 700.
- **Don't** hard-code chart colours. Take them from `chartTheme()` (see Components), which follows the theme.
- **Don't** hard-code neutral hex values in templates. They won't follow the theme.
- **Don't** use bare `rounded`. Use `rounded-md` for controls and `rounded-lg` for surfaces.
