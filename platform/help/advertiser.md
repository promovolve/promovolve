# Advertiser Quickstart

How to go from nothing to a live campaign on a Promovolve deployment, and
what to expect once it's running.

## 1. Get an account

Accounts are requested, then approved by an operator admin:

1. Open **Request Account** on the dashboard login page and fill in your
   company, website, contact name, and email.
2. Register a **passkey** as part of the request — Promovolve is
   passkey-only; there is no password to set.
3. Wait for approval. Until then, signing in reports your request as
   pending. Once an admin approves you, your advertiser account is
   provisioned and passkey sign-in works.

> **Keep your passkey safe.** There is no self-service recovery — if you
> lose access to your passkey (and haven't added a second one under
> Account), an admin has to mint you a one-time recovery link.

## 2. Set your daily budget first

The campaigns page redirects you to **Account** until you've set an
account-level daily budget — that's the first thing to do after signing
in. Note there are two budget layers: this account-wide daily budget, and
a per-campaign daily budget you set on each campaign.

## 3. Create a campaign

From **Campaigns → New campaign** you provide:

| Field | Notes |
|---|---|
| Name | For your own reference |
| Ad product category | What you're advertising (type-ahead picker). **Permanent** — cannot be changed later |
| Landing page URL | The page your ad drives to. **Permanent** — see below |
| Daily budget | Per-campaign, spread over the day by pacing |
| Max CPM | The most you're willing to pay per 1000 impressions — see [What you pay](#what-you-pay) |
| Target categories | Optional; auto-derived from your landing page if left empty |
| Sites | Optional allowlist; empty = eligible everywhere |
| Schedule | Optional start/end. **Times are interpreted as UTC**, not your local timezone |

**Why the landing page is permanent:** your creative is generated *from*
the landing page and the system's learning about the creative is anchored
to it. Re-pointing a learned creative at a different page would poison
that learning, so it's rejected by the server, not just hidden in the UI.
To promote a different page or product, create a new campaign.

## 4. Creatives are generated for you

You don't upload banner images. From the creative editor, Promovolve
analyzes your landing page, extracts its content and brand, and writes an
original three-page "magazine" creative — a compact banner that expands
into a page-turning story when a reader engages. You choose the
persuasion arc:

- **Hook → Proof → Call**
- **Problem → Solution → Offer**
- **Feature → Story → Plan**
- **Tease → Reveal → Invite**

Only the final page asks for the click. The copy is original (never
sentences lifted from your page) but factually grounded — any numbers are
kept verbatim-true to your landing page. You can steer tone and angle
with an optional brief, edit the generated copy, and regenerate layouts
before saving. On save, the creative is rendered and its category is
verified before it can serve.

Creatives are **fluid**: they scale to whatever slot dimensions a
publisher declares, so one creative competes for every slot shape — no
per-size variants to manage.

## 5. Targeting

- **Category targeting** — your campaign bids on pages whose content
  matches your target categories. If you leave categories empty, they're
  derived from your landing page. **Watch for the "Untargeted" badge**: if
  auto-derivation can't produce categories, the campaign will not bid in
  any auction until you set categories manually or enable *Bid on
  unmatched context*.
- **Site allowlist** — restrict the campaign to specific publisher sites;
  empty means everywhere.
- **Site blocklist** (account-wide) — exclude publisher domains you never
  want to appear on, across all your campaigns. The picker lists domains
  you've actually served on.

Publishers can also block *your* domain on their sites; that's their
control, not yours.

## 6. What you pay

The auction is quality-adjusted and second-price in spirit: candidates are
ranked by predicted engagement × CPM, and **the winner pays the minimum
CPM that still wins**, never more than your max CPM (and never less than
the publisher's floor).

The practical consequence: **bid your true maximum**. Shading your bid
below what an impression is really worth to you can only lose you
auctions you'd have profitably won — it cannot lower the price you pay
when you do win.

Your daily budget is paced: serving is throttled through the day so the
budget lasts rather than exhausting in the first hour. Budgets reset at
midnight in your account's timezone (set by the platform operator; UTC by
default — ask the operator to change it). Billing and settlement always
use UTC days regardless of your account timezone.

## 7. Reading your metrics

Each campaign row shows:

- **Spend today** vs. daily budget (the pacing bar)
- **Impressions** — creative rendered and viewable
- **Clicks / CTR** — reader engaged with the creative (e.g. expanded it)
- **Imp share** — the fraction of eligible auctions you're winning; low
  share with healthy budget usually means your max CPM is uncompetitive
- **eCPM** — what you're effectively paying

Engagement is tracked in three tiers — impression, click (expand), and
CTA click (the actual visit to your landing page) — with signed,
replay-protected tracking URLs.

## 8. Managing a running campaign

Editable any time: name, daily budget, max CPM, target categories, site
allowlist, schedule, pause/resume. Not editable: landing page URL and ad
product category (create a new campaign instead). Deleting a campaign
requires pausing it first.
