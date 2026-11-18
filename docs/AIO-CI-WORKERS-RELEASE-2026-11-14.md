# AIO CI-tracking release — 14 November 2026 (Azure)

## Problem

On Azure, AIO ran with `AIO_WORKERS_ENABLED=false` so delivery reconcilers could
not apply GCP-era GitOps values. That flag also stopped CI tracking. Runs stayed
`PENDING` with no steps, the run page showed no stages or logs, and **Cancel** on
an already-finished PipelineRun returned Tekton's HTTP 400 ("Once the PipelineRun
is complete, no updates are allowed"). AIO showed that as *Kubernetes operation
failed; check access and cluster availability*.

## Change

Commit `0b1b520` (branch `feat/cloud-agnostic-20261114`): new flag
`app.runtime.ci-workers-enabled` / `AIO_CI_WORKERS_ENABLED` (defaults to
`AIO_WORKERS_ENABLED`) for `PipelineWatcher`, `ScanWatcher`, `RegistryRunCleanup`
and CI PipelineRun pruning. These touch only the `ci` namespace and the platform
database. Cluster reconcile, deployment polling, domains and release workers keep
the original flag and stay **off**. `mvn verify`: 96 tests, 0 failures.

## Build

Built on Azure Tekton (`ci/aio-platform-release-9qtzj`) from the exact commit with
`deploy=false` (no GitOps write). The standard 2 GiB kaniko limit OOMed; the build
used kaniko `--compressed-caching=false --use-new-run` within the same limit,
because the nodes do not have headroom for a larger build.

Image: `ghcr.io/satrishabh/aio-platform:0b1b5206e1de`
Digest: `sha256:33f2e8b48e3c3462dd0b2202d2f01c22596ddaa2314e095e4fc4342f83c1ab73`

## Vulnerability gate — approved risk exception

The HIGH/CRITICAL gate failed: critical=8, high=12, medium=17. The previously
running image `sha256:7a9c896b…de732a` was scanned the same way
(`ci/aio-platform-current-scan-nxpn8`) and has the **identical 20 CVE IDs**. The
release introduces no new findings. The user approved releasing with this exception.

Affected dependencies (unchanged by this release; remediation still required):
Tomcat embed 10.1.55 (fix 10.1.59+), Spring WebMVC 6.2.19 (fix listed in 7.0.9),
pgjdbc 42.7.11 (fix 42.7.12), plus Alpine base packages.

## Rollout

Helm release `aio-platform`, revision 3, chart `helm/aio-platform` with the live
values, only `image.digest` and `config.AIO_CI_WORKERS_ENABLED=true` changed.
Pod Ready with 0 restarts; public `/api/me` returned 401 as expected.

Revision 2 failed with no outage (old pod kept serving). It used
`.deploy/packages/aio-platform-0.1.0.tgz`, an **older build of chart 0.1.0 without
`extraVolumes` support**, which dropped the DB trust mount. Do not use that package;
it should be rebuilt from `helm/aio-platform`. A client-side `kubectl diff` did not
show the missing volume, so compare full pod templates before upgrading.

## Verification

- Run #187 → SUCCEEDED, 7 steps with pods; #186 → FAILED (real Tekton reason);
  stale GCP-era #184 → FAILED "PipelineRun disappeared".
- All #187 step logs readable as `system:serviceaccount:aio-platform:aio-platform`.
