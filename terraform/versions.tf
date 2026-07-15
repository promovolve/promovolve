terraform {
  # optional() defaults and modern provider blocks; nothing newer needed.
  required_version = ">= 1.7"

  required_providers {
    google = {
      source = "hashicorp/google"
      # 6.x is the first series where hyperdisk boot disks and the current
      # WIF provider schema are both stable.
      version = "~> 6.0"
    }
  }
}
