param([switch]$Lan)
$action=if($Lan){'RelayLan'}else{'Relay'}
& (Join-Path $PSScriptRoot 'windows\FamilyCare.ps1') -Action $action
