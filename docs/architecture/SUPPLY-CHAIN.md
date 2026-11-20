# Supply chain: Buildpacks, Tekton Chains, Kyverno

**Status:** implemented in the repository, not yet verified on a live cluster. **Date:** 2026-11-17.

Three open-source add-ons on top of Tekton + Argo CD:

| Add-on | Version | What it does in AIO |
| --- | --- | --- |
| Cloud Native Buildpacks (Paketo noble builder) | `ubuntu-noble-builder:0.0.203`, lifecycle 0.21.22 | Builds repos that have no Dockerfile |
| Tekton Chains | v0.29.9 | Signs each built image and attaches signed SLSA v1.0 provenance |
| Kyverno | v1.19.2 (chart 3.9.2) | Checks signature + provenance when Pods start in `dev`, `staging`, `prod` |

```
clone ─► test ─► build-push (build-image task) ─► scan ─► deploy-dev
                   │ detect: Dockerfile? ── yes ─► Kaniko
                   │                      └─ no ─► Buildpacks (lifecycle creator)
                   └─ results IMAGE_URL / IMAGE_DIGEST / CHAINS-GIT_*
                        └─► Tekton Chains (watches TaskRuns) ─► registry: <image>:sha256-….sig / .att
Argo CD sync ─► Pod CREATE ─► Kyverno aio-verify-signed-images ─► PolicyReport (Audit) or reject (Deny)
```

## Install

```bash
bash bootstrap/install-supply-chain.sh                 # Audit mode
POLICY_MODE=Deny bash bootstrap/install-supply-chain.sh   # enforce
```

`gcp-setup.sh` runs it after the secrets step (`SUPPLY_CHAIN=0` skips). It needs `kubectl`, `helm`, `openssl` and the `ci/registry-credentials` Secret, and it is safe to re-run: an existing signing key is kept.

The script:
1. Installs Chains and applies [chains-config.yaml](../../supply-chain/chains/chains-config.yaml).
2. Generates an ECDSA P-256 key into `tekton-chains/signing-secrets` (only if missing) and writes the public half to `supply-chain/keys/chains.pub`. The public key is safe to commit. The private key never leaves the cluster.
3. Creates `ci/registry-signing` (dockerconfigjson). The `pipeline-runner` service account lists it in `imagePullSecrets`, which is how Chains authenticates when it pushes `.sig`/`.att`.
4. Installs Kyverno and copies registry read credentials to `kyverno/registry-read` (prefers `ci/registry-read-credentials`).
5. Renders and applies the [ImageValidatingPolicy](../../supply-chain/kyverno/verify-aio-images.yaml.tmpl) for `APP_REGISTRY` (default `ghcr.io/satrishabh`).

## Buildpacks

- `build-strategy` pipeline parameter: `auto` (default), `dockerfile`, `buildpacks`. The portal does not pass it yet, so every project uses `auto`. The strategy used is a task result (`BUILD_STRATEGY`) and, for `ci-build-v2`, a pipeline result.
- Apps must still listen on 8080 and answer `GET /health` (chart defaults). Spring Boot does by default. For Node and others, read `PORT` (Paketo sets it to 8080) or hard-code 8080. Some stacks need a `Procfile` to name the start command.
- Buildpack images include an SBOM (Syft) in their layers.
- The builder runs as uid 1001 on a private copy of the source; the Kaniko step still runs as root, as before.
- Both builder images are pulled for every build (steps cannot be skipped individually). Nodes cache them, but the first pull of the Paketo builder is large.
- Configure per repo with `project.toml` or `BP_*` variables (for example `BP_JVM_VERSION=21`).

## Chains

- Signs images from TaskRuns whose results are `IMAGE_URL` + `IMAGE_DIGEST` (only `build-image`).
- Format `slsa/v2alpha4` = SLSA provenance v1.0 (`https://slsa.dev/provenance/v1`), including the git URL and commit (`CHAINS-GIT_*`).
- `transparency.enabled: false`: nothing goes to the public Rekor log, so private image names stay private. Verifiers must skip the tlog check (`--insecure-ignore-tlog`, `insecureIgnoreTlog`).
- **External user registries (independent delivery mode)**: Chains uses the service account's credentials, not the per-run push Secret, so it cannot sign images pushed to a user-owned registry yet. Those builds succeed but stay unsigned. Fixing it needs per-run signing credentials or `storage.oci.repository` pointed at a platform-owned repository.

Check a run:
```bash
kubectl -n ci get taskrun -l tekton.dev/pipelineTask=build-push \
  -o custom-columns=NAME:.metadata.name,SIGNED:'.metadata.annotations.chains\.tekton\.dev/signed'
cosign verify --key supply-chain/keys/chains.pub --insecure-ignore-tlog ghcr.io/<owner>/<app>@sha256:…
cosign verify-attestation --key supply-chain/keys/chains.pub --insecure-ignore-tlog --type slsaprovenance1 ghcr.io/<owner>/<app>@sha256:…
```

## Kyverno policy

- Only Pods in `dev`, `staging`, `prod`, only images under `APP_REGISTRY`; sidecars and other registries are ignored.
- Three checks: signed by the Chains key; signed SLSA v1.0 attestation; builder id `https://tekton.dev/chains/v2`.
- Audit mode: `failurePolicy: Ignore`, results in `kubectl get policyreports -n dev`. Deny mode: `failurePolicy: Fail`, unsigned Pods are rejected and Argo CD shows the sync as degraded.
- Uses the `policies.kyverno.io` `ImageValidatingPolicy` API because Kyverno 1.19 deprecates the legacy `kyverno.io` `ClusterPolicy` types.

### Moving to Deny

1. Every running image must have been rebuilt after Chains was installed; older images are unsigned. Re-run CI or promote a fresh build in each environment.
2. `kubectl get policyreports -A` shows no failures for `aio-verify-signed-images`.
3. `POLICY_MODE=Deny bash bootstrap/install-supply-chain.sh`.
4. A scan-gate "accept risk" re-run is still signed, because signing is independent of the Trivy gate.

## Key rotation

Delete `tekton-chains/signing-secrets`, re-run the script (new key + new `chains.pub`, policy updated), then rebuild images. During the switch, keep Audit mode, or add the old public key as a second attestor.

## Not done yet

- Portal: build strategy setting per project, "signed / provenance" badge on runs (`chains.tekton.dev/signed` annotation), policy report view.
- Signing for external user registries (see above).
- Keyless signing (Fulcio) or cloud KMS (`signers.kms.kmsref`) in place of the cluster-held key.
- `install.ps1` equivalent.
