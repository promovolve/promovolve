# Site Verification Design

## Why publisherId alone is insufficient

`publisherId` (and `data-pub` in page source) is **public by design** — it's visible in every publisher's HTML. Anyone can copy it from page source and embed the Promovolve widget on their own site. Without verification, this lets an attacker steal ad impressions and revenue by posing as the publisher.

## What verified hosts are

A **verified host** is a domain that a publisher has proven they control by placing a specific file at a well-known URL. Each site registered in Promovolve has:

- A unique **verification token** (UUID, generated at site creation)
- A **verification status**: unverified → verified
- A **verified host**: the normalized domain (e.g., `sports-daily.com`)

Verification proves: "the person who registered this site in Promovolve also controls the web server at that domain."

## Verification method: HTTP-file

The publisher must create a file at:

```
https://<domain>/.well-known/promovolve.txt
```

With exact content:

```
promovolve-site-verification=<token>
```

When the publisher triggers verification, the system fetches this URL and checks the token matches. On success, the site's verified host is persisted and broadcast to all serving nodes via DData.

## Where enforcement happens

### 1. Crawl gating (SiteEntity)

`StartCrawling`, `BootstrapCrawl`, and `TriggerCrawl` commands check `state.isVerified` before proceeding. Unverified sites cannot be crawled — no page classification, no auctions, no candidates.

### 2. Serve-time host check (AdServer — hot path)

On every `/v1/serve` request, before any pacing/ranking/selection:

```
1. Parse host from request URL (e.g., "http://sports-daily.com/page" → "sports-daily.com")
2. Compare against verifiedHost from DData (populated by SiteEntity on verification)
3. If mismatch or no verified host → return HostNotVerified (403)
```

This check is:
- **O(1)**: string comparison, no network call, no DB lookup
- **In-memory**: verifiedHost is held in AdServer's state via DData subscription
- **Early**: runs before any auction/pacing work

### 3. DData propagation

SiteEntity publishes to `VerifiedHostKey` (LWWMap[SiteId, String]) on:
- Successful verification
- Recovery (startup)

AdServer subscribes to this key and stores the verified host in its local state. DData replication ensures all nodes converge within seconds.

## Serve-time rule

Reject unless ALL of the following are true:
- Publisher entity exists (siteId is valid)
- Request URL is parseable
- Normalized request host == publisher's verified host

## Tradeoffs and follow-up items

### Current limitations
- **HTTP-only fetch**: the well-known-file check is hardcoded to `http://` for ALL hosts (`SiteEntity.scala`, `val scheme = "http"`) — intentional, so plain-HTTP hosts (e.g. the GitHub Pages demo behind the tunnel) can verify; HTTPS sites still pass via redirect
- **No DNS TXT verification**: designed for extension but only HTTP-file is implemented
- **No wildcard domains**: `www.example.com` and `example.com` are treated as distinct hosts
- **No automatic re-verification**: once verified, stays verified. A periodic re-check could be added
- **Existing sites default to unverified**: after deploying this change, existing sites need manual verification

### Future work
- DNS TXT record verification method
- Periodic re-verification (e.g., monthly)
- Verification expiry / revocation
- Admin override for verification
- Metrics: counters for verification attempts, failures, serve-time rejections
