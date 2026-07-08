# Stripe Top-Ups

**Status: DESIGNED 2026-07-05, not built.** Companion to
[BILLING.md](BILLING.md) — this automates the *money-in* edge of the
ledger. Publisher payouts via Stripe Connect are explicitly out of scope
(KYC onboarding, platform liability, per-country capabilities — a separate
project for when payout volume justifies it).

## Why

Top-ups are the last manual step on the advertiser side. With them
automated, advertisers are fully self-service end to end: fund → serve →
suspend at zero → re-fund at 2am without the operator in the loop. That
closes the "anyone can start Promovolve" story for the collection side.

The integration is deliberately thin because the billing design planned
for it: the Stripe webhook becomes a **second caller of the existing
`billing.RecordTopup`** — the same function the admin form calls today,
already live-tested including the auto-resume of a suspended advertiser.
The ledger, enforcement, and reconciliation do not change.

## Decisions

| Question | Decision |
|---|---|
| Payment UI | **Stripe Checkout** (hosted page). No card fields in our UI, no PCI scope beyond SAQ-A, Stripe handles 3DS/SCA. |
| Amount | **Free choice** (advertiser types an amount), bounded to limit card-testing abuse: min $5, max $10,000. Prepaid wallets don't want tiers. |
| Currency | USD only, matching the ledger. |
| Fees | **Gross to the wallet.** The advertiser's $100 credits $100; Stripe's ~3% lives in the operator's Stripe balance, outside the ledger. "Cash" in the ledger then means "Stripe balance + bank" — the reconciliation identity stays clean against money owed, not against any single account. (Optional later: a monthly manual adjustment booking fees against platform revenue, if the operator wants fee visibility inside the ledger.) |
| Optionality | **Off unless configured.** No Stripe keys → no Top-up button, manual flow only. Preserves the OSS stance that each operator picks their own PSP; Stripe is the reference implementation of the money-in edge, not a requirement. |
| Refunds / disputes | Manual, v1. Refund via the existing admin refund flow (then refund in the Stripe dashboard); `charge.dispute.created` is logged loudly for the operator, no automatic clawback. |

## Configuration

Two keys in `k8s/platform-secrets.env` (platform side only — core is not
involved):

```
STRIPE_SECRET_KEY=sk_live_…      # API key
STRIPE_WEBHOOK_SECRET=whsec_…    # webhook endpoint signing secret
```

Feature flag = both present. Test mode is just test-mode keys.

## Flow

```
Advertiser Wallet page ── "Top up $___" ──► POST /advertiser/wallet/topup
                                              │  create Checkout Session:
                                              │    amount (validated bounds),
                                              │    metadata.advertiserId ← FROM CLAIMS,
                                              │    success/cancel URLs → /advertiser/wallet
                                              ▼
                                     303 redirect to Stripe Checkout
                                              │  (card, 3DS, …)
                                              ▼
Stripe ── checkout.session.completed ──► POST /webhooks/stripe   (public route)
                                              │  1. verify signature (WEBHOOK_SECRET)
                                              │  2. require payment_status == "paid"
                                              │  3. RecordTopup{
                                              │       AdvertiserID:  session.metadata.advertiserId,
                                              │       AmountMicros:  session.amount_total × 10⁴,
                                              │       Memo:          "stripe " + session.id,
                                              │       IdempotencyKey:"stripe:" + event.id,
                                              │     }
                                              │  4. if Reactivated → settler.ResumeServing
                                              ▼
                                     200 (Stripe stops retrying)
```

Security properties, all load-bearing:

- **The amount is Stripe's word, not the browser's** — read from
  `session.amount_total` in the verified webhook, never from a form value.
- **The advertiser identity is ours, not the browser's** — stamped into
  session metadata server-side from the JWT claims when the session is
  created. The webhook trusts metadata because only our server could have
  set it.
- **Signature verification is mandatory** — the webhook route is public
  (Stripe must reach it) and unauthenticated; the HMAC signature is the
  auth. Unverifiable payloads → 400, no processing, no logging of bodies.
- **Idempotency is the event id** — Stripe retries webhooks for days;
  every retry hits the ledger's existing dedup and returns success without
  double-crediting. Restarts, replays, and duplicate deliveries are all
  the same no-op.

Failure handling: if `RecordTopup` fails transiently (DB down), return
5xx so Stripe retries — the idempotency key makes eventual delivery safe.
If the metadata is missing/malformed (shouldn't happen), return 200 and
log an error with the session id: retrying can't fix a malformed event,
and the operator reconciles from the Stripe dashboard.

## UI

- **Wallet page**: a "Top up" card (amount input + button) above the
  activity table, rendered only when Stripe is configured. On return from
  Checkout, `?topup=success` shows a green banner ("payment received —
  your balance updates within a few seconds"), `?topup=cancelled` an
  amber one. The balance may lag the redirect by a webhook round-trip;
  the banner wording owns that.
- **Admin billing**: nothing new — Stripe top-ups appear in the journal
  and activity feeds like any other, with the session id in the memo. The
  manual Record-a-top-up form stays (bank-transfer advertisers, and any
  operator running without Stripe).

## Implementation sketch

1. `platform/internal/billing/stripe.go` — session creation + webhook
   handling behind a small `StripeGateway` struct (official `stripe-go`
   SDK; keys injected, nil gateway = feature off).
2. Routes in `main.go`: `POST /advertiser/wallet/topup` (role-guarded) and
   `POST /webhooks/stripe` (public, signature-verified). Config plumbing
   in `config.go` + `platform-secrets.env.example`.
3. Wallet template: top-up card + result banners; `Earnings`-style
   struct field carries the enabled flag.
4. Tests: webhook handler with `stripe-go`'s signature test helpers —
   valid event credits once, replayed event credits zero, bad signature
   rejected, unpaid session ignored. Live verification with `stripe
   listen --forward-to` in dev.

## Non-goals (v1)

- Stripe Connect payouts (see header).
- Saved cards / off-session charges, and with them **auto-top-up** ("keep
  my balance above $X") — natural v2 once the webhook path is proven,
  pairs beautifully with the low-balance watermark that already exists.
- Multi-currency, Stripe Tax / invoices.
- Automatic dispute clawbacks — disputes are rare at prepaid-wallet scale
  and the manual adjustment path already exists.
