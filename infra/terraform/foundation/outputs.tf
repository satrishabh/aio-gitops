output "project_id" {
  value = var.project_id
}
output "region" {
  value = var.region
}
output "cluster_name" {
  value = google_container_cluster.main.name
}
output "kubectl_credentials_command" {
  value = "gcloud container clusters get-credentials ${google_container_cluster.main.name} --region ${var.region} --project ${var.project_id}"
}
output "ingress_ip" {
  value = google_compute_address.ingress.address
}
output "image_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}/aio-platform"
}
output "sql_connection_name" {
  value = google_sql_database_instance.main.connection_name
}
output "sql_instance_name" {
  value = google_sql_database_instance.main.name
}
output "database_consumers" {
  value = {
    for key, consumer in var.database_consumers : key => {
      database        = consumer.database
      namespace       = consumer.namespace
      service_account = consumer.service_account
      google_identity = google_service_account.database[key].email
      database_user   = google_sql_user.iam[key].name
      jdbc_url        = "jdbc:postgresql:///${consumer.database}?cloudSqlInstance=${google_sql_database_instance.main.connection_name}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&enableIamAuth=true&ipTypes=PRIVATE"
    }
  }
}
output "secret_ids" {
  value = {
    for key, secret in google_secret_manager_secret.runtime : key => secret.secret_id
  }
}
