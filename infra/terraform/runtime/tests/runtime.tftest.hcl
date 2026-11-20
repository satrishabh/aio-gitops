mock_provider "kubernetes" {}
mock_provider "helm" {}
variables {
  kube_context = "gke_test-project_asia-south1_aio-dev"
  ingress_ip   = "203.0.113.10"
  database_consumers = {
    platform = {
      namespace       = "aio-platform"
      service_account = "aio-platform"
      google_identity = "aio-dev-platform@test-project.iam.gserviceaccount.com"
    }
  }
}
run "scoped_runtime" {
  command = plan
  assert {
    condition     = length(kubernetes_role_v1.environment) == 3 && length(kubernetes_service_account_v1.database) == 1
    error_message = "Preserve the shared environment roles and workload identity account."
  }
  assert {
    condition     = !contains(flatten([for rule in kubernetes_cluster_role_v1.platform.rule : rule.resources]), "secrets") && !contains(flatten([for rule in kubernetes_cluster_role_v1.platform.rule : rule.verbs]), "delete")
    error_message = "AIO must not receive cluster-wide Secret access or namespace deletion."
  }
}
