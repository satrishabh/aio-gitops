# Migration and future microservice extraction

This is an execution runbook and proposed target design, not a claim that services, outbox delivery or circuit breakers have been implemented. Start with the [current flow](README.md), [audit evidence](DATABASE-AUDIT.md), and [fresh-environment Terraform instructions](../../infra/README.md).

## Boundaries that keep extraction possible

Use a modular monolith first: `identity`, `projects`, `delivery`, `scanning`, `domains`, and `audit`. Each owns its use cases, domain state, repositories and integration ports. Controllers depend on use-case interfaces; another module cannot write the owning module's repository. Keep API DTOs separate from JPA entities. Shared code is limited to contracts and small primitives, not a shared entity/repository library. Add architecture dependency checks when package boundaries are introduced.

| Boundary | Public contract | Possible later deployment |
| --- | --- | --- |
| Identity | Verified actor, issuer/subject, policy decision | Existing Keycloak plus local policy adapter; avoid copying an identity provider |
| Projects | Project ID, owner, environment binding, lifecycle commands | Project registry, only if independent ownership/lifecycle warrants it |
| Delivery | Start build, request promotion/rollback, operation status | Worker/orchestrator often has a stronger independent scaling case than CRUD |
| Scanning | Request scan; publish immutable report/reference | Separate worker/service for resource isolation and independent scanner upgrades |
| Domains | Reconcile desired routes/cert ownership | Extract after per-host coordination and route contracts stabilize |
| Audit | Append event, scoped query | Append-only consumer/read model after durable event delivery exists |

Services may initially share a PostgreSQL instance to control cost, but extracted services need separate databases/users and migrations. Never grant a future audit worker write access to delivery tables. The Terraform `database_consumers` map provisions additional identity/database slots; it does not split existing tables or implement data replication. Migrations/SQL grants and service deployments remain explicit steps.

## Contracts and orchestration

Keep Tekton responsible for CI tasks and Argo responsible for desired-state reconciliation. AIO's application orchestrator records business intent and outcome. Use Command/Handler for use cases, Repository for owned persistence, ports/adapters for external clients, explicit state transitions for workflow steps, and selective saga compensation for reversible owned effects. Introduce an interface only where a boundary or interchangeable policy warrants it.

Proposed event envelope:

```json
{
  "eventId": "uuid",
  "eventType": "delivery.deployment.observed.v1",
  "occurredAt": "2026-11-08T10:00:00Z",
  "aggregateId": "deployment-id",
  "aggregateVersion": 7,
  "operationId": "uuid",
  "correlationId": "uuid",
  "causationId": "uuid",
  "actor": {"issuer": "https://identity.example/realms/aio", "subject": "immutable-subject", "kind": "HUMAN"},
  "payload": {"projectId": 42, "environment": "dev", "imageDigest": "sha256:...", "outcome": "HEALTHY"}
}
```

Use a durable local transaction for intent + outbox, at-least-once delivery, consumer inbox deduplication, bounded retries, dead-letter/replay tooling and contract version compatibility. Preserve aggregate ordering with versions; quarantine gaps/conflicts rather than assuming global event order. Record explicit SYSTEM actors for automation. Carry original actor/authorization basis, but revalidate authority for new side effects. Never put credentials, full logs or broad personal profiles into events.

Start with a database-backed dispatcher. Add a broker only when workload/delivery requirements justify it; do not introduce Kafka and a service mesh merely to create boundaries. An extracted service uses its own outbox/inbox; there is no transaction spanning PostgreSQL, GitHub and Kubernetes. No exactly-once claim is valid for those external effects.

Circuit breakers belong on independent dependency/client operations, with bounded connection/read/overall deadlines and concurrency bulkheads. Retry only classified transient failures within the operation budget; honor rate-limit headers. A timeout after a write is an unknown outcome: look up the deterministic external reference before resubmitting. Authentication/authorization, invalid requests and scan-policy failures do not count as transient dependency outages. UI fallbacks expose stale/unknown status and cannot authorize deployment. Detailed placement and saga safeguards are in the [engineering blueprint](README.md).

## Database migration sequence

### 1. Inventory and establish recovery

- Record immutable source/image commits, Flyway versions/checksums, DB engine/extensions/collation/timezone, grants/role ownership, database flags, backup settings, row counts, sequences, FK/index definitions and intended RPO/RTO. Run `ops/database/audit.sql` again with read-only credentials; the stored audit is not a current inventory forever.
- Inventory GitOps repo/branch/path, Argo destinations, registry pull credentials, custom-domain certificate owner and Secret references. Learn is `sarvaikam`; the homepage is `sarvaikam-home`. Preserve `apps/sarvaikam/values-dev.yaml` `envFrom` pointing to `sarvaikam-profile-runtime` in its actual workload namespace. Terraform AIO bootstrap does not recreate that application Secret.
- Verify a successful backup, then restore/PITR into a separate instance. A backup listing alone does not demonstrate recoverability. Time the restore and validate application behavior on an isolated repo and host with outbound writes disabled by network/credential isolation.
- Agree a write freeze and rollback decision window. Current AIO has scheduled side effects at startup; **do not start a restored copy with live GitHub/Argo tokens or the live GitOps repo**.

### 2. Rehearse a PostgreSQL 18 logical transfer

Use PostgreSQL 18 client tools from a trusted runner that can reach the source and target. Store credentials through a protected service/passfile or approved connector, never command history/URLs or repo files. Encrypt dumps and tightly restrict access; they contain user data and potentially logs/configuration.

```bash
# SOURCE_SERVICE and TARGET_SERVICE are pg_service.conf entries with protected credentials.
pg_dump --dbname="service=$SOURCE_SERVICE" --format=custom --no-owner --no-acl --file=aio.dump
pg_restore --dbname="service=$TARGET_SERVICE" --no-owner --no-acl --exit-on-error aio.dump
psql "service=$TARGET_SERVICE" -v ON_ERROR_STOP=1 -f ops/database/audit.sql
```

Use a **new empty target database** for restore. Do not start AIO/Flyway first, and do not use `--clean` against an occupied environment. A single-database pg_dump is transactionally consistent, but writes after its snapshot are absent. The final cutover dump therefore requires the write freeze, or a separately designed logical-replication catch-up. Compare exact counts, references, constraints, sequences and Flyway checksums; confirm restored object ownership and grant runtime access explicitly. No broad global-role dump should be restored blindly into Cloud SQL.

For an existing-data restore, the fresh IAM user's CREATE/USAGE grants alone do not allow access to tables owned by the restoring administrator. Use the intended migration role as object owner; grant runtime SELECT/INSERT/UPDATE/DELETE on required tables, USAGE/SELECT on required sequences, and matching default privileges **for that owner**. Test the exact IAM login from a pod. Flyway must use an owner/migration identity if it needs ALTER rights. Introduce separate `spring.flyway` credentials before constraining the runtime identity to DML.

### 3. Expand, backfill, switch, contract

1. Add V8+ changes additively with old/new binary compatibility. Do not rewrite V1–V7. Pilot durable operation/outbox changes in one workflow.
2. Backfill in bounded restartable batches with trusted identity mappings and checkpoints. Keep unknown legacy owners admin-only. Do not infer ownership from a display name.
3. Compare old/new reads on a clone. Validate indexes with representative EXPLAIN plans, uniqueness, nullability, timezones, sequence next-values and retention behavior. Run security/recovery tests before constraints become stricter.
4. Deploy compatible code and observe. Only remove old columns/code after the rollback window and all readers/consumers have migrated.
5. Never run independently writing old/new workflow schedulers against a shared database. Keep one dispatcher until leases/idempotency are proven.

## Infrastructure/data cutover

| Gate | Required evidence |
| --- | --- |
| Recovery | Successful restore rehearsal, measured RPO/RTO, protected backup/dump, rollback owner |
| Target infra | Reviewed Terraform plans, private SQL connectivity/IAM grants, secrets delivered, controller versions ready |
| Identity | Correct issuer/client roles/callbacks; immutable owner behavior; ordinary user cannot enumerate another user's runs/logs/audit |
| Delivery | Sample safe project clone/build/scan/dev deploy; commit/image digest exact; GitOps change only in target repo; rollout readiness observed |
| Routing | HTTP redirect, public trusted TLS, OIDC callback, SSE/log streaming, path rewrite, www/shared host/cert ownership and large request behavior tested |
| Preservation | Runtime envFrom/probes/resources retained; Learn and homepage remain separate; risk exception cannot spread beyond approved dev image |
| Operations | Stalled CI/Argo, SQL pool, backup, certificate and error alerts tested; operator recovery exercise recorded |

During final cutover, stop admission and all writers/schedulers to the old DB, finish or record in-flight external work, take the final snapshot/dump, restore and validate, then start **one** target AIO instance with target credentials/repo. Move DNS/webhooks/OIDC URLs in a controlled window. Preserve old resources for the agreed rollback window. DNS rollback alone is insufficient if external deployment intent or the target DB has changed.

Rollback before target writes can restart the old environment after verifying external state. After target writes begin, freeze again and reconcile changes back through a reviewed data plan; never point old and new writers at diverged databases. Additive schema permits binary rollback only if data/state transitions remain compatible. Disable ingress to the unwanted instance and revoke its side-effect credentials before resuming the chosen writer.

## Extract one service at a time

Choose extraction based on independent scaling, failure isolation or team ownership. Audit or scan processing are candidates after contracts/outbox exist. For each: define contract and SLO; allocate identity/database; backfill a consistent snapshot; replay/checkpoint events; compare a shadow read model; cut over commands with a single writer; monitor lag/errors; retain a rollback path; remove cross-boundary SQL/FKs only after the contract takes ownership. Do not dual-write two databases from an HTTP request. Separate data ownership must precede independent deployment claims.

## Required follow-up implementation

The infrastructure files do not implement durable operation/outbox tables, dispatcher claims, client breakers, separate Flyway credentials, database RLS, retention enforcement, CI sandboxing, or observability dashboards. Deliver these in the phased order in the [blueprint](README.md#11-phased-delivery-and-completion-criteria), with fault/recovery and authorization tests. Record each migration rehearsal, dependency/version review and deployment acceptance result in a dated audit entry.
