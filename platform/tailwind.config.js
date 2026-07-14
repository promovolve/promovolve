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
  // Dark mode is a `.dark` class on <html>, toggled by the inline head
  // script in layout.html (tri-state auto/light/dark like the designer).
  darkMode: 'class',
  content: [
    './templates/**/*.html', // includes classes built in the templates' inline JS
    './static/passkey.js',
  ],
  theme: {
    extend: {
      colors: {
        /* Brand = the creative designer's amber (tokens.ts light theme,
           oklch(0.62 0.17 50) → hex). Text on brand is DARK INK, not
           white — the designer's convention; white fails AA on this amber.
           Intentionally NOT variable-driven: the accent is stable across
           themes. */
        brand: { DEFAULT: '#d35f00', hover: '#c35800', soft: '#fce4c4' },
        /* Neutrals + white route through CSS variables (space-separated RGB
           triples, defined in tailwind.input.css :root / .dark). Every
           bg-white / bg-gray-* / text-gray-* / border-gray-* across the
           templates re-themes when `.dark` flips those variables — no
           per-page edits. `<alpha-value>` keeps opacity modifiers
           (bg-gray-100/40) working. Each step preserves its CONTRAST ROLE
           across themes (gray-50 = lightest surface in light mode → the page
           void in dark; gray-900 = darkest text in light → near-white text
           in dark), so a light-authored template reads correctly in both. */
        white: 'rgb(var(--c-white) / <alpha-value>)',
        gray: {
          50: 'rgb(var(--c-gray-50) / <alpha-value>)',
          100: 'rgb(var(--c-gray-100) / <alpha-value>)',
          200: 'rgb(var(--c-gray-200) / <alpha-value>)',
          300: 'rgb(var(--c-gray-300) / <alpha-value>)',
          400: 'rgb(var(--c-gray-400) / <alpha-value>)',
          500: 'rgb(var(--c-gray-500) / <alpha-value>)',
          600: 'rgb(var(--c-gray-600) / <alpha-value>)',
          700: 'rgb(var(--c-gray-700) / <alpha-value>)',
          800: 'rgb(var(--c-gray-800) / <alpha-value>)',
          900: 'rgb(var(--c-gray-900) / <alpha-value>)',
          950: 'rgb(var(--c-gray-950) / <alpha-value>)',
        },
      },
    },
  },
};
