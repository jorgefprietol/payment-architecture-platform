param(
    [Parameter(Mandatory)][string]$Archive,
    [Parameter(Mandatory)][string]$Reference,
    [Parameter(Mandatory)][string]$ExpectedConfigId,
    [Parameter(Mandatory)][string]$Commit
)
$ErrorActionPreference = 'Stop'
# Docker's classic store exposes a config digest as Id; containerd may expose a manifest digest.
# Validate the signed archive's config and layers, which have the same meaning in both stores.
$archiveManifest = @(tar -xOf $Archive manifest.json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read signed image archive manifest' }
$entry = $archiveManifest | Where-Object { $_.RepoTags -contains $Reference } | Select-Object -First 1
if (-not $entry -or $entry.Config -notmatch '^[a-z0-9][a-z0-9/_.-]*$' -or $entry.Config.Contains('..')) { throw 'Archive image entry missing or invalid' }
$digestMatch = [regex]::Match($entry.Config, '([a-f0-9]{64})(?:\.json)?$')
if (-not $digestMatch.Success -or "sha256:$($digestMatch.Groups[1].Value)" -ne $ExpectedConfigId) { throw 'Signed config identity mismatch' }
$archivedConfig = tar -xOf $Archive $entry.Config | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Cannot read signed image configuration' }
$metadata = docker image inspect $Reference | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect loaded image' }
function Assert-SignedValue($actual, $expected) {
    if ($null -eq $expected) { if ($null -ne $actual) { throw 'Loaded configuration differs' }; return }
    if ($expected -is [System.Management.Automation.PSCustomObject]) {
        foreach ($property in $expected.PSObject.Properties) { Assert-SignedValue $actual.($property.Name) $property.Value }
    } elseif ($expected -is [array]) {
        if (@($actual).Count -ne $expected.Count) { throw 'Loaded configuration array differs' }
        for ($index = 0; $index -lt $expected.Count; $index++) { Assert-SignedValue $actual[$index] $expected[$index] }
    } elseif (($actual | ConvertTo-Json -Compress) -cne ($expected | ConvertTo-Json -Compress)) { throw 'Loaded configuration value differs' }
}
Assert-SignedValue $metadata[0].Config $archivedConfig.config
Assert-SignedValue $metadata[0].RootFS.Layers $archivedConfig.rootfs.diff_ids
if ($metadata[0].Config.Labels.'org.opencontainers.image.revision' -ne $Commit -or
    $metadata[0].Os -ne $archivedConfig.os -or $metadata[0].Architecture -ne $archivedConfig.architecture) { throw 'Loaded image source or platform differs' }
Write-Output 'PASS signed_image_configuration_and_layers'
