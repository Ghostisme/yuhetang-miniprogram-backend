param([ValidateRange(1024,65535)][int]$Port = 8790)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$expectedJar = Join-Path $backendRoot 'target-api\reservation-api-1.0.0.jar'
$statePath = Join-Path $backendRoot "data\local-server-$Port.json"
if (!(Test-Path -LiteralPath $statePath)) { Write-Output 'No tracked local server.'; exit 0 }
$state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $($state.pid)"
if (!$process) { Write-Output 'The tracked server is already stopped.'; exit 0 }
if ($state.jar -ne $expectedJar -or $process.Name -ne 'java.exe' -or !$process.CommandLine.Contains($expectedJar)) {
    throw 'PID ownership check failed. No process was stopped.'
}
Stop-Process -Id $state.pid
Write-Output 'Stopped this project only. Business data is retained.'
