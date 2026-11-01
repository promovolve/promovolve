# Delivery check — design

Approved in conversation 2026-10-31 (the deterministic "Delivery check" half
of the ohra-agents discussion). Cluster health is a separate spec.

## Goal

"Why isn't my campaign showing?" is the most common delivery question. The
per-page bid checker answers it for one URL. This answers it for the
campaign as a whole: an ordered checklist on the Campaigns page that names
the first thing stopping delivery. Advertisers use it directly; the operator
uses it through view-as. No LLM, no new service.

## Scope

In: one core endpoint, one BFF proxy, one panel on each campaign's edit
section, EN/日本語 copy.

Out, on purpose:
- Frequency caps — enforced in the reader's browser; nothing server-side to check.
- Pacing throttle — normal behaviour, not a fault.
- Per-page targeting (category/place/audience for a URL) — the existing bid checker covers it.
- Cluster-level faults (shard wedges, silent categories) — Cluster health spec.

## Task 0 (separate issue + PR, first): bid checker ignores category floors

`bidCheckLogic` (`EndpointRoutes.scala:1754`) reads only
`SiteEntity.GetFloorCpm`. The auction admits at
`currentCategoryFloors.getOrElse(cat, currentFloorCpm)`
(`AuctioneerEntity.scala:1538`), so the checker can say "would bid" on a page
whose category floor is above the campaign's max CPM, or "below floor" when
the category floor is lower than the site floor.

Fix: the route also asks `SiteEntity.GetCategoryFloors`; `State.bidVerdict`
takes `categoryFloors: Map[String, CPM]` plus the site floor and checks each
tried category against `categoryFloors.getOrElse(c, siteFloor)`. The
response's `floorCpm` becomes the floor of the matched (or first rejected)
category. Test: a campaign with max 2.0, site floor 1.0, category floor 3.0
→ `BelowFloor` (today: would bid). Check 9 below reuses this resolution.

## Endpoint

`GET /v1/advertisers/{advertiserId}/campaigns/{campaignId}/delivery-check`
(the BFF rewrites `/me/`). Ownership via `requireOwnedCampaign`, exactly as
`bidCheckOwnedLogic`.

Response:

```json
{ "items": [ { "key": "advertiserActive", "status": "ok|blocked|warning|unknown",
               "detail": { ... } } ],
  "firstBlocked": "campaignActive" }
```

`detail` carries the numbers the copy needs (budgets, N of M sites, the
categories missing from bidders). Copy lives in the Go templates / ja.go,
not in core. `unknown` = the source did not answer within the ask timeout;
it never counts as blocked.

The evaluation is a pure function `DeliveryCheck.evaluate(inputs, now):
List[Item]` in the api module, so every rule is unit-tested without actors.
The route only gathers inputs.

## Checks, in order

| # | key | blocked when | warning when | source |
|---|-----|-------------|--------------|--------|
| 1 | `advertiserActive` | `AdvertiserInfo.status != Active` | — | `AE.GetAdvertiserInfo` (already asked by `requireOwnedCampaign`) |
| 2 | `advertiserBudget` | daily budget is zero, or `withinBudget` false with status Active | remaining < 10% of daily | `AE.GetBudgetStatus` |
| 3 | `campaignActive` | `status != Active` | — | `CE.GetCampaign` |
| 4 | `schedule` | now before `startAt`, or at/after `endAt` | — | `CampaignInfo.startAt/endAt` |
| 5 | `categories` | effective categories empty and not `bidOnUnmatchedContext` | — | `CampaignInfo` (effective = `categories`, else `suggestedCategories`) |
| 6 | `creatives` | no assigned creative with `isActive` | — | `CampaignInfo.creativeIds` ∩ `AE.GetCreatives` |
| 7 | `approval` | — | union of `approvedSites` over the active assigned creatives is empty ("pending: a win puts it in publishers' queues") | same `GetCreatives` reply |
| 8 | `campaignBudget` | `BudgetInfo.remaining` ≤ 0 | remaining < 10% of daily | `CE.GetBudgetInfo` |
| 9 | `floors` | 0 of M eligible sites clear (M > 0) | some but not all clear; or M = 0 ("no site carries these categories yet") | fan-out, below |
| 10 | `bidders` | — | any effective category the directory does not list the campaign under ("warming up") | `CampaignDirectory.GetCampaignsFor` per category |

Check 1's reason (org suspended by admin vs. wallet empty) is only in the
platform DB. The Go handler enriches item 1 when core says blocked: org
suspended (`orgRepo.IsEntitySuspended`) → `detail.reason = "org"`; wallet
balance ≤ 0 (`billing.Service.GetAccount`) → `"wallet"`; else `"other"`.
Never-funded advertisers are locked to the Overview, so they never reach
this panel.

Check 8 caveat: right after the campaign's midnight, `BudgetInfo.remaining`
can read 0 for the seconds before `ResetDayStart` lands, while bidding
already sees a fresh budget (`remainingBudgetForBidding`). Accepted; not
worth a new field.

### Check 9: floors

1. Eligible sites: sites carrying any effective category or a descendant
   (`TieredCategory.getAllDescendants`), minus suspended sites
   (`SiteSuspendedKey`), intersected with `siteAllowlist` when it is set.
   The site list comes from `CategoryRegistry.GetPublishersForCategory`,
   made public (it is private at `CategoryRegistry.scala:302`).
2. Per eligible site, ask `GetFloorCpm` and `GetCategoryFloors` in parallel
   (the same pair the floor admin page uses, `EndpointRoutes.scala:3341`).
3. A site clears when `maxCpm >= categoryFloors.getOrElse(c, siteFloor)` for
   some carried category `c` — Task 0's resolution.
4. `detail`: `{cleared: N, eligible: M, lowestFloor, highestFloor}` so the
   copy can say "your bid clears 2 of 5 sites; floors run ¥80–¥240".

Admin slot floors are not checked (they are per slot, and a site with one
high slot still serves its other slots). Fan-out cost is two asks per site;
the network has a handful of sites. `ponytail:` comment at the fan-out:
cap at 200 sites, switch to the `floor_decisions` table if it grows past that.

### Check 10: bidders

`EndpointRoutes` has no CampaignDirectory ref today: `HttpBootstrap` holds
it (`HttpBootstrap.scala:48`) but does not pass it in (`:415`). Pass it.
Then one `GetCampaignsFor(category)` ask per effective category; a category
whose result lacks this campaign id is "warming up". Filler-only campaigns
(`bidOnUnmatchedContext`, no categories) check the Filler category.

## Panel

On each campaign's edit section in `advertiser/campaigns.html`, above the
bid checker: "Delivery check". It fetches when the section opens (Alpine,
like the bid checker; BFF route `GET /api/delivery-check?campaignId=`).

- Headline: the first blocked item's sentence in bold, or "Nothing is
  stopping this campaign." when none is blocked.
- Below: every item as one line with ✓ / ✕ / ! / ? (greyscale, printed-page
  style; no colour), warnings included.
- Each blocked line says what to change and, where it exists, links to it
  (budget dialog, schedule fields, creatives, the Overview wallet).
- View-as: read-only already; the panel is a GET.

All strings go through `{{t}}` with ja.go entries.

## Testing

- `DeliveryCheckSpec` (api): one case per row of the table, both sides of
  each threshold, plus ordering (`firstBlocked` is the earliest blocked key)
  and `unknown` never blocking.
- Task 0: the `bidVerdict` category-floor case above.
- Go: handler test for item-1 enrichment (org / wallet / other).
- Live: printed-page check for the panel on a demo campaign, EN and 日本語,
  light and dark; screenshot to the user before commit.

## Order of work

1. Task 0 — bid checker category floors (own issue, PR).
2. Core endpoint + `DeliveryCheck.evaluate` + directory wiring + registry
   message public (one PR).
3. BFF proxy + item-1 enrichment + panel + i18n (one PR, after the user
   reviews screenshots).
