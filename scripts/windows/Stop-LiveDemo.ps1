$ErrorActionPreference='Stop'
try {
    $url='http://127.0.0.1:8770/'
    $html=(Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 3).Content
    $key=[regex]::Match($html,"const key='([^']+)'").Groups[1].Value
    Invoke-RestMethod ($url+'api/action') -Method Post -Headers @{'X-Demo-Key'=$key} -ContentType 'application/json' -Body '{"action":"shutdown"}' -TimeoutSec 30 | Out-Null
    Write-Host 'Rehearsal stopped. The emulator and phone relay remain available.'
    exit 0
}catch{Write-Host "Could not stop the rehearsal: $($_.Exception.Message)";exit 1}
