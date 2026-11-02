#!/usr/bin/env bash
# Installs cert-manager on the current Kubernetes context. ClusterIssuers are configured separately.
set -euo pipefail

CERT_MANAGER_VERSION="${CERT_MANAGER_VERSION:-v1.21.3}"

echo ">> cert-manager ${CERT_MANAGER_VERSION}"
kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"
kubectl -n cert-manager wait --for=condition=Available \
  deploy/cert-manager deploy/cert-manager-cainjector deploy/cert-manager-webhook --timeout=300s
