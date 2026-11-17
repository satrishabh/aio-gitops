terraform {
  required_version = ">= 1.9, < 2.0"
  backend "gcs" {}
  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "2.38.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "3.0.2"
    }
  }
}

provider "kubernetes" {
  config_path    = pathexpand(var.kubeconfig_path)
  config_context = var.kube_context
}
provider "helm" {
  kubernetes = {
    config_path    = pathexpand(var.kubeconfig_path)
    config_context = var.kube_context
  }
}

variable "kubeconfig_path" {
  type    = string
  default = "~/.kube/config"
}
variable "kube_context" {
  type = string
  validation {
    condition     = startswith(var.kube_context, "gke_")
    error_message = "Supply the explicit context of the NEW GKE cluster."
  }
}
variable "ingress_ip" { type = string }
variable "database_consumers" {
  type = map(object({
    namespace       = string
    service_account = string
    google_identity = string
  }))
  validation {
    condition     = try(var.database_consumers.platform.namespace == "aio-platform" && var.database_consumers.platform.service_account == "aio-platform", false)
    error_message = "The current AIO bootstrap requires platform = aio-platform/aio-platform."
  }
}

locals {
  environment_names = toset(["dev", "staging", "prod"])
  namespaces        = setunion(toset(["ci", "argocd", "cert-manager", "ingress-system", "argo-rollouts"]), local.environment_names, toset([for c in var.database_consumers : c.namespace]))
}

resource "kubernetes_namespace_v1" "main" {
  for_each = local.namespaces
  metadata {
    name = each.key
    labels = contains(local.environment_names, each.key) ? {
      "aio.platform/managed"               = "true"
      "aio.platform/env"                   = each.key
      "pod-security.kubernetes.io/enforce" = "baseline"
      "pod-security.kubernetes.io/warn"    = "restricted"
    } : {}
  }
}
resource "kubernetes_service_account_v1" "database" {
  for_each = var.database_consumers
  metadata {
    name        = each.value.service_account
    namespace   = kubernetes_namespace_v1.main[each.value.namespace].metadata[0].name
    annotations = { "iam.gke.io/gcp-service-account" = each.value.google_identity }
  }
}
resource "kubernetes_service_account_v1" "pipeline" {
  metadata {
    name      = "pipeline-runner"
    namespace = kubernetes_namespace_v1.main["ci"].metadata[0].name
  }
}
resource "kubernetes_ingress_class_v1" "compatibility" {
  metadata { name = "nginx" }
  spec { controller = "k8s.io/ingress-nginx" }
}

resource "helm_release" "ingress" {
  name       = "traefik"
  namespace  = kubernetes_namespace_v1.main["ingress-system"].metadata[0].name
  repository = "https://traefik.github.io/charts"
  chart      = "traefik"
  version    = "42.3.1"
  atomic     = true
  timeout    = 600
  values     = [templatefile("${path.module}/traefik-values.yaml.tftpl", { ingress_ip = var.ingress_ip })]
  depends_on = [kubernetes_ingress_class_v1.compatibility]
}
resource "helm_release" "cert_manager" {
  name       = "cert-manager"
  namespace  = kubernetes_namespace_v1.main["cert-manager"].metadata[0].name
  repository = "https://charts.jetstack.io"
  chart      = "cert-manager"
  version    = "v1.21.3"
  atomic     = true
  timeout    = 600
  values     = [yamlencode({ crds = { enabled = true } })]
}
resource "helm_release" "argocd" {
  name       = "argocd"
  namespace  = kubernetes_namespace_v1.main["argocd"].metadata[0].name
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argo-cd"
  version    = "10.15.0"
  atomic     = true
  timeout    = 900
  values = [yamlencode({
    configs = {
      cm = { "accounts.aio-platform" = "apiKey" }
      rbac = {
        "policy.default" = ""
        "policy.csv" = join("\n", [
          "p, role:aio-platform, applications, *, apps/*, allow",
          "p, role:aio-platform, projects, get, apps, allow",
          "p, role:aio-platform, clusters, get, *, allow",
          "p, role:aio-platform, repositories, get, *, allow",
          "p, role:aio-platform, logs, get, apps/*, allow",
          "p, role:aio-platform, certificates, get, *, allow",
          "g, aio-platform, role:aio-platform"
        ])
      }
    }
    server = { service = { type = "ClusterIP" } }
  })]
}

output "kube_context" { value = var.kube_context }
