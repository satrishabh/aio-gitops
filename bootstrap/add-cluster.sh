#!/usr/bin/env bash
# Adds a GKE workload cluster to the AIO platform (hub = the cluster running the platform, Tekton and Argo CD).
# Run from Cloud Shell inside a clone of the aio-gitops repo, with kubectl pointing at the HUB:
#   bash bootstrap/add-cluster.sh aio-nonprod asia-south1 dev,staging
#   bash bootstrap/add-cluster.sh aio-prod    asia-south1 prod
# Re-runnable: every step is idempotent. Re-run after rotating the GitHub token (refreshes ghcr-pull).
#
# Only Argo CD gets access to the new cluster (Workload Identity, no keys). The platform reaches it through
# the Argo CD API, so nothing here gives the platform pod any credentials for the workload cluster.
set -euo pipefail

NAME="${1:?usage: add-cluster.sh NAME LOCATION ENVS (e.g. aio-prod asia-south1 prod)}"
LOCATION="${2:?location (zone or region) required}"
ENVS="${3:?comma-separated envs required: dev,staging,prod}"
PROJECT_ID="${PROJECT_ID:-project-d28840cd-be2f-4901-85e}"
HUB_CLUSTER="${HUB_CLUSTER:-cluster-1}"
HUB_LOCATION="${HUB_LOCATION:-asia-south1}"
GH_USER="${GH_USER:-satrishabh}"
REGISTRY_HOST="${REGISTRY_HOST:-ghcr.io}"
NUM_NODES="${NUM_NODES:-2}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-standard-2}"
# Argo Rollouts (blue-green) is installed when the cluster hosts prod, or with ROLLOUTS=true
ROLLOUTS="${ROLLOUTS:-$([[ ",$ENVS," == *",prod,"* ]] && echo true || echo false)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ARGO_ROLLOUTS_VERSION=v1.10.1
INGRESS_NGINX_VERSION=controller-v1.11.2
ARGO_GSA="argocd-fleet@${PROJECT_ID}.iam.gserviceaccount.com"

step() { echo; echo "==> $*"; }
[[ "$NAME" =~ ^[a-z0-9]([-a-z0-9]{0,38}[a-z0-9])?$ ]] || { echo "invalid cluster name: $NAME"; exit 1; }
IFS=',' read -ra ENV_LIST <<< "$ENVS"
for e in "${ENV_LIST[@]}"; do [[ "$e" =~ ^(dev|staging|prod)$ ]] || { echo "unknown env: $e"; exit 1; }; done

gcloud config set project "$PROJECT_ID" >/dev/null
HUB_KUBE="$(mktemp)"; WL_KUBE="$(mktemp)"
trap 'rm -f "$HUB_KUBE" "$WL_KUBE"' EXIT
KUBECONFIG="$HUB_KUBE" gcloud container clusters get-credentials "$HUB_CLUSTER" --location "$HUB_LOCATION" >/dev/null
hub() { KUBECONFIG="$HUB_KUBE" kubectl "$@"; }
wl()  { KUBECONFIG="$WL_KUBE"  kubectl "$@"; }

# ---------------------------------------------------------------- 1. cluster
step "GKE cluster $NAME ($LOCATION)"
if ! gcloud container clusters describe "$NAME" --location "$LOCATION" >/dev/null 2>&1; then
  gcloud container clusters create "$NAME" --location "$LOCATION" --node-locations "${LOCATION%-?}-a" \
    --num-nodes "$NUM_NODES" --machine-type "$MACHINE_TYPE" --disk-size 50 \
    --release-channel regular --enable-ip-alias --workload-pool "${PROJECT_ID}.svc.id.goog"
fi
KUBECONFIG="$WL_KUBE" gcloud container clusters get-credentials "$NAME" --location "$LOCATION" >/dev/null
ENDPOINT=$(gcloud container clusters describe "$NAME" --location "$LOCATION" --format='value(endpoint)')
CA=$(gcloud container clusters describe "$NAME" --location "$LOCATION" --format='value(masterAuth.clusterCaCertificate)')
SERVER="https://${ENDPOINT}"

# ---------------------------------------------------------------- 2. ingress (free nip.io DNS per cluster)
step "ingress-nginx on $NAME"
wl apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/${INGRESS_NGINX_VERSION}/deploy/static/provider/cloud/deploy.yaml" >/dev/null
wl -n ingress-nginx wait --for=condition=Available deploy/ingress-nginx-controller --timeout=300s
echo -n "waiting for load balancer IP"
IP=""
until [ -n "$IP" ]; do
  IP=$(wl -n ingress-nginx get svc ingress-nginx-controller -o jsonpath='{.status.loadBalancer.ingress[0].ip}' || true)
  [ -z "$IP" ] && { echo -n "."; sleep 5; }
done
DOMAIN="${IP}.nip.io"
echo " $IP -> $DOMAIN"

if [ "$ROLLOUTS" = "true" ]; then
  step "Argo Rollouts ${ARGO_ROLLOUTS_VERSION} on $NAME (blue-green)"
  wl create namespace argo-rollouts --dry-run=client -o yaml | wl apply -f - >/dev/null
  wl apply -n argo-rollouts --server-side --force-conflicts \
    -f "https://github.com/argoproj/argo-rollouts/releases/download/${ARGO_ROLLOUTS_VERSION}/install.yaml" >/dev/null
  wl -n argo-rollouts rollout status deploy/argo-rollouts --timeout=300s
fi

# ---------------------------------------------------------------- 3. env namespaces (same as the platform does on the hub)
step "namespaces ${ENVS} + LimitRange + ghcr-pull on $NAME"
GH_TOKEN=$(hub -n aio-platform get secret aio-platform-secrets -o jsonpath='{.data.GITHUB_TOKEN}' | base64 -d)
[ -n "$GH_TOKEN" ] || { echo "GITHUB_TOKEN missing in hub secret aio-platform-secrets"; exit 1; }
REG_USER=$(hub -n aio-platform get secret aio-platform-secrets -o jsonpath='{.data.APP_REGISTRY_USER}' 2>/dev/null | base64 -d || true)
REG_USER="${REG_USER:-$GH_USER}"
for env in "${ENV_LIST[@]}"; do
  wl apply --server-side --force-conflicts -f - >/dev/null <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: ${env}
  labels:
    aio.platform/managed: "true"
    aio.platform/env: ${env}
    pod-security.kubernetes.io/enforce: baseline
    pod-security.kubernetes.io/warn: restricted
---
apiVersion: v1
kind: LimitRange
metadata: { name: defaults, namespace: ${env} }
spec:
  limits:
    - type: Container
      defaultRequest: { cpu: 50m, memory: 64Mi }
      default: { cpu: 500m, memory: 512Mi }
EOF
  # piped from memory, never written to disk
  wl -n "$env" create secret docker-registry ghcr-pull --docker-server="$REGISTRY_HOST" \
    --docker-username="$REG_USER" --docker-password="$GH_TOKEN" --dry-run=client -o yaml \
    | wl label --local -f - aio.platform/managed=true -o yaml | wl apply -f - >/dev/null
  echo "  $env ready"
done
unset GH_TOKEN

# ---------------------------------------------------------------- 4. Argo CD -> cluster access (Workload Identity, no keys)
step "Workload Identity for Argo CD ($ARGO_GSA)"
gcloud iam service-accounts describe "$ARGO_GSA" >/dev/null 2>&1 || \
  gcloud iam service-accounts create argocd-fleet --display-name "Argo CD access to AIO workload clusters"
# container.developer: deploy namespaced workloads (no cluster-admin, no RBAC changes on the workload cluster)
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:${ARGO_GSA}" \
  --role roles/container.developer --condition=None >/dev/null
for ksa in argocd-application-controller argocd-server; do
  gcloud iam service-accounts add-iam-policy-binding "$ARGO_GSA" --role roles/iam.workloadIdentityUser \
    --member "serviceAccount:${PROJECT_ID}.svc.id.goog[argocd/${ksa}]" >/dev/null
  hub -n argocd annotate sa "$ksa" "iam.gke.io/gcp-service-account=${ARGO_GSA}" --overwrite >/dev/null
done
hub -n argocd rollout restart statefulset/argocd-application-controller deploy/argocd-server >/dev/null

step "register $NAME in Argo CD"
hub apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: cluster-${NAME}
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: cluster
    aio.platform/envs: "${ENVS//,/.}"
type: Opaque
stringData:
  name: ${NAME}
  server: ${SERVER}
  config: |
    {
      "execProviderConfig": {
        "command": "argocd-k8s-auth",
        "args": ["gcp"],
        "apiVersion": "client.authentication.k8s.io/v1beta1"
      },
      "tlsClientConfig": { "insecure": false, "caData": "${CA}" }
    }
EOF
# Platform account may read cluster status + pod logs; apps project allows registered clusters (by name)
hub -n argocd patch cm argocd-rbac-cm --type merge -p '{"data":{"policy.csv":"p, aio-platform, applications, *, apps/*, allow\np, aio-platform, logs, get, apps/*, allow\np, aio-platform, projects, get, apps, allow\np, aio-platform, clusters, get, *, allow\n"}}' >/dev/null
hub apply -f "$ROOT/argocd/projects/" >/dev/null
hub -n argocd rollout status statefulset/argocd-application-controller --timeout=300s >/dev/null

# ---------------------------------------------------------------- 5. done
step "$NAME registered"
cat <<EOF
Server : ${SERVER}
Domain : ${DOMAIN}
Envs   : ${ENVS}

Add it to the platform's cluster list in platform/deploy/values-platform.yaml (env: block), one index per cluster,
then redeploy the platform. Every env must be mapped to exactly one cluster:

  - { name: APP_CLUSTERS_<i>_NAME,        value: "${NAME}" }
  - { name: APP_CLUSTERS_<i>_SERVER,      value: "${SERVER}" }
  - { name: APP_CLUSTERS_<i>_ENVS,        value: "${ENVS}" }
  - { name: APP_CLUSTERS_<i>_BASEDOMAIN,  value: "${DOMAIN}" }
  - { name: APP_CLUSTERS_<i>_ROLLOUTS,    value: "${ROLLOUTS}" }

On restart dev/staging move automatically (old copies on the hub are deleted). Prod moves only when an approver
clicks "Repair environments" on the project. Check connection status on the platform's Clusters page.
EOF
