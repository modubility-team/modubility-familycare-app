param([switch]$Force)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$sdkFolder = Join-Path $projectRoot 'android\local-sdk\tmap'
$names = @('tmap-sdk-3.7.aar', 'vsm-tmap-sdk-v2-eaa-2.0.14.aar')
if (-not $Force -and ($names | Where-Object { -not (Test-Path -LiteralPath (Join-Path $sdkFolder $_)) }).Count -eq 0) {
    Write-Host 'TMAP SDK 3.7 is already installed.'
    return
}
# Official distribution only. No application key is needed to download the SDK.
$url = 'https://tmapapi.tmapmobility.com/presigned-url/%5BAndroid%5DTMapVSMSDK_3.7.zip'
$expected = '513A1C170487306D62BB7DBB48604ECB73E61BE3156EDE1A892188EA49D9E008'
$archive = Join-Path ([System.IO.Path]::GetTempPath()) ('familycare-tmap-' + [guid]::NewGuid().ToString('N') + '.zip')
try {
    $response = Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing -PassThru
    # The official endpoint may return a short-lived download URL instead of the ZIP.
    if ([string]$response.Headers['Content-Type'] -like 'application/json*') {
        $metadata = Get-Content -Raw -Encoding UTF8 -LiteralPath $archive | ConvertFrom-Json
        $download = [Uri]$metadata.url
        if ($download.Scheme -ne 'https' -or
            $download.Host -ne 's3-top-guide-prd.s3.ap-northeast-2.amazonaws.com' -or
            [Uri]::UnescapeDataString($download.AbsolutePath) -ne '/download/[Android]TMapVSMSDK_3.7.zip') {
            throw 'The official SDK download location changed. Review it before installing.'
        }
        Invoke-WebRequest -Uri $download.AbsoluteUri -OutFile $archive -UseBasicParsing
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) {
        throw 'The official SDK archive changed. Review the new version before installing.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
    try {
        New-Item -ItemType Directory -Path $sdkFolder -Force | Out-Null
        foreach ($name in $names) {
            $entry = $zip.GetEntry('[Android]TMapVSMSDK_3.7/lib/' + $name)
            if ($null -eq $entry) { throw "Missing official SDK library: $name" }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $sdkFolder $name), $true)
        }
    } finally { $zip.Dispose() }
    Write-Host 'TMAP SDK 3.7 installed. This does not activate an API product or paid subscription.'
} finally {
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
}
