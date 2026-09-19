# GKE deployment — the public Promovolve cluster

This is **the** deployment. Promovolve is not operated as a business, so
there is no production tier above this — the cluster is deliberately
staging-grade and cost-shaped (~**$65–75/mo**, see below).

| Choice | Value | Why |
|---|---|---|
| Project | `promovolve` | dedicated project = the whole bill in one place |
| Cluster | zonal, `asia-northeast1-b` | free-tier credit covers the $73/mo management fee for one zonal cluster; `-b` is the only Tokyo zone with c4a |
| Nodes | 1 × `c4a-standard-4` (ARM/Axion) **Spot** | ARM because images are pushed single-arch arm64 from the dev Mac (x86 nodes = `exec format error`); spot ~60-70% off, preemption = a few minutes of Pekko self-heal |
| Postgres | in-cluster (same StatefulSet as dev) | ~free beyond the 5Gi PVC |
| Storage | `hyperdisk-balanced` StorageClass, patched onto every PVC | c4a machines cannot attach pd-* volumes |
| Images | private Docker Hub + `regcred` | identical push workflow to the DD cluster |
| TLS | GKE Ingress + Google-managed certs | one global HTTPS LB for both hosts |
| Hostnames | `promovolve.programmer.llc` (dashboard), `ads.programmer.llc` (serve/tracking) | the identities the base config already advertises (`RP_ID` binds passkeys to the host) |

## Bring-up

```sh
gcloud auth login                 # interactive, once
k8s-gke/setup.sh                  # idempotent: project → IP → cluster → deploy → verify
```

Then follow the printed next steps: grey-cloud A records at Cloudflare,
wait for the managed cert, register the first account at `/setup`.

## Running your own deployment (open source)

The digests pinned in `k8s/kustomization.yaml` point at **private** Docker
Hub repos under `hanishi` — this cluster is the maintainer's staging
deployment, and those images are not pullable by anyone else. Build and
push your own instead:

```sh
docker login ghcr.io                                   # your registry
REGISTRY=ghcr.io/you k8s-gke/setup.sh --build-images
```

`--build-images` builds `Dockerfile.api` and `Dockerfile.platform` for
**linux/arm64** (the c4a nodes are ARM — an amd64 image dies with `exec
format error`), pushes both to `$REGISTRY`, and deploys exactly the digests
it just pushed. A first install therefore never depends on someone else's
registry, nor on this repo's pins being fresh. Re-run it to redeploy after
code changes.

Everything else is already parameterised — pick your own project, zone and
machine type:

```sh
PROJECT_ID=acme-ads ZONE=us-central1-a MACHINE=c4a-standard-4 \
  REGISTRY=ghcr.io/you k8s-gke/setup.sh --build-images
```

If your images are **private**, create the pull secret yourself first (the
script only mints `regcred` for the maintainer's Docker Hub account):

```sh
kubectl -n promovolve create secret docker-registry regcred \
  --docker-server=ghcr.io --docker-username=you --docker-password=<token>
```

Public images need no secret. You will also want your own hostnames — see
`RP_ID` and the ingress hosts below, since `RP_ID` binds passkeys to a host
permanently.

## Redeploys (maintainer / staging)

CI builds and rolls images by digest on every merge to `main`; that is the
normal path and needs nothing here. For an infra/config-only change:

```sh
k8s-gke/setup.sh --deploy-only
```

which preserves the running CI-deployed images across the apply. To
deliberately deploy the digests pinned in `k8s/kustomization.yaml` instead,
add `--pin-images`.

> **The pins are written back by CI** (`deploy.yml`'s `pin-images` and
> `publish-banner` jobs, via `scripts/pin-back-pr.sh`), so
> `k8s/kustomization.yaml` describes what is actually deployed. Do not
> hand-edit them. They land **through a pull request** on the `ci/pins`
> branch — `main` requires a PR and green checks, so CI cannot push to it —
> which auto-merges once CI passes. Until it merges the pins lag the
> cluster, and `setup.sh` refuses to apply while that PR is open
> (`--allow-open-pins` overrides).
>
> They used to be hand-maintained, and drifted permanently behind `main`.
> That is why `--deploy-only` preserves the running images across an apply:
> a plain apply deploys the pins, which rolled the app backwards twice —
> four shipped fixes reverted on 2026-07-12, and on 2026-07-27
> `scripts/gke-factory-reset.sh` reverted api + singleton to a four-day-old
> build, taking every `/v1/internal/*` route with it. That preserve step is
> now belt-and-braces rather than load-bearing.
>
> It matters most on a **clean** install: there is nothing running to
> preserve, so the pins are simply what deploys.

The kubectl context is `gke_promovolve_asia-northeast1-b_promovolve`; the
script always passes `--context` explicitly, so it can't land in
`docker-desktop` (and `up.sh`'s guard keeps the reverse from happening).

## Architecture notes (what differs from the DD cluster)

- **Services are ClusterIP**, not LoadBalancer. One GKE Ingress (global
  HTTPS LB, static IP `promovolve-ingress`) does host routing:
  dashboard → platform:9090, ads → api:8080. HTTP redirects to HTTPS
  (FrontendConfig) — plain HTTP would break passkeys (Secure cookies /
  WebAuthn) and mixed-content-block tracking beacons.
- **SSE**: `BackendConfig.timeoutSec=3600` on both services. The GCLB
  default of 30s would cut every stream at exactly core's first 30s
  heartbeat. EventSource reconnects on the hourly cut.
- **LB health checks** are explicit (`BackendConfig.healthCheck`): api =
  `/ready` on the management port 8558 (cluster members only, mirrors the
  readinessProbe), platform = `GET /health` on 9090. Without them the GCLB
  would probe `/` on the serving port and mark api backends unhealthy.
- **BFF→api pinning still works**: the GCLB reaches pods via NEGs, but
  in-cluster `CORE_API_URL` traffic still traverses the ClusterIP, so the
  base's `sessionAffinity: ClientIP` keeps the single platform pod pinned
  to one api pod (read-your-writes + SSE attachment).
- **Cloud Logging ingests SYSTEM only** — the two JVMs are too chatty for
  the 50GB/mo free tier. `kubectl logs` is unaffected.
- **DNS must stay grey-cloud** (DNS-only) at Cloudflare: managed-cert
  issuance/renewal needs the hostnames to resolve straight to the LB.

## Spot caveats

- A preemption (30s notice) looks like a full cluster restart: both api
  pods die, the replacement node arrives in ~1–2 min, Pekko re-forms and
  replays, ads go dark for the usual ~1–2 min self-heal window. Postgres
  and DData PVCs survive and reattach.
- If ads are dark and pods are young, that's the [post-restart dark
  window](../../docs) — wait, don't debug.

## Cost levers

- Pause when idle (PVCs/IP/DNS survive, LB answers 502):
  `gcloud container clusters resize promovolve --zone asia-northeast1-b --num-nodes 0 --quiet`
  and `--num-nodes 1` to resume (~3–5 min to full health).
- The static IP is free while attached to the LB; if you tear the Ingress
  down but keep the IP, it bills ~$7/mo as an unattached reserved IP.
- Delete everything: `gcloud projects delete promovolve` (the whole bill
  lives in this one project).

## CI deploys (.github/workflows/deploy.yml)

Merge to `main` → the changed image(s) build natively on arm64 runners →
Docker Hub (`main-<sha>` tags; `:dev` stays laptop-owned) → `kubectl set
image` by digest on the GKE workloads → the rolled digests (and, after a
banner publish, the bundle URL) are written back to `k8s/kustomization.yaml`
via an auto-merging pull request. CI never renders the kustomize overlay
(secrets stay off GitHub), so **a manual `setup.sh --deploy-only` renders
the pins** — which is why they are kept true, and why the script refuses to
run while a pin-back PR is still open.

`main` is protected by a Ruleset (pull request + the five CI checks, squash
merges only). Nothing pushes to it directly — not contributors, not CI. The
pin-back PR needs two repository settings that are already on (*Allow
GitHub Actions to create and approve pull requests*, *Allow auto-merge*)
and one secret: **`PIN_DEPLOY_KEY`**, a write deploy key the pin jobs push
the `ci/pins` branch with. A push made with `GITHUB_TOKEN` fires no
workflow, and check runs from a dispatched run do not count toward
required checks, so without the key the PR opens but never merges. Create
it once with:

```sh
scripts/setup-pin-deploy-key.sh
```

which generates an ed25519 key, registers the public half as a deploy key
with write access, stores the private half as the secret, and discards it
locally. Rotate by running it again. The organization policy *Deploy keys*
must allow them (`deploy_keys_enabled_for_repositories` on the org; it had
been switched off on promovolve and was re-enabled 2026-09-04) — the script
says so if registration is refused.

Auth: GCP via Workload Identity Federation (pool `github`, provider
`github-oidc`, SA `github-deployer@promovolve.iam.gserviceaccount.com`,
restricted to this repo — no keys). Docker Hub via DOCKERHUB_USERNAME /
DOCKERHUB_TOKEN repo secrets.
