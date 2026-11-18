# AIO platform chart

Portable installation of the AIO control plane on an existing Kubernetes cluster. This chart manages the platform Deployment, ClusterIP Service, optional ServiceAccount and optional TLS Ingress. Database, identity, registry, GitOps and controllers are operator-supplied services.

See [installation contract and acceptance gates](../../docs/CLOUD-AGNOSTIC-PRODUCT.md) before installing. Start from [values-example.yaml](values-example.yaml); default values intentionally fail validation until installation-specific settings are supplied. The example image/digest is a placeholder, not a published release.

This chart is separate from `helm/app`, which deploys customer workloads. Do not install it over an existing GitOps-managed production deployment without a management migration.
