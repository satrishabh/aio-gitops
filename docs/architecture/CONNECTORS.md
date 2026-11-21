# Connectors: open-source tools managed from AIO

**Status:** the framework and the phase 1-3 connectors (Prometheus + Grafana; Trivy Operator, OpenCost, Goldilocks + VPA, kube-green; Velero, Renovate, KEDA, Descheduler) are implemented, unit-tested and chart-rendered. The phase 4 connectors (node autoprovisioning on AKS and GKE; Loki, Tempo, OpenTelemetry Collector; External Secrets Operator; Gatus) are in aio-catalog, validated and chart-rendered at both versions, as of 2026-11-20. Phase 5 (CloudNativePG, Valkey, Harbor; Biome as a CI lint step) likewise, as of 2026-11-20; CrowdSec from the same wave is live on AKS since 2026-11-18. Phase 6 (Strimzi, Crossplane, OpenKruise; Dependency-Track, Falco, OpenBao; Airflow, Superset, Appsmith, APISIX; Milvus, Langfuse; Buildah as a CI build strategy) likewise, as of 2026-11-20. Phase 7 (Gitea, SonarQube; Dapr, Temporal, Camel K, Volcano; Spark, Flink, OpenSearch, TiDB and Doris operators, Pulsar; ERPNext, Jitsi Meet) likewise, as of 2026-11-20. None of phases 1-7 is deployed to a live cluster yet, except CrowdSec. **Date:** 2026-11-20.

The **Connectors → Open source** page (admins only) lists every open-source tool AIO works with: what it is, whether it runs, and what an admin can do with it. The list is data, not code: the aio-catalog repository (one file per tool). Adding a tool means adding one catalog entry, one values file and one line in the Argo CD project. No Java or React changes are needed.

## Where things live (since 2026-11-17)

The tool catalog moved out of AIO into its own repository, **aio-catalog** (`d:6io-catalog`, to be pushed as `github.com/satrishabh/aio-catalog`). It holds one file per tool, the values files, the extras, the setup scripts, the Keycloak SSO clients, the policy, and the generated Argo CD project. AIO only holds the code that reads it:

- `CONNECTORS_REPO` + `CONNECTORS_REVISION` (a tag): AIO reads `dist/catalog.yaml` from that tag every 10 minutes, and Argo CD reads values and extras from the same tag. If a refresh fails, the last good catalog stays and the page shows the error.
- With neither set, AIO shows the built-in core components only.
- `CONNECTORS_SITE` selects install-specific overlays (`site-<site>.yaml`: domains, Keycloak issuer, ingress).
- The catalog repo's README and POLICY cover scope (64 tools), schedule waves 4–7, licensing and the planned → managed promotion. Its `sso/README.md` covers single sign-on.

## Kinds and actions

| Kind | Installed by | Status from | Actions |
| --- | --- | --- | --- |
| core | bootstrap scripts | its Deployment (available replicas) | Test |
| managed | the portal, as Argo CD app `connector-<id>` in project `connectors` | Argo CD health + last test | Enable, Test, Roll back, Use pinned, Disable |
| planned | not yet | — | none (shows the scope) |

- **Test** for a managed connector always reports its Argo CD health, then runs its HTTP probes from the platform pod (in-cluster `*.svc` addresses only), plus a Deployment check when the entry names a `deployment`.
- **Needs**: a connector with `requires` cannot be enabled until those connectors are present (OpenCost needs Prometheus; kube-green needs cert-manager). A connector cannot be disabled while an enabled connector still needs it.
- **Degraded** means Argo CD reports it unhealthy, or Argo CD reports it healthy but the last test failed. Every lookup fails soft: an unreachable tool or a missing permission marks only that connector. The page and the rest of AIO keep working.
- **Roll back** reinstalls the catalog's `previousVersion`. **Use pinned** returns to `version`. Only these two exact versions are accepted; there are no floating tags.
- **Disable** deletes the Argo CD app with cascade: everything the tool installed is removed, including its stored data (Prometheus metrics).
- Every Enable, Roll back and Disable is written to the audit log as `connector.*`.
- **Phases: off until ready, then one switch.** Connectors → Phases lists each catalog phase with how many of its connectors are on. Nothing in a phase runs until an admin presses **Switch on**: it enables every managed connector of the phase for this cloud at its pinned version, dependencies first, and turns on the phase's CI steps (Biome lint, phase 5). **Switch off** removes them in reverse order (stored data included) and holds the CI steps off again; that is the way back if a phase misbehaves after go-live. Members already in the wanted state are left alone, one failure does not stop the rest, and each member reports its own result. Audited as `connector.phase-on` / `connector.phase-off`. The CI-step switches live in table `platform_switches`; no row means off.
- `CLOUD_PROVIDER` (`any`, `gke`, `eks`, `aks`, `oke`, `onprem`) marks entries with a `clouds` list that excludes this cloud as "not for this cloud", so the catalog stays cloud-agnostic.

## Running on AKS (current cluster)

AIO runs on AKS `creative` (resource group `creative`, South India).

Read from the cluster on 2026-11-17:
- **Nodes:** node pool `b2as`, `Standard_B2as_v2` (2 vCPU, 8 GiB, burstable CPU; about 1.9 CPU and 5.65 GiB allocatable per node). Autoscaling 1-2 nodes, currently at the maximum of 2.
- **Cluster:** Kubernetes 1.36.5, Free tier. Azure CNI overlay with Cilium, so Node Auto Provisioning is possible. OIDC issuer and Workload ID are on. The KEDA and VPA add-ons and the Blob CSI driver are off.
- **Workloads:** Traefik ingress (`aio-ingress`), in-cluster Postgres in `aio-validation`. Set `CLOUD_PROVIDER=aks` on the platform. In `ops/portable/azure-native-aio-values.yaml` that is `CLOUD_PROVIDER: aks` under `config`. It does three things:

- applies the `values-aks.yaml` overrides: Velero through Workload ID, and a smaller Prometheus (3 days, 512 Mi, 5 Gi disk);
- shows Traefik instead of ingress-nginx as the core ingress;
- offers AKS Node Auto Provisioning in phase 4.

**Capacity is the constraint.** Requested resources if every connector is enabled:

| Connector | CPU | Memory | Other |
| --- | --- | --- | --- |
| Prometheus + Grafana (AKS sizing) | 330m | 1088 Mi | 5 Gi disk; Grafana alone uses ~400 Mi at start (limit 512 Mi) |
| Velero (+ node agent on each node) | 200m | 384 Mi | storage account |
| KEDA | 110m | 320 Mi | |
| Goldilocks + VPA recommender | 60m | 320 Mi | |
| Trivy Operator | 50m | 128 Mi | + up to 2 scan jobs of 100m / 128 MB while scanning |
| Descheduler | 50m | 64 Mi | |
| OpenCost | 20m | 96 Mi | |
| kube-green | 20m | 64 Mi | |
| Renovate | 250m | 512 Mi | only while the Monday job runs |
| **Total, steady (phases 1-3)** | **~0.85 CPU** | **~2.4 GiB** | |
| Loki (phase 4) | 100m | 256 Mi | 10 Gi disk |
| Tempo (phase 4) | 50m | 256 Mi | 5 Gi disk |
| OpenTelemetry Collector (phase 4, one per node) | 50m × nodes | 128 Mi × nodes | |
| External Secrets Operator (phase 4, 3 pods) | 30m | 128 Mi | |
| Gatus (phase 4) | 10m | 32 Mi | 1 Gi disk |
| Node autoprovisioning (phase 4) | 0 | 0 | runs in the cloud's control plane |
| **Phase 4 on 2 nodes** | **~0.3 CPU** | **~0.9 GiB** | 16 Gi disk |
| CloudNativePG operator (phase 5) | 20m | 96 Mi | |
| Valkey + exporter (phase 5) | 30m | 88 Mi | 2 Gi disk |
| Harbor, 7 pods incl. its CloudNativePG database (phase 5) | 160m | 670 Mi | 26 Gi disk |
| Biome lint step (phase 5) | 100m | 128 Mi | only while a run lints |
| **Phase 5, steady** | **~0.2 CPU** | **~0.85 GiB** | 28 Gi disk |
| Strimzi operator (phase 6; Kafka clusters extra) | 50m | 384 Mi | |
| Crossplane core (phase 6) | 70m | 256 Mi | + 50m / 256 Mi per provider pod |
| OpenKruise manager (phase 6) | 50m | 192 Mi | |
| Dependency-Track + its CloudNativePG database (phase 6) | 310m | 1.55 GiB | 12 Gi disk |
| Falco (phase 6, one per node) | 50m × nodes | 192 Mi × nodes | |
| OpenBao (phase 6) | 50m | 128 Mi | 2 Gi disk |
| Airflow + its CloudNativePG database (phase 6) | 440m | 1.85 GiB | 7 Gi disk |
| Superset + its CloudNativePG database (phase 6) | 130m | 672 Mi | 2 Gi disk |
| Appsmith (phase 6) | 250m | 1.5 GiB | 5 Gi disk |
| APISIX (phase 6) | 50m | 128 Mi | |
| Milvus standalone (phase 6) | 100m | 512 Mi | 10 Gi disk |
| Langfuse: web, worker, ClickHouse, PostgreSQL, Valkey, S3 (phase 6) | 500m | 2.0 GiB | 26 Gi disk |
| Buildah build step (phase 6) | | | only while a build runs |
| **Phase 6 on 2 nodes** | **~2.1 CPU** | **~9.5 GiB** | 64 Gi disk |
| Gitea + its CloudNativePG database (phase 7) | 80m | 352 Mi | 15 Gi disk |
| SonarQube + its CloudNativePG database (phase 7) | 230m | 2.2 GiB | 10 Gi disk |
| Temporal, 6 pods + its CloudNativePG database (phase 7) | 290m | 896 Mi | 5 Gi disk |
| Dapr control plane (phase 7) | 110m | 464 Mi | 3 × 1 Gi disk |
| Camel K operator (phase 7) | 50m | 192 Mi | builds up to 1.5 GiB while running |
| Volcano (phase 7) | 50m | 160 Mi | |
| Spark, Flink, OpenSearch, TiDB, Doris operators (phase 7) | 240m | 1.1 GiB | app clusters extra |
| Pulsar standalone (phase 7) | 250m | 896 Mi | 10 Gi disk |
| ERPNext: 7 pods, MariaDB, 2 Valkeys (phase 7) | 210m | 1.5 GiB | 10 Gi disk |
| Jitsi Meet (phase 7) | 350m | 880 Mi | |
| **Phase 7** | **~1.9 CPU** | **~8.5 GiB** | 53 Gi disk |

On 2026-11-17, one node had 99% of its allocatable memory requested and was using more than that (6.3 GiB). The other node had just been added by the autoscaler and was nearly empty. The cluster cannot grow beyond these two nodes, so every connector must fit in the second node's free space. Phase 6 as a whole does not fit: switch it on only after the spot pool or Node Auto Provisioning adds capacity, and until then enable its connectors one at a time. Check headroom with `kubectl describe nodes | grep -A6 "Allocated resources"` before each Enable. Recommended order on AKS:

1. Velero first. It closes the documented gap: the nightly Postgres dumps currently stay on a disk inside the AKS node resource group.
2. Then kube-green and Trivy Operator (small).
3. Add a spot node pool (below) before Prometheus, KEDA and the rest.

**Scale to zero (Sablier).** On AKS, AIO, Argo CD and Tekton belong to the Sablier group `aio`. They scale to zero when no one uses the portal, and a browser visit wakes them; bots, curl and uptime checks are ignored on purpose. While the group sleeps, connectors keep running, but Argo CD does not sync or self-heal them and the Connectors page is unavailable. That is acceptable for these tools and saves memory. To keep the group up for maintenance without a browser, ask Sablier directly:
```bash
kubectl -n sablier port-forward svc/sablier 18080:10000 &
curl "http://127.0.0.1:18080/api/strategies/blocking?group=aio&session_duration=45m&timeout=5m"
```

**Managed AKS add-ons:** AKS can run KEDA (`az aks update --enable-keda`) and VPA (`--enable-vpa`) itself. Use either the add-on or the connector, never both. If the VPA add-on is on, set `vpa.enabled: false` in `aio-catalog values/goldilocks/values.yaml`.

## Scope: 56 connectors

| Group | Connectors | Kind |
| --- | --- | --- |
| CI/CD | Tekton, Argo CD, Argo Rollouts | core |
| Supply chain | Tekton Chains, Kyverno (Buildpacks and Trivy run as pipeline steps) | core |
| Networking | cert-manager, ingress-nginx (GKE/EKS/OKE/on-prem) or Traefik (AKS) | core |
| Observability | **Prometheus + Grafana + Alertmanager** | managed (phase 1) |
| Security | **Trivy Operator** | managed (phase 2) |
| Cost | **OpenCost, Goldilocks + VPA, kube-green** | managed (phase 2) |
| Cost | **KEDA, Descheduler** | managed (phase 3) |
| Reliability / upkeep | **Velero, Renovate** | managed (phase 3) |
| Observability | **Loki, Tempo, OpenTelemetry Collector** | managed (phase 4) |
| Security | **External Secrets Operator** | managed (phase 4) |
| Reliability | **Gatus** | managed (phase 4) |
| Cost | **Node autoprovisioning** (AKS Node Auto Provisioning, GKE node auto-provisioning) | managed (phase 4) |
| Data | **CloudNativePG, Valkey** | managed (phase 5) |
| Security | **Harbor** | managed (phase 5) |
| Security | **CrowdSec** (edge bans, optional) | managed (phase 5, live on AKS) |
| CI/CD | **Biome** (lint step in the CI pipeline) | core (phase 5) |
| Platform | **Strimzi** (Kafka operator), **Crossplane**, **OpenKruise** | managed (phase 6) |
| Security | **Dependency-Track, Falco, OpenBao** | managed (phase 6) |
| Data / apps | **Airflow, Superset, Appsmith, APISIX** | managed (phase 6) |
| AI | **Milvus, Langfuse** | managed (phase 6) |
| CI/CD | **Podman / Buildah** (build strategy in the CI pipeline) | core (phase 6) |
| Developer tools | **Gitea, SonarQube** | managed (phase 7) |
| App runtime | **Dapr, Temporal, Camel K, Volcano** | managed (phase 7) |
| Data | **Spark, Flink, OpenSearch, TiDB, Doris operators; Pulsar** | managed (phase 7) |
| Business apps | **ERPNext, Jitsi Meet** | managed (phase 7) |
| Cost / tracing | Dragonfly, Koordinator, Jaeger | planned (see Activation (phase 6)) |
| Other | Odoo, SuiteCRM, MediaWiki, Answer, DolphinScheduler, ShardingSphere, SkyWalking, HertzBeat, OpenNMS, Nacos, Seata, Higress, Karmada, KubeEdge, vLLM | planned (see Activation (phase 7)) |
| Cost | Karpenter on EKS | planned (when AIO runs on EKS) |

Not included, because each would replace AIO itself rather than plug into it: Jenkins, GitLab CI, Flux, Spinnaker, Backstage. Commercial-only tools (Kubecost Enterprise, Cast AI, ScaleOps) are excluded too.

### Cheapest compute

Cost tools that work on any cloud: OpenCost (see the cost), Goldilocks + VPA (right-size requests), kube-green (sleep dev and staging off-hours), KEDA (scale idle apps to zero) and Descheduler (repack pods so nodes can be removed). Spot capacity itself is a cloud setting, not something an open-source tool can supply:

- **AKS (current):** a spot node pool that scales to zero, for dev and staging. Spot VMs cost a fraction of regular VMs but can be evicted at any time:
  ```bash
  az aks nodepool add -g creative --cluster-name creative -n spot --priority Spot --eviction-policy Delete \
    --spot-max-price -1 --node-vm-size Standard_B2as_v2 --enable-cluster-autoscaler --min-count 0 --max-count 3 \
    --node-taints kubernetes.azure.com/scalesetpriority=spot:NoSchedule
  ```
  AKS taints spot nodes, so only pods that tolerate `kubernetes.azure.com/scalesetpriority=spot` land there. Dev and staging apps need that toleration; that is a change to the app chart, still to do. Later, Node Auto Provisioning (phase 4) picks VM sizes and spot automatically. It needs Azure CNI overlay with Cilium. Check `az aks show -g creative -n creative --query networkProfile` first.
- **GKE:** a spot node pool with the built-in cluster autoscaler (`optimize-utilization` profile).
- **EKS:** Karpenter, which chooses the cheapest node and prefers spot.

## Schedule

| Phase | Dates | Connectors | Exit check before the next phase |
| --- | --- | --- | --- |
| 1 | 2026-11-17 → 11-21 | Prometheus + Grafana | Test passes 3 days in a row; Grafana dashboards show nodes and pods |
| 2 | 2026-11-24 → 12-05 | Trivy Operator, OpenCost, Goldilocks + VPA, kube-green | Rescan reports exist for dev/staging/prod; OpenCost shows cost per namespace; dev/staging sleep and wake on time |
| 3 | 2026-12-08 → 12-19 | Velero, Renovate, KEDA, Descheduler | Restore drill of the `aio-platform` namespace and the Chains key succeeds; first Renovate PRs merged |
| 4 | 2027-01 | Node autoprovisioning (AKS, GKE); Loki, Tempo, OpenTelemetry Collector; External Secrets Operator; Gatus | Logs of every namespace and a test trace visible in Grafana; one ExternalSecret synced from the cloud store; Gatus checks green 3 days; a pending dev pod gets a Spot node and the node is removed once empty |
| 5 | 2027-01 | CloudNativePG, Valkey, Harbor; Biome lint step (CrowdSec already live) | A dev app reads and writes its own CloudNativePG database and the shared Valkey; an image pushed to Harbor through Keycloak login is pulled by a dev app; a repo with biome.json fails CI on a lint error and passes once fixed |
| 6 | 2027-02 | Strimzi, Crossplane, OpenKruise; Dependency-Track, Falco, OpenBao; Airflow, Superset, Appsmith, APISIX; Milvus, Langfuse; Buildah build strategy | A dev app produces to and consumes from a single-node Kafka; Crossplane creates one cloud resource through Workload ID; the AIO pipeline's SBOM shows up in Dependency-Track; a Falco alert from a `kubectl exec` is visible in Loki; OpenBao is unsealed again after a pod restart; a DAG runs in Airflow and a Superset chart reads a CloudNativePG database, both through Keycloak login; a trace from a dev app is in Langfuse; a project built with Buildah deploys to dev |
| 7 | 2027-03 onwards | Gitea, SonarQube; Dapr, Temporal, Camel K, Volcano; Spark, Flink, OpenSearch, TiDB, Doris operators, Pulsar; ERPNext, Jitsi Meet | A repo mirrored into Gitea through Keycloak login; the AIO pipeline's analysis shows in SonarQube; a dev app calls another through Dapr and runs a Temporal workflow; a SparkPi job and a Flink example job finish in dev; an app's OpenSearch cluster answers a search; an app produces to and consumes from Pulsar with its token; ERPNext login through Keycloak; a three-person Jitsi call on sarvaikam |

Recurring jobs set up inside the tools when each one is added:

| Job | Schedule (IST) |
| --- | --- |
| Trivy Operator rescan of running images | every 24h |
| Velero backup | daily 03:30 (after the 02:30 Postgres dump), kept 14 days |
| Renovate | weekly, Monday 05:00 |
| Descheduler pass | every 30 minutes |
| kube-green: dev and staging asleep | 20:00 → 08:00 on weekdays, all weekend |
| Goldilocks recommendations | continuous |

Each phase follows the same cycle: one connector at a time; Enable, then Test; if it misbehaves, Roll back or Disable; move on only when it's healthy.

## Activation (Prometheus, phase 1)

1. Push the repo. Argo CD reads `aio-catalog values/kube-prometheus-stack/values.yaml` from the GitOps repo and branch the platform is configured with.
2. Apply the Argo CD project and permission, and the platform RBAC. On GKE, `gcp-setup.sh` does all three on re-run. On AKS, by hand:
   ```bash
   kubectl apply -f ../aio-catalog/deploy/connectors-project.yaml
   kubectl apply -f platform/deploy/rbac.yaml        # read-only check of core Deployments
   kubectl -n argocd edit cm argocd-rbac-cm
   # AKS: the platform's Argo CD account is aio-native. Add to the key policy.aio-native.csv:
   #   p, role:aio-native, applications, *, connectors/*, allow
   # GKE (gcp-setup.sh adds it): p, aio-platform, applications, *, connectors/*, allow
   ```
3. Deploy the new platform image with `CLOUD_PROVIDER=aks` (or `gke`). Optionally set `TOOLS_GRAFANA_URL=https://…` if Grafana gets an HTTPS ingress (otherwise the page shows the port-forward command).
4. Connectors → Prometheus + Grafana → **Enable**. Wait for Healthy (a few minutes), then **Test**.

Resource budget: about 0.35 CPU and 1.1 GiB requested plus a 5 GiB disk on AKS (`values-aks.yaml`), or 0.45 CPU, 1.7 GiB and 10 GiB with the default values. Grafana needs catalog `v2026.11.6` or later: earlier versions capped it at 256 Mi and it restarted in a loop.

## Activation (phase 2)

Pinned versions (each released at least two weeks before pinning; rollback target in brackets): trivy-operator 0.38.1 (0.37.0), opencost 2.5.33 (2.5.32), goldilocks 11.2.0 (11.1.1), kube-green 0.7.1 (0.7.0).

1. Push the repo and re-apply `deploy/connectors-project.yaml` (aio-catalog) and `platform/deploy/rbac.yaml` (or re-run `gcp-setup.sh`). The project now allows the four chart repositories and the namespaces `trivy-system`, `opencost`, `goldilocks`, `kube-green`, plus `dev` and `staging` for the kube-green schedules.
2. Deploy the new platform image.
3. Enable one connector at a time, wait for Healthy, then **Test**:

| Connector | What Test checks | What to look at afterwards |
| --- | --- | --- |
| Trivy Operator | operator Deployment; metrics endpoint answers | `kubectl get vulnerabilityreports -A` fills within an hour; each report is replaced every 24h |
| OpenCost (after Prometheus) | Deployment; health; 24h cost allocation by namespace | the UI (port-forward in the row); cost needs a few hours of metrics before it is meaningful |
| Goldilocks + VPA | controller Deployment; dashboard health | dashboard lists dev, staging and prod workloads with suggested requests after about a day |
| kube-green | controller Deployment | `kubectl get sleepinfo -n dev`; at 20:00 IST dev and staging scale to 0, at 08:00 they come back |

4. **kube-green only:** apply the Argo CD sync windows, or self-heal undoes the sleep within minutes:
   ```bash
   kubectl -n argocd patch appproject apps --type merge --patch-file aio-catalog extras/kube-green-apps-sync-windows.yaml
   ```
   They block automatic syncs in dev and staging from 20:00 to 08:00 IST on weekdays and all weekend. Manual syncs (portal deploys) still go through, which wakes the app until the next 20:00. Remove the windows when kube-green is disabled (command in the file).

Trade-offs to know:
- Trivy Operator scans `dev`, `staging`, `prod` and `aio-platform`, HIGH and CRITICAL only, fixable issues only, at most two scan jobs at a time. Its ServiceMonitor is off so it works without Prometheus; turn it on in the values file for Grafana dashboards.
- OpenCost prices: compare the node cost it shows with the Azure price of `Standard_B2as_v2` in South India before trusting it. If they differ, configure Azure pricing as described in the OpenCost docs. Reservations, savings plans and negotiated discounts are never reflected.
- Goldilocks only recommends. The VPA updater and admission webhook are not installed, so nothing is resized automatically.
- The connectors project may create anything in `dev` and `staging` (needed for SleepInfo). Changes still only come from reviewed files in the GitOps repo.

## Activation (phase 3)

Pinned versions (rollback target in brackets): velero 12.2.3 / Velero 1.18.2 with the GCP, AWS and Azure plugins v1.14.4 (12.2.2), renovate 46.405.0 / Renovate 44.210.0 (46.404.0), keda 2.21.0 (2.20.2), descheduler 0.37.0 (0.36.0).

1. Push, re-apply `deploy/connectors-project.yaml` (aio-catalog) and `platform/deploy/rbac.yaml`, deploy the new platform image.
2. **Velero on AKS.** With `CLOUD_PROVIDER=aks` (adds `values-aks.yaml`), run `bash aio-catalog setup/velero-setup-azure.sh` (needs `az login` and the AKS kubeconfig). It creates a private storage account in resource group `creative` (shared keys disabled) and the managed identity `aio-velero` with access to that account only, federated with `velero/velero-server`. If Workload ID is off on the cluster it stops; re-run with `ENABLE_WORKLOAD_ID=1` to turn it on (an AKS update). Enable Velero, then run the script again: it annotates the service account, restarts Velero and creates the backup location. On GKE the same flow is `aio-catalog setup/velero-setup-gcp.sh` with `CLOUD_PROVIDER=gke`. Exit check: `kubectl -n velero get backupstoragelocation default` shows Available, the first scheduled backup completes, and a restore drill into a scratch namespace succeeds:
   ```bash
   velero backup create drill --include-namespaces aio-platform,aio-validation --wait
   velero restore create --from-backup drill --namespace-mappings aio-platform:drill-platform,aio-validation:drill-validation --wait
   ```
   On other clouds, create the bucket and a `velero-credentials` secret (key `cloud`) by the plugin's instructions, and a BackupStorageLocation named `default`.
3. **Renovate.** Create the token secret (commands in `aio-catalog values/renovate/values.yaml`), enable, then trigger a first run instead of waiting for Monday: `kubectl -n renovate create job --from=cronjob/renovate renovate-first`. Each repository gets an onboarding PR; nothing changes until it is merged.
4. **KEDA.** Enable and Test. It does nothing until an app has a ScaledObject. Per-app scale-to-zero from the portal is follow-up work. For web apps, it also needs the KEDA HTTP add-on or a Prometheus trigger.
5. **Descheduler.** Enable and Test. It only lowers cost when nodes autoscale: the AKS spot pool with the cluster autoscaler, or Node Auto Provisioning (see *Cheapest compute*). On today's fixed 2-node pool it only tidies duplicates and crash-looping pods.

Trade-offs to know:
- Velero copies the Postgres data volume while the database runs. That copy is crash-consistent only. The consistent restore point is the `pg_dump` file on the `postgres-backups` volume, which Velero copies to Blob every night. Restore from the dump.
- On GKE, direct Workload Identity access means `velero backup logs` and `describe --details` cannot download from the bucket; read logs with `kubectl`. AKS uses a managed identity and does not have this limitation.
- KEDA owns the `external.metrics.k8s.io` API. Do not also serve external metrics from a Prometheus Adapter.

## Activation (phase 4)

Pinned versions (rollback target in brackets): loki 7.5.0 / Loki 3.6.16 (7.3.0), tempo 3.2.1 / Tempo 3.1.1 from grafana-community (3.2.0), opentelemetry-collector 0.178.2 / collector 0.163.2 (0.178.1), external-secrets 2.14.0 (2.13.0), gatus 1.5.0 / Gatus v5.34.0 (1.4.5), bedag raw 2.0.2 (2.0.0) for the node policy.

Every phase 4 connector works on AKS and GKE (and on EKS or on-prem, except node autoprovisioning). Loki, Tempo, the collector and Gatus need no cloud credentials: they keep their data on disks from the default StorageClass. Only External Secrets and node autoprovisioning have a per-cloud setup script.

1. Push the catalog, tag it, re-apply `deploy/connectors-project.yaml` (five new chart repositories and the namespaces `loki`, `tempo`, `opentelemetry`, `external-secrets`, `gatus`, `node-provisioning`), and move `CONNECTORS_REVISION` to the tag. No platform image change is needed.
2. **Prometheus first, re-synced.** Its values now let Grafana read datasources from every namespace (`grafana.sidecar.datasources.searchNamespace: ALL`). Roll back and re-pin it, or wait for the self-heal sync, so Grafana picks that up.
3. **Logs and traces, in this order** (the collector needs both stores):
   1. Loki → Test. Grafana gets the datasource Loki (ConfigMap in `extras/loki-datasource`).
   2. Tempo → Test. Grafana gets the datasource Tempo; a trace links to its pod's logs and back.
   3. OpenTelemetry Collector → Test (the probe hits the collector on the platform pod's node). Exit check: in Grafana → Explore → Loki, `{k8s_namespace_name="dev"}` shows log lines within a minute. For a trace, set `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector.opentelemetry.svc:4318` on one dev app with OpenTelemetry instrumentation (Spring Boot: the OpenTelemetry Java agent or starter).
4. **External Secrets.** Enable → Test, then run the setup script, which needs the CRDs to exist:
   - AKS: `bash aio-catalog setup/external-secrets-setup-azure.sh`. It creates a Key Vault (RBAC mode, purge protection), the managed identity `aio-external-secrets` with read-only access to that vault, federated with the service account `external-secrets/aio-keyvault`, and the ClusterSecretStore `azure-keyvault`. The operator's own service account gets no Azure access.
   - GKE: `bash aio-catalog setup/external-secrets-setup-gcp.sh`. It grants the operator's service account `roles/secretmanager.secretAccessor` through Workload Identity (project-wide, or per secret with `SCOPE=secrets SECRETS="a b"`) and creates the ClusterSecretStore `gcp-secret-manager`.
   - Exit check: `kubectl get clustersecretstore` shows Valid, and an `ExternalSecret` in dev produces its Secret.
5. **Gatus.** Enable → Test. It checks Argo CD in-cluster everywhere, and on sarvaikam the portal, Keycloak, Argo CD and Grafana, including certificate expiry (alert below 14 days). The page: `kubectl -n gatus port-forward svc/gatus 8080:80`. Add an installation's sites in `values/gatus/site-<site>.yaml`.
6. **Node autoprovisioning (AKS, GKE).** Run the setup script first: without the cloud feature, the node policy's API does not exist and the connector shows Degraded.
   - AKS: `bash aio-catalog setup/node-autoprovisioning-aks.sh` prints what it would do; `CONFIRM=1` applies it. It checks Azure CNI overlay with Cilium, **turns the cluster autoscaler off on every node pool** (NAP and the autoscaler cannot run together; each pool keeps its current node count), and sets `--node-provisioning-mode Auto`. Then Enable: it adds the NodePool `aio-spot` (Spot first, on-demand fallback, B and D series up to 8 vCPU, capped at 16 vCPU / 64 GiB, empty nodes removed after 2 minutes). Its nodes carry the usual AKS spot taint, so only pods with the spot toleration use them. This replaces the manual spot node pool in *Cheapest compute*: use one or the other.
   - GKE: `CLUSTER=… LOCATION=… bash aio-catalog setup/node-autoprovisioning-gke.sh`, then `CONFIRM=1`. It enables node auto-provisioning (limits `MAX_CPU` 16 / `MAX_MEMORY` 64 GiB, `optimize-utilization` profile, optional `NAP_SERVICE_ACCOUNT`) on GKE 1.30.3+. Then Enable: it adds the ComputeClass `aio-spot` (e2 Spot first, e2 on-demand fallback, moves back to Spot when it returns). Pods opt in with the node selector `cloud.google.com/compute-class: aio-spot`.
   - Turn off: Disable the connector, then run the script with `DISABLE=1 CONFIRM=1`.
   - Apps opt in through the app chart: `spot: { enabled: true, provider: aks }` (spot toleration) or `provider: gke` (compute-class selector) in an app's dev or staging values. The chart refuses it in a prod namespace, and so does the CD page's values editor. New apps get it automatically in dev and staging when this connector is installed, the env runs on the platform's own cluster and `CLOUD_PROVIDER` is `aks` or `gke` (`SPOT_FOR_NEW_APPS=off` turns that off). Existing apps are never changed: add the block on the CD page.

Trade-offs to know:
- Loki and Tempo keep data on one disk each (7 and 3 days). Losing the disk loses that history; metrics in Prometheus are unaffected. Object storage (Blob, GCS, S3) needs a per-cloud identity, like Velero's, and is the next step if logs must outlive a disk.
- The collector reads every container's log on every node, including tainted spot nodes. Lines are never parsed into fields; Loki indexes only the Kubernetes labels (namespace, pod, container, workload).
- The grafana/helm-charts `tempo` chart is deprecated; the catalog uses the grafana-community chart, now with Tempo 3.
- The node-autoprovisioning connector installs only AIO's node policy. The autoprovisioner is the cloud's managed service, so Disable leaves the cloud feature on until the script turns it off.
- EKS: Karpenter needs per-cluster IAM (node role, controller role, interruption queue). It stays a planned entry until AIO runs on EKS.

## Activation (phase 5)

Pinned versions (rollback target in brackets): cloudnative-pg 0.29.1 / operator 1.30.1 (0.29.0), valkey 0.12.1 / Valkey 9.1.3 (0.12.0), harbor 1.19.4 / Harbor 2.15.4 (1.19.2), Biome 2.6.5 (image pinned by digest). CrowdSec 0.24.2 is already live (aio-catalog `extras/edge-protection`).

All of them work on AKS, GKE, EKS and on-prem: data on disks from the default StorageClass, no cloud credentials. Karpenter stays planned until AIO runs on EKS.

Everything stays off until switched on: steps 1-5 prepare (nothing runs yet), step 6 switches the whole phase on. Single connectors can still be enabled one at a time instead.

1. Push and tag the catalog, re-apply `deploy/connectors-project.yaml` (three chart repositories; namespaces `cnpg-system`, `harbor`, `valkey`) and move `CONNECTORS_REVISION` to the tag. Deploy the platform image with the phase switch (migration `V10__platform_switches`).
2. **CloudNativePG** → Enable → Test (the operator's controllers answer on a metrics Service from `extras/cloudnative-pg`). An app gets a database from one `Cluster` manifest in its namespace (example in `values/cloudnative-pg/values.yaml`); it reads Secret `<cluster>-app` and connects to `<cluster>-rw:5432`.
3. **Valkey.** Create the password first, then Enable → Test (the exporter logs in with it, so `redis_up 1` proves the password works):
   ```bash
   kubectl create namespace valkey
   kubectl -n valkey create secret generic valkey-users --from-literal=default="$(openssl rand -hex 24)"
   ```
   Apps in `dev` and `staging` connect to `valkey.valkey.svc:6379` as user `default`; a NetworkPolicy keeps everyone else out. Copy the password into the app's namespace as a Secret (or sync it with External Secrets).
4. **Harbor** (after CloudNativePG):
   1. `bash aio-catalog setup/harbor-secrets.sh` creates `harbor-secrets` (internal secrets, admin password, registry login) and `harbor-token`. Under Argo CD the chart cannot keep its own generated secrets; it would make new ones on every sync.
   2. sarvaikam only: `python tools/gen_sso.py && bash sso/keycloak/import.sh` (adds a `groups` claim to client `aio-harbor`), then `OIDC_CLIENT_SECRET='<aio-harbor secret>' OIDC_ENDPOINT=https://profile.sarvaikam.com/realms/aio bash setup/harbor-secrets.sh`. Client role `aio-admin` becomes Harbor admin.
   3. Enable → Test (ping, then `/api/v2.0/health` for every component). The database `harbor-db` comes up first; Harbor core restarts until it is ready.
   4. Exit check: `docker login harbor.tools.sarvaikam.com` (Keycloak user name + CLI secret from Harbor → User Profile), push an image, and pull it from a dev app through an image pull Secret of a Harbor robot account.
5. **Biome**: apply the task before the pipelines that use it, then the pipelines:
   ```bash
   kubectl apply -n ci -f tekton/tasks/lint-biome.yaml
   kubectl apply -n ci -f tekton/pipelines/ci-pipeline.yaml -f tekton/pipelines/ci-build-v2.yaml
   ```
   Every run gets a `lint` step after clone, held **off** (the pipeline default) until phase 5 is switched on. From then on the platform passes `lint=auto`: `biome ci` runs only when the repo has `biome.json` or `biome.jsonc` at the build context; other repos see "skipping" and nothing changes. Runs started by the Tekton EventListener (not the platform) keep the default `off`.
6. **Switch on.** Once steps 2-5's prerequisites are in place (catalog tag, Valkey password, Harbor secrets, Tekton task), Connectors → Phases → phase 5 → **Switch on**. Then Test each connector.

Trade-offs to know:
- Harbor here is a registry for images you choose to keep in-cluster. The AIO pipeline keeps pushing to `APP_REGISTRY` (GHCR); pointing it at Harbor is a separate change (registry credentials in `ci`, pull Secrets in app namespaces).
- Harbor's own Trivy scanner is off: the pipeline scans every image it builds, and the Trivy Operator scans what runs. Turn it on in the values file (`trivy.enabled`, about 0.2 CPU / 512 Mi more) to scan images pushed from elsewhere.
- Harbor's Redis has no password; a NetworkPolicy limits it to the `harbor` namespace. NetworkPolicies need an enforcing CNI: Cilium on AKS, Dataplane V2 on GKE.
- One Valkey is shared by all dev and staging apps: it is a cache, not a store of record. Give each app its own key prefix. Prod apps that need Redis-compatible storage get their own instance.
- CloudNativePG databases are backed up by Velero's nightly volume copies (crash-consistent; PostgreSQL recovers like after a power cut). Point-in-time recovery needs the Barman Cloud plugin and an object-storage identity per cloud; it is not part of this connector yet.
- The Keycloak settings for Harbor are applied at every start from Secret `harbor-oidc`; changes made in Harbor's UI to those fields are overwritten.

## Activation (phase 6)

Pinned versions (rollback target in brackets):
- **Operators:** strimzi 1.3.1 (1.3.0), crossplane 2.4.3 (2.4.2), openkruise 1.9.3 (1.9.2).
- **Security:** dependency-track 2.9.0 / Dependency-Track 5.2.1 (2.8.0), falco 9.2.2 / Falco 0.45.2 (9.2.1), openbao 0.30.6 / OpenBao 2.7.2 (0.30.5).
- **Data and apps:** airflow 1.22.0 / Airflow 3.2.2 (1.21.0), superset 0.22.8 / Superset 6.1.0 (0.22.7), appsmith 3.9.1 / Appsmith CE v2.6.0 (3.9.0), apisix 2.18.2 / APISIX 3.19.0 (2.18.1).
- **AI:** milvus 5.0.33 / Milvus 3.0.5 (5.0.32), langfuse 2.2.3 / Langfuse 4.75.0 (2.2.1).
- **CI:** Buildah 1.43.5 (`quay.io/buildah/stable`, pinned by digest).

All of them work on AKS, GKE, EKS and on-prem, except where noted: data goes on disks from the default StorageClass, and no Bitnami images are used. Databases run on CloudNativePG (phase 5), so Dependency-Track, Airflow, Superset and Langfuse require it. Every value a chart would otherwise generate at random on each render comes from a Secret that a setup script creates once.

**Capacity first.** Phase 6 requests about 2.1 CPU and 9.5 GiB, more than the two reference nodes have free. Add capacity before **Switch on**, or enable the connectors you need one at a time. The heavy ones are Langfuse (2 GiB), Airflow (1.85 GiB), Dependency-Track (1.55 GiB) and Appsmith (1.5 GiB).

1. Push and tag the catalog. Re-apply `deploy/connectors-project.yaml`: 12 more chart repositories, and the namespaces `strimzi`, `crossplane-system`, `kruise-system`, `dependency-track`, `falco`, `openbao`, `airflow`, `superset`, `appsmith`, `apisix`, `milvus` and `langfuse`. Move `CONNECTORS_REVISION` to the tag. Deploy the platform image with the build-strategy setting (migration `V11__project_build_strategy`).
2. **Keycloak (sarvaikam only).** Run `python tools/gen_sso.py && bash sso/keycloak/import.sh`. This creates or updates the clients:
   - `aio-airflow`, `aio-superset` and `aio-dependency-track`, each with a flat `groups` claim;
   - `aio-langfuse`.

   `aio-dependency-track` is a **public** client: the browser does the login with PKCE, so it has no secret. Appsmith has no OIDC in its Community Edition, so it sits behind the oauth2-proxy gate instead, and its users still sign in to Appsmith once there.
3. **Secrets, before Enable.** Run these from aio-catalog:
   - `bash setup/airflow-secrets.sh`
   - `bash setup/superset-secrets.sh`
   - `bash setup/appsmith-secrets.sh`
   - `bash setup/langfuse-secrets.sh`
   - `bash setup/milvus-secrets.sh`
   - `bash setup/dependency-track-secrets.sh`, first run: the key that encrypts Dependency-Track's stored secrets.

   For tools with Keycloak login, set `OIDC_CLIENT_SECRET='<client secret>'` on the run so the script also writes the login Secret. Each script never replaces an existing Secret, and it prints how to read the admin password, never the password itself.
4. **Operators**, each Enable → Test:
   - **Strimzi:** the operator only; an app creates its own Kafka in its namespace (single-node KRaft example in `values/strimzi/values.yaml`). The Test metric appears after the first periodic reconcile, about 2 minutes after start, so an immediate Test can fail once.
   - **Crossplane:** core only. For cloud resources, run `setup/crossplane-setup-azure.sh` or `-gcp.sh` after Enable. These create a keyless identity (AKS Workload ID or GKE Workload Identity) scoped to one resource group or to the roles you name, install the provider family and the resource providers in `PROVIDERS`, and create `ProviderConfig default`. Registering Azure resource providers and enabling GCP APIs stay manual.
   - **OpenKruise:** CloneSet, Advanced StatefulSet and DaemonSet, BroadcastJob, AdvancedCronJob. Pod webhooks (SidecarSet, WorkloadSpread, PodUnavailableBudget) and the node daemon are off, so a stopped manager cannot block pod creation or node drains. Webhook certificates come from cert-manager (`extras/openkruise/webhook-cert.yaml`), so Argo CD and kruise-manager do not fight over them. This path uses an alpha feature gate: try it once on a staging cluster first. On a chart bump, regenerate `extras/openkruise/crds.yaml` (steps in its header).
5. **Security**, each Enable → Test:
   - **Dependency-Track:** **right after Enable**, run `bash setup/dependency-track-secrets.sh` again. It replaces the seeded `admin/admin` password, creates a read-only team "Viewers", and maps `aio-admin` → Administrators and `aio-business` → Viewers. Until then the default login works, and on sarvaikam it is reachable from outside. Upload SBOMs with a team API key (`POST /api/v1/bom`; header of `values/dependency-track/values.yaml`). If it is OOM-killed by the nightly vulnerability mirror, raise the limit to 2.5 GiB and `-Xmx` to 2g.
   - **Falco:** a privileged DaemonSet with the modern eBPF probe. It needs kernel 5.8+ with BTF, which current AKS, GKE COS and EKS images have, and it does not run on GKE Autopilot. Alerts are JSON in its logs, which Loki collects.
   - **OpenBao:** after Enable, run `bash setup/openbao-init.sh`. It initialises OpenBao, unseals it, and keeps the unseal key and root token in Secret `openbao/openbao-init`. Copy that Secret offline, then delete it. Until init, the app shows Progressing and Test fails; that is expected. **Every restart, node drain or spot eviction seals OpenBao again**: re-run the script with `UNSEAL_KEY_FILE`. Auto-unseal through Azure Key Vault or GCP KMS is described in the values header but not built.
6. **Data and apps**, each Enable → Test:
   - **Airflow:** Airflow 3 with LocalExecutor: tasks run inside the scheduler pod, at most 4 at a time, with no broker and no pod per task. DAGs come from git-sync once `dags.gitSync` is set (values header). Database migration and the admin user are Argo CD Sync hooks. Keycloak roles: `aio-admin` → Admin, `aio-business` → Viewer.
   - **Superset:** one web pod and no Redis, so queries run synchronously and alerts, reports and thumbnails are off. It uses the official `-dev` image, because the slim image has neither the PostgreSQL driver nor the OAuth library. Keycloak roles: `aio-admin` → Admin, `aio-business` → Gamma (an Admin grants datasets). The chart is deprecated in favour of the Superset operator; move once that can be pinned.
   - **Appsmith:** Community Edition (Apache-2.0) in one pod, with MongoDB and Redis inside it on a 5 Gi disk. The first account created becomes the administrator; turn off sign-up in Admin Settings afterwards. Back up with `appsmithctl backup`.
   - **APISIX:** standalone, with no etcd and no Admin API; routes are declared in `values/apisix/values.yaml` and changed through a PR to aio-catalog. Apps reach it at `apisix-gateway.apisix.svc:80`. If a route change is not picked up, `kubectl -n apisix rollout restart deploy/apisix`.
7. **AI**, each Enable → Test:
   - **Milvus:** standalone with embedded etcd and local storage on one 10 Gi disk; login required (root password from `milvus-root`). Apps connect to `milvus.milvus.svc:19530`.
   - **Langfuse:** Langfuse v4 with a single-node ClickHouse, a CloudNativePG database, the chart's Valkey, and a small SeaweedFS for S3. On first start it creates the AIO organisation, the project, its API keys and the admin user from `langfuse-secrets`. Apps send traces to `http://langfuse-web.langfuse.svc:3000` with the project keys. On sarvaikam, login is Keycloak only; set `LANGFUSE_ADMIN_EMAIL` to the admin's Keycloak e-mail. Other users see nothing until an admin adds them; give `aio-business` users the Viewer role. Media attachments and batch export are off (their pre-signed links point at the in-cluster S3). On a chart bump, update the ClickHouse and SeaweedFS image tags in `extras/langfuse` by hand.
8. **Buildah build strategy.** Podman / Buildah (catalog id `podman`, a core CI step) adds a third build strategy next to BuildKit (Dockerfile) and Cloud Native Buildpacks.
   - **What it does:** with `build-strategy=buildah`, the `build-image` task builds the repository's Dockerfile with Buildah 1.43.5 and pushes it with the same registry credentials. It writes the same `IMAGE_URL` and `IMAGE_DIGEST` results, so the Trivy scan, Chains signing and the GitOps update work unchanged.
   - **How it runs:** rootless and unprivileged, as uid 1000 in its own user namespace, with chroot isolation and vfs storage. The container keeps only SETUID/SETGID (for `newuidmap` and `newgidmap`) and, like rootless BuildKit, runs with seccomp and AppArmor unconfined.
   - **Choosing it:** `auto` never picks Buildah. A project opts in under Settings → Build strategy, which offers "Dockerfile with Buildah" only while phase 6 is switched on.
   - **When the switch is off:** the platform refuses to save that choice. If the switch is turned off after a project chose it, that project's runs fail with a message saying so.
   - **Switch row:** switching phase 6 sets the `podman` row in `platform_switches`; unlike Biome, it passes no pipeline parameter.
   - **Apply order:** the task before the pipelines:
     ```bash
     kubectl apply -n ci -f tekton/tasks/build-image.yaml
     kubectl apply -n ci -f tekton/pipelines/ci-pipeline.yaml -f tekton/pipelines/ci-build-v2.yaml
     ```
   - **Not yet tested:** no real Buildah build has run. Make the first one a test project on AKS.
9. **Switch on**, once capacity allows and steps 2-3 are done: Connectors → Phases → phase 6 → **Switch on**. The connectors install in dependency order, and the `podman` switch is turned on. Then run steps 5 (Dependency-Track and OpenBao) and 4 (Crossplane providers), and Test each connector.

Kept planned in phase 6, with the reasons:
- **Dragonfly** (P2P image distribution) needs a privileged host-network DaemonSet that rewrites containerd's registry config on every node. The chart turns off TLS verification for docker.io and ghcr.io by default, and the node changes stay after uninstall. On 2 nodes, peer-to-peer saves almost nothing. Reconsider at 10+ nodes, with the registries named, `skipVerify: false` and a node cleanup step.
- **Koordinator** (colocation scheduling) has a node agent that shrinks the CPU set of every BestEffort pod, including Argo CD and cert-manager, and leaves cgroup and Node changes behind after uninstall. Its colocation config is hard-coded in the chart. Reconsider once the platform pods have resource requests and the cluster has spare capacity to lend.
- **Jaeger**: Grafana → Explore → Tempo already gives trace search, the trace view and links to logs. The Tempo bridge for the Jaeger UI is version-fragile.

Trade-offs to know:
- The Langfuse chart's bundled Valkey checks its health with an unauthenticated `valkey-cli ping`. That passes, because valkey-cli exits 0 on NOAUTH, but it only proves the process answers. Langfuse itself proves the password.
- `pin.py` reports Crossplane as too new because the chart index stamps every release with 2026-11-18. The GitHub releases are from 2026-10-22 (2.4.3) and 2026-09-22 (2.4.2), as noted in its `pinNote`.
- Buildah and Falco need relaxed Pod Security (unconfined profiles, and privileged for Falco). If the `ci` or `falco` namespace ever enforces baseline or restricted Pod Security, label those namespaces `privileged`.

## Activation (phase 7)

Pinned versions (rollback target in brackets):
- **Developer tools:** gitea 12.7.0 / Gitea 1.27.0 (12.6.0), sonarqube 2026.5.1004 / Community Build 26.9 (2026.5.1003).
- **App runtime:** dapr 1.19.1 (1.19.0), temporal 1.8.0 / server 1.32.2 (1.7.0), camel-k 2.11.0 (2.10.2), volcano 1.16.0 (1.15.3).
- **Data operators:** spark-operator 2.5.2 (2.5.1), flink-kubernetes-operator 1.16.1 (none), opensearch-operator 3.0.14 / operator 3.0.0 (3.0.12), tidb-operator v1.6.6 (v1.6.5), doris-operator 25.8.0 (none).
- **Single shared services:** pulsar 4.8.0 / Pulsar 5.0.0 standalone (4.7.0).
- **Business apps:** erpnext 8.0.91 / ERPNext v16.54.0 (8.0.90), jitsi-meet 3.12.0 / stable-11248-3 (3.11.1).

Every connector here works on AKS, GKE, EKS and on-prem; Jitsi Meet's group-call media path needs a per-cloud overlay. Data systems are operators only: apps create their own clusters from the example in each values header, so those costs land in the app's namespace. Gitea, SonarQube, Temporal and ERPNext keep their databases on CloudNativePG (phase 5), except ERPNext, which needs MariaDB and runs the official image from its chart.

**Capacity.** The phase 7 connectors together request about 1.9 CPU and 8.5 GiB of memory, with no app clusters running. The heavy ones are SonarQube (2.2 GiB), ERPNext (1.5 GiB), Temporal and Pulsar (0.9 GiB each) and Jitsi Meet (0.9 GiB). On the reference cluster, enable them one at a time. Some app clusters cannot fit on the reference cluster at all: a Doris cluster needs about 8 CPU and 16 GiB, and a TiDB cluster about 1.2 CPU and 2.6 GiB.

1. Push and tag the catalog. Re-apply `deploy/connectors-project.yaml`: 14 more chart repositories and their namespaces. The Spark and Flink operators also write Roles into `dev` and `staging`. Move `CONNECTORS_REVISION` to the tag.
2. **Keycloak (sarvaikam only).** Run `python tools/gen_sso.py && bash sso/keycloak/import.sh`. This creates the clients `aio-gitea` (with a `groups` claim), `aio-temporal` and `aio-erpnext`. It also turns off the PKCE requirement for clients whose tools don't send PKCE: Gitea, Harbor, Argo CD, Temporal and ERPNext. Before this change, every client required it, so Keycloak would have rejected those logins.
3. **Secrets, before Enable.** Run these from aio-catalog:
   - `bash setup/gitea-secrets.sh`
   - `bash setup/sonarqube-secrets.sh`
   - `bash setup/erpnext-secrets.sh` (on sarvaikam, with `SITE_URL=https://erpnext.tools.sarvaikam.com`)
   - `bash setup/jitsi-meet-secrets.sh`
   - `bash setup/pulsar-secrets.sh`
   - `bash setup/temporal-secrets.sh`, only for the Keycloak login.

   For tools with Keycloak login, set `OIDC_CLIENT_SECRET` on the run so the script also writes the login Secret.
4. **Developer tools**, each Enable → Test:
   - **Gitea:** Git over HTTPS only (no SSH) and no Actions runner. Nothing is visible without signing in, and self-registration is off. Keycloak `aio-admin` makes a user a site admin, and `aio-business` makes them a restricted user. Users with neither role cannot sign in. The admin `gitea_admin` password is in Secret `gitea-admin`.
   - **SonarQube Community Build:** after Enable, a PostSync job replaces `admin/admin` with the password from `sonarqube-admin`. Running `setup/sonarqube-secrets.sh` again once SonarQube is up checks that, and stores a CI analysis token in `sonarqube-ci-token`. There is no OIDC in the Community Build, so it sits behind the SSO gate and users still sign in to SonarQube itself; SonarQube's built-in SAML is a possible later step. Elasticsearch runs without the privileged sysctl init container (bootstrap checks off). For big indexes, raise `vm.max_map_count` through the node pool (AKS `linuxOSConfig`, GKE `linuxConfig`).
5. **App runtime**, each Enable → Test:
   - **Dapr:** the control plane runs a single replica of each service, except the scheduler, which the chart fixes at 3 members with 1 Gi disks. On AKS that uses 3 of the nodes' free disk attachments. An app opts in with the pod annotations `dapr.io/enabled: "true"`, `dapr.io/app-id` and `dapr.io/app-port`. The injector webhook ignores every other pod and fails open.
   - **Temporal:** frontend, history, matching, worker and web UI, with PostgreSQL for both stores. Schema setup and the `default` namespace (3 days retention) are Argo CD Sync jobs that are safe to rerun. The first install crash-loops for a few minutes until the schema exists. `numHistoryShards` is 16 and can never change. Apps connect to `temporal-frontend.temporal.svc:7233` **without auth or TLS**: any pod in the cluster can use Temporal. On sarvaikam the UI sits behind the gate (`aio-admin` only) as well as its own Keycloak login, because the UI lets anyone it admits cancel or terminate workflows.
   - **Camel K:** the operator only. Integrations run from images the AIO pipeline builds (`kamel run --image`). For in-cluster builds, run `setup/camel-k-registry.sh` after Enable with a registry that serves HTTPS, such as Harbor with a robot account, ACR, Artifact Registry or ECR. Builds need internet access to Maven Central and run one at a time inside the operator pod.
   - **Volcano:** a second scheduler named `volcano`, used only by pods that ask for it. The default scheduler is untouched, and its admission webhooks cover only Volcano's own resources. On GKE the extras add the ResourceQuota that `system-cluster-critical` pods need outside kube-system.
6. **Data operators**, each Enable → Test:
   - **Spark operator:** Spark jobs run only in `dev` and `staging`, with the example SparkPi in the values header. Its webhooks touch only Spark pods, so a down operator only blocks new Spark jobs. Adding `prod` means adding it to `spark.jobNamespaces` and to the project.
   - **Flink operator:** watches `dev` and `staging` only, with webhook certificates from cert-manager. It has **no rollback version**, because Apache publishes one Helm repo per release: to go back, change `chartRepo` and `version` together. The chart's fixed webhook keystore password stays as it is, because the key it protects is in the same Secret in plain text.
   - **OpenSearch operator:** apps create `OpenSearchCluster`s. The example uses a per-cluster cert-manager CA, the admin password from `setup/opensearch-cluster-secrets.sh <ns> <cluster>`, and a single 1 GiB node with mmap off, so it needs no privileged sysctl. Loki stays the log store; OpenSearch is for app search.
   - **TiDB operator:** the CRDs come from the release file in `extras/tidb/crds.yaml` and are kept when the connector is disabled (Prune=false), so app databases survive. Replace the file on every version bump.
   - **Doris operator:** the chart is from SelectDB, the repository the Apache Doris docs name. It has **no rollback version**, because the previous chart hard-codes 2 CPU and 4 GiB for the operator. A Doris cluster does not fit the reference cluster.
   - **Pulsar:** one shared standalone Pulsar (ZooKeeper, BookKeeper and broker in one JVM) with JWT auth. Apps are separated by tenant, namespace and token role. Mint an app token with `APP_ROLE=<role> APP_NAMESPACE=<ns> bash setup/pulsar-secrets.sh`. There is no replication; use Strimzi for production streams. **Roll back only on an empty install:** 4.7.0 runs Pulsar 4.0, which is not tested on data written by 5.0.
7. **Business apps**, each Enable → Test:
   - **ERPNext:** MariaDB and two Valkeys run on official images, with NetworkPolicies limiting them to the namespace. All ERPNext pods share one RWO disk, so they run on one node. The first sync creates the site, which takes 5-10 minutes; then sign in as Administrator and finish the wizard. Keycloak login is set up inside ERPNext, under Integrations → Social Login Key, as described in the site overlay. ERPNext does not map Keycloak roles, so an admin assigns roles in ERPNext. On a chart bump, update the image tag in both `values/erpnext/values.yaml` and `extras/erpnext/site-job.yaml`.
   - **Jitsi Meet:** behind the SSO gate (`aio-admin` only), so `aio-business` users, outside guests and mobile apps cannot join. Without a site overlay only two-person calls work. On sarvaikam the bridge gets UDP 10000 on the existing public IP. Azure accepting UDP next to Traefik's TCP rules on that one IP is not yet proven: check it first, and if it fails, give the bridge its own public IP. The bridge needs roughly one vCPU per 20-40 active video participants.
8. **Switch on**, once capacity allows: Connectors → Phases → phase 7 → **Switch on**, then Test each connector.

Kept planned in phase 7, with the reasons:
- **No official chart in an https Helm repository:** Odoo, SuiteCRM (which has no official image either), MediaWiki (Wikimedia's charts are for Wikipedia's production stack), Apache Answer (its chart is only in the source repo) and DolphinScheduler (OCI only, needs about 2.5 GiB around the clock, and its operator is alpha).
- **Unmaintained:** ShardingSphere on Cloud, whose last release was in 2023.
- **Would replace part of AIO:** Backstage and GitLab CE.
- **Overlap with what's already managed:** SkyWalking, HertzBeat and OpenNMS overlap Prometheus, Grafana, Tempo and Gatus.
- **Niche or not needed on a single cluster:** Nacos, Seata and Higress serve the Spring Cloud Alibaba stack. Karmada and KubeEdge are for multi-cluster and edge setups.
- **Needs GPU nodes:** vLLM.

## Adding a connector

Open a pull request in **aio-catalog**:

1. Add `catalog/<category>/<id>.yaml` and `values/<id>/values.yaml`, plus overlays if needed.
2. Run `python tools/pin.py`, `python tools/validate.py --render` and `python tools/build.py`.
3. Tag a release, apply `deploy/connectors-project.yaml` if it changed, and move `CONNECTORS_REVISION` to the new tag.

The repo's POLICY.md has the promotion checklist. No AIO code change is needed.
