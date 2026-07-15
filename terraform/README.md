# Terraform — the GCP substrate

Replicates PromoVolve's GCP footprint from nothing: project services, the
global static IP, the zonal GKE cluster + ARM Spot node pool, Workload
Identity Federation for GitHub Actions CI, the DB-backup bucket/SA, and an
optional monthly budget. Derived from the live deployment (project
`promovolve`, `asia-northeast1-b`) on **2026-07-15** — defaults are the
live values.

This module ends where the Kubernetes API begins. It replaces the
GCP-provisioning half of `k8s-gke/setup.sh` (steps 1–3) for people who
prefer Terraform; `setup.sh --deploy-only` remains the deploy path either
way.

## What Terraform deliberately does NOT manage

- **In-cluster manifests** — namespace, StatefulSets, Ingress,
  ManagedCertificate, BackendConfig/FrontendConfig, StorageClass, the
  backup CronJob + its KSA: all owned by `k8s/` + `k8s-gke/` (kustomize),
  applied by `k8s-gke/setup.sh`.
- **Secrets** — `k8s/secrets.env`, `k8s/platform-secrets.env`, and the
  Docker Hub `regcred` stay on the operator's machine; setup.sh mints them.
- **Docker Hub images** — built on the laptop / CI, private repos under
  `hanishi/promovolve-*`. Fork = your own repos + update
  `k8s/kustomization.yaml` and `deploy.yml`'s `env:` image names.
- **Cloudflare** — DNS for the hostnames and the R2 CDN bucket
  (`CDN_BASE_URL`) live in Cloudflare, outside GCP.
- **The GCP project itself** — project IDs are global and billing linkage
  is interactive; create it first (below).

## Prerequisites

- Terraform >= 1.7, `gcloud` authenticated as a user who can administer
  the project.
- A GCP project with billing linked:

  ```sh
  gcloud projects create <PROJECT_ID> --name="PromoVolve"
  gcloud billing projects link <PROJECT_ID> --billing-account=<XXXXXX-XXXXXX-XXXXXX>
  ```

- Quota for one `c4a-standard-4` Spot instance in the zone (c4a exists in
  `asia-northeast1-b` but not every zone; changing regions means
  re-checking ARM availability).

## Apply

```sh
cd terraform
terraform init
terraform apply \
  -var project_id=<PROJECT_ID> \
  -var github_repo=<owner>/<repo>          # your fork; gates WIF
# optional budget (needs roles/billing.admin on the account):
#   -var billing_account=XXXXXX-XXXXXX-XXXXXX
```

Everything else defaults to the live values: `asia-northeast1-b`, 1×
`c4a-standard-4` Spot, 50GB hyperdisk-balanced, REGULAR channel,
SYSTEM-only logging (the two JVMs would blow the 50GB/mo free logging
tier), Workload Identity enabled.

State is local by default (`-backend=false` works for a look; add your own
backend block if a team shares this). Never point it at the live
`promovolve` project — this module is for fresh provisioning, not import.

## After apply (the existing runbook takes over)

1. **DNS** — create A records for each hostname → `ingress_ip_address`
   (output `dns_records` prints the exact pairs). At Cloudflare they must
   be **DNS-only / grey cloud**: proxying breaks Google managed-cert
   issuance.
2. **GitHub Actions** — `.github/workflows/deploy.yml` hardcodes the auth
   values; on a fork, replace them with the outputs:
   - `workload_identity_provider:` ← output `workload_identity_provider`
     (the project **number** in the path changes per project)
   - `service_account:` ← output `deployer_service_account`
   - `cluster_name` / `location` / `project_id` in the
     get-gke-credentials step ← outputs `cluster_name`,
     `cluster_location`, your project id
   - repo secrets `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` (push scope)
     for the image pushes.
3. **Deploy the app** — fill in `k8s/secrets.env` +
   `k8s/platform-secrets.env`, then:

   ```sh
   k8s-gke/setup.sh --deploy-only
   ```

   (Full `setup.sh` also works — its provisioning steps detect the
   Terraform-created resources and skip.)
4. **Wait for the managed cert** (15–60 min after DNS resolves), then
   register the first account at `https://<dashboard-host>/setup`.
5. **Push to main** — CI builds arm64 images and rolls the cluster by
   digest.

## Notes

- The static IP name `promovolve-ingress` and the WIF ids
  `github`/`github-oidc`/`github-deployer` are referenced by name from
  `k8s-gke/ingress.yaml` and `deploy.yml` — keep them stable.
- Deleted WIF pools/providers stay soft-deleted for 30 days and block
  re-creation under the same id; `terraform destroy` + immediate re-apply
  of the pool will fail until restored or expired.
- GKE auto-taints the ARM pool (`kubernetes.io/arch=arm64:NoSchedule`);
  the taint is intentionally not declared here to avoid permanent diffs.
- The budget is alerts-only (50/90/100% of ¥15,000 by default) — GCP
  budgets never cap spend.
