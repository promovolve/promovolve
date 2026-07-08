// <magazine-preview> — the SHARED creative-preview component used by both
// the publisher approval page (server-rendered Go template) and the
// advertiser designer (client-side TS). A single custom element so the
// two previews can never drift: change it once, both update.
//
// It owns the preview CHROME — a PC / Mobile toggle and the two framed
// stages (PC = 16:9 card, Mobile = phone bezel) — and reuses the
// <expandable-magazine-banner> for the actual render. Both stages mount
// the banner with preview-frame="1" (kept inside the box, un-tracked)
// and the active one auto-expands so the magazine shows immediately.
//
// Usage (identical in either context):
//   <magazine-preview pages='[...]'></magazine-preview>
//   <magazine-preview pages='[...]' config='{...}' landing-url="..."
//                     default-mode="mobile"></magazine-preview>
//
// Styling is self-contained (Shadow DOM + inline styles), so it carries
// no dependency on the host page's CSS (Tailwind on the publisher side,
// the designer's token sheet on the advertiser side).

type Mode = "pc" | "mobile";

// One stop of the slot-size tour (slot-tour="1"): a standard ad dimension
// plus how the mock article places it. "inline" = boxed unit inside the
// copy column (the classic 300x250 read); "top" = full-width leaderboard
// row above the columns; "rail" = tall unit in a right-hand rail beside
// the copy. Mobile is a single column, so everything places inline there.
interface SlotStop {
  w: number;
  h: number;
  name: string;
}

const PC_SLOTS: SlotStop[] = [
  { w: 300, h: 250, name: "Medium Rectangle" },
  { w: 728, h: 90, name: "Leaderboard" },
  { w: 336, h: 280, name: "Large Rectangle" },
  { w: 300, h: 600, name: "Half Page" },
];
const MOBILE_SLOTS: SlotStop[] = [
  { w: 300, h: 250, name: "Medium Rectangle" },
  { w: 320, h: 100, name: "Large Mobile Banner" },
  { w: 320, h: 50, name: "Mobile Banner" },
];

type Placement = "inline" | "top" | "rail";

function placementFor(stop: SlotStop, mobile: boolean): Placement {
  if (mobile) return "inline";
  if (stop.w >= 600) return "top";
  if (stop.h / stop.w >= 1.6) return "rail";
  return "inline";
}

const LOREM =
  "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";

// Renders a newspaper-article FRAGMENT into `host` (a preview stage = the
// device "screen") with the ad `slot` embedded in it: a few columns of body
// copy fill the frame, the creative sits among them with article text on all
// four sides, and the frame simply clips to that piece. No masthead, no
// headline — just a believable scrap of an article with an ad in it. The
// preview thus simulates production delivery: the creative SITS IN the page
// (not floating over it); clicking it expands the reader over the dimmed page.
//
// Body type is sized in cqw (container units on `host`) at ROUGHLY the
// creative's own text size, so the ad reads as part of the article rather
// than pasted onto a shrunk-down page — and everything scales together with
// the preview. `host` must be a size container (container-type:size) and the
// positioning context for the banner's expand overlay (position:relative +
// overflow:hidden). The article is aria-hidden + inert; only the slot is live.
function buildNewspaperInto(
  host: HTMLElement,
  slot: HTMLElement,
  mobile: boolean,
  placement: Placement = "inline",
): void {
  host.style.background = "#f3efe6";
  host.style.color = "#1a1a1a";
  host.style.fontFamily = "Georgia, 'Times New Roman', serif";
  host.style.display = "flex";
  host.style.flexDirection = placement === "top" ? "column" : "row";
  host.style.gap = mobile ? "0" : "3cqw";
  host.style.padding = mobile ? "2.5cqw 2.5cqw" : "2.5cqw 3cqw";

  // Body copy ≈ the creative's own text size (relatively close) so the ad
  // blends into the article. Larger than a real dense newspaper on purpose:
  // matching the creative is what sells the "ad placed in an article" read.
  const bodyFs = mobile ? "5.8cqw" : "1.6cqw";

  // A real publisher page places an ad BETWEEN whole paragraphs, and the page
  // SCROLLS — so article copy is never cut mid-line against the ad; only the
  // viewport edge clips. Mirror that instead of clipping continuous text:
  // render discrete COMPLETE paragraphs. The region ABOVE the ad is
  // bottom-anchored (justify-content:flex-end) so its last whole paragraph
  // ends cleanly right at the label while older paragraphs run off the TOP
  // edge like scrolled-past copy; the region BELOW is top-anchored so a fresh
  // whole paragraph starts under the slot and later ones run off the bottom.
  // No fade, no mid-line cut at the label — the only clipping is at the frame
  // edge, exactly like a real article. (Split on ". " rather than a lookbehind
  // so older Safari doesn't choke at parse time.)
  const SENTENCES = LOREM.split(/\.\s+/).filter(Boolean).map((s) =>
    s.endsWith(".") ? s : s + ".",
  );
  const paragraph = (idx: number): HTMLElement => {
    const p = document.createElement("p");
    p.style.cssText = "margin:0";
    p.textContent =
      SENTENCES[(idx * 2) % SENTENCES.length] +
      " " +
      SENTENCES[(idx * 2 + 1) % SENTENCES.length];
    return p;
  };
  const copyColumn = (count: number, anchor: "top" | "bottom"): HTMLElement => {
    const c = document.createElement("div");
    c.setAttribute("aria-hidden", "true");
    c.style.cssText = [
      "flex: 1 1 0",
      "min-width: 0",
      "min-height: 0",
      "overflow: hidden",
      `font-size: ${bodyFs}`,
      "line-height: 1.5",
      "text-align: justify",
      "display: flex",
      "flex-direction: column",
      `justify-content: ${anchor === "bottom" ? "flex-end" : "flex-start"}`,
      "gap: 0.9em",
    ].join(";");
    for (let i = 0; i < count; i++) c.append(paragraph(i));
    return c;
  };

  // The slot stays in static flow so the banner's expand overlay still
  // escapes to fill the whole frame. Placement-specific sizing tweaks live
  // in the branches below — in particular the rail's HEIGHT-driven slot
  // must not get a competing max-width: with both axes constrained the
  // aspect-ratio can't hold, the box comes out taller than the render, and
  // the banner centers its design box leaving pad-like gaps top and bottom.
  slot.style.flex = "0 0 auto";

  // "Advertisement" disclosure — a labeled break between the paragraph above
  // and the slot, like the real page. The paragraph boundary carries the
  // separation, so the label needs only modest symmetric margin.
  const label = document.createElement("div");
  label.className = "ad-label";
  label.setAttribute("aria-hidden", "true");
  label.textContent = "Advertisement";
  label.style.cssText = [
    "text-align: center",
    `font-size: ${mobile ? "2.6cqw" : "0.95cqw"}`,
    "letter-spacing: 0.12cqw",
    "color: rgba(0,0,0,0.5)",
    "font-family: system-ui, sans-serif",
    `margin: ${mobile ? "2.4cqw 0 1.4cqw" : "1.4cqw 0 0.8cqw"}`,
    "flex: 0 0 auto",
  ].join(";");

  if (placement === "top" && !mobile) {
    // Leaderboard/billboard: the unit spans the page above the article,
    // exactly where a real desktop page hangs it. Label + ad in a
    // full-width row, then the copy columns fill the rest of the frame.
    const adRow = document.createElement("div");
    adRow.style.cssText = "flex:0 0 auto;display:flex;flex-direction:column;align-items:center;";
    slot.style.margin = "0 auto 1cqw";
    adRow.append(label, slot);
    const cols = document.createElement("div");
    cols.style.cssText = "flex:1 1 0;min-height:0;display:flex;gap:3cqw;overflow:hidden;";
    cols.append(copyColumn(8, "top"), copyColumn(8, "top"), copyColumn(8, "top"));
    host.append(adRow, cols);
    return;
  }

  if (placement === "rail" && !mobile) {
    // Half-page/skyscraper: the tall unit lives in a right-hand rail
    // beside the copy — the placement those formats are sold for.
    const rail = document.createElement("div");
    rail.style.cssText = "flex:0 0 auto;min-width:0;display:flex;flex-direction:column;align-items:center;";
    slot.style.margin = "0";
    rail.append(label, slot);
    host.append(copyColumn(7, "top"), copyColumn(7, "top"), rail);
    return;
  }

  // In-column placement: centered with breathing room off the column edges.
  slot.style.margin = mobile ? "0 auto 1.6cqw" : "0 auto 1cqw";
  slot.style.maxWidth = "94%";

  const adCol = document.createElement("div");
  adCol.style.cssText = "flex:1 1 0;min-width:0;display:flex;flex-direction:column;";
  adCol.append(
    copyColumn(mobile ? 3 : 5, "bottom"),
    label,
    slot,
    copyColumn(mobile ? 3 : 5, "top"),
  );

  if (mobile) {
    host.append(adCol);                                       // single column, phone
  } else {
    host.append(copyColumn(6, "top"), adCol, copyColumn(6, "top")); // copy LEFT | ad | RIGHT
  }
}

interface PreviewBanner extends HTMLElement {
  _expanded?: boolean;
  _expand?: () => void;
  _replayExpandEffect?: () => void;
}

export class MagazinePreview extends HTMLElement {
  private root: ShadowRoot;
  private pcWrap!: HTMLDivElement;
  private mbWrap!: HTMLDivElement;
  private pcBanner!: PreviewBanner;
  private mbBanner!: PreviewBanner;
  private pcBtn!: HTMLButtonElement;
  private mbBtn!: HTMLButtonElement;
  private built = false;
  private mode: Mode = "pc";
  private io: IntersectionObserver | null = null;
  private ro: ResizeObserver | null = null;
  private noAutoExpand = false;
  // Slot-size tour (slot-tour="1"): cycle the collapsed unit through the
  // standard dimensions, rebuilding the mock article around each. Paused
  // while the pointer is over a stage and while the reader is expanded.
  private caption: HTMLDivElement | null = null;
  private tourTimer: number | null = null;
  private tourIdx = 0;
  private tourPaused = false;
  // A dog-eared (pinned) creative holds the tour: the fold says "I'm
  // interested in THIS" — don't shape-shift the page under it. Unfolding
  // releases the hold.
  private tourHeld = false;

  private get touring(): boolean {
    return this.getAttribute("slot-tour") === "1";
  }

  private slotList(mode: Mode): SlotStop[] {
    return mode === "mobile" ? MOBILE_SLOTS : PC_SLOTS;
  }

  constructor() {
    super();
    this.root = this.attachShadow({ mode: "open" });
  }

  connectedCallback(): void {
    if (this.built) return;
    this.built = true;
    this.build();
    this.mode = this.getAttribute("default-mode") === "mobile" ? "mobile" : "pc";
    this.selectMode(this.mode);
    // start-collapsed: show the served ad (a 300x250 unit on the screen)
    // and DON'T auto-expand — the publisher sees the ad as a visitor would
    // FIND it on the page, and clicks it to open the magazine; close
    // returns to it (keep-frame). The designer omits this and auto-expands.
    this.noAutoExpand = this.getAttribute("start-collapsed") === "1";
    if (this.noAutoExpand) {
      // Reveal the collapsed render mkBanner parked at opacity 0 — there's
      // no expand to do it. The banner's own click handler expands it.
      this.pcBanner.style.opacity = "1";
      this.mbBanner.style.opacity = "1";
    } else {
      // Defer expansion until the preview is actually VISIBLE. On the
      // publisher side the detail panel starts hidden and is revealed
      // later, so expanding on connect would measure a zero-size box; in
      // the designer modal the element is visible at once and this fires
      // immediately. Either way the active frame expands the moment it
      // has layout.
      this.io = new IntersectionObserver((entries) => {
        if (entries.some((e) => e.isIntersecting)) this.expandActive();
      });
      this.io.observe(this);
    }
    // Track host width for the adaptive bezel (fires once on observe, so
    // the initial aspect is right before first paint).
    this.ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect?.width ?? this.clientWidth;
      if (w > 0) this.fitBezelAspect(w);
    });
    this.ro.observe(this);
    if (this.touring) {
      const raw = parseInt(this.getAttribute("slot-tour-interval") ?? "", 10);
      const interval = Number.isFinite(raw) && raw >= 1500 ? raw : 5000;
      this.tourTimer = window.setInterval(() => this.tourTick(), interval);
      // Hovering a stage means the viewer is looking/interacting — hold.
      for (const w of [this.pcWrap, this.mbWrap]) {
        w.addEventListener("pointerenter", () => { this.tourPaused = true; });
        w.addEventListener("pointerleave", () => { this.tourPaused = false; });
      }
      this.updateCaption();
    }
    // Note: closing the magazine inside the preview is handled per host.
    // The designer modal listens for the banner's `magazine-collapse` and
    // dismisses the whole preview. The publisher pane sets keep-frame, so
    // close instead collapses the creative back into the frame (the banner
    // restores its own collapsed render) and the pane stays open.
  }

  disconnectedCallback(): void {
    this.io?.disconnect();
    this.io = null;
    this.ro?.disconnect();
    this.ro = null;
    if (this.tourTimer !== null) {
      clearInterval(this.tourTimer);
      this.tourTimer = null;
    }
  }

  /** One tour step: advance to the next slot size for the active mode and
   *  rebuild that stage's article around it. Skipped while hidden, while
   *  hovered, and while the reader is expanded (never yank the page out
   *  from under someone reading the magazine). */
  private tourTick(): void {
    if (this.tourPaused || this.tourHeld) return;
    if (!this.offsetParent && this.offsetWidth === 0) return; // hidden
    const active = this.mode === "mobile" ? this.mbBanner : this.pcBanner;
    if (active?._expanded) return;
    const list = this.slotList(this.mode);
    this.tourIdx = (this.tourIdx + 1) % list.length;
    this.buildStage(this.mode === "mobile", list[this.tourIdx]);
    this.updateCaption();
  }

  private updateCaption(): void {
    if (!this.caption) return;
    if (!this.touring) {
      this.caption.style.display = "none";
      return;
    }
    const list = this.slotList(this.mode);
    const stop = list[this.tourIdx % list.length];
    this.caption.style.display = "";
    this.caption.textContent = this.tourHeld
      ? `${stop.w}×${stop.h} · ${stop.name} — paused (page pinned; unfold to resume)`
      : `${stop.w}×${stop.h} · ${stop.name} — cycling slot sizes (hover to pause)`;
  }

  /** Adapt the PC bezel's aspect to the host width. The expanded reader is
   * a 9:16 PORTRAIT sheet whose size is bounded by bezel HEIGHT (= width /
   * aspect): at 3:2 a narrow host yields a stubby bezel and a small sheet.
   * Keep the wide desktop look when there's room; trade width for height
   * as the host narrows so the sheet stays the visual subject. */
  private fitBezelAspect(hostWidth: number): void {
    const aspect = hostWidth >= 900 ? "3 / 2" : hostWidth >= 700 ? "4 / 3" : "1 / 1";
    if (this.pcWrap.style.aspectRatio !== aspect) this.pcWrap.style.aspectRatio = aspect;
  }

  /** (Re)build one stage: a fresh banner at `stop`'s dimensions, embedded
   *  in a freshly-laid newspaper whose placement suits the slot's shape.
   *  Used for the initial build (first stop) and for every tour tick. */
  private buildStage(mobile: boolean, stop: SlotStop): void {
    const wrap = mobile ? this.mbWrap : this.pcWrap;
    const banner = this.mkBanner(mobile, stop);
    if (this.touring) {
      // The dog-ear events bubble composed from the banner host.
      banner.addEventListener("dogear-fold", () => { this.tourHeld = true; this.updateCaption(); });
      banner.addEventListener("dogear-unfold", () => { this.tourHeld = false; this.updateCaption(); });
    }
    if (this.noAutoExpand) banner.style.opacity = "1";
    const slot = document.createElement("div");
    // Wide/boxy slots are width-driven; TALL slots (half page) must be
    // height-driven or they'd collapse inside the content-sized rail and
    // overflow the bezel vertically — cap at most of the frame height and
    // let the aspect ratio derive the width.
    const tall = stop.h > stop.w;
    slot.style.cssText = [
      "position: static",
      tall ? `height: min(${stop.h}px, 86cqh)` : "width: 100%",
      tall ? "" : `max-width: min(${stop.w}px, 92cqw)`,
      `aspect-ratio: ${stop.w} / ${stop.h}`,
      "margin: 0 auto",
      "overflow: visible",
    ].filter(Boolean).join(";");
    slot.appendChild(banner);
    wrap.replaceChildren();
    buildNewspaperInto(wrap, slot, mobile, placementFor(stop, mobile));
    if (mobile) this.mbBanner = banner;
    else this.pcBanner = banner;
  }

  private mkBanner(mobile: boolean, stop: SlotStop): PreviewBanner {
    const b = document.createElement("expandable-magazine-banner") as PreviewBanner;
    // Hidden until it has expanded. The banner renders its COLLAPSED
    // state synchronously on connect, and only expands a frame later
    // (expandActive's rAF). Without this, that collapsed render flashes
    // before the expand — the two-stage reveal. Keeping it at opacity 0
    // until expandActive reveals it means the configured expand effect
    // is the first thing seen, in every host (designer modal + publisher
    // panel), with no per-host code.
    b.style.opacity = "0";
    const pages = this.getAttribute("pages");
    if (pages) b.setAttribute("pages", pages);
    const config = this.getAttribute("config");
    if (config) b.setAttribute("config", config);
    const landing = this.getAttribute("landing-url");
    if (landing) b.setAttribute("landing-url", landing);
    b.setAttribute("preview-frame", "1");
    // keep-frame opt-in (publisher approval): close collapses the creative
    // back into the frame instead of the host dropping the whole preview.
    if (this.getAttribute("keep-frame") === "1") b.setAttribute("keep-frame", "1");
    // The collapsed unit renders at the stop's real ad dimensions: the
    // attrs drive pickCollapsedLayout to the matching authored layout and
    // width/height:100% fill the slot box buildStage sizes to the stop.
    // force-mobile keeps the EXPANDED reader mobile-optimized on the phone.
    if (mobile) b.setAttribute("force-mobile", "1");
    b.setAttribute("width", String(stop.w));
    b.setAttribute("height", String(stop.h));
    b.style.width = "100%";
    b.style.height = "100%";
    return b;
  }

  private build(): void {
    // Custom elements are display:inline by default, which collapses the
    // inner width:100% to nothing — make the host a full-width block so
    // the PC card can grow to its max-width.
    const style = document.createElement("style");
    style.textContent = ":host{display:block;width:100%;}";
    this.root.appendChild(style);

    const wrap = document.createElement("div");
    wrap.style.cssText = [
      "display: flex",
      "flex-direction: column",
      "align-items: center",
      "gap: 10px",
      "width: 100%",
    ].join(";");

    // PC / Mobile toggle.
    const toggles = document.createElement("div");
    toggles.style.cssText = "display:flex;gap:6px;";
    this.pcBtn = this.mkToggle("PC", () => this.show("pc"));
    this.mbBtn = this.mkToggle("Mobile", () => this.show("mobile"));
    toggles.append(this.pcBtn, this.mbBtn);

    // PC: a desktop BROWSER-WINDOW frame (3:2); preview-frame keeps the
    // expanded magazine inside this box (absolute, not fullscreen).
    // Sized to match the designer's preview (up to 900px wide, capped
    // to the viewport). Was a 16:9 tablet bezel while the expanded PC
    // reader was the 16:9 master — but the reader is a PORTRAIT sheet
    // now (portrait unification, 2026-07), so the bezel's HEIGHT is
    // what bounds the sheet: at 16:9 the "expanded" sheet came out
    // NARROWER than the collapsed 300px ad it grew from. 3:2 keeps the
    // desktop-page read (multi-column article) while giving the
    // portrait sheet enough height to preview at a useful size.
    this.pcWrap = document.createElement("div");
    this.pcWrap.style.cssText = [
      "position: relative",
      "box-sizing: border-box",
      // Size container so the article-fragment type scales with the preview
      // (≈ the creative) — see buildNewspaperInto, which fills this box with
      // copy around the embedded ad. The expand overlay is absolute and
      // fills the whole bezel regardless (pcWrap is its positioning context
      // and clips it).
      "container-type: size",
      "width: 100%",
      "max-width: 920px",
      // Aspect is ADAPTIVE (see fitBezelAspect): 3:2 at full width for the
      // desktop-page read, squarer as the host narrows — the expanded
      // portrait sheet is height-bound, and at 3:2 a narrow host (e.g. the
      // approval detail pane) starves it until it reads smaller than the
      // newspaper filler beside it.
      "aspect-ratio: 3 / 2",
      "max-height: 80vh",
      "overflow: hidden",
      "border-radius: 20px",
      "border: 9px solid #111",
      "box-shadow: 0 8px 30px rgba(0,0,0,0.3)",
    ].join(";");
    // Initial stage = the first tour stop (300x250 Medium Rectangle —
    // identical to the historical fixed slot). buildStage owns the slot
    // box + newspaper layout; tour ticks rebuild through the same path.
    this.buildStage(false, PC_SLOTS[0]);

    // Mobile: 9:16 phone bezel — same newspaper-embed treatment, single
    // column.
    this.mbWrap = document.createElement("div");
    this.mbWrap.style.cssText = [
      "position: relative",
      "box-sizing: border-box",
      "container-type: size",
      "width: 320px",
      "height: 640px",
      "max-width: 100%",
      "overflow: hidden",
      "border-radius: 28px",
      "border: 8px solid #111",
      "box-shadow: 0 8px 30px rgba(0,0,0,0.3)",
    ].join(";");
    this.buildStage(true, MOBILE_SLOTS[0]);

    // Tour caption ("728×90 · Leaderboard — cycling…"); hidden unless the
    // slot tour is active.
    this.caption = document.createElement("div");
    this.caption.style.cssText =
      "font-family:system-ui,sans-serif;font-size:11px;color:#777;display:none;";
    wrap.append(toggles, this.caption, this.pcWrap, this.mbWrap);
    this.root.appendChild(wrap);
  }

  private mkToggle(label: string, onClick: () => void): HTMLButtonElement {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    b.style.cssText = [
      "padding: 4px 14px",
      "border-radius: 4px",
      "border: 1px solid #d0d0d6",
      "background: transparent",
      "color: #555",
      "font: inherit",
      "font-size: 12px",
      "cursor: pointer",
    ].join(";");
    b.addEventListener("click", onClick);
    return b;
  }

  private setToggle(active: HTMLButtonElement, inactive: HTMLButtonElement): void {
    active.style.borderColor = "#2563eb";
    active.style.background = "#eff4ff";
    active.style.color = "#2563eb";
    inactive.style.borderColor = "#d0d0d6";
    inactive.style.background = "transparent";
    inactive.style.color = "#555";
  }

  /** Switch which frame is shown (DOM + toggle styling), and expand it
   *  if the preview is already visible. */
  private show(mode: Mode): void {
    this.selectMode(mode);
    if (this.touring) {
      // Keep stage and caption in agreement across a device switch: the
      // other stage was last built at some earlier stop of ITS list.
      const list = this.slotList(mode);
      this.tourIdx = this.tourIdx % list.length;
      const active = mode === "mobile" ? this.mbBanner : this.pcBanner;
      if (!active?._expanded) this.buildStage(mode === "mobile", list[this.tourIdx]);
      this.updateCaption();
    }
    if (this.noAutoExpand) {
      (mode === "mobile" ? this.mbBanner : this.pcBanner).style.opacity = "1";
    } else {
      this.expandActive();
    }
  }

  /** DOM show/hide + toggle styling only — no expansion. */
  private selectMode(mode: Mode): void {
    this.mode = mode;
    const mobile = mode === "mobile";
    // Both stages are flex columns (the newspaper article) — restore "flex"
    // to show, NOT "" which would clear the inline display set in build()
    // and drop the article back to block flow.
    this.pcWrap.style.display = mobile ? "none" : "flex";
    this.mbWrap.style.display = mobile ? "flex" : "none";
    this.setToggle(mobile ? this.mbBtn : this.pcBtn, mobile ? this.pcBtn : this.mbBtn);
  }

  /** Expand the active frame once it has layout (rAF). Already-expanded
   *  banners keep their state when toggled back. No-op while hidden —
   *  the IntersectionObserver re-invokes this when the preview shows. */
  private expandActive(): void {
    const banner = this.mode === "mobile" ? this.mbBanner : this.pcBanner;
    if (banner._expanded) return;
    if (!this.offsetParent && this.offsetWidth === 0) return; // still hidden
    requestAnimationFrame(() => {
      try { banner._expand?.(); } catch { /* boot race — element not upgraded yet */ }
      // Reveal now that the expanded overlay exists (or, on a boot race,
      // fall back to whatever rendered rather than a permanently blank
      // frame). The expand effect's keyframes are already armed on the
      // wrapper, so this shows the entrance, not a hard pop.
      banner.style.opacity = "1";
    });
  }

  /** Replay the active frame's configured open effect as an entrance
   *  animation. The host (designer preview modal) calls this the moment
   *  it fades the pre-expanded preview in, so the creative's chosen
   *  effect (fade / slide-up / CRT) plays on the already-rendered
   *  magazine — a real entrance, with no collapsed-creative two-stage. */
  replayEntrance(): void {
    const banner = this.mode === "mobile" ? this.mbBanner : this.pcBanner;
    banner._replayExpandEffect?.();
  }
}
