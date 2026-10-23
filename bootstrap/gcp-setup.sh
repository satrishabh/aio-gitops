#!/usr/bin/env bash
# One-shot setup on GCP, run from Cloud Shell inside a clone of the aio-gitops repo:
#   git clone https://github.com/satrishabh/aio-gitops.git && cd aio-gitops && bash bootstrap/gcp-setup.sh
# Re-runnable: every step is idempotent. Secrets are read with hidden prompts and never written to disk.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-project-d28840cd-be2f-4901-85e}"
# LOCATION may be a zone (asia-south1-a) or a region (asia-south1) - existing clusters are reused as-is
LOCATION="${LOCATION:-asia-south1}"
CLUSTER="${CLUSTER:-cluster-1}"
GH_USER="${GH_USER:-satrishabh}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

TEKTON_PIPELINES_VERSION=v1.6.0
ARGOCD_VERSION=v3.5.6   # must match what's running; CRDs need --server-side (applicationsets CRD is too large)
INGRESS_NGINX_VERSION=controller-v1.11.2

step() { echo; echo "==> $*"; }

# ---------------------------------------------------------------- 1. cluster
step "GKE cluster $CLUSTER ($LOCATION)"
gcloud config set project "$PROJECT_ID" >/dev/null
gcloud services enable container.googleapis.com
if ! gcloud container clusters describe "$CLUSTER" --location "$LOCATION" >/dev/null 2>&1; then
  gcloud container clusters create "$CLUSTER" --location "$LOCATION" --node-locations "${LOCATION%-?}-a" \
    --num-nodes 2 --machine-type e2-standard-4 --disk-size 50 \
    --release-channel regular --enable-ip-alias
fi
gcloud container clusters get-credentials "$CLUSTER" --location "$LOCATION"
if [ "$(gcloud container clusters describe "$CLUSTER" --location "$LOCATION" --format='value(autopilot.enabled)')" = "True" ]; then
  echo "NOTE: Autopilot cluster - pods get default resources; Kaniko runs as root (allowed, non-privileged)."
fi
kubectl get nodes -o wide

# ---------------------------------------------------------------- 2. ingress (free nip.io DNS)
step "ingress-nginx"
kubectl apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/${INGRESS_NGINX_VERSION}/deploy/static/provider/cloud/deploy.yaml"
kubectl -n ingress-nginx wait --for=condition=Available deploy/ingress-nginx-controller --timeout=300s
echo -n "waiting for load balancer IP"
IP=""
until [ -n "$IP" ]; do
  IP=$(kubectl -n ingress-nginx get svc ingress-nginx-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}' || true)
  [ -z "$IP" ] && { echo -n "."; sleep 5; }
done
DOMAIN="${IP}.nip.io"
PUBLIC_URL="http://aio.${DOMAIN}"
echo " $IP -> $DOMAIN"

# ---------------------------------------------------------------- 3. Tekton + Argo CD
step "Tekton Pipelines ${TEKTON_PIPELINES_VERSION}"
# Triggers/Dashboard are not installed: the platform receives GitHub webhooks itself.
# v1.x images live on ghcr.io (older gcr.io release images no longer pull).
kubectl apply --server-side --force-conflicts -f "https://storage.googleapis.com/tekton-releases/pipeline/previous/${TEKTON_PIPELINES_VERSION}/release.yaml"
kubectl -n tekton-pipelines wait --for=condition=Available deploy/tekton-pipelines-controller deploy/tekton-pipelines-webhook --timeout=300s

step "Argo CD ${ARGOCD_VERSION}"
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argocd --server-side --force-conflicts -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
kubectl -n argocd rollout restart deploy/argocd-applicationset-controller   # picks up CRD if it was missing
# Only wait for what the platform uses; the ApplicationSet controller is optional here
kubectl -n argocd wait --for=condition=Available deploy/argocd-server deploy/argocd-repo-server --timeout=300s
kubectl -n argocd rollout status statefulset/argocd-application-controller --timeout=300s

# API account for the platform (apiKey only, scoped to the 'apps' project)
kubectl -n argocd patch cm argocd-cm --type merge -p '{"data":{"accounts.aio-platform":"apiKey"}}'
kubectl -n argocd patch cm argocd-rbac-cm --type merge -p '{"data":{"policy.csv":"p, aio-platform, applications, *, apps/*, allow\np, aio-platform, projects, get, apps, allow\n"}}'
kubectl -n argocd rollout restart deploy/argocd-server
kubectl -n argocd rollout status deploy/argocd-server --timeout=300s

step "CI resources (namespace ci) + Argo CD project"
kubectl apply -f "$ROOT/tekton/rbac/"
kubectl apply -n ci -f "$ROOT/tekton/tasks/"
kubectl apply -n ci -f "$ROOT/tekton/pipelines/"
kubectl apply -f "$ROOT/argocd/projects/"
kubectl apply -f "$ROOT/platform/deploy/rbac.yaml"

# ---------------------------------------------------------------- 4. secrets (typed by you, hidden)
step "Secrets - input is hidden and not stored on disk"
read -rsp "GitHub PAT (classic: repo, write:packages, read:packages, admin:repo_hook, read:org): " GH_PAT; echo
read -rp  "GitHub OAuth App Client ID: " GH_CLIENT_ID
read -rsp "GitHub OAuth App Client secret: " GH_CLIENT_SECRET; echo

AUTH=$(printf '%s:%s' "$GH_USER" "$GH_PAT" | base64 -w0)
printf '{"auths":{"ghcr.io":{"auth":"%s"}}}' "$AUTH" > /dev/shm/config.json
kubectl -n ci create secret generic registry-credentials --from-file=config.json=/dev/shm/config.json \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n aio-platform create secret generic ghcr-pull --type=kubernetes.io/dockerconfigjson \
  --from-file=.dockerconfigjson=/dev/shm/config.json --dry-run=client -o yaml | kubectl apply -f -
rm -f /dev/shm/config.json
kubectl -n ci create secret generic github-token --from-literal=token="$GH_PAT" \
  --dry-run=client -o yaml | kubectl apply -f -

# Argo CD token for the platform account, via the API (admin session, local port-forward)
kubectl -n argocd port-forward svc/argocd-server 8443:443 >/dev/null 2>&1 &
PF=$!; trap 'kill $PF 2>/dev/null || true' EXIT; sleep 5
ADMIN_PW=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)
SESSION=$(curl -sk https://localhost:8443/api/v1/session -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PW}\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
ARGOCD_TOKEN=$(curl -sk -X POST https://localhost:8443/api/v1/account/aio-platform/token \
  -H "Authorization: Bearer ${SESSION}" -H 'Content-Type: application/json' -d '{"name":"aio-platform"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$ARGOCD_TOKEN" ] || { echo "failed to create Argo CD token"; exit 1; }

DB_PASSWORD=$(kubectl -n aio-platform get secret aio-platform-secrets -o jsonpath='{.data.DB_PASSWORD}' 2>/dev/null | base64 -d || true)
[ -n "$DB_PASSWORD" ] || DB_PASSWORD=$(openssl rand -hex 16)
kubectl -n aio-platform create secret generic aio-platform-secrets \
  --from-literal=DB_URL=jdbc:postgresql://aio-postgres:5432/aio \
  --from-literal=DB_USER=aio \
  --from-literal=DB_PASSWORD="$DB_PASSWORD" \
  --from-literal=GITHUB_CLIENT_ID="$GH_CLIENT_ID" \
  --from-literal=GITHUB_CLIENT_SECRET="$GH_CLIENT_SECRET" \
  --from-literal=GITHUB_TOKEN="$GH_PAT" \
  --from-literal=ARGOCD_TOKEN="$ARGOCD_TOKEN" \
  --from-literal=APP_PUBLIC_URL="$PUBLIC_URL" \
  --from-literal=APP_BASE_DOMAIN="$DOMAIN" \
  --dry-run=client -o yaml | kubectl apply -f -

# ---------------------------------------------------------------- 5. build platform image + deploy
step "Build & push ghcr.io/${GH_USER}/aio-platform:0.1.0"
echo "$GH_PAT" | docker login ghcr.io -u "$GH_USER" --password-stdin
docker build -t "ghcr.io/${GH_USER}/aio-platform:0.1.0" "$ROOT/platform"
docker push "ghcr.io/${GH_USER}/aio-platform:0.1.0"
docker image prune -af >/dev/null   # Cloud Shell has a small disk
unset GH_PAT GH_CLIENT_SECRET ARGOCD_TOKEN SESSION ADMIN_PW

step "Postgres + platform"
kubectl apply -f "$ROOT/platform/deploy/postgres.yaml"
kubectl -n aio-platform rollout status statefulset/aio-postgres --timeout=300s
helm upgrade --install aio-platform "$ROOT/helm/app" -n aio-platform \
  -f "$ROOT/helm/app/values.yaml" -f "$ROOT/platform/deploy/values-platform.yaml" \
  --set ingress.host="aio.${DOMAIN}"
kubectl -n aio-platform rollout restart deploy/aio-platform >/dev/null 2>&1 || true
kubectl -n aio-platform rollout status deploy/aio-platform --timeout=600s || {
  echo "Platform not ready. If you see ImagePullBackOff, make the GHCR package public (see below) and re-run."; }

cat <<EOF

==================================================================
 Platform UI   : ${PUBLIC_URL}
 Argo CD admin : kubectl -n argocd port-forward svc/argocd-server 8080:443  (user admin)
 Tekton UI     : kubectl -n tekton-pipelines port-forward svc/tekton-dashboard 9097:9097

 1. GitHub -> Settings -> Developer settings -> OAuth Apps -> AIO Platform:
      Homepage URL : ${PUBLIC_URL}
      Callback URL : ${PUBLIC_URL}/login/oauth2/code/github
 2. GitHub -> your profile -> Packages -> aio-platform -> Package settings -> Change visibility -> Public
    (do the same for each app image after its first CI build, e.g. sample-app)
 3. Open ${PUBLIC_URL}, sign in, New project -> https://github.com/${GH_USER}/sample-app

 Stop all costs: gcloud container clusters delete ${CLUSTER} --location ${LOCATION}
==================================================================
EOF
