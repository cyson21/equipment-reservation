#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PostgresBin
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$postgres = (Resolve-Path -LiteralPath $PostgresBin).Path
$initdb = Join-Path $postgres 'initdb.exe'
$pgCtl = Join-Path $postgres 'pg_ctl.exe'
$psql = Join-Path $postgres 'psql.exe'
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to a Java 21 JDK before running the demo.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jdeps = Join-Path $env:JAVA_HOME 'bin\jdeps.exe'
$jar = Join-Path $project 'app\build\libs\equipment-reservation.jar'
foreach ($file in @($initdb, $pgCtl, $psql, $java, $jdeps)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Executable not found: $file" }
}

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE." }
}

function Assert-Equal([string]$Check, $Actual, $Expected) {
    if ($Actual -cne $Expected) { throw "$Check expected [$Expected], got [$Actual]." }
    "$Check -> $Actual"
}

function Get-LoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Show-TestResults([string]$Path, [string]$Suite, [string]$RequiredCase) {
    $cases = $failed = $skipped = 0
    $seconds = 0.0
    $requiredCaseSeen = !$RequiredCase
    foreach ($file in (Get-ChildItem -LiteralPath $Path -File -Filter 'TEST-*.xml')) {
        $report = ([xml](Get-Content -LiteralPath $file.FullName -Raw)).testsuite
        $cases += [int]$report.tests
        $failed += [int]$report.failures + [int]$report.errors
        $skipped += [int]$report.skipped
        if ($RequiredCase -and $report.testcase.name -contains ($RequiredCase + '()')) {
            $requiredCaseSeen = $true
        }
        $seconds += [double]::Parse($report.time, [Globalization.CultureInfo]::InvariantCulture)
    }
    if ($cases -eq 0 -or $failed -ne 0 -or $skipped -ne 0) {
        throw "$Suite reported $cases tests, $failed failures and $skipped skips."
    }
    if (!$requiredCaseSeen) { throw "$Suite did not run $RequiredCase." }
    "$Suite -> $cases passed, 0 failed, 0 skipped ($([Math]::Round($seconds, 3)) s in JUnit suites)"
}

function Invoke-DemoRequest([string]$Method, [string]$Route, [hashtable]$Payload) {
    $request = @{
        Uri = "http://127.0.0.1:$apiPort$Route"
        Method = $Method
        TimeoutSec = 4
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Payload) {
        $request.ContentType = 'application/json'
        $request.Body = ConvertTo-Json -InputObject $Payload -Compress
    }
    Invoke-WebRequest @request
}

function Invoke-DemoCli([string[]]$Arguments) {
    $startup = [Diagnostics.ProcessStartInfo]::new($java)
    $startup.UseShellExecute = $false
    $startup.CreateNoWindow = $true
    $startup.RedirectStandardOutput = $true
    $startup.RedirectStandardError = $true
    foreach ($argument in (@('-jar', $jar, 'cli') + $Arguments)) {
        $startup.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::Start($startup)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(25000)) {
            $process.Kill()
            throw 'CLI did not exit within 25 seconds.'
        }
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Stdout = $stdout.GetAwaiter().GetResult().Trim()
            Stderr = $stderr.GetAwaiter().GetResult().Trim()
        }
    } finally { $process.Dispose() }
}

$work = [IO.Directory]::CreateTempSubdirectory('equipment-reservation-demo-').FullName
$pgData = Join-Path $work 'data'
$pgLog = Join-Path $work 'postgres.log'
$apiLog = Join-Path $work 'api.log'
$apiErrors = Join-Path $work 'api.err'
# Keep external Spring configuration out of the demo's disposable database.
$inherited = @(Get-ChildItem Env: | Where-Object Name -Match '^(SPRING|SERVER)[_.-]' | Select-Object -ExpandProperty Name)
$demoKeys = @('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'SPRING_CONFIG_LOCATION',
    'TEST_POSTGRES_URL', 'TEST_POSTGRES_USER', 'TEST_POSTGRES_PASSWORD',
    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
$savedEnvironment = @{}
foreach ($key in ($demoKeys + $inherited)) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}

$api = $null
Push-Location -LiteralPath $project
try {
    foreach ($key in $savedEnvironment.Keys) {
        Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    }
    $env:SPRING_CONFIG_LOCATION = 'classpath:/application.properties'
    Invoke-Checked '.\gradlew.bat' @(':core:test', ':core:jar', '--rerun-tasks', '--console=plain')
    Show-TestResults 'core\build\test-results\test' 'Core'
    $modules = Invoke-Checked $jdeps @('--print-module-deps', 'core\build\libs\core.jar')
    Assert-Equal 'Core JDK modules' $modules 'java.base'

    $pgPort = Get-LoopbackPort
    Invoke-Checked $initdb @('-D', $pgData, '--username=reservation_demo', '--auth=trust', '--encoding=UTF8', '--locale=C') | Out-Null
    Invoke-Checked $pgCtl @('start', '-D', $pgData, '-l', $pgLog, '-w', '-t', '20', '-o', "-h 127.0.0.1 -p $pgPort")
    $connection = @('-X', '-w', '-h', '127.0.0.1', '-p', "$pgPort", '-U', 'reservation_demo', '-v', 'ON_ERROR_STOP=1')
    Invoke-Checked $psql ($connection + @('-d', 'postgres', '-c', 'CREATE DATABASE reservation_test;', '-c', 'CREATE DATABASE reservation_demo;'))
    $env:TEST_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:$pgPort/reservation_test"
    $env:TEST_POSTGRES_USER = 'reservation_demo'
    $env:TEST_POSTGRES_PASSWORD = ''
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$pgPort/reservation_demo"
    $env:SPRING_DATASOURCE_USERNAME = 'reservation_demo'
    $env:SPRING_DATASOURCE_PASSWORD = ''

    Invoke-Checked '.\gradlew.bat' @(':app:test', ':app:postgresTest', ':app:bootJar', '--rerun-tasks', '--console=plain')
    Show-TestResults 'app\build\test-results\test' 'REST and CLI'
    Show-TestResults 'app\build\test-results\postgresTest' 'PostgreSQL' 'overlappingRequestsActuallyContendAndOnlyTheCommittedReservationSurvives'

    $apiPort = Get-LoopbackPort
    $server = @{
        FilePath = $java
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = $apiLog
        RedirectStandardError = $apiErrors
        ArgumentList = @('-jar', ('"' + $jar + '"'), "--server.port=$apiPort", '--server.address=127.0.0.1')
    }
    $api = Start-Process @server
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        if ($api.HasExited) { throw 'REST server exited during startup.' }
        try { $equipment = Invoke-DemoRequest 'GET' '/equipment' } catch [Net.Http.HttpRequestException] { $equipment = $null }
        if ($equipment.StatusCode -eq 200) { break }
        Start-Sleep -Milliseconds 200
    } until ([DateTime]::UtcNow -ge $deadline)
    Assert-Equal 'REST list' ([int]$equipment.StatusCode) 200
    $items = @(ConvertFrom-Json -InputObject $equipment.Content)
    Assert-Equal 'Seeded equipment IDs' ($items.id -join ',') '1,2'

    $booking = Invoke-DemoRequest 'POST' '/reservations' @{
        equipmentId = 1
        startsAt = '2030-01-01T23:00:00.000001+09:00'
        endsAt = '2030-01-02T00:00:00.000001+09:00'
    }
    Assert-Equal 'REST reserve' ([int]$booking.StatusCode) 201
    $original = [guid]::Parse((ConvertFrom-Json -InputObject $booking.Content).reservationId)
    $overlap = Invoke-DemoCli @('reservation', 'create', '1', '2030-01-01T14:15:00Z', '2030-01-01T14:20:00Z')
    Assert-Equal 'CLI overlap exit code' $overlap.ExitCode 4
    Assert-Equal 'CLI overlap stderr' $overlap.Stderr 'TIME_CONFLICT'
    Assert-Equal 'CLI overlap stdout' $overlap.Stdout ''

    $cancelled = Invoke-DemoRequest 'DELETE' "/reservations/$original"
    Assert-Equal 'REST cancel' ([int]$cancelled.StatusCode) 204
    Stop-Process -Id $api.Id
    if (!$api.WaitForExit(10000)) { throw 'REST server did not stop.' }

    $rebook = Invoke-DemoCli @('reservation', 'create', '1', '2030-01-01T14:00:00.000001Z', '2030-01-01T15:00:00.000001Z')
    Assert-Equal 'CLI rebook with REST stopped' $rebook.ExitCode 0
    Assert-Equal 'CLI rebook stderr' $rebook.Stderr ''
    $replacement = [guid]::Parse($rebook.Stdout)
    if ($replacement -eq $original) { throw 'New booking reused the cancelled reservation ID.' }

    $sql = @"
SELECT count(*),
       count(*) FILTER (WHERE id = '$original' AND status = 'CANCELLED'),
       count(*) FILTER (WHERE id = '$replacement' AND status = 'ACTIVE'
           AND starts_at = '2030-01-01T14:00:00.000001Z'::timestamptz
           AND ends_at = '2030-01-01T15:00:00.000001Z'::timestamptz)
FROM reservation WHERE equipment_id = 1;
"@
    $state = Invoke-Checked $psql ($connection + @('-d', 'reservation_demo', '-qAt', '-c', $sql))
    Assert-Equal 'Database rows (total|cancelled original|active exact replacement)' $state '2|1|1'
} catch {
    foreach ($log in @($apiLog, $apiErrors, $pgLog)) {
        if (Test-Path -LiteralPath $log -PathType Leaf) { Get-Content -LiteralPath $log -Tail 12 }
    }
    throw
} finally {
    try {
        Pop-Location
        foreach ($key in $savedEnvironment.Keys) {
            if ($null -eq $savedEnvironment[$key]) {
                Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process')
            }
        }
        if ($api) {
            try {
                if (!$api.HasExited) {
                    Stop-Process -Id $api.Id
                    if (!$api.WaitForExit(10000)) { throw 'REST server cleanup timed out.' }
                }
            } finally { $api.Dispose() }
        }
    } finally {
        if (Test-Path -LiteralPath (Join-Path $pgData 'postmaster.pid')) {
            Invoke-Checked $pgCtl @('stop', '-D', $pgData, '-m', 'fast', '-w', '-t', '20')
        }
        $resolved = (Resolve-Path -LiteralPath $work).Path
        $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
        if ((Split-Path -Parent $resolved) -ne $temp -or (Split-Path -Leaf $resolved) -notlike 'equipment-reservation-demo-*') {
            throw 'Unexpected temporary directory path.'
        }
        Remove-Item -LiteralPath $resolved -Recurse
    }
}
'Demo verified. Temporary PostgreSQL and REST processes were stopped.'
