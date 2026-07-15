# The exact values .github/workflows/deploy.yml and the post-apply runbook
# need — see README.md for where each one goes.

output "cluster_name" {
  description = "GKE cluster name (deploy.yml: get-gke-credentials cluster_name)."
  value       = google_container_cluster.promovolve.name
}

output "cluster_location" {
  description = "Cluster zone (deploy.yml: get-gke-credentials location)."
  value       = google_container_cluster.promovolve.location
}

output "kubectl_context" {
  description = "The context name gcloud get-credentials creates; k8s-gke/setup.sh pins every kubectl call to it."
  value       = "gke_${var.project_id}_${var.zone}_${google_container_cluster.promovolve.name}"
}

output "ingress_ip_name" {
  description = "Static IP resource name — must match kubernetes.io/ingress.global-static-ip-name in k8s-gke/ingress.yaml."
  value       = google_compute_global_address.ingress.name
}

output "ingress_ip_address" {
  description = "The address to point DNS at."
  value       = google_compute_global_address.ingress.address
}

output "dns_records" {
  description = "A records to create (Cloudflare: DNS-ONLY / grey cloud, or managed-cert issuance fails)."
  value       = { for d in var.domain_names : d => google_compute_global_address.ingress.address }
}

output "workload_identity_provider" {
  description = "Full provider resource name for deploy.yml's google-github-actions/auth `workload_identity_provider` (projects/<number>/locations/global/workloadIdentityPools/github/providers/github-oidc)."
  value       = google_iam_workload_identity_pool_provider.github_oidc.name
}

output "deployer_service_account" {
  description = "SA email for deploy.yml's google-github-actions/auth `service_account`."
  value       = google_service_account.github_deployer.email
}

output "backup_bucket" {
  description = "GCS bucket receiving the nightly Postgres dumps."
  value       = google_storage_bucket.db_backups.name
}

output "backup_writer_service_account" {
  description = "SA email the db-backup KSA must be annotated with (iam.gke.io/gcp-service-account in k8s-gke/backup-cronjob.yaml)."
  value       = google_service_account.db_backup_writer.email
}
