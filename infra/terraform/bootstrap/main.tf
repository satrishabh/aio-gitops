terraform {
  required_version = ">= 1.9, < 2.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "7.13.0"
    }
  }
}

provider "google" {
  project = var.project_id
}

variable "project_id" {
  type = string
}
variable "bucket_name" {
  type = string
}
variable "region" {
  type    = string
  default = "asia-south1"
}

resource "google_storage_bucket" "state" {
  name                        = var.bucket_name
  project                     = var.project_id
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  versioning {
    enabled = true
  }
  lifecycle {
    prevent_destroy = true
  }
}

output "bucket_name" {
  value = google_storage_bucket.state.name
}
