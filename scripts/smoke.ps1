param(
    [string]$CsharpUrl = 'http://127.0.0.1:18080',
    [string]$JavaUrl = 'http://127.0.0.1:18081',
    [string]$Token = $env:API_TOKEN
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Token)) { throw 'API token is required' }
$correlation = '11111111-1111-1111-1111-111111111111'
$query = '/quotes?id=11111111-1111-1111-1111-111111111111&amountMinor=1000&currency=USD'
$responses = @()
foreach ($serviceUrl in @($CsharpUrl, $JavaUrl)) {
    $health = Invoke-RestMethod -Uri "$serviceUrl/health/ready" -TimeoutSec 10
    if ($health.status -ne 'ok') { throw 'Readiness failed' }
    try { Invoke-WebRequest -Uri "$serviceUrl$query" -TimeoutSec 10 | Out-Null; throw 'Missing token was accepted' }
    catch { if ([int]$_.Exception.Response.StatusCode -ne 401) { throw } }
    $headers = @{ Authorization = "Bearer $Token"; 'X-Correlation-ID' = $correlation }
    $quoteResponse = Invoke-WebRequest -Uri "$serviceUrl$query" -Headers $headers -TimeoutSec 10
    $quote = $quoteResponse.Content | ConvertFrom-Json
    if ($quote.feeMinor -ne 20 -or $quote.currency -ne 'USD' -or $quote.route -ne 'modern' -or $quote.correlationId -ne $correlation) {
        throw 'Quote contract failed'
    }
    if ($quoteResponse.Headers['X-Correlation-ID'] -ne $correlation) { throw 'Correlation header missing' }
    $responses += $quoteResponse.Content
    foreach ($invalidQuery in @('/quotes?id=bad&amountMinor=1000&currency=USD',
        '/quotes?id=11111111-1111-1111-1111-111111111111&amountMinor=0&currency=USD',
        '/quotes?id=11111111-1111-1111-1111-111111111111&amountMinor=1000&currency=GBP')) {
        try { Invoke-WebRequest -Uri "$serviceUrl$invalidQuery" -Headers $headers -TimeoutSec 10 | Out-Null; throw 'Invalid request was accepted' }
        catch { if ([int]$_.Exception.Response.StatusCode -ne 400) { throw } }
    }
    $metrics = Invoke-WebRequest -Uri "$serviceUrl/metrics" -TimeoutSec 10
    if ($metrics.Content -notmatch 'requests_total [1-9]' -or $metrics.Content -notmatch 'errors_total [1-9]') { throw 'Metrics missing' }
}
if ($responses[0] -cne $responses[1]) { throw 'HTTP contract parity failed' }
Write-Output 'PASS authenticated_http_contracts'
Write-Output 'PASS http_validation_and_metrics'
Write-Output 'PASS http_cross_language_parity'
