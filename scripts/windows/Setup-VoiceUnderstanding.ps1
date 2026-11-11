$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$options=Read-LocalOptions $project
# The voice runtime needs Python 3.11-3.13 (pinned onnxruntime/numpy wheels).
# Look where teammates usually install Python, not only the per-user default folder.
$choices=@((Join-Path $project 'dist\voice-runtime\venv\Scripts\python.exe'))
if($options.PythonExe){$choices+=[string]$options.PythonExe}
$onPath=Get-Command python.exe -ErrorAction SilentlyContinue
if($onPath -and $onPath.Source -notlike '*WindowsApps*'){$choices+=$onPath.Source}
foreach($version in @('312','313','311')){
    $choices+=Join-Path $env:LOCALAPPDATA ('Programs\Python\Python'+$version+'\python.exe')
    if($env:ProgramFiles){$choices+=Join-Path $env:ProgramFiles ('Python'+$version+'\python.exe')}
    $choices+=Join-Path $env:LOCALAPPDATA ('Python\pythoncore-'+$version.Insert(1,'.')+'-64\python.exe')
}
$selected=$null
foreach($candidate in $choices){
    if($candidate -and (Test-Path -LiteralPath $candidate)){
        $check=Read-NativeOutput $candidate @('-c','import sys; print(3 == sys.version_info.major and 11 <= sys.version_info.minor <= 13)')
        if($check.ExitCode -eq 0 -and (($check.Lines -join '').Trim() -eq 'True')){$selected=$candidate;break}
    }
}
if(!$selected){throw 'Python 3.11-3.13 not found. Install Python 3.12 (python.org Windows installer, Install Now) or set PythonExe in familycare.local.json, then run setup again.'}
Write-Host ('Voice runtime Python: '+$selected)
& $selected -X utf8 (Join-Path $project 'scripts\setup_voice_model.py')
if($LASTEXITCODE -ne 0){throw 'Voice understanding setup failed. Check the connection and messages above.'}
Write-Host 'Local voice understanding is ready. No paid AI account or cloud speech upload is used.'
