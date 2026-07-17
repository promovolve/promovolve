// Re-exports the schema shared with the banner component, plus
// designer-only types that describe editor state (not used by the
// renderer). Putting both in one place means interaction code imports
// from a single `./types` module.

export type {
  BannerConfig,
  CircleItem,
  ImageItem,
  LayoutItem,
  MotionTarget,
  Page,
  RectItem,
  TextItem,
  TextureBg,
  VideoBg,
} from "@banner/types";

// Which mode the editor is in: the 16:9 master ("expanded") or a
// specific IAB banner size. When not "expanded", layout mutations
// target page.banners[sizeKey] instead of page.layout. The shape
// matches `Mode` from ./modes — re-exported to keep downstream imports
// pulling from a single place.
export type { Mode as EditMode } from "./modes";

// Context injected by the Go shell template as window.__DESIGNER__.
export interface DesignerContext {
  campaignId: string;
  landingUrl: string;
  creativeName: string;
  bannerSize: string;
  pages: unknown;         // JSON-parsed Page[]; validated on boot
  bannerScriptUrl: string;
  // Saved creative-wide BannerConfig JSON (logo, paper stock, reading
  // direction, entrance). Set on resume; empty/absent on first-time
  // authoring. MUST seed the store or the next save wipes the stored
  // config back to defaults.
  bannerConfigJson?: string;
  // Non-empty when a save/publish failed and the shell re-rendered the
  // designer with the submitted state — shown as a dismissible toast.
  errorMsg?: string;
  // Set when reopening a draft so Save Draft / Publish overwrite the
  // same row rather than creating a new creative per save. Empty on
  // first-time authoring from the editor.
  creativeId?: string;
  // Human-readable campaign name for the menu-bar identity cluster.
  // Optional — when the Go template doesn't provide it the menu bar
  // hides the "Campaign ▸" prefix and shows only the creative name.
  campaignName?: string;
  // Concatenated raw LP text captured during extraction. Forwarded
  // back on save so core can feed text (not image bytes) to Gemini
  // category verification. Empty when the creative was authored
  // without going through LP-to-Creative (direct upload / hand-built
  // creative) — verification then falls back to image.
  lpTextSnapshot?: string;
  // Server-side advertiser brand kit. When present, takes precedence
  // over any localStorage kit so the authoritative palette wins. The
  // shape is left as `unknown` here to avoid a hard dependency on
  // brand-kit.ts in the boot path; loadBrandKit() validates the shape
  // at consumption time.
  brandKit?: unknown;
  // Brand kit as a serialized JSON string, injected by the Go shell
  // when the LP-to-Creative flow's first step had a kit picked. Used
  // when the typed `brandKit` above isn't present (i.e., before the
  // server-side persistence work lands). Empty string when no kit.
  brandKitJson?: string;
  // Layout template chosen on the LP-to-Creative flow's first step.
  // One of the catalog ids served by /v1/layout-templates, or empty
  // when "no template (Gemini decides)" was chosen.
  templateId?: string;
  // Serialized [{family,weight,hash}] of LP-original fonts the
  // advertiser opted in to re-hosting (license checkbox in the wizard).
  // Passed through VERBATIM on save — presence is the consent record;
  // the designer neither reads nor edits it. Empty otherwise.
  lpFontsJson?: string;
}

// Top-level editor state. History (undo/redo) wraps this.
//
// selectedItemIdxs is the multi-selection list: insertion-ordered,
// deduped. Figma-style: click replaces selection, Shift+click toggles
// membership, marquee drag builds a selection from items inside the
// rectangle. Empty = nothing selected.
export interface DesignerState {
  pages: import("@banner/types").Page[];
  pageIdx: number;
  mode: import("./modes").Mode;
  selectedItemIdxs: number[];
  // Canvas zoom factor (1 = fit to viewport). View state only — mutated
  // via store.replace so undo/redo skips over it.
  zoom: number;
  // Banner-level config (one record per creative). Drives the
  // expand transition selection plus future settings (font, tag/sub
  // visibility). Edited from banner-config-panel; passed through to
  // the preview banner via the `config` attribute.
  bannerConfig: import("@banner/types").BannerConfig;
}

declare global {
  interface Window {
    __DESIGNER__?: DesignerContext;
  }
}
