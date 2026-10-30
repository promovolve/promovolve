# Creative designer in the Forecast world

Issue: #322. Branch: `feat/forecast-designer`, stacked on `feat/forecast-pub-admin` (#315) as stage 5 of the Forecast redesign.

## Goal

The creative designer (`/advertiser/creatives/design`, `platform/creative-designer`) should look like the rest of the dashboard, and its tools should be easy to find.

Today it has its own visual system: warm greys, an amber accent, 4–8px corners and its own theme setting. Its tools are scattered:

| Tool | Where it is today | Problem |
|---|---|---|
| Select, Text, Image, Formula, Scrim, Layout templates, Brand kit, Delete | Icon row in the canvas header | Icons only, the name is only a hover tooltip. Templates and Brand kit sit among the creation tools. |
| Replace image, Crop, Remove background, Edit formula | Properties panel | Only once the item is selected, partway down a long panel |
| Bring to front / forward / backward / to back, Group | Right-click menu | No button anywhere |
| Text align | Canvas header | Faded unless exactly one text item is selected |
| Regenerate this size | Canvas header | Split from Regenerate all |
| Regenerate all, Preview, Theme | Menu bar | |
| Rulers, Zoom | Footer | |

Success means: an author can find every tool without hovering or right-clicking, and the designer reads as the same product as the dashboard in both light and dark.

## Decisions

These were settled with the user on 2026-10-30.

1. **Accent.** Black and grey for all chrome. Focus Blue (`#2185ff`, JMA step 2) is the only accent, used for the selection outline and handles, the active tool, and the selected layer. Amber is removed.
2. **Theme.** One setting shared with the dashboard, stored under `promovolve.dashboard.theme`, tri-state Auto/Light/Dark, default Auto.
3. **Scope.** Restyle and rework the layout. Tools are grouped by the question the author is asking. The approved mockup is described under Layout.
4. **Not included.** Translating the designer into Japanese. It is English-only today and gets its own issue. Merging the Animation tab into Properties is also out of scope.

## Layout

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Top bar (Field)  Campaigns / <campaign> / <creative> [Draft]             │
│                  Undo Redo | Regenerate this size  Regenerate all |      │
│                  Preview  Save  Theme                                    │
├──────┬───────────────────────────────────────────────┬───────────────────┤
│ Rail │ Size strip: chips with thumbnails, fanout     │ Properties │ Anim │
│ Add  ├───────────────────────────────────────────────┤ Showing: <scope>  │
│ Sel  │ Selection bar: actions for the selected item  │                   │
│ Text ├───────────────────────────────────────────────┤ Nothing selected: │
│ Img  │                                               │   Banner          │
│ Form │                 Canvas (unchanged)            │   Page background │
│ Scrim│                                               │ Item selected:    │
│ ──── │                                               │   item values     │
│ Start├───────────────────────────────────────────────┤                   │
│ Tmpl │ Footer: pager · size · metrics | rulers zoom  │ Layers            │
│ Brand│                                               │                   │
└──────┴───────────────────────────────────────────────┴───────────────────┘
```

### Top bar: the whole banner

- Field background, 40px, like the dashboard top bar.
- Left: breadcrumb `Campaigns / <campaign name> / <creative name>`. "Campaigns" links to the campaigns page. The Draft badge becomes a plain Forecast tag (Fill background, label-grey text). The amber-to-red "pv" gradient badge is removed.
- Right: Undo, Redo, then Regenerate this size and Regenerate all, then Preview, Save (the one ink-filled primary button) and the theme button.
- Regenerate this size keeps its current enable/disable logic and tooltip. It moves from `canvas-header.ts`.

### Left rail: what can I add?

- 92px wide, always visible, the same in every state.
- Group "Add": Select (V), Text (T), Image (I), Formula, Scrim. Each is an icon with a text label underneath, and the shortcut shown when one exists.
- Divider, then group "Start from": Layout templates, Brand kit.
- The active tool has a Fill background, a 3px Focus Blue bar on its left edge, and a Focus Blue icon.
- Delete moves to the selection bar.

### Selection bar: what can I do to this?

- 40px, directly above the canvas, replaces the canvas header.
- Starts with the selected item's type and name in bold, for example "Image · hero.jpg".
- Nothing selected: one line of secondary text, "Nothing selected. Click an item on the banner, or add one from the left."
- Actions by selection, all labelled buttons:
  - Image: Replace, Crop, Remove background
  - Text: Align left, Align centre, Align right
  - Formula: Edit formula
  - Any single item: Bring to front, Bring forward, Send backward, Send to back, Delete
  - Two or more items: Group, plus the arrange actions and Delete
- Delete sits at the right end, separated from the rest.
- Each action calls the same function the old button or menu item called. The right-click menu stays as it is.

### Size strip

- Keeps everything it has today: the fanout summary and progress bar, a chip per size with its thumbnail, dimensions, status dot and nickname, the divider between master layouts and IAB sizes, and horizontal scrolling.
- Restyled only: square corners and black-and-grey chips. The active size gets a 2px ink underline instead of the amber fill.

### Canvas and footer

- The canvas, overlay, rulers, guides and pan/zoom behaviour are unchanged. Only colours change: the surround becomes the page colour with hairline dots, and the selection outline and handles become Focus Blue.
- Footer, left to right: page pager `‹ Page n / total ›` (moved from the canvas header), the current size and its status (for example "300×250 · Medium rectangle · Filled", taking over the canvas header's mode label and status pill), the spec metrics, then rulers and zoom on the right.

### Right panel: what are its settings?

- 300px (down from 320px), tabs Properties and Animation with the ink underline on the active tab.
- A scope line at the top: "Showing: Banner and page n" or "Showing: Image · hero.jpg".
- Nothing selected: the Banner section (`banner-config-panel`) and the page background section (`page-bg-panel`).
- Something selected: only that item's values (`props-panel`). The Replace image, Crop, Remove background and Edit formula buttons move out to the selection bar.
- Layers stays collapsible at the bottom in both states. The selected layer is Focus Blue.
- Sections are separated by a 2px ink top rule with a heading, not by boxes.

### Minimum window size

The rail adds 92px and the panel loses 20px. To keep today's minimum canvas width of about 440px, `MIN_W` in `ui/size-gate.ts` goes from 760 to 840. `MIN_H` stays 480.

## Visual system

### Colour tokens

`src/ui/tokens.ts` keeps its structure: one `COLORS` table of light/dark pairs, injected as `--cd-*` variables. Components keep using `tokens.X`. The values change to Forecast's (from `platform/tailwind.input.css`), and the amber keys are renamed.

| Token | Role | Light | Dark |
|---|---|---|---|
| `ink900` | app background, panel surface | `#ffffff` | `#111519` |
| `ink800` | raised or recessed surface | `#f6f8fa` | `#1a1f24` |
| `ink700` | Fill: hover, plain tags | `#e8edf2` | `#242a30` |
| `ink600` | Hairline: row lines | `#d6dee5` | `#323a42` |
| `ink500` | Rule: borders, input edges | `#bdc7d0` | `#424c56` |
| `ink400` | Mark: icons and rules only, never text | `#8a96a1` | `#828e99` |
| `ink300` | secondary text | `#4a5560` | `#a8b3bd` |
| `ink200` | body text | `#2a3138` | `#d8dfe5` |
| `ink100` | Ink: headings, primary fill | `#1a1a1a` | `#f2f5f8` |
| `field` (new) | top bar, selected rows | `#dfe7ee` | `#222a33` |
| `focus` (was `amber`) | selection, handles, active tool | `#2185ff` | `#2185ff` |
| `focusMuted` (was `amberMuted`) | secondary selection marks | `rgb(33 133 255 / 0.45)` | `rgb(33 133 255 / 0.55)` |
| `focusBg` (was `amberBg`) | selected backgrounds | same as `field` | same as `field` |
| `canvas` | recessed thumbnail surface | `#e8edf2` | `#0c0f12` |
| `ok` | success text and marks | `#146c2e` | `#8fd6a3` |
| `warn` | advisory, used as a fill with Ink text | `#fff500` | `#fff500` |
| `err` | Warning red, error text and marks | `#d70a00` | `#ff6a5f` |

Rules that come with the values:

- `ink400` is Mark (3:1) and must not be text. The 17 files that use it are checked. Any text set in `ink400` moves to `ink300`.
- `warn` is Advisory Yellow and is unreadable as text on white. Its 5 uses are checked. A tag becomes a yellow fill with Ink text. Plain text becomes Ink with a yellow marker.
- The Colour Is Data rule from `DESIGN.md` applies. No other hue appears in the chrome.

### Hard-coded colours

About 90 colours are written directly in 18 files outside `tokens.ts`. Each one is sorted into one of three groups:

- **Chrome** (panels, buttons, modals, rulers, overlay chrome): moves onto a token.
- **Shadows and dim layers** behind modals and menus: stay as they are, neutral black at low opacity.
- **Banner content** (anything rendered into the advertiser's creative, colour pickers' swatches, defaults for new items): unchanged, because it belongs to the creative, not to the editor.

The page background in `templates/advertiser/creative-design.html` (`#0a0a0b`) becomes `var(--cd-ink900)`, with a matching light or dark value set before the bundle loads, so the light theme no longer flashes dark.

### Type and corners

- `creative-design.html` links the self-hosted `fonts/biz-udpgothic.css` through the `asset` helper, the same file the dashboard layout uses. The Vite harness `index.html` links it too.
- `tokens.sans` becomes `"BIZ UDPGothic", system-ui, sans-serif`. `tokens.mono` drops JetBrains Mono, which was never loaded, and keeps the system monospace stack.
- This changes the editor's own text only. Fonts inside the banner (`canvas-fonts.ts`) are untouched.
- Body 13px in the chrome, labels 12px, tabular figures for every number. That is one step denser than the dashboard's 14px on purpose: an editor packs more controls beside the canvas, as in the approved mockup.
- `r4`, `r6` and `r8` all become 2. The tokens stay so call sites don't change. A follow-up can collapse them into one.

## Theme

- The designer reads and writes `promovolve.dashboard.theme` (`auto` / `light` / `dark`). Auto follows the OS and keeps following it live.
- Migration, once on startup: if the dashboard key is absent and `promovolve.designer.theme` is present, copy its value to the dashboard key, then remove the designer key. Nobody's chosen theme silently flips.
- The designer keeps setting `data-cd-theme` on `<html>` for its own variables. It does not need the dashboard's `.dark` class.
- The theme button in the top bar cycles Auto → Light → Dark, labelled "Theme: Auto" and so on, like the dashboard's account menu item.
- Not syncing live between two open tabs. The other tab picks it up on its next load.

## Files

| File | Change |
|---|---|
| `src/ui/tokens.ts` | New values, `field` added, amber keys renamed to focus, BIZ UDPGothic, radii 2, shared theme key and migration |
| `src/index.ts` | `renderShell` gets the new grid: top bar, rail, size strip, selection bar, canvas, footer, panel |
| `src/ui/toolbar.ts` → `src/ui/tool-rail.ts` | Vertical labelled rail with the two groups; Delete removed |
| `src/ui/selection-bar.ts` (new) | Per-selection actions, empty-state line |
| `src/ui/canvas-header.ts` | Deleted. Its pieces move to the top bar, the selection bar and the footer. |
| `src/ui/align-toolbar.ts` | Folded into the selection bar's text actions |
| `src/ui/menu-bar.ts` | Becomes the top bar: breadcrumb, Draft tag, whole-banner actions, theme button |
| `src/ui/history-buttons.ts`, `src/ui/preview-button.ts`, `src/ui/save.ts` | Restyled, mounted in the top bar |
| `src/ui/canvas-foot.ts` | Gains the pager and the current-size line |
| `src/ui/sidebar.ts` | Scope line; shows banner and page sections or item values depending on selection |
| `src/ui/props-panel.ts` | Replace image, Crop, Remove background and Edit formula buttons removed (they live in the selection bar) |
| `src/ui/size-matrix.ts` | Restyled chips, ink underline on the active size |
| `src/ui/size-gate.ts` | `MIN_W` 760 → 840 |
| The 18 files with hard-coded colours | Chrome colours moved onto tokens |
| `platform/templates/advertiser/creative-design.html` | Font link, themed background |
| `platform/creative-designer/index.html` | Font link for the Vite harness |

## What does not change

The canvas renderer, the selection overlay's behaviour, rulers, guides, pan and zoom, the banner component, saving and publishing, the saved data format, keyboard shortcuts and the right-click menu. No backend or Go handler changes beyond the template.

## Testing

- `npm test` (vitest) passes unchanged.
- `npm run build` and the fanout to `platform/static` succeed, and `go test ./...` in `platform` passes (the template and embedded bundle).
- Playwright against the Vite harness (`npm run serve`, :5173) checks the mechanics:
  - every tool in the old table is reachable from the rail, the selection bar or the top bar, without hovering or right-clicking
  - selecting an image, a text item, a formula and two items shows the right actions, and each action does what the old control did
  - the right panel switches between banner/page settings and item values on selection
  - the theme follows `promovolve.dashboard.theme`, and an existing `promovolve.designer.theme` value is carried over once
  - the size gate appears below 840px wide
  - screenshots in light and dark for review
- The user then reviews the look on the Vite harness in light and dark. Nothing is committed until they approve it.

## Risks

- **Muscle memory.** Existing authors lose the icon row in the header. The shortcuts and right-click menu are unchanged, which softens it.
- **Narrower canvas** on small laptop screens because of the rail. The size gate keeps the canvas at about 440px or wider.
- **Stacking.** This branch sits on #315. Each earlier Forecast PR must be merged in order, and this branch rebased onto `main` after #315 merges.
