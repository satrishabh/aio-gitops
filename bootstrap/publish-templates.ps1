# Publishes templates/<name> as public GitHub *template* repos (satrishabh/aio-template-<name>), tagged with the
# 'aio-template' topic so the AIO portal lists them under "AIO Starters". Safe to re-run: content is re-pushed.
# Run:  powershell -ExecutionPolicy Bypass -File d:\2026\AIO\bootstrap\publish-templates.ps1 [-Only react-vite]
param([string]$Owner = 'satrishabh', [string[]]$Only = @())
$ErrorActionPreference = 'Stop'
$env:CLOUDSDK_CONFIG = 'D:\gcloud\config'
$env:Path = "D:\tools;D:\gcloud\google-cloud-sdk\bin;$env:Path"

$templates = [ordered]@{
    'react-vite'  = @{ desc = 'AIO starter: React + Vite dashboard UI served by nginx (port 8080, /health)'; topics = @('react', 'vite', 'ui', 'dashboard') }
    'nextjs'      = @{ desc = 'AIO starter: Next.js App Router app, standalone server (port 8080, /health)'; topics = @('nextjs', 'react', 'ui') }
    'spring-boot' = @{ desc = 'AIO starter: Spring Boot 3 REST API on Java 21 (port 8080, /health)'; topics = @('spring-boot', 'java', 'api') }
    'fastapi'     = @{ desc = 'AIO starter: Python FastAPI service (port 8080, /health)'; topics = @('fastapi', 'python', 'api') }
    'go-api'      = @{ desc = 'AIO starter: Go standard-library HTTP API on distroless (port 8080, /health)'; topics = @('go', 'golang', 'api') }
}

# PAT comes from the existing Kubernetes secret; it is never printed or written to disk.
$b64 = kubectl -n ci get secret github-token -o 'jsonpath={.data.token}'
if (-not $b64) { throw 'secret ci/github-token not found - run set-secrets.ps1 first' }
$token = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64)).Trim()
$headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
$api = 'https://api.github.com'
$root = Split-Path $PSScriptRoot -Parent

foreach ($name in $templates.Keys) {
    if ($Only.Count -and $Only -notcontains $name) { continue }
    $t = $templates[$name]
    $repo = "aio-template-$name"
    Write-Host "==> $Owner/$repo"

    try { Invoke-RestMethod "$api/repos/$Owner/$repo" -Headers $headers | Out-Null }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
        $body = @{ name = $repo; description = $t.desc; private = $false; has_wiki = $false } | ConvertTo-Json
        Invoke-RestMethod "$api/user/repos" -Method Post -Headers $headers -Body $body -ContentType 'application/json' | Out-Null
        Write-Host '    created'
    }

    $work = Join-Path $env:TEMP ("aio-tpl-" + [guid]::NewGuid())
    Copy-Item -Recurse (Join-Path $root "templates\$name") $work
    try {
        Push-Location $work
        git init -q -b main
        git add -A
        git -c user.name='AIO Platform' -c user.email='aio-platform@users.noreply.github.com' commit -q -m "AIO starter template: $name"
        # Template repos are generated from, never edited in place, so the published tree is replaced wholesale.
        git -c "http.extraHeader=Authorization: Basic $basic" push -q --force "https://github.com/$Owner/$repo.git" main
        if ($LASTEXITCODE -ne 0) { throw "git push failed for $repo" }
    }
    finally { Pop-Location; Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }

    $patch = @{ is_template = $true; description = $t.desc; default_branch = 'main' } | ConvertTo-Json
    Invoke-RestMethod "$api/repos/$Owner/$repo" -Method Patch -Headers $headers -Body $patch -ContentType 'application/json' | Out-Null
    $topics = @{ names = @('aio-template', "aio-$name") + $t.topics } | ConvertTo-Json
    Invoke-RestMethod "$api/repos/$Owner/$repo/topics" -Method Put -Headers $headers -Body $topics -ContentType 'application/json' | Out-Null
    Write-Host "    published https://github.com/$Owner/$repo (template)"
}
Remove-Variable token, basic, headers -ErrorAction SilentlyContinue
