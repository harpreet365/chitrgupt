<#
  One-time setup: checks prerequisites, creates .env with random secrets, builds everything.
  Usage:  .\setup.ps1            (build)
          .\setup.ps1 -RunTests  (build + run Java and Python test suites)
#>
param([switch]$RunTests)
. "$PSScriptRoot\scripts\common.ps1"
Set-Location $Root

Write-Step 'Checking prerequisites'
$missing = @()
if (Test-Command 'java') {
    $jv = (& cmd /c 'java -version 2>&1' | Select-Object -First 1)
    if ($jv -match '"(\d+)') { if ([int]$Matches[1] -lt 25) { $missing += "Java 25+ (found $jv)" } else { Write-Ok "Java: $jv" } }
} else { $missing += 'Java 25+ (https://adoptium.net)' }
if (Test-Command 'node') { Write-Ok "Node.js: $(node -v)" } else { $missing += 'Node.js 20+ (https://nodejs.org)' }
$py = Get-PythonCommand
if ($py) { Write-Ok "Python: $(& $py[0] $py[1..9] --version 2>&1)" } else { $missing += 'Python 3.11+ (https://python.org)' }
if (Test-Command 'docker') { Write-Ok 'Docker found (optional)' } else { Write-Host '    --  Docker not found (optional; embedded PostgreSQL will be used)' }
if ($missing.Count -gt 0) { $missing | ForEach-Object { Write-Bad "Missing: $_" }; exit 1 }

if (-not (Test-Path '.env')) {
    Write-Step 'Creating .env with random secrets'
    $adminPw = 'Adm-' + (New-Secret 8) + 'X9'
    $demoPw  = 'Demo-' + (New-Secret 6) + 'Q7'
    (Get-Content '.env.example') `
        -replace '^INTERNAL_API_TOKEN=.*',      "INTERNAL_API_TOKEN=$(New-Secret)" `
        -replace '^ANALYTICS_SERVICE_TOKEN=.*', "ANALYTICS_SERVICE_TOKEN=$(New-Secret)" `
        -replace '^DB_PASSWORD=.*',             "DB_PASSWORD=$(New-Secret 12)" `
        -replace '^BOOTSTRAP_ADMIN_PASSWORD=.*', "BOOTSTRAP_ADMIN_PASSWORD=$adminPw" `
        -replace '^DEMO_PASSWORD=.*',           "DEMO_PASSWORD=$demoPw" |
        Set-Content -Encoding ASCII '.env'
    Write-Ok '.env created (git-ignored). Credentials are printed at the end.'
} else { Write-Ok '.env already exists - leaving it unchanged' }

Write-Step 'Building Java backend (Maven wrapper downloads Maven on first run)'
Push-Location backend-java
if ($RunTests) { & .\mvnw.cmd -B package } else { & .\mvnw.cmd -B -q -DskipTests package }
if ($LASTEXITCODE -ne 0) { Pop-Location; Write-Bad 'Backend build failed'; exit 1 }
Pop-Location; Write-Ok 'backend-java/target/bms-backend.jar'

# pip/npm print notices and warnings on stderr; failures are detected through $LASTEXITCODE instead
$ErrorActionPreference = 'Continue'

Write-Step 'Setting up Python analytics service'
Push-Location analytics-python
if (-not (Test-Path '.venv')) { & $py[0] $py[1..9] -m venv .venv }
& .\.venv\Scripts\python.exe -m pip install -q --disable-pip-version-check -r requirements.txt
if ($LASTEXITCODE -ne 0) { Pop-Location; Write-Bad 'pip install failed'; exit 1 }
if ($RunTests) { & .\.venv\Scripts\python.exe -m pytest -q; if ($LASTEXITCODE -ne 0) { Pop-Location; exit 1 } }
Pop-Location; Write-Ok 'analytics-python/.venv'

Write-Step 'Building frontend'
Push-Location frontend
& npm ci --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { & npm install --no-audit --no-fund }
& npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; Write-Bad 'Frontend build failed'; exit 1 }
Pop-Location; Write-Ok 'frontend/dist'

Import-DotEnv
Write-Host ''
Write-Host 'Setup complete. Next: .\start.ps1' -ForegroundColor Green
Write-Host "  Admin login : $env:BOOTSTRAP_ADMIN_USERNAME / $env:BOOTSTRAP_ADMIN_PASSWORD (you will be asked to change it)"
Write-Host "  Demo logins : employee.demo, teller.demo, aarav.demo, priya.demo ... / $env:DEMO_PASSWORD"
Write-Host '  (values are stored in .env)'
