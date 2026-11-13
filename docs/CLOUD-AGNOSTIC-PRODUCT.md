# Portable AIO product

The first product delivery is a self-hosted control plane installed on an existing Kubernetes cluster. Cloud provisioning stays outside the application. This permits a common installation contract for AWS, Azure, GCP and on-premises; it does not establish tested support on all four environments.

## Delivered foundation

`helm/aio-platform` packages the platform independently of the production GitOps values. It requires an operator-selected OCI image digest, PostgreSQL credentials, OIDC issuer/client, GitHub GitOps repository, Argo CD endpoint and runtime Secret. Ingress class, annotations, TLS Secret, image pull Secrets, service-account annotations and scheduling constraints are configurable. No cloud SDK command is needed to render or install the chart. A startup probe accommodates JVM startup, and replicas remain fixed at one because background observers are not generally coordinated.

The existing GCP installation remains an environment-specific deployment. This package does not adopt its Helm/Argo resources or modify its database, domains, identities or workloads.

## Installation contract

1. Supply an existing Kubernetes cluster, Tekton Pipelines, Argo CD and Argo Rollouts if using blue-green delivery. Supply working PVC provisioning for CI workspaces. Install the repo's Tekton tasks/pipelines and CI ServiceAccount/RBAC after reviewing privileges. Installation of those controllers is not performed by this chart.
2. Supply reachable PostgreSQL with a dedicated database/user authorized for Flyway migrations. The runtime Secret's `DB_URL` accepts standard PostgreSQL JDBC connections; use TLS and your provider's CA/authentication requirements. Cloud SQL is optional; the existing image retains its connector for compatibility. Verify backups and restores independently.
3. Supply OIDC with the AIO client-role claims expected by the current authorization layer. A Keycloak-compatible issuer is the supported initial identity contract; arbitrary OIDC providers require claim-mapping validation. Register `https://<aio-host>/login/oauth2/code/profile` as the exact callback. Keep AIO's approved base roles and ownership checks.
4. Copy the shared `helm/app` chart into your own GitHub GitOps repository, configure the `apps` and `platform` AppProjects, destination clusters/namespaces and scoped Argo credentials. GitHub remains the source/GitOps provider in this product stage.
5. In namespace `aio-platform`, provision a Secret named `aio-platform-runtime` containing `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OIDC_CLIENT_SECRET`, `GITHUB_TOKEN` and `ARGOCD_TOKEN` through your secret manager or secure Kubernetes tooling. Do not commit populated Secrets or pass credentials through Helm values. Configure registry build/scan credentials separately in CI. The legacy managed workload-pull path currently reuses the GitHub token: use its existing GHCR contract until independent provider credentials are completed; an arbitrary registry hostname alone does not make that legacy path portable.
6. Apply the reviewed `platform/deploy/rbac.yaml`, which creates the `aio-platform` ServiceAccount and CI/environment permissions. It contains broad environment namespace/Secret permissions and must be assessed for your tenancy model. For this initial package, use namespace/service account `aio-platform` and CI namespace `ci`; custom namespace RBAC generation is future work. Since that manifest creates the ServiceAccount, set `serviceAccount.create: false` in your values. Do not apply it over unrelated pre-existing installations.
7. Copy `helm/aio-platform/values-example.yaml` outside the repository and replace every example endpoint/image with your own values. The digest is a nonfunctional placeholder. Set `APP_REGISTRY` to your validated managed image destination. Supply trusted Argo HTTPS or explicitly review any private-certificate override. Supply a TLS Secret and ingress controller, or disable Ingress and use your existing HTTPS gateway.

```sh
helm lint helm/aio-platform -f customer-values.yaml
helm template aio-platform helm/aio-platform --namespace aio-platform -f customer-values.yaml
helm upgrade --install aio-platform helm/aio-platform \
  --namespace aio-platform -f customer-values.yaml --wait --timeout 10m
```

Use an explicit kubeconfig context appropriate to the new customer installation. Before install, validate the rendered resources with a server dry-run on that target. Do not install this package over the current production `aio-platform` Deployment: changing its management authority needs an explicit migration plan.

## Product roadmap and acceptance

| Stage | Work | Acceptance |
| --- | --- | --- |
| Portable package | Explicit install inputs, existing Secrets, configurable ingress/scheduling | Render and negative configuration tests; fresh-cluster acceptance still required |
| Provider adapters | Optional EKS/AKS/GKE/on-prem provisioning, storage, identity and secret integrations | Fresh install, login, build, scan, dev deploy and restore on each claimed target |
| Registry independence | Separate source, image push, scan and workload pull identities; ECR/ACR/OCI credential lifecycles | Private image build/pull, rotation, revocation and tenant isolation |
| Delivery product | Durable onboarding, recovery, operator diagnostics and readiness UI | Lost-response/retry tests and authenticated browser acceptance |
| SaaS option | Tenant isolation, billing/quotas, isolated CI and coordinated workers | Isolation/load/recovery gates before multi-tenant availability claims |

GitHub independence, universal OIDC claim mapping, automatic DNS/certificates, provider-specific IAM and autoscaling telemetry installation remain explicit follow-up capabilities. A successful Helm render is not cloud deployment certification.

Contract verification:

```sh
java --class-path <snakeyaml.jar> ops/tests/PortablePlatformTest.java
```
