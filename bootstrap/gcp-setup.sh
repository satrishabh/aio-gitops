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

# Platform database: "incluster" (default, the aio-postgres StatefulSet) or "cloudsql" (managed Cloud SQL for PostgreSQL).
# cloudsql needs Workload Identity (GKE_METADATA) on every node pool, which updates the nodes and needs spare quota.
DB_MODE="${DB_MODE:-incluster}"
REGION="${LOCATION%-[a-z]}"                 # Cloud SQL is regional: asia-south1-a -> asia-south1
SQL_INSTANCE="${SQL_INSTANCE:-aio-db}"
SQL_TIER="${SQL_TIER:-db-f1-micro}"          # shared core, cheapest; use db-custom-1-3840 for real workloads
SQL_GSA="aio-platform-sql@${PROJECT_ID}.iam.gserviceaccount.com"

TEKTON_PIPELINES_VERSION=v1.6.0
ARGOCD_VERSION=v3.5.6   # must match what's running; CRDs need --server-side (applicationsets CRD is too large)
ARGO_ROLLOUTS_VERSION=v1.10.1   # blue-green for prod
INGRESS_NGINX_VERSION=controller-v1.11.2

step() { echo; echo "==> $*"; }

# ---------------------------------------------------------------- 1. cluster
step "GKE cluster $CLUSTER ($LOCATION)"
gcloud config set project "$PROJECT_ID" >/dev/null
gcloud services enable container.googleapis.com
if ! gcloud container clusters describe "$CLUSTER" --location "$LOCATION" >/dev/null 2>&1; then
  gcloud container clusters create "$CLUSTER" --location "$LOCATION" --node-locations "${LOCATION%-?}-a" \
    --num-nodes 2 --machine-type e2-standard-4 --disk-size 50 \
    --release-channel regular --enable-ip-alias --workload-pool "${PROJECT_ID}.svc.id.goog"
fi
gcloud container clusters get-credentials "$CLUSTER" --location "$LOCATION"
AUTOPILOT=$(gcloud container clusters describe "$CLUSTER" --location "$LOCATION" --format='value(autopilot.enabled)')
if [ "$AUTOPILOT" = "True" ]; then
  echo "NOTE: Autopilot cluster - pods get default resources; Kaniko runs as root (allowed, non-privileged)."
fi
# Workload Identity lets the platform pod reach Cloud SQL as a Google service account, with no key files.
# Autopilot always has it; older Standard clusters may not (enabling it updates the node pools).
if [ "$DB_MODE" = "cloudsql" ] && [ "$AUTOPILOT" != "True" ] && \
   [ -z "$(gcloud container clusters describe "$CLUSTER" --location "$LOCATION" --format='value(workloadIdentityConfig.workloadPool)')" ]; then
  echo "Enabling Workload Identity on $CLUSTER (node pools are updated; takes a few minutes)"
  gcloud container clusters update "$CLUSTER" --location "$LOCATION" --workload-pool "${PROJECT_ID}.svc.id.goog"
  for POOL in $(gcloud container node-pools list --cluster "$CLUSTER" --location "$LOCATION" --format='value(name)'); do
    gcloud container node-pools update "$POOL" --cluster "$CLUSTER" --location "$LOCATION" --workload-metadata GKE_METADATA
  done
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

# API account for the platform (apiKey only, scoped to the 'apps' project). logs + resource actions + cluster list
# are how the platform sees workload clusters: it holds no credentials for them, Argo CD does.
kubectl -n argocd patch cm argocd-cm --type merge -p '{"data":{"accounts.aio-platform":"apiKey"}}'
kubectl -n argocd patch cm argocd-rbac-cm --type merge -p '{"data":{"policy.csv":"p, aio-platform, applications, *, apps/*, allow\np, aio-platform, logs, get, apps/*, allow\np, aio-platform, projects, get, apps, allow\np, aio-platform, clusters, get, *, allow\n"}}'
kubectl -n argocd rollout restart deploy/argocd-server
kubectl -n argocd rollout status deploy/argocd-server --timeout=300s

step "Argo Rollouts ${ARGO_ROLLOUTS_VERSION} (blue-green for prod)"
kubectl create namespace argo-rollouts --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argo-rollouts --server-side --force-conflicts \
  -f "https://github.com/argoproj/argo-rollouts/releases/download/${ARGO_ROLLOUTS_VERSION}/install.yaml"
kubectl -n argo-rollouts rollout status deploy/argo-rollouts --timeout=300s

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

if [ "$DB_MODE" = "cloudsql" ]; then
  # ---------------------------------------------------------------- 4b. Cloud SQL for PostgreSQL
  step "Cloud SQL instance $SQL_INSTANCE ($REGION, $SQL_TIER)"
  gcloud services enable sqladmin.googleapis.com iam.googleapis.com
  if ! gcloud sql instances describe "$SQL_INSTANCE" >/dev/null 2>&1; then
    # Public IP but no authorised networks: only IAM-authorised connectors/proxies can reach it (TLS)
    gcloud sql instances create "$SQL_INSTANCE" --database-version POSTGRES_16 --edition ENTERPRISE \
      --tier "$SQL_TIER" --region "$REGION" --availability-type zonal \
      --storage-size 10 --storage-auto-increase --backup-start-time 03:00
  fi
  gcloud sql databases describe aio --instance "$SQL_INSTANCE" >/dev/null 2>&1 || \
    gcloud sql databases create aio --instance "$SQL_INSTANCE"
  if gcloud sql users list --instance "$SQL_INSTANCE" --format='value(name)' | grep -qx aio; then
    gcloud sql users set-password aio --instance "$SQL_INSTANCE" --password "$DB_PASSWORD"
  else
    gcloud sql users create aio --instance "$SQL_INSTANCE" --password "$DB_PASSWORD"
  fi
  SQL_CONN=$(gcloud sql instances describe "$SQL_INSTANCE" --format='value(connectionName)')

  step "Workload Identity: aio-platform pod -> $SQL_GSA (roles/cloudsql.client)"
  gcloud iam service-accounts describe "$SQL_GSA" >/dev/null 2>&1 || \
    gcloud iam service-accounts create aio-platform-sql --display-name "AIO platform - Cloud SQL client"
  # A new service account takes a short while to become usable in IAM policies
  until gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:${SQL_GSA}" \
          --role roles/cloudsql.client --condition None >/dev/null; do echo "waiting for IAM to see ${SQL_GSA}..."; sleep 10; done
  gcloud iam service-accounts add-iam-policy-binding "$SQL_GSA" --role roles/iam.workloadIdentityUser \
    --member "serviceAccount:${PROJECT_ID}.svc.id.goog[aio-platform/aio-platform]" >/dev/null
  kubectl -n aio-platform annotate serviceaccount aio-platform --overwrite "iam.gke.io/gcp-service-account=${SQL_GSA}"

  DB_URL="jdbc:postgresql:///aio?cloudSqlInstance=${SQL_CONN}&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
else
  DB_URL=jdbc:postgresql://aio-postgres:5432/aio
  # left over from an earlier cloudsql run: without Workload Identity on the nodes it only causes 403s
  kubectl -n aio-platform annotate serviceaccount aio-platform iam.gke.io/gcp-service-account- >/dev/null 2>&1 || true
fi

kubectl -n aio-platform create secret generic aio-platform-secrets \
  --from-literal=DB_URL="$DB_URL" \
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
# Same image as platform/deploy/values-platform.yaml (Artifact Registry, built by Cloud Build)
PLATFORM_IMAGE=$(sed -n 's/^  repository: *//p' "$ROOT/platform/deploy/values-platform.yaml" | head -1)
PLATFORM_TAG=$(sed -n 's/^  tag: *"\{0,1\}\([^"]*\)"\{0,1\}.*/\1/p' "$ROOT/platform/deploy/values-platform.yaml" | head -1)
step "Build ${PLATFORM_IMAGE}:${PLATFORM_TAG} with Cloud Build"
gcloud services enable cloudbuild.googleapis.com artifactregistry.googleapis.com
AR_REPO=$(echo "$PLATFORM_IMAGE" | cut -d/ -f3)
gcloud artifacts repositories describe "$AR_REPO" --location "$REGION" >/dev/null 2>&1 || \
  gcloud artifacts repositories create "$AR_REPO" --repository-format docker --location "$REGION"
gcloud builds submit "$ROOT/platform" --tag "${PLATFORM_IMAGE}:${PLATFORM_TAG}"
unset GH_PAT GH_CLIENT_SECRET ARGOCD_TOKEN SESSION ADMIN_PW

step "Database + platform"
if [ "$DB_MODE" = "cloudsql" ]; then
  # copies existing data from the old in-cluster Postgres the first time (no-op afterwards)
  SQL_INSTANCE_CONN="$SQL_CONN" bash "$ROOT/bootstrap/migrate-to-cloudsql.sh"
else
  kubectl apply -f "$ROOT/platform/deploy/postgres.yaml"
  kubectl -n aio-platform rollout status statefulset/aio-postgres --timeout=300s
fi
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
                 gcloud sql instances delete ${SQL_INSTANCE}   (Cloud SQL bills while it exists)
==================================================================
EOF
