# Running Promovolve on Kubernetes (local dev base)

Deploys the full stack into a local Kubernetes cluster — Docker Desktop's
built-in Kubernetes or a standalone kind cluster (`k8s/kind-cluster.yaml`).
This directory is the **kustomize base**; the public GKE deployment lives in
`k8s-gke/` as an overlay over it (see its README). The app tier runs **2
all-roles api pods with soft (preferred) anti-affinity** — they spread across
nodes when possible but co-locate on a single node without changes (see
[Single-node fallback](#single-node-fallback)).

| Component        | Kind         | Why                                                        |
|------------------|--------------|------------------------------------------------------------|
| `promovolve-db`  | StatefulSet  | TimescaleDB (hypertables + retention/compression) on a PVC |
| `promovolve-api` | StatefulSet  | `promovolve.api.Main` — Pekko cluster; every pod carries ALL roles (singleton+entity+api+crawler) + LMDB DData on a PVC. All Playwright work (designer/LP creative generation) runs in-process. |
| `promovolve-platform` | Deployment | Go dashboard / BFF (stateless)                          |

## Topology decisions (change here if your needs differ)

- **App tier = 2 all-roles pods (`api-statefulset.yaml: replicas: 2`) — the
  CONSOLIDATED topology (2026-07-02).** Every pod carries ALL roles
  (`singleton`+`entity`+`api`+`crawler` — see `application-app.conf`). The
  dedicated crawler tier was **deleted** and the dedicated singleton tier
  (`singleton-statefulset.yaml`) is **parked at `replicas: 0`**; the comments
  in those files document the rationale and the real-infra restore path.
  The pods form ONE cluster via **Pekko Management + Cluster Bootstrap** over
  **Kubernetes API discovery** (`pekko-management*` +
  `pekko-discovery-kubernetes-api`, `PEKKO_CLUSTER_BOOTSTRAP=on`, the
  `application-app.conf` overlay that empties `seed-nodes`, pod-IP via the
  downward API, and the `api-rbac.yaml` ServiceAccount for pod discovery).
  **Split-brain handling.** A Split Brain Resolver is configured
  (`pekko.cluster.split-brain-resolver`, `keep-majority` scoped to
  **`role = "entity"`** — `application.conf`). With 2 entity nodes an even
  count is tolerable: a clean 1-vs-1 split is broken by the lowest-address
  rule, so one side always survives, the other self-downs and rejoins. On
  REAL multi-node infra prefer an odd entity count — the restore path is
  these 2 pods + the singleton pod scaled to 1 (roles `[singleton,entity]`)
  = 3 entity members.
  Bootstrap uses **`PEKKO_BOOTSTRAP_REQUIRED_CONTACT_POINTS=2`**
  (`api-config`): a NEW cluster only forms when both pods see each other (a
  lone pod can never form a split cluster); rejoin of an existing cluster is
  unaffected.
  *Historical note:* the self-down cascades that once motivated a 3-entity +
  dedicated-crawler layout were root-caused (2026-07-02) to bare-tuple ask
  replies deserializing to `null` and killing Artery inbound streams — not
  the topology (see the comments in `api-statefulset.yaml`).
  For **local single-node dev** (`run-dev.sh`), `PEKKO_CLUSTER_BOOTSTRAP` is
  unset, so the node uses the static loopback `seed-nodes` — no k8s needed;
  the single all-roles node makes the SBR a no-op.
- **Dashboard read-your-writes / live SSE — `sessionAffinity: ClientIP`** on
  `api-service.yaml`. The single platform BFF makes the dashboard's reads,
  writes, and the SSE event stream; round-robining those across app pods caused
  "had to refresh to see a new site" and blank live publisher pages. Affinity
  pins the BFF (one source IP) to one of the 2 app pods, so its reads see its
  own writes. Browser ad-serve/tracking traffic still spreads across both.
- **Shards pinned to entity nodes.** `application.conf` sets
  `pekko.cluster.sharding.role = "entity"` so shard regions **and the shard
  coordinator** only run on entity-role nodes. A no-op while every pod carries
  the entity role, but it protects the restore path where a non-entity pod
  joins the cluster (the coordinator drifting onto one wedges shard
  allocation — observed with the old dedicated crawler tier).
- **Crawler is a ROLE, not a tier.** The dedicated crawler Deployment was
  deleted in the 2026-07-02 consolidation. Every api pod now carries the
  `crawler` role, which pins the LP-worker/Playwright pool used for creative
  generation. The role is load-bearing: if no cluster member carries it,
  landing-page creative generation breaks silently.
- **TimescaleDB, not vanilla Postgres.** `docker/init-db.sql` calls
  `create_hypertable` / `add_retention_policy` / `add_compression_policy`,
  which only exist with the `timescaledb` extension. Plain Postgres aborts at
  the first one.
- **Playwright** runs *inside* the API JVM. The image is built on
  `mcr.microsoft.com/playwright/java:v1.54.0-jammy` (browser build matched to
  the pinned `playwright` dep) and `CHROMIUM_NO_SANDBOX=true` lets Chromium
  start as root in the container.

## Prerequisites

- A Kubernetes cluster — Docker Desktop's built-in Kubernetes works, single-
  or multi-node (see [Multi-node cluster](#multi-node-cluster)).
- `kubectl` pointing at that cluster's context (e.g. `kubectl config use-context
  kind-promovolve` for standalone kind, or `docker-desktop` for Docker Desktop).
- A **Docker Hub account** (images are pushed there — see below).
- Enough RAM for the pod limits: 2×api at 2560Mi + db 2Gi + platform 256Mi
  ≈ **7.5 GiB** of limits. Give the Docker Desktop / kind VM headroom
  (~8+ GiB) or pods get OOM-killed, which mid-bootstrap stalls cluster
  formation (a new cluster needs both api pods as contact points).

### Multi-node cluster

The 2 api pods schedule fine on one node (anti-affinity is soft), but spread
across nodes when available — a node failure then costs one pod, not both.
Two ways to get multiple nodes:

- **Standalone kind** (reproducible, recommended for repeatable runs):
  ```sh
  kind create cluster --name promovolve --config k8s/kind-cluster.yaml
  kubectl config use-context kind-promovolve
  ```
  Standalone kind has no cloud LoadBalancer, so reach the services via
  `port-forward` (see the note in `k8s/kind-cluster.yaml`), not
  `localhost:<port>`.
- **Docker Desktop multi-node:** Settings → Kubernetes → enable the multi-node
  option and set the node count to **≥2**. Docker Desktop binds the
  LoadBalancer services to `localhost`, so `http://localhost:9090 / :8080`
  work as in "Open it" below.

<a id="single-node-fallback"></a>
### Single-node fallback

Anti-affinity is `preferredDuringSchedulingIgnoredDuringExecution` (soft), so
both api pods co-locate on a single node with no changes. To run a true
1-pod cluster instead, set `api-statefulset.yaml` `replicas: 1`, point
`config.file` at `/conf/application-single.conf`, and set
`PEKKO_BOOTSTRAP_REQUIRED_CONTACT_POINTS=1` (see the comments in
`api-statefulset.yaml`). For local single-node dev without k8s at all,
`scripts/run-dev.sh` is the simpler path.

**The public GKE deployment lives in `k8s-gke/`** — a kustomize overlay over
this base (GKE Ingress + managed certs, ARM spot node, CI-deployed). See its
README.

> **Images must be pushed to a registry; the cluster does NOT use your local
> daemon's images.** Docker Desktop's *multi-node* option is kind-based: the
> nodes run inside the DD VM and pull through an internal registry mirror, so
> host-built images give `ErrImageNeverPull`. We push to Docker Hub (private
> repos) and pull with an `imagePullSecret` (`regcred`, step 2). `kustomization.yaml`
> rewrites `promovolve/api` → `docker.io/hanishi/promovolve-api:dev` etc.
>
> *Standalone kind shortcut:* instead of Docker Hub you can sideload host-built
> images straight into the cluster — `kind load docker-image
> hanishi/promovolve-api:dev hanishi/promovolve-platform:dev --name promovolve`
> — which skips the push/pull and the `regcred` secret entirely.

## 1. Build and push the images

```sh
# from the repo root — tag for your Docker Hub namespace
docker build -f Dockerfile.api      -t hanishi/promovolve-api:dev      .
docker build -f platform/Dockerfile -t hanishi/promovolve-platform:dev platform
docker push hanishi/promovolve-api:dev
docker push hanishi/promovolve-platform:dev
```

The API build is heavy the first time (sbt downloads the world, then compiles).
The runtime image is large (~3.4 GB) because it bundles Chromium + a JRE.
After a code change, rebuild+push only the affected image and
`kubectl -n promovolve rollout restart` its workload (`:dev` is mutable +
`imagePullPolicy: Always`, so the restart re-pulls). The platform image is tiny
(~10 MB, distroless).

### ⚠️ Disk: prune the IN-NODE image cache, not just the host

The single biggest operational hazard on Docker Desktop + kind: the shared VM
disk fills, Postgres hits `FATAL: could not write … No space left on device`,
the `promovolve-db` pod crash-loops, and because it's a **headless** service
that takes every app pod down with it (`UnknownHostException: promovolve-db` →
cluster-wide CrashLoopBackOff, then a slow remember-entities replay storm to
recover). It has bitten us repeatedly.

**Why it happens — there are TWO separate image stores, and the obvious prune
only cleans one:**

```
Host Docker daemon ─► docker images / docker image prune   ← what you reach by default
       │
       └─ runs each kind node as a container, and INSIDE each node:
              containerd image store ─► crictl images / crictl rmi --prune   ← the real bloat
```

`:dev` is mutable, so every `docker push …:dev` + pod re-pull leaves the
*previous* image's layers cached in each node's **containerd** store. Those
never get cleaned automatically, and **`docker image prune` / `docker builder
prune` on the host never touch them** — over many pushes they fill the VM disk.

**The fix (non-destructive — keeps all PVC data; only evicts images no running
pod references):** prune the in-node cache across every node, ideally *before*
each heavy API rebuild:

```sh
for n in $(kubectl get nodes -o jsonpath='{.items[*].metadata.name}'); do
  docker exec "$n" crictl rmi --prune
done
docker builder prune -f          # host-side build cache, also worth clearing
```

This typically reclaims ~10 GB+. Check headroom with
`docker exec <node> df -h /` (e.g. `desktop-control-plane`). If the DB has
already crashed on a full disk, run the prune, then it recovers on the kubelet's
restart backoff (no need to delete the stateful DB pod). You do **not** need to
clear PVCs / `reset.sh` for this — the data volumes (5Gi DB + 2×2Gi DData) are
not the bloat; the node containerd caches are. The durable fix is to give Docker
Desktop a larger disk (Settings → Resources).

## 2. Provide secrets

```sh
cp k8s/secrets.env.example k8s/secrets.env
# edit k8s/secrets.env — set GEMINI_API_KEY (and R2_* if you use R2)
```

`k8s/secrets.env` is gitignored. `JDBC_PASSWORD` defaults to the dev value and
must match the DB password in `kustomization.yaml`.

### Image pull secret (private Docker Hub repos)

The images live in **private** Docker Hub repos, so the cluster needs
credentials to pull them. Create a Docker Hub **access token** (Account
Settings → Personal access tokens → Generate, read-only is enough), then:

```sh
kubectl create namespace promovolve --dry-run=client -o yaml | kubectl apply -f -
kubectl -n promovolve create secret docker-registry regcred \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=hanishi \
  --docker-password=<your-access-token>
```

The pod specs reference this `regcred` secret via `imagePullSecrets`. It's
created out-of-band (not part of kustomize) so the token never lands in a
tracked file.

## 3. Deploy

```sh
# The --load-restrictor flag is needed because the DB init ConfigMap is
# single-sourced from ../docker/init-db.sql (outside the kustomize root),
# which kustomize blocks by default. This keeps one copy of init-db.sql.
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/ | kubectl apply -f -
kubectl -n promovolve get pods -w
```

Prefer a one-word command? Run `alias pvk='kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/'`
then `pvk | kubectl apply -f -`.

First boot: the DB initializes (runs `init-db.sql`), then the API replays
persistence/DData — the API `startupProbe` allows up to ~5 min.

## 4. Open it

The two `LoadBalancer` services are bound to localhost by Docker Desktop —
**no `kubectl port-forward` needed**. Verified on the multi-node (kind) cluster:
DD maps `localhost:<port>` to the service even though the `EXTERNAL-IP` shown
(`172.23.0.x`) is VM-internal and not directly host-reachable.

- Dashboard: <http://localhost:9090>
- Ad server / API: <http://localhost:8080> (Swagger at `/docs`)

The dashboard redirects to a login page — register an account through the UI.

`CDN_BASE_URL` and `TRACKING_BASE_URL` are set to `http://localhost:8080`
because that's how a **browser** reaches the API. If you expose the cluster
differently (real ingress hostname), update them in `kustomization.yaml`.

## Operations

**Tail API logs**
```sh
kubectl -n promovolve logs -f statefulset/promovolve-api
```

**Reset runtime state — use `k8s/reset.sh`.** Runtime state lives in two PVCs
that must be wiped *together, in the right order*: the Postgres PVC
(`data-promovolve-db-0` — campaigns, creatives, sites, logins, durable_state,
journals) and the per-pod DData PVCs (`ddata-promovolve-api-*` — ServeIndex,
classifications, approvals, floor, **and the remember-entities list**). The
gotcha: if you wipe Postgres but leave DData (or leave the api running so it
re-fills remember-entities), cluster sharding re-activates the old entities and
they re-persist as empty "ghost" campaigns. The script does it safely — scale
writers to 0 → delete PVC(s) → bring up db, then api, then restart platform:

```sh
k8s/reset.sh                # FULL reset (Postgres + DData); interactive confirm
k8s/reset.sh --ddata-only   # DData only — keeps campaigns/creatives/sites/logins
k8s/reset.sh --yes          # skip the confirmation prompt
k8s/reset.sh --ns myns      # override namespace (default: promovolve)
```

> After a reset, **clear your browser before reopening the UI**: log out, close
> all promovolve tabs (especially any `?campaignId=…` URL), use a fresh/private
> window, then register again. A stale JWT or open tab re-activates old entities
> and they reappear as ghost campaigns. (`reset.sh` prints this reminder too.)

For a `--fresh`-style truncate **without** wiping the volume (keeps the PVC,
just clears the tables), exec `psql` in the DB pod and run the TRUNCATE/DROP
block from `scripts/run-dev.sh` — then run `k8s/reset.sh --ddata-only` so DData
doesn't re-seed the truncated entities.

**Tear everything down**
```sh
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/ | kubectl delete -f -
kubectl -n promovolve delete pvc --all   # PVCs are not removed by delete
```

## What's configured where

- Non-secret env → `configMapGenerator` (`api-config`, `platform-config`) in `kustomization.yaml`
- Secrets → `secretGenerator` (`db-secret`, `api-secret` from `secrets.env`, `platform-secret`)
- `init-db.sql` → `configMapGenerator` reads `../docker/init-db.sql` directly (no copy)
