param(
    [string]$JavaHome = "",
    [string]$SdkPath = "",
    [switch]$PrepareOnly
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    foreach ($candidate in @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )) {
        if (Test-Path "$candidate\bin\java.exe") { $JavaHome = $candidate; break }
    }
}
if (-not (Test-Path "$JavaHome\bin\java.exe")) {
    throw "Install Android Studio or provide -JavaHome pointing to JDK 17 or 21."
}
$env:JAVA_HOME = $JavaHome
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$SdkPath\platforms\android-35\android.jar")) {
    throw "Install Android SDK Platform 35 in Android Studio, or specify -SdkPath."
}
$env:ANDROID_HOME = $SdkPath

$cache = Join-Path $PSScriptRoot ".build-tools"
$gradlePath = Join-Path $cache "gradle-8.9\bin\gradle.bat"
if (-not (Test-Path "$PSScriptRoot\gradlew.bat")) {
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    if (-not (Test-Path $gradlePath)) {
        $zip = Join-Path $cache "gradle-8.9-bin.zip"
        $base = "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
        Write-Host "Downloading Gradle 8.9 from the official distribution server..."
        Invoke-WebRequest -UseBasicParsing -Uri $base -OutFile $zip
        $expected = ((Invoke-WebRequest -UseBasicParsing -Uri "$base.sha256").Content).Trim()
        $actual = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected.ToLowerInvariant()) { throw "Gradle SHA256 mismatch. Remove .build-tools and retry." }
        Expand-Archive -Path $zip -DestinationPath $cache -Force
        Remove-Item $zip
    }
    $bootstrap = Join-Path $cache "wrapper-bootstrap"
    New-Item -ItemType Directory -Force -Path $bootstrap | Out-Null
    [IO.File]::WriteAllText((Join-Path $bootstrap "settings.gradle"), "rootProject.name = 'bootstrap'`n")
    [IO.File]::WriteAllText((Join-Path $bootstrap "build.gradle"), "")
    & $gradlePath -p $bootstrap wrapper --gradle-version 8.9 --distribution-type bin
    if ($LASTEXITCODE -ne 0) { throw "Gradle wrapper generation failed." }
    Copy-Item "$bootstrap\gradlew" $PSScriptRoot -Force
    Copy-Item "$bootstrap\gradlew.bat" $PSScriptRoot -Force
    Copy-Item "$bootstrap\gradle" $PSScriptRoot -Recurse -Force
}
if ($PrepareOnly) { Write-Host "Ready. Open this directory in Android Studio."; exit 0 }
Push-Location $PSScriptRoot
try {
    & "$PSScriptRoot\gradlew.bat" --no-daemon :app:assembleDebug :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw "Build or lint failed. See the error above." }
    Write-Host "APK: $PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
} finally { Pop-Location }
