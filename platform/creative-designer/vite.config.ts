import { defineConfig } from "vite";
import { resolve } from "node:path";

// Library mode: single IIFE bundle (+ style.css) that boots from
// window.__DESIGNER__ and mounts into #designer-root. The dashboard's
// creative-design.html template is a thin Go-rendered shell that
// injects context and loads this bundle.
//
// Dev mode (`vite build --watch`, mode=development) writes the bundle
// straight into platform/static/, so a Go server with DEV_STATIC_DIR
// set (run-dashboard.sh sets it by default) serves edits on the next
// request — no fanout, no Go rebuild.
// Production (`vite build`) keeps writing to dist/ for fanout.
export default defineConfig(({ mode }) => {
  const isDev = mode === "development";
  return {
    resolve: {
      alias: {
        // Share schema with the banner component so the designer and the
        // renderer can't drift on what a LayoutItem looks like.
        "@banner": resolve(__dirname, "../banner-component/src"),
      },
    },
    server: {
      fs: {
        // Allow the dev harness to import the sibling banner-component
        // package via the @banner alias. Default vite would block
        // anything outside the package root.
        allow: [resolve(__dirname, ".."), resolve(__dirname)],
      },
      // Proxy backend paths to the Go dashboard so the harness can use
      // real asset upload, auth, and creative endpoints. Requires the
      // Go dashboard running on :9091 (override via DASHBOARD_URL).
      // To use upload/save flows in the harness:
      //   1. Start the Go dashboard (./scripts/run-dashboard.sh).
      //   2. Open http://localhost:5173/login and sign in there — the
      //      Set-Cookie response is forwarded so the auth cookie ends
      //      up on the :5173 origin, and subsequent /advertiser/*
      //      requests carry it through the proxy.
      proxy: (() => {
        const target = process.env.DASHBOARD_URL ?? "http://localhost:9091";
        const opts = { target, changeOrigin: true } as const;
        return {
          "/advertiser": opts,
          "/api": opts,
          "/login": opts,
          "/logout": opts,
          // Dashboard pages (layout.html) load their JS/CSS from /static
          // (htmx, htmx-sse, alpine, style.css, the embedded designer
          // bundle…). Without this they 404 against the Vite dev server and
          // every proxied dashboard page — including the analyze-LP editor —
          // breaks. NOTE: the designer's own dev shell boots from /src
          // (HMR), so this only affects proxied dashboard pages, which use
          // the platform's *embedded* designer bundle, not HMR.
          "/static": opts,
        };
      })(),
    },
    build: {
      target: "es2020",
      outDir: isDev ? resolve(__dirname, "../static") : "dist",
      // Don't wipe platform/static/ in dev — it holds htmx, alpine,
      // style.css, etc. that we don't manage.
      emptyOutDir: !isDev,
      sourcemap: true,
      minify: "esbuild",
      cssCodeSplit: false,
      lib: {
        entry: resolve(__dirname, "src/index.ts"),
        name: "PromovolveCreativeDesigner",
        formats: ["iife"],
        fileName: () => "creative-designer.js",
      },
    },
  };
});
