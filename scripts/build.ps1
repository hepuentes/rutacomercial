param([string[]]$Tasks = @('assembleDebug', 'testOwnerDebugUnitTest', 'testRepartidorDebugUnitTest', 'lintOwnerDebug', 'lintRepartidorDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME) {
    $studioJdk = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath "$studioJdk\bin\java.exe") { $env:JAVA_HOME = $studioJdk }
    else { throw 'Configure JAVA_HOME con un JDK 17 o 21.' }
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.android'
$sdkPath = Join-Path $projectRoot '.tools\android-sdk'
if (Test-Path -LiteralPath $sdkPath) { $env:ANDROID_HOME = $sdkPath }
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
& (Join-Path $PSScriptRoot 'check-source.ps1')
if (-not (Test-Path -LiteralPath 'local.properties') -and $env:ANDROID_HOME) {
    $sdkProperty = 'sdk.dir=' + $env:ANDROID_HOME.Replace('\','/').Replace(':','\:') + "`n"
    [System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), $sdkProperty, [System.Text.UTF8Encoding]::new($false))
}
& .\gradlew.bat @Tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle terminó con código $LASTEXITCODE" }
