# creative-designer

Dashboard UI for authoring `<expandable-magazine-banner>` creatives. Canvas, drag/resize/rotate, per-size layout overrides, asset library, animation picker.

This module is the single source of truth for the designer's client code. It replaces the ~1100-line inline JS that lived in `platform/templates/advertiser/creative-design.html`.

**New to this codebase?** Two companion docs:
- [DESIGN.md](DESIGN.md) — *why* the module is shaped this way. System context, module topology, data model, and the load-bearing design decisions (replace-vs-commit, percent coords, presets-for-IAB, selection-as-view-state, etc.).
- [FLOWS.md](FLOWS.md) — *what happens when*. Six Mermaid sequence diagrams for boot, pointer dispatch, drag, regenerate, auto-layout fanout, and save.

Read `DESIGN.md` first for orientation, then `FLOWS.md` before touching any specific flow.

## Why this module exists

The inline-JS version worked but was hostile to the kind of improvements the product needs next (undo/redo, multi-select, alignment guides, layer panel, layout templates, animation timing UI, brand kits). All of those are straightforward with modules + types + unit tests, and nearly impossible in a 1100-line `<script>` block inside a Go template.

Shares the `LayoutItem`, `Page`, and `Animation` types with `@promovolve/banner-component` (via the `@banner` path alias), so the editing model and the rendering model can't drift.

## Architecture

- **Go shell template** (`platform/templates/advertiser/creative-design.html`) — minimal HTML + `<div id="designer-root">` + dynamic context injected as `window.__DESIGNER__` + `<script src="/static/creative-designer.js">`. Auth/session stays in Go; everything else is client-side.
- **TypeScript entry** (`src/index.ts`) — reads `window.__DESIGNER__`, mounts the designer into `#designer-root`.
- **Build output** — `dist/creative-designer.js` + `dist/style.css` (single CSS bundle, inlined by Vite's `cssCodeSplit: false`).
- **Fan-out target** — `platform/static/creative-designer.*` (served by the Go dashboard via `//go:embed`). Either a local fan-out step, or later the same R2 + hashed-filename workflow `banner-component` uses.

## Layout

```
platform/creative-designer/
├── src/
│   ├── index.ts           # boot + mount
│   ├── state.ts           # state shape + mutations + history
│   ├── types.ts           # designer-only types; re-exports from @banner
│   ├── interaction/       # drag, resize, rotate, marquee-select (planned)
│   ├── render/            # canvas, overlays, props panel, layer panel
│   └── assets/            # /advertiser/assets fetch + upload
├── tests/                 # vitest — resize math, coordinate transforms, history ops
├── dist/                  # build output (gitignored)
├── package.json
├── vite.config.ts         # library mode, IIFE, single CSS bundle
├── tsconfig.json          # strict TS, path alias @banner → banner-component
└── eslint.config.js
```

## Workflow

```bash
cd platform/creative-designer
npm install            # first time only

npm run serve          # vite dev server with HMR (the dev loop)
npm run build          # one-shot production build → dist/
npm run dev            # vite build --watch (rarely needed; serve is faster)
npm run test           # vitest
npm run typecheck
npm run lint
```

### Dev loop

The designer is developed through vite's HMR dev server, which
proxies backend calls through the Go dashboard, which proxies through
to Scala core. **Three terminals:**

```bash
# Terminal 1 — Scala core (auctions, creatives, persistence)
sbt run                            # → http://localhost:8080

# Terminal 2 — Go dashboard (HTML + proxy to Scala)
./scripts/run-dashboard.sh         # → http://localhost:9091

# Terminal 3 — vite dev server (HMR for the designer)
cd platform/creative-designer
npm run serve                      # → http://localhost:5173
```

Then **always work against `:5173`**, never `:9091` directly:

1. **Sign in via the harness origin:** open
   `http://localhost:5173/login`. The login POST is proxied to Go,
   and the Set-Cookie response lands on `:5173` so subsequent fetches
   from the harness carry the auth cookie. Logging in on `:9091`
   puts the cookie on the wrong origin and the harness can't see it.
2. **Open the designer:** `http://localhost:5173/` boots the harness,
   reads `window.__DESIGNER__` from `index.html`, and mounts the
   designer. Edits to any `.ts` in `src/` hot-swap in ~50ms.
3. **Backend calls just work** — `/advertiser/*`, `/api/*`,
   `/login`, `/logout` are proxied to `:9091` (configured in
   `vite.config.ts > server.proxy`). Override the target with
   `DASHBOARD_URL=http://...` if Go runs elsewhere.

**Why three layers and not two:** the Go dashboard owns auth,
templates, and the proxy to Scala core. Scala owns the actual data
model. Vite owns HMR. Each layer does one thing. The designer-only
harness used to run without Go at all, but real flows (asset upload,
save, auth) need the chain.

**Designer-only mode** (no Go required) still works for pure UI work:
canvas, panels, drag/resize/rotate, keyboard, undo/redo, and layout
synthesis all run against the fixture in `index.html`. Calls that
need server endpoints log errors but the UI keeps running. Useful
when you don't want to spin up the whole stack.

**Swapping fixtures:** edit `window.__DESIGNER__` at the top of
`index.html`. Paste in real `pages` JSON pulled from devtools on a
live save to reproduce a specific creative locally.

**`npm run dev` vs `npm run serve`:** `serve` is the dev loop
(HMR). `dev` runs `vite build --watch` and writes a bundle to
`dist/` — useful when iterating against a non-vite host that loads
the built bundle, e.g., the Go dashboard at `:9091` directly. Most
of the time you want `serve`.


