// Promovolve ad bootstrap — the publisher-facing integration primitive.
//
// Publishers embed one script + a container div per slot:
//
//   <script async src="https://cdn.promovolve.com/js/promovolve-bootstrap.<hash>.js"></script>
//   <div data-promovolve-slot="leader-top" data-w="970" data-h="250"></div>
//   <div data-promovolve-slot="rect-inline" data-w="300" data-h="250"></div>
//
// The bootstrap, once loaded, mounts a GPT-style command queue on
// window.__promovolve__ (so cmd.push calls made before the script
// loaded still execute in-order). Programmatic usage:
//
//   window.__promovolve__ = window.__promovolve__ || { cmd: [] };
//   __promovolve__.cmd.push(() => {
//     promovolve.setConfig({ pub: 'siteA' });
//     promovolve.defineSlot('leader-top', [970, 250]);
//     promovolve.defineSlot('rect-inline', [300, 250]);
//     promovolve.display();
//   });
//
// If the publisher doesn't call defineSlot explicitly, the bootstrap
// auto-discovers `[data-promovolve-slot]` divs on DOMContentLoaded
// and fires display() for them. This is the zero-config path — just
// drop the script tag + place divs.
//
// Behaviour:
//   1. Collect slots.
//   2. POST one /v1/serve/batch request covering all slots.
//   3. For each winner: lazy-load the banner web component (via the
//      URL in the response), instantiate <expandable-magazine-banner>
//      with pages + slot width/height (from publisher's data-w / data-h)
//      attributes, inject into the slot div.
//   4. collapseEmptyDivs (default on): slots with no winner (batch
//      returned null, batch errored, or batch timed out) get
//      display:none so they don't leave reserved space.

import {
  clearCounted, clearPin, clearPinsByCreativeIds, getAllPins, markCounted, pinExpiresAt, setPin,
  touchPin, wasCounted,
  type Pin,
} from "./dogear-storage.js";
import { attachLpPrefetch } from "./lp-prefetch.js";

// ─── Types mirroring server-side BatchServeReq / BatchServeRes ────

interface BatchImp {
  id: string;
  w: number;
  h: number;
  floorCpm?: number;
}

interface PinHint {
  slotId: string;
  creativeId: string;
}

interface BatchServeReq {
  pub: string;
  url: string;
  imp: BatchImp[];  pins?: PinHint[];
}

interface DogearInfo {
  honored: boolean;
  reason?: string;
}

interface ServeRes {
  assetUrl: string;
  mime: string;
  clickUrl: string;
  impUrl: string;
  ctaUrl: string;
  creativeId: string;
  version: number;
  landingUrl: string;
  pagesJson?: string;
  bannerScriptUrl?: string;
  // Banner-level config blob (animation, expandDurationMs, etc.).
  // Forwarded to the banner element verbatim via the `config`
  // attribute. Absent = banner uses hardcoded defaults.
  bannerConfigJson?: string;
  // Dog-ear feature fields. canFold/honorPin are independent: a
  // campaign that exhausted CPF budget has canFold=false honorPin=true.
  canFold?: boolean;
  honorPin?: boolean;
  foldToken?: string;
  dogear?: DogearInfo;
  // Campaign endAt as epoch millis — bootstrap caps the pin's
  // expiresAt at this value. Absent = open-ended campaign, fall back
  // to the bootstrap's default 7-day TTL.
  pinExpiresAt?: number;
}

interface BatchImpResult {
  id: string;
  winner: ServeRes | null;
  // Slot-level dogear info — surfaced even when winner is null so the
  // client can clear stale IDB pins (reason="creative_removed") on
  // unfilled slots that didn't go through renderWinner.
  dogear?: DogearInfo;
}

interface BatchServeRes {
  seatbid: BatchImpResult[];
  // creativeIds of OFF-PAGE pin hints the server determined can never
  // be reconciled by the per-slot dogear channel: the creative no
  // longer exists, or the pinned slotId is gone from the site's slot
  // config (renamed/redesigned). Without this, such a pin rides along
  // forever, excluding its campaign in this browser with no recovery
  // path. Absent/empty = nothing to clean.
  stalePins?: string[];
  // Legacy cold flag (= reclassifyInMs <= 0). Kept for back-compat.
  needText?: boolean;
  // Freshness token: ms until this page's classification should be refreshed.
  // <= 0 → extract live-page text and POST /v1/classify-page (cold OR stale);
  // > 0 → fresh, do nothing. Preferred over needText. See
  // docs/design/ON_DEMAND_CLASSIFICATION.md.
  reclassifyInMs?: number;
}

// ─── Public API types ────────────────────────────────────────────

interface PromovolveConfig {
  pub: string;  // Origin of the API. Defaults to the page's own hostname on port
  // 8080 (mirrors legacy promovolve-ad.js for dev). Set explicitly via
  // setConfig({apiBase: "https://api.example.com"}) or `data-api` on
  // the script tag for prod.
  apiBase?: string;
  batchEndpoint?: string;     // default `${apiBase}/v1/serve/batch`
  dogearEventEndpoint?: string; // default `${apiBase}/v1/dogear-event`
  batchTimeoutMs?: number;    // default 1000
  collapseEmptyDivs?: boolean;   // default true
}

interface Slot {
  id: string;
  w: number;
  h: number;
}

type CmdFn = () => void;

interface PromovolveApi {
  setConfig(cfg: Partial<PromovolveConfig>): void;
  defineSlot(id: string, size: [number, number]): void;
  display(): Promise<void>;
}

interface Cmd { cmd: CmdFn[]; }

declare global {
  interface Window {
    __promovolve__?: Cmd & Partial<PromovolveApi>;
    promovolve?: PromovolveApi;
  }
}

// ─── Internal state ──────────────────────────────────────────────

const config: PromovolveConfig = {
  pub: "",
  // apiBase resolved at install time (see install IIFE) to either the
  // script tag's data-api attribute or the page hostname on port 8080.
  // The endpoint URLs are computed lazily so apiBase changes after
  // setConfig() take effect on the next request.
  apiBase: "",
  batchTimeoutMs: 1000,
  // Leave the slot's placeholder visible when an auction returns no
  // winner. Matches the legacy promovolve-ad.js behavior — publishers
  // see their dashed-border placeholder for unfilled slots, which is
  // helpful in dev (visual confirmation that the slot was discovered)
  // and conventional in prod (page layout doesn't reflow between
  // fills and skips).
  collapseEmptyDivs: false,
};

function batchEndpoint(): string {
  return config.batchEndpoint ?? `${config.apiBase}/v1/serve/batch`;
}
function dogearEventEndpoint(): string {
  return config.dogearEventEndpoint ?? `${config.apiBase}/v1/dogear-event`;
}

const slots = new Map<string, Slot>();
let displayed = false;

// Memoise banner-script loading. Each distinct URL → one promise that
// resolves when customElements.get("expandable-magazine-banner") is
// defined. Concurrent winners share the same load.
const bannerScriptLoads = new Map<string, Promise<void>>();

// ─── Helpers ─────────────────────────────────────────────────────

function findContainer(slotId: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(
    `[data-promovolve-slot="${cssEscape(slotId)}"]`,
  );
}

function cssEscape(s: string): string {
  return (window.CSS?.escape ?? ((x: string) => x.replace(/[^\w-]/g, "\\$&")))(s);
}

function collectDomSlots(): void {
  const els = document.querySelectorAll<HTMLElement>("[data-promovolve-slot]");
  els.forEach((el) => {
    const id = el.dataset.promovolveSlot;
    if (!id) return;
    if (slots.has(id)) return;
    const w = parseInt(el.dataset.w ?? "0", 10);
    const h = parseInt(el.dataset.h ?? "0", 10);
    if (!(w > 0 && h > 0)) {
      console.warn("[promovolve] slot %s missing data-w / data-h; skipping", id);
      return;
    }
    slots.set(id, { id, w, h });
  });
}

function loadBannerScript(url: string): Promise<void> {
  if (customElements.get("expandable-magazine-banner")) return Promise.resolve();
  const cached = bannerScriptLoads.get(url);
  if (cached) return cached;
  const promise = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(
      `script[src="${url}"]`,
    );
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", reject);
      return;
    }
    const s = document.createElement("script");
    s.src = url;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = reject;
    document.head.appendChild(s);
  });
  bannerScriptLoads.set(url, promise);
  return promise;
}

/** Append `&dogeared=1` to the impression URL when the server honored
 *  this slot's pin. Re-encounter telemetry only — not billed. Uses a
 *  string concat (`?` vs `&`) instead of URL parsing because the imp
 *  URL is server-built and known to already carry query params.
 */
function withDogearedFlag(impUrl: string): string {
  if (impUrl.indexOf("dogeared=") >= 0) return impUrl;
  const sep = impUrl.indexOf("?") >= 0 ? "&" : "?";
  return `${impUrl}${sep}dogeared=1`;
}

/** Process the dogear field on a serve response. Returns the (possibly
 *  modified) impression URL and the pinned-page index to forward to
 *  the banner — null if there was no pin or if the pin was cleared.
 *
 *  Side effect: clears the IndexedDB pin if the response signals the
 *  creative is no longer servable (`reason === "creative_removed"`,
 *  per the v1 server-side simplification). Other reasons retain the
 *  pin (campaign may resume).
 */
async function processDogearResponse(
  slot: Slot,
  winner: ServeRes,
  pin: Pin | undefined,
): Promise<{ impUrl: string; clickUrl: string; ctaUrl: string; pinnedPage: number | null }> {
  let impUrl = winner.impUrl;
  let clickUrl = winner.clickUrl;
  let ctaUrl = winner.ctaUrl;
  let pinnedPage: number | null = null;

  const dogear = winner.dogear;
  if (dogear) {
    if (dogear.honored) {
      // Pin honored — flag every beacon URL as a re-encounter so the
      // server suppresses metrics for this serve (the user already
      // saw the creative when they folded it; this is bookmark
      // fulfillment, not a billable re-impression).
      impUrl   = withDogearedFlag(impUrl);
      clickUrl = withDogearedFlag(clickUrl);
      ctaUrl   = withDogearedFlag(ctaUrl);
      if (pin) pinnedPage = pin.page;
      // Reader visited the pinned creative again — bump lastSeenAt so
      // the idle-sweep timer resets. Without this, a reader who keeps
      // re-encountering the pin would still lose it after 24h because
      // the field never refreshes.
      void touchPin(slot.id);
    } else if (dogear.reason === "creative_removed") {
      // Server signal: the creative behind this pin is no longer
      // servable. Clear the pin client-side so subsequent serves don't
      // ride along with a stale hint. Also clear the ts_counted record
      // so a future resurrected campaign with the same creativeId
      // gets a fresh fold count instead of being silently dedup'd.
      // Other reasons (campaign_inactive, budget_exhausted,
      // dogear_disabled) leave the pin alone — the campaign may resume.
      await clearPin(slot.id);
      if (pin) await clearCounted(pin.creativeId);
    }
  }

  return { impUrl, clickUrl, ctaUrl, pinnedPage };
}

/** Listen for dogear-fold / dogear-unfold custom events on the banner
 *  and persist them: setPin/clearPin against IndexedDB and POST to
 *  /v1/dogear-event. The banner is presentation-only — all storage
 *  and network I/O lives here so the banner stays a pure component.
 */
function attachDogearListeners(
  banner: HTMLElement,
  slot: Slot,
  winner: ServeRes,
): void {
  banner.addEventListener("dogear-fold", (raw) => {
    const e = raw as CustomEvent<{ page?: number }>;
    const page = typeof e.detail?.page === "number" ? e.detail.page : 0;
    const foldedAt = Date.now();
    const expiresAt = pinExpiresAt(foldedAt, winner.pinExpiresAt);
    void (async () => {
      // Await the IDB commit before doing anything else — fire-and-forget
      // setPin used to race with rapid reloads, so the next page load
      // could see an empty pins store and miss the user's bookmark.
      await setPin({
        slotId:     slot.id,
        creativeId: winner.creativeId,
        page,
        foldedAt,
        expiresAt,
        // Same instant as foldedAt — the fold itself counts as a
        // visit, giving the reader a full IDLE_WINDOW_MS grace
        // period before the use-it-or-lose-it sweep can clear it.
        lastSeenAt: foldedAt,
      });
      // Dedup gate: refolds within the pin window are the same intent,
      // not new fold signals. Skip the network round-trip and the
      // server-side posterior bump if we've already told the server
      // about this creative within the dedup window.
      if (await wasCounted(winner.creativeId)) return;
      await postDogearEvent({
        pub:        config.pub,
        url:        window.location.href,
        creativeId: winner.creativeId,
        slotId:     slot.id,
        event:      "fold",
        foldToken:  winner.foldToken,
        page,
      });
      await markCounted(winner.creativeId, expiresAt);
    })();
  });
  banner.addEventListener("dogear-unfold", () => {
    // No /v1/dogear-event POST — unfolds are deliberately silent
    // server-side ("don't accept unfold"). Clear the local pin so the
    // next page load auctions normally instead of re-honoring the pin.
    // We intentionally do NOT clearCounted: keeping the dedup record
    // means a refold inside the same window stays silent and doesn't
    // cycle the fold posterior.
    void clearPin(slot.id);
  });
}

interface DogearEventBody {
  pub: string;
  url: string;
  creativeId: string;
  slotId: string;
  event: "fold" | "unfold";
  foldToken?: string;
  page?: number;
}

/** Fire-and-forget POST to /v1/dogear-event. fetch with keepalive is
 *  the primary transport — it handles CORS preflight cleanly and
 *  survives page unload (the keepalive flag keeps the request alive
 *  through navigation). sendBeacon is the fallback for browsers
 *  without keepalive support; some browsers block sendBeacon for
 *  application/json bodies cross-origin, so it can't be the primary.
 *  No credentials, no identifying headers — per spec design principle 2.
 */
function postDogearEvent(body: DogearEventBody): void {
  const url = dogearEventEndpoint();
  const payload = JSON.stringify(body);
  try {
    void fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: payload,
      keepalive: true,
      credentials: "omit",
    });
    return;
  } catch {
    // fall through to sendBeacon
  }
  if (typeof navigator !== "undefined" && typeof navigator.sendBeacon === "function") {
    try {
      const blob = new Blob([payload], { type: "application/json" });
      navigator.sendBeacon(url, blob);
    } catch {
      // Best-effort. If both transports fail, the local pin still stands;
      // we just lose the billing event for this fold (per spec — degraded
      // mode is acceptable).
    }
  }
}

async function renderWinner(slot: Slot, winner: ServeRes, pin?: Pin): Promise<void> {
  const host = findContainer(slot.id);
  if (!host) {
    console.warn("[promovolve] slot %s has no container div; skipping", slot.id);
    return;
  }
  if (!winner.bannerScriptUrl) {
    console.warn("[promovolve] winner missing bannerScriptUrl; skipping");
    return;
  }
  const { impUrl, clickUrl, ctaUrl, pinnedPage } = await processDogearResponse(slot, winner, pin);
  // Every winner on this platform is a magazine creative authored in
  // the designer. Lazy-load the web component bundle from the URL the
  // server returned (per-creative content-hashed URL, cache-safe to
  // fetch across publishers).
  try {
    await loadBannerScript(winner.bannerScriptUrl);
  } catch (e) {
    console.error("[promovolve] banner script load failed", e);
    return;
  }
  const banner = document.createElement("expandable-magazine-banner");
  banner.setAttribute("pages", winner.pagesJson ?? "[]");
  // Slot pixel dim comes from the publisher's `data-w`/`data-h` attrs
  // (read at slot discovery, stored on `slot`). Server-side ServeRes
  // no longer carries render dimensions — the slot is authoritative.
  banner.setAttribute("width", String(slot.w));
  banner.setAttribute("height", String(slot.h));
  banner.setAttribute("click-url", clickUrl);
  banner.setAttribute("imp-url", impUrl);
  banner.setAttribute("cta-url", ctaUrl);
  banner.setAttribute("landing-url", winner.landingUrl);
  // Pass through banner-level config when the serve response
  // included one. Absence = banner uses its default config.
  if (winner.bannerConfigJson) {
    banner.setAttribute("config", winner.bannerConfigJson);
  }
  // Dog-ear: every winner here gets the corner wired (the server
  // mints a fold token unconditionally — see ServeRoutes.scala). The
  // attribute drives banner.ts gating; the listeners persist pins +
  // fire /v1/dogear-event when the reader folds.
  if (winner.canFold && winner.foldToken) {
    banner.setAttribute("data-can-fold", "true");
    banner.setAttribute("data-fold-token", winner.foldToken);
    banner.setAttribute("data-creative-id", winner.creativeId);
    banner.setAttribute("data-slot-id", slot.id);
    attachDogearListeners(banner, slot, winner);
  } else {
    // Defensive — server didn't return a fold token (publisher secret
    // missing, signing failure). Hide the corner rather than render an
    // unbillable affordance.
    banner.setAttribute("data-can-fold", "false");
  }
  if (pinnedPage !== null) {
    banner.setAttribute("data-pinned-page", String(pinnedPage));
  }
  // Wire LP prefetch / preconnect / prerender to the banner's
  // lifecycle events before mount so the listeners are live by the
  // time `magazine-expand` fires from `_expand()` post-renderOverlay.
  attachLpPrefetch(banner);
  host.replaceChildren(banner);
}

function collapseEmpty(slot: Slot): void {
  if (!config.collapseEmptyDivs) return;
  const host = findContainer(slot.id);
  if (host) host.style.display = "none";
}

async function runBatch(
  slotsToServe: Slot[],
  pinHints: PinHint[],
): Promise<{ results: Map<string, BatchImpResult>; stalePins: string[]; needClassify: boolean }> {
  const body: BatchServeReq = {
    pub: config.pub,
    url: window.location.href,
    imp: slotsToServe.map((s) => ({ id: s.id, w: s.w, h: s.h })),
    pins: pinHints.length > 0 ? pinHints : undefined,
  };
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), config.batchTimeoutMs);
  try {
    const resp = await fetch(batchEndpoint(), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!resp.ok) {
      console.warn("[promovolve] batch request failed: %d", resp.status);
      return { results: new Map(), stalePins: [], needClassify: false };
    }
    const data = (await resp.json()) as BatchServeRes;
    const out = new Map<string, BatchImpResult>();
    for (const entry of data.seatbid) out.set(entry.id, entry);
    // Prefer the freshness token (covers cold AND stale); fall back to the
    // legacy needText flag if an older server omits reclassifyInMs.
    const needClassify =
      data.reclassifyInMs !== undefined ? data.reclassifyInMs <= 0 : data.needText === true;
    return { results: out, stalePins: data.stalePins ?? [], needClassify };
  } catch (e) {
    console.warn("[promovolve] batch request error", e);
    return { results: new Map(), stalePins: [], needClassify: false };
  } finally {
    window.clearTimeout(timeout);
  }
}

// ─── On-demand classification (cold pages) ───────────────────────────
// When the serve response carries needText=true the page has never been
// classified. We extract the live-page text here (the crawl-free text
// source) and POST it to /v1/classify-page so the next serve fills. This
// runs ONLY on a cold miss, off the critical path (after the serve reply),
// and never blocks the ad render. See docs/design/ON_DEMAND_CLASSIFICATION.md.

// Matches Gemini's MaxContentLength (IABTaxonomy.scala) — extra text is
// dropped server-side anyway, so bound the walk here.
const CLASSIFY_TEXT_MAX = 8000;

function classifyPageEndpoint(): string {
  return `${config.apiBase}/v1/classify-page`;
}

// Bounded DOM text walk. Unlike the crawler's extractContent (which
// collects the full document + links for link discovery), this stops once
// it has enough text for classification, so even a huge homepage DOM costs
// only a few ms. Skips script/style/noscript; trims whitespace.
function extractPageText(): string {
  if (!document.body) return "";
  const parts: string[] = [];
  let len = 0;
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
    acceptNode(node: Node): number {
      const parent = node.parentElement;
      if (!parent) return NodeFilter.FILTER_REJECT;
      const tag = parent.tagName;
      if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT") {
        return NodeFilter.FILTER_REJECT;
      }
      return node.textContent && node.textContent.trim()
        ? NodeFilter.FILTER_ACCEPT
        : NodeFilter.FILTER_REJECT;
    },
  });
  while (len < CLASSIFY_TEXT_MAX) {
    const n = walker.nextNode();
    if (!n) break;
    const t = n.textContent!.trim();
    if (t) {
      parts.push(t);
      len += t.length + 1;
    }
  }
  return parts.join(" ").slice(0, CLASSIFY_TEXT_MAX);
}

// ── Slot quality signals (crawl-free prior) ──────────────────────────
// Mirrors crawler.js extractSlots: measures each slot's rendered position so
// the server can rebuild a SlotPrior (above-fold / viewability / region / text
// density → qualityScore → ±50% floor scaling) WITHOUT a crawl. Measured in the
// visitor's real viewport, so it's better data than the headless crawler had.
interface SlotSignals {
  aboveFold: boolean;
  viewability: number;
  region: string;
  textDensity: number;
}

function detectRegion(
  el: HTMLElement,
  rect: DOMRect,
  yTopAbs: number,
  docH: number,
  vw: number,
): string {
  for (let n: HTMLElement | null = el; n; n = n.parentElement) {
    const tag = n.tagName ? n.tagName.toLowerCase() : "";
    if (
      tag === "article" || tag === "main" || tag === "aside" ||
      tag === "footer" || tag === "header" || tag === "nav"
    ) return tag;
    const role = (n.getAttribute?.("role") ?? "").toLowerCase();
    if (role === "main" || role === "article") return "main";
    if (role === "complementary") return "aside";
    if (role === "contentinfo") return "footer";
    if (role === "banner") return "header";
    if (role === "navigation") return "nav";
  }
  const yFrac = docH > 0 ? yTopAbs / docH : 0;
  if (yFrac < 0.15) return "header";
  if (yFrac > 0.85) return "footer";
  const slotW = rect.width || 0;
  if (vw > 0 && slotW > 0 && slotW < vw * 0.4) {
    const cx = rect.left + slotW / 2;
    const offCenter = Math.abs(cx - vw / 2) / (vw / 2);
    if (offCenter > 0.4) return "aside";
  }
  return "unknown";
}

function textDensityNearby(el: HTMLElement): number {
  let n: HTMLElement | null = el.parentElement;
  let depth = 0;
  while (n && depth < 4) {
    const t = (n.innerText || "").replace(/\s+/g, " ").trim();
    if (t.length > 200) return Math.min(1, t.length / 1500);
    n = n.parentElement;
    depth += 1;
  }
  return 0;
}

function measureSlotSignals(slotId: string): SlotSignals {
  const el = findContainer(slotId);
  if (!el) return { aboveFold: false, viewability: 0, region: "unknown", textDensity: 0 };
  const vh = window.innerHeight || 0;
  const vw = window.innerWidth || 0;
  const docH = Math.max(
    document.documentElement.scrollHeight || 0,
    document.body ? document.body.scrollHeight : 0,
    1,
  );
  const rect = el.getBoundingClientRect();
  const yTop = Math.round(rect.top + (window.scrollY || 0));
  const aboveFold = vh > 0 && rect.top < vh && rect.bottom > 0;
  let visibleH = 0;
  let visibleW = 0;
  if (vh > 0 && vw > 0) {
    visibleH = Math.max(0, Math.min(rect.bottom, vh) - Math.max(rect.top, 0));
    visibleW = Math.max(0, Math.min(rect.right, vw) - Math.max(rect.left, 0));
  }
  const slotArea = (rect.width || el.offsetWidth) * (rect.height || el.offsetHeight);
  let viewability = slotArea > 0 ? (visibleH * visibleW) / slotArea : 0;
  viewability = Math.max(0, Math.min(1, viewability));
  return {
    aboveFold,
    viewability,
    region: detectRegion(el, rect, yTop, docH, vw),
    textDensity: textDensityNearby(el),
  };
}

// Fire-and-forget. keepalive lets it survive a navigation. Single-flighted
// server-side, so concurrent first-visitors collapse to one Gemini call.
function postClassifyPage(slotsToServe: Slot[]): void {
  const text = extractPageText();
  if (!text) return;
  const body = {
    pub: config.pub,
    url: window.location.href,
    text,
    imp: slotsToServe.map((s) => {
      const sig = measureSlotSignals(s.id);
      return {
        id: s.id,
        w: s.w,
        h: s.h,
        aboveFold: sig.aboveFold,
        viewability: sig.viewability,
        region: sig.region,
        textDensity: sig.textDensity,
      };
    }),
  };
  try {
    void fetch(classifyPageEndpoint(), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      keepalive: true,
    });
  } catch (e) {
    console.warn("[promovolve] classify-page error", e);
  }
}

// Run extraction when the browser is idle so a large DOM can't jank a
// frame; fall back to a macrotask where requestIdleCallback is absent.
function scheduleClassify(slotsToServe: Slot[]): void {
  const run = (): void => postClassifyPage(slotsToServe);
  const ric = (window as unknown as {
    requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => void;
  }).requestIdleCallback;
  if (typeof ric === "function") ric(run, { timeout: 2000 });
  else window.setTimeout(run, 0);
}

async function displayImpl(): Promise<void> {
  if (!config.pub) {
    console.warn("[promovolve] setConfig({ pub: ... }) must be called before display()");
    return;
  }
  displayed = true;

  // Zero-config path: publishers who only place divs and never call
  // defineSlot get auto-discovery on first display.
  if (slots.size === 0) collectDomSlots();

  const allSlots = Array.from(slots.values());
  if (allSlots.length === 0) return;

  // Read EVERY pin from IDB — not just pins matching slots on this
  // page. Pins whose slotId is on the current page are honored as
  // before; pins for slots not on this page become "exclude this
  // creative globally" hints, so the user never encounters their
  // pinned creative in some random slot on a different page (which
  // would defeat the user's "save for later" intent). Acts as the
  // expiry sweep at the same time — getAllPins drops expired records
  // in the same read pass.
  const allPins = await getAllPins();
  const slotIds = new Set(allSlots.map((s) => s.id));
  const pinsBySlot = new Map<string, Pin>();
  for (const p of allPins) {
    if (slotIds.has(p.slotId)) pinsBySlot.set(p.slotId, p);
  }
  const pinHints: PinHint[] = allPins.map((p) => ({
    slotId:     p.slotId,
    creativeId: p.creativeId,
  }));

  const { results: batchResults, stalePins, needClassify } = await runBatch(allSlots, pinHints);

  // Page needs (re)classification — cold (never classified) or stale (token
  // expired). Extract the live-page text and POST it so the next serve fills/
  // refreshes. Off the critical path — does not block rendering below.
  if (needClassify) scheduleClassify(allSlots);

  // Server-reported stale OFF-PAGE pins: their creative is gone or
  // their slot no longer exists on the site, so the per-slot dogear
  // channel below can never reconcile them. Delete pin + ts_counted.
  if (stalePins.length > 0) void clearPinsByCreativeIds(stalePins);

  const renderTasks: Array<Promise<void>> = [];

  for (const slot of allSlots) {
    const result = batchResults.get(slot.id);
    // Slot-level dogear cleanup runs even when there's no winner — the
    // server only emits creative_removed when the creative is actually
    // gone from the repo (paused/deleted), so it's safe to clear without
    // worrying about transient ServeView misses.
    if (result?.dogear && result.dogear.honored === false &&
        result.dogear.reason === "creative_removed") {
      void clearPin(slot.id);
      const stalePin = pinsBySlot.get(slot.id);
      if (stalePin) void clearCounted(stalePin.creativeId);
    }
    if (result?.winner) {
      renderTasks.push(renderWinner(slot, result.winner, pinsBySlot.get(slot.id)));
    } else {
      // Slot was in the response with no winner, batch errored, or batch
      // returned nothing — collapse uniformly.
      collapseEmpty(slot);
    }
  }
  await Promise.all(renderTasks);
}

// ─── Public API ──────────────────────────────────────────────────

const api: PromovolveApi = {
  setConfig(cfg) {
    Object.assign(config, cfg);
  },
  defineSlot(id, [w, h]) {
    slots.set(id, { id, w, h });
  },
  display() {
    return displayImpl();
  },
};

// ─── Command queue bootstrap (GPT-style) ────────────────────────

(function install(): void {
  // Capture the script tag NOW — document.currentScript is only valid
  // during synchronous script execution. Reading it later (inside
  // autoDisplay, which fires after DOMContentLoaded) returns null.
  const installScript = document.currentScript as HTMLScriptElement | null;

  // Resolve apiBase: explicit data-api on the script tag wins; else
  // derive from the page's own hostname on port 8080. Mirrors the
  // legacy promovolve-ad.js behavior so dev publisher pages on port
  // 8888 (or any non-8080 port) talk to the API on port 8080
  // automatically. Production sets data-api explicitly.
  if (!config.apiBase) {
    const fromAttr = installScript?.dataset.api;
    if (fromAttr) {
      config.apiBase = fromAttr.replace(/\/$/, "");
    } else {
      const proto = location.protocol;
      const host = location.hostname;
      const port = location.port === "8080" ? "" : ":8080";
      config.apiBase = `${proto}//${host}${port}`;
    }
  }

  const existing = window.__promovolve__;
  const queue: CmdFn[] = Array.isArray(existing?.cmd) ? existing!.cmd : [];

  // Replace the plain array with an object whose push immediately
  // invokes the callback. Any callbacks pushed BEFORE the bootstrap
  // loaded still drain below.
  const cmd: CmdFn[] = [];
  const api2: PromovolveApi & Cmd = Object.assign({ cmd }, api);
  // Monkey-patch push so callbacks pushed after load execute synchronously.
  cmd.push = ((...fns: CmdFn[]): number => {
    for (const fn of fns) {
      try { fn(); } catch (e) { console.error("[promovolve] cmd error", e); }
    }
    return cmd.length;
  }) as typeof Array.prototype.push;

  window.promovolve = api;
  window.__promovolve__ = api2;

  // Drain any pre-load cmd calls now that the API is live.
  while (queue.length > 0) {
    const fn = queue.shift();
    if (fn) {
      try { fn(); } catch (e) { console.error("[promovolve] queued cmd error", e); }
    }
  }

  // Zero-config auto-display: if no one has called display() by the
  // time DOMContentLoaded fires, scan the DOM and fire for any
  // [data-promovolve-slot] elements we find.
  const autoDisplay = (): void => {
    if (displayed) return;
    // Publisher may have set pub via data-pub on the script tag.
    // installScript was captured during the IIFE's synchronous execution
    // (document.currentScript is null inside this deferred callback).
    const pubFromScript = installScript?.dataset.pub;
    if (pubFromScript && !config.pub) config.pub = pubFromScript;
    if (!config.pub) return;
    collectDomSlots();
    if (slots.size > 0) void displayImpl();
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", autoDisplay, { once: true });
  } else {
    // DOM already parsed; microtask so the calling script's rest runs first.
    queueMicrotask(autoDisplay);
  }
})();

export {};
