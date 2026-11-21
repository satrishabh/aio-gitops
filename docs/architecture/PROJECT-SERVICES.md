# Project services and project export: brief

Status: agreed 2026-11-21, in progress. Branch `feat/project-services` (from `main` at 667b2c4).

## What the owner asked for

1. **Use connectors in projects.** A connector that an admin switched on can be used by any number of projects.
   Each project attaches it per environment from a new **Services** tab. AIO writes the wiring into that project's
   values files in git: its own database or Kafka in the app's namespace, a copied secret, and environment variables
   such as `DATABASE_URL`. **Remove** undoes it.
2. **Leave AIO with your project.** Project → **Export** downloads a zip that runs on any Kubernetes cluster without AIO:
   the chart, each environment's values, Argo CD Application files, a GitHub Actions workflow in place of Tekton, the
   pinned chart and values of every connector the project uses, secret templates and a README.
3. **Roll out the connector phases** on the live cluster, in order, with each phase's exit check
   (see *Rollout* at the end; this is cluster work, not code).

## Principles

- **Data, not code, per connector.** How a project uses a connector is a `binding` block in its aio-catalog entry. Adding
  a bindable connector later is a catalog change, not a Java or React change, the same rule as connectors themselves.
- **Git stays the source of truth.** Everything a binding creates is in the project's values files (`services:` key),
  rendered by the app chart. The values file alone is enough to run the app elsewhere, which is what makes Export simple.
- **Secrets never go to git.** Shared-service credentials (Valkey password, Langfuse keys, Milvus root password) are
  copied by the platform into the app namespace as a Secret it owns, and only referenced from git. Export writes them as
  `*.example.yaml` templates without values.
- **Only where the connector runs.** Bindings are offered only for environments that deploy to the platform's own
  cluster (where connectors run). Other clusters show "not available on <cluster>".
- **Tiers unchanged.** Anyone who may change a project's settings may attach and remove services; viewing is open to
  anyone who can see the project. Connectors stay admin-only to switch on.

## 1. Catalog: `binding` block (aio-catalog)

Added to a connector entry. Optional; entries without it are not offered on the Services tab.

```yaml
binding:
  label: PostgreSQL database                 # what the Services tab calls it
  summary: Your own PostgreSQL cluster in this environment, with a generated user and password.
  scope: instance                            # instance | shared | config
  envs: [dev, staging, prod]                 # where it may be attached (default all three)
  options:                                   # shown as a small form when attaching; values are strings
    - { id: storage, label: Disk size, default: 1Gi, choices: [1Gi, 5Gi, 10Gi] }
    - { id: instances, label: Instances, default: '1', choices: ['1', '2', '3'] }
  manifests: bindings/cloudnative-pg.yaml    # optional, path in aio-catalog; multi-document YAML template
  env:                                       # env vars added to the app container
    - { name: DATABASE_URL, secret: '${name}-app', key: uri }
    - { name: PGHOST, value: '${name}-rw' }
  copySecret:                                # scope shared only: copy one Secret from the connector into the app namespace
    from: valkey-users                       # Secret in the connector's namespace
    keys: { default: password }              # source key -> key in the copy
    name: '${app}-valkey'                    # name of the copy in the app namespace
  podAnnotations: {}                         # e.g. Dapr: dapr.io/enabled etc.
  exportNote: Needs the CloudNativePG operator (connectors/cloudnative-pg).   # line in the export README
```

Placeholders, replaced by the platform (plain string substitution, no template language):
`${app}` project name, `${env}` environment, `${namespace}` app namespace, `${name}` = `${app}-<connector id>`,
`${opt.<id>}` an option's value. Unknown placeholders are a validation error.

`scope` meaning:
- **instance**: the app gets its own copy in its namespace from `manifests` (CloudNativePG Cluster, Strimzi Kafka,
  OpenSearchCluster).
- **shared**: the app uses the connector's single shared service; `copySecret` brings the credentials.
- **config**: only `env` and/or `podAnnotations` (OpenTelemetry endpoint, Temporal address, Dapr sidecar).

**First wave** (connectors already in the catalog):

| Connector | scope | What a project gets |
| --- | --- | --- |
| cloudnative-pg | instance | Own PostgreSQL `Cluster`; `DATABASE_URL`, `PGHOST`, `PGUSER`, `PGPASSWORD`, `PGDATABASE` |
| valkey | shared | `REDIS_URL` / `VALKEY_HOST` / `VALKEY_PASSWORD`, key prefix `${app}:${env}:` in `VALKEY_KEY_PREFIX` (dev, staging only, as the connector) |
| strimzi | instance | Own single-node KRaft Kafka + `KafkaNodePool`; `KAFKA_BOOTSTRAP_SERVERS` |
| opensearch | instance | Own 1-node `OpenSearchCluster` (example from the values header); `OPENSEARCH_URL`, user/password Secret |
| opentelemetry-collector | config | `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME=${app}`, `OTEL_RESOURCE_ATTRIBUTES=deployment.environment=${env}` |
| temporal | config | `TEMPORAL_ADDRESS=temporal-frontend.temporal.svc:7233`, `TEMPORAL_NAMESPACE=default` |
| dapr | config | pod annotations `dapr.io/enabled`, `dapr.io/app-id=${app}`, `dapr.io/app-port` (option, default 8080) |
| langfuse | shared | `LANGFUSE_HOST`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY` (copied) |
| milvus | shared | `MILVUS_URI`, `MILVUS_TOKEN` (copied root credentials; documented as shared admin access) |

Pulsar (needs a minted token per app), KEDA (ScaledObject per app) and External Secrets (key mapping UI) are the
second wave.

Catalog tooling: `tools/validate.py` checks the block (scope, placeholders, that `manifests` exists and renders with
sample values to valid YAML with `metadata.name` containing `${name}` or `${app}`), `tools/build.py` copies it into
`dist/catalog.yaml` and inlines the manifest text as `binding.manifestsText`, so the platform reads one file.
Release as tag `v2026.11.4`.

## 2. App chart: `services:` values (aio-gitops `helm/app`)

Written only by the platform (comment in the file says so); kept per environment in `apps/<app>/values-<env>.yaml`:

```yaml
# Managed by AIO (Project → Services). Edit there, not by hand.
services:
  cloudnative-pg:
    manifests: |            # already substituted, multi-document YAML; rendered as-is into the release
      apiVersion: postgresql.cnpg.io/v1
      kind: Cluster
      ...
    env:
      - { name: DATABASE_URL, valueFrom: { secretKeyRef: { name: shop-cloudnative-pg-app, key: uri } } }
    podAnnotations: {}
```

Chart changes: `templates/services.yaml` renders every `manifests` block (each document gets the chart's labels added);
`deployment.yaml` appends every service's `env` after `.Values.env` and merges `podAnnotations` into the pod template.
`helm template` with no `services` must render byte-identical to today (test).

## 3. Platform (aio-gitops `platform/backend`)

- **Migration `V12__project_services.sql`:** table `project_services` (`id`, `project_id` FK, `connector_id`, `env`,
  `options` jsonb/text, `status` ATTACHED/FAILED, `detail`, `created_by`, `created_at`), unique (`project_id`,
  `connector_id`, `env`). Git is authoritative; the table records who attached what and the options, for the UI and Export.
- **`ConnectorCatalog`:** parse `binding` (record `Binding`, `BindingOption`, `BindingEnv`, `CopySecret`).
- **`ProjectServicesService`:**
  - `available(project)` per env: bindable connectors, whether installed and healthy, whether the env runs on the
    platform cluster, whether the env is allowed.
  - `attach(project, connectorId, env, options, actor)`: checks (connector installed and not Degraded, env allowed,
    env on platform cluster, options valid), copies the secret for `shared`, renders and writes the `services.<id>`
    block through `GitOpsService` (one commit "services: attach <id> to <app> <env>"), records the row, audits
    `project.service-attach`.
  - `detach(...)`: removes the block (commit), deletes the copied Secret, deletes the row, audits
    `project.service-detach`. A detached `instance` service's data is deleted with its manifests when Argo CD prunes:
    the UI says so and asks for the project name to confirm.
  - `usage(connectorId)`: projects/envs using it.
- **ConnectorService:** Disable and phase Switch off refuse while a project uses the connector (list them), unless
  `force` is passed by an admin.
- **API:**
  - `GET /api/projects/{id}/services` → `{ envs: [...], available: [...], attached: [...] }`
  - `POST /api/projects/{id}/services` body `{ connector, env, options }`
  - `DELETE /api/projects/{id}/services/{connector}?env=dev&confirm=<project name>`
  - `GET /api/connectors/{id}/usage` (admin)
  - `GET /api/projects/{id}/export` → `application/zip` (see 5)
- **RBAC:** the platform already reads/writes Secrets in app namespaces; add `get` on Secrets in the namespaces of
  shared connectors (`valkey`, `langfuse`, `milvus`) to `platform/deploy/rbac.yaml`.
- Tests: catalog parse/validate, substitution (unknown placeholder fails), attach/detach writes the expected YAML,
  refuses on another cluster / prod for dev-only connectors / not-installed connector, disable refused while used.

## 4. Portal (aio-gitops `platform/frontend`)

- **Project → Services tab** (between Domains and Settings, icon `box`): one card per attached service, grouped by
  connector, with per-env chips (dev / staging / prod: attached, available, not available + reason); **Add service**
  opens a picker of bindable connectors (label, summary, scope badge, per-env checkboxes, options form). Remove asks
  for the project name when data would be deleted. Empty state explains that an admin switches connectors on first.
- **Connectors page:** each bindable connector shows "Used by N projects" (link list); Disable shows who uses it.
- **Project → Export** button in the project header (and the command palette): downloads the zip; a short dialog lists
  what's in it and the "include every switched-on connector" option.
- Follows `docs/ui/REDESIGN-BRIEF.md`: three themes, 44 px targets, aria patterns, no colour-only status.

## 5. Export bundle

`GET /api/projects/{id}/export[?allConnectors=true]` streams `<app>-aio-export-<date>.zip`:

```
<app>/
  README.md                     how to run it without AIO: prerequisites, install order, secrets to create, URLs
  chart/                        the app chart (helm/app) at the platform's revision
  values/values-dev.yaml        each environment's values as they are in git now (incl. services:)
  values/values-staging.yaml
  values/values-prod.yaml
  argocd/<app>-<env>.yaml       Argo CD Applications pointing at REPO_URL placeholders (their own git repo)
  ci/github/workflows/build.yaml  build (Dockerfile or buildpacks per the project), test command, Trivy scan,
                                push to their registry, bump values-dev.yaml image tag (GitHub Actions; actions pinned by SHA)
  connectors/<id>/application.yaml   Argo CD Application: the catalog's chartRepo, chart, pinned version, values
  connectors/<id>/values.yaml        the catalog values (+ cloud overlay chosen at export time, default "any")
  connectors/<id>/extras/            the catalog extras, if any
  connectors/<id>/setup.sh           the catalog setup script, if any
  connectors/README.md               install order (requires first) and per-connector notes (exportNote, accessHint)
  secrets/<name>.example.yaml        every Secret the app or its services expect, keys without values
```

Connectors included: the ones the project uses plus their `requires`, or every switched-on connector with
`allConnectors=true`. Nothing secret is ever included: no Secret values, no tokens, no webhook secret.
Audited as `project.export`. Test: unzip and `helm template chart -f values/values-dev.yaml` renders.

## Delivery

One PR per part, in this order (2 and 3 can run in parallel once 1's schema is fixed by this brief):
1. aio-catalog: `binding` schema, tooling, first-wave bindings, tag `v2026.11.4`.
2. aio-gitops: chart `services:` + platform services API + migration + RBAC.
3. aio-gitops: portal Services tab, Connectors usage, Export button.
4. aio-gitops: export endpoint and bundle.

Deploy after merge as usual: backup, build on spot, `helm upgrade` changing only `image.digest` (and
`CONNECTORS_REVISION` → `v2026.11.4`), apply RBAC.

## Rollout of the connector phases (cluster)

Per `CONNECTORS.md` → Schedule. Each phase: secrets/setup first, Enable one connector at a time (or phase Switch on),
Test, exit check, then the next phase.

| Phase | State 2026-11-21 | Next |
| --- | --- | --- |
| 1 Prometheus + Grafana | fits now (~0.3 CPU, 1 GiB free on node 1) | owner clicks Enable + Test; exit: Test passes 3 days |
| 2 Trivy Operator, OpenCost, Goldilocks, kube-green | not started | from 2026-11-24, after phase 1's exit check; kube-green needs the sync windows |
| 3 Velero, Renovate, KEDA, Descheduler | not started | Velero setup script (Azure storage + identity), Renovate token |
| 4–7 | not started | capacity: spot pool / Node Auto Provisioning first; secrets scripts per phase |

## API shapes

Defined by part 2 (`ProjectServicesController`, `ConnectorController`); the portal codes against these. All under the
usual session + CSRF rules. Project endpoints need the project (owner or admin, tiers admin/business); connector
endpoints are admin-only. Errors are `{ "message": "..." }` with 400 (bad input: unknown connector or option, option not
in its choices, env not allowed for the binding, missing confirmation), 404 (no such service), 409 (cannot right now:
connector not switched on or not healthy, env on another cluster, Secret conflict, values file changed meanwhile, disable
while used), 502 (Kubernetes / GitHub failure).

### `GET /api/projects/{id}/services`

```jsonc
{
  "envs": [   // always dev, staging, prod in that order
    { "env": "dev", "cluster": "in-cluster", "platformCluster": true, "namespace": "dev" }
  ],
  "available": [   // every connector with a binding that supports this cloud, catalog order
    {
      "connector": "cloudnative-pg", "name": "CloudNativePG",
      "label": "PostgreSQL database", "summary": "Your own PostgreSQL cluster ...",
      "scope": "instance",              // instance | shared | config
      "deletesData": true,              // scope instance: Remove deletes the data (ask for the project name)
      "status": "HEALTHY",              // connector status as on the Connectors page (HEALTHY, PROGRESSING, DEGRADED,
                                        // NOT_INSTALLED, OFF, UNKNOWN, ...)
      "statusMessage": null,
      "usable": true,                   // status HEALTHY or PROGRESSING
      "options": [ { "id": "storage", "label": "Disk size", "default": "1Gi", "choices": ["1Gi", "5Gi", "10Gi"] } ],
      "envVars": ["DATABASE_URL", "PGHOST", "PGUSER", "PGPASSWORD", "PGDATABASE"],   // names only
      "podAnnotations": [],             // annotation keys it adds (Dapr)
      "exportNote": "Needs the CloudNativePG operator ...",
      "envs": {                         // one entry per env
        "dev":     { "state": "attached",    "reason": null, "message": null },
        "staging": { "state": "available",   "reason": null, "message": null },
        "prod":    { "state": "unavailable", "reason": "OTHER_CLUSTER", "message": "not available on aks-prod: ..." }
      }
    }
  ],
  "attached": [   // rows recorded by AIO merged with what is in git (git is authoritative)
    {
      "connector": "cloudnative-pg", "name": "CloudNativePG", "label": "PostgreSQL database",
      "scope": "instance",              // null when the connector is no longer in the catalog
      "env": "dev",
      "options": { "instances": "1", "storage": "5Gi" },
      "status": "ATTACHED",             // ATTACHED | FAILED (attach failed, see detail; Remove clears it)
                                        // | NOT_IN_GIT (row, but the block was removed from git by hand)
                                        // | UNTRACKED (block in git, not attached through AIO)
      "inGit": true,                    // null when the values file could not be read
      "deletesData": true,
      "envVars": ["DATABASE_URL", "..."],
      "detail": null,
      "createdBy": "alice", "createdAt": "2026-11-21T10:00:00Z"   // null for UNTRACKED
    }
  ],
  "warnings": []                        // e.g. a values file that could not be read
}
```

`reason` codes, in the order they are checked: `ENV_NOT_ALLOWED` (binding `envs`, e.g. Valkey is dev/staging only),
`OTHER_CLUSTER` (env deploys to a cluster other than the platform's own), `NOT_INSTALLED` (connector not switched on;
an admin enables it), `NOT_HEALTHY` (Degraded or unknown).

### `POST /api/projects/{id}/services`

Body `{ "connector": "cloudnative-pg", "env": "dev", "options": { "storage": "5Gi" } }`; `options` may be omitted
(defaults are used) and values are strings that must be one of the option's `choices`. One env per call: the portal
calls once per ticked env. Attaching a connector that is already attached in that env updates it (new options, Secret
copied again). Response: one `attached` item as above (status `ATTACHED`). Commit message
`services: attach <id> to <app> <env>`.

### `DELETE /api/projects/{id}/services/{connector}?env=dev&confirm=<project name>`

`confirm` is required (exactly the project name) when the service deletes data (`deletesData`), optional otherwise.
Response:

```json
{ "connector": "opensearch", "env": "staging", "dataDeleted": true,
  "leftBehind": ["staging/shop-opensearch-admin-password"] }
```

`leftBehind`: Secrets of the instance that its operator created without an owner reference (OpenSearch admin password,
cert-manager CA), so Argo CD's prune does not remove them. They are not deleted by the platform (prod has no auto-sync,
and the running instance still needs them until it is pruned); the UI lists them with the `kubectl delete secret`
hint. Commit message `services: detach <id> from <app> <env>`.

### `GET /api/connectors/{id}/usage` (admin)

```json
{ "connector": "valkey", "count": 2,
  "projects": [ { "projectId": 7, "project": "shop", "envs": ["dev", "staging"] } ] }
```

`count` is the number of projects. `GET /api/connectors` also carries, per connector, `bindable` (boolean),
`binding` (`{ "label", "scope" }`, only when bindable) and `usedBy` (the `projects` list above, only when bindable).

### Disable and phase Switch off while used

`POST /api/connectors/{id}/disable` answers 409 with a message naming the projects and envs while any project uses
the connector; `POST /api/connectors/{id}/disable?force=true` disables anyway (audited as forced; the projects' services
stay in git and stop working). `POST /api/connectors/phases/{phase}/off` reports such connectors as failed steps
(`ok: false`, detail names the projects); `?force=true` switches them off too.

### Implementation notes (part 2)

- Chart: documents rendered from `services.<id>.manifests` get `app.kubernetes.io/part-of`, `app.kubernetes.io/instance`,
  `managed-by`, `helm.sh/chart` and `aio.platform/service: <id>`, but not `app.kubernetes.io/name`: name + instance is
  the app Service's selector, and operators such as Strimzi copy their resource's labels onto the pods they create.
  Services' env vars are appended after `.Values.env`, services in key order, each service's vars in catalog order
  (so `$(VAR)` references work). Integers beyond 2^53 in manifests lose precision (Helm `fromYaml`).
- Copied Secrets (scope shared) are named by `copySecret.name`, carry the labels `aio.platform/managed=true`,
  `aio.platform/project`, `aio.platform/service`, `aio.platform/env`, and are only overwritten or deleted when those
  match; the name is kept in `project_services.copied_secret`. Project delete removes them as well.
- RBAC: `delete` on Secrets added to `aio-platform-envs` (rbac.yaml); `get` on the three shared connectors' Secrets is
  in `platform/deploy/rbac-shared-services.yaml` (separate file: it fails to apply before those namespaces exist).
