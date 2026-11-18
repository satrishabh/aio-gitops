# Multi-cloud product configuration and acceptance

For the complete reusable migration procedure, see
[Cloud migration runbook](CLOUD-MIGRATION-RUNBOOK.md). This document describes
provider configuration foundations and earlier acceptance status; Azure was
subsequently migrated as recorded in the linked runbook and dated event records.

Updated 2026-11-14. The implementation provides one portable Kubernetes chart, provider profiles, generated read-only preflight plans, and an operator-facing target catalog. It does not provision provider infrastructure or claim tested installations outside the existing GCP environment. The new package still needs fresh-cluster acceptance on every target.

## Target matrix

| Provider | Installation target | Provider-owned integration to validate |
| --- | --- | --- |
| AWS | EKS | IAM, CSI, load balancer, ECR credential renewal, database networking |
| Azure | AKS | Entra/RBAC, CSI, load balancer, ACR identities, database networking |
| Alibaba Cloud | ACK | RAM, CSI, load balancer, Container Registry, regional egress |
| Google Cloud | GKE | Workload Identity, CSI, ingress, Artifact Registry, PostgreSQL |
| Oracle Cloud | OKE | Identity, CSI, load balancer, registry and PostgreSQL |
| DigitalOcean | DOKS | Kubernetes access, storage, load balancer and registry |
| On-premises / other | Conformant Kubernetes | Operator-owned networking, storage, registry, identity and database |
| Cloudflare DNS/CDN | Integration in front of a Kubernetes target | DNS, proxy/origin connectivity, trusted TLS, challenge routing |
| Cloudflare Workers/Containers | Separate edge adapter, not implemented | Workers/Wrangler deployment, runtime lifecycle, secret and rollback contracts |

Cloudflare Containers uses a Workers/Wrangler model. A Helm deployment and Tekton/Argo runtime do not run there through the current Kubernetes adapter. The profile generator explicitly rejects Cloudflare edge and DNS-only targets as Kubernetes installations. AIO may run on Kubernetes with Cloudflare in front of it; this is a different integration.

## Common process for each Kubernetes provider

1. Record provider, account/subscription, region, existing cluster, permitted operator identity, costs, test owner and retention window. Obtain a private kubeconfig through the provider's authenticated tooling; keep it outside Git. No cloud-account credential belongs in AIO's UI.
2. Use explicit context and kubeconfig selection. Verify nodes, permissions, storage, ingress, egress and existing controller ownership. Use the generated preflight plan as read-only inspection; it neither signs in nor registers clusters.
3. Provision isolated PostgreSQL, GitOps repository, OIDC client, registry credentials, runtime Secret and reviewed controller/RBAC dependencies. See `CLOUD-AGNOSTIC-PRODUCT.md`. Keep AIO itself at one replica.
4. Render the same portable chart. Select ingress class, annotations, TLS and service-account identity annotations through values rather than provider-specific application branches. Validate with the target API before install.
5. Verify login/denial, project ownership, build/test/scan, desired GitOps commit, healthy workload rollout, certificate issuer/expiry, log access, restart/recovery and backup restore. Record evidence rather than marking a provider connected because its name appears in the UI.
6. Repeat provider-specific identity/registry rotation and failure tests. Remove only the disposable resources created for the test; retain evidence and approved backups. Do not remove shared controllers or clusters.

## Generate a provider-specific installation plan

Copy `ops/portable/values-example.json` to a private non-secret customer configuration and replace all image, hostname and identity examples. The image digest is a placeholder. The output is Helm-compatible JSON; it merges with chart defaults. Provider selection changes metadata and operator prerequisites, not the platform runtime contract.

```sh
python ops/portable/render.py --provider aws --context customer-eks \
  --values customer-values.json --output private-generated/aws-test
helm lint helm/aio-platform -f private-generated/aws-test/values-platform.json
```

Other provider IDs: `azure`, `alibaba`, `gcp`, `oci`, `digitalocean`, `onprem`, `generic`. The UI and generator share the catalog in `platform/frontend/src/data/cloudProviders.json`. Outputs refuse overwrite, pin a digest, require explicit installation inputs and keep independent delivery disabled until acceptance gates pass. Do not store populated runtime Secrets in the input/output directory. The generator does not execute its `preflight.json` commands.

For Azure, follow the detailed `AZURE-ACCEPTANCE-RUNBOOK.md`; the existing cluster's subscription, resource group and name are still needed. For EKS, retrieve a separate kubeconfig with `aws eks update-kubeconfig --region <region> --name <cluster> --kubeconfig <private-file>`. For ACK, retrieve operator-scoped kubeconfig using the ACK console/API, respecting API network restrictions. Do not assume public access or administrator rights.

## Managed registry pull credentials

`AIO_WORKLOAD_PULL_SECRET` optionally names an existing `kubernetes.io/dockerconfigjson` Secret in the configured CI namespace. AIO validates its registry entry and copies only matching credentials into its managed workload pull Secret for hub-hosted environments. It never reads credentials from a provider profile or returns the payload in errors. Provision/rotate this Secret externally with registry read-only permissions. Build push and scan-read identities remain separate CI configuration.

Without this setting, the existing GitHub-token fallback remains only for `ghcr.io`. Other managed registries fail closed rather than receiving GitHub credentials. Remote workload clusters must have their pull credentials provisioned independently: AIO holds no direct workload-cluster credentials. Revocation must also happen at the registry provider; copies already mounted/created are not automatically revoked. ECR login tokens expire after 12 hours, so a one-time token is not a production credential lifecycle. Provider-native pull identity or an external refresh process must be verified before certification.

## Cloudflare DNS and certificates

The example `ops/portable/cloudflare-clusterissuer.example.yaml` prepares a cert-manager DNS-01 ClusterIssuer using an existing scoped API-token Secret. It is not applied automatically. Replace the email and zone, grant DNS Edit and Zone Read only for intended zones, and provision the Secret in cert-manager's configured cluster-resource namespace. Start with the staging issuer, which produces untrusted test certificates. Use a separately reviewed production issuer only after successful challenge/renewal tests.

Set each AIO workload cluster's issuer field to the installed ClusterIssuer name. The existing workload chart's cert-manager annotations and controller must be compatible. Configuring an issuer does not automatically publish application A/AAAA/CNAME records: manage those separately. Validate Cloudflare proxy/origin TLS and challenge behavior on the intended DNS configuration. The Certificates tab observes cert-manager state via existing authorized domain APIs; it does not install controllers or reveal API tokens/private keys.

## Verification and remaining boundaries

Run the provider-profile unit suite, portable Helm contracts, backend credential tests/full regression and frontend build. These establish local implementation behavior. AWS/Azure/Alibaba/other cloud acceptance, infrastructure provisioning adapters, Cloudflare edge delivery, automatic registry-token refresh, automatic DNS record administration, GitHub-independent GitOps and arbitrary OIDC claim mapping are not completed by these changes.

Local validation on 2026-11-14: all 95 backend tests passed, including six workload credential tests; all eight provider-plan tests passed, including actual Helm rendering for every Kubernetes profile and CLI overwrite protection; all 13 portable chart contracts passed; chart lint and frontend production build passed. No new cloud account was connected and no infrastructure or production rollout was performed. The Certificates tab and target catalog remain feature-branch changes until an image is built and deployed.

References: [EKS kubeconfig](https://docs.aws.amazon.com/eks/latest/userguide/create-kubeconfig.html), [ACK kubeconfig](https://www.alibabacloud.com/help/en/ack/ack-managed-and-ack-dedicated/user-guide/kubeconfig-operation-guide/), [ECR token lifetime](https://docs.aws.amazon.com/AmazonECR/latest/userguide/registry_auth.html), [Cloudflare Containers](https://developers.cloudflare.com/containers/), [Cloudflare cert-manager DNS solver](https://cert-manager.io/docs/configuration/acme/dns01/cloudflare/).
