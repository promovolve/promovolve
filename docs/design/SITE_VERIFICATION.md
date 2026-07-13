# Site Verification Design

## Why publisherId alone is insufficient

`publisherId` (and `data-pub` in page source) is **public by design** — it's visible in every publisher's HTML. Anyone can copy it from page source and embed the Promovolve widget on their own site. Without verification, this lets an attacker steal ad impressions and revenue by posing as the publisher.

## What verified hosts are

A **verified host** is a domain that a publisher has proven they control by placing a specific file at a well-known URL. Each site registered in Promovolve has:

- A unique **verification token** (UUID, generated at site creation)
- A **verification status**: unverified → verified
- A **verified host**: the normalized domain (e.g., `sports-daily.com`)

Verification proves: "the person who registered this site in Promovolve also controls the web server at that domain."

## Verification methods: well-known file, DNS TXT fallback

The publisher must create a file at:

```
https://<domain>/.well-known/promovolve.txt
```

With exact content:

```
promovolve-site-verification=<token>
```

When the publisher triggers verification, the system fetches this URL and checks the token matches. Public hosts are fetched **HTTPS first, then HTTP**; private hosts (dev/loopback) are HTTP-only. On success, the site's verified host is persisted and broadcast to all serving nodes via DData.

**DNS TXT fallback**: hosts that can't serve the well-known file (locked-down
managed hosting) can instead publish a DNS TXT record carrying the same
`promovolve-site-verification=<token>` value at a promovolve-specific record
name (kept off the apex so it doesn't collide with SPF/DMARC). If the
HTTP-file check fails, the TXT record is consulted.

**Site-approval gate**: before a publisher can even attempt verification, the
added site must be approved by an operator (`site_requests` table, admin
approval queue).

## Where enforcement happens

### 1. Serve-time host check (AdServer — hot path)

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

### 2. DData propagation

SiteEntity publishes to `VerifiedHostKey` (LWWMap[SiteId, String]) on:
- Successful verification
- Recovery (startup)

AdServer subscribes to this key and stores the verified host in its local state. DData replication ensures all nodes converge within seconds.

## Serve-time rule

Reject unless ALL of the following are true:
- Publisher entity exists (siteId is valid)
- Request URL is parseable
- Normalized request host == publisher's verified host

Host normalization lowercases and drops a leading `www.`, so a site verified
as `example.com` also fills on `www.example.com` and vice-versa (the common
WordPress www/non-www split). Subdomains are otherwise distinct sites — the
host IS the site identity.

## Tradeoffs and follow-up items

### Current limitations
- **No automatic re-verification**: once verified, stays verified. A periodic re-check could be added
- **No verification expiry**: a host that changes hands keeps serving until manually revoked

### Future work
- Periodic re-verification (e.g., monthly)
- Verification expiry / revocation
- Admin override for verification
- Metrics: counters for verification attempts, failures, serve-time rejections
