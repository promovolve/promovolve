# Contributing to Promovolve

Thanks for your interest in improving Promovolve. This project's premise is that
an ad exchange can be **legible** — the auction, pricing, and pacing are meant to
be readable and verifiable, not hidden. Contributions that keep it that way
(clear code, honest docs, no dark patterns) are especially welcome.

By submitting a contribution you agree it is licensed under the project's
[Apache License 2.0](LICENSE).

## Ways to contribute

- **Bugs & features** — open an issue describing the behavior you saw vs.
  expected, or the change you'd like. For anything non-trivial, an issue first
  (before a PR) saves everyone time.
- **Docs** — the design docs in [`docs/`](docs/) are first-class. Fixes and
  clarifications there are as valuable as code.
- **Security** — please **do not** open a public issue for a vulnerability.
  Report it privately to the maintainer and allow time for a fix before
  disclosure.

## Project layout

See the [README](README.md#repository-layout) for the map. The two build targets:

- **`modules/`** — the Scala 3 / Apache Pekko core (auctions, serving, tracking,
  classification, creative generation). Built with **sbt**.
- **`platform/`** — the Go dashboard / BFF (server-rendered templates, htmx,
  Tailwind, passkey auth). Built with **go**.

## Prerequisites

- **JDK 21 and sbt** (Scala 3.7)
- **Go 1.26+**
- **GNU Make** (Go platform tasks)
- **Node.js 24** (to build the Tailwind CSS and the JS ad bundles)
- **Docker** (local Postgres/TimescaleDB)
- To run the full system end-to-end you also need an S3-compatible bucket
  (Cloudflare R2) and one LLM API key (Gemini, OpenAI, or Anthropic) — the core
  **refuses to boot without both**. Unit tests do not require them.

## Local development

```bash
docker compose up -d postgres          # TimescaleDB on :5432 (container promovolve-db)
cp scripts/.env.example scripts/.env   # fill in R2 credentials + an LLM API key
scripts/run-dev.sh --fresh             # core API on :8080  (--fresh resets the DB)
scripts/run-dashboard.sh               # dashboard on :9091
```

See the [Self-Hosting guide](docs/guides/self-hosting.md) for the full
configuration surface.

## Building & testing

```bash
# Scala core
sbt compile
sbt test                 # all modules; or e.g. `sbt "core/testOnly *FloorSweep*"`

# Go platform — build + vet + `go test -race` over every first-party package,
# exactly what CI runs
make -C platform check
```

Run `make -C platform help` to list targets. `build`, `vet`, and `test` can also run individually; `build-server` writes `platform/server`.

Notes:

- **`sbt test` must be green, and CI runs it.** There is no known-failing
  baseline any more: a non-zero exit code means something is broken. If your
  change makes a test fail, fix it — don't work around it.
- **Tests that leave the JVM are excluded by tag, not by luck.** Anything that
  calls a live LLM provider or shells out to a host tool (ffmpeg) carries the
  `promovolve.Integration` tag from
  `modules/core/src/test/scala/promovolve/TestTags.scala`, and `build.sbt`
  excludes that tag from `sbt test`. Run them deliberately, with the relevant
  keys in the environment:

  ```bash
  sbt "testOnly * -- -n promovolve.Integration"
  ```

  Tag any new test of that kind the same way. Never hardcode a key to make one
  run — read it from the environment (see the existing specs).

- **Scala database tests live in their own project, `dbIt`.** They check the
  Slick repositories against a real TimescaleDB: the traffic-shape snapshot
  round trip, the `ensureSchema` upgrade of a table written before the
  weekday/weekend split, and `docker/init-db.sql` applied to an empty
  database. `dbIt` is deliberately not aggregated by the root project, so
  `sbt test` never runs it and stays Docker-free. CI runs it as its own job
  ("Scala persistence (timescaledb)"). With Docker running:

  ```bash
  sbt dbIt/test
  ```

  It starts one pinned TimescaleDB container for the run. If Testcontainers
  cannot find your Docker socket — Docker Desktop on macOS serves an API it
  rejects — point the tests at a server you started yourself instead. It must
  be TimescaleDB, because init-db.sql creates the extension:

  ```bash
  docker run -d --rm -e POSTGRES_USER=promovolve -e POSTGRES_PASSWORD=promovolve \
    -e POSTGRES_DB=promovolve_test -p 55441:5432 timescale/timescaledb:2.17.2-pg15
  DB_IT_DATABASE_URL='jdbc:postgresql://localhost:55441/promovolve_test' sbt dbIt/test
  ```

- **The Go billing suite has a database half, behind a build tag.** Ledger
  transactionality, idempotency keys, local-day settlement, fraud holds and
  clawbacks are SQL behavior, so those tests run against a real Postgres.
  `make -C platform check` skips them and needs no Docker; CI runs them in a
  separate mandatory job ("Platform billing (postgres)"). With Docker running:

  ```bash
  go test -tags=integration ./internal/billing/      # from platform/
  ```

  `TestMain` starts one pinned Postgres for the package run (Testcontainers)
  and each test migrates its own throwaway schema with the production
  `db.Migrate`. To use a server you already have instead of a container —
  faster on repeat runs, and safe against the dev database since nothing is
  created outside the per-test schema:

  ```bash
  BILLING_TEST_DATABASE_URL='postgres://promovolve:promovolve@localhost:5432/promovolve?sslmode=disable' \
    go test -tags=integration ./internal/billing/
  ```

  Add database-backed tests to that build tag, never to the Docker-free job.

## Code style

- **Scala** — run `sbt scalafmtAll` before committing (config in
  `.scalafmt.conf`). CI-style check: `sbt scalafmtCheckAll`.
- **Go** — `gofmt`/`goimports`; `make -C platform check` runs `go vet` over the
  first-party packages (a bare `./...` also sweeps up Go snippets that npm
  vendors under `node_modules`). Keep to standard Go style.
- **Match the surrounding code.** Naming, comment density, and idiom should look
  like the file you're editing.

Two build traps worth knowing (both self-inflicted footguns if skipped):

- **Tailwind is compiled, not CDN.** If you change template classes, rebuild the
  committed stylesheet with `scripts/build-tailwind.sh` (Docker images compile it
  in-image, but a local `go run` serves the committed `static/tailwind.css`).
- **In-app help is synced from `docs/guides/`.** Those pages are embedded copies;
  a drift test fails if they diverge. Edit the originals under `docs/guides/` and
  run `scripts/sync-help.sh`.

## Secrets — never commit them

- API keys, JWT secrets, R2 credentials, and passwords **must not** land in the
  repo or its history. `scripts/.env`, `k8s/secrets.env`, and
  `k8s/platform-secrets.env` are git-ignored — keep real values there, and
  commit only the `*.env.example` templates.
- Read secrets from the environment in code and tests. If you see a hardcoded
  credential, treat it as a bug.

## Commits & pull requests

- **Everything lands on `main` through a pull request** — a repository
  Ruleset requires one, with the CI checks green, and squash-merges it.
  Maintainers included; CI's own digest pin-back goes through a PR too
  (`scripts/pin-back-pr.sh`). Merging to `main` is what deploys.
- **Conventional-commit style** subject lines (`feat:`, `fix:`, `chore:`,
  `docs:`, `refactor:`), matching the existing history.
- Keep PRs **focused** — one logical change per PR is much easier to review.
- In the PR description, say **what you changed, why, and how you verified it**
  (which tests you ran, or how you exercised the behavior). Screenshots help for
  UI changes.
- Update the relevant docs in `docs/` when you change behavior.

Thanks for helping keep ad tech honest.
