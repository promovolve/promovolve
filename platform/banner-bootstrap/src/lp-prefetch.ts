// Layered LP prefetch / prerender driven by banner lifecycle events.
//
// When the spread overlay opens the reader has committed to 15-30s of
// reading; that's free time we can spend warming the advertiser's
// landing page so the eventual CTA tap doesn't dump them into a 3-10s
// white-screen wait. Three layers, each gated on a stronger intent
// signal:
//
//   Layer 1 — `magazine-expand`: dns-prefetch + preconnect on the LP
//             origin. Very cheap (a single DNS + TCP+TLS warm-up); a
//             reader who closes immediately costs us nothing material.
//   Layer 2 — `magazine-expand`: `<link rel=prefetch as=document>` on
//             the exact CTA URL. Browser fetches the HTML in the
//             background so a tap mid-read paints from cache.
//   Layer 3 — `magazine-page-changed` reaching last page: Speculation
//             Rules `prerender`. Heavier than prefetch (executes the
//             LP's JS), gated on strong tap-intent. Chromium-only;
//             feature-detected.
//
//             ⚠️  As of Chromium 147 (May 2026), Speculation Rules
//             `prerender` is restricted to *same-site* targets only.
//             Advertiser LPs are by definition cross-site from the
//             publisher, so in practice Layer 3 fails with reason
//             "cross-site prerender not supported" on every real LP
//             we'd want to warm. Tracker: crbug.com/1176054. We keep
//             the layer in place because (a) the code is correct and
//             will activate automatically when Chromium ships cross-
//             site support, (b) it gracefully no-ops on failure, and
//             (c) Layer 2 already gives a measured ~50-300x TTFB
//             speedup at the cache-hit point, which is the realistic
//             ceiling until cross-site prerender lands.
//
// All three clean up on `magazine-collapse`. By that time any CTA tap
// has already issued its window.open, so the new tab keeps whatever
// the prefetch/prerender warmed up; removing the <link>/<script> from
// <head> only stops *future* speculative work the reader is no longer
// going to do.
//
// Privacy: client-side only, no new identifiers. Prerender (when it
// becomes available) executes the advertiser's analytics — a known
// platform-level tradeoff that the Speculation Rules API is designed
// for. See spec.

interface MagazineExpandDetail {
  ctaUrl: string;
  pageIndex: number;
  totalPages: number;
}

interface MagazinePageChangedDetail {
  pageIndex: number;
  totalPages: number;
}

interface PrefetchSession {
  ctaUrl: string;
  origin: string;
  // Hints we injected — held so cleanup on collapse can remove them
  // without affecting hints another concurrent overlay (multi-slot
  // page) may have added on the same origin.
  injected: HTMLElement[];
  prerendered: boolean;
}

function safeMark(name: string): void {
  if (typeof performance !== "undefined" && performance.mark) {
    try { performance.mark(name); } catch { /* ignore */ }
  }
}

function extractOrigin(url: string): string | null {
  try {
    const u = new URL(url, window.location.href);
    // Skip same-origin (publisher) URLs — preconnect to ourselves is
    // pointless and prefetch would duplicate the publisher page.
    if (u.origin === window.location.origin) return null;
    // http(s) only; data:/blob:/javascript: have no origin to warm.
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    return u.origin;
  } catch {
    return null;
  }
}

function hasExistingHint(rel: string, href: string): boolean {
  return document.head.querySelector(
    `link[rel="${rel}"][href="${href}"]`,
  ) !== null;
}

function injectLink(rel: string, href: string, attrs: Record<string, string> = {}): HTMLLinkElement | null {
  if (hasExistingHint(rel, href)) return null;
  const link = document.createElement("link");
  link.rel  = rel;
  link.href = href;
  for (const [k, v] of Object.entries(attrs)) link.setAttribute(k, v);
  document.head.appendChild(link);
  return link;
}

function supportsSpeculationRules(): boolean {
  // Chromium 121+. Treat any unsupported environment (Safari, Firefox,
  // older Chromium) as feature-absent — never throw, never inject.
  const Script = (typeof HTMLScriptElement !== "undefined") ? HTMLScriptElement : null;
  if (!Script) return false;
  const supports = (Script as unknown as { supports?: (type: string) => boolean }).supports;
  if (typeof supports !== "function") return false;
  try { return supports.call(Script, "speculationrules"); }
  catch { return false; }
}

function injectSpeculationRules(ctaUrl: string): HTMLScriptElement | null {
  // One-shot per session: bail if a speculationrules script for the
  // same URL is already in <head>. Different URL would mean the spread
  // changed mid-session, which can't happen — but be defensive.
  const existing = document.head.querySelectorAll<HTMLScriptElement>(
    'script[type="speculationrules"][data-pv-prerender]',
  );
  for (const s of existing) {
    if (s.dataset.pvPrerenderUrl === ctaUrl) return null;
  }
  const script = document.createElement("script");
  script.type  = "speculationrules";
  script.dataset.pvPrerender    = "1";
  script.dataset.pvPrerenderUrl = ctaUrl;
  script.textContent = JSON.stringify({
    prerender: [{ urls: [ctaUrl] }],
  });
  document.head.appendChild(script);
  return script;
}

/** Wire prefetch lifecycle to a freshly mounted banner element. Call
  * once per banner mount; events bubble to the host so the listeners
  * stay attached for the banner's lifetime. Idempotent across
  * collapse/re-expand cycles via the per-session state.
  */
export function attachLpPrefetch(banner: HTMLElement): void {
  let session: PrefetchSession | null = null;

  const onExpand = (raw: Event): void => {
    const e = raw as CustomEvent<MagazineExpandDetail>;
    const ctaUrl = e.detail?.ctaUrl ?? "";
    if (!ctaUrl) return;
    const origin = extractOrigin(ctaUrl);
    if (!origin) return;
    // Build session lazily — first expand of this banner mount.
    session = { ctaUrl, origin, injected: [], prerendered: false };

    // Layer 1: DNS warm-up + TCP/TLS handshake. Cheap and reusable
    // across any subsequent fetch on the origin (including the
    // Layer 2 prefetch and whatever the LP itself loads).
    const dns = injectLink("dns-prefetch", origin);
    if (dns) session.injected.push(dns);
    const pre = injectLink("preconnect",   origin, { crossorigin: "" });
    if (pre) session.injected.push(pre);

    // Layer 2: full LP HTML prefetch. Browser caches the response;
    // the eventual CTA tap (window.open same-URL) hits the cache.
    // Byte identity matters — that's why ctaUrl came verbatim from
    // the banner's resolveCtaUrl(), which mirrors the in-overlay
    // handler's URL precedence.
    const fetch = injectLink("prefetch", ctaUrl, { as: "document" });
    if (fetch) {
      session.injected.push(fetch);
      safeMark("pv:lp-prefetch-injected");
    }
  };

  const onPageChanged = (raw: Event): void => {
    if (!session || session.prerendered) return;
    const e = raw as CustomEvent<MagazinePageChangedDetail>;
    const detail = e.detail;
    if (!detail) return;
    // Layer 3 fires on the last page — the strongest tap-intent
    // signal we have without clairvoyance. The banner auto-collapses
    // on a "next" gesture from the last page, so this is the last
    // chance to escalate from prefetch to prerender.
    //
    // Currently inert for advertiser LPs: Chromium 147 restricts
    // Speculation Rules `prerender` to same-site targets, and ad LPs
    // are cross-site by definition. Injection still happens (the spec
    // doesn't require us to skip it client-side); Chromium reports
    // the failure in the Preloading panel and falls back to Layer 2's
    // prefetch cache, which is what actually delivers the speedup
    // today. Header comment has the full story.
    const reachedLast = detail.pageIndex >= detail.totalPages - 1;
    if (!reachedLast) return;
    safeMark("pv:page-3-reached");
    if (!supportsSpeculationRules()) return;
    const script = injectSpeculationRules(session.ctaUrl);
    if (script) {
      session.injected.push(script);
      session.prerendered = true;
      safeMark("pv:lp-prerender-injected");
    }
  };

  const onCollapse = (): void => {
    if (!session) return;
    // By collapse time any CTA tap's window.open has already issued
    // its request; the new tab will pick up whatever's already in
    // cache. Removing the <link>/<script> from <head> just stops
    // future speculative work the reader has now opted out of.
    for (const el of session.injected) {
      el.remove();
    }
    session = null;
  };

  banner.addEventListener("magazine-expand",       onExpand);
  banner.addEventListener("magazine-page-changed", onPageChanged);
  banner.addEventListener("magazine-collapse",     onCollapse);
}
