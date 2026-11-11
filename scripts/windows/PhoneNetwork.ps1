function Test-PhonePrivateIPv4([string]$Address) {
    $parsed=$null
    if(![Net.IPAddress]::TryParse($Address,[ref]$parsed) -or $parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork){return $false}
    $b=$parsed.GetAddressBytes()
    return ($b[0] -eq 10 -or ($b[0] -eq 192 -and $b[1] -eq 168) -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31))
}

function Get-PhoneNetworkChoices($Configurations,$Addresses) {
    # Prefer connected physical Wi-Fi/Ethernet interfaces with a gateway.
    # Hyper-V, VPN and emulator interfaces must not silently win auto-selection.
    $preferred=@(foreach($config in $Configurations){
        if($config.NetAdapter.HardwareInterface -eq $true -and $config.NetAdapter.Status -eq 'Up' -and $config.IPv4DefaultGateway){
            foreach($ip in $config.IPv4Address){
                if((Test-PhonePrivateIPv4 $ip.IPAddress) -and $ip.AddressState -eq 'Preferred'){
                    [pscustomobject]@{InterfaceAlias=$config.InterfaceAlias;IPAddress=$ip.IPAddress}
                }
            }
        }
    })
    $candidates=if($preferred.Count){$preferred}else{
        @($Addresses | Where-Object {$_.AddressState -eq 'Preferred' -and (Test-PhonePrivateIPv4 $_.IPAddress)})
    }
    @($candidates | Sort-Object IPAddress -Unique)
}
