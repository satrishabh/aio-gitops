# Retrospective: GCP → Azure migration (13–15 November 2026)

What we moved, what it cost, every problem we hit and how we fixed it, and a checklist for the next migration.
For the step-by-step procedure use [CLOUD-MIGRATION-RUNBOOK.md](CLOUD-MIGRATION-RUNBOOK.md). This document is
the "what actually happened" companion to it.

## 1. Summary

| | Source (GCP) | Target (Azure) |
|---|---|---|
| Cluster | GKE `cluster-1`, regional, `asia-south1` (Mumbai), 2 × e2-highmem-2 | AKS `creative`, South India, 1 × B2as_v2 (autoscaler 1–2) |
| Database | Cloud SQL `aio-002` (PostgreSQL 18) | In-cluster PostgreSQL 18.7 StatefulSet, one database `aio_rehearsal`, five schemas |
| Ingress | ingress-nginx + GCP load balancer | Traefik (IngressClass `aio-azure`), static IP 52.140.55.184 |
| Images | Artifact Registry `aio` | GHCR |
| Delivery | Argo CD + Tekton + AIO | Same stack, plus GitHub SSO, network policy, sleep-when-idle, webhook buffer |
| Backups | Cloud SQL automatic | Nightly `pg_dump` to an on-cluster disk **and** Azure Blob (restore-tested) |
| DNS | Hostinger (unchanged) | Same records, now pointing at Azure |

**Apps moved:** sarvaikam.com (homepage), Learn, Profile (Keycloak), Blog and AIO.
**Cutover:** 14 Nov. GCP was hibernated the same day and decommissioned on 15 Nov after a checksum-verified archive.

## 2. Cost journey

All figures are monthly **list prices**. GCP figures are estimates, because the monthly bill was never captured as
one number, so confirm against the GCP billing report. Azure figures come from the Azure Retail Prices API.

| Stage | Main cost drivers | ≈ US$/month |
|---|---|---|
| GCP, before migration | GKE regional management fee **73**; 2 × e2-highmem-2 (~75–80 each); Cloud SQL `aio-002` (tier not recorded); load balancer, disks, Artifact Registry | **≈ 250–300+** |
| GCP, after the 14 Nov downsize and hibernation | GKE fee, retained disks, stopped Cloud SQL storage | ≈ 80–90 |
| GCP, after the 15 Nov decommission | One e2-micro tracker VM (free tier) | ≈ 0 |
| Azure, day 1 (14 Nov) | 2 × F2as_v6 (**140 each**), 2 × 128 GB Premium OS disks, load balancer + 2 IPs (~25) | **≈ 330** |
| Azure, now (15 Nov) | 1 × B2as_v2 (**77**), 64 GB OS disk (9), load balancer + IPs (~25), data disks and Blob (~5), Functions (~0) | **≈ 115** |
| Azure, possible next | Same single node in **Central India** (36) with a 1-year reservation (**21**) | **≈ 60** |

**Biggest lessons on cost:**
- **Region matters more than VM type.** The same B2as_v2 costs US$77 in South India, US$36 in Central India and US$55 in East US. We picked South India without comparing.
- **We over-provisioned on day 1.** The 2 × F2as_v6 compute-optimised nodes ran at about 0.25 CPU of real load. Burstable B-series fits a workload that is mostly idle.
- **Node count follows CPU *requests*, not usage.** Apps requested 100–250m CPU while idling at 1–7m. That alone forced a second node, twice.
- **Idle control planes are expensive.** AIO, Argo CD and Tekton now sleep when unused (Sablier) and hard-stop at 23:00 IST.

## 3. Timeline

| When (IST) | Milestone |
|---|---|
| 13 Nov | Cloud-agnostic platform release (multi-cluster, per-cluster ingress), migration plan |
| 14 Nov 00:55–03:00 | Source inventory, Azure preflight, migration tracker |
| 14 Nov 03:11–04:28 | Azure controllers bootstrapped; homepage staged; PostgreSQL rehearsal restore; Learn running on isolated schemas |
| 14 Nov 10:55–11:43 | Private then public Azure ingress; Let's Encrypt; all domains served by Azure with GCP backends |
| 14 Nov 12:13–13:53 | **Native cutover**: final schema sync (row counts and content fingerprints matched), DNS to Azure, GCP hibernated |
| 14 Nov 15:33–22:54 | GitOps adoption, nightly backups + TLS CA, CI tracking, Traefik support, zero-downtime rollouts, Argo SSO, Blob backups, budget/alerts, Cilium network policy |
| 15 Nov 00:08 | GCP decommissioned after a verified archive to Azure Blob |
| 15 Nov 01:35–12:26 | Cost work: single node, sleep-when-idle, nightly stop, webhook buffer, right-sized requests |

## 4. Problems we hit, and the fixes

Each entry gives: **symptom → cause → fix → lesson**.

### Data and databases
- **Proving the data copy.** Before switching writes, both app sides were drained, GCP AIO and Argo paused, and all five schemas streamed. We checked row counts **and content fingerprints** per table (`evidence/native-final-schema-sync.json`). → **Lesson:** fingerprints, not just counts. Keep the previous target schemas (suffix `_before_<timestamp>`) for rollback.
- **No backups on the target at first.** We added a nightly `pg_dump` CronJob to a retained disk, then uploaded to Azure Blob through AKS workload identity, and ran a real restore from Blob. → **Lesson:** backups and a tested restore belong *before* cutover, not after.
- **Postgres internal TLS.** The server certificate needed a stable CA, so cert-manager now runs a CA and issuer (90-day certificate, renewed at 30 days). Clients trust the CA, not the leaf certificate.
- **Never resume the source writers.** Once Azure accepted writes, restarting GCP writers without a reverse sync would have forked the data. We recorded this as a hard rule.

### Delivery (Argo CD, Tekton, AIO)
- **AIO showed "Kubernetes operation failed".** The CI namespace and RBAC were missing on the new cluster, and CI tracking was tied to the delivery-worker flag. We created the `ci` namespace and Tekton RBAC, then split the flag (`AIO_CI_WORKERS_ENABLED` separate from `AIO_WORKERS_ENABLED`).
- **The Azure cluster was not usable in AIO.** It had to be registered with its own ingress class and controller: Traefik on Azure, nginx on GKE. We added per-cluster `ingressClass` and `ingressController` settings, and AIO now writes them into app values.
- **A stale chart package crashlooped AIO.** The prebuilt `.tgz` predated the `extraVolumes` change. → **Lesson:** rebuild chart packages from source at deploy time, never reuse old `.tgz` files.
- **`kubectl diff` hid drift.** Client-side diff doesn't show fields that exist only on the live object. → **Lesson:** use a server-side diff, or compare full pod templates.
- **Kaniko builds hit out-of-memory.** Fixed with `--compressed-caching=false --use-new-run`.
- **Argo SSO admin did not work.** RBAC was bound to an email, but Dex presents a different subject. Bind to the Dex subject. Local admin is now disabled.
- **Webhooks failed after the move** (wrong URL and secrets). Fixed in AIO. Later, while AIO sleeps, pushes got 503 → an Azure Function webhook buffer now catches, wakes and replays them ([AZURE-WEBHOOK-BUFFER.md](AZURE-WEBHOOK-BUFFER.md)).

### Applications
- **Learn's newer image was broken** (nginx config). We kept serving the last good image, pinned by digest. Fixing the newer image is still open.
- **Learn was restarted mid-startup**: the liveness probe fired before its slowest service was up. We added startup allowances and readiness gated on the content API with `successThreshold: 2`.
- **502s during every rollout**: Traefik still sent traffic to terminating pods. A `preStop` sleep drain (8 s) fixed it, and rollouts are now surge-first.
- **Profile outages, twice:**
  1. A node was deleted before checking that Profile had moved off it.
  2. Pods were preempted during the priority-class rollout.
  → **Lesson:** cordon, roll surge-first, verify, then delete. Give always-on apps a PriorityClass (`aio-critical`).

### Ingress, TLS and DNS
- **Cutover order that worked:**
  1. Azure ingress, private first.
  2. A trusted Let's Encrypt certificate per domain.
  3. Azure edge in front of GCP backends.
  4. Native Azure backends.
  5. Lower DNS TTL to 300 s at Hostinger **beforehand**.
  6. Switch the A records.
- **Clients with the old IP cached** were served for a while by GCP forwarding to Azure. We released the GCP IP once both Hostinger nameservers answered with Azure. → **Lesson:** the old IP cannot be recovered after release, so wait for TTL expiry.
- **We caused an outage, 4 min, on 14 Nov.** A Traefik `helm upgrade` with only the base values file replaced the public LoadBalancer with `ClusterIP`. The static IP resource survived and re-attached. → **Lesson:** always pass the public overlay, and run `--dry-run=server` and check the service type first. The values file now carries a warning header.
- **Network policy.** We switched AKS to Azure CNI powered by Cilium and made every app namespace deny ingress by default, allowing only Traefik and same-namespace traffic. Only app namespaces and the backup job may reach PostgreSQL.

### Azure account and platform limits
- **Free Trial subscription:**
  - The hard cap is **4 vCPU per region**, and quota increases are rejected.
  - With 2 × 2-vCPU nodes there was no room for upgrade surge, and the system pool rejects `maxUnavailable > 0`.
  - → **Lesson:** check the subscription type and quotas in preflight. Plan for one node, or upgrade to Pay-As-You-Go before building.
- **Resource providers must be registered** per subscription (`Microsoft.Web` was missing for Functions).
- **The node VM size cannot be changed in place.** We added a new system pool, moved workloads (Deployments surge-first, Postgres restarted in 42 s), then deleted the old pool.

### Sleep-when-idle (Sablier)
- **Sablier 1.19 fails every scan** unless it may list CloudNativePG clusters, even though none exist here. We granted read-only access.
- **The plugin's blocking strategy doesn't work on Kubernetes**, and its waiting page answers 200, which would silently swallow webhooks. Webhooks therefore bypass the gate and go to the Function buffer.
- **Waking needed a second node** because Tekton reserves 100m per component and idles at 1m. We lowered its requests ([`azure-tekton-requests.sh`](../ops/portable/azure-tekton-requests.sh)); re-run it after each Tekton upgrade.
- **New workloads silently added a node.** The ScaleSafe Postgres and Keycloak, deployed 15 Nov, pushed the cluster back to 2 nodes. → **Lesson:** a new always-on workload must state its CPU request against the single-node budget, which is 1.9 CPU allocatable.

### Tooling on Windows
- **Calling `az` from Git Bash:**
  - `az.cmd` breaks on `{}`, `&`, `||`, `==`, `@` and spaces. Use PowerShell with the full path to `az.cmd`, or `az rest --body @file`.
  - Git Bash rewrites `/subscriptions/...` into a path: set `MSYS_NO_PATHCONV=1`.
- **Line endings:**
  - CRLF in captured CLI output broke scripts, including GCP deletes with invisible `\r` in names. Pipe through `tr -d '\r'`.
  - Write files with `newline='\n'`.
- **Paths:** `/tmp` in Git Bash and in Windows Python are different directories. Use one explicit scratch path.
- **Heredocs:** Python inside a heredoc mangles escapes, so write YAML from raw strings or with dedicated tools.
- **Watching for one value:** `az storage` warnings span several lines and fooled `grep -v WARNING` checks. Redirect stderr (`2>/dev/null`) when parsing.

### Decommissioning GCP
- **Archive, verify, then delete.** We kept 16 files (6 Cloud SQL exports, old bucket dumps, a tar of the pre-Cloud SQL disk) in Azure Blob with SHA-256 and MD5 checks *before* deleting anything ([GCP-DECOMMISSION-2026-11-15.md](GCP-DECOMMISSION-2026-11-15.md)).
- **The regional GKE fee (US$73/month) is charged even with zero nodes.** Delete the cluster; don't just scale it to zero.

## 5. Checklist for the next migration

**Before building**
- [ ] Compare **region prices** for the target VM (Azure Retail Prices API or the provider's equivalent) and pick the cheapest region with acceptable latency.
- [ ] Check the subscription type, regional vCPU quota and registered resource providers. A Free Trial means one small node.
- [ ] Size from **measured usage**. Set CPU requests near real idle use plus headroom; the limits carry the bursts.
- [ ] Pick burstable VMs for mostly idle workloads.
- [ ] Decide which control-plane parts can sleep (AIO, Argo CD, Tekton), and route webhooks through a buffer from day 1.

**Before cutover**
- [ ] Backups running and a restore tested on the target.
- [ ] DNS TTL lowered to 300 s at least one TTL period ahead.
- [ ] Per-domain trusted certificates issued on the target.
- [ ] Final data sync verified with row counts **and** fingerprints; source writers and reconcilers paused.

**During changes**
- [ ] `helm upgrade --dry-run=server` and a diff before every ingress-controller upgrade. Check the service type and IP annotations.
- [ ] Node changes: cordon → roll surge-first → verify → drain → delete. Never delete a node first.
- [ ] Stateful pods last, one at a time, with a timed check that the apps reconnect.

**After cutover**
- [ ] GitOps ownership for every app, and no hand-applied drift (record anything live-patched).
- [ ] Budget, cost alerts and a maintenance window.
- [ ] Archive the source with checksums, then delete clusters, databases, disks, registries, buckets, DNS zones and IPs.
- [ ] Write the retrospective (this file) while the details are fresh.

## 6. Still open (15 Nov)
- GCP: all projects shut down 15 Nov (recoverable until ~15 Dec); close billing account 011955-… in the console.
- Upgrade the subscription from Free Trial to Pay-As-You-Go before the trial ends.
- Optional: move the cluster to Central India and buy a 1-year reservation (≈ US$60/month total).
- Have AIO renew its own sleep session while a build is running.
- Fix Learn's newer image (nginx config); patch dependency CVEs (Tomcat, pgjdbc, Spring).
- Adopt Blog and Profile into Argo CD; add `priorityClassName` support to the Helm charts (currently live-patched).

## Related records
[AZURE-MIGRATION-PROCESS.md](AZURE-MIGRATION-PROCESS.md) ·
[NATIVE-AZURE-CUTOVER-2026-11-14.md](NATIVE-AZURE-CUTOVER-2026-11-14.md) ·
[GCP-DOWNSIZE-2026-11-14.md](GCP-DOWNSIZE-2026-11-14.md) ·
[GCP-DECOMMISSION-2026-11-15.md](GCP-DECOMMISSION-2026-11-15.md) ·
[AZURE-DATABASE-BACKUP.md](AZURE-DATABASE-BACKUP.md) ·
[ARGOCD-GITHUB-SSO.md](ARGOCD-GITHUB-SSO.md) ·
[AZURE-CILIUM-NETPOL-2026-11-14.md](AZURE-CILIUM-NETPOL-2026-11-14.md) ·
[AZURE-COST-HIBERNATION-2026-11-15.md](AZURE-COST-HIBERNATION-2026-11-15.md) ·
[AZURE-WEBHOOK-BUFFER.md](AZURE-WEBHOOK-BUFFER.md)
