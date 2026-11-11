param([string]$Alias='modubility-release')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
# Creates the team-owned release key once and writes android\keystore.properties (ignored by Git).
# The key file is kept outside the project folder so it is never zipped or pushed by accident.
function ConvertTo-PropertiesText([string]$Value){
    # java.util.Properties reads ISO-8859-1, so non-ASCII folder names are written as \uXXXX.
    $builder=New-Object Text.StringBuilder
    foreach($ch in $Value.ToCharArray()){
        if([int]$ch -gt 126){[void]$builder.AppendFormat('\u{0:X4}',[int]$ch)}else{[void]$builder.Append($ch)}
    }
    return $builder.ToString()
}
function ConvertFrom-SecureText([Security.SecureString]$Secure){
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try{return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
    finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
try{
    $options=Read-LocalOptions $project
    $jdk=Find-Jdk17 $options
    $keytool=Join-Path $jdk 'bin\keytool.exe'
    if(!(Test-Path -LiteralPath $keytool)){throw 'keytool.exe was not found in JDK 17.'}
    $settings=Join-Path $project 'android\keystore.properties'
    if(Test-Path -LiteralPath $settings){throw 'android\keystore.properties already exists. Keep using that team key; nothing was changed.'}
    $folder=Join-Path $env:USERPROFILE 'FamilyCareReleaseKey'
    $store=Join-Path $folder 'modubility-familycare-release.jks'
    if(Test-Path -LiteralPath $store){throw ('A release key already exists: '+$store+' . Nothing was changed. Recreate android\keystore.properties for that key instead of making a new one.')}
    Write-Host 'Create the team release signing key for the contest APK. Do this once per team.'
    Write-Host 'If a teammate already made the team key, copy their .jks file and keystore.properties instead.'
    Write-Host 'Password: 8-32 English letters or numbers. Write it down; a lost key or password cannot be recovered.'
    $first=ConvertFrom-SecureText (Read-Host 'New key password' -AsSecureString)
    $second=ConvertFrom-SecureText (Read-Host 'Type the password again' -AsSecureString)
    if($first -cne $second){throw 'The passwords do not match. Nothing was created.'}
    if($first -notmatch '^[A-Za-z0-9]{8,32}$'){throw 'Use 8-32 English letters or numbers only. Nothing was created.'}
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $env:FAMILYCARE_RELEASE_KEY_PASSWORD=$first
    try{
        $result=Read-NativeOutput $keytool @('-genkeypair','-keystore',$store,'-storetype','PKCS12','-alias',$Alias,'-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Modubility, OU=WithME App, O=Modubility, C=KR','-storepass:env','FAMILYCARE_RELEASE_KEY_PASSWORD','-keypass:env','FAMILYCARE_RELEASE_KEY_PASSWORD')
    }finally{Remove-Item Env:FAMILYCARE_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue}
    if($result.ExitCode -ne 0 -or !(Test-Path -LiteralPath $store)){throw 'keytool could not create the key. android\keystore.properties was not written.'}
    $lines=@(
        '# Team release signing for the contest APK. Git ignores this file. Never upload or share it publicly.',
        ('storeFile='+(ConvertTo-PropertiesText ($store.Replace('\','/')))),
        ('storePassword='+$first),
        ('keyAlias='+$Alias),
        ('keyPassword='+$first)
    )
    [IO.File]::WriteAllLines($settings,[string[]]$lines,(New-Object Text.UTF8Encoding($false)))
    Write-Host ('Release key: '+$store)
    Write-Host ('Signing settings: '+$settings+' (ignored by Git)')
    Write-Host 'Back up the key file and the password now (USB drive or a private team vault). Do not upload them anywhere.'
    Write-Host 'Next: run 08_BuildReleaseApk.cmd.'
    exit 0
}catch{Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red;exit 1}
