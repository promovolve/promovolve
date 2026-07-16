# Self-Hosting Promovolve

How to run your own Promovolve deployment. This guide covers what the
pieces are, the configuration every operator must supply, and the order to
bring things up in. The reference deployment is Kubernetes via
`k8s/` (Kustomize); a local-dev path is at the end.

## The pieces

| Component | What it is | Serves |
|---|---|---|
| Core API (`modules/api`) | Scala / Pekko cluster: auctions, serving, tracking, classification, creative generation | `:8080` HTTP, `:8558` management, `:25520` remoting |
| Platform dashboard (`platform/`) | Go web app: publisher/advertiser/admin UI, passkey auth | `:9090` |
| PostgreSQL + TimescaleDB | Event journal, projections, tracking hypertables | `:5432` |
| Cloudflare R2 (or S3-compatible) | Creative images + the two published JS bundles | your public CDN origin |

And **three public domains** you must own and route:

1. **Ads API domain** (e.g. `ads.example.com`) → core API `:8080`. This is
   what publisher pages call for serving and what viewers' browsers POST
   tracking beacons to. It must be public HTTPS — a localhost or plain-HTTP
   value here silently records no deliveries.
2. **Dashboard domain** (e.g. `dash.example.com`) → platform `:9090`. This
   becomes the WebAuthn Relying Party ID — see the warning in
   [First run](#first-run-the-setup-wizard).
3. **CDN origin** — your R2 bucket's public URL or a custom domain in
   front of it.

## Not included — you provide these

Two external integrations are deliberately left out so you can wire your own.
The app runs fully without them; both are single, well-marked seams.

- **Payments.** There is no payment-gateway integration. Advertiser top-ups
  and publisher payouts are **ledger-first**: an admin records them in the
  billing dashboard (e.g. after a bank transfer clears). That manual path
  *is* the API — to add self-service, point a Stripe (or other PSP) webhook
  at the same `RecordTopup` / payout call, idempotency-keyed on the
  provider's event id. Nothing in settlement or serving depends on a PSP
  existing. See [BILLING.md](../design/BILLING.md).
- **Email / SMTP.** The platform **never sends email**. Passkey-recovery and
  org-invite links are *minted, not mailed* — surfaced via the break-glass
  CLI (`mint-recovery`, [below](#break-glass-cli-run-inside-the-platform-pod))
  or the admin UI and handed to the user out of band. To deliver them by
  email, wrap your own mailer around those link-minting calls; the auth flow
  itself is unchanged.

## Prerequisites

- A Kubernetes cluster (or Docker for the local path) and a container
  registry you can push to.
- A Cloudflare R2 bucket with S3 API credentials and public access
  enabled. R2 is **required at boot** — the core API refuses to start
  without it (`R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`,
  `R2_BUCKET`).
- An LLM API key. The core API **hard-fails at boot** without one of
  `GEMINI_API_KEY`, `OPENAI_API_KEY`, or `ANTHROPIC_API_KEY` (precedence
  in that order). Classification and creative generation run through it.
- Node.js (to build/publish the JS bundles) and JDK/sbt + Go if building
  images yourself.

## Step 1 — R2 bucket and CDN origin

Create the bucket, enable public access (the `pub-….r2.dev` URL or a
custom domain), and create an S3 API token. The public origin becomes
`CDN_BASE_URL` everywhere below.

## Step 2 — Publish the JS bundles

Two bundles live in your bucket and are rebuilt/published from this repo.
Both scripts read R2 credentials + `CDN_BASE_URL` from `scripts/.env`
(copy `scripts/.env.example`):

```bash
scripts/publish-bootstrap.sh   # → promovolve-ad.js (stable alias) + js/promovolve-bootstrap.<hash>.js
scripts/publish-banner.sh      # → js/expandable-magazine-banner.<hash>.js
```

- `promovolve-ad.js` is the **loader** your publishers put in their
  `<script src=…>` tags — its URL is `{CDN_BASE_URL}/promovolve-ad.js`,
  stable across releases (short cache).
- The banner component bundle is content-hashed and immutable; its full
  URL becomes `BANNER_SCRIPT_URL`. The publish script writes the new value
  into `scripts/.env` and `k8s/kustomization.yaml` for you — you redeploy
  after each banner publish.

## Step 3 — Database

Postgres with the TimescaleDB extension (the compose file uses
`timescale/timescaledb:latest-pg15`). Schema comes from
`docker/init-db.sql` — it creates the Pekko persistence tables,
projection/read tables, and the `tracking_events` / `floor_decisions`
hypertables with retention policies. In k8s this is wired automatically
via the `db-initdb` configMap; the app and the dashboard create their
remaining tables at startup.

## Step 4 — Configure and deploy (k8s)

```bash
cp k8s/secrets.env.example k8s/secrets.env                    # core secrets
cp k8s/platform-secrets.env.example k8s/platform-secrets.env  # JWT_SECRET
```

Fill in: `JDBC_PASSWORD`, your LLM key, the four `R2_*` values, and a real
`JWT_SECRET` (e.g. `python3 -c "import secrets; print(secrets.token_urlsafe(48))"`).
Never ship the `change-me-in-production` default — it lets anyone forge
admin sessions. Also set `INTERNAL_API_KEY` to the **same** random value in
both files — it gates the core's `/v1/internal` billing endpoints (metering,
advertiser suspend/resume) and the platform sends it as `X-Internal-Key`.

Then edit `k8s/kustomization.yaml` — the checked-in values point at the
reference deployment's infrastructure and **every one of these must become
yours**:

| Key | Set to |
|---|---|
| `CDN_BASE_URL` | your R2 public origin (step 1) |
| `BANNER_SCRIPT_URL` | written by `publish-banner.sh` (step 2) |
| `TRACKING_BASE_URL` | `https://<ads-api-domain>/v1` |
| `ALLOWED_ORIGIN`, `RP_ID`, `RP_ORIGINS` | your dashboard domain (`RP_ID` is the bare host) |
| `images:` `newName`/`digest` | your registry's `promovolve-api` / `promovolve-platform` images |

Build and push the images, then apply:

```bash
docker build -f Dockerfile.api -t <you>/promovolve-api:tag .
docker build -f platform/Dockerfile -t <you>/promovolve-platform:tag platform/
docker push <you>/promovolve-api:tag && docker push <you>/promovolve-platform:tag
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/ | kubectl apply -f -
```

> Plain `kubectl apply -k k8s/` fails with a `file is not in or below`
> error: the DB-init ConfigMap sources `../docker/init-db.sql` from
> outside the kustomize root (single source of truth, no copy), which
> needs the `--load-restrictor` flag — and `-k` can't pass it.

If your registry is private, create the `regcred` pull secret in the
`promovolve` namespace (see `k8s/README.md`); on a local kind cluster you
can `kind load docker-image` instead and skip the registry.

Topology notes:

- `promovolve-api` is a 2-replica StatefulSet carrying the
  `entity`,`api`,`crawler` roles; cluster formation uses the Kubernetes
  API (needs the ServiceAccount + RBAC in `k8s/api-rbac.yaml`) and waits
  for 2 contact points. The `crawler` role must be carried by some pod —
  without a carrier, landing-page creative generation breaks silently.
- `promovolve-platform` must stay at **1 replica** — WebAuthn ceremony
  state is in memory.
- Config in the shared `api-config` configMap (e.g. a `BANNER_SCRIPT_URL`
  bump) rolls **every** core pod on apply. For a value only the platform
  reads, prefer a surgical `kubectl set env deployment/promovolve-platform`
  over a full re-apply.

Route your ads-API and dashboard domains to the `promovolve-api` and
`promovolve-platform` services. The reference deployment uses a GKE Ingress
with Google-managed certificates (`k8s-gke/` — a kustomize overlay over
`k8s/`); a Cloudflare tunnel remains a fine option for exposing a local
cluster.

## First run — the /setup wizard

Until an admin account exists, the dashboard redirects every request to
`/setup`, which creates the single admin account with a **passkey**.

> **Do this only after `RP_ID` is set to the final dashboard domain.**
> Passkeys are cryptographically bound to `RP_ID`; changing it later
> invalidates every registered credential, including the admin's. There is
> a break-glass `mint-recovery` CLI for admin lockout (see **Break-glass
> CLI** below), but don't plan on needing it.

After setup: publishers and advertisers request accounts from the login
page; an admin approves them from the admin view (approval is what
provisions the entity), and they register their own passkeys.

Publisher sites go through the same gate: adding a site only files a
request, an admin approves it from **Site Requests** (approval is what
creates the site on the ad server), and only then can the publisher run
domain verification (hosted file or DNS TXT — works out of the box).
For dev/test environments only, `POST /v1/publishers/{pub}/sites/{siteId}/force-verify`
bypasses verification — never expose test routes in production
(`ENABLE_TEST_ROUTES` must stay `false`).

## Suspending a company

The operator can suspend an organization from **Admin → Users →
Organizations** (a required reason is part of the form). Suspension is a
reversible freeze of the whole relationship:

- **Members can still log in** — their passkeys keep working — but every
  dashboard page is replaced by a notice showing your reason, so nobody
  debugs a mystery 403. Log out is the only action left to them.
- **Serving stops on both sides.** The advertiser side is benched exactly
  like a wallet suspension (approvals kept); the publisher side's sites
  refuse every ad request, so impressions — and therefore earnings and
  spend — stop.
- **Money freezes too.** New top-ups, payouts, and adjustments for the
  org are refused; recording an external payment as paid or cancelling an
  existing payout remains possible. A wallet top-up recorded by mistake
  will NOT resume serving: operator suspension outranks wallet health.
- **Resume restores everything** — dashboards on the members' next
  request, sites within seconds, and the advertiser side unless its wallet
  is still unfunded (then the normal prepaid policy keeps it benched).

Suspend/resume actions land in the audit log with the reason.

## Break-glass CLI (run inside the platform pod)

Two subcommands on the platform binary cover the cases the passkey-only
login can't. Both are deliberately unreachable from the web: you run them
via `kubectl exec`, and anyone who can do that already owns the database
and `JWT_SECRET`, so they grant nothing new.

**Admin lockout** — the lone admin lost their passkey, so no session exists
to mint a recovery link through the UI:

```bash
kubectl exec deploy/promovolve-platform -- /server mint-recovery --email <email>
```

Prints a one-time recovery link (72 h, single use) on the public origin.
Open it on the device that should hold the new passkey. Only the token's
sha256 is stored, and consuming it is atomic with the credential insert.

**Scriptable login** — automation (agents, e2e checks, smoke tests) can't
perform a passkey ceremony, which needs a real browser authenticator:

```bash
kubectl exec deploy/promovolve-platform -- /server mint-session --email <email>
```

Prints a session JWT for an **active** account (pending/rejected accounts
are refused). Use it as the `token` cookie:

```bash
curl -H "Cookie: token=<jwt>" https://<dashboard-domain>/admin/requests
```

The token carries the account's role, expires after `JWT_EXPIRY`
(default 24 h), and dies early if the account is rejected — every page
render re-checks account status.

## Configuration reference

### Core API — boot fails without these

| Var | Notes |
|---|---|
| `CDN_BASE_URL` | no default — config resolution fails if unset |
| `GEMINI_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | at least one |
| `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET` | all four |

### Core API — required for correct operation

| Var | Default | Notes |
|---|---|---|
| `BANNER_SCRIPT_URL` | empty | empty → ads never render (no crash) |
| `TRACKING_BASE_URL` | `http://localhost:8080/v1` | must be the public HTTPS ads-API URL or nothing is tracked |
| `JDBC_URL` / `JDBC_USER` / `JDBC_PASSWORD` | localhost / promovolve / promovolve | |
| `HTTP_HOST` / `HTTP_PORT` | 0.0.0.0 / 8080 | |
| `DDATA_DIR` | `target/ddata` | persistent volume in k8s (`/data/ddata`) |
| `ENABLE_TEST_ROUTES` | false | never true in production |

### Platform dashboard

| Var | Default | Notes |
|---|---|---|
| `DATABASE_URL` | localhost dev DSN | |
| `CORE_API_URL` | `http://localhost:8080` | in-cluster service URL, not the public domain |
| `JWT_SECRET` | `change-me-in-production` | **must** override |
| `RP_ID` / `RP_ORIGINS` | `localhost` / `http://localhost:9091` | permanent once passkeys exist |
| `ALLOWED_ORIGIN` | empty (CORS `*`) | set to the dashboard origin in prod |
| `BANNER_SCRIPT_URL` | empty | used by the creative designer preview |
| `CDN_BASE_URL` | – | read directly by the asset proxy |
| `INTERNAL_API_KEY` | empty | must match the core's value; sent on billing settlement calls |
| `DEV_AUTH` | unset | `true` re-enables password auth — local dev only |

## Rotating secrets

Secrets are deliberately **not** editable from the dashboard: two of them
can't be (the DB password is needed to reach the settings store, and
`JWT_SECRET` signs the admin session that would be doing the editing), the
core reads the rest only at boot, and keeping credentials out of the UI
means a compromised admin session can't become an infrastructure
compromise. Business settings that change routinely — margin, payout
floor — live in the dashboard instead.

Rotation is one edit and one command. The env files feed kustomize
`secretGenerator`s, which hash the secret contents into the secret's
*name* — so an apply rolls exactly the pods that consume the changed
secret, with correct restart ordering, and nothing else:

```bash
$EDITOR k8s/secrets.env            # and/or k8s/platform-secrets.env
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/ | kubectl apply -f -
kubectl rollout status statefulset/promovolve-api -n promovolve      # if core secrets changed
kubectl rollout status deployment/promovolve-platform -n promovolve  # if platform secrets changed
```

Per-key notes:

| Key | Rotation effect |
|---|---|
| LLM key, `R2_*` | api pods roll; no user-visible impact beyond the restart (expect the usual 1–2 min ad-fill warmup per pod) |
| `JWT_SECRET` | every dashboard session is invalidated — all users sign in again. That's the point; do it deliberately |
| `INTERNAL_API_KEY` | change it in **both** env files in the same apply. A brief mismatch is harmless — the settlement job gets 403s, logs them, and retries next tick — but a lasting one silently stops settlement (watch the admin Billing page's settlement-health panel) |
| `JDBC_PASSWORD` | two-step: change the database user's password first (`ALTER USER promovolve …` — and the db-secret if you manage Postgres in-cluster), then rotate this and apply |

Keep the env files out of version control (they're gitignored) and back
them up somewhere private — they are the only copy of your credentials
that the deploy reads.

## Local development

```bash
docker compose up postgres          # TimescaleDB on :5432, schema from docker/init-db.sql
cp scripts/.env.example scripts/.env   # fill R2 + LLM key + CDN_BASE_URL
scripts/run-dev.sh --fresh          # core API on :8080 (--fresh wipes DB + DData + projection offsets)
scripts/run-dashboard.sh            # dashboard on :9091, DEV_AUTH=true, RP_ID=localhost
```

`--fresh` matters: DData `remember-entities` state survives restarts and
resurrects stale entities if you truncate the database without also
clearing DData — the script does both.

Expect an **ad-dark window of ~1–2 minutes after any core restart** while
in-memory demand state rebuilds; it self-heals, don't chase it.
