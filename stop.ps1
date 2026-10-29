<#
  Stops the services started by start.ps1. The backend is asked to shut down gracefully (which also
  stops embedded PostgreSQL cleanly); anything still running afterwards is terminated.
#>
. "$PSScriptRoot\scripts\common.ps1"
Set-Location $Root
try { Import-DotEnv } catch { }

Write-Step 'Stopping Java backend'
try {
    Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/actuator/shutdown' -Headers @{ 'X-Service-Token' = $env:INTERNAL_API_TOKEN } -ContentType 'application/json' -Body '{}' -TimeoutSec 5 | Out-Null
    Write-Ok 'Graceful shutdown requested'
    Start-Sleep -Seconds 6
} catch { Write-Host "    --  Graceful shutdown not possible ($($_.Exception.Message))" }

foreach ($name in 'frontend', 'analytics', 'backend') {
    $pidFile = "$RunDir\$name.pid"
    if (Test-Path $pidFile) {
        $procId = Get-Content $pidFile
        if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
            & taskkill /PID $procId /T /F *> $null
            Write-Ok "Stopped $name (pid $procId)"
        } else { Write-Ok "$name already stopped" }
        Remove-Item $pidFile -Force
    }
}

# Embedded PostgreSQL normally stops with the backend; if the backend was killed, stop it cleanly with pg_ctl.
$pgPort = if ($env:EMBEDDED_DB_PORT) { $env:EMBEDDED_DB_PORT } else { '55432' }
Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match "-p $pgPort\b" -and $_.CommandLine -match '-D "([^"]+)"' } |
    ForEach-Object {
        $null = $_.CommandLine -match '-D "([^"]+)"'
        $pgCtl = Join-Path (Split-Path $_.ExecutablePath) 'pg_ctl.exe'
        & $pgCtl stop -D $Matches[1] -m fast *> $null
        Write-Ok "Stopped embedded PostgreSQL (pid $($_.ProcessId))"
    }

if (Test-Path "$RunDir\docker") {
    Write-Step 'Stopping PostgreSQL container (data volume is kept)'
    & docker compose stop postgres
    Remove-Item "$RunDir\docker" -Force
}
Write-Host 'All services stopped.' -ForegroundColor Green
