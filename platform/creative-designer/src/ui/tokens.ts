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
  // Surfaces — stepped from app background (900) to high-contrast ink
  // (100). Values are the dashboard's Forecast neutrals
  // (platform/tailwind.input.css) so the two read as one product.
  ink900: { dark: "#111519", light: "#ffffff" }, // background, panel surface
  ink800: { dark: "#1a1f24", light: "#f6f8fa" }, // raised / recessed surface
  ink700: { dark: "#242a30", light: "#e8edf2" }, // Fill: hover, plain tags
  ink600: { dark: "#323a42", light: "#d6dee5" }, // Hairline: row lines
  ink500: { dark: "#424c56", light: "#bdc7d0" }, // Rule: borders, input edges
  ink400: { dark: "#828e99", light: "#8a96a1" }, // Mark: icons and rules only, never text
  ink300: { dark: "#a8b3bd", light: "#4a5560" }, // secondary text
  ink200: { dark: "#d8dfe5", light: "#2a3138" }, // body text
  ink100: { dark: "#f2f5f8", light: "#1a1a1a" }, // Ink: headings, primary fill

  // Field blue-grey — the top bar and selected rows.
  field: { dark: "#222a33", light: "#dfe7ee" },

  // The only accent: Focus Blue (JMA step 2) — selection, handles, the
  // active tool. Every other hue in the chrome is data or an alert.
  focus:      { dark: "#2185ff", light: "#2185ff" },
  focusMuted: { dark: "rgb(33 133 255 / 0.55)", light: "rgb(33 133 255 / 0.45)" },
  focusBg:    { dark: "#222a33", light: "#dfe7ee" },

  // Thumbnail "ad surface" — the recessed inner canvas of a size-matrix
  // dimension preview, darker than the chip surround in both themes.
  canvas: { dark: "#0c0f12", light: "#e8edf2" },

  // Alerts. warn is Advisory Yellow: a FILL with ink text, never text.
  ok:   { dark: "#8fd6a3", light: "#146c2e" },
  warn: { dark: "#fff500", light: "#fff500" },
  err:  { dark: "#ff6a5f", light: "#d70a00" },
} satisfies Record<string, ThemePair>;

type ColorKey = keyof typeof COLORS;
const COLOR_KEYS = Object.keys(COLORS) as ColorKey[];
const varName = (k: ColorKey): string => `--cd-${k}`;

const colorTokens = Object.fromEntries(
  COLOR_KEYS.map((k) => [k, `var(${varName(k)})`]),
) as Record<ColorKey, string>;

export const tokens = {
  ...colorTokens,

  // Typography. BIZ UDPGothic is self-hosted and linked by the Go shell
  // template (fonts/biz-udpgothic.css), the same face as the dashboard.
  sans: '"BIZ UDPGothic", system-ui, sans-serif',
  mono: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",

  // Radii (plain numbers — used in `${tokens.r4}px` arithmetic). Forecast
  // corners are square-ish: all three are 2.
  r4: 2,
  r6: 2,
  r8: 2,
};

// ─── Theme plumbing ─────────────────────────────────────────────────

export type Theme = "dark" | "light";
// Shared with the dashboard (templates/layout.html), so one choice themes
// both. The designer's old key is read once by migrateThemeKey.
export const THEME_KEY = "promovolve.dashboard.theme";
export const LEGACY_THEME_KEY = "promovolve.designer.theme";

export type ThemeStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

const isMode = (v: string | null): v is ThemeMode => v === "auto" || v === "light" || v === "dark";

// Carry an existing designer-only choice over to the shared key (unless
// the dashboard already has one), then drop the old key for good.
export function migrateThemeKey(storage: ThemeStorage): void {
  try {
    const legacy = storage.getItem(LEGACY_THEME_KEY);
    if (legacy === null) return;
    if (isMode(legacy) && storage.getItem(THEME_KEY) === null) storage.setItem(THEME_KEY, legacy);
    storage.removeItem(LEGACY_THEME_KEY);
  } catch {
    /* storage blocked — nothing to carry over */
  }
}

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
    return isMode(v) ? v : "auto";
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
  try {
    migrateThemeKey(localStorage);
  } catch {
    /* no localStorage at all */
  }
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
