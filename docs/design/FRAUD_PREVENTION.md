# Fraud Prevention (In-House, Layered)

> **Status:** DESIGN — not yet built. Captures the 2026-07-22 design
> discussion. Build order is the layer order; each layer ships value on its
> own and none blocks the others' operation.
>
> **Related:** [`BILLING.md`](BILLING.md) (settlement + CLEARING account the
> money controls ride on), [`TIME_AND_TIMEZONES.md`](TIME_AND_TIMEZONES.md)
> (local-day settlement windows), [`PERIODIC_AUCTION_DESIGN.md`](PERIODIC_AUCTION_DESIGN.md)
> (where serve decisions happen), and the mount-heartbeat subsystem
> (`banner-bootstrap`, `mount_beacons`).

## Threat model — who actually attacks this network

PromoVolve's structure eliminates most of the fraud taxonomy that plagues
programmatic advertising before any code is written:

- **No reseller chain.** Every site is DNS/file-verified, admin-approved, and
  identity-pinned (host = site). "Mystery supply" — spoofed domains, laundered
  inventory, multi-hop resold impressions — cannot exist here.
- **Context, not audiences.** Nothing is bought on user data, so there is no
  cookie-stuffing, no data-leakage angle, no incentive to fake identity.
- **Human-gated supply and demand.** Sites and creatives pass approval gates;
  trust anchors are revocable.

What remains, in order of expected loss:

1. **Publisher self-inflation** (the primary adversary): a publisher inflating
   their own earnings — page auto-refresh, cheap purchased traffic, scripted
   engagement on their own slots. The publisher is *paid per billed
   impression*; this is where the incentive concentrates.
2. **Commodity bot noise**: crawlers, scrapers, headless browsers, datacenter
   traffic that isn't targeting us but still triggers serves and pollutes
   stats and spend.
3. **Advertiser-side griefing** (minor): a competitor burning a rival
   campaign's budget with fake engagement. Same detectors catch it from the
   other side.

Explicitly **out of scope**: nation-state-grade botnets on residential
proxies running real browsers. At this network's scale the economics of
mounting that attack don't close, and the money controls (Layer 3) bound the
loss even if someone tries.

## Design principles

- **Mark, don't block.** A request classified as non-human still gets a
  polite response (no-fill, or serve-without-bill) — it is *excluded from
  money and learning*, not rejected. Blocking generates false-positive
  support load and teaches adversaries the detector's edges; quiet exclusion
  keeps books honest while attackers burn effort against a mirror.
  (Established precedent: dog-ear re-views render but never bill.)
- **Protect the stats, not just the money.** Fraudulent events must be
  excluded from Thompson-sampling CTR/fold estimates and floor-sweep
  observations, or fraud trains the auction even when it isn't paid.
- **Robust statistics before ML.** Median/MAD z-scores against a site's own
  history and network percentiles. Models only if the labeled data ever
  demands them.
- **Principles are public, thresholds are not.** This doc (and the OSS repo)
  describe every mechanism; the numeric thresholds live in configuration.
  Transparency about *how* detection works doesn't require handing over the
  exact trip-wires.
- **Money is recoverable until released.** Detection can lag by a day when
  settlement can hold; nothing needs to be decided at request time except
  marking.

## Layer 0 — Request hygiene (serve + track path)

> **Status: BUILT 2026-07-22 (beacon side).** Detectors:
> `promovolve.fraud.{IpClassifier, BotUaMatcher, RequestRateGate,
> RequestHygiene, Suspect}` (all unit-tested). Marking is wired at the
> **beacon** (`TrackRoutes`) — where the real reader's IP/UA already
> resolve and where every billable/learnable event originates — so a
> datacenter/bot/over-rate source marks its impression, click, CTA, and
> fold events `suspect_reason`. Exclusion is live in `LearningEventLog`
> (no RecordSpend / RecordClick / RecordFold / floor obs for suspect),
> `DashboardProjectionHandler` (suspect events move no counter),
> `CreativeStatsSnapshotRepo` + every metering/market-rate/revenue SQL
> read (`AND suspect_reason IS NULL`). ASN db loads from
> `FRAUD_ASN_DB_PATH` (gzipped iptoasn TSV); absent → fails open, rate
> gate still active.
>
> **Deliberately deferred to Phase 0.1** (serve-path, not payout
> vectors): rate-capping `/serve/batch` itself, and gating the
> serve-time per-creative `recordImpression` denominator + budget
> reserve in `AdServer`. Threading a signed suspect code through the
> `BatchSelect` cross-node pipeline is the mechanism. Impact today: a
> bot serve still consumes a reservation (known reserve-leak residual)
> and dilutes the served creative's CTR denominator (advertiser stat
> noise, not a publisher payout) — both bounded, neither a self-inflation
> vector, which is why they wait.

Runs inline on the api pods where `/serve/batch` and the tracking beacons
land. Budget: microseconds, no network calls.

- **ASN classification.** Monthly-refreshed free ASN database (GeoLite2-ASN
  or ip2asn) loaded into memory at boot (same pattern as ServeIndex's local
  store). Every request gets an `IpClass`: `residential | datacenter |
  unknown`. Datacenter-class requests are marked `suspect(datacenter_asn)`.
- **UA hygiene.** Compiled matcher over known crawler/bot UA fragments and
  headless markers (IAB spiders-and-bots equivalent). Absent or degenerate
  UAs are suspect.
- **Rate caps.** Token-bucket per (IP, site) on serve and per (IP, token
  family) on tracking events. Over-cap requests are marked, not refused.

Marks travel with the request into the tracking event (new nullable
`suspect_reason` column on `tracking_events` — the journal builders must ALL
carry it; see the journal-field audit discipline). Billing, projections, and
learning filter on `suspect_reason IS NULL`.

## Layer 1 — Protocol invariants

> **Status: BUILT 2026-07-22.** `EngagementGuard` (sharded by `rid`, the
> reservation id signed into all three of a serve's imp/click/cta tokens
> — so `rid` IS the chain key) + `EngagementChecker` (routing facade,
> fail-open). Records each beacon's SERVER-arrival time; a click checks
> against its impression, a CTA against its click. Pure decision core
> (`onClick`/`onCta`) unit-tested. Wired into `TrackRoutes`: impression
> records, click/cta check, and the verdict folds into `suspect_reason`
> (`hygiene.orElse(chain)` — Layer 0 wins if it already marked). In-memory,
> TTL-evicted (30min) + per-partition size cap; fail-open on pod restart
> or guard error. **Off by default** (`promovolve.fraud.engagement-guard.enabled`);
> ships dark until Layer 0 proves stable.

- **Chain**: a beacon whose predecessor never arrived is marked
  `suspect(chain)`. (Soft signal — a lost impression beacon also trips it;
  Layer 2's ratios separate that noise from systematic abuse.)
- **Timing**: SERVER-side predecessor→event delta below a conservative
  floor (100ms default, well under any human) marks `suspect(timing)` —
  unforgeable, since the client never supplies the timestamps. Deltas will
  also feed Layer 2 as a per-site distribution (uniform robotic timing is a
  stronger signal than any single fast event).
- **Dedup** stays as-is (the replay guard — one billable event per token).
- **Deferred**: folds use a different token (hash, not `rid`) and are left
  out of chain/timing for now; the per-site timing distribution feed to
  Layer 2 lands with Layer 2.

> **Status: BUILT 2026-07-22.** Pure core `promovolve.fraud.FraudDetection`
> (robust median/MAD z-scores, `evaluate` + `assembleMetrics`, unit-tested)
> + `FraudFlagRepo` (two per-site/day SQL rollups over
> `tracking_events` × `mount_beacons`, idempotent flag upsert, review-queue
> reads) + `FraudDetector` cluster singleton (hourly timer, read-only over
> traffic, append-only to `fraud_flags`). Signals live now: suspect-share
> (hard threshold on Layer 0/1 marks), imp/pageview spike, CTR spike (both
> one-directional robust-z vs the site's OWN trailing history — a drop is
> never fraud). Every signal volume-gated so low-traffic sites can't trip.
> **Off by default** (`promovolve.fraud.detector.enabled`); needs the
> dashboard DB. `fraud_flags` table added (init-db + migration). Deferred to
> a later pass: network-percentile comparison, traffic-shape-deviation and
> event-timing-distribution signals, and local-day (vs UTC-day) bucketing.

## Layer 2 — Economics detector (batch, the layer that catches humans)

The publisher-self-inflation adversary uses real browsers; request hygiene
never sees them. Their tell is economic: ratios that drift from the site's
own baseline. All inputs already exist in TimescaleDB.

Per site, per local day (hourly refresh for the current day):

| Signal | Source | Tell |
|---|---|---|
| Impressions ÷ heartbeat pageviews | `tracking_events` × `mount_beacons` | Inflation without real pageviews |
| CTR / fold-rate vs network percentile | `tracking_events` | Scripted engagement |
| Traffic-shape deviation | learned shape baselines | Bought/scheduled traffic vs organic rhythm |
| Event-timing distribution | Layer 1 deltas | Robotic uniformity |
| Suspect-mark share | Layer 0/1 marks | Rising bot fraction |

Scoring: each signal → robust z-score against the site's trailing history
(median/MAD) and against network percentiles; a site crossing configured
thresholds on any signal (or a combined score) writes a row to a new
`fraud_flags` table with the evidence snapshot inline. This is a SQL job in
the projection tier — no new infrastructure.

> **Status: PARTIAL 2026-07-22.** The **review queue + enforcement** are
> built: core internal endpoints `GET /v1/internal/fraud-flags`,
> `POST .../fraud-flags/{id}/resolve` (released|confirmed), and a new
> surgical `POST /v1/internal/sites/{siteId}/suspend|resume`
> (`SiteEntity.SetSuspended` — freezes ONE site, not the whole publisher).
> Admin page `/admin/fraud` (RoleAdmin-gated, mirrors the site-approval
> queue) renders each flag's evidence with **Release** (false-positive
> label) and **Confirm fraud & suspend site** (resolve + surgical suspend,
> composed platform-side like the operator org-suspend). EN/JA + render
> test. **Phase 3.1 BUILT 2026-07-22:** the automatic settlement
> HOLD-in-CLEARING on flag and the monetary clawback (held and
> already-settled legs) now ship — see the Layer 3 section below. "Confirm"
> both stops future serving AND reverses the fraudulent earnings.

## Layer 3 — Money controls (rides existing settlement machinery)

> **BUILT 2026-07-22 (Phase 3.1).** The hold, release, and clawback are
> implemented in the platform billing engine (`platform/internal/billing/
> fraud_holds.go`): the settler consults the open-flag set and, for a
> flagged site, records a `fraud_holds` row instead of draining clearing to
> the publisher; `/admin/fraud` **Release** pays the held cells out and
> **Confirm** claws them back to the advertiser (plus a day-scoped reversal
> of any cells that settled before the flag landed). An admin-billing
> "Held" tile surfaces the held total. Five integration tests assert the
> reconciliation identity after hold / release / clawback / clawback-after-
> settle / day-scoping. The only follow-on is Stripe-style external payout
> reversal, which does not exist yet (payouts are still operator-manual).

- **Hold-then-release.** A flagged site's local-day settlement routes to a
  HELD state instead of payable — the CLEARING-account double-entry design
  already models money-in-transit, and governance-outranks-wallet is
  established precedent (org suspension). Unflagged sites are unaffected;
  there is no global payout delay.
- **Admin review queue.** A dashboard page in the approval-queue mold (SSE
  live updates, evidence panel rendering the `fraud_flags` snapshot:
  ratio charts against the site's baseline). Two verbs:
  - **Release** — pay out, and the flag becomes a labeled false positive.
  - **Confirm** — clawback (reverse the held legs), and the existing
    flag/revoke machinery fires: serving stops, trust anchors break.
- **Advertiser side:** confirmed-fraud impressions were never billed if
  marked in-flight; for Layer-2 catches (billed then held), the clawback
  reverses advertiser charges through the same journal — double-entry makes
  this an ordinary correcting entry, not a special case.

## Layer 4 — Feedback loop

Every admin decision labels a flag. A monthly look at
precision/recall per signal tunes thresholds (config change, no deploy).
If and when labels accumulate enough to justify it, a simple classifier can
replace the hand-set thresholds — but robust stats are expected to carry
this network for a long time.

## What we deliberately do NOT do

- **No edge-vendor bot scoring** (Cloud Armor / Cloudflare bot management).
  Ad requests originate from publishers' visitors — challenge pages are
  impossible on this path, which reduces every vendor to passive scoring
  we can replicate with ASN lists, while the primary adversary (real
  browsers, publisher-owned) is invisible to them anyway. Revisit only if
  volumetric abuse ever outruns Layer 0.
- **No CAPTCHA/challenges anywhere in the ad path.** Ever.
- **No IP storage beyond classification.** The ASN class and a truncated/hashed
  IP suffice for rate caps and evidence; raw IPs are not retained past the
  tracking-event retention window already in place.
- **No global payout delays.** Holds are per-flagged-site only; honest
  publishers keep the existing settlement cadence.

## Build order & effort

| Phase | Scope | Effort |
|---|---|---|
| 0 | ASN db + UA matcher + rate caps + `suspect_reason` through the journal + billing/learning filters | ~1 week |
| 1 | Chain binding + timing deltas | ~2-3 days |
| 2 | SQL detector + `fraud_flags` + thresholds config | ~1 week |
| 3 | Settlement hold + review queue UI + clawback | ~3-4 days |

Phase 0 alone removes the commodity-bot category; Phase 2+3 close the
publisher-inflation loop. Each phase is independently shippable and
independently verifiable (Layer 0/1 via synthetic traffic in the dev
cluster; Layer 2/3 via the scripted "cheating publisher" scenario — its
regression test, below).

## The "cheating publisher" regression

`platform/cmd/simulate-traffic -mode cheat` drives fraud-shaped traffic
through the real serve→beacon path against a dev cluster and asserts
Layers 0–2 catch it (exit 0/1):

1. **Baseline** — honest pageviews: browser UA, human-paced clicks,
   one mount beacon per pageview.
2. **Chain violations** (Layer 1) — clicks with no prior impression
   (`suspect(chain)`) and sub-100ms impression→click deltas
   (`suspect(timing)`). Runs before the burst so a drained rate bucket
   can't mask the chain marks (hygiene wins over chain).
3. **Bot traffic** (Layer 0) — a `curl/` UA; bot marking short-circuits
   before the rate gate, so this never drains the bucket.
4. **Rate burst** (Layer 0) — unpaced impressions past the per-IP cap.
5. **Top-up** — more bot impressions until the day clears the Layer-2
   `suspect_share` gates (≥500 events, ≥30% marked), adapting to
   whatever honest traffic the site already had today.
6. **Verify** — `GET /v1/internal/fraud-suspects/{site}` must show
   bot/rate (+chain/timing when the guard is on) marks, then the
   scenario polls `GET /v1/internal/fraud-flags` until the detector
   writes the `suspect_share` flag.

Cluster prerequisites: `FRAUD_ENGAGEMENT_GUARD_ENABLED=true`,
`FRAUD_DETECTOR_ENABLED=true`, and a short sweep cadence
(`FRAUD_DETECTOR_INTERVAL_SECONDS=30`) so the flag lands within the
scenario's `-flag-timeout`. `-expect-l1=false` / `-expect-flag=false`
skip the corresponding assertions when a layer is deliberately dark.

The `rate_cap` mark is **observational, not a hard gate**, in the live
scenario: `RequestRateGate` is a per-pod, per-IP token bucket, so behind
a multi-pod load balancer a single client's burst splits across pods and
the refill outpaces it — `rate_cap` marks are non-deterministic from one
driver. The authoritative rate-cap test is `RequestRateGateSpec`; the
scenario reports whether marks appeared without failing on their absence.

Verified end-to-end against GKE prod 2026-07-22 (demo site
publisher-programmer-llc): bot_ua, chain, and timing marks landed and the
detector wrote the `suspect_share` flag (35.7% of the day's events) — all
through the real serve→beacon→detector path on live traffic.

The `imp_per_pageview` and `ctr_spike` signals need ≥5 days of per-site
history, so they can't be exercised by a bounded live-traffic run;
they're covered by seeding shaped history directly into
`tracking_events`/`mount_beacons` (see the FraudDetection unit specs
for the shapes) plus the pure-core tests.
