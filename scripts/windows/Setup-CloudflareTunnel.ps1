$ErrorActionPreference='Stop'
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$folder=Join-Path $project 'dist\tools'
$file=Join-Path $folder 'cloudflared.exe'
$expected='2493DBC168923BB3E59E5E7716F71DECED92992EEA9ACD6E9D74F84AC7751343'
if((Test-Path -LiteralPath $file) -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -eq $expected){Write-Host 'Cloudflare connector ready: 2026.10.7';return}
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$download=Join-Path $folder ('cloudflared-'+[guid]::NewGuid().ToString('N')+'.download')
try{
 Invoke-WebRequest 'https://github.com/cloudflare/cloudflared/releases/download/2026.10.7/cloudflared-windows-amd64.exe' -UseBasicParsing -OutFile $download
 if((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $expected){throw 'Cloudflare download verification failed.'}
 Move-Item -LiteralPath $download -Destination $file -Force
 Write-Host 'Cloudflare connector installed locally: 2026.10.7'
}finally{if(Test-Path -LiteralPath $download){Remove-Item -LiteralPath $download -Force}}
