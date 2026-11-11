$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PhoneNetwork.ps1')
function Assert-Network($Condition,[string]$Message){if(!$Condition){throw $Message}}
function Network-Fixture($Name,$Ip,$Hardware=$true){
    [pscustomobject]@{InterfaceAlias=$Name;NetAdapter=[pscustomobject]@{HardwareInterface=$Hardware;Status='Up'};IPv4DefaultGateway='gateway';IPv4Address=@([pscustomobject]@{IPAddress=$Ip;AddressState='Preferred'})}
}
$wifi=Network-Fixture 'Wi-Fi' '192.168.1.4'
$vpn=Network-Fixture 'VPN' '10.0.0.2' $false
$wired=Network-Fixture 'Ethernet' '172.20.0.5'
$one=@(Get-PhoneNetworkChoices @($vpn,$wifi) @())
Assert-Network ($one.Count -eq 1 -and $one[0].IPAddress -eq '192.168.1.4') 'VPN must not replace physical Wi-Fi.'
$two=@(Get-PhoneNetworkChoices @($wifi,$wired) @())
Assert-Network ($two.Count -eq 2) 'Multiple physical networks must require selection.'
$none=@(Get-PhoneNetworkChoices @((Network-Fixture 'Public' '8.8.8.8')) @([pscustomobject]@{IPAddress='127.0.0.1';AddressState='Preferred'},[pscustomobject]@{IPAddress='169.254.3.4';AddressState='Preferred'}))
Assert-Network ($none.Count -eq 0) 'Public, loopback and link-local addresses must be excluded.'
$fallback=@(Get-PhoneNetworkChoices @() @([pscustomobject]@{IPAddress='10.1.1.1';AddressState='Preferred'},[pscustomobject]@{IPAddress='10.1.1.1';AddressState='Preferred'}))
Assert-Network ($fallback.Count -eq 1) 'Fallback candidates must be deduplicated.'
foreach($ip in @('172.16.0.1','172.31.255.254','10.0.0.1','192.168.0.1')){Assert-Network (Test-PhonePrivateIPv4 $ip) "Expected private address: $ip"}
foreach($ip in @('172.15.0.1','172.32.0.1','192.169.0.1','::1','bad')){Assert-Network (!(Test-PhonePrivateIPv4 $ip)) "Unexpected private address: $ip"}
Write-Host 'Phone network selection: 5 scenario groups passed.'
