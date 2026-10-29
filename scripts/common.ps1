# Shared helpers for setup/start/stop (Windows PowerShell 5.1+).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root '.run'
$LogDir = Join-Path $Root 'logs'

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    OK  $msg" -ForegroundColor Green }
function Write-Bad($msg)  { Write-Host "    !!  $msg" -ForegroundColor Red }

function Import-DotEnv {
    $envFile = Join-Path $Root '.env'
    if (-not (Test-Path $envFile)) { throw ".env not found. Run .\setup.ps1 first." }
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $k, $v = $line -split '=', 2
        [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim(), 'Process')
    }
}

function Test-Command($name) { return [bool](Get-Command $name -ErrorAction SilentlyContinue) }

function Get-PythonCommand {
    if (Test-Command 'py') {
        foreach ($v in '3.11', '3.12', '3.13') { & py "-$v" --version *> $null; if ($LASTEXITCODE -eq 0) { return @('py', "-$v") } }
    }
    if (Test-Command 'python') { return @('python') }
    return $null
}

function Wait-Http($url, $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try { $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { return $true } } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

function New-Secret([int]$bytes = 24) {
    $b = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    return -join ($b | ForEach-Object { $_.ToString('x2') })
}
