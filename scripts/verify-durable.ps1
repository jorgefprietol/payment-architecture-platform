param([string]$Dotnet = 'dotnet', [string]$Java = 'java')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('payment-restart-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$token = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$headers = @{ Authorization = "Bearer $token" }
$results = @{}
$process = $null
function Check($Condition, $Message) { if (-not $Condition) { throw $Message } }
function Start-ServiceProcess($Language, $Data, [bool]$FailAfterEffect = $false, [bool]$Auto = $false) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $script:port = $listener.LocalEndpoint.Port; $listener.Stop()
    $script:base = "http://127.0.0.1:$script:port"
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = if ($Language -eq 'csharp') { $Dotnet } else { $Java }
    $arguments = if ($Language -eq 'csharp') { @( (Join-Path $projectRoot 'csharp/bin/Release/net10.0/PaymentPlatform.dll'), '--serve' ) }
        else { @('-cp', (Join-Path $projectRoot 'java/target/classes'), 'platform.Main', '--serve') }
    foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
    $info.UseShellExecute = $false; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.CreateNoWindow = $true
    $info.Environment['API_TOKEN'] = $token
    $info.Environment['SAGA_DATA_DIR'] = $Data
    $info.Environment['HTTP_HOST'] = '127.0.0.1'; $info.Environment['HTTP_PORT'] = "$script:port"
    $info.Environment['SAGA_AUTODISPATCH'] = if ($Auto) { 'true' } else { 'false' }
    $info.Environment['SAGA_FAIL_AFTER_EFFECT'] = if ($FailAfterEffect) { '1' } else { '0' }
    $script:process = [Diagnostics.Process]::Start($info)
    $script:stdout = $script:process.StandardOutput.ReadToEndAsync()
    $script:stderr = $script:process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($script:process.HasExited) { throw "Service exited: $($script:stderr.GetAwaiter().GetResult())" }
        try { $ready = Invoke-RestMethod "$script:base/health/ready" -TimeoutSec 2; if ($ready.status -eq 'ok') { return } } catch {}
        Start-Sleep -Milliseconds 100
    }
    throw 'Service startup timed out'
}
function Stop-ServiceProcess {
    if ($script:process) {
        if (-not $script:process.HasExited) { $script:process.Kill($true) }
        $script:process.WaitForExit(); $script:process.Dispose(); $script:process = $null
    }
}
function Request($Method, $Path) { Invoke-RestMethod "$script:base$Path" -Method $Method -Headers $headers -TimeoutSec 10 }
function Status($Method, $Path, $Expected) {
    $response = Invoke-WebRequest "$script:base$Path" -Method $Method -Headers $headers -SkipHttpErrorCheck -TimeoutSec 10
    Check ($response.StatusCode -eq $Expected) "Expected $Expected for $Path, got $($response.StatusCode)"
}
$id = '11111111-1111-1111-1111-111111111111'
$start = '22222222-2222-2222-2222-222222222222'
try {
    foreach ($language in @('csharp', 'java')) {
        $data = Join-Path $testRoot $language
        Start-ServiceProcess $language $data
        $unauthorized = Invoke-WebRequest "$base/sagas/$id" -SkipHttpErrorCheck
        Check ($unauthorized.StatusCode -eq 401) 'Saga must require authentication'
        Status GET "/sagas/$id" 404
        Status POST "/sagas/$id/events?eventId=$start&fact=0" 400
        Status POST "/sagas/$id/events?eventId=$start&fact=80" 400
        $before = Request POST "/sagas/$id/events?eventId=$start&fact=Start"
        Check ($before.stage -eq 'Reserving' -and $before.pending.Count -eq 1 -and $before.effects -eq 0) 'Snapshot and outbox must commit together'
        Status POST "/sagas/$id/events?eventId=33333333-3333-3333-3333-333333333333&fact=Reserved" 409
        Stop-ServiceProcess # Actual forced process termination after commit, before dispatch.
        Start-ServiceProcess $language $data $true
        $recovered = Request GET "/sagas/$id"
        Check (($before | ConvertTo-Json -Compress) -ceq ($recovered | ConvertTo-Json -Compress)) 'Checkpoint did not survive restart'
        try { Request POST "/sagas/$id/dispatch" | Out-Null } catch {}
        Check ($process.WaitForExit(10000)) 'Injected crash did not terminate the process'
        Check ($process.ExitCode -eq 86) 'Unexpected injected failure exit code'
        Stop-ServiceProcess
        Start-ServiceProcess $language $data
        $uncertain = Request GET "/sagas/$id"
        Check ($uncertain.effects -eq 1 -and $uncertain.pending.Count -eq 1 -and $uncertain.delivered.Count -eq 0) 'Missing send/ack uncertainty window'
        $delivered = Request POST "/sagas/$id/dispatch"
        Check ($delivered.effects -eq 1 -and $delivered.pending.Count -eq 0 -and $delivered.delivered.Count -eq 1) 'Replay duplicated effect'
        $replay = Request POST "/sagas/$id/events?eventId=$start&fact=Start"
        Check (($delivered | ConvertTo-Json -Compress) -ceq ($replay | ConvertTo-Json -Compress)) 'Event replay mutated checkpoint'
        Status POST "/sagas/$id/events?eventId=$start&fact=Reserved" 409
        foreach ($item in @(@('33333333-3333-3333-3333-333333333333','Reserved'), @('44444444-4444-4444-4444-444444444444','Approved'), @('55555555-5555-5555-5555-555555555555','Settled'))) {
            Request POST "/sagas/$id/events?eventId=$($item[0])&fact=$($item[1])" | Out-Null
            $complete = Request POST "/sagas/$id/dispatch"
        }
        Check ($complete.stage -eq 'Completed' -and $complete.effects -eq 3 -and $complete.events -eq 4 -and $complete.delivered.Count -eq 3) 'Happy path did not complete once'
        Stop-ServiceProcess
        Start-ServiceProcess $language $data
        $repeat = Request POST "/sagas/$id/dispatch"
        Check (($complete | ConvertTo-Json -Compress) -ceq ($repeat | ConvertTo-Json -Compress)) 'Terminal restart duplicated effects'
        $compensation = '66666666-6666-6666-6666-666666666666'
        foreach ($fact in @('Start', 'Reserved', 'Rejected', 'Released')) {
            Request POST "/sagas/$compensation/events?eventId=$([Guid]::NewGuid())&fact=$fact" | Out-Null
            $compensated = Request POST "/sagas/$compensation/dispatch"
        }
        Check ($compensated.stage -eq 'Compensated' -and $compensated.effects -eq 3 -and $compensated.pending.Count -eq 0) 'Compensation did not complete once'
        $results[$language] = $complete | ConvertTo-Json -Compress
        Stop-ServiceProcess
        # Recover the first language's checkpoint using the other implementation.
        $other = if ($language -eq 'csharp') { 'java' } else { 'csharp' }
        Start-ServiceProcess $other $data
        $portable = Request POST "/sagas/$id/dispatch"
        Check (($complete | ConvertTo-Json -Compress) -ceq ($portable | ConvertTo-Json -Compress)) 'Cross-language checkpoint recovery failed'
        Stop-ServiceProcess
        # Background recovery must reconcile pending commands without an HTTP dispatch call.
        Start-ServiceProcess $language $data
        $automaticId = [Guid]::NewGuid()
        Request POST "/sagas/$automaticId/events?eventId=$([Guid]::NewGuid())&fact=Start" | Out-Null
        Stop-ServiceProcess
        Start-ServiceProcess $language $data $false $true
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        do { $automatic = Request GET "/sagas/$automaticId"; if ($automatic.pending.Count -eq 0) { break }; Start-Sleep -Milliseconds 150 } while ([DateTime]::UtcNow -lt $deadline)
        Check ($automatic.effects -eq 1 -and $automatic.delivered.Count -eq 1 -and $automatic.pending.Count -eq 0) 'Background recovery failed'
        Stop-ServiceProcess
        $checkpointPath = Join-Path $data "$id.saga"
        $checkpoint = [IO.File]::ReadAllText($checkpointPath)
        $tampered = $checkpoint -replace "delivered\|$id`:reserve\|reserve\n", ''
        Check ($tampered -cne $checkpoint) 'Corruption fixture did not change the checkpoint'
        [IO.File]::WriteAllText($checkpointPath, $tampered)
        $rejected = $false
        $startupFailure = ''
        try { Start-ServiceProcess $language $data } catch { $startupFailure = $_.Exception.Message }
        Stop-ServiceProcess
        $diagnostic = $startupFailure + $script:stderr.GetAwaiter().GetResult()
        $rejected = $diagnostic -match 'invalid_checkpoint'
        Check $rejected "Corrupt checkpoint rejection failed: $diagnostic"
        Write-Output "PASS durable_${language}_restart_send_ack_replay_compensation_portable_checkpoint_background_corruption"
    }
    Check ($results.csharp -ceq $results.java) 'Durable HTTP contracts differ across languages'
    Write-Output 'PASS durable_http_cross_language_parity'
} finally {
    Stop-ServiceProcess
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'payment-restart-*') { throw 'Unsafe test cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
