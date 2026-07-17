import type { BannerConfig, ExpandAnimation, LayoutItem, MotionTarget, Page, PaperFeel, TextureBg, VideoBg } from "./types";
import { PAPER_FEEL } from "./types";
import { fontMain, fontUI } from "./fonts";
import { layoutItemToNode } from "./layout-item";
import { applyTargetState, autoFitText, harmonizeAutofit, transitionFor } from "./motion";
import { renderCollapsedItemHtml } from "./render-collapsed";
import {
  buildCloseButton,
  buildExpandWrapper,
  buildNavButton,
  buildOverlay,
  buildPageCounter,
} from "./render-overlay";
import { collectExpandedImageUrls, parseJSON, pickCollapsedLayout, pickExpandedLayout, sheetFitPct, sheetSizeFor } from "./utils";
import { resolveExpandedFonts } from "./font-catalog";
import { animatePageTurn, createInteractivePeel, buildGrainOverlay, dogEarPeelFrame, DOGEAR_PEEL_TRAVEL, PAPER_CSS, PAPER_BACK_BLEND, paperBackBackground } from "./paper";

// The reader deals its sheets in by default (the kawaraban lifecycle);
// an advertiser can opt out via cfg.entrance = "fade" for a plain fade
// in and out. The legacy expandAnimation field is deliberately ignored:
// creatives saved before 2026-07-17 all carry "fade" from the old
// default and must not be robbed of the deal by it. (Reduced-motion
// overrides to a flat fade at the call sites.)
function resolveExpandEffect(cfg: BannerConfig): ExpandAnimation {
  return cfg.entrance === "fade" ? "fade" : "stack";
}

// Effect CSS, motion helpers, and pure utilities live in their own
// modules (expand-effects.ts, motion.ts, utils.ts) so this file
// stays focused on the custom-element lifecycle, render pipeline,
// and overlay machinery. DEFAULT_CONFIG stays here because it's the
// fallback the configData getter reads when an instance has no
// `config` attribute set — tightly coupled to the class's behavior.

const DEFAULT_CONFIG: BannerConfig = {
  layout: "auto",
  font: "sans",
  showTag: true,
  showSub: true,
  paperWeight: "medium",
};

export class ExpandableMagazineBanner extends HTMLElement {
  private _expanded = false;
  private currentPage = 0;
  // Interactive corner-peel (thumb-driven forward turn). Non-null only
  // while a peel gesture is live.
  private _peel: { scrubTo: (t: number) => void; release: (commit: boolean, v0?: number) => void } | null = null;
  private _peelStartX = 0;
  private _peelWidth = 1;
  // Release momentum: smoothed fold-progress velocity (units/sec) and the
  // last sample, so endPeel can throw the page at the speed the thumb was
  // moving and let a fast flick commit even short of the distance line.
  private _peelVel = 0;
  private _peelLastP = 0;
  private _peelLastMs = 0;
  private readonly handleKeyDown: (e: KeyboardEvent) => void;
  // Guards against double-firing the preload when the banner is
  // scrolled out and back in, or when attributes thrash.
  private _preloadFired = false;
  private _preloadObserver: IntersectionObserver | null = null;
  // One-shot re-render for banners first rendered inside display:none
  // (container-query units stick at viewport fallback otherwise).
  private _hiddenRenderRO: ResizeObserver | null = null;
  // Same idempotency pattern for the impression beacon — once fired
  // for a given mount, never re-fire even if attributes change or the
  // banner scrolls out and back in.
  private _impressionFired = false;
  private _impressionObserver: IntersectionObserver | null = null;
  // One-shot on-view teaser peel: a transient corner-lift hint played
  // when the collapsed banner first becomes viewable, advertising that
  // the ad can be dog-eared. Purely visual — it settles back and never
  // sets fold state or pins (that stays a deliberate user gesture).
  private _teaserPlayed = false;
  // Expanded-reader tease budget: at most ONE corner-peel demonstration
  // per magazine-open, shared across all pages and both input paths
  // (touch top-sheet tease + hover tease). Per-page teasing played the
  // flap on every swipe, which read as nagging rather than a hint —
  // the static dashed crease carries the affordance after the one play.
  // Reset in _expand() so each reading session gets its single hint.
  private _overlayTeasePlayed = false;
  /** Check-and-consume the per-open tease budget. Returns true exactly
    * once per magazine-open; passed into buildDogEar as the gate. */
  private consumeOverlayTease = (): boolean => {
    if (this._overlayTeasePlayed) return false;
    this._overlayTeasePlayed = true;
    return true;
  };
  // Dog-ear handles for every page in the current render. Used by the
  // host-level fold/unfold listener to broadcast visual state across
  // all pages so folding one page folds every page (the pin is on the
  // creative, not a specific page index).
  private _dogEars: DogEarHandle[] = [];
  // Idempotency for the click beacon. A user that expands → closes →
  // expands again would re-fire the same signed click-url (same `rid`)
  // and get 409 from the server's duplicate check. Track per-mount so
  // the beacon only goes out for the first expansion of this serve.
  private _clickFired = false;
  // Framed-preview only: timer that hides the collapsed render AFTER the
  // expand animation, so the ad doesn't blank-out mid-fade (see _expand).
  private _collapseHideTimer: number | null = null;
  // Removes the window/visualViewport resize listeners the mobile
  // sheet-stack installs to keep page heights pinned to the measured
  // reader height. Set on expand (mobile), called on collapse.
  // Page index the reader currently sees, as opposed to currentPage
  // (the target). Lets updatePages know which page a navigation came
  // FROM so the paper page-turn can animate between the two. null =
  // overlay just (re)built, nothing displayed yet → no turn.
  private _displayedPage: number | null = null;
  // Captured once per expand; the page-turn falls back to the plain
  // cross-fade when the user prefers reduced motion.
  private _reducedMotion = false;
  // Finish-now handle for an in-flight page turn. Called before
  // starting the next turn so spamming next/prev can't stack
  // half-finished animations.
  private _finishTurn: (() => void) | null = null;

  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this.handleKeyDown = (e) => this.onKeyDown(e);
  }

  static get observedAttributes(): string[] {
    return ["pages", "width", "height", "config", "collapsed-page-index", "mode", "force-mobile", "preview-frame", "imp-url"];
  }

  private get editMode(): boolean {
    return this.getAttribute("mode") === "edit";
  }

  private get collapsedPageIdx(): number {
    // Attribute wins (designer canvas / preview), so editor surfaces
    // can force a specific cover. Production never sets it — the
    // bootstrap leaves it off, so we fall back to `config.coverPageIdx`
    // (author's pick at design time) and finally to 0.
    const attr = this.getAttribute("collapsed-page-index");
    if (attr !== null) {
      const n = parseInt(attr, 10);
      return Number.isFinite(n) ? n : 0;
    }
    const cfgIdx = this.configData.coverPageIdx;
    return typeof cfgIdx === "number" && Number.isFinite(cfgIdx) ? cfgIdx : 0;
  }

  /** True when the corner should be rendered. Default-on; the bootstrap
    * explicitly opts OUT by setting `data-can-fold="false"` on serves
    * where the campaign isn't dog-ear-enabled. This keeps the corner
    * visible in the designer canvas (mode="edit"), the designer's
    * preview modal (any device size), and any legacy ad-tag flow that
    * mounts the banner without bootstrap context — i.e., everywhere
    * the pre-dogear `main` showed it. Only real publisher serves on
    * non-CPF campaigns suppress it.
    */
  private get canFold(): boolean {
    return this.getAttribute("data-can-fold") !== "false";
  }

  /** Page index (0-based) that was originally folded by the reader on
    * a prior visit and is being honored by the server this serve. -1
    * when no pin is honored — corners render in the unfolded state.
    */
  private get pinnedPageIdx(): number {
    const raw = this.getAttribute("data-pinned-page");
    if (raw === null) return -1;
    const n = parseInt(raw, 10);
    return Number.isFinite(n) ? n : -1;
  }

  private get pagesData(): Page[] {
    const attr = this.getAttribute("pages");
    if (attr) {
      try {
        const parsed = JSON.parse(attr) as unknown;
        return Array.isArray(parsed) ? (parsed as Page[]) : [];
      } catch {
        // fall through
      }
    }
    return [];
  }

  private get configData(): BannerConfig {
    const attr = this.getAttribute("config");
    if (attr) {
      try {
        const parsed = JSON.parse(attr) as Partial<BannerConfig>;
        return { ...DEFAULT_CONFIG, ...parsed };
      } catch {
        // fall through
      }
    }
    return { ...DEFAULT_CONFIG };
  }

  connectedCallback(): void {
    // Per-creative dog-ear sync — folding any one page should fold
    // every page of the same creative (the pin is for the creative,
    // not a specific page). The dog-ear click handlers only dispatch
    // events; this listener does the actual visual application across
    // all pages and updates the host attribute so a re-render after
    // close → reopen mounts in the right state.
    this.addEventListener("dogear-fold", this.onDogearFold);
    this.addEventListener("dogear-unfold", this.onDogearUnfold);
    if (this.pagesData.length > 0) this.render();
    this.scheduleExpandedPreload();
    this.scheduleImpressionFire();
  }

  attributeChangedCallback(): void {
    if (this.isConnected && this.pagesData.length > 0) this.render();
    this.scheduleExpandedPreload();
    this.scheduleImpressionFire();
  }

  disconnectedCallback(): void {
    window.removeEventListener("keydown", this.handleKeyDown);
    this.removeEventListener("dogear-fold", this.onDogearFold);
    this.removeEventListener("dogear-unfold", this.onDogearUnfold);
    this._preloadObserver?.disconnect();
    this._preloadObserver = null;
    this._impressionObserver?.disconnect();
    this._impressionObserver = null;
    this._hiddenRenderRO?.disconnect();
    this._hiddenRenderRO = null;
  }

  private readonly onDogearFold = (e: Event): void => {
    const detail = (e as CustomEvent<{ page?: number }>).detail;
    const page = typeof detail?.page === "number" ? detail.page : 0;
    // Semantic fold state is creative-wide (dataset.folded on every
    // corner — the click toggle reads it), but the VISUAL is one fold at
    // a time, on the top sheet of the pile: rendering the whole pile
    // folded nests the punched corners into a see-through cascade. The
    // reader is the same stacked-sheet model on every device now, so
    // currentPage is always the in-view page.
    const foldIdx = this.currentPage;
    for (const de of this._dogEars) {
      de.wrap.dataset.folded = "1";
      if (de.pageIndex === foldIdx) de.applyFolded();
      else de.applyUnfolded();
    }
    this.setAttribute("data-pinned-page", String(page));
    // Toggle the collapsed view's static dog-ear corner so folding in
    // the expanded reader is visible behind the overlay (and remains
    // visible after the user closes back to collapsed).
    this.shadowRoot?.querySelector<HTMLElement>(".banner")?.classList.add("folded");
  };

  private readonly onDogearUnfold = (): void => {
    for (const de of this._dogEars) {
      de.applyUnfolded();
      de.wrap.dataset.folded = "0";
    }
    this.removeAttribute("data-pinned-page");
    this.shadowRoot?.querySelector<HTMLElement>(".banner")?.classList.remove("folded");
  };

  /**
   * Warm the browser cache for every image used across the expanded
   * pages, so clicking to expand shows all pages populated instead of
   * images popping in as they download. One-shot: fires the first
   * time the banner is ≥25% visible and then disconnects.
   *
   * Fires via `new Image()` at default priority — small post-
   * compression sizes (~200-400KB typical) make this an acceptable
   * tax on the publisher page. Skipped entirely in edit mode (the
   * designer already shows the canvas eagerly) and in preview-frame
   * mode (the designer's preview modal handles its own load timing).
   */
  private scheduleExpandedPreload(): void {
    if (this._preloadFired || this._preloadObserver) return;
    if (this.editMode) return;
    if (this.getAttribute("preview-frame") === "1") return;
    if (typeof IntersectionObserver !== "function") {
      // Very old browsers — fall back to immediate preload rather than
      // no preload. Publisher-page cost is slightly higher; accepted
      // for a vanishing fraction of traffic.
      this.firePreload();
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        if (entry.intersectionRatio < 0.25) continue;
        this.firePreload();
        observer.disconnect();
        this._preloadObserver = null;
        return;
      }
    }, { threshold: [0.25] });
    observer.observe(this);
    this._preloadObserver = observer;
  }

  private firePreload(): void {
    if (this._preloadFired) return;
    this._preloadFired = true;
    const urls = collectExpandedImageUrls(this.pagesData);
    for (const url of urls) {
      // Assignment to .src triggers the HTTP request. No DOM insert
      // needed — the browser image cache is populated purely via the
      // loader. Rejections and 404s are silently ignored; the real
      // <img> at expand time would surface any issue.
      const img = new Image();
      img.decoding = "async";
      img.src = url;
    }
    this.preloadExpandedFonts();
  }

  /**
   * Register self-hosted web fonts for the expanded view, warmed during
   * the same one-shot preload as images so the exact brand face is ready
   * by the time the reader clicks to expand. Uses the FontFace API:
   * `document.fonts.add()` makes the face available across the Shadow
   * DOM boundary (a <style> @font-face inside the shadow root would NOT
   * apply to text rendered in the document), and `.load()` IS the
   * preload — no separate fetch needed.
   *
   * Fonts are only ever allow-listed Google families self-hosted on our
   * own CDN (see font-catalog.ts); the collapsed view never loads them.
   * The CSS font stack always carries a system fallback
   * (e.g. "Montserrat, sans-serif"), so if the face fails to load the
   * text still renders cleanly — this is pure enhancement.
   */
  private preloadExpandedFonts(): void {
    if (typeof document === "undefined" || !document.fonts || typeof FontFace !== "function") return;
    const faces = resolveExpandedFonts(this.pagesData);
    for (const f of faces) {
      try {
        const weight = String(f.weight);
        // Skip if this family+weight is already registered (another banner
        // on the page, or a prior mount). Keyed on weight too, since we now
        // self-host per-weight faces of the same family.
        let already = false;
        document.fonts.forEach((ff) => { if (ff.family === f.family && ff.weight === weight) already = true; });
        if (already) continue;
        const face = new FontFace(f.family, `url(${f.url}) format("woff2")`, { display: "swap", weight });
        // load() returns a promise; add() on resolve. Failures (404 for a
        // family not yet provisioned, network error) are swallowed — the
        // system fallback in the CSS stack covers it.
        void face.load().then((loaded) => { document.fonts.add(loaded); }).catch(() => {});
      } catch {
        // FontFace constructor can throw on a malformed family — ignore.
      }
    }
  }

  /** Fire the impression beacon (1×1 tracking pixel) when the banner
    * first becomes ≥50% visible — IAB MRC-style viewability gate, so
    * we don't bill the advertiser for a banner the reader never saw.
    * One-shot per mount; the `_impressionFired` flag prevents re-fire
    * on scroll-out/scroll-in, attribute thrash, or any later render.
    *
    * Skipped in edit/preview modes (the designer doesn't bill) and
    * when no `imp-url` was provided (defensive — bootstrap always
    * sets one for served winners).
    */
  private scheduleImpressionFire(): void {
    if (this._impressionFired || this._impressionObserver) return;
    if (this.editMode) return;
    if (this.getAttribute("preview-frame") === "1") return;
    const impUrl = this.getAttribute("imp-url");
    if (!impUrl) return;
    if (typeof IntersectionObserver !== "function") {
      // Old browsers — fall through to immediate fire. Same trade-off
      // as preload: small accuracy loss for a tiny share of traffic.
      this.fireImpression(impUrl);
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        if (entry.intersectionRatio < 0.5) continue;
        this.fireImpression(impUrl);
        observer.disconnect();
        this._impressionObserver = null;
        return;
      }
    }, { threshold: [0.5] });
    observer.observe(this);
    this._impressionObserver = observer;
  }

  private fireImpression(impUrl: string): void {
    if (this._impressionFired) return;
    this._impressionFired = true;
    // 1×1 image beacon — survives adblockers better than fetch and
    // doesn't compete with banner asset loads at default priority.
    const pixel = new Image();
    pixel.src = impUrl;
    // Tie the teaser to the viewability moment: the corner-lift hint
    // plays exactly when the impression counts (and the reader is
    // looking), not on mount where it'd fire off-screen.
    this.playTeaserPeel();
  }

  /** One-shot teaser peel on the collapsed banner: lift the dog-ear
    * corner a shade, then settle it back. A hint that the ad folds, not
    * a fold — it sets no fold state, emits no `dogear-fold`, and never
    * pins. Skipped when the corner is disabled (`canFold` false), when
    * the serve is already pin-folded, in the designer CANVAS (edit
    * mode), and under `prefers-reduced-motion`. Plays once per trigger
    * (`_teaserPlayed` guard). In delivery it fires on the viewability
    * impression; in framed previews (designer modal + publisher
    * approval) it's replayed on each close→collapse so the in-frame
    * 300x250 ad demonstrates the fold exactly like the delivered ad. */
  private playTeaserPeel(): void {
    if (this._teaserPlayed) return;
    if (!this.canFold || this.editMode) return;
    if (this.pinnedPageIdx >= 0) return; // already dog-eared (honored pin)
    if (
      typeof window !== "undefined" &&
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ) {
      return;
    }
    const banner = this.shadowRoot?.querySelector<HTMLElement>(".banner");
    const box = this.shadowRoot?.querySelector<HTMLElement>(".design-box");
    if (!banner || !box) return;
    this._teaserPlayed = true;
    const rtl = resolveReadingRtl(this.configData, this.pagesData);

    // Identical engine to the reader's peelTease (buildDogEar): dogEarPeelFrame
    // per frame drives keptClip on the lifting sheet (the design box) plus
    // cutClip + flapTransform on a paper flap carrying the rotated back.
    // Constants match the reader — peak 0.09, 480ms up / 320ms hold / 400ms
    // down — so the collapsed teaser reads exactly like the page-turn peel.
    const flap = document.createElement("div");
    flap.className = "paper-flap";
    flap.appendChild(buildGrainOverlay());
    banner.appendChild(flap);

    let raf = 0;
    const render = (t: number): void => {
      const r = box.getBoundingClientRect();
      const ff = dogEarPeelFrame(t, r.width || 1, r.height || 1, rtl);
      box.style.clipPath = ff.keptClip ?? "";
      box.style.setProperty("-webkit-clip-path", ff.keptClip ?? "");
      if (ff.cutClip) {
        flap.style.display = "block";
        flap.style.clipPath = ff.cutClip;
        flap.style.transform = ff.flapTransform;
      } else {
        flap.style.display = "none";
      }
    };
    const ease = (u: number): number => (u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2);
    const tween = (from: number, to: number, dur: number, onDone?: () => void): void => {
      const start = performance.now();
      const step = (now: number): void => {
        const u = Math.min(1, (now - start) / dur);
        render(from + (to - from) * ease(u));
        if (u < 1) raf = requestAnimationFrame(step);
        else onDone?.();
      };
      raf = requestAnimationFrame(step);
    };
    const clear = (): void => {
      cancelAnimationFrame(raf);
      box.style.clipPath = "";
      box.style.removeProperty("-webkit-clip-path");
      flap.remove();
    };
    // Peak peel depth. The reader uses 0.09, but that's keyed to the SHORT
    // side, so on a 728x90 leaderboard (90px short side) it's only a ~14px
    // nick. Lift the peak on short/wide slots so the fold reads — targeting a
    // minimum absolute peel leg — capped so it stays a teaser. Near-square /
    // portrait slots (short side >= ~210px) keep ~0.09, matching the reader.
    const r0 = box.getBoundingClientRect();
    const shortSide = Math.min(r0.width, r0.height) || 1;
    const MIN_PEEL_LEG_PX = 32;
    const peak = Math.min(0.3, Math.max(0.09, MIN_PEEL_LEG_PX / (shortSide * DOGEAR_PEEL_TRAVEL)));

    // peel up → hold a beat → lay back down → clean up
    tween(0, peak, 480, () => {
      window.setTimeout(() => tween(peak, 0, 400, clear), 320);
    });
  }

  // ─── Navigation ───

  private onKeyDown(e: KeyboardEvent): void {
    if (!this._expanded) return;
    if (e.key === "Escape") this._collapse();
    // RTL reading: the forward direction is to the LEFT, so the arrows
    // map the opposite way (← advances, → goes back).
    const rtl = resolveReadingRtl(this.configData, this.pagesData);
    if (e.key === "ArrowRight") {
      if (rtl) this.prev();
      else this.next();
    }
    if (e.key === "ArrowLeft") {
      if (rtl) this.next();
      else this.prev();
    }
  }

  private _expand(): void {
    this._expanded = true;
    this.currentPage = 0;
    this._overlayTeasePlayed = false; // fresh open → one tease available
    window.addEventListener("keydown", this.handleKeyDown);
    // Defensive: nuke any lingering overlays from a prior expansion whose
    // close animation didn't finish (animationend can be flaky on iOS
    // Safari when the animation runs off-screen because the user scrolled
    // to the end before auto-collapse). Without this, a new overlay
    // mounts next to a half-dead one and inherits its scrolled-to-bottom
    // visual state — the "reopens above viewport" symptom.
    this.shadowRoot?.querySelectorAll(".overlay").forEach((el) => el.remove());
    this.renderOverlay();
    // Framed preview (designer modal + publisher approval): the collapsed
    // render (`.banner`) sits directly behind the expanded overlay, and
    // its dog-ear folds in sync (`.banner.folded`). On mobile the expanded
    // pages don't fully cover it, so the expanded fold's see-through punch
    // looks straight through to the collapsed sheet's own folded flap —
    // its dimmed cream reads as a warm "divided colour" inside the hole.
    // Hide the collapsed render while expanded so the punch reveals a
    // single clean backdrop. Restored on _collapse. Framed only — delivery
    // expands over the publisher article, not this collapsed slot render.
    //
    // DEFER the hide until the expand animation has played. Hiding it
    // immediately blanks the slot while the overlay is still fading in —
    // invisible in delivery (the collapsed ad sits on the covered publisher
    // page) but very visible in the EMBEDDED preview, where the ad sits in
    // an article: you'd see the 300x250 vanish, THEN the reader appear.
    // Keeping it through the fade gives a clean cross-fade (the collapsed ad
    // dims out as the opaque reader fades in over it); we hide it only once
    // the reader covers it, so the dog-ear punch still reads a single
    // backdrop. Cancelled by _collapse if the reader closes first.
    if (this.getAttribute("preview-frame") === "1") {
      const cfg = this.configData;
      const animMs = typeof cfg.expandDurationMs === "number" && cfg.expandDurationMs > 0
        ? cfg.expandDurationMs
        // stack: last sheet lands at (360 + 2·90)·tempo; +80 buffer.
        : resolveExpandEffect(cfg) === "stack"
          ? Math.round(540 * this.paperFeel().tempo) + 80 : 400;
      if (this._collapseHideTimer) window.clearTimeout(this._collapseHideTimer);
      this._collapseHideTimer = window.setTimeout(() => {
        this._collapseHideTimer = null;
        if (!this._expanded) return; // closed again before the timer fired
        const collapsed = this.shadowRoot?.querySelector<HTMLElement>(".banner");
        if (collapsed) collapsed.style.visibility = "hidden";
      }, animMs + 60);
    }
    // Resolve the CTA URL (the `landing-url` attribute) so bootstrap-side
    // prefetch can warm the exact URL the eventual tap will navigate to.
    // Cache-hit on the prefetched HTML requires byte identity, so this must
    // agree with the in-overlay handler's ctaHref below.
    const ctaUrl = this.resolveCtaUrl();
    if (typeof performance !== "undefined" && performance.mark) {
      performance.mark("pv:overlay-open");
    }
    this.dispatchEvent(new CustomEvent("magazine-expand", {
      bubbles:  true,
      composed: true,
      detail:   {
        ctaUrl,
        pageIndex:  0,
        totalPages: this.pagesData.length,
      },
    }));
    this.dispatchPageChanged();
  }

  /** Returns the URL the CTA will open: the `landing-url` attribute (the
    * campaign's landing page). The bootstrap consumes this via the
    * `magazine-expand` event detail to inject prefetch hints whose href
    * matches the eventual navigation exactly. Returns "" when no
    * landing-url is set (defensive — bootstrap will skip injection).
    */
  private resolveCtaUrl(): string {
    return this.getAttribute("landing-url") ?? "";
  }

  private dispatchPageChanged(): void {
    this.dispatchEvent(new CustomEvent("magazine-page-changed", {
      bubbles:  true,
      composed: true,
      detail:   {
        pageIndex:  this.currentPage,
        totalPages: this.pagesData.length,
      },
    }));
  }

  private _collapse(): void {
    this._expanded = false;
    // Cancel a pending deferred-hide (see _expand) so it can't blank the
    // collapsed ad after keep-frame has just restored it.
    if (this._collapseHideTimer) {
      window.clearTimeout(this._collapseHideTimer);
      this._collapseHideTimer = null;
    }
    window.removeEventListener("keydown", this.handleKeyDown);
    const overlay = this.shadowRoot?.querySelector<HTMLElement>(".overlay");
    const framed = this.getAttribute("preview-frame") === "1";
    // keep-frame: the host wants close to return the creative to its
    // COLLAPSED state inside the frame (publisher approval iPad preview)
    // rather than drop the whole frame. We restore the collapsed render
    // (.banner) that _expand hid, so the close lands on the in-frame ad —
    // clickable to re-expand, exactly like delivery. Default framed
    // previews (the designer modal) still leave it hidden: there the host
    // dismisses the whole preview on magazine-collapse, so showing the
    // slot ad would only flash an "image left behind" before it closes.
    const keepFrame = framed && this.getAttribute("keep-frame") === "1";
    // Reveal the collapsed ad NOW, at the start of the close — before the
    // reader animates out — so as the reader fades it cross-fades back to
    // the in-frame ad instead of fading out over a blank slot and popping
    // the ad back at the end (the mirror of the deferred-hide on expand).
    if (keepFrame) {
      const collapsed = this.shadowRoot?.querySelector<HTMLElement>(".banner");
      if (collapsed) collapsed.style.visibility = "";
    }
    const restoreCollapsed = (): void => {
      if (!keepFrame) return;
      // Visibility is already restored above; this runs once the reader is
      // gone. Demonstrate the dog-ear on the restored 300x250 ad, like
      // delivery: replay the on-view corner-fold teaser (purely visual — no
      // pin, no event; skipped when already dog-eared, see playTeaserPeel).
      // Reset the one-shot guard so it plays again on each close→collapse,
      // and defer a frame so the un-hidden render has layout to measure.
      this._teaserPlayed = false;
      requestAnimationFrame(() => this.playTeaserPeel());
    };
    // Hide the mobile close pill immediately. It lives on the overlay
    // (not inside the wrapper) so the wrapper's close animation doesn't
    // include it — without this it would float alone over the shrinking
    // wrapper for the full animation duration.
    const closeX = overlay?.querySelector<HTMLElement>(".mobile-close-x");
    if (closeX) closeX.style.display = "none";
    if (overlay) {
      // Run the close effect (reverse of the open animation) before the
      // overlay is removed — in framed previews too, so the preview plays
      // the same open/close effect delivery does, then drops the frame.
      // fade reverses its open keyframe; CRT powers off.
      {
        const wrapper = overlay.querySelector<HTMLElement>(".expand-wrapper");
        const cfg = this.configData;
        const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        const effectName = reducedMotion ? "fade" : resolveExpandEffect(cfg);
        if (wrapper) {
          // The close animation runs on the wrapper — pages are stacked
          // absolutely inside it on every device. Delivery pins it to
          // the viewport; a framed preview must NOT (position:fixed
          // would break out of the phone/card frame to the real
          // viewport; there the wrapper is already in place).
          const animTarget = wrapper;
          if (!framed) {
            wrapper.style.position = "fixed";
            wrapper.style.inset = "0";
          }
          wrapper.classList.remove(`expand-effect-${effectName}`);
          animTarget.classList.add(`expand-effect-${effectName}-closing`);
          let done = false;
          const finish = (): void => {
            if (done) return;
            done = true;
            animTarget.removeEventListener("animationend", onEnd);
            overlay.remove();
            // keep-frame hosts: reveal the collapsed render now that the
            // close effect has played, so the frame shows the in-frame ad.
            restoreCollapsed();
            // Framed: tell the host to drop the frame only AFTER the
            // close effect has finished (delivery fires it immediately
            // below, for tracking).
            if (framed) this.dispatchEvent(new CustomEvent("magazine-collapse", { bubbles: true, composed: true }));
          };
          const onEnd = (e: AnimationEvent): void => {
            // Same prefix-filter as the open path — pseudoelement
            // animations also fire here and we only want the
            // wrapper-level keyframes to trigger removal.
            if (!e.animationName.startsWith("expand-effect-")) return;
            finish();
          };
          animTarget.addEventListener("animationend", onEnd);
          // Safety net: if animationend doesn't fire (tab hidden,
          // the page navigated, etc.) remove the overlay anyway so
          // it doesn't ghost on top of the page forever. Match the
          // CSS default for the effect plus a small buffer.
          const cssDefaultMs = effectName === "stack" ? 300 : 400;
          const safetyMs = (cfg.expandDurationMs ?? cssDefaultMs) + 100;
          setTimeout(finish, safetyMs);
        } else {
          // No wrapper — pre-effect-registry layout, fall back to
          // the original 400ms opacity fade.
          overlay.style.opacity = "0";
          setTimeout(() => {
            overlay.remove();
            restoreCollapsed();
            if (framed) this.dispatchEvent(new CustomEvent("magazine-collapse", { bubbles: true, composed: true }));
          }, 400);
        }
      }
    }
    // Let external hosts (designer preview modal, custom wrappers,
    // bootstrap) react to collapse. Delivery fires immediately (tracking);
    // framed previews fire only after the close effect finishes (above),
    // so the host drops the frame once the animation has played.
    if (!framed) {
      this.dispatchEvent(new CustomEvent("magazine-collapse", { bubbles: true, composed: true }));
    }
  }

  /** Replay the open effect (the deal-in) on the
   *  ALREADY-expanded overlay. Used by the designer preview: the magazine
   *  is pre-expanded behind opacity:0 (no two-stage flash), so opening it
   *  re-runs the effect's keyframes on the rendered content for a real
   *  entrance animation. No-op if not currently expanded. Underscore-named
   *  like _expand/_collapse so it survives minification for host calls. */
  _replayExpandEffect(): void {
    const overlay = this.shadowRoot?.querySelector<HTMLElement>(".overlay");
    if (!overlay) return;
    const wrapper = overlay.querySelector<HTMLElement>(".expand-wrapper");
    if (!wrapper) return;
    const reducedMotion =
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    const effectName = reducedMotion ? "fade" : resolveExpandEffect(this.configData);
    const cls = `expand-effect-${effectName}`;
    // Drop any close-state class + the open class, force a reflow, then
    // re-add — that restarts the CSS animation from its first keyframe.
    wrapper.classList.remove(`${cls}-closing`, cls);
    void wrapper.offsetWidth;
    wrapper.classList.add(cls);
    overlay.scrollTop = 0;
  }

  private next(): void {
    if (this.currentPage < this.pagesData.length - 1) {
      this.currentPage++;
      this.updatePages();
      this.dispatchPageChanged();
    } else {
      // Already on the last page — a "next" gesture (arrow/swipe/key)
      // means the user has read through. The last sheet flies off the
      // pile like every other one, then the reader closes.
      this.flyOffLastAndCollapse();
    }
  }

  /** keep-frame hosts hide the collapsed 300x250 once the reader covers
    * it (_expand's deferred hide). _collapse un-hides it — but the fly-
    * away flights run BEFORE _collapse, so the sheets would peel back to
    * an EMPTY slot and the ad would pop in at the end (the blink). Call
    * this the moment a closing flight starts: the ad is already resting
    * in the slot as the sheets leave, and the close reads as sheets
    * flying off a page that was there all along. */
  private revealCollapsedForClose(): void {
    if (this.getAttribute("preview-frame") !== "1") return;
    if (this.getAttribute("keep-frame") !== "1") return;
    if (this._collapseHideTimer) {
      window.clearTimeout(this._collapseHideTimer);
      this._collapseHideTimer = null;
    }
    const collapsed = this.shadowRoot?.querySelector<HTMLElement>(".banner");
    if (collapsed) collapsed.style.visibility = "";
  }

  /** Close request from the CLOSE pill: the WHOLE remaining pile flies
    * away from wherever the reader is — top sheet first, the rest
    * following in a quick stagger — and then the stand closes. The
    * same lift-and-slide direction every sheet uses; closing is just
    * all of them leaving at once. */
  private closeViaFlight(): void {
    if (this._reducedMotion || this._peel) { this._collapse(); return; }
    // Fade entrance: the CLOSE button leaves the way we came in — a
    // plain fade, no scatter. (Page-turn flights, including the last
    // sheet flying off on next/drag, stay in both modes: moving a card
    // out of view is the paper experience, not the entrance theatre.)
    if (resolveExpandEffect(this.configData) === "fade") { this._collapse(); return; }
    const overlay = this.shadowRoot?.querySelector<HTMLElement>(".overlay");
    if (!overlay) { this._collapse(); return; }
    // Settle any in-flight turn so its frame loop can't fight the exits.
    this._finishTurn?.();
    this._finishTurn = null;
    // Chrome out instantly — the clicked pill must not linger.
    overlay.classList.add("last-flight");
    this.revealCollapsedForClose();
    const pages = this.pagesData;
    const rtl = resolveReadingRtl(this.configData, pages);
    const flyers: HTMLElement[] = [];
    for (let i = this.currentPage; i < pages.length; i++) {
      const el = overlay.querySelector<HTMLElement>(`.page-${i}`);
      if (el) flyers.push(el);
    }
    if (flyers.length === 0) { this._collapse(); return; }
    const tempo = this.paperFeel().tempo;
    const flyMs = Math.round(360 * tempo);
    const flyStagger = Math.round(90 * tempo);
    flyers.forEach((el, k) => {
      el.animate(
        [
          { transform: "none", opacity: 1 },
          {
            transform: `translate(${rtl ? "85%" : "-85%"}, -30%) rotate(${rtl ? 6 : -6}deg)`,
            opacity: 0,
          },
        ],
        // Same tempo as the deal-in, scaled by paper mass — the pile
        // leaves at the speed it arrived, and heavy stock moves slower.
        { duration: flyMs, delay: k * flyStagger, easing: "cubic-bezier(0.5, 0, 0.8, 0.4)", fill: "forwards" },
      );
    });
    setTimeout(() => this._collapse(), flyMs + (flyers.length - 1) * flyStagger + 40);
  }

  /** Send the last sheet away with the same lift-and-slide the drag
    * uses (a committed release from rest), then collapse. Falls back to
    * an immediate collapse when the flight can't run. */
  private flyOffLastAndCollapse(): void {
    if (this._reducedMotion) { this._collapse(); return; }
    if (this._peel) return; // a drag is already in charge
    // Settle any still-running page turn NOW (spam-clicking next lands
    // here inside the previous turn's 850ms window; its frame loop and
    // settle would fight the flight's transforms — the ghost sheet).
    this._finishTurn?.();
    this._finishTurn = null;
    const overlay = this.shadowRoot?.querySelector<HTMLElement>(".overlay");
    const turning = overlay?.querySelector<HTMLElement>(`.page-${this.currentPage}`);
    if (!overlay || !turning) { this._collapse(); return; }
    const feel = this.paperFeel();
    const peel = createInteractivePeel({
      turning,
      under: null,
      rtl: resolveReadingRtl(this.configData, this.pagesData),
      springK: feel.springK,
      springC: feel.springC,
      onCommit: () => {
        this._peel = null;
        // Teardown restored the sheet — re-hide it or it ghosts back
        // at center for the collapse fade.
        turning.style.opacity = "0";
        this._collapse();
      },
      onCancel: () => { this._peel = null; },
    });
    if (!peel) { this._collapse(); return; }
    // Chrome out the instant the flight is committed (a clicked CLOSE
    // pill must vanish immediately, not linger over the flying sheet).
    overlay.classList.add("last-flight");
    this.revealCollapsedForClose();
    this._peel = peel;
    peel.scrubTo(0.05);
    peel.release(true, 2.2); // thrown with momentum, like a flick
  }

  private prev(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.updatePages();
      this.dispatchPageChanged();
    }
  }

  // Shared by the touch swipe and the mouse drag. `swipedLeft` is the
  // physical gesture; which page that reaches depends on reading
  // direction — an RTL magazine's NEXT page is on the LEFT, so you
  // drag it rightward (mirrors the arrows/keyboard mapping).
  private swipeNavigate(swipedLeft: boolean): void {
    const rtl = resolveReadingRtl(this.configData, this.pagesData);
    if (swipedLeft !== rtl) this.next();
    else this.prev();
  }

  // ── Interactive corner-peel (thumb-driven forward turn) ──────────────
  // A peel only starts from the BOTTOM HALF of the sheet — the natural
  // "grab a lower corner" affordance — but the fold itself always peels
  // from the bottom peel-side corner regardless of where the thumb is.
  // Structural (not a feel knob), so it stays a constant.
  private static readonly PEEL_GRAB_ZONE = 0.5;

  /** The resolved paper-weight feel preset for this creative — the source
    * of every peel tuning value (pull gain, commit line, flick, spring). */
  private paperFeel(): PaperFeel {
    return PAPER_FEEL[this.configData.paperWeight ?? "medium"];
  }

  /** Fold progress for the current thumb position (0 at grab, 1 fully
    * peeled). Forward peel: LTR pulls the corner LEFT, RTL pulls RIGHT.
    * The gain (thumb-distance per fold) comes from the paper weight. */
  private peelProgress(clientX: number): number {
    const rtl = resolveReadingRtl(this.configData, this.pagesData);
    const dx = rtl ? clientX - this._peelStartX : this._peelStartX - clientX;
    return dx / (this._peelWidth * this.paperFeel().travel);
  }

  /** Try to start a thumb-driven peel of the current page. Returns true
    * if a peel engaged (pointerdown landed in the bottom-half grab zone
    * of the top sheet, a next page exists, and no turn is in flight). */
  private beginPeel(clientX: number, clientY: number): boolean {
    if (this._peel || this._finishTurn || this._reducedMotion) return false;
    const pages = this.pagesData;
    const overlay = this.shadowRoot?.querySelector<HTMLElement>(".overlay");
    if (!overlay) return false;
    const turning = overlay.querySelector<HTMLElement>(`.page-${this.currentPage}`);
    // The LAST sheet has nothing beneath — it flies off over the
    // backdrop and the reader closes. Same lift-and-slide as every
    // other sheet: the pile empties, the kawaraban is read. This stays
    // even with the "fade" entrance: page turns are the paper
    // experience, not the entrance theatre.
    const isLast = this.currentPage >= pages.length - 1;
    const under = isLast
      ? null
      : overlay.querySelector<HTMLElement>(`.page-${this.currentPage + 1}`);
    const sheet = turning?.querySelector<HTMLElement>(".paper-sheet");
    if (!turning || (!isLast && !under) || !sheet) return false;

    const rect = sheet.getBoundingClientRect();
    // Grab zone: bottom half of the sheet only.
    if (clientY < rect.top + rect.height * (1 - ExpandableMagazineBanner.PEEL_GRAB_ZONE)) return false;

    const rtl = resolveReadingRtl(this.configData, pages);
    const feel = this.paperFeel();
    const peel = createInteractivePeel({
      turning,
      under,
      rtl,
      springK: feel.springK,
      springC: feel.springC,
      onCommit: () => {
        this._peel = null;
        if (isLast) {
          // The pile is empty — the last sheet sailed away; close.
          // (Teardown just restored the sheet's transform: re-hide it
          // or it ghosts back at center for the collapse fade.)
          turning.style.opacity = "0";
          this._collapse();
          return;
        }
        // The peel already played the turn to t=1; advance and re-lay
        // the stack instantly (no second clock turn).
        this.currentPage += 1;
        this.updatePages(false);
        this.dispatchPageChanged();
      },
      onCancel: () => {
        // Nothing turned — restore the resting stack (z/offsets the peel
        // overrode) WITHOUT replaying the current page's item anims.
        this._peel = null;
        overlay.classList.remove("last-flight");
        this.applyStackLayout(overlay, pages.length, this.currentPage, new Set());
      },
    });
    if (!peel) return false;
    // Last sheet in hand: the reader is ending — chrome bows out now,
    // and returns only if the drag cancels.
    if (isLast) {
      overlay.classList.add("last-flight");
      // Grabbing the last sheet may end in a committed flight; put the
      // ad back in the slot now so the flight reveals it, not a hole.
      // (Harmless on cancel: the overlay covers the page anyway, same
      // as delivery where the in-page ad is never hidden.)
      this.revealCollapsedForClose();
    }

    this._peel = peel;
    this._peelStartX = clientX;
    this._peelWidth = rect.width || 1;
    this._peelVel = 0;
    this._peelLastP = 0;
    this._peelLastMs = performance.now();
    peel.scrubTo(0);
    return true;
  }

  /** Feed a live pointer position to the peel in progress, tracking a
    * smoothed progress velocity for the release throw. */
  private movePeel(clientX: number): void {
    if (!this._peel) return;
    const p = this.peelProgress(clientX);
    const now = performance.now();
    const dt = (now - this._peelLastMs) / 1000;
    if (dt > 0) {
      const inst = (p - this._peelLastP) / dt; // progress units / sec
      // EMA — smooth out per-frame jitter while staying responsive to a flick.
      this._peelVel = this._peelVel * 0.6 + inst * 0.4;
    }
    this._peelLastP = p;
    this._peelLastMs = now;
    this._peel.scrubTo(p);
  }

  /** End the peel. Commit if pulled past the distance line OR flicked fast
    * forward; otherwise spring back to flat. Either way the page leaves at
    * the thumb's release speed (momentum). */
  private endPeel(clientX: number): void {
    if (!this._peel) return;
    const feel = this.paperFeel();
    const p = this.peelProgress(clientX);
    const v = this._peelVel;
    const commit = p >= feel.commitAt || v >= feel.flickVel;
    // Clamp the seed so a wild flick can't fling the spring unstable.
    const v0 = Math.max(-12, Math.min(12, v));
    this._peel.release(commit, v0);
  }

  // ═══════════════════════════════════════════════════════════════
  // COLLAPSED STATE
  // ═══════════════════════════════════════════════════════════════

  private render(): void {
    // Chromium resolves container-query units (the layout items' cqmax
    // font sizes) at their VIEWPORT fallback for elements rendered inside
    // a display:none ancestor, and never re-resolves after reveal — a
    // banner built hidden shows design-space glyph sizes forever. If we
    // render while unlaid-out, arm a one-shot re-render for the moment
    // the element first gets real size. (magazine-preview already guards
    // its own expand path the same way.)
    if (!this.offsetParent && this.offsetWidth === 0 && typeof ResizeObserver === "function") {
      if (!this._hiddenRenderRO) {
        this._hiddenRenderRO = new ResizeObserver(() => {
          if (this.offsetWidth > 0) {
            this._hiddenRenderRO?.disconnect();
            this._hiddenRenderRO = null;
            this.render();
          }
        });
        this._hiddenRenderRO.observe(this);
      }
    }
    // Each render rebuilds the page DOM, so previous dog-ear handles
    // point at orphaned elements — start the per-render collection
    // fresh so the host listener doesn't broadcast to detached nodes.
    this._dogEars = [];
    const w = parseInt(this.getAttribute("width") ?? "300", 10);
    const h = parseInt(this.getAttribute("height") ?? "250", 10);
    const cfg = this.configData;
    const pages = this.pagesData;
    const idx = Math.max(0, Math.min(this.collapsedPageIdx, pages.length - 1));
    const page = pages[idx];
    if (!page) return;

    // Nearest-aspect layout selection: pick whichever authored variant
    // (aspect bucket or legacy exact size) is closest in SHAPE to this
    // slot, then render it fluidly into the slot's actual box. Replaces
    // the old exact `"WxH"` match — see pickCollapsedLayout for why.
    // Returns null when the 16:9 master is the nearest shape.
    const picked = pickCollapsedLayout(page.banners, w, h);
    if (picked) {
      const sizedPage: Page = { ...page, layout: picked.layout, designAspect: `${w}/${h}` };
      this.renderFromLayout(sizedPage, cfg);
      this.wireCollapsedClick();
      return;
    }
    if (page.layout && page.layout.length > 0) {
      this.renderFromLayout(page, cfg);
      this.wireCollapsedClick();
      return;
    }

    // No authored layout. In the DESIGNER (mode="edit") render a clean
    // empty page surface — the size-matrix chip carries the "missing"
    // signal there, and a placeholder box on the canvas just gets in
    // the way of starting to author. In DELIVERY keep the visible
    // placeholder so a publisher sees the creative is broken rather
    // than a silent blank.
    if (this.editMode) {
      this.renderFromLayout({ ...page, layout: [] }, cfg);
      return;
    }
    if (this.shadowRoot) {
      this.shadowRoot.innerHTML = `
        <style>:host { display: block; } * { box-sizing: border-box; margin: 0; padding: 0; }</style>
        <div style="width:${w}px;height:${h}px;display:flex;align-items:center;justify-content:center;
                    background:#1a1a1a;color:#9ca3af;font:11px/1.4 system-ui;text-align:center;padding:8px;
                    border:1px solid #333;border-radius:6px;">
          Creative missing layout
        </div>`;
    }
  }

  private wireCollapsedClick(): void {
    if (this.editMode) return;
    const banner = this.shadowRoot?.querySelector<HTMLElement>(".banner");
    banner?.addEventListener("click", () => {
      // Click beacon — banner-expansion event in the three-tier model
      // (impression → click → CTA). 1×1 pixel mirrors how the
      // impression and CTA beacons fire; the bootstrap puts the
      // signed URL on click-url at serve time. Fire once per mount so
      // expand → close → expand doesn't re-bill (same signed `rid`).
      if (!this._clickFired) {
        const clickUrl = this.getAttribute("click-url");
        if (clickUrl) {
          this._clickFired = true;
          const pixel = new Image();
          pixel.src = clickUrl;
        }
      }
      this._expand();
    });
  }

  // Collapsed renderer — items absolutely positioned in %, sized via cq units.
  private renderFromLayout(page: Page, cfg: BannerConfig): void {
    const defaultFont = fontMain(cfg);
    const items: LayoutItem[] = page.layout ?? [];
    const editMode = this.editMode;

    const html = items
      .map((item, i) => renderCollapsedItemHtml(item, i, page, defaultFont))
      .join("\n");

    const aspectCss = page.designAspect ?? cfg.designAspect ?? "16/9";

    // The slot owns dimensions in production: the publisher's
    // `.ad-slot` CSS sets max-width and aspect-ratio per `data-w`/
    // `data-h`, so the banner just fills 100%. `width`/`height` attrs
    // are still read for the per-size `page.banners[sizeKey]` lookup,
    // not for styling the host.
    this.style.display = "block";
    this.style.width = "100%";
    this.style.height = "100%";
    this.style.maxWidth = "";
    this.style.aspectRatio = "";

    // Dog-ear visual on the collapsed banner — when this serve is honoring
    // a pin (pinnedPageIdx >= 0), the corner of the design-box is clipped
    // off and a folded triangle is painted in its place. Static visual
    // only: clicking the banner still expands rather than toggling fold.
    // The folded class is toggled live in onDogearFold/onDogearUnfold so
    // folding in the expanded view updates the collapsed view behind it
    // without a full re-render (which would destroy the overlay).
    const startFolded = !editMode && this.pinnedPageIdx >= 0;
    const earSize = "7.5cqmin"; // 1.25× the prior 6cqmin — bigger fold corner on collapsed/IAB sizes
    // RTL reading (Arabic / vertical) mirrors the dog-ear to the top-LEFT.
    const rtl = resolveReadingRtl(cfg, this.pagesData);
    const pageClip = rtl
      ? `polygon(${earSize} 0, 100% 0, 100% 100%, 0 100%, 0 ${earSize})`
      : `polygon(0 0, calc(100% - ${earSize}) 0, 100% ${earSize}, 100% 100%, 0 100%)`;
    const flapSide = rtl ? "left: 0;" : "right: 0;";
    const flapClip = rtl ? "polygon(100% 0, 100% 100%, 0 100%)" : "polygon(0 0, 0 100%, 100% 100%)";
    const flapShadow = rtl ? "drop-shadow(3px 3px 3px rgba(0,0,0,0.2))" : "drop-shadow(-3px 3px 3px rgba(0,0,0,0.2))";

    if (!this.shadowRoot) return;
    this.shadowRoot.innerHTML = `
      <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        .design-box {
          container-type: size;
          aspect-ratio: ${aspectCss};
          width: 100%;
          max-width: 100%;
          max-height: 100%;
          position: relative;
          overflow: hidden;
          background: ${page.bg ?? "linear-gradient(135deg,#1a1a1a,#2d2518)"};
          border-radius: ${editMode ? "0" : "4px"};
        }
        .banner.folded .design-box {
          clip-path: ${pageClip};
          -webkit-clip-path: ${pageClip};
        }
        /* The ad frame border is on .banner (not clipped), so when the
           corner is punched it would trace a contour line across the
           see-through hole. Drop it while folded — the corner reads as a
           clean window to the publisher. border-color keeps the 1px box
           so nothing shifts. */
        .banner.folded {
          border-color: transparent;
        }
        .collapsed-dogear-flap {
          position: absolute;
          top: 0;
          ${flapSide}
          width: ${earSize};
          height: ${earSize};
          clip-path: ${flapClip};
          background: #d9d2bf;
          filter: ${flapShadow};
          pointer-events: none;
          z-index: 2;
          opacity: 0;
          transition: opacity 0.2s ease;
        }
        .banner.folded .collapsed-dogear-flap { opacity: 1; }
        /* Paper-flap + grain styles, shared with the reader, so the on-view
           teaser peel (playTeaserPeel) renders the lifted corner with the
           same paper-toned back, sheen, shadow and grain as the page turn. */
        ${PAPER_CSS}
      </style>
      <div class="banner${startFolded ? " folded" : ""}" style="
        width:100%;height:100%;
        cursor:${editMode ? "default" : "pointer"};
        display:flex;align-items:center;justify-content:center;
        position:relative;overflow:hidden;
        background:transparent;
        border:${editMode ? "none" : "1px solid rgba(196,163,90,0.25)"};
        border-radius:${editMode ? "0" : "6px"};
      ">
        <div class="design-box">${html}</div>
        <div class="collapsed-dogear-flap"></div>
      </div>`;

    // Per-page full-bleed video background. Runs in every mode —
    // collapsed PC, collapsed mobile, and every IAB size — because
    // we inject it into .design-box, which every render path builds.
    const designBox = this.shadowRoot.querySelector<HTMLElement>(".design-box");
    if (designBox) {
      applyTextureBg(designBox, page.textureBg, applyVideoBg(designBox, page.videoBg));
      // Logo is an EXPANDED-view element. In the collapsed render it shows
      // ONLY in the designer (edit mode), so the author can place it on the
      // expanded master — delivery's collapsed served ad carries no logo.
      if (this.editMode) applyLogo(designBox, this.configData.logo);
    }

    // Auto-fit text items marked data-autofit="1": shrink font-size
    // until scrollHeight fits within the item's clientHeight. Runs in
    // rAF so layout has settled. Applies in edit mode too so the
    // authoring preview matches delivery.
    requestAnimationFrame(() => {
      const shadow = this.shadowRoot;
      if (!shadow) return;
      for (const el of shadow.querySelectorAll<HTMLElement>('[data-autofit="1"]')) {
        autoFitText(el);
      }
    });

    // Tween items that declare animationTo from base state → target.
    // Items without animationTo are static. Edit mode skips animation.
    if (editMode) return;
    items.forEach((item, i) => {
      const to = item.animationTo;
      if (!to) return;
      const el = this.shadowRoot?.querySelector<HTMLElement>(`[data-layout-idx="${i}"]`);
      if (!el) return;

      const baseValues = {
        left: item.left ?? 0,
        top: item.top ?? 0,
        rotation: item.rotation ?? 0,
        scale: 1,
        opacity: item.opacity ?? 1,
      };
      const delay = to.delay ?? 0;
      setTimeout(() => {
        el.style.transition = transitionFor({ ...to, delay: 0 }, 0.8);
        // Two rAFs: the first lets the browser paint the baseline with
        // the new transition rule committed; the second changes the
        // target properties so the transition actually fires. A single
        // rAF or a sync reflow isn't enough — the browser batches both
        // style mutations and skips the animation.
        requestAnimationFrame(() => requestAnimationFrame(() => {
          applyTargetState(el, to, {
            left: to.left ?? baseValues.left,
            top: to.top ?? baseValues.top,
            rotation: to.rotation ?? baseValues.rotation,
            scale: to.scale ?? 1,
            opacity: to.opacity ?? 1,
          });
        }));
      }, delay * 1000);
    });
  }


  // ═══════════════════════════════════════════════════════════════
  // EXPANDED STATE — full-screen overlay
  // ═══════════════════════════════════════════════════════════════

  // Phone-class reader — decides the sheet fit, the close control, and
  // scrim-tap dismissal. force-mobile lets previews exercise it from a
  // desktop viewport.
  private isMobileReader(): boolean {
    return (
      this.getAttribute("force-mobile") === "1" ||
      window.matchMedia("(max-width: 768px)").matches
    );
  }

  private renderOverlay(): void {
    if (!this.shadowRoot) return;
    const pages = this.pagesData;
    const cfg = this.configData;
    const font = fontMain(cfg);
    const ui = fontUI();
    // The reader is the same floating sheet everywhere; "mobile" only
    // decides the sheet fit (94% vs 88% — see sheetFitPct), the close
    // control, and the scrim-tap-to-close convention. force-mobile
    // lets the designer preview exercise the mobile behavior from a
    // desktop viewport.
    const isMobile = this.isMobileReader();
    // When framed (desktop preview of the mobile variant), the overlay
    // lives inside a host-sized phone frame rather than the full
    // viewport — absolute positioning relative to the shadow host
    // instead of fixed.
    const framed = this.getAttribute("preview-frame") === "1";
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    this._reducedMotion = reducedMotion;
    // Fresh overlay → nothing displayed yet; the first updatePages
    // must place the cover without animating a turn from a stale index.
    this._displayedPage = null;

    // Derive the overlay background from the cover page's bg so the
    // reader chrome harmonizes with the creative's authored palette,
    // tinted *away* from the page color so the page edge still reads
    // as a distinct boundary. Direction depends on luminance —
    // light pages get a darker margin, dark pages get a lighter
    // margin, so contrast is preserved at both ends of the spectrum.
    // Hex parsing only; gradients / named colors fall back to the
    // hardcoded dark default inside buildOverlay.
    // Dim scrim over the publisher's page — the floating magazine
    // sheet reads as a modal: the article stays visible (and
    // recognizable) around the sheet, just pushed back. The pages
    // themselves are opaque (own bg), so only the margin shows the
    // scrim. On mobile the full-bleed pages cover the viewport and the
    // scrim is never seen. (History: started as a cover-derived grey
    // color-mix, then fully transparent per the floating-page
    // direction; the scrim is the middle ground — floating sheet WITH
    // separation from the article so the takeover isn't total.)
    //
    // Scrim everywhere — the reader floats as a modal with the page behind
    // it dimmed but recognizable. The framed preview sits over the faux
    // newspaper article, so a LIGHTER scrim keeps that article readable
    // (dimmed, pushed back) instead of crushing it to flat grey; delivery
    // dims the real publisher page a bit harder. The pages are opaque (own
    // bg) so only the margin/punch shows the scrim; the close/nav glass
    // frosts the dimmed article through it.
    const overlayBg = framed ? "rgba(0,0,0,0.4)" : "rgba(0,0,0,0.55)";
    // The page counter is drawn INSIDE the sheet (top-left, over the
    // opaque page), so it follows the cover's luminance — dark ink on a
    // light creative, light ink on a dark one.
    const readerOnLight = isLightSurface(pages[0]?.bg);
    // The close button + nav arrows hang into the MARGIN (straddling the sheet
    // edge), which sits on a DARK surface in EVERY context — the scrim
    // (rgba(0,0,0,0.55)) in delivery, and the dim modal / dark #44464c screen
    // behind the framed preview. So keep them light-on-dark (and the close
    // pill's frosted glass) everywhere, regardless of cover luminance: that's
    // the "close button I can't see on dark" fix, and it makes the PREVIEW
    // match the delivered ad. (The page counter sits INSIDE the sheet, so it
    // still follows the cover via readerOnLight above.)
    const marginOnLight = false;
    // Close-button label follows the creative's content language
    // (Japanese content → 「閉じる」, otherwise "Close"). JA/EN for now.
    const closeLabel = pickCloseLabel(pages);
    const overlay = buildOverlay({ font, framed, background: overlayBg });
    // Paper-back stock colour: one CSS var recolours every peeled surface
    // (flap, dog-ear tease, folded corner) while their fiber/mottle/sheen
    // texture layers ride on top unchanged. Unset → the default warm tone.
    if (cfg.paperBackColor) overlay.style.setProperty("--paper-back-color", cfg.paperBackColor);
    // Navigation gestures, unified across touch AND mouse via pointer
    // events (touch fires pointer* with pointerType "touch"; desktop and
    // the framed mobile-preview on a PC use mouse). Two gestures share
    // one pointerdown→up path:
    //
    //   • Interactive peel — a drag starting in the BOTTOM HALF of the
    //     sheet grabs the page; the fold sticks to the thumb (movePeel)
    //     and release commits past the threshold or springs back.
    //   • Threshold swipe — any other horizontal drag drives next/prev
    //     on release (50px). next() past the last page auto-collapses.
    //
    // Peel engagement is DEFERRED until the pointer actually moves past a
    // small slop: a tap (no movement) falls straight through to whatever
    // control is under it — the close pill straddles the sheet's bottom
    // edge (in the grab zone), and a tap on the page navigates to the LP.
    // Capturing the pointer on pointerdown would swallow both. We also
    // never arm on a press that lands directly on a control.
    const PEEL_SLOP = 8;
    let dragStartX: number | null = null;
    let armX = 0, armY = 0;
    let armed = false; // pointerdown was a peel candidate, awaiting slop
    overlay.addEventListener("pointerdown", (e) => {
      dragStartX = e.clientX;
      armX = e.clientX;
      armY = e.clientY;
      const tgt = e.target as HTMLElement | null;
      // A press ON a control (close pill, nav arrows, CTA link) is that
      // control's — never a peel.
      armed = !tgt?.closest("a, button, input, select, textarea");
    });
    overlay.addEventListener("pointermove", (e) => {
      if (this._peel) {
        this.movePeel(e.clientX);
        e.preventDefault();
        return;
      }
      if (!armed) return;
      const rtl = resolveReadingRtl(this.configData, this.pagesData);
      const dx = rtl ? e.clientX - armX : armX - e.clientX;
      if (dx <= PEEL_SLOP) return;
      // Slop crossed in the forward direction — try to grab the page from
      // where the press began. One-shot: disarm regardless of outcome.
      armed = false;
      if (this.beginPeel(armX, armY)) {
        try { overlay.setPointerCapture(e.pointerId); } catch { /* older engines */ }
        e.preventDefault();
        this.movePeel(e.clientX);
      }
    });
    const endPointer = (e: PointerEvent): void => {
      armed = false;
      if (this._peel) {
        // The peel owned this gesture — commit-or-spring, no swipe.
        this.endPeel(e.clientX);
        try { overlay.releasePointerCapture(e.pointerId); } catch { /* ignore */ }
        dragStartX = null;
        return;
      }
      if (dragStartX === null) return;
      const diff = dragStartX - e.clientX;
      dragStartX = null;
      if (Math.abs(diff) > 50) this.swipeNavigate(diff > 0);
    };
    overlay.addEventListener("pointerup", endPointer);
    overlay.addEventListener("pointercancel", endPointer);
    // Scroll lock: the overlay owns every touch gesture while the reader
    // is open. touch-action:none (buildOverlay) handles engines with
    // pointer-event gestures; this non-passive preventDefault covers the
    // rest — without it, dragging the reader scrolls the publisher page
    // BEHIND the modal.
    overlay.addEventListener("touchmove", (e) => e.preventDefault(), { passive: false });
    // A drag shouldn't smear a text selection across the creative.
    overlay.style.userSelect = "none";
    (overlay.style as CSSStyleDeclaration & { webkitUserSelect?: string }).webkitUserSelect = "none";
    // Reading direction drives the deal-in mirror, the dog-ear/peel/nav
    // side, and the counter's corner. Computed once for everything here.
    const readingRtl = resolveReadingRtl(cfg, pages);
    const wrapper = buildExpandWrapper({ cfg, reducedMotion, rtl: readingRtl });
    overlay.appendChild(wrapper);

    // Chrome box: overlays the floating SHEET (same centering + aspect
    // fit), so the nav arrows, close, and page counter sit at the
    // CREATIVE's edges, not the viewport's. The sheet aspect-fits at
    // 88% of the reader, so viewport-anchored chrome would drift far
    // out into the margins; anchoring to this box keeps it on the
    // creative. One reader model on every device now.
    // Mirror createPage's portrait-master selection so the chrome
    // hugs the sheet that actually renders.
    const portrait = pages[0] ? pickExpandedLayout(pages[0]) !== undefined : false;
    const chromeAspect = portrait
      ? "9/16"
      : (pages[0]?.designAspect ?? cfg.designAspect ?? "16/9");
    const chromeBox = document.createElement("div");
    Object.assign(chromeBox.style, {
      position: "absolute",
      inset: "0",
      margin: "auto",
      aspectRatio: chromeAspect,
      // ONE driven dimension; aspect-ratio derives the other — see
      // sheetSizeFor for why setting both squashes the box.
      ...sheetSizeFor(chromeAspect, sheetFitPct(isMobile)),
      pointerEvents: "none",
      zIndex: "100",
    } as Partial<CSSStyleDeclaration>);
    wrapper.appendChild(chromeBox);
    const chromeParent = chromeBox;

    // Close control. Desktop: the labeled pill straddling the sheet's
    // bottom edge. Mobile keeps the ORIGINAL reader's close — a
    // self-contained pill pinned at the viewport's top-left on the
    // scrim (opposite the dog-ear corner; RTL mirrors it), always
    // visible regardless of which page is showing. Both appear in
    // framed previews too, so previews carry the same close control
    // delivery does.
    if (isMobile) {
      const closeX = document.createElement("button");
      closeX.className = "mobile-close-x";
      closeX.setAttribute("aria-label", closeLabel);
      Object.assign(closeX.style, {
        position: "absolute",
        top: framed ? "12px" : "max(12px, env(safe-area-inset-top, 0px))",
        [readingRtl ? "right" : "left"]: "12px",
        zIndex: "100",
        height: "40px",
        padding: "0 20px",
        borderRadius: "20px",
        border: "1px solid rgba(255,255,255,0.3)",
        background: "rgba(0,0,0,0.7)",
        color: "rgba(255,255,255,0.95)",
        fontSize: "13px",
        letterSpacing: "2px",
        lineHeight: "1",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        cursor: "pointer",
        fontFamily: ui,
        boxShadow: "0 4px 14px rgba(0,0,0,0.4)",
        backdropFilter: "blur(6px)",
        WebkitBackdropFilter: "blur(6px)",
      } as Partial<CSSStyleDeclaration>);
      closeX.textContent = closeLabel;
      closeX.addEventListener("click", () => this.closeViaFlight());
      overlay.appendChild(closeX);
      // The original reader's rhythm: hidden while the user interacts,
      // fading in when they pause — a clean beat on expand, then the
      // pill returns after each gesture. (It was scroll-driven in the
      // scroll-stack; touch gestures are the interaction now.)
      closeX.style.transition = "opacity 0.3s ease";
      let pillTimer: number | null = null;
      const setPill = (visible: boolean): void => {
        closeX.style.opacity = visible ? "1" : "0";
        closeX.style.pointerEvents = visible ? "" : "none";
      };
      const schedulePill = (ms: number): void => {
        if (pillTimer !== null) window.clearTimeout(pillTimer);
        pillTimer = window.setTimeout(() => setPill(true), ms);
      };
      overlay.addEventListener("touchstart", (e) => {
        // A tap on the pill itself must not hide it out from under its
        // own click.
        if (e.composedPath().includes(closeX)) return;
        if (pillTimer !== null) window.clearTimeout(pillTimer);
        setPill(false);
      });
      overlay.addEventListener("touchend", (e) => {
        if (e.composedPath().includes(closeX)) return;
        schedulePill(900);
      });
      setPill(false);
      schedulePill(1200);

      // Gesture hint — a small callout at the bottom, alternating
      // "Swipe to see next page" ⇄ "Tap to see details" (content
      // language, like the close label) on an interval. It keeps
      // coming back until the user's FIRST page turn — on mobile the
      // only navigations are swipe and the corner peel, so a
      // magazine-page-changed event means the gesture has been
      // learned and the hint retires for this reader session.
      // Single-page creatives only get the tap message.
      const ja = closeLabel === "閉じる";
      const hintMsgs =
        pages.length > 1
          ? ja
            ? ["スワイプで次のページへ", "タップして詳細を見る"]
            : ["Swipe to see next page", "Tap to see details"]
          : ja
            ? ["タップして詳細を見る"]
            : ["Tap to see details"];
      const hint = document.createElement("div");
      hint.className = "swipe-hint";
      Object.assign(hint.style, {
        position: "absolute",
        bottom: framed ? "14px" : "max(14px, env(safe-area-inset-bottom, 0px))",
        left: "50%",
        transform: "translateX(-50%)",
        zIndex: "100",
        height: "32px",
        padding: "0 16px",
        borderRadius: "16px",
        border: "1px solid rgba(255,255,255,0.25)",
        background: "rgba(0,0,0,0.65)",
        color: "rgba(255,255,255,0.92)",
        fontSize: "12px",
        letterSpacing: "1px",
        lineHeight: "1",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        whiteSpace: "nowrap",
        // Informational only — taps pass through to the sheet (CTA).
        pointerEvents: "none",
        fontFamily: ui,
        boxShadow: "0 4px 14px rgba(0,0,0,0.35)",
        backdropFilter: "blur(6px)",
        WebkitBackdropFilter: "blur(6px)",
        opacity: "0",
        transition: "opacity 0.4s ease",
      } as Partial<CSSStyleDeclaration>);
      let hintIdx = 0;
      hint.textContent = hintMsgs[0]!;
      overlay.appendChild(hint);
      let hintDone = false;
      let hintTimer: number | null = null;
      const HINT_SHOW_MS = 2600; // message on screen per beat
      const HINT_SWAP_MS = 450;  // fade gap while the text swaps
      const hintCycle = (): void => {
        if (hintDone || !overlay.isConnected) return;
        hint.style.opacity = "1";
        hintTimer = window.setTimeout(() => {
          hint.style.opacity = "0";
          hintTimer = window.setTimeout(() => {
            hintIdx = (hintIdx + 1) % hintMsgs.length;
            hint.textContent = hintMsgs[hintIdx]!;
            hintCycle();
          }, HINT_SWAP_MS);
        }, HINT_SHOW_MS);
      };
      hintTimer = window.setTimeout(hintCycle, 1400); // beat after expand
      // Mid-gesture the hint is clutter — hide it; the cycle re-shows
      // on its next beat if the gesture wasn't a page turn.
      overlay.addEventListener("touchstart", () => {
        if (!hintDone) hint.style.opacity = "0";
      });
      // _expand dispatches an initial page-changed for the cover
      // (pageIndex 0) right after renderOverlay — only a change to a
      // NON-zero page is a real turn, i.e. the gesture was learned.
      const onTurn = (e: Event): void => {
        const d = (e as CustomEvent<{ pageIndex?: number }>).detail;
        if (!d || d.pageIndex === 0) return;
        hintDone = true;
        if (hintTimer !== null) window.clearTimeout(hintTimer);
        hint.style.opacity = "0";
        this.removeEventListener("magazine-page-changed", onTurn);
      };
      this.addEventListener("magazine-page-changed", onTurn);
    } else {
      chromeParent.appendChild(buildCloseButton({ ui, onClick: () => this.closeViaFlight(), label: closeLabel, onLight: marginOnLight }));
    }
    chromeParent.appendChild(buildPageCounter({ ui, onLight: readerOnLight, rtl: readingRtl }));

    pages.forEach((page, i) => {
      wrapper.appendChild(this.createPage(page, i, cfg));
    });

    // Mobile delivery: tapping the scrim (outside the sheet) dismisses
    // — the universal modal convention, kept from the old mobile
    // scroll-stack reader. A tap that lands on the overlay itself or a
    // page's transparent centering frame is outside the sheet; taps on
    // the sheet (creative, dog-ear, CTA) never bubble with those
    // targets. Delivery only — in a framed preview the surrounding
    // modal/panel owns dismissal. Desktop keeps click-outside inert
    // (deliberate: the close pill + Escape are the exits there).
    if (isMobile && !framed) {
      overlay.addEventListener("click", (e) => {
        const t = e.target as HTMLElement;
        // Outside the sheet, the hit target is the expand-wrapper
        // (page containers are pointer-events:none); the overlay edge
        // and page frames are kept for safety.
        if (
          t === overlay ||
          t.classList.contains("expand-wrapper") ||
          t.classList.contains("magazine-page")
        ) {
          this._collapse();
        }
      });
    }

    // Nav arrows — desktop only, straddling the sheet edges. Mobile
    // keeps the original reader's chrome: swipe + the corner peel are
    // the gestures, and at the 97% mobile fit there's no margin for a
    // straddling arrow anyway. No bottom dots/swipe-hint — the
    // top-left counter carries page position. RTL reading flips each
    // arrow's side + glyph so "next" sits on the left.
    if (!isMobile) {
      chromeParent.appendChild(buildNavButton({ direction: "prev", onClick: () => this.prev(), onLight: marginOnLight, rtl: readingRtl }));
      chromeParent.appendChild(buildNavButton({ direction: "next", onClick: () => this.next(), onLight: marginOnLight, rtl: readingRtl }));
    }

    this.shadowRoot.appendChild(overlay);
    requestAnimationFrame(() => {
      overlay.style.opacity = "1";
      this.updatePages();
      // Auto-fit pass — mirrors the mobile branch so desktop preview
      // also shrinks textFit:"shrink" items to fit their boxes.
      overlay.querySelectorAll<HTMLElement>('[data-autofit="1"]').forEach(autoFitText);
      // Cross-page: unify each field's fitted size to the smallest across pages.
      harmonizeAutofit(overlay);
    });
  }

  private createPage(page: Page, index: number, cfg: BannerConfig): HTMLElement {
    // The expanded reader renders the portrait master on EVERY device
    // as a floating 9:16 sheet with peel turns (swipe or arrows to
    // navigate). See pickExpandedLayout for why the 16:9 desktop
    // master was retired from delivery; it remains the fallback for
    // creatives authored before the portrait unification.
    const portraitLayout = pickExpandedLayout(page);
    if (portraitLayout) {
      const portraitPage: Page = { ...page, layout: portraitLayout, designAspect: "9/16" };
      return this.createPageFromLayout(portraitPage, index, cfg);
    }
    // The only supported expanded rendering is the authored layout. If it's
    // missing we surface a placeholder rather than falling back to a template
    // the advertiser didn't design.
    if (page.layout && page.layout.length > 0) {
      return this.createPageFromLayout(page, index, cfg);
    }
    const missing = document.createElement("div");
    missing.className = `magazine-page page-${index}`;
    Object.assign(missing.style, {
      position: "absolute",
      inset: "0",
      // Sits over the overlay's dim scrim like a real page does.
      background: "transparent",
      opacity: "0",
      transition: "opacity 0.5s ease",
      pointerEvents: "none",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      color: "#9ca3af",
      fontSize: "14px",
      fontFamily: "system-ui",
    });
    missing.textContent = "This page has no layout.";
    return missing;
  }

  private createPageFromLayout(page: Page, index: number, cfg: BannerConfig): HTMLElement {
    const defaultFont = fontMain(cfg);
    const aspectCss = page.designAspect ?? cfg.designAspect ?? "16/9";

    const el = document.createElement("div");
    el.className = `magazine-page page-${index}`;
    // Pages stack absolutely; the active sheet shows, upcoming sheets
    // lie beneath at small offsets (applyStackLayout). Transparent:
    // the sheet floats over the overlay's dim scrim with the
    // publisher's article visible around it — the page container is
    // only a centering frame, not a backdrop.
    Object.assign(el.style, {
      position: "absolute",
      inset: "0",
      background: "transparent",
      opacity: "0",
      transition: "opacity 0.5s ease",
      pointerEvents: "none",
      overflow: "hidden",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
    });

    // pageBox holds the stage + fold affordance together so both
    // share the same dimensions and position. Flex-centering in `el`
    // (for mobile letterboxing and desktop framing) drives pageBox
    // as a whole unit; inside it the fold button lines up with the
    // stage's top-right corner instead of floating in `el`'s corner.
    //
    // On desktop the fold cut-out (and the area behind a turning
    // sheet) reveals the stack's leaf pseudos — i.e. the next page of
    // the magazine — rather than a flat "behind the paper" colour.
    // Mobile keeps the #1a1a1a letterbox surface.
    const pageBox = document.createElement("div");
    Object.assign(pageBox.style, {
      position: "relative",
      aspectRatio: aspectCss,
      // Floating-magazine sizing: the sheet aspect-fits inside the
      // reader so the dimmed page stays visible around it (a sheet
      // that fills the window reads as a takeover, not a magazine
      // lying on the page). Fit is device-classed — 88% desktop, 94%
      // phones (see sheetFitPct). ONE driven dimension; aspect-ratio
      // derives the other — see sheetSizeFor for the squash failure
      // modes this avoids on portrait sheets and narrow viewports.
      ...sheetSizeFor(aspectCss, sheetFitPct(this.isMobileReader())),
      // Transparent so the dog-ear PUNCH and the area beneath a turn
      // see through to the offset leaves / the scrim'd publisher page
      // — not a dark #1a1a1a patch.
      background: "transparent",
    });
    // The STATIC half of the paper treatment: deep ambient shadow +
    // stacked leaves, tinted from the page's own bg so the "rest of
    // the magazine" matches the creative. Stays put during a page
    // turn — only the inner sheet rotates.
    pageBox.classList.add("paper-stack");
    pageBox.style.setProperty("--leaf-bg", page.bg ?? "#0a0a0b");

    // The PEELING half: a thin leaf carrying the creative (stage) and
    // the dog-ear flap. animatePageTurn drives its clip-path per
    // frame during the corner peel — peeling it alone (instead of the
    // whole pageBox) keeps the stack and its shadow grounded, like a
    // real page lifting off a pile.
    const sheet = document.createElement("div");
    Object.assign(sheet.style, {
      position: "absolute",
      inset: "0",
    });
    sheet.classList.add("paper-sheet");

    const stage = document.createElement("div");
    // .paper-stage: lookup hook for animatePageTurn — during a peel
    // of a dog-eared page, the fold's punch clip moves from the
    // static pageBox onto this element so the notch travels with the
    // peeling sheet (nested clips = intersection with the peel clip).
    stage.classList.add("paper-stage");
    Object.assign(stage.style, {
      position: "absolute",
      inset: "0",
      containerType: "size",
      background: page.bg ?? "#0a0a0b",
      overflow: "hidden",
      // The expanded magazine reader is always interactive — a tap
      // navigates to the LP and page-turns are draggable — so the sheet
      // reads as clickable. Set the finger cursor directly on the stage
      // (the element the reader actually hovers) rather than relying on
      // it inheriting from pageBox's CTA-gated cursor, which leaves the
      // sheet showing the default arrow when landing-url resolves late
      // (e.g. in the publisher approval preview). Edit mode keeps the
      // default cursor — the designer canvas isn't a click target.
      cursor: this.editMode ? "default" : "pointer",
    });

    // Full-bleed video behind every layout item, in every expanded
    // view. Inserted first so subsequent layoutItemToNode calls stack
    // on top (DOM order decides z-stack; layout items don't set an
    // explicit z-index).
    applyTextureBg(stage, page.textureBg, applyVideoBg(stage, page.videoBg));
    applyLogo(stage, this.configData.logo);

    (page.layout ?? []).forEach((item, i) => {
      const node = layoutItemToNode(item, page, defaultFont, i);
      if (node) stage.appendChild(node);
    });

    // Paper grain over the whole composition. Appended after the
    // layout items so it sits on top (they never set z-index);
    // pointer-events:none keeps CTA hit-testing intact. Inside the
    // stage so the dog-ear's punch-out clip applies to it too.
    stage.appendChild(buildGrainOverlay());

    sheet.appendChild(stage);
    // Dog-ear: fold affordance at the top-right of each expanded
    // page. Unfolded = dotted diagonal where the crease will be.
    // Folded = opaque paper-tone triangle in the bottom-left of the
    // corner area (the flap resting on the page) + the top-right
    // triangle of the corner area is punched out of the page so the
    // reader sees through to the overlay behind. Appended to pageBox
    // (a sibling of stage) so the flap sits outside the stage's
    // clip path and is anchored to the actual page corner, not the
    // outer letterboxed `el` corner.
    //
    // PC uses 12cqmin (min-dim = page height in landscape). Mobile
    // uses a smaller value because min-dim = page width in portrait,
    // and the same 12% reads as a visually larger chunk of the
    // narrower page.
    //
    // Gated on data-can-fold (set by the bootstrap when the campaign
    // opted into CPF and a fold token was minted). Without that
    // surface, buildDogEar isn't invoked at all — the corner doesn't
    // exist on creatives without dog-ear configured.
    if (this.canFold) {
      // Folding any one page bookmarks the entire creative, so every
      // page mounts in the same fold state — `>= 0` means "any page is
      // pinned" rather than the per-page index match used previously.
      //
      // Preview-only shrink: in preview-frame mode (the designer modal +
      // the publisher approval preview) use a subtler corner. Delivered
      // ads never set preview-frame, so real served creatives keep the
      // production sizes.
      // Sized in cqmin of the sheet stage, so the resting corner scales
      // with the floating sheet on every device (the old full-bleed
      // mobile reader had its own tuning; the unified sheet uses the
      // desktop values).
      const framed = this.getAttribute("preview-frame") === "1";
      const dogEarSize = framed ? "7cqmin" : "12cqmin";
      const handle = buildDogEar(
        pageBox,
        dogEarSize,
        this,
        index,
        this.pinnedPageIdx >= 0,
        page.bg,
        resolveReadingRtl(cfg, this.pagesData),
        true, // alignToPeel: px-match the dotted crease to the peel (PC + mobile)
        this.consumeOverlayTease, // one tease per magazine-open, all pages
      );
      // On the rotating sheet, not the static stack — a fold belongs
      // to its page and turns with it.
      sheet.appendChild(handle.wrap);
      this._dogEars.push(handle);
    }
    pageBox.appendChild(sheet);
    el.appendChild(pageBox);

    // Whole-sheet CTA: a deliberate tap anywhere on the sheet opens the
    // landing page in a new tab. This replaces the per-item ctaTarget
    // hotspots — readers expect the ad itself to be the link, and the
    // hotspot model made everything OUTSIDE the (invisible) hotspot a
    // dead zone. ctaTarget survives on items only as a styling hint for
    // the designer.
    //
    // "Deliberate tap" is the load-bearing part — the mobile reader is
    // a scroller, so a drag must never navigate:
    //  - we listen on `click`, which browsers already suppress for
    //    touch gestures that scrolled or stopped a momentum fling,
    //  - plus an explicit pointerdown→click movement gate (12px slop)
    //    for the cases click doesn't cover (mouse drags, slow wobbly
    //    presses that end near where they started on some engines),
    //  - taps on interactive sub-elements (dog-ear corner, links,
    //    buttons) keep their own behavior,
    //  - mid page-turn clicks on desktop don't navigate.
    const ctaHref = this.getAttribute("landing-url") ?? "";
    if (ctaHref) {
      const ctaTrackingUrl = this.getAttribute("cta-url") ?? "";
      pageBox.style.cursor = "pointer";
      let downAt: { x: number; y: number } | null = null;
      pageBox.addEventListener("pointerdown", (e) => {
        downAt = { x: e.clientX, y: e.clientY };
      });
      pageBox.addEventListener("pointercancel", () => { downAt = null; });
      pageBox.addEventListener("click", (e) => {
        const d = downAt;
        downAt = null;
        const t = e.target as HTMLElement | null;
        if (t?.closest(".dogear-corner, a, button")) return;
        // Movement gate. A click with no recorded pointerdown (keyboard
        // / synthetic activation) counts as deliberate.
        if (d && Math.hypot(e.clientX - d.x, e.clientY - d.y) > 12) return;
        if (this._finishTurn) return; // desktop corner-peel in flight
        e.preventDefault();
        e.stopPropagation();
        if (typeof performance !== "undefined" && performance.mark) {
          performance.mark("pv:cta-tap");
        }
        // Tap-feedback: a brief pressed state on the creative before the
        // new tab opens, so the reader gets confirmation their tap
        // registered. On the stage, NOT the pageBox — applyStackLayout
        // owns pageBox transforms on desktop and a feedback transform
        // there would clobber the stack offsets. In-overlay loading
        // transition is skipped because window.open shifts focus to the
        // new tab. Instant state change for prefers-reduced-motion.
        const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
        stage.style.transition = reduced ? "" : "transform 120ms ease-out, opacity 120ms ease-out";
        stage.style.transform  = "scale(0.96)";
        stage.style.opacity    = "0.85";
        window.setTimeout(() => {
          stage.style.transition = "";
          stage.style.transform  = "";
          stage.style.opacity    = "";
        }, reduced ? 80 : 180);
        this.dispatchEvent(new CustomEvent("cta-click", { bubbles: true, composed: true }));
        if (ctaTrackingUrl) {
          const pixel = new Image();
          pixel.src = ctaTrackingUrl;
        }
        window.open(ctaHref, "_blank", "noopener,noreferrer");
      });
    }
    return el;
  }

  // Tween a layout item node from base state to its animationTo values
  // when its page becomes active. Reads animationTo off dataset (stashed
  // by layoutItemToNode).
  private playItemAnimation(item: HTMLElement): void {
    const to = parseJSON<MotionTarget>(item.dataset.animationTo);
    if (!to) return;
    const baseValues = {
      left: Number(item.dataset.baseLeft ?? "0"),
      top: Number(item.dataset.baseTop ?? "0"),
      rotation: Number(item.dataset.baseRotation ?? "0"),
      scale: 1,
      opacity: Number(item.dataset.baseOpacity ?? "1"),
    };
    const delay = to.delay ?? 0;
    setTimeout(() => {
      item.style.transition = transitionFor({ ...to, delay: 0 }, 0.8);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        applyTargetState(item, to, {
          left: to.left ?? baseValues.left,
          top: to.top ?? baseValues.top,
          rotation: to.rotation ?? baseValues.rotation,
          scale: to.scale ?? 1,
          opacity: to.opacity ?? 1,
        });
      }));
    }, delay * 1000);
  }

  // `animate=false` forces an instant swap (no clock page-turn) — used
  // when an interactive peel has ALREADY played the turn visually and
  // just needs the bookkeeping (stack shift, item anims, counter, nav).
  private updatePages(animate = true): void {
    const overlay = this.shadowRoot?.querySelector<HTMLElement>(".overlay");
    if (!overlay) return;

    const pages = this.pagesData;
    const current = this.currentPage;

    // Paper page-turn: animate real navigation (a from→to pair) when
    // motion is allowed — every device uses the peel now (swipe and
    // arrows both land here). The initial render after expand has
    // _displayedPage === null and just places the cover. A turn
    // already in flight is settled instantly first, so spamming
    // next/prev lands each page in a consistent resting state.
    const prevIdx = this._displayedPage;
    this._displayedPage = current;
    if (this._finishTurn) this._finishTurn();
    const turnFrom =
      animate && prevIdx !== null && prevIdx !== current && !this._reducedMotion
        ? prevIdx
        : null;

    // Physical page stack: upcoming pages are VISIBLE, laid beneath
    // the active sheet at small offsets — peel the top sheet and the
    // next one is literally the sheet beneath; the pile shrinks as
    // you read (and shifts forward one slot after each turn, via the
    // .paper-stack transform transition). This also grounds the
    // dog-ear: the punched corner reveals the next sheet the fold
    // lies on, not a hole to the scrim. During a turn the from/to
    // pair is owned by animatePageTurn; everything else lays out now,
    // and onDone re-lays the full stack.
    const turnPair: Set<number> =
      turnFrom !== null ? new Set([turnFrom, current]) : new Set();
    this.applyStackLayout(overlay, pages.length, current, turnPair);

    pages.forEach((_, i) => {
      const el = overlay.querySelector<HTMLElement>(`.page-${i}`);
      if (!el) return;
      const isActive = i === current;

      if (isActive) {
        el.querySelectorAll<HTMLElement>("[data-anim-item]").forEach((item) => {
          this.playItemAnimation(item);
        });
      } else if (i !== turnFrom) {
        // The outgoing page of a turn keeps its items posed until the
        // sheet has rotated away (reset in onDone) — resetting now
        // would visibly snap them mid-turn.
        this.resetItemAnimations(el);
      }

      const divider = el.querySelector<HTMLElement>(".divider");
      if (divider) divider.style.width = isActive ? "48px" : "0";
    });

    if (turnFrom !== null) {
      const outgoing = overlay.querySelector<HTMLElement>(`.page-${turnFrom}`);
      const incoming = overlay.querySelector<HTMLElement>(`.page-${current}`);
      if (outgoing && incoming) {
        this._finishTurn = animatePageTurn({
          outgoing,
          incoming,
          direction: current > turnFrom ? "next" : "prev",
          rtl: resolveReadingRtl(this.configData, pages),
          onDone: () => {
            this._finishTurn = null;
            // Full stack re-layout: the turned pair lands in its
            // resting slots and the remaining pile shifts forward
            // (the .paper-stack transform transition animates the
            // slide while the page is still settling).
            this.applyStackLayout(overlay, pages.length, current, new Set());
            this.resetItemAnimations(outgoing);
          },
        });
      }
    }

    const counter = overlay.querySelector<HTMLElement>(".page-counter");
    if (counter)
      counter.textContent = `${String(current + 1).padStart(2, "0")} / ${String(pages.length).padStart(2, "0")}`;

    const prevBtn = overlay.querySelector<HTMLElement>(".nav-prev");
    const nextBtn = overlay.querySelector<HTMLElement>(".nav-next");
    if (prevBtn) prevBtn.style.display = current > 0 ? "flex" : "none";
    if (nextBtn) nextBtn.style.display = current < pages.length - 1 ? "flex" : "none";
  }

  // Lay the desktop pages out as a physical stack: the active sheet
  // on top at the origin, upcoming sheets visible beneath at small
  // diagonal offsets (depth-capped at 2 — deeper pages sit exactly
  // under the depth-2 sheet, fully covered), read pages hidden. The
  // z band stays in single digits so a turn (z 30/20 in paper.ts)
  // and the reader chrome (z 10+) always ride above the pile.
  // `skip` excludes the from/to pair of an in-flight turn, whose
  // visibility belongs to animatePageTurn until its onDone.
  private applyStackLayout(
    overlay: HTMLElement,
    pageCount: number,
    current: number,
    skip: Set<number>,
  ): void {
    // RTL reading: the pile fans to the bottom-LEFT (mirror the x offset)
    // so upcoming sheets peek out on the same side as the peel + dog-ear.
    const rtl = resolveReadingRtl(this.configData, this.pagesData);
    for (let i = 0; i < pageCount; i++) {
      if (skip.has(i)) continue;
      const el = overlay.querySelector<HTMLElement>(`.page-${i}`);
      if (!el) continue;
      const box = el.querySelector<HTMLElement>(".paper-stack");
      const depth = i - current;
      if (depth < 0) {
        // Already read — off the pile.
        el.style.opacity = "0";
        el.style.pointerEvents = "none";
        el.style.zIndex = "";
        if (box) box.style.transform = "";
      } else {
        // Offset caps at depth 2, but z keeps strictly decreasing with
        // RAW depth — pages sharing the capped offset slot must still
        // stack in reading order or a later sibling (equal z, later in
        // DOM) would paint its edge over the nearer sheet.
        const d = Math.min(depth, 2);
        el.style.opacity = "1";
        el.style.pointerEvents = depth === 0 ? "auto" : "none";
        el.style.zIndex = String(Math.max(6 - depth, 1));
        if (box) box.style.transform = d === 0 ? "" : `translate(${(rtl ? -d : d) * 6}px, ${d * 6}px)`;
      }
    }

    // Fold VISUAL only on the top sheet. The bookmark is creative-wide
    // (every page is semantically pinned), but rendering every sheet
    // in the pile folded nests the punched corners into a cascade of
    // ledges tunnelling to the scrim — real paper folds ONE leaf,
    // which lies on the intact corner of the next. Under-sheets render
    // unfolded; the new top sheet inherits the fold at each turn's
    // settle (skip covers the in-flight pair, so the peeling sheet
    // keeps its notch while it lifts).
    const folded = this.pinnedPageIdx >= 0;
    const touchTease =
      !this._reducedMotion && !window.matchMedia("(hover: hover)").matches;
    for (const de of this._dogEars) {
      if (skip.has(de.pageIndex)) continue;
      const isTop = de.pageIndex === current;
      // Under-sheets show NO corner affordance at all — their dashed
      // fold hint would otherwise peek through the top sheet's punch.
      de.wrap.style.visibility = isTop ? "" : "hidden";
      if (folded && isTop) de.applyFolded();
      else de.applyUnfolded();
      // Touch: schedule the corner demonstration when a page becomes
      // the top sheet — the touch counterpart of the hover tease.
      // teasePlayed only stops re-SCHEDULING for the same page; the
      // per-open budget inside peelTease is what limits actual plays
      // to one per magazine-open (flapping on every swipe was too much).
      if (touchTease && isTop && !folded && !de.teasePlayed) {
        de.teasePlayed = true;
        window.setTimeout(() => de.peelTease(), 350);
      }
    }
  }

  // Reset a page's animated items to their authored base state so
  // re-entering the page replays the motion. Used for every inactive
  // page on navigation, and for the outgoing page of a paper turn
  // once its sheet has rotated out of view.
  private resetItemAnimations(pageEl: HTMLElement): void {
    pageEl.querySelectorAll<HTMLElement>("[data-anim-item]").forEach((item) => {
      const baseLeft = Number(item.dataset.baseLeft ?? "0");
      const baseTop = Number(item.dataset.baseTop ?? "0");
      const baseRotation = Number(item.dataset.baseRotation ?? "0");
      const baseOpacity = Number(item.dataset.baseOpacity ?? "1");
      item.style.transition = "none";
      item.style.left = `${baseLeft}%`;
      item.style.top = `${baseTop}%`;
      item.style.rotate = baseRotation ? `${baseRotation}deg` : "";
      item.style.scale = "";
      item.style.opacity = baseOpacity !== 1 ? String(baseOpacity) : "";
    });
  }
}

// Dog-ear fold affordance. Lives in the top-right corner of an
// expanded page. Click to toggle folded.
//
// Unfolded: a dotted diagonal line runs from the top-left of the
// corner area to the bottom-right (i.e., where the crease will be).
// Nothing else visible — the page content below isn't obscured.
//
// Folded:
//   1. The bottom-left triangle of the corner area is painted with
//      the opaque paper-tone fold flap.
//   2. Drop-shadow on the flap, offset down-left, so the shadow shows
//      outside the flap's left and bottom edges (the two non-
//      hypotenuse sides) — reading as the lifted paper resting on
//      the page beneath.
//   3. The top-right triangle of the corner area is clipped off the
//      page (the stage) so the reader sees through to whatever is
//      behind the page — the "empty space" where the corner used to
//      be before it got folded away.
interface DogEarHandle {
  wrap: HTMLElement;
  // Which page this corner belongs to — applyStackLayout uses it to
  // show the folded VISUAL only on the top sheet of the desktop pile
  // (placeholder pages don't build a dog-ear, so handle order alone
  // can't be trusted as the page index).
  pageIndex: number;
  applyFolded: () => void;
  applyUnfolded: () => void;
  /** One peel-and-return demonstration of the corner. Hover devices
    * fire it on hover entry; touch devices get it from applyStackLayout
    * on the top sheet (see teasePlayed). Both paths are additionally
    * capped by the banner's per-open tease budget (teaseGate): at most
    * ONE tease plays per magazine-open across all pages. The old
    * per-corner IntersectionObserver fired ALL pages at once in the
    * unified reader, where every sheet occupies the same box. */
  peelTease: () => void;
  teasePlayed: boolean;
}

// ─── Reader-chrome contrast + locale helpers ──────────────────────
//
// The expanded reader's overlay controls (close, nav arrows, page
// counter, dots, dog-ear crease hint) are authored light-on-dark for
// the default #1a1a1a reader. When the creative's authored background
// is light, that low-opacity white chrome blends into it. These pick a
// readable ink direction from the background's luminance.

/** Rec.709 relative luminance (0–1) of a #rrggbb color, or null when
 *  the value isn't 6-digit hex (named colors / gradients fall through). */
function hexLuminance(color: string | undefined): number | null {
  const hex = color?.trim().match(/^#([0-9a-f]{6})$/i)?.[1];
  if (!hex) return null;
  const r = parseInt(hex.slice(0, 2), 16);
  const g = parseInt(hex.slice(2, 4), 16);
  const b = parseInt(hex.slice(4, 6), 16);
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
}

/** True when a surface is light enough that light-on-dark chrome would
 *  blend in (→ use dark ink). Non-hex backgrounds return false, keeping
 *  the light-on-dark default that matches the reader's #1a1a1a base. */
function isLightSurface(bg: string | undefined): boolean {
  const lum = hexLuminance(bg);
  return lum != null && lum >= 0.5;
}

/** Detects Japanese text (hiragana, katakana, CJK, half-width kana). */
function hasJapanese(s: string): boolean {
  // Hiragana+Katakana (U+3040–30FF), CJK Unified (U+3400–9FFF),
  // half-width katakana (U+FF66–FF9F).
  return /[぀-ヿ㐀-鿿ｦ-ﾟ]/.test(s);
}

/** Picks the close-button label from the creative's content language.
 *  JA/EN only for now: any Japanese in the page text → 「閉じる」. */
function pickCloseLabel(pages: Page[]): string {
  const text = pages
    .map((p) =>
      [p.headline, p.sub, p.body, p.caption, p.tag, p.ctaLabel]
        .concat((p.layout ?? []).map((it) => (it as { text?: string }).text))
        .filter(Boolean)
        .join(" "),
    )
    .join(" ");
  return hasJapanese(text) ? "閉じる" : "Close";
}

/** All text across the creative's pages — headline/sub/body/etc. plus
 *  every layout item's text. Shared by language + direction detection. */
function collectPageText(pages: Page[]): string {
  return pages
    .map((p) =>
      [p.headline, p.sub, p.body, p.caption, p.tag, p.ctaLabel]
        .concat((p.layout ?? []).map((it) => (it as { text?: string }).text))
        .filter(Boolean)
        .join(" "),
    )
    .join(" ");
}

/** Arabic / Arabic-Supplement / presentation-forms ranges. */
function hasArabic(text: string): boolean {
  return /[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]/.test(text);
}

/** Any text item set to Japanese tategaki (vertical-rl). */
function hasVerticalWriting(pages: Page[]): boolean {
  return pages.some((p) =>
    (p.layout ?? []).some(
      (it) => (it as { writingMode?: string }).writingMode === "vertical-rl",
    ),
  );
}

/** Resolve the reader's RTL flag. config.readingDirection forces it;
 *  "auto" (default) → RTL when the content is Arabic or uses vertical-rl
 *  writing (both read right-to-left). Drives the peel corner, dog-ear
 *  corner, and nav arrows. */
function resolveReadingRtl(cfg: BannerConfig, pages: Page[]): boolean {
  const dir = cfg.readingDirection ?? "auto";
  if (dir === "rtl") return true;
  if (dir === "ltr") return false;
  return hasArabic(collectPageText(pages)) || hasVerticalWriting(pages);
}

function buildDogEar(
  // The page's outer box (.paper-stack on desktop). The fold clips
  // THIS — stack, leaves, sheet, stage, everything — so the punched
  // corner is a hole through the whole magazine to the article
  // behind, not a reveal of some internal surface.
  pageBox: HTMLElement,
  size: string = "12cqmin",
  host: HTMLElement,
  pageIndex: number,
  initiallyFolded: boolean,
  // Page background — the unfolded crease hint is a pale cream that
  // vanishes on light/cream pages, so on a light bg we ink it dark.
  bg?: string,
  // RTL reading (Arabic / vertical) puts the dog-ear on the top-LEFT.
  rtl = false,
  // When true, the resting corner's size is measured from the sheet in
  // PIXELS so its dotted crease lands exactly on the animated peel's
  // crease (cqmin doesn't convert 1:1 to the peel's px geometry). Used
  // on mobile, where the corner should match the auto-peel.
  alignToPeel = false,
  // Check-and-consume budget shared across ALL pages of one magazine
  // open: returns true exactly once, so the reader sees at most one
  // tease per open regardless of how many pages they turn or hover.
  // Absent (designer/standalone contexts) = unlimited, old behavior.
  teaseGate?: () => boolean,
): DogEarHandle {
  // The folded clip punches the corner through the WHOLE pageBox —
  // but a clip-path confines ALL painting to its polygon, so a
  // box-tight polygon would truncate the sheet's contact shadow. The
  // polygon extends 120px beyond the box on all sides and only dips
  // in at the corner notch, which stays cut exactly at the page
  // edge. The region beside the notch is excluded too, so the shadow
  // is notched at the hole — paper that isn't there casts no shadow.
  // RTL mirrors every x across the box (top-right notch → top-left).
  // Parameterised by fold size so the hover hint can interpolate the
  // page's clip-path between unfolded (sz=0, a degenerate notch ≈ full
  // rect) and folded — both 7-point polygons, so clip-path animates
  // smoothly between them.
  const foldClipFor = (sz: string): string => rtl
    ? `polygon(calc(100% + 120px) -120px, ${sz} -120px, ${sz} 0, ` +
      `0 ${sz}, -120px ${sz}, ` +
      `-120px calc(100% + 120px), calc(100% + 120px) calc(100% + 120px))`
    : `polygon(-120px -120px, calc(100% - ${sz}) -120px, calc(100% - ${sz}) 0, ` +
      `100% ${sz}, calc(100% + 120px) ${sz}, ` +
      `calc(100% + 120px) calc(100% + 120px), -120px calc(100% + 120px))`;
  // Peak peel depth, shared by the hover/auto peel and (when alignToPeel)
  // the resting-corner px sync below.
  const PEEL_MAX = 0.09;
  // `let` so alignToPeel can recompute it from the measured px leg.
  let pageClip = foldClipFor(size);

  // Respect prefers-reduced-motion: skip the CSS transitions so the
  // fold/unfold visual change is instant rather than animated. Per
  // spec — accessibility-first, no JS-driven animation either way.
  const reduceMotion =
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  const wrap = document.createElement("button");
  wrap.type = "button";
  // Lookup hook only (no styling): animatePageTurn measures this box
  // to notch the curling back layer when the page is dog-eared.
  wrap.classList.add("dogear-corner");
  wrap.setAttribute("aria-label", initiallyFolded ? "Unfold page" : "Fold page");
  wrap.dataset.folded = initiallyFolded ? "1" : "0";
  Object.assign(wrap.style, {
    position: "absolute",
    top: "0",
    ...(rtl ? { left: "0" } : { right: "0" }),
    width: size,
    height: size,
    padding: "0",
    margin: "0",
    background: "transparent",
    border: "none",
    cursor: "pointer",
    zIndex: "5",
  });

  // Unfolded hint: dashed diagonal SVG line from top-left to
  // bottom-right of the corner area, previewing where the crease
  // will appear once folded. Geometry matches the fold triangle's
  // hypotenuse exactly. vector-effect=non-scaling-stroke keeps
  // stroke-width + dash lengths in screen pixels regardless of the
  // SVG's scaled-to-fit size.
  const SVG_NS = "http://www.w3.org/2000/svg";
  const hint = document.createElementNS(SVG_NS, "svg");
  hint.setAttribute("viewBox", "0 0 100 100");
  hint.setAttribute("preserveAspectRatio", "none");
  Object.assign(hint.style, {
    position: "absolute",
    inset: "0",
    pointerEvents: "none",
    transition: reduceMotion ? "none" : "opacity 0.2s ease",
  });
  // Crease diagonal: top-left→bottom-right (LTR corner) or
  // top-right→bottom-left (RTL corner). Mirror the x endpoints.
  const diagX1 = rtl ? "100" : "0";
  const diagX2 = rtl ? "0" : "100";
  const hintLine = document.createElementNS(SVG_NS, "line");
  hintLine.setAttribute("x1", diagX1);
  hintLine.setAttribute("y1", "0");
  hintLine.setAttribute("x2", diagX2);
  hintLine.setAttribute("y2", "100");
  // Pale cream reads on dark pages; on a light page it disappears, so
  // ink the crease hint dark instead. Folded flap keeps its paper tone.
  const dogEarOnLight = isLightSurface(bg);
  hintLine.setAttribute("stroke", dogEarOnLight ? "#000000" : "#d9d2bf");
  hintLine.setAttribute("stroke-opacity", dogEarOnLight ? "0.4" : "0.5");
  hintLine.setAttribute("stroke-width", "1.5");
  hintLine.setAttribute("stroke-dasharray", "4 4");
  hintLine.setAttribute("vector-effect", "non-scaling-stroke");
  hint.appendChild(hintLine);
  wrap.appendChild(hint);

  // Folded crease: a solid hairline along the fold diagonal (the
  // dashed hint is hidden while folded). The punched side of the
  // diagonal is clipped off the page entirely, so the line renders
  // its flap-side half — a fine crease right at the cut edge.
  const crease = document.createElementNS(SVG_NS, "svg");
  crease.setAttribute("viewBox", "0 0 100 100");
  crease.setAttribute("preserveAspectRatio", "none");
  Object.assign(crease.style, {
    position: "absolute",
    inset: "0",
    pointerEvents: "none",
    opacity: "0",
    transition: reduceMotion ? "none" : "opacity 0.25s ease",
  });
  const creaseLine = document.createElementNS(SVG_NS, "line");
  creaseLine.setAttribute("x1", diagX1);
  creaseLine.setAttribute("y1", "0");
  creaseLine.setAttribute("x2", diagX2);
  creaseLine.setAttribute("y2", "100");
  creaseLine.setAttribute("stroke", dogEarOnLight ? "#000000" : "#ffffff");
  creaseLine.setAttribute("stroke-opacity", dogEarOnLight ? "0.30" : "0.35");
  creaseLine.setAttribute("stroke-width", "1");
  creaseLine.setAttribute("vector-effect", "non-scaling-stroke");
  crease.appendChild(creaseLine);
  wrap.appendChild(crease);

  // Folded: bottom-left triangle via clip-path. Vertices (0,0),
  // (0,100%), (100%,100%). Its three edges are:
  //   - left edge: x=0 (non-hypotenuse)
  //   - bottom edge: y=100% (non-hypotenuse)
  //   - hypotenuse: diagonal from top-left to bottom-right
  // drop-shadow offset (-2px, 2px) casts shadow outside the left and
  // bottom edges (onto the page surface); the hypotenuse-side shadow
  // falls into the flap body and is covered by the fill.
  const flap = document.createElement("div");
  Object.assign(flap.style, {
    position: "absolute",
    inset: "0",
    // LTR fold = bottom-left triangle; RTL fold = bottom-right triangle.
    clipPath: rtl ? "polygon(100% 0, 100% 100%, 0 100%)" : "polygon(0 0, 0 100%, 100% 100%)",
    background: "transparent",
    filter: "none",
    transition: reduceMotion ? "none" : "background 0.25s ease, filter 0.25s ease",
    pointerEvents: "none",
  });
  // Texture on the folded-over paper back — same grain as the page
  // faces and the peel flap. Hidden until folded (the grain child
  // would otherwise render a noise triangle on the unfolded corner).
  const flapGrain = buildGrainOverlay();
  flapGrain.style.opacity = "0";
  flapGrain.style.transition = reduceMotion ? "none" : "opacity 0.25s ease";
  flap.appendChild(flapGrain);
  wrap.appendChild(flap);

  // ── Hover affordance: a REAL corner peel, reusing the page-turn ──
  // Hovering anywhere on the page lifts the dog-ear's top corner a
  // little, using paper.ts's peel geometry (dogEarPeelFrame): the SHEET
  // is clipped to the kept side (revealing the page beneath) while a
  // `.paper-flap` sibling renders the folded-over corner — exactly the
  // motion of a page turn, just small and reversible. Pointer-fine only
  // (touch taps to fold for real); skipped under reduced-motion / while
  // actually folded. Drives its own rAF so it never touches the WAAPI/
  // CSS state used by applyFolded.
  let peelRaf: number | null = null;
  let peelFlap: HTMLElement | null = null;
  let peelT = 0;
  let peeling = false; // a teaser cycle is in flight
  const stopPeelRaf = (): void => {
    if (peelRaf !== null) { cancelAnimationFrame(peelRaf); peelRaf = null; }
  };
  // Desktop sheets carry `.paper-sheet`; mobile sheets don't (no
  // floating stack), but the dog-ear wrap is a direct child of the sheet
  // either way — so fall back to the parent.
  const peelSheet = (): HTMLElement | null =>
    wrap.closest<HTMLElement>(".paper-sheet") ?? wrap.parentElement;
  // Hard reset — used on real fold/unfold so the hint never lingers.
  const clearPeel = (): void => {
    stopPeelRaf();
    peelT = 0;
    peeling = false;
    const sheet = peelSheet();
    if (sheet) sheet.style.clipPath = "";
    if (peelFlap) { peelFlap.remove(); peelFlap = null; }
  };
  const renderPeel = (sheet: HTMLElement, t: number): void => {
    const r = sheet.getBoundingClientRect();
    const ff = dogEarPeelFrame(t, r.width || 1, r.height || 1, rtl);
    sheet.style.clipPath = ff.keptClip ?? "";
    if (peelFlap) {
      if (ff.cutClip) {
        peelFlap.style.display = "block";
        peelFlap.style.clipPath = ff.cutClip;
        peelFlap.style.transform = ff.flapTransform;
      } else {
        peelFlap.style.display = "none";
      }
    }
  };
  const tweenPeel = (to: number, dur: number, onDone?: () => void): void => {
    const sheet = peelSheet();
    if (!sheet) return;
    if (to > 0 && !peelFlap) {
      // The flap is a SIBLING of the sheet (in pageBox), so the sheet's
      // keptClip doesn't clip it away; its own cutClip+transform place it.
      peelFlap = document.createElement("div");
      peelFlap.className = "paper-flap";
      peelFlap.appendChild(buildGrainOverlay());
      Object.assign(peelFlap.style, {
        position: "absolute", inset: "0", display: "none",
        pointerEvents: "none", zIndex: "4",
      });
      pageBox.appendChild(peelFlap);
    }
    stopPeelRaf();
    const from = peelT;
    const startT = performance.now();
    const ease = (u: number): number => (u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2);
    const step = (now: number): void => {
      const u = Math.min(1, (now - startT) / dur);
      peelT = from + (to - from) * ease(u);
      renderPeel(sheet, peelT);
      if (u < 1) peelRaf = requestAnimationFrame(step);
      else { peelRaf = null; onDone?.(); }
    };
    peelRaf = requestAnimationFrame(step);
  };

  // Teaser: peel the corner up, hold a beat, then lay it back to the
  // ORIGINAL position so the corner is ready for the user to fold for
  // real. One cycle per hover (guarded), then it rests.
  // After a tease, suppress re-teasing for a randomized 4-8s so the hint reads
  // as occasional/spontaneous instead of firing on EVERY hover (which is just
  // annoying). Re-rolled each time for an organic cadence. (`peeling` only
  // guards the ~1.2s tween itself — this adds the quiet gap after it.)
  let teaseBlockedUntil = 0;
  const peelTease = (): void => {
    const now = Date.now();
    if (peeling || wrap.dataset.folded === "1" || !peelSheet() || now < teaseBlockedUntil) return;
    // Per-open budget LAST, after the cheap guards — a skipped attempt
    // (mid-peel, already folded) must not burn the open's single tease.
    if (teaseGate && !teaseGate()) return;
    teaseBlockedUntil = now + 4000 + Math.random() * 4000;
    peeling = true;
    // 2× slower than before (240/160/200 → 480/320/400) so the peel reads
    // as a gentle invitation rather than a quick twitch.
    tweenPeel(PEEL_MAX, 480, () => {
      window.setTimeout(() => {
        tweenPeel(0, 400, clearPeel); // back to flat + drop the flap/clip
      }, 320);
    });
  };

  // Visual application factored out so we can render a pre-folded
  // page on initial mount (honored pin) without going through a click.
  const applyFolded = (): void => {
    clearPeel(); // a real fold supersedes any hover peel in progress
    // Clip the whole pageBox: the punched corner is a hole through
    // the magazine (stack, leaves, sheet, content) to the article
    // behind it. The flap sits on the kept side of the diagonal, so
    // it survives the clip.
    pageBox.style.clipPath = pageClip;
    // -webkit-clip-path isn't in the standard CSSStyleDeclaration
    // typings, so go through setProperty to avoid an `any` cast.
    pageBox.style.setProperty("-webkit-clip-path", pageClip);
    // Back of the folded corner: the magazine's shared paper stock
    // (same textured fill as the peel flap — every page's back is the
    // same paper), with a sheen brightest at the crease.
    // Sheen brightest at the crease — mirror the gradient angle for RTL.
    const flapSheen = rtl
      ? "linear-gradient(135deg, rgba(255,255,255,0.30), rgba(255,255,255,0) 55%)"
      : "linear-gradient(225deg, rgba(255,255,255,0.30), rgba(255,255,255,0) 55%)";
    flap.style.background = paperBackBackground(flapSheen);
    flap.style.backgroundBlendMode = PAPER_BACK_BLEND;
    flapGrain.style.opacity = "0.05";
    // A folded flap rests ON the page, so it casts a real shadow
    // underneath — offset down-left (toward the page) for a top-right
    // fold. drop-shadow because the flap is clipped to a triangle —
    // it traces the clipped silhouette; box-shadow would draw around
    // the full element box. Two layers: a tight contact shadow at the
    // crease + a softer ambient falloff.
    // Shadow offsets toward the page (down + away from the fold edge):
    // down-left for a top-right fold, down-right for a top-left (RTL) fold.
    flap.style.filter = rtl
      ? "drop-shadow(4px 5px 4px rgba(0,0,0,0.45)) drop-shadow(10px 14px 14px rgba(0,0,0,0.30))"
      : "drop-shadow(-4px 5px 4px rgba(0,0,0,0.45)) drop-shadow(-10px 14px 14px rgba(0,0,0,0.30))";
    // Fold triangle takes over; hide the hint, show the crease.
    hint.style.opacity = "0";
    crease.style.opacity = "1";
    wrap.setAttribute("aria-label", "Unfold page");
    // Keep the folded flag in sync with the VISUAL state. syncCornerToPeel
    // (and the click toggle) read dataset.folded; if it lingers at "1" on
    // a sheet we've visually unfolded, the corner observer re-clips it —
    // which is exactly how a reopened pin folded every under-sheet (each
    // mounts folded="1", applyStackLayout unfolds the under-sheets but
    // left the flag set, so the observer punched the fold back through
    // them to the scrim instead of resting on the next sheet).
    wrap.dataset.folded = "1";
  };
  const applyUnfolded = (): void => {
    clearPeel();
    pageBox.style.clipPath = "";
    pageBox.style.removeProperty("-webkit-clip-path");
    flap.style.background = "transparent";
    flap.style.filter = "none";
    flap.style.transform = "";
    flapGrain.style.opacity = "0";
    hint.style.opacity = "1";
    crease.style.opacity = "0";
    wrap.setAttribute("aria-label", "Fold page");
    wrap.dataset.folded = "0";
  };

  // Hover corner-peel — fires when the pointer enters the PAGE (so the
  // hint is easy to notice anywhere on the creative), not just the tiny
  // corner. Pointer-fine devices only (touch taps to fold for real);
  // skipped under reduced-motion / while actually folded.
  const supportsHover =
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(hover: hover)").matches;
  if (!reduceMotion && supportsHover) {
    // Pointer devices: one peel-and-return teaser per hover entry; then
    // it rests so the user can fold the corner themselves. Touch
    // devices get the teaser from applyStackLayout instead — top sheet
    // only, once. (An IntersectionObserver per corner did this when the
    // mobile reader was a scroll-stack; with every sheet stacked in the
    // same box, it fired all pages simultaneously on open.)
    pageBox.addEventListener("pointerenter", peelTease);
  }

  // alignToPeel (mobile): size the resting corner from the sheet in px so
  // its dotted crease lands exactly on the animated peel's crease. cqmin
  // doesn't convert 1:1 to the peel's px geometry, so measure + match,
  // and keep it matched across resize/rotate.
  if (alignToPeel) {
    const syncCornerToPeel = (): void => {
      const sheet = peelSheet();
      if (!sheet) return;
      // Measure the sheet's UNTRANSFORMED layout box (offsetWidth/Height),
      // NOT getBoundingClientRect — the rect bakes in ancestor transforms.
      // The CRT power-on entrance holds the wrapper at scaleY(0.005) for
      // the first ~500ms, so a getBoundingClientRect taken in the opening
      // rAF reported a ~0px-tall sheet and sized the corner to a degenerate
      // leg: no dotted crease, no hit area, so a tap on the corner fell
      // through to the sheet and fired the CTA (→ LP). Worse, the
      // ResizeObserver below watches layout size (transform-invariant), so
      // it never re-fired when the animation settled — only a real window
      // resize did, which is why the corner "appeared on resize". The wrap
      // is a px child of the sheet, so its leg belongs in the sheet's own
      // pre-transform space, which is exactly what offset metrics give; at
      // rest this equals the old rect value, so non-CRT creatives are
      // unchanged.
      const sw = sheet.offsetWidth;
      const sh = sheet.offsetHeight;
      if (!sw || !sh) return;
      const leg = Math.min(sw, sh) * PEEL_MAX * DOGEAR_PEEL_TRAVEL;
      wrap.style.width = `${leg}px`;
      wrap.style.height = `${leg}px`;
      pageClip = foldClipFor(`${leg}px`); // real-fold clip uses the same leg
      if (wrap.dataset.folded === "1") {
        pageBox.style.clipPath = pageClip;
        pageBox.style.setProperty("-webkit-clip-path", pageClip);
      }
    };
    requestAnimationFrame(syncCornerToPeel);
    if (typeof ResizeObserver === "function") {
      const ro = new ResizeObserver(syncCornerToPeel);
      requestAnimationFrame(() => { const s = peelSheet(); if (s) ro.observe(s); });
    }
  }

  if (initiallyFolded) applyFolded();

  wrap.addEventListener("click", (e) => {
    e.stopPropagation();
    const folded = wrap.dataset.folded === "1";
    const eventName = folded ? "dogear-unfold" : "dogear-fold";
    // Apply state is handled at the banner host so all pages stay in
    // sync — folding any one page bookmarks the whole creative, so
    // every page should mount/render in the same fold state. The host
    // listener calls applyFolded/applyUnfolded on every dog-ear it
    // built.
    //
    // Bubble + composed so the bootstrap (outside the shadow root)
    // also sees the event and persists the pin / POSTs /v1/dogear-event.
    host.dispatchEvent(new CustomEvent(eventName, {
      bubbles:  true,
      composed: true,
      detail:   { page: pageIndex },
    }));
  });

  return { wrap, pageIndex, applyFolded, applyUnfolded, peelTease, teasePlayed: false };
}

// ─── Brand logo overlay ────────────────────────────────────────────
//
// A single creative-wide logo rendered ON TOP of the layout in every
// mode and on every page, at the position carried by BannerConfig.logo
// (percent of the design box). Always object-fit:contain so it never
// crops. pointer-events:none so it doesn't block clicks/drags.
function applyLogo(container: HTMLElement, logo: BannerConfig["logo"]): HTMLImageElement | null {
  if (!logo || !logo.src) return null;
  const img = document.createElement("img");
  img.src = logo.src;
  img.alt = "";
  img.className = "pv-logo";
  Object.assign(img.style, {
    position: "absolute",
    left: `${logo.left}%`,
    top: `${logo.top}%`,
    width: `${logo.width}%`,
    height: `${logo.height}%`,
    objectFit: "contain",
    pointerEvents: "none",
    zIndex: "50",
  });
  container.appendChild(img);
  return img;
}

// ─── Video background ──────────────────────────────────────────────
//
// Full-bleed per-page video that renders behind every layout item in
// every mode (collapsed PC / mobile, all IAB sizes). Called from both
// render paths — pass the container that holds the layout items
// (.design-box in collapsed, stage in expanded) and the VideoBg spec.
// Returns the inserted <video> (or null) so callers can keep a handle
// if they want to wire additional behavior.
function applyVideoBg(container: HTMLElement, videoBg: VideoBg | undefined): HTMLVideoElement | null {
  if (!videoBg || !videoBg.src) return null;
  const fit: "cover" | "contain" = videoBg.fit ?? "cover";
  const opacity = videoBg.opacity ?? 1;
  const loop = videoBg.loop ?? true;
  const muted = videoBg.muted ?? true;
  const autoplay = videoBg.autoplay ?? true;

  const video = document.createElement("video");
  video.src = videoBg.src;
  if (videoBg.poster) video.poster = videoBg.poster;
  video.muted = muted;
  if (autoplay) video.autoplay = true;
  // Loop attribute only when no explicit trim window — trim-loop logic
  // below takes over in that case so the native loop doesn't fight it.
  const hasTrim = typeof videoBg.inSec === "number" || typeof videoBg.outSec === "number";
  if (loop && !hasTrim) video.loop = true;
  video.playsInline = true;
  // iOS requires the `playsinline` attribute in markup form, not just
  // the DOM property — set it explicitly so inline playback works
  // across browsers.
  video.setAttribute("playsinline", "");
  if (muted) video.setAttribute("muted", "");
  Object.assign(video.style, {
    position: "absolute",
    inset: "0",
    width: "100%",
    height: "100%",
    objectFit: fit,
    opacity: String(opacity),
    pointerEvents: "none",
    zIndex: "0",
  });

  if (hasTrim) {
    const inSec = typeof videoBg.inSec === "number" ? Math.max(0, videoBg.inSec) : 0;
    const outSec = typeof videoBg.outSec === "number" ? videoBg.outSec : undefined;
    video.addEventListener("loadedmetadata", () => {
      if (inSec > 0) {
        try { video.currentTime = inSec; } catch { /* ignore seek errors on unsupported codecs */ }
      }
    });
    if (outSec !== undefined) {
      video.addEventListener("timeupdate", () => {
        if (video.currentTime >= outSec) {
          if (loop) {
            try { video.currentTime = inSec; } catch { /* seek races can throw on some browsers */ }
            video.play().catch(() => { /* autoplay can be denied without user gesture */ });
          } else {
            video.pause();
          }
        }
      });
    }
  }

  // First child of the container so subsequent layout items stack on
  // top. Items use position:absolute without explicit z-index, so DOM
  // order decides the stack.
  container.insertBefore(video, container.firstChild);

  // Explicit play for autoplay=true browsers that gate on a play()
  // call (Safari sometimes refuses to start when only the attribute
  // is present). Swallow the promise rejection — if autoplay is denied
  // the poster still shows and the user gets something visible.
  if (autoplay) {
    video.play().catch(() => { /* autoplay policy may refuse without mute+gesture */ });
  }

  return video;
}

// ─── Texture background ────────────────────────────────────────────
//
// Full-bleed per-page image texture that renders behind every layout
// item in every mode. Mirrors applyVideoBg's call contract: pass the
// container that holds the layout items plus the TextureBg spec, and
// the video node (if any) so the texture stacks just above the video
// and the solid bg color but below the layout items. Returns the
// inserted layer (or null). Exported for unit tests.
export function applyTextureBg(
  container: HTMLElement,
  textureBg: TextureBg | undefined,
  afterNode: Node | null,
): HTMLElement | null {
  if (!textureBg || !textureBg.src) return null;
  const mode: "tile" | "cover" = textureBg.mode ?? "tile";
  const opacity = textureBg.opacity ?? 1;

  const layer = document.createElement("div");
  Object.assign(layer.style, {
    position: "absolute",
    inset: "0",
    // JSON.stringify quotes the URL so commas/parens/spaces in the CDN
    // path can't break out of the url() token.
    backgroundImage: `url(${JSON.stringify(textureBg.src)})`,
    opacity: String(opacity),
    pointerEvents: "none",
    zIndex: "0",
  });
  if (mode === "cover") {
    layer.style.backgroundSize = "cover";
    layer.style.backgroundRepeat = "no-repeat";
    // Focal point: which part of the image stays visible under the crop.
    const fx = Math.max(0, Math.min(100, textureBg.focusX ?? 50));
    const fy = Math.max(0, Math.min(100, textureBg.focusY ?? 50));
    layer.style.backgroundPosition = `${fx}% ${fy}%`;
  } else {
    layer.style.backgroundRepeat = "repeat";
    // Fixed px tile size keeps the pattern's physical scale constant as
    // the fluid creative grows (it just tiles more); "auto" = natural.
    layer.style.backgroundSize =
      typeof textureBg.scale === "number" && textureBg.scale > 0
        ? `${textureBg.scale}px`
        : "auto";
  }
  if (textureBg.blend && textureBg.blend !== "normal") {
    layer.style.mixBlendMode = textureBg.blend;
  }

  // Stack just above the video (afterNode), below the layout items.
  // Both video and items sit at the z-index:0 level, so DOM order
  // decides the paint order (see applyVideoBg's note).
  if (afterNode && afterNode.parentNode === container) {
    container.insertBefore(layer, afterNode.nextSibling);
  } else {
    container.insertBefore(layer, container.firstChild);
  }
  return layer;
}
