# Connectors: open-source tools managed from AIO

**Status:** the framework and the phase 1-3 connectors (Prometheus + Grafana; Trivy Operator, OpenCost, Goldilocks + VPA, kube-green; Velero, Renovate, KEDA, Descheduler) are implemented, unit-tested and chart-rendered. The phase 4 connectors (node autoprovisioning on AKS and GKE; Loki, Tempo, OpenTelemetry Collector; External Secrets Operator; Gatus) are in aio-catalog, validated and chart-rendered at both versions, as of 2026-11-20. None is deployed to a live cluster yet. **Date:** 2026-11-20.

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
| Prometheus + Grafana (AKS sizing) | 280m | 960 Mi | 5 Gi disk |
| Velero (+ node agent on each node) | 200m | 384 Mi | storage account |
| KEDA | 110m | 320 Mi | |
| Goldilocks + VPA recommender | 60m | 320 Mi | |
| Trivy Operator | 50m | 128 Mi | + up to 2 scan jobs of 100m / 128 MB while scanning |
| Descheduler | 50m | 64 Mi | |
| OpenCost | 20m | 96 Mi | |
| kube-green | 20m | 64 Mi | |
| Renovate | 250m | 512 Mi | only while the Monday job runs |
| **Total, steady (phases 1-3)** | **~0.8 CPU** | **~2.3 GiB** | |
| Loki (phase 4) | 100m | 256 Mi | 10 Gi disk |
| Tempo (phase 4) | 50m | 256 Mi | 5 Gi disk |
| OpenTelemetry Collector (phase 4, one per node) | 50m × nodes | 128 Mi × nodes | |
| External Secrets Operator (phase 4, 3 pods) | 30m | 128 Mi | |
| Gatus (phase 4) | 10m | 32 Mi | 1 Gi disk |
| Node autoprovisioning (phase 4) | 0 | 0 | runs in the cloud's control plane |
| **Phase 4 on 2 nodes** | **~0.3 CPU** | **~0.9 GiB** | 16 Gi disk |

On 2026-11-17, one node had 99% of its allocatable memory requested and was using more than that (6.3 GiB). The other node had just been added by the autoscaler and was nearly empty. The cluster cannot grow beyond these two nodes, so every connector must fit in the second node's free space. Check headroom with `kubectl describe nodes | grep -A6 "Allocated resources"` before each Enable. Recommended order on AKS:

1. Velero first. It closes the documented gap: the nightly Postgres dumps currently stay on a disk inside the AKS node resource group.
2. Then kube-green and Trivy Operator (small).
3. Add a spot node pool (below) before Prometheus, KEDA and the rest.

**Scale to zero (Sablier).** On AKS, AIO, Argo CD and Tekton belong to the Sablier group `aio`. They scale to zero when no one uses the portal, and a browser visit wakes them; bots, curl and uptime checks are ignored on purpose. While the group sleeps, connectors keep running, but Argo CD does not sync or self-heal them and the Connectors page is unavailable. That is acceptable for these tools and saves memory. To keep the group up for maintenance without a browser, ask Sablier directly:
```bash
kubectl -n sablier port-forward svc/sablier 18080:10000 &
curl "http://127.0.0.1:18080/api/strategies/blocking?group=aio&session_duration=45m&timeout=5m"
```

**Managed AKS add-ons:** AKS can run KEDA (`az aks update --enable-keda`) and VPA (`--enable-vpa`) itself. Use either the add-on or the connector, never both. If the VPA add-on is on, set `vpa.enabled: false` in `aio-catalog values/goldilocks/values.yaml`.

## Scope: 24 connectors

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

Resource budget: about 0.3 CPU and 1 GiB requested plus a 5 GiB disk on AKS (`values-aks.yaml`), or 0.4 CPU, 1.6 GiB and 10 GiB with the default values.

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
   - Apps opt in through the app chart: `spot: { enabled: true, provider: aks }` (spot toleration) or `provider: gke` (compute-class selector) in an app's dev or staging values. The chart refuses it in a prod namespace. Still to do: the platform writing this into new apps' dev/staging values from `CLOUD_PROVIDER`.

Trade-offs to know:
- Loki and Tempo keep data on one disk each (7 and 3 days). Losing the disk loses that history; metrics in Prometheus are unaffected. Object storage (Blob, GCS, S3) needs a per-cloud identity, like Velero's, and is the next step if logs must outlive a disk.
- The collector reads every container's log on every node, including tainted spot nodes. Lines are never parsed into fields; Loki indexes only the Kubernetes labels (namespace, pod, container, workload).
- The grafana/helm-charts `tempo` chart is deprecated; the catalog uses the grafana-community chart, now with Tempo 3.
- The node-autoprovisioning connector installs only AIO's node policy. The autoprovisioner is the cloud's managed service, so Disable leaves the cloud feature on until the script turns it off.
- EKS: Karpenter needs per-cluster IAM (node role, controller role, interruption queue). It stays a planned entry until AIO runs on EKS.

## Adding a connector

Open a pull request in **aio-catalog**:

1. Add `catalog/<category>/<id>.yaml` and `values/<id>/values.yaml`, plus overlays if needed.
2. Run `python tools/pin.py`, `python tools/validate.py --render` and `python tools/build.py`.
3. Tag a release, apply `deploy/connectors-project.yaml` if it changed, and move `CONNECTORS_REVISION` to the new tag.

The repo's POLICY.md has the promotion checklist. No AIO code change is needed.
