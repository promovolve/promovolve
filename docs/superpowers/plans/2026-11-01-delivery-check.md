# Delivery Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A per-campaign "Delivery check" panel that names the first thing stopping a campaign from serving, plus a fix so the existing per-page bid checker uses category floors.

**Architecture:** One core endpoint gathers state with a handful of actor asks and hands it to a pure `DeliveryCheck.evaluate`, which returns an ordered checklist. The Go BFF proxies it, adds the suspension reason that only the platform DB knows, and the Campaigns page renders it with Alpine like the bid checker.

**Tech Stack:** Scala 3 / Pekko Typed (core, `modules/core`), tapir + spray-json (`modules/api`), Go html/template + Alpine (`platform/`).

**Spec:** `docs/superpowers/specs/2026-11-01-delivery-check-design.md`

## Global Constraints

- Ship flow: issue first, then a PR with `Closes #N`; never push to main. Three PRs: Task 1; Tasks 2–4; Tasks 5–6.
- Scala: `sbt "core/Test/scalafmtOnly <abs path>"` on every NEW test file, then `sbt scalafmtAll scalafmtCheckAll` last before `git add`. No `return` in Scala.
- New fields on cross-node messages and replies are `Option[...] = None` (rolling deploys mix versions; a missing field must deserialize).
- Platform: verify with `make check` in `platform/` (the i18n drift test lives in the root package). Every user-visible string goes through `{{t}}` with a `ja.go` entry; handler error strings that the page shows untranslated get no `ja.go` entry.
- UI: printed-page style — greyscale, no colour for status. The user reviews screenshots before the platform PR is committed.
- Copy lives in templates/`ja.go`, never in core responses. Core returns keys and numbers.
- `unknown` (a source did not answer) never counts as blocked.

## Review Focus

1. Campaign with `bidOnUnmatchedContext` and no categories: check 5 must be ok, and checks 9/10 use the Filler category, not "no categories". → Task 3 test `filler-only campaign is not blocked on categories`.
2. Advertiser suspended AND budget fields zero: only check 1 is blocked; check 2 must not also report blocked (withinBudget is false when suspended). → Task 3 test `suspended advertiser blocks only on check 1`.
3. A source times out (site floor ask, directory ask): that item is `unknown`, `firstBlocked` skips it. → Task 3 test `unknown never blocks`.
4. Campaign whose categories no site carries (M = 0): check 9 is a warning, not blocked, and not "0 of 0 clear". → Task 3 test `no carrying sites is a warning`.
5. Old api pod sends `CheckBid` without the new floors field during a rolling deploy: the entity falls back to the site floor, no exception. → Task 1 test `missing category floors fall back to the site floor`.

---

### Task 1: Bid checker uses category floors (own issue + PR)

**Files:**
- Modify: `modules/core/src/main/scala/promovolve/advertiser/CampaignEntity.scala` (`CheckBid` ~1832, `BidVerdict` ~1841, handler ~988, `State.bidVerdict` ~2400)
- Modify: `modules/api/src/main/scala/promovolve/api/EndpointRoutes.scala` (`bidCheckLogic` ~1722)
- Test: `modules/core/src/test/scala/promovolve/advertiser/BidVerdictCategoryFloorSpec.scala`

**Interfaces:**
- Produces: `CheckBid(..., categoryFloors: Option[Map[String, Double]] = None, replyTo)` — append the field before `replyTo`; `BidVerdict(..., floorCpm: Option[Double] = None)`; `State.bidVerdict(siteId, pageCategories, siteFloor: CPM, categoryFloors: Map[String, Double], siteAudience, siteAudienceVerified, pagePlaces): BidVerdict`. The floor for a tried category `c` is `categoryFloors.get(c).map(CPM(_)).getOrElse(siteFloor)` — the auction's rule at `AuctioneerEntity.scala:1538`. Task 4 reuses this rule.

- [ ] **Step 1: Write the failing test** — pure, on `CampaignEntity.State` (build it the way `CampaignScheduleBidSpec` does), campaign targets category `"483"`, maxCpm 2.0:

```scala
"use the page category's floor, not the site floor" in {
  val v = state.bidVerdict(site, Set("483"), CPM(1.0), Map("483" -> 3.0), Set.empty, false, Set.empty)
  v.wouldBid shouldBe false
  v.reason shouldBe Some("BelowFloor")
  v.floorCpm shouldBe Some(3.0)
}
"bid when the category floor is below the site floor" in {
  val v = state.bidVerdict(site, Set("483"), CPM(5.0), Map("483" -> 1.5), Set.empty, false, Set.empty)
  v.wouldBid shouldBe true
  v.floorCpm shouldBe Some(1.5)
}
"missing category floors fall back to the site floor" in {
  state.bidVerdict(site, Set("483"), CPM(1.0), Map.empty, Set.empty, false, Set.empty).wouldBid shouldBe true
}
```

- [ ] **Step 2: Run** `sbt "core/testOnly promovolve.advertiser.BidVerdictCategoryFloorSpec"` — Expected: compile failure (signature), then after a signature-only change, FAIL on the first case (`true was not false`).
- [ ] **Step 3: Implement** the signature change; `bidRejectReason` is called per tried category with that category's floor. `floorCpm` in the verdict = the matched category's floor, else the floor of the category whose reason was reported. The handler passes `categoryFloors.getOrElse(Map.empty)`.
- [ ] **Step 4: Route** — `bidCheckLogic` also asks `SiteEntity.GetCategoryFloors` (as at `EndpointRoutes.scala:3341`), passes `Some(floors)`, and sets the response `floorCpm` to `v.floorCpm.getOrElse(floor.toDouble)`.
- [ ] **Step 5: Run** the spec, then `sbt scalafmtAll scalafmtCheckAll core/test` — Expected: all green.
- [ ] **Step 6: Commit**, issue "Bid checker ignores category floors", PR.

### Task 2: Registry answers "which sites carry these categories"

Deviation from the spec (which said "make `GetPublishersForCategory` public"): one subtree-aware ask replaces one ask per descendant category, and it returns which declared categories each site carries, which check 9 needs for the floor lookup.

**Files:**
- Modify: `modules/core/src/main/scala/promovolve/taxonomy/CategoryRegistry.scala`
- Test: `modules/core/src/test/scala/promovolve/taxonomy/SubtreeCarriersSpec.scala`

**Interfaces:**
- Produces: public `GetCarriers(categories: Set[CategoryId], replyTo: ActorRef[Carriers]) extends Command`; `Carriers(bySite: Map[SiteId, Set[String]]) extends CborSerializable` — for each site, the declared categories inside any requested category's subtree; sites with none are absent. Pure core `private[taxonomy] def subtreeCarriers(publisherCategories: Map[String, Set[String]], categories: Set[CategoryId]): Map[SiteId, Set[String]]`, same subtree rule as `coverageRows` (~line 216).

- [ ] **Step 1: Failing test** on `subtreeCarriers`: publishers `s1 -> {child of "483"}`, `s2 -> {"483"}`, `s3 -> {unrelated}`; request `{"483"}` → `Map(SiteId("s1") -> Set(child), SiteId("s2") -> Set("483"))`. Request a child only, with `s2` declaring the parent only → `s2` absent (parent never credits a child).
- [ ] **Step 2: Run** `sbt "core/testOnly promovolve.taxonomy.SubtreeCarriersSpec"` — Expected: compile failure.
- [ ] **Step 3: Implement** the function and the message handler.
- [ ] **Step 4: Run** it — Expected: PASS.
- [ ] **Step 5: Commit** (PR comes with Task 4).

### Task 3: `DeliveryCheck.evaluate`

**Files:**
- Create: `modules/api/src/main/scala/promovolve/api/DeliveryCheck.scala`
- Test: `modules/api/src/test/scala/promovolve/api/DeliveryCheckSpec.scala`

**Interfaces:**
- Consumes: core types `AdvertiserEntity.AdvertiserInfo`, `AdvertiserBudgetStatus`, `CampaignEntity.CampaignInfo`, `BudgetInfo`, `AdvertiserEntity.Creative`.
- Produces:

```scala
object DeliveryCheck {
  final case class SiteFloors(siteFloor: Double, categoryFloors: Map[String, Double], carried: Set[String])
  final case class Inputs(
      advertiser: AdvertiserEntity.AdvertiserInfo,
      advertiserBudget: Option[AdvertiserEntity.AdvertiserBudgetStatus], // None = no answer
      campaign: CampaignEntity.CampaignInfo,
      campaignBudget: Option[CampaignEntity.BudgetInfo],
      creatives: Option[Set[AdvertiserEntity.Creative]],
      sites: Option[Map[SiteId, SiteFloors]],           // eligible sites only (allowlist + suspension already applied)
      directory: Option[Map[String, Boolean]]           // effective category -> campaign listed
  )
  final case class Item(key: String, status: String, detail: Map[String, String])
  def evaluate(in: Inputs, now: java.time.Instant): Vector[Item]
  def firstBlocked(items: Vector[Item]): Option[String]
}
```

Keys and order exactly: `advertiserActive, advertiserBudget, campaignActive, schedule, categories, creatives, approval, campaignBudget, floors, bidders`. Status values: `ok | blocked | warning | unknown`. Thresholds from the spec table: warning when remaining < 10% of daily (checks 2 and 8). Check 9 detail keys: `cleared, eligible, lowestFloor, highestFloor`; check 10 detail key `missing` (comma-joined categories); check 2/8 detail keys `daily, remaining`. Effective categories = `categories`, else `suggestedCategories`; empty and `bidOnUnmatchedContext` → `Set(CategoryId.Filler)`.

- [ ] **Step 1: Failing tests** in `DeliveryCheckSpec`, one fixture `healthy` (all ok) and one case per rule, each asserting the item status and `firstBlocked`:
  - `healthy campaign has no blocked item` → `firstBlocked == None`, all ten keys in order.
  - one test per blocked/warning row of the spec table, on both sides of each 10% threshold.
  - Review Focus 1–4 tests by name (see above).
- [ ] **Step 2: Run** `sbt "api/testOnly promovolve.api.DeliveryCheckSpec"` — Expected: compile failure.
- [ ] **Step 3: Implement** `evaluate`. A site clears when `campaign.maxCpm >= categoryFloors.getOrElse(c, siteFloor)` for some `c` in `carried` (Task 1's rule).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 4: Endpoint, wiring, and the second PR

**Files:**
- Modify: `modules/api/src/main/scala/promovolve/api/Endpoints.scala` (next to `bidCheck` ~102), `ApiModels.scala`, `ApiJsonFormats.scala`, `ApiSchemas.scala`, `EndpointRoutes.scala`, `HttpBootstrap.scala` (~48, ~415)

**Interfaces:**
- Consumes: Task 2 `GetCarriers`, Task 3 `DeliveryCheck`.
- Produces: `GET /v1/advertisers/{advertiserId}/campaigns/{campaignId}/delivery-check` → `DeliveryCheckResponse(items: Vector[DeliveryCheckItem], firstBlocked: Option[String])`, `DeliveryCheckItem(key: String, status: String, detail: Map[String, String])`. Task 5 consumes this JSON.

- [ ] **Step 1:** `EndpointRoutes` gets `campaignDirectory: Option[ActorRef[CampaignDirectory.Command]] = None` (same shape as `categoryRegistry`); `HttpBootstrap` passes `Some(campaignDirectory)`.
- [ ] **Step 2:** `deliveryCheckLogic` behind `requireOwnedCampaign`. Asks in parallel, each `.map(Some(_)).recover { case _ => None }`: `GetBudgetStatus`, `GetCampaign`, `GetBudgetInfo`, `GetCreatives`. Then: `GetCarriers(effective categories)`; drop sites suspended in `SiteSuspendedKey` (ReadLocal, as `bidCheckLogic` reads `VerifiedHostKey`) and outside a non-empty `siteAllowlist`; per remaining site `GetFloorCpm` + `GetCategoryFloors`. Cap at 200 sites with a `ponytail:` comment ("switch to floor_decisions past this"). Directory: one `GetCampaignsFor` per effective category. Any failed per-site ask makes `sites = None`.
- [ ] **Step 3: Run** `sbt scalafmtAll scalafmtCheckAll core/test api/test` — Expected: green.
- [ ] **Step 4:** The gathering code has no unit test (the rules are in Task 3). It is verified live after this PR deploys: `node fetch` the route through the BFF for one real campaign and check all ten keys come back with no `unknown`.
- [ ] **Step 5: Commit**, issue "Delivery check endpoint", PR (Tasks 2–4).

### Task 5: BFF proxy and suspension reason

**Files:**
- Modify: `platform/internal/handler/api.go` (next to `BidCheck` ~99), `platform/cmd/server/main.go` (~434)
- Test: `platform/internal/handler/delivery_check_test.go`

**Interfaces:**
- Consumes: Task 4 JSON.
- Produces: `GET /api/delivery-check?campaignId=` returning Task 4's JSON with item `advertiserActive.detail.reason` set to `org | wallet | other` when that item is blocked. Pure helper `func suspensionReason(orgSuspended bool, balanceMicros int64) string` — `org` if suspended, else `wallet` if `balanceMicros <= 0`, else `other`.

- [ ] **Step 1: Failing test** `TestSuspensionReason` — table: `(true, 500) → "org"`, `(false, 0) → "wallet"`, `(false, -1) → "wallet"`, `(false, 500) → "other"`.
- [ ] **Step 2: Run** `go test ./internal/handler -run TestSuspensionReason` — Expected: FAIL (undefined).
- [ ] **Step 3: Implement** the helper and `DeliveryCheck` handler: proxy like `BidCheck`; when item 1 is blocked, read `h.orgRepo.IsEntitySuspended(ctx, advertiserID)` and `h.billingSvc.GetAccount(ctx, billing.OwnerAdvertiser, advertiserID)` (nil `billingSvc` → `other`), set the reason, re-encode.
- [ ] **Step 4: Run** `make check` in `platform/` — Expected: PASS.
- [ ] **Step 5: Commit** (PR comes with Task 6).

### Task 6: Panel, copy, screenshots, third PR

**Files:**
- Modify: `platform/templates/advertiser/campaigns.html` (above the bid checker, ~420), `platform/internal/i18n/ja.go`

- [ ] **Step 1: Panel** — Alpine block fetching `/api/delivery-check?campaignId={{.ID}}` when the campaign section opens. Headline: the first blocked item's sentence in bold, or "Nothing is stopping this campaign." Below: one line per item with ✓ / ✕ / ! / ?; blocked lines say what to change and link where it lives (budget dialog, schedule fields, creatives, Overview wallet). One `x-show` sentence per key and status, as the bid checker does per reason; numbers come from `detail`.
- [ ] **Step 2: Copy** — every sentence in `{{t}}` with a `ja.go` entry.
- [ ] **Step 3: Run** `make check` — Expected: PASS (drift test included).
- [ ] **Step 4: Screenshots** — Playwright from `platform/dashboard-tests` (copy the script in, remove it after) against a local platform with a stubbed core response for: a healthy campaign, a blocked one (paused), and a warning one (2 of 5 sites clear); EN and 日本語, light and dark. Send to the user and wait for approval.
- [ ] **Step 5: Commit**, issue "Delivery check panel", PR (Tasks 5–6). After deploy, check one real campaign through the BFF.
