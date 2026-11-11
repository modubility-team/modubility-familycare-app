param([switch]$NoBrowser)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
. (Join-Path $PSScriptRoot 'PhoneNetwork.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$options=Read-LocalOptions $project
$panelUrl='http://127.0.0.1:8770/'
$startupClient=$null
function Open-DemoPanel {if(!$NoBrowser){Start-Process $panelUrl};Write-Host "Ready: $panelUrl"}
function Invoke-DemoAdb([string[]]$Arguments){
    $result=Read-NativeOutput $adb (@('-s',$serial)+$Arguments)
    if($result.ExitCode -ne 0){throw ('ADB failed: '+($result.Lines -join ' '))}
    return $result.Lines
}
try {
    $existing=$null
    try{$existing=Invoke-RestMethod ($panelUrl+'api/state') -TimeoutSec 3}catch{}
    if($existing.connected -and $existing.sharing){Open-DemoPanel;exit 0}
    if($existing.health){
        $html=(Invoke-WebRequest $panelUrl -UseBasicParsing -TimeoutSec 5).Content
        $key=[regex]::Match($html,"const key='([^']+)'").Groups[1].Value
        Invoke-RestMethod ($panelUrl+'api/action') -Method Post -Headers @{'X-Demo-Key'=$key} -ContentType 'application/json' -Body '{"action":"reconnect"}' -TimeoutSec 120 | Out-Null
        Open-DemoPanel;exit 0
    }
    if(Get-NetTCPConnection -LocalPort 8770 -State Listen -ErrorAction SilentlyContinue){throw 'Port 8770 is already occupied. Close the existing rehearsal session before restarting.'}
    $sdk=Find-AndroidSdk $project $options
    $adb=Join-Path $sdk 'platform-tools\adb.exe'
    $python=Find-Python $options
    & $python -c 'import qrcode, PIL' 2>$null
    if($LASTEXITCODE -ne 0){
        & $python -m pip install -r (Join-Path $project 'scripts\requirements-tablet.txt')
        if($LASTEXITCODE -ne 0){throw 'Tablet QR dependency setup failed.'}
    }
    $serial=Find-PleosDevice $adb $options
    if(!$serial){
        & (Join-Path $PSScriptRoot 'FamilyCare.ps1') -Action Emulator
        if($LASTEXITCODE -ne 0){throw 'Start the Pleos emulator first.'}
        $serial=Find-PleosDevice $adb $options
    }
    if(!$serial){throw 'Pleos emulator not ready.'}
    $androidUser=((Invoke-DemoAdb -Arguments @('shell','am','get-current-user')) -join '').Trim()
    if($androidUser -notmatch '^\d+$'){throw 'Android user not ready.'}
    Invoke-DemoAdb -Arguments @('forward','tcp:8088','tcp:8088') | Out-Null
    Invoke-DemoAdb -Arguments @('reverse','tcp:8765','tcp:8765') | Out-Null
    try{& (Join-Path $PSScriptRoot 'Setup-CloudflareTunnel.ps1')}catch{Write-Host 'External connector setup is unavailable. Local Wi-Fi can still be used.'}
    $runtime=Join-Path $project 'dist\demo'
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    $relay=$null
    try{$relay=Invoke-RestMethod 'http://127.0.0.1:8765/health' -TimeoutSec 3}catch{}
    if(!$relay){
        if(Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue){throw 'Another service uses port 8765.'}
        $networks=@(Get-PhoneNetworkChoices (Get-NetIPConfiguration) (Get-NetIPAddress -AddressFamily IPv4))
        if($networks.Count -ne 1){throw ('Could not choose the phone network automatically ('+$networks.Count+' found). Run 05_DeveloperMenu.cmd and choose 2 (Start Pleos). Then run 05_DeveloperMenu.cmd again and choose 6 to pick the Wi-Fi your phone uses (or 5 to use only the external connection). Keep that window open and run 02_StartDemo.cmd again.')}
        $public='http://'+$networks[0].IPAddress+':8765'
        Write-Host "Starting phone relay on your local network: $public"
        Start-Process -FilePath $python -ArgumentList @('-u',('"'+(Join-Path $project 'companion\server.py')+'"'),'--host','0.0.0.0','--port','8765','--public-url',$public) -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'relay.log') -RedirectStandardError (Join-Path $runtime 'relay-error.log') | Out-Null
    }
    $env:JAVA_HOME=Find-Jdk17 $options
    & (Join-Path $PSScriptRoot 'Setup-TmapSdk.ps1')
    Ensure-SdkPath $project $sdk
    & (Join-Path $project 'android\gradlew.bat') -p (Join-Path $project 'android') --offline exportDemoApks exportTabletApk
    if($LASTEXITCODE -ne 0){throw 'Demo build failed. Check the messages above.'}
    Invoke-DemoAdb -Arguments @('install','-r','--user',$androidUser,(Join-Path $runtime 'app-debug.apk')) | Out-Null
    Invoke-DemoAdb -Arguments @('install','-r','--user',$androidUser,(Join-Path $runtime 'app-debug-androidTest.apk')) | Out-Null
    # The Virtual Console suspends its DDS bridge without an active client.
    # Keep a temporary session alive until the rehearsal controller takes over.
    $startupClient='family-care-startup-'+[guid]::NewGuid().ToString('N')
    $bridgeReady=$false
    for($attempt=0;$attempt -lt 25;$attempt++){
        Invoke-RestMethod ('http://127.0.0.1:8088/browser/session?client='+$startupClient) -Method Post -TimeoutSec 4 | Out-Null
        $vehicleState=Invoke-RestMethod 'http://127.0.0.1:8088/vehicle/state' -TimeoutSec 4
        if($vehicleState.browser.active -and $vehicleState.hvac.emulator_active -and $vehicleState.hvac.publisher_ready.any){$bridgeReady=$true;break}
        Start-Sleep -Seconds 1
    }
    if(!$bridgeReady){throw 'Virtual Console simulation bridge is not ready. Check that the Pleos emulator has finished starting.'}
    # Only the documented Virtual Console simulation endpoints control the vehicle state.
    Invoke-RestMethod 'http://127.0.0.1:8088/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0' -Method Put -TimeoutSec 5 | Out-Null
    Invoke-RestMethod 'http://127.0.0.1:8088/vehicle/gear?gear=Park' -Method Put -TimeoutSec 5 | Out-Null
    $session=Start-Process -FilePath $adb -ArgumentList @('-s',$serial,'shell','am','instrument','--user',$androidUser,'-w','-r','-e','liveDemo','true','-e','class','com.familycare.navigation.LiveDemoSessionTest','com.familycare.navigation.test/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'session.log') -RedirectStandardError (Join-Path $runtime 'session-error.log')
    $controller=Start-Process -FilePath $python -ArgumentList @('-u',('"'+(Join-Path $project 'scripts\live_demo.py')+'"'),'--adb',('"'+$adb+'"'),'--serial',$serial) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'panel.log') -RedirectStandardError (Join-Path $runtime 'panel-error.log')
    @{session=$session.Id;controller=$controller.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'processes.json')
    $ready=$false
    for($attempt=0;$attempt -lt 40;$attempt++){
        try{$status=Invoke-RestMethod ($panelUrl+'api/state') -TimeoutSec 3;if($status.connected){$ready=$true;break}}catch{}
        Start-Sleep -Seconds 1
    }
    if(!$ready){throw "Demo session did not connect. Logs: $runtime"}
    $html=(Invoke-WebRequest $panelUrl -UseBasicParsing).Content
    $key=[regex]::Match($html,"const key='([^']+)'").Groups[1].Value
    Invoke-RestMethod ($panelUrl+'api/action') -Method Post -Headers @{'X-Demo-Key'=$key} -ContentType 'application/json' -Body '{"action":"prepare"}' -TimeoutSec 60 | Out-Null
    Open-DemoPanel
    exit 0
}catch{Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red;exit 1}
finally{
    if($startupClient){
        try{Invoke-RestMethod ('http://127.0.0.1:8088/browser/session?client='+$startupClient+'&closing=1') -Method Post -TimeoutSec 3 | Out-Null}catch{}
    }
}
