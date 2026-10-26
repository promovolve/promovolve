# Classifier eval

A small safety net for the page classifier: a couple dozen real pages with
the answer we'd accept, run through the **real** classifier, scored, and
diffed against the last run. It answers "did this prompt change make the
classifier better or worse on pages we care about" with a table instead of
a reload and a squint at logs.

Not CI. It calls a paid model and reads live pages. Run it by hand:

- before merging any change to `IABTaxonomy.buildPrompt`
- before bumping `ClassificationEntry.CurrentClassifierVersion` — that
  re-classifies the whole corpus, so the new prompt had better be at least
  as good
- occasionally unprompted: the provider's model changes under us
  (`gemini-2.0-flash` was retired in 2026-08 with a 404)

## Run

```sh
export GEMINI_API_KEY=…            # the same key the app uses (or OPENAI_/ANTHROPIC_API_KEY)
scripts/classify-eval/run.sh --label before
# …edit the prompt…
scripts/classify-eval/run.sh --no-extract --label after --baseline scripts/classify-eval/out/before.json
```

`--no-extract` reuses the page text captured by the first run, so an A/B
compares prompts on identical input. `--no-hints` ignores the page's
`data-section` / `data-place` and shows what the hints buy (on the
programmer.llc set: the 温泉 archive and Kinosaki lose their categories
without them). `--strict` exits 1 on any wrong / invented / missing cell.

## Files

| file | what |
|---|---|
| `pages.tsv` | the eval set: `url  categories  places  note`. `\|`-separated alternatives, any one = hit; a child of an accepted id (a city inside the region, a leaf under the category) also counts as hit, an ancestor counts as **broad**; empty = not checked; `-` = must be empty |
| `extract.mjs` | Playwright: captures each page's text exactly as the ad tag does (`bootstrap.ts extractPageText`, 8,000 chars, body text nodes, no script/style), plus the page's own `data-section` / `data-place` → `pages/*.json` (gitignored) |
| `run.sh` | extract (unless `--no-extract`) then `sbt core/Test/runMain promovolve.tools.ClassifyEval` |
| `modules/core/src/test/scala/promovolve/tools/ClassifyEval.scala` | the runner: `IABTaxonomy.analyze` (same prompt, provider and `Places.resolveEmitted` post-processing as the serve path), scoring, `out/<label>.json`, baseline diff |

## Reading the table

```
kinosaki-sotoyu/   cats=[Spas(671), Travel Type(664)] hit   places=[Toyooka(GN1849831)] hit
category/onsen/    cats=[] missing                          places=[Toyooka(GN1849831)] —
```

`hit` — an accepted answer (or something more specific) is present.
A category that is a *sibling* of an accepted one (Europe Travel beside the
expected Asia Travel) makes the cell `WRONG` even when the accepted one is
there too; unrelated extras are not judged.
`broad` — the model stopped at a parent of what we wanted (Travel for a Spas
page): serves broader campaigns, loses the specific ones. `missing` — nothing
came back where something should have. `WRONG` / `INVENTED` — answered
something unacceptable / answered where `-` demanded silence; these are the
ones to act on. `—` — not asserted in the TSV.

## Growing the set

Add a line per page you care about, with the answer *you* would give; keep
alternatives generous (`653|664` = "anywhere in Travel is fine") and reserve
`-` for pages that really are about nowhere. Twenty pages you know beat a
thousand you don't. Category ids: `modules/core/src/main/resources/iab_content_taxonomy/3_0.tsv`;
place codes: search the dashboard's place picker, or `Places.search` — the
chip shows the code.
