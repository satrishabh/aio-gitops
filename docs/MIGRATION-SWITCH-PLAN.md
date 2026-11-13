# All-workload migration and switch plan

Draft: 2026-11-14. Destination and switch semantics await user selection. No application, data, cluster mapping or traffic has been moved.

## Observed source inventory

Read-only Kubernetes/Argo inventory shows the AIO platform and all configured workload environments on the existing GKE/in-cluster destination. AIO is Ready at one replica. Running dev workloads include `sarvaika-blog`, `sarvaikam`, `sarvaikam-home` and `user-profile-manager`. Several sample workloads and all observed staging Deployments are intentionally at zero; preserve these desired counts during migration. Do not equate a zero-replica or missing production application with a migration failure or automatically start it.

Public routes include `app.sarvaikam.com`, `learn.sarvaikam.com`, `profile.sarvaikam.com`, `blog.sarvaikam.com` and `sarvaikam.com`, plus legacy IP-based aliases. DNS authorities/records, callback URLs and TLS ownership must be inventoried separately before any cutover. Current IP-derived aliases cannot simply retain the same meaning on a different load-balancer IP.

The cluster contains Cloud SQL proxy Deployments, a stopped `aio-postgres` StatefulSet with a retained 10Gi PVC, and CI workspace PVCs. Active database endpoints, databases/users, backups and restore tests require their own dependency inventory. A stopped historical PostgreSQL PVC is not proof that it contains the current application database. CI workloads/affinity assistants were present at inventory time and must be drained or explicitly handled before moving CI storage/controllers.

Controller dependencies include Argo CD, Tekton, Rollouts, cert-manager, ingress and ExternalDNS. Provider-managed GKE system controllers are not application workloads to copy into AKS/EKS/ACK.

## Existing behavior that must not implement the new switch

`ArgoCdService` currently handles destination moves with cascading delete/recreate. `ClusterRegistry` maps each environment to exactly one configured destination; startup/Repair can reconcile moves. This behavior does not stage parallel deployments or coordinate data, traffic and rollback. Do not change current environment mappings merely to preview another cloud.

## Required migration stages

1. **Select scope and target:** one app/environment, all workloads, or management hub plus workloads. Record source/target identities, operator permissions and costs. Choose move versus dual deployment/traffic switching explicitly.
2. **Inventory dependencies:** application digests/configuration, current desired replicas, databases, storage, external APIs, identity clients, domain/DNS authority, certificates, GitOps ownership and credentials. Never export populated Secrets into Git.
3. **Prepare target:** validate cluster/network/storage/controller prerequisites and independent image/source/scan/pull credentials. Give target applications distinct Argo identities/values and isolated validation routes so source reconciliation continues safely.
4. **Validate target:** verify exact digest, readiness, smoke tests, owner/non-owner access, OIDC callbacks, TLS, logs, storage behavior and backup/restore evidence. Cloudflare proxy health alone is insufficient evidence that the target application is healthy.
5. **Synchronize data:** choose snapshot maintenance-window migration, ongoing replication or an explicitly designed shared-database approach per service. Define the write authority and permissible data loss. A DNS change does not migrate data.
6. **Approve cutover:** display immutable plan revision, source/target, dependency checks, expected downtime, active builds, data checkpoint, traffic mechanism, rollback conditions and source retention period. Production changes require the existing approval policy.
7. **Switch and observe:** record desired traffic separately from observed application and routing health. Execute an idempotent provider-specific routing operation, wait for observation/propagation, and retain source resources. Do not report completion when only a DNS API accepted a request.
8. **Finalize or roll back:** traffic rollback is allowed only if the source is still viable and data write direction is safe. A stale source database must not be reactivated automatically. Decommission requires a separate explicit action after the rollback window.

## Proposed UI and persistence contract

The switch belongs to a migration record with project/environment scope, source and target, plan version, immutable image digest, dependency results, traffic adapter, data checkpoint, actor/approvals, timestamps, error/retry information and audit history. States should distinguish Draft, Preparing, Ready for approval, Switching, Observing, Completed, Failed and Rolled back. The API must persist intent before external operations and reconcile ambiguous responses without issuing duplicate cutovers.

The UI should show current serving destination and separately show the proposed destination. Enable switching only when target checks, authorization, approval, data safety and a configured routing adapter pass. The initial implementation must not present a disabled mock switch as working migration support. The AIO management hub needs its own final cutover: transfer database/GitOps/controller authority without two active AIO worker sets reconciling the same projects.

## Blocking inputs

Destination provider/cluster and access details; scope of “all”; whether both deployments stay available; desired traffic mechanism; database migration/rollback requirements. Existing Azure access details have not yet been supplied. Implementation of dependent switching actions and real migration remain pending these decisions.
