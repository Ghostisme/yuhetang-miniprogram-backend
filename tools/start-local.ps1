param([switch]$Build, [switch]$Lan, [ValidateRange(1024,65535)][int]$Port = 8790, [string]$AdminPath = '')
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
if (!$AdminPath) { $AdminPath = Join-Path (Split-Path -Parent $backendRoot) 'yuhetang-miniprogram-admin' }
$AdminPath = [IO.Path]::GetFullPath($AdminPath)
$jarPath = Join-Path $backendRoot 'target\reservation-1.0.0.jar'
$dataRoot = Join-Path $backendRoot 'data'
$logRoot = Join-Path $backendRoot 'logs'
$statePath = Join-Path $dataRoot "local-server-$Port.json"
New-Item -ItemType Directory -Force -Path $dataRoot, $logRoot | Out-Null
$envFile = Join-Path $backendRoot '.env.local'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}
if ($env:APP_MODE -and $env:APP_MODE -ne 'local') { throw 'This launcher is for APP_MODE=local only. Use the deployment instructions for production.' }
$env:APP_MODE = 'local'
$env:PORT = [string]$Port
$env:BIND_ADDRESS = if ($Lan) { '0.0.0.0' } else { '127.0.0.1' }
$env:APP_DATA_DIR = $dataRoot
if (!$env:DB_URL) {
    $databaseFile = (Join-Path $dataRoot 'reservation').Replace('\','/')
    $env:DB_URL = "jdbc:h2:file:$databaseFile;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=15000"
}
$listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -gt 0) {
    if (Test-Path -LiteralPath $statePath) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $running = Get-CimInstance Win32_Process -Filter "ProcessId = $($state.pid)"
        if ($running -and $running.CommandLine.Contains($jarPath) -and $listeners.OwningProcess -contains $state.pid) {
            Write-Output "Already running: http://127.0.0.1:$Port/ | http://127.0.0.1:$Port/admin"
            exit 0
        }
    }
    throw "Port $Port is in use. The launcher will not stop an untracked process."
}
if ($Build -or !(Test-Path -LiteralPath $jarPath)) {
    if (!(Test-Path -LiteralPath (Join-Path $AdminPath 'package.json'))) { throw 'Admin repository not found. Clone it beside this repository or pass -AdminPath.' }
    Push-Location $AdminPath
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    } finally { Pop-Location }
    Push-Location $backendRoot
    try {
        & mvn.cmd -Prelease package ("-Dfrontend.dist=" + (Join-Path $AdminPath 'dist'))
        if ($LASTEXITCODE -ne 0) { throw 'Java package failed' }
    } finally { Pop-Location }
}
$javaCommand = (Get-Command java.exe -ErrorAction Stop).Source
$arguments = '-Dfile.encoding=UTF-8 -jar "' + $jarPath + '"'
$process = Start-Process -FilePath $javaCommand -ArgumentList $arguments -WorkingDirectory $backendRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "backend-$Port.log") -RedirectStandardError (Join-Path $logRoot "backend-$Port-error.log")
@{pid=$process.Id;jar=$jarPath;port=$Port;startedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
for ($attempt=0; $attempt -lt 45; $attempt++) {
    if ($process.HasExited) { throw "Java exited. See logs/backend-$Port.log and logs/backend-$Port-error.log." }
    try {
        $health = Invoke-RestMethod "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
        if ($health.status -eq 'ok') {
            Write-Output "Customer: http://127.0.0.1:$Port/"
            Write-Output "Admin:    http://127.0.0.1:$Port/admin"
            Write-Output 'Local accounts: admin / yuyao / bailemen / frontdesk; password: Yht@9800dev'
            Write-Output ('Database and uploads: ' + $dataRoot)
            exit 0
        }
    } catch { }
    Start-Sleep -Milliseconds 700
    $process.Refresh()
}
throw "Startup timed out. See logs/backend-$Port.log."
