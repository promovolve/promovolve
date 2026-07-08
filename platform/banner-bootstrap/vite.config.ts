/// <reference types="vitest" />
import { defineConfig } from "vite";
import { resolve } from "node:path";

// Library mode: emits a single IIFE bundle that mounts
// `window.__promovolve__` on load and processes any queued commands.
// Publishers include it via `<script async src="…">`.
export default defineConfig({
  build: {
    target: "es2020",
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: true,
    minify: "esbuild",
    lib: {
      entry: resolve(__dirname, "src/bootstrap.ts"),
      name: "PromovolveBootstrap",
      formats: ["iife"],
      fileName: () => "promovolve-bootstrap.js",
    },
    rollupOptions: {
      output: {
        extend: true,
      },
    },
  },
  test: {
    setupFiles: ["./tests/setup.ts"],
  },
});
