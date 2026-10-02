param(
    [string]$CsharpUrl = 'http://127.0.0.1:18080', [string]$JavaUrl = 'http://127.0.0.1:18081',
    [string]$Token = $env:API_TOKEN, [switch]$RestartContainers,
    [string]$Project = 'payment-platform-ci', [string]$ComposeFile = 'compose.yaml'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Token)) { throw 'API token is required' }
$headers = @{ Authorization = "Bearer $Token" }
$id = [Guid]::NewGuid().ToString('D'); $startId = [Guid]::NewGuid().ToString('D')
$urls = @($CsharpUrl, $JavaUrl)
function Wait-Delivered($Url, $Stage, $Effects) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $state = Invoke-RestMethod "$Url/sagas/$id" -Headers $headers -TimeoutSec 10
        if ($state.stage -eq $Stage -and $state.pending.Count -eq 0 -and $state.effects -eq $Effects -and $state.delivered.Count -eq $Effects) { return $state }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Durable outbox did not reconcile'
}
foreach ($url in $urls) {
    Invoke-RestMethod "$url/sagas/$id/events?eventId=$startId&fact=Start" -Method Post -Headers $headers -TimeoutSec 10 | Out-Null
    Wait-Delivered $url 'Reserving' 1 | Out-Null
}
if ($RestartContainers) {
    docker compose -f $ComposeFile -p $Project restart --timeout 2
    if ($LASTEXITCODE -ne 0) { throw 'Container restart failed' }
    docker compose -f $ComposeFile -p $Project up -d --no-build --pull never --wait --wait-timeout 90
    if ($LASTEXITCODE -ne 0) { throw 'Restart readiness failed' }
}
foreach ($url in $urls) {
    $state = Wait-Delivered $url 'Reserving' 1
    $replay = Invoke-RestMethod "$url/sagas/$id/events?eventId=$startId&fact=Start" -Method Post -Headers $headers -TimeoutSec 10
    if (($state | ConvertTo-Json -Compress) -cne ($replay | ConvertTo-Json -Compress)) { throw 'Restart event replay duplicated effects' }
    $conflict = Invoke-WebRequest "$url/sagas/$id/events?eventId=$startId&fact=Reserved" -Method Post -Headers $headers -SkipHttpErrorCheck -TimeoutSec 10
    if ($conflict.StatusCode -ne 409) { throw 'Conflicting replay accepted' }
}
foreach ($item in @(@('Reserved','Checking',2), @('Approved','Settling',3), @('Settled','Completed',3))) {
    $eventId = [Guid]::NewGuid().ToString('D')
    foreach ($url in $urls) {
        Invoke-RestMethod "$url/sagas/$id/events?eventId=$eventId&fact=$($item[0])" -Method Post -Headers $headers -TimeoutSec 10 | Out-Null
        Wait-Delivered $url $item[1] $item[2] | Out-Null
    }
}
$responses = @($urls | ForEach-Object { Invoke-RestMethod "$_/sagas/$id" -Headers $headers -TimeoutSec 10 | ConvertTo-Json -Compress })
if ($responses[0] -cne $responses[1]) { throw 'Durable HTTP parity failed' }
Write-Output 'PASS durable_container_http_outbox_replay_parity'
if ($RestartContainers) { Write-Output 'PASS durable_container_volume_restart' }
