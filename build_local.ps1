# Local Android APK Build Script for Windows
$ErrorActionPreference = "Stop"

$workspace = "C:\Users\Gaming Krew\Documents\antigravity\optimistic-meitner"
$toolsDir = "C:\Users\Gaming Krew\Documents\android-build-tools"
$jdkDir = "$toolsDir\jdk-17"
$sdkDir = "$toolsDir\android-sdk"
$cmdlineToolsDir = "$sdkDir\cmdline-tools\latest"

New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
New-Item -ItemType Directory -Force -Path $sdkDir | Out-Null

# 1. Download & Extract JDK 17 if not present
if (-not (Test-Path "$jdkDir\bin\javac.exe")) {
    Write-Host ">>> [1/4] Downloading Portable JDK 17..."
    $jdkZip = "$toolsDir\jdk17.zip"
    curl.exe -L "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12+7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.12_7.zip" -o $jdkZip
    Write-Host ">>> Extracting JDK 17..."
    Expand-Archive -Path $jdkZip -DestinationPath $toolsDir -Force
    $extractedJdk = Get-ChildItem -Directory "$toolsDir\jdk-17*" | Select-Object -First 1
    if ($extractedJdk.FullName -ne $jdkDir) {
        Rename-Item -Path $extractedJdk.FullName -NewName "jdk-17" -Force
    }
    Remove-Item $jdkZip -Force
}
Write-Host ">>> JDK 17 is ready at $jdkDir"

# 2. Download Android Command-Line Tools if not present
if (-not (Test-Path "$cmdlineToolsDir\bin\sdkmanager.bat")) {
    Write-Host ">>> [2/4] Downloading Android Commandline Tools..."
    $cmdlineZip = "$toolsDir\cmdline-tools.zip"
    curl.exe -L "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -o $cmdlineZip
    Write-Host ">>> Extracting Commandline Tools..."
    Expand-Archive -Path $cmdlineZip -DestinationPath "$toolsDir\temp-cmdline" -Force
    New-Item -ItemType Directory -Force -Path "$sdkDir\cmdline-tools" | Out-Null
    Move-Item -Path "$toolsDir\temp-cmdline\cmdline-tools" -Destination $cmdlineToolsDir -Force
    Remove-Item "$toolsDir\temp-cmdline" -Recurse -Force
    Remove-Item $cmdlineZip -Force
}
Write-Host ">>> Android SDK Commandline Tools ready at $cmdlineToolsDir"

# 3. Configure Environment Variables
$env:JAVA_HOME = $jdkDir
$env:PATH = "$jdkDir\bin;$cmdlineToolsDir\bin;$sdkDir\platform-tools;$env:PATH"
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir

# 4. Accept Licenses & Install Required SDK packages (Platforms 35, Build-Tools, NDK, CMake)
Write-Host ">>> [3/4] Installing Android SDK platform-35, build-tools, NDK, and CMake..."
$sdkManager = "$cmdlineToolsDir\bin\sdkmanager.bat"
& echo y | & $sdkManager --licenses
& $sdkManager --install "platforms;android-35" "build-tools;35.0.0" "ndk;26.1.10909125" "cmake;3.22.1"

# 5. Build the APK locally using Gradle
Write-Host ">>> [4/4] Assembling Debug APK..."
Set-Location $workspace
& "$jdkDir\bin\java.exe" -version
if (-not (Test-Path "$workspace\gradle\wrapper\gradle-wrapper.jar")) {
    Write-Host ">>> Downloading Gradle 8.7 binary..."
    $gradleZip = "$toolsDir\gradle-8.7-bin.zip"
    curl.exe -L "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -o $gradleZip
    Expand-Archive -Path $gradleZip -DestinationPath $toolsDir -Force
    $env:PATH = "$toolsDir\gradle-8.7\bin;$env:PATH"
    Remove-Item $gradleZip -Force
    & gradle wrapper --gradle-version 8.7
}

& "$workspace\gradlew.bat" assembleDebug --stacktrace

$apkPath = "$workspace\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    Write-Host "=================================================="
    Write-Host ">>> SUCCESS! APK BUILT AT: $apkPath"
    Write-Host "=================================================="
} else {
    Write-Error "APK Build failed to produce app-debug.apk"
}
