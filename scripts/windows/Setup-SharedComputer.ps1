$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
try {
 $options=Read-LocalOptions $project
 $sdk=Find-AndroidSdk $project $options
 $python=Find-Python $options
 $env:JAVA_HOME=Find-Jdk17 $options
 foreach($relative in @('platforms\android-35\android.jar','build-tools\35.0.0\aapt2.exe','platform-tools\adb.exe','emulator\emulator.exe')){
  if(!(Test-Path -LiteralPath (Join-Path $sdk $relative))){throw ('Install the SDK component first: '+$relative)}
 }
 $avd=Get-PleosAvd $sdk $options
 Write-Host ('SDK and Pleos AVD found: '+$avd)
 Ensure-SdkPath $project $sdk
 & $python -m pip install -r (Join-Path $project 'scripts\requirements-tablet.txt')
 if($LASTEXITCODE -ne 0){throw 'QR dependency installation failed.'}
 & (Join-Path $PSScriptRoot 'Setup-VoiceUnderstanding.ps1')
 if($LASTEXITCODE -ne 0){throw 'Voice model setup failed.'}
 & (Join-Path $PSScriptRoot 'Setup-TmapSdk.ps1')
 Write-Host 'Preparing Android dependencies and building all apps. Internet is needed on first use.'
 & (Join-Path $project 'android\gradlew.bat') -p (Join-Path $project 'android') exportDemoApks exportDebugApks exportTabletApk
 if($LASTEXITCODE -ne 0){throw 'Android build failed. Read the error above; setup is not complete.'}
 Write-Host 'Setup complete. Close this window and run 02_StartDemo.cmd.'
 Write-Host 'Use APKs in team-project\dist\apk from this computer to keep the same signing identity.'
 exit 0
}catch{Write-Host ('SETUP INCOMPLETE: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
