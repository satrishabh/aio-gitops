# Native Azure cutover — 14 November 2026

The homepage, Learn, Profile Manager, Blog and AIO now serve their public domains
from AKS `creative`, resource group `creative`, South India. The Azure ingress IP
is `52.140.55.184`. This is a native application/database move: the Azure-to-GCP
transition ingress was deleted and its proxy deployment scaled to zero.

## Data and authority

Azure PostgreSQL on persistent Standard SSD storage is now the write authority.
One database, `aio_rehearsal`, contains the schemas `sarvaikam`,
`sarvaikam_reels`, `profile_manager`, `sarvaika_blog`, and `aio_platform`.
Before cutover both app sides were drained, GCP AIO and its Argo application
controller paused, then all five source snapshots streamed and verified.
Every table's row count and content fingerprint matched its exported snapshot.
Evidence: `evidence/native-final-schema-sync.json`.

GCP Cloud SQL backup `1794641232110` succeeded before the freeze. Previous target
schemas remain with suffix `_before_20261114073054`. Source databases are retained.
GCP application writers and Argo reconciliation must remain paused. Once Azure
accepts writes, resuming source writers without a reverse sync loses consistency.

## Routing and checks

All five canonical domains passed trusted HTTPS checks over normal DNS, direct
Azure IP, and the old GCP IP: 15 checks. Existing GCP domain ingress now forwards
to a fixed Azure HTTPS origin with CA/SNI verification for cached DNS clients.
No credentials are included in these forwarding manifests or evidence.

Native Learn passed 14 read-only HTTP checks, including five built UI assets,
10 content categories, readiness and unauthenticated API rejection. All running
Azure pods were Ready. See `evidence/native-public-cutover.json`.
Authenticated user login and visual browser acceptance were not performed.

## Delivery state and remaining work

The user approved the target-local GitHub/CI/repository credential setup and an
Argo API account scoped to the `apps` project. These were configured, and native
AIO restarted successfully. Secrets were not printed or written locally.

**AIO background deployment workers remain disabled** (`AIO_WORKERS_ENABLED=false`).
Native workload GitOps adoption/environment mapping must be completed before
enabling them; source Argo resources/values are not Azure deployment definitions.
Do not enable source reconciliation, run environment repair, or assume a fresh
CI release has been accepted on Azure yet. Existing application images are pinned.
Preserve vulnerability gates; the source homepage scan has failed jobs.

The independent e2-micro tracker is an operator-published observation/planning UI,
not an autonomous migration executor. Its snapshot now records native Azure
serving and the outstanding delivery mapping. No cloud credentials are placed
on the tracker VM. Other cloud adapters and automatic cloud switching remain
separate work.

## Reproducible operator steps

The portable scripts require explicit Azure kubeconfig and context; source
credentials are referenced only by temporary source-local export clients.

1. `native-cutover-control.py freeze` saves original replica counts before pausing
   reconcilers/writers. State is local operator metadata, not a secret.
2. `final-schema-sync.py --execute` refuses active writers/connections, retains
   target schemas, refreshes data, verifies snapshots and restores schema owners.
3. Start native identity, publish its canonical route, then start dependent OAuth
   applications. `activate-native-routes.py --identity-first` handles that route.
4. `activate-native-routes.py` requires all apps and certificates Ready, validates
   trusted public HTTPS, updates cached-DNS forwarding and removes the old bridge.
5. `record-native-observation.py` verifies the 15 HTTPS paths and renders the
   sanitized tracker snapshot/startup metadata locally for publication over IAP.

Do not blindly repeat a freeze/restore against the now-live Azure database.
Post-cutover rollback requires an explicit Azure-to-source data synchronization
under a new write freeze, followed by ingress reversal and source restart.
