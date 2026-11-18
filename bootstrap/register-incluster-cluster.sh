#!/usr/bin/env bash
# Registers the hub's own cluster (Argo CD, Tekton and the platform run in the same cluster as the apps)
# as a named AIO workload cluster. Cloud-neutral counterpart of add-cluster.sh, which is GKE-only.
# Used for Azure AKS `creative` on 14 Nov 2026:
#   KUBECONFIG=D:/2026/AIO/.tools/azure-creative.kubeconfig KUBE_CONTEXT=aio-azure-creative \
#     bash bootstrap/register-incluster-cluster.sh azure dev,staging,prod
# Idempotent. Does not install Argo Rollouts; set ROLLOUTS=true only when blue-green prod is wanted
# and nodes have headroom. The platform's APP_CLUSTERS_<i>_NAME must equal NAME.
set -euo pipefail

NAME="${1:?usage: register-incluster-cluster.sh NAME ENVS (e.g. azure dev,staging,prod)}"
ENVS="${2:?comma-separated envs required: dev,staging,prod}"
PULL_SECRET_SOURCE_NS="${PULL_SECRET_SOURCE_NS:-dev}"
ROLLOUTS="${ROLLOUTS:-false}"
ARGO_ROLLOUTS_VERSION=v1.10.1
k() { kubectl ${KUBE_CONTEXT:+--context "$KUBE_CONTEXT"} "$@"; }

[[ "$NAME" =~ ^[a-z0-9]([-a-z0-9]{0,38}[a-z0-9])?$ ]] || { echo "invalid cluster name: $NAME"; exit 1; }
IFS=',' read -ra ENV_LIST <<< "$ENVS"
for e in "${ENV_LIST[@]}"; do [[ "$e" =~ ^(dev|staging|prod)$ ]] || { echo "unknown env: $e"; exit 1; }; done

echo "==> register in-cluster destination as '$NAME' in Argo CD"
k apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: cluster-${NAME}-in-cluster
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: cluster
    aio.platform/envs: "${ENVS//,/.}"
type: Opaque
stringData:
  name: ${NAME}
  server: https://kubernetes.default.svc
  config: '{"tlsClientConfig":{"insecure":false}}'
EOF

echo "==> env namespaces ${ENVS} + LimitRange + ghcr-pull"
for env in "${ENV_LIST[@]}"; do
  # Check that enforcing Pod Security baseline would not reject running pods.
  if k get ns "$env" >/dev/null 2>&1; then
    warn=$(k label ns "$env" pod-security.kubernetes.io/enforce=baseline --overwrite --dry-run=server 2>&1 | grep -i warning || true)
    [ -z "$warn" ] || { echo "$env: running pods violate baseline, fix first:"; echo "$warn"; exit 1; }
  fi
  k apply --server-side --force-conflicts --field-manager=aio-add-cluster -f - >/dev/null <<EOF
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
  if [ "$env" != "$PULL_SECRET_SOURCE_NS" ]; then
    # copy the registry pull secret object in-cluster; credentials are never printed or written to disk
    k -n "$PULL_SECRET_SOURCE_NS" get secret ghcr-pull -o json \
      | python3 -c "import json,sys;o=json.load(sys.stdin);o['metadata']={'name':'ghcr-pull','namespace':'$env','labels':{'aio.platform/managed':'true'}};print(json.dumps(o))" \
      | k apply -f - >/dev/null
  else
    k -n "$env" label secret ghcr-pull aio.platform/managed=true --overwrite >/dev/null
  fi
  echo "  $env ready"
done

if [ "$ROLLOUTS" = "true" ]; then
  echo "==> Argo Rollouts ${ARGO_ROLLOUTS_VERSION}"
  k create namespace argo-rollouts --dry-run=client -o yaml | k apply -f - >/dev/null
  k apply -n argo-rollouts --server-side --force-conflicts \
    -f "https://github.com/argoproj/argo-rollouts/releases/download/${ARGO_ROLLOUTS_VERSION}/install.yaml" >/dev/null
  k -n argo-rollouts rollout status deploy/argo-rollouts --timeout=300s
fi

echo "==> $NAME registered. Check the platform's Clusters page (Connection: Connected)."
