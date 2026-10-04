# 构建脚本

## rpk-pack.ps1 — 手环端 rpk 打包签名

```powershell
.\scripts\rpk-pack.ps1 [-NoSign] [-OutDir <输出目录>]
```

- 调用 `aiot-toolkit`（AIoT-IDE 官方命令行打包工具）把 `band-qq/` 打包并**签名**为 `dist/bandqq.release.rpk`。
- 自动从 `keystore.jks` 提取 `private.pem` + `certificate.pem` 到 `band-qq/sign/{debug,release}/`，
  供 aiot-toolkit 签名（release 模式使用 `sign/release/`）。
- 使用与 Android release APK 同一证书（`keystore.jks`），满足互联签名前提。

> 依赖：`keytool`（JDK）、`openssl`、`band-qq/node_modules` 已安装 `aiot-toolkit`（`cd band-qq && npm install`）。

## build-android.ps1 — Android 同步器构建

```powershell
.\scripts\build-android.ps1 [-Task assembleDebug|assembleRelease|test] [-OutDir <输出目录>]
```

- 本机工具链（已安装到 D 盘）：Android SDK `D:\android-sdk`、JDK 17 `D:\android-build\jdk17`、
  Gradle `D:\android-build\gradle-8.13`（项目要求 8.13+，`gradle-8.7` 已无法满足）、Gradle 缓存 `D:\android-build\gradle-home`。
- 若系统已配置 `ANDROID_HOME`/`JAVA_HOME` 则优先使用环境变量。
- 产物复制到 `dist/`（默认 `assembleDebug`）。

## 产物

构建完成后 `dist/` 下应有：
- `app-debug.apk` / `app-release.apk`：Android 同步器
- `bandqq.release.rpk`：手环端（已用 `keystore.jks` 证书签名）

三端使用同一证书（`keystore.jks`，指纹 `62:c7:81:8b...a1:90`），满足互联前提。
