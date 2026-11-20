resource "google_service_account" "nodes" {
  account_id   = "${var.name}-nodes"
  display_name = "${var.name} GKE nodes"
  depends_on   = [google_project_service.api]
}
resource "google_project_iam_member" "node_role" {
  for_each = toset(["roles/container.defaultNodeServiceAccount"])
  project  = var.project_id
  role     = each.key
  member   = "serviceAccount:${google_service_account.nodes.email}"
}
resource "google_artifact_registry_repository" "images" {
  repository_id = var.name
  location      = var.region
  format        = "DOCKER"
  docker_config {
    immutable_tags = true
  }
  depends_on = [google_project_service.api]
}
resource "google_artifact_registry_repository_iam_member" "node_pull" {
  repository = google_artifact_registry_repository.images.name
  location   = var.region
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.nodes.email}"
}
resource "google_container_cluster" "main" {
  name                     = var.name
  location                 = var.region
  node_locations           = length(var.node_locations) == 0 ? ["${var.region}-a"] : var.node_locations
  network                  = google_compute_network.main.id
  subnetwork               = google_compute_subnetwork.main.id
  networking_mode          = "VPC_NATIVE"
  remove_default_node_pool = true
  initial_node_count       = 1
  deletion_protection      = true
  enable_shielded_nodes    = true
  # The temporary default pool also uses the dedicated identity before removal.
  node_config {
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
  }
  release_channel {
    channel = "REGULAR"
  }
  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }
  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
    master_ipv4_cidr_block  = var.master_cidr
  }
  master_authorized_networks_config {
    dynamic "cidr_blocks" {
      for_each = toset(var.authorized_cidrs)
      content {
        cidr_block   = cidr_blocks.value
        display_name = "trusted-runner"
      }
    }
  }
  master_auth {
    client_certificate_config {
      issue_client_certificate = false
    }
  }
  depends_on = [google_project_service.api, google_project_iam_member.node_role]
}
resource "google_container_node_pool" "main" {
  name               = "application-and-ci"
  location           = var.region
  cluster            = google_container_cluster.main.name
  initial_node_count = 1
  autoscaling {
    total_min_node_count = var.min_nodes
    total_max_node_count = var.max_nodes
  }
  management {
    auto_repair  = true
    auto_upgrade = true
  }
  node_config {
    machine_type    = var.machine_type
    disk_size_gb    = 80
    disk_type       = "pd-balanced"
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    metadata = {
      disable-legacy-endpoints = "true"
    }
    workload_metadata_config {
      mode = "GKE_METADATA"
    }
    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }
  depends_on = [google_project_iam_member.node_role, google_artifact_registry_repository_iam_member.node_pull, google_compute_router_nat.main]
}
