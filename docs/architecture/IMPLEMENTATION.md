# Delivery implementation and activation

**Updated:** 2026-11-13. The initial delivery implementation has completed a controlled production rollout. Independent delivery and SSO UI exposure remain disabled pending their prerequisites; this is not full end-to-end feature acceptance.

## Implemented

- Independent `ci-build-v2`: clone, test, build/push, digest-based scan, stable Pipeline results; no GitOps task or GitOps location inputs.
- New run delivery mode and a durable PostgreSQL release queue on the run record. Build completion and release state persist together. A release worker serializes writes with a project row lock, supersedes older intents, checks ownership/destination/scan evidence, retries GitOps failures with backoff, and leaves build success intact. Accepted desired state is reported as `COMMITTED`, not healthy deployment. Manual release retry reuses the artifact.
- Deterministic independent PipelineRun names and reconciliation of submissions with lost local responses. An unconfirmed submission times out visibly rather than remaining pending indefinitely. Existing runs retain `LEGACY` mode.
- Digest-aware Helm image rendering, promotion value handling, and live image observation. New digest releases cannot promote to staging/production before higher-tier evidence verification exists.
- Strict `.aio/project.yaml` validation, approved configuration hashes, exact source-commit resolution for configured projects, destination/connection scope checks, and a standard build-profile test-image allowlist.
- Project-owned registry registration/rotation/revocation using Kubernetes Secrets. Push and scan/read credentials are separate; each build receives separate temporary copies. Terminal-run cleanup checks the cluster for active runs before deleting credentials. API responses and registration diagnostics exclude tokens.
- External-registry **build-only** runs, available through the manual build deploy checkbox. Registry registration records credentials; the build is the access check. The operator host allowlist defaults to GHCR and Docker Hub; no server-side arbitrary-host registry probe is made.
- Normal/Verified/Golden evidence evaluation rules and tests. Requests for a tier do not grant that tier. Signing, provenance collection/verification, SBOM generation, and golden approval storage are not yet connected, so current independent artifacts remain Normal.
- AIO registry controls, separate release status/retry, and configurable Argo CD/Tekton links.
- Renderable `helm/tool-access` manifests: direct Argo OIDC with the existing AIO realm, deny-by-default application read grants, TLS ingresses, and an operator-only Tekton OIDC gateway with a Dashboard ingress NetworkPolicy. This is a deployment artifact, not a configured live login.

## Local activation prerequisites

1. Apply additive Flyway migrations V8 and V9 through the normal backend rollout. Applied migrations V1–V7 are unchanged. Keep one application replica; existing observers and housekeeping are not coordinated for general multi-replica use.
2. Install `tekton/pipelines/ci-build-v2.yaml` alongside the existing pipeline and apply the reviewed changes to `platform/deploy/rbac.yaml` and `tekton/rbac/rbac.yaml`. The new Secret permissions are scoped to `ci`; review the existing broad environment housekeeping role separately.
3. Provision `github-source-token` with key `token`, read-only for permitted source repositories, and `registry-read-credentials` with key `config.json`, read-only for managed images. These must not be GitOps writer credentials. Keep the managed push credential in the existing configured registry Secret. Kubernetes Secret encryption at rest and access/audit restrictions are operator prerequisites.
4. Drain legacy builds before migrating a project. Set `AIO_INDEPENDENT_DELIVERY=true` for a controlled managed-registry pilot, or approve a committed `.aio/project.yaml` on an individual project. The code refuses to start an independent run while legacy runs remain active on that project. Keep new submissions paused and drain releases before rolling the global setting back.
5. For an external registry, register two distinct named connections in the project's registry panel, using separate writer and reader credentials. Commit the example below, then select **Approve repository configuration**. Uncheck **Deploy manual builds to dev after checks pass** before starting the run. Existing automatic push/onboarding flows request deployment and are rejected for external-registry projects until workload pull provisioning is implemented.

```yaml
apiVersion: aio.platform/v1alpha1
kind: ProjectDelivery
spec:
  registry:
    mode: external
    host: ghcr.io
    repository: example-team/example-app
    credentials:
      pushConnection: image-push
      pullConnection: image-pull
  build:
    profile: standard-v1
  artifacts:
    requestedTier: normal
```

For the managed registry use `registry: {mode: managed}` and omit external connection fields. Connection names are at most 32 lowercase letters/digits/hyphens, beginning with a letter. Approving a configuration is an explicit project action; changing the file requires approval again. Credentials remain in Secrets and must not appear in the file. Credential revocation blocks new snapshots; already-running builds may retain their mounted copy until completion. Revoke a compromised token at the registry provider as well.

Configuration:

| Variable | Default / purpose |
| --- | --- |
| `AIO_INDEPENDENT_DELIVERY` | `false`; controlled switch for new managed-registry builds |
| `AIO_SOURCE_SECRET` | `github-source-token`; source read credential |
| `AIO_REGISTRY_READ_SECRET` | `registry-read-credentials`; managed scan/read credential |
| `AIO_REGISTRY_HOSTS` | `ghcr.io,index.docker.io,docker.io`; operator-approved external hosts |
| `AIO_TEST_IMAGES` | Explicit standard-profile test-image allowlist; see `application.yml` |
| `TOOLS_ARGOCD_URL` | Empty until SSO deployment passes acceptance; HTTPS UI root |
| `TOOLS_TEKTON_URL` | Empty until operator gateway passes acceptance; HTTPS UI root |

## SSO deployment preparation

Render and inspect before any live change:

```powershell
helm lint helm/tool-access
helm template aio-tool-access helm/tool-access --values operator-values.yaml
```

Create two dedicated confidential clients in the existing `aio` realm, not the Learn realm:

| Client | Exact proposed callback | Secret destination |
| --- | --- | --- |
| `aio-argocd` | `https://argocd.app.sarvaikam.com/auth/callback` | Existing `argocd-secret`, key `oidc.aio.clientSecret` |
| `aio-tekton-dashboard` | `https://tekton.app.sarvaikam.com/oauth2/callback` | `tekton-pipelines/aio-tekton-oidc`, key `client-secret` |

Provide an independently generated cookie secret under key `cookie-secret` in `aio-tekton-oidc`, according to oauth2-proxy's supported key requirements. Provision secrets through the existing secret-management process. Configure group claims and the intended client audiences; only platform-managed membership may supply `/aio/operators` or per-project entitlements. Verify issuer, audience, nonce/state, exact callback restrictions, session expiry, and logout for both clients.

Create DNS records and trusted TLS certificates for the configured hosts. The chart references existing TLS Secrets and does not automatically create certificates. Configure `argocd.bindings` with exact existing `AppProject/application` names; do not use wildcards for user bindings. Review all existing `argocd-rbac-cm` policies and group mappings because old grants can still confer permissions. Existing admin/recovery access is not removed by this artifact.

The chart emits patches for **existing** Argo ConfigMaps as well as new resources. Do not run a normal Helm install that attempts to take ownership of those ConfigMaps. Review the render, then use the operator's existing GitOps/field-manager process to merge the intended keys. Never force ownership conflicts away without reviewing existing keys. The issuer and URLs must match the actual environment, not merely these defaults.

Tekton is intentionally restricted to `/aio/operators` because current builds share `ci`. Use the upstream Dashboard read-only installation and verify its actual ServiceAccount RBAC, labels, and port against the NetworkPolicy selector. Verify the CNI enforces NetworkPolicy and remove any direct Dashboard ingress, NodePort, or LoadBalancer route. NetworkPolicies are additive: an older allow policy can defeat the new restriction. Do not expose the gateway before direct-access tests pass.

Only set AIO's tool URLs after testing a permitted user and an unrelated denied user through the browser and direct APIs. Tool sessions are separate from AIO's session; the common Keycloak session supplies SSO. The manifests follow [Argo Keycloak integration](https://argo-cd.readthedocs.io/en/stable/operator-manual/user-management/keycloak/), [Tekton Dashboard access control](https://tekton.dev/docs/dashboard/install/), and [oauth2-proxy Keycloak configuration](https://oauth2-proxy.github.io/oauth2-proxy/7.10.x/configuration/providers/keycloak_oidc/).

## Remaining implementation before full acceptance

1. Per-user GitHub App authorization, installation/repository binding, user-owned repository creation, scoped installation credentials for builds, and App webhook lifecycle/deduplication. Current OIDC GitHub sign-in remains separate and repository operations still use the existing GitHub client.
2. Tenant CI/runtime namespace isolation and per-user Dashboard authorization. Dashboard remains operator-only; Argo read grants do not replace workload isolation or restricted AppProjects.
3. External-registry workload pull provisioning across the actual destination clusters, then deployment/promotion acceptance for external images. Current external support is build-only.
4. Trusted builder/task image digest pinning, SBOM/provenance/signing integration, protected evidence storage, cryptographic verification, golden approvals, and deployment admission enforcement. The evaluator is not a signer or verifier.
5. Persisted release Git commit references and exact-revision rollout completion. `COMMITTED` currently means the GitOps write returned or was reconciled by equal desired content; Argo health remains separate in the existing deployment UI.
6. Durable submission dispatch, current-identity entitlement revalidation for background work, complete webhook inbox/outbox handling, deletion tombstones and orphan cleanup, cross-replica coordination, and classified provider failures. The run row is a durable release queue, not the full operation framework in the architecture blueprint.

Run the architecture acceptance matrix in a non-production deployment after these prerequisites. Local tests and manifest rendering cannot establish working live SSO, registry permissions, cryptographic supply-chain guarantees, or tenant isolation.

Local verification on 2026-11-13: the initial workspace backend `mvn -o verify` passed with 90 tests; the isolated release rebased onto remote main passed 89 tests and produced the application JAR. The Vite production build and both Helm charts passed checks. Digest rendering/rejection and an exact Argo application binding were checked. A dedicated PostgreSQL migration rehearsal and authenticated browser/live-provider acceptance remain pending.

## Production rollout — 2026-11-13

- Released through the existing Cloud Build / Artifact Registry / GitOps / Argo CD flow. Source commit `83f8225d033134f9cad42c98d2fe4455a1c50310`; deployment commit `29265b3a29c9794f2395750380ed3e1fc0994efc`.
- Cloud Build `d63fb5f2-7570-4f7e-9ff3-cf230d9c8fba` succeeded. Production runs image digest `sha256:db7d5b0304d29f36d81429823e4c4af5cc657f930ff45a47bcca6b1d3bcb6ffe` (tag `delivery-83f8225d0331`).
- Cloud SQL backup `1794564548817` completed before rollout. Flyway successfully applied V8/V9; current schema is `v9`.
- Argo CD reported Synced/Healthy; the new pod became Ready with zero restarts. Internal readiness is UP, public login/frontend return HTTP 200, and anonymous project API access returns HTTP 401.
- The approved CI Role permissions and runner token-mount change are applied. The build-only pipeline is installed in `ci`; the unused default-namespace copy was removed.
- `AIO_INDEPENDENT_DELIVERY=false`; SSO tool URLs are empty. Scoped source/registry-read credentials and SSO setup remain prerequisites before activation.
- Hybrid application autoscaling is separately implemented in local commit `df88272d1512b6b12f28c49f5489d132e4e15c0b`, with 22 contract checks, Helm lint, and server dry-run passing. No live HPA, Istio, or Prometheus setup was added by this rollout. See [the autoscaling guide](HYBRID-AUTOSCALING.md).

Deployment used an isolated worktree at `.deploy/delivery-20261113`, preserving newer remote security fixes and unrelated local edits. The original working branch was not reset or rebased.
