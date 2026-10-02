param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit,
    [Parameter(Mandatory)][string]$CsharpImage,
    [Parameter(Mandatory)][string]$JavaImage,
    [string]$OutputDirectory = 'artifacts/release',
    [string]$Repository = 'jorgefprietol/payment-architecture-platform'
)
$ErrorActionPreference = 'Stop'
$archivePath = Join-Path $OutputDirectory 'images.tar.gz'
if (-not (Test-Path -LiteralPath $archivePath)) { throw 'Container archive missing' }
$archiveManifest = @(tar -xOf $archivePath manifest.json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Container archive manifest cannot be read' }
$imageEntries = @()
foreach ($reference in @($CsharpImage, $JavaImage)) {
    $imageMetadata = docker image inspect $reference | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Image inspection failed' }
    if ($imageMetadata[0].Config.Labels.'org.opencontainers.image.revision' -ne $Commit) { throw 'Image revision mismatch' }
    $archiveEntry = $archiveManifest | Where-Object { $_.RepoTags -contains $reference } | Select-Object -First 1
    $configDigest = [regex]::Match($archiveEntry.Config, '([a-f0-9]{64})(?:\.json)?$')
    if (-not $configDigest.Success) { throw 'Archive config digest missing' }
    $imageEntries += @{ reference = $reference; id = "sha256:$($configDigest.Groups[1].Value)" }
}
$manifest = [ordered]@{
    schemaVersion = 1
    repository = $Repository
    commit = $Commit
    runId = $env:GITHUB_RUN_ID
    archive = 'images.tar.gz'
    sha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    images = $imageEntries
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'release.json') -Encoding utf8
Write-Output 'Release manifest generated'
