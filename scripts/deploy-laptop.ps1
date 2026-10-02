param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit,
    [Parameter(Mandatory)][long]$RunId,
    [Parameter(Mandatory)][string]$DeployRoot,
    [string]$Repository = 'jorgefprietol/payment-architecture-platform'
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($DeployRoot)
New-Item -ItemType Directory -Force -Path $root | Out-Null
$lockPath = Join-Path $root 'deployment.lock'
$deploymentLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$savedEnvironment = @{}
foreach ($name in @('API_TOKEN', 'CSHARP_IMAGE', 'JAVA_IMAGE', 'CSHARP_PORT', 'JAVA_PORT', 'MIGRATION_PERCENTAGE', 'PAYMENT_SUBNET')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $run = gh api "repos/$Repository/actions/runs/$RunId" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $run.conclusion -ne 'success' -or $run.event -ne 'push' -or $run.head_branch -ne 'main' -or
        $run.head_sha -ne $Commit -or $run.path -ne '.github/workflows/ci.yml') { throw 'Release is not from successful main CI' }
    $mainCommit = gh api "repos/$Repository/commits/main" --jq .sha
    if ($LASTEXITCODE -ne 0 -or $mainCommit -ne $Commit) { throw 'Release is no longer the main head' }
    $statePath = Join-Path $root 'current.json'
    $previous = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
    if ($previous -and $previous.commit -eq $Commit) { Write-Output 'Deployment already reconciled'; return }
    $releaseDirectory = Join-Path $root ("releases/" + $Commit + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
    gh run download $RunId --repo $Repository --name release-bundle --dir $releaseDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Release download failed' }
    $manifest = Get-Content -LiteralPath (Join-Path $releaseDirectory 'release.json') -Raw | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.repository -ne $Repository -or $manifest.commit -ne $Commit -or
        [long]$manifest.runId -ne $RunId -or $manifest.archive -ne 'images.tar.gz' -or $manifest.images.Count -ne 2) { throw 'Release manifest mismatch' }
    $archive = Join-Path $releaseDirectory 'images.tar.gz'
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sha256) { throw 'Release checksum mismatch' }
    gh attestation verify $archive --repo $Repository --signer-workflow "$Repository/.github/workflows/ci.yml" --source-ref refs/heads/main --source-digest $Commit --deny-self-hosted-runners
    if ($LASTEXITCODE -ne 0) { throw 'Release provenance verification failed' }
    docker load --input $archive
    if ($LASTEXITCODE -ne 0) { throw 'Image loading failed' }
    $expectedReferences = @("ghcr.io/$Repository-csharp`:$Commit", "ghcr.io/$Repository-java`:$Commit")
    for ($i = 0; $i -lt 2; $i++) {
        $reference = $expectedReferences[$i]
        if ($manifest.images[$i].reference -ne $reference) { throw 'Unexpected image reference' }
        & (Join-Path $PSScriptRoot 'verify-loaded-image.ps1') -Archive $archive -Reference $reference -ExpectedConfigId $manifest.images[$i].id -Commit $Commit
    }
    $envPath = Join-Path $root '.env'
    if (-not (Test-Path -LiteralPath $envPath)) {
        $newToken = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        [IO.File]::WriteAllText($envPath, "API_TOKEN=$newToken`n")
    }
    $tokenLine = Get-Content -LiteralPath $envPath | Where-Object { $_.StartsWith('API_TOKEN=') } | Select-Object -First 1
    if (-not $tokenLine -or $tokenLine.Substring(10).Length -lt 32) { throw 'Invalid laptop credential' }
    $env:API_TOKEN = $tokenLine.Substring(10)
    $env:CSHARP_IMAGE = $expectedReferences[0]; $env:JAVA_IMAGE = $expectedReferences[1]
    $env:CSHARP_PORT = '18080'; $env:JAVA_PORT = '18081'; $env:MIGRATION_PERCENTAGE = '25'
    $env:PAYMENT_SUBNET = '10.203.64.0/28'
    $sourceRoot = Split-Path $PSScriptRoot -Parent
    $composePath = Join-Path $releaseDirectory 'compose.yaml'
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'compose.yaml') -Destination $composePath
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'smoke.ps1') -Destination (Join-Path $releaseDirectory 'smoke.ps1')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'smoke-durable.ps1') -Destination (Join-Path $releaseDirectory 'smoke-durable.ps1')
    try {
        docker compose --env-file $envPath -f $composePath -p payment-platform up -d --no-build --pull never --wait --wait-timeout 90
        if ($LASTEXITCODE -ne 0) { throw 'Candidate readiness failed' }
        & (Join-Path $releaseDirectory 'smoke.ps1') -Token $env:API_TOKEN
        & (Join-Path $releaseDirectory 'smoke-durable.ps1') -Token $env:API_TOKEN
    } catch {
        $candidateFailure = $_
        if ($previous) {
            $env:CSHARP_IMAGE = $previous.images[0].reference; $env:JAVA_IMAGE = $previous.images[1].reference
            $previousCompose = Join-Path $previous.releaseDirectory 'compose.yaml'
            docker compose --env-file $envPath -f $previousCompose -p payment-platform up -d --no-build --pull never --wait --wait-timeout 90
            if ($LASTEXITCODE -ne 0) { throw 'Candidate failed and rollback readiness failed; inspect Docker status' }
            & (Join-Path $previous.releaseDirectory 'smoke.ps1') -Token $env:API_TOKEN
            Write-Output 'Previous release restored'
        } else {
            docker compose --env-file $envPath -f $composePath -p payment-platform down
        }
        throw $candidateFailure
    }
    $state = [ordered]@{ commit = $Commit; runId = $RunId; images = $manifest.images; releaseDirectory = $releaseDirectory; deployedAt = [DateTimeOffset]::UtcNow.ToString('O') }
    $temporaryState = Join-Path $root 'current.json.tmp'
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporaryState -Encoding utf8
    Move-Item -LiteralPath $temporaryState -Destination $statePath -Force
    Write-Output "Deployment healthy: $Commit"
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $deploymentLock.Dispose()
}
