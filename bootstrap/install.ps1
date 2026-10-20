# Windows equivalent of install.sh. Uses the current kubectl context.
$ErrorActionPreference = 'Stop'

$TektonPipelines = if ($env:TEKTON_PIPELINES_VERSION) { $env:TEKTON_PIPELINES_VERSION } else { 'v0.62.3' }
$TektonTriggers  = if ($env:TEKTON_TRIGGERS_VERSION)  { $env:TEKTON_TRIGGERS_VERSION }  else { 'v0.29.1' }
$TektonDashboard = if ($env:TEKTON_DASHBOARD_VERSION) { $env:TEKTON_DASHBOARD_VERSION } else { 'v0.50.0' }
$ArgoCd          = if ($env:ARGOCD_VERSION)           { $env:ARGOCD_VERSION }           else { 'v2.12.4' }
$Root = Split-Path -Parent $PSScriptRoot

kubectl apply -f "https://storage.googleapis.com/tekton-releases/pipeline/previous/$TektonPipelines/release.yaml"
kubectl apply -f "https://storage.googleapis.com/tekton-releases/triggers/previous/$TektonTriggers/release.yaml"
kubectl apply -f "https://storage.googleapis.com/tekton-releases/triggers/previous/$TektonTriggers/interceptors.yaml"
kubectl apply -f "https://storage.googleapis.com/tekton-releases/dashboard/previous/$TektonDashboard/release.yaml"
kubectl wait --for=condition=Available deploy --all -n tekton-pipelines --timeout=300s

kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argocd -f "https://raw.githubusercontent.com/argoproj/argo-cd/$ArgoCd/manifests/install.yaml"
kubectl wait --for=condition=Available deploy --all -n argocd --timeout=300s

kubectl apply -f "$Root\tekton\rbac\"
kubectl apply -n ci -f "$Root\tekton\tasks\"
kubectl apply -n ci -f "$Root\tekton\pipelines\"
kubectl apply -f "$Root\tekton\triggers\"
kubectl apply -f "$Root\argocd\projects\"
kubectl apply -f "$Root\argocd\applicationsets\"

$pw = kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}'
Write-Host "Done. Argo CD admin password: $([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($pw)))"
