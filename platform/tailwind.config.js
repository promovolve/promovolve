/** Tailwind config for the dashboard — the compiled replacement for the
 * Play-CDN inline config that used to live in templates/layout.html.
 * Design tokens: see dev_docs/DESIGN_SYSTEM.md.
 *
 * Rebuild static/tailwind.css after ANY template class change:
 *   scripts/build-tailwind.sh
 * The built CSS is committed (same convention as the designer bundle) so the
 * Go build stays node-free.
 */
module.exports = {
  content: [
    './templates/**/*.html', // includes classes built in the templates' inline JS
    './static/passkey.js',
  ],
  theme: {
    extend: {
      colors: {
        /* Brand = the creative designer's amber (tokens.ts light theme,
           oklch(0.62 0.17 50) → hex). Text on brand is DARK INK, not
           white — the designer's convention; white fails AA on this amber. */
        brand: { DEFAULT: '#d35f00', hover: '#c35800', soft: '#fce4c4' },
        /* Neutrals = the designer's warm ink scale (hue ~60-75, low
           chroma) mapped onto the gray-* steps the templates already
           use — overriding `gray` restyles every page without touching
           markup. Cool Tailwind gray is retired from the dashboard. */
        gray: {
          50: '#fcfaf7', 100: '#f5f3f0', 200: '#eae7e4', 300: '#d3d0cd',
          400: '#a8a49f', 500: '#7d7a75', 600: '#5b5753', 700: '#403c38',
          800: '#272320', 900: '#14110e', 950: '#0c0806',
        },
      },
    },
  },
};
