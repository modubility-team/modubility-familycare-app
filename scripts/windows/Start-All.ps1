param([string]$Launcher,[switch]$NoShortcut)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
. (Join-Path $PSScriptRoot 'PhoneNetwork.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtime=Join-Path $project 'dist\demo'
$panelUrl='http://127.0.0.1:8770/'
# One-click start for the team. It only chains the existing steps: first-time setup when needed,
# the phone network choice, Start-LiveDemo, then the emulator microphone and (without a shared
# Wi-Fi) the external connection. Nothing machine-wide changes except two desktop shortcuts.

function Show-Step([string]$Text){Write-Host '';Write-Host ('== '+$Text) -ForegroundColor Cyan}
function Test-Url([string]$Url){try{Invoke-RestMethod $Url -TimeoutSec 3 | Out-Null;return $true}catch{return $false}}
function Find-Launcher([string]$Name){
    foreach($folder in @((Split-Path -Parent $project),$project)){
        $candidate=Join-Path $folder $Name
        if(Test-Path -LiteralPath $candidate){return $candidate}
    }
    return $null
}
function Set-DesktopShortcut([string]$Name,[string]$Target,[string]$Description){
    if(!$Target -or !(Test-Path -LiteralPath $Target)){return $false}
    $desktop=[Environment]::GetFolderPath('Desktop')
    if(!$desktop -or !(Test-Path -LiteralPath $desktop)){return $false}
    $path=Join-Path $desktop ($Name+'.lnk')
    $shell=New-Object -ComObject WScript.Shell
    if((Test-Path -LiteralPath $path) -and $shell.CreateShortcut($path).TargetPath -eq $Target){return $false}
    $link=$shell.CreateShortcut($path)
    $link.TargetPath=$Target
    $link.WorkingDirectory=Split-Path -Parent $Target
    $link.Description=$Description
    $link.Save()
    return $true
}
function Start-Relay([string]$Bind,[string]$Public){
    # Same command as Start-LiveDemo; Start-LiveDemo then reuses this running server.
    $python=Find-Python $options
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    $arguments=@('-u',('"'+(Join-Path $project 'companion\server.py')+'"'),'--host',$Bind,'--port','8765','--public-url',$Public)
    Start-Process -FilePath $python -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'relay.log') -RedirectStandardError (Join-Path $runtime 'relay-error.log') | Out-Null
    for($i=0;$i -lt 20;$i++){if(Test-Url 'http://127.0.0.1:8765/health'){return};Start-Sleep -Seconds 1}
    throw ('연결 서버가 켜지지 않았어요. 로그: '+(Join-Path $runtime 'relay-error.log'))
}
function Get-PanelKey{
    $html=(Invoke-WebRequest $panelUrl -UseBasicParsing -TimeoutSec 5).Content
    return [regex]::Match($html,"const key='([^']+)'").Groups[1].Value
}

try{
    Write-Host 'WithME 시연 한번에 켜기 · 팀 모두빌리티' -ForegroundColor Green
    $options=Read-LocalOptions $project

    if(!$NoShortcut){
        try{
            $startTarget=if($Launcher -and (Test-Path -LiteralPath $Launcher)){(Resolve-Path -LiteralPath $Launcher).Path}else{Find-Launcher '00_한번에켜기.cmd'}
            $stopTarget=$null
            if($startTarget){$sibling=Join-Path (Split-Path -Parent $startTarget) '03_StopAll.cmd';if(Test-Path -LiteralPath $sibling){$stopTarget=$sibling}}
            if(!$stopTarget){$stopTarget=Find-Launcher '03_StopAll.cmd'}
            $made=@()
            if(Set-DesktopShortcut 'WithME 시연 켜기' $startTarget 'WithME 시연을 한 번에 켭니다'){$made+='켜기'}
            if(Set-DesktopShortcut 'WithME 시연 끄기' $stopTarget 'WithME 시연을 모두 끕니다'){$made+='끄기'}
            if($made.Count){Write-Host ("바탕화면에 'WithME 시연 "+($made -join '/')+"' 버튼을 만들었어요. 다음부터는 그 버튼을 누르면 됩니다.")}
        }catch{Write-Host '바탕화면 버튼은 만들지 못했어요. 이 실행 파일을 그대로 쓰면 됩니다.' -ForegroundColor Yellow}
    }

    Show-Step '1/4 설치 상태 확인'
    $markers=@('android\local.properties','android\local-sdk\tmap\tmap-sdk-3.7.aar','dist\voice-runtime\venv\Scripts\python.exe','dist\apk\02-FamilyCare-navigation-debug.apk')
    $missing=@($markers | Where-Object {!(Test-Path -LiteralPath (Join-Path $project $_))})
    if($missing.Count){
        Write-Host '이 PC에서 처음 켜는 것 같아요. 설치와 전체 빌드를 먼저 합니다. (처음엔 10~30분, 인터넷 필요)'
        & (Join-Path $PSScriptRoot 'Setup-SharedComputer.ps1')
        if($LASTEXITCODE -ne 0){throw '처음 설치가 끝나지 않았어요. 위의 빨간 SETUP INCOMPLETE 문장을 실행 안내 14장에서 찾아 해결한 뒤 이 버튼을 다시 눌러 주세요.'}
        Write-Host '처음 설치가 끝났어요. 창을 닫지 말고 기다리세요. 이어서 시연을 켭니다.' -ForegroundColor Green
    }else{Write-Host '설치 확인 완료'}
    if(!(Test-Path -LiteralPath (Join-Path $project 'tmap.local.json'))){
        Write-Host '주의: tmap.local.json이 없어 지도·검색·경로가 동작하지 않아요. (실행 안내 5장)' -ForegroundColor Yellow
    }

    Show-Step '2/4 휴대폰 연결 방법 확인'
    $externalOnly=$false;$phoneUrl=''
    if((Test-Url ($panelUrl+'api/state')) -or (Test-Url 'http://127.0.0.1:8765/health')){
        Write-Host '이미 켜져 있는 연결 서버를 그대로 씁니다.'
    }else{
        if(Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue){throw '다른 프로그램이 8765 포트를 쓰고 있어요. 끄기 버튼(03_StopAll.cmd)을 누른 뒤 다시 켜 주세요.'}
        $networks=@(Get-PhoneNetworkChoices (Get-NetIPConfiguration) (Get-NetIPAddress -AddressFamily IPv4))
        $choice=1
        if($networks.Count -gt 1){
            Write-Host '노트북에 연결된 네트워크가 여러 개예요. 휴대폰이 연결된 Wi-Fi의 번호를 입력하세요.'
            for($i=0;$i -lt $networks.Count;$i++){Write-Host ('{0}. {1} / {2}' -f ($i+1),$networks[$i].InterfaceAlias,$networks[$i].IPAddress)}
            Write-Host '0. 휴대폰은 다른 Wi-Fi나 LTE를 씀 (외부 연결)'
            $answer=Read-Host '번호'
            if(![int]::TryParse($answer,[ref]$choice) -or $choice -lt 0 -or $choice -gt $networks.Count){throw '목록에 있는 번호를 입력해 주세요. 버튼을 다시 누르면 처음부터 합니다.'}
        }
        if($networks.Count -eq 0 -or $choice -eq 0){
            if($networks.Count -eq 0){Write-Host '휴대폰과 같이 쓸 Wi-Fi를 찾지 못했어요. 외부 연결(인터넷)로 휴대폰을 연결합니다.' -ForegroundColor Yellow}
            $externalOnly=$true
            Start-Relay '127.0.0.1' 'http://127.0.0.1:8765'
        }elseif($networks.Count -eq 1){
            $phoneUrl='http://'+$networks[0].IPAddress+':8765'
            Write-Host ('휴대폰 연결용 Wi-Fi: '+$networks[0].InterfaceAlias+' / '+$networks[0].IPAddress+' (휴대폰도 같은 Wi-Fi에 연결하세요)')
        }else{
            $phoneUrl='http://'+$networks[$choice-1].IPAddress+':8765'
            Start-Relay '0.0.0.0' $phoneUrl
            Write-Host ('연결 서버를 켰어요: '+$phoneUrl)
        }
    }

    Show-Step '3/4 차량 에뮬레이터·차량 앱·시연 보조 화면 켜기 (몇 분 걸려요)'
    & (Join-Path $PSScriptRoot 'Start-LiveDemo.ps1')
    if($LASTEXITCODE -ne 0){
        Write-Host ('로그 폴더: '+$runtime)
        throw '시연을 켜지 못했어요. 에뮬레이터가 아직 켜지는 중이었다면 차량 홈 화면이 뜬 뒤 이 버튼을 한 번 더 누르세요. 그래도 안 되면 위의 빨간 ERROR 문장을 실행 안내 14장에서 찾아보세요.'
    }

    Show-Step '4/4 마이크와 휴대폰 연결 마무리'
    try{
        $adb=Join-Path (Find-AndroidSdk $project $options) 'platform-tools\adb.exe'
        $serial=Find-PleosDevice $adb $options
        if(!$serial){throw 'no emulator'}
        # Emulator console command; the host microphone resets to off whenever the emulator restarts.
        $mic=Read-NativeOutput $adb @('-s',$serial,'emu','avd','hostmicon')
        if(($mic.Lines -join ' ') -notmatch '\bOK\b'){throw 'microphone'}
        Write-Host '노트북 마이크를 에뮬레이터에 연결했어요. (에뮬레이터를 다시 켜면 꺼지니 이 버튼으로 켜세요)'
    }catch{Write-Host "마이크: 에뮬레이터 옆 '...'(Extended controls) → Microphone → 'Virtual microphone uses host audio input'을 직접 켜 주세요." -ForegroundColor Yellow}
    if(!$phoneUrl -and !$externalOnly){
        try{
            $owner=[string](Invoke-RestMethod ($panelUrl+'api/state') -TimeoutSec 3).links.owner
            if($owner){$uri=[Uri]$owner;if($uri.Host -ne '127.0.0.1'){$phoneUrl=$uri.Scheme+'://'+$uri.Host+':'+$uri.Port}}
        }catch{}
    }
    if($externalOnly){
        try{
            $key=Get-PanelKey
            Invoke-RestMethod ($panelUrl+'api/tunnel') -Method Post -Headers @{'X-Demo-Key'=$key} -ContentType 'application/json' -Body '{"action":"start"}' -TimeoutSec 30 | Out-Null
            $ready=$false
            for($i=0;$i -lt 45;$i++){
                try{$state=Invoke-RestMethod ($panelUrl+'api/state') -TimeoutSec 3;if($state.externalConnection.active){$ready=$true;break}}catch{}
                Start-Sleep -Seconds 2
            }
            if($ready){Write-Host '외부 연결 준비 완료.'}
            else{Write-Host "외부 연결이 아직 준비 중이에요. 시연 보조 화면의 '다른 Wi-Fi 연결' 상태를 확인하세요." -ForegroundColor Yellow}
        }catch{Write-Host ("외부 연결을 자동으로 켜지 못했어요. 시연 보조 화면의 '다른 Wi-Fi 연결' → '외부 연결 시작'을 눌러 주세요. ("+$_.Exception.Message+')') -ForegroundColor Yellow}
    }

    Write-Host ''
    Write-Host '준비 완료!' -ForegroundColor Green
    Write-Host ('- 시연 보조 화면: '+$panelUrl+' (브라우저에 열렸어요)')
    if($externalOnly){
        Write-Host "- 휴대폰: 시연 보조 화면 '다른 Wi-Fi 연결'의 외부 QR을 스캔하세요."
    }else{
        Write-Host '- 휴대폰: 노트북과 같은 Wi-Fi에서 시연 보조 화면의 QR을 스캔하세요.'
        if($phoneUrl){Write-Host ('- 휴대폰 앱 설치: '+$phoneUrl+'/install/owner.apk (내 차), '+$phoneUrl+'/install/family.apk (WithME 가족)')}
        Write-Host "- 같은 Wi-Fi인데 휴대폰이 안 붙으면 시연 보조 화면에서 '다른 Wi-Fi 연결' → '외부 연결 시작'"
    }
    Write-Host "- 끝낼 때: 바탕화면의 'WithME 시연 끄기' (또는 03_StopAll.cmd)"
    exit 0
}catch{Write-Host ('오류: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
