# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Promovolve is a two-sided ad network. When a design decision has to favour one side, **advertisers come first**.

- **Advertisers (primary):** they bid on content categories rather than on people. They generate creatives from their own landing page, fund a prepaid wallet, set campaign and daily budgets, and watch spend, delivery and tap-throughs.
- **Publishers:** site owners, often on WordPress, who add a script tag plus one `<div>` per slot. They approve or block the ads matched to their pages and watch their earnings.
- **Operators/admins:** they run an instance and handle site requests, verification, billing, payouts and fraud review.

## Product Purpose

Promovolve brings back what magazine advertising had: ads that match what the reader is actually reading, with no cookies, no user profiles and no cross-site tracking. The page's content is the only targeting signal. Success means advertisers reach readers through relevant context, and the reading experience is not degraded.

## Positioning

- **Content, not people.** Targeting comes from on-demand classification of a page's text into IAB categories. Privacy holds because the system is built that way, not because a policy promises it.
- **Transparency is the product.** The project is open source (Apache 2.0), so anyone can read the auction, pricing, floors and pacing logic and check that nothing is manipulated. A closed network cannot truthfully make this claim.
- **The magazine creative.** From an advertiser's landing page, Promovolve generates an original three-page "magazine" creative: a compact banner that expands into a page-turning story. It is fluid, so it scales to any slot size. It is as central to the identity as contextual targeting.

## Operating Context

- **Advertisers:** campaigns, creatives (generated, then refined in the creative designer/editor), wallet, stats and reports.
- **Publishers:** sites, ad approval (including trusted advertisers), stats, earnings, ledger and site observations.
- **Admins:** sites, account requests, users, billing (accounts, journal, top-ups, payouts), fraud and settings.
- The ad unit runs inside third-party pages as a Shadow-DOM web component, so it must hold up on hostile host CSS and on mobile.
- Auctions run periodically per site. Thompson sampling picks the ad at serve time. Winners pay a quality-adjusted second price.

## Capabilities and Constraints

- **Dashboard stack:** server-rendered Go templates with compiled Tailwind. No React. The ad unit and loader are plain web components and scripts.
- **Bilingual:** every UI string ships in English and 日本語 at parity. A third language may be added later, so copy should not assume only two.
- **Login is passkey-only.** Organisations are one per email domain.
- **Money has three levels:** wallet, then advertiser daily budget, then campaign daily budget. Billing is a prepaid wallet with local-day settlement. Stripe is not built.
- **Gestures carry meaning:** click expands the creative, a fold dog-ears (bookmarks) it, and a CTA tap goes through to the advertiser. These feed the auction score, so their meaning must stay intact.

## Brand Commitments

- The name is written **Promovolve** in all copy.
- The logo is `docs/assets/logo.png`.
- The magazine/paper metaphor (page turn, dog-ear, three-page story) is part of the product, not decoration.

## Evidence on Hand

- README and documentation: `README.md`, `docs/` (guides, architecture, design docs).
- The Promovolve book: https://promovolve.github.io/promovolve-book/
- The demo video playlist linked from the README.
- **Absences:** the product is early, with few real users. Never invent customers, testimonials, usage metrics, benchmarks or pricing.

## Product Principles

1. **Advertiser clarity first:** an advertiser should always be able to see where their money went and why an ad did or did not serve.
2. **Show the mechanism:** the auction, pricing and pacing are explainable, and the UI should explain them rather than hide them behind black-box summaries.
3. **Respect the reader:** nothing should degrade the host page or track the person reading it.
4. **The creative is the product:** the magazine creative deserves the same craft as the platform.
5. **Honest about scale:** state real numbers and real limits, and never inflate them.

## Accessibility & Inclusion

The bar is WCAG 2.2 AA for both the dashboard and the ad unit. EN and 日本語 have equal standing, which includes CJK typography and font fallback.
