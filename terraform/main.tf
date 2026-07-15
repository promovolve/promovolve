# PromoVolve GCP substrate — everything BELOW the Kubernetes API.
#
# Derived from the live deployment (project `promovolve`) on 2026-07-15.
# In-cluster resources (namespace, workloads, Ingress, ManagedCertificate,
# BackendConfig, regcred, StorageClass) are deliberately NOT here — they are
# owned by k8s/ + k8s-gke/ and applied via k8s-gke/setup.sh. This module is
# the fresh-provision replacement for setup.sh steps 1-3 (project services,
# static IP, cluster) plus the pieces setup.sh never covered: the GitHub
# Actions WIF plumbing and the DB-backup bucket/SA.

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

data "google_project" "this" {
  project_id = var.project_id
}

locals {
  backup_bucket = var.backup_bucket_name != "" ? var.backup_bucket_name : "${var.project_id}-db-backups"

  # Curated from the live project's enabled services — only what this stack
  # actually exercises (GKE pulls in compute; WIF needs iam/iamcredentials/
  # sts; budgets is enabled unconditionally so the optional budget resource
  # doesn't fail on first use).
  services = [
    "cloudresourcemanager.googleapis.com",
    "compute.googleapis.com",
    "container.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "sts.googleapis.com",
    "storage.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "billingbudgets.googleapis.com",
  ]
}

# --- project services ---------------------------------------------------------

resource "google_project_service" "services" {
  for_each = toset(local.services)
  service  = each.value

  # Disabling APIs on destroy can wedge shared projects; leave them on.
  disable_on_destroy = false
}

# --- static IP (the Ingress frontend) -------------------------------------------

# Global because the GKE Ingress provisions a GLOBAL HTTPS LB (one LB, host
# routing for both hostnames). k8s-gke/ingress.yaml references it by NAME
# (kubernetes.io/ingress.global-static-ip-name: promovolve-ingress) — keep
# the name stable.
resource "google_compute_global_address" "ingress" {
  name       = "promovolve-ingress"
  depends_on = [google_project_service.services]
}

# --- GKE cluster ----------------------------------------------------------------

resource "google_container_cluster" "promovolve" {
  name     = var.cluster_name
  location = var.zone # zonal: the free-tier credit only covers zonal clusters

  # Node config lives in the separate pool resource below; GKE requires a
  # throwaway default pool at create time.
  remove_default_node_pool = true
  initial_node_count       = 1

  network    = "default"
  subnetwork = "default"

  # VPC-native; empty block lets GKE pick the secondary ranges (matches how
  # `gcloud container clusters create` provisioned the live cluster).
  ip_allocation_policy {}

  release_channel {
    channel = "REGULAR"
  }

  # Keyless auth for in-cluster workloads (the backup CronJob impersonates
  # db-backup-writer through this pool) — no SA keys anywhere.
  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  # SYSTEM only: the two JVMs are chatty enough to blow through the 50GB/mo
  # free Cloud Logging tier. `kubectl logs` is unaffected (reads kubelet).
  logging_config {
    enable_components = ["SYSTEM_COMPONENTS"]
  }

  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS"]
    managed_prometheus {
      enabled = true
    }
  }

  addons_config {
    gce_persistent_disk_csi_driver_config {
      enabled = true # provisions the hyperdisk PVCs (StorageClass in k8s-gke/)
    }
    dns_cache_config {
      enabled = true
    }
  }

  deletion_protection = var.deletion_protection

  depends_on = [google_project_service.services]
}

resource "google_container_node_pool" "default" {
  name     = "default-pool"
  cluster  = google_container_cluster.promovolve.name
  location = var.zone

  # Fixed size, no autoscaler: the whole app fits one c4a-standard-4 and the
  # budget is the binding constraint, not load.
  node_count = var.node_count

  node_config {
    machine_type = var.machine_type
    spot         = var.spot
    disk_type    = var.disk_type # c4a attaches hyperdisk ONLY — pd-* fails
    disk_size_gb = var.disk_size_gb
    image_type   = "COS_CONTAINERD"

    # GKE auto-taints ARM pools with kubernetes.io/arch=arm64:NoSchedule;
    # declaring it here would just fight the API with perma-diffs. The
    # k8s manifests carry the matching toleration.

    # Pods resolve their GCP identity via the GKE metadata server (required
    # for Workload Identity — the backup CronJob depends on it).
    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    # The stock GKE node scopes; access is governed by IAM on the node SA,
    # not by widening these.
    oauth_scopes = [
      "https://www.googleapis.com/auth/devstorage.read_only",
      "https://www.googleapis.com/auth/logging.write",
      "https://www.googleapis.com/auth/monitoring",
      "https://www.googleapis.com/auth/service.management.readonly",
      "https://www.googleapis.com/auth/servicecontrol",
      "https://www.googleapis.com/auth/trace.append",
    ]
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  upgrade_settings {
    max_surge       = 1
    max_unavailable = 0
  }
}

# --- Workload Identity Federation for GitHub Actions CI -------------------------
#
# deploy.yml authenticates with google-github-actions/auth@v2 using the
# provider path + SA email (see outputs). No key material ever reaches
# GitHub. NOTE: deleted pools/providers linger soft-deleted for 30 days and
# block re-creation under the same ID.

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"
  depends_on                = [google_project_service.services]
}

resource "google_iam_workload_identity_pool_provider" "github_oidc" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-oidc"
  display_name                       = "GitHub OIDC"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  # Hard gate at the provider: tokens from any other repo are rejected
  # before IAM is even consulted.
  attribute_condition = "assertion.repository=='${var.github_repo}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "github_deployer" {
  account_id   = "github-deployer"
  display_name = "GitHub Actions deployer"
}

# CI only moves images (`kubectl set image` + rollout status) — never
# renders manifests or touches infra. container.developer is exactly that:
# cluster credentials + in-cluster edit, no GCP mutations.
resource "google_project_iam_member" "github_deployer_container" {
  project = var.project_id
  role    = "roles/container.developer"
  member  = "serviceAccount:${google_service_account.github_deployer.email}"
}

# Let workflows of the repo (and only the repo, via attribute.repository)
# impersonate the deployer SA.
resource "google_service_account_iam_member" "github_deployer_wif" {
  service_account_id = google_service_account.github_deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repo}"
}

# --- DB backup bucket + writer SA ------------------------------------------------
#
# Accounts, passkeys, orgs, and the billing ledger all live on ONE zonal
# hyperdisk; the nightly dump to this bucket (k8s-gke/backup-cronjob.yaml)
# is the only recovery path from a disk loss or a bad delete.

resource "google_storage_bucket" "db_backups" {
  name     = local.backup_bucket
  location = upper(var.region)

  uniform_bucket_level_access = true

  # Nightly dumps; 30 days of history is plenty and keeps storage ~free.
  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = 30
    }
  }

  depends_on = [google_project_service.services]
}

resource "google_service_account" "db_backup_writer" {
  account_id   = "db-backup-writer"
  display_name = "DB backup writer (GKE WI)"
}

# objectAdmin on THIS bucket only (not project-wide storage roles): the
# uploader overwrites/retries, so create-only is not enough.
resource "google_storage_bucket_iam_member" "db_backup_writer_object_admin" {
  bucket = google_storage_bucket.db_backups.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.db_backup_writer.email}"
}

# Bind the in-cluster KSA (namespace/name from k8s-gke/backup-cronjob.yaml)
# to the GCP SA through the cluster's workload pool — keyless.
resource "google_service_account_iam_member" "db_backup_writer_wi" {
  service_account_id = google_service_account.db_backup_writer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${var.backup_namespace}/${var.backup_ksa}]"
}

# --- monthly budget (optional) ----------------------------------------------------
#
# Alerts only — a budget never stops spend. Gated on billing_account because
# creating budgets requires roles/billing.admin on the ACCOUNT, which many
# replicators won't have; the live thresholds are 50/90/100% of ¥15,000.

resource "google_billing_budget" "monthly" {
  count = var.billing_account != "" ? 1 : 0

  billing_account = var.billing_account
  display_name    = "promovolve monthly"

  budget_filter {
    projects               = ["projects/${data.google_project.this.number}"]
    calendar_period        = "MONTH"
    credit_types_treatment = "INCLUDE_ALL_CREDITS"
  }

  amount {
    specified_amount {
      currency_code = "JPY"
      units         = tostring(var.budget_amount_jpy)
    }
  }

  threshold_rules {
    threshold_percent = 0.5
    spend_basis       = "CURRENT_SPEND"
  }
  threshold_rules {
    threshold_percent = 0.9
    spend_basis       = "CURRENT_SPEND"
  }
  threshold_rules {
    threshold_percent = 1.0
    spend_basis       = "CURRENT_SPEND"
  }

  depends_on = [google_project_service.services]
}
