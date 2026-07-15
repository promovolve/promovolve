# Local dev on Docker Desktop Kubernetes

Run the full PromoVolve stack (Postgres/TimescaleDB, Pekko api cluster,
singleton, Go platform) on **Docker Desktop's built-in Kubernetes** from images
you build locally — the fast inner loop, no Docker Hub push/pull.

This overlay layers over the shared [`k8s/`](../k8s) base: it swaps the image
tags to `:local` and forces `imagePullPolicy: IfNotPresent`. Everything else
(manifests, secrets, config) is inherited from the base.

## TL;DR

```bash
# one-time: Docker Desktop → Settings → Kubernetes → Enable Kubernetes
# one-time: cp k8s/secrets.env.example k8s/secrets.env  (fill in the keys)

k8s-local/up.sh --build     # build :local images, side-load, apply, wait
open http://localhost:9090  # dashboard (redirects to /setup on a fresh DB)
```

After the first bring-up, iterate with `k8s-local/up.sh --build` on each code
change. Full teardown: `kubectl --context docker-desktop delete ns promovolve`.

## Why the side-load (the one non-obvious bit)

Docker Desktop's Kubernetes is **kind-based**: the node runs as the container
`desktop-control-plane` with its *own* containerd. A `docker build` on your host
lands in the host's image store, which the node cannot see — so `:local` images
would `ImagePullBackOff`. Enabling the "containerd image store" in Docker Desktop
settings does **not** fix this (the node's containerd is still separate).

The reliable fix (what `up.sh` does, the `kind load docker-image` mechanism) is
to import the image straight into the node's containerd:

```bash
docker save localhost/promovolve-api:local \
  | docker exec -i desktop-control-plane ctr -n k8s.io images import -
```

The `:local` images use a neutral `localhost/` name on purpose — **not** a
Docker Hub namespace. Side-load means nothing is ever pushed or pulled, so the
tag belongs to no account (not the maintainer's, not yours); `localhost/` just
makes "local-only" explicit. (If you instead want to run on a *non-local*
cluster, use the [`k8s/`](../k8s) base and point its images at your own repo.)

The `k8s.io` namespace is the one kubelet's CRI reads, so with `IfNotPresent`
the pod uses it directly. `up.sh` skips the side-load for an image already in
the node unless you pass `--build` (rebuilt content must be re-imported under
the same tag; `IfNotPresent` won't restart pods on its own, so `--build` also
`rollout restart`s the workloads).

## Manual steps (what `up.sh` automates)

```bash
NODE=desktop-control-plane   # = kubectl --context docker-desktop get nodes -o name

# 1. build
docker build -f Dockerfile.api      -t localhost/promovolve-api:local      .
docker build -f platform/Dockerfile -t localhost/promovolve-platform:local platform

# 2. side-load into the node's containerd
docker save localhost/promovolve-api:local      | docker exec -i $NODE ctr -n k8s.io images import -
docker save localhost/promovolve-platform:local | docker exec -i $NODE ctr -n k8s.io images import -

# 3. apply the overlay (LoadRestrictionsNone: db-initdb single-sources ../docker/init-db.sql)
kubectl --context docker-desktop create namespace promovolve --dry-run=client -o yaml | kubectl --context docker-desktop apply -f -
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s-local | kubectl --context docker-desktop apply -f -

# 4. after a REBUILD, force pods onto the new same-tag content
kubectl --context docker-desktop -n promovolve rollout restart statefulset/promovolve-api deployment/promovolve-platform
```

## Access

Docker Desktop maps `LoadBalancer` services to `localhost`, so no port-forward
is needed:

| service   | URL |
|-----------|-----|
| dashboard | http://localhost:9090 |
| api/docs  | http://localhost:8080/docs |

Direct Postgres access: `kubectl --context docker-desktop -n promovolve
port-forward pod/promovolve-db-0 5433:5432` (5432 is usually taken by a host
Postgres), then `psql postgres://promovolve@localhost:5433/promovolve`.

## First run / login

A fresh cluster comes up with an empty DB, so `/` redirects to `/setup` to
create the first account. **Passkeys won't register against `localhost`** — the
base config sets `RP_ID=promovolve.programmer.llc`, which must match the browser
origin. For local login use the `DEV_AUTH` hatch or mint a session:
`kubectl --context docker-desktop -n promovolve exec deploy/promovolve-platform
-- /server mint-session --email you@example.com`.

## Caveats (shared prod resources)

The overlay only changes image tags/pull policy — it inherits the base config,
which points at **production R2**:

- `CDN_BASE_URL` / `R2_*` → the live R2 bucket. Rendered banners and LP-captured
  images your local stack produces are written there (content-addressed, so
  low blast radius, but not isolated). Override `CDN_BASE_URL` + `R2_*` with a
  local `configMapGenerator`/`secretGenerator` patch here if you want a separate
  bucket (or a MinIO).
- `GEMINI_API_KEY` → real Gemini calls (creative generation, classification).
- `RP_ID` → prod host (see login note above).

The **database is a separate local pod** — local traffic never touches the prod
DB.

## Housekeeping

- **Reconcile / restart:** `k8s-local/up.sh` (no `--build`) re-applies without
  rebuilding.
- **Teardown (keeps images, wipes data):** `kubectl --context docker-desktop
  delete ns promovolve` — removes the namespace, PVCs, and all local data.
- **Free node disk:** side-loaded images accumulate in the node's containerd;
  prune with `docker exec desktop-control-plane crictl rmi --prune`.
