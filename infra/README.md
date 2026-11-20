# Rebuild AIO on GCP with Terraform

This creates a **new, isolated environment** for the current AIO application, with room for later service extraction. It does not adopt the running cluster, migrate application data, provision Keycloak/Learn, or implement the proposed orchestration code. Use [migration gates](../docs/architecture/MIGRATION.md) before moving existing traffic or data.

Validated locally on 2026-11-08: Terraform schema validation, mocked plan tests, helper tests and chart rendering. **No Terraform apply or fresh-environment deployment has been performed.** A real plan, quota/policy checks and the acceptance tests below are required before calling a new environment ready.

## What owns what

| Layer | Files / owner | Creates |
| --- | --- | --- |
| State bootstrap | `terraform/bootstrap` / Terraform | Private, versioned GCS state bucket; protected from deletion |
| Foundation | `terraform/foundation` / Terraform | VPC/subnet, NAT, private nodes, restricted GKE control plane, static ingress IP, Artifact Registry, private PostgreSQL 18, PITR/backups, workload identities, empty Secret Manager containers |
| Runtime | `terraform/runtime` / Terraform | Namespaces, KSAs/WIF annotations, scoped AIO RBAC, Traefik ingress, cert-manager, Argo CD |
| CRD bootstrap | Commands below / operator | Pinned Tekton Pipelines and Argo Rollouts; repo-owned Tekton tasks/pipelines; AppProjects/issuer/platform Application |
| Secrets | Secret Manager / operator helper | Explicit versions copied into Kubernetes, outside Terraform state |
| Application | New GitOps repository / Argo CD | Shared Helm chart, platform image/values; AIO creates later workload apps |
| Data/identity/DNS | Operator / external services | SQL grants, optional data restore, OIDC client/roles, DNS records, public TLS verification |

Do not run `bootstrap/gcp-setup.sh`, `bootstrap/install.*`, or existing ApplicationSets over Terraform-owned resources. Keep one authority for each object. Do not copy the live `apps/`, `argocd/`, or `platform/deploy/values-platform.yaml` into the new GitOps repo: they reference existing applications, hosts and cluster settings.

Pinned tools/providers: Terraform tested with **1.17.1** (configuration requires >=1.9 <2), Google **7.13.0**, Kubernetes **2.38.0**, Helm **3.0.2**. Commit each root's `.terraform.lock.hcl`; use explicit reviewed upgrades. Charts: Traefik **42.3.1**, cert-manager **v1.21.3**, argo-cd **10.15.0**. Tekton **v1.6.0**, Rollouts **v1.10.1**. GKE uses REGULAR release channel, not an indefinitely frozen Kubernetes version; check controller compatibility at creation/upgrade time.

Ingress NGINX retired in March 2026. The new stack uses Traefik's NGINX compatibility provider with class `nginx`, preserving the current shared chart's annotations. Native Traefik ingress providers are disabled to avoid double handling. Compatibility requires testing; snippet annotations are deliberately disabled. This does not switch the live controller. [Kubernetes retirement statement](https://kubernetes.io/blog/2026/01/29/ingress-nginx-statement/), [Traefik provider documentation](https://doc.traefik.io/traefik/reference/install-configuration/providers/kubernetes/kubernetes-ingress-nginx/).

## 0. Prerequisites and decisions

Commands below use Bash on Linux/WSL and Python 3.10+ from the repository root. PowerShell users can invoke the same Terraform/Python tools; adapt environment assignment and line continuation. Install Terraform, gcloud with `gke-gcloud-auth-plugin`, kubectl, Helm 3.17+ or compatible Helm 4, Git, Docker/buildx, PostgreSQL 18 client and Cloud SQL Auth Proxy v2. Use an approved signed/checksummed distribution for each tool.

You need a billed GCP project, API/quota permission, a globally unique state bucket, non-overlapping IP ranges, trusted operator/runner **public egress CIDRs**, an isolated GitHub GitOps repo, GHCR publishing/pull credentials, an accessible OIDC issuer, and DNS control. Defaults cost money: regional GKE, at least two e2-standard-4 nodes, HA SQL 2 vCPU/8GB, NAT and a load balancer. Review a current GCP estimate and budget alerts before apply. SQL can explicitly be ZONAL for disposable dev; that trades away HA.

The default regional cluster places nodes in one zone (`region-a`); REGIONAL SQL does not make app nodes multi-zone. Set `node_locations` to multiple valid zones for the required availability and ensure regional quota. CI shares the node pool initially. This is not a hardened multi-tenant CI sandbox.

Nodes use a dedicated account with `roles/container.defaultNodeServiceAccount` plus repository-scoped image reader, following [GKE node identity guidance](https://docs.cloud.google.com/kubernetes-engine/security/configure-node-service-accounts).

Use a dedicated deployment identity, not project Owner. Provisioning requires permissions for API enablement, compute/network/service networking, GKE, Cloud SQL, Artifact Registry, Secret Manager metadata, service accounts and scoped project IAM changes; GCS state access is separate. Have the project administrator grant only the required roles/custom permissions for your organization. The operator/build identity needs Artifact Registry writer on the **new repository**. The secret-loading operator needs accessor on the five **new** secrets and Kubernetes Secret write rights. Runtime AIO receives SQL connection/login roles and WIF; it receives neither Secret Manager accessor nor project administration.

Private SQL needs a runner on the VPC or a correctly routed VPN/peered network. Public control-plane authorization does not provide private SQL connectivity. Plan a trusted administration VM/runner; do not add a public SQL endpoint as a workaround. Trusted egress CIDRs must cover the machine actually running Terraform runtime/kubectl. Never use `0.0.0.0/0`.

```bash
export PROJECT=your-new-gcp-project
export REGION=asia-south1
export ENV_NAME=aio-dev
export STATE_BUCKET=your-globally-unique-aio-state
gcloud auth login
gcloud auth application-default login
gcloud config set project "$PROJECT"
```

Prefer workload identity federation for CI credentials. ADC is used by Terraform Google; Kubernetes providers use the explicit kubeconfig context and the gcloud auth plugin. Neither access tokens nor DB passwords belong in tfvars/state. Treat state and saved plans as privileged operational data nonetheless.

## 1. Create and move state

Enable `storage.googleapis.com`/Service Usage through your project administrator if not already enabled. Copy and edit examples; local inputs are ignored by Git.

```bash
cp infra/terraform/bootstrap/terraform.tfvars.example infra/terraform/bootstrap/terraform.tfvars
# Edit project_id, bucket_name and region.
terraform -chdir=infra/terraform/bootstrap init
terraform -chdir=infra/terraform/bootstrap plan -out=bootstrap.tfplan
terraform -chdir=infra/terraform/bootstrap apply bootstrap.tfplan
cp infra/terraform/bootstrap/backend.tf.example infra/terraform/bootstrap/backend.tf
# Edit the backend bucket to STATE_BUCKET. Keep prefix aio/bootstrap.
terraform -chdir=infra/terraform/bootstrap init -migrate-state
```

Verify remote state and bucket IAM/versioning before removing any local backup. Terraform's GCS backend locks state; do not force-unlock an active operation. Keep bootstrap/foundation/runtime at distinct prefixes. Grant CI bucket object access only as needed; public access prevention is enforced.

## 2. Provision foundation

```bash
cp infra/terraform/foundation/terraform.tfvars.example infra/terraform/foundation/terraform.tfvars
cp infra/terraform/foundation/backend.hcl.example infra/terraform/foundation/backend.hcl
# Edit project/name, trusted CIDRs, ranges, regions/zones, capacity and bucket/prefix.
terraform -chdir=infra/terraform/foundation init -backend-config=backend.hcl
terraform -chdir=infra/terraform/foundation validate
terraform -chdir=infra/terraform/foundation plan -out=foundation.tfplan
# Review: only NEW resources, no existing cluster/SQL replacements.
terraform -chdir=infra/terraform/foundation apply foundation.tfplan
mkdir -p infra/generated
terraform -chdir=infra/terraform/foundation output -json > infra/generated/foundation.json
gcloud container clusters get-credentials "$ENV_NAME" --region "$REGION" --project "$PROJECT"
export KUBE_CONTEXT="gke_${PROJECT}_${REGION}_${ENV_NAME}"
kubectl --context "$KUBE_CONTEXT" get nodes
```

Do not apply if the plan targets `cluster-1`, `aio-002`, existing DNS, or a live state prefix. The foundation enables APIs but not billing, org policy exceptions or resource quotas. If provisioning fails, inspect the cause and re-plan against the same state; never delete state to retry.

## 3. Render environment settings and provision runtime

```bash
cp infra/config.example.json infra/config.json
# Edit all hostnames, GitHub identities, OIDC settings, image tag and email.
python3 infra/scripts/render.py --foundation infra/generated/foundation.json \
  --config infra/config.json --output infra/generated/bootstrap
cp infra/generated/bootstrap/runtime.tfvars.json infra/terraform/runtime/terraform.tfvars.json
cp infra/terraform/runtime/backend.hcl.example infra/terraform/runtime/backend.hcl
# Edit the state bucket; use a distinct runtime prefix.
terraform -chdir=infra/terraform/runtime init -backend-config=backend.hcl
terraform -chdir=infra/terraform/runtime plan -out=runtime.tfplan
terraform -chdir=infra/terraform/runtime apply runtime.tfplan
kubectl --context "$KUBE_CONTEXT" -n ingress-system get service traefik
kubectl --context "$KUBE_CONTEXT" -n argocd rollout status deployment/argocd-server --timeout=300s
```

Use a new render output directory for subsequent renders; the helper refuses overwrites. Foundation and runtime are separate states because Helm/Kubernetes providers need a reachable existing cluster. New service consumers require foundation apply, re-render runtime inputs, runtime apply, database grants and the service's own deployment/migration. Do not place application secrets inside `config.json`.

## 4. Install CI and rollout controllers

Download pinned official manifests into the ignored generated directory. Review their permissions/images and record SHA256 in the environment change record before applying. These are operator-owned bootstrap releases; upgrade them deliberately, not by re-running `latest` URLs. Current AIO posts PipelineRuns directly; Tekton Triggers/EventListener are not required for that path.

```bash
curl --fail --location https://infra.tekton.dev/tekton-releases/pipeline/previous/v1.6.0/release.yaml \
  --output infra/generated/tekton-v1.6.0.yaml
curl --fail --location https://github.com/argoproj/argo-rollouts/releases/download/v1.10.1/install.yaml \
  --output infra/generated/rollouts-v1.10.1.yaml
sha256sum --check infra/controllers.sha256
kubectl --context "$KUBE_CONTEXT" apply -f infra/generated/tekton-v1.6.0.yaml
kubectl --context "$KUBE_CONTEXT" -n tekton-pipelines rollout status deployment/tekton-pipelines-controller --timeout=300s
kubectl --context "$KUBE_CONTEXT" -n argo-rollouts apply -f infra/generated/rollouts-v1.10.1.yaml
kubectl --context "$KUBE_CONTEXT" -n argo-rollouts rollout status deployment/argo-rollouts --timeout=300s
kubectl --context "$KUBE_CONTEXT" -n ci apply -f tekton/tasks/ -f tekton/pipelines/
kubectl --context "$KUBE_CONTEXT" get storageclass
```

Ensure the cluster has a default StorageClass and workspace PVC provisioning succeeds. Do not apply `tekton/rbac/rbac.yaml` over Terraform's namespace/KSA resources or install unused triggers. Tasks currently include hostPath cache and build steps requiring a trusted CI environment; isolate/harden before accepting untrusted source. [Tekton installation](https://tekton.dev/docs/pipelines/install/).

## 5. Prepare SQL privileges or restore data

Terraform registers Cloud SQL IAM service-account users but does not grant PostgreSQL schema privileges. Generated `database-grants.sql` grants CONNECT and USAGE/CREATE for each consumer on **fresh empty** databases. AIO's current startup Flyway needs CREATE; Hibernate validates, not creates, schema. No user receives CREATEDB/CREATEROLE from these grants.

Set an administrator password securely through the Cloud SQL console/approved password manager, outside Terraform. From a VPC-connected runner with Cloud SQL Client permission, start the proxy; authenticate `psql` as the SQL administrator with an interactive password prompt or protected passfile:

```bash
# Separate terminal on the trusted private-network runner:
cloud-sql-proxy --private-ip --address=127.0.0.1 --port=55432 PROJECT:REGION:INSTANCE
# Copy the rendered non-secret SQL to that runner, then:
psql -h 127.0.0.1 -p 55432 -U postgres -d postgres -W -v ON_ERROR_STOP=1 \
  -f infra/generated/bootstrap/database-grants.sql
```

Use the foundation `sql_connection_name` output instead of the placeholder. A fresh instance must have its postgres credential established before this step. IAM login uses short-lived tokens via the Java connector, with `DB_PASSWORD` set to an empty string. Google IAM database login and SQL table/schema access are separate checks. [Cloud SQL IAM login](https://docs.cloud.google.com/sql/docs/postgres/iam-logins).

For a migration, **do not run AIO before restoring**. Follow the [database transfer and ownership steps](../docs/architecture/MIGRATION.md#database-migration-sequence). A restored database requires grants/ownership for existing tables/sequences and an appropriate Flyway owner; the fresh-create script is not a complete existing-data privilege migration. Separate Flyway credentials remain a planned code/config change.

## 6. Build image and seed the isolated GitOps repo

Build a reviewed source revision, run its tests, and use a never-reused tag (full source SHA recommended). The renderer rejects `latest`; immutable registry tags prevent overwrites. The existing Dockerfile context is `platform/`. The operator/build identity needs writer access on this environment's Artifact Registry repository; nodes already have reader access.

```bash
export IMAGE_REPOSITORY="$(terraform -chdir=infra/terraform/foundation output -raw image_repository)"
export IMAGE_TAG="$(git rev-parse HEAD)"
gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin "${REGION}-docker.pkg.dev"
docker buildx build --platform linux/amd64 --file platform/Dockerfile \
  --tag "${IMAGE_REPOSITORY}:${IMAGE_TAG}" --push platform
```

Set `image_tag` to that exact tag before the initial render (or re-render into another directory), and record the resulting image digest. Dockerfile runs backend tests; additionally run frontend checks for the reviewed revision. Do not build from a dirty worktree under a clean SHA tag.

Create a **new empty** GitOps repository with a main branch; clone it into a separate directory, e.g. `../aio-dev-gitops`. Copy only:

```bash
mkdir -p ../aio-dev-gitops/helm ../aio-dev-gitops/platform ../aio-dev-gitops/apps
cp -R helm/app ../aio-dev-gitops/helm/
cp infra/generated/bootstrap/values-platform.json ../aio-dev-gitops/platform/
# Review, git add/commit/push INSIDE ../aio-dev-gitops, not this source repository.
```

The platform Application references `helm/app` and `../../platform/values-platform.json`. Workload values later live in `apps/<app>/values-<env>.yaml`. Argo's platform Project can deploy only to `aio-platform`; workload Project only to `dev/staging/prod`. AIO's API token can manage `apps/*`, not its own platform Application or global Argo configuration.

## 7. Identity, DNS and secrets

Use an existing reachable Keycloak/User Profile Manager or provision one separately. Configure a confidential OIDC client, role claims, web origins and exact redirect:

```text
https://<platform_host>/login/oauth2/code/profile
```

Current role parsing expects client roles under **`aio-platform`** even if the login client ID differs. Easiest isolated setup: a separate realm with client ID `aio-platform`; otherwise explicitly include that role claim/audience and test approved/unapproved users. Do not widen existing live callbacks to `*`. GitHub broker callback belongs to the realm, e.g. `https://profile.sarvaikam.com/realms/aio/broker/github/endpoint`, not the AIO browser callback. Existing GitHub sign-in was confirmed working; don't replace the registered broker URL arbitrarily.

Add A records for `<platform_host>` and workload hosts (or the chosen wildcard base-domain) to foundation `ingress_ip`; manage DNS in the current external provider. This Terraform deliberately does not import/update live DNS. ACME HTTP-01 needs public port 80 and a resolvable hostname. Defaults use **Let's Encrypt staging** to test without production issuance limits. Staging certificates are untrusted: after challenge validation, switch to the production ACME URL and a new issuer/account name in the rendered issuer manifest; update `ingress.issuer` and `APP_CLUSTERS_0_ISSUER` in GitOps values, commit and apply the intended changes, then verify the trusted certificate before user sign-in. Never bypass browser certificate verification for production acceptance.

Argo server is ClusterIP only. Port-forward for operator administration:

```bash
kubectl --context "$KUBE_CONTEXT" -n argocd port-forward service/argocd-server 8443:443
# In another terminal, use Argo CLI login to localhost:8443, verify its initial certificate.
# Retrieve initial admin password securely from Kubernetes/approved UI, not into logs.
argocd login localhost:8443 --username admin
argocd account generate-token --account aio-platform
```

Generate the token only in a trusted terminal and immediately store it securely. Do not paste it into shell history or this repository. Disable/rotate initial Argo admin after another recovery/admin access path exists. The current AIO Java client defaults to skipping internal Argo certificate validation; the generated values retain this for compatibility. Before production, install a trusted Argo certificate and Java trust chain, verify DNS/hostname, then commit `ARGOCD_INSECURE=false`.

Populate **five JSON objects** in Secret Manager through its console or protected temporary files outside the repository, using the prefixed IDs from the foundation `secret_ids` output:

| Secret suffix | Exact JSON keys/content |
| --- | --- |
| `aio-platform-config` | Copy generated `platform-config.template.json`; fill `ARGOCD_TOKEN`; IAM `DB_PASSWORD` remains `""`. DB URL/user, registry settings and public URLs must match outputs/config |
| `aio-platform-oidc` | Copy generated `oidc.template.json`; fill `OIDC_CLIENT_SECRET` |
| `aio-github-token` | `{"token":"<GitHub credential>"}`; source clone/read, GitOps contents write and webhook access as needed; currently also used to construct app registry pull auth |
| `aio-registry-config` | Docker config JSON `{"auths":{"ghcr.io":{"auth":"<base64 user:registry-token>"}}}`; CI push/pull privileges for intended GHCR scope |
| `aio-argocd-repository` | `{"url":"https://github.com/OWNER/NEW-REPO.git","username":"x-access-token","password":"<read credential>"}` |

Current AIO's combined GitHub/registry credential model limits least privilege. If separate credentials are used, ensure its application pull-secret creation still has package-read access; splitting these adapters is follow-up work. Never use broad organization admin tokens for convenience.

Record explicit numeric secret versions, then load them (replace numbers if versions differ):

```bash
python3 infra/scripts/sync_secrets.py --project "$PROJECT" --name "$ENV_NAME" --context "$KUBE_CONTEXT" \
  --config-version 1 --oidc-version 1 --github-version 1 --registry-version 1 --repository-version 1
```

The helper validates object schemas, bounds command durations, captures command output, sends Secrets over stdin using server-side apply, and prints no values. It does not use force-conflicts; investigate field ownership conflicts. It creates five Kubernetes Secrets in `aio-platform`, `ci`, `argocd`. After an apply timeout, inspect resource metadata and re-run the same version set; server-side apply is repeatable. It is a one-shot sync, **not** automatic secret rotation. Re-run with new explicit versions and deliberately restart AIO after rotation; track the version set in the environment record. Delete protected temporary credential files according to your workstation policy.

## 8. Start GitOps and verify

```bash
kubectl --context "$KUBE_CONTEXT" apply -f infra/generated/bootstrap/projects.json
kubectl --context "$KUBE_CONTEXT" apply -f infra/generated/bootstrap/issuer.json
kubectl --context "$KUBE_CONTEXT" apply -f infra/generated/bootstrap/application.json
kubectl --context "$KUBE_CONTEXT" -n argocd get application aio-platform
kubectl --context "$KUBE_CONTEXT" -n aio-platform rollout status deployment/aio-platform --timeout=600s
kubectl --context "$KUBE_CONTEXT" -n aio-platform get pods,ingress,certificate
```

If the image is pending, inspect events for registry access, Secret keys, SQL grants/IAM/private connectivity or OIDC discovery. Read logs through approved access and avoid copying credentials/user data into tickets. Startup failure must not be solved by disabling Flyway validation or ownership checks.

Acceptance checklist:

- Flyway is at the expected schema version, readiness succeeds, JDBC uses the intended database/identity, SQL runtime has no CREATEDB/CREATEROLE.
- Actual platform image digest equals the reviewed build; node pulls do not require a static registry password.
- DNS points to the new IP; HTTP redirects, trusted TLS, callback URLs, GitHub broker login and logout work.
- Admin sees permitted global resources; two ordinary users see only their own projects/builds/deployments/audit/logs; test guessed IDs/downloads/SSE. Create privileged test identities only with explicit authorization and remove them afterward.
- A safe sample app clones, tests, builds, scans, writes **only the new GitOps repo**, deploys to dev, and reaches observed health. Failed scan remains blocked; dev exceptions bind the exact intended image.
- Domain/path rewrite, shared host, www redirect, certificate owner, streaming/log connections and mobile/desktop views work under the new controller. No assumed NGINX parity.
- Promotion/approval and Rollout promote/abort paths work on a disposable test app; do not use real production workloads for the test.
- Backup restore, credential rotation, controller restart and failed external-call recovery have recorded results. Alerts and ownership/support contacts exist before production use.

Keep AIO at **one replica** until durable scheduling/claims are implemented. Do not enable AIO HPA just because GKE has capacity. App namespaces get scoped Secret/LimitRange writes; AIO can patch only dev/staging/prod namespace objects. Legacy per-project namespace deletion is intentionally denied and requires operator review during migration.

Generated default workload hosts initially use HTTP (`APP_CLUSTERS_0_TLS=false`) for dev bootstrap. Once DNS and a production issuer are working, set it to `true` for environments requiring default HTTPS and test each route. The platform host itself already requests TLS. HTTP-01 cannot issue a wildcard certificate; workloads receive individual host certificates unless a separately configured DNS-01 design is introduced.

## 9. Upgrade, recover, adopt or retire

On every change: pin/review versions, validate and test locally, plan each affected state, inspect replacements/IAM changes, apply foundation before runtime, then update GitOps. Run `plan` periodically to inspect drift; resolve ownership rather than applying two tools to the same object. Re-check available versions and controller/Kubernetes compatibility when recreating this environment months later.

```bash
terraform -chdir=infra/terraform/bootstrap validate
terraform -chdir=infra/terraform/foundation validate
terraform -chdir=infra/terraform/foundation test  # mock providers, plan only
terraform -chdir=infra/terraform/runtime validate
terraform -chdir=infra/terraform/runtime test     # mock providers, plan only
python3 -m unittest discover -s infra/tests -v
terraform fmt -check -recursive infra/terraform
```

For offline schema/tests on a fresh checkout, run each root's `init -backend=false` first. A real cloud plan still needs authenticated credentials; mocked tests do not check quotas, organizational policy, Helm install behavior, network reachability or end-to-end application health. [Terraform tests](https://developer.hashicorp.com/terraform/language/tests).

Restore SQL into a new instance first, validate and switch application configuration deliberately. Restore lost Terraform state from bucket versions; reconcile actual resources before the next plan. Never recreate production because a state file appears empty. Never restore an old state version over a running apply or use `state rm` as an attempted repair.

To adopt current infrastructure: capture actual config/IAM and a state backup; create an environment-specific root matching it; import one resource at a time (e.g. the SQL instance ID `projects/PROJECT/instances/aio-002` and cluster ID `projects/PROJECT/locations/REGION/clusters/cluster-1`); run a refreshed plan; require zero unintended replacements before ownership transfer. **Do not point these fresh defaults at current names and apply.** Existing public SQL networking, identity, controllers and Helm release ownership differ. Importing a Helm release/namespace does not import related cloud infrastructure or application data.

Deletion is intentionally blocked by state bucket/SQL/database/Secret container lifecycle guards and cloud SQL/GKE deletion protection. Retirement is a separate reviewed change after data export/restore verification and retention approval: stop Argo reconciliation, revoke integrations, remove DNS/routes, remove application resources/controllers, then runtime, then explicitly relax the required cloud/state guards. Preserve SQL backups and state retention until the agreed date. There is no safe universal `terraform destroy` shortcut for retained databases.

## Remaining application engineering work

See the [blueprint](../docs/architecture/README.md) and [migration runbook](../docs/architecture/MIGRATION.md). Durable sagas/outbox/idempotency, selective client circuit breakers, separate Flyway/runtime privileges, log/audit retention, hardened CI isolation and full dashboards are planned work. This infrastructure creates a place to implement and test them without changing the current application flow.
