# AQuant unified service manager (Windows / PowerShell)
#
# Starts MySQL + aktools + backend + frontend as one managed set.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File aquant-services.ps1 start
#   powershell -NoProfile -ExecutionPolicy Bypass -File aquant-services.ps1 start -NoMonitor
#   powershell -NoProfile -ExecutionPolicy Bypass -File aquant-services.ps1 stop
#   powershell -NoProfile -ExecutionPolicy Bypass -File aquant-services.ps1 status
#
# Tool locations are read from environment variables so this file stays
# machine-independent. Put your own paths in a sibling file named
# `aquant-services.local.ps1` (git-ignored) and it will be loaded automatically:
#
#   $env:MYSQL_HOME     = 'C:\Program Files\MySQL\MySQL Server 8.4'
#   $env:JAVA_HOME      = '...\jdk-17'
#   $env:MAVEN_HOME     = '...\apache-maven-3.9.16'
#   $env:AQUANT_PYTHON  = '...\python.exe'      # interpreter with aktools+akshare
#   $env:AQUANT_NODE_DIR= '...\node'            # dir containing npm.cmd
#   $env:MYSQL_DATADIR  = '...\mysql\data'
#
# NOTE: keep this file ASCII-only. A non-ASCII byte here is read as GBK by
# Windows PowerShell and breaks parsing.

$ErrorActionPreference = 'Stop'

# ---- project root: this script's own directory (no hard-coded path) ----
if (-not $env:PROJECT) { $env:PROJECT = $PSScriptRoot }

# ---- per-machine overrides (git-ignored, optional) ----
$localFile = Join-Path $env:PROJECT 'aquant-services.local.ps1'
if (Test-Path $localFile) { . $localFile }

function Get-Setting([string]$name, [string]$default) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($v)) { return $default }
    return $v
}

$MYSQL_HOME    = Get-Setting 'MYSQL_HOME'    'C:\Program Files\MySQL\MySQL Server 8.4'
$JAVA_HOME     = Get-Setting 'JAVA_HOME'     ''
$MAVEN_HOME    = Get-Setting 'MAVEN_HOME'    ''
$PYTHON        = Get-Setting 'AQUANT_PYTHON' 'python'
$NODE_DIR      = Get-Setting 'AQUANT_NODE_DIR' ''
$MYSQL_DATADIR = Get-Setting 'MYSQL_DATADIR' (Join-Path $env:PROJECT '.runtime\mysql\data')

$LogDir  = Join-Path $env:PROJECT '.runtime\logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$PidFile = Join-Path $LogDir 'pids.json'

# ---- components ----
$components = @(
    @{ name='MySQL';    port=3306; exe="$MYSQL_HOME\bin\mysqld.exe"
       args=@("--basedir=`"$MYSQL_HOME`"","--datadir=`"$MYSQL_DATADIR`"","--lc-messages-dir=`"$MYSQL_HOME\share`"","--port=3306","--console")
       wd="$MYSQL_HOME"; health='port'; healthUrl=$null; extraPath=$null },
    @{ name='aktools';  port=8080; exe="$PYTHON"; args=@('-m','aktools','--host','0.0.0.0','--port','8080')
       wd="$env:PROJECT"; health='http'; healthUrl='http://127.0.0.1:8080/docs'; extraPath=$null },
    @{ name='backend';  port=8084; exe="$MAVEN_HOME\bin\mvn.cmd"; args=@('-B','spring-boot:run')
       wd="$env:PROJECT\aquant-backend"; health='http'; healthUrl='http://127.0.0.1:8084/doc.html'; extraPath=$null },
    @{ name='frontend'; port=5173; exe="$NODE_DIR\npm.cmd"; args=@('run','dev')
       wd="$env:PROJECT\aquant-frontend"; health='http'; healthUrl='http://localhost:5173/'; extraPath="$NODE_DIR" }
)

function Test-Healthy($c) {
    if ($c.health -eq 'port') {
        return (Get-NetTCPConnection -State Listen -LocalPort $c.port -ErrorAction SilentlyContinue) -ne $null
    }
    try { $null = Invoke-WebRequest -Uri $c.healthUrl -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop; return $true }
    catch { return $false }
}

function Get-RunningPids {
    if (Test-Path $PidFile) {
        try {
            $o = Get-Content $PidFile -Raw | ConvertFrom-Json
            $h = @{}
            foreach ($prop in $o.psobject.Properties) { $h[$prop.Name] = [string]$prop.Value }
            return $h
        } catch { return @{} }
    }
    return @{}
}

function Test-Prerequisites {
    $missing = @()
    foreach ($c in $components) {
        if ($c.exe -match '^\S*\\' -and -not (Test-Path $c.exe)) { $missing += "$($c.name): $($c.exe)" }
    }
    if ($missing.Count -gt 0) {
        Write-Host 'Missing executables -- set the matching env vars (or aquant-services.local.ps1):'
        $missing | ForEach-Object { Write-Host "  $_" }
        Write-Host ''
        Write-Host '  MYSQL_HOME / JAVA_HOME / MAVEN_HOME / AQUANT_PYTHON / AQUANT_NODE_DIR'
        return $false
    }
    return $true
}

function StartAll([bool]$monitor) {
    if (-not (Test-Prerequisites)) { exit 1 }

    # Leaked host env vars would override Spring Boot's server.port and make the
    # backend grab the host's own port instead of 8084 -- clear them first.
    foreach ($v in 'SERVER__PORT','SERVER__HOST') {
        if (Test-Path "Env:$v") { Remove-Item "Env:$v" -Force }
    }

    $pids = @{}
    foreach ($c in $components) {
        if (Test-Healthy $c) { Write-Host "[skip] $($c.name) already up (port $($c.port))"; continue }
        Write-Host "[start] $($c.name) ..."
        $log = Join-Path $LogDir "$($c.name).log"
        $err = Join-Path $LogDir "$($c.name).err.log"
        if ($c.extraPath) { $env:PATH = "$($c.extraPath);$env:PATH" }
        $proc = Start-Process -FilePath $c.exe -ArgumentList $c.args -WorkingDirectory $c.wd `
            -RedirectStandardOutput $log -RedirectStandardError $err -PassThru -WindowStyle Hidden
        $pids[$c.name] = $proc.Id
        Write-Host "        PID=$($proc.Id)  log=$log"
    }
    $pids | ConvertTo-Json | Set-Content $PidFile -Encoding UTF8
    Write-Host ''
    Write-Host 'AQuant started.'
    Write-Host '  Frontend   http://localhost:5173'
    Write-Host '  API docs   http://127.0.0.1:8084/doc.html'
    Write-Host '  Data svc   http://127.0.0.1:8080/docs'

    if (-not $monitor) { return }
    Write-Host ''
    Write-Host 'Monitoring (Ctrl+C stops monitoring, NOT the services).'
    while ($true) {
        Start-Sleep -Seconds 10
        foreach ($c in $components) {
            if (-not (Test-Healthy $c)) { Write-Host "[warn] $($c.name) port $($c.port) unreachable" }
        }
    }
}

function StopAll {
    $pids = Get-RunningPids
    foreach ($c in $components) {
        $procId = $pids[$c.name]
        if ($procId) {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue; Write-Host "[stop] $($c.name) (PID $procId)" }
            else { Write-Host "[stop] $($c.name) already exited" }
        } else { Write-Host "[stop] $($c.name) no recorded PID" }
    }
    Remove-Item $PidFile -ErrorAction SilentlyContinue
    Write-Host 'All components stopped.'
}

function Status { foreach ($c in $components) { $ok = Test-Healthy $c; Write-Host ("{0}`t{1}`tport {2}" -f ($(if($ok){'UP'}else{'DOWN'})), $c.name, $c.port) } }

if ($NODE_DIR) { $env:PATH = "$NODE_DIR;$env:PATH" }

if (-not $args) { Write-Host 'Usage: aquant-services.ps1 <start [-NoMonitor]|stop|status>'; exit 1 }
$monitor = -not ($args -contains '-NoMonitor')
switch ($args[0]) {
    'start'  { StartAll $monitor }
    'stop'   { StopAll }
    'status' { Status }
    default  { Write-Host "Unknown action: $($args[0]) (use start / stop / status)" }
}
