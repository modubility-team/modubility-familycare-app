param([ValidateSet('Menu','Check','Configure','Emulator','Car','Apks','Relay','RelayLan','Studio','Test','Stop')][string]$Action='Menu')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
. (Join-Path $PSScriptRoot 'PhoneNetwork.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$options=Read-LocalOptions $project
function Invoke-Build([string[]]$Tasks){
    & (Join-Path $PSScriptRoot 'Setup-TmapSdk.ps1')
    $sdk=Find-AndroidSdk $project $options
    $env:JAVA_HOME=Find-Jdk17 $options
    $env:ANDROID_HOME=$sdk
    Ensure-SdkPath $project $sdk
    & (Join-Path $project 'android\gradlew.bat') -p (Join-Path $project 'android') @Tasks
    if($LASTEXITCODE -ne 0){throw 'Gradle failed. Keep this window open and review the error above.'}
}
try{
    if($Action -eq 'Menu'){
        Write-Host 'WithME - Windows team launcher'
        Write-Host '1 Check setup    2 Start Pleos    3 Build + run vehicle app'
        Write-Host '4 Build 3 APKs   5 Local server   6 Phone Wi-Fi server'
        Write-Host '7 Android Studio 8 Tests         9 Stop Pleos    0 Exit'
        $choice=Read-Host 'Number'
        $actions=@{'1'='Check';'2'='Emulator';'3'='Car';'4'='Apks';'5'='Relay';'6'='RelayLan';'7'='Studio';'8'='Test';'9'='Stop'}
        if($choice -eq '0'){exit 0}
        if(!$actions.ContainsKey($choice)){throw 'Choose a number from 0 to 9.'}
        $Action=$actions[$choice]
    }
    switch($Action){
        'Check' {
            $missing=0
            foreach($name in @('Git','JDK 17','Android SDK','Python','Android Studio','Pleos AVD')){
                try{
                    $path=switch($name){
                        'Git'{Find-Git};'JDK 17'{Find-Jdk17 $options};'Android SDK'{Find-AndroidSdk $project $options}
                        'Python'{Find-Python $options};'Android Studio'{Find-Studio $options};'Pleos AVD'{Get-PleosAvd (Find-AndroidSdk $project $options) $options}
                    }
                    Write-Host "[OK] $name : $path"
                }catch{$missing++;Write-Host "[MISSING] $name : $($_.Exception.Message)"}
            }
            try{
                $sdk=Find-AndroidSdk $project $options
                foreach($item in @('platforms\android-35\android.jar','build-tools\35.0.0\aapt2.exe','platform-tools\adb.exe','emulator\emulator.exe')){
                    if(Test-Path -LiteralPath (Join-Path $sdk $item)){Write-Host "[OK] $item"}else{$missing++;Write-Host "[MISSING] $item"}
                }
                & (Join-Path $sdk 'emulator\emulator.exe') -accel-check
                if($LASTEXITCODE -ne 0){$missing++;Write-Host '[MISSING] Emulator acceleration: see the setup guide.'}
            }catch{Write-Host '[INFO] SDK checks skipped.'}
            Write-Host 'Instructions: docs/WINDOWS-TEAM-SETUP.txt'
            if($missing -gt 0){exit 1}
        }
        'Configure' {Ensure-SdkPath $project (Find-AndroidSdk $project $options)}
        'Studio' {Start-Process -FilePath (Find-Studio $options) -ArgumentList ('"'+(Join-Path $project 'android')+'"')}
        'Emulator' {
            $sdk=Find-AndroidSdk $project $options;$adb=Join-Path $sdk 'platform-tools\adb.exe'
            $serial=Find-PleosDevice $adb $options
            if(!$serial){
                $avd=Get-PleosAvd $sdk $options
                Write-Host "Starting $avd with cold boot. Wait for the car home screen."
                # The emulator is the visible interactive tool explicitly selected by the user.
                Start-Process -FilePath (Join-Path $sdk 'emulator\emulator.exe') -ArgumentList @('-avd',('"'+$avd+'"'),'-no-snapshot','-gpu','host','-timezone','Asia/Seoul')
            }
            $ready=$false
            for($i=0;$i -lt 90;$i++){
                $serial=Find-PleosDevice $adb $options
                if($serial){
                    $boot=(Read-NativeOutput $adb @('-s',$serial,'shell','getprop','sys.boot_completed')).Lines -join ''
                    $activity=(Read-NativeOutput $adb @('-s',$serial,'shell','service','check','activity')).Lines -join ''
                    if($boot.Trim() -eq '1' -and $activity -match ': found'){$ready=$true;break}
                }
                Start-Sleep -Seconds 2
            }
            if(!$ready){throw 'Startup timed out. Check the emulator window and run this action again.'}
            & (Join-Path $PSScriptRoot 'Sync-PleosClock.ps1') -Adb $adb -Serial $serial
            & $adb -s $serial forward tcp:8088 tcp:8088
            if($LASTEXITCODE -ne 0){throw 'Virtual Console port forwarding failed.'}
            Write-Host 'Ready. Open http://127.0.0.1:8088/ for the Virtual Console.'
        }
        'Car' {
            $sdk=Find-AndroidSdk $project $options;$adb=Join-Path $sdk 'platform-tools\adb.exe'
            $serial=Find-PleosDevice $adb $options
            if(!$serial){throw 'Start the Pleos emulator first (menu 2).'}
            Invoke-Build -Tasks @('exportDebugApks')
            & (Join-Path $PSScriptRoot 'Sync-PleosClock.ps1') -Adb $adb -Serial $serial
            $user=((& $adb -s $serial shell am get-current-user) -join '').Trim()
            if($user -notmatch '^\d+$'){throw 'The emulator is not ready. Wait for its home screen.'}
            & $adb -s $serial install -r --user $user (Join-Path $project 'dist\apk\01-FamilyCare-vehicle-debug.apk')
            if($LASTEXITCODE -ne 0){throw 'APK installation failed. No app data was removed. See the troubleshooting guide.'}
            & $adb -s $serial shell am start --user $user -n com.familycare.navigation/.MainActivity
            if($LASTEXITCODE -ne 0){throw 'Could not start the vehicle app.'}
        }
        'Apks' {Invoke-Build -Tasks @('exportDebugApks');Write-Host "APKs: $project\dist\apk"}
        {$_ -in 'Relay','RelayLan'} {
            $sdk=Find-AndroidSdk $project $options;$adb=Join-Path $sdk 'platform-tools\adb.exe'
            $serial=Find-PleosDevice $adb $options
            if(!$serial){throw 'Start the Pleos emulator first (menu 2).'}
            $python=Find-Python $options
            & $adb -s $serial reverse tcp:8765 tcp:8765
            if($LASTEXITCODE -ne 0){throw 'Could not connect the emulator to the server.'}
            if(Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue){throw 'Port 8765 is already in use. Stop your existing server with Ctrl+C before restarting.'}
            $bind='127.0.0.1';$public='http://127.0.0.1:8765'
            if($Action -eq 'RelayLan'){
                Write-Host 'Use the same trusted Wi-Fi for this PC and your phone.'
                $addresses=@(Get-PhoneNetworkChoices (Get-NetIPConfiguration) (Get-NetIPAddress -AddressFamily IPv4))
                if(!$addresses.Count){throw 'No private network address found. Connect this PC and your phone to the same trusted Wi-Fi and try again.'}
                if($addresses.Count -eq 1){
                    $selected=$addresses[0].IPAddress
                    Write-Host ('Selected network: '+$addresses[0].InterfaceAlias+' / '+$selected)
                }else{
                    for($index=0;$index -lt $addresses.Count;$index++){
                        Write-Host (('{0}. {1} / {2}' -f ($index+1),$addresses[$index].InterfaceAlias,$addresses[$index].IPAddress))
                    }
                    $number=0
                    $answer=Read-Host 'Choose the number for the network shared with your phone'
                    if(![int]::TryParse($answer,[ref]$number) -or $number -lt 1 -or $number -gt $addresses.Count){throw 'Choose a network number from the list.'}
                    $selected=$addresses[$number-1].IPAddress
                }
                $bind='0.0.0.0';$public='http://'+$selected+':8765'
                Write-Host 'No firewall rules are changed. If prompted by Windows, allow only your trusted private network.'
            }
            Write-Host "Server: $public (keep this window open; Ctrl+C stops it)."
            & $python -u (Join-Path $project 'companion\server.py') --host $bind --port 8765 --public-url $public
            if($LASTEXITCODE -ne 0){throw 'Server stopped or could not start.'}
        }
        'Test' {
            & (Join-Path $PSScriptRoot 'Test-PhoneNetwork.ps1')
            Invoke-Build -Tasks @(':app:testDebugUnitTest',':phone:testNavigationDebugUnitTest',':phone:testCareDebugUnitTest')
            $python=Find-Python $options
            Push-Location (Join-Path $project 'companion')
            try{& $python -m unittest -v test_server;if($LASTEXITCODE -ne 0){throw 'Server tests failed.'}}finally{Pop-Location}
        }
        'Stop' {
            $adb=Join-Path (Find-AndroidSdk $project $options) 'platform-tools\adb.exe'
            $serial=Find-PleosDevice $adb $options
            if(!$serial){Write-Host 'No Pleos emulator is running.';break}
            Write-Host 'End trips, timers and sharing in the vehicle app before closing it.'
            if((Read-Host "Close $serial now? Type YES") -eq 'YES'){& $adb -s $serial emu kill}
        }
    }
    exit 0
}catch{Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red;exit 1}
