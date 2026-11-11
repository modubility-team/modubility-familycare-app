param(
    [ValidateRange(-85,85)][double]$Latitude=37.48544722,
    [ValidateRange(-180,180)][double]$Longitude=127.03636666
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$options=Read-LocalOptions $project
$sdk=Find-AndroidSdk $project $options
$adb=Join-Path $sdk 'platform-tools\adb.exe'
$serial=Find-PleosDevice $adb $options
if(!$serial){throw 'Start the Pleos emulator first.'}
if($serial -notlike 'emulator-*'){throw 'GPS simulation is restricted to an emulator.'}
$culture=[Globalization.CultureInfo]::InvariantCulture
& $adb -s $serial emu geo fix $Longitude.ToString('F8',$culture) $Latitude.ToString('F8',$culture)
if($LASTEXITCODE -ne 0){throw 'The emulator rejected the GPS position.'}
Write-Host 'Simulated GPS position applied. This is NOT the laptop/phone real location.'
Write-Host "Latitude: $Latitude / Longitude: $Longitude"
Write-Host 'Open WithME (blue talking-car icon), select a destination on the map, then find route and start guidance.'
