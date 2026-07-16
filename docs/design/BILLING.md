# Billing & Settlement

**Status: DESIGNED 2026-07-04, ALL PHASES (1-5) BUILT 2026-07-05, live
end-to-end on the public GKE cluster. REDESIGNED 2026-07-15 to LOCAL-DAY
settlement: every entity settles on its own local days via per-entity
instant-chained windows (see "Daily settlement" below); the UTC billing
day is gone, and with it the budget-day ≠ billing-day split. The ledger
was reset with the rollout (`scripts/reset-billing.sh`) — historical demo
data was declared disposable, so there is no dual-format migration.**

How the platform operator collects money from advertisers, pays publishers, and
captures the platform margin. Promovolve is self-hostable, so the design is
**ledger-first**: the internal accounting is complete and correct on its own,
and real money movement (bank transfer, Stripe, etc.) is recorded manually by
the admin. A payment-service-provider integration is a later, pluggable step —
each operator picks their own PSP.

## Decisions

| Question | Decision |
|---|---|
| Advertiser collection | **Prepaid wallet.** Advertisers fund a balance up front; campaigns can only spend funded money. Zero credit risk for the operator. |
| Money movement | **Manual, ledger-first.** Admin records top-ups and payouts (e.g. after receiving/sending a bank transfer). PSP integration later. |
| Platform margin | **Single revenue-share %** (the existing `platform_margin_history` rate). Advertiser pays the auction clearing price; platform keeps `margin_bps`; publisher receives the rest. One transparent knob — consistent with the OSS transparency stance. |
| Currency | Single currency per deployment, `currency` column everywhere, default `USD`. No FX. |

## What already exists (and what this builds on)

- **Metering** — spend is debited per viewable impression at `cpm/1000`
  (`LearningEventLog.scala` → `CampaignEntity.RecordSpend`, idempotent by
  `requestId`). `tracking_events` is the source of truth but has **30-day
  retention** — it is a metering log, not a billing record.
- **Margin** — `platform_margin_history` (platform DB, append-only,
  effective-dated, basis points) with `settings.Net()` applied **at display
  only**. The comment in `pages.go` already anticipates "and at future
  payouts" — this design is that future.
- **Enforcement hook** — `AdvertiserEntity` already publishes
  `AdvertiserSuspended` / budget-exhausted events to `budgetEventTopic`, which
  the AdServer consumes to gate serving. Wallet enforcement reuses this path.
- **Two databases** — core (Scala) Postgres holds metering; platform (Go)
  Postgres holds users and margin. **All billing tables live in the platform
  DB**; the core DB stays a metering/learning store. The bridge is one
  internal metering API (below).

## Model overview

```
                 top-up (recorded by admin)
 Advertiser ────────────────────────────────► advertiser wallet (liability)
                                                     │
              advertiser-side settlement             │ gross, per ADVERTISER-local day
                                                     ▼
                                            platform clearing (in transit)
                                                     │
              publisher-side settlement              │ gross, per PUBLISHER-local day
                                                     ▼
                                     ┌───────────────┴───────────────┐
                                     │                               │
                          net = gross × (1 − bps/10000)     fee = gross × bps/10000
                                     │                               │
                                     ▼                               ▼
                        publisher payable (liability)     platform revenue (income)
                                     │
                 payout (recorded by admin)
 Publisher ◄─────────────────────────┘
```

Double-entry over five account types: `cash` (asset — external money the
operator has received/sent), `advertiser_wallet` (liability — unspent
advertiser funds), `publisher_payable` (liability — earned, unpaid publisher
revenue), `platform_revenue` (income — captured margin), and `clearing`
(settlement gross in transit between the advertiser's local billing day and
the publisher's — the two sides of the same events book at different times,
so this account holds the difference and nets to exactly zero over any
event range settled on both sides). At all times:

```
cash = Σ advertiser_wallet + Σ publisher_payable + platform_revenue + clearing − Σ payouts already sent
```

That identity is the reconciliation check the admin dashboard displays;
the clearing tile is labeled "In transit." It nets to exactly zero over any
event range **both** sides have closed — but under continuous traffic it is
normally **non-zero**, and legitimately **negative**. The two local days
close at different moments, so whichever side's day rolls first books its
slice before the other side has; clearing carries that float and *leapfrogs*
around zero as the zones settle in turn, resting at zero only when traffic
quiesces and both sides catch up. (Verified live: a Tokyo-advertiser /
UTC-publisher pair drove clearing to −$0.71 between its two closes — the
publisher's UTC day ran ~9h past the advertiser's JST close, settling a slice
the advertiser hadn't yet — and it self-corrected on the advertiser's next
roll.) The anomaly to watch for is a persistent, *growing* imbalance; a
bounded oscillation, including negative, is expected behaviour.

## Schema (platform DB)

Implemented in `platform/internal/db/db.go` (Migrate) with the ledger service
in `platform/internal/billing/`. Amounts are **BIGINT micro-dollars**
(1 000 000 = $1) rather than NUMERIC — integer arithmetic keeps ledger math
exact in Go with no decimal dependency; floats exist only at the metering and
display boundaries. Entries are signed double-entry amounts: **positive =
debit, negative = credit**, and every transaction's entries sum to zero.
Natural (displayed) balances: platform `cash` is the only debit-normal
account (asset); wallets, payables, and platform `revenue` are credit-normal.

```sql
-- One row per advertiser wallet / publisher payable, plus the two platform
-- singletons (owner_type='platform', owner_id 'cash' | 'revenue').
-- balance_micros is materialized in the same DB transaction as the entries;
-- the entries are authoritative (billing.Reconcile checks for drift).
CREATE TABLE billing_accounts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type     TEXT NOT NULL CHECK (owner_type IN ('advertiser','publisher','platform')),
    owner_id       TEXT NOT NULL,             -- core-side advertiserId / publisherId
    currency       TEXT NOT NULL DEFAULT 'USD',
    balance_micros BIGINT NOT NULL DEFAULT 0,
    status         TEXT NOT NULL DEFAULT 'active'
                   CHECK (status IN ('active','low_balance','suspended')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (owner_type, owner_id)
);

-- Append-only journal. Every money movement is one transaction with
-- balanced legs. Nothing is ever updated or deleted; corrections are
-- new 'adjustment' transactions.
CREATE TABLE ledger_transactions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         TEXT NOT NULL CHECK (kind IN
                 ('topup','settlement','payout','adjustment','refund')),
    idempotency_key TEXT NOT NULL UNIQUE,     -- e.g. 'settle:2026-07-04:advA:campX:site1'
    memo         TEXT NOT NULL DEFAULT '',
    created_by   UUID REFERENCES platform_users(id),  -- NULL for system jobs
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_id        UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id    UUID NOT NULL REFERENCES billing_accounts(id),
    amount_micros BIGINT NOT NULL CHECK (amount_micros <> 0)  -- +debit / −credit
);

-- Per-entity settlement cursor: everything before settled_until is booked.
-- Windows chain on instants ([settled_until, next local midnight)), so
-- coverage is gapless and overlap-free by construction; a timezone change
-- moves only future boundaries (one short/long window, booked exactly
-- once) and DST needs no special-casing.
CREATE TABLE settlement_cursors (
    owner_type    TEXT NOT NULL CHECK (owner_type IN ('advertiser','publisher')),
    owner_id      TEXT NOT NULL,
    settled_until TIMESTAMPTZ NOT NULL,
    timezone      TEXT NOT NULL DEFAULT '',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (owner_type, owner_id)
);

-- Settled-window journal: health panel + the retro re-settle list. Retro
-- passes MUST replay these recorded windows (never recompute boundaries
-- from the current zone — after a zone change that would book the same
-- events twice under different idempotency keys). A row is written only
-- after every cell of the window posted. Empty windows are recorded too.
CREATE TABLE settlement_windows (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type   TEXT NOT NULL,
    owner_id     TEXT NOT NULL,
    window_from  TIMESTAMPTZ NOT NULL,
    window_to    TIMESTAMPTZ NOT NULL,
    local_date   DATE NOT NULL,               -- display label (window's local day)
    timezone     TEXT NOT NULL DEFAULT '',
    rows_settled INTEGER NOT NULL,
    rows_skipped INTEGER NOT NULL DEFAULT 0,  -- unmapped-site cells (alerted, unbilled)
    gross_micros BIGINT NOT NULL,
    settled_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (owner_type, owner_id, window_from)
);

-- Durable billable record, one table per settlement side — replaces
-- reliance on the 30-day tracking_events hypertable (and the old
-- daily_settlements). local_date is a LABEL; uniqueness rides on
-- window_from, because a zone change can give two windows the same label.
CREATE TABLE advertiser_settlements (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    advertiser_id TEXT NOT NULL,
    campaign_id   TEXT NOT NULL,
    site_id       TEXT NOT NULL,
    publisher_id  TEXT NOT NULL DEFAULT '',   -- lineage/alerting only
    window_from   TIMESTAMPTZ NOT NULL,
    window_to     TIMESTAMPTZ NOT NULL,
    local_date    DATE NOT NULL,
    timezone      TEXT NOT NULL DEFAULT '',
    impressions   BIGINT NOT NULL,
    gross_micros  BIGINT NOT NULL,
    txn_id        UUID REFERENCES ledger_transactions(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (advertiser_id, window_from, campaign_id, site_id)
);

CREATE TABLE publisher_settlements (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publisher_id  TEXT NOT NULL,
    site_id       TEXT NOT NULL,
    campaign_id   TEXT NOT NULL,
    advertiser_id TEXT NOT NULL,
    window_from   TIMESTAMPTZ NOT NULL,
    window_to     TIMESTAMPTZ NOT NULL,
    local_date    DATE NOT NULL,
    timezone      TEXT NOT NULL DEFAULT '',
    impressions   BIGINT NOT NULL,
    gross_micros  BIGINT NOT NULL,
    margin_bps    INTEGER NOT NULL,           -- SNAPSHOT of rate applied (at window end)
    fee_micros    BIGINT NOT NULL,
    net_micros    BIGINT NOT NULL,            -- fee + net = gross exactly
    txn_id        UUID REFERENCES ledger_transactions(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (publisher_id, window_from, site_id, campaign_id, advertiser_id)
);

-- Payout runs: accrual → admin sends money → records reference.
CREATE TABLE payouts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publisher_id  TEXT NOT NULL,
    amount_micros BIGINT NOT NULL CHECK (amount_micros > 0),
    currency      TEXT NOT NULL DEFAULT 'USD',
    period_start  DATE NOT NULL,
    period_end    DATE NOT NULL,
    status        TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending','paid','cancelled')),
    external_ref  TEXT NOT NULL DEFAULT '',   -- bank transfer ref / PSP id
    txn_id        UUID REFERENCES ledger_transactions(id),
    created_by    UUID REFERENCES platform_users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at       TIMESTAMPTZ
);

CREATE TABLE payout_methods (
    publisher_id      TEXT PRIMARY KEY,
    method            TEXT NOT NULL,          -- 'bank_transfer' | 'paypal' | ...
    details           JSONB NOT NULL,         -- operator-defined fields
    min_payout_micros BIGINT NOT NULL DEFAULT 50000000,  -- $50
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Rounding rule (`billing.SplitFee`): `fee = (gross_micros × bps + 5000) /
10000` — integer half-up — and `net = gross − fee`, computed once per
settlement row so `fee + net == gross` exactly. Never recompute net from a
percentage downstream. Floats convert at the boundary via
`billing.DollarsToMicros` (round half away from zero).

## Flows

### 1. Advertiser top-up (manual)

Advertiser wires money / hands the operator cash → admin opens **Admin →
Billing → Record top-up**, picks the advertiser, enters amount + memo (bank
ref). This writes one `topup` transaction: `cash +X`, `advertiser_wallet +X`,
updates the materialized balance, and — if the account was `suspended` —
re-activates it (calls the core resume endpoint, below). The advertiser sees
the credit and their statement immediately.

Later PSP step: a Stripe webhook calls the exact same internal
`RecordTopup(advertiser, amount, idempotencyKey=stripe_event_id)` function.
The manual path *is* the API; the PSP is just another caller.

### 2. Local-day settlement (automatic, the core of the design)

Every entity — advertiser account and publisher alike — settles on **its
own local days**. The zone is the owning org's `orgs.timezone` (the same
zone that rolls the advertiser's budget, so budget day == billing day;
`''`/org-less = UTC). Each entity carries a `settled_until` instant cursor;
a Go-side job (30-minute ticker in the platform process, single replica with
no leader election; no new deployable) books the window
`[settled_until, next local midnight)` once that midnight
plus a one-hour finality lag has passed, then advances the cursor. Windows
chain on instants — gapless, overlap-free, DST-proof; an operator timezone
change moves only future boundaries, giving the change day one shortened or
extended window, booked exactly once. Every pass is idempotent (settled
windows are journaled in `settlement_windows`, ledger rows dedupe on their
settlement key), so a restart at any time self-heals.

Because the advertiser's and the publisher's local days close at different
times, the two sides of the same events book **independently** and meet at
the platform `clearing` account:

- **Advertiser side**, per (campaign, site) cell of the advertiser's
  window: `advertiser_wallet −gross`, `clearing +gross`. Key
  `settle:adv:{advertiserId}:{windowFromRFC3339}:{campaignId}:{siteId}`.
- **Publisher side**, per (site, campaign, advertiser) cell of the
  publisher's window: margin split happens here — `clearing −gross`,
  `publisher_payable +net`, `platform_revenue +fee` (zero legs dropped at
  extreme margins). `margin_bps` is the rate effective at window END,
  snapshotted on each row. Key
  `settle:pub:{publisherId}:{windowFrom}:{siteId}:{campaignId}:{advertiserId}`.

Mechanics per pass:

1. **Entity discovery** — `GET /v1/internal/metering/entities?since=` returns
   advertiser/publisher ids with billable impressions (72h lookback), each
   with its earliest event time; entities without a cursor get a **genesis
   cursor at the local midnight before that earliest event** (nothing
   pre-genesis is ever billed — billing starts at install/reset).
2. **Window booking** — for each due window, pull
   `GET /v1/internal/metering/range?from=&to=&advertiserId=|publisherId=`:
   cells summed from `tracking_events` in **integer micro-dollars per
   event** (`SUM(ROUND(cpm*1000))` — partition-invariant, so both sides'
   totals over any commonly settled range are identical and clearing nets
   to exactly zero; `NOT dogeared`, impressions only). Cells with an empty
   `publisherId` (unmapped site) are skipped + alerted on the advertiser
   side too — **nobody is billed until the mapping exists** — and counted
   into `settlement_windows.rows_skipped`.
   Auth: `INTERNAL_API_KEY` as `X-Internal-Key` when set, as before.
3. **Retro replay** — recorded windows ending within 72h are re-settled
   using their **recorded** boundaries (never recomputed from the current
   zone — that would double-book after a zone change). Late cells and
   fixed publisher mappings get billed; everything else dedupes.
4. **Retention clamp** — a cursor stalled past the 30-day tracking_events
   retention jumps forward with an alert: that metering is gone and cannot
   be billed.
5. After settling, evaluates each advertiser's wallet (below).

Why per-window pull-based settlement rather than streaming per-impression
into the ledger: the metering side already dedupes per-impression via
`requestId`; a local-day aggregate keeps the ledger small, human-auditable,
and immune to the firehose, and the entity's own day is the unit users
think in. The statement line an advertiser sees ("July 3 · campaign X on
site Y · 12,431 impressions · $24.86") is one settlement row in their own
timezone.

### 3. Wallet enforcement (prepaid means prepaid)

After each settlement run (and on any top-up/adjustment):

- `balance ≤ 0` → mark account `suspended` and call a new core endpoint
  `POST /v1/internal/advertisers/{id}/suspend` → `AdvertiserEntity` emits the
  existing `AdvertiserSuspended` to `budgetEventTopic` → AdServer stops
  serving that advertiser's campaigns. Top-up reverses it via `/resume`.
- `balance < lowWatermark` (default: 2 × trailing-7-day average daily spend)
  → mark `low_balance`, show a warning banner on the advertiser dashboard.
- Wallet evaluation runs every settlement tick using the **projected
  balance**: settled balance minus unsettled spend from
  `POST /v1/internal/metering/unsettled`, which takes **one since-instant
  per advertiser** (each advertiser's own `settled_until` cursor — a
  shared since would double-count spend already settled for advertisers
  with newer cursors). All transitions (suspend / resume / low-balance)
  key off the projection, with no ledger writes — money is only booked by
  window settlement.

**Bounded overdraft, by design:** an advertiser can overspend by at most
one evaluation tick's worth of delivery (~30 minutes) before the projected
balance suspends them. Whatever overshoot lands settles the wallet
negative and nets against the next top-up. Tightening further
(per-impression balance checks in the serve path) is explicitly out of
scope — it would put a cross-DB read in the hot path.

This applies to BRAND-NEW advertisers too, and it is a stated policy, not
an accident: a just-approved, never-funded advertiser may serve for up to
one tick before being paused. That exposure is acceptable because (a) the
spend is fully metered and billed — it becomes wallet debt that nets
against their first top-up, and they stay paused until then; and
(b) advertiser accounts only exist via operator approval, so the window
cannot be farmed by re-registration. When self-service payments land
(Stripe top-ups — future work), flip to strict prepaid: provision new advertisers
suspended with a $0 wallet, first top-up activates serving, and an
optional operator-configured signup credit covers trials — at that point
the friction of funding-before-serving falls on the advertiser's card,
not the operator's inbox.

New-advertiser UX: a configurable **signup credit** (`adjustment` transaction,
default $0) lets an operator offer trial spend without faking a top-up.

### 4. Publisher payout (manual, accrual-based)

`publisher_payable` accrues from settlements. Payout periods are
**publisher-local days** (the same days as the earnings statement, so a
period is a clean sum of statement days); "yesterday" for a new payout is
computed in the publisher org's zone. **Admin → Billing → Payouts**
lists every publisher whose payable ≥ their effective payout floor — the
greater of their per-publisher `min_payout_micros` and the global operator
floor (`platform_settings` key `payout_floor_micros`) — with a "Create
payout" action:

1. Creates a `payouts` row (`pending`) for the full payable over a period,
   and one `payout` transaction: `publisher_payable −X`, `cash −X`.
2. Admin sends the actual bank transfer, then marks the payout `paid` with
   the `external_ref`. (`cancelled` reverses via an `adjustment`.)

Publishers configure their payout method + details in their dashboard
(free-form per operator; `payout_methods.details` is JSONB on purpose —
bank fields differ per country and OSS operators shouldn't fight a schema).

### 5. Adjustments & refunds

Everything else — goodwill credit, invalid-traffic clawback, refunding an
unspent wallet — is a manual `adjustment`/`refund` transaction entered by the
admin with balanced legs and a mandatory memo. No special machinery; the
append-only journal is the audit trail. (Automated invalid-traffic detection
is the fraud-prevention project, not this one; when it lands, it emits
adjustments through this same door.)

## Dashboard surfaces

**Advertiser**
- Wallet balance card (+ `low_balance`/`suspended` banner with "contact the
  operator to top up" text — configurable, since payment instructions are
  operator-specific).
- Statement page: top-ups and daily settlement lines (day, campaign, site,
  impressions, gross), monthly totals. This is the invoice-equivalent.

**Publisher**
- Balance card: accrued (unpaid) net earnings + lifetime paid. The existing
  "Revenue Today" tile stays as the *live* number; the balance card is the
  *settled* number — label them so the gap (today isn't settled yet) is
  self-explanatory.
- Payout history + payout method settings.

**Admin (→ Billing section, alongside the existing margin settings)**
- Overview: total cash position, Σ wallets, Σ payables, platform revenue
  (margin captured — finally a real number, not display math), and the
  reconciliation identity with a green/red check.
- Record top-up form; payout queue (publishers over threshold); adjustment
  form; full journal browser.
- Settlement health: per-entity "settled through" cursors with a behind
  flag, plus the recent settled-window journal (local day, exact instant
  range, rows, skips, gross).

The existing display-time `settings.Net()` call sites stay as-is for live
(unsettled) numbers — they're a preview; the ledger is the record.

## Implementation phases

1. **Ledger core (Go)** — ✅ BUILT (`platform/internal/billing/`): schema in
   `db.Migrate`, `RecordTopup` / `RecordSettlement` / `CreatePayout` +
   `MarkPayoutPaid` / `CancelPayout` / `Adjust` / `RefundAdvertiser`, all
   idempotent; balance materialization with sorted-lock posting;
   `Reconcile` (zero-sum + drift check) and journal/settlement listings.
   Unit tests plus a full-cycle integration test (gated on
   `BILLING_TEST_DATABASE_URL`, runs in a throwaway schema).
2. **Metering endpoint (Scala)** — ✅ BUILT: `/v1/internal/metering/daily`
   (aggregate over `tracking_events`, half-open UTC-day range, dog-eared
   excluded, `publisher_sites` join) plus
   `POST /v1/internal/advertisers/{id}/suspend|resume` mapping to
   `AdvertiserEntity.UpdateStatus`. Two enforcement fixes rode along:
   `GetBidContext` now returns zero budget for non-Active advertisers
   (previously a suspended advertiser's campaigns re-entered at the next
   periodic re-auction — the purge was one-shot), and the
   Suspended→Active transition now publishes `AdvertiserBudgetReset` so
   auctioneers immediately re-run auctions with the advertiser
   participating (resume previously had no serving-restart path at all).
   Live e2e (suspend → no serve → resume → serve) owed after deploy.
3. **Settlement job (Go)** — ✅ BUILT (`billing.Settler` + `HTTPCoreClient`,
   wired in `cmd/server/main.go`): 30-min idempotent tick, checkpointed
   catch-up in `billing_settlement_days` with the retention-gap alert,
   margin snapshot via `settings.MarginBpsAt` (rate in effect at the end of
   the settled day), unmapped-publisher rows skipped + alerted + counted,
   and wallet evaluation (suspend ≤ 0 / resume when refunded / low-balance
   watermark = 2 × trailing-7-day average). Core suspend/resume calls
   happen *before* the status flips so a failed call retries next pass.
   First run ever settles yesterday only — billing starts at install, no
   retroactive charges for pre-billing traffic. `INTERNAL_API_KEY` on the
   platform side is sent as `X-Internal-Key`.
4. **Dashboards** — ✅ BUILT (`internal/handler/billing.go` + templates
   `admin/billing.html`, `advertiser/wallet.html`,
   `publisher/earnings.html`): admin Billing section (overview tiles +
   live reconciliation badge, settlement health with skipped-row
   highlighting, record-top-up form with advertiser picker, payout queue
   → create/mark-paid/cancel, credit/charge/refund adjustment form,
   ledger journal with per-leg movement), advertiser Wallet
   (balance + suspended/low-balance banners, top-up activity, monthly
   spend, daily statement), publisher Earnings (balance owed + lifetime
   paid, payout history, monthly gross−fee=net, free-form payout method
   + minimum). Mutating forms carry a nonce that becomes the ledger
   idempotency key, so double-submits post once. The top-up handler calls
   core resume when it reactivates a suspended wallet. Verified end to
   end locally: seeded ledger, drove every form, books stayed balanced.
5. **Enforcement wiring** — ✅ BUILT. As designed but with one refinement:
   instead of a separate hourly check-only pass (which could flap against
   the settled-balance evaluation), wallet evaluation always uses the
   **projected balance** = settled balance − unsettled intraday spend from
   a new `GET /v1/internal/metering/intraday?since=` core endpoint (same
   billing predicate as /daily, from the first unsettled day to now).
   Suspend, resume, and low-balance transitions all key off the projection,
   so an advertiser burning through their wallet is paused within one
   30-minute tick — the overdraft bound is hours, not a day — with zero
   ledger writes. If the intraday call fails (old core image), evaluation
   degrades to settled balances rather than skipping. Low-balance /
   suspended banners also appear on the advertiser Account and Stats pages
   (not just Wallet), linking to the Wallet page.
6. **Local-day redesign (2026-07-15)** — ✅ BUILT, superseding the UTC-day
   mechanics in phases 2/3/5 above (kept for history): `RecordSettlement`
   split into `RecordAdvertiserSettlement`/`RecordPublisherSettlement`
   through the new `clearing` account; `daily_settlements` +
   `billing_settlement_days` replaced by `settlement_cursors` +
   `settlement_windows` + per-side settlement tables; `/metering/daily` +
   `/metering/intraday` replaced by instant-based `/metering/range`,
   `POST /metering/unsettled` (per-advertiser since), and
   `/metering/entities` (genesis discovery, all integer micros);
   `settle-day` CLI replaced by `settle-entity --type --id [--now]`;
   publisher Revenue-Today takes a `tz` param from the platform proxy.
   Rollout used `scripts/reset-billing.sh` (books wiped, world kept —
   users/orgs/passkeys/sites/campaigns/creatives survive).
7. *(Later, separate)* PSP integration (Stripe top-ups via the same
   `RecordTopup` API), CPF/dog-ear pricing (currently unmonetized — any
   cost-per-fold charge plugs in as additional metering rows), taxes/VAT,
   multi-currency.

## Non-goals

- Real-time per-impression balance checks in the serve path (bounded
  overdraft accepted instead).
- Multi-currency, FX, tax calculation, formal PDF invoices.
- Automated fraud clawbacks (separate project; interfaces via adjustments).
- Any change to auction pricing — the clearing price stays gross; margin is
  taken between advertiser and publisher, never inside the auction, so floor
  optimization and second-price incentives are untouched. (The floor-sweep
  net display already handles the publisher-facing view.)
