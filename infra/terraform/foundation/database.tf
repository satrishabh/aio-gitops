resource "google_sql_database_instance" "main" {
  name                = "${var.name}-postgres"
  database_version    = "POSTGRES_18"
  region              = var.region
  deletion_protection = true
  settings {
    edition                     = "ENTERPRISE"
    tier                        = var.sql_tier
    availability_type           = var.sql_availability
    disk_type                   = "PD_SSD"
    disk_size                   = 20
    disk_autoresize             = true
    deletion_protection_enabled = true
    database_flags {
      name  = "cloudsql.iam_authentication"
      value = "on"
    }
    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.main.id
      ssl_mode        = "ENCRYPTED_ONLY"
    }
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "03:00"
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = 7
      }
    }
  }
  lifecycle {
    prevent_destroy = true
  }
  depends_on = [google_service_networking_connection.sql]
}
resource "google_sql_database" "database" {
  for_each = toset([for consumer in values(var.database_consumers) : consumer.database])
  name     = each.key
  instance = google_sql_database_instance.main.name
  lifecycle {
    prevent_destroy = true
  }
}
resource "google_service_account" "database" {
  for_each     = var.database_consumers
  account_id   = "${var.name}-${each.key}"
  display_name = "${each.key}: ${each.value.database} database identity"
  depends_on   = [google_project_service.api]
}
resource "google_project_iam_member" "sql_client" {
  for_each = var.database_consumers
  project  = var.project_id
  role     = "roles/cloudsql.client"
  member   = "serviceAccount:${google_service_account.database[each.key].email}"
}
resource "google_project_iam_member" "sql_login" {
  for_each = var.database_consumers
  project  = var.project_id
  role     = "roles/cloudsql.instanceUser"
  member   = "serviceAccount:${google_service_account.database[each.key].email}"
}
resource "google_service_account_iam_member" "workload_identity" {
  for_each           = var.database_consumers
  service_account_id = google_service_account.database[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${each.value.namespace}/${each.value.service_account}]"
  depends_on         = [google_container_cluster.main]
}
resource "google_sql_user" "iam" {
  for_each = var.database_consumers
  name     = trimsuffix(google_service_account.database[each.key].email, ".gserviceaccount.com")
  instance = google_sql_database_instance.main.name
  type     = "CLOUD_IAM_SERVICE_ACCOUNT"
}
resource "google_secret_manager_secret" "runtime" {
  for_each  = var.secret_ids
  secret_id = "${var.name}-${each.key}"
  replication {
    auto {
    }
  }
  lifecycle {
    prevent_destroy = true
  }
  depends_on = [google_project_service.api]
}
