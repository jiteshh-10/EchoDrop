param(
    [string[]]$DeviceSerials = @(),
    [string]$PackageName = "com.dev.echodrop",
    [string]$MainActivity = ".MainActivity",
    [int]$CaptureSeconds = 120,
    [string]$OutputDir = "logs"
)

$ErrorActionPreference = "Stop"

function Require-Adb {
    $adb = "C:\Users\jites\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        throw "adb not found at $adb"
    }
    return $adb
}

function Get-ConnectedSerials {
    param([string]$Adb)
    $lines = & $Adb devices | Out-String
    return ($lines -split "`n" | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0].Trim() })
}

function Run-ForEachDevice {
    param([string]$Adb, [string[]]$Serials, [scriptblock]$Action)
    foreach ($s in $Serials) {
        & $Action $Adb $s
    }
}

$adb = Require-Adb
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

$connected = Get-ConnectedSerials -Adb $adb
if ($DeviceSerials.Count -eq 0) {
    $DeviceSerials = $connected
}

if ($DeviceSerials.Count -lt 3) {
    Write-Error "Need >=3 connected devices for Phase 3 stress test. Found: $($DeviceSerials.Count)"
}

Write-Host "Using devices: $($DeviceSerials -join ', ')"

Run-ForEachDevice -Adb $adb -Serials $DeviceSerials -Action {
    param($Adb, $Serial)
    & $Adb -s $Serial logcat -c
    & $Adb -s $Serial shell am start -n "$PackageName/$MainActivity" | Out-Null
}

Write-Host "Capture running for $CaptureSeconds seconds..."
Start-Sleep -Seconds $CaptureSeconds

$markers = "RELAY_TRIGGERED|RELAY_SENT|RELAY_RECEIVED|DEDUP_SKIPPED|GATT_CONNECTED|MANIFEST_EXCHANGED|MESSAGE_RECEIVED|DB_INSERT|ED:BT_STATE_OFF|ED:BT_STATE_ON|ED:SERVICE_ALREADY_RUNNING"

Run-ForEachDevice -Adb $adb -Serials $DeviceSerials -Action {
    param($Adb, $Serial)
    $outFile = Join-Path $OutputDir "phase3_$Serial.log"
    (& $Adb -s $Serial logcat -d | Select-String -Pattern $markers) | Out-File -Encoding utf8 $outFile
    Write-Host "Wrote $outFile"
}

Write-Host "Phase 3 validation capture complete."
