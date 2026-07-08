# Verifying Budget Bench & Release (UI Walkthrough)

A step-by-step scenario you can run entirely from the dashboards to prove
that budget enforcement benches a campaign at exactly its daily budget and
releases it the moment headroom returns. No tools, no scripts — just the
advertiser dashboard, the publisher observations page, and a browser tab
on the publisher's site.

The trick that makes it verifiable in one impression: **set the CPM equal
to the daily budget × 1000**, so a single impression consumes the entire
day's budget. One page view is the whole experiment.

## Prerequisites

- An advertiser account with one **approved** creative serving on a
  publisher site (see the [advertiser quickstart](advertiser-quickstart.md)).
- The publisher observations page open in a second tab:
  `/publisher/sites/<siteId>/observations`.
- A page on the publisher's site where the creative serves.

## Scenario A — bench on the very first impression

| Step | Do | Expect |
|------|----|--------|
| 1 | On the campaign, set **max CPM = $1000** and **daily budget = $1.00** | Campaign saves; it stays eligible (spent $0.00 < $1.00) |
| 2 | Open the publisher observations tab | The creative's category shows **1 bidder, floor = $1000** — a sole bidder pays its own bid, so the floor follows the CPM instantly |
| 3 | Load a page on the publisher site where the ad serves | The ad renders **once** |
| 4 | Refresh the advertiser campaign page | **Spent today: $1.00 / $1.00** and the badge **"Benched — out of budget today, resumes at rollover"** |
| 5 | Reload the publisher page a few times | **No ad serves.** Budget is secured at serve time (reserve-before-delivery), so the second request is refused *before* anything renders — the bench does not wait for tracking beacons |
| 6 | Check observations again (within ~1 min) | The category's bidders drop to **0** and its floor **collapses to the site minimum** — a benched campaign stops bidding, and floors only price demand that can actually serve |

What step 4 proves: the reserve counter benches at **exactly** the budget
(1 × $1.00 impression = $1.00), not before, not after.

## Scenario B — release by restoring headroom

| Step | Do | Expect |
|------|----|--------|
| 7 | Edit the campaign: **daily budget = $5.00** (anything above the $1.00 already spent). Optionally lower max CPM back to a sane value (e.g. $4) at the same time | Save succeeds |
| 8 | Refresh the campaign page | Bench badge is **gone** (spent $1.00 < $5.00) |
| 9 | Check observations (within ~1 min) | The category shows **1 bidder** again, floor = the **new** CPM — the monopoly floor re-prices to the current bid immediately, with no memory of the $1000 experiment |
| 10 | Reload the publisher page | The ad **serves again** — no restart, no pause/resume, no waiting for rollover |

What step 8–10 prove: the bench is a pure `spent < budget` comparison
re-evaluated on every serve attempt — restoring headroom releases it
instantly, and the floor system re-prices to the live bid rather than any
stale history.

## Reading the aftermath (why eCPM looks wild)

The stats page computes **realized eCPM** = spend ÷ delivered impressions
× 1000, as a lifetime average. After this experiment it will show an eCPM
near $1000 until enough cheap impressions dilute it. That is honest
reporting of what was paid, not a bug — the auction competes on
*predicted* eCPM (sampled CTR × bid), which uses your current bid and is
unaffected by the experiment.

> **Sole-bidder warning:** with no competitor in a category, your bid IS
> your price — the monopoly reserve follows it exactly. $1000 entered
> means $1000 paid. Run this experiment with a budget you're willing to
> spend (that's why this guide uses $1).

## Variations

- **Rollover release:** skip step 7 and wait for the day rollover instead —
  the badge clears and serving resumes with the counter reset to $0.
- **Exact-boundary check:** set daily budget = $2.00 with CPM $1000 — you
  should get exactly **two** impressions before the bench.
- **Advertiser-level bench:** the same experiment works one level up by
  setting the account-wide daily budget on the Account page; the
  account-level gate is checked before every campaign reserve.
