# AIO Platform: a self-service CI/CD portal

You paste a GitHub URL. The platform runs **CI on Tekton** and **CD on Argo CD**, and records everything along the way: runs, steps, live logs, images, vulnerability scans, deployments, promotions, approvals, rollbacks and the audit log.

- **Stack:** Java 21 / Spring Boot 3 backend, React (Vite) frontend, PostgreSQL, and OIDC login through User Profile Manager.
- **Cloud-agnostic:** it needs only a Kubernetes cluster with the Tekton + Argo CD setup from the repo root (`bootstrap/install.sh`).

```
React UI ──REST/SSE──> Spring Boot ──k8s API──> Tekton  (create PipelineRun, watch TaskRuns, read step logs)
                          │         ──REST────> Argo CD (Applications <project>-dev|staging|prod, status, sync)
                          │         ──REST────> GitHub  (repo lookup, webhooks, GitOps values commits)
                          └── PostgreSQL (Flyway schema)
```

## What happens when you add a repo
1. The platform validates the URL and checks the repo through the GitHub API.
2. It creates `apps/<project>/values-{dev,staging,prod}.yaml` in the GitOps repo, plus the Argo CD apps `<project>-dev`, `<project>-staging` and `<project>-prod`:
   - dev and staging sync automatically
   - prod syncs only after an approval
   - each app deploys to the namespace `<project>-<env>`
3. Optionally, it registers a GitHub webhook pointing to `/api/webhooks/github/<id>`, signed with a per-project secret:
   - a push to the default branch runs CI and deploys to dev
   - a pull request runs build and scan only
4. The server starts the first CI run as part of project creation (including template creation), so API clients cannot accidentally leave a new project without a build. Its pipeline is clone → test → kaniko build and push (`:<sha>`) → Trivy scan → bump the tag in dev.
5. Background watchers keep the database in sync:
   - Tekton is polled every 4 s
   - Argo CD is polled every 15 s
   - run pages update live over server-sent events (SSE)

Until the first CI run finishes, dev points at the placeholder tag `0.0.0`. Its pods will show ImagePullBackOff until then; this is expected.

## Promotion, approval and rollback
- **dev → staging:** commits the dev tag to the staging values file, which then auto-syncs.
- **staging → prod:** creates a PENDING request.
  - An approver approves it on the Approvals page. The platform then commits the tag and triggers an Argo CD sync.
  - If `APPROVERS` is empty, anyone except the requester can approve.
- **Rollback:** pick a row in deployment history. The platform commits that older tag back to the GitOps repo; a prod rollback also needs approval.

## Custom domains
The Domains tab on each project adds a hostname and optional path route for dev, staging or prod. The app writes route
values to the GitOps repo; Argo CD creates the Ingress, and the platform reports DNS and certificate status.
Production domain changes are limited to approvers.

Before adding domains, install ingress-nginx and cert-manager on each target cluster, and create the configured
cert-manager `ClusterIssuer` (defaults to `gts`). The bootstrap scripts install cert-manager; issuer credentials
and DNS provider setup are intentionally separate. Configure `APP_CLUSTERS_<i>_ISSUER` to select another issuer,
and `APP_CLUSTERS_<i>_DNS_ZONE` when external-dns manages the host's DNS zone. Otherwise, point the displayed A
record at the target cluster's ingress IP. See [the GKE deployment tracker](../docs/GKE-DEPLOYMENT-TRACKER.md)
for the current cluster state and remaining issuer setup.

For a delegated Cloud DNS zone, install the ExternalDNS controller with a dedicated Workload Identity:

```sh
PROJECT_ID=project-d28840cd-be2f-4901-85e \
DNS_ZONE=app.sarvaikam.com \
DNS_ZONE_NAME=aio-sarvaikam-app \
bash bootstrap/install-external-dns.sh
```

The controller is restricted to that DNS suffix and uses `upsert-only`, so it will not delete records.
The `app` subdomain must also be delegated from the existing DNS provider to the four nameservers
listed in the GKE deployment tracker; do not replace the apex nameservers.
For cert-manager DNS-01 challenges, bind the DNS identity to the cert-manager service account and configure
GTS EAB credentials in a Kubernetes Secret in the `cert-manager` namespace; never commit or expose those values.

If the DNS provider cannot create a child-zone NS delegation, keep the parent DNS zone there, add an A record
for the app hostname pointing to the cluster ingress IP, and use the GTS HTTP-01 solver. In that case leave
`APP_CLUSTERS_<i>_DNSZONE` blank so the platform displays the manual A-record target instead of expecting
ExternalDNS to publish the record.

## Configuration
Settings come from environment variables; see `backend/src/main/resources/application.yml` and `.env.example`.

| Var | Purpose |
|---|---|
| `OIDC_ISSUER_URI` | AIO's isolated User Profile Manager realm issuer |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | AIO's confidential OIDC client. Callback: `https://<host>/login/oauth2/code/profile` |
| `GITHUB_TOKEN` | Read access to app repos, `contents:write` on the GitOps repo, `admin:repo_hook` for webhooks |
| `ARGOCD_URL` / `ARGOCD_TOKEN` | Token for an Argo CD account with `applications` create/get/sync rights in project `apps` |
| `APP_REGISTRY` | Image prefix, e.g. `ghcr.io/my-org` or `docker.io/myuser` |
| `GITOPS_*` | GitOps repo URL, owner, repo name and branch (it must contain `helm/app`) |
| `ALLOWED_USERS` / `ALLOWED_ROLES` | OIDC usernames/emails or AIO client roles allowed to log in. If both are empty, any AIO-realm user can log in |
| `APPROVERS` | Normalized User Profile usernames allowed to approve prod |
| `APP_PUBLIC_URL` | The portal's public URL; needed for webhook registration |
| `APP_BASE_DOMAIN` | Optional. Each env gets ingress `<project>-<env>.<domain>` |

## Public platform endpoint

The AIO portal is served at `https://app.sarvaikam.com` with a GTS certificate. `APP_PUBLIC_URL` is set to this
hostname for new GitHub webhook registrations. The `aio.34.180.3.18.nip.io` route is retained as a legacy
webhook endpoint for existing registrations; remove that alias only after those hooks have been moved to the
new hostname. The `aio-platform` OIDC client includes callback
`https://app.sarvaikam.com/login/oauth2/code/profile` and post-logout redirect
`https://app.sarvaikam.com/login`.

### AIO registration approval and roles

User Profile Manager hosts isolated realms. AIO users register in the `aio` realm; Learn users
register separately in the `sarvaikam` realm. Accounts, credentials, sessions, roles, and client
consent do not cross realm boundaries. AIO access is approved with client-scoped roles on
`aio-platform`:

| Client role | AIO access |
|---|---|
| `aio-admin` | Full access and production approval |
| `aio-business` | Project, build, scan, dev, and staging operations |
| `aio-approver` | Optional production approval added to a business user |
| `aio-community` | Read-only project summaries and delivery metrics |

A newly registered profile has no AIO client role and is denied by AIO until an identity
administrator assigns one. Keep `ALLOWED_USERS` empty when public registration is enabled; access
must never depend on an unverified, user-chosen username. Roles belonging to Learn or another OIDC
client do not grant AIO access. Use the Keycloak master administrator only for bootstrap/recovery.

Approval workflow:

1. The user selects **Register** on AIO's login page, creates an AIO-realm account, and verifies
   their email. This does not create or reuse a Learn account.
2. In the Keycloak Admin Console, select realm `aio`, open **Users**, select the user, then open
   **Role mapping > Assign role** and filter by client `aio-platform`.
3. Assign exactly one base tier: `aio-admin`, `aio-business`, or `aio-community`. Add
   `aio-approver` only to a business user who may approve production changes.
4. The user starts a new AIO login session. Users without an approved base tier remain denied.
5. To revoke access immediately, remove the AIO client roles and terminate that user's active
   sessions in the `aio` realm; removing a role alone does not invalidate an already issued token.

Application-specific custom roles may be added alongside a base tier. They must remain client
roles of `aio-platform`; realm roles or roles from another client must not be used as an AIO access
shortcut.

### Per-user data access

Only `aio-admin` can view all users' projects, builds, deployments, scans, metrics, and audit history.
Other approved users see projects owned by their immutable OIDC issuer/subject identity; related
builds, logs, SSE streams, deployment operations, and approvals enforce the same ownership at the
API boundary. Standalone scans belong to their initiating identity. Regular users' audit feeds
contain only events attributed to that identity. Cluster inventory and the user directory are
admin-only; an approver role does not bypass resource ownership.

Migration V7 binds historical project, scan, and audit rows to already-recorded OIDC users.
Unmatched rows stay admin-only rather than being claimed when someone registers a matching name.
Ownership never depends on a mutable display name or email.

Appearance is available in **Profile & settings**. The notification bell opens the authorized
audit feed and links to the full audit log. OIDC sign-in does not provide a GitHub repository token:
regular users' repository picker lists only their connected repositories, and the platform-token
listing is admin-only. Private repository onboarding/inspection requires existing authorized
ownership or an administrator; public repositories and newly generated per-user templates remain
available without exposing other users' private repositories.

For GitHub social sign-in, the AIO OAuth app must register the exact broker callback
`https://profile.sarvaikam.com/realms/aio/broker/github/endpoint`. This differs from AIO's own OIDC
callback (`https://app.sarvaikam.com/login/oauth2/code/profile`) and from Learn's separate realm.

### Domain configuration ownership

The Domains tab owns only the top-level `routes:` block in an application's environment values:
host, path, TLS/certificate ownership, HTTPS redirect, optional prefix stripping, and whether the
cluster manages DNS. Image, environment variables, secrets, probes, resources, autoscaling, and
all application-specific Helm values remain owned by the deployed application. Reconciliation is
idempotent and preserves those application-owned values.

To create an Argo CD token, add `accounts.aio-platform: apiKey` to `argocd-cm`, grant RBAC in `argocd-rbac-cm`, then run `argocd account generate-token --account aio-platform`.

## Run locally
```bash
cp .env.example .env                 # fill in values
docker compose up --build            # http://localhost:8080 (uses ~/.kube/config)
```
For UI hot reload:
```bash
cd backend && mvn spring-boot:run    # needs Postgres on :5432
cd frontend && npm install && npm run dev   # http://localhost:5173
```
For local backend login, add `http://localhost:8080/login/oauth2/code/profile` to a development
OIDC client's redirect allowlist. The Vite server proxies API/login traffic to that backend.

## Deploy to a cluster
```bash
kubectl apply -f deploy/rbac.yaml
kubectl -n aio-platform create secret generic aio-platform-secrets \
  --from-literal=DB_URL=jdbc:postgresql://aio-postgres:5432/aio --from-literal=DB_USER=aio \
  --from-literal=DB_PASSWORD=$(openssl rand -hex 16) \
  --from-literal=OIDC_ISSUER_URI=https://profile.sarvaikam.com/realms/aio \
  --from-literal=OIDC_CLIENT_ID=aio-platform --from-literal=OIDC_CLIENT_SECRET=... \
  --from-literal=GITHUB_TOKEN=... --from-literal=ARGOCD_TOKEN=...
kubectl apply -f deploy/postgres.yaml
kubectl apply -f ../argocd/projects/app-project.yaml   # allows <project>-<env> namespaces
kubectl apply -f deploy/argocd-app.yaml               # platform deployed via GitOps
```
The platform can build itself: add this repo as a project with dockerfile `platform/Dockerfile` and context `platform`.

## Tests
```bash
cd backend && mvn verify
```
The tests cover GitOps tag editing, scan-summary parsing, name sanitizing and webhook HMAC verification.

## Security notes
- Webhooks are verified with HMAC-SHA256 (constant-time compare) and are the only unauthenticated API.
- All other API calls need a User Profile Manager OIDC session. CSRF is enforced with a cookie token.
- Repo URLs, git refs, paths and image tags are validated against strict patterns before they reach Kubernetes or Git.
- The test command runs as shell in CI pods by design. Only allow trusted OIDC users or AIO client roles to log in.
- The platform's ServiceAccount can only touch PipelineRuns, TaskRuns and pod logs in the `ci` namespace.
- `ARGOCD_INSECURE=true` skips TLS verification. Use it only for the in-cluster `argocd-server` service URL.
- Run one replica: the pollers have no leader election.
