# Frequency capping

> Status: **server half BUILT** (2026-08-24): `CampaignEntity.FrequencyCap`
> (+ `State`/`CampaignInfo`/`UpdateConfig` tri-state), API DTOs
> (`FrequencyCapDto`, create/update/get; `impressions = 0` clears), `ServeRes.frequencyCap`
> (`FrequencyCapWire{n, windowMs}` via the widened per-winner `GetCampaign` ask),
> `BatchServeReq.excludeCampaigns` → `ExcludeCampaigns.merge` (≤ 32) into the
> hard exclusion; size-only logging. Specs: `CampaignFrequencyCapSpec`
> (rules + real-entity tri-state), `CampaignJsonSpec`, `ExcludeCampaignsSpec`,
> `AdServerBatchRetrySpec` pin-bypass case. **Tag half BUILT** (2026-08-24):
> `banner.ts` dispatches `impression` at the viewability/billing moment;
> `dogear-storage.ts` v3 adds the `impressions` store (`recordImpression`,
> `getImpressions`, 7-day retention, 500-row cap); `frequency-cap.ts`
> `cappedCampaigns` (policy = most recent record, exclusive window boundary,
> ≤ 32, most-recent-first); bootstrap records auction winners only (not
> pin-honoured), sends `excludeCampaigns`. `FrequencyCapWire` carries
> `campaignId` (ServeRes has no campaign id otherwise). vitest: `frequency-cap`,
> `dogear-storage` impressions, `serve-retry` body case. **Dashboard half
> BUILT** (2026-08-24): create form + inline Edit panel "Frequency cap"
> (No cap / 1 2 3 5 10 × per hour/day/week; `freqCapN`/`freqCapWindow`),
> Go proxy sends `frequencyCap` on create (n > 0) and on every edit-panel save
> (0 clears), EN/ja strings, advertiser guide row, `campaigns_render_test`.
> **All three halves built; rollout in progress.** Before this: the only cap was *one campaign per page per
> batch* (`AdServer.batchReserveWithRetry`); no `frequencyCap` field had ever
> existed (`git log --all -S frequencyCap` was empty).

## Decision

Cap how often one **browser** is shown ads from one **campaign**, with the
**browser holding the count** and the **server supplying the policy**. The ad
tag records each billed impression in its existing IndexedDB (next to the
dog-ear pins), evaluates the campaign's cap locally, and sends the server
only the list of campaigns it is declining — the same wire shape the server
already honours for off-page dog-ear pins (`excludedCampaigns`). No viewer
identifier is created, stored, or sent; the server never sees counts.

A campaign's cap is **N impressions per window**, window ∈ {1 hour, 24
hours, 7 days}, default *none*. It is an advertiser setting on the campaign
(create + edit), carried to the browser on every served winner.

## Why

**Why not an identity (WordPress-as-IdP, a cookie, an IP).** A cap needs
"this reader again". WordPress knows only its logged-in users — a rounding
error on a publisher's traffic — and the plugin prints page-cached markup
that cannot vary per reader anyway. A server-side viewer ID (cookie,
fingerprint, IP bucket) would be the profile this architecture is built not
to have: the serve path reads the **page**, never the viewer (`ServeRoutes`
GPC note, `GPC.md`, the fraud layer's "no identifier ingested"). An IP cap
is also wrong on the merits (NAT = one household or one office = one
reader).

**Why the browser.** The tag already keeps per-browser state in IndexedDB
(`promovolve-dogear`: `pins`, `ts_counted`) and already tells the server
"don't show me these" (`BatchServeReq.pins` → `excludedCreatives` /
`excludedCampaigns`). Frequency capping is the same gesture with a different
source — "I have seen enough of this campaign" instead of "I bookmarked this
one". Per-browser is also where the industry landed without third-party
cookies; it is a real cap, not a pretend one, with honest limits (below).

**Why the server supplies the policy but does not evaluate it.** Two
candidate designs:

| | A. browser sends counts, server decides | B. server sends policy, browser decides, sends only `exclude` (chosen) |
|---|---|---|
| What leaves the browser | per-campaign counts (and windows) on every request — a quasi-identifier | a short list of campaign IDs, same class as pins |
| Server-side state | the cap must be known at serve time → snapshot on `CandidateView` at auction (like `placeTargeting`) or an ask per candidate | none beyond the campaign field; merge into the exclusion set that already exists |
| Policy-change latency | immediate | loosening takes effect when records age out (≤ 7 d); tightening overshoots by ≤ 1 impression |
| Complexity | new auction-time field + comparison in `BatchSelectViewLoaded` | one new request field, one merge |

B wins on privacy and on touching the least code; its latency cost is
bounded and acceptable for a v1. A is recorded here as the fallback if
advertisers ever need instant policy changes.

## Non-goals

- **Per-person or cross-device capping.** Per-browser only. Clearing site
  data, private windows, and a second device reset the count. Documented,
  not hidden.
- **Publisher-side caps** ("no advertiser more than X/day to my readers").
  Same mechanism could carry a site-level policy in `ServeRes`; not in v1.
- **Per-creative caps.** Campaign is the unit of advertiser intent; a
  per-creative cap would let a campaign rotate creatives around it.
- **A "capped" reason in reporting / no-fill stats.** Nice later; v1 ships
  without it.
- Any change to billing, pacing, floors, the auction, dog-ear pricing, or
  the GPC posture.

## How it works

```
campaign.frequencyCap = {impressions: N, window: 1h|24h|7d}      (advertiser setting)
        │
        ▼  auction/serve unchanged
ServeRes.frequencyCap = {n, windowMs}   (alongside pinExpiresAt, per winner)
        │
        ▼  banner ≥50% visible → impression beacon fires (billing moment)
tag: record {campaignId, at, n, windowMs} in IDB store `impressions`     (NOT for pin-honoured renders)
        │
        ▼  next page load, before /v1/serve/batch (same place pins are read)
tag: for each campaign with records: count(at > now − windowMs) ≥ n  →  capped
     body.excludeCampaigns = [capped…]  (bounded, most recent first)
        │
        ▼
server: excludedCampaigns ++= body.excludeCampaigns   →  batchReserveWithRetry as today
        (hard exclusion from the auction pool; pins still honoured)
```

### Campaign setting (core + api + dashboard)

- `CampaignEntity.CampaignInfo.frequencyCap: Option[FrequencyCap] = None`,
  `FrequencyCap(impressions: Int, window: FrequencyWindow)` with
  `FrequencyWindow ∈ {Hour, Day, Week}` — persisted as **plain strings** in
  the event/state JSON (Jackson sealed-trait case-objects corrupt recovery;
  see the `feedback_jackson_sealed_traits` rule), default `None` so existing
  journals recover unchanged.
- `UpdateConfig.frequencyCap: Option[Option[FrequencyCap]] = None` — the
  existing tri-state convention (`None` = no change, `Some(None)` = clear to
  unlimited, `Some(Some(x))` = set).
  Campaign *creation* carries no config payload (`AdvertiserEntity.CreateCampaign`
  → the API's immediate `UpdateConfig`), so the cap rides on that first
  `UpdateConfig` — nothing to add to the create command. `CampaignEntity` is a
  `DurableStateBehavior`: the only persisted shape is `State` (Jackson,
  default-`None` field is recovery-safe).
- API: `CreateCampaignRequest` / `UpdateCampaignRequest` / `Campaign`
  response gain `frequencyCap: Option[FrequencyCapDto]` — **`Option`**, never
  a defaulted field (spray `jsonFormatN` ignores case-class defaults; an
  older client omitting it would fail to parse otherwise). Validation:
  `1 ≤ impressions ≤ 100`, window in the enum. **Clearing on PATCH**: spray
  cannot tell an explicit `null` from an absent field for `Option[Option[_]]`,
  so the wire convention is `{"impressions": 0, "window": "…"}` = clear; the
  route maps that to `UpdateConfig(frequencyCap = Some(None))`. `Campaign`
  responses write `frequencyCap: null` when uncapped (hand-written format).
- Dashboard: campaign create form + the inline Edit panel get "Frequency
  cap: at most ⟨N⟩ impressions per reader per ⟨hour|day|week⟩ · ☐ none".
  New strings go into the EN/ja catalogs (drift test). Help guide: one
  paragraph under the campaign settings, then `scripts/sync-help.sh`.
- Bid checker / campaign dims: no change (the cap does not affect
  eligibility to bid, only delivery to a given browser).

### Serve (api)

- `ServeRes.frequencyCap: Option[FrequencyCapWire] = None` with
  `FrequencyCapWire(campaignId: String, n: Int, windowMs: Long)` — the
  campaign id rides inside the policy because `ServeRes` carries none
  otherwise (it is already public in the signed beacon URLs and landing
  macros); populated per winner. Source:
  the campaign's current config. `campaignPinExpiresAt` already does one
  `GetCampaign` ask per winner for `pinExpiresAt`; widen that ask to return
  both values in one round trip rather than adding a second ask.
- `BatchServeReq.excludeCampaigns: Option[Vector[String]] = None`
  (**`Option`** — same spray rule). Server: validate shape, **cap at 32
  entries** (ignore the rest), and merge into the exclusion set passed to
  `BatchSelect` (`excludedCampaigns = fromPins ++ fromRequest`). The existing
  `BatchServe` log line may log `excludeCampaigns.size`; it must **not** log
  the IDs (they describe the reader's history).
- Nothing else changes server-side: the hard exclusion already removes the
  campaign from the pool before scoring; per-page cap, floors, Thompson
  sampling, clearing, reservation are untouched.

### Tag (platform/banner-bootstrap + banner-component)

- **Impression event.** `banner.ts` `fireImpression()` — the IAB-style ≥50 %
  viewability gate that already fires the billing pixel — additionally
  dispatches `new CustomEvent("impression", { detail: { creativeId }, bubbles: true })`
  once per mount. The bootstrap listens on the banner element in the same
  place it attaches the dog-ear listeners (`attachDogearListeners`'s
  sibling), so storage and network stay in the bootstrap and the banner
  stays presentation-only.
- **Storage.** `dogear-storage.ts` DB `promovolve-dogear` version 2 → 3,
  new object store `impressions` (`keyPath: "id", autoIncrement`), record
  `{ campaignId, creativeId, at, n, windowMs }`. Same deadline wrapper as
  every other IDB call (the WebKit hung-open problem). Retention: records
  older than **7 days** (the longest window) are swept on read, exactly as
  `getAllPins` sweeps pins; hard size cap 500 records (drop oldest). Pins and
  `ts_counted` are untouched.
- **What is recorded.** Only impressions of **auction** winners. A render
  that honoured a dog-ear pin (`dogear.honored === true` on the served
  slot, already known to the bootstrap via `processDogearResponse`) is **not**
  recorded — the reader asked for that creative; it is also counted
  exclusively in the `dogeared_*` columns server-side and never in primary
  metrics, so the two sides agree that a pinned re-encounter is not a
  "frequency".
- **Evaluation.** In the same pre-batch step that reads pins: group records
  by campaign; use the **most recent** record's `{n, windowMs}` as the
  campaign's policy; `capped = count(at > now − windowMs) ≥ n`. Send
  `excludeCampaigns` sorted by most recent impression, truncated to 32.
  Campaigns with `n` absent (policy removed) are never capped.
- **Pins bypass the cap.** A slot whose pin points at a capped campaign is
  still honoured: the exclusion applies to the auction pool, and pin
  honouring looks up `pinLookupPool`, the pre-floor eligible set, mirroring
  "pins bypass floor" (the `pinLookupPool` note in `batchReserveWithRetry`;
  the `format/dog-ear.md` path that comment cites no longer exists). Implementation must keep the
  capped IDs out of the pin lookup; confirm against
  `batchReserveWithRetry`'s pinned-slots-first ordering when building.
- **No change** to `/v1/classify-page`, heartbeats, or the WordPress plugin.
  The plugin prints the tag; it neither knows nor needs to know about caps.

## Interplay with existing rules

| Rule | Effect |
|---|---|
| One campaign per page per batch (hard) + 15 s `pageWinners` soft preference | unchanged; the cap is the cross-pageview complement |
| Off-page pins → `excludedCampaigns` | same set; capped campaigns are added to it |
| On-page pin honouring | bypasses the cap (reader intent) |
| Dog-eared re-encounters (`dogeared=1`, $0, exclusive counting) | not recorded, not capped |
| Advertiser blocklist / site allowlist / place targeting | orthogonal, evaluated as today |
| GPC / privacy posture | unchanged: still no viewer identity; the browser volunteers "not these" exactly as it volunteers pins |
| Pacing / budgets | a capped browser is simply not in that campaign's pool for that request; spend curves shift slightly toward breadth — intended |

## Limits, stated plainly

**Per browser, per publisher site — not cross-site.** The tag's IndexedDB
belongs to the publisher's origin (the page the tag runs in), not to
Promovolve, so a reader capped on one publisher starts from zero on the
next. A cross-site cap would need storage under a Promovolve origin — the
third-party identity this design refuses, and one browsers now partition by
top-level site anyway (ITP, Total Cookie Protection, Chrome storage
partitioning), so it would not work reliably even if wanted. Corollary:
`www` and apex are distinct origins to the browser although one site to
Promovolve; a publisher reachable on both gets two counters until they
canonicalise. Advertiser copy must say "per browser, per site", never "per
reader".

Per browser, not per person. Resets when site data is cleared; absent in
private windows after close; a phone and a laptop are two readers. A reader
who blocks storage is uncapped. A tightened cap can overshoot by one. These
are the same limits every cookieless cap has; the advertiser UI copy should
say "per browser".

## The cap refresh — why a removed cap needs a back channel

**Found live 2026-08-26.** The first cut said "a loosened or removed cap
takes effect as soon as one more ad from that campaign is seen", reading the
policy stamped on the browser's most recent impression record. That premise
cannot hold, and the loop is closed:

> at the cap → the campaign is in `excludeCampaigns` → the auction excludes
> it → it never wins → **no newer record is ever written** → the stamped
> policy never changes → still at the cap.

Winning once is the only way a new policy could arrive, and being capped is
exactly what prevents it. Turning the cap off in the dashboard therefore did
nothing a reader could see until the old records aged out of the *stamped*
window — up to a week on `per week`. The unit test passed only because it
hand-wrote a newer record; nothing in the running system could produce one.

So the browser asks. Alongside `excludeCampaigns` it sends `capCheck` — the
same campaigns, each paired with a creative of its own it actually saw — and
the response carries `capPolicies`, the CURRENT policy for each. `n = 0`
means the cap is gone. `applyCapPolicies` re-stamps every stored record of
that campaign, so the next `cappedCampaigns` call reaches the right answer.
No extra impression is served to learn this.

Three properties worth keeping:

- **The creative is the key AND the authorization.** Campaign entities are
  keyed `advertiserId|campaignId` and the serve path has no campaign→owner
  index, so the creative names its own owner (`creativeRepo`). It also means
  a policy is answered only when the creative really belongs to the campaign
  asked about — a client cannot enumerate caps it was never served.
- **Silence never lifts a cap.** A campaign whose policy could not be
  established — creative gone, mismatched, entity ask failed, unknown window
  — is *omitted* from `capPolicies`, and the browser's own stamp stands. A
  timeout must not hand an advertiser unlimited delivery.
- **Same bound as the exclusions it mirrors** (`CapRefresh.MaxEntries` =
  `ExcludeCampaigns.MaxEntries`): the two lists carry the same ids, so a
  client that cannot blank its auction with thousands of exclusions cannot
  spend the server's lookups either.

### The other half: an untouched budget vetoed the whole edit

Same report, different cause, and it is what actually kept the cap set. The
campaign edit form resubmits **every** field, so an untouched daily budget
arrives as a `ReplenishBudget` on every save. `CampaignEntity` refused any
budget that no longer exceeded the day's spend — including one that had not
changed — and the API applies the patch in sequence, so a budget rejection
aborted it *before* `configUpdateF`, which is where the frequency cap lives.
An exhausted budget therefore silently vetoed every other edit on the panel.

Asking for the budget a campaign already has is not a request for anything,
and is now a no-op success: nothing persisted, nothing published. The guard
still refuses a genuinely NEW budget that today's spend has passed.

## Tests

- `CampaignEntitySpec`: set / clear / round-trip through recovery with
  string-encoded window; `UpdateConfig` tri-state.
- API: create/update/get carry the field; validation bounds; a request
  without the field parses (older dashboards).
- `ServeRoutes` / `AdServerBatchRetrySpec`: `excludeCampaigns` merges into
  the hard exclusion; a 33-entry list is truncated; a pinned slot for a
  capped campaign still honours the pin; IDs are not in the log line.
- Bootstrap unit tests (alongside the `dogear-response` client-contract
  tests): record/sweep/size-cap; evaluation at the window boundary; pinned
  renders not recorded; policy-removed campaigns never capped; the request
  body carries `excludeCampaigns` only when non-empty.
- Hostile-env suite: a browser with IndexedDB blocked still serves (the cap
  fails **open**, never blank).
- `CapRefreshSpec`: the back channel is trimmed, deduped per campaign, and
  bounded at the exclusion limit.
- Bootstrap `cap refresh` tests: it asks only about campaigns it is actually
  declining, and with the newest creative it saw; a reported `n = 0` stops
  the decline on the next call; a tightened cap applies just as readily;
  campaigns the server said nothing about are left alone.
- `CampaignFrequencyCapSpec` (`ReplenishBudget` block): the budget a campaign
  already has is accepted even when the day's spend has reached it — equal
  amounts at different scales included — while a genuinely lowered budget
  below today's spend is still refused.

## Rollout

1. **Server first**: campaign field + API + `ServeRes.frequencyCap` +
   `BatchServeReq.excludeCampaigns` (ignored-if-absent). Old tags send
   nothing and keep working; spray ignores unknown JSON fields, so a new tag
   against an old server is also harmless — confirm in the API test.
2. **Tag** (`deploy.yml` publishes the bootstrap and banner bundles; the
   bootstrap job is gated on the api deploy precisely for this ordering).
3. **Dashboard UI + i18n + help**.
4. Watch: impressions-per-browser distribution is not observable
   server-side by design; verify with the Docker publisher demo (two tabs,
   same browser; cap 1/hour → second page load shows a different
   advertiser or no fill).

## Cross-site capping: ruled out

Per site is final (settled 2026-09-11), not a stage on the way to something
wider. Capping a reader across publishers means recognising the same reader
on different sites, and there is no way to do that here:

- **Browsers forbid it.** Storage is partitioned by top-level site (ITP,
  Total Cookie Protection, Chrome storage partitioning), so nothing the tag
  writes on one publisher is visible on the next.
- **Every working route is an identifier that follows the reader.** A
  third-party cookie on the ads host is the viewer identity this architecture
  refuses: it turns the structural "nothing to opt out of" (`GPC.md`) into a
  policy promise and needs consent in the EU, where capping is not "strictly
  necessary". It also only works where third-party cookies still do, which
  means Chrome and not Safari, Firefox or Brave. Fingerprinting is not an
  option at all.
- **The rest do not reach readers.** A reader login covers only the readers
  who sign up, which is almost none. Chrome's Shared Storage would have
  required moving the render into fenced frames, it only ever worked in
  Chrome, and Google has since announced its retirement.

What that costs is bounded by the targeting. Cross-site repetition in the
wider industry comes mostly from retargeting, where one reader is followed
to every site they visit. Promovolve is contextual: a campaign appears only
where the page matches its categories, so a reader meets it again only by
reading matching pages on several of our publishers. That can still happen,
up to N × the number of those publishers, but it is the exception.

If it ever becomes an advertiser's complaint, the only lever that keeps the
no-identity posture is an estimate rather than a cap. Lower the per-site N
for a campaign that runs on many sites in the same vertical. That bounds
the average exposure across sites, but it cannot bound any one reader's.

## Open

- Publisher-level cap (site policy in `ServeRes`) — same mechanism, later.
- A "capped" reason surfaced in advertiser reporting / heartbeat no-fill
  breakdown.
- Whether a loosened cap should be pushed (policy re-sent on a no-cap
  ServeRes for a *different* campaign? no — only the capped campaign's
  policy matters and it is by definition not served) — accept the latency.
- Per-creative caps if an advertiser asks.
