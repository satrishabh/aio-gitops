variable "project_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "Use an existing GCP project ID, not its display name."
  }
}
variable "name" {
  type    = string
  default = "aio-dev"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,17}$", var.name))
    error_message = "Use a 3–18 character lowercase environment name."
  }
}
variable "region" {
  type    = string
  default = "asia-south1"
}
variable "node_locations" {
  type    = list(string)
  default = []
}
variable "machine_type" {
  type    = string
  default = "e2-standard-4"
}
variable "min_nodes" {
  type    = number
  default = 2
}
variable "max_nodes" {
  type    = number
  default = 6
  validation {
    condition     = var.max_nodes >= var.min_nodes && var.min_nodes >= 1
    error_message = "Node totals must satisfy max_nodes >= min_nodes >= 1."
  }
}
variable "authorized_cidrs" {
  type = list(string)
  validation {
    condition     = length(var.authorized_cidrs) > 0 && alltrue([for cidr in var.authorized_cidrs : can(cidrnetmask(cidr)) && !endswith(cidr, "/0")])
    error_message = "Provide trusted runner/operator IPv4 CIDRs; unrestricted /0 is prohibited."
  }
}
variable "subnet_cidr" {
  type    = string
  default = "10.20.0.0/20"
}
variable "pods_cidr" {
  type    = string
  default = "10.24.0.0/14"
}
variable "services_cidr" {
  type    = string
  default = "10.28.0.0/20"
}
variable "master_cidr" {
  type    = string
  default = "172.20.0.0/28"
}
variable "sql_range_address" {
  type    = string
  default = "10.30.0.0"
}
variable "sql_tier" {
  type    = string
  default = "db-custom-2-8192"
}
variable "sql_availability" {
  type    = string
  default = "REGIONAL"
  validation {
    condition     = contains(["REGIONAL", "ZONAL"], var.sql_availability)
    error_message = "Choose REGIONAL for HA or explicitly select ZONAL for a lower-cost environment."
  }
}
variable "database_consumers" {
  description = "One IAM identity per logical database consumer. Additional consumers support future service extraction."
  type = map(object({
    database = string, namespace = string, service_account = string
  }))
  default = {
    platform = {
      database = "aio", namespace = "aio-platform", service_account = "aio-platform"
    }
  }
  validation {
    condition = length(var.database_consumers) > 0 && alltrue([for key, value in var.database_consumers :
      can(regex("^[a-z][a-z0-9-]{0,7}$", key)) && can(regex("^[a-z][a-z0-9_]{0,62}$", value.database)) &&
    can(regex("^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$", value.namespace)) && can(regex("^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$", value.service_account))])
    error_message = "Use short consumer keys, safe database names, and valid Kubernetes namespace/account names."
  }
  validation {
    condition     = length(distinct([for c in var.database_consumers : "${c.namespace}/${c.service_account}"])) == length(var.database_consumers)
    error_message = "Each database consumer requires its own Kubernetes service-account identity."
  }
}
variable "secret_ids" {
  description = "Secret containers only. Values are supplied out of band and are never Terraform inputs."
  type        = set(string)
  default     = ["aio-platform-config", "aio-platform-oidc", "aio-github-token", "aio-registry-config", "aio-argocd-repository"]
}
