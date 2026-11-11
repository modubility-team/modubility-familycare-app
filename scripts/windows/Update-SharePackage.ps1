param([string]$Archive='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
try {
 if(!$Archive){$Archive=Join-Path ([Environment]::GetFolderPath('Desktop')) 'FamilyCare_수정파일_20261108.zip'}
 $options=Read-LocalOptions $project
 $python=Find-Python $options
 $sdk=Find-AndroidSdk $project $options
 $env:JAVA_HOME=Find-Jdk17 $options
 Ensure-SdkPath $project $sdk
 & (Join-Path $project 'android\gradlew.bat') -p (Join-Path $project 'android') exportDemoApks exportDebugApks exportTabletApk
 if($LASTEXITCODE -ne 0){throw 'APK build failed. The existing ZIP has not been changed.'}
 & $python -X utf8 (Join-Path $project 'scripts\package_share.py') --archive $Archive --include-api-settings
 if($LASTEXITCODE -ne 0){throw 'Package verification failed. Check the error above.'}
 Write-Host 'The same handoff ZIP has been updated and verified.'
 exit 0
} catch {Write-Host ('PACKAGE UPDATE FAILED: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
