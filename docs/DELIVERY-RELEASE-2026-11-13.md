# AIO delivery release — 2026-11-13

This release adds registry configuration/credential controls, an opt-in independent Tekton build and GitOps release path, digest rendering, artifact policy foundations, and prepared SSO manifests.

Production rollout keeps `AIO_INDEPENDENT_DELIVERY=false` and both tool UI URLs empty. Existing projects retain legacy delivery. `ci-build-v2` is installed alongside the existing pipeline. External registries currently support manual build-only runs; workload pull provisioning, per-user GitHub App authorization, signing/admission enforcement, and tenant Dashboard access remain unfinished.

Before enabling independent delivery, provision a separate read-only source Secret `github-source-token` (`token`) and managed-registry reader `registry-read-credentials` (`config.json`). Keep GitOps writer credentials out of build pods. Per-project configuration approval also selects the independent pipeline, so wait for these prerequisites before approving `.aio/project.yaml`.

Argo CD and Tekton UI exposure requires dedicated Keycloak clients, exact callbacks, scoped platform-managed groups, TLS certificates, and direct-access/authorization tests. The `helm/tool-access` chart is included for review and is not installed by this release.

The release is prepared in an isolated worktree from remote main so newer security fixes and other applications' GitOps values are preserved. Unrelated local edits are excluded. Backend verification and frontend build run before the image is submitted; the Docker build also runs backend tests. V8/V9 are additive Flyway migrations; existing migrations are unchanged.

The rollout uses the existing Cloud Build → Artifact Registry → GitOps → Argo CD path. A fresh Cloud SQL backup is requested before migrations. Image digest, source/release commits, backup result, schema version, and live health verification are recorded after rollout.
