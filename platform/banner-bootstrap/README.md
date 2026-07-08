# banner-bootstrap

Publisher-facing bootstrap script for PromoVolve ads. **The sole integration primitive publishers embed.** The `<expandable-magazine-banner>` custom element stays an internal renderer — the bootstrap loads it on demand from the URL the server returns with each winner.

## Why this exists

Before this module, publishers integrated by embedding `<expandable-magazine-banner>` tags directly — one per slot. Every tag fired its own `GET /v1/serve` request. The result: the first slot to fire won with the full candidate pool's best pick, later slots served from a pool narrowed by the per-page-per-campaign cap, and on pages with narrow pools the bottom slots often went empty.

This bootstrap replaces that integration pattern. Publishers place marker divs; the bootstrap discovers them, fires one `POST /v1/serve/batch` request covering every slot, server runs a joint greedy auction, winners distributed back. Matches the shape of Google Publisher Tag's Single Request Architecture and prebid.js.

## Integration — publisher HTML

```html
<script async src="https://cdn.example.com/js/promovolve-bootstrap.<hash>.js"
        data-pub="siteA"></script>

<div data-promovolve-slot="leader-top" data-w="970" data-h="250"></div>
<article>…</article>
<div data-promovolve-slot="rect-inline" data-w="300" data-h="250"></div>
<aside>
  <div data-promovolve-slot="sky-right" data-w="160" data-h="600"></div>
</aside>
```

That's the zero-config path. On DOMContentLoaded the bootstrap scans for `[data-promovolve-slot]` divs, picks up the publisher id from the script's `data-pub`, fires one batch request, distributes winners.

## Integration — programmatic

For publishers who need more control (user ids, timeouts, slot-by-slot opts):

```html
<script async src="https://cdn.example.com/js/promovolve-bootstrap.<hash>.js"></script>
<script>
  window.__promovolve__ = window.__promovolve__ || { cmd: [] };
  __promovolve__.cmd.push(() => {
    promovolve.setConfig({
      pub: 'siteA',
      uid: 'user-abc',
      batchTimeoutMs: 800,
      collapseEmptyDivs: true,
    });
    promovolve.defineSlot('leader-top',  [970, 250]);
    promovolve.defineSlot('rect-inline', [300, 250]);
    promovolve.defineSlot('sticky-foot', [320, 50], { singleRequestOptOut: true });
    promovolve.display();
  });
</script>
```

Command-queue pattern (same as GPT): calls pushed onto `cmd` before the script loads run in order once it does.

## Config options

| Key | Default | Purpose |
|---|---|---|
| `pub` | _(required)_ | Publisher site id — passed as the `pub` field in batch/serve requests |
| `uid` | `undefined` | Optional user id for frequency capping + pacing |
| `batchEndpoint` | `/v1/serve/batch` | POST target for the batch auction |
| `singleServeEndpoint` | `/v1/serve` | GET target for per-slot fallback + late-arriving slots |
| `batchTimeoutMs` | `1000` | Abort the batch fetch after this many ms; falls back to per-slot |
| `enableSingleRequest` | `true` | Set `false` to disable batching entirely (every slot fires its own request) |
| `collapseEmptyDivs` | `true` | `display:none` on slots that didn't fill, so they don't reserve space |

## Per-slot options

| Key | Purpose |
|---|---|
| `singleRequestOptOut` | Exclude this slot from the batch, always use per-slot endpoint. For sticky-footer / modal / deferred placements that don't load with the rest of the page. |

## Late-arriving slots

For slots appearing after initial page load (SPA route change, infinite-scroll, modal), call:

```js
promovolve.defineSlot('new-slot', [300, 250]);
promovolve.serveSlot('new-slot');
```

`serveSlot` hits the per-slot endpoint directly — no batch, no coordination.

## Build & publish

```bash
cd platform/banner-bootstrap
npm install
npm run release          # typecheck + lint + build + publish to R2
```

`publish:r2` uploads the bundle to R2 under `js/promovolve-bootstrap.<hash>.js` (content-addressed filename, `Cache-Control: immutable`), then rewrites `BOOTSTRAP_SCRIPT_URL` in `scripts/.env`. Publishers embed the current URL in their pages; each publish produces a new URL so there's no stale-cache window.

Shares R2 credentials with `banner-component` and the JVM-side `R2ImageStorage` — same bucket, same keys.

## What it does NOT do

- **Not a bidder** — this is a publisher tag, not a DSP client. All auction logic lives on the server side of the batch endpoint.
- **Not responsive** — each slot has one fixed size. Responsive/multi-size slots are a phase-3 concern (affects server-side candidate matching too).
- **Not a consent framework** — it respects the server's `Sec-GPC: 1` handling but doesn't implement TCF/GPP signals itself. Publishers needing consent management wrap this with a CMP.

## Observability

All warnings and errors go through `console.warn` / `console.error` with the `[promovolve]` prefix. Publishers running in dev can grep their browser console for that tag to see what the bootstrap is doing.

## LP prefetch

The reader spends 15-30s with the spread overlay open before tapping the CTA. We use that window to warm the advertiser's landing page so the eventual tap doesn't dump them into a 3-10s white screen.

Three layers, each gated on a stronger intent signal, all driven by events the banner component dispatches:

| Layer | Trigger event | Hint injected | Browsers |
|---|---|---|---|
| 1 | `magazine-expand` | `<link rel=dns-prefetch>` + `<link rel=preconnect crossorigin>` on the LP origin | All |
| 2 | `magazine-expand` | `<link rel=prefetch as=document>` on the exact CTA URL (UTMs included) | All modern |
| 3 | `magazine-page-changed` reaching last page | `<script type=speculationrules>` with `prerender` for the CTA URL | Chromium 121+ (feature-detected, but currently inert for advertiser LPs — see note) |

All hints are removed from `<head>` on `magazine-collapse`. By collapse time any CTA tap's `window.open` has already issued its request and the new tab keeps whatever was already cached — cleanup only stops *future* speculative work the reader has opted out of.

URL identity matters: a prefetched hint hits the cache only if the navigation URL is byte-identical. The banner emits the resolved CTA URL in the `magazine-expand` event detail so the bootstrap injects exactly the URL the in-overlay handler will use.

**Layer 3 status (May 2026):** the Speculation Rules `prerender` action is restricted to same-site targets in Chromium ([crbug.com/1176054](https://crbug.com/1176054)). Advertiser LPs are cross-site from the publisher, so Layer 3 currently fails with "cross-site prerender not supported" on every real LP. The injection still happens — Chromium logs the failure in DevTools' Preloading panel and degrades to Layer 2's prefetch cache, which is what's actually delivering the speedup today. We're keeping Layer 3 in place because it'll start working automatically when Chromium ships cross-site support, and the failure is cost-free (no user-facing impact, ~600B in the bundle).

Performance marks emitted: `pv:overlay-open`, `pv:lp-prefetch-injected`, `pv:page-3-reached`, `pv:lp-prerender-injected`, `pv:cta-tap`. No telemetry beacons; field measurement happens via `performance.getEntriesByType("mark")` from publisher-supplied dashboards.

Privacy note: the prerender layer executes the LP's JavaScript ahead of time, including its analytics. The advertiser's GA4 may see a pageview the reader never visited. This is a known platform-level tradeoff that the Speculation Rules API is designed for; the spec document covers it.
