# AIO Platform: a self-service CI/CD portal

You paste a GitHub URL. The platform runs **CI on Tekton** and **CD on Argo CD**, and records everything along the way: runs, steps, live logs, images, vulnerability scans, deployments, promotions, approvals, rollbacks and the audit log.

- **Stack:** Java 21 / Spring Boot 3 backend, React (Vite) frontend, PostgreSQL, GitHub OAuth login.
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
4. It starts the first CI run. Its pipeline is clone → test → kaniko build and push (`:<sha>`) → Trivy scan → bump the tag in dev.
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

## Configuration
Settings come from environment variables; see `backend/src/main/resources/application.yml` and `.env.example`.

| Var | Purpose |
|---|---|
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | GitHub OAuth App. Callback: `https://<host>/login/oauth2/code/github` |
| `GITHUB_TOKEN` | Read access to app repos, `contents:write` on the GitOps repo, `admin:repo_hook` for webhooks |
| `ARGOCD_URL` / `ARGOCD_TOKEN` | Token for an Argo CD account with `applications` create/get/sync rights in project `apps` |
| `APP_REGISTRY` | Image prefix, e.g. `ghcr.io/my-org` or `docker.io/myuser` |
| `GITOPS_*` | GitOps repo URL, owner, repo name and branch (it must contain `helm/app`) |
| `ALLOWED_USERS` / `ALLOWED_ORGS` | Who may log in. If both are empty, any GitHub user can log in |
| `APPROVERS` | GitHub logins allowed to approve prod |
| `APP_PUBLIC_URL` | The portal's public URL; needed for webhook registration |
| `APP_BASE_DOMAIN` | Optional. Each env gets ingress `<project>-<env>.<domain>` |

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
For the dev server, set the OAuth callback to `http://localhost:5173/login/oauth2/code/github`.

## Deploy to a cluster
```bash
kubectl apply -f deploy/rbac.yaml
kubectl -n aio-platform create secret generic aio-platform-secrets \
  --from-literal=DB_URL=jdbc:postgresql://aio-postgres:5432/aio --from-literal=DB_USER=aio \
  --from-literal=DB_PASSWORD=$(openssl rand -hex 16) \
  --from-literal=GITHUB_CLIENT_ID=... --from-literal=GITHUB_CLIENT_SECRET=... \
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
- All other API calls need a GitHub session. CSRF is enforced with a cookie token.
- Repo URLs, git refs, paths and image tags are validated against strict patterns before they reach Kubernetes or Git.
- The test command runs as shell in CI pods by design. Only allow trusted users and orgs to log in.
- The platform's ServiceAccount can only touch PipelineRuns, TaskRuns and pod logs in the `ci` namespace.
- `ARGOCD_INSECURE=true` skips TLS verification. Use it only for the in-cluster `argocd-server` service URL.
- Run one replica: the pollers have no leader election.
