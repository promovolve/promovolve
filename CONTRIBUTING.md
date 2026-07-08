# Contributing to PromoVolve

Thanks for your interest in improving PromoVolve. This project's premise is that
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

- **JDK 17+ and sbt** (Scala 3.7)
- **Go 1.26+**
- **Node.js** (to build the Tailwind CSS and the JS ad bundles)
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

# Go platform
cd platform
go build ./...
go test ./cmd/...        # server + tools
```

Notes:

- **The core test suite has a known-failing baseline** (a handful of
  pre-existing failures unrelated to most changes). Compare the *set of failing
  test names* before and after your change — a non-zero exit code alone doesn't
  mean you broke something. If your change adds a new failure, fix it or explain
  why.
- **Integration specs that need API keys skip themselves** when the relevant env
  var is unset (`ANTHROPIC_API_KEY`, `GEMINI_API_KEY`). Never hardcode a key to
  make one run — read it from the environment (see the existing specs).

## Code style

- **Scala** — run `sbt scalafmtAll` before committing (config in
  `.scalafmt.conf`). CI-style check: `sbt scalafmtCheckAll`.
- **Go** — `gofmt`/`goimports` and `go vet ./...`. Keep to standard Go style.
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

- **Conventional-commit style** subject lines (`feat:`, `fix:`, `chore:`,
  `docs:`, `refactor:`), matching the existing history.
- Keep PRs **focused** — one logical change per PR is much easier to review.
- In the PR description, say **what you changed, why, and how you verified it**
  (which tests you ran, or how you exercised the behavior). Screenshots help for
  UI changes.
- Update the relevant docs in `docs/` when you change behavior.

Thanks for helping keep ad tech honest.
