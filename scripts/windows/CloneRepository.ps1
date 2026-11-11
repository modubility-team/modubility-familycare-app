param([string]$Destination,[string]$Url)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
try{
    $git=Find-Git
    $url=$Url
    if(!$url){$url=Read-Host 'Repository URL (https://github.com/<owner>/modubility-familycare-app.git)'}
    if(!$url){throw 'No repository URL was given.'}
    Write-Host 'For a private repository, accept the invitation with your own GitHub account first.'
    Write-Host 'If Git opens a sign-in page, use your own account. Do not share passwords or tokens.'
    if(!$Destination){
        $default=Join-Path $env:USERPROFILE 'source\modubility-familycare-app'
        $Destination=Read-Host "New project folder (Enter = $default)"
        if(!$Destination){$Destination=$default}
    }
    $Destination=[IO.Path]::GetFullPath($Destination)
    if(Test-Path -LiteralPath $Destination){throw 'Destination already exists. Choose a new folder; existing files were not changed.'}
    $parent=Split-Path -Parent $Destination
    if(!(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent -Force | Out-Null}
    & $git clone -- $url $Destination
    if($LASTEXITCODE -ne 0){throw 'Clone failed. Check GitHub sign-in, repository invitation and network. No existing files were removed.'}
    Write-Host "Downloaded: $Destination"
    Write-Host "Next: open $Destination\FamilyCare.cmd and choose 1 (Check setup)."
    exit 0
}catch{Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red;exit 1}
