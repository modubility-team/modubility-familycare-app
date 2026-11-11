param([Parameter(Mandatory=$true)][string]$Adb,[Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')

function Invoke-ClockCommand([string[]]$Command) {
    $result=Read-NativeOutput $Adb (@('-s',$Serial,'shell')+$Command)
    $value=($result.Lines -join "`n").Trim()
    if($result.ExitCode -ne 0 -or $value -match 'Exception|Unknown command|Permission Denial'){
        throw "Pleos clock command failed: $value"
    }
    return $value
}

# These development commands are for the Pleos emulator only, never a real phone/car.
if($Serial -notmatch '^emulator-\d+$'){throw 'Clock sync is restricted to the Pleos emulator.'}
$fingerprint=Invoke-ClockCommand @('getprop','ro.build.fingerprint')
if($fingerprint -notmatch '(?i)pleos|crp_avd'){throw 'The selected device is not a Pleos emulator.'}

# The bundled virtual device advertises a US time zone. Keep the Korean demo zone
# fixed, while retaining Android's automatic time/date synchronization.
$null=Invoke-ClockCommand @('cmd','time_detector','set_auto_detection_enabled','true')
$null=Invoke-ClockCommand @('cmd','time_zone_detector','set_auto_detection_enabled','false')
$null=Invoke-ClockCommand @('cmd','time_zone_detector','set_time_zone_state_for_tests','--zone_id','Asia/Seoul','--user_should_confirm_id','false')
$zone=Invoke-ClockCommand @('getprop','persist.sys.timezone')
if($zone -ne 'Asia/Seoul'){throw 'Could not apply the Asia/Seoul time zone.'}

$deviceEpoch=Invoke-ClockCommand @('date','+%s')
if($deviceEpoch -notmatch '^\d+$'){throw 'Could not read the emulator clock.'}
$difference=[Math]::Abs([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-[long]$deviceEpoch)
if($difference -gt 5){
    # Pair PC UTC with the emulator's monotonic clock; do not hard-code a date.
    $uptime=Invoke-ClockCommand @('cat','/proc/uptime')
    $seconds=[double]::Parse(($uptime -split '\s+')[0],[Globalization.CultureInfo]::InvariantCulture)
    $elapsed=[string][long][Math]::Floor($seconds*1000)
    $epoch=[string][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $null=Invoke-ClockCommand @('cmd','time_detector','set_time_state_for_tests','--elapsed_realtime',$elapsed,'--unix_epoch_time',$epoch,'--user_should_confirm_time','false')
    $deviceEpoch=Invoke-ClockCommand @('date','+%s')
    if($deviceEpoch -notmatch '^\d+$'){throw 'Could not verify the emulator clock.'}
    $difference=[Math]::Abs([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()-[long]$deviceEpoch)
}
if($difference -gt 5){throw "Emulator and PC clocks differ by $difference seconds. Check the PC date/time settings."}
$automatic=Invoke-ClockCommand @('cmd','time_detector','is_auto_detection_enabled')
if($automatic -ne 'true'){throw 'Automatic date/time synchronization is not enabled.'}
$current=Invoke-ClockCommand @('date')
Write-Host "[OK] Pleos date/time: $current (Asia/Seoul, automatic time, PC difference ${difference}s)"
