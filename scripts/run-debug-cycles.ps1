$ErrorActionPreference = 'Stop'
$serial = if ($env:ANDROID_SERIAL) { $env:ANDROID_SERIAL } else { 'emulator-5556' }
$component = 'com.amaya.intelligence.debug/com.amaya.intelligence.ui.activities.debug.DebugActivity'
$browser = 'com.amaya.intelligence.debug/com.amaya.intelligence.ui.activities.browser.BrowserDebugActivity'
$log = "app/build/debug-cycles-$((Get-Date).ToString('yyyyMMdd-HHmmss')).log"

function Invoke-Adb([string[]]$arguments) { & adb.exe -s $serial @arguments | Tee-Object -FilePath $log -Append }
function Start-Debug([string]$mode, [hashtable]$extras = @{}) {
  $args = @('shell','am','start','-W','-n',$component,'--es','mode',$mode,'--ez','autorun','true')
  foreach ($key in $extras.Keys) { $args += @('--el',$key,[string]$extras[$key]) }
  Invoke-Adb $args
}
function Wait-Marker([string]$marker, [int]$seconds = 120) {
  for ($i=0; $i -lt $seconds; $i++) {
    $line = (& adb.exe -s $serial logcat -d -s AmayaDebug:I AmayaBrowserDebug:I '*:S' | Select-String $marker | Select-Object -Last 1)
    if ($line) { return $line.Line }
    Start-Sleep 1
  }
  throw "Timeout waiting for $marker"
}
function Stop-App() { Invoke-Adb @('shell','am','force-stop','com.amaya.intelligence.debug') | Out-Null }

Invoke-Adb @('wait-for-device')
Invoke-Adb @('shell','input','keyevent','224') | Out-Null
Invoke-Adb @('logcat','-c') | Out-Null

# Deterministic local cycles.
Start-Debug 'all-no-stream'
Wait-Marker 'SUMMARY' | Out-Host
Stop-App

# Delegation uses the first two agents in the first persisted group.
Start-Debug 'delegation'
Wait-Marker 'delegation-live' 240 | Out-Host
Stop-App

Start-Debug 'headless' @{ iterations = 1000 }
Wait-Marker 'SUMMARY' | Out-Host
Stop-App

Start-Debug 'background'
Start-Sleep 3
Invoke-Adb @('shell','input','keyevent','3') | Out-Null
Start-Sleep 20
Invoke-Adb @('logcat','-d','-s','AmayaDebug:I','*:S')
Stop-App

# Browser headless / process restore are already fully in-process and report via logcat.
Invoke-Adb @('shell','am','start','-W','-n',$browser,'--es','mode','manager-headless')
Wait-Marker 'MANAGER_HEADLESS SUMMARY' | Out-Host
Stop-App

Start-Debug 'corruption'
Wait-Marker 'debug-report-corruption' | Out-Host
Stop-App

Start-Debug 'streaming'
Wait-Marker 'SUMMARY' 180 | Out-Host
Stop-App

Start-Debug 'stream-cancel-retry'
Wait-Marker 'SUMMARY' 180 | Out-Host
Stop-App

Start-Debug 'delegation-matrix'
Wait-Marker 'SUMMARY' 1800 | Out-Host
Stop-App

Start-Debug 'persistence-deep'
Wait-Marker 'SUMMARY' 60 | Out-Host
Stop-App

Start-Debug 'delegation-matrix'
Wait-Marker 'SUMMARY' 1800 | Out-Host
Stop-App

Start-Debug 'approval'
Wait-Marker 'SUMMARY' 30 | Out-Host
Stop-App

Start-Debug 'screen-off-stream'
Start-Sleep 3
Invoke-Adb @('shell','input','keyevent','223') | Out-Null
Start-Sleep 5
$screenOff = Invoke-Adb @('shell','dumpsys','power') | Select-String 'mWakefulness='
Invoke-Adb @('shell','input','keyevent','224') | Out-Null
Start-Sleep 5
$screenOn = Invoke-Adb @('shell','dumpsys','power') | Select-String 'mWakefulness='
Write-Host "screen-off=$screenOff screen-on=$screenOn"
Wait-Marker 'SUMMARY' 240 | Out-Host
Stop-App

Start-Debug 'soak' @{ iterations = 10000 }
Wait-Marker 'SUMMARY' 300 | Out-Host
Stop-App

# Kill/recovery: seed an active model turn, kill process, then relaunch.
Start-Debug 'kill-seed'
Wait-Marker 'kill-seed' 60 | Out-Host
Invoke-Adb @('shell','am','force-stop','com.amaya.intelligence.debug') | Out-Null
Start-Debug 'restore'
Wait-Marker 'SUMMARY' 60 | Out-Host
Stop-App

# Browser process restore: seed persisted browser state, kill process, check restored state.
Invoke-Adb @('shell','am','start','-W','-n',$browser,'--es','mode','restore-seed','--es','url','http://example.com')
Start-Sleep 8
Stop-App
Invoke-Adb @('shell','am','start','-W','-n',$browser,'--es','mode','restore-check')
Start-Sleep 8
Invoke-Adb @('logcat','-d','-s','AmayaBrowserDebug:I','AndroidRuntime:E','*:S')
Stop-App

Write-Host "PASS: debug cycles log=$log"
