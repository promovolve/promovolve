# Reporting wire contract (Scala ⇄ Go)

The Go dashboard decodes reporting JSON produced by the Scala API. Neither
compiler sees the other side, so a renamed field or a changed type used to
compile everywhere and only break when someone opened the report page.

The JSON files in this directory are the **contract source**: one canonical
body per response. Both sides read these same files in CI.

| Fixture | Response | Consumed by |
|---|---|---|
| `advertiser-report.json` | `AdvertiserReportResponse` | `fetchReportRows` |
| `advertiser-report-empty.json` | same, no rows | `fetchReportRows` |
| `advertiser-report-breakdown.json` | `AdvertiserReportBreakdownResponse` | `fetchReportBreakdown` |
| `publisher-site-categories.json` | `PublisherSiteCategoryReportResponse` | `fetchPublisherSiteCategories` |
| `publisher-site-categories-empty.json` | same, no rows | `fetchPublisherSiteCategories` |

**Producer** — `modules/api/src/test/scala/promovolve/api/ReportContractSpec.scala`
parses each fixture into its DTO and re-encodes it, requiring an identical
AST. That pins field names and types in both directions: an added, dropped or
renamed field changes the JsObject and fails.

**Consumer** — `platform/internal/handler/report_contract_test.go` serves each
fixture from a test server and runs the real `fetch*` helpers, asserting the
decoded values, the money parsing and the derived display. A renamed field
decodes as a zero value and fails there.

Both run in CI already: `sbt test` (Scala core job) and `make check`
(Platform job).

## Changing the wire format

Edit the fixture and both sides **in the same PR**. Editing the fixture alone
fails whichever side has not caught up, which is the intended behavior — that
red test is the drift alarm, not an obstacle to route around.

## What the values are chosen to catch

- **Money is a 4-decimal string**, in dollars — not a number, not cents, not
  micros. `1234.5678` catches unit slips; `0.0001` catches sub-cent rounding
  to zero.
- **`grossRevenue` is pre-margin.** The Go side nets it down (30% in the test
  → 700 from 1000). Sending net here would deduct the margin twice.
- **Blank strings are meaningful**, never null: `host` `''` = no healed host,
  `category` `''` = uncategorized, `key` `''` = no publisher row, `label` `''`
  = fall back to the key.
- **`coverageFrom`** dates the "data starts" note; `''` = no rollups yet.
- **Empty results are `[]`**, never null — the Go side ranges without a nil
  check.
- **Four separate dog-ear counters** (`folds`, `unfolds`, `dogearedClicks`,
  `dogearedCtaClicks`). Crossing two is invisible in aggregate and wrong on
  the Engagement tile.

Out of scope here: serve and tracking contracts consumed by JavaScript
clients, and endpoints the dashboard does not decode.
