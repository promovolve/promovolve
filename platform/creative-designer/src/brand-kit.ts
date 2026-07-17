// Brand kit: per-advertiser color palette + font choices that the
// author defines once and reuses everywhere. Colors surface as
// quick-pick chips next to color pickers in the props panel; fonts
// land in the same dropdown as the built-in family list.
//
// MVP storage is localStorage scoped by advertiser/campaign — works
// without any server changes. When the Go shell template injects
// `window.__DESIGNER__.brandKit`, that takes precedence so the
// authoritative server-side kit overrides whatever's in the browser.
//
// Future: a real /v1/advertisers/{id}/brand-kit endpoint that the
// shell hits server-side and serializes into __DESIGNER__. Drop the
// localStorage path then or keep it as a draft buffer.

export interface BrandKitColor {
  /** User-visible name ("Primary", "Accent", "Brand red"). Free text;
    * picker shows it as the chip's tooltip. */
  name: string;
  /** CSS color value — anything <input type="color"> roundtrips
    * (hex #rrggbb). Allow named/CSS values via direct edit too. */
  value: string;
}

export interface BrandKit {
  /** Display name for the kit ("Acme", "Fall 2026 promo"). Optional —
    * single-kit setups can leave it empty. */
  name?: string;
  colors: BrandKitColor[];
  /** Font-family strings, ready to drop into CSS. Up to ~6. */
  fonts: string[];
  /** Optional logo URL (CDN-hosted). Picker treats as a one-click
    * image insert; not auto-applied to layouts. */
  logoUrl?: string;
}

const STORAGE_KEY_PREFIX = "promovolve.designer.brandKit";

/** Storage key for the current campaign. Keys include the campaign
  * id so different campaigns can live in the same browser without
  * trampling each other. Falls back to a global key when no campaign
  * id is available (e.g., in tests). */
function storageKey(campaignId?: string): string {
  return campaignId ? `${STORAGE_KEY_PREFIX}.${campaignId}` : STORAGE_KEY_PREFIX;
}

/** Fallback kit when no LP-/server-/localStorage-sourced kit is present.
  * Ships NO default colours — arbitrary brand colours would just mislead
  * (the creative's real palette comes from the LP); authors add their own
  * via the brand-kit modal. Fonts keep a sane heading/body pair so layout
  * generation has something to resolve. */
export const EMPTY_KIT: BrandKit = {
  name: "",
  colors: [],
  fonts: ["Inter", "Georgia"],
};

/** Resolve a brand-kit font by role index (0 = heading, 1 = body),
  * falling back to the kit's primary font and then a system family.
  * Shared by the layout builders (normalize.ts collapsed layout +
  * presets.ts expanded presets) so a determined LP font reaches every
  * view, not just the expanded one. Kit fonts are pre-snapped to a
  * "<Real Family>, <system bucket>" stack, so the system family after
  * the comma is the always-available fallback wherever the woff2 hasn't
  * loaded yet. */
export function kitFont(kit: BrandKit | null, index: number, fallback: string): string {
  const fonts = kit && Array.isArray(kit.fonts) ? kit.fonts : [];
  return fonts[index] ?? fonts[0] ?? fallback;
}

/** Resolve a brand-kit colour by its role name ("Background", "Text",
  * "Accent", "Brand"), case-insensitive → hex value or null. The LP-seeded
  * kit names colours by role (see creative-editor buildBrandKitFromLP), so the
  * layout can pull the LP's background/text/accent the way kitFont pulls its
  * fonts. Values are hex (the editor runs _toHex on extraction). */
export function kitColor(kit: BrandKit | null, name: string): string | null {
  if (!kit || !Array.isArray(kit.colors)) return null;
  const lower = name.toLowerCase();
  // Alias map: sessions on the 2026-07-17 i18n build saved kits with
  // translated role names; heal them transparently so those kits keep
  // resolving (the wizard writes English names again since the fix).
  const JA_ROLES: Record<string, string> = {
    "背景": "background",
    "テキスト": "text",
    "アクセント": "accent",
    "ブランド": "brand",
  };
  const roleOf = (n: string): string => {
    const t = n.trim();
    return (JA_ROLES[t] ?? t).toLowerCase();
  };
  const hit = kit.colors.find((c) => typeof c?.name === "string" && roleOf(c.name) === lower);
  return hit && typeof hit.value === "string" ? hit.value : null;
}

/** Load the brand kit. Source precedence:
  *   1. localStorage (kit edited locally in the browser — the ONLY place
  *      the modal's Save writes, so it must win or edits are shadowed)
  *   2. Server-injected typed kit (future server-side persistence)
  *   3. brandKitJson from the LP-to-Creative handoff (a SEED, not an
  *      authority: the wizard mirrors its kit into the same localStorage
  *      key at handoff, so this only matters on a cold browser)
  *   4. EMPTY_KIT
  * localStorage used to be LAST, which silently discarded every modal
  * edit (colors/fonts/name/logo) whenever the creative came through the
  * wizard — i.e. always. Never throws — invalid payloads at any source
  * fall through to the next. */
export function loadBrandKit(campaignId?: string): BrandKit {
  const ctx = (typeof window !== "undefined")
    ? window.__DESIGNER__ as { brandKit?: BrandKit; brandKitJson?: string } | undefined
    : undefined;

  // 1. localStorage — the author's own edits win.
  if (typeof localStorage !== "undefined") {
    try {
      const raw = localStorage.getItem(storageKey(campaignId));
      if (raw) {
        const parsed = JSON.parse(raw) as BrandKit;
        if (parsed && Array.isArray(parsed.colors) && Array.isArray(parsed.fonts)) {
          return parsed;
        }
      }
    } catch {
      // Fall through.
    }
  }

  // 2. Typed server-injected kit.
  if (ctx?.brandKit && Array.isArray((ctx.brandKit as BrandKit).colors)) {
    return ctx.brandKit as BrandKit;
  }

  // 3. JSON string from the handoff form. Parse defensively.
  if (ctx?.brandKitJson) {
    try {
      const parsed = JSON.parse(ctx.brandKitJson) as BrandKit;
      if (parsed && Array.isArray(parsed.colors) && Array.isArray(parsed.fonts)) {
        return parsed;
      }
    } catch {
      // Fall through.
    }
  }

  return EMPTY_KIT;
}

/** Persist the kit to localStorage. No-op when localStorage isn't
  * available. */
export function saveBrandKit(kit: BrandKit, campaignId?: string): void {
  if (typeof localStorage === "undefined") return;
  try {
    localStorage.setItem(storageKey(campaignId), JSON.stringify(kit));
  } catch {
    // Quota exceeded or storage disabled — silent. The kit lives
    // only in memory until the page reloads.
  }
}

/** Clear the kit from localStorage. */
export function clearBrandKit(campaignId?: string): void {
  if (typeof localStorage === "undefined") return;
  try {
    localStorage.removeItem(storageKey(campaignId));
  } catch {
    // Silent.
  }
}

/** Subscribe to kit changes. Other modules (props-panel quick-pick
  * chips) listen so they re-render when the user edits the kit in
  * the modal. Returns an unsubscribe fn. */
type Listener = (kit: BrandKit) => void;
const listeners = new Set<Listener>();

export function subscribeBrandKit(listener: Listener): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function notifyBrandKitChanged(kit: BrandKit): void {
  for (const l of listeners) l(kit);
}
