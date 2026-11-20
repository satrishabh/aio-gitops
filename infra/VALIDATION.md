# Validation record — 2026-11-08

This records local verification, not deployment acceptance.

| Check | Result |
| --- | --- |
| Terraform bootstrap / foundation / runtime | Provider initialization and schema validation passed |
| Foundation mocked plan tests | 4 passed: private SQL/PITR/HA, private nodes/deletion guards, restricted API CIDRs, separate future consumer identities and duplicate identity rejection |
| Runtime mocked plan test | 1 passed: scoped namespace roles, database KSA, no cluster-wide Secret access or namespace deletion |
| Python helpers | 10 tests passed: generated Helm values, isolated GitOps references, placeholders/image validation, overwrite protection, secret schema/names, suppressed child error output, explicit context and stdin delivery |
| Helm rendering | Shared application chart with generated values, Traefik 42.3.1, cert-manager v1.21.3, argo-cd 10.15.0 rendered locally |
| Ingress provider | Rendered args enable only NGINX compatibility provider, disable snippets/cross-namespace resources, use explicit ingress IP; Traefik image v3.7.17 |
| Pinned controller URLs | Tekton v1.6.0 and Rollouts v1.10.1 manifests downloaded over HTTPS; SHA256 recorded in `controllers.sha256` |
| Live database audit | Read-only catalogs/aggregate checks completed; ephemeral Job and ConfigMap cleaned up |

Terraform tested with 1.17.1 on Windows. Providers: Google 7.13.0, Kubernetes 2.38.0, Helm 3.0.2. Python uses standard-library modules; helper chart test requires local Helm. No new backend/frontend runtime changes were made in this task.

Not executed: authenticated cloud plan, Terraform apply, SQL restore, fresh-environment login/build/deploy, actual ingress annotation compatibility, failure/load tests, or production cutover. These remain the explicit acceptance gates in [the deployment runbook](README.md) and [migration runbook](../docs/architecture/MIGRATION.md). No microservice, durable dispatcher or circuit breaker has been implemented merely by adding this infrastructure/documentation.
