# Puts the GitHub credentials directly into Kubernetes Secrets (no other storage).
# Run in a VS Code terminal:  powershell -ExecutionPolicy Bypass -File d:\2026\AIO\bootstrap\set-secrets.ps1
# Values are typed with hidden input, written to short-lived temp files only for kubectl, then deleted.
$ErrorActionPreference = 'Stop'
$env:CLOUDSDK_CONFIG = 'D:\gcloud\config'
$env:Path = "D:\gcloud\google-cloud-sdk\bin;$env:Path"
$GhUser = 'satrishabh'

function Read-Secret([string]$prompt) {
    $s = Read-Host -Prompt $prompt -AsSecureString
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b).Trim() }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}

kubectl get ns ci aio-platform | Out-Null   # fails fast if not connected to cluster-1

$pat          = Read-Secret 'GitHub PAT (classic, ghp_...)'
$clientId     = Read-Host  -Prompt 'GitHub OAuth App Client ID'
$clientSecret = Read-Secret 'GitHub OAuth App Client secret'
if (-not $pat -or -not $clientId -or -not $clientSecret) { throw 'all three values are required' }

$dir = Join-Path $env:TEMP ("aio-" + [guid]::NewGuid())
New-Item -ItemType Directory $dir | Out-Null
try {
    $utf8 = New-Object Text.UTF8Encoding($false)
    $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${GhUser}:$pat"))
    [IO.File]::WriteAllText("$dir\config.json", "{`"auths`":{`"ghcr.io`":{`"auth`":`"$auth`"}}}", $utf8)
    [IO.File]::WriteAllText("$dir\token", $pat, $utf8)
    [IO.File]::WriteAllText("$dir\GITHUB_TOKEN", $pat, $utf8)
    [IO.File]::WriteAllText("$dir\GITHUB_CLIENT_ID", $clientId, $utf8)
    [IO.File]::WriteAllText("$dir\GITHUB_CLIENT_SECRET", $clientSecret, $utf8)

    # CI: Kaniko pushes to GHCR, update-gitops commits image tags
    kubectl -n ci create secret generic registry-credentials "--from-file=config.json=$dir\config.json" --dry-run=client -o yaml | kubectl apply -f -
    kubectl -n ci create secret generic github-token "--from-file=token=$dir\token" --dry-run=client -o yaml | kubectl apply -f -

    # Platform: merge GitHub keys into the existing secret (keeps DB / Argo CD values)
    $patch = @{ data = @{
        GITHUB_TOKEN         = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$dir\GITHUB_TOKEN"))
        GITHUB_CLIENT_ID     = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$dir\GITHUB_CLIENT_ID"))
        GITHUB_CLIENT_SECRET = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$dir\GITHUB_CLIENT_SECRET"))
    } } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText("$dir\patch.json", $patch, $utf8)
    kubectl -n aio-platform patch secret aio-platform-secrets --type merge --patch-file "$dir\patch.json"
}
finally {
    Remove-Item -Recurse -Force $dir -ErrorAction SilentlyContinue
    Remove-Variable pat, clientSecret, auth, patch -ErrorAction SilentlyContinue
}

kubectl -n aio-platform rollout restart deploy/aio-platform
kubectl -n aio-platform rollout status deploy/aio-platform --timeout=300s
Write-Host "`nDone. Secrets set in ci (registry-credentials, github-token) and aio-platform (aio-platform-secrets)."
