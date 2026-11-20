# To do

## Open (from the 2026-11-20 release)

- [ ] **Phase 1 connectors:** in the portal, Connectors → Prometheus + Grafana → **Enable**, wait for Healthy, then **Test**.
      Needs about 0.3 CPU, 1 GiB and a 5 GiB disk. Argo CD only syncs while the Sablier group `aio` is awake (open the portal in a browser).
      Exit check: the test passes 3 days in a row, and Grafana dashboards show nodes and pods.
- [ ] **ghcr.io image cleanup:** add repo secret `GHCR_CLEANUP_TOKEN` on `satrishabh/aio-gitops`
      (classic PAT: `read:packages` + `delete:packages`). Then run the `ghcr-cleanup` workflow as a dry run, check it, and run it for real.
- [ ] **Confirm the Connectors page** lists the phase 4-7 connectors from catalog `v2026.11.3`.
- [ ] **Spot for dev/staging apps:** the AKS `spot` pool exists (0-3 nodes, tainted). Apps opt in on the CD page with
      `spot: { enabled: true, provider: aks }` in their dev or staging values.
- [ ] **First Buildah build:** not yet tested; make the first one a test project on AKS (phase 6).
- [ ] **C: drive space:** run Windows Update Cleanup (4.4 GB) and consider `powercfg /h off` (6.3 GB) as administrator.
- [ ] **Spring Boot 4 migration:** removes the two remaining `spring-webmvc` CRITICALs (CVE-2026-47884, CVE-2026-47890),
      currently released under a documented "not reachable" exception.

## Done (2026-11-20)

- Platform from `main` (9c6a478): Helm revision 10, image `aio-platform:9c6a4786ec59`, migrations V10 and V11 applied.
  Pre-deploy backup `aio_rehearsal-20261120T070114Z.dump` in Azure Blob.
- Catalog tag `v2026.11.3`; platform `CONNECTORS_REVISION` moved to it (revision 11).
- Connectors project, platform RBAC, Tekton tasks `lint-biome` and `build-image`, and updated pipelines applied;
  the new pipeline was checked with a build that reproduced the same image digest.
- AKS spot node pool `spot` created.
