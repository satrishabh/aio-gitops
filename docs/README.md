# Cloud-agnostic CI/CD: Tekton (CI) + Argo CD (CD) + Helm

This uses only standard Kubernetes APIs, so it runs on EKS, GKE, AKS, OKE, DigitalOcean, k3s, kind or on-prem clusters. The only settings that differ between clusters are:
- the default StorageClass (used for pipeline workspaces)
- `ingressClassName` (nginx, traefik, alb, gce, ...)

```
GitHub push/PR --webhook--> Tekton EventListener (HMAC verified)
   └─ ci-pipeline: clone → test → kaniko build+push (:<git-sha>) → trivy scan → bump values-dev.yaml
                                                                                 │ (git commit)
GitOps repo  <───────────────────────────────────────────────────────────────────┘
   └─ Argo CD ApplicationSet: app-dev (auto) · app-staging (auto) · app-prod (MANUAL sync)
```
PRs build and scan only. Pushes to `main` also deploy to dev.

## 1. Install
```bash
./bootstrap/install.sh        # or  .\bootstrap\install.ps1
```
Before installing, replace the `satrishabh`, `aio-gitops` and `REPLACE_DOMAIN` placeholders in `tekton/triggers/triggers.yaml`, `argocd/**` and `helm/app/values*.yaml`.

## 2. Secrets (never commit real values)
```bash
# Registry: GHCR (PAT with write:packages) or Docker Hub (access token)
kubectl -n ci create secret docker-registry tmp --docker-server=ghcr.io \
  --docker-username=<user> --docker-password=<token> --dry-run=client -o json \
  | jq -r '.data[".dockerconfigjson"]' | base64 -d > config.json
kubectl -n ci create secret generic registry-credentials --from-file=config.json && rm config.json

kubectl -n ci create secret generic github-token --from-literal=token=<PAT contents:write on gitops repo>
kubectl -n ci create secret generic github-webhook-secret --from-literal=secretToken=$(openssl rand -hex 20)
```
For production, manage these with External Secrets Operator, Sealed Secrets or Vault, so you can use any cloud's secret manager.

If the GitOps repo is private, register it in Argo CD: `argocd repo add https://github.com/<org>/<gitops>.git --username x --password <token>`.

## 3. Hook up an app repo (any language)
1. The repo needs a `Dockerfile`. Java, Node, Python, Go and others all work, because the pipeline only builds the Dockerfile.
2. In GitHub, go to Settings → Webhooks and add one:
   - URL: `https://tekton-hooks.<domain>/`
   - Content type: `application/json`
   - Secret: the value of `github-webhook-secret`
   - Events: Pushes and Pull requests
3. Optional: set the test command for your language in `tekton/triggers/triggers.yaml` (`test-image` / `test-command`):

| Stack | test-image | test-command |
|---|---|---|
| Java | `maven:3.9-eclipse-temurin-21` | `mvn -B verify` |
| Node | `node:20` | `npm ci && npm test` |
| Python | `python:3.12` | `pip install -r requirements.txt && pytest` |
| Go | `golang:1.22` | `go test ./...` |

   If you leave it empty, the test step is skipped. Use that when your tests run inside a multi-stage Dockerfile.
4. Copy `helm/` into your GitOps repo. Your app must listen on `containerPort` (8080 by default) and expose `/health`, or you can change the paths in `values.yaml`.

Manual run: `tkn pipeline start ci-pipeline -n ci --use-param-defaults -p repo-url=... -p app-name=... -p image=ghcr.io/org/app -p gitops-repo=... -w name=source,volumeClaimTemplateFile=pvc.yaml -w name=dockerconfig,secret=registry-credentials -w name=github-token,secret=github-token`

## 4. Promotion
- **dev**: deployed automatically by CI.
- **staging**: open a PR that copies the dev `image.tag` into `values-staging.yaml`. Merging it auto-syncs.
- **prod**: open a PR that changes `values-prod.yaml` and merge it. Then someone has to sync it explicitly with `argocd app sync app-prod` (or in the UI). That manual sync is the approval gate.

## 5. Rollback
```bash
# Preferred (GitOps): revert the tag commit
git revert <commit> && git push          # Argo CD re-syncs the previous tag
# Fast path (prod is manual-sync, so it won't be immediately overwritten)
argocd app history app-prod
argocd app rollback app-prod <ID>
# Verify
kubectl -n app-prod rollout status deploy/app-prod
kubectl -n app-prod get pods
```

## 6. Multi-cloud / multi-cluster
Register each cluster with `argocd cluster add <context>`, then add an element to the list generator in `argocd/applicationsets/app-envs.yaml` with that cluster's `server` URL. Tekton can run in any one cluster.

## Security notes
- Images are tagged with the git SHA, and the chart refuses to render with the `latest` tag.
- Trivy fails the build on fixable HIGH or CRITICAL vulnerabilities.
- Webhooks are HMAC-verified by the GitHub interceptor.
- Pods run as non-root with all capabilities dropped. Resource limits are set on both pipeline steps and workloads.
