# Promovolve Documentation

## Guides

Start here if you want to run, publish with, or advertise on Promovolve.

- [Self-Hosting](guides/self-hosting.md) — deploy your own Promovolve
  instance: services, required configuration, CDN/R2 assets, first-run
  setup.
- [Publisher Integration](guides/publisher-integration.md) — put ads on
  your site with a script tag and a `div` per slot.
- [Advertiser Quickstart](guides/advertiser-quickstart.md) — create a
  campaign from a landing page and understand what you pay.

## Architecture

- [ARCHITECTURE](ARCHITECTURE.md) — system overview: cluster topology,
  actors, projections, serving path.

## Design documents

Deep dives into why the system works the way it does, in `design/`:

| Area | Documents |
|---|---|
| Auction | [PERIODIC_AUCTION_DESIGN](design/PERIODIC_AUCTION_DESIGN.md), [REAUCTION_MATRIX](design/REAUCTION_MATRIX.md), [DELIVERY_ALGORITHMS](design/DELIVERY_ALGORITHMS.md), [MAB_INTEGRATION](design/MAB_INTEGRATION.md) |
| Pricing & pacing | [BUDGET_PACING](design/BUDGET_PACING.md), [floor-cpm-optimization](design/floor-cpm-optimization.md) |
| Classification | [CONTENT_CLASSIFICATION](design/CONTENT_CLASSIFICATION.md), [ON_DEMAND_CLASSIFICATION](design/ON_DEMAND_CLASSIFICATION.md) |
| Publisher identity | [SITE_VERIFICATION](design/SITE_VERIFICATION.md) |
| Creatives | [TEXT_COLOR_PIPELINE](design/TEXT_COLOR_PIPELINE.md) |
| Billing | [BILLING](design/BILLING.md) |
| Time & timezones | [TIME_AND_TIMEZONES](design/TIME_AND_TIMEZONES.md) |
| Fraud prevention | [FRAUD_PREVENTION](design/FRAUD_PREVENTION.md) *(design — not yet built)* |

## Developer notes

Internal development workflow docs live in `dev_docs/` at the repo root
(designer dev loops, dashboard design system, test walkthroughs).
