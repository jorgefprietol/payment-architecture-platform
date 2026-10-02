param(
    [string]$Dotnet = 'dotnet',
    [string]$Java = 'java',
    [string]$Javac = 'javac'
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$artifactPath = Join-Path $projectRoot 'artifacts'
$classesPath = Join-Path $projectRoot 'java/target/classes'
New-Item -ItemType Directory -Force -Path $artifactPath, $classesPath | Out-Null
Push-Location $projectRoot
try {
    & $Dotnet build (Join-Path $projectRoot 'csharp/PaymentPlatform.csproj') -c Release --nologo -v quiet
    if ($LASTEXITCODE -ne 0) { throw 'C# compilation failed' }
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'java/src/platform') -Filter '*.java' | ForEach-Object FullName)
    & $Javac -encoding UTF-8 --release 21 -d $classesPath @sources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    $csharpReport = @(& $Dotnet (Join-Path $projectRoot 'csharp/bin/Release/net10.0/PaymentPlatform.dll'))
    if ($LASTEXITCODE -ne 0) { $csharpReport | Write-Output; throw 'C# scenarios failed' }
    $javaReport = @(& $Java -cp $classesPath platform.Main)
    if ($LASTEXITCODE -ne 0) { $javaReport | Write-Output; throw 'Java scenarios failed' }
    $csharpReport | Set-Content -LiteralPath (Join-Path $artifactPath 'csharp.txt') -Encoding utf8
    $javaReport | Set-Content -LiteralPath (Join-Path $artifactPath 'java.txt') -Encoding utf8
    if (($csharpReport -join "`n") -cne ($javaReport -join "`n")) { throw 'C# / Java scenario reports differ' }
    $csharpReport | Write-Output
    Write-Output 'PASS cross_language_parity'
    $csharpDemo = @(& $Dotnet (Join-Path $projectRoot 'csharp/bin/Release/net10.0/PaymentPlatform.dll') --demo)
    if ($LASTEXITCODE -ne 0) { throw 'C# demo failed' }
    $javaDemo = @(& $Java -cp $classesPath platform.Main --demo)
    if ($LASTEXITCODE -ne 0) { throw 'Java demo failed' }
    if ($csharpDemo.Count -ne 2 -or $javaDemo.Count -ne 2) { throw 'Unexpected demo output' }
    foreach ($demoLog in @($csharpDemo[0], $javaDemo[0])) {
        $parsedLog = $demoLog | ConvertFrom-Json
        if ($parsedLog.operation -ne 'quote-read' -or $parsedLog.correlationId -ne '11111111-1111-1111-1111-111111111111') {
            throw 'Invalid structured demo log'
        }
        # Read the original ISO string: newer PowerShell versions convert JSON dates to local DateTime values.
        $timestampText = [regex]::Match($demoLog, '"timestamp":"([^"]+)"').Groups[1].Value
        $logTimestamp = [DateTimeOffset]::Parse($timestampText)
        if ($logTimestamp.Offset -ne [TimeSpan]::Zero) { throw 'Log timestamp is not UTC' }
    }
    $demoSummary = $csharpDemo[1] | ConvertFrom-Json
    if ($demoSummary.route -ne 'modern' -or $demoSummary.feeMinor -ne 20 -or $demoSummary.sagaState -ne 'Completed' -or
        $demoSummary.commands -ne 3 -or $demoSummary.volumeMinor -ne 1000 -or $demoSummary.duplicateApplied -ne $false) {
        throw 'Unexpected end-to-end demo result'
    }
    if ($csharpDemo[1] -cne $javaDemo[1]) { throw 'C# / Java demo results differ' }
    $csharpDemo | Set-Content -LiteralPath (Join-Path $artifactPath 'csharp-demo.jsonl') -Encoding utf8
    $javaDemo | Set-Content -LiteralPath (Join-Path $artifactPath 'java-demo.jsonl') -Encoding utf8
    Write-Output 'PASS end_to_end_demo_parity'
} finally { Pop-Location }
