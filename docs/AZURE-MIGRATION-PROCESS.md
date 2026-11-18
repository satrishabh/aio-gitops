# Azure migration execution record

For the reusable procedure, see [Cloud migration runbook](CLOUD-MIGRATION-RUNBOOK.md).
This staged execution record contains historical observations. The later
[native cutover](NATIVE-AZURE-CUTOVER-2026-11-14.md) and
[GCP hibernation](GCP-DOWNSIZE-2026-11-14.md) records describe subsequent outcomes.

## 1. Independent controller foundation — installed

Installed on existing AKS `creative` using explicit kubeconfig/context, from official version-pinned manifests:

| Component | Version | Verified result |
| --- | --- | --- |
| cert-manager | v1.21.3 | Three deployments available; internal self-signed Certificate Ready |
| Argo CD | v3.5.6 | Six deployments and application-controller StatefulSet Ready; no source Applications imported |
| Tekton Pipelines | v1.6.0 | Controller, events, webhook and remote resolver Ready; TaskRun Succeeded |

Download URLs and SHA-256 checksums are in `ops/portable/controllers.lock.json`. Download each pinned file, verify its checksum, then use `kubectl --kubeconfig <file> --context aio-azure-creative apply --server-side --field-manager=aio-azure-bootstrap -f <file>`. Argo requires namespace `argocd` and `-n argocd`. Do not force conflicts or overwrite an installation owned by someone else. Do not import production Argo Applications as a controller test.

`ops/portable/azure-controller-smoke.yaml` contains the observed issuance and TaskRun rehearsal. Its `validation.invalid` self-signed certificate is internal test material; this does not validate ACME, domain ownership or browser trust. The TaskRun printed `AIO_AZURE_TEKTON_VERIFIED`; this does not validate a complete application CI build/scan/release. Argo, Tekton and certificate services have no public endpoint.

## 2. Private application registry — homepage pull verified

The user chose a dedicated GHCR read-only credential instead of creating a billable ACR. No ACR was created. Initial anonymous and first authenticated attempts failed (401 and 403). After the user updated the credential, AKS pulled the pinned homepage digest successfully. At `2026-11-13T21:54:40Z`, one Azure replica was Ready with zero restarts, `/health` returned `200 ok`, and `/` returned HTTP 200 with 8730 bytes of homepage HTML. This verifies package access for this image only, not every private package or token permission scope. Source remains at one replica on GCP; no public route was moved. Visual browser acceptance remains pending.

In your own interactive PowerShell terminal, run from this worktree:

```powershell
.\ops\portable\set-azure-ghcr-pull.ps1 -GitHubUsername satrishabh
```

Use the GitHub username that owns the dedicated token. The script prompts with hidden input and installs only `ghcr-readonly` in Azure namespace `aio-validation`. Grant only the package-read access needed for these private images, including any organization authorization required by GitHub; avoid a broad deployment/write token. Do not put the token in chat, Git, arguments or Helm values. The operator must verify token scope and package access; the helper cannot infer or reduce token permissions. Rotate/revoke this credential through GitHub and rerun the helper to update the Secret.

The verified one-replica target state is recorded in `ops/portable/azure-home-stage.yaml`. It pins the exact source image digest and creates no public route or DNS change. Validate `/health` and the homepage through a local-only port-forward; the session used for this test forwards local port 18089 to service port 8080. Secret rotation requires a new image-pull test; an already-running pod alone does not prove a replacement credential works.

The earlier temporary GAR token test applies only to the AIO image and has been cleaned up. It is not a durable credential for GHCR or the AIO hub.

## 3. Database and object-storage migration — isolated target verified

Source Cloud SQL `aio-002` runs PostgreSQL 18 in asia-south1. Application databases observed: `aio`, `profile_manager`, `sarvaika_blog`, `sarvaikam`, `sarvaikam_reels`. Fresh on-demand backup `1794605803842`, operation `fb518212-0838-41bb-9b78-cec10000002f`, completed SUCCESSFUL at `2026-11-13T21:38:15.120Z`. A backup is not a cross-cloud restore rehearsal.

No Azure PostgreSQL Flexible Server was returned by the subscription inventory. The user requested the cheapest suitable alternative, so an isolated PostgreSQL 18.7 StatefulSet uses existing AKS capacity with a retained 4Gi Standard SSD (observed disk retail price $0.30/month plus transactions). TLS, rejection of unencrypted TCP, synthetic backup/restore and persistence tests passed. After explicit approval to include data and use schemas, Learn/reels snapshots were restored into ONE database `aio_rehearsal`: `sarvaikam` (14 tables, 254 rows) and `sarvaikam_reels` (2 tables, 1 migration-history row). Every table's count/content fingerprint matched its exported source snapshot; constraints and indexes are valid. Source remains authoritative and application acceptance is pending. See [cost comparison and restore evidence](AZURE-DATABASE-REHEARSAL.md).

Learn now has `EMBEDDED_DB=false`, uses `sarvaikam-cloudsql-proxy:5432` for its main and reels databases, and references GCS `cotent_bucket`. Database SSL is disabled only on the local proxy hop; this is not an appropriate direct Azure database setting. Use required TLS and the destination's trust configuration. The GCS object references/signing identity are a separate dependency: keeping GCS temporarily is cross-cloud operation, while a full move requires an object-store adapter and copying/verifying objects plus references. Do not silently substitute an Azure bucket URL into GCP-specific SDK code.

## 4. Application staging order

Current update: homepage and Learn's schema-backed content/reels APIs pass isolated Azure checks. Learn uses fresh target-only identities and a reviewed Nginx compatibility override; production identity is disconnected, so authenticated login remains pending. See [test evidence and applied mappings](AZURE-POD-TEST-AND-MAPPING.md). Profile, blog and AIO hub are not running on Azure yet.

1. Homepage: stateless exact-image staging and HTTP validation.
2. Profile manager: isolated database restore and validation client/callback configuration; preserve source identity authority.
3. Blog and Learn/reels: restore rehearsals, object-storage resolution, identity/ownership and browser acceptance.
4. AIO hub: move last after GitOps, CI, registry, database and identity readiness; prevent two active worker sets reconciling the same projects.

Keep intentionally stopped sample/staging/production workloads stopped. Existing source environment mappings use one Argo destination per environment and cascading replacement, so they must not be changed as a staging shortcut.

## 5. Routing and cutover — pending

Select and validate a supported target ingress controller rather than copying the old source ingress installation automatically. Create isolated validation hostnames, configure exact OIDC callbacks, prove trusted certificate issuance, then test traffic behavior before touching production DNS. ExternalDNS is deliberately absent on Azure to prevent competing ownership of the same source records. Define database write authority, final synchronization, downtime, rollback conditions and source retention before switching traffic. No production cutover or application-data transfer has occurred in these steps.
