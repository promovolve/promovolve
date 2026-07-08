# Self-Hosted Web Fonts (exact LP font in expanded view)

> **STATUS: IMPLEMENTED** (2026-05-31). Files: `GoogleFontCatalog.scala`,
> `ImageStorage.scala` (storeFont/fontExists), `GoogleFontProvisioner.scala`,
> `CreativeProcessor.scala` (familiesFromPagesJson + provisionFonts hook, both
> non-draft publish paths), `Server.scala` (wiring), `banner-component/src/
> font-catalog.ts`, `banner.ts` (preloadExpandedFonts via FontFace API),
> `creative-editor.html` (_snapFont keeps allow-listed real family). Tests:
> `FontProvisionSpec` (Scala, catalog + extraction), `font-catalog.test.ts`
> (banner, 10). Compiles + all green. **Deploy note:** banner.ts changed →
> rebuild the banner component (`npm run build` / release fanout) so the served
> bundle includes the font preload. **Verify owed:** publish a creative whose LP
> uses an allow-listed font; confirm the woff2 lands in R2 at
> `fonts/<slug>-latin.woff2` and the expanded view loads it from our CDN.
>
> Implementation note: the banner uses Shadow DOM, so instead of injecting an
> `@font-face` `<style>` into the overlay (which wouldn't cross the shadow
> boundary), `banner.ts` registers faces document-wide via the FontFace API
> (`document.fonts.add`) during the existing `firePreload()` — that single call
> both loads (preloads) and makes the face available to shadow-rendered text.
> No `render-overlay.ts` / `BannerConfig` / serve-path change was needed; the
> CDN origin is derived from the creative's own image URLs.

**Goal:** Render the LP's *actual* font face (e.g. Montserrat) in the banner's
**expanded** view, self-hosted from our own R2/CDN — never calling Google Fonts
at the visitor's runtime (privacy + the German Google-Fonts ruling + OSS
transparency). Collapsed view keeps the snapped system fallback. Builds on the
already-shipped LP brand-kit extraction + font snapping
([lp-brand-kit-extraction.md](lp-brand-kit-extraction.md)).

## Locked decisions
- **Fetch timing:** at **publish** (copy final; only ships fonts for real creatives).
- **Catalog gate:** **static allow-list** (~40 popular Google fonts). No API key,
  no live catalog call. Family not on the list → snap to system (today's behavior).
- **Subsetting:** **full latin woff2** (no per-creative glyph subset). The file is
  then *identical for every creative using that family* → store once, dedup, reuse.

## The simplification these decisions buy us
Because the font is the full latin file and the family is allow-listed, the R2
object is a pure function of the family name:

    key  = fonts/<slug>-latin.woff2          (slug = lowercased, spaces→hyphens)
    url  = $CDN_BASE_URL/fonts/<slug>-latin.woff2

So **no per-creative font persistence and no new entity field** are needed. The
only stateful action at publish is "ensure this family's woff2 exists in R2"
(idempotent). The expanded view derives everything else from the layout it
already has + the allow-list + the CDN base.

### CSS font-stack trick (no renderer branching)
The editor stores the kit font as a *stack* with the system fallback baked in:

    allow-listed:    "Montserrat, sans-serif"
    not allow-listed: "sans-serif"          (unchanged from today)

`template-apply.ts` already copies `kit.fonts[idx]` onto `item.fontFamily`, and
the renderer emits `font-family:${ff}` verbatim. Result:
- **Collapsed view** (no @font-face injected): "Montserrat" is unknown → browser
  uses `sans-serif`. Clean, no FOIT, no extra bytes on the common path.
- **Expanded view** (@font-face injected + preloaded): "Montserrat" resolves to
  the self-hosted woff2. Exact brand font.

The same string works in both views; the only difference is whether the
@font-face is present. Zero branching in the renderer.

## Components & file changes

### 1. Canonical allow-list (one source, mirrored)
`fonts/<slug>-latin.woff2` slug + the ~40 families. Lives in 3 places (small,
acceptable; keep them in sync — add a comment cross-linking):
- **Scala** `modules/core/.../publisher/assets/GoogleFontCatalog.scala` (gate for fetch)
- **Banner TS** `platform/banner-component/src/font-catalog.ts` (gate for @font-face + preload)
- **Editor JS** allow-list inside `creative-editor.html` `_snapFont` (keep real family when listed)

Each entry: `{ family, slug }`. Slug derives the R2 key + url everywhere.

### 2. R2 stores woff2 (Scala)
`ImageStorage.mimeToExt` (`modules/core/.../assets/ImageStorage.scala:23`):
add `case "font/woff2" | "application/font-woff2" => "woff2"`. The `store(hash,
bytes, mime)` API is already generic over mime — but it keys objects as
`assets/<hash>.<ext>`. Fonts want a *stable, human* key (`fonts/<slug>-latin.woff2`),
not a content hash, so add a small dedicated path rather than reusing the
hash-based image path:
- New `FontStorage` (or extend R2ImageStorage) with `storeFont(slug, bytes):
  Future[Unit]` writing to `fonts/<slug>-latin.woff2`, and `fontExists(slug):
  Future[Boolean]`. Reuses the same S3 client/bucket/settings.

### 3. Font provisioner (Scala, core)
`GoogleFontProvisioner.ensure(family): Future[Unit]`:
1. Look up family in `GoogleFontCatalog`; not found → no-op (return unit).
2. `fontExists(slug)` → already there → no-op (dedup).
3. Fetch `https://fonts.googleapis.com/css2?family=<Family>&display=swap` **with a
   modern desktop User-Agent** (UA-sniffing is how Google decides woff2 vs ttf —
   without a real UA you get ttf or an error). Parse the FIRST
   `src: url(https://fonts.gstatic.com/...woff2)` for the `latin` unicode-range
   block.
4. Download that woff2; `storeFont(slug, bytes)`.
All network is server-side, at publish, best-effort (failures log + fall back to
the system stack — never block publish).

### 4. Publish hook (Scala, api)
In the creative save/publish path: parse the saved pages, walk text items'
`fontFamily`, take the first token of each stack, dedupe, and
`provisioner.ensure(family)` for each (fire-and-forget with logging; publish does
not await font fetch). Idempotent + dedup'd across the whole platform.

### 5. Serve emits the CDN base (Scala, api)
`bannerConfigJson` (built in `ServeRoutes.scala`) gains one constant field:
`fontBaseUrl: "$CDN_BASE_URL/fonts"`. That's all serve needs — the component
derives families + slugs itself. No serve-path scan of layout, no hot-path cost.

### 6. Expanded render + preload (Banner TS)
- `BannerConfig` (`types.ts`) gains `fontBaseUrl?: string`.
- New `font-catalog.ts`: allow-list + `slugFor(family)` + `faceUrl(base, family)`.
- `collectExpandedFontFamilies(pages)`: walk expanded layout items' `fontFamily`,
  take first token, keep only allow-listed → `{family, url}[]`.
- `render-overlay.ts buildOverlay()`: inject a `<style>` with one `@font-face`
  per derived family (`font-family:"Montserrat"; src:url(<url>) format("woff2");
  font-display:swap`). (There are already two injected `<style>` blocks here.)
- `banner.ts firePreload()` (the existing IntersectionObserver-driven expanded
  image warm-up): also `fetch(url)` / `new FontFace().load()` each font url so it's
  cached before the user clicks expand. Rides the exact hook the user asked about.

### 7. Editor keeps the real family (creative-editor.html)
`_snapFont(name)`: if `name` matches the allow-list → return `"<Family>, <bucket>"`
(real family + snapped fallback); else → `"<bucket>"` (unchanged). `bucket` is the
existing serif/sans/mono classification. `buildBrandKitFromLP` is otherwise
unchanged — it already dedupes + stores the result.

## Honest limits
- Only allow-listed (free, embeddable OFL/Apache) Google fonts self-host. Licensed
  faces (Helvetica Neue, proprietary brand fonts) aren't in the catalog and can't
  be legally embedded → they fall back to the snapped system font. No silent
  breakage, no licensing exposure.
- Allow-list is mirrored in 3 languages; keep in sync (cross-ref comments).
- Full latin font ≈ 20–120KB per family, fetched once into R2, cached by CDN and
  by the browser after first expand. Acceptable for the expanded path; never
  loaded on the collapsed common path.

## Test plan
- **Scala:** `GoogleFontCatalog` slug/url derivation unit test; provisioner
  dedup (no fetch when `fontExists`) with a stubbed storage + http.
- **Banner TS:** `font-catalog` slug/url; `collectExpandedFontFamilies` filters to
  allow-list and extracts first token from a stack; @font-face string shape.
- **Editor:** `_snapFont` returns stack for allow-listed, bucket-only otherwise
  (manual / mirror of the Scala catalog).
- **Live:** publish a creative whose LP uses an allow-listed font; confirm the
  woff2 lands in R2 at the deterministic key, the expanded view network-loads it
  from our CDN (not gstatic), and the glyphs render in the real face.
