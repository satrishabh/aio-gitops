# Environments, clusters and safe promotion: brief

Status: agreed 2026-11-21, in progress. Branches `fix/promotion-health` (part A) and `feat/environments-clusters`
(parts B, C), both from `main` at 667b2c4.

## Decisions (from the owner)

1. **Promotion must not say "done" when the target is broken.** On 2026-11-21 three dev → staging promotions of
   user-profile-manager showed DONE while staging crash-looped (`KC_DB_URL is required`): promotion copies only the image
   tag, and staging had none of dev's settings.
2. **A fourth environment that runs outside**, on another cloud's Kubernetes cluster, **after prod**:
   dev → staging → prod → *X*. Promoted from prod, with approval like prod.
3. **Add clusters from the portal (multi-cluster, multi-cloud)** with a **bootstrap script**: Clusters → Add cluster,
   choose the cloud, fill in ingress and domain, run a one-line script against the new cluster that creates a limited
   service account and prints its token, paste the token; AIO registers the cluster in Argo CD and tests it.
   No admin kubeconfig leaves the user's machine.

## A. Safe promotion (`fix/promotion-health`)

- New promotion status **DEPLOYING** between approval/commit and DONE. After `setTag` the promotion is DEPLOYING; a
  watcher (extend `DeploymentPoller` or a small `PromotionWatcher`) checks the target env's Argo CD app: synced to the
  commit and Healthy with the new image → **DONE**; Degraded, or not Healthy within 10 minutes (configurable) →
  **FAILED** with a reason: the failing pod's container state reason (CrashLoopBackOff, ImagePullBackOff,
  CreateContainerConfigError) and the last log lines (max 20 lines / 2 KB, from the Kubernetes API of that env's cluster).
  Existing rows keep their status; the UI shows DEPLOYING with a spinner and FAILED with the reason.
- **Pre-promotion check** (`GET /api/projects/{id}/promotions/check?from=dev&to=staging`) compares the two values files
  and the target namespace: env var names present in source but missing in target; `envFrom` Secrets/ConfigMaps and
  `extraVolumes` ConfigMaps/Secrets that don't exist in the target namespace; `command`/`args`, `containerPort`, probes
  paths that differ. It never reads Secret values (existence only). Returns `{ ok, missing: [...], differs: [...] }`.
- **Promote dialog** shows the check. With findings, the user can (a) **Copy settings** (writes the source's `command`,
  `args`, `containerPort`, `env` (names that are missing only; never overwrites target values), `envFrom`,
  `extraVolumes`, `extraVolumeMounts`, `probes`, `podSecurityContext` into the target values in the same commit as the
  tag; never copies `ingress`, `routes`, `replicaCount`, `resources`, `spot`, `services`, image), or (b) promote anyway
  (typed confirmation), or cancel. Missing Secrets/ConfigMaps can't be copied (they're not in git): the dialog names
  them with the `kubectl` command to create them.
- Tests: watcher transitions (healthy → DONE, degraded → FAILED with reason, timeout), the check's findings on the
  user-profile-manager dev/staging case, copy-settings merge rules.

## B. Environments as data (`feat/environments-clusters`)

Today `GitOpsService.ENVS = [dev, staging, prod]` is used in ~27 backend places and 5 frontend pages, and
`ClusterRegistry` maps each env to exactly one cluster from static config (`APP_CLUSTERS_*`).

- **Migration `V13__environments_clusters.sql`** (V12 is taken by project services):
  - `environments` (`name` PK, `position` int, `promote_from` nullable FK → name, `cluster` → clusters.name,
    `namespace`, `approval_required` bool, `spot_allowed` bool, `prod_like` bool, `created_at`).
  - `clusters` (`name` PK, `cloud` aks|gke|eks|oke|doks|onprem|other, `server`, `base_domain`, `ingress_ip`,
    `ingress_class`, `ingress_controller`, `issuer`, `dns_zone`, `tls` bool, `rollouts` bool, `status`, `last_check_at`,
    `last_error`, `created_by`, `created_at`). Credentials are **never** in the database: they live only in the Argo CD
    cluster Secret.
- **Seeding:** on start, if the tables are empty, insert the clusters from `app.clusters` (or the in-cluster fallback)
  and the environments dev (from none), staging (from dev), prod (from staging, approval, prod_like) exactly as they
  are today, so an upgrade changes nothing.
- **`EnvironmentRegistry`** replaces every `GitOpsService.ENVS` use: ordered list, `promoteFrom(env)`, `isProdLike`,
  `clusterFor(env)` (moved from ClusterRegistry, which keeps cluster lookups). `SpotDefaults` uses `spot_allowed`; the
  chart's "no spot in a prod namespace" rule keeps working by namespace name and additionally the platform refuses spot
  where `prod_like`.
- **Adding an environment** (admin): Clusters → Environments → Add: name (`[a-z][a-z0-9-]{1,19}`), cluster, namespace
  (default = name), promote from (default: the last env, so it goes after prod), approval (default on when promoted
  from a prod-like env), prod-like. On save: create the namespace on that cluster with the same baseline as the others
  (Pod Security labels, default-deny ingress + ingress-controller NetworkPolicy, `ghcr-pull` Secret via
  `ClusterService.ensureEnvironment`), and for each project: `apps/<app>/values-<env>.yaml` (tag `0.0.0`,
  `replicaCount: 0`, ingress host `<app>-<env>.<base domain>`), and its Argo CD Application. Deleting an environment
  is refused while any project has it scaled above 0.
- **Frontend:** every `const ENVS = [...]` becomes data from `GET /api/environments` (ordered, with `promoteFrom`,
  `cluster`, `prodLike`); the pipeline strips, CD page, Overview and Metrics render N environments; layouts that assume
  three columns wrap (phone width included).
- **Metrics:** `MetricsService` treats every `prod_like` env as production for DORA (deploys and rollbacks), keeping
  current numbers unchanged when only `prod` exists.

## C. Add a cluster (`feat/environments-clusters`)

- **Clusters page → Add cluster** (admin), a 3-step dialog:
  1. Name (`[a-z0-9-]`), cloud, API server URL, ingress: controller (traefik|nginx), class, IP, base domain, issuer,
     TLS on/off.
  2. **Bootstrap script** generated for that cluster: `kubectl apply -f -` of a ServiceAccount `aio-deployer` in
     namespace `aio-system`, a long-lived token Secret for it, and RBAC: a ClusterRole to manage namespaces it
     creates (`create`/`get`/`list`/`patch` namespaces), and inside app namespaces (bound per namespace by the
     platform later through Argo CD) the verbs Argo CD needs on apps' kinds; plus read-only cluster-wide
     `get/list/watch` on nodes, namespaces, pods, events, CRDs (for health/rollouts checks). Then it prints the token and
     CA (`kubectl -n aio-system get secret aio-deployer-token -o jsonpath=...`). Document clearly that the token gives
     those rights only, and how to revoke (`kubectl delete ns aio-system` + the ClusterRoleBinding).
     Offer it as a copy button and a downloadable `aio-bootstrap-<name>.sh` (bash) — no secrets in it.
  3. Paste token + CA (or the printed one-line JSON). The platform creates the Argo CD cluster Secret
     (`argocd.argoproj.io/secret-type: cluster`, `config: {bearerToken, tlsClientConfig: {caData}}`) in the `argocd`
     namespace, then **tests**: Kubernetes `/version`, can-i checks for the needed verbs, Argo CD cluster connection
     state. Shows each check pass/fail. Stores the cluster row (no credentials).
- **Remove cluster:** refused while an environment points to it; deletes the Argo CD cluster Secret.
- **Rotate token:** re-run step 2/3 for an existing cluster.
- **Platform RBAC:** create/update/delete Secrets in `argocd` with the cluster label only — check what
  `platform/deploy/rbac.yaml` already grants and add the minimum.
- **Health:** a scheduled check every 5 minutes updates `status`/`last_error` (unreachable clusters show red on the
  Clusters page and their environments show "cluster unreachable", never blocking the rest of AIO).
- Connectors still run only on the platform's own cluster: environments on other clusters show project services as
  "not available on <cluster>" (already in the project-services brief).

## Delivery

- Part A first (small, independent): PR `fix/promotion-health` → main.
- Parts B + C: PR `feat/environments-clusters` → main. They touch the same files as the project-services PRs
  (GitOpsService, ProjectService, ProjectDetail.jsx): rebase onto main after those merge; migrations: services V12,
  this V13.
- Deploy as usual (backup first; the migration is additive; seeding reproduces today's state, check
  `GET /api/environments` returns dev, staging, prod with the same clusters before adding anything).
