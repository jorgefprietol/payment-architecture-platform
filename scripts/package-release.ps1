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
$imageEntries = @()
foreach ($reference in @($CsharpImage, $JavaImage)) {
    $imageMetadata = docker image inspect $reference | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Image inspection failed' }
    if ($imageMetadata[0].Config.Labels.'org.opencontainers.image.revision' -ne $Commit) { throw 'Image revision mismatch' }
    $imageEntries += @{ reference = $reference; id = $imageMetadata[0].Id }
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
