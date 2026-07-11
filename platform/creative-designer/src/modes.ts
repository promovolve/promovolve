// The edit modes the designer supports: the 9:16 expanded reader
// (every device renders it — full-bleed on mobile, floating portrait
// sheet on PC) and four ASPECT BUCKETS for collapsed banners. The
// reader ("mobile") mutations target `page.banners["mobile-expanded"]`;
// bucket mutations target `page.banners[sizeKey]`.
//
// The 16:9 wide layout (storage `page.layout`) has NO TAB. Delivery
// renders the portrait master on every device (2026-07, see
// banner-component pickExpandedLayout), so 16:9 stopped being an
// authoring surface — but `page.layout` persists as a DELIVERY
// ARTIFACT: (a) 16:9-ish COLLAPSED slots render it (the implicit
// master candidate in pickCollapsedLayout), (b) creatives published
// before the portrait fanout fall back to it in the reader, and
// (c) masterColor anchors collapsed-bucket colours on it — the
// portrait master is often white-on-scrim (full-bleed image), which
// would be unreadable inherited onto flat collapsed backgrounds.
// The auto-layout fanout keeps authoring it invisibly (WIDE_MASTER
// below), and the pin/main-image flows keep its hero in sync; it's
// just not hand-editable anymore.
//
// Why buckets, not the full IAB size list: creatives are fluid —
// delivery picks the authored layout whose aspect is NEAREST the
// slot's and renders it into the slot's actual box (percent-based
// layout, no letterboxing). Per-pixel-size variants were waste: a
// 336×280 layout next to a 300×250 one was two Gemini generations and
// two author tabs for the same shape (both 1.20:1), while a 320×100
// slot got the squeezed 16:9 master because the old exact-match lookup
// ignored the almost-right strip layout. Four shapes cover the whole
// IAB zoo and every odd custom slot in between.
//
// sizeKeys keep their canonical pixel names ("300x250" etc.) so
// existing creatives' authored layouts stay first-class with no
// migration; layouts stored under retired keys (336x280, 970x90,
// 160x600, 320x50, 320x100) remain in the creative JSON and the
// nearest-aspect picker still serves them — they're just no longer
// editable tabs here.

export interface Mode {
  key: string;
  label: string;
  aspect: string; // e.g., "16/9" or "300/250"
  w: number;
  h: number;
  // Present for sized modes, absent for the wide master. Matches the
  // banner component's `page.banners[sizeKey]` indexing.
  sizeKey?: string;
  // What the size-matrix chip shows under the thumbnail instead of the
  // raw "w×h". The expanded reader isn't a fixed ad slot — it scales
  // to the viewer's screen — so its design-canvas pixels (540×960)
  // would read as a bookable size when they're not. Absent = "w×h".
  dims?: string;
}

/** The tabless 16:9 wide layout — NOT in MODES (no tab, no fanout
  * pill, doesn't gate publish). auto-layout still generates it into
  * `page.layout` so wide collapsed slots and legacy readers get a
  * template/preset-quality layout, and auto-crop sizes its items
  * against this canvas. */
export const WIDE_MASTER: Mode = { key: "expanded", label: "Wide (16:9)", aspect: "16/9", w: 1600, h: 900 };

// Order matters twice: it's the TAB order (size-matrix renders MODES
// as-is, portrait-first = the surface that actually ships opens first)
// and the FANOUT order (auto-layout fires cells in MODES order; the
// tabless WIDE_MASTER is fired FIRST, before any of these, so
// page.layout holds its resolved fonts before the bucket presets
// inherit them — see presets.ts expandedFont).
export const MODES: readonly Mode[] = [
  { key: "mobile",   label: "Expanded (9:16)", aspect: "9/16",    w: 540,  h: 960, sizeKey: "mobile-expanded",
    dims: "full screen" },
  // Aspect buckets; the pixel size is the canonical design canvas.
  { key: "300x250",  label: "Rectangle (6:5)", aspect: "300/250", w: 300,  h: 250, sizeKey: "300x250" },
  { key: "970x250",  label: "Billboard (4:1)", aspect: "970/250", w: 970,  h: 250, sizeKey: "970x250" },
  { key: "728x90",   label: "Strip (8:1)",     aspect: "728/90",  w: 728,  h: 90,  sizeKey: "728x90" },
  // Mobile banner strip — the highest-volume family on mobile-first
  // traffic. 3.2:1 sits between Billboard (3.88:1) and Strip (8:1); an
  // authored master keeps 320x50/320x100-ish slots from squeezing either
  // neighbour. Preset already existed (presets.ts wideMobile); delivery
  // needs no change — pickCollapsedLayout matches any WxH bucket by
  // nearest aspect, and creatives published before this bucket keep
  // resolving to their nearest existing one.
  { key: "320x100",  label: "Mobile (16:5)",   aspect: "320/100", w: 320,  h: 100, sizeKey: "320x100" },
  { key: "300x600",  label: "Tall (1:2)",      aspect: "300/600", w: 300,  h: 600, sizeKey: "300x600" },
];

export function findMode(key: string): Mode {
  if (key === WIDE_MASTER.key) return WIDE_MASTER;
  return MODES.find((m) => m.key === key) ?? MODES[0]!;
}

export function isSized(mode: Mode): boolean {
  return mode.sizeKey !== undefined;
}

/** Multi-page modes walk through every page of the magazine; IAB-sized
  * modes only render the cover frame. Only the portrait reader is
  * multi-page now — the wide master has no tab to walk. */
export function isMultiPage(mode: Mode): boolean {
  return mode.key === "mobile";
}
