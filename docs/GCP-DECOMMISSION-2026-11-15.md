# GCP decommission — 14/15 November 2026

Project `project-d28840cd-be2f-4901-85e` (billing enabled). Azure has been the write
authority since the 14 Nov cutover. The project is kept (empty) at the owner's request.

## Archived first (nothing lost)

Azure Blob `aiocreativebackups` / container **`gcp-archive`** (no lifecycle expiry; 14-day
soft delete), prefix `gcp-project-d28840cd-20261114/`, with `SHA256SUMS` manifest.
All 16 blobs verified against local files by MD5 after upload.

| Archive | Source |
| --- | --- |
| `cloudsql-aio-002/aio-002_<db>_20261114.sql.gz` (aio, profile_manager, sarvaika_blog, sarvaikam, sarvaikam_reels, postgres) | Fresh `gcloud sql export` of Cloud SQL `aio-002` (instance started ~10 min with all GCP writers at zero, then stopped) |
| `cotent_bucket/db-backup-20261114/*.sql.gz` | Earlier dumps that were the only contents of `gs://cotent_bucket` |
| `disk-data-aio-postgres-0/data-aio-postgres-0.tar.gz` (+ `.sha256`, `listing.txt`) | Read-only tar of the 10 GB PD `data-aio-postgres-0` (AIO PostgreSQL **16** data dir from before Cloud SQL, 23 Oct) via a temporary VM |

Restore a SQL export: `gunzip -c file.sql.gz | psql -d <db>`. Restore the old PG16 data
dir: extract into an empty volume and run `postgres:16` with `PGDATA` pointing at `pgdata/`.

## Deleted

GKE `cluster-1` (regional, ~US$73/month management fee not covered by the free credit);
Cloud SQL `aio-002` (+ its 11 backups, archived above); 8 unattached PDs (7 x 2 GB + 10 GB);
Artifact Registry `aio` (1.26 GB, images now on GHCR); buckets `cotent_bucket`,
`<project>_cloudbuild`, `663499455283-global-cloudbuild-logs`; Cloud DNS zone
`aio-sarvaikam-app` (never delegated; `sarvaikam.com` is on Hostinger); temporary
archive bucket and VM.

## Remaining

Only `aio-migration-tracker` (e2-micro, us-central1-a, no public IP) and its 10 GB
pd-standard disk — within the GCP free tier. Expected saving: roughly US$80–90/month
(list-price estimate; confirm in GCP Billing).
