/** Tailwind config for the dashboard — the compiled replacement for the
 * Play-CDN inline config that used to live in templates/layout.html.
 * Design tokens: see DESIGN.md.
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
  // Intensity cells pick their step at render time (bg-jma-{{.Level}}).
  safelist: [{ pattern: /^bg-jma-[0-7]$/ }],
  theme: {
    /* Forecast world (DESIGN.md): every corner is 2px; only dots and the
       chip × stay round. */
    borderRadius: {
      none: '0', sm: '2px', DEFAULT: '2px', md: '2px', lg: '2px', xl: '2px', '2xl': '2px', full: '9999px',
    },
    /* No decorative depth. Only floating layers (popovers, menus) cast a
       shadow, so they read as above the page. */
    boxShadow: {
      none: 'none', sm: 'none', DEFAULT: 'none', md: 'none',
      lg: '0 8px 24px -6px rgb(16 24 32 / 0.22)', xl: '0 8px 24px -6px rgb(16 24 32 / 0.22)',
    },
    extend: {
      fontFamily: {
        /* Morisawa's Universal Design gothic, self-hosted (static/fonts),
           for Latin and Japanese alike. */
        sans: ['"BIZ UDPGothic"', '"BIZ UDPゴシック"', 'system-ui', '-apple-system', '"Hiragino Sans"', '"Yu Gothic UI"', 'sans-serif'],
      },
      colors: {
        /* brand = the ink action colour: ink-filled primary buttons and
           selected states in light mode, light fill in dark mode. Text on
           it is on-brand. brand-ink = link blue; brand-soft = the
           blue-grey field used for selected rows and the top bar. */
        brand: {
          DEFAULT: 'rgb(var(--c-brand) / <alpha-value>)',
          ink: 'rgb(var(--c-brand-ink) / <alpha-value>)',
          soft: 'rgb(var(--c-field) / <alpha-value>)',
        },
        'on-brand': 'rgb(var(--c-on-brand) / <alpha-value>)',
        field: 'rgb(var(--c-field) / <alpha-value>)',
        /* JMA rainfall-intensity scale: ONLY for intensity data (spend or
           impressions per hour). Never decoration. */
        jma: {
          0: '#f2f2ff', 1: '#a0d2ff', 2: '#2185ff', 3: '#0041ff',
          4: '#fff500', 5: '#ff9900', 6: '#ff2800', 7: '#b40068',
        },
        /* Alert levels, JMA convention: advisory = yellow with ink text,
           warning = red with white text (5.4:1). Stable across themes. */
        advisory: '#fff500',
        warning: '#d70a00',
        /* Neutrals + white route through CSS variables (tailwind.input.css
           :root / .dark); each step keeps its contrast role in both themes,
           so a light-authored template reads correctly in dark. */
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
