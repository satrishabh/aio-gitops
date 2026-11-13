# Low-cost Azure PostgreSQL restore rehearsal

## Cost decision

The user requested the cheapest suitable alternative. Existing AKS has spare capacity, so a small PostgreSQL StatefulSet avoids provisioning a separate managed database or VM. At the observed load the existing two nodes remained sufficient; adding workloads must not silently cause autoscaling when estimating cost.

South India public USD consumption prices checked on 2026-11-14 through the [Azure Retail Prices API](https://prices.azure.com/api/retail/prices):

| Alternative | Observed meter | Additional monthly cost estimate |
| --- | --- | --- |
| PostgreSQL on existing AKS, 4Gi Standard SSD | E1 LRS Disk | $0.30 disk, plus transactions |
| PostgreSQL on existing AKS, minimum 32Gi Standard HDD | S4 LRS Disk | $1.536 disk, plus transactions |
| Managed PostgreSQL B1MS | $0.0245/hour compute | $17.885 at 730 hours, plus database storage/backup |

These are incremental retail estimates, not a promise of a zero bill or account-specific quote. Existing AKS nodes, source GCP services, cross-cloud egress, retained disks, tax and any additional autoscaled capacity are separate. No new VM, ACR, public IP, load balancer or managed PostgreSQL server was created for this rehearsal. See [managed PostgreSQL pricing](https://azure.microsoft.com/en-us/pricing/details/postgresql/flexible-server/) and [disk tiers](https://learn.microsoft.com/en-us/azure/virtual-machines/disks-types).

External free-tier alternatives were also checked: [Neon Free](https://neon.com/blog/neon-free-plan-1-gb-per-project) advertises 1GB per project, while [Supabase Free](https://supabase.com/kb/faqs/platform/database-only-cost) includes a 500MB database with shared resources and pauses after a week of low activity. These require separate vendor accounts, acceptance of usage limits, connectivity and PostgreSQL version/extension checks. Neither account was supplied or provisioned. The installed AKS rehearsal keeps the target in the requested Azure account with no extra compute instance; it is not the only possible low-cost PostgreSQL hosting choice.

## Provisioned and verified

Namespace `aio-validation`, StatefulSet/pod `postgres-rehearsal`/`postgres-rehearsal-0`, private headless service `postgres-rehearsal`, PostgreSQL 18.7 from a pinned official image. A new random administrator password is held only in a target Kubernetes Secret, not in Git or this document. The 4Gi claim uses `StandardSSD_LRS` and a dedicated Retain StorageClass. Retain prevents automatic disk removal with the claim; retained disks continue billing and require an explicit later cleanup decision.

The server requires TLS for all TCP connections and SCRAM password authentication. The internal certificate was issued by the target's namespaced self-signed issuer; clients must trust its certificate explicitly and use `sslmode=verify-full`. This is not a public/browser-trusted certificate. Local Unix-socket administrative access is confined to the pod and Kubernetes exec authorization.

Passed: startup/readiness, TLS with hostname/certificate verification, rejection of unencrypted TCP, synthetic `pg_dump`/`pg_restore`, and reading restored synthetic data after pod deletion/recreation. Only synthetic databases `rehearsal` and `rehearsal_restore_probe` were present beyond the default databases. This is a single-instance test target, not a claim of production HA or tested off-cluster backups. Certificate/key rotation must include a database certificate reload or controlled restart before expiry.

## Source compatibility and prepared transfer

Learn's running application image contains `pg_dump` 17.12, which cannot dump the PostgreSQL 18 source. A temporary source-side PostgreSQL 18 client was therefore created using references to existing source-namespace Secret keys; no source password was exported. It was removed after metadata checks.

Observed source database sizes: `sarvaikam` 9,139,903 bytes, `sarvaikam_reels` 8,099,519 bytes. Extensions: Learn `plpgsql`, `uuid-ossp`; reels `plpgsql`. These fit the guarded rehearsal allowance. No archive or application records were written to local files or Git.

Automatic approval review initially rejected real-data transfer pending explicit authorization. The user then requested schemas instead of separate databases and explicitly confirmed including data. The executed operation was:

- Consistent `pg_dump -Fc` from GCP Cloud SQL `aio-002`, databases `sarvaikam` and `sarvaikam_reels`.
- Binary streams through the authenticated operator's Kubernetes connections into ONE new Azure database `aio_rehearsal`, schemas `sarvaikam` and `sarvaikam_reels`, on `aio-validation/postgres-rehearsal-0`, AKS `creative`, subscription `150909ba-4c23-44e9-b98d-e8a2d433f709`. Separate application databases were not created.
- Source stays writable and authoritative. No production connection, route or traffic is changed. Source passwords remain in GCP. Application data, including any user records, will be present in the Azure rehearsal databases; Kubernetes/database administrators with sufficient access can read them.
- The schema-consolidation script refuses existing destinations, unsupported extensions, extra source schemas, custom functions, large objects and oversized databases. Each dump and its row/content validation use the same exported read-only source snapshot. Each restore is transactional; the complete two-schema operation is not one global transaction. Failed or ambiguous runs retain the target for inspection and must not be treated as accepted migration or automatically overwritten.

Executed with `ops/portable/stream-schema-rehearsal.py --kubeconfig <isolated-file> --execute` after metadata preview and authorization. The legacy separate-database script now refuses execution. Native `ALTER SCHEMA` moved objects; no SQL text or COPY payload was rewritten. Source audit confirmed no custom functions, additional schemas or large objects requiring special remapping. The temporary exporter was deleted after verification.

## Verified schema-and-data snapshot

Completed at `2026-11-13T22:23:11Z` (2026-11-14 in India):

| Azure database | Schema | Tables | Total rows across tables | Validation |
| --- | --- | --- | --- | --- |
| aio_rehearsal | sarvaikam | 14 | 254 | Every table's count and content fingerprint matched its source snapshot |
| aio_rehearsal | sarvaikam_reels | 2 | 1 | Every table's count and content fingerprint matched its source snapshot |

Reels' one row is migration history; the `reels` table was empty in the source snapshot. Restored indexes and constraints are valid; neither application schema grants access to PUBLIC. No tables remain in `public`. The `uuid-ossp` extension moved into `sarvaikam`. The evidence file stores only table names, counts and comparison results, not row contents or content digests.

Sources remain live and writable on GCP. These are independently consistent snapshots of two databases, not ongoing replication or a globally synchronized cutover checkpoint. Applications are not yet connected to these schemas. Before staging them, create schema-scoped application identities, configure main/reels search paths and Flyway default schemas (for example JDBC `currentSchema=sarvaikam` or `currentSchema=sarvaikam_reels`), mount the TLS trust certificate, resolve GCS dependencies, and run isolated application acceptance. A final migration needs a new consistent checkpoint after handling live writes.
