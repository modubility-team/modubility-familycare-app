$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
# Builds apk\<teamName>_<appName>_release.apk with the team key (names come from android\gradle.properties).
try{
    $options=Read-LocalOptions $project
    if(!$env:FAMILYCARE_SIGNING_PROPERTIES -and !(Test-Path -LiteralPath (Join-Path $project 'android\keystore.properties'))){
        throw 'No release signing settings. Run 07_CreateReleaseKey.cmd first, or copy the team keystore.properties into the android folder.'
    }
    $sdk=Find-AndroidSdk $project $options
    $env:JAVA_HOME=Find-Jdk17 $options
    $env:ANDROID_HOME=$sdk
    $env:Path=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:Path
    & (Join-Path $PSScriptRoot 'Setup-TmapSdk.ps1')
    Ensure-SdkPath $project $sdk
    $apkFolder=Join-Path $project 'apk'
    $existing=@(Get-ChildItem -LiteralPath $apkFolder -Filter '*_release.apk' -File -ErrorAction SilentlyContinue)
    if($existing.Count){
        Write-Host 'A submission APK is already in the apk folder:'
        foreach($file in $existing){Write-Host ('  '+$file.Name)}
        if((Read-Host 'Move it to apk\old and build a new one? Type YES') -cne 'YES'){throw 'Cancelled. The existing APK was kept.'}
        $archive=Join-Path $apkFolder 'old'
        New-Item -ItemType Directory -Force -Path $archive | Out-Null
        $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
        foreach($file in $existing){Move-Item -LiteralPath $file.FullName -Destination (Join-Path $archive ($stamp+'_'+$file.Name))}
    }
    Write-Host 'Running unit tests and building the signed release APK. The first build needs the internet.'
    & (Join-Path $project 'android\gradlew.bat') -p (Join-Path $project 'android') ':app:testDebugUnitTest' ':app:packageSubmission'
    if($LASTEXITCODE -ne 0){throw 'Release build failed. Scroll up to the first error message.'}
    $apk=Get-ChildItem -LiteralPath $apkFolder -Filter '*_release.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(!$apk){throw 'The submission APK was not found in the apk folder.'}
    $apksigner=Join-Path $sdk 'build-tools\35.0.0\apksigner.bat'
    if(Test-Path -LiteralPath $apksigner){
        $check=Read-NativeOutput $apksigner @('verify','--print-certs',$apk.FullName)
        if($check.ExitCode -ne 0){throw 'apksigner could not verify the APK signature.'}
        foreach($line in $check.Lines){if($line -match 'Signer #1 certificate (DN|SHA-256 digest)'){Write-Host $line}}
    }else{Write-Host 'apksigner was not found. Check the signature before submitting (docs\RELEASE.md).'}
    $hash=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
    Write-Host ('Submission APK: '+$apk.FullName)
    Write-Host ('Size: {0:N1} MB / SHA-256: {1}' -f ($apk.Length/1MB),$hash)
    Write-Host 'Next: install it on the Pleos emulator to check it, then upload the apk folder to GitHub.'
    exit 0
}catch{Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red;exit 1}
