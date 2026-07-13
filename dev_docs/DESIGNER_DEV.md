# Creative-Designer Dev Loops

The creative-designer (`platform/creative-designer/`) is a Vite-built IIFE
bundle. In production the dashboard embeds it via `go:embed` and serves it at
`/static/creative-designer.js`; the Go template `creative-design.html` is a thin
shell that injects the `window.__DESIGNER__` context and loads that bundle.

Because the bundle is **embedded into the platform image**, a naive change has a
long path to the cluster:

```
edit src → npm run build → npm run fanout (→ platform/static/) →
docker build platform image → push → kubectl rollout
```

Two dev loops short-circuit that. Pick by what you're changing.

---

## Loop A — true HMR against a real backend  ⭐ (default for UI/UX work)

Under `vite serve` (this loop only) the designer exposes its store as
`window.__STORE__` — Playwright can inject state and assert against it,
and `await import("/src/<module>.ts")` inside `page.evaluate` gives
unit-level access to any module. The hook is gated on `import.meta.hot`,
so no build ever ships it.

Serve the designer straight from source with Vite, and point its **own built-in
proxy** (`vite.config.ts → server.proxy`) at an already-running backend. Edits
hot-reload in the browser instantly; backend calls (auth, asset upload, save,
generate-layout) are proxied to the real cluster.

```bash
./scripts/run-designer-dev.sh                 # proxy → k8s platform on :9090 (default)
DASHBOARD_URL=http://localhost:9091 ./scripts/run-designer-dev.sh   # → local dashboard
PORT=5180 ./scripts/run-designer-dev.sh
```

- **Designer (HMR):** http://localhost:5173/ — boots from `/src` (true HMR).
- **Proxied to `$DASHBOARD_URL`:** `/login`, `/advertiser/*`, `/api/*`, **`/static/*`**.
  `/static` matters: dashboard pages (`layout.html`) load htmx, htmx-sse, alpine,
  `style.css`, and the embedded designer bundle from `/static/…`. Without it
  every proxied dashboard page (including the analyze-LP editor) 404s its JS and
  silently breaks (spinner + blurred error). Caveat: proxied dashboard routes
  therefore load the platform's **embedded** designer bundle, *not* the HMR
  source — only `:5173/` itself is HMR. Iterate designer UI at `:5173/`; use the
  proxied routes for real-data flows (analyze → save → real creative).
- **Default backend:** the live k8s platform Service that Docker Desktop exposes
  on `localhost:9090`. No local api/postgres needed.

**Auth** (only needed for save/upload/generate — not for pure UI work): open
**http://localhost:5173/login** and sign in *there*. The proxy forwards
`Set-Cookie` so the auth cookie lands on the `:5173` origin, and subsequent
`/advertiser/*` calls carry it through.

This is the loop to use for the kind of changes we iterate on most — selection,
the properties panel, the canvas footer, overlay/handles — because they're
pure front-end and reload instantly.

---

## Loop B — local stack, reload-to-see (`run-dashboard.sh`)

Run the Go dashboard locally and let it serve the designer bundle from the live
directory instead of the embed. Pair with `npm run dev` (Vite `build --watch`),
which writes into `platform/static/` — so a browser reload picks up edits.

```bash
# terminal 1: the dashboard (needs local api on :8080 + postgres on :5432)
./scripts/run-dashboard.sh            # Go dashboard on :9091, DEV_STATIC_DIR=platform/static

# terminal 2: rebuild-on-save
cd platform/creative-designer && npm run dev   # vite build --watch → platform/static/
```

- Dashboard at http://localhost:9091 (real Go-rendered shell, real `__DESIGNER__`
  context for an actual creative).
- **No HMR** — you reload the page to see changes.
- Requires the **full local stack** (`CORE_API_URL=localhost:8080`,
  `DATABASE_URL=localhost:5432`). Use this when you also need a real
  Go-rendered creative page, or you're touching Go/template code.

You can combine them: `run-dashboard.sh` on `:9091` + Loop A with
`DASHBOARD_URL=http://localhost:9091` gives HMR against the local dashboard.

---

## ⚠️ The blind spot

Loop A/B only change the **dev** bundle. The **cluster keeps serving the
embedded bundle** until you ship it. When you're happy with the HMR iteration,
deploy for real:

```bash
cd platform/creative-designer && npm run build && npm run fanout   # → platform/static/
cd /Users/hanishi/promovolve
docker build -f platform/Dockerfile -t hanishi/promovolve-platform:dev platform
docker push hanishi/promovolve-platform:dev          # (guardrail: run via `!`)
kubectl rollout restart deployment/promovolve-platform -n promovolve
```

Verify before declaring done: the served bundle is the new one, e.g.

```bash
kubectl port-forward -n promovolve svc/promovolve-platform 9099:9090 &
curl -s localhost:9099/static/creative-designer.js | grep -c '<a marker from your change>'
```

(`creative-designer.js` is **not** content-hashed, so in the browser hard-reload
with `Cmd/Ctrl+Shift+R` to bypass cache.)
