# Azure database backups and TLS — 14 November 2026

Applies to the live Azure PostgreSQL `aio-validation/postgres-rehearsal`
(database `aio_rehearsal`, schemas `sarvaikam`, `sarvaikam_reels`,
`profile_manager`, `sarvaika_blog`, `aio_platform`). Azure is the write authority.

## Backups

Manifest: `ops/portable/azure-postgres-backup.yaml`.

| Item | Value |
| --- | --- |
| Schedule | CronJob `postgres-backup`, 02:30 Asia/Kolkata daily |
| Content | `pg_dump -Fc` of `aio_rehearsal` (excludes `*_before_20261114073054`) + `pg_dumpall --globals-only --no-role-passwords` |
| Target | PVC `postgres-backups`, 8 GiB StandardSSD, reclaim **Retain** |
| Retention | 14 days; each dump checked with `pg_restore --list` before it is kept |
| Extra | Job also runs `pg_reload_conf()` so a renewed server certificate is served |
| One-off snapshot | VolumeSnapshot `postgres-data-20261114-pre-backup` (class `azure-disk-retain`, Retain) |

Verified 14 Nov 2026: first dump `aio_rehearsal-20261114T101851Z.dump` (648 KB);
restore test into scratch database `restore_check` returned 139/139 tables and
5102/5102 rows identical to live; scratch database dropped.

### Off-cluster copy (Azure Blob) — added 14 Nov 2026

Each run uploads that night's dump and globals file to storage account
**`aiocreativebackups`** (resource group `creative`, Standard_GRS, Cool tier),
container **`postgres-backups`**:

- No public access, **shared-key access disabled** (Entra ID only), HTTPS, TLS 1.2+.
- 14-day soft delete for blobs and containers; lifecycle rule deletes blobs after 30 days.
- Upload uses AKS workload identity: ServiceAccount `aio-validation/postgres-backup` →
  managed identity `id-aio-postgres-backup` (federated credential
  `aks-creative-postgres-backup`), role *Storage Blob Data Contributor* on **that
  container only**. No keys exist anywhere.
- Each blob carries its SHA-256 as metadata (`sha256`); uploads never overwrite.

Verified 14 Nov 2026: dump `aio_rehearsal-20261114T145604Z.dump` downloaded back from
Azure, checksum matched, restored into a scratch database: 139/139 tables; the only
row differences were 2 Keycloak offline sessions that expired after the dump.

Restore from Azure: run a pod as ServiceAccount `postgres-backup` with label
`azure.workload.identity/use: "true"`, `az login --service-principal --federated-token`,
`az storage blob download --auth-mode login --account-name aiocreativebackups -c
postgres-backups -n <blob>`, check `sha256` metadata, then `pg_restore` as below.
(Your own user needs a *Storage Blob Data Reader* role to download from a laptop.)

### Run a backup now

```sh
kubectl -n aio-validation create job postgres-backup-manual-$(date +%s) --from=cronjob/postgres-backup
```

### Restore

Role passwords are not in the globals file; app role passwords live in their
Kubernetes Secrets. Never restore over the live database while apps write to it.

1. Run a job/pod that mounts PVC `postgres-backups` (read-only), the
   `postgres-rehearsal-admin` secret and the `rehearsal-db-trust` ConfigMap, using
   the same postgres image digest as the server.
2. Restore into a new database first and compare row counts:
   `createdb restore_check && pg_restore -d restore_check --exit-on-error <dump>`.
3. For a real recovery: scale writers to zero, rename the affected schema aside,
   `pg_restore -d aio_rehearsal -n <schema> <dump>`, verify, restart writers.

## TLS CA rollover (done 14 Nov 2026)

Manifest: `ops/portable/azure-postgres-tls-ca.yaml`.

Before: the server used a self-signed leaf, and every client trusted that exact
leaf. The first renewal (6 Dec) or expiry (13 Dec) would have broken every
`verify-full` connection.

Now:

- `postgres-internal-ca` (ECDSA P-256, 10 years, expires 2036-11-11) issues the
  server certificate through Issuer `postgres-ca`.
- Server leaf: 90 days, renews 30 days early; the nightly backup job reloads Postgres.
- `rehearsal-db-trust` (`ca.crt`) in `dev`, `aio-platform` and `aio-validation`
  holds the **old self-signed leaf + the new CA**.
- Learn was rolling-restarted (Node reads `NODE_EXTRA_CA_CERTS` only at start).
- Verified: CA-only `verify-full` connection succeeds; after closing idle pool
  connections, AIO, Blog, Learn and Profile reopened 13 TLS connections, with no
  TLS errors in their logs.

Rollback copy of the previous server keypair: Secret
`aio-validation/postgres-rehearsal-tls-rollback-20261114`. If needed, point the
Certificate back at `postgres-rehearsal-selfsigned`, restore that keypair into
`postgres-rehearsal-tls` and run `SELECT pg_reload_conf()`.

Follow-up: after 2026-12-13 remove the old leaf from the three trust bundles
(keep only the CA) and delete the rollback Secret.
