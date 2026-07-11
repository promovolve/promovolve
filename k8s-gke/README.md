# GKE deployment — the public PromoVolve cluster

This is **the** deployment. PromoVolve is not operated as a business, so
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

## Redeploys

Same flow as dev: push images, pin the new digests in
`k8s/kustomization.yaml` (the base — the overlay inherits them), then:

```sh
k8s-gke/setup.sh --deploy-only
```

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

Push to `main` → the changed image(s) build natively on arm64 runners →
Docker Hub (`main-<sha>` tags; `:dev` stays laptop-owned) → `kubectl set
image` by digest on the GKE workloads. CI never renders the kustomize
overlay (secrets stay off GitHub), so **a manual `setup.sh --deploy-only`
rolls GKE back to the digests pinned in k8s/kustomization.yaml** — refresh
the pins after CI deploys if you intend to deploy manually.

Auth: GCP via Workload Identity Federation (pool `github`, provider
`github-oidc`, SA `github-deployer@promovolve.iam.gserviceaccount.com`,
restricted to this repo — no keys). Docker Hub via DOCKERHUB_USERNAME /
DOCKERHUB_TOKEN repo secrets.
