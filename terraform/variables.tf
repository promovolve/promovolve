# Defaults mirror the live deployment (GCP project `promovolve`, captured
# 2026-07-15). Only project_id has no default: project IDs are globally
# unique and `promovolve` is taken.

variable "project_id" {
  description = "Existing GCP project to provision into (create it and link billing first — see README). Live value: promovolve."
  type        = string
}

variable "region" {
  description = "Region for regional resources (backup bucket)."
  type        = string
  default     = "asia-northeast1"
}

variable "zone" {
  description = "Cluster zone. Zonal on purpose: the GKE free-tier credit covers the management fee for ONE zonal cluster per billing account. asia-northeast1-b is the only Tokyo zone with c4a machines."
  type        = string
  default     = "asia-northeast1-b"
}

variable "cluster_name" {
  description = "GKE cluster name. deploy.yml and the kubectl context (gke_<project>_<zone>_<cluster>) both reference it."
  type        = string
  default     = "promovolve"
}

variable "machine_type" {
  description = "Node machine type. c4a (ARM/Axion) because images are built/pushed single-arch arm64 — x86 nodes die with `exec format error`. c4a attaches hyperdisk ONLY."
  type        = string
  default     = "c4a-standard-4"
}

variable "spot" {
  description = "Run nodes as Spot (~60-70% off). Preemption = a few minutes of Pekko self-heal; acceptable for this deployment."
  type        = bool
  default     = true
}

variable "node_count" {
  description = "Fixed node count (the live cluster runs 1 node, no autoscaling)."
  type        = number
  default     = 1
}

variable "disk_type" {
  description = "Node boot disk type. Must stay a hyperdisk-* type on c4a (pd-* cannot attach)."
  type        = string
  default     = "hyperdisk-balanced"
}

variable "disk_size_gb" {
  description = "Node boot disk size in GB."
  type        = number
  default     = 50
}

variable "deletion_protection" {
  description = "Refuse `terraform destroy` of the cluster. Flip to false only when you really mean to tear it down."
  type        = bool
  default     = true
}

variable "github_repo" {
  description = "GitHub repository (owner/name) allowed to impersonate the deployer SA via Workload Identity Federation."
  type        = string
  default     = "promovolve/promovolve"
}

variable "domain_names" {
  description = "Public hostnames served by the Ingress (documentation + dns_records output; the ManagedCertificate that actually uses them lives in k8s-gke/, not here)."
  type        = list(string)
  default     = ["promovolve.programmer.llc", "ads.programmer.llc"]
}

variable "backup_bucket_name" {
  description = "GCS bucket for nightly Postgres dumps. Empty = <project_id>-db-backups. Bucket names are global; override on collision."
  type        = string
  default     = ""
}

variable "backup_namespace" {
  description = "Kubernetes namespace of the db-backup KSA (k8s-gke/backup-cronjob.yaml)."
  type        = string
  default     = "promovolve"
}

variable "backup_ksa" {
  description = "Kubernetes ServiceAccount name that impersonates the backup writer SA."
  type        = string
  default     = "db-backup"
}

variable "billing_account" {
  description = "Billing account ID (XXXXXX-XXXXXX-XXXXXX) to attach the monthly budget to. Empty = no budget resource (creating budgets needs roles/billing.admin on the account, which replicators often lack)."
  type        = string
  default     = ""
}

variable "budget_amount_jpy" {
  description = "Monthly budget amount in JPY (alerts only, never a hard cap). Live: ¥15,000 — the deployment runs ~¥10-12k/mo with the free-tier credit."
  type        = number
  default     = 15000
}
