# Connectors: open-source tools managed from AIO

**Status:** the framework and the phase 1 and 2 connectors (Prometheus + Grafana, Trivy Operator, OpenCost, Goldilocks + VPA, kube-green) are implemented, unit-tested and chart-rendered; none is deployed to a live cluster yet. **Date:** 2026-11-17.

The **Connectors → Open source** page (admins only) lists every open-source tool AIO works with: what it is, whether it runs, and what an admin can do with it. The list is data, not code: [catalog.yaml](../../platform/backend/src/main/resources/connectors/catalog.yaml). Adding a tool means adding one catalog entry, one values file and one line in the Argo CD project. No Java or React changes are needed.

## Kinds and actions

| Kind | Installed by | Status from | Actions |
| --- | --- | --- | --- |
| core | bootstrap scripts | its Deployment (available replicas) | Test |
| managed | the portal, as Argo CD app `connector-<id>` in project `connectors` | Argo CD health + last test | Enable, Test, Roll back, Use pinned, Disable |
| planned | not yet | — | none (shows the scope) |

- **Test** runs that connector's HTTP probes from the platform pod (in-cluster `*.svc` addresses only), plus a Deployment check when the entry names a `deployment`.
- **Needs**: a connector with `requires` cannot be enabled until those connectors are present (OpenCost needs Prometheus; kube-green needs cert-manager). A connector cannot be disabled while an enabled connector still needs it.
- **Degraded** means Argo CD reports it unhealthy, or Argo CD reports it healthy but the last test failed. Every lookup fails soft: an unreachable tool or a missing permission marks only that connector. The page and the rest of AIO keep working.
- **Roll back** reinstalls the catalog's `previousVersion`. **Use pinned** returns to `version`. Only these two exact versions are accepted; there are no floating tags.
- **Disable** deletes the Argo CD app with cascade: everything the tool installed is removed, including its stored data (Prometheus metrics).
- Every Enable, Roll back and Disable is written to the audit log as `connector.*`.
- `CLOUD_PROVIDER` (`any`, `gke`, `eks`, `aks`, `oke`, `onprem`) marks entries with a `clouds` list that excludes this cloud as "not for this cloud", so the catalog stays cloud-agnostic.

## Scope: 17 connectors

| Group | Connectors | Kind |
| --- | --- | --- |
| CI/CD | Tekton, Argo CD, Argo Rollouts | core |
| Supply chain | Tekton Chains, Kyverno (Buildpacks and Trivy run as pipeline steps) | core |
| Networking | cert-manager, ingress-nginx | core |
| Observability | **Prometheus + Grafana + Alertmanager** | managed (phase 1) |
| Security | **Trivy Operator** | managed (phase 2) |
| Cost | **OpenCost, Goldilocks + VPA, kube-green** | managed (phase 2) |
| Cost | KEDA, Descheduler, Karpenter (EKS/AKS only) | planned |
| Reliability / upkeep | Velero, Renovate | planned |

Not included, because each would replace AIO itself rather than plug into it: Jenkins, GitLab CI, Flux, Spinnaker, Backstage. Commercial-only tools (Kubecost Enterprise, Cast AI, ScaleOps) are excluded too.

### Cheapest compute

Cost tools that work on any cloud: OpenCost (see the cost), Goldilocks + VPA (right-size requests), kube-green (sleep dev and staging off-hours), KEDA (scale idle apps to zero) and Descheduler (repack pods so nodes can be removed). Spot capacity itself is a cloud setting, not something an open-source tool can supply:

- **GKE:** a spot node pool with the built-in cluster autoscaler. Add it in `infra/terraform`.
- **EKS / AKS:** Karpenter, which chooses the cheapest node and prefers spot.

## Schedule

| Phase | Dates | Connectors | Exit check before the next phase |
| --- | --- | --- | --- |
| 1 | 2026-11-17 → 11-21 | Prometheus + Grafana | Test passes 3 days in a row; Grafana dashboards show nodes and pods |
| 2 | 2026-11-24 → 12-05 | Trivy Operator, OpenCost, Goldilocks + VPA, kube-green | Rescan reports exist for dev/staging/prod; OpenCost shows cost per namespace; dev/staging sleep and wake on time |
| 3 | 2026-12-08 → 12-19 | Velero, Renovate, KEDA, Descheduler | Restore drill of the `aio-platform` namespace and the Chains key succeeds; first Renovate PRs merged |
| 4 | 2027-01 | Karpenter (EKS/AKS clusters only); candidates Loki, OpenTelemetry + Tempo, External Secrets Operator, Gatus | Approved individually |

Recurring jobs set up inside the tools when each one is added:

| Job | Schedule (IST) |
| --- | --- |
| Trivy Operator rescan of running images | every 24h |
| Velero backup | daily 01:30, kept 14 days |
| Renovate | weekly, Monday before 06:00 |
| kube-green: dev and staging asleep | 20:00 → 08:00 on weekdays, all weekend |
| Goldilocks recommendations | continuous |

Each phase follows the same cycle: one connector at a time; Enable, then Test; if it misbehaves, Roll back or Disable; move on only when it's healthy.

## Activation (Prometheus, phase 1)

1. Push the repo. Argo CD reads `connectors/kube-prometheus-stack/values.yaml` from the GitOps repo and branch the platform is configured with.
2. Apply the Argo CD project and permission, and the platform RBAC. `gcp-setup.sh` does all three on re-run; by hand:
   ```bash
   kubectl apply -f argocd/projects/connectors-project.yaml
   kubectl apply -f platform/deploy/rbac.yaml        # read-only check of core Deployments
   # add to argocd-rbac-cm policy.csv:  p, aio-platform, applications, *, connectors/*, allow
   ```
3. Deploy the new platform image. Optionally set `CLOUD_PROVIDER=gke`, and `TOOLS_GRAFANA_URL=https://…` if Grafana gets an HTTPS ingress (otherwise the page shows the port-forward command).
4. Connectors → Prometheus + Grafana → **Enable**. Wait for Healthy (a few minutes), then **Test**.

Resource budget on the current 2 × e2-standard-4 cluster: about 0.4 CPU and 1.6 GiB requested, plus a 10 GiB volume.

## Activation (phase 2)

Pinned versions (each released at least two weeks before pinning; rollback target in brackets): trivy-operator 0.38.1 (0.37.0), opencost 2.5.33 (2.5.32), goldilocks 11.2.0 (11.1.1), kube-green 0.7.1 (0.7.0).

1. Push the repo and re-apply `argocd/projects/connectors-project.yaml` and `platform/deploy/rbac.yaml` (or re-run `gcp-setup.sh`). The project now allows the four chart repositories and the namespaces `trivy-system`, `opencost`, `goldilocks`, `kube-green`, plus `dev` and `staging` for the kube-green schedules.
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
   kubectl -n argocd patch appproject apps --type merge --patch-file connectors/kube-green/apps-sync-windows.yaml
   ```
   They block automatic syncs in dev and staging from 20:00 to 08:00 IST on weekdays and all weekend. Manual syncs (portal deploys) still go through, which wakes the app until the next 20:00. Remove the windows when kube-green is disabled (command in the file).

Trade-offs to know:
- Trivy Operator scans `dev`, `staging`, `prod` and `aio-platform`, HIGH and CRITICAL only, fixable issues only, at most two scan jobs at a time. Its ServiceMonitor is off so it works without Prometheus; turn it on in the values file for Grafana dashboards.
- OpenCost uses the cloud's public list prices. Committed-use or negotiated discounts are not reflected unless custom pricing is configured.
- Goldilocks only recommends. The VPA updater and admission webhook are not installed, so nothing is resized automatically.
- The connectors project may create anything in `dev` and `staging` (needed for SleepInfo). Changes still only come from reviewed files in the GitOps repo.

## Adding a connector

1. Add the entry to `catalog.yaml`: id, name, category, kind `managed`, namespace, chartRepo, chart, version and previousVersion (exact, at least two weeks old), releaseName, valuesPath, probes, and optionally `clouds`, `requires`, `deployment` and `extrasPath`.
2. Add `connectors/<chart>/values.yaml`.
3. Add the chart repository and namespace to [connectors-project.yaml](../../argocd/projects/connectors-project.yaml).
4. Add the Deployment name to the `aio-platform-connectors` ClusterRole in `platform/deploy/rbac.yaml` if the entry has a `deployment`.
5. Run `mvn test`. `ConnectorServiceTest` loads and validates the catalog: unknown keys, floating versions, non-HTTPS chart repos, path traversal, probes outside the cluster and unknown `requires` all fail the build. It also checks that each values file and extras directory exists and that the chart repository and namespace are allowed in `connectors-project.yaml`.
