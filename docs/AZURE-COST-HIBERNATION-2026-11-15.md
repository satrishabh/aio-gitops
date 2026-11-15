# Azure cost reduction: single node + AIO hibernation (2026-11-15)

**Result:** AKS `creative` went from 2 × Standard_F2as_v6 to **1 × Standard_B2as_v2**, and the AIO control plane
(AIO, Argo CD, Tekton) now **sleeps when unused**. Public sites stay always on.

| | Before | After |
|---|---|---|
| Nodes | 2 × F2as_v6 (2 vCPU / 8 GiB) | 1 × B2as_v2 (2 vCPU / 8 GiB, burstable), autoscaler 1–2 |
| Compute, list price South India | ≈ US$280 / month | ≈ US$77 / month (2nd node only while a build needs it) |
| OS disks | 2 × 128 GB Premium | 1 × 64 GB Premium |
| vCPU used of the 4-core trial cap | 4 / 4 (no room for upgrade surge) | 2 / 4 (surge upgrades possible again, `maxSurge 1`) |

Load balancer, public IP, data disks and Blob storage are unchanged (≈ US$25 / month). Prices are list prices from
the Azure Retail Prices API. Confirm in Cost Management after a few days.

## How hibernation works

```
browser ─► Traefik ─► sablier-aio middleware ──(group "aio" asleep)──► "waking up" page, scale group to 1
                                    │                                    (≈ 2 min on B2as_v2, page auto-refreshes)
                                    └──(awake)──► AIO / Argo CD; every request renews a 30 min session
Sablier 1.19.1 (namespace sablier) scales group "aio" to 0 when the session expires.
```

- **Gated routes:** `app.sarvaikam.com` (middleware `aio-platform/sablier-aio`) and `argocd.sarvaikam.com`
  (`argocd/sablier-aio`). Either one wakes the whole group, because AIO needs Argo CD and Tekton.
- **Group `aio`** (labels `sablier.enable=true`, `sablier.group=aio`):
  - `aio-platform/aio-platform`;
  - every Argo CD Deployment and the `argocd-application-controller` StatefulSet;
  - `tekton-pipelines-controller`, `tekton-events-controller`, `tekton-pipelines-remote-resolvers`.
- **Always on:**
  - Traefik, cert-manager, PostgreSQL, the backup CronJob;
  - Learn, home, Blog, Profile, scalesafe-site;
  - Sablier itself;
  - `tekton-pipelines-webhook`. The API server calls it to convert Tekton custom resources, and with it scaled to zero, listing those resources fails cluster-wide.
- **Crawlers and probes** (bot, crawl, spider, curl, wget, uptime and similar user agents) get an empty `200` and never wake the group.
- **Anything started outside Sablier**, such as a `helm upgrade` of AIO or a `kubectl scale`, gets a 30 min session and then sleeps again (`--provider.auto-warm-externally-started`).
- **Sablier restarts don't stop running workloads** (`--provider.auto-stop-on-startup=false`).
- **Measured on 2026-11-14:** first request returned the waiting page; AIO served the real app after **126 s**.

### GitHub webhooks while AIO sleeps

`/api/webhooks/*` has its own Ingress (`native-aio-webhooks`, priority 90) **without** the gate. The Traefik plugin's
blocking strategy does not work on Kubernetes, and the waiting page would answer `200` and silently drop the event.

While AIO sleeps, Traefik answers `503`, so GitHub records a **failed delivery**. To recover it, open AIO, then either
redeliver it from the repository's *Settings → Webhooks → Recent deliveries*, or click **Run** on the project.

The planned Azure Function webhook buffer will replace this. It will answer GitHub at once, wake AIO, and replay the events.

**Known limit:** if every browser tab is closed during a long build, the group can sleep after 30 min while the
build is still running. The build pods keep running, but Tekton stops advancing the pipeline until the next wake.
Keep a tab open during long builds. A follow-up will have AIO renew its own session while a run is active.

## Nightly hard stop (23:00 IST)

CronJob `sablier/hibernate-aio-nightly` (`ops/portable/azure-hibernation-nightly.yaml`, `timeZone: Asia/Kolkata`)
scales group `aio` to zero every night at 23:00 IST, even with an active session. The group stays asleep until someone opens
app.sarvaikam.com or argocd.sarvaikam.com.

Tested on 2026-11-14: all 11 workloads went to 0, stayed at 0, and a visit woke AIO after 48 s. A browser tab left open
on AIO keeps polling and will wake it again.

```bash
# run it now / pause it
$K -n sablier create job hibernate-now --from=cronjob/hibernate-aio-nightly
$K -n sablier patch cronjob hibernate-aio-nightly -p '{"spec":{"suspend":true}}'
```

## Operations

```bash
K="kubectl --kubeconfig D:/2026/AIO/.tools/azure-creative.kubeconfig --context aio-azure-creative"
# wake without a browser
curl -s -A "Mozilla/5.0" https://app.sarvaikam.com >/dev/null
# what is asleep
$K get deploy,sts -A -l sablier.group=aio
# to keep the group awake during a daily window, use Sablier's "running hours" annotation
# (https://sablierapp.dev/features/running-hours/ ; Sablier local time is UTC, 09:00 IST = 03:30 UTC)
# opt a workload out: remove the labels, then scale it back up
$K -n <ns> label deploy <name> sablier.enable- sablier.group-
```

- **Re-label after reinstalling** Argo CD or Tekton from upstream manifests. Labels added with
  `kubectl label` survive later `kubectl apply` and `helm upgrade`, but not a delete and reinstall.
- **Tekton CPU requests:** run `ops/portable/azure-tekton-requests.sh` after every Tekton release apply.
  - It sets the remote resolvers to 20m and the webhook to 30m, instead of the upstream 100m each. Both idle at about 1m.
  - Without it, waking the group needs a 2nd node: the 2026-11-14 test did exactly that until the requests were lowered.
- **Traefik upgrades:** always pass `azure-ingress-public-values.yaml` after `azure-ingress-values.yaml` (see the header of that file).

### Rollback

1. Remove `,aio-platform-sablier-aio@kubernetescrd` and `,argocd-sablier-aio@kubernetescrd` from the two Ingress annotations.
2. Scale the group back to 1.
3. Delete namespace `sablier`.

The plugin entry in Traefik is harmless when unused.

## Node migration (done 2026-11-14 ~19:45–20:05 UTC)

1. Put the group to sleep. Cordon F node `…000003`, roll its Deployments onto `…000000` (all roll surge-first, so with no downtime), drain it, then `az aks nodepool delete-machines` with the autoscaler off. Cores went from 4 to 2.
2. `az aks nodepool add -n b2as --mode System -s Standard_B2as_v2 -c 1 --node-osdisk-size 64 --max-surge 1`.
3. Cordon `…000000`, roll every Deployment onto the B node, then restart `postgres-rehearsal-0`, which was **back in 42 s**. Profile (Keycloak) health recovered on its own about 1.5 min later. Then drain.
4. `az aks nodepool delete -n agentpool`, then `nodepool update -n b2as --enable-cluster-autoscaler --min-count 1 --max-count 2`.

## Incident during the change

The first `helm upgrade` of Traefik used `--reuse-values -f azure-ingress-values.yaml`. That file's
`service.spec.type: ClusterIP` replaced the live LoadBalancer, so **all public sites were unreachable for about 4 min**
(about 19:36–19:40 UTC). Fixed by upgrading again with `service.spec.type=LoadBalancer`. The same static IP
`aio-ingress-public-ip` (52.140.55.184) was re-attached, so no DNS change was needed. The values file now carries a warning header.

## Not done, and why

- **PostgreSQL on Blob over NFS:** Microsoft does not support Blob NFS for database workloads (no file locking or fsync guarantees). The database disk is 4 GiB Standard SSD, about US$1 / month, and disks were not what forced extra nodes. CPU requests were.
- **Azure Functions for Argo CD or CI:** Argo CD is a long-running reconciler and Tekton builds need container builds, so neither fits Functions. The useful Functions job is the **webhook buffer** above, which is the next step.
- **B2als_v2 (4 GiB, about US$39):** too small. The always-on workloads already reserve about 3.3 GiB.
