# 手环端 rpk 打包脚本
# 用法: .\scripts\rpk-pack.ps1 [-NoSign] [-OutDir <输出目录>]
#
# 说明:
#   - 用 aiot-toolkit（AIoT-IDE 官方命令行打包工具）把 band-qq/ 打包并签名成 release rpk。
#   - 默认生成/复用证书 keystore.jks（与 Android release 一致），并提取
#     sign/debug + sign/release 下的 private.pem + certificate.pem 供 aiot-toolkit 签名。
#   - 要求:
#       * keytool（JDK）生成/读取 keystore
#       * openssl（Windows 可用 Git 自带 C:\Program Files\Git\usr\bin\openssl.exe）
#       * band-qq/node_modules 已安装 aiot-toolkit（npm install）

param(
    [switch]$NoSign,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$bandDir = Join-Path $root "band-qq"
$outDir = if ($OutDir) { $OutDir } else { Join-Path $root "dist" }

if (-not (Test-Path -LiteralPath $bandDir)) {
    Write-Error "未找到手环端目录: $bandDir"
    exit 1
}

New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$keystore = Join-Path $root "keystore.jks"
$storePass = "bandqq123"
$keyAlias = "bandqq"

# 1. 生成调试证书（如不存在）
if (-not $NoSign -and -not (Test-Path -LiteralPath $keystore)) {
    Write-Host "生成调试证书 $keystore ..."
    # 用 cmd /c 包装并将 stderr 重定向到文件，避免触发 PowerShell 5.1 的 NativeCommandError
    $errFile = Join-Path $outDir "_keytool.err"
    cmd /c "keytool -genkeypair -v -keystore `"$keystore`" -alias $keyAlias -keyalg RSA -keysize 2048 -validity 3650 -storepass $storePass -keypass $storePass -dname `"CN=BandQQ, OU=Dev, O=BandQQ, L=City, ST=State, C=CN`" 2>`"$errFile`"" | Out-Null
    Remove-Item -Force $errFile -ErrorAction SilentlyContinue
    if (-not (Test-Path -LiteralPath $keystore)) {
        Write-Error "keytool 生成证书失败，请确认已安装 JDK 并在 PATH 中。"
        exit 1
    }
}

# 2. 打包 band-qq/ 为已签名 rpk（调用 aiot-toolkit）
#    要求: band-qq/node_modules 下已安装 aiot-toolkit（npm install），且 sign/release 下
#          已有 private.pem + certificate.pem（见第 3 步）。
$target = Join-Path $outDir "bandqq.release.rpk"

# 3. 从 keystore.jks 提取签名用的 private.pem / certificate.pem（aiot-toolkit 打包时使用）
#    release 模式读取 sign/release/，build 模式读取 sign/debug/
$signDirs = @{ "release" = Join-Path $bandDir "sign\release"; "debug" = Join-Path $bandDir "sign\debug" }
$openssl = "$env:ProgramFiles\Git\usr\bin\openssl.exe"
if (Test-Path -LiteralPath $openssl) {
    foreach ($mode in @("release", "debug")) {
        $signDir = $signDirs[$mode]
        New-Item -ItemType Directory -Path $signDir -Force | Out-Null
        $p12 = Join-Path $signDir "bandqq.p12"
        $pem = Join-Path $signDir "bandqq.pem"
        # jks -> p12（keytool stderr 重定向到文件避免误报）
        $errFile = Join-Path $outDir "_keytool2_$mode.err"
        cmd /c "keytool -importkeystore -srckeystore `"$keystore`" -destkeystore `"$p12`" -srcstoretype jks -deststoretype pkcs12 -storepass $storePass -srcstorepass $storePass -noprompt 2>`"$errFile`"" | Out-Null
        Remove-Item -Force $errFile -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $p12) {
            & $openssl pkcs12 -in $p12 -nodes -out $pem -passin pass:$storePass 2>&1 | Out-Null
            $pemContent = Get-Content -Raw $pem
            $priv = [regex]::Match($pemContent, '(?s)-----BEGIN PRIVATE KEY-----.*?-----END PRIVATE KEY-----').Value
            $cert = [regex]::Match($pemContent, '(?s)-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----').Value
            Set-Content -LiteralPath (Join-Path $signDir "private.pem") -Value $priv -Encoding ASCII
            Set-Content -LiteralPath (Join-Path $signDir "certificate.pem") -Value $cert -Encoding ASCII
            Write-Host "签名文件已生成: $signDir\private.pem / certificate.pem"
            # 清理中间产物
            Remove-Item -Force $p12, $pem -ErrorAction SilentlyContinue
        }
    }
}

# 4. 运行 aiot-toolkit release 命令打包并签名
if ($NoSign) {
    Write-Host "已跳过 aiot-toolkit 打包（-NoSign）。签名材料仍在 $keystore。"
    Write-Host "如需未签名包, 请直接修改 quickapp 后运行: cd band-qq && npm run build"
} else {
    $aiot = Join-Path $bandDir "node_modules\.bin\aiot.cmd"
    if (Test-Path -LiteralPath $aiot) {
        Push-Location $bandDir
        try {
            & $aiot release
            if ($LASTEXITCODE -ne 0) {
                Write-Error "aiot release 失败。请确认已 npm install（aiot-toolkit）。"
                exit 1
            }
            # 复制产物到目标目录
            $releaseRpk = Join-Path $bandDir "dist\com.example.bandqq.release.1.0.0.rpk"
            if (Test-Path -LiteralPath $releaseRpk) {
                Copy-Item -Force $releaseRpk $target
            }
        } finally {
            Pop-Location
        }
    } else {
        Write-Error "未找到 aiot-toolkit ($aiot)。请先在 band-qq 目录运行 npm install。"
        exit 1
    }
}

if ($NoSign) {
    Write-Host "已生成（未签名，打 zip 用 npm run build）: $target"
} else {
    Write-Host "已生成签名包: $target"
    Write-Host "使用证书（keystore.jks，与 Android release 一致）签名，见 docs/signing.md。"
}

Write-Host "证书信息:"
Write-Host "  store: $keystore"
Write-Host "  storepass/keypass: $storePass"
Write-Host "  alias: $keyAlias"
Write-Host "Android release 签名参考: android-sync/keystore.example.properties"
