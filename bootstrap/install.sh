#!/usr/bin/env bash
# Installs Tekton + Argo CD on ANY conformant Kubernetes cluster (EKS, GKE, AKS, OKE, k3s, kind, on-prem).
# Uses the current kubectl context.
set -euo pipefail

TEKTON_PIPELINES_VERSION="${TEKTON_PIPELINES_VERSION:-v0.62.3}"
TEKTON_TRIGGERS_VERSION="${TEKTON_TRIGGERS_VERSION:-v0.29.1}"
TEKTON_DASHBOARD_VERSION="${TEKTON_DASHBOARD_VERSION:-v0.50.0}"
ARGOCD_VERSION="${ARGOCD_VERSION:-v2.12.4}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo ">> Tekton Pipelines ${TEKTON_PIPELINES_VERSION}"
kubectl apply -f "https://storage.googleapis.com/tekton-releases/pipeline/previous/${TEKTON_PIPELINES_VERSION}/release.yaml"
echo ">> Tekton Triggers ${TEKTON_TRIGGERS_VERSION}"
kubectl apply -f "https://storage.googleapis.com/tekton-releases/triggers/previous/${TEKTON_TRIGGERS_VERSION}/release.yaml"
kubectl apply -f "https://storage.googleapis.com/tekton-releases/triggers/previous/${TEKTON_TRIGGERS_VERSION}/interceptors.yaml"
echo ">> Tekton Dashboard ${TEKTON_DASHBOARD_VERSION}"
kubectl apply -f "https://storage.googleapis.com/tekton-releases/dashboard/previous/${TEKTON_DASHBOARD_VERSION}/release.yaml"

kubectl wait --for=condition=Available deploy --all -n tekton-pipelines --timeout=300s

echo ">> Argo CD ${ARGOCD_VERSION}"
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argocd -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
kubectl wait --for=condition=Available deploy --all -n argocd --timeout=300s

echo ">> CI resources"
kubectl apply -f "$ROOT/tekton/rbac/"
kubectl apply -n ci -f "$ROOT/tekton/tasks/"
kubectl apply -n ci -f "$ROOT/tekton/pipelines/"
kubectl apply -f "$ROOT/tekton/triggers/"

echo ">> CD resources"
kubectl apply -f "$ROOT/argocd/projects/"
kubectl apply -f "$ROOT/argocd/applicationsets/"

echo "Done. Create secrets next (see docs/README.md). Argo CD admin password:"
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
