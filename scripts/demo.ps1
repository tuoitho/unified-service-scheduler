param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$LondonDealershipId = "11111111-1111-1111-1111-111111111111"
$OilChangeId        = "22222222-2222-2222-2222-222222222221"
$EvDiagId           = "22222222-2222-2222-2222-222222222223"

$TargetDate = (Get-Date).ToUniversalTime().Date.AddDays(14).ToString("yyyy-MM-dd")
$MorningSlot = "${TargetDate}T09:00:00Z"
$BurstSlot   = "${TargetDate}T14:00:00Z"

Write-Host "`n=== Unified Service Scheduler: End-to-End & Concurrency Demo ===" -ForegroundColor Cyan
Write-Host "Target Base URL : $BaseUrl"
Write-Host "Demo Date (UTC) : $TargetDate`n"

# 1. Health Check
Write-Host "[1/7] Checking service health (/actuator/health)..." -ForegroundColor Yellow
$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get
Write-Host "      Status: $($health.status)`n" -ForegroundColor Green

# 2. Catalog Discovery
Write-Host "[2/7] Discovering seeded dealerships & service types..." -ForegroundColor Yellow
$dealership = Invoke-RestMethod -Uri "$BaseUrl/api/v1/dealerships/$LondonDealershipId" -Method Get
$serviceTypes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/service-types" -Method Get
$activeBayCount = @($dealership.serviceBays | Where-Object { $_.active }).Count
$activeTechCount = @($dealership.technicians | Where-Object { $_.active }).Count
Write-Host "      Dealership : $($dealership.name) ($($dealership.code), TZ=$($dealership.timezone))"
Write-Host "      Resources  : $activeBayCount active bays, $activeTechCount active technicians"
Write-Host "      Catalog    : $($serviceTypes.Count) service types available`n"

# 3. Real-Time Availability Check
Write-Host "[3/7] Checking real-time availability for OIL_CHANGE at $MorningSlot..." -ForegroundColor Yellow
$avail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/availability/check?dealershipId=$LondonDealershipId&serviceTypeId=$OilChangeId&startTime=$MorningSlot" -Method Get
Write-Host "      Available=$($avail.available) | FreeBays=$($avail.availableBays) | QualifiedTechs=$($avail.availableQualifiedTechnicians) | MaxConcurrent=$($avail.maxConcurrentBookings)`n"

# 4. Book Single Appointment with Correlation ID
Write-Host "[4/7] Booking single OIL_CHANGE appointment at $MorningSlot..." -ForegroundColor Yellow
$corrId = "demo-single-" + [guid]::NewGuid().ToString().Substring(0, 8)
$singleBody = @{
    dealershipId     = $LondonDealershipId
    customerId       = [guid]::NewGuid().ToString()
    vehicleId        = [guid]::NewGuid().ToString()
    serviceTypeId    = $OilChangeId
    desiredStartTime = $MorningSlot
    notes            = "Routine annual synthetic oil service"
} | ConvertTo-Json

$singleResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/appointments" `
    -Method Post `
    -ContentType "application/json" `
    -Headers @{ "X-Correlation-ID" = $corrId } `
    -Body $singleBody

Write-Host "      201 Created -> Appointment=$($singleResp.id)" -ForegroundColor Green
Write-Host "      Assigned Bay=$($singleResp.serviceBayNumber) | Technician=$($singleResp.technicianName) | Window=$($singleResp.startTime) -> $($singleResp.endTime)`n"

# 5. High-Concurrency Burst (12 simultaneous requests for EV_BATTERY_DIAG: 3 bays, 2 EV-qualified techs)
Write-Host "[5/7] Firing 12 simultaneous EV_BATTERY_DIAG requests for $BurstSlot..." -ForegroundColor Yellow
Write-Host "      (London Flagship has 3 active bays, but only 2 EV_HIGH_VOLTAGE technicians: Priya & Liam)"

$httpClient = [System.Net.Http.HttpClient]::new()
try {
    $tasks = @()
    for ($i = 1; $i -le 12; $i++) {
        $payload = @{
            dealershipId     = $LondonDealershipId
            customerId       = [guid]::NewGuid().ToString()
            vehicleId        = [guid]::NewGuid().ToString()
            serviceTypeId    = $EvDiagId
            desiredStartTime = $BurstSlot
            notes            = "Concurrent EV burst request #$i"
        } | ConvertTo-Json -Compress

        $reqMsg = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/v1/appointments")
        $reqMsg.Headers.Add("X-Correlation-ID", "demo-burst-$i")
        $reqMsg.Content = [System.Net.Http.StringContent]::new($payload, [System.Text.Encoding]::UTF8, "application/json")
        $tasks += $httpClient.SendAsync($reqMsg)
    }

    [System.Threading.Tasks.Task]::WaitAll($tasks)

    $createdList = @()
    $conflictCount = 0
    $unexpectedCount = 0

    foreach ($t in $tasks) {
        $res = $t.Result
        $statusCode = [int]$res.StatusCode
        $jsonText = $res.Content.ReadAsStringAsync().Result
        if ($statusCode -eq 201) {
            $createdList += ($jsonText | ConvertFrom-Json)
        } elseif ($statusCode -eq 409) {
            $conflictCount++
        } else {
            $unexpectedCount++
            Write-Host "      Unexpected HTTP $statusCode : $jsonText" -ForegroundColor Red
        }
    }

    Write-Host "      Burst Results -> 201 Created: $($createdList.Count) | 409 Conflict: $conflictCount | Unexpected: $unexpectedCount" -ForegroundColor Green
    foreach ($appt in $createdList) {
        Write-Host "        * Confirmed $($appt.id) -> Bay=$($appt.serviceBayNumber), Technician=$($appt.technicianName)"
    }
    if ($createdList.Count -ne 2 -or $conflictCount -ne 10 -or $unexpectedCount -ne 0) {
        throw "Concurrency invariant violated! Expected 2 created and 10 conflicts."
    }
} finally {
    $httpClient.Dispose()
}
Write-Host ""

# 6. Cancel & Re-Book Freed Capacity
$toCancelId = $createdList[0].id
Write-Host "[6/7] Cancelling EV appointment $toCancelId and re-booking freed slot..." -ForegroundColor Yellow
$cancelled = Invoke-RestMethod -Uri "$BaseUrl/api/v1/appointments/$toCancelId/cancel" -Method Post
Write-Host "      Cancelled status=$($cancelled.status)"

$rebookBody = @{
    dealershipId     = $LondonDealershipId
    customerId       = [guid]::NewGuid().ToString()
    vehicleId        = [guid]::NewGuid().ToString()
    serviceTypeId    = $EvDiagId
    desiredStartTime = $BurstSlot
    notes            = "Re-booked immediately after cancellation"
} | ConvertTo-Json

$rebooked = Invoke-RestMethod -Uri "$BaseUrl/api/v1/appointments" -Method Post -ContentType "application/json" -Body $rebookBody
Write-Host "      Re-book succeeded -> Appointment=$($rebooked.id) | Bay=$($rebooked.serviceBayNumber) | Technician=$($rebooked.technicianName)`n" -ForegroundColor Green

# 7. Cleanup Demo Bookings & Inspect Prometheus Metrics
Write-Host "[7/7] Inspecting Prometheus domain metrics (/actuator/prometheus)..." -ForegroundColor Yellow
$promText = Invoke-RestMethod -Uri "$BaseUrl/actuator/prometheus" -Method Get
$metricLines = ($promText -split "`n") | Where-Object { $_ -match "^scheduler_booking_(requests_total|duration_seconds_count)" }
foreach ($line in $metricLines) {
    Write-Host "      $line"
}

# Clean up created demo appointments so script is idempotently repeatable on the same day
Invoke-RestMethod -Uri "$BaseUrl/api/v1/appointments/$($singleResp.id)/cancel" -Method Post | Out-Null
Invoke-RestMethod -Uri "$BaseUrl/api/v1/appointments/$($createdList[1].id)/cancel" -Method Post | Out-Null
Invoke-RestMethod -Uri "$BaseUrl/api/v1/appointments/$($rebooked.id)/cancel" -Method Post | Out-Null

Write-Host "`nDemo completed successfully with zero double-bookings!" -ForegroundColor Cyan
