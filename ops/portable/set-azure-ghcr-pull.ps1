param(
    [Parameter(Mandatory=$true)][string]$GitHubUsername,
    [string]$Kubeconfig = 'D:\2026\AIO\.tools\azure-creative.kubeconfig',
    [string]$Context = 'aio-azure-creative',
    [string]$Namespace = 'aio-validation'
)
$ErrorActionPreference = 'Stop'
# Run in your own interactive PowerShell terminal. Never supply the token as an argument.
$secureToken = Read-Host 'Dedicated GHCR read-only token (hidden)' -AsSecureString
$tokenPointer = [IntPtr]::Zero
try {
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
    if ([string]::IsNullOrWhiteSpace($plainToken)) { throw 'An empty token is not valid.' }
    $encodedAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($GitHubUsername + ':' + $plainToken))
    $configJson = @{auths=@{'ghcr.io'=@{auth=$encodedAuth}}} | ConvertTo-Json -Depth 5 -Compress
    $manifest = @{
        apiVersion='v1'; kind='Secret'
        metadata=@{name='ghcr-readonly';namespace=$Namespace}
        type='kubernetes.io/dockerconfigjson'
        data=@{'.dockerconfigjson'=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($configJson))}
    }
    # Server-side apply avoids persisting the credential again in a last-applied annotation.
    $manifest | ConvertTo-Json -Depth 8 -Compress | & kubectl --kubeconfig $Kubeconfig --context $Context apply --server-side --field-manager=aio-registry-credential -f -
    if ($LASTEXITCODE -ne 0) { throw 'Azure registry Secret installation failed.' }
    Write-Host 'GHCR pull Secret installed. No workload was started by this script.'
} finally {
    if ($tokenPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer) }
    if ($secureToken) { $secureToken.Dispose() }
    $plainToken=$null; $encodedAuth=$null; $configJson=$null; $manifest=$null
}
