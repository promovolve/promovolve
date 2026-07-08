// Design tokens for the creative-designer shell. Colours are CSS custom
// properties (--cd-*) so the theme can switch INSTANTLY without re-mounting
// the designer (which would lose unsaved canvas state). `tokens.X` holds a
// `var(--cd-X)` reference — components keep using it exactly as before
// (inline styles resolve the variable); the real per-theme values live in
// COLORS below and are injected onto :root by injectThemeStyles().
//
// The ink scale goes dark→light in the DARK theme (ink900 = app void,
// ink100 = near-white headings); the LIGHT theme inverts the surfaces
// (ink900 = near-white bg, ink100 = near-black text) so every component's
// "ink900 background / ink100 text" assumption keeps working.

type ThemePair = { dark: string; light: string };

const COLORS = {
  // Surfaces — stepped from app void (900) to high-contrast (100).
  ink900: { dark: "oklch(0.14 0.008 60)", light: "oklch(0.985 0.004 75)" },
  ink800: { dark: "oklch(0.18 0.008 60)", light: "oklch(0.965 0.004 75)" },
  ink700: { dark: "oklch(0.22 0.008 60)", light: "oklch(0.930 0.005 75)" },
  ink600: { dark: "oklch(0.28 0.008 60)", light: "oklch(0.860 0.006 75)" },
  ink500: { dark: "oklch(0.34 0.008 60)", light: "oklch(0.800 0.006 75)" },
  ink400: { dark: "oklch(0.45 0.008 60)", light: "oklch(0.580 0.008 75)" },
  ink300: { dark: "oklch(0.60 0.006 60)", light: "oklch(0.460 0.008 75)" },
  ink200: { dark: "oklch(0.78 0.004 60)", light: "oklch(0.320 0.008 75)" },
  ink100: { dark: "oklch(0.92 0.003 60)", light: "oklch(0.200 0.010 75)" },

  // Accent — amber. Slightly darker/denser in light mode for contrast on a
  // pale background.
  amber:      { dark: "oklch(0.74 0.17 55)", light: "oklch(0.62 0.17 50)" },
  amberMuted: { dark: "oklch(0.52 0.10 55)", light: "oklch(0.68 0.12 55)" },
  amberBg:    { dark: "oklch(0.30 0.07 55)", light: "oklch(0.93 0.05 75)" },

  // Thumbnail "ad surface" — the recessed inner canvas of a size-matrix
  // dimension preview. Near-black in dark mode (so it reads as the void the
  // ad floats on); a soft recessed gray in light mode (darker than the chip
  // surround, ink800, so the relationship stays "inner is recessed").
  canvas: { dark: "oklch(0.10 0.005 60)", light: "oklch(0.925 0.005 75)" },

  // Functional — darker in light mode so status text stays legible on pale.
  ok:   { dark: "oklch(0.72 0.14 150)", light: "oklch(0.55 0.13 150)" },
  warn: { dark: "oklch(0.78 0.15 85)",  light: "oklch(0.62 0.14 80)" },
  err:  { dark: "oklch(0.66 0.18 25)",  light: "oklch(0.56 0.19 25)" },
} satisfies Record<string, ThemePair>;

type ColorKey = keyof typeof COLORS;
const COLOR_KEYS = Object.keys(COLORS) as ColorKey[];
const varName = (k: ColorKey): string => `--cd-${k}`;

const colorTokens = Object.fromEntries(
  COLOR_KEYS.map((k) => [k, `var(${varName(k)})`]),
) as Record<ColorKey, string>;

export const tokens = {
  ...colorTokens,

  // Typography. Inter + JetBrains Mono are loaded by the Go shell template.
  sans: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif',
  mono: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',

  // Radii (plain numbers — used in `${tokens.r4}px` arithmetic).
  r4: 4,
  r6: 6,
  r8: 8,
};

// ─── Theme plumbing ─────────────────────────────────────────────────

export type Theme = "dark" | "light";
const THEME_KEY = "promovolve.designer.theme";

// Inject the :root variable block once. Base = dark; the light override
// raises specificity via the data-attribute so switching is one attr flip.
export function injectThemeStyles(): void {
  if (typeof document === "undefined" || document.getElementById("cd-theme-vars")) return;
  const block = (t: "dark" | "light"): string =>
    COLOR_KEYS.map((k) => `${varName(k)}:${COLORS[k][t]}`).join(";");
  const style = document.createElement("style");
  style.id = "cd-theme-vars";
  style.textContent = `:root{${block("dark")}}:root[data-cd-theme="light"]{${block("light")}}`;
  document.head.appendChild(style);
}

// The author's choice: "auto" follows the OS; "light"/"dark" force it.
// Default is "auto", so a fresh setup honours the system appearance.
export type ThemeMode = "auto" | "light" | "dark";

function systemTheme(): Theme {
  try {
    return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  } catch {
    return "dark";
  }
}

export function getMode(): ThemeMode {
  try {
    const v = localStorage.getItem(THEME_KEY);
    return v === "light" || v === "dark" || v === "auto" ? v : "auto";
  } catch {
    return "auto";
  }
}

function resolve(mode: ThemeMode): Theme {
  return mode === "auto" ? systemTheme() : mode;
}

function setThemeAttr(theme: Theme): void {
  if (typeof document !== "undefined") document.documentElement.dataset.cdTheme = theme;
}

// Persist the chosen mode and apply its resolved theme.
export function applyMode(mode: ThemeMode): void {
  setThemeAttr(resolve(mode));
  try {
    localStorage.setItem(THEME_KEY, mode);
  } catch {
    /* private browsing — mode just won't persist */
  }
}

// Inject vars, apply the stored mode (default auto → OS), and keep
// following the OS live while the mode is "auto".
export function initTheme(): void {
  injectThemeStyles();
  setThemeAttr(resolve(getMode()));
  try {
    window.matchMedia("(prefers-color-scheme: light)").addEventListener("change", (e) => {
      if (getMode() === "auto") setThemeAttr(e.matches ? "light" : "dark");
    });
  } catch {
    /* matchMedia unsupported — stays on the boot-time theme */
  }
}

// Cycle auto → light → dark → auto.
export function cycleMode(): ThemeMode {
  const order: ThemeMode[] = ["auto", "light", "dark"];
  const next = order[(order.indexOf(getMode()) + 1) % order.length]!;
  applyMode(next);
  return next;
}
