# Pre-create shared environments. AIO can patch only these namespace objects;
# it cannot create/delete arbitrary namespaces or read Secrets cluster-wide.
resource "kubernetes_cluster_role_v1" "platform" {
  metadata { name = "aio-platform-discovery" }
  rule {
    api_groups = [""]
    resources  = ["namespaces"]
    verbs      = ["get", "list"]
  }
  rule {
    api_groups     = [""]
    resources      = ["namespaces"]
    resource_names = ["dev", "staging", "prod"]
    verbs          = ["patch", "update"]
  }
  rule {
    api_groups     = ["apiextensions.k8s.io"]
    resources      = ["customresourcedefinitions"]
    resource_names = ["rollouts.argoproj.io"]
    verbs          = ["get"]
  }
}
resource "kubernetes_cluster_role_binding_v1" "platform" {
  metadata { name = "aio-platform-discovery" }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = kubernetes_cluster_role_v1.platform.metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.database["platform"].metadata[0].name
    namespace = "aio-platform"
  }
}
resource "kubernetes_role_v1" "environment" {
  for_each = local.environment_names
  metadata {
    name      = "aio-platform-environment"
    namespace = kubernetes_namespace_v1.main[each.key].metadata[0].name
  }
  rule {
    api_groups = [""]
    resources  = ["secrets", "limitranges"]
    verbs      = ["get", "create", "update", "patch"]
  }
}
resource "kubernetes_role_binding_v1" "environment" {
  for_each = local.environment_names
  metadata {
    name      = "aio-platform-environment"
    namespace = each.key
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role_v1.environment[each.key].metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.database["platform"].metadata[0].name
    namespace = "aio-platform"
  }
}
resource "kubernetes_role_v1" "ci" {
  metadata {
    name      = "aio-platform-ci"
    namespace = kubernetes_namespace_v1.main["ci"].metadata[0].name
  }
  rule {
    api_groups = ["tekton.dev"]
    resources  = ["pipelineruns"]
    verbs      = ["create", "get", "list", "watch", "patch", "update", "delete"]
  }
  rule {
    api_groups = ["tekton.dev"]
    resources  = ["taskruns"]
    verbs      = ["get", "list", "watch"]
  }
  rule {
    api_groups = [""]
    resources  = ["pods", "pods/log"]
    verbs      = ["get", "list"]
  }
}
resource "kubernetes_role_binding_v1" "ci" {
  metadata {
    name      = "aio-platform-ci"
    namespace = "ci"
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role_v1.ci.metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.database["platform"].metadata[0].name
    namespace = "aio-platform"
  }
}
