<#
  Starts PostgreSQL (Docker, if configured), the Java backend, the Python analytics service and the
  frontend in the background. Logs go to .\logs, process ids to .\.run.  Stop with .\stop.ps1
#>
. "$PSScriptRoot\scripts\common.ps1"
Set-Location $Root
Import-DotEnv
New-Item -ItemType Directory -Force $RunDir, $LogDir | Out-Null

$jar = 'backend-java\target\bms-backend.jar'
foreach ($p in @($jar, 'analytics-python\.venv\Scripts\python.exe', 'frontend\dist\index.html')) {
    if (-not (Test-Path $p)) { Write-Bad "$p not found - run .\setup.ps1 first"; exit 1 }
}
if (Test-Path "$RunDir\backend.pid") {
    $oldPid = Get-Content "$RunDir\backend.pid"
    if (Get-Process -Id $oldPid -ErrorAction SilentlyContinue) {
        Write-Bad 'Services seem to be running already (.run\backend.pid). Run .\stop.ps1 first.'; exit 1
    }
    # Left over from a crash, reboot or closed window: clean up whatever is still running, then start fresh
    Write-Host '    --  Found leftovers from a previous run that did not stop cleanly - cleaning up'
    & "$PSScriptRoot\stop.ps1" | Out-Null
    New-Item -ItemType Directory -Force $RunDir | Out-Null
}

function Start-Bg($name, $exe, $argList, $dir) {
    $p = Start-Process -FilePath $exe -ArgumentList $argList -WorkingDirectory $dir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$LogDir\$name.log" -RedirectStandardError "$LogDir\$name.err.log"
    Set-Content "$RunDir\$name.pid" $p.Id
    return $p
}

if ($env:DB_MODE -eq 'external' -and $env:USE_DOCKER_DB -eq 'true') {
    Write-Step 'Starting PostgreSQL (Docker Compose)'
    if (-not (Test-Command 'docker')) { Write-Bad 'USE_DOCKER_DB=true but docker is not installed'; exit 1 }
    & docker compose up -d --wait postgres
    if ($LASTEXITCODE -ne 0) { Write-Bad 'PostgreSQL container did not become healthy'; exit 1 }
    Set-Content "$RunDir\docker" 'true'
    Write-Ok 'PostgreSQL ready on 127.0.0.1:5432'
} elseif ($env:DB_MODE -eq 'embedded') {
    Write-Host '    --  DB_MODE=embedded: the backend starts PostgreSQL itself (data in data\postgres)'
}

Write-Step 'Starting Java backend (port 8080)'
Start-Bg 'backend' 'java' @('-jar', $jar) $Root | Out-Null
if (Wait-Http 'http://127.0.0.1:8080/api/v1/health' 180) { Write-Ok 'Backend healthy (database connected, migrations applied)' }
else { Write-Bad 'Backend did not become healthy - see logs\backend.log'; & "$PSScriptRoot\stop.ps1"; exit 1 }

Write-Step 'Starting Python analytics service (port 8090)'
Start-Bg 'analytics' "$Root\analytics-python\.venv\Scripts\python.exe" @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8090') "$Root\analytics-python" | Out-Null
if (Wait-Http 'http://127.0.0.1:8090/health' 60) { Write-Ok 'Analytics healthy' } else { Write-Bad 'Analytics did not start - see logs\analytics.err.log' }

Write-Step 'Starting frontend (port 5173)'
Start-Bg 'frontend' 'node' @('node_modules\vite\bin\vite.js', 'preview') "$Root\frontend" | Out-Null
if (Wait-Http 'http://127.0.0.1:5173/' 60) { Write-Ok 'Frontend ready' } else { Write-Bad 'Frontend did not start - see logs\frontend.err.log' }

$h = (Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/health')
Write-Host ''
Write-Host "Health: database=$($h.database) analytics=$($h.analytics)"
Write-Host 'Open http://127.0.0.1:5173  (educational simulation - no real money)' -ForegroundColor Green
Write-Host 'Stop everything with .\stop.ps1'
