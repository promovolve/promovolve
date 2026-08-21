# Global Privacy Control (GPC)

Global Privacy Control is a browser-level signal that tells a site: *do not sell
or share my personal information.* Promovolve serves ads to browsers sending
`Sec-GPC: 1` exactly as it serves every other browser — because there is no
personal information to sell or share, and never was.

This page explains that position, because "we serve ads under GPC" is the kind
of sentence that deserves its reasoning in full.

## Why serving is the correct response

The auction reads the **page**, not the viewer.

- **No identifier is ingested.** The serve request carries a publisher id, the
  page URL, and slot dimensions. No cookie, no device id, no fingerprint, no
  hashed email.
- **No profile is built.** Candidates are matched on the page's content
  category. Nothing about the reader enters the scoring function.
- **No server-side viewer identity exists.** There is no per-viewer record to
  attach a preference to, to sell, to share, or to leak.
- **Pricing is CPM-only.** Nothing about an individual is being valued.

One thing on this page changed in August 2026, and it is described in full
under [What the audience-verification counters do](#what-the-audience-verification-counters-do)
rather than left for a reader to discover: Promovolve now derives a **per-site,
per-day count of reader countries** from beacon traffic. It does not enter the
auction, it is not per-viewer, and the four claims above still hold as written —
but the honest statement is no longer "no viewer-derived data exists anywhere",
it is "no viewer-derived data reaches selection, and none of it is per-viewer".

Given that, declining to serve on `Sec-GPC: 1` would be an odd gesture: it would
concede that normal Promovolve serving is the kind of thing GPC exists to stop.
It is not, and the architecture makes that structurally true rather than
promised. Honoring an opt-out from data collection by suppressing an ad that
collects no data communicates the opposite of what is happening.

Privacy here is "can't," not "won't." The one-time cost of that is that the
usual privacy gestures have nothing to attach to.

## Why this is not a revenue rationalization

It would be if the reasoning ran the other way — if the architecture collected
viewer data and the doc argued its way out of the signal. It doesn't. Test the
claim directly: read `ServeRoutes.scala`, follow `BatchServeReq` into
`AdServer.BatchSelect`, and look for a viewer field. There isn't one. The code
is open source precisely so this is checkable rather than trusted.

The audience-verification counters are the one place viewer-derived data exists
at all, and they are checkable the same way: read `TrackRoutes.scala` for what
is passed to `AudienceObservationCounter.record` (a site id and a two-letter
country, nothing else), and read `AudienceObservationRepo` for what reaches
disk. If either grows a field that identifies a reader, this document is wrong
and should be treated as such.

The revenue consequence is real and worth stating plainly: GPC is **on by
default** in Brave and DuckDuckGo. Suppressing serve on the header meant every
one of those viewers was a guaranteed zero-fill on every publisher, with no
privacy gained by anybody — a cost paid by publishers for a gesture.

## What the earlier implementation did

Until 2026-08-12, `ServeRoutes` short-circuited `POST /v1/serve/batch` to
`204 No Content` whenever `Sec-GPC: 1` was present. That branch has been
removed.

It also had a bug worth recording. `204` is a 2xx, so the browser tag's
`resp.ok` check passed and the empty body reached `resp.json()`, which threw
`Unexpected end of JSON input`. The throw was then classified as a transient
network fault and **retried**, producing a second 204 and a second throw. Every
Brave-desktop pageview logged two console errors, made two serve requests, and
reported a network failure to the mount heartbeat instead of a clean no-fill.

The tag now treats `204` as an answered response with no winners
(`bootstrap.ts`, `batchAttempt`). That path still matters: the batch endpoint
returns `204` for an operator-suspended site and for content too old to serve.

## If you are deploying Promovolve yourself

This is a per-deployment policy decision, not a law of the codebase. If your
jurisdiction, counsel, or publisher agreements require declining to serve on
GPC, reinstate the branch at the top of the batch route:

```scala
path("batch") {
  post {
    optionalHeaderValueByName("Sec-GPC") {
      case Some("1") => complete(StatusCodes.NoContent)
      case _         =>
        entity(as[BatchServeReq]) { req =>
          // normal serving flow
        }
    }
  }
}
```

The tag handles the resulting `204` correctly, so reinstating it is a one-place
change.

## Browser support

| Browser        | GPC support                          | Default |
|----------------|--------------------------------------|---------|
| **Brave**      | Native                               | On      |
| **DuckDuckGo** | Native (desktop & mobile)            | On      |
| **Firefox**    | Native (Settings → Privacy)          | Off     |
| **Chrome**     | Via extension (Privacy Badger, etc.) | N/A     |
| **Edge**       | Via extension                        | N/A     |
| **Safari**     | Not natively supported               | N/A     |

## What the audience-verification counters do

Publishers can declare who reads their site — "my readers are in Japan" — and
advertisers can buy against that declaration
(`docs/design/GEOGRAPHIC_CONTEXT.md`). A declaration nobody can check is a claim
the publisher is paid to make, so Promovolve checks it against traffic.

Mechanically, on the mount beacon only:

1. the request's IP is resolved to an ISO country using the ASN database
   already loaded for fraud hygiene;
2. `1` is added to an in-memory counter keyed `(site, country, UTC day)`;
3. the IP is discarded — the same discard that already happened after hygiene
   classification.

Every five minutes those counters are summed into `site_audience_daily`. That
table is the complete record: four columns, `site_id`, `country`, `day`,
`count`.

**What this is not.** There is no per-event row, no timestamped country, no
column anywhere describing one reader, and nothing joinable to a person. A
distribution is only released above a 100-observation floor, so a site with a
handful of readers publishes nothing at all. The code path is a single class,
`AudienceObservationCounter`, which accepts `(siteId, country)` and can only
emit sums; its test asserts that 10,000 observations collapse to one row.

**What it affects.** A publisher's declaration is suppressed where the observed
traffic does not support it, and advertisers can opt into declaration-backed
inventory only. That is the extent of it. The counters are an audit of the
*publisher's claim*, never an input to choosing a creative.

**Why this does not reopen the question above.** Re-run the test in the section
below: follow `BatchServeReq` into `AdServer.BatchSelect` and look for a viewer
field. There still isn't one. Selection reads the page and the site; the audit
reads yesterday's totals. Two readers of the same page in different countries
get the same auction, the same pool, and the same eligible creatives — which is
the property GPC is asking about.

**Why we did not simply skip it.** The alternative was leaving audience
targeting purely self-reported. That does not avoid a privacy cost so much as
move it: an unverifiable claim market rewards inflating claims, and the party
paying is the advertiser. We chose the option that keeps selection viewer-blind
and makes the *seller's* claim checkable, rather than the option that keeps the
architecture diagram tidy.

## Why there is no server-side opt-out registry

An earlier design considered a server-side do-not-target registry keyed on a
hashed user identifier (`uid`), so logged-in users could opt out
browser-independently. We deliberately do **not** build this:

- It would require ingesting a per-user identifier at serve time — reintroducing
  exactly the server-side viewer identity the architecture avoids by design.
- It would create a new store of hashed-email PII, with its own access/deletion
  obligations.
- It solves a problem the architecture already solves structurally: a
  do-not-target registry exists to let a user opt out of being profiled, but
  Promovolve builds no per-viewer profile to begin with. Adding a tracking
  identifier in order to suppress tracking that does not happen is incoherent.

## References

- [GPC Specification](https://globalprivacycontrol.github.io/gpc-spec/)
- [California Attorney General GPC FAQ](https://oag.ca.gov/privacy/ccpa)
- [GPC.org](https://globalprivacycontrol.org/)
