# Creative Designer — Key Flows

Sequence diagrams for the six flows most worth having a mental model of when
picking up work on this module. Each diagram is small on purpose — it answers
one question. Read alongside the source files it references.

Diagrams are Mermaid; they render natively on GitHub and in most Markdown
previewers.

---

## 1. Boot

*What happens between the page loading and the canvas becoming interactive.*

```mermaid
sequenceDiagram
    autonumber
    participant Template as Go template<br/>creative-design.html
    participant Entry as src/index.ts
    participant Store
    participant Shell as renderShell
    participant Mounts as mount* modules
    participant AutoLayout as installAutoLayoutGenerator
    participant Banner as expandable-magazine-banner

    Template->>Entry: window.__DESIGNER__ = ctx<br/>script tag loads creative-designer.js
    Entry->>Entry: isEnabled()? (URL ?designer=new or localStorage)
    Entry->>Entry: hideInlineEditor() — hide existing DOM, add #designer-root
    Entry->>Entry: normalizePages(ctx.pages)
    Entry->>Store: new Store(initialState(pages))
    Entry->>Shell: renderShell(root)
    Shell-->>Entry: {menuBarHost, matrixHost, canvasHeaderHost, canvasHost, canvasFootHost, sidebarHost}
    Entry->>Mounts: mountMenuBar → mountSizeMatrix → mountCanvasHeader (incl. mountToolbar)
    Entry->>Mounts: mountCanvas — inserts expandable-magazine-banner element
    Entry->>Banner: pages / width / height attrs
    Entry->>Mounts: mountOverlay → mountGuides → mountRulers
    Entry->>Mounts: mountCanvasFoot
    Entry->>Mounts: mountSidebar → mountPropsPanel / mountAnimationPanel / mountLayers
    Entry->>Store: store.subscribe(stateSnapshot => fan out to every panel.update)
    Entry->>Mounts: initial update() on each panel
    Entry->>Entry: installKeyboard(store)
    Entry->>AutoLayout: installAutoLayoutGenerator(store)
    AutoLayout->>AutoLayout: fanOutAllCells() — populate empty (page × mode) cells
    AutoLayout->>Store: store.seed() — promote populated state to baseline
    Note over Store: undo history now starts from<br/>the populated canvas, not blank
```

**Key files:** `src/index.ts`, `src/store.ts`, `src/auto-layout.ts`.

---

## 2. Pointerdown on canvas — which gesture starts?

*How a single click/drag dispatches to rotate / resize / drag / marquee.
Reading `src/render/overlay.ts::pointerdown` alongside this diagram makes it
obvious.*

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Overlay as overlay.ts pointerdown
    participant Gesture as interaction/* module
    participant Store

    User->>Overlay: pointerdown (e)
    Overlay->>Overlay: target = e.target
    alt target has [data-cd-motion-rotate-idx]
        Overlay->>Gesture: startMotionRotate (motion ghost rotation)
    else target has [data-cd-motion-scale-idx]
        Overlay->>Gesture: startMotionScale
    else target has [data-cd-ghost-idx]
        Overlay->>Gesture: startMotionDrag (motion end-position drag)
    else target.class contains 'cd-rotate' && single selection
        Overlay->>Gesture: startRotate
    else target has [data-cd-dir] (resize handle) && single selection
        Overlay->>Gesture: startResize
    else target has [data-cd-idx] (item hitbox)
        Overlay->>Store: update selection (toggle / replace)
        alt already solo-selected text item
            Overlay->>Gesture: maybe-edit<br/>(move 3px → startDrag, else → startInlineTextEdit)
        else
            Overlay->>Gesture: startDrag
        end
    else empty canvas space
        Overlay->>Store: clear selection (unless Shift/Cmd)
        Overlay->>Gesture: startMarquee
    end
    Note over Overlay: Locked items have pointer-events:none<br/>so their hitbox doesn't catch clicks.<br/>Hidden items don't render a hitbox at all.
```

**Key files:** `src/render/overlay.ts`, `src/interaction/*.ts`.

---

## 3. Drag — one undo step per gesture

*Why dragging 500 frames produces one history entry, not 500. The
replace-vs-commit split is the heart of the editor; every interaction
module uses it.*

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Drag as interaction/drag.ts
    participant Store
    participant History
    participant Subs as subscribers<br/>(canvas, overlay, props, layers…)

    User->>Drag: startDrag on pointerdown
    Drag->>Drag: snapshot origins of every selected item<br/>+ bounds of non-dragged items (for snap)

    loop each pointermove frame
        User->>Drag: pointermove
        Drag->>Drag: compute dx/dy in %, run computeSnap()
        Drag->>Drag: updateItem() for every selected item<br/>(clamp to canvas)
        Drag->>Store: store.replace(nextState)
        Store->>History: history.replace — overwrites top, no new entry
        Store->>Subs: notify()
        Subs-->>User: paint next frame
    end

    User->>Drag: pointerup / pointercancel / blur
    Drag->>Drag: hideGuides()
    Drag->>Drag: moved? (any item.left/top changed vs origin)
    alt moved
        Drag->>Store: store.commit(store.state)
        Store->>History: history.push — new undo entry
    else no movement
        Drag->>Drag: no commit — click acted as selection only
    end
```

**Key files:** `src/interaction/drag.ts`, `src/store.ts`, `src/history.ts`.

**Pattern to remember:** every gesture follows this shape.
`replace` during the gesture (navigation / transient), `commit` at the end
(undoable). Selection changes go through `replace` too — undo skips over
them by design.

---

## 4. Regenerate — per-banner regeneration

*Why "Regenerate this size" is a visual no-op on the aspect-bucket tabs
but rewrites the copy on the portrait reader. See
[auto-layout.ts::regenerateCurrentMode](src/auto-layout.ts).*

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Header as canvas-header.ts
    participant Regen as regenerateCurrentMode
    participant Presets as presets.ts
    participant Gemini as api/rewrite-copy.ts
    participant Store

    User->>Header: click "Regenerate this size"
    Header->>Regen: regenerateCurrentMode(store)
    Regen->>Regen: read current page + mode
    alt collapsed aspect bucket (not multi-page)
        Regen->>Presets: presetLayoutFor(mode.key, page, kit)
        Presets-->>Regen: items[] (deterministic)
        Regen->>Store: applyLayout(store, pageIdx, mode, items)<br/>tagged _generated:true
        Store-->>Header: state update — panels re-render
        Note over Regen,Store: Preset is deterministic, so identical<br/>to what was already there → no visible change
    else portrait reader ("mobile" — the only multi-page mode)
        Regen->>Gemini: rewriteCopy(page, lpTextSnapshot)
        Gemini-->>Regen: fresh tag/headline/sub/body (async)
        Regen->>Store: applyPage — rewritten copy replaces the page<br/>(copy is creative-level, all sizes pick it up)
        Regen->>Regen: layoutForMode(merged, mode)<br/>template/preset container, never AI layout
        Regen->>Store: applyLayout(...) — field: refs slot the new copy
        Store-->>Header: state update — new copy, same composition
    end
```

**Key files:** `src/auto-layout.ts`, `src/presets.ts`, `src/api/rewrite-copy.ts`.

**Why layout is never AI-generated here:** Gemini free-styling layouts
produced landscape 50/50 compositions on portrait canvases even with
hard hints. The layout container stays deterministic (template or
preset); Gemini's only job is rewriting the copy.

---

## 5. Auto-layout fanout — populating empty cells at boot

*Why every tab starts with content on a fresh creative even though the
server sent empty pages — and why the tabless 16:9 wide master fires
first. Reading this diagram alongside
[auto-layout.ts](src/auto-layout.ts) shows how the `generated` and
`inFlight` Sets prevent duplicate work.*

```mermaid
sequenceDiagram
    autonumber
    participant Boot as installAutoLayoutGenerator
    participant Fanout as fanOutAllCells
    participant Cell as fireFor(pageIdx, mode)
    participant Templates as template-apply.ts
    participant Presets as presets.ts
    participant Gemini as generateLayout
    participant Store

    Boot->>Boot: initialise inFlight + generated Sets
    Boot->>Fanout: fanOutAllCells()
    loop every pageIdx in state.pages
        Fanout->>Cell: fireFor(pageIdx, WIDE_MASTER) — tabless, FIRST<br/>page.layout gets resolved fonts before buckets inherit them
        loop every mode in MODES (portrait reader first)
            Fanout->>Cell: fireFor(pageIdx, mode)
        end
    end
    Note over Cell: per cell, three sources in order
    alt cell already in `generated` set OR already in-flight
        Cell->>Cell: skip
    else cell already populated (authored or previously generated)
        Cell->>Cell: add to `generated`, skip
    else expanded surface (reader / wide master) AND templateId picked
        Cell->>Templates: applyTemplate(template, kit, page, variant)
        Templates-->>Cell: items[] (mobileItems for the reader)
        Cell->>Store: applyLayout — sync, no network
    else preset available (every mode key has one today)
        Cell->>Presets: presetLayoutFor(mode.key, page, kit)
        Presets-->>Cell: items[]
        Cell->>Store: applyLayout — sync
    else no template AND no preset (currently unreachable)
        Cell->>Cell: inFlight.add(key), notify listeners
        Cell-)Gemini: generateLayout(page, mode)
        Gemini--)Cell: items[] (async)
        Cell->>Store: applyLayout (on resolve)
        Cell->>Cell: inFlight.delete(key), notify listeners
    end
    Fanout->>Store: store.seed() — synchronously populated state becomes baseline
    Boot->>Store: store.subscribe(fanOutAllCells) — catch later page adds
    Note over Store: Undo can't rewind past seed.<br/>Any async Gemini responses land later<br/>via replace, fold into the first real edit.
```

**Key files:** `src/auto-layout.ts`, `src/presets.ts`, `src/template-apply.ts`.

**Behaviour:** every cell fills synchronously at boot (template or
preset — no network), including the tabless wide master, which exists
as a delivery artifact for wide collapsed slots and legacy readers
even though it can't be hand-edited. The Gemini branch is kept for a
future template that excludes itself from a mode; today every mode key
has a preset, so it never fires during fanout.

---

## 6. Save draft / Publish

*The only flow that hits the Go handler. Everything else is client-only.*

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant SaveBtn as save.ts
    participant Form as hidden <form>
    participant Go as platform/internal/handler
    participant Core as modules/api (Pekko)
    participant DB

    User->>SaveBtn: click "Save Draft" or "Publish"
    SaveBtn->>SaveBtn: submitSave(store, ctx, draft)
    SaveBtn->>Form: build hidden form<br/>pagesJson = JSON.stringify(store.state.pages)<br/>+ campaignId, name, landingUrl, w, h, draft?, creativeId?
    Form->>Go: POST /advertiser/creatives/save
    Go->>Core: proxy to /v1/advertisers/me/creatives
    Core->>DB: upsert creative row (id stable across draft → publish)
    Core-->>Go: 200 + creativeId
    Go-->>User: redirect (full-page reload)
    Note over SaveBtn,Form: Uses a <form> submit not fetch,<br/>so the server drives navigation and<br/>the page fully reloads with the new state.
```

**Key files:** `src/ui/save.ts`, `platform/internal/handler/pages.go`,
`modules/api/src/main/scala/promovolve/api/EndpointRoutes.scala`.

**Behaviour:** draft and publish hit the same endpoint; the `draft` flag is
the only functional difference. The `creativeId` is preserved across repeated
saves so we overwrite the same row rather than creating orphans. Full-page
reload on response — there's no SPA-style in-place refresh yet.

---

## How these fit together

- Flows **1, 4, 5** are boot / bulk-state flows. They run once or on explicit
  user action; they never fire per-frame.
- Flows **2, 3** are the interaction hot path. Read them together — every
  other gesture module (resize, rotate, motion-drag, motion-rotate,
  motion-scale, text edit) is a variation on flow 3's shape.
- Flow **6** is the only flow that crosses the client/server boundary for
  content (asset upload is the other, through a separate modal code path).

## Tips for reading the code

- Start with **flow 1** (boot), open every file it mentions, skim one level
  deep — you'll have seen most of the codebase once through.
- Then pick a gesture from **flow 2** and trace it. Resize is the easiest;
  drag is the canonical one.
- **`store.commit`** vs **`store.replace`** is the single most important
  distinction in this codebase. If in doubt: does this fire many times per
  second? → `replace`. Does the user expect Cmd+Z to undo it? → `commit`.

## Editing these diagrams

GitHub renders Mermaid with `securityLevel: 'strict'`, which is stricter
than the local `mmdc` CLI. Two pitfalls that have bitten us:

- **`;` is a statement separator.** Inside a message label, write
  `inFlight.add(key), notify listeners`, not `inFlight.add(key); notify
  listeners` — the second form ends the message early and the next line
  fails to parse.
- **Raw HTML tags get stripped.** `<script src="...">` and
  `<expandable-magazine-banner>` in a label render fine locally but
  disappear (or break the line) on GitHub. Rephrase to plain text
  (`script tag loads creative-designer.js`,
  `expandable-magazine-banner element`). `<br/>` is the only HTML that's
  safe — it's a Mermaid-recognised line break.

When a diagram looks wrong on GitHub, render it locally first with
`npx -p @mermaid-js/mermaid-cli mmdc -i diag.mmd -o diag.svg`. If it
renders locally too, the bug is real syntax; if not, it's a sanitiser
mismatch and the fix is in this list.
