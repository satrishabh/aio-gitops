mock_provider "google" {

}
variables {

  project_id       = "aio-test-project"
  authorized_cidrs = ["203.0.113.10/32"]

}
run "secure_foundation" {

  command = plan
  assert {
    condition     = google_sql_database_instance.main.settings[0].ip_configuration[0].ipv4_enabled == false
    error_message = "Cloud SQL must not have a public IPv4 endpoint."
  }
  assert {
    condition     = google_sql_database_instance.main.settings[0].backup_configuration[0].point_in_time_recovery_enabled
    error_message = "PITR is required."
  }
  assert {
    condition     = google_sql_database_instance.main.settings[0].availability_type == "REGIONAL"
    error_message = "Default SQL must be regional HA."
  }
  assert {
    condition     = google_container_cluster.main.private_cluster_config[0].enable_private_nodes
    error_message = "Nodes must be private."
  }
  assert {
    condition     = google_container_cluster.main.deletion_protection
    error_message = "Cluster deletion protection must remain enabled."
  }
  assert {
    condition     = length(google_secret_manager_secret.runtime) == 5
    error_message = "Expected metadata-only runtime Secret containers."
  }

}
run "reject_unrestricted_master_access" {

  command = plan
  variables {
    authorized_cidrs = ["0.0.0.0/0"]
  }
  expect_failures = [var.authorized_cidrs]

}
run "future_service_has_separate_identity" {
  command = plan
  variables {
    database_consumers = {
      platform = { database = "aio", namespace = "aio-platform", service_account = "aio-platform" }
      audit    = { database = "aio_audit", namespace = "aio-audit", service_account = "aio-audit" }
    }
  }
  assert {
    condition     = length(google_sql_database.database) == 2 && google_service_account.database["platform"].account_id != google_service_account.database["audit"].account_id
    error_message = "Future services must have independent database and IAM slots."
  }
}
run "reject_shared_service_identity" {
  command = plan
  variables {
    database_consumers = {
      platform = { database = "aio", namespace = "aio-platform", service_account = "aio-platform" }
      audit    = { database = "aio_audit", namespace = "aio-platform", service_account = "aio-platform" }
    }
  }
  expect_failures = [var.database_consumers]
}
