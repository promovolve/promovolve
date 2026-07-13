# Content Classification — IAB Content Taxonomy 3.0

> Replaces the former `IAB_AD_PRODUCT_TAXONOMY.md`, which documented a static
> content↔ad-product bridge (`ContentToAdProductMapping` +
> `content_to_adproduct.tsv`). That system was deliberately removed in commit
> `d42e08aa` ("taxonomy: migrate to IAB Content Taxonomy 3.0, drop static
> content↔adProduct bridge"). Both sides — page content and campaign demand —
> now speak one vocabulary: IAB Content Taxonomy 3.0 category IDs.

## Overview

Publisher pages are classified into IAB Content Taxonomy 3.0 categories by an
LLM; campaigns register demand against the same category IDs. Matching is a
direct category match — there is no intermediate ad-product mapping layer and
no static relevance-score table.

## Taxonomy data

`modules/core/src/main/resources/iab_content_taxonomy/`:

- `3_0.tsv` — the live taxonomy, loaded at startup by
  `taxonomy/TieredCategory.scala`.
- `2_1.tsv` — the legacy 2.x taxonomy (kept for migration).
- `legacy_2x_to_3_0.tsv` — the 2.x→3.0 ID migration table, loaded by
  `taxonomy/Iab2xTo3xMigration.scala` so entities persisted with 2.x IDs
  recover cleanly.

## Classification (`taxonomy/IABTaxonomy.scala`)

LLM-backed classifier with pluggable providers — Gemini (the effective
deployment default is `gemini-2.5-flash`, set in `ClusterBootstrap`; the bare
case-class default is `gemini-2.0-flash`), OpenAI (`gpt-4o-mini`), and
Anthropic (`claude-3-haiku`). Operational hardening around the call:

- **Circuit breaker** — 5 consecutive failures open the breaker for 30s
  (15s call timeout).
- **Rate limiting** — Gemini calls go through `GeminiRateLimiter` (a
  `TokenBucketLimiter` singleton). The default budget assumes the free tier
  (8 RPM); set `GEMINI_TOKENS_PER_MINUTE` / `GEMINI_BURST` higher for paid
  keys, or bursty classification loads will time out past the 30s acquire
  deadline and fall back to low-confidence generic categories.

## When classification runs

Page classification is **demand-gated**: `SiteEntity` only classifies pages
when at least one campaign has registered demand categories
(`demandCategories` non-empty). On a fresh database with no campaigns, the
dashboard's "Matched Category" column is expectedly blank — that's the gate,
not a bug.

Classifications are persisted in `SiteEntity` state (`pageClassifications`)
and replayed on recovery, so a restart does not re-classify
already-known pages.

## Campaign demand side

Campaigns register against categories via
`POST /v1/auction/categories/{category}/campaigns`. The category-indexed
actors (`CategoryRegistry`, `CategoryBidderEntity`, `CampaignDirectory`,
`ContentProductAffinity`) fan auction requests out to campaigns whose demand
matches the page's classified categories.

## What does NOT exist (so you don't go looking)

- No `relevanceScore()` between content and ad-product categories — ranking is
  Thompson Sampling over real engagement (see `MAB_INTEGRATION.md` /
  `DELIVERY_ALGORITHMS.md`), with the category match as a binary gate and the
  category's sampled prior informing cold-start.
- No ad-product blocklist. The publisher-facing block tool is the
  **advertiser domain blocklist**, enforced at serve time in `AdServer` by
  matching each candidate's landing domain against the publisher's blocked
  domains. (The `AdvertiserDomainKey` DData map is the campaign→landing-domain
  registry that feeds this check, not the blocklist itself.) Plus the
  campaign-side `siteAllowlist`.
