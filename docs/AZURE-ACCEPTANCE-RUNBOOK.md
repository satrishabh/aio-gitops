# Azure portability test process

Status: updated on 2026-11-14. Browser authentication and preflight succeeded for AKS `creative`, resource group `creative`, subscription `150909ba-4c23-44e9-b98d-e8a2d433f709`. Isolated HTTP, service DNS, disk persistence and exact AIO image/runtime tests passed. Independent cert-manager, Argo CD and Tekton installations are Ready; internal self-signed issuance and a credential-free TaskRun passed. Homepage staging is paused pending the user-selected dedicated GHCR credential. Application acceptance and data/traffic migration remain pending. See [preflight results](AZURE-PREFLIGHT-2026-11-14.md) and [migration process](AZURE-MIGRATION-PROCESS.md).

## 1. Record the target

Record subscription ID, tenant, existing AKS resource group and name, location, operator identity, network access, node capacity, cost owner and test expiry date. If creating a cluster instead, select region, node size/count, network/address ranges, API access policy and budget first. Do not repurpose production namespaces. Azure CLI authentication should use normal Entra identity rather than retrieving admin credentials by default.

## 2. Connect without replacing the GCP context

Install Azure CLI and the AKS authentication helper appropriate to the cluster. Authenticate interactively; never paste access tokens into this document or AIO. Use a dedicated kubeconfig outside Git:

```powershell
az login
az account show --subscription <subscription-id> --output table
az aks show --subscription <subscription-id> --resource-group <resource-group> --name <cluster> --query '{name:name,location:location,state:provisioningState}'
az aks get-credentials --subscription <subscription-id> --resource-group <resource-group> --name <cluster> --file <private-path-to-azure-kubeconfig>
kubectl --kubeconfig <private-path-to-azure-kubeconfig> config get-contexts
```

Record the exact context, then include both `--kubeconfig` and `--context` on every kubectl/Helm command. Follow Entra login/network requirements if the API is private. Do not add `--admin` or overwrite an existing context as an access workaround.

## 3. Preflight, read-only

Verify API connectivity, node readiness/resources, Kubernetes/controller versions, default StorageClass and a suitable PVC provisioner, ingress classes, DNS, egress to source/image/identity services, NetworkPolicy support and namespace permissions. Inventory existing cert-manager/Tekton/Argo installations and ownership before any controller install. Do not replace shared controllers.

```powershell
kubectl --kubeconfig <file> --context <context> get nodes
kubectl --kubeconfig <file> --context <context> get storageclass,ingressclass
kubectl --kubeconfig <file> --context <context> get deployments -A
kubectl --kubeconfig <file> --context <context> auth can-i create namespaces
kubectl --kubeconfig <file> --context <context> get crd certificates.cert-manager.io pipelineruns.tekton.dev applications.argoproj.io
```

Log missing prerequisites as missing; do not interpret a denied inventory request as absence.

## 4. Prepare isolated dependencies

Create a dedicated test GitOps repository and test identities. Provision a reachable PostgreSQL database/user, backup policy, and runtime Secret. Use standard PostgreSQL JDBC with required TLS. Choose a registry and separately provision source, push, scan and workload pull credentials. Begin with the existing validated GHCR credential contract; ACR image build/pull and identity rotation are an additional acceptance gate, not assumed from AKS availability. Copy the AIO image to an accessible registry if the cluster cannot pull from the current registry, preserving/verifying its digest.

Install only missing controllers through an explicitly owned process with reviewed pinned versions: Tekton, Argo CD, optional Rollouts, ingress and cert-manager. Record versions and ownership. Supply the repo's tasks, pipelines, service accounts/RBAC and Argo AppProjects. Verify CI workspace PVC creation/deletion with a disposable claim before starting builds.

## 5. Prepare identity and TLS

Use a dedicated AIO OIDC client with an exact Azure-test hostname callback and compatible AIO client-role claims. Reuse an identity service only through a separate client and authorized redirect. Provide DNS and an existing TLS Secret or an approved cert-manager issuer. Prefer an ACME staging issuer for issuance rehearsals, then explicitly validate a trusted production issuer; staging certificates are not browser-trusted. HTTP-01 requires that DNS and challenge ingress routing reach this cluster. Azure DNS-01 and managed certificates are separate provider integrations.

The new project Certificates tab shows issuer, status, expiry and last observation using existing authorized domain data. It does not install cert-manager or expose private keys. Verify a pending certificate, valid certificate, expiry warning, missing observation and failed issuance. cert-manager manages automatic renewals; observation refresh is not a forced renewal.

## 6. Render and install

Follow `CLOUD-AGNOSTIC-PRODUCT.md`, use the portable chart with customer values and the existing runtime Secret. For this first chart, retain namespace/service account `aio-platform` and CI namespace `ci` on an isolated test cluster. The static RBAC manifest must be reviewed before applying; it is not an AKS tenancy sandbox. Set `serviceAccount.create: false` if that manifest created the account.

```powershell
helm lint helm/aio-platform -f <azure-values.yaml>
helm template aio-platform helm/aio-platform --namespace aio-platform -f <azure-values.yaml> | kubectl --kubeconfig <file> --context <context> apply --dry-run=server -f -
helm upgrade --install aio-platform helm/aio-platform --kubeconfig <file> --kube-context <context> --namespace aio-platform -f <azure-values.yaml> --wait --timeout 10m
```

Keep credentials outside Helm values and Git. Record chart revision, image digest, values revision and deployment logs without secret values.

## 7. Acceptance evidence

| Check | Required evidence | Current result |
| --- | --- | --- |
| Platform | Ready pod, zero unexpected restarts, readiness UP, correct image digest | Not run |
| Identity | Approved login works; unrelated/unauthorized user denied | Not run |
| UI | Project, Certificates, registry settings and CD configuration render in browser | Not run |
| TLS | DNS/challenge, Ready certificate, issuer/expiry shown, browser trust | Not run |
| Delivery | Exact source build, tests, scan gate, dev GitOps update and healthy rollout | Not run |
| Isolation | Owner/non-owner list/detail/log/action tests | Not run |
| Recovery | Application restart, failed-build behavior, database restore rehearsal | Not run |
| ACR option | Separate image push/read/workload pull, rotation and revoked access | Not run |

Capture timestamps, controller versions, test project/run IDs, screenshots and redacted logs. Mark Azure supported only after the claimed scenarios pass.

## 8. Cleanup and comparison

Remove only explicitly created disposable resources after evidence capture and backup retention decisions. Do not delete a shared AKS cluster, shared controllers or registry. Record remaining billable resources and responsible owner.

After Azure, apply the same acceptance matrix to EKS and an on-premises Kubernetes distribution. Keep common product code and chart settings constant; record only provider-specific storage, load-balancer, identity, secret and registry adapters. Each successful run turns a portability claim into reproducible evidence.

References: [AKS CLI](https://learn.microsoft.com/en-us/cli/azure/aks?view=azure-cli-latest), [AKS kubeconfig access](https://learn.microsoft.com/en-us/azure/aks/control-kubeconfig-access), [cert-manager Certificate lifecycle](https://cert-manager.io/docs/usage/certificate/).
