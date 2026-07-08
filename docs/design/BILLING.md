# Billing & Settlement

**Status: DESIGNED 2026-07-04. ALL PHASES (1-5) BUILT as of 2026-07-05:
ledger core, core metering + suspend/resume, settlement job, dashboards,
intraday projected-balance enforcement. Live e2e against a deployed
cluster still owed (needs new api + platform images).**

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
                                    daily settlement │ gross spend for day D
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

Double-entry over four account types: `cash` (asset — external money the
operator has received/sent), `advertiser_wallet` (liability — unspent
advertiser funds), `publisher_payable` (liability — earned, unpaid publisher
revenue), `platform_revenue` (income — captured margin). At all times:

```
cash = Σ advertiser_wallet + Σ publisher_payable + platform_revenue − Σ payouts already sent
```

That identity is the reconciliation check the admin dashboard displays.

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

-- Durable per-day billable record — replaces reliance on the 30-day
-- tracking_events hypertable. One row per (day, advertiser, campaign, site);
-- publisher_id is denormalized here because the platform DB has no
-- site→publisher mapping (the metering endpoint supplies it).
CREATE TABLE daily_settlements (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    day           DATE NOT NULL,              -- UTC day (matches budget reset)
    advertiser_id TEXT NOT NULL,
    campaign_id   TEXT NOT NULL,
    site_id       TEXT NOT NULL,
    publisher_id  TEXT NOT NULL,
    impressions   BIGINT NOT NULL,
    gross_micros  BIGINT NOT NULL,
    margin_bps    INTEGER NOT NULL,           -- SNAPSHOT of rate applied
    fee_micros    BIGINT NOT NULL,
    net_micros    BIGINT NOT NULL,            -- fee + net = gross exactly
    txn_id        UUID REFERENCES ledger_transactions(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (day, advertiser_id, campaign_id, site_id)
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

### 2. Daily settlement (automatic, the core of the design)

A Go-side job (ticker in the platform process; no new deployable) settles
day D−1 shortly after each UTC midnight (UTC days match the existing
campaign budget-reset boundary). As built, it ticks every 30 minutes and
re-checks rather than firing at exactly 00:30 — every pass is idempotent
(settled days are checkpointed in `billing_settlement_days`, ledger rows
dedupe on their settlement key), so the tick just bounds how soon after
midnight yesterday gets booked, and a restart at any time self-heals:

1. Calls the **internal metering endpoint** on the Scala API —
   `GET /v1/internal/metering/daily?date=D` → rows of
   `(advertiserId, campaignId, siteId, publisherId, impressions, gross)`
   summed from `tracking_events` (`SUM(cpm)/1000` where
   `event_type='impression' AND NOT dogeared` — dog-eared impressions
   never debit campaign budget, so they are not billable, even though the
   display endpoints count them). `publisherId` rides along (joined from
   `publisher_sites`) because the platform DB has no site→publisher
   mapping of its own; rows with an empty `publisherId` (unmapped site)
   must be skipped and alerted, never silently credited.
   Auth: the core API had no auth at all, so Phase 2 introduced an
   optional shared secret — when `INTERNAL_API_KEY` is set in the core
   environment, `/v1/internal/*` requires it as an `X-Internal-Key`
   header; when unset the endpoints rely on network isolation like the
   rest of the core API (the Go platform is the auth boundary).
2. Looks up the margin rate effective **on day D** from
   `platform_margin_history` and snapshots `margin_bps` into each row.
3. For each row, inserts a `daily_settlements` row and one `settlement`
   transaction with three legs:
   `advertiser_wallet −gross`, `publisher_payable +net`,
   `platform_revenue +fee`.
   Idempotency key `settle:{day}:{advertiserId}:{campaignId}:{siteId}` — the
   job can re-run safely; already-settled rows are skipped via the UNIQUE
   constraints.
4. Records the last fully settled day; on startup it **catches up** any missed
   days (up to the 30-day retention window; beyond that it alerts — data
   would be lost, which is exactly why settlement runs daily, not monthly).
5. After settling, evaluates each advertiser's wallet (below) and each
   publisher's payable vs threshold.

Why per-day pull-based settlement rather than streaming per-impression into
the ledger: the metering side already dedupes per-impression via `requestId`;
a daily aggregate keeps the ledger small, human-auditable, and immune to the
firehose, and one day is the natural unit given UTC budget resets. The
statement line an advertiser sees ("July 3 · campaign X on site Y · 12,431
impressions · $24.86") is one settlement row.

### 3. Wallet enforcement (prepaid means prepaid)

After each settlement run (and on any top-up/adjustment):

- `balance ≤ 0` → mark account `suspended` and call a new core endpoint
  `POST /v1/internal/advertisers/{id}/suspend` → `AdvertiserEntity` emits the
  existing `AdvertiserSuspended` to `budgetEventTopic` → AdServer stops
  serving that advertiser's campaigns. Top-up reverses it via `/resume`.
- `balance < lowWatermark` (default: 2 × trailing-7-day average daily spend)
  → mark `low_balance`, show a warning banner on the advertiser dashboard.
- Wallet evaluation runs every settlement tick using the **projected
  balance**: settled balance minus unsettled spend from the intraday
  metering endpoint. All transitions (suspend / resume / low-balance) key
  off the projection, with no ledger writes — money is only booked by the
  daily settlement.

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
(STRIPE_TOPUPS.md), flip to strict prepaid: provision new advertisers
suspended with a $0 wallet, first top-up activates serving, and an
optional operator-configured signup credit covers trials — at that point
the friction of funding-before-serving falls on the advertiser's card,
not the operator's inbox.

New-advertiser UX: a configurable **signup credit** (`adjustment` transaction,
default $0) lets an operator offer trial spend without faking a top-up.

### 4. Publisher payout (manual, accrual-based)

`publisher_payable` accrues from settlements. **Admin → Billing → Payouts**
lists every publisher whose payable ≥ their `min_payout`, with a "Create
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
- Settlement health: last settled day, rows settled, catch-up/alert state.

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
6. *(Later, separate)* PSP integration (Stripe top-ups via the same
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
