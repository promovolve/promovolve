import { defineConfig } from "vite";
import { resolve } from "node:path";

// Library mode: emits a single IIFE bundle that defines the
// <expandable-magazine-banner> custom element as a side effect of loading.
// Publishers and the dashboard include it via a plain <script src="…"> tag.
export default defineConfig({
  build: {
    target: "es2020",
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: true,
    minify: "esbuild",
    lib: {
      entry: resolve(__dirname, "src/index.ts"),
      name: "ExpandableMagazineBanner",
      formats: ["iife"],
      fileName: () => "expandable-magazine-banner.js",
    },
    rollupOptions: {
      output: {
        // No external deps — the bundle is fully self-contained so publishers
        // only need one <script> tag.
        extend: true,
      },
    },
  },
});
