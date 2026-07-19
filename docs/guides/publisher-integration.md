# Publisher Integration Guide

How to put Promovolve ads on your site. The integration is two pieces of
HTML — a script tag and one container `<div>` per ad slot — and no
JavaScript to write. Pages are classified automatically from real traffic,
and ad slots are discovered as the page serves: there is nothing to
pre-register and no setup step to wait for.

## Prerequisites

1. A publisher account on the Promovolve dashboard of the deployment you
   are joining (accounts are created by request and approved by an admin).
2. Your site added under **Sites** in the dashboard, **approved by an
   admin** (a new site sits at "Awaiting approval" until then), and the
   domain **verified** (see [Domain verification](#domain-verification)).

You need three values, all specific to the deployment you are integrating
with:

| Value | What it is | Where to get it |
|---|---|---|
| Loader script URL | The `promovolve-ad.js` bootstrap, served from the operator's CDN | The operator (or the dashboard, if it shows an embed snippet) |
| Ads API base | The public serve endpoint, e.g. `https://ads.example.com` | The operator |
| Site ID | Your site's identifier — a slug of its domain, e.g. `news-example-com` | Shown on your site's card on the dashboard **Sites** page |

## The integration

Add the loader once per page, anywhere in the document:

```html
<script src="https://YOUR_OPERATOR_CDN/promovolve-ad.js"
        data-pub="YOUR_SITE_ID"
        data-api="https://YOUR_ADS_API"></script>
```

Then add one container per ad slot where you want an ad to appear:

```html
<div data-promovolve-slot="sidebar-top" data-w="300" data-h="250"></div>
<div data-promovolve-slot="article-footer" data-w="728" data-h="90"></div>
```

That's everything. On page load the script finds every
`[data-promovolve-slot]` element, sends a single batched ad request, and
renders a winning creative into each slot as a self-contained web
component. Slots with no winner are collapsed (`display: none`) so they
don't leave a hole in your layout.

### Attribute reference

| Attribute | On | Meaning |
|---|---|---|
| `data-pub` | script | Your Site ID (required) |
| `data-api` | script | Ads API base URL (required in production; defaults to `host:8080` for local dev) |
| `data-promovolve-slot` | div | Your identifier for the slot. Pick any stable, unique-per-site string and keep it — it is how the slot is tracked for reporting and per-slot floor prices |
| `data-w`, `data-h` | div | Slot dimensions in CSS pixels. **Required** — a slot missing positive dimensions is skipped with a console warning |

Creatives are fluid: they scale to fill the box you declare with
`data-w`/`data-h` rather than requiring exact IAB pixel sizes, so pick
dimensions that fit your layout.

## What happens at runtime

1. On `DOMContentLoaded` the loader collects your slots and issues one
   `POST {data-api}/v1/serve/batch` with the page URL and slot dimensions.
2. If the page has never been seen (or its classification is stale), the
   server asks the loader for page text, and the loader posts it to
   `/v1/classify-page` off the critical path. The very first pageviews of
   a brand-new page may therefore serve no ads for a minute or two while
   the page is classified — this resolves on its own with traffic.
3. For each winner the loader lazy-loads the banner component bundle
   (one fetch per page) and injects an `<expandable-magazine-banner>`
   element into your container. You never write this element yourself.

The rendered unit uses Shadow DOM, so your site's CSS and the ad's CSS
cannot leak into each other.

### Single-page apps and programmatic control

If your pages render client-side or you insert slots after load, use the
GPT-style command queue instead of relying on auto-discovery:

```html
<script>
  window.__promovolve__ = window.__promovolve__ || { cmd: [] };
  window.__promovolve__.cmd.push(() => {
    __promovolve__.setConfig({ pub: "YOUR_SITE_ID", apiBase: "https://YOUR_ADS_API" });
    __promovolve__.defineSlot("sidebar-top", 300, 250, document.querySelector("#ad1"));
    __promovolve__.display();
  });
</script>
<script async src="https://YOUR_OPERATOR_CDN/promovolve-ad.js"></script>
```

## Sites and subdomains

A site is identified by its host: `news.example.com` and
`blog.example.com` are **separate sites**, each with its own Site ID,
verification, and floor prices. The one exception is the apex/`www` pair —
`example.com` and `www.example.com` are treated as the same site.

## Domain verification

Ads only serve on verified domains. Verification unlocks once an admin
approves the site request; the dashboard then offers two methods:

- **Hosted file** — place the provided token file at the well-known path
  on your domain.
- **DNS TXT record** — add the provided token as a TXT record; useful when
  you can't serve files at the root (e.g. some static hosts).

Details of the mechanism are in
[SITE_VERIFICATION](../design/SITE_VERIFICATION.md).

## Floor prices

You can set a site-wide floor CPM and override it per slot from the
dashboard **Sites** page. Leaving the floor to the system is usually
better: Promovolve continuously sweeps floor candidates against observed
demand and picks the revenue-maximizing floor for you (see
[floor-cpm-optimization](../design/floor-cpm-optimization.md)).

## Creative approval and auto-approval

Every creative that wins an auction on your site first lands in the
dashboard **Approval** inbox; nothing serves until you approve it. For
advertisers you already vetted you can opt in to auto-approval, per site,
from the inbox's site panel:

- Each **manual** approval records trust for that creative's campaign and
  its landing-page domain (`shop.acme.com` and `www.acme.com` count as the
  same `acme.com`).
- With **Auto-approve trusted advertisers** switched on, a new creative
  from a trusted campaign or landing domain skips the queue and starts
  serving immediately, marked with an *Auto-approved* badge.
- Rejecting, flagging, or revoking a creative withdraws the trust for its
  campaign and domain — later creatives from them return to the manual
  queue. The full trust list lives on the **Trusted Advertisers** page,
  where you can review every trusted campaign/domain per site and remove
  entries individually.
- Enabling the toggle (or approving a creative that mints new trust) also
  sweeps the existing queue: pending creatives that match are re-auctioned
  and auto-approved within seconds — the inbox updates live.
- Turning the toggle off pauses auto-approval without deleting the trust
  list; turning it back on restores it.

The toggle is off by default: approved creatives inform your floor
pricing, so widening what gets approved automatically is a deliberate
choice.

## Content-Security-Policy and Trusted Types

The ad tag works on pages that enforce
`require-trusted-types-for 'script'`: it creates one Trusted Types policy
named **`promovolve`** and routes all of its own HTML through it. If your
CSP *also* restricts which policy names may be created (a `trusted-types`
directive listing names), add `promovolve` to that list:

```
Content-Security-Policy: require-trusted-types-for 'script'; trusted-types promovolve yourOtherPolicies
```

Strict `style-src` policies (no `'unsafe-inline'`) log two cosmetic
style-attribute warnings in the console; rendering is unaffected.

## Troubleshooting

- **A slot renders nothing** — check the browser console. A missing or
  non-positive `data-w`/`data-h` skips the slot with a warning. An empty
  auction (thin demand, brand-new page still classifying) collapses the
  slot silently; it will fill as demand and classification catch up.
- **No ads only in *your* browser** — if you've used the dog-ear
  (bookmark) feature on an ad and later navigated away, a pinned campaign
  is excluded for your browser. Test in a private window before assuming
  serving is broken.
- **Ads stop after the operator deploys** — in-memory demand is rebuilt
  for a minute or two after a server restart; this self-heals.
