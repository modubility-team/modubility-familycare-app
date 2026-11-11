# Shared, read-only discovery. No machine-wide environment changes.
$ErrorActionPreference = 'Stop'
function Read-NativeOutput([string]$Executable,[string[]]$Arguments) {
    # Windows PowerShell 5.1 treats ordinary native stderr (e.g. ADB startup) as errors.
    $previous=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';$lines=@(& $Executable @Arguments 2>$null);$code=$LASTEXITCODE}
    finally{$ErrorActionPreference=$previous}
    return [pscustomobject]@{Lines=$lines;ExitCode=$code}
}
function Read-LocalOptions([string]$Project) {
    $file=Join-Path $Project 'familycare.local.json'
    if(Test-Path -LiteralPath $file){return Get-Content -Raw -LiteralPath $file | ConvertFrom-Json}
    return [pscustomobject]@{}
}
function Find-AndroidSdk([string]$Project,$Options) {
    $candidates=@($Options.AndroidSdk,$env:ANDROID_HOME,$env:ANDROID_SDK_ROOT)
    $local=Join-Path $Project 'android\local.properties'
    if(Test-Path -LiteralPath $local){
        $line=Get-Content -LiteralPath $local | Where-Object {$_ -match '^sdk\.dir\s*='} | Select-Object -First 1
        if($line){
            $value=($line -replace '^sdk\.dir\s*=','').Replace('\:',':').Replace('\\','\')
            $value=[regex]::Replace($value,'\\u([0-9a-fA-F]{4})',{param($m) [char][Convert]::ToInt32($m.Groups[1].Value,16)})
            $candidates+=$value
        }
    }
    $candidates+=@((Join-Path $env:LOCALAPPDATA 'Android\Sdk'),'C:\Android\Sdk')
    foreach($path in $candidates){if($path -and (Test-Path -LiteralPath (Join-Path $path 'platform-tools\adb.exe'))){return (Resolve-Path -LiteralPath $path).Path}}
    throw 'Android SDK not found. Install SDK Platform-Tools, or set AndroidSdk in familycare.local.json.'
}
function Find-Jdk17($Options) {
    $candidates=@($Options.JdkHome,$env:JAVA_HOME)
    $java=Get-Command java.exe -ErrorAction SilentlyContinue
    if($java){$candidates+=Split-Path -Parent (Split-Path -Parent $java.Source)}
    foreach($base in @((Join-Path $env:ProgramFiles 'Eclipse Adoptium'),(Join-Path $env:USERPROFILE '.jdks'))){
        if(Test-Path -LiteralPath $base){$candidates+=@(Get-ChildItem -LiteralPath $base -Directory | Select-Object -ExpandProperty FullName)}
    }
    foreach($path in $candidates){
        if($path -and (Test-Path -LiteralPath (Join-Path $path 'bin\javac.exe')) -and (Test-Path -LiteralPath (Join-Path $path 'release'))){
            if((Get-Content -LiteralPath (Join-Path $path 'release')) -match '^JAVA_VERSION="17(?:\.|\")'){return (Resolve-Path -LiteralPath $path).Path}
        }
    }
    throw 'JDK 17 not found. Install Temurin JDK 17 or set JdkHome in familycare.local.json.'
}
function Find-Python($Options) {
    $candidates=@($Options.PythonExe)
    $python=Get-Command python.exe -ErrorAction SilentlyContinue
    if($python -and $python.Source -notlike '*WindowsApps*'){$candidates+=$python.Source}
    $py=Get-Command py.exe -ErrorAction SilentlyContinue
    if($py){try{$detected=& $py.Source -3 -c 'import sys; print(sys.executable)' 2>$null;if($LASTEXITCODE -eq 0){$candidates+=($detected | Select-Object -Last 1)}}catch{}}
    foreach($base in @((Join-Path $env:LOCALAPPDATA 'Programs\Python'),$env:ProgramFiles)){
        if(Test-Path -LiteralPath $base){$candidates+=@(Get-ChildItem -LiteralPath $base -Directory -Filter 'Python*' | ForEach-Object {Join-Path $_.FullName 'python.exe'})}
    }
    foreach($path in $candidates){
        if($path -and (Test-Path -LiteralPath $path)){
            try{& $path -c 'import sys; sys.exit(0 if sys.version_info.major == 3 and sys.version_info >= (3,11) else 1)' 2>$null;if($LASTEXITCODE -eq 0){return (Resolve-Path -LiteralPath $path).Path}}catch{}
        }
    }
    throw 'Python 3.11+ not found. Install Python or set PythonExe in familycare.local.json.'
}
function Find-Git {
    $command=Get-Command git.exe -ErrorAction SilentlyContinue
    if($command){return $command.Source}
    $path=Join-Path $env:ProgramFiles 'Git\cmd\git.exe'
    if(Test-Path -LiteralPath $path){return $path}
    throw 'Git for Windows not found. Install Git and open this launcher again.'
}
function Find-Studio($Options) {
    $candidates=@($Options.StudioExe,(Join-Path $env:ProgramFiles 'Android\Android Studio\bin\studio64.exe'))
    $command=Get-Command studio64.exe -ErrorAction SilentlyContinue
    if($command){$candidates+=$command.Source}
    foreach($path in $candidates){if($path -and (Test-Path -LiteralPath $path)){return $path}}
    throw 'Android Studio not found. Open the android folder manually or set StudioExe in familycare.local.json.'
}
function Get-PleosAvd([string]$Sdk,$Options) {
    $emulator=Join-Path $Sdk 'emulator\emulator.exe'
    if(!(Test-Path -LiteralPath $emulator)){throw 'Install Android Emulator in SDK Manager.'}
    $result=Read-NativeOutput $emulator @('-list-avds')
    $all=@($result.Lines | Where-Object {$_ -match '\S'})
    if($result.ExitCode -ne 0){throw 'Could not list Android virtual devices.'}
    if($Options.AvdName){if($Options.AvdName -notin $all){throw 'Configured AvdName was not found.'};return $Options.AvdName}
    $matchingAvds=@($all | Where-Object {$_ -match '(?i)pleos'})
    if($matchingAvds.Count -eq 1){return $matchingAvds[0]}
    throw 'Create one Pleos 2.1 AVD named FamilyCare_Pleos, or set AvdName in familycare.local.json.'
}
function Find-PleosDevice([string]$Adb,$Options) {
    $result=Read-NativeOutput $Adb @('devices')
    if($result.ExitCode -ne 0){throw 'ADB could not list devices. Check the SDK Platform-Tools installation.'}
    $lines=$result.Lines
    $devices=@($lines | Where-Object {$_ -match '^(\S+)\s+device$'} | ForEach-Object {($_ -split '\s+')[0]})
    if($Options.DeviceSerial){$devices=@($devices | Where-Object {$_ -eq $Options.DeviceSerial})}
    $pleosDevices=@()
    foreach($serial in $devices){
        if($serial -notlike 'emulator-*'){continue}
        $fingerprint=(Read-NativeOutput $Adb @('-s',$serial,'shell','getprop','ro.build.fingerprint')).Lines -join ''
        if($fingerprint -match '(?i)pleos|crp_avd'){$pleosDevices+=$serial}
    }
    if($pleosDevices.Count -eq 1){return $pleosDevices[0]}
    if($pleosDevices.Count -gt 1){throw 'Multiple Pleos emulators are running. Set DeviceSerial in familycare.local.json.'}
    return $null
}
function Ensure-SdkPath([string]$Project,[string]$Sdk) {
    $file=Join-Path $Project 'android\local.properties'
    if(Test-Path -LiteralPath $file){
        $existing=Get-Content -LiteralPath $file | Where-Object {$_ -match '^sdk\.dir\s*='}
        if($existing){return}
        throw 'local.properties exists without sdk.dir. Set the SDK path in Android Studio before building.'
    }
    $value=$Sdk.Replace('\','/')
    $value=[regex]::Replace($value,'[^\x00-\x7F]',{param($m) '\u{0:x4}' -f [int][char]$m.Value})
    [IO.File]::WriteAllText($file,"sdk.dir=$value`n",[Text.Encoding]::ASCII)
    Write-Host 'Created local android/local.properties (ignored by Git).'
}
