$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$options=Read-LocalOptions $project
try{
 $listeners=@(Get-NetTCPConnection -State Listen -LocalPort 8770 -ErrorAction SilentlyContinue)
 $ours=$false
 foreach($connection in $listeners){$proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)";if($proc.CommandLine -like ('*'+$project+'*')){$ours=$true}}
 if($ours){
  $url='http://127.0.0.1:8770/'
  $html=(Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 3).Content
  $demoKey=[regex]::Match($html,"const key='([^']+)'").Groups[1].Value
  Invoke-RestMethod ($url+'api/action') -Method Post -Headers @{'X-Demo-Key'=$demoKey} -ContentType 'application/json' -Body '{"action":"shutdown"}' -TimeoutSec 20 | Out-Null
 }
}catch{Write-Host 'The rehearsal controller was unavailable; checking remaining services.'}
try{
 $adb=Join-Path (Find-AndroidSdk $project $options) 'platform-tools\adb.exe'
 $serial=Find-PleosDevice $adb $options
 if($serial){
  & $adb -s $serial forward --remove tcp:8088 2>$null | Out-Null
  & $adb -s $serial emu kill | Out-Null
  for($attempt=0;$attempt -lt 10;$attempt++){
   $listed=((Read-NativeOutput $adb @('devices')).Lines -join ' ')
   if($listed -notmatch [regex]::Escape($serial)){break}
   Start-Sleep -Seconds 2
  }
  if($listed -match [regex]::Escape($serial)){Write-Host 'The emulator is still finishing shutdown. Check its window.'}
 }
}catch{Write-Host ('Emulator check: '+$_.Exception.Message)}
foreach($proc in Get-CimInstance Win32_Process){
 if($proc.Name -match '^python(?:w)?\.exe$' -and $proc.CommandLine -like ('*'+$project+'*') -and $proc.CommandLine -match '(server|live_demo|tablet_bridge|voice_model)\.py'){
  Stop-Process -Id $proc.ProcessId -ErrorAction SilentlyContinue
 }
}
Write-Host 'Project server shutdown finished. Source files and installed apps have been kept.'
