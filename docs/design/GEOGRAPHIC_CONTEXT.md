# Geographic Context

> Status: **plan**. Nothing here is implemented yet.

## Decision

Promovolve gains three geographic signals. Every one of them is an **attribute
of the inventory**, resolved before the reader arrives.

Promovolve does **not** gain per-request viewer selection — choosing a creative
from the individual reader's location. That is an attribute of the *person*, and
it is an explicit non-goal for reasons in "Why not per-request viewer
selection".

That distinction is the organising principle of this design. The line is not
"geo data vs no geo data" — tier 3 below is derived from real reader IPs. The
line is **inventory vs person**.

Scope for v1: **country (ISO 3166-1) and first-level subdivision (ISO 3166-2)**,
plus **city** where it is declared rather than inferred. No postal code, no
coordinates, no radius.

## The three tiers

| | Scope | Source | Granularity | Verified | Answers |
|---|---|---|---|---|---|
| **1. Content location** | page | LLM classification | subdivision | n/a | *what is this page about?* |
| **2. Declared audience** | site | publisher declares | **city** | no — labelled a claim | *who does this publisher say reads it?* |
| **3. Observed audience** | site | aggregate beacon geo | **country** | **yes** | *who actually reads it?* |

Tiers 2 and 3 are complementary, not redundant. Declaration reaches city but
cannot be verified; observation is verified but stops at country because the
iptoasn data carries no subdivision. Tier 3 is what stops tier 2 from being a
free lie — see "Suppression".

## The invariant that makes this work

Every geographic fact is established **before the reader exists**:

- content location — once per URL, at classification
- declared audience — once, at site setup
- observed audience — continuously, but as a rolling aggregate, never per request

So none of them vary by who is asking, which means:

- **safe under full-page caching and CDN caching.** The WordPress plugin already
  documents that LiteSpeed on Hostinger caches pages for days
  (`promovolve_purge_page_caches`). A per-reader value rendered into the page
  would be captured in the first cache fill and served to everyone until purge.
- **evaluable at bid time.** The auction is periodic and runs ahead of the
  request; when a `BidQuote` is written the reader does not exist. All three
  tiers are known by then. Per-request viewer geo is not, and never can be.
- **no cost on the serve path.** `BatchServeReq` is untouched.

## End-to-end chain

```
TIER 1 — content                        TIER 2/3 — site
─────────────────────                   ─────────────────────────────
WordPress (names, any language)         SiteConfig.audienceRegions
  taxonomies · geo_address                (publisher-declared, city-capable)
        │ data-place="Tokyo"                      │
        ▼                                         │
POST /v1/classify-page                            │
        │                                         │
        ▼                                  beacon geo (country)
IABTaxonomy prompt → ISO codes            → (site, country, day) counters
        │  validated vs closed table              │
        ▼                                         ▼
ClassificationEntry.places              effectiveAudience(declared, observed)
        │        (per URL, cached)                │   suppression + MinSample
        └──────────────┬──────────────────────────┘
                       ▼
   CategoryBidRequest(siteId, url, places, siteAudience, slotId, …)
                       │
      ┌────────────────┴────────────────┐
      ▼                                 ▼
 live ask: canBid(…)          bid book: answerFromBook / BidQuote
                       │
                       ▼
              Candidate → ServeIndex → serve
                       (no geo evaluation at serve)
```

---

# Tier 1 — Content location

What the page is *about*. A page about Tokyo is about Tokyo for every reader,
which is why it inherits the caching and freshness properties the classification
layer already relies on: one value per canonical URL
(`UrlNormalizer.stripTrackingParams`), cached in `SiteEntity.pageClassifications`,
freshness-windowed, demand-gated.

## What WordPress can supply

The plugin's job is unchanged in kind: hand over what the CMS knows as fact, as
an **unverified, interested claim** — the `data-section` contract.

| Source | API | Notes |
|---|---|---|
| Custom taxonomy terms | `get_object_taxonomies($post)` | **The main win.** Travel/local sites put destinations in `destination`, `location`, `place`, `city`, `region`. Currently ignored — `promovolve_declared_topic()` hardcodes `category` and `post_tag`. |
| Categories & tags | `get_the_terms()` | Already read for `data-section`; frequently contain place names. |
| Taxonomy archives | `is_tax()` | Already handled; for a destination archive the term *is* the place. |
| WP Geodata meta | `get_post_meta($id,'geo_address')` | Opportunistic bonus only — see below. `geo_latitude`/`geo_longitude` are out of scope. |
| Site locale | `get_locale()` → `ja_JP` | Weak country hint. Feeds the tier-2 *suggestion*, not tier 1. |
| Rendered page text | already sent | The primary source. Needs nothing from WP. |

Every row is **names, not codes**, in the publisher's own language (`Tokyo`,
`東京`, `Kanto`), and **page-scoped, never viewer-scoped**.

### On geo plugins

**Do not depend on one.** The ecosystem has no meaningful penetration: GEO my WP
3,000+ installs, Geo Mashup 1,000+, Simple Location 300+. Yoast SEO is in the
millions. A plugin-specific reader would serve a rounding error while taxonomy
terms and page text cover 100% of publishers with no plugin at all.

If one must be named, **Simple Location** is the best citizen — it writes the
standard WP Geodata quad rather than private keys, and is actively maintained.
But support the **convention** (`geo_address`), never the plugin.

Two caveats:

- Geo plugins store **coordinates first**; `geo_address` is often empty. Under a
  no-coordinates v1 we frequently get nothing even where a plugin is installed.
- **Local-SEO plugins are the wrong semantic and must not be read.** Yoast Local
  and Rank Math Local store the *publisher's business address*, not what the
  article is about. A Tokyo bakery's post about Kyoto would resolve to Tokyo —
  confidently wrong, which is worse than absent.

## Names → codes

**The plugin never converts.** It sends names; the server emits codes.

1. A gazetteer cannot live in a WP plugin across every install.
2. The plugin is untrusted by design — `IABTaxonomy.sanitizeHint` exists because
   a WP category can be named anything, including a prompt injection. A
   publisher-emitted `JP-13` is an unverified *code* wearing an authoritative
   costume; a name is visibly a claim.
3. The server already has an LLM reading the page, and can cross-check the claim
   against the content.

### The closed vocabulary

**Built and shipping** — `modules/core/src/main/resources/places/`, loaded by
`taxonomy/Places.scala` exactly as `TieredCategory` loads `3_0.tsv`. Localised
names use the existing companion-file convention (`LocalizedNames.loadAll`)
rather than a `name_local` column:

- `countries.tsv` — `code | name_en`
- `subdivisions.tsv` — `code | country | name_en`
- `cities.tsv` — `code | name_en | country | admin1 | population`
- `*_<lang>.tsv` — `code | name`

### Two authorities, and why

| Table | Source | Rows |
|---|---|---|
| countries | Debian **iso-codes** `iso_3166-1.json` | 249 |
| subdivisions | Debian **iso-codes** `iso_3166-2.json` | 5,046 |
| cities | **GeoNames** `cities5000.zip` | 69,596 |
| `*_ja` | iso-codes gettext catalogues + GeoNames `alternatenames/JP` | 249 / 2,883 / 1,665 |

**GeoNames is deliberately not the source for subdivision codes.** Its admin1
numbering is its own and does not match ISO: GeoNames `JP.13` is Hyogo, ISO
`JP-13` is Tokyo. An earlier build of these tables used GeoNames admin1 under an
ISO label, which would have resolved every LLM-emitted `JP-13` to the wrong
prefecture — silently, with no validation error, because the code *was* in the
table. `PlacesSpec` now pins the ISO reading of four Japanese codes precisely so
this cannot regress.

Cities are linked to ISO subdivisions by exact normalised name within their
country. That maps **47% of GeoNames admin1 rows (61% of cities)** — 100% for
Japan. An unmatched city stores an empty `admin1` and chains straight to its
country: a coarser answer, never a wrong one. The build prints the coverage on
every run so the number stays visible.

`cities5000` (population ≥ 5,000) covers every town a publisher would plausibly
declare; the whole directory is 2.4 MB and loads into memory without ceremony.
Ancestor chains (`Kamakura → JP-14 → JP`) come from the table's own
`country`/`admin1` columns via `Places.expandWithHops`, never from parsing a
code — city ids are opaque GeoNames ids precisely to remove that temptation.

**Self-hosting matters here**: shipping the tables means `setup.sh` works
offline, with no GeoNames account, API key, or quota.

**Licences**: GeoNames is CC BY 4.0; iso-codes data is LGPL-2.1+ (data tables
only — no program code is incorporated or linked). Both are Apache-2.0
compatible with attribution, recorded in `NOTICE`.

### Prompt shape

Extend `IABTaxonomy.buildPrompt` with a second declared block (same
interested-claim framing and `sanitizeHint` treatment as the topic hint) and a
second response key:

```json
{"selected_taxonomy_ids": [{"id": "150", "confidence": 0.9}],
 "places": [{"code": "JP-13", "confidence": 0.88}]}
```

Ask for *the places the page is about*, not every place it mentions — an article
about Tokyo that name-drops Paris once is about Tokyo. Cap at 3, require high
confidence, and keep the empty array cheap.

Anything not in the shipped table is **dropped**. No free-text place ever enters
the system, which is what makes set intersection meaningful downstream.

Emit-then-validate is the v1 approach — models know ISO 3166 well and the table
is the guarantee. If accuracy disappoints, fall back to two-stage: country first
(249 rows, fits in the prompt), then subdivisions within it (Japan 47, US 56).

---

# Tier 2 — Declared site audience

A publisher-declared property of the site: *"my readers are in Kamakura."*

Sites already declare their content categories — `SiteConfig.taxonomyIds`
(`SiteEntity.scala:1944`). This is the same shape, one field over:

```scala
audienceRegions: Set[String]   // ISO 3166 or city ids, same closed vocabulary
```

Declared at site setup, surfaced in the existing site-approval review.

## City granularity is supported here

**The cost of granularity lives in extraction, not representation.** Content
location must resolve arbitrary place names out of arbitrary text automatically
— that needs disambiguation ("Springfield, MA or IL?"). A site audience is
*declared once by a human who already knows the answer*, from a search box. Hard
for a classifier, trivial for the publisher picking their own town.

Codes carry their ancestor chain, so a campaign targeting `{JP}` still matches a
Kamakura site.

## Eligibility, not relevance

Content location is a *relevance* signal and decays by ancestor hops. Audience
is an *eligibility* gate and is binary — a `{JP}` campaign on a `{JP-13}` site is
fully eligible, not 0.7×. Ancestor matching still applies; score decay does not.

## Declaration is optional, and most sites will not declare

`audienceRegions` defaults to empty and stays empty for most inventory. A city
news site declares Kamakura; a sports news site has no geographic audience and
declares nothing. That is the expected steady state, not a gap to close.

**Empty means *unknown*, not "everywhere".** An audience-targeted campaign is
**not** eligible on an undeclared site:

- it is what the advertiser asked for — "JP readers" should not be satisfied by
  a site that never claimed any;
- it keeps compliance/serviceability honest;
- it makes declaring **unlock** demand, so the incentive is aligned.

An undeclared site loses nothing else: it still receives all non-audience-
targeted demand, and **tier 1 still works there**. A sports site's articles carry
places anyway (Hanshin Tigers → JP-27, a World Cup piece → QA).

**Never auto-declare.** The setup form may *suggest* a region from
`get_locale()`, the TLD, or the publisher org's country, but the publisher must
confirm or clear it. An inferred audience presented as a declaration is the same
confidently-wrong failure as reading a local-SEO plugin's business address.

## The claim is labelled

Advertisers see *"publisher-declared audience: JP — unverified"*. Same contract
as `data-section`: an interested claim, visibly a claim, so the buyer can price
the risk and exclude bad inventory via the advertiser blocklist that already
exists.

Declaring unlocks demand, so there is real pressure to inflate. The
quality-adjusted auction reduces what an inflated claim earns (mismatched
creatives get poor CTR, so the site wins less and clears lower) but **does not
make lying unprofitable** — the site still went from zero of that demand to some
of it, and the advertiser eats the cost. The auction is not the answer. Tier 3
is.

---

# Tier 3 — Observed site audience

The verification tier. Aggregate, site-level, country-granular, derived from
real reader IPs and never retained per reader.

## How it is computed

```
beacon request (imp / click / cta / mount heartbeat)
   │
   ├─ extractClientIP                  ← already happens, TrackRoutes.scala:76
   ├─ IpClassifier binary search       ← already happens
   │     today: → datacenter?  (fraud hygiene, cols(2)/cols(4))
   │     add:   → country code         cols(3) of the iptoasn TSV,
   │                                   currently parsed and DISCARDED
   ├─ increment in-memory counter (siteId, country, today)
   └─ IP discarded                     ← already happens
```

Counters flush every N minutes to `site_audience_daily(site_id, country, day,
count)`. **Only aggregates ever touch disk** — no per-event country is
persisted, so no row anywhere describes one reader.

The data source is the iptoasn TSV already downloaded for fraud hygiene.
`IpClassifier.load` currently reads `cols(2)` and `cols(4)` and drops `cols(3)`
(`IpClassifier.scala:110-121`). Keeping it is one more parallel array and one
more array read per lookup. No MaxMind, no licence, no new download.

Mount heartbeats fire even on no-fill, so a site with poor fill still has a
denominator.

## Suppression — what gives tier 2 teeth

Once a site has `observed ≥ MinSample` over the rolling window:

- observed country shares are **authoritative at country level**;
- a declared region whose country is **not** in the observed set above a floor
  share is **suppressed from matching** and flagged for operator review;
- declared regions below country level (cities) inherit their country's
  verification status.

This is the enforcement the auction could not provide. A site declaring `{JP}`
whose traffic is 5% JP does not merely earn less — it stops matching `{JP}`
demand.

Below `MinSample` the site is *unverified*, not *rejected*: declaration still
matches, still labelled a claim. Precedent for the floor exists — market rates
already use `MinSample 100`.

## How tier 3 interacts with the other two

**Tier 3 → tier 2: strong, and the reason tier 3 exists.** Suppression, above.

**Scope suppression to audience eligibility only.** A site that overstated its
audience still publishes real articles about real places, and must keep
receiving tier-1 content-targeted demand. Blanket-excluding a suppressed site
from all geographic demand is over-punishment and costs the advertiser relevant
inventory.

**Tier 3 → tier 1: none, deliberately.** They are orthogonal. An article about
Kyoto is about Kyoto regardless of who reads the site, and a JP-audience site
writing about Paris is normal travel publishing, not a contradiction. There is
no consistency check to make between them.

Reject the tempting version: using observed audience to *weight* content places
("this is a JP site, boost JP places"). It is backwards — it would make a
Japanese site's Paris article harder to match with Paris demand, which is
precisely the demand that article should attract.

**No measurement death spiral.** Suppression reduces fill, which reduces
beacons, which could shrink the sample that caused the suppression — leaving a
site unable to recover. It does not happen: mount heartbeats fire even on
no-fill and sit inside the same `extractClientIP` block as billable beacons
(`TrackRoutes.scala:76`, `:260`), so the denominator survives regardless of
fill and a site whose traffic genuinely shifts can un-suppress.

## Limits, stated plainly

- **Country only.** iptoasn has no subdivision. A Kamakura declaration is
  verifiable as "is your traffic Japanese?", never as "is it Kamakura?"
- **A minimum-sample floor is mandatory, not optional.** A site with three
  readers a day has an "observed distribution" close to a per-reader disclosure.
- **IP geo measures egress, not residence.** VPNs, corporate proxies, tourists,
  expats. Precise measurement of a proxy for the thing advertisers want.

## Why this is worth doing anyway

It converts a **cheap lie into an expensive one**. Faking a declaration is typing
"JP" into a form. Faking observed traffic means generating JP-egress traffic at
volume — which is exactly what `IpClassifier`'s datacenter-ASN marking and the
rest of the fraud stack already exist to catch.

## What it costs

**These counters become money-load-bearing.** That is a real change of status
from an audit signal, and it raises the bar on correctness, on the sample floor,
and on manipulation resistance.

**`GPC.md` needs an honest rewrite, not a footnote.** The auction still has no
viewer field, and no per-viewer record exists. But the auction's *inputs* now
include a statistic derived from viewers — different in kind from today. That
doc's whole posture is "follow the code and check", so this must be described
plainly rather than discovered.

---

# Campaign targeting

Two set-valued fields plus one boolean on `CampaignEntity.State`, all with
semantics cloned from `siteAllowlist` — already exactly this shape: a set-valued,
additive `where` filter that does not override the `what`.

```scala
placeTargeting: Set[String]        // matches page places       (tier 1)
audienceTargeting: Set[String]     // matches site audience     (tiers 2+3)
requireVerifiedAudience: Boolean   // default false
```

```scala
// CampaignEntity.State.canBid — alongside the existing siteOk
val placeOk    = placeTargeting.isEmpty    || placeTargeting.exists(pagePlaces.contains)
val audienceOk = audienceTargeting.isEmpty || audienceTargeting.exists(siteAudience.contains)
```

**One audience field, not two.** Advertisers do not care whether a site's
audience came from a declaration or from counters — they care whether it is
trustworthy. `siteAudience` is the *effective* set, resolved server-side from
declared + observed with the suppression rule above. `requireVerifiedAudience`
is the risk knob: set it and only observation-backed inventory matches, at the
cost of reach.

`requireVerifiedAudience = true` combined with a city-level target is
contradictory (cities are unverifiable). **Reject at campaign save** with a clear
message rather than silently degrading to the country.

Place and audience are deliberately **separate fields**: they answer different
questions and an advertiser routinely wants one without the other. A Paris hotel
buys `place={FR}`. Japanese travel insurance buys `audience={JP}`. JAL buys both.
All three AND with category matching.

## Hierarchy

Reuse `AuctioneerEntity.expandWithHops` and `CandidateLogic.AncestorAffinityDecay`:

- page places and site audience both expand to ancestors with min hops
- match = non-empty intersection with the campaign's set
- **tier 1 only**: score decays `0.7^hops`, best (min-hop) member wins, so a
  40-prefecture set does not outrank a 1-prefecture set by breadth
- tier 2/3: binary, no decay

`{JP}` matches a JP-13 page and a Kamakura site. `{JP-13}` does **not** match a
page or site that only says "Japan" — no descendant match, consistent with how
categories already behave. An advertiser wanting both writes `{JP-13, JP}`.

## The two paths that must stay in sync

Set-valued eligibility is evaluated twice, and `siteAllowlist` shows where:

1. **Live ask** — `CampaignBidRequest` → `State.canBid`
2. **Standing bid book** — `CategoryBidderEntity.answerFromBook`, which
   replicates eligibility without asking the campaign, which is why `BidQuote`
   carries `siteAllowlist` verbatim

`BidQuote` needs `placeTargeting`, `audienceTargeting` and
`requireVerifiedAudience` for the same reason, and `CategoryBidRequest` must
carry `places` and `siteAudience`. Miss this and targeted campaigns get wrong
answers from quotes up to `BookHardTtlMs` (10 min) old.

## Eviction

`CampaignChanged` already carries `siteAllowlist` and `targetCategories` so
per-site auctioneers can detect a narrowing and fire `EvictCampaignFromSlots`.
The new fields need the same treatment, **in both directions**:

- a campaign that drops JP-13 must stop serving on Tokyo pages immediately
- a site whose `audienceRegions` is edited — or whose declaration is
  **suppressed** by tier 3 — must evict campaigns that no longer qualify

---

# Advertiser side

## Targeting UI

The pattern already exists in `platform/templates/advertiser/campaigns.html`
three times over. Clone it rather than inventing anything:

| Existing | Line | Geo equivalent |
|---|---|---|
| Category autocomplete → `/api/taxonomy/categories?q=` | 158, 542 | `/api/places?q=`, backed by the shipped tables |
| Site-allowlist chip picker → hidden `siteAllowlist` input | 213, 598-607 | two pickers writing `placeTargeting` and `audienceTargeting` |
| `/advertiser/category-availability?categories=` | 151, 535 | extend with geo params |
| `/advertiser/market-rates-hint?categories=` | 726 | extend so pricing reflects the narrowed pool |

Publisher and advertiser use the **same** `/api/places` lookup — the publisher to
declare, the advertiser to target — so both sides speak one vocabulary.

## Silent zero-delivery is the #1 failure mode

Line 780 already carries the right warning for categories:

> *"No publisher inventory matches these topics yet — a campaign targeting only
> these topics will not serve today."*

Geo needs the same, and it matters more here. There is prod evidence: the
market-rates amber tier is **already dormant** because sites have not declared
`taxonomyIds`. Audience targeting hits exactly that wall — most sites will never
declare (by design, see tier 2), so early geo campaigns will find nothing. An
advertiser who is not told *why* concludes the product is broken.

## `requireVerifiedAudience` needs a reach delta, not a checkbox

This is the only place the tier-2/tier-3 distinction should surface to
advertisers. Everywhere else they see one effective audience.

```
☐ Verified audience only
   Verified:            2 sites · ~400 imp/day
   Including declared: 11 sites · ~3,100 imp/day
```

Without the numbers the toggle is a superstition knob.

## Reporting must be honestly labelled

A geographic cut may say **"impressions on JP-audience inventory"** or
**"impressions on JP-13 content"**. It must never say **"delivered to people in
Japan"** — we do not know that, and by design never will. The advertiser-reporting
dimensions (`campaign_dim_daily_stats`) gain a geo cut; the column headers carry
the qualification.

## Expectation setting at campaign creation

A narrowly-targeted campaign paces against the site-wide arrival rate and will
underdeliver (see Pacing). Say so at save time rather than letting the advertiser
discover it from a spend chart.

## Blocklist interaction

An advertiser who concludes a site's declared audience is bogus excludes it via
the existing advertiser blocklist. Surface the site's declared-vs-observed
audience in that UI so the decision is informed.

# Approval

Unchanged, and already correct. Approval is per-`creativeId` per-site
(`AdServer.persistedApprovedIds`), so one creative serves across a campaign's
whole target set, and explicit regional variants are separate `creativeId`s with
their own pending queue rows — independently reviewable by construction.

**One hole to close regardless.** Auto-approve trust anchors
(`trustedCampaigns` / `trustedDomains`, `AdServer.scala:3702`) auto-approve any
new creative from a campaign or landing domain the publisher previously
approved. A regional variant would skip the queue. Opt-in and off by default,
but it is exactly the "regional variant inherits an approval" failure this design
forbids.

The approval queue must also **display** the creative's geographic targeting, or
"independently reviewable" is true in the data model and false at the desk.

# Pacing

No new machinery. A campaign with a narrow target set is eligible on fewer pages
and paces against the site-wide arrival rate
(`SelectionContext.requestArrivalRate`) — identical to a narrowly
category-targeted campaign today. Worth surfacing in the UI; not worth a
per-region traffic model.

# Granularity — why each tier stops where it does

Three different reasons, and only one is about privacy.

- **Tier 1 stops at subdivision — cost, not principle.** ISO 3166-2 is the last
  level with a small, stable, standardised code set. Shipping `cities5000`
  removes the *vocabulary* objection but not the *disambiguation* one. Revisit
  when demand justifies it; the door is now cheap to open.
- **Tier 2 reaches city — because it is declared, not inferred.** See above.
- **Tier 3 stops at country — data.** iptoasn has no subdivision. Going finer
  means MaxMind City: licence, monthly updates, and a refresh cron.
- **Per-request viewer selection: narrowing is disqualifying.** The privacy
  argument gets *monotonically stronger* with granularity — country is a large
  anonymity set, a postal code is thousands of people, a radius can be one. And
  the use case that most wants it (proximity) is useless at subdivision
  granularity and only works at the granularity that most breaks the model.

# Why not per-request viewer selection

Recorded here because the absence has to be defensible, in the style of
`modules/api/src/main/scala/promovolve/api/GPC.md`.

1. **It cannot be a bid-time gate.** The auction is periodic and runs ahead of
   the request; when a `BidQuote` is written the viewer does not exist. Viewer
   region could only subtract from a pool already matched, priced and truncated
   without it — burning `CategoryBidderEntity` truncation budget on every page
   and discarding the result. All three tiers gate *before* truncation. This
   asymmetry is structural, not incidental.
2. **PHP resolution is destroyed by page caching**, and the failure is silent —
   it reads as "everyone in the world gets Tokyo ads". Even the dedicated
   WordPress plugins concede this: Geolocation IP Detection's own guidance is to
   *disable caching on pages that call geo functions*.
3. **It is not obtainable at subdivision granularity** where it would have to
   come from. `CF-IPCountry` gives country; subdivision needs Cloudflare
   Enterprise or a licensed MaxMind City DB, neither of which exists on the
   shared hosting our publishers use.
4. **It falsifies a live, checkable claim.** `GPC.md` invites the reader to
   follow `BatchServeReq` into `AdServer.BatchSelect` and look for a viewer
   field. That absence is why we serve under `Sec-GPC: 1` rather than returning
   204 — and why every Brave and DuckDuckGo reader is not a guaranteed zero-fill.
5. **Coarseness does not launder it.** Choosing a creative from an attribute of
   the person is user-level differentiation regardless of granularity or
   retention. Per-campaign membership booleans do not survive composition: the
   union across campaigns with different sets reconstructs the region.

Rejected variant: ship the target sets to the browser and evaluate membership
locally. It leaks every advertiser's targeting to the page, breaks second-price
clearing (the server must know the winner to price it against the runner-up),
breaks `TryReserve` as the money gate, and lets a hostile page claim any region.

**What tier 3 changes about this argument.** It does not weaken it. Tier 3 uses
the same underlying data — reader IPs — but aggregates it to the inventory
before anything reads it. The auction still has no viewer field; there is still
no per-viewer record; the answer still does not vary by who is asking. Every
numbered objection above survives tier 3 intact, which is the test that says the
line is drawn in the right place.

# Implementation sequence

Ordered by cost, not by tier number. **Tiers 2 and 3 ship before tier 1** — they
need no LLM work and cover most advertiser intent.

**1. Places vocabulary — DONE.** `scripts/build-places.sh` +
`scripts/build-places.mjs` + `places/*.tsv` + `taxonomy/Places.scala`
(`get`/`contains`/`validate`/`ancestors`/`expandWithHops`/`nameIn`/
`displayName`/`search`) + `PlacesSpec` (19 tests). `NOTICE` carries the
attribution. Pure data and pure functions; nothing wired into the auction yet.

**2. Plugin: widen the term harvest — DONE (v0.3.0).**
`promovolve_declared_topic()` now reads every public, UI-visible taxonomy on
the post type, not just `category`/`post_tag`; `post_format` is denied by name
(a presentation choice, not a subject) and plumbing taxonomies fail the
public/`show_ui` test structurally. Order is `category`, `post_tag`, then the
rest alphabetically, so the attribute value is stable across requests. New
`promovolve_topic_taxonomies` filter for a public-but-not-topical taxonomy.

Two things surfaced while building it. Terms are now taken **round-robin**
across taxonomies — concatenating them let a post with eight tags push its
`destination` terms past the cap, so the one taxonomy carrying the page's
location never reached the server at all. And the cap rises 5 → 8, still far
inside the server's own 200-character `sanitizeHint` bound.

`integrations/wordpress/tests/topic-test.php` (10 assertions, no WordPress
required — it stubs the core surface) is now a gate in `build-zip.sh`, since
`php -l` only proves the file parses and a hint that is merely *thinner* than
it should be still looks fine.

**3. Tier 2 — declared audience — DONE.** `audienceRegions` on `SiteConfig`
(default-empty, Jackson-safe), validated through `Places.validate` on write.
Pushed to `AuctioneerEntity.UpdateAudienceRegions` on every config apply AND
re-armed on `AuctioneerStarted` — the auctioneer holds it in memory, so an
auctioneer-only restart would otherwise bid as if the site declared nothing.
Carried on `CategoryBidRequest.siteAudience` and logged at the bidder; nothing
gates on it until step 4.

`GET /v1/places` serves the type-ahead for both sides, with a `codes=` lookup
mode so stored selections render as "Kanagawa" rather than "JP-14". The
publisher picker on `sites.html` posts to `/publisher/sites/audience`;
`SiteEntity.mergeAudienceRegions` holds the None-vs-Some(empty) rule.
`SiteAudienceSpec` (8 tests) pins it.

**Correction to this plan: there is no site-approval review to display in.**
Operator approval *creates* the site (`siterequest.Service` POSTs
`/v1/publishers/{id}/sites` with empty config), so the approval gate runs
strictly before any declaration can exist. Reviewing an unverifiable claim at
that point would add ceremony without information anyway — declared-vs-observed
only becomes meaningful in **step 6**, which is where the operator surface now
belongs.

The TLD suggestion is a *prompt beside an empty picker*, never a pre-filled
value: a guess from the domain presented as the publisher's own declaration is
the same confidently-wrong failure as reading a local-SEO plugin's business
address.

**4. Campaign audience targeting — DONE.** `audienceTargeting` on
`CampaignEntity.State`, gating `canBid` and `bidRejectReason`
(`BidRejectReason.AudienceNotAllowed`, checked AFTER `SiteNotAllowed` so an
advertiser debugging media targeting is not misdirected). `siteAudience` rides
`CampaignBidRequest` and the internal `BidRequest` (it has to survive the
advertiser-budget hop). `BidQuote.audienceTargeting` + `answerFromBook` cover
the book path; `CampaignChanged.audienceTargeting` threads through
`CampaignReady` → `CategoryBiddersAcknowledged` → the topic for eviction.

**The two paths share one predicate rather than two implementations.**
`CampaignEntity.audienceAdmits` is called by both `State.canBid` and
`answerFromBook`, so book-vs-live drift is not expressible. `siteAllowlist`, the
precedent, is reimplemented in both — that is exactly the risk this avoids.
`AudienceTargetingSpec` asserts agreement across the full 6×5 matrix of
targeting sets and declarations.

The matching rule lives in `Places.targetingMatches`: the *inventory* side
expands to ancestors, the targeting side does not. `{JP}` matches a Kamakura
site; `{JP-13}` does not match a site that only says `JP`. Non-empty targeting
against an empty declaration is false — the strict-unknown rule falls out of the
expansion rather than needing its own branch.

Eviction runs **both directions**: a campaign narrowing its audience off a site
takes the same whole-site eviction as a `siteAllowlist` narrow, and a site
editing its own declaration now triggers a re-auction (step 3 deliberately did
not, since nothing consumed the value yet).

Two incidental findings: `Campaign` hit spray-json's `jsonFormat22` ceiling, so
its format is now hand-written; and `CampaignEntity`'s config-change detector
needed `audienceTargeting` added or the directory would never republish and no
auctioneer would ever evict.

**5. Tier 3 — observed counters — DONE (read-only).** `IpClassifier` keeps
`cols(3)` (`countryOf`, one more parallel array and one more array read;
iptoasn's literal `"None"` normalises to absent so it cannot invent a country).
`RequestHygiene.countryOf` + `hasDb` expose it from the single loaded db.

Counted on the **mount beacon only** — it fires once per pageview regardless of
fill, so the denominator survives a poorly-filled site, which is what stops
suppression from starving the sample that caused it. Counting on impressions
would bias toward filled pageviews.

`AudienceObservationCounter` is the privacy boundary: it takes
`(siteId, country)` and nothing else, holds `LongAdder`s keyed by
`(site, country, day)` (UTC), and can only emit sums. It drains-and-resets on a
5-minute flush to `site_audience_daily`, whose writes are **additive**
(`count = count + EXCLUDED.count`) because every API pod flushes its own partial
tally — a last-writer-wins upsert would silently undercount a multi-pod cluster.
Migration: `scripts/migrations/2026-08-20-site-audience-daily.sql`.

Counting is gated on the ASN db being loaded: without it every lookup returns
None and a tally of zero is indistinguishable from a site with no foreign
readers. Better to not measure than to publish a confidently empty
distribution.

`GET /v1/publishers/{id}/sites/{siteId}/audience-observed` returns
declared-vs-observed with a `sufficientSample` flag at `MinSample = 100`; the
publisher's own sites page shows the top three shares. **Nothing gates on any
of this** — that is step 6.

Surfaced to the publisher rather than only the operator: they can self-correct
before anything suppresses them, and admin view-as gives operators the same
view. Below the sample floor nothing is shown at all, so a publisher is never
invited to "correct" a true declaration against noise.

**6. Suppression + `requireVerifiedAudience` — DONE.** `AudienceVerification`
holds the whole rule as pure functions: `MinSample = 100`, `MinShare = 0.05`,
`effectiveAudience`, `suppressed`, `isVerified`,
`verifiableAtCountryGranularity`. `SiteEntity` re-reads the 30-day aggregate on
a staggered 15-minute tick, suppresses the declaration against it, and pushes
`UpdateAudienceRegions(effective, verified)` — reusing step 3's channel and step
4's re-auction-on-change, so site-side eviction needed no new machinery.

`requireVerifiedAudience` rides `CampaignEntity.State`, the bid request, the
internal `BidRequest` hop and `BidQuote`; `audienceAdmits` grew the dimension so
the book and live paths still cannot disagree, and the agreement matrix now
covers all four combinations of require/verified.

**Suppression is deliberately lenient** (5% share). Wrongly cutting an honest
publisher's demand is worse than letting a marginal claim through: the
advertiser has two other defences (the blocklist and `requireVerifiedAudience`)
and the publisher has none. This catches the site claiming five markets it has
no readers in, not the one whose traffic drifted.

Three fail-open decisions, all deliberate: a DB read error keeps the last known
observation rather than suppressing (an unrelated outage must not cut an honest
publisher's demand); below `MinSample` the declaration stands untouched
(unverified is not refuted); and `lastObserved` is ephemeral, so a restart
serves unsuppressed until the first tick.

Campaign-save validation rejects `requireVerifiedAudience` combined with any
target finer than a country, on **both** create and edit — the edit path merges
the patch onto current state, so turning the flag on without touching targets,
or adding a city target to an already-verified-only campaign, both fail.

**`GPC.md` rewritten in this step**, as required. It now describes the counters
in full, states plainly that the honest claim changed from "no viewer-derived
data exists anywhere" to "none reaches selection and none is per-viewer", and
extends its own falsifiability test to the new code path.

**7. Tier 1 — classification carries places — DONE.** The classifier now
answers "where is this page about?" alongside "what is it about?":
`IABTaxonomy.analyze` returns `Analysis(categories, places)`, the prompt asks
for ISO codes with an explicit "empty is correct" instruction, and every code
is validated against the shipped table (`Places.validate`) before it goes
anywhere. `analyzeTaxonomy` stays as a categories-only wrapper.

**No place fallback on LLM failure.** A guessed category keeps the auction from
starving; a guessed *place* would put an advertiser's geographic buy on a page
nobody established is about that place. The failure path returns
`Analysis(fallbackCategories, Nil)`.

Plugin **v0.4.0** sends `data-place` from location-ish taxonomy slugs
(`destination`, `location`, `city`, …, filterable via
`promovolve_place_taxonomies`) with WordPress's own `geo_address` meta as a
fallback. Names, never codes — a publisher-supplied ISO code would be an
unverified value dressed as an authoritative one.
`ClassifyPageTextReq.place` is `Option` (spray ignores defaults);
`ClassificationEntry.places` defaults to empty (Jackson-safe, no migration).

**The audit found the real trap.** `AuctioneerEntity.lastPage` is a
`(categories, slots, ts)` tuple, so a restored or re-auctioned page would have
silently dropped its places — step 8 would have started on a latent bug. Places
now ride `PageCategoriesClassified` and a parallel `lastPagePlaces` map, written
at all four sites that populate `lastPage` and pruned wherever it shrinks.

*Deliberate debt*: the parallel map can drift from `lastPage`. Folding both
into a `PageContext` case class is the right fix and the compiler would find
every site — but `lastPage`'s shape is load-bearing in ~45 places including the
restore/cleanup interplay that already caused a live incident, so that refactor
deserves its own change rather than riding on a feature. `prunePlaces()` is the
containment; nothing else removes from the map.

**8. Content place targeting — DONE.** `placeTargeting` on
`CampaignEntity.State`, gated by `placeAdmits` (the shared predicate, called by
both `canBid` and `answerFromBook`) with `BidRejectReason.PlaceNotAllowed`.
`pagePlaces` rides `CategoryBidRequest`, `CampaignBidRequest` and the internal
`BidRequest` hop; `BidQuote.placeTargeting` covers the book path.

**Gate returns a distance, not a boolean.** `placeAdmits` yields `Some(hops)`,
which travels on `CampaignBidResponse` → `CampaignBid` → `Candidate.placeHops`
→ `CandidateLogic`, where it decays the selection prior by `0.7^hops` beside the
existing taxonomy decay. That is the tier-1/tier-2 difference made concrete:
audience is binary eligibility, content place is relevance. A campaign targeting
`{JP}` still bids on a Kamakura article — that reach is deliberate — and loses
the slot to one targeting Kamakura.

Untargeted records `Some(0)`, not a miss: no constraint is a perfect fit, not a
distant one, or every campaign without place targeting would be quietly
penalised on any page that has places.

**Eviction is PAGE-scoped here, unlike the audience narrow.** Dropping a reader
population means the campaign no longer targets the SITE; dropping a place means
it no longer targets some ARTICLES. `placeEvictionSlotKeys` removes only the
pages that stopped qualifying, and never touches a page with no places — silence
from the classifier is not a contradiction.

One trap avoided: `Candidate` persists as JSON in `pending_selection`, and
spray's `jsonFormatN` rejects missing fields. `placeHops` joins `ancestorHops`
in the read-side default patch — without it every pre-existing approval card
would have failed to load.

**9. Dashboard — MOSTLY DONE.** The pickers shipped with their own steps
(audience in 3, place in 8, site declaration in 3). What this step added is the
inventory feedback:

`GET /v1/geo-availability` counts sites matching a campaign's audience and place
targeting, using the SAME predicates the auction uses so the number an
advertiser sees cannot disagree with what actually bids. It reads a new per-site
DData summary (`SiteEntity.SiteGeoKey`) — necessary because neither signal has a
global index: declarations live in each site's config and page places in its
classifications.

The campaign form now warns **before saving** when either half matches nothing,
and says WHICH half — an advertiser told only "no inventory" cannot tell whether
to widen the place, drop the audience, or wait for publishers to declare. It
also distinguishes "nothing matches yours" from "nothing has been declared at
all", which are different problems.

`requireVerifiedAudience` shows its reach cost in numbers (`N verified · M
including declared`) rather than being the superstition knob this document
warned about.

A lookup failure renders NO warning rather than zeros: a wrong "no inventory" is
worse than silence.

**Not done: geographic targeting on the approval-queue row.** The card is built
from `PendingSelectionStore` rows plus an advertiser-directory lookup that
returns id→label only, so campaign targeting is not reachable without extending
the core pending-approval response — a third plumbing chain that belongs in its
own change. Until then "independently reviewable" holds in the data model (each
regional variant is a separate `creativeId` with its own queue row) but the
publisher does not see the targeting at the desk.

**Also not done: the geographic reporting cut**, so the honest-labelling rule
("impressions on JP-audience inventory", never "delivered to people in Japan")
is documented but not yet enforced by any UI.

**10. Trust-anchor decision — DONE, and it reverses this document's earlier
recommendation.**

The proposal was to break a trust anchor when a creative's campaign targets a
different place or audience than the anchor was granted under. On inspection
that does not survive asking what approval is FOR. The publisher is judging the
creative's **content and suitability for their site**; geography changes neither.
It changes which of their pages it runs on — their own inventory decision, not a
content one. A rule that auto-approved a seasonal variant but queued a regional
one would be arbitrary, and an arbitrary rule in an approval path is worse than
no rule.

**What actually protects the constraint** ("a regional variant must not bypass
approval by being treated as an already-approved creative") is identity, and it
was already true: approval is keyed per `creativeId` (`persistedApprovedIds`),
so a variant is a distinct creative with its own decision. Nothing inherits
another creative's approval. Auto-approve is a separate mechanism — opt-in,
off by default, and described to the publisher in `trusted.html` as exactly
"new creatives matching a trusted entry skip the approval queue". A publisher
who turns it on is choosing that, geography or no geography.

The work done here is making that decision checkable rather than asserted:
`AdServer.partitionAutoApprovable` is extracted as a pure predicate — this is
the one path where a creative reaches serving without a publisher looking at
it, and a rule that consequential should be readable on its own —
and `AutoApproveTrustSpec` (8 tests) pins the default posture, the trust paths,
the identity guarantee, and that `placeHops` does **not** affect the decision,
so the non-rule is a recorded choice rather than an oversight.

# Tests

- `PlacesSpec` — **written, 19 passing**: ancestor hops, min-hop expansion,
  closed-vocabulary rejection, ja resolution at all three levels, search by
  English and localised name, and a regression pin that `JP-13` is Tokyo
- `IABTaxonomyPlacesSpec` — **written, 10 passing**: documented and
  object-wrapped response shapes; empty array and absent key treated alike;
  malformed input never throws; the closed-vocabulary gate keeps only known
  codes; and the prompt frames a publisher place hint as an unverified claim
- Plugin: `tests/topic-test.php` — **extended to 17 passing**, covering place
  taxonomies, the `geo_address` fallback, taxonomy-beats-meta precedence, the
  filter hook, and archives declaring no place
- `CampaignEntitySpec` — empty set bids everywhere; non-empty gates; ancestor
  match; **no** descendant match; `PlaceNotAllowed` / `AudienceNotAllowed`;
  place and audience compose with AND, not OR
- `SiteEntitySpec` — `audienceRegions` declared, normalised, republished on edit
- `AudienceObservationSpec` — **written, 7 passing**: sums per site/country/day;
  malformed codes ignored; drain-and-reset so counts are never double-flushed;
  and the property that 10,000 observations collapse to ONE row — there is no
  arrangement of inputs yielding a per-reader artefact
- `IpClassifierSpec` — **extended**: `countryOf` for v4/v6, unrouted and
  non-address inputs, and that iptoasn's literal `"None"` never becomes a
  country
- `CategoryBidderEntitySpec` — book path agrees with live-ask path on identical
  inputs (the two-path sync is the likeliest regression)
- `AuctioneerEntitySpec` — narrowing on either side (campaign edit, site edit,
  tier-3 suppression) evicts from the affected slot keys
- Plugin: `tests/topic-test.php` — **written, 10 passing**; extend it with a
  `data-place` case when step 3 lands

# Open questions

- **Demand gate for places.** Classification is gated on campaigns having demand
  categories. If no campaign declares place targeting, extracting places costs
  tokens for nothing — but gating means a blank `places` on a fresh DB looks like
  a bug. Prefer: extract always (the call is already being made), gate only the
  matching.
- **Suppression thresholds.** What observed share is "consistent with" a
  declaration? What window? Needs real traffic before the numbers can be honest.
- **Subdivision variance.** Japan 47 prefectures, UK four countries plus 200+
  councils. "First-level subdivision" is not a uniform unit of advertiser intent.
