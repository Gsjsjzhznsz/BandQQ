# Android 同步器构建脚本
# 用法: .\scripts\build-android.ps1 [-Task test|assembleDebug|assembleRelease] [-OutDir <输出目录>]
#
# 本机工具链约定（已在 D 盘安装，见脚本内路径）：
#   - Android SDK : D:\android-sdk
#   - JDK 17      : D:\android-build\jdk17\<版本>
#   - Gradle 8.7  : D:\android-build\gradle-8.7
#   - Gradle 缓存: D:\android-build\gradle-home
# 若你的机器已在标准位置安装（ANDROID_HOME / JAVA_HOME 已配置），脚本会优先使用环境变量。

param(
    [string]$Task = "assembleDebug",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$proj = Join-Path $root "android-sync"
$outDir = if ($OutDir) { $OutDir } else { Join-Path $root "dist" }

if (-not (Test-Path -LiteralPath $proj)) {
    Write-Error "未找到 Android 工程: $proj"
    exit 1
}

# --- 工具链发现 ---
if (-not $env:ANDROID_HOME -and (Test-Path "D:\android-sdk")) {
    $env:ANDROID_HOME = "D:\android-sdk"
    $env:ANDROID_SDK_ROOT = "D:\android-sdk"
}
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "D:\android-build\jdk17" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH }
if (-not $env:GRADLE_USER_HOME -and (Test-Path "D:\android-build")) {
    $env:GRADLE_USER_HOME = "D:\android-build\gradle-home"
}

# --- Gradle 命令 ---
$gradle = $null
if (Test-Path (Join-Path $proj "gradlew.bat")) {
    $gradle = Join-Path $proj "gradlew.bat"
} elseif (Test-Path "D:\android-build\gradle-8.7\bin\gradle.bat") {
    $gradle = "D:\android-build\gradle-8.7\bin\gradle.bat"
}
if (-not $gradle) {
    Write-Error "未找到 gradle：请用 Android Studio 打开 android-sync/ 生成 wrapper，或安装 gradle 到 D:\android-build\gradle-8.7。"
    exit 1
}

# local.properties 指向 SDK（如缺失）
if (-not (Test-Path (Join-Path $proj "local.properties")) -and $env:ANDROID_HOME) {
    $sdk = $env:ANDROID_HOME -replace '\\', '\\'
    Set-Content -LiteralPath (Join-Path $proj "local.properties") -Value "sdk.dir=$sdk" -Encoding ASCII
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "运行: $gradle $Task (工作目录: $proj)"

Push-Location $proj
try {
    if ($gradle.EndsWith("gradlew.bat")) {
        & $gradle $Task
    } else {
        & $gradle $Task --no-daemon
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle 构建失败（exit=$LASTEXITCODE）。"
        exit 1
    }
} finally {
    Pop-Location
}

# --- 复制 APK 产物到 dist/ ---
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$apkDir = Join-Path $proj "app\build\outputs\apk"
if (Test-Path -LiteralPath $apkDir) {
    Get-ChildItem -Recurse -Filter "*.apk" -Path $apkDir | ForEach-Object {
        Copy-Item -Force $_.FullName (Join-Path $outDir $_.Name)
        Write-Host "产物: $(Join-Path $outDir $_.Name)"
    }
}

Write-Host "完成。dist 目录: $outDir"
