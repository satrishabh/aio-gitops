locals {
  apis = toset(["compute.googleapis.com", "container.googleapis.com", "sqladmin.googleapis.com",
    "servicenetworking.googleapis.com", "artifactregistry.googleapis.com", "secretmanager.googleapis.com",
  "iam.googleapis.com", "iamcredentials.googleapis.com", "cloudresourcemanager.googleapis.com"])
}
resource "google_project_service" "api" {
  for_each           = local.apis
  service            = each.key
  disable_on_destroy = false
}
resource "google_compute_network" "main" {
  name                    = var.name
  auto_create_subnetworks = false
  depends_on              = [google_project_service.api]
}
resource "google_compute_subnetwork" "main" {
  name                     = var.name
  region                   = var.region
  network                  = google_compute_network.main.id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.services_cidr
  }
}
resource "google_compute_router" "main" {
  name    = var.name
  region  = var.region
  network = google_compute_network.main.id
}
resource "google_compute_router_nat" "main" {
  name                               = var.name
  router                             = google_compute_router.main.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}
resource "google_compute_global_address" "sql" {
  name          = "${var.name}-sql"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  address       = var.sql_range_address
  prefix_length = 16
  network       = google_compute_network.main.id
}
resource "google_service_networking_connection" "sql" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql.name]
}
resource "google_compute_address" "ingress" {
  name         = "${var.name}-ingress"
  region       = var.region
  address_type = "EXTERNAL"
  depends_on   = [google_project_service.api]
}
