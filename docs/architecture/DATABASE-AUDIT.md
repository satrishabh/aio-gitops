# AIO database and platform audit

Audit date: **2026-11-08**. This separates observed production facts from proposed remediation. No production grants, ownership, schema, or infrastructure were changed by this audit.

## Evidence and limits

Reviewed backend entities, repositories, Flyway V1–V7, security configuration, Helm values, Tekton tasks, RBAC, and bootstrap scripts. Ran [read-only audit SQL](../../ops/database/audit.sql) using an ephemeral PostgreSQL client Job and the existing runtime credentials. The Job and ConfigMap were deleted afterward. [Captured aggregate results](audits/2026-11-08-database.txt) contain catalogs/counts, not user records or credentials. Table row estimates are statistics; the explicit integrity/ownership counts are exact at the audit time.

Read-only cloud/Kubernetes checks identified GKE `cluster-1` in `asia-south1`, project `project-d28840cd-be2f-4901-85e`, AIO image `0.13.0` with one replica, and Cloud SQL `aio-002`. This is a point-in-time review, not a load test, penetration test, exhaustive IAM/network review, or restore rehearsal. Workload application databases, including Learn's own data, were not audited.

## Observed database

| Item | Observation | Consequence |
| --- | --- | --- |
| Engine / size | PostgreSQL 18.6; `aio` is 9446 kB | Small dataset; prioritize correctness/recovery before sharding or extra services |
| Schema | Flyway V1–V7 successful | Next additive change is V8; never edit applied migrations |
| SQL availability | ZONAL, `db-custom-2-8192` | Backups do not provide automatic zonal HA; assess RTO against REGIONAL cost |
| Recovery | Backups enabled, 7 retained; PITR enabled, transaction logs retained 7 days | Configuration verified; actual restore and business RPO/RTO remain unverified |
| SQL networking | Public IPv4 enabled; no private VPC network in selected settings | Does not prove public access is unrestricted. Audit authorized networks, SSL policy, proxy clients, and firewall separately |
| Runtime role | Not superuser; **CREATEDB and CREATEROLE enabled**, CREATE in `public` | Excessive runtime privileges; split migration/runtime duties after dependency review |
| Identity | 8 projects have NULL `owner_subject`; standalone scans missing owner: 0 | Legacy projects remain admin-only under fail-closed guards; backfill only from verified identity mappings |
| Audit attribution | 298 audit rows have NULL actor subject | Includes legitimate system events. Classify by source; never invent a human actor |
| Referential indexing | `promotions(project_id)` is the only FK lacking a full leading non-partial index | Candidate additive migration, with query-plan evidence; no current measured bottleneck |
| Rerun references | No missing referenced run IDs found | Rerun IDs lack database FKs; define intended deletion/retention before adding constraints |
| Database RLS | Disabled on application tables | Application ownership guards are the current isolation boundary; pooled connection RLS requires a separately tested design |
| Archived logs | 166 non-null run-step logs, 260828 bytes | No present storage pressure; define redaction/retention before growth |

Largest tables by estimated rows: `run_steps` 333, `audit_log` 322, `deployments` 80, `ci_runs` 64, `env_status` 24, `projects` 8. `app_users` has 5 rows; `promotions` 1; `domains` 2; scan tables 0. Dead tuples were present in frequently updated tables; this small snapshot does not justify VACUUM FULL, reindexing, or changing autovacuum settings.

## Data ownership and lifecycle

| Proposed owning module | Existing tables | Migration concern |
| --- | --- | --- |
| Identity/access | `app_users` | Keycloak remains identity authority. Preserve issuer/subject semantics and client-role policy; do not equate GitHub login with OIDC subject |
| Projects | `projects` | Source repository is not the unique app identity. Preserve Learn versus homepage, per-env GitOps path, and owner subjects |
| Delivery | `ci_runs`, `run_steps`, `env_status`, `deployments`, `promotions` | Project deletion currently cascades history. Agree retention/tombstones before extraction; preserve approvals and exact image references |
| Security scanning | `scan_runs`, `scan_steps`, `scan_findings` | Project deletion sets scan project ID NULL. Test whether owner subject still permits intended access afterward |
| Domains | `domains` | Shared hosts, routes, cert ownership, namespace and environment must survive migration together |
| Audit | `audit_log` | Preserve system events and actor identity. Define retention/legal requirements and append-only access before a separate service |

Current tables share a schema and JPA relationships. The boundaries above are a design proposal. No service owns an independent database yet. Environment variables are stored as text JSON; do not treat that field as a secret vault. Review existing values without exporting them into reports and move credentials to Secret references. Full logs, findings, backups and dumps require access controls and redaction appropriate to their content.

## Other operational findings

| Priority | Finding | Recommended work / acceptance gate |
| --- | --- | --- |
| P1 | Runtime role has role/database administration rights | Inventory role ownership, grants and dependent automation; rehearse least-privilege roles on a clone; separate Flyway credentials before revocation |
| P1 | Legacy ownership cannot be inferred safely | Admin-approved mapping to trusted issuer/subject, targeted transactional backfill, cross-user regression checks; unknown owners remain admin-only |
| P1 | Reconciliation is not a durable workflow | External effect can succeed before local result saves; implement idempotency and durable operation/step records, then restart fault tests |
| P1 | Ingress NGINX maintenance ended in March 2026 | New stack uses maintained Traefik NGINX compatibility provider; test annotation behavior before moving any existing hostname. [Kubernetes statement](https://kubernetes.io/blog/2026/01/29/ingress-nginx-statement/) |
| P1 | Production resources are not tracked by the new Terraform state | Use isolated fresh names first; import/adopt existing resources only through a reviewed zero-replacement plan |
| P2 | One AIO replica; scheduled watchers have no distributed claim | Keep one until durable jobs/leases and coordination tests permit more |
| P2 | Promotion DONE represents requested GitOps/sync work | Distinguish commit accepted, reconciling and healthy; business completion must reflect rollout observation |
| P2 | Cleanup can discard rows despite external cleanup warnings | Track deletion intent and retained evidence; compensate only owned resources |
| P2 | Existing cluster RBAC permits cluster-wide Secret operations and namespace lifecycle | New stack scopes writes to known shared environments; legacy namespace deletion becomes an operator task |
| P2 | Internal Argo client skips certificate validation | Configure a trusted CA for the Argo endpoint and verify Java trust before switching `ARGOCD_INSECURE` off |
| P2 | CI uses hostPath Trivy cache and root-capable image build steps | Treat CI as trusted privileged infrastructure; isolate runners/node pool and credentials before onboarding untrusted users |
| P2 | Cloud bootstrap defaults differ from live SQL version/settings | New Terraform is the fresh-environment path; do not run legacy bootstrap against Terraform-owned resources |
| P2 | CI/build inputs include older images, dynamic downloads, and `npm install` | Inventory support/CVEs, pin reviewed digests/tool downloads, switch to reproducible lockfile installs in a separate tested code change |
| P2 | Monitoring and restore procedures not demonstrated | Add alerts/dashboards for DB capacity/pool waits, backup failures, stalled CI/workflows, Argo drift, certificate expiry; rehearse restore |

The new foundation defaults to private-only PostgreSQL, REGIONAL availability, IAM authentication and per-consumer identities. IAM login still requires database grants; Cloud SQL IAM does not replace SQL privileges. Current AIO runs Flyway at startup, so its fresh-environment identity temporarily needs schema CREATE, but no CREATEDB/CREATEROLE. Separating migration and runtime identities remains application follow-up. [Cloud SQL IAM authentication](https://docs.cloud.google.com/sql/docs/postgres/iam-authentication).

## Changes to prepare, not silently apply

1. Add an index on `promotions(project_id)` in the next reviewed migration if retained under the final schema. Review EXPLAIN plans and lock behavior on a restored copy.
2. Inventory project ownership via trusted OIDC records, including issuer; obtain business mapping before any backfill. Do not assign all legacy projects to the current admin.
3. Separate Flyway ownership/DDL credentials from runtime DML credentials. Explicit grants must cover tables/sequences and future default privileges from the migration owner. Verify startup, reads, writes, scheduling, and rollback before removing elevated rights.
4. Define audit/log retention, restore objectives and project-deletion behavior. Introduce operation/outbox tables only alongside executable transaction/recovery tests.
5. Follow the [migration runbook](MIGRATION.md); record measured rehearsal results and outstanding risks before production cutover.
