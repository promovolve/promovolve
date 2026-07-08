<h1 align="center">
  <img alt="Promovolve" src="docs/assets/logo.svg" width="330">
</h1>

<p align="center">An open-source ad network that targets <strong>content, not people</strong>.</p>

<p align="center">
  <a href="https://github.com/promovolve/promovolve/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/promovolve/promovolve/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/License-Apache_2.0-blue.svg"></a>
</p>

Promovolve is an attempt to get back what magazine advertising had:
relevant ads matched to what the reader is actually reading, with no
cookies, no user profiles, no cross-site tracking, and no degradation of
the reading experience. The page's content is the only targeting signal —
an article about hiking gets ads for hiking gear because of what it *is*,
not because of who is reading it.

Being open source is not incidental: **transparency is the product**.
Publishers and advertisers can read the auction, the pricing, and the
pacing logic and verify there is no hidden manipulation — something no
closed ad network can offer.

## Demo

[![Watch the Promovolve demo](https://img.youtube.com/vi/Ly9ZSh27oA4/maxresdefault.jpg)](https://www.youtube.com/watch?v=Ly9ZSh27oA4)

## How it works

1. **A publisher adds two lines of HTML** — a script tag and a `<div>`
   per ad slot. No SDK, no ad server account, no JavaScript to write.
2. **Pages are classified on demand** from real traffic: an LLM maps the
   page's text into IAB content categories the first time it serves.
3. **Auctions run periodically per site**, not on every page load.
   Campaigns bid on content categories; a shortlist of candidates per
   slot is cached in a replicated in-memory serve index, keeping the
   serve path fast.
4. **Thompson sampling picks the ad at serve time** — candidates are
   scored by `sampledCTR × CPM^α`, balancing revenue against ad quality
   (α is publisher-configurable) and exploring new creatives without
   re-running the auction.
5. **Winners pay the minimum price that still wins** (quality-adjusted,
   second-price style), so advertisers can simply bid their true value.
   Publisher floor prices are optimized continuously against observed
   demand.
6. **Creatives are generated, not uploaded.** From an advertiser's
   landing page, Promovolve writes an original three-page "magazine"
   creative — a compact banner that expands into a page-turning story.
   Creatives are fluid: they scale to any slot dimensions.

## Repository layout

| Path | What it is |
|---|---|
| `modules/` | The core: a Scala / Apache Pekko cluster — auctions, serving, tracking, classification, creative generation |
| `platform/` | The dashboard: a Go web app (server-rendered templates, passkey auth) plus the banner web component and the publisher loader script |
| `docs/` | [Documentation index](docs/README.md) — guides, architecture, design docs |
| `k8s/` | Base Kubernetes manifests (Kustomize) |
| `deploy/` | Ready-made deployment overlays (GKE Autopilot demo · GKE Standard production shape) |
| `docker/` | Database schema (`init-db.sql`) and container bits |
| `scripts/` | Dev runners, asset publish scripts, test harnesses |

## Getting started

Pick your role:

- **Run your own instance** → [Self-Hosting guide](docs/guides/self-hosting.md)
- **Put ads on your site** → [Publisher Integration guide](docs/guides/publisher-integration.md)
- **Advertise** → [Advertiser Quickstart](docs/guides/advertiser-quickstart.md)

For local development:

```bash
docker compose up -d postgres          # TimescaleDB on :5432 (detached)
cp scripts/.env.example scripts/.env   # fill in R2 credentials + an LLM API key
scripts/run-dev.sh --fresh             # core API on :8080
scripts/run-dashboard.sh               # dashboard on :9091
```

You'll need JDK + sbt, Go, Node.js, Docker, a Cloudflare R2 bucket, and
one LLM API key (Gemini, OpenAI, or Anthropic) — the core refuses to boot
without R2 and an LLM provider. Details in the
[self-hosting guide](docs/guides/self-hosting.md).

## Documentation

- [`docs/README.md`](docs/README.md) — index of all guides and design docs
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — system overview

## Status

Active development, pre-1.0. Interfaces, schemas, and deployment layout
may still change without notice.

## License

Licensed under the [Apache License 2.0](LICENSE).
