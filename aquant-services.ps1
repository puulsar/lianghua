# AQuant unified service manager (all components live under D:\AQuant, single sandbox)
# Usage:
#   powershell -ExecutionPolicy Bypass -File aquant-services.ps1 start    # start all + monitor
#   powershell -ExecutionPolicy Bypass -File aquant-services.ps1 stop     # stop all
#   powershell -ExecutionPolicy Bypass -File aquant-services.ps1 status   # show status

# ---- all runtime data kept under D:\AQuant so the whole stack runs inside one sandbox ----
$env:MYSQL_HOME     = "C:\Program Files\MySQL\MySQL Server 8.4"
$env:MYSQL_DATADIR  = "D:\AQuant\.runtime\mysql\data"
$env:JAVA_HOME      = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:MAVEN_HOME     = "C:\Users\Admin\.workbuddy\binaries\maven\apache-maven-3.9.16"
$env:PYTHON         = "C:\Users\Admin\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
$env:NODE_DIR       = "C:\Users\Admin\.workbuddy\binaries\node\versions\22.22.2-2"
$env:PROJECT        = "D:\AQuant"

$LogDir  = "D:\AQuant\.runtime\logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$PidFile = Join-Path $LogDir "pids.json"

# ---- components ----
$components = @(
    @{ name="MySQL";    port=3306; exe="$env:MYSQL_HOME\bin\mysqld.exe"
       args=@("--basedir=`"$env:MYSQL_HOME`"","--datadir=`"$env:MYSQL_DATADIR`"","--lc-messages-dir=`"$env:MYSQL_HOME\share`"","--port=3306","--console")
       wd="$env:MYSQL_HOME"; health="port"; healthUrl=$null; extraPath=$null },
    @{ name="aktools";  port=8080; exe="$env:PYTHON"; args=@("-m","aktools")
       wd="$env:PROJECT"; health="http"; healthUrl="http://127.0.0.1:8080/docs"; extraPath=$null },
    @{ name="backend";  port=8084; exe="$env:MAVEN_HOME\bin\mvn.cmd"; args=@("-B","spring-boot:run")
       wd="$env:PROJECT\aquant-backend"; health="http"; healthUrl="http://127.0.0.1:8084/doc.html"; extraPath=$null },
    @{ name="frontend"; port=5173; exe="$env:NODE_DIR\npm.cmd"; args=@("run","dev")
       wd="$env:PROJECT\aquant-frontend"; health="http"; healthUrl="http://localhost:5173/"; extraPath="$env:NODE_DIR" }
)

function Test-Healthy($c) {
    if ($c.health -eq "port") {
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

function StartAll {
    $pids = @{}
    foreach ($c in $components) {
        if (Test-Healthy $c) { Write-Host "[skip] $($c.name) already up (port $($c.port))"; continue }
        Write-Host "[start] $($c.name) ..."
        $log = Join-Path $LogDir "$($c.name).log"
        $err = Join-Path $LogDir "$($c.name).err.log"
        $proc = Start-Process -FilePath $c.exe -ArgumentList $c.args -WorkingDirectory $c.wd `
            -RedirectStandardOutput $log -RedirectStandardError $err -PassThru -WindowStyle Hidden
        $pids[$c.name] = $proc.Id
        Write-Host "        PID=$($proc.Id)  log=$log"
    }
    $pids | ConvertTo-Json | Set-Content $PidFile -Encoding UTF8
    Write-Host ""
    Write-Host "AQuant unified sandbox started. Idle monitor running. Press Ctrl+C to stop monitoring."
    Write-Host "  Frontend   http://localhost:5173"
    Write-Host "  API docs   http://127.0.0.1:8084/doc.html"
    Write-Host "  Data svc   http://127.0.0.1:8080/docs"
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
    Write-Host "All components stopped."
}

function Status { foreach ($c in $components) { $ok = Test-Healthy $c; Write-Host ("{0}`t{1}`tport {2}" -f ($(if($ok){"UP"}else{"DOWN"})), $c.name, $c.port) } }

$env:PATH = "$env:NODE_DIR;$env:PATH"

if (-not $args) { Write-Host "Usage: aquant-services.ps1 <start|stop|status>"; exit 1 }
switch ($args[0]) {
    "start"  { StartAll }
    "stop"   { StopAll }
    "status" { Status }
    default  { Write-Host "Unknown action: $($args[0]) (use start / stop / status)" }
}