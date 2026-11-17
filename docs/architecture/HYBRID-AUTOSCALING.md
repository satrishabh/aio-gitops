# Application autoscaling with CPU and Istio traffic

**Scope:** applications deployed through AIO. AIO itself stays at one replica because its background workers are not generally coordinated across replicas. **Date:** 2026-11-13.

The shared Helm chart supports one `autoscaling/v2` HPA with app-container CPU utilization and optional Istio HTTP request rate. Kubernetes selects the larger replica recommendation, bounded by `minReplicas` and `maxReplicas`; a low CPU signal does not suppress a high traffic signal. This follows [Kubernetes multiple-metric autoscaling](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/).

CPU remains usable without Istio. Hybrid mode needs the existing CPU metrics API plus an operator-installed Istio data plane, Prometheus scraping of its HTTP metrics, and a Prometheus Adapter exposing the custom service metric. The current cluster has CPU metrics but no observed Istio/Prometheus deployment or custom-metrics API. No service mesh, metrics stack, namespace injection, or live workload HPA is installed/enabled by these source changes.

## Per-environment configuration

Merge [the example](../../helm/app/values-hybrid-autoscaling.example.yaml) into the intended application's `apps/<project>/values-<env>.yaml`:

```yaml
autoscaling:
  enabled: true
  minReplicas: 1
  maxReplicas: 5
  targetCPUUtilizationPercentage: 70
  istio:
    enabled: true
    metricName: istio_requests_per_second
    requestsPerSecondPerReplica: 25
```

For CPU-only operation, leave `autoscaling.istio.enabled: false`. Autoscaling is disabled by default. The initial bounds allow 1–20 desired replicas, with defaults 1–5. Choose measured thresholds and budget/capacity bounds per application. The CPU metric targets container `app`, so Envoy sidecar CPU does not distort application utilization. A positive application CPU request is required. ContainerResource CPU metrics require a compatible Kubernetes version; validate support before applying to another cluster.

The Istio metric describes the same application's Service in the HPA namespace and uses `AverageValue`: 125 aggregate requests/second at a target of 25 requests/second/replica recommends five replicas. CPU can independently recommend more. The default downscale window is five minutes, with bounded scale-up and scale-down policies to reduce oscillation. These settings are configurable in `autoscaling.behavior`.

The chart omits workload `spec.replicas` while the HPA is active. Manual fixed replica counts do not override an active HPA: change its bounds or disable autoscaling before requesting a fixed count. AIO's Stop action writes `scaledDown: true`; the chart then removes the HPA and holds the workload at zero. Start clears that marker and resumes HPA ownership. Placeholder images tagged `0.0.0` do not create an HPA and remain at zero until a real image is selected.

Blue-green applications target the Argo Rollout scale subresource. Traffic is measured for the active Service, excluding the separate preview Service. Preview/retained ReplicaSets can add pods beyond the HPA's desired replica count; include that overlap in cluster and registry budgets and verify the behavior with the installed Rollouts version.

## Metrics integration

Use [the Prometheus Adapter values overlay](../../ops/metrics/prometheus-adapter-istio.values.yaml) with the official `prometheus-community/prometheus-adapter` chart. Configure its Prometheus service URL/port and authentication/TLS using operator-owned values; keep credentials outside Git. Review and merge existing adapter rules rather than replacing a custom-metrics API already used by other workloads.

The rule maps `destination_service_namespace` to Kubernetes namespaces and `destination_service_name` to Services, then exposes:

```text
GET /apis/custom.metrics.k8s.io/v1beta1/namespaces/<namespace>/services/<service>/istio_requests_per_second
```

It sums `rate(istio_requests_total{reporter="destination", ...}[2m])` for that exact Service and namespace. The destination reporter prevents counting both source and destination observations. See [Istio standard metric labels](https://istio.io/latest/docs/reference/config/metrics/) and [Prometheus Adapter rule configuration](https://github.com/kubernetes-sigs/prometheus-adapter/blob/master/docs/config.md).

Missing telemetry is not converted to zero. If an Istio metric cannot be fetched, Kubernetes can still upscale from an available CPU recommendation, while an unavailable metric prevents a normal downscale decision. Verify this failure behavior with the installed controller. An idle service with a previously observed counter can report a real zero rate; a service that has never emitted the series requires traffic before the custom metric becomes discoverable. The initial adapter targets sidecar destination reports. Ambient waypoints require a separately validated reporter/label query; L4-only telemetry cannot supply HTTP request rate.

## Verification before enabling an environment

1. Verify CPU metrics and the workload's app CPU request. Confirm that the selected workload is enrolled in Istio and Prometheus scrapes its destination-side HTTP series with service and namespace labels.
2. Verify the custom-metrics API reports one finite request-rate value for the exact Service. Test two same-named Services in different namespaces to prove isolation. Missing series must produce a missing/error observation, not a fabricated zero.
3. Render/lint the environment values and use Kubernetes server-side dry-run. Check there is only one HPA for the target and that GitOps does not reconcile a fixed replica count over it.
4. Apply to one non-production application. Run separate CPU and HTTP load exercises, then a combined exercise; observe the higher recommendation and min/max bounds. Test metric interruption, low-traffic cooldown, Stop/Start, and blue-green preview traffic before production rollout.
5. Record controller versions, measured thresholds, namespace/service identity, peak pods/resources, scale timings, and rollback values. Disable autoscaling in GitOps and set the intended fixed replica count for rollback.

Local contract tests render and parse the actual chart and validate CPU-only/hybrid metrics, service scoping, stop/placeholder behavior, Rollout targeting, cooldown, and invalid bounds/thresholds:

```powershell
java --class-path <path-to-snakeyaml.jar> ops/tests/HybridAutoscalingTest.java
helm lint helm/app
```

These tests validate configuration contracts; they are not a live traffic or failure-injection test of an installed mesh.

Validation on 2026-11-13: 22 local chart/adapter contract checks passed; Helm lint passed; Kubernetes accepted the hybrid HPA in a server-side dry-run in `dev`. No live HPA was created, and the metrics infrastructure remains absent.
