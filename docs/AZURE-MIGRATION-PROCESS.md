# Azure migration execution record

## 1. Independent controller foundation — installed

Installed on existing AKS `creative` using explicit kubeconfig/context, from official version-pinned manifests:

| Component | Version | Verified result |
| --- | --- | --- |
| cert-manager | v1.21.3 | Three deployments available; internal self-signed Certificate Ready |
| Argo CD | v3.5.6 | Six deployments and application-controller StatefulSet Ready; no source Applications imported |
| Tekton Pipelines | v1.6.0 | Controller, events, webhook and remote resolver Ready; TaskRun Succeeded |

Download URLs and SHA-256 checksums are in `ops/portable/controllers.lock.json`. Download each pinned file, verify its checksum, then use `kubectl --kubeconfig <file> --context aio-azure-creative apply --server-side --field-manager=aio-azure-bootstrap -f <file>`. Argo requires namespace `argocd` and `-n argocd`. Do not force conflicts or overwrite an installation owned by someone else. Do not import production Argo Applications as a controller test.

`ops/portable/azure-controller-smoke.yaml` contains the observed issuance and TaskRun rehearsal. Its `validation.invalid` self-signed certificate is internal test material; this does not validate ACME, domain ownership or browser trust. The TaskRun printed `AIO_AZURE_TEKTON_VERIFIED`; this does not validate a complete application CI build/scan/release. Argo, Tekton and certificate services have no public endpoint.

## 2. Private application registry — waiting for dedicated credential

The user chose a dedicated GHCR read-only credential instead of creating a billable ACR. No ACR was created. The homepage's exact source digest is private; anonymous GHCR pull returned 401. The Azure homepage Deployment is paused at zero replicas until pull access is installed. Source remains at one replica on GCP.

In your own interactive PowerShell terminal, run from this worktree:

```powershell
.\ops\portable\set-azure-ghcr-pull.ps1 -GitHubUsername satrishabh
```

Use the GitHub username that owns the dedicated token. The script prompts with hidden input and installs only `ghcr-readonly` in Azure namespace `aio-validation`. Grant only the package-read access needed for these private images, including any organization authorization required by GitHub; avoid a broad deployment/write token. Do not put the token in chat, Git, arguments or Helm values. The operator must verify token scope and package access; the helper cannot infer or reduce token permissions. Rotate/revoke this credential through GitHub and rerun the helper to update the Secret.

After Secret existence and image pull are verified, scale only `aio-validation/sarvaikam-home` to one replica. Validate `/health` and the homepage through a local port-forward. `ops/portable/azure-home-stage.yaml` pins the exact running image digest; it creates no public route or DNS change. Persist any subsequently approved replica change in this manifest before adopting GitOps ownership.

The earlier temporary GAR token test applies only to the AIO image and has been cleaned up. It is not a durable credential for GHCR or the AIO hub.

## 3. Database and object-storage migration — inventoried, not transferred

Source Cloud SQL `aio-002` runs PostgreSQL 18 in asia-south1. Application databases observed: `aio`, `profile_manager`, `sarvaika_blog`, `sarvaikam`, `sarvaikam_reels`. Fresh on-demand backup `1794605803842`, operation `fb518212-0838-41bb-9b78-cec10000002f`, completed SUCCESSFUL at `2026-11-13T21:38:15.120Z`. A backup is not a cross-cloud restore rehearsal.

No Azure PostgreSQL Flexible Server was returned by the subscription inventory. A database destination and its capacity/cost/backup policy must be chosen before real data is copied. For each database, identify owners/extensions, capture a consistent logical backup with a compatible PostgreSQL client, restore into an isolated target, and verify schema, row counts, application reads/writes and roles. Keep dumps encrypted and outside Git. Do not copy the stopped historical `aio-postgres` PVC as if it were the active database.

Learn now has `EMBEDDED_DB=false`, uses `sarvaikam-cloudsql-proxy:5432` for its main and reels databases, and references GCS `cotent_bucket`. Database SSL is disabled only on the local proxy hop; this is not an appropriate direct Azure database setting. Use required TLS and the destination's trust configuration. The GCS object references/signing identity are a separate dependency: keeping GCS temporarily is cross-cloud operation, while a full move requires an object-store adapter and copying/verifying objects plus references. Do not silently substitute an Azure bucket URL into GCP-specific SDK code.

## 4. Application staging order

1. Homepage: stateless exact-image staging and HTTP validation.
2. Profile manager: isolated database restore and validation client/callback configuration; preserve source identity authority.
3. Blog and Learn/reels: restore rehearsals, object-storage resolution, identity/ownership and browser acceptance.
4. AIO hub: move last after GitOps, CI, registry, database and identity readiness; prevent two active worker sets reconciling the same projects.

Keep intentionally stopped sample/staging/production workloads stopped. Existing source environment mappings use one Argo destination per environment and cascading replacement, so they must not be changed as a staging shortcut.

## 5. Routing and cutover — pending

Select and validate a supported target ingress controller rather than copying the old source ingress installation automatically. Create isolated validation hostnames, configure exact OIDC callbacks, prove trusted certificate issuance, then test traffic behavior before touching production DNS. ExternalDNS is deliberately absent on Azure to prevent competing ownership of the same source records. Define database write authority, final synchronization, downtime, rollback conditions and source retention before switching traffic. No production cutover or application-data transfer has occurred in these steps.
