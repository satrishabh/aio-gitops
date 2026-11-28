# To do

## Open (from the 2026-11-20 release)

- [ ] **ghcr.io image cleanup:** add repo secret `GHCR_CLEANUP_TOKEN` on `satrishabh/aio-gitops`
      (classic PAT: `read:packages` + `delete:packages`). Then run the `ghcr-cleanup` workflow as a dry run, check it, and run it for real.
- [ ] **Node memory headroom (watch):** on 2026-11-28 pod memory *limits* were 324% / 315% / 212% of allocatable on the
      three nodes (requests 62% / 60% / 49%); no MemoryPressure, evictions or OOM kills yet. If OOM kills appear, lower the
      largest limits first (Keycloak x3 and Prometheus use ~570-690 Mi each) before adding a node.

## Done (since 2026-11-21)

- Phase 1 connectors (checked 2026-11-28): `connector-prometheus` Synced/Healthy. The four catalog probes (Prometheus query,
  Prometheus ready, Alertmanager, Grafana) pass. Prometheus reports 100% uptime for Prometheus, Grafana, Alertmanager,
  operator and kube-state-metrics over the last 72 h (pods running since 2026-11-21, Grafana since 2026-11-23).
  Node exporters report from all 3 nodes, 180 pods are tracked, and the node and pod dashboards are provisioned (25 in all).
- Connectors catalog: the platform runs `CONNECTORS_REVISION=v2026.11.9` (newer than the planned `v2026.11.3`) and loads
  it without refresh warnings; it lists 61 phase 4-7 connectors (8 / 6 / 16 / 31).
- First Buildah build: test project `aio-platform-buildah-test` on AKS, 2026-11-21. The first run failed on short image
  names (fixed in PR #21); the second built and pushed `aio-platform-buildah-test:971f103b24cc`.
- Spot for dev/staging apps: all 39 dev and staging values files carry `spot: { enabled: true, provider: aks }`
  (PR #19, 2026-11-21; ScaleSafe dev stateless apps PR #38, 2026-11-26). Project CI also runs on spot (PR #34).
- Spring Boot 4 migration: backend on Spring Boot 4.0.9 / Spring Framework 7 (PR #20, 2026-11-21), which removes
  CVE-2026-47884 and CVE-2026-47890. The live platform pod starts with Spring Boot v4.0.9 (checked 2026-11-28).

## Done (2026-11-20)

- Platform from `main` (9c6a478): Helm revision 10, image `aio-platform:9c6a4786ec59`, migrations V10 and V11 applied.
  Pre-deploy backup `aio_rehearsal-20261120T070114Z.dump` in Azure Blob.
- Catalog tag `v2026.11.3`; platform `CONNECTORS_REVISION` moved to it (revision 11).
- Connectors project, platform RBAC, Tekton tasks `lint-biome` and `build-image`, and updated pipelines applied;
  the new pipeline was checked with a build that reproduced the same image digest.
- AKS spot node pool `spot` created.
