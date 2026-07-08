# GKE Autopilot demo overlay

The cheapest way to stand PromoVolve up publicly. Versus the base `k8s/`:

- **One L7 load balancer** instead of two L4 LBs — both `LoadBalancer`
  Services become `ClusterIP` behind a single GCE `Ingress` (host-routed:
  `promovolve.programmer.llc` → dashboard, `ads.programmer.llc` → ad-serve/tracking).
- **Spot Pods** everywhere, requests trimmed to Autopilot's floor.
- **Single-node api cluster** (`replicas: 1`) — the documented single-node
  fallback, halving the most expensive tier.

Rough bill (us-central1, left running 24/7): **~$25–30/mo** compute + one L7 LB
(~$18) + tiny PD/egress. Tear the cluster down between demos and you pay
pennies. The **LLM key is billed separately** by the provider — the app won't
boot without it.

## Prerequisites

1. **Create an Autopilot cluster** (its own control plane; first zonal/Autopilot
   cluster's management fee is covered by the free-tier credit):
   ```sh
   gcloud container clusters create-auto promovolve-demo \
     --region us-central1 --project <PROJECT>
   gcloud container clusters get-credentials promovolve-demo --region us-central1
   ```

2. **Namespace + image pull secret** (private Docker Hub repos under `hanishi`):
   ```sh
   kubectl create namespace promovolve
   kubectl -n promovolve create secret docker-registry regcred \
     --docker-server=https://index.docker.io/v1/ \
     --docker-username=<dockerhub-user> --docker-password=<dockerhub-PAT>
   ```

3. **Secrets** — same as the base. Fill these in (they are git-ignored):
   ```sh
   cp k8s/secrets.env.example          k8s/secrets.env            # JDBC_PASSWORD, GEMINI_API_KEY, R2_*
   cp k8s/platform-secrets.env.example k8s/platform-secrets.env   # JWT_SECRET
   ```

4. **Static IP + DNS**:
   ```sh
   gcloud compute addresses create promovolve-ip --global
   gcloud compute addresses describe promovolve-ip --global --format='value(address)'
   ```
   Point **A records** for `promovolve.programmer.llc` and `ads.programmer.llc`
   at that IP. (Or drop the `global-static-ip-name` annotation in `ingress.yaml`
   and read the assigned IP off the Ingress after it provisions.)

## Deploy

```sh
kubectl apply -k k8s/overlays/gke-autopilot
```

Then wait for the LB + managed cert (~15–60 min for the cert; it stays
`Provisioning` until DNS resolves to the LB IP):

```sh
kubectl -n promovolve get ingress promovolve -w
kubectl -n promovolve get managedcertificate promovolve-cert -w   # want Active
```

Once the cert is `Active`, open `https://promovolve.programmer.llc`.

## Cost knobs

- **Tear down between demos** (the real minimum): `gcloud container clusters
  delete promovolve-demo --region us-central1`. Recreate when you next demo.
- **DB stability over cost**: delete the `nodeSelector`/`tolerations` block in
  `patch-db.yaml` so Postgres runs on an on-demand node (immune to preemption).

## Restoring the base 2-node api cluster

This overlay pins `replicas: 1` + `application-single.conf`. To go back to the
base 2-pod cluster, drop `patch-api.yaml` from `kustomization.yaml` (the base's
`application-app.conf` + `CONTACT_POINTS=2` take over) — but note the base's
hard anti-affinity wants ≥2 schedulable nodes for the api pods.

## Why the health checks look unusual

GCE L7 backends need an HTTP `200`. Neither serving port gives one at `/`:

- **api `:8080`** exposes only `/v1/...` — a `GET /` 404s. So
  `api-backendconfig` probes the **management** port `:8558/ready` (200 once the
  pod is a cluster member).
- **platform `:9090/`** 302-redirects to `/login`. So `platform-backendconfig`
  probes `:9090/health` (200 `{"status":"ok"}`, auth-exempt).
