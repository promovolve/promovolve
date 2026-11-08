> Renamed 2026-11-08: the format is **Fold-out** (折り込みポスター), stored as `format: "foldout"`. 'Map' below means Fold-out.

# Map (gatefold poster) format — design

Approved in conversation 2026-11-08, section by section. Prototype:
`platform/banner-component/dev/map-check.html` (`?poster`), throwaway.

## Goal

A second creative format beside the Magazine. The Magazine is three pictures
with three captions, turned like pages. The Map is **one picture with three
captions**: a 9:16 poster folded in thirds, like the gatefold poster in an
old magazine. The reader taps the slot, the folded poster lifts out and
unfolds downward panel by panel, and the folds run straight through the
image. Each panel is one beat of the story arc: the cover hook, the middle,
and the payoff with the CTA.

This is a deliberate exception to the fixed three-page rule. A Map creative
is one page.

## Scope

In:
- the Format choice in the Creative Editor;
- the hand-off to the designer;
- a deterministic poster composer;
- designer changes for one-page Map;
- the `map-fold.ts` delivery module;
- tests;
- EN/日本語 copy.

Out, on purpose (v1):
- **AI-composed posters.** The first layout is deterministic. Auto-layout's Gemini pass stays Magazine-only.
- **LPExtractor prompt changes.** The story arc still writes three beats.
- **Video backgrounds in Map.**
- **Switching format inside the designer.** It's chosen once, in the editor.
- **Map on a landscape phone.** The map closes automatically when the phone is rotated.

## 1. Creative Editor (`platform/templates/advertiser/creative-editor.html`)

**New step 2, Format.** It sits between Landing page and Story arc, and the
later steps renumber. It offers two cards:
- *Magazine — 3 pages*;
- *Map — one poster, folds in thirds*.

Alpine state is `format: 'magazine' | 'map'`, with `'magazine'` the default.

**Story arc.** Unchanged. For Map, the subtitle reads "how the 3 panels persuade".

**Pages step, Map version.** The step title becomes "Poster". It has:
- **One 9:16 image slot.** It's the single target for the image pool's click-to-place.
- **The three beats' copy beside it,** labelled *Top fold*, *Middle* and *Bottom*, with dashed fold rules between them.

The image pool, uploads and library toggles are unchanged.

**Hand-off.**
- `pagesJsonStr()` still emits three pages. In Map, every page carries the poster image as `img`.
- A new hidden input `format` goes in `#designer-handoff`, containing `"map"` or empty.

## 2. Go hand-off (`platform/internal/handler/pages.go`, `/advertiser/creatives/design`)

The handler reads `format` next to `templateId`. It accepts only `"map"`;
anything else becomes empty.

For a **first-time design** (no `creativeId`, no saved `bannerConfigJson`),
`format=map` seeds the designer context's `bannerConfigJson` with
`{"format":"map"}`. When a creative is resumed, the saved `bannerConfigJson`
wins and `format` is ignored.

## 3. Designer (`platform/creative-designer`)

**First open of a Map hand-off.** This happens when `bannerConfig.format === "map"` and three hand-off pages arrive with no layout yet.
- **`posterLayoutFor(beats, img, kit)`** lives in `auto-layout.ts`, next to `presetLayoutFor`. It's deterministic and never calls Gemini.
  - The image is `cover` across the full 9:16 page.
  - Each beat's tag, headline and sub sit inside its third's safe area, inset from the crease lines at ⅓ and ⅔ so no text is cut by a fold.
  - The CTA goes in the bottom band.
  - The result goes through the existing `legibilizeItems` contrast pass.
- **The pages collapse to one.** The poster becomes `pages[0].banners["mobile-expanded"]` (the 9:16 portrait master; `layout` stays the hidden wide master), and `pages[0]`'s flat fields stay as beat 1, so the collapsed-size fanout builds the slot cover from the hook and the poster image. `pages[1]` and `pages[2]` are dropped.
- **No recompose on resume.** A resumed one-page Map isn't composed again, so the author's edits persist.

**Banner config panel (`ui/banner-config-panel.ts`).**
- No Format control.
- The Entrance row is hidden when `format === "map"`, because the map has its own open and close and Entrance describes how the stacked cards arrive.
- Reading direction, Paper, Tempo and Back stay. The map's backs are that paper stock.

**Expanded 9:16 canvas.**
- One page.
- The pager (`ui/canvas-foot.ts`) is hidden.
- Dashed **fold guides** at ⅓ and ⅔, labelled "fold", go on the overlay layer. They're never saved and never delivered.

**Video.**
- The video-background option (`ui/page-bg-panel.ts`) is disabled, with the note "Video backgrounds aren't available in Map format".
- If page 1 already has a video, the designer shows a warning. Delivery renders the page colour or texture instead.

**Unchanged.**
- Collapsed size tabs and their auto-layout fanout, which make the slot cover.
- Regenerate.
- The preview eye (`ui/preview-button.ts` passes the whole `bannerConfig`).
- The publisher approval preview.

**One-page audit.** Find and fix the places that assume three pages, such as
`ui/confirm-delete.ts`, `selection-model.ts` and the regenerate flow. One-page
Map creatives must behave the same in the designer, the preview and delivery.

## 4. Data and persistence

**What a Map creative is.**
- **One page,** `pages[0]`:
  - `banners["mobile-expanded"]`: the 9:16 poster (the portrait master; `layout` stays the wide master);
  - `banners`: the collapsed sizes;
  - flat fields from beat 1.
- **Plus `bannerConfig.format = "map"`.**

**The type.** `BannerConfig.format?: "map"` goes in
`platform/banner-component/src/types.ts`, the one definition shared by the
designer and delivery. When it's absent, the creative is a Magazine, so
existing creatives don't change.

**Transport, already end to end.** `bannerConfigJson` is an opaque string
through:
- save (`ui/save.ts:215`);
- resume (`index.ts:202`, `state.ts:38`);
- the Go form;
- Scala `bannerConfigJson`;
- the `banner_config_json` column;
- the serve `config` attribute.

No Scala change, no DB migration.

**Safety.**
- **Old cached component with a Map config:** it ignores `format` and plays a one-page magazine, still a working ad.
- **Unknown `format` value:** treated as Magazine.
- **A Map config with more than one page:** delivery uses `pages[0]`.

## 5. Delivery (`platform/banner-component`)

**New module `src/map-fold.ts`.**
- **Entry point:** `_expand()` in `banner.ts` branches to it when `config.format === "map"`, instead of calling `renderOverlay()`.
- **Pure exported maths, testable:**
  - the hinge schedule;
  - spring stepping;
  - the release decision;
  - the panel slicing geometry.

**Geometry.**
- The poster is `pages[0]`'s 9:16 expanded layout at the reader sheet size (`sheetSizeFor` / `sheetFitPct`).
- It's cut into three horizontal panels of about 27:16. Each panel's face renders the whole page offset by −i × panel height, inside a clip.
- The map unfolds **downward on every device**, as a Z-fold. Panel 1 is the top third, hinged at its bottom edge.
- The vanishing point (`perspective()` inside the map transform) is centred on the cover.
- The cover crease rests at 178° to avoid z-fighting. The inner creases fold flat at 180°.

**Opening.**
- A tap on the slot (`wireCollapsedClick`) opens it. There's no drag from the slot.
- The map lifts from the slot rect to the centre while the collapsed frame crossfades into the folded top panel.
- A critically damped spring then unfolds it, with overlapping hinges, so there's no stall between panels.
- The slot rect is measured once per open and once per close.

**While open.**
- A **tap on the poster** emits `cta-click` and opens the landing page. Drags are excluded.
- A **drag up** folds it back under the thumb, which is manipulation, not navigation. On release:
  - it closes past halfway, or on a flick (projected from the velocity);
  - otherwise it springs open, carrying the flick's velocity.
- **Closing** works through the close control, a tap on the backdrop, or Escape. It folds up and flies back to the slot.
- **Dog-ear** (`buildDogEar`) sits on panel 1's top-right corner, mirrored for RTL, and shows only when the map is fully open.

**Paper identity** (`paper.ts`).
- Grain on the faces.
- `paperBackStock` / `paperBackColor` with fiber and mottle on the backs.
- A box-shadow contact shadow. A filter is avoided because it would flatten preserve-3d.
- The backdrop fades through its background alpha, not opacity, so the map isn't faded with it.

**Item entrances.** They play once the unfold lands, synchronized across the three panel copies.

**Edge cases.**
- **Reduced motion:** a plain fade in and out.
- **Phone rotated to landscape:** closes automatically.
- **Desktop resize:** the map re-lays out.
- **`videoBg` in Map:** ignored.

## 6. Signals

Same events as today, so the auction and reporting don't change.

| Moment | Event | Auction meaning |
|---|---|---|
| Slot visible | `impression` | unchanged |
| Tap on the slot opens the map | `magazine-expand` (detail gains `format: "map"`) | EXPAND |
| Tap on the open poster | `cta-click` | tap-through |
| Dog-ear | `dogear-fold` / `dogear-unfold`, `page: 0` | DOG-EAR |
| Map closed (any way) | `magazine-collapse` | unchanged |

- No `magazine-page-changed`.
- A drag that folds partway and springs back open emits nothing.

## 7. Tests

- **Banner component (vitest):** `map-fold.ts` pure functions:
  - the hinge schedule (order, overlap, ends at 0 and at the maximum);
  - the spring settles and clamps at the bounds;
  - the release decision (tap, flick, past halfway, rested finger with velocity zeroed after 80 ms);
  - the slicing (three panels, offsets at thirds).
- **Designer (vitest):**
  - `posterLayoutFor` puts each beat inside its band and clear of the creases, puts the CTA in the bottom band, and passes contrast.
  - The first open collapses three hand-off pages to one; a resumed one-page Map isn't recomposed.
- **Go (`creative_design_render_test.go`):**
  - a POST with `format=map` seeds `{"format":"map"}`;
  - a resumed draft keeps it;
  - an invalid `format` is dropped.
- **Hostile-environment suite:** a Map fixture.
- **Visual:**
  - `dev/map-check.html` is retired, and `dev/index.html` gains a Map creative on the real component.
  - Playwright captures open, held drag poses and closed, at 390×844 and 1280×800, plus the per-frame motion trace.
  - The user reviews the screenshots before commit.
  - One real-iPhone check over `vite --host`.
